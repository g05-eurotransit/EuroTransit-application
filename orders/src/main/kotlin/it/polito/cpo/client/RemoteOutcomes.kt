package it.polito.cpo.client

import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.core.publisher.Mono

/**
 * Permissive decode target for a non-2xx body. EuroTransit puts three different shapes on the wire
 * and a money-path client must read all of them:
 *
 *  * `ErrorResponse` (`{code, message, correlationId, details}`) from `ApiException`, the frozen
 *    error model in docs/design/api-design.md;
 *  * `ReservationResponse` (`{reservationId, status, expiresAt}`) on the 409 that Inventory returns
 *    when the requested seats are not available;
 *  * `PaymentResponse` / `PaymentRefundResponse` (`{status, errorCode}`) from Payments, which does
 *    not use the shared error model.
 *
 * Every field is nullable so one decode covers all three; Boot's Jackson ignores unknown properties.
 */
internal data class RemoteFailureBody(
    val code: String? = null,
    val message: String? = null,
    val status: String? = null,
    val errorCode: String? = null,
) {
    /** The machine-readable refusal code, whichever field carried it. */
    val refusalCode: String?
        get() = code ?: status
}

/**
 * A DEFINITIVE, non-retryable business refusal decoded from a 4xx. Raised inside the WebClient chain
 * so `onStatus` can read the [ClientResponse] while it is still open, and converted to a sealed
 * outcome immediately outside the resilience operators — it never escapes the client package.
 *
 * Must be listed in `ResilienceConfiguration.ignoreExceptions`: a refusal on the merits is not a
 * dependency failure and must not open the circuit breaker.
 */
internal class DefinitiveRefusal(
    val statusCode: HttpStatusCode,
    val code: String,
    val detail: String?,
) : RuntimeException("$statusCode $code${detail?.let { ": $it" } ?: ""}")

/**
 * Maps a 4xx response to a [DefinitiveRefusal].
 *
 * A 4xx with no decodable code is deliberately NOT a refusal: it means the request never reached a
 * business decision (a broken service credential, a contract drift), and converting that into a
 * business outcome would let a deployment bug silently compensate a paid checkout. Falling back to
 * the standard `WebClientResponseException` keeps it classified as an unknown outcome.
 */
internal fun ClientResponse.asDefinitiveRefusal(): Mono<Throwable> =
    bodyToMono(RemoteFailureBody::class.java)
        .mapNotNull<Throwable> { body ->
            body.refusalCode?.let { code ->
                DefinitiveRefusal(statusCode(), code, body.message ?: body.errorCode)
            }
        }
        .switchIfEmpty(Mono.defer { createException().cast(Throwable::class.java) })
        .onErrorResume { Mono.defer { createException().cast(Throwable::class.java) } }
