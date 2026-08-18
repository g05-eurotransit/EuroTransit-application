package it.polito.cpo.observability

/**
 * Error body shared by all EuroTransit services, as frozen in docs/design/api-design.md:
 * `code`, `message`, and `correlationId` are mandatory; `details` is optional
 * machine-readable validation data.
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String?,
    val details: Map<String, Any?>? = null,
)
