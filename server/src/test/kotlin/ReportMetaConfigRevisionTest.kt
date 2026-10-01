package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.CycleTimeReport
import ch.nokillswit.reports.DeriveStamp
import ch.nokillswit.reports.deriveStamp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `meta.configRevision` (`.claude/docs/reports.md` "`meta`") is the revision the served figures were DERIVED under — the
 * `config_revision` of the connection's newest SUCCEEDED `derive_runs` row — never the live
 * `metrics.settings.config_revision`, which a configuration change bumps before its DERIVE has run. Unit-wide (several
 * connections in scope) it is the OLDEST such revision, the `derivedCoverage` rule; `null` before any successful DERIVE.
 * Hand-built `derive_runs` rows on fresh DISABLED connections (no real DERIVE needed: only the stamp is under test).
 */
class ReportMetaConfigRevisionTest {

    private suspend fun insertRun(connId: UInt, startedAt: Long, revision: Long, status: String = "SUCCEEDED", finishedAt: Long? = null) =
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DeriveRuns.insert {
                it[MetricsTables.DeriveRuns.connectionId] = connId.toInt()
                it[configRevision] = revision
                it[processingVersion] = 1
                it[MetricsTables.DeriveRuns.startedAt] = startedAt
                it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
                it[MetricsTables.DeriveRuns.status] = status
            }
        }

    private suspend fun stamp(vararg connIds: UInt): DeriveStamp =
        suspendTransaction(sharedDatabaseForTests()) { deriveStamp(connIds.toList()) }

    @Test
    fun `a report's meta keeps the derived revision after the live revision is bumped`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "meta-rev", enabled = false)
        val client = seededClient("meta-rev")
        val query = "/api/v1/reports/cycle-time?connectionId=$connId&from=2026-01-01&to=2026-01-31"
        try {
            val notDerived = client.get(query)
            assertEquals(HttpStatusCode.OK, notDerived.status)
            val before = notDerived.body<CycleTimeReport>().meta
            assertNull(before.derivedAt, "no DERIVE yet")
            assertNull(before.configRevision, "no DERIVE yet: there is no derived revision to report")

            val settings = DerivedStubFixture.metricsSettings()
            val derivedRevision = settings.read().configRevision
            insertRun(connId, startedAt = 1_000L, revision = derivedRevision, finishedAt = 2_000L)

            withMetricsSettings(settings, { it.copy(minSampleSize = it.minSampleSize + 1) }) {
                val live = settings.read().configRevision
                assertTrue(live > derivedRevision, "the settings save bumped the live revision past the derived one")
                val meta = client.get(query).body<CycleTimeReport>().meta
                assertEquals(derivedRevision, meta.configRevision, "derived revision, not the live $live")
                assertEquals(2_000L, meta.derivedAt)
            }
        } finally {
            deleteDeriveRuns(connId)
        }
    }

    @Test
    fun `a connection's newest successful run wins, ignoring failed and older runs`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "meta-rev-newest", enabled = false)
        try {
            insertRun(connId, startedAt = 1_000L, revision = 3L, finishedAt = 1_500L)
            insertRun(connId, startedAt = 2_000L, revision = 5L, finishedAt = 2_500L)
            insertRun(connId, startedAt = 3_000L, revision = 9L, status = "FAILED", finishedAt = 3_500L)
            insertRun(connId, startedAt = 4_000L, revision = 11L, status = "RUNNING")
            val result = stamp(connId)
            assertEquals(5L, result.configRevision, "the newest SUCCEEDED run's revision")
            assertEquals(2_500L, result.derivedAt)
        } finally {
            deleteDeriveRuns(connId)
        }
    }

    @Test
    fun `several connections in scope report the oldest derived revision, never-derived ones excluded`() = testApplication {
        usePostgresTestcontainer()
        val fresh = SyncedStubFixture.createConnection(namePrefix = "meta-rev-fresh", enabled = false)
        val stale = SyncedStubFixture.createConnection(namePrefix = "meta-rev-stale", enabled = false)
        val never = SyncedStubFixture.createConnection(namePrefix = "meta-rev-never", enabled = false)
        try {
            assertNull(stamp(fresh, stale, never).configRevision, "nothing derived anywhere")
            insertRun(fresh, startedAt = 5_000L, revision = 8L, finishedAt = 5_500L)
            insertRun(stale, startedAt = 1_000L, revision = 4L, finishedAt = 1_500L)
            val result = stamp(fresh, stale, never)
            assertEquals(4L, result.configRevision, "the stale connection's figures reflect the OLDEST configuration")
            assertEquals(5_500L, result.derivedAt, "derivedAt stays the latest finish")
            assertEquals(8L, stamp(fresh, never).configRevision, "a never-derived connection adds nothing")
            assertNull(stamp(never).configRevision)
            assertNull(stamp().configRevision)
        } finally {
            listOf(fresh, stale, never).forEach { deleteDeriveRuns(it) }
        }
    }
}
