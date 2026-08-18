package it.polito.cpo.client

import it.polito.cpo.contracts.payments.PaymentRequest
import java.util.UUID

/**
 * Orders' view of the Payments money-path commands. Same contract as [InventoryClient]: the sealed
 * outcomes carry only definitive results, and an unknown outcome is thrown so it cannot be turned
 * into a business decision.
 */
interface PaymentClient {

    suspend fun authorizePayment(
        request: PaymentRequest,
        idempotencyKey: String,
        correlationId: String,
    ): AuthorizationOutcome

    /**
     * Reverses the authorization made for [orderId]. Payments resolves the provider reference from
     * its own authorization record and picks void or refund itself, so Orders passes only the order
     * id. Idempotent on [idempotencyKey].
     */
    suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome
}
