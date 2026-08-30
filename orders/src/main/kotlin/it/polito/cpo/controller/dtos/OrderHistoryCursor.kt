package it.polito.cpo.controller.dtos

import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * The keyset pagination cursor for `GET /api/orders`: the sort key of the last row of a page.
 *
 * It is encoded rather than exposed as two query parameters so that the pagination key stays an
 * implementation detail of the endpoint. A caller that starts constructing `createdAt`/`id` pairs by
 * hand pins the sort order into client code, and changing it later then breaks them silently.
 *
 * There is nothing secret in it — it is the caller's own order timestamp and id, both of which they
 * already have — so the encoding is Base64, not a signature. It carries no authorization: the
 * principal filter is applied server-side from the JWT on every page, so replaying someone else's
 * cursor still returns only the caller's own orders.
 */
data class OrderHistoryCursor(val createdAt: LocalDateTime, val id: UUID) {

    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$createdAt|$id".toByteArray())

    companion object {

        /**
         * Decodes a cursor, returning null when it is not one. The caller turns that into a `400`:
         * a malformed cursor is a bad request, and silently restarting from the first page would
         * make a client loop over the same page forever.
         */
        fun decode(raw: String): OrderHistoryCursor? = try {
            val parts = String(Base64.getUrlDecoder().decode(raw)).split("|")
            if (parts.size != 2) {
                null
            } else {
                OrderHistoryCursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1]))
            }
        } catch (_: IllegalArgumentException) {
            // Covers both a non-Base64 string and a malformed UUID.
            null
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * One page of the customer's order history.
 *
 * [nextCursor] is null on the last page. It is present whenever a further page *may* exist, which is
 * decided by fetching one row beyond the page size rather than by counting: a `COUNT(*)` over the
 * money-path table on every page request is a cost the customer history does not justify.
 */
data class OrderPage(
    val orders: List<OrderView>,
    val nextCursor: String?,
)
