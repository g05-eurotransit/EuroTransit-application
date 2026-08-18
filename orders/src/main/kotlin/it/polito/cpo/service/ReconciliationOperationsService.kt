package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.controller.dtos.RedriveResponse
import it.polito.cpo.controller.dtos.ResolveReconciliationRequest
import it.polito.cpo.controller.dtos.ResolveReconciliationResponse
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.UUID

/**
 * The two operator commands over reconciliation incidents.
 *
 * Neither touches business state. A redrive only re-triggers the durable pipeline by writing a fresh
 * `order-placed` outbox row; the stored `orders.status` is what selects the resume action, so this
 * service contains no knowledge of *which* stage to resume — that stays in the orchestrator's state
 * table. A resolution only closes an investigated incident.
 *
 * "Cannot waive a pending payment reversal or change business state" is structural: this service holds
 * no `PaymentClient` and no `InventoryClient`, and the resolution SQL is filtered to an open
 * INVARIANT_VIOLATION, so there is no reachable path to a reversal or to an order transition.
 */
@Service
class ReconciliationOperationsService(
    private val orderService: OrderService,
    private val reconciliationService: ReconciliationService,
    private val records: ReconciliationRecordRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val txOperator: TransactionalOperator,
) {
    private val logger = LoggerFactory.getLogger(ReconciliationOperationsService::class.java)

    /**
     * Re-triggers the checkout pipeline for an order with an open incident.
     *
     * Returns `202`: the work happens asynchronously in the consumer, exactly as it does for a fresh
     * checkout. Repeating the same idempotency key replays the stored response and writes no second
     * outbox row.
     */
    suspend fun redrive(orderId: UUID, idempotencyKey: String, operatorId: String): RedriveResponse {
        val correlationId = correlationId()
        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        val fingerprint = fingerprint(orderId.toString(), OPERATION_REDRIVE)
        replayed<RedriveResponse>(idempotencyKey, fingerprint)?.let {
            meterRegistry.counter("orders.reconciliation.redrive", "result", "replayed").increment()
            return it
        }

        if (order.status.isTerminal) {
            // The incident is stale: the order converged after it was opened. Close it, then refuse.
            // The close runs in its own committed transaction and the refusal is raised afterwards —
            // throwing from inside the transaction would roll the close back and leave the record
            // open forever.
            val closed = reconciliationService.closeStaleFor(orderId)
            meterRegistry.counter("orders.reconciliation.redrive", "result", "rejected_terminal").increment()
            logger.info("Redrive refused for terminal order {}; closed {} stale incident(s)", orderId, closed)
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_ALREADY_TERMINAL",
                "Order in state ${order.status} is terminal and cannot be redriven",
            )
        }

        val open = records.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).firstOrNull()
            ?: run {
                meterRegistry.counter("orders.reconciliation.redrive", "result", "rejected_no_record").increment()
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "NO_OPEN_RECONCILIATION_RECORD",
                    "Order has no open reconciliation record to redrive",
                )
            }

        val response = RedriveResponse(
            orderId = orderId,
            orderStatus = order.status,
            attemptCount = open.attemptCount + 1,
        )
        txOperator.executeAndAwait {
            orderService.saveIdempotentRequest(
                idempotencyKey,
                objectMapper.writeValueAsString(response),
                operatorId,
                OPERATION_REDRIVE,
                fingerprint,
            )
            outboxEventRepository.save(
                orderOutboxRow(
                    objectMapper,
                    "order-placed",
                    orderId,
                    OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = order.userId),
                ),
            )
            records.touchRedrive(orderId, correlationId)
        }

        meterRegistry.counter("orders.reconciliation.redrive", "result", "accepted").increment()
        logger.info(
            "Redrive accepted for order {} in {} by {} (correlationId={})",
            orderId, order.status, operatorId, correlationId,
        )
        return response
    }

    /**
     * Closes an investigated invariant violation. Requires a terminal order, so the money question is
     * already settled, and a non-empty evidence reference, so the closure is auditable.
     */
    suspend fun resolve(
        orderId: UUID,
        recordId: UUID,
        idempotencyKey: String,
        request: ResolveReconciliationRequest,
        operatorId: String,
    ): ResolveReconciliationResponse {
        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        val evidence = request.evidenceReference.trim()
        if (evidence.isEmpty()) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "EVIDENCE_REFERENCE_REQUIRED",
                "An incident or audit reference is required to resolve a reconciliation record",
            )
        }

        val fingerprint = fingerprint(orderId.toString(), recordId.toString(), evidence)
        replayed<ResolveReconciliationResponse>(idempotencyKey, fingerprint)?.let { return it }

        if (!order.status.isTerminal) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_NOT_TERMINAL",
                "Order in state ${order.status} has not settled; the incident cannot be resolved yet",
            )
        }

        val record = records.findById(recordId)
        // A record belonging to another order is reported as missing rather than forbidden, so the API
        // never reveals that an incident exists elsewhere — the same rule the order reads follow.
        if (record == null || record.orderId != orderId) {
            throw ApiException(
                HttpStatus.NOT_FOUND,
                "RECONCILIATION_RECORD_NOT_FOUND",
                "Reconciliation record not found for this order",
            )
        }
        if (record.kind != ReconciliationKind.INVARIANT_VIOLATION.name) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "RECONCILIATION_KIND_NOT_RESOLVABLE",
                "Only an INVARIANT_VIOLATION is resolved by an operator; ${record.kind} closes automatically",
            )
        }
        if (record.status != ReconciliationStatus.OPEN.name) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "RECONCILIATION_ALREADY_RESOLVED",
                "Reconciliation record is already resolved",
            )
        }

        val resolved = txOperator.executeAndAwait {
            if (records.resolveInvariantViolation(recordId, orderId, operatorId, evidence) == 0L) {
                null
            } else {
                val stored = records.findById(recordId)!!
                val response = ResolveReconciliationResponse(
                    orderId = orderId,
                    recordId = recordId,
                    kind = stored.kind,
                    status = stored.status,
                    resolvedAt = stored.resolvedAt!!,
                )
                orderService.saveIdempotentRequest(
                    idempotencyKey,
                    objectMapper.writeValueAsString(response),
                    operatorId,
                    OPERATION_RESOLVE,
                    fingerprint,
                )
                response
            }
        } ?: throw ApiException(
            HttpStatus.CONFLICT,
            "RECONCILIATION_ALREADY_RESOLVED",
            "Reconciliation record was resolved concurrently",
        )

        meterRegistry.counter(
            "orders.reconciliation.resolved",
            "kind", ReconciliationKind.INVARIANT_VIOLATION.name,
            "resolution", "operator",
        ).increment()
        logger.info("Incident {} on order {} resolved by {} with evidence {}", recordId, orderId, operatorId, evidence)
        return resolved
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Replays a stored result for a repeated key, or rejects the key if it was reused with a different
     * payload — the same contract as the public checkout endpoint (api-design.md, shared headers).
     */
    private suspend inline fun <reified T : Any> replayed(idempotencyKey: String, fingerprint: String): T? {
        val existing = orderService.findIdempotentRequest(idempotencyKey) ?: return null
        if (existing.requestFingerprint != null && existing.requestFingerprint != fingerprint) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key was reused with a different request",
            )
        }
        return objectMapper.readValue(existing.responseBody, T::class.java)
    }

    private suspend fun correlationId(): String =
        currentCoroutineContext()[ReactorContext]?.context
            ?.getOrEmpty<String>(CorrelationId.CONTEXT_KEY)?.orElse(null)
            ?: CorrelationId.generate()

    private fun fingerprint(vararg parts: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val OPERATION_REDRIVE = "reconciliation-redrive"
        const val OPERATION_RESOLVE = "reconciliation-resolve"
    }
}
