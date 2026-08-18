package it.polito.cpo.repository

import it.polito.cpo.model.IdempotentRequest
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime

interface IdempotentRequestRepository : CoroutineCrudRepository<IdempotentRequest, String> {

    // Retention purge (api-design.md: Orders keeps checkout idempotency records for 24h).
    @Modifying
    @Query("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
    suspend fun deleteExpired(cutoff: LocalDateTime): Long
}
