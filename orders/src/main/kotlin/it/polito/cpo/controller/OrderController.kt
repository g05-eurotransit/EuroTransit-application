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
import it.polito.cpo.observability.ApiException
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.CustomerFailureCode
import it.polito.cpo.service.OrderService
import it.polito.cpo.service.RefundOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
    private val checkoutOrchestrator: CheckoutOrchestrator,
    private val refundOrchestrator: RefundOrchestrator,
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
        // Issue #98, first half. `POST /api/orders` rejects a past travel date with this same code,
        // and this endpoint forwarded one to Inventory unchallenged — so a customer could be shown a
        // seat map for a train that left last week and only be told at checkout. The same answer
        // from both endpoints is the point: a client that handles one handles the other.
        //
        // Second line of defence, not the fix: which seats exist for a past date is Inventory's own
        // truth and is #97. The remaining half of #98 — a *same-day* run that has already departed —
        // needs the departure instant per route, which the Catalog client added in this change now
        // makes reachable.
        if (travelDate.isBefore(LocalDate.now())) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "TRAVEL_DATE_IN_THE_PAST",
                "Travel date $travelDate is in the past",
            )
        }

        // No ownership check and no principal use: there is no owned resource here. Authentication
        // is still required — the security chain covers this path with the customer/operations rule
        // — because this is a step of the checkout, not public browsing.
        val outcome = try {
            inventoryReadClient.getSeatAvailability(routeId, travelDate)
        } catch (cancellation: CancellationException) {
            // A disconnected client is not an Inventory failure; swallowing it into a 503 would
            // report a dependency problem that never happened.
            throw cancellation
        } catch (failure: Exception) {
            // The client classifies only definitive refusals and throws everything else -- a timeout,
            // an open `inventory-read` breaker, a 5xx, a refused connection -- so that nobody can
            // mistake "Inventory did not answer" for "no seats are free". That is correct and stays.
            // What was missing is this mapping: with none, all of it reached GlobalErrorHandler's
            // `Throwable` handler and came back as `500 INTERNAL_ERROR`. The likeliest trigger is not
            // a cold start but a rollout: while the `inventory-read` breaker is open, every seat map
            // request answered `500` for the ten seconds it stayed that way (#104).
            //
            // `503` and not `504`: ApiException's status set is frozen, and `503` is the member
            // defined as "dependency unavailable or circuit open". No new counter is added, because
            // `http_server_requests` already carries the status per URI and GlobalErrorHandler logs a
            // 5xx ApiException together with its cause.
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SEAT_MAP_UNAVAILABLE",
                "The seat map could not be loaded right now. Please try again.",
                cause = failure,
            )
        }

        // The `when` stays outside the try above on purpose. The `404` below is a definitive answer
        // from Inventory, not a failure to reach it, and widening the try to cover it would collapse
        // the two -- which is the realistic way to get this change wrong. Structure prevents it here,
        // rather than care having to.
        val seats = when (outcome) {
            is SeatAvailabilityOutcome.Found -> outcome.seats
            // Inventory answers one 404 for two different cases (#96): an unknown route, and a
            // known route with no seats provisioned for that travel date. Until those are split,
            // re-raising it is the honest answer — reporting an empty seat map for a route that does
            // not exist would read as a departed or unbookable train.
            //
            // A sold-out run is NOT one of those cases and never reaches here: its seat rows exist
            // as HELD/SOLD, so Inventory returns them and every seat is simply unavailable.
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

    /**
     * Requests a post-sale refund (ADR-015). Same ownership rule as [cancel]: owner or `operations`,
     * 404 rather than 403 for anyone else, so the API never reveals that another customer's order
     * exists.
     *
     * Returns `202 Accepted`, not `200`: unlike [cancel] — which commits a terminal state
     * synchronously — this commits only the *acceptance* of a refund whose money and seat reversal
     * are driven asynchronously by [RefundOrchestrator]'s own consumer. The body still reflects that:
     * the order is `REFUNDING`, not yet `REFUNDED`.
     *
     * Requires `Idempotency-Key`, unlike [cancel]: ADR-015's replay rule — a key accepted before the
     * refund cutoff must return its original result even after the cutoff, while a genuinely new
     * request after it is refused — needs a stored record to tell the two apart, which `cancel` has
     * no equivalent of.
     */
    @PostMapping("/{orderId}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun refund(
        @PathVariable orderId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): OrderView {
        val userId = jwt.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim")
        val roles = jwt.getClaim<Map<String, Any>>("realm_access")?.get("roles") as? List<*>
        val isStaff = roles?.contains("operations") == true
        return refundOrchestrator.refund(orderId, idempotencyKey, userId, isStaff)
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
            currency = order.currency,
            createdAt = order.createdAt,
            reservationExpiresAt = order.reservationExpiresAt,
            failureCode = failure?.name,
            // Derived, not stored: display text is presentation, and storing it would freeze today's
            // wording into every historical row.
            failureMessage = failure?.customerMessage,
            offerId = order.offerId,
            refundable = order.refundable,
            refundCutoffMinutesBeforeDeparture = order.refundCutoffMinutesBeforeDeparture,
            departureAt = order.departureAt,
        )
    }
}
