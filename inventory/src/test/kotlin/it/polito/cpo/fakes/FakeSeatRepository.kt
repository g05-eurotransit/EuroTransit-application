package it.polito.cpo.fakes

import it.polito.cpo.repository.SeatRepository
import org.mockito.Mockito
import java.time.LocalDate

class FakeSeatRepository(
    private val delegate: SeatRepository = Mockito.mock(SeatRepository::class.java),
    var seatsAvailable: Boolean = true
) : SeatRepository by delegate {
    var releaseCalledWith: String? = null
    var batchReleaseCalledWith: Collection<String>? = null
    override suspend fun holdSeats(routeId: String, travelDate: LocalDate, seatIds: List<String>, reservationId: String, expectedCount: Int): Int {
        return if (seatsAvailable) seatIds.size else 0
    }
    override suspend fun releaseSeats(reservationId: String): Int {
        releaseCalledWith = reservationId
        return 1
    }
    override suspend fun releaseSeatsForReservations(reservationIds: Collection<String>): Int {
        batchReleaseCalledWith = reservationIds
        return reservationIds.size
    }
    var confirmCalledWith: String? = null
    var confirmSeatsReturnCount: Int = 2
    var countByReservationIdReturnCount: Long = 2L
    override suspend fun confirmSeats(reservationId: String): Int {
        confirmCalledWith = reservationId
        return confirmSeatsReturnCount
    }
    override suspend fun countByReservationId(reservationId: String): Long {
        return countByReservationIdReturnCount
    }
}
