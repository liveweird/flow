package ch.nokillswit

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.reports.SprintConsistencyGroup
import ch.nokillswit.reports.SprintConsistencyReport
import ch.nokillswit.reports.SprintConsistencySprint
import ch.nokillswit.reports.SprintFigures
import io.ktor.client.HttpClient
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
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `sample-data/jira/expected.json`'s `golden.sprint` — every scope bucket this report shows. */
@Serializable
private data class ConsistencyGoldenSprint(
    val sprintId: Long,
    val committedMd: Double,
    val committedItems: Int,
    val addedMd: Double,
    val addedItems: Int,
    val removedMd: Double,
    val removedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val droppedMd: Double,
    val droppedItems: Int,
)

@Serializable
private data class ConsistencyGoldenFixture(val sprint: ConsistencyGoldenSprint)

@Serializable
private data class ConsistencyExpectedFixture(val golden: ConsistencyGoldenFixture)

private val REPORT_CONSISTENCY_JSON = Json { ignoreUnknownKeys = true }

private val reportConsistencyGolden: ConsistencyGoldenSprint by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    REPORT_CONSISTENCY_JSON.decodeFromString<ConsistencyExpectedFixture>(file.readText()).golden.sprint
}

private const val MD_TOLERANCE = 0.005

private fun SprintConsistencySprint.figures() = SprintFigures(
    committedMd, committedItems, addedMd, addedItems, removedMd, removedItems, finalMd, finalItems,
    deliveredMd, deliveredItems, carriedOverMd, carriedOverItems, droppedMd, droppedItems,
)

private fun SprintConsistencyGroup.figures() = SprintFigures(
    committedMd, committedItems, addedMd, addedItems, removedMd, removedItems, finalMd, finalItems,
    deliveredMd, deliveredItems, carriedOverMd, carriedOverItems, droppedMd, droppedItems,
)

private fun ConsistencyGoldenSprint.figures() = SprintFigures(
    committedMd, committedItems, addedMd, addedItems, removedMd, removedItems, finalMd, finalItems,
    deliveredMd, deliveredItems, carriedOverMd, carriedOverItems, droppedMd, droppedItems,
)

private fun List<SprintFigures>.total() = SprintFigures(
    sumOf { it.committedMd }, sumOf { it.committedItems }, sumOf { it.addedMd }, sumOf { it.addedItems },
    sumOf { it.removedMd }, sumOf { it.removedItems }, sumOf { it.finalMd }, sumOf { it.finalItems },
    sumOf { it.deliveredMd }, sumOf { it.deliveredItems }, sumOf { it.carriedOverMd }, sumOf { it.carriedOverItems },
    sumOf { it.droppedMd }, sumOf { it.droppedItems },
)

/** Every one of the fourteen figures: MD within [MD_TOLERANCE], items exactly. */
private fun assertFigures(expected: SprintFigures, actual: SprintFigures, what: String) {
    assertEquals(expected.committedMd, actual.committedMd, MD_TOLERANCE, "$what committedMd")
    assertEquals(expected.committedItems, actual.committedItems, "$what committedItems")
    assertEquals(expected.addedMd, actual.addedMd, MD_TOLERANCE, "$what addedMd")
    assertEquals(expected.addedItems, actual.addedItems, "$what addedItems")
    assertEquals(expected.removedMd, actual.removedMd, MD_TOLERANCE, "$what removedMd")
    assertEquals(expected.removedItems, actual.removedItems, "$what removedItems")
    assertEquals(expected.finalMd, actual.finalMd, MD_TOLERANCE, "$what finalMd")
    assertEquals(expected.finalItems, actual.finalItems, "$what finalItems")
    assertEquals(expected.deliveredMd, actual.deliveredMd, MD_TOLERANCE, "$what deliveredMd")
    assertEquals(expected.deliveredItems, actual.deliveredItems, "$what deliveredItems")
    assertEquals(expected.carriedOverMd, actual.carriedOverMd, MD_TOLERANCE, "$what carriedOverMd")
    assertEquals(expected.carriedOverItems, actual.carriedOverItems, "$what carriedOverItems")
    assertEquals(expected.droppedMd, actual.droppedMd, MD_TOLERANCE, "$what droppedMd")
    assertEquals(expected.droppedItems, actual.droppedItems, "$what droppedItems")
}

/**
 * `GET /api/v1/reports/sprint-consistency` (v0.3.0 M4 commit 10d, reports 6.1-6.3,
 * `.claude/docs/measures.md` "Report 6"). Reads [DerivedStubFixture]'s connection; every request
 * narrows via `connectionId`. `seededClient` is a non-admin, so every `200` also proves D12.
 */
class ReportSprintConsistencyTest {

    private suspend fun floTeamId(connectionId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connectionId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
    }

    private suspend fun HttpClient.consistency(query: String): SprintConsistencyReport {
        val response = get("/api/v1/reports/sprint-consistency?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    @Test
    fun `golden FLO sprint buckets match expected json exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportConsistencyGolden
        val client = seededClient("reports-consistency-golden")

        val sprint = client.consistency("connectionId=$connId&sprintId=${golden.sprintId}").sprints.single()

        assertEquals(golden.sprintId, sprint.sprintId)
        assertNotNull(sprint.completedAt)
        // Whole numbers in the fixture, so MD is asserted exactly — no tolerance hides a bucket off by one MD.
        assertEquals(golden.figures(), sprint.figures(), "golden.sprint buckets")
        assertNotNull(sprint.snapshot, "a closed, team-mapped sprint is snapshotted")
        assertFigures(sprint.figures(), sprint.snapshot, "snapshot")
        assertTrue(!sprint.drift, "the fixture's snapshot is derived from the same data — no drift")
    }

    @Test
    fun `every returned closed sprint partitions as final equals delivered plus carried plus dropped`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-consistency-partition")

        val body = client.consistency("connectionId=$connId&lastSprints=52")

        assertTrue(body.sprints.size > 1, "expected several closed sprints, got ${body.sprints.size}")
        for (sprint in body.sprints) {
            val what = "sprint ${sprint.sprintId}"
            assertNotNull(sprint.completedAt, "$what is closed")
            assertEquals(
                sprint.finalMd, sprint.deliveredMd + sprint.carriedOverMd + sprint.droppedMd, MD_TOLERANCE,
                "$what: final MD = delivered + carried + dropped (A17)",
            )
            assertEquals(
                sprint.finalItems, sprint.deliveredItems + sprint.carriedOverItems + sprint.droppedItems,
                "$what: final items = delivered + carried + dropped (A17)",
            )
            assertEquals(sprint.finalItems, sprint.committedItems + sprint.addedItems, "$what: final items = committed + added")
        }
        // UNIT groups sum every figure per team, so Σ groups == Σ sprints for all fourteen.
        assertFigures(body.sprints.map { it.figures() }.total(), body.groups.map { it.figures() }.total(), "UNIT groups")
        assertLabelThenIdOrder("UNIT", body.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
    }

    @Test
    fun `TEAM level groups sum to the team figures for every bucket`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportConsistencyGolden.sprintId)
        val client = seededClient("reports-consistency-team")

        val body = client.consistency("connectionId=$connId&teamId=$floTeamId&lastSprints=52")

        assertTrue(body.sprints.all { it.teamId == floTeamId }, "TEAM level narrows to the one team's sprints")
        assertTrue(body.groups.isNotEmpty(), "expected at least one assignee-at-commitment group")
        assertFigures(body.sprints.map { it.figures() }.total(), body.groups.map { it.figures() }.total(), "Σ users vs team")
        assertLabelThenIdOrder("TEAM", body.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
    }

    @Test
    fun `USER level sums over the golden sprint's accounts plus the unassigned remainder to the team`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportConsistencyGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-consistency-user")

        val team = client.consistency("connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
        val teamSprint = team.sprints.single()
        val named = team.groups.filter { it.accountId != null }
        assertTrue(named.isNotEmpty(), "the golden sprint has assigned scope")

        val userFigures = named.map { group ->
            val user = client.consistency(
                "connectionId=$connId&teamId=$floTeamId&accountId=${group.accountId}&sprintId=${golden.sprintId}",
            )
            assertTrue(user.groups.isEmpty(), "USER level has nothing further to drill")
            val sprint = user.sprints.single()
            assertNull(sprint.snapshot)
            assertTrue(!sprint.drift)
            assertFigures(group.figures(), sprint.figures(), "USER ${group.accountId} vs its TEAM group")
            sprint.figures()
        }
        // The null-assignee bucket has no USER-level query (no accountId to name): it is the remainder.
        val unassigned = team.groups.filter { it.accountId == null }.map { it.figures() }
        assertFigures(teamSprint.figures(), (userFigures + unassigned).total(), "Σ users + unassigned vs team")
    }

    private fun scopeItem(
        issueId: Long,
        assignee: String?,
        committed: Boolean = true,
        inScopeAtClose: Boolean = true,
        addedAtMs: Long? = null,
        removedAtMs: Long? = null,
        commitMd: Double?,
        closeMd: Double?,
        done: Boolean = false,
        carried: Boolean = false,
        dropped: Boolean = false,
    ) = SprintScopeItem(
        issueId = issueId, addedAtMs = addedAtMs, removedAtMs = removedAtMs, committed = committed,
        inScopeAtClose = inScopeAtClose, estimateAtCommitmentMd = commitMd, estimateAtCloseMd = closeMd,
        estimateAtDoneMd = if (done) closeMd else null, assigneeAtCommitment = assignee, doneInSprint = done,
        carriedOver = carried, dropped = dropped,
    )

    private fun closedSprint(sprintId: Long, boardId: Long, teamId: UInt, name: String, completeAt: Long) =
        DimSprintRow(sprintId, boardId, teamId, name, "closed", completeAt - 14 * 86_400_000L, completeAt, completeAt, null, null)

    private fun factSprint(sprintId: Long, teamId: UInt, completeAt: Long, items: List<SprintScopeItem>) =
        FactSprintRow(sprintId, teamId, completeAt, DeriveKernels.sprintTotals(items), capacityMd = null, load = null)

    private fun figures(
        committed: Pair<Double, Int> = 0.0 to 0,
        added: Pair<Double, Int> = 0.0 to 0,
        removed: Pair<Double, Int> = 0.0 to 0,
        final: Pair<Double, Int> = 0.0 to 0,
        delivered: Pair<Double, Int> = 0.0 to 0,
        carried: Pair<Double, Int> = 0.0 to 0,
        dropped: Pair<Double, Int> = 0.0 to 0,
    ) = SprintFigures(
        committed.first, committed.second, added.first, added.second, removed.first, removed.second, final.first, final.second,
        delivered.first, delivered.second, carried.first, carried.second, dropped.first, dropped.second,
    )

    /**
     * The stub fixture has no removed scope and no estimated added scope, so the removed/added per-user
     * attribution is graded on hand-built rows (a fresh DISABLED connection, cleaned up afterwards) with
     * exactly hand-computed answers. A SECOND connection maps its own sprint to the same team and both connections
     * hold stale `fact_sprint_scope` rows for the OTHER's sprint id (two connections to one Jira site share sprint
     * ids; a disabled one can keep a stale mapping): a `connection IN (...) AND sprint IN (...)` fetch would cross
     * the two and leak those rows into Σ groups, so the report must keep exactly the in-scope (connection, sprint) pairs.
     */
    @Test
    fun `hand-built removed and added scope attribute to the right user and never leak across connections`() = testApplication {
        usePostgresTestcontainer()
        val connA = SyncedStubFixture.createConnection(namePrefix = "consistency-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "consistency-b", enabled = false)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("consistency-team"))
        val sprintId = 900_001L
        val otherSprintId = 900_002L
        val closedAt = 1_770_724_800_000L // 2026-02-10T12:00:00Z
        val items = listOf(
            scopeItem(1, "acc-1", commitMd = 3.0, closeMd = 3.0, done = true),
            scopeItem(2, "acc-1", commitMd = 2.0, closeMd = 2.0, carried = true),
            // Added mid-sprint at 4 MD, re-estimated to 5 MD by the close, then dropped.
            scopeItem(3, "acc-2", committed = false, addedAtMs = closedAt - 1_000, commitMd = 4.0, closeMd = 5.0, dropped = true),
            // Committed at 1.5 MD, taken out before the close: in the removed bucket only.
            scopeItem(4, "acc-2", inScopeAtClose = false, removedAtMs = closedAt - 500, commitMd = 1.5, closeMd = null),
            scopeItem(5, null, commitMd = null, closeMd = null, carried = true),
        )
        val store = MetricsStore(sharedDatabaseForTests())
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDimSprints(
                connA,
                listOf(closedSprint(sprintId, 1L, teamId, "Hand-built sprint", closedAt)),
                configRevision = 1L,
            )
            store.insertFactSprint(connA, listOf(factSprint(sprintId, teamId, closedAt, items)), 1L)
            store.insertFactSprintScope(connA, items.map { FactSprintScopeRow(sprintId, it) }, 1L)
            // A holds a stale scope row for B's sprint id (no fact_sprint row of its own for it).
            store.insertFactSprintScope(
                connA,
                listOf(FactSprintScopeRow(otherSprintId, scopeItem(98, "acc-1", commitMd = 100.0, closeMd = 100.0, done = true))),
                1L,
            )
            // B: its own sprint mapped to the same team, plus a stale scope row for A's sprint id.
            val bItems = listOf(scopeItem(97, "acc-3", commitMd = 7.0, closeMd = 7.0, carried = true))
            store.insertDimSprints(
                connB,
                listOf(closedSprint(otherSprintId, 2L, teamId, "Other sprint", closedAt + 1)),
                configRevision = 1L,
            )
            store.insertFactSprint(connB, listOf(factSprint(otherSprintId, teamId, closedAt + 1, bItems)), 1L)
            store.insertFactSprintScope(connB, bItems.map { FactSprintScopeRow(otherSprintId, it) }, 1L)
            store.insertFactSprintScope(
                connB,
                listOf(FactSprintScopeRow(sprintId, scopeItem(99, "acc-1", commitMd = 100.0, closeMd = 100.0, done = true))),
                1L,
            )
        }
        try {
            val client = seededClient("reports-consistency-hand-built")
            val team = client.consistency("teamId=$teamId&sprintId=$sprintId")
            assertFigures(
                figures(
                    committed = 5.0 to 3, added = 4.0 to 1, removed = 1.5 to 1, final = 10.0 to 4,
                    delivered = 3.0 to 1, carried = 2.0 to 2, dropped = 5.0 to 1,
                ),
                team.sprints.single().figures(), "hand-built team",
            )
            val byAccount = team.groups.associateBy { it.accountId }
            assertEquals(setOf("acc-1", "acc-2", null), byAccount.keys, "one group per assignee at commitment")
            assertEquals(
                figures(committed = 5.0 to 2, final = 5.0 to 2, delivered = 3.0 to 1, carried = 2.0 to 1),
                byAccount.getValue("acc-1").figures(),
            )
            // The added item attributes to the assignee at ENTRY; the removed one to the assignee at commitment.
            assertEquals(
                figures(added = 4.0 to 1, removed = 1.5 to 1, final = 5.0 to 1, dropped = 5.0 to 1),
                byAccount.getValue("acc-2").figures(),
            )
            assertEquals(figures(committed = 0.0 to 1, final = 0.0 to 1, carried = 0.0 to 1), byAccount.getValue(null).figures())
            assertNull(team.groups.last().accountId, "the unassigned group sorts last")

            val user = client.consistency("teamId=$teamId&accountId=acc-2&sprintId=$sprintId").sprints.single()
            assertEquals(byAccount.getValue("acc-2").figures(), user.figures())

            // Both connections' sprints in scope at once: no stale row of the other connection leaks in.
            val both = client.consistency("teamId=$teamId&lastSprints=5")
            assertEquals(setOf(sprintId, otherSprintId), both.sprints.map { it.sprintId }.toSet())
            val bothByAccount = both.groups.associateBy { it.accountId }
            assertEquals(setOf("acc-1", "acc-2", "acc-3", null), bothByAccount.keys)
            assertEquals(byAccount.getValue("acc-1").figures(), bothByAccount.getValue("acc-1").figures(), "no leaked 100 MD row")
            assertEquals(figures(committed = 7.0 to 1, final = 7.0 to 1, carried = 7.0 to 1), bothByAccount.getValue("acc-3").figures())
            assertFigures(both.sprints.map { it.figures() }.total(), both.groups.map { it.figures() }.total(), "Σ users vs team")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteSprintFacts(connA)
                store.deleteSprintFacts(connB)
            }
        }
    }

    @Test
    fun `an open or future sprint is reachable by sprintId with live figures and no snapshot`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportConsistencyGolden.sprintId)
        val client = seededClient("reports-consistency-open-sprint")

        val open = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprint.selectAll()
                .where {
                    (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.teamId eq floTeamId) and
                        MetricsTables.FactSprint.completeAt.isNull()
                }
                .toList()
        }
        assertTrue(open.isNotEmpty(), "the stub fixture must carry an active/future FLO sprint")
        for (row in open) {
            val sprint = client.consistency("connectionId=$connId&sprintId=${row[MetricsTables.FactSprint.sprintId]}").sprints.single()
            assertNull(sprint.completedAt)
            assertNull(sprint.snapshot)
            assertTrue(!sprint.drift)
            assertEquals(row[MetricsTables.FactSprint.committedMd].toDouble(), sprint.committedMd, MD_TOLERANCE)
            assertEquals(row[MetricsTables.FactSprint.finalItems], sprint.finalItems)
            assertEquals(row[MetricsTables.FactSprint.addedItems], sprint.addedItems)
        }
    }

    @Test
    fun `teamId 0 is always empty`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-consistency-unassigned")

        val body = client.consistency("connectionId=$connId&teamId=0&lastSprints=5")
        assertTrue(body.sprints.isEmpty() && body.groups.isEmpty())
    }

    @Test
    fun `unknown sprintId is 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-consistency-unknown-sprint")

        val response = client.get("/api/v1/reports/sprint-consistency?connectionId=$connId&sprintId=999999999")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `from after to is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-consistency-bad-range")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/sprint-consistency?from=2026-01-01&to=2025-01-01").status)
    }
}
