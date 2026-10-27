package ch.nokillswit

import ch.nokillswit.metrics.teamTaskWipSql
import ch.nokillswit.reports.SnapshotScope
import ch.nokillswit.reports.WipDoneRequest
import ch.nokillswit.reports.WipTaskDaysRequest
import ch.nokillswit.reports.WipUserRequest
import ch.nokillswit.reports.wipDoneBandSql
import ch.nokillswit.reports.wipUserOpenBandSql
import ch.nokillswit.reports.wipUserDayCountsSql
import ch.nokillswit.reports.wipUserTaskCountsSql
import ch.nokillswit.reports.wipUserTaskDaysSql
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * Pins the PLAN SHAPE of the WIP team/task statement (`DeriveWipStep.kt` `teamTaskWipSql`), not its timing: the team
 * is resolved by set-based joins, never by a correlated sub-select per (day, task). The sub-select version ran ~4.8 M
 * times at scale 20 and read `dim_sprint` by index or by seq scan at a near-equal cost, so a DERIVE took ~32 s or ~53 s
 * depending on the plan (`.claude/docs/build-times.md` WHY 16). A timing check at stub scale cannot see that flip; a
 * `SubPlan` node in the plan is exactly what brings it back. `EXPLAIN` without `ANALYZE` plans the `INSERT` without
 * running it, so the shared derived connection stays untouched.
 */
class WipPlanShapeTest {
    @Test
    fun `the WIP team-task statement resolves sprint and assignee teams without a per-row sub-select`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val plan = PostgresTestSupport.connect()
            .use { conn ->
                conn.createStatement().use { st ->
                    val sql = "EXPLAIN (FORMAT JSON) " + teamTaskWipSql(connId, DerivedStubFixture.PINNED_NOW, configRevision = 0)
                    st.executeQuery(sql).use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                }
            }

        assertTrue(plan.contains("\"dim_sprint\""), "the plan must read metrics.dim_sprint at all:\n$plan")
        assertFalse(
            Regex("\"Parent Relationship\":\\s*\"SubPlan\"").containsMatchIn(plan),
            "a correlated sub-select (SubPlan) is back in the WIP team/task statement:\n$plan",
        )
    }

    /**
     * The report 9 DONE band (`reports/WipDoneBand.kt`, decision B) is ONE statement over the period's days, never a per-day loop and
     * never a correlated sub-select per (day, item): no `SubPlan` node in its plan for any scope, and the `task_domain` lookup of a
     * DOMAIN scope is a join. (Its timing — 63 ms for 4,500 done items over 186 days, 8-13 ms for a team or a domain — was
     * measured with `EXPLAIN ANALYZE` on a synthetic 6,000-task connection; `build-times.md` WHY 16.)
     */
    @Test
    fun `the DONE band statement has no per-row sub-select for any scope`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val scopes = listOf(SnapshotScope.AllTeams, SnapshotScope.Team(1u), SnapshotScope.Unassigned, SnapshotScope.Domain("AAA"))
        PostgresTestSupport.connect().use { conn ->
            for (scope in scopes) {
                val built = wipDoneBandSql(WipDoneRequest(listOf(connId), scope, true, true, "2025-09-01", "2026-03-05", accountIds = null))
                val plan = conn.prepareStatement("EXPLAIN (FORMAT JSON) " + built.sql).use { st ->
                    built.arguments.forEachIndexed { index, value -> st.setString(index + 1, value) }
                    st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                }
                assertTrue(plan.contains("\"item_stage\""), "the DONE band must read metrics.item_stage ($scope):\n$plan")
                assertFalse(
                    Regex("\"Parent Relationship\":\\s*\"SubPlan\"").containsMatchIn(plan),
                    "a correlated sub-select (SubPlan) is in the DONE band statement ($scope):\n$plan",
                )
            }
        }
    }

    /**
     * Report 9 at USER level (brief 04, `reports/WipUserReport.kt`): the picks' open bands are ONE statement, the team resolved by
     * LEFT JOINs onto the held (day, task) rows, never a correlated sub-select per row. Its measured cost (a synthetic 6,600-task
     * connection, 8 people over 278 days, ~80 ms) is in `reports.md`; a `SubPlan` in the plan is what would bring the cost back.
     */
    @Test
    fun `the USER-level open-band statement has no per-row sub-select`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        for (team in listOf(1u, 0u)) {
            val built = wipUserOpenBandSql(WipUserRequest(listOf(connId), team, listOf("a", "b"), "2025-09-01", "2026-03-05"))
            val plan = PostgresTestSupport.connect()
                .use { conn ->
                    conn.prepareStatement("EXPLAIN (FORMAT JSON) " + built.sql).use { st ->
                        built.arguments.forEachIndexed { index, value -> st.setString(index + 1, value) }
                        st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                    }
                }
            val readsBoth = plan.contains("\"item_stage\"") && plan.contains("\"task_assignee\"")
            assertTrue(readsBoth, "the statement reads both bridges (team $team):\n$plan")
            assertFalse(
                Regex("\"Parent Relationship\":\\s*\"SubPlan\"").containsMatchIn(plan),
                "a correlated sub-select (SubPlan) is in the USER-level WIP statement (team $team):\n$plan",
            )
        }
    }

    /**
     * Compare-people (report 18) reads the picks' WIP across EVERY team with its own statement (`wipUserTaskDaysSql`): the same
     * `held`/`held_days` CTEs as the USER-level open bands, restricted to the working stages, and NO team resolution -- no sprint or
     * membership join, hence no `SubPlan` either.
     */
    @Test
    fun `the compare-people task-days statement joins no team and has no per-row sub-select`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val built = wipUserTaskDaysSql(WipTaskDaysRequest(listOf(connId), listOf("a", "b"), "2025-09-01", "2026-03-05"))
        assertFalse(built.sql.contains("dim_sprint") || built.sql.contains("team_membership"), "no team resolution:\n${built.sql}")
        val plan = PostgresTestSupport.connect()
            .use { conn ->
                conn.prepareStatement("EXPLAIN (FORMAT JSON) " + built.sql).use { st ->
                    built.arguments.forEachIndexed { index, value -> st.setString(index + 1, value) }
                    st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                }
            }
        assertTrue(plan.contains("\"item_stage\"") && plan.contains("\"task_assignee\""), "the statement reads both bridges:\n$plan")
        assertFalse(plan.contains("\"dim_sprint\"") || plan.contains("\"team_membership\""), "no team join in the plan:\n$plan")
        val subPlan = Regex("\"Parent Relationship\":\\s*\"SubPlan\"").containsMatchIn(plan)
        assertFalse(subPlan, "a SubPlan is in the task-days statement:\n$plan")
    }

    /**
     * Compare-people's aggregated statements (`wipUserDayCountsSql`, `wipUserTaskCountsSql`) group the very `held_days` rows of the
     * task-days statement in Postgres: they read both bridges, join no team, group (an Aggregate node is in the plan, so the rows
     * are counted server-side, never shipped per task-day) and have no per-row sub-select.
     */
    @Test
    fun `the compare-people aggregated statements group in the database with no team join and no sub-select`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val request = WipTaskDaysRequest(listOf(connId), listOf("a", "b"), "2025-09-01", "2026-03-05")
        val statements = mapOf(
            "day counts" to wipUserDayCountsSql(request),
            "task counts" to wipUserTaskCountsSql(request, listOf(LocalDate.parse("2025-09-01"), LocalDate.parse("2025-09-02"))),
        )
        val subPlan = Regex("\"Parent Relationship\":\\s*\"SubPlan\"")
        val aggregate = Regex("\"Node Type\":\\s*\"Aggregate\"")
        for ((label, built) in statements) {
            assertFalse(built.sql.contains("dim_sprint") || built.sql.contains("team_membership"), "$label: no team resolution")
            val plan = PostgresTestSupport.connect()
                .use { conn ->
                    conn.prepareStatement("EXPLAIN (FORMAT JSON) " + built.sql).use { st ->
                        built.arguments.forEachIndexed { index, value -> st.setString(index + 1, value) }
                        st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                    }
                }
            assertTrue(plan.contains("\"item_stage\"") && plan.contains("\"task_assignee\""), "$label: reads both bridges:\n$plan")
            assertTrue(aggregate.containsMatchIn(plan), "$label: groups in the database:\n$plan")
            assertFalse(plan.contains("\"dim_sprint\"") || plan.contains("\"team_membership\""), "$label: no team join:\n$plan")
            assertFalse(subPlan.containsMatchIn(plan), "$label: a SubPlan is in the statement:\n$plan")
        }
    }

    /**
     * Person trend (report 20) reads the tasks held on the LAST listed day of each window with ONE statement narrowed by
     * `WipTaskDaysRequest.days`: the narrowing is a `day IN (...)` of validated ISO dates inside `day_range` (absent without `days`), so
     * the plan keeps its shape -- both bridges, no team join, no `SubPlan` -- and a value that is not an ISO date cannot reach the SQL.
     */
    @Test
    fun `the days-narrowed compare-people statements keep the plan shape and only ever interpolate ISO dates`() = runBlocking<Unit> {
        val connId = DerivedStubFixture.connectionId()
        val plain = WipTaskDaysRequest(listOf(connId), listOf("a"), "2025-09-01", "2026-03-05")
        val narrowed = plain.copy(days = listOf("2025-09-10", "2025-12-31"))
        assertFalse(wipUserTaskDaysSql(plain).sql.contains("day IN"), "no days, no narrowing")
        val subPlan = Regex("\"Parent Relationship\":\\s*\"SubPlan\"")
        for ((label, built) in mapOf("task days" to wipUserTaskDaysSql(narrowed), "day counts" to wipUserDayCountsSql(narrowed))) {
            assertTrue(built.sql.contains("AND day IN ('2025-09-10', '2025-12-31')"), "$label: the days are listed:\n${built.sql}")
            val plan = PostgresTestSupport.connect()
                .use { conn ->
                    conn.prepareStatement("EXPLAIN (FORMAT JSON) " + built.sql).use { st ->
                        built.arguments.forEachIndexed { index, value -> st.setString(index + 1, value) }
                        st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString(1)) } }
                    }
                }
            assertTrue(plan.contains("\"item_stage\"") && plan.contains("\"task_assignee\""), "$label: reads both bridges:\n$plan")
            assertFalse(plan.contains("\"dim_sprint\"") || plan.contains("\"team_membership\""), "$label: no team join:\n$plan")
            assertFalse(subPlan.containsMatchIn(plan), "$label: a SubPlan is in the statement:\n$plan")
        }
        assertFailsWith<DateTimeParseException> { wipUserTaskDaysSql(plain.copy(days = listOf("2026-01-01') OR ('1"))) }
        assertFailsWith<DateTimeParseException> { wipUserDayCountsSql(plain.copy(days = listOf("not-a-day"))) }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
