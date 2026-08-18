package it.polito.cpo.payments.config

import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
class KafkaConfiguration {

    // Values are pre-serialized to JSON strings by PaymentService using the app's Jackson 3
    // ObjectMapper (which handles java.time). This keeps producer and consumer symmetric (both
    // String + manual parse, as in orders/notifications) and avoids the Kafka JsonSerializer's
    // Jackson 2 java.time gap. Mirrors orders' KafkaConfiguration.
    @Bean
    fun producerFactory(properties: KafkaProperties): ProducerFactory<String, String> {
        val configProps = properties.buildProducerProperties()
        return DefaultKafkaProducerFactory(configProps, StringSerializer(), StringSerializer())
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> {
        return KafkaTemplate(producerFactory)
    }
}
