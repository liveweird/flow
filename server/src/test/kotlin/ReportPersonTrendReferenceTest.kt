package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.PersonTrendRow
import ch.nokillswit.reports.TREND_ROW_KEYS
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * Report 20's team reference line (A46 amended 2026-10-28) in a hand-built world, windows ending Jan 1, Jan 15 and Jan 29 2026.
 * Seven people: the PERSON moves from team A to team B (A from Jan 10 noon, B from Jan 20 noon); team A holds four members (three active
 * peers and a quiet one with no activity at all), team B two. Every reference is graded against `GET /compare-people` for each peer (one
 * truth), and the hand-computed medians pin the rule: the person never pulls their own reference, a member with no activity is no peer,
 * fewer than three shown peers leave `value` out with the count, a slice applies to the peers as to the person, and a point with no
 * membership has no team.
 */
class ReportPersonTrendReferenceTest {

    private class Team(val id: UInt, val name: String)

    private class World(
        val connId: UInt,
        val person: String,
        val teamA: Team,
        val teamB: Team,
        val peersA: List<String>,
        val peersB: List<String>,
    ) {
        val base get() = "connectionId=$connId"
        private val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())

        /** The person moves A (from Jan 10 noon) to B (from Jan 20 noon); four members in A and two in B throughout. */
        suspend fun seedMemberships() {
            memberships.create(teamA.id, TeamMembershipCreateRequest(person, noonUtc("2026-01-10"), noonUtc("2026-01-20")))
            memberships.create(teamB.id, TeamMembershipCreateRequest(person, noonUtc("2026-01-20")))
            peersA.forEach { memberships.create(teamA.id, TeamMembershipCreateRequest(it, 0L)) }
            peersB.forEach { memberships.create(teamB.id, TeamMembershipCreateRequest(it, 0L)) }
        }
    }

    private fun PersonTrendReport.row(key: String): PersonTrendRow = rows.single { it.key == key }

    /** A task done at noon on [date], worth 1 MD, with [wd] working days of cycle time. */
    private fun done(id: Long, account: String, date: String, wd: Double, type: String = "Story") = handTask(
        id, startedAt = noonUtc(date) - 5 * CMP_DAY_MS, doneAt = noonUtc(date), actualMd = 1.0, estimateAtStartMd = 1.0,
        estimateAtDoneMd = 1.0, account = account, cycleMs = 5 * CMP_DAY_MS, cycleWorkingDays = wd, activeMs = 2 * CMP_DAY_MS,
        activityType = type,
    )

    private suspend fun withWorld(withTeams: Boolean = true, block: suspend (World) -> Unit) {
        val connId = SyncedStubFixture.createConnection(namePrefix = "trend-reference", enabled = false)
        val people = CapacityFixtures.people(connId, 7)
        val nameA = SyncedStubFixture.unique("ref-a")
        val nameB = SyncedStubFixture.unique("ref-b")
        val teamA = Team(TestTeams.seed(nameA), nameA)
        val teamB = Team(TestTeams.seed(nameB), nameB)
        val store = MetricsStore(sharedDatabaseForTests())
        val world = World(connId, people[0], teamA, teamB, people.slice(1..4), people.slice(5..6))
        try {
            val tasks = listOf(
                done(1, world.person, "2025-11-20", 2.0), done(2, world.person, "2025-12-20", 3.0),
                done(3, world.person, "2026-01-03", 4.0), done(4, world.person, "2026-01-08", 5.0, "Bug"),
                done(5, world.person, "2026-01-09", 6.0, "Bug"),
                // Team A: three active peers (1, 2 and 3 tasks; Bug: 1, 1 and 2) and a fourth member with nothing at all.
                done(11, world.peersA[0], "2025-12-01", 4.0, "Bug"),
                done(21, world.peersA[1], "2025-12-02", 3.0), done(22, world.peersA[1], "2025-12-03", 5.0, "Bug"),
                done(31, world.peersA[2], "2025-12-04", 2.0), done(32, world.peersA[2], "2025-12-05", 4.0, "Bug"),
                done(33, world.peersA[2], "2025-12-06", 9.0, "Bug"),
                done(41, world.peersB[0], "2026-01-05", 7.0),
                done(51, world.peersB[1], "2026-01-06", 8.0, "Bug"), done(52, world.peersB[1], "2026-01-07", 1.0),
            )
            suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L) }
            if (withTeams) world.seedMemberships()
            block(world)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(teamA.id, teamB.id))
        }
    }

    @Test
    fun `the person's team per point, every reference equals the median of the peers' Compare people cells`() = testApplication {
        usePostgresTestcontainer()
        withWorld { w ->
            val admin = seededClient("trend-reference", FlowUserRole.ADMIN)
            withMinSampleSize(2) {
                val trend = admin.personTrend("${w.base}&from=2026-01-01&to=2026-01-29&accountId=${w.person}")

                assertEquals(listOf("2026-01-01", "2026-01-15", "2026-01-29"), trend.windows.map { it.end })
                assertNull(trend.windows[0].team, "no membership covers Jan 1")
                assertEquals(w.teamA.id, trend.windows[1].team?.id, "Jan 15: team A")
                assertEquals(w.teamB.id, trend.windows[2].team?.id, "Jan 29: team B - the move is visible from the windows")
                assertEquals(w.teamA.name, trend.windows[1].team?.name, "the name rides along")
                assertEquals(w.teamB.name, trend.windows[2].team?.name)

                val compared = admin.assertReferenceIsPeerMedian(
                    trend, w.person, listOf(null, w.peersA, w.peersB), w.base,
                )
                assertEquals(2 * TREND_ROW_KEYS.size, compared, "two windows with a team x every row")

                // Hand-computed. Jan 15, team A: the peers delivered 1, 2 and 3 items; the person's own 5 would make the median 2.5.
                val items = trend.row("deliveredItems").points
                assertEquals(5.0, items[1].value, "the person's own figure is untouched")
                assertEquals(2.0, items[1].reference?.value, "median of 1, 2, 3 - the person is not one of the peers")
                assertEquals(3, items[1].reference?.peers, "the fourth member of team A has no activity: not a peer (else 1.5 of 4)")
                assertEquals(2.0, trend.row("deliveredMd").points[1].reference?.value)
                // With a minimum sample of 2, peer 1's one cycle time is hidden and peers 2 and 3 show 4.0: two peers, no value.
                val cycle = trend.row("cycleWorkingDaysP50").points[1].reference
                assertNotNull(cycle)
                assertEquals(2, cycle.peers)
                assertNull(cycle.value, "fewer than 3 peers with a value")
                // Jan 29, team B has two peers: a count row has a value for both of them, still under the minimum.
                assertEquals(2, items[2].reference?.peers)
                assertNull(items[2].reference?.value)
                // Jan 1: no team, no reference on any row.
                assertTrue(trend.rows.all { it.points[0].reference == null })
            }
        }
    }

    @Test
    fun `a slice applies to the peers as to the person - a member with no task under it is no peer`() = testApplication {
        usePostgresTestcontainer()
        withWorld { w ->
            val admin = seededClient("trend-reference-slice", FlowUserRole.ADMIN)
            val slice = "${w.base}&activityType=Bug"
            withMinSampleSize(2) {
                val trend = admin.personTrend("$slice&from=2026-01-01&to=2026-01-29&accountId=${w.person}")
                val compared = admin.assertReferenceIsPeerMedian(trend, w.person, listOf(null, w.peersA, w.peersB), slice)
                assertEquals(2 * TREND_ROW_KEYS.size, compared)
                // Bug items on Jan 15: the person 2; team A's peers 1, 1 and 2 (the quiet member none) -> median 1.0 over three.
                val items = trend.row("deliveredItems").points
                assertEquals(2.0, items[1].value)
                assertEquals(1.0, items[1].reference?.value)
                assertEquals(3, items[1].reference?.peers)
                // Jan 29, team B: only one peer has a Bug, the other has no activity under the slice: one peer, no value.
                assertEquals(1, items[2].reference?.peers)
                assertNull(items[2].reference?.value)
            }
        }
    }

    @Test
    fun `memberships change nothing but the team and the reference - the figures and the cue are the person's own`() = testApplication {
        usePostgresTestcontainer()
        withWorld(withTeams = false) { w ->
            val admin = seededClient("trend-reference-cue", FlowUserRole.ADMIN)
            val query = "${w.base}&from=2026-01-01&to=2026-01-29&accountId=${w.person}"
            val without = admin.personTrend(query)
            w.seedMemberships()
            val with = admin.personTrend(query)
            withMinSampleSize(2) {
                val tight = admin.personTrend(query)
                admin.assertPersonTrendIsComparePeople(tight, w.person, w.peersA[0], w.base)
            }

            assertTrue(with.windows.any { it.team != null } && without.windows.all { it.team == null })
            for (row in with.rows) {
                val bare = without.row(row.key)
                val figures = { r: PersonTrendRow -> r.points.map { Triple(it.value, it.n, it.hidden) } }
                assertEquals(figures(bare), figures(row), "${row.key}: the person's figures")
                assertEquals(bare.trend, row.trend, "${row.key}: the cue is the person's own")
            }
            assertEquals(without.workMix, with.workMix, "the work mix has no reference and does not move")
            assertEquals(without.windows.map { it.coverage }, with.windows.map { it.coverage })
        }
    }

    @Test
    fun `a person with no membership at all has no team and no reference`() = testApplication {
        usePostgresTestcontainer()
        withWorld(withTeams = false) { w ->
            val admin = seededClient("trend-reference-none", FlowUserRole.ADMIN)
            val trend = admin.personTrend("${w.base}&from=2026-01-01&to=2026-01-29&accountId=${w.person}")
            assertTrue(trend.windows.all { it.team == null })
            assertTrue(trend.rows.all { row -> row.points.all { it.reference == null } })
            assertEquals(0, admin.assertReferenceIsPeerMedian(trend, w.person, listOf(null, null, null), w.base))
            admin.assertPersonTrendIsComparePeople(trend, w.person, w.peersA[0], w.base)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
