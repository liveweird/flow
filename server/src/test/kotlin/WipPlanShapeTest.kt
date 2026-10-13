package ch.nokillswit

import ch.nokillswit.metrics.teamTaskWipSql
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
}
