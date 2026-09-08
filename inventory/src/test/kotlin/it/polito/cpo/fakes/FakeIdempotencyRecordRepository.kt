package it.polito.cpo.fakes

import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.repository.IdempotencyRecordRepository
import org.mockito.Mockito
import java.time.OffsetDateTime

class FakeIdempotencyRecordRepository(
    private val delegate: IdempotencyRecordRepository = Mockito.mock(IdempotencyRecordRepository::class.java),
    var shouldThrow: Boolean = false
) : IdempotencyRecordRepository by delegate {
    val store = HashMap<String, IdempotencyRecord>()
    override suspend fun <S : IdempotencyRecord> save(entity: S): S {
        store["${entity.idempotencyKey}-${entity.principalId}"] = entity
        return entity
    }
    override suspend fun findByKeyAndPrincipal(key: String, principalId: String): IdempotencyRecord? {
        return store["$key-$principalId"]
    }
    override suspend fun deleteByCreatedAtBefore(cutoff: OffsetDateTime): Int {
        if (shouldThrow) throw RuntimeException("Database error during idempotency purge")
        val toRemove = store.filter { it.value.createdAt?.isBefore(cutoff) == true }.keys
        toRemove.forEach { store.remove(it) }
        return toRemove.size
    }
}
