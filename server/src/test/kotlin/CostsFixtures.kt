package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built Costs world shared by the report-16 tests (`ReportCostsTest`, `ReportCostsBreakdownTest`): the fixture class, the
 * `wl` fact-row builder and `withCostFixture`, which seeds and removes it.
 */

/** The hand-built world of one test: three teams, six authors and a fresh disabled connection. Removed by [withCostFixture]. */
internal class CostFixture(
    val connId: UInt,
    val t1: UInt,
    val t2: UInt,
    val t3: UInt,
    val alice: String,
    val bob: String,
    val carol: String,
    val erin: String,
    val dave: String,
    val zed: String,
) {
    val range get() = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
}

/** A row builder: every parameter is one column of the stored fact. */
internal fun wl(
    id: Long,
    team: UInt?,
    account: String?,
    day: String,
    md: Double,
    taskDomain: String,
    epicDomain: String? = null,
    foreign: Boolean = false,
    activity: String = "Story",
    category: String? = null,
    startedAtMs: Long? = null,
) = FactWorklogRow(
    worklogId = id, issueId = id, authorAccountId = account, authorTeamId = team, startedAt = startedAtMs ?: noonUtc(day),
    createdAt = null, lateMs = null, md = md, taskDomainKey = taskDomain, epicId = null, epicDomainKey = epicDomain,
    activityType = activity, workCategory = category, sprintIdAtStarted = null, sprintTeamIdAtStarted = null,
    foreignWork = foreign, assigneeAccountIdAtStarted = null, assigneeTeamIdAtStarted = null,
)

/**
 * January 2026 (noon UTC, far from any day boundary). Domain columns as EPIC / TASK view:
 *  - w1 T1 alice 1.0, foreign — a cross-domain task: task AAA under an epic of BBB (EPIC: BBB, TASK: AAA)
 *  - w2 T1 bob 2.5, a Bug — epic-less: AAA in both
 *  - c1..c3 T2 carol 0.3333 each (thirds): AAA epic-less; task AAA under an epic of BBB; CCC epic-less, foreign
 *  - w4 T2 erin 1.5, category Ops — logged on an epic: BBB in both (A21)
 *  - w5 UNASSIGNED dave 4.0 on BBB; w6 UNASSIGNED with no known author 0.5 on CCC
 *  - w7 T3 zed 0.0 on AAA
 *  - w8/w9 are outside January (2026-02-20 and 2025-12-31, 99 and 50 MD) and never count in it.
 * Domains AAA "Alpha" and BBB "Beta" have `dim_domain` rows, CCC has none (its column has no name).
 */
internal suspend fun withCostFixture(block: suspend (CostFixture) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "costs", enabled = false)
    val store = MetricsStore(sharedDatabaseForTests())
    val t1 = TestTeams.seed(SyncedStubFixture.unique("cm-t1"))
    val t2 = TestTeams.seed(SyncedStubFixture.unique("cm-t2"))
    val t3 = TestTeams.seed(SyncedStubFixture.unique("cm-t3"))
    val tag = SyncedStubFixture.unique("cm")
    val f = CostFixture(connId, t1, t2, t3, "$tag-alice", "$tag-bob", "$tag-carol", "$tag-erin", "$tag-dave", "$tag-zed")
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertDomains(
            connId,
            listOf(DimDomainRow("AAA", "Alpha", emptyList(), null), DimDomainRow("BBB", "Beta", emptyList(), null)),
            configRevision = 1L,
        )
        store.insertFactWorklog(
            connId,
            listOf(
                wl(1, t1, f.alice, "2026-01-10", 1.0, "AAA", "BBB", foreign = true),
                wl(2, t1, f.bob, "2026-01-11", 2.5, "AAA", activity = "Bug"),
                wl(3, t2, f.carol, "2026-01-12", 0.3333, "AAA"),
                wl(4, t2, f.carol, "2026-01-13", 0.3333, "AAA", "BBB"),
                wl(5, t2, f.carol, "2026-01-14", 0.3333, "CCC", foreign = true),
                wl(6, t2, f.erin, "2026-01-15", 1.5, "BBB", "BBB", category = "Ops"),
                wl(7, null, f.dave, "2026-01-16", 4.0, "BBB"),
                wl(8, null, null, "2026-01-17", 0.5, "CCC"),
                wl(9, t3, f.zed, "2026-01-18", 0.0, "AAA"),
                wl(10, t1, f.alice, "2026-02-20", 99.0, "AAA"),
                wl(11, t1, f.alice, "2025-12-31", 50.0, "AAA"),
            ),
            configRevision = 1L,
        )
    }
    try {
        block(f)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactWorklog(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(t1, t2, t3))
    }
}
