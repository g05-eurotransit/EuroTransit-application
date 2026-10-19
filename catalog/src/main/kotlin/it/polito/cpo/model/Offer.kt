package it.polito.cpo.model

import it.polito.cpo.contracts.catalog.RefundPolicy
import java.math.BigDecimal

data class Offer(
    val offerId: String,
    val routeId: String,
    val fareClass: String,
    val price: BigDecimal,
    val currency: String,
    val refundPolicy: RefundPolicy,
    // Monotonic marker over the refund terms. Orders persists it with the offer id at checkout, so
    // editing an offer later cannot retroactively rewrite what a past customer accepted. Bump it on
    // any change to refundPolicy.
    val termsVersion: Int,
    // Optional customer-facing information appended to the rendered refund sentence.
    // Only describe capabilities the system supports.
    val displayNote: String? = null,
) {
    /**
     * The customer-facing conditions sentence, rendered from [refundPolicy].
     *
     * Derived, never stored: the policy is the source of truth and the prose follows it. Storing
     * both would let them drift, and the drift would show up as a refund promise the system does
     * not honour.
     */
    val conditions: String
        get() = listOfNotNull(refundPolicy.render(), displayNote).joinToString(" ")
}

private fun RefundPolicy.render(): String {
    if (!refundable) return "Non-refundable."
    val cutoff = cutoffMinutesBeforeDeparture
    return if (cutoff == null || cutoff == 0) {
        "Refundable until departure."
    } else {
        "Refundable until ${humanize(cutoff)} before departure."
    }
}

private fun humanize(minutes: Int): String = when {
    minutes % 60 != 0 -> plural(minutes, "minute")
    else -> plural(minutes / 60, "hour")
}

private fun plural(value: Int, unit: String): String =
    if (value == 1) "$value $unit" else "$value ${unit}s"
