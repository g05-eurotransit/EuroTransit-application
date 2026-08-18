package it.polito.cpo.repository

import it.polito.cpo.model.Offer
import it.polito.cpo.model.Route
import kotlinx.coroutines.flow.Flow

/**
 * Read-side catalog store. Catalog owns no database in the data-ownership contract;
 * the current implementation is an in-memory seeded store, replaceable without
 * touching the API layer.
 */
interface RouteRepository {
    fun findAllRoutes(): Flow<Route>
    suspend fun findRouteById(routeId: String): Route?
    fun findAllOffers(): Flow<Offer>
}
