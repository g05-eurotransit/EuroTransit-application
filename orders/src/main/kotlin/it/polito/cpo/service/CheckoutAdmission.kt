package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.transaction.support.DefaultTransactionDefinition
import it.polito.cpo.observability.ApiException
import java.util.concurrent.atomic.AtomicInteger

/** Cross-replica backlog gate. No remote call or Kafka processing runs while the lock is held. */
@Service
class CheckoutAdmission(
    private val database: DatabaseClient,
    manager: ReactiveTransactionManager,
    private val metrics: MeterRegistry,
    @Value("\${orders.admission.max-outstanding:0}") private val maximum: Int,
    @Value("\${orders.admission.max-concurrent:8}") private val concurrency: Int,
    @Value("\${orders.admission.lock-timeout-ms:100}") private val lockTimeout: Int,
) {
    private val inflight = AtomicInteger()
    private val transaction = TransactionalOperator.create(manager, DefaultTransactionDefinition().apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 2
    })
    init { require(maximum >= 0 && concurrency > 0 && lockTimeout in 1..1000) }

    suspend fun <T> admit(replay: suspend () -> T?, persist: suspend () -> T): T {
        try {
            return transaction.executeAndAwait {
                // All new admissions use the same stable key, including replicas running with cap=0.
                // This also makes the second idempotency check safe for simultaneous first requests.
                // Enforce the query bound in PostgreSQL as well as the transaction manager;
                // statement_timeout covers slow backlog scans and writes, not only lock waits.
                database.sql("SET LOCAL statement_timeout = '2000ms'").then().awaitSingleOrNullUnit()
                database.sql("SET LOCAL lock_timeout = '${lockTimeout}ms'").then().awaitSingleOrNullUnit()
                database.sql("SELECT pg_advisory_xact_lock(697430123)").fetch().all().then().awaitSingleOrNullUnit()
                replay()?.let { return@executeAndAwait it }
                if (maximum > 0) {
                    val count = database.sql("""
                        SELECT COUNT(*) AS n FROM orders WHERE status IN
                        ('ACCEPTED','RESERVING','PAYMENT_PENDING','CONFIRMING_INVENTORY','COMPENSATING')
                    """).map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()
                    if (count >= maximum) refuse("backlog_full")
                }
                persist()
            }
        } catch (failure: Exception) {
            val sql = generateSequence(failure as Throwable) { it.cause }.filterIsInstance<R2dbcException>().firstOrNull()
            if (sql?.sqlState == "55P03") refuse("admission_busy")
            val unavailable = generateSequence(failure as Throwable) { it.cause }.any {
                it is java.util.concurrent.TimeoutException || it.javaClass.simpleName == "PoolAcquireTimeoutException"
            }
            if (sql?.sqlState?.startsWith("08") == true || sql?.sqlState == "57014" || unavailable) {
                throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHECKOUT_UNAVAILABLE", "Checkout is temporarily unavailable", cause = failure)
            }
            throw failure
        }
    }
    suspend fun <T> withSlot(work: suspend () -> T): T {
        if (inflight.incrementAndGet() > concurrency) {
            inflight.decrementAndGet()
            refuse("request_pool_full")
        }
        try { return work() } finally { inflight.decrementAndGet() }
    }
    private fun refuse(reason: String): Nothing {
        metrics.counter("orders.checkout.overload", "reason", reason).increment()
        throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHECKOUT_OVERLOADED", "Checkout is busy. Retry with the same idempotency key.")
    }
}

private suspend fun reactor.core.publisher.Mono<Void>.awaitSingleOrNullUnit() {
    awaitSingleOrNull()
}
