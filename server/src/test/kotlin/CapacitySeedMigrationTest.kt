package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `the seed takes each active team's latest figure - ties by sprint id, no start last, deleted teams and connections skipped`() =
        runBlocking {
            val live = SyncedStubFixture.createConnection(namePrefix = "seed-live", enabled = false)
            val dead = SyncedStubFixture.createConnection(namePrefix = "seed-dead", enabled = false)
            val tied = TestTeams.seed(SyncedStubFixture.unique("seed-tied"))
            val undated = TestTeams.seed(SyncedStubFixture.unique("seed-undated"))
            val onlyUndated = TestTeams.seed(SyncedStubFixture.unique("seed-only-undated"))
            val deletedTeam = TestTeams.seed(SyncedStubFixture.unique("seed-deleted-team"))
            val onDeadConnection = TestTeams.seed(SyncedStubFixture.unique("seed-dead-conn"))

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
}
