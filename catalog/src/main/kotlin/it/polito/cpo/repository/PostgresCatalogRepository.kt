package it.polito.cpo.repository

import io.r2dbc.spi.Row
import it.polito.cpo.contracts.catalog.RefundPolicy
import it.polito.cpo.model.Offer
import it.polito.cpo.model.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

@Repository
@ConditionalOnProperty(name = ["catalog.storage"], havingValue = "postgres")
class PostgresCatalogRepository(private val db: DatabaseClient) : RouteRepository {
    override fun findAllRoutes(): Flow<Route> =
        db.sql("SELECT * FROM routes ORDER BY display_order").map { row, _ -> route(row) }.all().asFlow()

    override suspend fun findRouteById(routeId: String): Route? =
        db.sql("SELECT * FROM routes WHERE route_id = :id").bind("id", routeId)
            .map { row, _ -> route(row) }.one().awaitSingleOrNull()

    override fun findAllOffers(): Flow<Offer> =
        db.sql("SELECT * FROM offers ORDER BY offer_id").map { row, _ -> offer(row) }.all().asFlow()

    override suspend fun findOfferById(offerId: String): Offer? =
        db.sql("SELECT * FROM offers WHERE offer_id = :id").bind("id", offerId)
            .map { row, _ -> offer(row) }.one().awaitSingleOrNull()

    private fun route(row: Row) = Route(
        routeId = row.get("route_id", String::class.java)!!,
        origin = row.get("origin", String::class.java)!!,
        destination = row.get("destination", String::class.java)!!,
        departureTime = row.get("departure_time", LocalTime::class.java)!!,
        arrivalTime = row.get("arrival_time", LocalTime::class.java)!!,
        departureZone = ZoneId.of(row.get("departure_zone", String::class.java)!!),
        trainType = row.get("train_type", String::class.java)!!,
        operatingDays = row.get("operating_days", Array<String>::class.java)!!.toList(),
    )

    private fun offer(row: Row) = Offer(
        offerId = row.get("offer_id", String::class.java)!!,
        routeId = row.get("route_id", String::class.java)!!,
        fareClass = row.get("fare_class", String::class.java)!!,
        price = row.get("price", BigDecimal::class.java)!!,
        currency = row.get("currency", String::class.java)!!,
        refundPolicy = RefundPolicy(
            refundable = row.get("refundable", java.lang.Boolean::class.java)!!.booleanValue(),
            cutoffMinutesBeforeDeparture = row.get("refund_cutoff_minutes_before_departure", java.lang.Integer::class.java)?.toInt(),
        ),
        termsVersion = row.get("terms_version", java.lang.Integer::class.java)!!.toInt(),
        displayNote = row.get("display_note", String::class.java),
    )
}
