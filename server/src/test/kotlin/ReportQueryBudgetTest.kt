package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.isQueryCanceled
import ch.nokillswit.reports.ReportService
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The report query budget (checkup 2A3): every report read runs through `ReportService.reportTransaction`, which
 * applies `reports.statementTimeoutSeconds` as the transaction's statement timeout. A cancelled statement (SQLSTATE
 * 57014) answers a 500 problem — the spec's already-declared, cross-cutting status — and the setting never
 * outlives the transaction on the pooled connection (pinned as Exposed's per-statement reset, see that test).
 */
class ReportQueryBudgetTest {

    private fun service(timeoutSeconds: Int): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(
            sharedDatabaseForTests(),
            metricsSettings,
            TeamMembershipService(sharedDatabaseForTests(), metricsSettings),
            timeoutSeconds,
        )
    }

    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.showTimeout(): String? =
        exec("SHOW statement_timeout") { row -> row.get(0, String::class.java) }?.toList()?.singleOrNull()

    /**
     * Runs [block] while ANOTHER session holds an ACCESS EXCLUSIVE lock on `metrics.dim_domain` (every report's
     * reference read of it blocks on the lock — a deterministic "slow query" that no data volume is needed for).
     * The lock is always released in `finally`.
     */
    private suspend fun <T> withDimDomainLocked(block: suspend () -> T): T = coroutineScope {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
                try {
                    conn.autoCommit = false
                    conn.createStatement().use { it.execute("LOCK TABLE metrics.dim_domain IN ACCESS EXCLUSIVE MODE") }
                    held.complete(Unit)
                    release.await()
                    conn.rollback()
                } catch (failure: Exception) {
                    held.completeExceptionally(failure)
                }
            }
        }
        try {
            held.await()
            block()
        } finally {
            release.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun `a report endpoint over the budget answers a problem body, not a hang`() = testApplication {
        configureApp("reports.statementTimeoutSeconds" to "1")
        startApplication()
        val client = seededClient("reports-budget-http")
        val response = withDimDomainLocked { withTimeout(30_000) { client.get("/api/v1/reports/filters") } }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.headers["Content-Type"]?.startsWith("application/problem+json") == true)
        val problem = response.body<ProblemDetail>()
        assertEquals(500, problem.status)
        assertTrue("time budget" in problem.detail.orEmpty(), "detail was ${problem.detail}")
        // The lock is gone: the very same endpoint answers normally again on the same pool.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/filters").status)
    }

    @Test
    fun `a statement that outruns the budget is cancelled with SQLSTATE 57014`() = testApplication {
        usePostgresTestcontainer()
        val failure = runCatching {
            service(timeoutSeconds = 1).run { reportTransaction { exec("SELECT pg_sleep(5)") } }
        }.exceptionOrNull()
        assertNotNull(failure, "pg_sleep(5) must not outlive a 1 s budget")
        assertTrue(failure.isQueryCanceled(), "expected query_canceled, got $failure")
    }

    @Test
    fun `the report budget does not leak to the pooled connection (Exposed's per-statement reset)`() = testApplication {
        usePostgresTestcontainer()
        val baseline = suspendTransaction(sharedDatabaseForTests()) { showTimeout() }
        val inside = service(timeoutSeconds = 2).run { reportTransaction { showTimeout() } }
        assertEquals("2s", inside, "the budget applies inside the report transaction")
        // A canary for Exposed upgrades: the budget is session-level (`SET statement_timeout`) and stays on the pooled
        // connection after the commit; what stops it leaking is Exposed's own per-statement reset to the transaction's
        // `queryTimeout` (default 0) before every statement. Every following plain transaction (more than the test
        // pool is large, so the report's connection is among them) must therefore see the default, and a statement
        // longer than the report budget must run to completion. If Exposed ever stops resetting, this goes red.
        repeat(10) { i ->
            assertEquals(baseline, suspendTransaction(sharedDatabaseForTests()) { showTimeout() }, "plain transaction #$i after a report")
        }
        suspendTransaction(sharedDatabaseForTests()) { exec("SELECT pg_sleep(2.5)") }
    }

    @Test
    fun `a cancelled statement outside the reports is a plain 500, not the report wording`() = testApplication {
        configureApp()
        routing {
            get("/probe/cancelled") {
                suspendTransaction(sharedDatabaseForTests()) {
                    queryTimeout = 1
                    exec("SELECT pg_sleep(5)")
                }
                call.respondText("unreachable")
            }
        }
        startApplication()
        // The default client on purpose: /probe is not in the OpenAPI spec.
        val response = client.get("/probe/cancelled")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue("An unexpected error occurred" in body, body)
        assertTrue("time budget" !in body, "the friendly wording is for /api/v1/reports/ only: $body")
    }

    @Test
    fun `a malformed or out-of-range budget refuses startup`() {
        listOf("0", "abc", "3601").forEach { bad ->
            testApplication {
                configureApp("reports.statementTimeoutSeconds" to bad)
                assertStartupFails("reports.statementTimeoutSeconds") { startApplication() }
            }
        }
    }
}
