package it.polito.cpo.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext

/**
 * Verifies that the two maintenance schedulers are switched independently, and that the
 * reservation-expiration sweep stays off unless it is explicitly enabled.
 *
 * The expiration default is a correctness gate, not a preference: while Orders does not
 * confirm reservations synchronously after payment authorization, sweeping expired holds
 * can release seats belonging to a paid order. See the implementation gate in
 * docs/design/reservation-confirmation.md.
 */
class MaintenanceJobToggleTest {

    private val runner = ApplicationContextRunner()
        .withBean(InventoryMaintenanceService::class.java, { Mockito.mock(InventoryMaintenanceService::class.java) })
        .withConfiguration(
            UserConfigurations.of(ReservationExpirationJob::class.java, IdempotencyPurgeJob::class.java),
        )

    private fun ConfigurableApplicationContext.beanCount(type: Class<*>) =
        getBeanNamesForType(type).size

    @Test
    fun `by default the expiration sweep is disabled and the idempotency purge is enabled`() {
        runner.run { context ->
            assertEquals(0, context.beanCount(ReservationExpirationJob::class.java))
            assertEquals(1, context.beanCount(IdempotencyPurgeJob::class.java))
        }
    }

    @Test
    fun `the expiration sweep is registered only when explicitly enabled`() {
        runner.withPropertyValues("inventory.reservations.expiration.enabled=true").run { context ->
            assertEquals(1, context.beanCount(ReservationExpirationJob::class.java))
        }
    }

    @Test
    fun `an explicit false keeps the expiration sweep disabled`() {
        runner.withPropertyValues("inventory.reservations.expiration.enabled=false").run { context ->
            assertEquals(0, context.beanCount(ReservationExpirationJob::class.java))
        }
    }

    @Test
    fun `the idempotency purge can be disabled without touching the expiration sweep`() {
        runner
            .withPropertyValues(
                "inventory.reservations.expiration.enabled=true",
                "inventory.idempotency.purge.enabled=false",
            )
            .run { context ->
                assertEquals(1, context.beanCount(ReservationExpirationJob::class.java))
                assertEquals(0, context.beanCount(IdempotencyPurgeJob::class.java))
            }
    }
}
