package ch.nokillswit

import ch.nokillswit.infra.db.connectPooledDatabase
import ch.nokillswit.plugins.isQueryCanceled
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Correctness pins for `StatementTimeoutCachingConnection` (`infra/db/StatementTimeoutCache.kt`), each on its OWN
 * ONE-connection pool so every borrower is the same backend and no test depends on the shared pool's state or order.
 */
class StatementTimeoutCacheTest {
    private fun withSingleConnectionDb(block: suspend (R2dbcDatabase) -> Unit): Unit = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl)
            .mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()
        val (db, pool) = connectPooledDatabase(options, 1, 1, Duration.ofSeconds(10), Duration.ofMinutes(1))
        try {
            block(db)
        } finally {
            pool.dispose()
        }
    }

    private suspend fun R2dbcTransaction.showTimeout(): String? =
        exec("SHOW statement_timeout") { row -> row.get(0, String::class.java) }?.toList()?.singleOrNull()

    private suspend fun R2dbcDatabase.show(seconds: Int): String? = suspendTransaction(this) {
        queryTimeout = seconds
        showTimeout()
    }

    @Test
    fun `every borrower converges to its own timeout, including 0 after a budgeted one`() = withSingleConnectionDb { db ->
        assertEquals("0", db.show(0))
        assertEquals("2s", db.show(2))
        assertEquals("2s", db.show(2))
        assertEquals("0", db.show(0))
        assertEquals("3s", db.show(3))
        assertEquals("0", db.show(0))
    }

    @Test
    fun `a rolled-back transaction does not leave a stale remembered value`() = withSingleConnectionDb { db ->
        assertEquals("0", db.show(0))
        val failure = runCatching {
            suspendTransaction(db) {
                queryTimeout = 2
                assertEquals("2s", showTimeout())
                error("boom")
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        // PostgreSQL reverted the rolled-back SET to 0; the next borrower asking for 2s must get it re-applied.
        assertEquals("2s", db.show(2))
    }

    @Test
    fun `a transaction aborted by an error does not leave the budget remembered (library-upgrade canary)`() = withSingleConnectionDb { db ->
        // Pins two behaviours outside this repo: r2dbc-postgresql turns the ROLLBACK command tag of a COMMIT on an aborted
        // transaction into an exception, and Exposed rolls back after a failed commit. Together they revert the transaction's
        // session-level SET on the server, and the cache must have forgotten it: the next borrower asking for 2s sees 2s.
        assertEquals("0", db.show(0))
        runCatching {
            suspendTransaction(db) {
                queryTimeout = 2
                assertEquals("2s", showTimeout())
                runCatching { exec("SELECT 1/0") }
            }
        }
        assertEquals("2s", db.show(2))
    }

    @Test
    fun `a budget is enforced again after a statement was cancelled by it`() = withSingleConnectionDb { db ->
        repeat(2) { round ->
            val failure = runCatching {
                suspendTransaction(db) {
                    queryTimeout = 1
                    exec("SELECT pg_sleep(4)")
                }
            }.exceptionOrNull()
            assertNotNull(failure, "round $round: pg_sleep(4) must not outlive a 1 s budget")
            assertTrue(failure.isQueryCanceled(), "round $round: expected query_canceled, got $failure")
        }
        assertEquals("0", db.show(0))
    }

    @Test
    fun `an unchanged timeout is not re-sent, which is why only the queryTimeout may ever set it`() = withSingleConnectionDb { db ->
        // Pins the skip itself and its documented limit (`persistence.md` "Statement timeouts"): a hand-rolled SET behind
        // the cache is NOT undone by the next statement, because the connection already holds the requested 0. The pool
        // is this test's own and is disposed afterwards, so the desynchronised connection never reaches another test.
        suspendTransaction(db) {
            exec("SELECT 1")
            exec("SET statement_timeout = 7000")
            assertEquals("7s", showTimeout(), "the cache skipped the unchanged 0, so the hand-rolled value survived")
        }
        assertEquals("2s", db.show(2), "a changed value is always sent")
    }
}
