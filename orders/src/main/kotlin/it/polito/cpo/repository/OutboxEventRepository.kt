package it.polito.cpo.repository

import it.polito.cpo.model.OutboxEvent
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OutboxEventRepository : CoroutineCrudRepository<OutboxEvent, UUID>
