package it.polito.cpo.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import it.polito.cpo.observability.CorrelationId
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Captures the lines written to the [MoneyPathAudit] logger while open. Use with `use { }` so the
 * appender is always detached, even when the test fails.
 */
class AuditLogCapture : AutoCloseable {

    /** One captured audit line: its key-value fields, its correlation id from the MDC, and its message. */
    data class Line(val fields: Map<String, String?>, val correlationId: String?, val message: String) {
        val action get() = fields["action"]
        val outcome get() = fields["outcome"]
    }

    private val logger = LoggerFactory.getLogger(MoneyPathAudit.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    val lines: List<Line>
        get() = appender.list.toList().map { event ->
            Line(
                fields = event.keyValuePairs.orEmpty().associate { it.key to it.value?.toString() },
                correlationId = event.mdcPropertyMap[CorrelationId.CONTEXT_KEY],
                message = event.formattedMessage,
            )
        }

    /** The lines about [orderId] — the integration tests share the logger with consumer threads. */
    fun forOrder(orderId: UUID): List<Line> = lines.filter { it.fields["orderId"] == orderId.toString() }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
