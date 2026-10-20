package it.polito.cpo.notification

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory notification read model + live fan-out.
 *
 * History: a per-principal map whose value list is REPLACED (not mutated in place) on
 * each write via [ConcurrentHashMap.compute] — the immutable-list swap is atomic and
 * avoids a data race between the Kafka consumer thread writing and WebFlux request
 * threads reading. Bounded to [maxPerPrincipal] most-recent entries.
 *
 * The map is a projection, not the record: the listener rebuilds it on every start by
 * replaying `notification-requested` from the beginning of the topic (#131). That makes two
 * properties load-bearing here. A record is idempotent by id, because a replay or a
 * rebalance redelivers what is already held. And the list is ordered by [NotificationView.occurredAt],
 * not by arrival, because a replay reads the partitions interleaved and a customer's events
 * are spread across partitions by order id.
 *
 * Stream: one multicast [Sinks.Many]; each subscriber filters by its own principal, so a
 * user only ever sees their own notifications. Durability is bounded by the topic's
 * retention: what Kafka has deleted cannot be replayed.
 */
@Component
class NotificationStore(private val maxPerPrincipal: Int = 50) {

    private val history = ConcurrentHashMap<String, List<NotificationView>>()
    private val sink = Sinks.many().multicast().onBackpressureBuffer<NotificationView>(1024, false)

    /**
     * Adds [view] to its principal's history, and pushes it to live subscribers only when [live].
     * A replayed notification from before this process started belongs in the history a client
     * fetches, not in the stream: pushed live, days-old messages would arrive as if they were new.
     */
    fun record(view: NotificationView, live: Boolean = true) {
        var added = false
        history.compute(view.principalId) { _, existing ->
            val current = existing ?: emptyList()
            if (current.any { it.id == view.id }) {
                current
            } else {
                added = true
                // Stable sort: on equal timestamps the newer arrival stays first.
                (listOf(view) + current).sortedByDescending { it.occurredAt }.take(maxPerPrincipal)
            }
        }
        // Best-effort: if there are no subscribers or the buffer is full, drop silently.
        if (added && live) sink.tryEmitNext(view)
    }

    fun historyFor(principalId: String): List<NotificationView> =
        history[principalId] ?: emptyList()

    fun streamFor(principalId: String): Flux<NotificationView> =
        sink.asFlux().filter { it.principalId == principalId }
}
