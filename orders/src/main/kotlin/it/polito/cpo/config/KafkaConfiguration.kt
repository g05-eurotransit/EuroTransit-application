package it.polito.cpo.config

import it.polito.cpo.event.PoisonEventException
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

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
     */
    @Bean
    fun kafkaErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
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
