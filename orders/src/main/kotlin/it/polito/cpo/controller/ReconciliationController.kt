package it.polito.cpo.controller

import it.polito.cpo.controller.dtos.AbandonResponse
import it.polito.cpo.controller.dtos.RedriveResponse
import it.polito.cpo.controller.dtos.ResolveReconciliationRequest
import it.polito.cpo.controller.dtos.ResolveReconciliationResponse
import it.polito.cpo.controller.dtos.SettleResponse
import it.polito.cpo.model.OpenReconciliationIncident
import it.polito.cpo.service.ReconciliationOperationsService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Internal operator API over reconciliation incidents (api-design.md, Orders).
 *
 * Authorization is enforced by the security chain and requires the operations role — deliberately not
 * the service role: an internal credential drives automated calls and must not be able to redrive a
 * customer's checkout. There is no ownership check because these are not customer-facing operations.
 *
 * `X-Correlation-Id` is not a parameter here: the shared `CorrelationIdWebFilter` accepts or generates
 * it into the Reactor context, and the service reads it from there.
 */
@RestController
@RequestMapping("/api/orders")
class ReconciliationController(
    private val operations: ReconciliationOperationsService,
) {

    /**
     * The open incidents, most urgent first. A literal segment, so it wins over `GET /api/orders/{orderId}`;
     * the security chain gives it its own operations-only rule ahead of the order-read one.
     */
    @GetMapping("/reconciliation")
    suspend fun listOpen(
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<OpenReconciliationIncident> = operations.listOpen(limit)

    @PostMapping("/{orderId}/reconciliation/redrive")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun redrive(
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): RedriveResponse = operations.redrive(orderId, idempotencyKey, operatorId(jwt))

    /** Gives up a `PAYMENT_PENDING` order past its seat hold; compensation settles it (#140). */
    @PostMapping("/{orderId}/reconciliation/abandon")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun abandon(
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): AbandonResponse = operations.abandon(orderId, idempotencyKey, operatorId(jwt))

    /** Settles an abandoned checkout's unknown payment outcome with provider evidence (#140). */
    @PostMapping("/{orderId}/reconciliation/{recordId}/settle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun settle(
        @PathVariable orderId: UUID,
        @PathVariable recordId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: ResolveReconciliationRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): SettleResponse = operations.settle(orderId, recordId, idempotencyKey, request, operatorId(jwt))

    @PostMapping("/{orderId}/reconciliation/{recordId}/resolve")
    suspend fun resolve(
        @PathVariable orderId: UUID,
        @PathVariable recordId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: ResolveReconciliationRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResolveReconciliationResponse =
        operations.resolve(orderId, recordId, idempotencyKey, request, operatorId(jwt))

    /** The operator identity is recorded on the incident, so a missing subject is not acceptable. */
    private fun operatorId(jwt: Jwt): String =
        jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")
}
