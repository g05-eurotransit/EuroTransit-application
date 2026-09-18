package it.polito.cpo.payments.repository

import it.polito.cpo.payments.model.OutboxEvent
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OutboxEventRepository : CoroutineCrudRepository<OutboxEvent, UUID>
