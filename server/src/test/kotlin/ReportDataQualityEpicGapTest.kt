package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.DataQualityReport
import ch.nokillswit.reports.EstimateGapDirection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Report 14's "epic estimate vs its tasks' estimates" finding on hand-built epics with hand-computed answers: both directions,
 * the exactly-25% boundary (incl. a pair a floating-point division would push over it), epics with only one of the two figures,
 * an epic done in and one done before the period, the widest-gap-first order, and the owner-team drill.
 */
class ReportDataQualityEpicGapTest {
    private suspend fun HttpClient.dq(query: String): DataQualityReport {
        val response = get("/api/v1/reports/data-quality?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET data-quality?$query")
        return response.body()
    }

    @Test
    fun `epics whose estimate disagrees with their tasks by more than the tolerance are listed with both figures and the direction`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "dq-epicgap", enabled = false)
            val store = MetricsStore(sharedDatabaseForTests())
            val teamX = TestTeams.seed(SyncedStubFixture.unique("dq-gx"))
            val teamY = TestTeams.seed(SyncedStubFixture.unique("dq-gy"))
            val started = noonUtc("2026-01-05")
            val facts = listOf(
                // exactly +25%: not flagged
                handEpic(1, started, null, ownCurrent = 4.0, childSum = 5.0, ownerTeamId = teamX),
                // just over +25% (1.01 / 4 = 25.25%): OVER by 1.01
                handEpic(2, started, null, ownCurrent = 4.0, childSum = 5.01, ownerTeamId = teamX),
                // -60%: UNDER by 4
                handEpic(3, started, null, ownCurrent = 10.0, childSum = 6.0, ownerTeamId = teamX),
                // exactly -25%: not flagged
                handEpic(4, started, null, ownCurrent = 10.0, childSum = 7.5, ownerTeamId = teamX),
                // exactly +25% where 1.5 - 1.2 divided in floating point lands above 0.25: still not flagged
                handEpic(5, started, null, ownCurrent = 1.2, childSum = 1.5, ownerTeamId = teamX),
                // own estimate but no estimated task: not flagged (mostly not broken down yet)
                handEpic(6, started, null, ownCurrent = 5.0, childSum = 0.0, ownerTeamId = teamX),
                // estimated tasks but no own estimate: "epics without an estimate", not here
                handEpic(7, started, null, ownCurrent = null, childSum = 8.0, ownerTeamId = teamX),
                // done in the period: counted like an open one (OVER by 2)
                handEpic(8, started, noonUtc("2026-01-20"), ownCurrent = 2.0, childSum = 4.0, ownerTeamId = teamX),
                // done before the period: out of the population
                handEpic(9, started, noonUtc("2025-05-01"), ownCurrent = 2.0, childSum = 4.0, ownerTeamId = teamX),
                // the widest gap, owned by team Y (OVER by 10)
                handEpic(10, started, null, ownCurrent = 10.0, childSum = 20.0, ownerTeamId = teamY),
                // no owner team (UNOWNED): OVER by 3
                handEpic(11, started, null, ownCurrent = 3.0, childSum = 6.0, ownerTeamId = null),
                // own estimate of 0 can never be a base for a percentage
                handEpic(12, started, null, ownCurrent = 0.0, childSum = 3.0, ownerTeamId = teamY),
            )
            val dims = facts.map {
                DimEpicRow(it.issueId, "EG-${it.issueId}", "Epic ${it.issueId}", "AAA", null, "IN_PROGRESS", null, null)
            }
            suspendTransaction(sharedDatabaseForTests()) {
                store.replaceFactEpicDelivery(connId, facts, configRevision = 1L)
                store.insertEpics(connId, dims, configRevision = 1L)
            }
            try {
                val client = seededClient("dq-epicgap")
                val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
                val body = client.dq(query)

                val gaps = body.epicEstimateGap
                assertEquals(5, gaps.total)
                assertEquals(
                    listOf("EG-10", "EG-3", "EG-11", "EG-8", "EG-2"), gaps.items.map { it.epic.issueKey },
                    "widest absolute gap first: 10, 4, 3, 2, 1.01",
                )
                assertEquals(listOf(10.0, 10.0, 3.0, 2.0, 4.0), gaps.items.map { it.ownEstimateMd })
                assertEquals(listOf(20.0, 6.0, 6.0, 4.0, 5.01), gaps.items.map { it.childSumMd })
                assertEquals(listOf(10.0, -4.0, 3.0, 2.0, 1.01), gaps.items.map { it.gapMd })
                assertEquals(
                    listOf(
                        EstimateGapDirection.OVER, EstimateGapDirection.UNDER, EstimateGapDirection.OVER,
                        EstimateGapDirection.OVER, EstimateGapDirection.OVER,
                    ),
                    gaps.items.map { it.direction },
                )
                val done = gaps.items.single { it.epic.issueKey == "EG-8" }.epic
                assertEquals(noonUtc("2026-01-20"), done.doneAt)
                assertEquals(teamX, done.ownerTeamId)
                assertEquals("Epic 8", done.summary)
                assertTrue(gaps.items.all { it.epic.flags.isEmpty() }, "the drift flags belong to the drift finding")

                // The owner drill: X owns e1..e9 (flagged e2, e3, e8), Y owns e10 and e12 (flagged e10), e11 is UNOWNED.
                fun counts(team: UInt?) = body.groups.single { it.teamId == team }.epics!!
                assertEquals(3, counts(teamX).estimateGap)
                assertEquals(1, counts(teamY).estimateGap)
                assertEquals(1, counts(null).estimateGap)
                assertEquals(gaps.total, body.groups.sumOf { it.epics!!.estimateGap })

                // Slices: a team narrows to its epics (TEAM level carries no epic counts), teamId=0 is UNOWNED, USER has none.
                val team = client.dq("$query&teamId=$teamX")
                assertEquals(listOf("EG-3", "EG-8", "EG-2"), team.epicEstimateGap.items.map { it.epic.issueKey })
                assertTrue(team.groups.all { it.epics == null })
                assertEquals(listOf("EG-11"), client.dq("$query&teamId=0").epicEstimateGap.items.map { it.epic.issueKey })
                assertEquals(0, client.dq("$query&teamId=$teamX&accountId=nobody").epicEstimateGap.total)
                assertEquals(5, client.dq("$query&domain=AAA").epicEstimateGap.total)
                assertEquals(0, client.dq("$query&domain=BBB").epicEstimateGap.total)
                assertEquals(25, body.epicEstimateGapTolerancePercent, "the default tolerance is echoed")

                // The tolerance is the metrics setting: at 50 % the +25.25 % epic (EG-2) and the -40 % one (EG-3) drop out; at 24 %
                // the exactly-25 % ones (EG-1, EG-4, EG-5) are flagged and the echo follows the setting.
                val settings = DerivedStubFixture.metricsSettings()
                withMetricsSettings(settings, { it.copy(epicEstimateGapTolerancePercent = 50) }) {
                    val wide = client.dq(query)
                    assertEquals(50, wide.epicEstimateGapTolerancePercent)
                    assertEquals(listOf("EG-10", "EG-11", "EG-8"), wide.epicEstimateGap.items.map { it.epic.issueKey })
                    assertEquals(1, wide.groups.single { it.teamId == teamX }.epics!!.estimateGap)
                }
                withMetricsSettings(settings, { it.copy(epicEstimateGapTolerancePercent = 24) }) {
                    val narrow = client.dq(query)
                    assertEquals(24, narrow.epicEstimateGapTolerancePercent)
                    assertEquals(
                        setOf("EG-1", "EG-2", "EG-3", "EG-4", "EG-5", "EG-8", "EG-10", "EG-11"),
                        narrow.epicEstimateGap.items.map { it.epic.issueKey }.toSet(),
                    )
                }
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteFactEpicDelivery(connId)
                    store.deleteDims(connId)
                }
                cleanUpTeams(listOf(teamX, teamY))
            }
        }
}
