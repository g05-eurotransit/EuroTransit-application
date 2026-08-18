package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("reservations")
data class Reservation(
    @Id
    @get:JvmName("getReservationIdValue")
    val reservationId: String,
    
    val orderId: UUID,
    val routeId: String,
    val status: String,
    val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime? = null,
    
    @Transient
    var isNewRecord: Boolean = true
) : Persistable<String> {
    @PersistenceCreator
    constructor(
        reservationId: String,
        orderId: UUID,
        routeId: String,
        status: String,
        expiresAt: OffsetDateTime,
        createdAt: OffsetDateTime?
    ) : this(
        reservationId = reservationId,
        orderId = orderId,
        routeId = routeId,
        status = status,
        expiresAt = expiresAt,
        createdAt = createdAt,
        isNewRecord = false
    )

    override fun getId(): String = reservationId
    override fun isNew(): Boolean = isNewRecord
}
