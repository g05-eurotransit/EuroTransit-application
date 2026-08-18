package it.polito.cpo.client

import it.polito.cpo.contracts.payments.PaymentRequest
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/** Local `dev` stand-in for Payments, used when no real Payments is running. */
@Component
@Profile("dev")
class MockPaymentClient : PaymentClient {

    override suspend fun authorizePayment(
        request: PaymentRequest,
        idempotencyKey: String,
        correlationId: String,
    ): AuthorizationOutcome = AuthorizationOutcome.Authorized(providerReference = "mock_auth_id")

    override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome =
        ReversalOutcome.Reversed
}
