package ch.nokillswit

import ch.nokillswit.infra.db.PoolStatsKey
import ch.nokillswit.infra.db.connectPooledDatabase
import ch.nokillswit.infra.db.snapshot
import io.ktor.server.testing.testApplication
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `infra/db/Database.kt`'s `PoolStatsKey`: the telemetry sampler reads r2dbc-pool's `PoolMetrics` through it. This PINS that
 * `ConnectionPool.getMetrics()` is present with r2dbc-pool 1.0.2 - an upgrade that dropped it would silently blank the
 * pool section of the panel - and that the figures move with the pool.
 */
class PoolStatsTest {
    @Test
    fun `the pool reports its metrics and they follow its use`() = runBlocking {
        val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()
        val (database, pool) = connectPooledDatabase(
            options, maxSize = 3, initialSize = 1, maxAcquireTime = Duration.ofSeconds(10), maxIdleTime = Duration.ofMinutes(1),
        )
        try {
            assertTrue(pool.metrics.isPresent, "r2dbc-pool 1.0.2 exposes PoolMetrics")
            suspendTransaction(database) { exec("SELECT 1") }
            val idle = assertNotNull(pool.snapshot())
            assertEquals(0, idle.acquired)
            assertEquals(0, idle.pendingAcquire)
            assertTrue(idle.allocated >= 1 && idle.idle == idle.allocated, "everything allocated is idle: $idle")
            assertTrue(idle.maxAllocated >= idle.allocated)
            suspendTransaction(database) {
                exec("SELECT 1")
                assertEquals(1, assertNotNull(pool.snapshot()).acquired, "the open transaction holds one connection")
            }
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `the application publishes the pool figures`() = testApplication {
        usePostgresTestcontainer()
        val stats = application.attributes[PoolStatsKey]
        val snapshot = assertNotNull(stats(), "boot has already opened connections")
        assertTrue(snapshot.allocated >= 1)
    }
}
