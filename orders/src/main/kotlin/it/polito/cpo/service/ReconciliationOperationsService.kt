package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.controller.dtos.AbandonResponse
import it.polito.cpo.controller.dtos.RedriveResponse
import it.polito.cpo.controller.dtos.ResolveReconciliationRequest
import it.polito.cpo.controller.dtos.ResolveReconciliationResponse
import it.polito.cpo.controller.dtos.SettleResponse
import it.polito.cpo.model.OpenReconciliationIncident
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
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
 * The operator commands over reconciliation incidents, and the list an operator chooses from.
 *
 * Only one of them moves an order. A redrive only re-triggers a durable pipeline by writing a fresh
 * outbox row — `order-placed` for the checkout pipeline, `refund-requested` for ADR-015's refund,
 * chosen by which durable process the order is currently in; the stored `orders.status` is what
 * selects the resume action within that process, so this service contains no knowledge of *which*
 * stage resumes. A resolution only closes an investigated incident. An abandonment moves an expired
 * `PAYMENT_PENDING` order into compensation and lets the pipeline settle it (#140); a settlement only
 * records an operator's provider evidence for an unknown payment outcome and re-triggers that pipeline.
 *
 * "Cannot waive a pending payment reversal" is structural: this service holds no `PaymentClient` and no
 * `InventoryClient`, the resolution SQL is filtered to an open INVARIANT_VIOLATION and the settlement SQL
 * to an open PAYMENT_OUTCOME_UNKNOWN, and its one order transition (`markAbandoned`) leads into
 * compensation, never past it.
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
        return auditRejection(MoneyPathAudit.Action.RECONCILIATION_REDRIVE, operatorId, orderId, correlationId) {
            doRedrive(orderId, idempotencyKey, operatorId, correlationId)
        }
    }

    private suspend fun doRedrive(
        orderId: UUID,
        idempotencyKey: String,
        operatorId: String,
        correlationId: String,
    ): RedriveResponse {
        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        val fingerprint = fingerprint(orderId.toString(), OPERATION_REDRIVE)
        replayed<RedriveResponse>(idempotencyKey, fingerprint)?.let {
            meterRegistry.counter("orders.reconciliation.redrive", "result", "replayed").increment()
            MoneyPathAudit.record(
                MoneyPathAudit.Action.RECONCILIATION_REDRIVE, MoneyPathAudit.Outcome.REPLAYED, operatorId, orderId,
                correlationId, ownerId = order.userId,
            )
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
            //
            // One PAYMENT_PENDING order resumes WITHOUT authorizing (ADR-016): a customer-hold order whose
            // payment window was never recorded. Its resume action is the window request, which an
            // expired hold makes Inventory refuse, and the order then fails with no money moved — Payments
            // is only ever called after the window is recorded. Refusing it here would leave abandonment as
            // the only exit, and abandonment ends in PAYMENT_OUTCOME_UNKNOWN: an operator checking the
            // provider for an order Payments never saw.
            val resumesWithoutAuthorizing = order.paymentDeferred && !order.paymentWindowOpened
            val expiresAt = order.reservationExpiresAt
            if (order.status == OrderStatus.PAYMENT_PENDING &&
                !resumesWithoutAuthorizing &&
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
            records.touchRedrive(orderId, fitCorrelationId(correlationId))
        }

        meterRegistry.counter("orders.reconciliation.redrive", "result", "accepted").increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.RECONCILIATION_REDRIVE, MoneyPathAudit.Outcome.ACCEPTED, operatorId, orderId,
            correlationId, ownerId = order.userId,
        )
        logger.info(
            "Redrive accepted for order {} in {} by {} (correlationId={})",
            orderId, order.status, operatorId, correlationId,
        )
        return response
    }

    /**
     * Gives up an expired checkout whose payment outcome is unknown (#140): the one exit for a
     * `PAYMENT_PENDING` order past its seat hold, which the redrive refuses (`RESERVATION_HOLD_EXPIRED`).
     *
     * Moves the order to `COMPENSATING` and re-triggers the pipeline through the outbox, exactly as a
     * redrive does. The compensation then reverses an authorized payment and fails the order; if
     * Payments holds no settled authorization, it parks the order under PAYMENT_OUTCOME_UNKNOWN for
     * [settle], since that does not prove no money was taken. This service still never calls Payments
     * or Inventory itself, and nothing here can authorize.
     */
    suspend fun abandon(orderId: UUID, idempotencyKey: String, operatorId: String): AbandonResponse {
        val correlationId = correlationId()
        return auditRejection(MoneyPathAudit.Action.RECONCILIATION_ABANDON, operatorId, orderId, correlationId) {
            doAbandon(orderId, idempotencyKey, operatorId, correlationId)
        }
    }

    private suspend fun doAbandon(
        orderId: UUID,
        idempotencyKey: String,
        operatorId: String,
        correlationId: String,
    ): AbandonResponse {
        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        val fingerprint = fingerprint(orderId.toString(), OPERATION_ABANDON)
        replayed<AbandonResponse>(idempotencyKey, fingerprint)?.let {
            meterRegistry.counter("orders.reconciliation.abandon", "result", "replayed").increment()
            MoneyPathAudit.record(
                MoneyPathAudit.Action.RECONCILIATION_ABANDON, MoneyPathAudit.Outcome.REPLAYED, operatorId, orderId,
                correlationId, ownerId = order.userId,
            )
            return it
        }

        if (order.status != OrderStatus.PAYMENT_PENDING) {
            meterRegistry.counter("orders.reconciliation.abandon", "result", "rejected_state").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_NOT_ABANDONABLE",
                "Only a PAYMENT_PENDING order past its seat hold can be abandoned; this one is ${order.status}",
            )
        }
        val expiresAt = order.reservationExpiresAt
        if (expiresAt == null || !expiresAt.toInstant().isBefore(Instant.now())) {
            // While the hold runs, the redrive is the answer: the sale can still complete.
            meterRegistry.counter("orders.reconciliation.abandon", "result", "rejected_hold_live").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "RESERVATION_HOLD_NOT_EXPIRED",
                "The seat hold for this order runs until $expiresAt; redrive it instead",
            )
        }
        val open = records.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).firstOrNull()
        if (open == null) {
            meterRegistry.counter("orders.reconciliation.abandon", "result", "rejected_no_record").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "NO_OPEN_RECONCILIATION_RECORD",
                "Order has no open reconciliation record to act on",
            )
        }

        val response = AbandonResponse(orderId = orderId, orderStatus = OrderStatus.COMPENSATING)
        val abandoned = txOperator.executeAndAwait {
            // The expiry is re-checked in SQL, against the database clock. Losing here means the order
            // moved since it was read, or the database clock does not yet see the hold as expired.
            // Nothing has been written when it does, so the transaction commits nothing.
            if (orderService.markAbandoned(orderId) == 0L) return@executeAndAwait false
            orderService.saveIdempotentRequest(
                idempotencyKey,
                objectMapper.writeValueAsString(response),
                operatorId,
                OPERATION_ABANDON,
                fingerprint,
            )
            val event = OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = order.userId)
            outboxEventRepository.save(orderOutbox.row("order-placed", orderId, event))
            records.touchRedrive(orderId, fitCorrelationId(correlationId))
            true
        }
        if (abandoned != true) {
            meterRegistry.counter("orders.reconciliation.abandon", "result", "rejected_changed").increment()
            val now = orderService.getOrderById(orderId)?.status
            throw if (now != OrderStatus.PAYMENT_PENDING) {
                ApiException(
                    HttpStatus.CONFLICT,
                    "ORDER_NOT_ABANDONABLE",
                    "The order moved to $now while it was being abandoned",
                )
            } else {
                ApiException(
                    HttpStatus.CONFLICT,
                    "RESERVATION_HOLD_NOT_EXPIRED",
                    "The seat hold has not expired yet by the database clock; redrive instead",
                )
            }
        }

        meterRegistry.counter("orders.reconciliation.abandon", "result", "accepted").increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.RECONCILIATION_ABANDON, MoneyPathAudit.Outcome.ACCEPTED, operatorId, orderId,
            correlationId, ownerId = order.userId,
        )
        logger.info(
            "Order {} abandoned by {} past its hold ({}); compensating (correlationId={}, incident attempt {})",
            orderId, operatorId, expiresAt, correlationId, open.attemptCount + 1,
        )
        return response
    }

    /**
     * Settles the unknown payment outcome of an abandoned checkout (#140) with provider evidence: the
     * operator attests that the provider holds no money for this order — never charged, or refunded
     * there by hand. Closes the PAYMENT_OUTCOME_UNKNOWN incident with that evidence and re-triggers the
     * pipeline, which then fails the order as any finished compensation does (notification, metrics,
     * audit). The order itself is moved by the pipeline, not here.
     */
    suspend fun settle(
        orderId: UUID,
        recordId: UUID,
        idempotencyKey: String,
        request: ResolveReconciliationRequest,
        operatorId: String,
    ): SettleResponse {
        val correlationId = correlationId()
        return auditRejection(MoneyPathAudit.Action.RECONCILIATION_SETTLE, operatorId, orderId, correlationId) {
            try {
                doSettle(orderId, recordId, idempotencyKey, request, operatorId, correlationId)
            } catch (e: ApiException) {
                meterRegistry.counter("orders.reconciliation.settle", "result", "rejected").increment()
                throw e
            }
        }
    }

    private suspend fun doSettle(
        orderId: UUID,
        recordId: UUID,
        idempotencyKey: String,
        request: ResolveReconciliationRequest,
        operatorId: String,
        correlationId: String,
    ): SettleResponse {
        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        val evidence = request.evidenceReference.trim()
        if (evidence.isEmpty()) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "EVIDENCE_REFERENCE_REQUIRED",
                "A provider reference is required to settle an unknown payment outcome",
            )
        }

        val fingerprint = fingerprint(orderId.toString(), recordId.toString(), evidence, OPERATION_SETTLE)
        replayed<SettleResponse>(idempotencyKey, fingerprint)?.let {
            meterRegistry.counter("orders.reconciliation.settle", "result", "replayed").increment()
            MoneyPathAudit.record(
                MoneyPathAudit.Action.RECONCILIATION_SETTLE, MoneyPathAudit.Outcome.REPLAYED, operatorId, orderId,
                correlationId, ownerId = order.userId,
            )
            return it
        }

        val record = records.findById(recordId)
        // Another order's record is reported as missing, as in resolve.
        if (record == null || record.orderId != orderId) {
            throw ApiException(
                HttpStatus.NOT_FOUND,
                "RECONCILIATION_RECORD_NOT_FOUND",
                "Reconciliation record not found for this order",
            )
        }
        if (record.kind != ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN.name) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "RECONCILIATION_KIND_NOT_SETTLEABLE",
                "Only a PAYMENT_OUTCOME_UNKNOWN is settled with provider evidence; this one is ${record.kind}",
            )
        }
        if (record.status != ReconciliationStatus.OPEN.name) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "RECONCILIATION_ALREADY_RESOLVED",
                "Reconciliation record is already resolved",
            )
        }
        if (order.status != OrderStatus.COMPENSATING) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_NOT_SETTLEABLE",
                "Order in state ${order.status} is not waiting on an unknown payment outcome",
            )
        }

        val response = SettleResponse(orderId = orderId, recordId = recordId, orderStatus = order.status)
        txOperator.executeAndAwait {
            if (records.settlePaymentOutcomeUnknown(recordId, orderId, operatorId, evidence) == 0L) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "RECONCILIATION_ALREADY_RESOLVED",
                    "Reconciliation record was resolved concurrently",
                )
            }
            orderService.saveIdempotentRequest(
                idempotencyKey,
                objectMapper.writeValueAsString(response),
                operatorId,
                OPERATION_SETTLE,
                fingerprint,
            )
            val event = OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = order.userId)
            outboxEventRepository.save(orderOutbox.row("order-placed", orderId, event))
        }

        meterRegistry.counter("orders.reconciliation.settle", "result", "accepted").increment()
        meterRegistry.counter(
            "orders.reconciliation.resolved",
            "kind", ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN.name,
            "resolution", "operator",
        ).increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.RECONCILIATION_SETTLE, MoneyPathAudit.Outcome.RESOLVED, operatorId, orderId,
            correlationId, ownerId = order.userId,
        )
        logger.info("Unknown payment outcome of order {} settled by {} with evidence {}", orderId, operatorId, evidence)
        return response
    }

    /**
     * The open incidents, most urgent first (see the repository query), at most [limit] of them (clamped to 1..[MAX_LIST_LIMIT]). Read
     * only, so neither idempotency nor an audit line: it decides nothing.
     */
    suspend fun listOpen(limit: Int): List<OpenReconciliationIncident> =
        records.findOpenIncidents(limit.coerceIn(1, MAX_LIST_LIMIT)).toList()

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
        val correlationId = correlationId()
        return auditRejection(MoneyPathAudit.Action.RECONCILIATION_RESOLVE, operatorId, orderId, correlationId) {
            doResolve(orderId, recordId, idempotencyKey, request, operatorId, correlationId)
        }
    }

    private suspend fun doResolve(
        orderId: UUID,
        recordId: UUID,
        idempotencyKey: String,
        request: ResolveReconciliationRequest,
        operatorId: String,
        correlationId: String,
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
        replayed<ResolveReconciliationResponse>(idempotencyKey, fingerprint)?.let {
            MoneyPathAudit.record(
                MoneyPathAudit.Action.RECONCILIATION_RESOLVE, MoneyPathAudit.Outcome.REPLAYED, operatorId, orderId,
                correlationId, ownerId = order.userId,
            )
            return it
        }

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
            val how = if (record.kind == ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN.name) {
                "is settled with provider evidence (.../settle)"
            } else {
                "closes automatically"
            }
            throw ApiException(
                HttpStatus.CONFLICT,
                "RECONCILIATION_KIND_NOT_RESOLVABLE",
                "Only an INVARIANT_VIOLATION is resolved by an operator; ${record.kind} $how",
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
        MoneyPathAudit.record(
            MoneyPathAudit.Action.RECONCILIATION_RESOLVE, MoneyPathAudit.Outcome.RESOLVED, operatorId, orderId,
            correlationId, ownerId = order.userId,
        )
        logger.info("Incident {} on order {} resolved by {} with evidence {}", recordId, orderId, operatorId, evidence)
        return resolved
    }

    /** Audits every refusal of an operator action under its own code, then lets it propagate. */
    private suspend fun <T> auditRejection(
        action: MoneyPathAudit.Action,
        operatorId: String,
        orderId: UUID,
        correlationId: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: ApiException) {
        MoneyPathAudit.record(action, MoneyPathAudit.Outcome.REJECTED, operatorId, orderId, correlationId, reason = e.code)
        throw e
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
        const val OPERATION_ABANDON = "reconciliation-abandon"
        const val OPERATION_SETTLE = "reconciliation-settle"
        const val MAX_LIST_LIMIT = 500
    }
}
