package ch.nokillswit

import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.users.UserService
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bounded R2DBC connection pool (`infra/db/Database.kt`, `.claude/docs/persistence.md`
 * "Connection pool", ported from Lettuce): before it existed, a plain `r2dbc:postgresql://`
 * connect opened one PostgreSQL backend per `suspendTransaction` with nothing capping how many
 * ran at once — measured in Lettuce, v3.16.1, a 120-parallel burst produced dozens of concurrent
 * backends against PostgreSQL's default `max_connections = 100`.
 *
 * Each test mints a unique `postgres.pool.applicationName` so overlapping test applications
 * sharing the Testcontainer never share one `pg_stat_activity` count.
 */
class ConnectionPoolTest {

    /** A plain JDBC round trip against the shared Testcontainer — never through the pool under test. */
    private fun activeConnections(applicationName: String): Int =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?").use { stmt ->
                stmt.setString(1, applicationName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** A trivial query — just enough to make the transaction actually acquire a pooled connection. */
    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.touchDatabase() {
        UserService.Users.selectAll().limit(1).toList()
    }

    @Test
    fun `concurrent transactions are bounded by postgres pool maxSize`() = testApplication {
        val appName = "flow-test-${UUID.randomUUID()}"
        configureApp("postgres.pool.maxSize" to "4", "postgres.pool.applicationName" to appName)
        startApplication()
        val db = application.attributes[R2dbcDatabaseKey]

        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            repeat(12) {
                launch(Dispatchers.IO) {
                    suspendTransaction(db) {
                        touchDatabase()
                        gate.await()
                    }
                }
            }
            // Poll while (up to) four of the twelve coroutines are holding a pooled connection
            // and the rest are queued waiting to acquire one.
            // The pool is saturated once four backends are seen; an over-acquiring pool would have opened
            // its extra ones by then, so keep watching for a short settle window rather than a fixed 2 s
            // (2 s stays the ceiling if the fourth backend is slow to appear, e.g. on a busy CI runner).
            var maxObserved = 0
            var saturatedAt = 0L
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline && (saturatedAt == 0L || System.nanoTime() - saturatedAt < SETTLE_NANOS)) {
                maxObserved = maxOf(maxObserved, activeConnections(appName))
                if (saturatedAt == 0L && maxObserved >= 4) saturatedAt = System.nanoTime()
                delay(50)
            }
            assertEquals(
                4,
                maxObserved,
                "expected the pool to fill to, and never exceed, 4 concurrent pooled connections (postgres.pool.maxSize)",
            )
            gate.complete(Unit)
            // Exiting this coroutineScope suspends until all 12 launched transactions complete —
            // a hang here means a released connection was never handed to a queued waiter.
        }
    }

    @Test
    fun `a saturated pool times out an acquire instead of hanging`() = testApplication {
        val appName = "flow-test-${UUID.randomUUID()}"
        configureApp(
            "postgres.pool.maxSize" to "1",
            "postgres.pool.initialSize" to "0",
            "postgres.pool.maxAcquireTimeSeconds" to "1",
            "postgres.pool.applicationName" to appName,
        )
        startApplication()
        val db = application.attributes[R2dbcDatabaseKey]

        val gate = CompletableDeferred<Unit>()
        val holderStarted = CompletableDeferred<Unit>()
        coroutineScope {
            val holder = launch(Dispatchers.IO) {
                suspendTransaction(db) {
                    touchDatabase()
                    holderStarted.complete(Unit)
                    gate.await()
                }
            }
            holderStarted.await()

            // Database.kt pins defaultMaxAttempts = 1, so Exposed adds no attempts of its own; the pool itself
            // retries a failed acquire once (r2dbc-pool's acquireRetry = 1, kept on purpose: the same retry
            // replaces a connection that fails LOCAL validation), so a timed-out acquire surfaces after ~2x
            // maxAcquireTimeSeconds — .claude/docs/persistence.md "Connection pool". A generous outer bound
            // around that observed failure, never a sleep (.claude/docs/testing.md).
            val startedAt = System.nanoTime()
            val failure = withTimeoutOrNull(20_000) {
                runCatching { suspendTransaction(db) { touchDatabase() } }.exceptionOrNull()
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertNotNull(failure, "the second transaction must fail rather than hang past maxAcquireTimeSeconds")
            val chain = generateSequence(failure) { it.cause }.toList()
            assertTrue(
                chain.any { it is TimeoutException },
                "expected the pool's acquire-timeout exception in the cause chain, got: ${chain.map { it::class.qualifiedName }}",
            )
            // Pins the documented behaviour: the retry re-queues the waiter for a second full deadline (>= ~2x of
            // the 1 s setting), and Exposed's three attempts would push it to ~6 s (> the upper bound).
            assertTrue(elapsedMillis >= 1_900, "acquire gave up after ${elapsedMillis}ms; expected ~2x the 1000ms setting")
            assertTrue(elapsedMillis <= 4_000, "acquire took ${elapsedMillis}ms; expected ~2x the 1000ms setting")

            gate.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun `the pool releases every connection when the application stops`() {
        val appName = "flow-test-${UUID.randomUUID()}"
        testApplication {
            configureApp("postgres.pool.applicationName" to appName)
            startApplication()
            suspendTransaction(application.attributes[R2dbcDatabaseKey]) { touchDatabase() }
            assertTrue(activeConnections(appName) >= 1, "expected at least one pooled backend while the app is running")
        }
        // The testApplication{} block above has returned, which stops the embedded application
        // (and, via our ApplicationStopped hook, disposes the pool) before control reaches here.
        runBlocking {
            val cleared = withTimeoutOrNull(5_000) {
                while (isActive && activeConnections(appName) > 0) delay(100)
                true
            }
            assertNotNull(cleared, "expected pooled connections to be released once the application stopped")
        }
    }

    @Test
    fun `the pool is released even when a later module refuses startup`() {
        // configureBootstrap runs AFTER configureDatabase (application.yaml module order).
        // Deliberately WITHOUT bootstrap.adminInitialPassword: that branch's burned-value check
        // (`check(...)` against BURNED_INITIAL_PASSWORDS) runs BEFORE any DB call and would make
        // this test pass vacuously — the lazy pool never opens a connection, so "every connection
        // released" is trivially true. Omitting it instead reaches the SEED-PASSWORD check
        // (`userService.countActiveWithPasswordHash`), which issues a real query — the pool MUST
        // open at least one backend — before refusing startup, so disposal is genuinely exercised.
        val appName = "flow-test-${UUID.randomUUID()}"
        testApplication {
            configureApp(
                "postgres.pool.applicationName" to appName,
                "jwt.secret" to strongJwtSecret(),
                "security.encryption.key" to strongEncryptionKey(),
                "mail.transport" to "disabled",
            )
            serverConfig { developmentMode = false }
            withSeedRestored {
                assertStartupFails("seed password") { startApplication() }
            }
        }
        runBlocking {
            val cleared = withTimeoutOrNull(5_000) {
                while (isActive && activeConnections(appName) > 0) delay(100)
                true
            }
            assertNotNull(cleared, "expected the pool to be disposed after a failed startup")
        }
    }

    @Test
    fun `postgres pool maxSize out of range fails startup`() = testApplication {
        configureApp("postgres.pool.maxSize" to "0")
        assertStartupFails("postgres.pool.maxSize") { startApplication() }
    }

    @Test
    fun `postgres pool initialSize above maxSize fails startup`() = testApplication {
        configureApp("postgres.pool.initialSize" to "5", "postgres.pool.maxSize" to "2")
        assertStartupFails("postgres.pool.initialSize") { startApplication() }
    }

    // The bounds go through the shared infra/config helpers, so a non-numeric value is a config
    // error naming the key (it would otherwise escape as a raw NumberFormatException), and the
    // Long bounds honour their upper limit too.
    @Test
    fun `a non-numeric postgres pool bound fails startup naming the key`() = testApplication {
        configureApp("postgres.pool.maxSize" to "abc")
        assertStartupFails("Config \"postgres.pool.maxSize\" must be an integer") { startApplication() }
    }

    @Test
    fun `postgres pool maxAcquireTimeSeconds above its ceiling fails startup`() = testApplication {
        configureApp("postgres.pool.maxAcquireTimeSeconds" to "601")
        assertStartupFails("postgres.pool.maxAcquireTimeSeconds") { startApplication() }
    }

    private companion object {
        /** How long the pool is watched after it first shows `maxSize` (4) backends — ample for a stray fifth to appear. */
        const val SETTLE_NANOS = 500_000_000L
    }
}
