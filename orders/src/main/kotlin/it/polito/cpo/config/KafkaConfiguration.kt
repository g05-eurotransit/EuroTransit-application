package it.polito.cpo.config

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.event.PoisonEventException
import it.polito.cpo.service.FailureClassifier
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
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
import org.springframework.util.backoff.FixedBackOff

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
    meterRegistry.counter("orders.events.exhausted", "topic", topic, "failure_class", failureClass).increment()
}

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
        val recoverer = countingRecoverer(DeadLetterPublishingRecoverer(kafkaTemplate), meterRegistry)
        return DefaultErrorHandler(recoverer, FixedBackOff(1_000L, 3L)).apply {
            addNotRetryableExceptions(PoisonEventException::class.java)
        }
    }

    /**
     * Dedicated container factory for the dead-letter listener, with its own consumer group and
     * deliberately **no** [DeadLetterPublishingRecoverer].
     *
     * Without this the DLT listener would be served by the default factory, which does have one. A
     * transient database failure while recording a `DELIVERY_EXHAUSTED` incident would then publish
     * to `order-placed-dlt-dlt` — a topic nobody consumes — and the record would be lost precisely
     * when it matters most. Here a failure retries a couple of times and is then logged, leaving the
     * offset uncommitted so the next poll sees it again.
     *
     * The separate group id also keeps dead-letter consumption from sharing offsets with the
     * checkout pipeline.
     */
    @Bean
    fun deadLetterListenerContainerFactory(
        properties: KafkaProperties,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val props = properties.buildConsumerProperties().toMutableMap()
        props[ConsumerConfig.GROUP_ID_CONFIG] = "orders-dlt"
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer()))
        factory.setCommonErrorHandler(DefaultErrorHandler(FixedBackOff(1_000L, 2L)))
        factory.containerProperties.isObservationEnabled = true
        return factory
    }
}
