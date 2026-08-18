package it.polito.cpo.controller

import it.polito.cpo.model.Offer
import it.polito.cpo.model.Route
import it.polito.cpo.repository.RouteRepository
import it.polito.cpo.observability.ApiException
import kotlinx.coroutines.flow.Flow
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public catalog read APIs. Unauthenticated by contract: catalog data is public and
 * staleness-tolerant; personalized offers are out of scope for now.
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val repository: RouteRepository) {

    @GetMapping("/routes")
    fun listRoutes(): Flow<Route> = repository.findAllRoutes()

    @GetMapping("/routes/{routeId}")
    suspend fun getRoute(@PathVariable routeId: String): Route =
        repository.findRouteById(routeId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Route $routeId does not exist")

    @GetMapping("/offers")
    fun listOffers(): Flow<Offer> = repository.findAllOffers()
}