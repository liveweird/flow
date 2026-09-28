package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.VelocityReport
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A minimal local slice of `sample-data/jira/expected.json`'s `golden.sprint` — just what this test needs. */
@Serializable
private data class VelocityGoldenSprint(
    val sprintId: Long,
    val committedMd: Double,
    val committedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

@Serializable
private data class VelocityGoldenFixture(val sprint: VelocityGoldenSprint)

@Serializable
private data class VelocityExpectedFixture(val golden: VelocityGoldenFixture)

private val REPORT_VELOCITY_JSON = Json { ignoreUnknownKeys = true }

private val reportVelocityGolden: VelocityGoldenSprint by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    REPORT_VELOCITY_JSON.decodeFromString<VelocityExpectedFixture>(file.readText()).golden.sprint
}

/**
 * `GET /api/v1/reports/velocity` (v0.3.0 M4 commit 10b, Report 1, `.claude/docs/measures.md`
 * "Report 1 — Velocity"). Reads [DerivedStubFixture]'s connection (one FLO-mapped team, one
 * completed DERIVE). Every request narrows via `connectionId` to that one connection, so other
 * tests' connections in the shared database never leak into the figures. `seededClient` defaults to a
 * non-admin (`UserRole.USER`) caller, so every `200` below also proves D12 (`.claude/docs/reports.md`
 * "Access posture").
 */
class ReportVelocityTest {

    private suspend fun floTeamId(connectionId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsStore.FactSprint.selectAll()
            .where { (MetricsStore.FactSprint.connectionId eq connectionId) and (MetricsStore.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsStore.FactSprint.teamId]!!.value
    }

    @Test
    fun `UNIT level lists the FLO team's sprints with finalMd matching fact_sprint`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportVelocityGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-velocity-unit")

        val response = client.get(
            "/api/v1/reports/velocity?connectionId=$connId&from=2025-09-01&to=2026-03-06",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VelocityReport>()

        val floSprints = body.sprints.filter { it.teamId == floTeamId }
        assertTrue(floSprints.isNotEmpty(), "expected at least the FLO team's own sprints in the period")
        val goldenSprint = floSprints.single { it.sprintId == golden.sprintId }
        assertEquals(golden.finalMd, goldenSprint.finalMd, "finalMd must match fact_sprint")
        assertEquals(golden.finalItems, goldenSprint.finalItems)
        assertTrue(!goldenSprint.drift, "the fixture's snapshots are reconstructed from the same data — drift must be false")

        val floGroup = body.groups.single { it.teamId == floTeamId }
        assertEquals(floSprints.sumOf { it.finalMd }, floGroup.finalMd, ABS_TOLERANCE)
        assertEquals(floSprints.sumOf { it.finalItems }, floGroup.finalItems)
    }

    @Test
    fun `sprintId period returns the golden FLO sprint matching expected json`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportVelocityGolden
        val client = seededClient("reports-velocity-sprint-id")

        val response = client.get("/api/v1/reports/velocity?connectionId=$connId&sprintId=${golden.sprintId}")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VelocityReport>()

        val sprint = body.sprints.single()
        assertEquals(golden.sprintId, sprint.sprintId)
        assertEquals(golden.committedMd, sprint.initialMd, "initialMd (committed) must match expected.json golden.sprint.committedMd")
        assertEquals(golden.committedItems, sprint.initialItems)
        assertEquals(golden.finalMd, sprint.finalMd, "finalMd must match expected.json golden.sprint.finalMd")
        assertEquals(golden.finalItems, sprint.finalItems)
        assertTrue(!sprint.drift)
    }

    @Test
    fun `TEAM level groups sum to the team total`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportVelocityGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-velocity-team")

        val response = client.get(
            "/api/v1/reports/velocity?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VelocityReport>()

        val teamTotal = body.sprints.single().finalMd
        val teamTotalItems = body.sprints.single().finalItems
        assertEquals(teamTotal, body.groups.sumOf { it.finalMd }, ABS_TOLERANCE, "Sigma users must equal the team total")
        assertEquals(teamTotalItems, body.groups.sumOf { it.finalItems })
        // The committed (initial) bucket too, in MD and items — the same removed-row rule as the team.
        val sprint = body.sprints.single()
        assertEquals(sprint.initialMd, body.groups.sumOf { it.initialMd }, ABS_TOLERANCE, "Sigma users initial must equal the team")
        assertEquals(sprint.initialItems, body.groups.sumOf { it.initialItems })
        assertTrue(body.groups.isNotEmpty(), "expected at least one assignee-at-commitment group")
    }

    @Test
    fun `unknown sprintId is 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-velocity-unknown-sprint")

        val response = client.get("/api/v1/reports/velocity?connectionId=$connId&sprintId=999999999")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `from after to is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-velocity-bad-range")

        val response = client.get("/api/v1/reports/velocity?from=2026-01-01&to=2025-01-01")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private companion object {
        const val ABS_TOLERANCE = 0.005
    }
}
