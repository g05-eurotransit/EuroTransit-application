package it.polito.cpo.client

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.math.absoluteValue

/**
 * Local `dev` stand-in for the Inventory seat map, used when no real Inventory is running.
 *
 * Availability is derived from `(routeId, travelDate, seatId)` rather than randomised, for the same
 * reason [MockInventoryClient] derives its reservation id from the order id: a value that changes
 * per call makes the SPA look broken only in `dev`, because reopening the picker would reshuffle
 * which seats are taken while the customer is choosing one.
 *
 * The pattern is arbitrary but not uniform — roughly a quarter of the seats read as taken — because
 * a seat map where everything is free exercises none of the states the page has to render.
 */
@Component
@Profile("dev")
class MockInventoryReadClient : InventoryReadClient {

    override suspend fun getSeatAvailability(routeId: String, travelDate: LocalDate): SeatAvailabilityOutcome {
        val seats = (1..ROWS).flatMap { row ->
            COLUMNS.map { column ->
                val seatId = "$row$column"
                SeatAvailability(
                    seatId = seatId,
                    available = "$routeId|$travelDate|$seatId".hashCode().absoluteValue % 4 != 0,
                )
            }
        }
        return SeatAvailabilityOutcome.Found(seats)
    }

    private companion object {
        const val ROWS = 10
        val COLUMNS = listOf("A", "B", "C", "D")
    }
}
