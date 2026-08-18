package it.polito.cpo.event

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateEventGuardTests {

    @Test
    fun `first sighting is new, second is duplicate`() {
        val guard = DuplicateEventGuard()
        val id = UUID.randomUUID()
        assertTrue(guard.markIfNew(id), "first call should be new")
        assertFalse(guard.markIfNew(id), "second call should be a duplicate")
    }

    @Test
    fun `distinct ids are all new`() {
        val guard = DuplicateEventGuard()
        repeat(100) { assertTrue(guard.markIfNew(UUID.randomUUID())) }
    }

    @Test
    fun `capacity eviction drops the oldest tracked id`() {
        val guard = DuplicateEventGuard(capacity = 2)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        assertTrue(guard.markIfNew(a))
        assertTrue(guard.markIfNew(b))
        assertTrue(guard.markIfNew(c)) // evicts a (eldest)
        assertTrue(guard.markIfNew(a), "a was evicted, so it reads as new again")
        assertFalse(guard.markIfNew(c), "c is still tracked")
    }
}
