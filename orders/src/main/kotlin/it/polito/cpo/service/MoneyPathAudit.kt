package it.polito.cpo.service

import it.polito.cpo.observability.CorrelationId
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * The money-path audit log (DoD §5.9): one line per decision that moves or refuses money or a seat,
 * carrying who acted, on which order, with what outcome, under which correlation id.
 *
 * Every line goes to the logger named [LOGGER_NAME], so the audit trail is one filter on
 * `log.logger` away from the rest of the service's logs. The fields are SLF4J key-value pairs, which
 * the ECS encoder writes as JSON members; the correlation id is the MDC entry every other line
 * already carries, never a second key — the same name twice would be a duplicate JSON member.
 *
 * The signature takes identifiers and closed-vocabulary outcomes only — never an order, a request or
 * an event — so a payment token, an email address or an amount cannot reach the line by accident
 * (`observability-conventions.md`, "Must NOT be logged").
 */
object MoneyPathAudit {

    const val LOGGER_NAME = "audit"

    private val logger = LoggerFactory.getLogger(LOGGER_NAME)

    enum class Action(val value: String) {
        CHECKOUT("checkout"),
        CHECKOUT_PIPELINE("checkout.pipeline"),
        CANCEL("cancel"),
        REFUND("refund"),
        REFUND_PIPELINE("refund.pipeline"),
        RECONCILIATION_REDRIVE("reconciliation.redrive"),
        RECONCILIATION_RESOLVE("reconciliation.resolve"),
        RECONCILIATION_ABANDON("reconciliation.abandon"),
        RECONCILIATION_SETTLE("reconciliation.settle"),
    }

    enum class Outcome(val value: String) {
        ACCEPTED("accepted"),
        REPLAYED("replayed"),
        REJECTED("rejected"),
        CONFIRMED("confirmed"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        REFUNDED("refunded"),
        RESOLVED("resolved"),
    }

    /**
     * Writes one audit line.
     *
     * [principalId] is whoever the decision is attributed to: the caller on an HTTP action, the order's
     * owner on the asynchronous pipelines that act on their behalf, the operator on a reconciliation
     * action. [ownerId] is added only when it differs, so a staff refund or an operator redrive still
     * names the customer whose order it was. [orderId] is null only for a checkout refused before an
     * order existed. [reason] is the error code or failure label, never free text.
     */
    fun record(
        action: Action,
        outcome: Outcome,
        principalId: String,
        orderId: UUID?,
        correlationId: String?,
        reason: String? = null,
        ownerId: String? = null,
    ) {
        withCorrelationId(correlationId) {
            var line = logger.atInfo()
                .addKeyValue("action", action.value)
                .addKeyValue("outcome", outcome.value)
                .addKeyValue("principalId", principalId)
                .addKeyValue("orderId", orderId?.toString())
            if (reason != null) line = line.addKeyValue("reason", reason)
            if (ownerId != null && ownerId != principalId) line = line.addKeyValue("ownerId", ownerId)
            line.log("audit {} {}", action.value, outcome.value)
        }
    }

    /**
     * Runs [block] with [correlationId] in the MDC, then puts back whatever was there. On the HTTP path
     * the id lives in the Reactor context and is not guaranteed to be in the MDC of a coroutine; on the
     * consumer path the listener already put it there, and removing it afterwards would strip it from
     * every later line of that delivery.
     */
    private inline fun withCorrelationId(correlationId: String?, block: () -> Unit) {
        val previous = MDC.get(CorrelationId.CONTEXT_KEY)
        if (correlationId == null || correlationId == previous) {
            block()
            return
        }
        MDC.put(CorrelationId.CONTEXT_KEY, correlationId)
        try {
            block()
        } finally {
            if (previous == null) MDC.remove(CorrelationId.CONTEXT_KEY) else MDC.put(CorrelationId.CONTEXT_KEY, previous)
        }
    }
}
