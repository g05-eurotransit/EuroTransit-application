package it.polito.cpo.controller

import it.polito.cpo.contracts.inventory.RouteSeatsResponse
import it.polito.cpo.contracts.inventory.SeatDto
import it.polito.cpo.contracts.inventory.SeatStatus
import it.polito.cpo.observability.GlobalErrorHandler
import it.polito.cpo.security.EuroTransitAuthorities
import it.polito.cpo.security.SecurityConfig
import it.polito.cpo.service.SeatService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [SeatController::class])
@Import(SecurityConfig::class, GlobalErrorHandler::class, SeatControllerTest.Config::class)
class SeatControllerTest {

    @Autowired
    lateinit var context: ApplicationContext
    
    @Autowired
    lateinit var seatService: SeatService

    class Config {
        @Bean
        @Primary
        fun seatService(): SeatService = Mockito.mock(SeatService::class.java)
    }

    @Test
    fun `getRouteSeats returns 200 OK and seats with ROLE_service`() {
        val client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient().build()

        val response = RouteSeatsResponse("route-1", listOf(SeatDto("1A", SeatStatus.AVAILABLE)))
        runBlocking {
            Mockito.`when`(seatService.getSeatsForRoute("route-1")).thenReturn(response)
        }

        client.mutateWith(
            mockJwt().authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
        )
            .get()
            .uri("/api/inventory/routes/route-1/seats")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.routeId").isEqualTo("route-1")
            .jsonPath("$.seats[0].seatId").isEqualTo("1A")
    }

    @Test
    fun `getRouteSeats returns 403 Forbidden if JWT lacks ROLE_service authority`() {
        val client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient().build()

        client.mutateWith(
            mockJwt().authorities(SimpleGrantedAuthority("ROLE_customer"))
        )
            .get()
            .uri("/api/inventory/routes/route-1/seats")
            .exchange()
            .expectStatus().isForbidden
    }
    
    @Test
    fun `getRouteSeats returns 401 Unauthorized if JWT is missing`() {
        val client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient().build()

        client.get()
            .uri("/api/inventory/routes/route-1/seats")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
