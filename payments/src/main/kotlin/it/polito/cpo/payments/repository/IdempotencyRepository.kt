package it.polito.cpo.payments.repository

import it.polito.cpo.payments.model.IdempotencyRecord
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IdempotencyRepository : CoroutineCrudRepository<IdempotencyRecord, String>
