package it.polito.cpo.notification.api

import it.polito.cpo.notification.NotificationStore
import it.polito.cpo.notification.NotificationView
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * Customer-facing notification read APIs. `SecurityConfig` already requires an
 * authenticated CUSTOMER/OPERATIONS principal on the notification API paths; the
 * controller additionally scopes every response to the caller's own `sub`, so one
 * customer can never see another's notifications.
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val store: NotificationStore) {

    @GetMapping
    fun history(@AuthenticationPrincipal jwt: Jwt): List<NotificationView> =
        store.historyFor(jwt.subject)

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@AuthenticationPrincipal jwt: Jwt): Flux<ServerSentEvent<NotificationView>> =
        store.streamFor(jwt.subject)
            .map { ServerSentEvent.builder(it).event("notification").build() }
}
