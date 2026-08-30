package it.polito.cpo.service

import it.polito.cpo.model.IdempotentRequest
import it.polito.cpo.model.Order
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.NonTerminalStateRow
import it.polito.cpo.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.time.LocalDateTime
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

    suspend fun markPaymentPending(id: UUID, reservationId: String): Long =
        orderRepository.markPaymentPending(id, reservationId)

    suspend fun markConfirmingInventory(id: UUID): Long = orderRepository.markConfirmingInventory(id)

    suspend fun markCompensating(id: UUID, reason: String): Long =
        orderRepository.markCompensating(id, reason)

    suspend fun markConfirmed(id: UUID): Long = orderRepository.markConfirmed(id)

    suspend fun markFailedUnpaid(id: UUID, failureCode: CustomerFailureCode): Long =
        orderRepository.markFailedUnpaid(id, failureCode.name)

    suspend fun markFailedAfterReversal(id: UUID, failureCode: CustomerFailureCode): Long =
        orderRepository.markFailedAfterReversal(id, failureCode.name)

    suspend fun cancelIfUnpaid(id: UUID): Long = orderRepository.cancelIfUnpaid(id)

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
