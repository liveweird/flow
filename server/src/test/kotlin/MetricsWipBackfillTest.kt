package ch.nokillswit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DERIVE's WIP days start at the LATER of the connection's earliest `created_at` day and its `backfill_from` (decision C,
 * `.claude/docs/metrics.md` "Daily WIP aggregate"). Each case derives a PRIVATE disabled clone of the stub (its work items
 * are real, so the rows are graded against the shared [DerivedStubFixture] derive of the same data) under the pinned clock;
 * only the DOMAIN and EPIC scopes are compared, because the fixture maps a board to a team and the clone does not (the TEAM
 * scope ids differ by construction).
 */
class MetricsWipBackfillTest {
    private suspend fun baseline(): List<WipRow> =
        readWipRows(DerivedStubFixture.connectionId()).filter { it.scopeKind != "TEAM" }

    private suspend fun deriveClone(backfillFrom: String): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-wip-backfill", enabled = false, backfillFrom = backfillFrom)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
        return connId
    }

    private fun Collection<WipRow>.sortedForCompare() =
        sortedWith(compareBy({ it.scopeKind }, { it.scopeId }, { it.day }, { it.itemKind }, { it.statusId }, { it.stage }))

    @Test
    fun `the stub's own WIP days start at its backfill date, so the fixture digests do not move`() = runBlocking {
        // The stub's issues are created from its backfill date on (sample-data/README.md; the first WIP day is exactly
        // 2025-09-01, measured), so max(earliest created_at day, backfill_from) IS the earliest created_at day and the rule
        // cuts nothing from the shared fixture: its rows and the metrics digests are byte-for-byte what they were.
        val first = baseline().minOf { it.day }
        assertEquals(SyncedStubFixture.BACKFILL_FROM, first, "the fixture's first WIP day is its backfill date, never earlier")
    }

    @Test
    fun `a later backfill date drops every earlier WIP day and leaves the later rows exactly as they were`() = runBlocking {
        val base = baseline()
        val cutoff = "2026-01-15"
        assertTrue(base.any { it.day < cutoff } && base.any { it.day >= cutoff }, "the cut-off must fall inside the fixture's days")
        val connId = deriveClone(cutoff)

        val rows = readWipRows(connId)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { it.day < cutoff }, "no agg_daily_wip row before backfill_from (first: ${rows.minOf { it.day }})")
        assertEquals(cutoff, rows.minOf { it.day }, "the series starts ON the backfill date")
        assertEquals(
            base.filter { it.day >= cutoff }.sortedForCompare(),
            rows.filter { it.scopeKind != "TEAM" }.sortedForCompare(),
            "from the backfill date on, a prefix cut: every row is the one the uncut derive wrote",
        )
        // The TEAM scope obeys the same start (its ids differ from the fixture's; only the days are graded).
        assertEquals(cutoff, rows.filter { it.scopeKind == "TEAM" }.minOf { it.day })

        // The backlog aggregate shares the day range but is NOT cut: decision C covers agg_daily_wip only.
        val backlog = readFlowBacklogRows(connId).filter { it.scopeKind == "DOMAIN" }.toSet()
        val baseBacklog = readFlowBacklogRows(DerivedStubFixture.connectionId()).filter { it.scopeKind == "DOMAIN" }.toSet()
        assertEquals(baseBacklog, backlog, "the estimated backlog keeps the creation-based start")
    }

    @Test
    fun `a backfill date older than the first created day changes nothing`() = runBlocking {
        val connId = deriveClone(EARLY_BACKFILL)
        assertEquals(baseline().sortedForCompare(), readWipRows(connId).filter { it.scopeKind != "TEAM" }.sortedForCompare())
    }
}
