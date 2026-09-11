package it.polito.cpo.client

import it.polito.cpo.contracts.catalog.RefundPolicy
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local `dev` stand-in for the Catalog offer lookup, used when no real Catalog is running.
 *
 * Unlike [MockInventoryReadClient], this one is **not** free to invent its answers: checkout now
 * rejects a request whose amount disagrees with the offer, so a mock with different prices would
 * make every `dev` checkout fail with `OFFER_PRICE_MISMATCH` against a SPA reading the real
 * catalogue. The six offers below therefore mirror `catalog`'s seeded repository exactly — including
 * the two refundable ones, so the refund terms frozen onto a `dev` order are the real rules.
 *
 * An id outside the seed answers [OfferOutcome.NotFound], which is what the real Catalog does.
 */
@Component
@Profile("dev")
class MockCatalogClient : CatalogClient {

    override suspend fun getOffer(offerId: String): OfferOutcome =
        OFFERS[offerId]?.let { OfferOutcome.Found(it) } ?: OfferOutcome.NotFound

    private companion object {
        private val ROME: ZoneId = ZoneId.of("Europe/Rome")
        private val NON_REFUNDABLE = RefundPolicy(refundable = false, cutoffMinutesBeforeDeparture = null)

        private fun offer(
            offerId: String,
            routeId: String,
            price: String,
            departureTime: LocalTime,
            refundPolicy: RefundPolicy = NON_REFUNDABLE,
        ) = ResolvedOffer(
            offerId = offerId,
            routeId = routeId,
            price = BigDecimal(price),
            currency = "EUR",
            refundPolicy = refundPolicy,
            termsVersion = 1,
            departureTime = departureTime,
            departureZone = ROME,
        )

        val OFFERS: Map<String, ResolvedOffer> = listOf(
            offer("of-001", "rt-to-mi-001", "24.90", LocalTime.of(7, 0)),
            offer("of-002", "rt-to-mi-001", "49.90", LocalTime.of(7, 0), RefundPolicy(true, 5)),
            offer("of-003", "rt-mi-pa-002", "89.00", LocalTime.of(9, 10)),
            offer("of-004", "rt-mi-zu-003", "64.00", LocalTime.of(11, 10)),
            offer("of-005", "rt-ro-vi-004", "129.00", LocalTime.of(19, 20), RefundPolicy(true, 1440)),
            offer("of-006", "rt-to-ly-005", "45.00", LocalTime.of(14, 31)),
        ).associateBy { it.offerId }
    }
}
