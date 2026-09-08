package it.polito.cpo.fakes

import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.repository.OutboxEventRepository
import org.mockito.Mockito

class FakeOutboxEventRepository(
    private val delegate: OutboxEventRepository = Mockito.mock(OutboxEventRepository::class.java),
    var shouldThrow: Boolean = false
) : OutboxEventRepository by delegate {
    val store = mutableListOf<OutboxEvent>()
    override suspend fun <S : OutboxEvent> save(entity: S): S {
        if (shouldThrow) throw RuntimeException("Database error during outbox save")
        store.add(entity)
        return entity
    }
}
