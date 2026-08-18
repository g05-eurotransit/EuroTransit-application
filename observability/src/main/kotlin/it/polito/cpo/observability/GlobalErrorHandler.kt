package it.polito.cpo.observability

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * Maps exceptions onto the shared {code, message, correlationId} error model.
 */
@RestControllerAdvice
class GlobalErrorHandler {

    private val log = LoggerFactory.getLogger(GlobalErrorHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException, exchange: ServerWebExchange): ResponseEntity<ErrorResponse> {
        val body = ErrorResponse(ex.code, ex.message, exchange.correlationId(), ex.details)
        if (ex.status.is5xxServerError) {
            log.error("Request failed: {} {}", ex.code, ex.message, ex)
        } else {
            log.info("Request rejected: {} {}", ex.code, ex.message)
        }
        return ResponseEntity.status(ex.status).body(body)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException, exchange: ServerWebExchange): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val body = ErrorResponse(status.name, ex.reason ?: status.reasonPhrase, exchange.correlationId())
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(Throwable::class)
    fun handleUnexpected(ex: Throwable, exchange: ServerWebExchange): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error", ex)
        val body = ErrorResponse(
            code = "INTERNAL_ERROR",
            message = "An unexpected error occurred",
            correlationId = exchange.correlationId(),
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }

    private fun ServerWebExchange.correlationId(): String? =
        attributes[CorrelationId.EXCHANGE_ATTRIBUTE] as? String
}
