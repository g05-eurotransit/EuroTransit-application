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
 * Stream: one multicast [Sinks.Many]; each subscriber filters by its own principal, so a
 * user only ever sees their own notifications. Best-effort by design (no persistence).
 */
@Component
class NotificationStore(private val maxPerPrincipal: Int = 50) {

    private val history = ConcurrentHashMap<String, List<NotificationView>>()
    private val sink = Sinks.many().multicast().onBackpressureBuffer<NotificationView>(1024, false)

    fun record(view: NotificationView) {
        history.compute(view.principalId) { _, existing ->
            (listOf(view) + (existing ?: emptyList())).take(maxPerPrincipal)
        }
        // Best-effort: if there are no subscribers or the buffer is full, drop silently.
        sink.tryEmitNext(view)
    }

    fun historyFor(principalId: String): List<NotificationView> =
        history[principalId] ?: emptyList()

    fun streamFor(principalId: String): Flux<NotificationView> =
        sink.asFlux().filter { it.principalId == principalId }
}
