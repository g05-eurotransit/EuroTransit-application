package it.polito.cpo.notification

import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationStoreTests {

    private fun view(principal: String) = NotificationView(
        id = UUID.randomUUID(),
        principalId = principal,
        orderId = UUID.randomUUID(),
        message = "msg",
        recipientEmail = "$principal@eurotransit.local",
        occurredAt = LocalDateTime.now(),
        receivedAt = LocalDateTime.now(),
    )

    @Test
    fun `history is bounded to the most recent entries per principal`() {
        val store = NotificationStore(maxPerPrincipal = 50)
        repeat(60) { store.record(view("alice")) }
        assertEquals(50, store.historyFor("alice").size)
    }

    @Test
    fun `history is isolated per principal`() {
        val store = NotificationStore()
        store.record(view("alice"))
        store.record(view("bob"))
        assertEquals(1, store.historyFor("alice").size)
        assertTrue(store.historyFor("alice").all { it.principalId == "alice" })
        assertTrue(store.historyFor("bob").none { it.principalId == "alice" })
    }

    @Test
    fun `most recent notification is first`() {
        val store = NotificationStore()
        val first = view("alice")
        val second = view("alice")
        store.record(first)
        store.record(second)
        assertEquals(second.id, store.historyFor("alice").first().id)
    }

    @Test
    fun `stream only delivers the subscribing principal's events`() {
        val store = NotificationStore()
        val aliceEvent = view("alice")

        StepVerifier.create(store.streamFor("alice"))
            .then {
                store.record(view("bob"))   // must be filtered out
                store.record(aliceEvent)    // must be delivered
            }
            .expectNextMatches { it.id == aliceEvent.id && it.principalId == "alice" }
            .thenCancel()
            .verify()
    }
}
