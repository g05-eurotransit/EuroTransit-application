package it.polito.cpo.event

import org.springframework.stereotype.Component
import java.util.Collections
import java.util.UUID

/**
 * Bounded, dependency-free idempotency guard keyed by event id.
 *
 * Kafka may redeliver; per the resilience model, side effects must be idempotent /
 * deduplicated. Notifications owns no database (best-effort, killable by design), so an
 * in-memory bounded LRU set is the proportionate choice: on restart the guard is empty
 * and a duplicate may slip through once, which is acceptable for a non-money-path,
 * best-effort consumer. This trade-off is documented, not silent.
 */
@Component
class DuplicateEventGuard(private val capacity: Int = 10_000) {

    private val seen: MutableSet<UUID> = Collections.newSetFromMap(
        Collections.synchronizedMap(
            object : LinkedHashMap<UUID, Boolean>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<UUID, Boolean>): Boolean =
                    size > capacity
            },
        ),
    )

    /** Returns true if this event id was not seen before (and records it); false if duplicate. */
    fun markIfNew(eventId: UUID): Boolean = seen.add(eventId)
}
