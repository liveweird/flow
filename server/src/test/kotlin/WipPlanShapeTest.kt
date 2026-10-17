package ch.nokillswit

import ch.nokillswit.metrics.teamTaskWipSql
import ch.nokillswit.reports.SnapshotScope
import ch.nokillswit.reports.WipDoneRequest
import ch.nokillswit.reports.WipUserRequest
import ch.nokillswit.reports.wipDoneBandSql
import ch.nokillswit.reports.wipUserOpenBandSql
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val plan = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
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
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
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
            val plan = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
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
}
