package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V24's TEAM_DEFAULT seed (domain-model A32), run as the migration's own SELECT (read from the file, so the test can never drift from
 * it) inside a transaction that is rolled back: the most recent configured per-sprint figure of each ACTIVE team on an ACTIVE connection.
 * Every case is hand-inserted into the legacy `team_sprint_capacity` + `norm.sprints` + `board_team_map` rows and the result is read
 * back per team.
 */
class CapacitySeedMigrationTest {
    private val seedSelect: String = Files.readString(Path.of("src/main/resources/db/migration/V24__capacity_management.sql"))
        .substringAfter("INSERT INTO metrics.team_capacity_defaults (team_id, default_capacity_md, updated_at, updated_by_user_id)")
        .trim().removeSuffix(";")

    private fun <T> rolledBack(body: (Connection) -> T): T =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { c ->
            c.autoCommit = false
            try {
                body(c)
            } finally {
                c.rollback()
            }
        }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.sprint(connectionId: UInt, sprintId: Long, boardId: Long, startAt: Long?, capacityMd: String) {
        run(
            "INSERT INTO norm.sprints (connection_id, sprint_id, board_id, name, state, start_at) VALUES " +
                "($connectionId, $sprintId, $boardId, 'S$sprintId', 'closed', ${startAt ?: "NULL"})",
        )
        run(
            "INSERT INTO metrics.team_sprint_capacity (connection_id, sprint_id, capacity_md) " +
                "VALUES ($connectionId, $sprintId, $capacityMd)",
        )
    }

    private val teamsToClean = mutableListOf<UInt>()
    private val connectionsToClean = mutableListOf<UInt>()

    private suspend fun connection(prefix: String): UInt =
        SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false).also { connectionsToClean += it }

    private suspend fun team(prefix: String): UInt = TestTeams.seed(SyncedStubFixture.unique(prefix)).also { teamsToClean += it }

    /** The rows the seed test committed (the teams and connections; the legacy rows themselves are rolled back) leave with it. */
    @AfterTest
    fun cleanUp() = runBlocking {
        teamsToClean.forEach { TestTeams.service.delete(it) }
        connectionsToClean.forEach { SyncedStubFixture.dataSources().delete(it) }
        teamsToClean.clear()
        connectionsToClean.clear()
    }

    @Test
    fun `the seed takes each active team's latest figure - ties by sprint id, no start last, deleted teams and connections skipped`() =
        runBlocking {
            val live = connection("seed-live")
            val dead = connection("seed-dead")
            val tied = team("seed-tied")
            val undated = team("seed-undated")
            val onlyUndated = team("seed-only-undated")
            val deletedTeam = team("seed-deleted-team")
            val onDeadConnection = team("seed-dead-conn")

            val seeded = rolledBack { c ->
                c.run("UPDATE teams SET marked_as_deleted = TRUE WHERE id = $deletedTeam")
                c.run("UPDATE source_connections SET marked_as_deleted = TRUE WHERE id = $dead")
                for ((board, team, connection) in listOf(
                    Triple(100L, tied, live), Triple(200L, undated, live), Triple(300L, onlyUndated, live),
                    Triple(400L, deletedTeam, live), Triple(500L, onDeadConnection, dead),
                )) {
                    c.run("INSERT INTO metrics.board_team_map (connection_id, board_id, team_id) VALUES ($connection, $board, $team)")
                }
                // A start_at tie: the higher sprint id wins.
                c.sprint(live, 10, 100, 1_000, "10.00")
                c.sprint(live, 11, 100, 1_000, "11.00")
                // An older, different figure never wins over a later start.
                c.sprint(live, 9, 100, 500, "9.00")
                // A sprint without a start ranks behind any dated one, and stands alone when it is the only one.
                c.sprint(live, 20, 200, 700, "20.00")
                c.sprint(live, 21, 200, null, "99.00")
                c.sprint(live, 30, 300, null, "30.00")
                c.sprint(live, 40, 400, 1_000, "40.00")
                c.sprint(dead, 50, 500, 1_000, "50.00")
                // A sprint on a board no team maps to has no team to seed.
                c.sprint(live, 60, 600, 1_000, "60.00")

                val teams = listOf(tied, undated, onlyUndated, deletedTeam, onDeadConnection).joinToString(",")
                c.createStatement().use { st ->
                    st.executeQuery("SELECT team_id, capacity_md FROM ($seedSelect) seed WHERE team_id IN ($teams)").use { rs ->
                        buildMap { while (rs.next()) put(rs.getInt(1).toUInt(), rs.getBigDecimal(2).toDouble()) }
                    }
                }
            }
            assertEquals(mapOf(tied to 11.0, undated to 20.0, onlyUndated to 30.0), seeded)
        }

    @Test
    fun `a team mapped on two connections with the same start and sprint id seeds from the lower connection id`() = runBlocking {
        val low = connection("seed-tie-low")
        val high = connection("seed-tie-high")
        val tied = team("seed-tie")
        assertTrue(low < high)

        val seeded = rolledBack { c ->
            // D10's UNIQUE(team_id) forbids one team on two boards; the constraint is dropped inside the rolled-back transaction only,
            // so the seed's final `connection_id ASC` tie-break is exercised for real.
            c.run("ALTER TABLE metrics.board_team_map DROP CONSTRAINT uq_metrics_board_team_map_team_id")
            c.run("INSERT INTO metrics.board_team_map (connection_id, board_id, team_id) VALUES ($high, 100, $tied), ($low, 100, $tied)")
            c.sprint(high, 10, 100, 1_000, "88.00")
            c.sprint(low, 10, 100, 1_000, "77.00")
            c.createStatement().use { st ->
                st.executeQuery("SELECT capacity_md FROM ($seedSelect) seed WHERE team_id = $tied").use { rs ->
                    buildList { while (rs.next()) add(rs.getBigDecimal(1).toDouble()) }
                }
            }
        }
        assertEquals(listOf(77.0), seeded)
    }
}
