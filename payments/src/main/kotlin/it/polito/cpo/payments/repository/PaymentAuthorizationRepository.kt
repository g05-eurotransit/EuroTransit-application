package it.polito.cpo.payments.repository

import it.polito.cpo.payments.model.PaymentAuthorization
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentAuthorizationRepository : CoroutineCrudRepository<PaymentAuthorization, UUID> {
    suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentAuthorization?
    suspend fun findByOrderId(orderId: String): PaymentAuthorization?
}
