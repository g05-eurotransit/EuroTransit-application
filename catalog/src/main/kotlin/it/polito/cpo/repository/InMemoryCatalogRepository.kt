package it.polito.cpo.repository

import it.polito.cpo.model.Offer
import it.polito.cpo.model.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalTime

private val EVERY_DAY = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI")

@Repository
class InMemoryCatalogRepository : RouteRepository {

    private val routes = listOf(
        Route("rt-to-mi-001", "Torino Porta Nuova", "Milano Centrale", LocalTime.of(7, 0), LocalTime.of(8, 5), "Frecciarossa", EVERY_DAY),
        Route("rt-mi-pa-002", "Milano Centrale", "Paris Gare de Lyon", LocalTime.of(9, 10), LocalTime.of(16, 22), "TGV", EVERY_DAY),
        Route("rt-mi-zu-003", "Milano Centrale", "Zürich HB", LocalTime.of(11, 10), LocalTime.of(14, 27), "EuroCity", EVERY_DAY),
        Route("rt-ro-vi-004", "Roma Termini", "Wien Hbf", LocalTime.of(19, 20), LocalTime.of(8, 42), "Nightjet", WEEKDAYS),
        Route("rt-to-ly-005", "Torino Porta Susa", "Lyon Part-Dieu", LocalTime.of(14, 31), LocalTime.of(18, 20), "TGV", WEEKDAYS),
    )

    private val offers = listOf(
        Offer("of-001", "rt-to-mi-001", "STANDARD", BigDecimal("24.90"), "EUR", "Non-refundable"),
        Offer("of-002", "rt-to-mi-001", "BUSINESS", BigDecimal("49.90"), "EUR", "Refundable until departure"),
        Offer("of-003", "rt-mi-pa-002", "STANDARD", BigDecimal("89.00"), "EUR", "Exchangeable with fee"),
        Offer("of-004", "rt-mi-zu-003", "STANDARD", BigDecimal("64.00"), "EUR", "Non-refundable"),
        Offer("of-005", "rt-ro-vi-004", "SLEEPER", BigDecimal("129.00"), "EUR", "Refundable until 24h before departure"),
        Offer("of-006", "rt-to-ly-005", "STANDARD", BigDecimal("45.00"), "EUR", "Non-refundable"),
    )

    override fun findAllRoutes(): Flow<Route> = routes.asFlow()

    override suspend fun findRouteById(routeId: String): Route? = routes.find { it.routeId == routeId }

    override fun findAllOffers(): Flow<Offer> = offers.asFlow()
}
