package ch.nokillswit

import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.sprintTotals
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.SprintConsistencyReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Two connections to one Jira site carry the SAME sprint id (and the same issue ids) under different teams, and a page drawing
 * several teams' bars (Home) must open exactly the (connection, sprint) the bar came from. The sprint-scope drill therefore
 * requires the bar's `teamId` (the reports scope guard; without one it would be the union of every team's sprint of that id,
 * listing every issue twice): it lists each of that team's items once and equals the bar and the Sprint consistency report's
 * figure, and an unpinned request is a `400`. Hand-built facts on two DISABLED connections, so no worker can re-derive them.
 */
class ReportDrillSprintTeamPinTest {

    private val sprintId = 910_002L
    private val closedAt = 1_770_724_800_000L // 2026-02-10T12:00:00Z

    /** Three committed, delivered items; the same issue ids on both connections, [scale] times apart in MD. */
    private fun items(scale: Double) = (1L..3L).map { issue ->
        SprintScopeItem(
            issueId = issue, addedAtMs = null, removedAtMs = null, committed = true, inScopeAtClose = true,
            estimateAtCommitmentMd = issue * scale, estimateAtCloseMd = issue * scale, estimateAtDoneMd = issue * scale,
            assigneeAtCommitment = "acc-$issue", doneInSprint = true, carriedOver = false, dropped = false,
        )
    }

    private suspend fun MetricsStore.seedSprint(connId: UInt, teamId: UInt, board: Long, scope: List<SprintScopeItem>) =
        suspendTransaction(sharedDatabaseForTests()) {
            val startAt = closedAt - FORTNIGHT_MS
            val dim = DimSprintRow(sprintId, board, teamId, "Shared sprint", "closed", startAt, closedAt, closedAt, null, null)
            insertDimSprints(connId, listOf(dim), configRevision = 1L)
            val fact = FactSprintRow(sprintId, teamId, closedAt, sprintTotals(scope), capacityMd = null, load = null)
            insertFactSprint(connId, listOf(fact), 1L)
            insertFactSprintScope(connId, scope.map { FactSprintScopeRow(sprintId, it) }, 1L)
        }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/sprint-consistency/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET sprint-consistency/items?$query")
        return response.body()
    }

    private suspend fun HttpClient.consistency(query: String): SprintConsistencyReport {
        val response = get("/api/v1/reports/sprint-consistency?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET sprint-consistency?$query")
        return response.body()
    }

    @Test
    fun `a drill pinned to the bar's team lists that team's sprint once even when another connection carries the same sprint id`() =
        testApplication {
            usePostgresTestcontainer()
            val connA = SyncedStubFixture.createConnection(namePrefix = "drill-pin-a", enabled = false)
            val connB = SyncedStubFixture.createConnection(namePrefix = "drill-pin-b", enabled = false)
            val teamA = TestTeams.seed(SyncedStubFixture.unique("drill-pin-team-a"))
            val teamB = TestTeams.seed(SyncedStubFixture.unique("drill-pin-team-b"))
            val store = MetricsStore(sharedDatabaseForTests())
            val figuresA = listOf(3.0, 2.0, 1.0)
            val figuresB = listOf(30.0, 20.0, 10.0)
            try {
                store.seedSprint(connA, teamA, board = 1L, scope = items(1.0))
                store.seedSprint(connB, teamB, board = 2L, scope = items(10.0))
                val client = seededClient("reports-drill-sprint-pin")
                val sprint = "sprintId=$sprintId"

                for (bucket in listOf(SprintBucket.COMMITTED, SprintBucket.FINAL, SprintBucket.DELIVERED)) {
                    for ((teamId, figures) in listOf(teamA to figuresA, teamB to figuresB)) {
                        val label = "$bucket/team $teamId"
                        val drill = client.items("$sprint&teamId=$teamId&bucket=$bucket")
                        val report = client.consistency("$sprint&teamId=$teamId").sprints.single()
                        assertEquals(figures, drill.items.map { it.figure }, "$label lists each item of its own sprint once")
                        assertEquals(listOf("1", "2", "3").sorted(), drill.items.map { it.issueKey }.sorted(), "$label issue keys")
                        assertEquals(setOf<UInt?>(teamId), drill.items.map { it.teamId }.toSet(), "$label only its own team's rows")
                        assertEquals(3, drill.bar.count, "$label bar.count")
                        assertEquals(figures.sum(), drill.bar.sumMd!!, MD_TOLERANCE, "$label bar.sumMd == Σ the listed figures")
                        val (reportMd, reportItems) = when (bucket) {
                            SprintBucket.COMMITTED -> report.committedMd to report.committedItems
                            SprintBucket.FINAL -> report.finalMd to report.finalItems
                            else -> report.deliveredMd to report.deliveredItems
                        }
                        assertEquals(reportItems, drill.bar.count, "$label bar.count == the report's items")
                        assertEquals(reportMd, drill.bar.sumMd, MD_TOLERANCE, "$label bar.sumMd == the report's MD")
                        assertEquals(drill.bar.count, drill.total)
                        assertEquals(listOf(teamId), drill.meta.resolvedSprints.map { it.teamId }, "$label resolves exactly one sprint")
                    }
                }

                // Throughput's sprint mode forwards to the same drill: pinned the same way.
                val viaThroughput = client.get("/api/v1/reports/throughput/items?$sprint&teamId=$teamB").body<ReportDrillItems>()
                assertEquals(figuresB, viaThroughput.items.map { it.figure })

                // Unpinned the drill (and the report) would be the union of both teams' sprints of that id: refused instead.
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/sprint-consistency/items?$sprint&bucket=FINAL").status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/sprint-consistency?$sprint").status)
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteSprintFacts(connA)
                    store.deleteSprintFacts(connB)
                }
            }
        }

    private companion object {
        const val MD_TOLERANCE = 0.005
        const val FORTNIGHT_MS = 14 * 86_400_000L
    }
}
