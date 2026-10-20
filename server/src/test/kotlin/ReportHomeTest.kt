package ch.nokillswit

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.CommitmentsReport
import ch.nokillswit.reports.HOME_MAX_TEAMS
import ch.nokillswit.reports.HomeFigure
import ch.nokillswit.reports.HomeOverview
import ch.nokillswit.reports.HomeUnassigned
import ch.nokillswit.reports.HomeVerdict
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.SprintConsistencyReport
import ch.nokillswit.reports.ThroughputReport
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.home
import ch.nokillswit.reports.homeItems
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass

private const val TOL = 1e-9
private val DEFAULT_TOLERANCE = BigDecimal("0.25")

/**
 * `GET /api/v1/reports/home` (`.claude/docs/reports.md` "Home overview"). The derived fixture is READ-only: every team figure is
 * graded against an INDEPENDENT re-derivation from the fact rows (`HomeOracle.kt`), the last sprint against `fact_sprint` and the
 * three sprint reports. The hand-built world (`HomeScenario.kt`) pins the window edges, the DST-crossing previous window, the medians,
 * PRED, the findings, the hidden figures and the trend verdicts to hand-computed numbers.
 */
class ReportHomeTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private fun floTeamId(connId: UInt): UInt = oracleFixtureTeam(connId)

    private suspend fun minSample(): Int =
        suspendTransaction(sharedDatabaseForTests()) { DerivedStubFixture.metricsSettings().read().minSampleSize }

    @Test
    fun `the fixture's every team figure equals an independent re-derivation from the fact rows over the 90-day window`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val zone = reportZone()
            val min = minSample()
            val team = floTeamId(connId)
            // The pinned DERIVE clock (done tasks and open items) and one clock inside the epics' history (their PRED samples).
            for (now in listOf(DerivedStubFixture.PINNED_NOW, homeAt("2025-11-30T12:00:00+01:00"))) {
                val overview = service().home(now, listOf(team))
                val mine = overview.teams.single { it.teamId == team }
                assertHomeTeamEqualsOracle(mine, listOf(connId), now, zone, min, DEFAULT_TOLERANCE)
                val (cur, prev) = oracleWindows(zone, now)
                assertEquals(cur.from.toString(), overview.window.from)
                assertEquals(cur.to.toString(), overview.window.to)
                assertEquals(prev.from.toString(), overview.previousWindow.from)
                assertEquals(prev.to.toString(), overview.previousWindow.to)
                assertEquals(zone.id, overview.timeZone)
                assertEquals(25, overview.epicEstimateGapTolerancePercent)
                assertEquals("UNIT", overview.meta.level.name)
                assertEquals("TASK", overview.meta.domainView.name)
                assertEquals(cur.from.toString(), overview.meta.from)
                assertEquals(min, overview.meta.minSampleSize)
                assertNotNull(overview.meta.derivedAt)
                val weeklyItems = mine.throughput.weeks.sumOf { it.deliveredItems }
                assertEquals(mine.throughput.deliveredItems.n, weeklyItems, "weeks add up to the window")
            }
            val atPin = service().home(DerivedStubFixture.PINNED_NOW, listOf(team)).teams.single { it.teamId == team }
            assertTrue(atPin.granularity.cycleWorkingDays.n > 0 && atPin.throughput.deliveredItems.n > 0, "must carry deliveries")
        }

    private suspend inline fun <reified T> HttpClient.report(path: String, query: String): T {
        val response = get("/api/v1/reports/$path?$query")
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.body()
    }

    private suspend fun HttpClient.sprintReports(
        connId: UInt,
        team: UInt,
    ): Triple<CommitmentsReport, ThroughputReport, SprintConsistencyReport> {
        val query = "connectionId=$connId&teamId=$team&lastSprints=1"
        return Triple(report("commitments", query), report("throughput", query), report("sprint-consistency", query))
    }

    @Test
    fun `the last closed sprint per team equals fact_sprint and the commitments, throughput and sprint-consistency reports`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val team = floTeamId(connId)
            val sprint = service().home(DerivedStubFixture.PINNED_NOW, listOf(team)).teams.single { it.teamId == team }.lastSprint
            assertNotNull(sprint, "the fixture team has closed sprints")
            val row = jdbcRows(
                """SELECT fs.sprint_id, ds.name, ds.start_at, fs.complete_at, fs.capacity_md, ds.capacity_source, fs.committed_md,
                          fs.committed_items, fs.final_md, fs.final_items, fs.delivered_md, fs.delivered_items
                   FROM metrics.fact_sprint fs JOIN metrics.dim_sprint ds ON ds.connection_id = fs.connection_id AND ds.sprint_id = fs.sprint_id
                   WHERE fs.connection_id = ? AND fs.team_id = ? AND fs.complete_at IS NOT NULL ORDER BY fs.complete_at DESC LIMIT 1""",
                connId.toInt(), team.toInt(),
            ).single()
            assertEquals((row[0] as Number).toLong(), sprint.sprintId)
            assertEquals(row[1], sprint.name)
            assertEquals((row[2] as Number?)?.toLong(), sprint.startAt)
            assertEquals((row[3] as Number).toLong(), sprint.completedAt)
            assertEquals((row[4] as Number?)?.toDouble(), sprint.capacityMd)
            assertEquals(row[5], sprint.capacitySource)
            assertEquals((row[6] as Number).toDouble(), sprint.initialMd, TOL)
            assertEquals((row[7] as Number).toInt(), sprint.initialItems)
            assertEquals((row[8] as Number).toDouble(), sprint.finalMd, TOL)
            assertEquals((row[9] as Number).toInt(), sprint.finalItems)
            assertEquals((row[10] as Number).toDouble(), sprint.deliveredMd, TOL)
            assertEquals((row[11] as Number).toInt(), sprint.deliveredItems)

            val client = seededClient("home-sprints", UserRole.USER)
            val (commitments, throughput, consistency) = client.sprintReports(connId, team)
            val c = commitments.sprints.single()
            assertEquals(sprint.sprintId, c.sprintId)
            assertEquals(listOf(c.initialMd, c.finalMd, c.capacityMd), listOf(sprint.initialMd, sprint.finalMd, sprint.capacityMd))
            assertEquals(listOf(c.initialItems, c.finalItems), listOf(sprint.initialItems, sprint.finalItems))
            assertEquals(c.capacitySource, sprint.capacitySource)
            val t = throughput.bySprint.single()
            assertEquals(listOf(sprint.sprintId, sprint.deliveredMd), listOf(t.sprintId, t.deliveredMd))
            assertEquals(sprint.deliveredItems, t.deliveredItems)
            val s = consistency.sprints.single()
            assertEquals(sprint.sprintId, s.sprintId)
            assertEquals(sprint.snapshot, s.snapshot, "the frozen snapshot is the sprint-consistency one")
            assertEquals(sprint.drift, s.drift, "drift is the sprint-consistency rule (all fourteen figures)")
            assertEquals(listOf(sprint.finalMd, sprint.deliveredMd), listOf(s.finalMd, s.deliveredMd))
            val resolved = service().home(DerivedStubFixture.PINNED_NOW, listOf(team)).meta.resolvedSprints
            assertTrue(resolved.any { it.teamId == team && it.sprintIds == listOf(sprint.sprintId) })
        }

    @Test
    fun `WIP counts and age percentiles equal an independent computation over the open items at the pinned clock`() = testApplication {
        usePostgresTestcontainer()
        withHomeScenario { world ->
            val zone = reportZone()
            val team = service().home(HomeScenario.NOW, listOf(world.team)).teams.single { it.teamId == world.team }
            // Hand-computed: five tasks in progress, ages 0.5, 2, 2, 3 and 7 working days at midnight 2026-02-18 (a Saturday start
            // counts nothing).
            assertEquals(5, team.wipTasks.count)
            assertFalse(team.wipTasks.hidden)
            assertEquals(2.0, team.wipTasks.p50!!, TOL)
            assertEquals(4.6, team.wipTasks.p85!!, TOL)
            assertEquals(6.2, team.wipTasks.p95!!, TOL)
            assertEquals(2, team.wipEpics.count)
            val epicAges = listOf(team.wipEpics.p50, team.wipEpics.p85, team.wipEpics.p95)
            assertTrue(team.wipEpics.hidden && epicAges.all { it == null }, "two epics are under the minimum of 3")
            assertHomeTeamEqualsOracle(team, listOf(world.connId), HomeScenario.NOW, zone, HomeScenario.MIN_SAMPLE, DEFAULT_TOLERANCE)
            val other = service().home(HomeScenario.NOW, listOf(world.team, world.other)).teams.single { it.teamId == world.other }
            assertEquals(1, other.wipTasks.count, "another team's open task counts for that team only")
        }
    }

    private fun HomeFigure.core() = Triple(value, n, hidden)

    @Test
    fun `the previous window's figures equal the current figures of the overview read 90 days earlier`() = testApplication {
        usePostgresTestcontainer()
        val picked = listOf(floTeamId(DerivedStubFixture.connectionId()))
        val now = DerivedStubFixture.PINNED_NOW
        // 2025-12-05 01:00 Warsaw: no DST change between the two clocks
        val earlier = service().home(now - 90 * 24 * 3_600_000L, picked)
        val later = service().home(now, picked)
        assertEquals(earlier.window, later.previousWindow, "the windows tile")
        assertTrue(later.teams.isNotEmpty())
        for (team in later.teams) {
            val twin = earlier.teams.single { it.teamId == team.teamId }
            fun check(label: String, now: HomeFigure, before: HomeFigure) {
                val previous = assertNotNull(now.previous, "$label previous")
                assertEquals(before.core(), Triple(previous.value, previous.n, previous.hidden), "${team.name} $label")
            }
            val g = team.granularity
            val e = twin.granularity
            check("estimate", g.estimateMd, e.estimateMd)
            check("cycle", g.cycleWorkingDays, e.cycleWorkingDays)
            check("cost", g.costMd, e.costMd)
            for ((a, b) in listOf(
                team.accuracy.tasks.pred10 to twin.accuracy.tasks.pred10, team.accuracy.tasks.pred25 to twin.accuracy.tasks.pred25,
                team.accuracy.tasks.pred50 to twin.accuracy.tasks.pred50, team.accuracy.epics.pred10 to twin.accuracy.epics.pred10,
                team.accuracy.epics.pred25 to twin.accuracy.epics.pred25, team.accuracy.epics.pred50 to twin.accuracy.epics.pred50,
            )) {
                check("pred", a, b)
            }
            val t = team.throughput
            val u = twin.throughput
            check("delivered MD", t.deliveredMd, u.deliveredMd)
            check("delivered items", t.deliveredItems, u.deliveredItems)
            check("cycle p50", t.cycleP50, u.cycleP50)
            check("cycle p85", t.cycleP85, u.cycleP85)
            check("no estimate", team.quality.tasksNoEstimate, twin.quality.tasksNoEstimate)
            check("unassigned", team.quality.tasksUnassigned, twin.quality.tasksUnassigned)
            check("estimated late", team.quality.tasksEstimatedLate, twin.quality.tasksEstimatedLate)
        }
    }

    private fun HomeFigure.verdict(): HomeVerdict? = previous?.verdict

    @Test
    fun `hand-built rows pin the window edges, the medians, PRED, the findings, hidden figures and the trend verdicts exactly`() =
        testApplication {
            usePostgresTestcontainer()
            withHomeScenario { world ->
                val zone = reportZone()
                val overview = service().home(HomeScenario.NOW, listOf(world.team))
                assertEquals("2025-11-21", overview.window.from)
                assertEquals("2026-02-18", overview.window.to)
                assertEquals("2025-08-23", overview.previousWindow.from)
                assertEquals("2025-11-20", overview.previousWindow.to)
                val team = overview.teams.single { it.teamId == world.team }
                assertHomeTeamEqualsOracle(team, listOf(world.connId), HomeScenario.NOW, zone, HomeScenario.MIN_SAMPLE, DEFAULT_TOLERANCE)
                assertNull(team.lastSprint, "no closed sprint")

                // Section 2: the tasks 101..105 (edge: 101 at the first millisecond, 104 at the last) against 111..115 (113 on the
                // 180-day edge).
                val g = team.granularity
                assertEquals(listOf(2.5, 4, false), listOf(g.estimateMd.value, g.estimateMd.n, g.estimateMd.hidden))
                assertEquals(listOf(2.0, 3, false), g.estimateMd.previous!!.let { listOf(it.value, it.n, it.hidden) })
                assertEquals(HomeVerdict.WORSE, g.estimateMd.verdict(), "a larger median batch is worse (lower is better)")
                assertEquals(0.5, g.estimateMd.previous.difference!!, TOL)
                assertEquals(listOf(3.0, 5), listOf(g.cycleWorkingDays.value, g.cycleWorkingDays.n))
                assertEquals(HomeVerdict.BETTER, g.cycleWorkingDays.verdict())
                assertEquals(-1.0, g.cycleWorkingDays.previous!!.difference!!, TOL)
                assertEquals(3.1, g.costMd.value!!, TOL)
                assertEquals(HomeVerdict.WORSE, g.costMd.verdict())

                // Section 3: PRED at start; 103 and 112 sit exactly on the 25 % and 50 % thresholds and count as within.
                val tasks = team.accuracy.tasks
                assertEquals(listOf(0.5, 0.75, 1.0), listOf(tasks.pred10.value, tasks.pred25.value, tasks.pred50.value))
                assertEquals(listOf(2, 3, 4), listOf(tasks.pred10.numerator, tasks.pred25.numerator, tasks.pred50.numerator))
                val predVerdicts = listOf(tasks.pred10.verdict(), tasks.pred25.verdict(), tasks.pred50.verdict())
                assertEquals(listOf(HomeVerdict.WORSE, HomeVerdict.BETTER, null), predVerdicts)
                val epics = team.accuracy.epics
                assertTrue(epics.pred25.hidden && epics.pred25.value == null && epics.pred25.n == 1, "one epic is under the minimum of 3")
                assertEquals(listOf(0, 1, 1), listOf(epics.pred10.numerator, epics.pred25.numerator, epics.pred50.numerator))
                assertNull(epics.pred25.verdict())

                // Section 6: delivery 11 MD / 5 items against 7 MD / 5 items; cycle p85 6.2 against 5.4.
                val t = team.throughput
                assertEquals(listOf(11.0, 5), listOf(t.deliveredMd.value, t.deliveredMd.n))
                assertEquals(HomeVerdict.BETTER, t.deliveredMd.verdict())
                assertEquals(listOf(5.0, 5.0), listOf(t.deliveredItems.value, t.deliveredItems.previous!!.value))
                assertNull(t.deliveredItems.verdict(), "equal item counts are a tie")
                assertEquals(HomeVerdict.BETTER, t.cycleP50.verdict())
                assertEquals(6.2, t.cycleP85.value!!, TOL)
                assertEquals(5.4, t.cycleP85.previous!!.value!!, TOL)
                assertEquals(HomeVerdict.WORSE, t.cycleP85.verdict())
                assertEquals(14, t.weeks.size)
                assertEquals(listOf("2025-11-17", "2026-02-16"), listOf(t.weeks.first().bucketStart, t.weeks.last().bucketStart))
                assertEquals(listOf(1.0, 5.0), listOf(t.weeks.first().deliveredMd, t.weeks.last().deliveredMd))

                // Section 7: the task findings trend, the epic ones (the Data quality population) carry none.
                val q = team.quality
                assertEquals(listOf(1.0, 2.0), listOf(q.tasksNoEstimate.value, q.tasksNoEstimate.previous!!.value))
                assertEquals(HomeVerdict.BETTER, q.tasksNoEstimate.verdict())
                assertEquals(HomeVerdict.BETTER, q.tasksUnassigned.verdict())
                assertEquals(HomeVerdict.WORSE, q.tasksEstimatedLate.verdict())
                val epicFindings = listOf(q.epicsNoEstimate.value, q.epicsNoDates.value, q.epicsEstimateGap.value)
                assertEquals(listOf(1.0, 1.0, 1.0), epicFindings, "201, 202, and 202 (203 is exactly +25 %)")
                assertTrue(listOf(q.epicsNoEstimate, q.epicsNoDates, q.epicsEstimateGap).all { it.previous == null })
            }
        }

    @Test
    fun `the picked teams are listed in name order, unassigned counts every active team, and no pick lists the first three by name`() =
        testApplication {
            usePostgresTestcontainer()
            withHomeScenario { world ->
                val retired = TestTeams.seed(SyncedStubFixture.unique("chome-r"))
                TestTeams.service.delete(retired)
                val idle = TestTeams.seed(SyncedStubFixture.unique("bhome-z"))
                try {
                    // Postgres' own ORDER BY name (whatever the collation) is the order; our teams are in it.
                    val dbOrder = jdbcRows("SELECT id FROM teams WHERE marked_as_deleted = false ORDER BY name, id")
                        .map { (it[0] as Number).toInt().toUInt() }
                    val picked = listOf(idle, world.team) // the reverse of the name order: the pick order is irrelevant
                    val overview = service().home(HomeScenario.NOW, picked)
                    assertEquals(dbOrder.filter { it in picked }, overview.teams.map { it.teamId }, "only the picked teams, in name order")
                    assertEquals(listOf(world.team, idle), overview.teams.map { it.teamId })
                    assertFalse(overview.teams.any { it.teamId == retired }, "a soft-deleted team is not listed")
                    val empty = overview.teams.single { it.teamId == idle }
                    assertNull(empty.lastSprint)
                    assertEquals(14, empty.throughput.weeks.size, "an idle team still has its zero-filled weeks")
                    assertTrue(empty.throughput.weeks.all { it.deliveredItems == 0 && it.deliveredMd == 0.0 })
                    assertEquals(listOf(0.0, 0), listOf(empty.throughput.deliveredItems.value, empty.throughput.deliveredItems.n))
                    val estimate = empty.granularity.estimateMd
                    assertTrue(estimate.hidden && estimate.value == null && estimate.n == 0)
                    assertTrue(empty.accuracy.tasks.pred25.hidden && empty.wipTasks.count == 0 && empty.wipTasks.hidden)
                    assertEquals(0.0, empty.quality.tasksNoEstimate.value)
                    assertEquals(0.0, empty.quality.epicsEstimateGap.value)

                    // The remainder is the work of NO ACTIVE team (no team, or a soft-deleted one), independent of the pick.
                    val (cur, _) = oracleWindows(reportZone(), HomeScenario.NOW)
                    val noActiveTeam = (jdbcRows(
                        """SELECT count(*) FROM metrics.fact_task_delivery f JOIN source_connections c ON c.id = f.connection_id
                           WHERE c.marked_as_deleted = false AND f.is_subtask = false AND f.done_at >= ? AND f.done_at < ?
                             AND (f.credit_team_id IS NULL
                                  OR f.credit_team_id NOT IN (SELECT id FROM teams WHERE marked_as_deleted = false))""",
                        cur.fromMs, cur.toMs,
                    ).single()[0] as Number).toInt()
                    assertEquals(noActiveTeam, overview.unassigned.doneTasks)
                    assertTrue(overview.unassigned.doneTasks >= 1 && overview.unassigned.openTasks >= 1 && overview.unassigned.epics >= 1)
                    val other = service().home(HomeScenario.NOW, listOf(world.other))
                    assertTrue(other.teams.single().throughput.deliveredItems.n > 0, "the other team has work of its own")
                    assertEquals(overview.unassigned, other.unassigned, "another team's work is never unassigned, whoever is picked")

                    // No pick: the first three active teams by name, whoever asks.
                    val default = service().home(HomeScenario.NOW)
                    assertEquals(dbOrder.take(HOME_MAX_TEAMS), default.teams.map { it.teamId })
                    assertEquals(overview.unassigned, default.unassigned)
                } finally {
                    cleanUpTeams(listOf(idle))
                }
            }
        }

    @Test
    fun `the teamId 400 matrix, duplicates collapse and the pick order does not matter over HTTP`() = testApplication {
        usePostgresTestcontainer()
        withHomeScenario { world ->
            val retired = TestTeams.seed(SyncedStubFixture.unique("chome-r"))
            TestTeams.service.delete(retired)
            val third = TestTeams.seed(SyncedStubFixture.unique("chome-3"))
            val fourth = TestTeams.seed(SyncedStubFixture.unique("dhome-4"))
            try {
                val client = seededClient("home-teams", UserRole.USER)
                suspend fun status(query: String) = client.get("/api/v1/reports/home$query").status
                val bad = mapOf(
                    "teamId 0" to "?teamId=0",
                    "a non-integer" to "?teamId=abc",
                    "a negative id" to "?teamId=-1",
                    "beyond the id range" to "?teamId=4294967296",
                    "an unknown team" to "?teamId=987654321",
                    "an inactive team" to "?teamId=$retired",
                    "a valid pick beside an inactive one" to "?teamId=${world.team}&teamId=$retired",
                    "four distinct ids" to "?teamId=${world.team}&teamId=${world.other}&teamId=$third&teamId=$fourth",
                )
                for ((what, query) in bad) assertEquals(HttpStatusCode.BadRequest, status(query), what)

                val three = client.get("/api/v1/reports/home?teamId=$third&teamId=${world.other}&teamId=${world.team}")
                assertEquals(HttpStatusCode.OK, three.status, "three is the cap")
                val order = jdbcRows("SELECT id FROM teams WHERE id IN (${world.team}, ${world.other}, $third) ORDER BY name, id")
                    .map { (it[0] as Number).toInt().toUInt() }
                assertEquals(order, three.body<HomeOverview>().teams.map { it.teamId }, "name order whatever the pick order")

                val dupQuery = "teamId=${world.team}&teamId=${world.team}&teamId=${world.other}&teamId=$third&teamId=$third"
                val dup = client.get("/api/v1/reports/home?$dupQuery")
                assertEquals(HttpStatusCode.OK, dup.status, "duplicates collapse before the bound")
                assertEquals(3, dup.body<HomeOverview>().teams.size)
                val one = client.get("/api/v1/reports/home?teamId=${world.team}").body<HomeOverview>()
                assertEquals(listOf(world.team), one.teams.map { it.teamId })
            } finally {
                cleanUpTeams(listOf(third, fourth))
            }
        }
    }

    @Test
    fun `freshness lists each active connection's last successful sync and derive`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val synced = SyncedStubFixture.createConnection(namePrefix = "home-fresh", enabled = false)
        suspendTransaction(sharedDatabaseForTests()) {
            val c = DataSourceService.Connections
            c.update({ c.id eq synced }) { it[c.lastSyncSucceededAt] = 1_700_000_000_000L }
        }
        val overview = service().home(DerivedStubFixture.PINNED_NOW)
        assertEquals(1_700_000_000_000L, overview.freshness.single { it.connectionId == synced }.lastSyncAt)
        val fresh = overview.freshness.single { it.connectionId == connId }
        val derive = (jdbcRows(
            "SELECT max(finished_at) FROM metrics.derive_runs WHERE connection_id = ? AND status = 'SUCCEEDED'", connId.toInt(),
        ).single()[0] as Number).toLong()
        assertEquals(derive, fresh.lastDeriveAt)
        // The last successful ingest is the connection row's own stamp (`sync_jobs` rows are pruned).
        val stamps = jdbcRows("SELECT id, last_sync_succeeded_at FROM source_connections WHERE marked_as_deleted = false")
            .associate { (it[0] as Number).toInt().toUInt() to (it[1] as Number?)?.toLong() }
        for (row in overview.freshness) assertEquals(stamps.getValue(row.connectionId), row.lastSyncAt, "sync of ${row.name}")
        val order = jdbcRows("SELECT id FROM source_connections WHERE marked_as_deleted = false ORDER BY name, id")
            .map { (it[0] as Number).toInt().toUInt() }
        assertEquals(order, overview.freshness.map { it.connectionId }, "name order")
        val active = (jdbcRows("SELECT count(*) FROM source_connections WHERE marked_as_deleted = false").single()[0] as Number).toInt()
        assertEquals(active, overview.freshness.size, "every active connection, paused ones included")
    }

    @Test
    fun `the epic estimate gap figure and its drill follow the configured tolerance`() = testApplication {
        usePostgresTestcontainer()
        withHomeScenario { world ->
            val svc = service()
            val gap = HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP
            val gapKeys = suspend { svc.homeItems(gap, world.team, HomeScenario.NOW).items.map { it.issueKey }.sorted() }
            // Default 25 %: HE-202 (+100 %) only; HE-203 sits exactly at +25 % and is not flagged.
            val atDefault = svc.home(HomeScenario.NOW, listOf(world.team))
            assertEquals(25, atDefault.epicEstimateGapTolerancePercent)
            assertEquals(1.0, atDefault.teams.single { it.teamId == world.team }.quality.epicsEstimateGap.value)
            assertEquals(listOf("HE-202"), gapKeys())
            // 24 %: the +25 % epic is now beyond the tolerance, in the figure, the echo and the drill alike.
            withMetricsSettings(DerivedStubFixture.metricsSettings(), { it.copy(epicEstimateGapTolerancePercent = 24) }) {
                val narrow = svc.home(HomeScenario.NOW, listOf(world.team))
                assertEquals(24, narrow.epicEstimateGapTolerancePercent)
                assertEquals(2.0, narrow.teams.single { it.teamId == world.team }.quality.epicsEstimateGap.value)
                assertEquals(listOf("HE-202", "HE-203"), gapKeys())
                assertEquals(2, svc.homeItems(gap, world.team, HomeScenario.NOW).bar.count)
            }
            assertEquals(listOf("HE-202"), gapKeys(), "restored")
        }
    }

    private class Rollback : RuntimeException("roll the emptied world back")

    @Test
    fun `nothing derived reads as an empty overview with a null derivedAt, and a plain user gets 200`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId()
        val team = TestTeams.seed(SyncedStubFixture.unique("ahome-empty"))
        var empty: HomeOverview? = null
        try {
            // The shared container always holds derived data: empty it INSIDE a transaction the overview's own one joins, then roll back.
            runCatching {
                suspendTransaction(sharedDatabaseForTests()) {
                    MetricsTables.DeriveRuns.deleteAll()
                    MetricsTables.FactTaskDelivery.deleteAll()
                    MetricsTables.FactEpicDelivery.deleteAll()
                    MetricsTables.FactSprint.deleteAll()
                    empty = service().home(HomeScenario.NOW, listOf(team))
                    throw Rollback()
                }
            }.onFailure { if (it !is Rollback) throw it }
            val overview = assertNotNull(empty)
            assertNull(overview.meta.derivedAt)
            assertNull(overview.meta.configRevision)
            val mine = overview.teams.single { it.teamId == team }
            assertNull(mine.lastSprint)
            assertTrue(overview.teams.all { it.throughput.deliveredItems.n == 0 && it.wipTasks.count == 0 && it.lastSprint == null })
            assertEquals(HomeUnassigned(0, 0, 0), overview.unassigned)
            assertTrue(overview.freshness.all { it.lastDeriveAt == null }, "no successful DERIVE anywhere")
            // The rollback left the world intact: the fixture is derived again.
            assertNotNull(service().home(HomeScenario.NOW).meta.derivedAt)
        } finally {
            cleanUpTeams(listOf(team))
        }
        val client = seededClient("home-plain", UserRole.USER)
        val response = client.get("/api/v1/reports/home")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HomeOverview>()
        assertEquals(90, ChronoUnit.DAYS.between(LocalDate.parse(body.window.from), LocalDate.parse(body.window.to)) + 1)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
