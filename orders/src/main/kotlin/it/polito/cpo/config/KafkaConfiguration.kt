package it.polito.cpo.config

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.event.PoisonEventException
import it.polito.cpo.service.FailureClassifier
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ErrorHandlingUtils
import org.springframework.kafka.listener.RetryListener
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.BackOffExecution
import java.util.concurrent.ThreadLocalRandom

/**
 * Wraps the dead-letter recoverer so the pod that gives up on a delivery counts it, once the dead
 * letter is actually published.
 *
 * Consumer-aware on purpose: the error handler passes the consumer to a recoverer that accepts one,
 * and [DeadLetterPublishingRecoverer] uses it (partition checks, consumer-group header). A plain
 * `ConsumerRecordRecoverer` wrapper would silently drop it.
 *
 * Counted after `accept` returns because `accept` throws when the publish fails
 * (`failIfSendResultIsError` defaults to true): the record is then redelivered and recovered again,
 * and counting first would count it twice.
 */
internal fun countingRecoverer(
    deadLetters: ConsumerAwareRecordRecoverer,
    meterRegistry: MeterRegistry,
) = ConsumerAwareRecordRecoverer { record, consumer, exception ->
    deadLetters.accept(record, consumer, exception)
    countExhausted(meterRegistry, record.topic(), exception)
}

/**
 * `orders.events.exhausted{topic,failure_class}`: a delivery this pod gave up on and dead-lettered.
 *
 * It exists beside `orders.events.dlt` because the two count different pods. `orders.events.dlt` is
 * incremented by the dead-letter *consumers*, which run in their own group (`orders-dlt`), so it
 * lands on whichever pod reads the dead letter — fine for the poison and unknown-order alerts, wrong
 * for a canary gate filtered by pod hash, which needs the pod whose code failed (step 5, F4). This one
 * is incremented where the delivery was abandoned.
 *
 * `failure_class` uses the same closed set as the incident, classified from the same cause the
 * recoverer writes to `DLT_EXCEPTION_CAUSE_FQCN` ([ErrorHandlingUtils.findRootCause] unwraps only the
 * container's wrappers). Two source topics times that set keeps the series count small.
 */
internal fun countExhausted(meterRegistry: MeterRegistry, topic: String, exception: Exception?) {
    val failureClass = FailureClassifier.forDeadLetter(
        exception?.javaClass?.name,
        ErrorHandlingUtils.findRootCause(exception)?.javaClass?.name,
    )
    exhaustedCounter(meterRegistry, topic, failureClass).increment()
}

/**
 * Unbounded exponential back-off for the dead-letter listeners: [initialIntervalMs], doubling, capped
 * at [maxIntervalMs], with no limit on attempts or elapsed time. The cap stays far below
 * `max.poll.interval.ms` (5 minutes by default), so the waiting itself never triggers a rebalance. A
 * single listener call blocked on an unreachable database still counts toward that limit; a
 * rebalance then costs one duplicate attempt, not the record.
 */
internal fun dltRetryBackOff(initialIntervalMs: Long, maxIntervalMs: Long) =
    ExponentialBackOff(initialIntervalMs, 2.0).apply { maxInterval = maxIntervalMs }

/**
 * The dead-letter listeners' error handler: [backOff], with **every** exception retryable, and each
 * failed attempt counted.
 *
 * The default classification treats a handful of types as fatal (`ClassCastException`, message and
 * header conversion failures, and others): one attempt, then the logging recoverer, then the offset
 * is committed — the very drop the unbounded back-off exists to prevent. Clearing it makes "never
 * committed until recorded" hold for those too.
 */
internal fun deadLetterErrorHandler(backOff: BackOff, meterRegistry: MeterRegistry) =
    DefaultErrorHandler(backOff).apply {
        setClassifications(emptyMap(), true)
        setRetryListeners(countFailedDeadLetterRecording(meterRegistry))
    }

/** The dead-letter topics, read by the listeners on `deadLetterListenerContainerFactory`. */
internal val DEAD_LETTER_TOPICS = listOf("order-placed-dlt", "refund-requested-dlt")

/** Counts every failed attempt to turn a dead letter into an incident, per DLT topic. */
internal fun countFailedDeadLetterRecording(meterRegistry: MeterRegistry) = RetryListener { record, _, _ ->
    deadLetterRecordFailed(meterRegistry, record.topic()).increment()
}

/** Registers `orders.events.dlt{result="record_failed"}` at zero, so an alert on `increase()` sees the first. */
internal fun registerDeadLetterRecordFailedSeries(meterRegistry: MeterRegistry) {
    DEAD_LETTER_TOPICS.forEach { deadLetterRecordFailed(meterRegistry, it) }
}

private fun deadLetterRecordFailed(meterRegistry: MeterRegistry, topic: String) =
    meterRegistry.counter("orders.events.dlt", "topic", topic, "result", "record_failed")

/** The topics whose listeners run on the default container factory, i.e. through [countingRecoverer]. */
internal val EXHAUSTABLE_TOPICS = listOf("order-placed", "refund-requested")

/**
 * Registers every `orders.events.exhausted` series at zero when the error handler is built.
 *
 * Without this a series is created by its first increment, so Prometheus first scrapes it at 1 and
 * `increase()` — which needs an earlier sample — reports 0. On a fresh canary pod that hides exactly
 * the first dead letter, which is usually the only one in a gate's window, and an `== 0` gate passes
 * on the failure it exists to catch. Two topics times [FailureClassifier.DEAD_LETTER_LABELS].
 */
internal fun registerExhaustedSeries(meterRegistry: MeterRegistry) {
    EXHAUSTABLE_TOPICS.forEach { topic ->
        FailureClassifier.DEAD_LETTER_LABELS.forEach { label -> exhaustedCounter(meterRegistry, topic, label) }
    }
}

private fun exhaustedCounter(meterRegistry: MeterRegistry, topic: String, failureClass: String) =
    meterRegistry.counter("orders.events.exhausted", "topic", topic, "failure_class", failureClass)

@Configuration
class KafkaConfiguration {

    // String producer/template. Orders no longer produces money-path events directly — they are
    // written to the transactional outbox and relayed to Kafka by Debezium. This String template is
    // retained for the consumer-side dead-letter recoverer below (and mirrors the String + manual
    // parse convention the consumers use, avoiding the Kafka JsonSerializer's Jackson 2 java.time gap).
    @Bean
    fun producerFactory(properties: KafkaProperties): ProducerFactory<String, String> {
        val configProps = properties.buildProducerProperties()
        return DefaultKafkaProducerFactory(configProps, StringSerializer(), StringSerializer())
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> {
        val template = KafkaTemplate(producerFactory)
        // Producer observation, so a record sent through this template carries `traceparent`. Since the
        // outbox, that is only the dead-letter path: business events are written to `outbox_events`
        // and relayed by Debezium, and their trace context travels in the row instead (OrderOutbox).
        template.setObservationEnabled(true)
        return template
    }

    /**
     * Persistently failing records: after a bounded set of retries, publish to the dead-letter topic
     * instead of redelivering forever (ADR-011 follow-up). Spring Boot wires this into the default
     * listener container factory.
     *
     * The destination is `<topic>-dlt`, i.e. `order-placed-dlt`: this version of
     * [DeadLetterPublishingRecoverer] appends `-dlt` rather than the `.DLT` spelling the design docs
     * originally used.
     *
     * A [PoisonEventException] skips the retries. A payload that does not parse will never parse, so
     * three redeliveries a second apart only delay the record reaching an operator.
     *
     * Every dead letter published is counted here, on the pod that gave up ([countExhausted]).
     */
    @Bean
    fun kafkaErrorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        meterRegistry: MeterRegistry,
    ): DefaultErrorHandler {
        registerExhaustedSeries(meterRegistry)
        val recoverer = countingRecoverer(DeadLetterPublishingRecoverer(kafkaTemplate), meterRegistry)
        return DefaultErrorHandler(recoverer, BackOff {
            object : BackOffExecution {
                private var retries = 0
                override fun nextBackOff(): Long {
                    if (retries >= 3) return BackOffExecution.STOP
                    val base = 1000L shl retries++
                    return ThreadLocalRandom.current().nextLong(base / 2, base + base / 2 + 1)
                }
            }
        }).apply {
            addNotRetryableExceptions(PoisonEventException::class.java)
        }
    }

    /**
     * Dedicated container factory for the dead-letter listeners, with its own consumer group and
     * deliberately **no** [DeadLetterPublishingRecoverer].
     *
     * Without this the DLT listeners would be served by the default factory, which does have one. A
     * transient database failure while recording a `DELIVERY_EXHAUSTED` incident would then publish
     * to `order-placed-dlt-dlt` — a topic nobody consumes — and the record would be lost precisely
     * when it matters most.
     *
     * **It retries until the incident is written, and never gives up.** An error handler with no
     * recoverer does not leave the record for the next poll once its back-off runs out: the container
     * substitutes a recoverer that only logs, the record counts as recovered, and its offset is
     * committed. With the bounded back-off this factory used to have, a database unavailable for more
     * than about two seconds lost the incident — the order stuck, with nothing but an error log. So the
     * back-off here is exponential and unbounded ([dltRetryBackOff]) and every exception is retryable
     * ([deadLetterErrorHandler]): a failure that ends delays the incident, it cannot drop it.
     *
     * Retrying is safe: recording upserts on the one-open-incident-per-(order, kind) index, and every
     * other branch of the listeners (unknown order, terminal order, stale close) is idempotent too.
     * The price is head-of-line blocking, and it is wider than one partition: each listener has one
     * consumer thread per pod, which owns all of its DLT topic's partitions there, so while one dead
     * letter keeps failing, that whole DLT stream waits on that pod (the other DLT topic has its own
     * container and is unaffected). With the database down nothing could be recorded anyway; if the
     * failure is permanent instead — a bug, or data that cannot be stored — the stream stays stuck
     * until someone acts, which is what the `orders.events.dlt{result="record_failed"}` count of every
     * failed attempt is for.
     *
     * The separate group id also keeps dead-letter consumption from sharing offsets with the
     * checkout pipeline.
     */
    @Bean
    fun deadLetterListenerContainerFactory(
        properties: KafkaProperties,
        meterRegistry: MeterRegistry,
        @Value("\${orders.kafka.dlt-retry.initial-interval-ms:1000}") initialIntervalMs: Long,
        @Value("\${orders.kafka.dlt-retry.max-interval-ms:30000}") maxIntervalMs: Long,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val props = properties.buildConsumerProperties().toMutableMap()
        props[ConsumerConfig.GROUP_ID_CONFIG] = "orders-dlt"
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer()))
        registerDeadLetterRecordFailedSeries(meterRegistry)
        factory.setCommonErrorHandler(
            deadLetterErrorHandler(dltRetryBackOff(initialIntervalMs, maxIntervalMs), meterRegistry),
        )
        factory.containerProperties.isObservationEnabled = true
        // The Boot-built factory's shutdown rule (`spring.kafka.listener.immediate-stop`) does not reach
        // a factory built by hand; read the same property so one switch governs both.
        factory.containerProperties.isStopImmediate = properties.listener.isImmediateStop
        return factory
    }
}
