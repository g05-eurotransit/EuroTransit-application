package it.polito.cpo.repository

import it.polito.cpo.contracts.catalog.RefundPolicy
import it.polito.cpo.model.Offer
import it.polito.cpo.model.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.stereotype.Repository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

private val EVERY_DAY = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI")

// Every seeded route departs from an Italian station, so they all carry the same zone today. That
// is precisely why the field is required rather than defaulted: the uniformity is a property of
// this seed, not of the model.
private val ROME = ZoneId.of("Europe/Rome")

private val NON_REFUNDABLE = RefundPolicy(refundable = false, cutoffMinutesBeforeDeparture = null)

@Repository
@ConditionalOnProperty(name = ["catalog.storage"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryCatalogRepository : RouteRepository {

    private val routes = listOf(
        Route("rt-to-mi-001", "Torino Porta Nuova", "Milano Centrale", LocalTime.of(7, 0), LocalTime.of(8, 5), ROME, "Frecciarossa", EVERY_DAY),
        Route("rt-mi-pa-002", "Milano Centrale", "Paris Gare de Lyon", LocalTime.of(9, 10), LocalTime.of(16, 22), ROME, "TGV", EVERY_DAY),
        Route("rt-mi-zu-003", "Milano Centrale", "Zürich HB", LocalTime.of(11, 10), LocalTime.of(14, 27), ROME, "EuroCity", EVERY_DAY),
        Route("rt-ro-vi-004", "Roma Termini", "Wien Hbf", LocalTime.of(19, 20), LocalTime.of(8, 42), ROME, "Nightjet", WEEKDAYS),
        Route("rt-to-ly-005", "Torino Porta Susa", "Lyon Part-Dieu", LocalTime.of(14, 31), LocalTime.of(18, 20), ROME, "TGV", WEEKDAYS),
    )

    private val offers = listOf(
        Offer("of-001", "rt-to-mi-001", "STANDARD", BigDecimal("24.90"), "EUR", NON_REFUNDABLE, termsVersion = 1),
        // The agreed rule for BUSINESS. "Refundable until departure" cannot literally mean at
        // departure, and those five minutes belong in the data rather than in Orders' code.
        Offer("of-002", "rt-to-mi-001", "BUSINESS", BigDecimal("49.90"), "EUR", RefundPolicy(refundable = true, cutoffMinutesBeforeDeparture = 5), termsVersion = 1),
        Offer("of-003", "rt-mi-pa-002", "STANDARD", BigDecimal("89.00"), "EUR", NON_REFUNDABLE, termsVersion = 1),
        Offer("of-004", "rt-mi-zu-003", "STANDARD", BigDecimal("64.00"), "EUR", NON_REFUNDABLE, termsVersion = 1),
        Offer("of-005", "rt-ro-vi-004", "SLEEPER", BigDecimal("129.00"), "EUR", RefundPolicy(refundable = true, cutoffMinutesBeforeDeparture = 1440), termsVersion = 1),
        Offer("of-006", "rt-to-ly-005", "STANDARD", BigDecimal("45.00"), "EUR", NON_REFUNDABLE, termsVersion = 1),
    )

    override fun findAllRoutes(): Flow<Route> = routes.asFlow()

    override suspend fun findRouteById(routeId: String): Route? = routes.find { it.routeId == routeId }

    override fun findAllOffers(): Flow<Offer> = offers.asFlow()

    override suspend fun findOfferById(offerId: String): Offer? = offers.find { it.offerId == offerId }
}
