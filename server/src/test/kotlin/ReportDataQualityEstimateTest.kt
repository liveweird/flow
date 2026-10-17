package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.DataQualityReport
import ch.nokillswit.reports.TaskFinding
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Report 14's estimate findings (domain-model A35): "estimated late" (done and open) and "estimated after done" (the backfill; done
 * only) on hand-built rows with hand-computed answers. The fixture-level counts live in `ReportDataQualityTest`.
 */
class ReportDataQualityEstimateTest {
    private suspend fun HttpClient.dq(query: String): DataQualityReport {
        val response = get("/api/v1/reports/data-quality?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET data-quality?$query")
        return response.body()
    }

    private fun counts(f: TaskFinding) = listOf(f.done, f.open, f.total)

    @Test
    fun `estimated late and estimated after done are counted per population, listed, and drilled by team and member`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-estimate", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("dq-est-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("dq-est-y"))
        val started = noonUtc("2025-12-15")
        // A done task of team [team] / [account]: handTask with the estimate columns the findings read.
        fun done(id: Long, day: String, team: UInt, account: String, atDone: Double? = null, current: Double? = null) =
            handTask(
                id, started, noonUtc(day), estimateAtDoneMd = atDone, estimateCurrentMd = current, creditTeamId = team, account = account,
            )
        val tasks = listOf(
            // done, estimated late: no estimate at start, one by done (3 MD at done)
            done(1, "2026-01-06", teamX, "acc-1", atDone = 3.0, current = 3.0).copy(estimatedLate = true),
            // done, estimated after done: nothing at start or at done, an estimate entered afterwards (current)
            done(2, "2026-01-07", teamX, "acc-1", current = 6.0).copy(estimatedAfterDone = true),
            done(3, "2026-01-08", teamY, "acc-2", current = 4.0).copy(estimatedAfterDone = true),
            // done, estimated at start: neither finding
            done(4, "2026-01-09", teamY, "acc-2", atDone = 2.0, current = 2.0),
            // open and started, estimated late (5 MD now)
            handTask(
                5, noonUtc("2026-01-05"), null, estimateCurrentMd = 5.0, estimatedLate = true,
                currentTeamId = teamX, currentAssignee = "acc-3",
            ),
            // never counted: a done task before the period, a sub-task
            done(6, "2025-05-01", teamX, "acc-1", current = 9.0).copy(estimatedAfterDone = true),
            done(7, "2026-01-10", teamX, "acc-1", current = 9.0).copy(estimatedAfterDone = true, isSubtask = true),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L) }
        try {
            val client = seededClient("dq-estimate")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val body = client.dq(query)

            val late = body.missing.estimatedLate
            assertEquals(listOf(1, 1, 2), counts(late))
            assertEquals(8.0, late.md, 1e-9, "3 at done (done task) + 5 now (open task)")
            assertEquals(listOf("HB-1", "HB-5"), late.items.map { it.issueKey }, "DONE first, then open")
            assertEquals(listOf(3.0, 5.0), late.items.map { it.estimateMd })
            assertNull(late.items.last().doneAt)

            val afterDone = body.missing.estimatedAfterDone
            assertEquals(listOf(2, 0, 2), counts(afterDone), "a backfill is a DONE-only finding")
            assertEquals(10.0, afterDone.md, 1e-9, "the backfilled (current) estimates, not the empty at-done ones")
            assertEquals(listOf("HB-3", "HB-2"), afterDone.items.map { it.issueKey }, "DONE newest first")
            assertEquals(listOf(4.0, 6.0), afterDone.items.map { it.estimateMd })

            // UNIT drill: X (late 1 done + 1 open, one backfill), Y (one backfill).
            val gx = body.groups.single { it.teamId == teamX }
            assertEquals(1 to 1, gx.tasks.estimatedLate.done to gx.tasks.estimatedLate.open)
            assertEquals(1, gx.tasks.estimatedAfterDone)
            val gy = body.groups.single { it.teamId == teamY }
            assertEquals(0 to 0, gy.tasks.estimatedLate.done to gy.tasks.estimatedLate.open)
            assertEquals(1, gy.tasks.estimatedAfterDone)
            assertEquals(afterDone.done, body.groups.sumOf { it.tasks.estimatedAfterDone })
            assertEquals(late.total, body.groups.sumOf { it.tasks.estimatedLate.done + it.tasks.estimatedLate.open })

            // TEAM level (X): per member.
            val team = client.dq("$query&teamId=$teamX")
            assertEquals(listOf(1, 1, 2), counts(team.missing.estimatedLate))
            assertEquals(listOf(1, 0, 1), counts(team.missing.estimatedAfterDone))
            val m1 = team.groups.single { it.accountId == "acc-1" }
            assertEquals(1 to 0, m1.tasks.estimatedLate.done to m1.tasks.estimatedLate.open)
            assertEquals(1, m1.tasks.estimatedAfterDone)
            val m3 = team.groups.single { it.accountId == "acc-3" }
            assertEquals(0 to 1, m3.tasks.estimatedLate.done to m3.tasks.estimatedLate.open)
            assertEquals(0, m3.tasks.estimatedAfterDone)

            // A period with no DONE task still lists the open late one and no backfill.
            val empty = client.dq("connectionId=$connId&from=2026-03-01&to=2026-03-31")
            assertEquals(listOf(0, 1, 1), counts(empty.missing.estimatedLate))
            assertEquals(listOf(0, 0, 0), counts(empty.missing.estimatedAfterDone))
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }
}
