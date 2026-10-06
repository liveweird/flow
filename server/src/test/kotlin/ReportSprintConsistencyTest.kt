package ch.nokillswit

import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.sprintTotals
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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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

private val NO_FIGURES = SprintFigures(0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0)

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
            // The fixture's snapshot is frozen from the very rows the live figures read: the per-user frozen figures equal them.
            assertFigures(sprint.figures(), assertNotNull(sprint.snapshot), "USER ${group.accountId} frozen vs live")
            assertTrue(!sprint.drift)
            assertFigures(group.figures(), sprint.figures(), "USER ${group.accountId} vs its TEAM group")
            sprint.figures()
        }
        // The null-assignee bucket has no USER-level query (no accountId to name): it is the remainder.
        val unassigned = team.groups.filter { it.accountId == null }.map { it.figures() }
        assertFigures(teamSprint.figures(), (userFigures + unassigned).total(), "Σ users + unassigned vs team")
    }

    /**
     * One assignee bucket's fourteen frozen figures, straight off the stored scope JSON with the predicates written out from
     * `.claude/docs/reports.md` "Report 6" (each item's MD rounded half-up to two decimals first) — never the production reader.
     */
    private fun frozenFigures(stored: List<JsonObject>, account: String?): SprintFigures {
        val rows = stored.filter { it.getValue("assigneeAtCommitment").jsonPrimitive.contentOrNull == account }
        fun flag(item: JsonObject, key: String) = item.getValue(key).jsonPrimitive.boolean
        fun set(item: JsonObject, key: String) = item.getValue(key) !is JsonNull
        fun bucket(pred: (JsonObject) -> Boolean, mdKey: String): Pair<Double, Int> {
            val items = rows.filter(pred)
            val md = items.fold(BigDecimal.ZERO) { acc, item ->
                val value = item.getValue(mdKey)
                val estimate = if (value is JsonNull) BigDecimal.ZERO else BigDecimal((value as JsonPrimitive).content)
                acc + estimate.setScale(2, RoundingMode.HALF_UP)
            }
            return md.toDouble() to items.size
        }
        return figures(
            committed = bucket({ flag(it, "committed") && flag(it, "inScopeAtClose") }, "estimateAtCommitmentMd"),
            added = bucket({ set(it, "addedAtMs") }, "estimateAtCommitmentMd"),
            removed = bucket({ set(it, "removedAtMs") }, "estimateAtCommitmentMd"),
            final = bucket({ flag(it, "inScopeAtClose") }, "estimateAtCloseMd"),
            delivered = bucket({ flag(it, "doneInSprint") }, "estimateAtDoneMd"),
            carried = bucket({ flag(it, "carriedOver") }, "estimateAtCloseMd"),
            dropped = bucket({ flag(it, "dropped") }, "estimateAtCloseMd"),
        )
    }

    @Test
    fun `USER level snapshot equals the stored scope and sums to the team snapshot`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val golden = reportConsistencyGolden
            val floTeamId = floTeamId(connId, golden.sprintId)
            val client = seededClient("reports-consistency-user-frozen")

            val team = client.consistency("connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}").sprints.single()
            val teamSnapshot = assertNotNull(team.snapshot, "the golden sprint is closed and team-mapped, so it is frozen")
            val stored = FrozenScopeFixtures.storedScope(connId, golden.sprintId).map { it.jsonObject }
            val accounts = stored.mapNotNull { it.getValue("assigneeAtCommitment").jsonPrimitive.contentOrNull }.distinct()
            assertTrue(accounts.isNotEmpty(), "the golden sprint has assigned scope")

            val named = accounts.map { account ->
                val sprint = client.consistency(
                    "connectionId=$connId&teamId=$floTeamId&accountId=$account&sprintId=${golden.sprintId}",
                ).sprints.single()
                val snapshot = assertNotNull(sprint.snapshot, "$account: a snapshotted sprint has per-user frozen figures")
                assertFigures(frozenFigures(stored, account), snapshot, "USER $account frozen vs the stored scope")
                assertFigures(sprint.figures(), snapshot, "USER $account live vs frozen")
                assertTrue(!sprint.drift, "$account: no drift")
                snapshot
            }
            // The null-assignee bucket has no USER-level query: it is the remainder.
            assertFigures(teamSnapshot, (named + frozenFigures(stored, null)).total(), "Σ users + unassigned vs the team snapshot")
        }

    @Test
    fun `USER level snapshot reads the stored scope per connection, with drift`() = testApplication {
        usePostgresTestcontainer()
        val sprintId = reportConsistencyGolden.sprintId
        val (connA, teamA) = FrozenScopeFixtures.derivedDisabledClone("jira-consistency-frozen", "consistency-frozen-team-a")
        val (connB, teamB) = FrozenScopeFixtures.derivedDisabledClone("jira-consistency-frozen", "consistency-frozen-team-b")
        val client = seededClient("reports-consistency-user-frozen-synthetic")
        fun userQuery(connId: UInt, teamId: UInt, accountId: String) =
            "connectionId=$connId&teamId=$teamId&accountId=$accountId&sprintId=$sprintId"
        fun item(
            issueId: Long, assignee: String?, committed: Boolean = true, inScopeAtClose: Boolean = true, addedAtMs: Long? = null,
            removedAtMs: Long? = null, commitMd: Double? = null, closeMd: Double? = null, doneMd: Double? = null,
            done: Boolean = false, carried: Boolean = false, dropped: Boolean = false,
        ) = FrozenScopeFixtures.scopeItem(
            issueId, assignee, committed, inScopeAtClose, addedAtMs, removedAtMs, commitMd, closeMd, doneMd, done, carried, dropped,
        )

        // Synthetic accounts own NO live scope row, so their live figures are zero and every frozen figure is the JSON's.
        val ann = "synthetic-ann"
        val other = "synthetic-other"
        // A, for [ann]: 1 committed + delivered (2.504 -> 2.50 at commitment, 3.0 at close and at done); 2 committed + carried over
        // (2.506 -> 2.51, 4.126 -> 4.13 at close); 3 committed + dropped with NO estimate (an item, 0 MD); 4 added mid-sprint
        // (priced 1.5 at entry, 2.0 at close and done) + delivered; 5 committed then removed (9.0, in no other bucket); plus
        // items of `other` and of the unassigned bucket, which must not count for `ann`.
        FrozenScopeFixtures.overwriteSnapshotScope(
            connA, sprintId,
            buildJsonArray {
                add(item(1, ann, commitMd = 2.504, closeMd = 3.0, doneMd = 3.0, done = true))
                add(item(2, ann, commitMd = 2.506, closeMd = 4.126, carried = true))
                add(item(3, ann, dropped = true))
                add(item(4, ann, committed = false, addedAtMs = 1_000, commitMd = 1.5, closeMd = 2.0, doneMd = 2.0, done = true))
                add(item(5, ann, inScopeAtClose = false, removedAtMs = 2_000, commitMd = 9.0))
                add(item(6, other, commitMd = 50.0, closeMd = 50.0, doneMd = 50.0, done = true))
                add(item(7, null, commitMd = 100.0, closeMd = 100.0, carried = true))
            },
        )
        // B shares the SAME sprint id (one Jira site, two connections) but a different snapshot.
        FrozenScopeFixtures.overwriteSnapshotScope(connB, sprintId, buildJsonArray { add(item(1, ann, commitMd = 1.0, closeMd = 1.0)) })

        val a = client.consistency(userQuery(connA, teamA, ann)).sprints.single()
        assertEquals(
            figures(
                committed = 5.01 to 3, added = 1.5 to 1, removed = 9.0 to 1, final = 9.13 to 4,
                delivered = 5.0 to 2, carried = 4.13 to 1, dropped = 0.0 to 1,
            ),
            assertNotNull(a.snapshot),
        )
        assertEquals(NO_FIGURES, a.figures(), "the live figures still come from fact_sprint_scope, not the snapshot")
        assertTrue(a.drift, "the frozen figures differ from the live ones")

        val b = client.consistency(userQuery(connB, teamB, ann)).sprints.single()
        assertEquals(figures(committed = 1.0 to 1, final = 1.0 to 1), assertNotNull(b.snapshot))
        assertTrue(b.drift)

        // An account with no item in the stored scope has zero frozen figures (not null: the sprint IS frozen) and no drift.
        val nobody = client.consistency(userQuery(connB, teamB, other)).sprints.single()
        assertEquals(NO_FIGURES, nobody.snapshot)
        assertTrue(!nobody.drift)
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
        FactSprintRow(sprintId, teamId, completeAt, sprintTotals(items), capacityMd = null, load = null)

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
            // USER level: a sprint with no snapshot (D13) has a null per-user snapshot and no drift, whoever the account.
            val user = client.consistency(
                "connectionId=$connId&teamId=$floTeamId&accountId=any-account&sprintId=${row[MetricsTables.FactSprint.sprintId]}",
            ).sprints.single()
            assertNull(user.snapshot)
            assertTrue(!user.drift)
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
