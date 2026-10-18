package it.polito.cpo.controller

import it.polito.cpo.contracts.catalog.OfferDetail
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

    /**
     * One offer by id, carrying the route departure fields needed to evaluate its own refund
     * cutoff, so a caller resolving a single offer needs exactly one call.
     */
    @GetMapping("/offers/{offerId}")
    suspend fun getOffer(@PathVariable offerId: String): OfferDetail {
        val offer = repository.findOfferById(offerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Offer $offerId does not exist")

        // An offer whose route is missing is a broken catalog, not a bad request: the caller asked
        // for something that legitimately exists. Fail loudly rather than answer with a cutoff that
        // cannot be evaluated.
        val route = repository.findRouteById(offer.routeId)
            ?: throw ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "OFFER_ROUTE_MISSING",
                "Offer $offerId references route ${offer.routeId}, which does not exist",
            )

        return OfferDetail(
            offerId = offer.offerId,
            routeId = offer.routeId,
            fareClass = offer.fareClass,
            price = offer.price,
            currency = offer.currency,
            conditions = offer.conditions,
            refundPolicy = offer.refundPolicy,
            termsVersion = offer.termsVersion,
            departureTime = route.departureTime,
            departureZone = route.departureZone,
        )
    }
}
