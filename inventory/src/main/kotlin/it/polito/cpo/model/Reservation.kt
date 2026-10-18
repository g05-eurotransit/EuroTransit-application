package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Table("reservations")
data class Reservation(
    @Id
    @get:JvmName("getReservationIdValue")
    val reservationId: String,
    
    val orderId: UUID,
    val routeId: String,
    val travelDate: LocalDate,
    val status: String,
    val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime? = null,
    // When the one-time T2 payment window was opened (ADR-016), by the database clock. Null until
    // then, and forever for a direct-flow reservation. Its presence is what stops a replay from
    // extending the hold a second time.
    val paymentWindowStartedAt: OffsetDateTime? = null,

    @Transient
    var isNewRecord: Boolean = true
) : Persistable<String> {
    @PersistenceCreator
    constructor(
        reservationId: String,
        orderId: UUID,
        routeId: String,
        travelDate: LocalDate,
        status: String,
        expiresAt: OffsetDateTime,
        createdAt: OffsetDateTime?,
        paymentWindowStartedAt: OffsetDateTime?
    ) : this(
        reservationId = reservationId,
        orderId = orderId,
        routeId = routeId,
        travelDate = travelDate,
        status = status,
        expiresAt = expiresAt,
        createdAt = createdAt,
        paymentWindowStartedAt = paymentWindowStartedAt,
        isNewRecord = false
    )

    override fun getId(): String = reservationId
    override fun isNew(): Boolean = isNewRecord
}
