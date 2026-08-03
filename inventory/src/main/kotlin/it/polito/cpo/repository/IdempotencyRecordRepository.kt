package it.polito.cpo.repository

import it.polito.cpo.model.IdempotencyRecord
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface IdempotencyRecordRepository : CoroutineCrudRepository<IdempotencyRecord, String> {
    
    /**
     * Finds an idempotency record by key and principal identifier.
     */
    @Query("SELECT * FROM idempotency_records WHERE idempotency_key = :key AND principal_id = :principalId")
    suspend fun findByKeyAndPrincipal(key: String, principalId: String): IdempotencyRecord?

    /**
     * Deletes idempotency records created before the specified timestamp.
     */
    @Modifying
    @Query("DELETE FROM idempotency_records WHERE created_at < :cutoff")
    suspend fun deleteByCreatedAtBefore(cutoff: java.time.OffsetDateTime): Int
}
