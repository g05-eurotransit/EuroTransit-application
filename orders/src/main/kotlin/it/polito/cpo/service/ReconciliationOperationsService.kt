package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.controller.dtos.RedriveResponse
import it.polito.cpo.controller.dtos.ResolveReconciliationRequest
import it.polito.cpo.controller.dtos.ResolveReconciliationResponse
import it.polito.cpo.model.OrderStatus
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
import java.time.Instant
import java.util.UUID

/**
 * The two operator commands over reconciliation incidents.
 *
 * Neither touches business state. A redrive only re-triggers a durable pipeline by writing a fresh
 * outbox row — `order-placed` for the checkout pipeline, `refund-requested` for ADR-015's refund,
 * chosen by which durable process the order is currently in; the stored `orders.status` is what
 * selects the resume action within that process, so this service contains no knowledge of *which*
 * stage resumes. A resolution only closes an investigated incident.
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
    private val orderOutbox: OrderOutbox,
) {
    private val logger = LoggerFactory.getLogger(ReconciliationOperationsService::class.java)

    /**
     * Re-triggers the durable pipeline the order is currently in, for an order with an open incident.
     *
     * Returns `202`: the work happens asynchronously in the consumer, exactly as it does for a fresh
     * checkout or a fresh refund request. Repeating the same idempotency key replays the stored
     * response and writes no second outbox row.
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

        // REFUNDING answers `true` from OrderStatus.isTerminal, but only from the CHECKOUT consumer's
        // point of view (see the KDoc on that state) — ADR-015's own refund has not converged. Taking
        // the generic terminal branch below for a REFUNDING order would call closeStaleFor, which now
        // also closes SEAT_RETURN_FAILURE and PAYMENT_REVERSAL_FAILURE — silently destroying the very
        // incident this endpoint exists to retry, with the gauge dropping to zero and the seat left
        // SOLD forever. REFUNDING is therefore resolved to its own outbox event before the generic
        // terminal check ever runs, and nothing is closed: the redrive re-emits `refund-requested` and
        // lets RefundOrchestrator's own consumer resume from the durable REFUNDING row, exactly as the
        // checkout branch below resumes from whatever phase marker the order is holding.
        val (outboxType, event) = if (order.status == OrderStatus.REFUNDING) {
            "refund-requested" to RefundRequestedEvent(
                correlationId = correlationId, orderId = orderId, principalId = order.userId,
            )
        } else if (order.status.isTerminal) {
            // The incident is stale: the order converged after it was opened. Close it, then refuse.
            // The close runs in its own committed transaction and the refusal is raised afterwards —
            // throwing from inside the transaction would roll the close back and leave the record
            // open forever. Correctly reachable now only for a genuinely terminal state (CONFIRMED,
            // FAILED, CANCELLED, REFUNDED) since REFUNDING was resolved above.
            val closed = reconciliationService.closeStaleFor(orderId)
            meterRegistry.counter("orders.reconciliation.redrive", "result", "rejected_terminal").increment()
            logger.info("Redrive refused for terminal order {}; closed {} stale incident(s)", orderId, closed)
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_ALREADY_TERMINAL",
                "Order in state ${order.status} is terminal and cannot be redriven",
            )
        } else {
            // Refuse a redrive that would authorize a payment against a hold Inventory has already
            // expired. Scoped to PAYMENT_PENDING because that is the only phase whose resume action is
            // `stageAuthorize` (CheckoutOrchestrator.runStage), and therefore the only one where a
            // redrive spends money. The other phases must stay redrivable: ACCEPTED/RESERVING re-take
            // the hold, CONFIRMING_INVENTORY drives the order to the resolution it needs (Inventory
            // refuses with RESERVATION_EXPIRED and the pipeline compensates), and COMPENSATING finishes
            // a reversal that has nothing to do with the hold. Refusing those would strand the order
            // rather than protect it.
            //
            // The same comparison is deliberately NOT made inside `stageAuthorize`. There the deadline
            // is seconds away, the instant comes from Inventory's database clock, and Inventory is the
            // only authority on whether a hold is still live (invariant 4) — comparing it against
            // Orders' clock at that boundary would throw away sales Inventory would still honour. By
            // redrive time the deadline is long past, the ambiguity is gone, and the operator gets an
            // honest refusal instead of triggering a charge and its reversal with one click.
            val expiresAt = order.reservationExpiresAt
            if (order.status == OrderStatus.PAYMENT_PENDING &&
                expiresAt != null &&
                expiresAt.toInstant().isBefore(Instant.now())
            ) {
                // The incident stays open: the order still needs an operator, and closing the record
                // here would hide it exactly as the REFUNDING bug above did.
                meterRegistry.counter("orders.reconciliation.redrive", "result", "rejected_hold_expired").increment()
                logger.info("Redrive refused for order {}: seat hold expired at {}", orderId, expiresAt)
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "RESERVATION_HOLD_EXPIRED",
                    "The seat hold for this order expired at $expiresAt; redriving it would authorize a " +
                        "payment Inventory can no longer confirm",
                )
            }
            "order-placed" to OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = order.userId)
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
            outboxEventRepository.save(orderOutbox.row(outboxType, orderId, event))
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
     * Closes an investigated invariant violation. Requires an order whose money question is already
     * settled, and a non-empty evidence reference, so the closure is auditable.
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

        // `isTerminal` answers a narrower question than this gate needs: "may the CHECKOUT consumer
        // acknowledge a redelivery without a remote call?" — not "is the money question settled?".
        // REFUNDING answers `true` to the first and `false` to the second, so it must be excluded
        // explicitly, exactly as `redrive` excludes it above and for the same reason: resolving away
        // the incident on an order whose reversal or seat return is still outstanding would close the
        // only record saying so. Not reachable today — this method is filtered to INVARIANT_VIOLATION,
        // written only by `enterCompensating`, and no order carrying one can reach CONFIRMED and hence
        // REFUNDING — but the predicate must not depend on that remaining true.
        if (!order.status.isTerminal || order.status == OrderStatus.REFUNDING) {
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
