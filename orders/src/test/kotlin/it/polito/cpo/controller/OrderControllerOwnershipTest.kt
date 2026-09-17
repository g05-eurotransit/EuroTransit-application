package it.polito.cpo.controller

import it.polito.cpo.client.CatalogClient
import it.polito.cpo.client.InventoryReadClient
import it.polito.cpo.client.OfferOutcome
import it.polito.cpo.client.RouteOutcome
import it.polito.cpo.client.RouteSchedule
import it.polito.cpo.client.SeatAvailability
import it.polito.cpo.client.SeatAvailabilityOutcome
import it.polito.cpo.controller.dtos.OrderHistoryCursor
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.service.CustomerFailureCode
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.security.SecurityConfig
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.OrderService
import it.polito.cpo.service.RefundOrchestrator
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import org.mockito.Mockito.mock
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Verifies the ownership rule of GET /api/orders/{orderId} (api-design.md §Security Boundary):
 * a customer sees only their own order; another customer's order is 404 (not 403) so existence
 * is not revealed; back-office `operations` may read any order.
 */
@WebFluxTest(controllers = [OrderController::class])
@Import(SecurityConfig::class, OrderControllerOwnershipTest.Cfg::class)
class OrderControllerOwnershipTest @Autowired constructor(
    private val webTestClient: WebTestClient,
    private val orderService: FakeOrderService,
    private val inventoryReadClient: FakeInventoryReadClient,
    private val catalogClient: FakeCatalogClient,
) {
    private val orderId = UUID.randomUUID()
    private val travelDate: LocalDate = LocalDate.now().plusDays(3)

    /**
     * `@WebFluxTest` caches one context for the whole class, so these spies carry whatever the
     * previous test left on them. Resetting here is what lets each test's assertions be about its own
     * request rather than about JUnit's method ordering.
     */
    @BeforeEach
    fun resetDoubles() {
        inventoryReadClient.lastRouteId = null
        inventoryReadClient.lastTravelDate = null
        inventoryReadClient.failNextWith = null
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(emptyList())
        catalogClient.lastRouteId = null
        catalogClient.failNextWith = null
        catalogClient.next = RouteOutcome.Found(
            RouteSchedule("R1", LocalTime.of(23, 59), ZoneId.of("Europe/Rome")),
        )
    }

    private fun seedOwnedBy(owner: String) {
        orderService.next = Order(
            id = orderId,
            userId = owner,
            status = OrderStatus.CONFIRMED,
            routeId = "R1",
            seats = "1A",
            travelDate = travelDate,
            totalAmount = BigDecimal.TEN,
            currency = "EUR",
            createdAt = LocalDateTime.now(),
        ).apply { setAsNew(false) }
    }

    @Test
    fun `owner can read their own order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.userId").isEqualTo("user-owner")
    }

    @Test
    fun `the order view exposes the travel date as an ISO date string`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // Asserted through the real Boot-configured mapper, not a locally built one: a LocalDate
            // serialized as [2026,9,1] instead of "2026-09-01" would still round-trip inside Kotlin
            // and only break the frontend, which parses the string.
            .jsonPath("$.travelDate").isEqualTo(travelDate.toString())
            // And it is not confused with the purchase timestamp, which is a different field.
            .jsonPath("$.createdAt").exists()
    }

    @Test
    fun `another customer gets 404, not 403, for someone else's order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("other") }
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `operations staff can read any order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("ops") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `a missing order is 404`() {
        orderService.next = null
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isNotFound
    }

    // --- GET /api/orders (customer history) -------------------------------------------------------

    private fun seedHistory(vararg orders: Order) {
        orderService.historyRows = orders.toList()
    }

    private fun order(
        owner: String,
        createdAt: LocalDateTime,
        status: OrderStatus = OrderStatus.CONFIRMED,
        offerId: String? = null,
        currency: String = "EUR",
        refundable: Boolean? = null,
        refundCutoffMinutesBeforeDeparture: Int? = null,
        offerTermsVersion: Int? = null,
        departureAt: OffsetDateTime? = null,
    ) =
        Order(
            id = UUID.randomUUID(),
            userId = owner,
            status = status,
            routeId = "R1",
            seats = "1A",
            travelDate = travelDate,
            totalAmount = BigDecimal.TEN,
            currency = currency,
            createdAt = createdAt,
            offerId = offerId,
            refundable = refundable,
            refundCutoffMinutesBeforeDeparture = refundCutoffMinutesBeforeDeparture,
            offerTermsVersion = offerTermsVersion,
            departureAt = departureAt,
        ).apply { setAsNew(false) }

    @Test
    fun `the list is scoped to the caller`() {
        seedHistory(order("user-owner", LocalDateTime.now()))
        webTestClient.get().uri("/api/orders")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.orders[0].userId").isEqualTo("user-owner")

        assertEquals("user-owner", orderService.lastHistoryUserId)
    }

    /**
     * The rule that differs from [getOrder]. `operations` may read one order it was given the id of,
     * but must not be able to list orders it does not own: the principal filter stays applied.
     */
    @Test
    fun `operations staff does not widen the list to other customers`() {
        seedHistory()
        webTestClient.get().uri("/api/orders")
            .headers { it.setBearerAuth("ops") }
            .exchange()
            .expectStatus().isOk

        assertEquals("user-ops", orderService.lastHistoryUserId)
    }

    @Test
    fun `the list rejects anonymous callers`() {
        webTestClient.get().uri("/api/orders")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `the status filter reaches the query`() {
        seedHistory()
        webTestClient.get().uri("/api/orders?status=CONFIRMED")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk

        assertEquals(OrderStatus.CONFIRMED, orderService.lastHistoryStatus)
    }

    @Test
    fun `an unknown status is a 400`() {
        webTestClient.get().uri("/api/orders?status=NOT_A_STATUS")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `the page size is clamped rather than trusted`() {
        seedHistory()
        webTestClient.get().uri("/api/orders?limit=100000")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk

        assertEquals(100, orderService.lastHistoryLimit)
    }

    @Test
    fun `a malformed cursor is a 400, not a silent restart from the first page`() {
        webTestClient.get().uri("/api/orders?cursor=not-a-cursor")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `nextCursor is null on the last page`() {
        seedHistory(order("user-owner", LocalDateTime.now()))
        webTestClient.get().uri("/api/orders?limit=5")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.orders.length()").isEqualTo(1)
            .jsonPath("$.nextCursor").doesNotExist()
    }

    @Test
    fun `a full page returns a cursor and drops the look-ahead row`() {
        // limit=2 asks the service for 3; returning 3 means a further page exists.
        val now = LocalDateTime.now()
        seedHistory(
            order("user-owner", now),
            order("user-owner", now.minusMinutes(1)),
            order("user-owner", now.minusMinutes(2)),
        )
        val body = webTestClient.get().uri("/api/orders?limit=2")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.orders.length()").isEqualTo(2)
            .jsonPath("$.nextCursor").exists()
            .returnResult()

        // And the cursor round-trips to the sort key of the last returned row.
        val json = String(body.responseBody!!)
        val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(json)!!.groupValues[1]
        assertEquals(now.minusMinutes(1), OrderHistoryCursor.decode(cursor)!!.createdAt)
    }

    // --- hold deadline ----------------------------------------------------------------------------

    /**
     * The field the SPA has been coded against since before it existed: `frontend/src/api/orders.ts`
     * declares `reservationExpiresAt` and `OrderStatusPage` feeds it to `useCountdown`, so until now
     * the countdown simply never rendered.
     *
     * Asserted through the real Boot-configured mapper, because the serialised shape is the contract:
     * an instant written without an offset is re-interpreted in the browser's own zone, which is the
     * bug the whole change exists to remove.
     */
    @Test
    fun `an order holding seats exposes the deadline as an offset-carrying instant`() {
        val deadline = OffsetDateTime.parse("2026-08-05T12:00:00+02:00")
        orderService.next = order("user-owner", LocalDateTime.now(), OrderStatus.PAYMENT_PENDING)
            .apply { reservationExpiresAt = deadline }

        val body = webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservationExpiresAt").exists()
            .returnResult()

        val json = String(body.responseBody!!)
        val serialized = Regex("\"reservationExpiresAt\":\"([^\"]+)\"").find(json)!!.groupValues[1]
        assertEquals(
            deadline.toInstant(),
            OffsetDateTime.parse(serialized).toInstant(),
            "the deadline did not survive serialization as the same instant",
        )
    }

    @Test
    fun `an order that never held seats carries no deadline`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservationExpiresAt").doesNotExist()
    }

    // --- failure reason ---------------------------------------------------------------------------

    @Test
    fun `a failed order exposes its customer-visible reason`() {
        orderService.next = order("user-owner", LocalDateTime.now(), OrderStatus.FAILED)
            .apply { failureCode = CustomerFailureCode.SEATS_UNAVAILABLE.name }
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.failureCode").isEqualTo("SEATS_UNAVAILABLE")
            .jsonPath("$.failureMessage").isEqualTo(CustomerFailureCode.SEATS_UNAVAILABLE.customerMessage)
    }

    @Test
    fun `a non-failed order carries no failure reason`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.failureCode").doesNotExist()
            .jsonPath("$.failureMessage").doesNotExist()
    }

    // --- the offer that was bought ----------------------------------------------------------------

    @Test
    fun `the order view exposes the offer the order was bought against`() {
        orderService.next = order("user-owner", LocalDateTime.now(), offerId = "of-001")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // The SPA needs this to show what was bought and to decide whether a refund is offered
            // at all (ADR-015).
            .jsonPath("$.offerId").isEqualTo("of-001")
    }

    @Test
    fun `an order bought without an offer carries no offerId`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // Every order placed before the field existed, which the refund endpoint will refuse.
            .jsonPath("$.offerId").doesNotExist()
    }

    // --- what the order was sold under ------------------------------------------------------------

    @Test
    fun `the order view carries the currency beside the amount`() {
        // An amount without its unit is the bug this change removes one layer down: the authorization
        // used to pair a client-supplied number with a hardcoded "EUR". Showing the same pair in the
        // UI without the currency would put it straight back.
        orderService.next = order("user-owner", LocalDateTime.now(), currency = "CHF")

        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.currency").isEqualTo("CHF")
    }

    @Test
    fun `the order view exposes the refund terms the order was sold under`() {
        val departure = OffsetDateTime.parse("2026-10-01T07:00:00+02:00")
        orderService.next = order(
            "user-owner",
            LocalDateTime.now(),
            offerId = "of-002",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = 5,
            offerTermsVersion = 1,
            departureAt = departure,
        )

        val body = webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.refundable").isEqualTo(true)
            .jsonPath("$.refundCutoffMinutesBeforeDeparture").isEqualTo(5)
            // The version marker is an audit field, not a client one: a SPA holding it might use it
            // as a key into a catalogue that no longer has that revision.
            .jsonPath("$.offerTermsVersion").doesNotExist()
            .jsonPath("$.termsVersion").doesNotExist()
            .returnResult()

        // The departure instant keeps its offset through serialization, like reservationExpiresAt:
        // it is what the cutoff counts back from, and reinterpreting it in the browser's own zone
        // would move the refund deadline by the offset.
        val json = String(body.responseBody!!)
        val serialized = Regex("\"departureAt\":\"([^\"]+)\"").find(json)!!.groupValues[1]
        assertEquals(departure.toInstant(), OffsetDateTime.parse(serialized).toInstant())
    }

    @Test
    fun `an order bought without an offer carries no refund terms at all`() {
        // It has none, and the refund endpoint refuses it (ADR-015). Reporting `refundable: false`
        // instead would say Catalog decided that, which nobody did.
        seedOwnedBy("user-owner")

        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.refundable").doesNotExist()
            .jsonPath("$.refundCutoffMinutesBeforeDeparture").doesNotExist()
            .jsonPath("$.departureAt").doesNotExist()
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/orders/seat-availability
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the literal seat-availability path wins over the by-id route`() {
        // The failure mode this guards is confusing rather than dangerous: if `{orderId}` matched
        // first, the request would fail converting "seat-availability" to a UUID and surface as a
        // 400 on a perfectly valid call. Spring's PathPattern comparator sorts literal segments
        // ahead of variables, and that is what is being pinned here.
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(listOf(SeatAvailability("1A", true)))

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.routeId").isEqualTo("R1")
            .jsonPath("$.seats[0].seatId").isEqualTo("1A")

        assertEquals("R1", inventoryReadClient.lastRouteId)
        assertEquals(travelDate, inventoryReadClient.lastTravelDate)
    }

    @Test
    fun `the seat map exposes availability only, never who holds a seat`() {
        // Distinguishing HELD from SOLD, or leaking a reservation or order id, would report one
        // customer's in-flight checkout to another. The response carries two fields and no more.
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(
            listOf(SeatAvailability("1A", true), SeatAvailability("1B", false)),
        )

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.seats[1].available").isEqualTo(false)
            .jsonPath("$.seats[1].length()").isEqualTo(2)
            .jsonPath("$.seats[1].reservationId").doesNotExist()
            .jsonPath("$.seats[1].orderId").doesNotExist()
            .jsonPath("$.seats[1].status").doesNotExist()
    }

    @Test
    fun `an unknown route is a 404`() {
        // Also the guard against collapsing the two mappings (#104): NotFound is a definitive answer
        // from Inventory, not a failure to reach it, so it must not be swept into the 503 that the
        // unknown outcomes below now produce.
        inventoryReadClient.next = SeatAvailabilityOutcome.NotFound

        webTestClient.get().uri("/api/orders/seat-availability?routeId=nope&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `an unknown outcome from Inventory is a 503, not a 500`() {
        // #104. The client classifies only definitive refusals and throws everything else, so that
        // "Inventory did not answer" can never be mistaken for "no seats are free". With no mapping
        // in the controller all of it reached GlobalErrorHandler's Throwable handler and came back
        // as 500 INTERNAL_ERROR -- an Orders bug, for what is a dependency being unavailable.
        inventoryReadClient.failNextWith = java.util.concurrent.TimeoutException("inventory-read timed out")

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEAT_MAP_UNAVAILABLE")
    }

    @Test
    fun `an open inventory-read breaker is a 503, which is the rollout case`() {
        // The likeliest trigger in the cluster is not a cold start: during an Inventory rollout the
        // inventory-read breaker opens, and for the ten seconds it stays open every seat map request
        // took the unmapped path. A separate case from the timeout above because it is a different
        // exception type reaching the same catch, and the catch is what must cover both.
        val breaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("inventory-read-test")
        breaker.transitionToOpenState()
        inventoryReadClient.failNextWith =
            io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(breaker)

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEAT_MAP_UNAVAILABLE")
    }

    @Test
    fun `a same-day run that has already departed is refused`() {
        // Issue #98 / ADR-014. A refund puts the seats of a departed run back to AVAILABLE by design,
        // so Inventory's rows are untouched and the train that left this morning still reads as
        // bookable. 409 rather than 400: the date is today and the request is well formed -- the
        // resource is valid and its state is wrong.
        catalogClient.next = RouteOutcome.Found(
            RouteSchedule("R1", LocalTime.of(0, 1), ZoneId.of("Europe/Rome")),
        )

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=${LocalDate.now()}")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RUN_ALREADY_DEPARTED")

        // Refused before Inventory was asked: there is nothing to show, so there is nothing to fetch.
        assertNull(inventoryReadClient.lastTravelDate)
    }

    @Test
    fun `a same-day run that has not departed yet still returns its seat map`() {
        catalogClient.next = RouteOutcome.Found(
            RouteSchedule("R1", LocalTime.of(23, 59), ZoneId.of("Europe/Rome")),
        )
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(listOf(SeatAvailability("1A", true)))

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=${LocalDate.now()}")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.seats[0].seatId").isEqualTo("1A")
    }

    @Test
    fun `a future date never asks Catalog at all`() {
        // The filter must not put a read-heavy public dependency on the common browsing request. A
        // date in the future cannot have departed, so there is nothing to ask.
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(listOf(SeatAvailability("1A", true)))

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk

        assertNull(catalogClient.lastRouteId)
    }

    @Test
    fun `a route Catalog does not know keeps answering as it did before the filter`() {
        // NotFound is Catalog answering, not failing. Inventory owns whether a route and date have
        // seats, and it already answers that -- so an unknown route must not gain a second service's
        // opinion about its existence.
        catalogClient.next = RouteOutcome.NotFound
        inventoryReadClient.next = SeatAvailabilityOutcome.NotFound

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R9&travelDate=${LocalDate.now()}")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `an unreachable Catalog fails the seat map closed rather than hiding the filter`() {
        // Not knowing whether the train has left is not a reason to say it has not. A seat sold on a
        // departed run is a real ticket for a train that is gone.
        catalogClient.failNextWith = java.util.concurrent.TimeoutException("catalog-read timed out")

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=${LocalDate.now()}")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEAT_MAP_UNAVAILABLE")
    }

    @Test
    fun `the seat map requires authentication`() {
        // It is a step of the checkout, not public browsing: the picker sits behind ProtectedRoute.
        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$travelDate")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `a travel date in the past is refused with the same code as checkout`() {
        // Issue #98, first half. The façade used to forward a past date to Inventory unchallenged,
        // so a customer could be shown a seat map for a train that left last week and be told only
        // at checkout, which rejects the same date with this code.
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(listOf(SeatAvailability("1A", true)))
        val past = LocalDate.now().minusDays(1)

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=$past")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("TRAVEL_DATE_IN_THE_PAST")

        // And Inventory was never asked: the guard is in front of the call, not a filter on its answer.
        assertNull(inventoryReadClient.lastTravelDate)
    }

    @Test
    fun `today is still a valid travel date`() {
        // The guard is `isBefore`, not `isAfter`: a run departing later today is bookable. Filtering
        // out same-day runs that have *already departed* needs the departure instant per route and
        // is the rest of #98.
        inventoryReadClient.next = SeatAvailabilityOutcome.Found(listOf(SeatAvailability("1A", true)))

        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1&travelDate=${LocalDate.now()}")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `a missing travel date is rejected rather than defaulted`() {
        // Seats are keyed by (route_id, travel_date, seat_id) since #64, so answering for "some
        // day" would return a seat map for a journey nobody asked about.
        webTestClient.get().uri("/api/orders/seat-availability?routeId=R1")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isBadRequest
    }

    open class FakeOrderService : OrderService(
        mock(OrderRepository::class.java),
        mock(IdempotentRequestRepository::class.java),
    ) {
        var next: Order? = null
        override suspend fun getOrderById(id: UUID): Order? = next

        var historyRows: List<Order> = emptyList()
        var lastHistoryUserId: String? = null
        var lastHistoryStatus: OrderStatus? = null
        var lastHistoryLimit: Int? = null

        override suspend fun findHistoryPage(
            userId: String,
            status: OrderStatus?,
            cursor: OrderHistoryCursor?,
            limit: Int,
        ): List<Order> {
            lastHistoryUserId = userId
            lastHistoryStatus = status
            lastHistoryLimit = limit
            return historyRows
        }
    }

    open class FakeInventoryReadClient : InventoryReadClient {
        var next: SeatAvailabilityOutcome = SeatAvailabilityOutcome.Found(emptyList())
        var lastRouteId: String? = null
        var lastTravelDate: LocalDate? = null

        /**
         * Makes the *next* call throw, standing in for the unknown outcomes the real client does not
         * classify. Consumed when it fires, on purpose: `@WebFluxTest` caches one context for the
         * whole class, so a sticky failure would leak into whichever test happens to run next.
         */
        var failNextWith: Throwable? = null

        override suspend fun getSeatAvailability(routeId: String, travelDate: LocalDate): SeatAvailabilityOutcome {
            lastRouteId = routeId
            lastTravelDate = travelDate
            failNextWith?.let { failNextWith = null; throw it }
            return next
        }
    }

    /**
     * Only [getRoute] is reachable from this controller; [getOffer] belongs to the checkout
     * orchestrator, which is a mock here.
     */
    open class FakeCatalogClient : CatalogClient {
        /** Default: a route that leaves late enough that "today" has not departed during a test run. */
        var next: RouteOutcome = RouteOutcome.Found(
            RouteSchedule("R1", LocalTime.of(23, 59), ZoneId.of("Europe/Rome")),
        )
        var lastRouteId: String? = null

        /** See the note on FakeInventoryReadClient.failNextWith: consumed so it cannot leak. */
        var failNextWith: Throwable? = null

        override suspend fun getOffer(offerId: String): OfferOutcome =
            throw UnsupportedOperationException("the seat map never resolves an offer")

        override suspend fun getRoute(routeId: String): RouteOutcome {
            lastRouteId = routeId
            failNextWith?.let { failNextWith = null; throw it }
            return next
        }
    }

    @TestConfiguration
    class Cfg {
        @Bean
        @Primary
        fun jwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { token -> Mono.just(jwtFor(token)) }

        @Bean
        fun orderService(): FakeOrderService = FakeOrderService()

        @Bean
        fun checkoutOrchestrator(): CheckoutOrchestrator = mock(CheckoutOrchestrator::class.java)

        @Bean
        fun refundOrchestrator(): RefundOrchestrator = mock(RefundOrchestrator::class.java)

        @Bean
        fun inventoryReadClient(): FakeInventoryReadClient = FakeInventoryReadClient()

        @Bean
        fun catalogClient(): FakeCatalogClient = FakeCatalogClient()
    }
}

private fun jwtFor(token: String): Jwt {
    val (sub, roles) = when (token) {
        "owner" -> "user-owner" to listOf("customer")
        "other" -> "user-other" to listOf("customer")
        "ops" -> "user-ops" to listOf("operations")
        else -> "nobody" to emptyList()
    }
    val now = Instant.now()
    return Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject(sub)
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("realm_access", mapOf("roles" to roles))
        .build()
}
