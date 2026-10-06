package it.polito.cpo.controller

import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.observability.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

/** Resource failures only; constraints, business refusals and programming defects retain their semantics. */
@RestControllerAdvice(basePackages = ["it.polito.cpo.controller"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DependencyErrorHandler {
    @ExceptionHandler(DataAccessResourceFailureException::class)
    fun resourceFailure(failure: DataAccessResourceFailureException, exchange: ServerWebExchange): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(
            "CHECKOUT_UNAVAILABLE", "Checkout is temporarily unavailable",
            exchange.attributes[CorrelationId.EXCHANGE_ATTRIBUTE] as? String,
        ))
}
