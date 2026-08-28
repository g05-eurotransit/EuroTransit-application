package it.polito.cpo.integration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

class InventoryMigrationIntegrationTest : BaseIntegrationTest() {

    @Test
    fun `versioned migrations require an explicit travel date and contain no seed versions`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'seats'
                      AND column_name = 'travel_date'
                    """.trimIndent()
                ).use { result ->
                    result.next()
                    assertNull(result.getString("column_default"))
                }

                statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version IN ('999', '1000')
                    """.trimIndent()
                ).use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `local bootstrap is idempotent and preserves occupied seats`() {
        val schema = "bootstrap_${UUID.randomUUID().toString().replace("-", "")}"
        val schemaJdbcUrl = "${postgres.jdbcUrl}?currentSchema=$schema"

        try {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            val bootstrapSql = checkNotNull(
                javaClass.getResource("/db/local-seed/R__bootstrap_demo_seats.sql")
            ).readText()

            DriverManager.getConnection(schemaJdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(bootstrapSql)
                    val firstCount = statement.executeQuery("SELECT COUNT(*) FROM seats").use { result ->
                        result.next()
                        result.getInt(1)
                    }

                    statement.executeUpdate(
                        """
                        UPDATE seats
                        SET status = 'HELD', reservation_id = 'test-reservation'
                        WHERE route_id = 'rt-to-mi-001'
                          AND travel_date = CURRENT_DATE
                          AND seat_id = '1A'
                        """.trimIndent()
                    )
                    statement.execute(bootstrapSql)

                    val secondCount = statement.executeQuery("SELECT COUNT(*) FROM seats").use { result ->
                        result.next()
                        result.getInt(1)
                    }
                    assertEquals(firstCount, secondCount)

                    statement.executeQuery(
                        """
                        SELECT COUNT(*)
                        FROM (
                            SELECT route_id, travel_date
                            FROM seats
                            GROUP BY route_id, travel_date
                            HAVING COUNT(*) <> 24
                        ) invalid_capacity
                        """.trimIndent()
                    ).use { result ->
                        result.next()
                        assertEquals(0, result.getInt(1))
                    }

                    statement.executeQuery(
                        """
                        SELECT status, reservation_id
                        FROM seats
                        WHERE route_id = 'rt-to-mi-001'
                          AND travel_date = CURRENT_DATE
                          AND seat_id = '1A'
                        """.trimIndent()
                    ).use { result ->
                        result.next()
                        assertEquals("HELD", result.getString("status"))
                        assertEquals("test-reservation", result.getString("reservation_id"))
                    }

                    statement.executeQuery("SELECT MIN(travel_date), MAX(travel_date) FROM seats").use { result ->
                        result.next()
                        assertEquals(LocalDate.now(), result.getObject(1, LocalDate::class.java))
                        assertEquals(LocalDate.now().plusMonths(6), result.getObject(2, LocalDate::class.java))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }
}
