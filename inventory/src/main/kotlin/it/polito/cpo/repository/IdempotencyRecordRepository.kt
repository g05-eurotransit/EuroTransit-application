package it.polito.cpo.repository

import it.polito.cpo.model.IdempotencyRecord
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IdempotencyRecordRepository : CoroutineCrudRepository<IdempotencyRecord, String> {
    
    /**
     * Retrieves an idempotency record using both the key and the user context (principal_id)
     */
    @Query("SELECT * FROM idempotency_records WHERE idempotency_key = :key AND principal_id = :principalId")
    suspend fun findByKeyAndPrincipal(key: String, principalId: String): IdempotencyRecord?

    @org.springframework.data.r2dbc.repository.Modifying
    @Query("DELETE FROM idempotency_records WHERE created_at < :cutoff")
    suspend fun deleteByCreatedAtBefore(cutoff: java.time.OffsetDateTime): Int
}
