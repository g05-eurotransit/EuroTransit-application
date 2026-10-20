package it.polito.cpo.catalog

import it.polito.cpo.repository.InMemoryCatalogRepository
import it.polito.cpo.repository.RouteRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["catalog.storage=postgres"])
class PostgresCatalogApiTests(@Autowired private val client: WebTestClient) : CatalogApiTests(client) {
    @Autowired private lateinit var repository: RouteRepository
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var flyway: Flyway

    @Test
    fun `postgres preserves the complete seeded catalog contract`() = runTest {
        val memory = InMemoryCatalogRepository()
        assertEquals(memory.findAllRoutes().toList(), repository.findAllRoutes().toList())
        assertEquals(memory.findAllOffers().toList(), repository.findAllOffers().toList())
        for (offer in memory.findAllOffers().toList()) {
            assertEquals(offer, repository.findOfferById(offer.offerId))
        }
        assertNull(repository.findOfferById("missing"))
        assertNull(repository.findRouteById("missing"))
    }

    @Test
    fun `rerunning migrations does not overwrite stored data`() = runTest {
        try {
            db.sql("UPDATE offers SET price = 90.00 WHERE offer_id = 'of-003'").fetch().rowsUpdated().awaitSingle()
            assertEquals(0, flyway.migrate().migrationsExecuted)
            assertEquals(BigDecimal("90.00"), repository.findOfferById("of-003")!!.price)
        } finally {
            db.sql("UPDATE offers SET price = 89.00 WHERE offer_id = 'of-003'").fetch().rowsUpdated().awaitSingle()
        }
    }

    @Test
    fun `database rejects invalid prices refund terms and orphan offers`() = runTest {
        for (assignment in listOf("price = 0", "terms_version = 0", "currency = 'eur'",
            "refund_cutoff_minutes_before_departure = 1", "route_id = 'missing'")) {
            assertFailsWith<DataIntegrityViolationException> {
                db.sql("UPDATE offers SET $assignment WHERE offer_id = 'of-003'").fetch().rowsUpdated().awaitSingle()
            }
        }
    }

    companion object {
        private class Postgres : PostgreSQLContainer<Postgres>("postgres:16-alpine")
        @Container @JvmStatic private val postgres = Postgres()

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
            registry.add("DB_PASSWORD", postgres::getPassword)
        }
    }
}
