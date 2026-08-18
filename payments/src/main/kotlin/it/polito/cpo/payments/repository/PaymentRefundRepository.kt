package it.polito.cpo.payments.repository

import it.polito.cpo.payments.model.PaymentRefund
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentRefundRepository : CoroutineCrudRepository<PaymentRefund, UUID>
