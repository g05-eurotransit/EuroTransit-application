package it.polito.cpo.observability

import java.util.UUID

/**
 * Shared correlation constants for the EuroTransit money path.
 *
 * Every service accepts an inbound [HEADER], or generates a new value when absent,
 * echoes it on the response, logs it under the MDC key [CONTEXT_KEY], and propagates
 * it on outgoing WebClient calls together with [SERVICE_NAME_HEADER].
 */
object CorrelationId {
    const val HEADER = "X-Correlation-Id"
    const val CONTEXT_KEY = "correlationId"
    const val SERVICE_NAME_HEADER = "X-Service-Name"

    /** Exchange attribute where the resolved correlation id is stored for the request. */
    const val EXCHANGE_ATTRIBUTE = "it.polito.cpo.observability.correlationId"

    fun generate(): String = UUID.randomUUID().toString()
}
