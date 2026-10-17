package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built world of the report 18 tests (`ReportComparePeopleTest`, `ReportComparePeopleDrillTest`): a fresh DISABLED connection
 * with two known people and two teams, January 2026 (Jan 1 and Jan 6 are public holidays, weekends off => 20 working days).
 *
 * | task | person | est start / done | actual | cycle wd | cycle days | active days | other |
 * |---|---|---|---|---|---|---|---|
 * | 1 | alice | 2 / 2 | 2.0 | 4 | 6 | 3 | Story, started 5th, done 12th |
 * | 2 | alice | 3 / 3 | 4.5 | 6 | 8 | 2 | Story, category Ops, blocked 1.5 wd, started 7th, done 15th |
 * | 3 | alice | 1 / 2, 1 change | 2.0 | 2 | 3 | 3 | Bug, started 8th, done 20th |
 * | 4 | alice | none / none (source NONE) | 1.0 | 1 | 2 | 1 | Story, started 9th, done 22nd |
 * | 5 | alice | open, 1 change | - | - | - | - | started 10th, current assignee alice |
 * | 6 | alice | sub-task, done 14th | - | - | - | - | excluded everywhere |
 * | 11 | bob | 1 / 1 | 1.0 | 5 | 7 | 2 | Story, blocked 1.0 wd, started 5th, done 13th |
 * | 12 | bob | 4 / 4 | 4.0 | 8 | 10 | 5 | Story, domain BBB, blocked 2.0 wd, started 6th, done 21st |
 * | 13 | bob | 2 / 1, 1 change | 1.0 | 3 | 4 | 1 | Bug, started 12th, done 26th |
 * | 14 | bob | 2 / 2, never started, no worklogs | 0.0 | - | - | - | Story, done 27th |
 *
 * Worklogs (by `started_at`): alice w1 1.0 (late 2 d), w2 2.0 foreign (late 0), w3 1.0 (creation time unknown), w99 9.0 on Dec 20
 * (before the period); bob w5 1.5 (late 3 d), w6 0.5 (late 0.5 d), w7 1.0 foreign (late 5 d), w8 1.25 (late 2 d).
 * Memberships: alice in team 1 throughout; bob in team 1 until noon of the 15th and in team 2 from then on (the transfer day is
 * covered by both).
 */
internal const val CMP_DAY_MS = 86_400_000L

internal class CompareWorld(
    val connId: UInt,
    val t1: UInt,
    val t2: UInt,
    val t1Name: String,
    val t2Name: String,
    val alice: String,
    val bob: String,
) {
    val store = MetricsStore(sharedDatabaseForTests())
    val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())

    fun query(vararg extra: String) =
        (listOf("connectionId=$connId", "from=2026-01-01", "to=2026-01-31", "accountId=$alice", "accountId=$bob") + extra).joinToString("&")

    private fun day(date: String) = noonUtc(date)

    private fun aliceTask(id: Long, started: String?, done: String?, block: (FactTaskDeliveryRow) -> FactTaskDeliveryRow = { it }) = block(
        handTask(
            id, startedAt = started?.let(::day), doneAt = done?.let(::day), creditTeamId = t1, account = alice,
        ),
    )

    private fun tasks(): List<FactTaskDeliveryRow> = listOf(
        aliceTask(1, "2026-01-05", "2026-01-12") {
            it.copy(
                estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0, actualMd = 2.0, cycleWorkingDays = 4.0, cycleMs = 6 * CMP_DAY_MS,
                activeMs = 3 * CMP_DAY_MS,
            )
        },
        aliceTask(2, "2026-01-07", "2026-01-15") {
            it.copy(
                estimateAtStartMd = 3.0, estimateAtDoneMd = 3.0, actualMd = 4.5, cycleWorkingDays = 6.0, cycleMs = 8 * CMP_DAY_MS,
                activeMs = 2 * CMP_DAY_MS, blockedWorkingDays = 1.5, workCategory = "Ops",
            )
        },
        aliceTask(3, "2026-01-08", "2026-01-20") {
            it.copy(
                estimateAtStartMd = 1.0, estimateAtDoneMd = 2.0, estimateChangesAfterStart = 1, actualMd = 2.0, cycleWorkingDays = 2.0,
                cycleMs = 3 * CMP_DAY_MS, activeMs = 3 * CMP_DAY_MS, activityType = "Bug",
            )
        },
        aliceTask(4, "2026-01-09", "2026-01-22") {
            it.copy(
                estimateSource = "NONE", actualMd = 1.0, cycleWorkingDays = 1.0, cycleMs = 2 * CMP_DAY_MS, activeMs = 1 * CMP_DAY_MS,
            )
        },
        handTask(
            5, startedAt = day("2026-01-10"), doneAt = null, changes = 1, estimateAtStartMd = 1.0, estimateCurrentMd = 1.0,
            currentTeamId = t1, currentAssignee = alice,
        ),
        handTask(6, startedAt = day("2026-01-05"), doneAt = day("2026-01-14"), creditTeamId = t1, account = alice, subtask = true),
        handTask(
            11, day("2026-01-05"), day("2026-01-13"), actualMd = 1.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0, creditTeamId = t1,
            account = bob, cycleMs = 7 * CMP_DAY_MS, cycleWorkingDays = 5.0, activeMs = 2 * CMP_DAY_MS,
        ).copy(blockedWorkingDays = 1.0),
        handTask(
            12, day("2026-01-06"), day("2026-01-21"), actualMd = 4.0, estimateAtStartMd = 4.0, estimateAtDoneMd = 4.0, creditTeamId = t1,
            account = bob, cycleMs = 10 * CMP_DAY_MS, cycleWorkingDays = 8.0, activeMs = 5 * CMP_DAY_MS, domain = "BBB",
        ).copy(blockedWorkingDays = 2.0),
        handTask(
            13, day("2026-01-12"), day("2026-01-26"), actualMd = 1.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 1.0, changes = 1,
            creditTeamId = t2, account = bob, cycleMs = 4 * CMP_DAY_MS, cycleWorkingDays = 3.0, activeMs = 1 * CMP_DAY_MS,
            activityType = "Bug",
        ),
        handTask(
            14, null, day("2026-01-27"), hasWorklogs = false, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0, creditTeamId = t2,
            account = bob,
        ),
    )

    private fun worklog(id: Long, account: String, date: String, md: Double, lateMs: Long?, foreign: Boolean = false) = FactWorklogRow(
        worklogId = id, issueId = id, authorAccountId = account, authorTeamId = null, startedAt = day(date),
        createdAt = lateMs?.let { day(date) + it }, lateMs = lateMs, md = md, taskDomainKey = "AAA", epicId = null, epicDomainKey = null,
        activityType = "Story", workCategory = null, sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = foreign,
        assigneeAccountIdAtStarted = null, assigneeTeamIdAtStarted = null,
    )

    private fun worklogs(): List<FactWorklogRow> = listOf(
        worklog(1, alice, "2026-01-05", 1.0, 2 * CMP_DAY_MS),
        worklog(2, alice, "2026-01-06", 2.0, 0L, foreign = true),
        worklog(3, alice, "2026-01-07", 1.0, null),
        worklog(99, alice, "2025-12-20", 9.0, 0L),
        worklog(5, bob, "2026-01-08", 1.5, 3 * CMP_DAY_MS),
        worklog(6, bob, "2026-01-09", 0.5, CMP_DAY_MS / 2),
        worklog(7, bob, "2026-01-12", 1.0, 5 * CMP_DAY_MS, foreign = true),
        worklog(8, bob, "2026-01-13", 1.25, 2 * CMP_DAY_MS),
    )

    suspend fun seed() {
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks(), configRevision = 1L)
            store.insertFactWorklog(connId, worklogs(), configRevision = 1L)
        }
        memberships.create(t1, TeamMembershipCreateRequest(alice, validFrom = 0L))
        memberships.create(t1, TeamMembershipCreateRequest(bob, validFrom = 0L, validTo = day("2026-01-15")))
        memberships.create(t2, TeamMembershipCreateRequest(bob, validFrom = day("2026-01-15")))
    }

    suspend fun cleanUp() {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactTaskDelivery(connId)
            store.deleteFactWorklog(connId)
        }
        cleanUpTeams(listOf(t1, t2))
    }
}

/** Runs [block] over a freshly seeded [CompareWorld], removing what it seeded afterwards. */
internal suspend fun withCompareWorld(block: suspend (CompareWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "compare-people", enabled = false)
    val people = CapacityFixtures.people(connId, 2)
    val t1Name = SyncedStubFixture.unique("cmp-t1")
    val t2Name = SyncedStubFixture.unique("cmp-t2")
    val world = CompareWorld(connId, TestTeams.seed(t1Name), TestTeams.seed(t2Name), t1Name, t2Name, people[0], people[1])
    world.seed()
    try {
        block(world)
    } finally {
        world.cleanUp()
    }
}
