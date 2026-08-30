package it.polito.cpo.repository

import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the customer order-history query against real PostgreSQL.
 *
 * It is an integration test rather than a unit test because every part of it that can break is in
 * the SQL, not in Kotlin:
 *
 *  * the optional status filter binds a **null** parameter. `CAST(:status AS VARCHAR) IS NULL` is
 *    what makes that legal — without the cast Postgres cannot infer the parameter's type and the
 *    statement fails at runtime, not at compile time;
 *  * pagination uses a **row-value comparison**, `(created_at, id) < (:createdAt, :id)`, which is
 *    the part that makes a page boundary exact when two orders share a timestamp;
 *  * `LIMIT :limit` is a bound parameter rather than an interpolated literal.
 *
 * The property under test is the one a customer would notice: paging through the history returns
 * every order exactly once, newest first, and never another customer's order.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.kafka.listener.auto-startup=false"],
)
@Testcontainers
@Import(OrderHistoryQueryIntegrationTest.NoRemoteJwks::class)
class OrderHistoryQueryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    @TestConfiguration
    class NoRemoteJwks {
        @Bean
        @Primary
        fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }

    @Autowired private lateinit var orders: OrderRepository

    private val owner = "user-history-${UUID.randomUUID()}"
    private val stranger = "user-stranger-${UUID.randomUUID()}"

    @Test
    fun `the history is scoped to one principal, newest first`() = runBlocking {
        val base = LocalDateTime.now().withNano(0)
        val mine = (0..4).map { seed(owner, base.minusMinutes(it.toLong())) }
        repeat(3) { seed(stranger, base.minusMinutes(it.toLong())) }

        val page = orders.findHistoryFirstPage(owner, null, 50).toList()

        assertEquals(mine.size, page.size, "another customer's orders leaked into the history")
        assertEquals(mine, page.map { it.id }, "orders are not newest-first")
    }

    @Test
    fun `the status filter is optional and applied in SQL`() = runBlocking {
        val base = LocalDateTime.now().withNano(0)
        val confirmed = seed(owner, base, OrderStatus.CONFIRMED)
        val failed = seed(owner, base.minusMinutes(1), OrderStatus.FAILED)

        // The null bind: this is the case that fails at runtime if the CAST is dropped.
        val unfiltered = orders.findHistoryFirstPage(owner, null, 50).toList().map { it.id }
        assertTrue(unfiltered.containsAll(listOf(confirmed, failed)))

        val onlyConfirmed = orders.findHistoryFirstPage(owner, OrderStatus.CONFIRMED.name, 50).toList()
        assertEquals(listOf(confirmed), onlyConfirmed.map { it.id })
    }

    @Test
    fun `paging returns every order exactly once, including across a timestamp tie`() = runBlocking {
        val base = LocalDateTime.now().withNano(0)

        // Two orders share created_at on purpose: this is what a page boundary keyed on the
        // timestamp alone would either skip or repeat.
        val tied = listOf(seed(owner, base), seed(owner, base))
        val rest = (1..5).map { seed(owner, base.minusMinutes(it.toLong())) }
        val expected = (tied + rest).toSet()

        val seen = mutableListOf<Order>()
        var cursor: Pair<LocalDateTime, UUID>? = null
        val pageSize = 2

        while (true) {
            val rows = cursor.let { c ->
                if (c == null) {
                    orders.findHistoryFirstPage(owner, null, pageSize)
                } else {
                    orders.findHistoryAfter(owner, null, c.first, c.second, pageSize)
                }
            }.toList()

            if (rows.isEmpty()) break
            seen += rows
            cursor = rows.last().let { it.createdAt to it.id }
        }

        // The property that matters: every order came back exactly once.
        assertEquals(expected.size, seen.size, "paging skipped or repeated rows")
        assertEquals(expected, seen.map { it.id }.toSet())

        // And the order a customer perceives is newest-first.
        //
        // The tie-break between the two orders sharing a timestamp is deliberately NOT asserted
        // against a Kotlin re-sort. PostgreSQL compares `uuid` as unsigned bytes, while Java's
        // `UUID.compareTo` compares the most-significant bits as a SIGNED long, so the two disagree
        // for any id whose first byte is >= 0x80. Reimplementing Postgres' collation here would only
        // test the reimplementation. What the pagination actually needs from the tie-break is that it
        // is *total and stable*, which the two assertions above already prove: an unstable one shows
        // up as a skipped or repeated row, not as a different order.
        assertEquals(
            seen.map { it.createdAt },
            seen.map { it.createdAt }.sortedDescending(),
            "history is not newest-first",
        )
    }

    private suspend fun seed(
        userId: String,
        createdAt: LocalDateTime,
        status: OrderStatus = OrderStatus.CONFIRMED,
    ): UUID {
        val id = UUID.randomUUID()
        orders.save(
            Order(
                id = id,
                userId = userId,
                status = status,
                routeId = "route-1",
                seats = "A1",
                travelDate = LocalDate.now().plusDays(3),
                totalAmount = BigDecimal("10.00"),
                createdAt = createdAt,
                recipientEmail = "$userId@example.com",
            ).apply { setAsNew(true) },
        )
        return id
    }
}
