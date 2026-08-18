package it.polito.cpo.controller

import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.controller.dtos.OrderView
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
    private val checkoutOrchestrator: CheckoutOrchestrator
) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun checkout(
        @RequestBody request: CheckoutRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt
    ): CheckoutResponse {
        val userId = jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")
        // email is optional delivery metadata (never the ownership key — that is `sub`).
        val email = jwt.getClaimAsString("email")
        return checkoutOrchestrator.checkout(request, idempotencyKey, userId, email)
    }

    @GetMapping("/{orderId}")
    suspend fun getOrder(
        @PathVariable orderId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): OrderView {
        val userId = jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")
        val order = orderService.getOrderById(orderId)

        // Ownership: the owner, or back-office staff (the `operations` realm role). A customer asking
        // for another customer's order gets 404 — same as a genuinely missing order — so the API never
        // reveals that another customer's order exists (api-design.md §Security Boundary).
        val roles = jwt.getClaim<Map<String, Any>>("realm_access")?.get("roles") as? List<*>
        val isStaff = roles?.contains("operations") == true
        if (order == null || (order.userId != userId && !isStaff)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        }

        return toView(order)
    }

    @PostMapping("/{orderId}/cancel")
    suspend fun cancel(
        @PathVariable orderId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): OrderView {
        val userId = jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")
        val order = orderService.getOrderById(orderId)

        // Same ownership rule as getOrder: owner or back-office `operations`. A non-owner (or a
        // missing order) gets 404 so the API never reveals another customer's order exists.
        val roles = jwt.getClaim<Map<String, Any>>("realm_access")?.get("roles") as? List<*>
        val isStaff = roles?.contains("operations") == true
        if (order == null || (order.userId != userId && !isStaff)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        }

        // Cancellable only while unpaid; the orchestrator throws ApiException(409) otherwise.
        return toView(checkoutOrchestrator.cancel(orderId))
    }

    private fun toView(order: it.polito.cpo.model.Order): OrderView =
        OrderView(
            orderId = order.id,
            userId = order.userId,
            status = order.status,
            routeId = order.routeId,
            seats = order.seats.split(","),
            totalAmount = order.totalAmount,
            createdAt = order.createdAt
        )
}
