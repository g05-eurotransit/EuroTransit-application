package it.polito.cpo.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import it.polito.cpo.observability.ApiException
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual PostgreSQL transactions, two gate instances sharing no JVM state. */
@Testcontainers
class CheckoutAdmissionIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }
    private val factory by lazy {
        ConnectionFactories.get("r2dbc:postgresql://${postgres.username}:${postgres.password}@${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}")
    }
    private val db by lazy { DatabaseClient.create(factory) }
    private fun gate(cap: Int = 2, concurrency: Int = 100) = CheckoutAdmission(db, R2dbcTransactionManager(factory), SimpleMeterRegistry(), cap, concurrency, 100)

    @BeforeEach
    fun setup() = runBlocking {
        db.sql("CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY, status TEXT NOT NULL)").then().awaitSingleOrNull()
        db.sql("CREATE TABLE IF NOT EXISTS admission_events (order_id TEXT PRIMARY KEY)").then().awaitSingleOrNull()
        db.sql("DELETE FROM admission_events").then().awaitSingleOrNull()
        db.sql("DELETE FROM orders").then().awaitSingleOrNull()
        Unit
    }
    private suspend fun replay(id: String): String? = db.sql("SELECT id FROM orders WHERE id=:id").bind("id", id).map { r, _ -> r.get("id", String::class.java)!! }.one().awaitSingleOrNull()
    private suspend fun persist(id: String): String {
        db.sql("INSERT INTO orders VALUES (:id,'ACCEPTED')").bind("id",id).then().awaitSingleOrNull()
        db.sql("INSERT INTO admission_events VALUES (:id)").bind("id",id).then().awaitSingleOrNull()
        return id
    }
    private suspend fun count(table: String): Long = db.sql("SELECT COUNT(*) AS n FROM $table").map { r, _ -> (r.get("n") as Number).toLong() }.one().awaitSingle()

    @Test fun `cross replica admission cannot exceed outstanding cap`() = runBlocking {
        val replicas = listOf(gate(), gate())
        val results = coroutineScope { (0..11).map { n -> async {
            try { replicas[n % 2].admit({replay("$n")}, {persist("$n")}); true }
            catch (e: ApiException) { assertEquals(429,e.status.value()); false }
        }}.awaitAll() }
        assertEquals(2,results.count { it }); assertEquals(2,count("orders")); assertEquals(2,count("admission_events"))
    }
    @Test fun `simultaneous same key persists once and replay bypasses full cap`() = runBlocking {
        val replicas = listOf(gate(1),gate(1))
        val results = coroutineScope { (0..3).map { n -> async { replicas[n % 2].admit({replay("same")},{persist("same")}) } }.awaitAll() }
        assertTrue(results.all { it == "same" });assertEquals(1,count("orders"));assertEquals(1,count("admission_events"))
    }
    @Test fun `terminal states release capacity refunds do not consume checkout capacity`() = runBlocking {
        val gate = gate(1)
        gate.admit({null},{persist("one")})
        db.sql("UPDATE orders SET status='CONFIRMED'").then().awaitSingleOrNull()
        gate.admit({null},{persist("two")})
        db.sql("UPDATE orders SET status='REFUNDING'").then().awaitSingleOrNull()
        assertEquals("three",gate.admit({null},{persist("three")}))
    }
    @Test fun `lock timeout rolls back and subsequent admission remains usable`() = runBlocking {
        DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { connection ->
            connection.autoCommit=false
            connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(697430123)") }
            try { gate().admit({null},{persist("blocked")}); error("expected refusal") }
            catch(e:ApiException) { assertEquals(429,e.status.value()) }
            assertEquals(0,count("orders"));connection.rollback()
        }
        assertEquals("after",gate().admit({null},{persist("after")}))
    }
    @Test fun `cancellation rolls back all writes and releases transaction lock`() = runBlocking {
        val g=gate(1)
        assertNull(withTimeoutOrNull(150) { g.admit({null},{persist("cancelled");delay(1000);"cancelled"}) })
        assertEquals(0,count("orders"));assertEquals(0,count("admission_events"))
        assertEquals("after",g.admit({null},{persist("after")}))
    }
    @Test fun `disabled backlog cap still provides atomic same key handling`() = runBlocking {
        val g=gate(0)
        repeat(4){n->g.admit({null},{persist("$n")})}
        assertEquals(4,count("orders"))
    }
}
