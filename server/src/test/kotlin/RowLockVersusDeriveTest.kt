package ch.nokillswit

import ch.nokillswit.infra.db.lockActiveForWrite
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarResponse
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row locks vs. FK locks (persistence.md "Row locks vs. FK locks"): DERIVE's one long transaction holds `FOR KEY SHARE` on every
 * team and connection row its inserts reference. A write that locked its row `FOR UPDATE` waited behind it (the capacity page
 * timed out); `FOR NO KEY UPDATE` does not conflict with key-share, but still serializes with another writer of the row.
 *
 * The blocker is a RAW, uncommitted `metrics.domain_map` insert (it references both the connection and the team and no route
 * under test writes that table, so the writer's own inserts never wait on the blocker's row), rolled back when it is closed.
 */
class RowLockVersusDeriveTest {

    /** A raw JDBC transaction left OPEN (own backend) — closing it rolls back. */
    private class OpenTransaction : AutoCloseable {
        private val connection: Connection = DriverManager
            .getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .also { it.autoCommit = false }

        fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }

        /** What DERIVE's child insert holds on the referenced rows: `FOR KEY SHARE` via the FKs, until the transaction ends. */
        fun insertDomainMapReferencing(connectionId: UInt, teamId: UInt?) = execute(
            "INSERT INTO metrics.domain_map (connection_id, project_key, domain_key, domain_name, owner_team_id) " +
                "VALUES ($connectionId, 'LOCK${UUID.randomUUID().toString().take(8)}', 'd', 'd', ${teamId ?: "NULL"})",
        )

        override fun close() {
            runCatching { connection.rollback() }
            connection.close()
        }
    }

    /** A write that blocks would hang the request; the real-time bound turns that into a failure in seconds. */
    private suspend fun <T> promptly(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(PROMPT_MS) { block() } }

    private fun SprintCalendarResponse.asDocument() = SprintCalendarDocument(
        teamCoefficient = teamCoefficient,
        days = days.map { SprintCalendarDay(it.day, it.enabled) },
        people = people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
    )

    @Test
    fun `capacity writes, a membership create and a team update do not wait for a transaction holding key-share on the team`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("rowlock-team", UserRole.ADMIN)
            val s = CapacityFixtures.seed("rowlock-team")
            val calendar = admin.get("/api/v1/teams/${s.teamId}/sprint-calendars/${s.sprintId}").body<SprintCalendarResponse>()
            val document = calendar.asDocument().copy(teamCoefficient = 1.25)
            val renamed = SyncedStubFixture.unique("rowlock-renamed")

            OpenTransaction().use { derive ->
                derive.insertDomainMapReferencing(s.connectionId, s.teamId)

                assertEquals(
                    HttpStatusCode.NoContent,
                    promptly { admin.putJson("/api/v1/teams/${s.teamId}/capacity", TeamCapacityRequest(32.0)) }.status,
                    "the team default",
                )
                assertEquals(
                    HttpStatusCode.NoContent,
                    promptly { admin.putJson("/api/v1/teams/${s.teamId}/sprint-calendars/${s.sprintId}", document) }.status,
                    "a sprint calendar",
                )
                assertEquals(
                    HttpStatusCode.Created,
                    promptly {
                        val body = TeamMembershipCreateRequest(s.outsider, validFrom = 0L)
                        admin.postJson("/api/v1/teams/${s.teamId}/jira-memberships", body)
                    }.status,
                    "a membership create",
                )
                // The rename moves the name, a column of a PARTIAL/expression unique index: still a non-key update.
                assertEquals(
                    HttpStatusCode.NoContent,
                    promptly { admin.putJson("/api/v1/teams/${s.teamId}", TeamUpdateRequest(name = renamed)) }.status,
                    "a team update",
                )
            }
        }

    @Test
    fun `a data source update does not wait for a transaction holding key-share on the connection`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rowlock-conn", UserRole.ADMIN)
        val connectionId = SyncedStubFixture.createConnection(
            projectKeys = uniqueProjectKeys(1), // the PUT below runs through the ENFORCED app: its keys must not overlap any other source
            namePrefix = "rowlock-conn",
            enabled = false,
        )
        val current = admin.get("/api/v1/data-sources/$connectionId").body<DataSourceResponse>()
        val update = DataSourceRequest(
            name = SyncedStubFixture.unique("rowlock-conn-renamed"),
            enabled = false,
            syncIntervalMinutes = current.syncIntervalMinutes,
            backfillFrom = current.backfillFrom,
            reconcileHourUtc = current.reconcileHourUtc,
            jira = JiraConnectionRequest(
                siteUrl = current.jira.siteUrl,
                email = current.jira.email,
                apiToken = null,
                projectKeys = current.jira.projectKeys,
                authScheme = current.jira.authScheme,
            ),
        )

        OpenTransaction().use { derive ->
            derive.insertDomainMapReferencing(connectionId, teamId = null)
            assertEquals(HttpStatusCode.NoContent, promptly { admin.putJson("/api/v1/data-sources/$connectionId", update) }.status)
        }
    }

    @Test
    fun `the team lock still serializes writers - a second lock and a soft delete wait while one is held`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rowlock-serial", UserRole.ADMIN)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("rowlock-serial"))

        OpenTransaction().use { writer ->
            writer.execute("SELECT id FROM teams WHERE id = $teamId FOR NO KEY UPDATE")

            // The shared helper itself: a second transaction asking for the lock waits (lock_timeout fires) instead of proceeding.
            val failure = assertFailsWith<Exception> {
                suspendTransaction(sharedDatabaseForTests()) {
                    exec("SET LOCAL lock_timeout = '300ms'")
                    TeamService.Teams.lockActiveForWrite(teamId)
                }
            }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any { "lock timeout" in it.message.orEmpty() },
                "expected a lock timeout, got $failure",
            )

            // End to end: the soft delete route does not finish while the lock is held, and completes once it is released.
            coroutineScope {
                val delete = async(Dispatchers.Default) { admin.delete("/api/v1/teams/$teamId").status }
                assertNull(withTimeoutOrNull(WAIT_MS) { delete.await() }, "the delete must wait for the held row lock")
                writer.close()
                assertEquals(HttpStatusCode.NoContent, withTimeout(PROMPT_MS) { delete.await() })
            }
        }
    }

    private companion object {
        const val PROMPT_MS = 5_000L
        const val WAIT_MS = 700L
    }
}
