package it.polito.cpo.service

import it.polito.cpo.controller.dtos.OrderHistoryCursor
import it.polito.cpo.model.IdempotentRequest
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.NonTerminalStateRow
import it.polito.cpo.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val idempotencyRepository: IdempotentRequestRepository
) {

    suspend fun getOrderById(id: UUID): Order? {
        return orderRepository.findById(id)
    }

    suspend fun saveOrder(order: Order): Order {
        return orderRepository.save(order)
    }

    // Race-free money-path transitions (see OrderRepository). Each returns rows affected: 0 means the
    // order was cancelled concurrently (forward transitions) or is not cancellable (cancelIfUnpaid).
    suspend fun markReserving(id: UUID): Long = orderRepository.markReserving(id)

    suspend fun markPaymentPending(
        id: UUID,
        reservationId: String,
        reservationExpiresAt: OffsetDateTime,
    ): Long = orderRepository.markPaymentPending(id, reservationId, reservationExpiresAt)

    suspend fun markConfirmingInventory(id: UUID): Long = orderRepository.markConfirmingInventory(id)

    suspend fun markCompensating(id: UUID, reason: String): Long =
        orderRepository.markCompensating(id, reason)

    suspend fun markAbandoned(id: UUID): Long = orderRepository.markAbandoned(id, ABANDONED_COMPENSATION_REASON)

    suspend fun markConfirmed(id: UUID): Long = orderRepository.markConfirmed(id)

    suspend fun markFailedUnpaid(id: UUID, failureCode: CustomerFailureCode): Long =
        orderRepository.markFailedUnpaid(id, failureCode.name)

    suspend fun markFailedAfterReversal(id: UUID, failureCode: CustomerFailureCode): Long =
        orderRepository.markFailedAfterReversal(id, failureCode.name)

    suspend fun cancelIfUnpaid(id: UUID): Long = orderRepository.cancelIfUnpaid(id)

    // Post-sale refund (ADR-015). See OrderRepository for the compare-and-set predicates.
    suspend fun markRefunding(id: UUID): Long = orderRepository.markRefunding(id)

    suspend fun markRefunded(id: UUID, providerReference: String?): Long =
        orderRepository.markRefunded(id, providerReference)

    /**
     * One page of a customer's own order history, newest first.
     *
     * [userId] is always applied as a predicate — there is no privileged variant of this call, so an
     * `operations` token cannot widen it. Fetches [limit] + 1 rows so the caller can tell whether a
     * further page exists without a second query or a count.
     */
    suspend fun findHistoryPage(
        userId: String,
        status: OrderStatus?,
        cursor: OrderHistoryCursor?,
        limit: Int,
    ): List<Order> {
        val fetch = limit + 1
        val rows = if (cursor == null) {
            orderRepository.findHistoryFirstPage(userId, status?.name, fetch)
        } else {
            orderRepository.findHistoryAfter(userId, status?.name, cursor.createdAt, cursor.id, fetch)
        }
        return rows.toList()
    }

    fun countNonTerminalByState(): Flow<NonTerminalStateRow> = orderRepository.countNonTerminalByState()

    suspend fun findIdempotentRequest(key: String): IdempotentRequest? {
        return idempotencyRepository.findById(key)
    }

    suspend fun saveIdempotentRequest(
        key: String,
        responseBody: String,
        principalId: String,
        operation: String,
        requestFingerprint: String,
    ): IdempotentRequest {
        val request = IdempotentRequest(
            key = key,
            responseBody = responseBody,
            createdAt = LocalDateTime.now(),
            principalId = principalId,
            operation = operation,
            requestFingerprint = requestFingerprint,
        )
        return idempotencyRepository.save(request)
    }

    suspend fun purgeExpiredIdempotency(cutoff: LocalDateTime): Long =
        idempotencyRepository.deleteExpired(cutoff)
}

/**
 * The compensation reason of a checkout an operator abandoned past its seat hold, with its payment
 * outcome unknown (#140). Deliberately not an `InventoryRefusalCode`: Inventory refused nothing. The
 * compensation reads it to know that Payments holding no AUTHORIZED row is a question for an operator
 * (PAYMENT_OUTCOME_UNKNOWN), not a reversal failure — and not proof that no money was taken.
 */
const val ABANDONED_COMPENSATION_REASON = "HOLD_EXPIRED_PAYMENT_UNKNOWN"
