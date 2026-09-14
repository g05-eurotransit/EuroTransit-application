package it.polito.cpo.service

import it.polito.cpo.model.Seat
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.LocalDate

class SeatServiceTest {

    private val travelDate = LocalDate.now().plusDays(1)

    @Test
    fun `getAvailability returns mapped privacy-safe seats when route and date exist`() = runBlocking {
        val repository = Mockito.mock(SeatRepository::class.java)
        Mockito.`when`(repository.findByRouteIdAndTravelDate("route-1", travelDate))
            .thenReturn(flowOf(
                Seat("route-1", travelDate, "1A", "AVAILABLE"),
                Seat("route-1", travelDate, "1B", "HELD"),
                Seat("route-1", travelDate, "1C", "SOLD")
            ))

        val service = SeatService(repository)
        val response = service.getAvailability("route-1", travelDate)

        assertEquals("route-1", response.routeId)
        assertEquals(travelDate, response.travelDate)
        assertEquals(3, response.seats.size)
        
        assertEquals("1A", response.seats[0].seatId)
        assertTrue(response.seats[0].isAvailable)
        
        assertEquals("1B", response.seats[1].seatId)
        assertFalse(response.seats[1].isAvailable)

        assertEquals("1C", response.seats[2].seatId)
        assertFalse(response.seats[2].isAvailable)
    }

    @Test
    fun `getAvailability throws ApiException ROUTE_NOT_FOUND when route does not exist at all`(): Unit = runBlocking {
        val repository = Mockito.mock(SeatRepository::class.java)
        Mockito.`when`(repository.findByRouteIdAndTravelDate("invalid-route", travelDate))
            .thenReturn(emptyFlow())
        Mockito.`when`(repository.existsByRouteId("invalid-route"))
            .thenReturn(false)

        val service = SeatService(repository)
        
        val exception = assertThrows(ApiException::class.java) {
            runBlocking {
                service.getAvailability("invalid-route", travelDate)
            }
        }
        
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
        assertEquals("ROUTE_NOT_FOUND", exception.code)
    }

    @Test
    fun `getAvailability throws ApiException NO_SEATS_FOR_DATE when route exists but not for the given date`(): Unit = runBlocking {
        val repository = Mockito.mock(SeatRepository::class.java)
        Mockito.`when`(repository.findByRouteIdAndTravelDate("valid-route", travelDate))
            .thenReturn(emptyFlow())
        Mockito.`when`(repository.existsByRouteId("valid-route"))
            .thenReturn(true)

        val service = SeatService(repository)
        
        val exception = assertThrows(ApiException::class.java) {
            runBlocking {
                service.getAvailability("valid-route", travelDate)
            }
        }
        
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
        assertEquals("NO_SEATS_FOR_DATE", exception.code)
    }
}
