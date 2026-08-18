package it.polito.cpo.model

import java.math.BigDecimal

data class Offer(
    val offerId: String,
    val routeId: String,
    val fareClass: String,
    val price: BigDecimal,
    val currency: String,
    val conditions: String,
)
