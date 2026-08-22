package it.polito.cpo.service

import it.polito.cpo.contracts.inventory.SeatStatus
import it.polito.cpo.model.Seat
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class SeatServiceTest {

    @Test
    fun `getSeatsForRoute returns mapped seats when route exists`() = runBlocking {
        val repository = Mockito.mock(SeatRepository::class.java)
        Mockito.`when`(repository.findByRouteId("route-1"))
            .thenReturn(flowOf(
                Seat("route-1", "1A", "AVAILABLE"),
                Seat("route-1", "1B", "HELD")
            ))

        val service = SeatService(repository)
        val response = service.getSeatsForRoute("route-1")

        assertEquals("route-1", response.routeId)
        assertEquals(2, response.seats.size)
        assertEquals("1A", response.seats[0].seatId)
        assertEquals(SeatStatus.AVAILABLE, response.seats[0].status)
        assertEquals("1B", response.seats[1].seatId)
        assertEquals(SeatStatus.HELD, response.seats[1].status)
    }

    @Test
    fun `getSeatsForRoute throws ApiException 404 when no seats found`(): Unit = runBlocking {
        val repository = Mockito.mock(SeatRepository::class.java)
        Mockito.`when`(repository.findByRouteId("invalid-route"))
            .thenReturn(emptyFlow())

        val service = SeatService(repository)
        
        val exception = assertThrows(ApiException::class.java) {
            runBlocking {
                service.getSeatsForRoute("invalid-route")
            }
        }
        
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
        assertEquals("ROUTE_NOT_FOUND", exception.code)
    }
}
