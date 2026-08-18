package it.polito.cpo.event

/**
 * A Kafka record whose payload cannot be parsed at all.
 *
 * Distinguished from a processing failure because the two need opposite handling: a processing failure
 * may well succeed on the next attempt, while a payload that does not parse will never parse. This is
 * registered as non-retryable so the record goes straight to the dead-letter topic instead of burning
 * the retry budget on a certainty.
 */
class PoisonEventException(message: String, cause: Throwable) : RuntimeException(message, cause)
