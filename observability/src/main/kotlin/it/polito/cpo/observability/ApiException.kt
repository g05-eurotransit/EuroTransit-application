package it.polito.cpo.observability

import org.springframework.http.HttpStatus

/**
 * Business exception mapped by [GlobalErrorHandler] onto the shared error model.
 *
 * Use the frozen status mapping: 400 malformed, 401 unauthenticated, 403 unauthorized,
 * 404 missing/not visible, 409 idempotency or state conflict, 422 invalid business
 * action, 503 dependency unavailable or circuit open.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any?>? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
