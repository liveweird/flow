package ch.nokillswit

import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.MetricsConfigService.WorkCategoryMap
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamWorkCategoryTargetService.TeamWorkCategoryTargets
import ch.nokillswit.metrics.sprintTotals
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built world of Costs' work-category widgets (`ReportCostsCategoriesTest`): one disabled connection (ACTIVE in the business
 * sense) mapping Run and Build, a team T (members alice, bob) with stored targets {Run 60, Build 30, Retired 10}, a team T2 (carol) with
 * NO targets, a team T3 (erin) with {Run 13, Build 87}. Spike and Retired carry a per-world tag and are mapped nowhere (Retired is only a
 * stored target, Spike only a logged label). The ISO weeks of the period Dec 17 2025 .. Jan 14 2026 start Dec 15, Dec 22, Dec 29, Jan 5,
 * Jan 12 (Dec 29 spans the year boundary). Worklogs are noon UTC, md:
 *
 *   T:  wk Dec 15: alice Run 3.0 (Dec 17), bob Build 1.0 (Dec 18)
 *       wk Dec 22: bob Run 1.5 (Dec 24), alice no category 0.5 (Dec 24)
 *       wk Dec 29: nothing
 *       wk Jan 5:  bob Build 2.0 (Jan 6), alice Spike 0.5 (Jan 8)
 *       wk Jan 12: alice Run 1.0 (Jan 13)
 *       => Run 5.5, Build 3.0, Spike 0.5, none 0.5; total 9.5
 *       outside the period: alice Run 7.0 (Dec 14, the Sunday before) and Run 7.0 (Jan 15)
 *   T2: carol Run 9.0 (Dec 18)           T3: erin Run 1.255 and Build 8.745 (Dec 17)   (total 10.0)
 */
internal class CostsCategoriesWorld(
    val connId: UInt,
    val team: UInt,
    val otherTeam: UInt,
    val thirdsTeam: UInt,
    val alice: String,
    val bob: String,
    val carol: String,
    val erin: String,
    val tag: String,
) {
    val run = "Run"
    val build = "Build"
    val spike = "Spike-$tag"
    val retired = "Retired-$tag"
    val range = "connectionId=$connId&from=2025-12-17&to=2026-01-14"
}

/** Seeds the world, runs [block], and removes everything it wrote (the category mapping, the targets, the facts, the teams). */
internal suspend fun withCostsCategoriesWorld(block: suspend (CostsCategoriesWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "costs-cat", enabled = false)
    val team = TestTeams.seed(SyncedStubFixture.unique("cc-team"))
    val other = TestTeams.seed(SyncedStubFixture.unique("cc-other"))
    val thirds = TestTeams.seed(SyncedStubFixture.unique("cc-thirds"))
    val tag = SyncedStubFixture.unique("cc").substringAfter('-')
    val w = CostsCategoriesWorld(connId, team, other, thirds, "$tag-alice", "$tag-bob", "$tag-carol", "$tag-erin", tag)
    var id = 0L
    fun log(t: UInt, who: String, day: String, md: Double, category: String?) =
        wl(++id, t, who, day, md, "AAA", category = category)
    val logs = listOf(
        log(team, w.alice, "2025-12-17", 3.0, w.run), log(team, w.bob, "2025-12-18", 1.0, w.build),
        log(team, w.bob, "2025-12-24", 1.5, w.run), log(team, w.alice, "2025-12-24", 0.5, null),
        log(team, w.bob, "2026-01-06", 2.0, w.build), log(team, w.alice, "2026-01-08", 0.5, w.spike),
        log(team, w.alice, "2026-01-13", 1.0, w.run),
        log(team, w.alice, "2025-12-14", 7.0, w.run), log(team, w.alice, "2026-01-15", 7.0, w.run),
        log(other, w.carol, "2025-12-18", 9.0, w.run),
        log(thirds, w.erin, "2025-12-17", 1.255, w.run), log(thirds, w.erin, "2025-12-17", 8.745, w.build),
    )
    val store = MetricsStore(sharedDatabaseForTests())
    suspendTransaction(sharedDatabaseForTests()) {
        for (category in listOf(w.run, w.build)) {
            WorkCategoryMap.insert {
                it[WorkCategoryMap.connectionId] = connId
                it[WorkCategoryMap.valueId] = "v-$category"
                it[WorkCategoryMap.category] = category
            }
        }
        store.insertFactWorklog(connId, logs, configRevision = 1L)
    }
    storeTargets(team, mapOf(w.run to 60, w.build to 30, w.retired to 10))
    storeTargets(thirds, mapOf(w.run to 13, w.build to 87))
    try {
        block(w)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            for (t in listOf(team, thirds)) TeamWorkCategoryTargets.deleteWhere { TeamWorkCategoryTargets.teamId eq t }
            WorkCategoryMap.deleteWhere { WorkCategoryMap.connectionId eq connId }
            store.deleteSprintFacts(connId)
            store.deleteFactWorklog(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(team, other, thirds))
    }
}

/** Writes [targets] for [teamId] straight into the table (the PUT's sum-to-100 and key rules are `TeamWorkCategoryTargetsTest`'s). */
internal suspend fun storeTargets(teamId: UInt, targets: Map<String, Int>) {
    suspendTransaction(sharedDatabaseForTests()) {
        TeamWorkCategoryTargets.deleteWhere { TeamWorkCategoryTargets.teamId eq teamId }
        for ((category, percent) in targets) {
            TeamWorkCategoryTargets.insert {
                it[TeamWorkCategoryTargets.teamId] = teamId
                it[TeamWorkCategoryTargets.category] = category
                it[targetPercent] = percent
                it[updatedAt] = 1L
            }
        }
    }
}

/** A closed sprint of [team] on the world's connection for [startMs] .. [completeMs], so a `sprintId` period resolves to that window. */
internal suspend fun CostsCategoriesWorld.addSprint(sprintId: Long, startMs: Long, completeMs: Long) {
    val dim = DimSprintRow(sprintId, sprintId, team, "Cat sprint $sprintId", "closed", startMs, completeMs, completeMs, null, null)
    val store = MetricsStore(sharedDatabaseForTests())
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertDimSprints(connId, listOf(dim), configRevision = 1L)
        store.insertFactSprint(connId, listOf(FactSprintRow(sprintId, team, completeMs, sprintTotals(emptyList()), null, load = null)), 1L)
    }
}
