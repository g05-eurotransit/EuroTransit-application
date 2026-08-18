package it.polito.cpo.event

import it.polito.cpo.notification.NotificationStore
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(partitions = 1, topics = ["notification-requested"])
@ActiveProfiles("test")
class NotificationRequestedEventListenerTests {

    @Autowired
    private lateinit var store: NotificationStore

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    private fun template(): KafkaTemplate<String, String> {
        val props = KafkaTestUtils.producerProps(embeddedKafka)
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }

    /** Mirrors the shared money-path-contracts envelope: schemaVersion + nested payload. */
    private fun eventJson(eventId: UUID, principal: String, order: UUID) = """
        {
          "eventId": "$eventId",
          "eventType": "notification-requested",
          "schemaVersion": 1,
          "occurredAt": "2026-07-06T19:14:43",
          "correlationId": "corr-$eventId",
          "orderId": "$order",
          "principalId": "$principal",
          "payload": {
            "recipientEmail": "$principal@eurotransit.local",
            "message": "Your order is confirmed"
          }
        }
    """.trimIndent()

    private fun awaitHistory(principal: String, expected: Int, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (store.historyFor(principal).size == expected) return
            Thread.sleep(200)
        }
        assertEquals(expected, store.historyFor(principal).size, "history did not converge in time")
    }

    @Test
    fun `consumes a notification-requested event into the store`() {
        val id = UUID.randomUUID()
        val order = UUID.randomUUID()
        template().send("notification-requested", order.toString(), eventJson(id, "alice", order)).get()

        awaitHistory("alice", 1)
        val recorded = store.historyFor("alice").first()
        assertEquals(order, recorded.orderId)
        assertEquals("Your order is confirmed", recorded.message)
    }

    @Test
    fun `duplicate event id is recorded only once`() {
        val id = UUID.randomUUID()
        val order = UUID.randomUUID()
        val json = eventJson(id, "carol", order)
        val t = template()
        t.send("notification-requested", order.toString(), json).get()
        t.send("notification-requested", order.toString(), json).get()

        // Give the second (duplicate) delivery time to be processed and rejected.
        Thread.sleep(2_000)
        assertEquals(1, store.historyFor("carol").size)
    }

    @Test
    fun `malformed payload does not stall the consumer`() {
        val t = template()
        t.send("notification-requested", "bad", "{ not valid json").get()

        val id = UUID.randomUUID()
        val order = UUID.randomUUID()
        t.send("notification-requested", order.toString(), eventJson(id, "dave", order)).get()

        // The good message after the poison one must still be processed → consumer is alive.
        awaitHistory("dave", 1)
        assertTrue(store.historyFor("dave").isNotEmpty())
    }
}
