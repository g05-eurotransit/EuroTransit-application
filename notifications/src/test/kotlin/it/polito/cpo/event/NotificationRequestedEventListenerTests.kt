package it.polito.cpo.event

import it.polito.cpo.contracts.events.NotificationType
import it.polito.cpo.notification.NotificationStore
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerSeekAware
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(partitions = 1, topics = ["notification-requested"])
@ActiveProfiles("test")
class NotificationRequestedEventListenerTests {

    @Autowired
    private lateinit var store: NotificationStore

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    @Autowired
    private lateinit var listener: NotificationRequestedEventListener

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

    /** The same envelope with the fields added for readable notifications. */
    private fun enrichedEventJson(eventId: UUID, principal: String, order: UUID) = """
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
            "message": "The selected seats are no longer available. No payment was taken.",
            "type": "ORDER_FAILED",
            "failureCode": "SEATS_UNAVAILABLE",
            "routeId": "rt-to-mi-001",
            "travelDate": "2026-08-18"
          }
        }
    """.trimIndent()

    @Test
    fun `an event produced before this contract still carries no type, code or route`() {
        // The property that makes the rollout safe in either order: Orders and Notifications are
        // deployed separately, so a payload without the new fields must still parse and record.
        val id = UUID.randomUUID()
        val order = UUID.randomUUID()
        template().send("notification-requested", order.toString(), eventJson(id, "erin", order)).get()

        awaitHistory("erin", 1)
        val recorded = store.historyFor("erin").first()
        assertNull(recorded.type)
        assertNull(recorded.failureCode)
        assertNull(recorded.route)
        assertEquals("Your order is confirmed", recorded.message)
    }

    @Test
    fun `carries the failure code, and is delivered even when Catalog cannot be reached`() {
        // No Catalog runs in this test, so route resolution fails. The notification must arrive
        // anyway: trip context is an enhancement, and losing it must never cost the customer the
        // message telling them what happened.
        val id = UUID.randomUUID()
        val order = UUID.randomUUID()
        template().send("notification-requested", order.toString(), enrichedEventJson(id, "frank", order)).get()

        awaitHistory("frank", 1)
        val recorded = store.historyFor("frank").first()
        assertEquals(NotificationType.ORDER_FAILED, recorded.type)
        assertEquals("SEATS_UNAVAILABLE", recorded.failureCode)
        assertNull(recorded.route)
        // The money claim stays exactly as Orders wrote it.
        assertEquals("The selected seats are no longer available. No payment was taken.", recorded.message)
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

    @Test
    fun `every partition assignment replays the topic from the beginning`() {
        val callback = Mockito.mock(ConsumerSeekAware.ConsumerSeekCallback::class.java)
        val partitions = mapOf(
            TopicPartition("notification-requested", 0) to 42L,
            TopicPartition("notification-requested", 1) to 7L,
        )

        listener.onPartitionsAssigned(partitions, callback)

        Mockito.verify(callback).seekToBeginning(partitions.keys)
    }
}
