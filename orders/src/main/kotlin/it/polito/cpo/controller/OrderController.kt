package it.polito.cpo.controller

import it.polito.cpo.client.InventoryReadClient
import it.polito.cpo.client.SeatAvailabilityOutcome
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.controller.dtos.OrderHistoryCursor
import it.polito.cpo.controller.dtos.OrderPage
import it.polito.cpo.controller.dtos.OrderView
import it.polito.cpo.controller.dtos.SeatAvailabilityView
import it.polito.cpo.controller.dtos.SeatView
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.CustomerFailureCode
import it.polito.cpo.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
    private val checkoutOrchestrator: CheckoutOrchestrator,
    private val inventoryReadClient: InventoryReadClient,
) {

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }

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

    /**
     * The authenticated customer's own order history, newest first.
     *
     * Unlike [getOrder], there is deliberately **no `operations` widening**. That role exists so a
     * support agent can look up one order they were given the id of; letting it drop the principal
     * filter here would return every customer's orders in one call. The filter is applied in SQL
     * (`OrderRepository.findHistoryFirstPage`), not in this method, so it cannot be bypassed by a
     * later edit to the controller.
     *
     * `status` is optional: the SPA renders "my tickets" as `?status=CONFIRMED`, while the
     * unfiltered list stays reachable so a customer can still find a failed attempt.
     */
    @GetMapping
    suspend fun listOrders(
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "$DEFAULT_PAGE_SIZE") limit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): OrderPage {
        val userId = jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")

        // Clamped rather than rejected: an out-of-range page size is not worth failing a read over,
        // but an unbounded one lets a caller pull the whole history in a single query.
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)

        // A malformed cursor is a 400, not a silent restart from page one — that would make a
        // paging client loop over the first page forever instead of surfacing the bug.
        val decoded = cursor?.let {
            OrderHistoryCursor.decode(it)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed cursor")
        }

        // One row beyond the page tells us whether a further page exists, without a count query.
        val rows = orderService.findHistoryPage(userId, status, decoded, pageSize)
        val page = rows.take(pageSize)
        val next = if (rows.size > pageSize) {
            page.lastOrNull()?.let { OrderHistoryCursor(it.createdAt, it.id).encode() }
        } else {
            null
        }

        return OrderPage(orders = page.map { toView(it) }, nextCursor = next)
    }

    /**
     * The seat map for a route on a travel date, so a customer can pick a seat instead of finding
     * out it was taken only after paying.
     *
     * Orders fronts this so Inventory stays internal: the browser reaches the `/api/orders` prefix
     * through Traefik, and Orders calls `/api/inventory/availability` with a service credential. The
     * alternative — routing it through Catalog — would serve an authenticated step of the checkout
     * (the picker lives behind `ProtectedRoute` at `/checkout/seats`) from a public anonymous route.
     *
     * The isolation cost of hosting browsing traffic here is paid explicitly in
     * `ResilienceConfiguration`: a separate `inventory-read` breaker and bulkhead, and no retry.
     *
     * **The answer is advisory.** It is a read with no lock: a seat reported free can lose the hold
     * race a moment later, and `SEATS_UNAVAILABLE` at checkout remains the authoritative answer.
     *
     * Declared before [getOrder] for readability only — routing does not depend on the order.
     * `seat-availability` is a literal segment and Spring's `PathPattern` comparator sorts literals
     * ahead of `{orderId}`, so this never reaches the by-id handler; `OrderControllerOwnershipTest`
     * pins that, because the failure mode would otherwise be a confusing 400 on a UUID conversion.
     */
    @GetMapping("/seat-availability")
    suspend fun seatAvailability(
        @RequestParam routeId: String,
        @RequestParam travelDate: LocalDate,
    ): SeatAvailabilityView {
        // No ownership check and no principal use: there is no owned resource here. Authentication
        // is still required — the security chain covers this path with the customer/operations rule
        // — because this is a step of the checkout, not public browsing.
        val outcome = inventoryReadClient.getSeatAvailability(routeId, travelDate)

        val seats = when (outcome) {
            is SeatAvailabilityOutcome.Found -> outcome.seats
            // Inventory conflates "unknown route" with "sold out" behind one 404 (raised on #68).
            // Until that is split, 404 is the honest answer: reporting an empty seat map for a route
            // that does not exist would look like a sold-out train.
            SeatAvailabilityOutcome.NotFound ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "No seats for this route and date")
        }

        return SeatAvailabilityView(
            routeId = routeId,
            travelDate = travelDate,
            seats = seats.map { SeatView(seatId = it.seatId, available = it.available) },
        )
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

    private fun toView(order: it.polito.cpo.model.Order): OrderView {
        // Null for every non-FAILED order: the column is written only by the terminal FAILED commit.
        val failure = CustomerFailureCode.parse(order.failureCode)
        return OrderView(
            orderId = order.id,
            userId = order.userId,
            status = order.status,
            routeId = order.routeId,
            seats = order.seats.split(","),
            travelDate = order.travelDate,
            totalAmount = order.totalAmount,
            createdAt = order.createdAt,
            reservationExpiresAt = order.reservationExpiresAt,
            failureCode = failure?.name,
            // Derived, not stored: display text is presentation, and storing it would freeze today's
            // wording into every historical row.
            failureMessage = failure?.customerMessage,
        )
    }
}
