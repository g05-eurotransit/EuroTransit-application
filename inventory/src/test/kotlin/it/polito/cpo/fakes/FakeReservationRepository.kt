package it.polito.cpo.fakes

import it.polito.cpo.model.Reservation
import it.polito.cpo.repository.ReservationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.mockito.Mockito
import org.springframework.dao.DuplicateKeyException
import java.time.OffsetDateTime
import java.util.UUID

class FakeReservationRepository(
    private val delegate: ReservationRepository = Mockito.mock(ReservationRepository::class.java)
) : ReservationRepository by delegate {
    val store = HashMap<String, Reservation>()
    override suspend fun findByReservationId(reservationId: String): Reservation? {
        return store[reservationId]
    }
    override suspend fun findByReservationIdForUpdate(reservationId: String): Reservation? {
        return store[reservationId]
    }
    override suspend fun findByOrderId(orderId: UUID): Reservation? {
        return store.values.firstOrNull { it.orderId == orderId }
    }
    override suspend fun <S : Reservation> save(entity: S): S {
        val existing = store.values.firstOrNull { it.orderId == entity.orderId && it.reservationId != entity.reservationId }
        if (existing != null) {
            throw DuplicateKeyException("Unique index violation on uk_reservations_order_id")
        }
        store[entity.reservationId] = entity
        return entity
    }
    var currentDbTime: OffsetDateTime = OffsetDateTime.now()
    
    override fun expireAndReturn(): Flow<Reservation> {
        val expired = store.values.filter { it.status == "HELD" && it.expiresAt.isBefore(currentDbTime) }
        val updated = expired.map { it.copy(status = "EXPIRED") }
        updated.forEach { store[it.reservationId] = it }
        return flowOf(*updated.toTypedArray())
    }
    
    override suspend fun getCurrentTimestamp(): OffsetDateTime {
        return currentDbTime
    }
    
    override suspend fun cancelIfHeld(reservationId: String): Int {
        val res = store[reservationId]
        if (res != null && res.status == "HELD") {
            store[reservationId] = res.copy(status = "CANCELLED")
            return 1
        }
        return 0
    }
}
