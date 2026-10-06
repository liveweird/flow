package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.VelocityReport
import ch.nokillswit.reports.frozenContributionsOf
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connectionId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
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
        assertLabelThenIdOrder("UNIT", body.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
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
        // EXACT (no tolerance): every MD figure is a whole-cent value and the groups round each item before summing
        // (`sumMd`), so Σ users is the team figure to the cent, whatever decimals the estimates carry.
        fun exactSum(values: List<Double>) = values.fold(BigDecimal.ZERO) { acc, v -> acc + v.toBigDecimal() }
        assertEquals(
            0, exactSum(listOf(teamTotal)).compareTo(exactSum(body.groups.map { it.finalMd })),
            "Sigma users must equal the team total",
        )
        assertEquals(teamTotalItems, body.groups.sumOf { it.finalItems })
        // The committed (initial) bucket too, in MD and items — the same removed-row rule as the team.
        val sprint = body.sprints.single()
        assertEquals(
            0, exactSum(listOf(sprint.initialMd)).compareTo(exactSum(body.groups.map { it.initialMd })),
            "Sigma users initial must equal the team",
        )
        assertEquals(sprint.initialItems, body.groups.sumOf { it.initialItems })
        assertTrue(body.groups.isNotEmpty(), "expected at least one assignee-at-commitment group")
        assertLabelThenIdOrder("TEAM", body.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
    }

    @Test
    fun `an open or future sprint is reachable by sprintId with live figures and no snapshot`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportVelocityGolden.sprintId)
        val client = seededClient("reports-velocity-open-sprint")

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
            val response = client.get("/api/v1/reports/velocity?connectionId=$connId&sprintId=${row[MetricsTables.FactSprint.sprintId]}")
            assertEquals(HttpStatusCode.OK, response.status)
            val sprint = response.body<VelocityReport>().sprints.single()
            assertEquals(null, sprint.completedAt)
            assertEquals(null, sprint.snapshot)
            assertTrue(!sprint.drift)
            assertEquals(row[MetricsTables.FactSprint.committedMd].toDouble(), sprint.initialMd, ABS_TOLERANCE)
            assertEquals(row[MetricsTables.FactSprint.finalItems], sprint.finalItems)
        }
    }

    /** One assignee bucket's frozen figures, straight off the stored scope JSON — never the production reader. */
    private data class FrozenFigures(val initialMd: BigDecimal, val initialItems: Int, val finalMd: BigDecimal, val finalItems: Int)

    private suspend fun frozenScopeFigures(connectionId: UInt, sprintId: Long): Map<String?, FrozenFigures> {
        val scopeJson = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprintSnapshot.selectAll()
                .where {
                    (MetricsTables.FactSprintSnapshot.connectionId eq connectionId) and
                        (MetricsTables.FactSprintSnapshot.sprintId eq sprintId)
                }
                .toList().single()[MetricsTables.FactSprintSnapshot.scope]
        }
        fun md(value: JsonElement): BigDecimal =
            if (value is JsonNull) BigDecimal.ZERO else BigDecimal((value as JsonPrimitive).content).setScale(2, RoundingMode.HALF_UP)
        return Json.parseToJsonElement(scopeJson).jsonArray.map { it.jsonObject }
            .groupBy { it.getValue("assigneeAtCommitment").jsonPrimitive.contentOrNull }
            .mapValues { (_, items) ->
                fun flag(item: JsonObject, key: String) = item.getValue(key).jsonPrimitive.boolean
                val committed = items.filter { flag(it, "committed") && flag(it, "inScopeAtClose") }
                val final = items.filter { flag(it, "inScopeAtClose") }
                FrozenFigures(
                    initialMd = committed.fold(BigDecimal.ZERO) { acc, i -> acc + md(i.getValue("estimateAtCommitmentMd")) },
                    initialItems = committed.size,
                    finalMd = final.fold(BigDecimal.ZERO) { acc, i -> acc + md(i.getValue("estimateAtCloseMd")) },
                    finalItems = final.size,
                )
            }
    }

    @Test
    fun `USER level frozen figures sum with the unassigned bucket to the team snapshot`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportVelocityGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-velocity-user-frozen")

        val team = client.get("/api/v1/reports/velocity?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
            .body<VelocityReport>()
        val teamSnapshot = assertNotNull(team.sprints.single().snapshot, "the golden sprint is closed and team-mapped, so it is frozen")
        val frozen = frozenScopeFigures(connId, golden.sprintId)
        val named = team.groups.mapNotNull { it.accountId }
        assertTrue(named.isNotEmpty(), "expected at least one named assignee-at-commitment group")

        var sumInitialMd = frozen[null]?.initialMd ?: BigDecimal.ZERO
        var sumFinalMd = frozen[null]?.finalMd ?: BigDecimal.ZERO
        var sumInitialItems = frozen[null]?.initialItems ?: 0
        var sumFinalItems = frozen[null]?.finalItems ?: 0
        for (accountId in named) {
            val response = client.get(
                "/api/v1/reports/velocity?connectionId=$connId&teamId=$floTeamId&accountId=$accountId&sprintId=${golden.sprintId}",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val sprint = response.body<VelocityReport>().sprints.single()
            val snapshot = assertNotNull(sprint.snapshot, "$accountId: a snapshotted sprint has per-user frozen figures")
            val expected = frozen.getValue(accountId)
            assertEquals(0, expected.initialMd.compareTo(BigDecimal.valueOf(snapshot.initialMd)), "$accountId initialMd")
            assertEquals(expected.initialItems, snapshot.initialItems, "$accountId initialItems")
            assertEquals(0, expected.finalMd.compareTo(BigDecimal.valueOf(snapshot.finalMd)), "$accountId finalMd")
            assertEquals(expected.finalItems, snapshot.finalItems, "$accountId finalItems")
            // The fixture's snapshot is frozen from the very rows the live figures read: no drift.
            assertEquals(sprint.initialMd, snapshot.initialMd, "$accountId live == frozen initialMd")
            assertEquals(sprint.finalItems, snapshot.finalItems, "$accountId live == frozen finalItems")
            assertTrue(!sprint.drift, "$accountId: no drift")
            sumInitialMd += BigDecimal.valueOf(snapshot.initialMd)
            sumFinalMd += BigDecimal.valueOf(snapshot.finalMd)
            sumInitialItems += snapshot.initialItems
            sumFinalItems += snapshot.finalItems
        }
        // The USER level cannot name the unassigned bucket: it joins from the stored JSON. Σ is exact to the cent.
        assertEquals(0, BigDecimal.valueOf(teamSnapshot.initialMd).compareTo(sumInitialMd), "Sigma users + unassigned initialMd")
        assertEquals(0, BigDecimal.valueOf(teamSnapshot.finalMd).compareTo(sumFinalMd), "Sigma users + unassigned finalMd")
        assertEquals(teamSnapshot.initialItems, sumInitialItems)
        assertEquals(teamSnapshot.finalItems, sumFinalItems)
    }

    @Test
    fun `USER level snapshot is null for a sprint with no snapshot and drift stays false`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportVelocityGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-velocity-user-no-snapshot")

        val accountId = client.get("/api/v1/reports/velocity?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
            .body<VelocityReport>().groups.mapNotNull { it.accountId }.first()
        val open = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprint.selectAll()
                .where {
                    (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.teamId eq floTeamId) and
                        MetricsTables.FactSprint.completeAt.isNull()
                }
                .toList().first()[MetricsTables.FactSprint.sprintId]
        }
        val sprint = client.get(
            "/api/v1/reports/velocity?connectionId=$connId&teamId=$floTeamId&accountId=$accountId&sprintId=$open",
        ).body<VelocityReport>().sprints.single()
        assertEquals(null, sprint.snapshot)
        assertTrue(!sprint.drift)
    }

    /** One stored-scope item in the writer's shape (`MetricsStore.sprintScopeItemsJson`), built independently of the production parser. */
    private fun scopeItem(
        issueId: Long,
        assignee: String?,
        committed: Boolean,
        inScopeAtClose: Boolean,
        commitMd: Double?,
        closeMd: Double?,
    ): JsonObject = buildJsonObject {
        put("issueId", issueId)
        put("addedAtMs", null as Long?)
        put("removedAtMs", null as Long?)
        put("committed", committed)
        put("inScopeAtClose", inScopeAtClose)
        put("estimateAtCommitmentMd", commitMd)
        put("estimateAtCloseMd", closeMd)
        put("estimateAtDoneMd", null as Double?)
        put("assigneeAtCommitment", assignee)
        put("doneInSprint", false)
        put("carriedOver", false)
        put("dropped", false)
    }

    @Test
    fun `USER level frozen figures come from the stored snapshot scope not the live rows, per connection`() = testApplication {
        usePostgresTestcontainer()
        val sprintId = reportVelocityGolden.sprintId
        val (connA, teamA) = FrozenScopeFixtures.derivedDisabledClone("jira-velocity-frozen", "velocity-frozen-team-a")
        val (connB, teamB) = FrozenScopeFixtures.derivedDisabledClone("jira-velocity-frozen", "velocity-frozen-team-b")
        val client = seededClient("reports-velocity-user-frozen-synthetic")

        fun userUrl(connId: UInt, teamId: UInt, accountId: String) =
            "/api/v1/reports/velocity?connectionId=$connId&teamId=$teamId&accountId=$accountId&sprintId=$sprintId"
        val ann = client.get("/api/v1/reports/velocity?connectionId=$connA&teamId=$teamA&sprintId=$sprintId")
            .body<VelocityReport>().groups.mapNotNull { it.accountId }.first()
        val before = client.get(userUrl(connA, teamA, ann)).body<VelocityReport>().sprints.single()
        assertNotNull(before.snapshot, "the clone's golden sprint is closed and team-mapped, so it is frozen")
        assertTrue(!before.drift, "before the overwrite the snapshot was frozen from the live rows")

        val other = "synthetic-other-account"
        // Connection A: [ann] 2.504 -> 2.50 and 2.506 -> 2.51 (committed, in scope), one committed item with NO estimate
        // (counts as an item, 0 MD), one added item (final only), one removed item (neither bucket); plus an item with a
        // null assignee and one of `other`, which must not count for `ann`.
        FrozenScopeFixtures.overwriteSnapshotScope(
            connA, sprintId,
            buildJsonArray {
                add(scopeItem(1, ann, committed = true, inScopeAtClose = true, commitMd = 2.504, closeMd = 3.0))
                add(scopeItem(2, ann, committed = true, inScopeAtClose = true, commitMd = 2.506, closeMd = 4.126))
                add(scopeItem(3, ann, committed = true, inScopeAtClose = true, commitMd = null, closeMd = null))
                add(scopeItem(4, ann, committed = false, inScopeAtClose = true, commitMd = null, closeMd = 1.5))
                add(scopeItem(5, ann, committed = true, inScopeAtClose = false, commitMd = 9.0, closeMd = 9.0))
                add(scopeItem(6, null, committed = true, inScopeAtClose = true, commitMd = 100.0, closeMd = 100.0))
                add(scopeItem(7, other, committed = true, inScopeAtClose = true, commitMd = 50.0, closeMd = 50.0))
            },
        )
        // Connection B shares the SAME sprint id (one Jira site, two connections) but a different snapshot.
        FrozenScopeFixtures.overwriteSnapshotScope(
            connB, sprintId,
            buildJsonArray { add(scopeItem(1, ann, committed = true, inScopeAtClose = true, commitMd = 1.0, closeMd = 1.0)) },
        )

        val a = client.get(userUrl(connA, teamA, ann)).body<VelocityReport>().sprints.single()
        val snapshotA = assertNotNull(a.snapshot)
        // Hand-computed: initial = items 1-3 -> 3 items, 2.50 + 2.51 + 0 = 5.01 MD;
        // final = items 1-4 -> 4 items, 3.00 + 4.13 + 0 + 1.50 = 8.63 MD.
        assertEquals(5.01, snapshotA.initialMd, ABS_TOLERANCE)
        assertEquals(3, snapshotA.initialItems)
        assertEquals(8.63, snapshotA.finalMd, ABS_TOLERANCE)
        assertEquals(4, snapshotA.finalItems)
        assertTrue(a.drift, "the frozen figures differ from the live ones")
        assertNotEquals(snapshotA.initialMd, a.initialMd, "the live figures still come from fact_sprint_scope, not the snapshot")
        assertNotEquals(snapshotA.finalMd, a.finalMd)

        val b = client.get(userUrl(connB, teamB, ann)).body<VelocityReport>().sprints.single()
        val snapshotB = assertNotNull(b.snapshot)
        assertEquals(1.0, snapshotB.initialMd, ABS_TOLERANCE)
        assertEquals(1, snapshotB.initialItems)
        assertEquals(1.0, snapshotB.finalMd, ABS_TOLERANCE)
        assertEquals(1, snapshotB.finalItems)
        assertTrue(b.drift)

        // An account with no item in the stored scope has zero frozen figures (not null: the sprint IS frozen).
        val nobody = assertNotNull(client.get(userUrl(connB, teamB, other)).body<VelocityReport>().sprints.single().snapshot)
        assertEquals(0.0, nobody.initialMd)
        assertEquals(0, nobody.initialItems)
        assertEquals(0.0, nobody.finalMd)
        assertEquals(0, nobody.finalItems)
    }

    @Test
    fun `frozen scope parsing fails loudly on a malformed row and keeps a null estimate null`() {
        fun parse(json: String) = frozenContributionsOf(json, 7u, 42L)
        fun item(vararg overrides: Pair<String, String>): String {
            val base = linkedMapOf(
                "committed" to "true", "inScopeAtClose" to "true", "estimateAtCommitmentMd" to "1.5",
                "estimateAtCloseMd" to "2.5", "assigneeAtCommitment" to "\"acc\"",
            )
            overrides.forEach { (key, value) -> base[key] = value }
            return base.entries.joinToString(prefix = "[{", postfix = "}]") { "\"${it.key}\":${it.value}" }
        }
        fun message(json: String) = assertFailsWith<IllegalStateException> { parse(json) }.message.orEmpty()

        val ok = parse(item()).single()
        assertEquals(1.5, ok.commitMd)
        assertEquals("acc", ok.accountId)
        val unestimated = parse(item("estimateAtCommitmentMd" to "null", "assigneeAtCommitment" to "null")).single()
        assertEquals(null, unestimated.commitMd)
        assertEquals(null, unestimated.accountId)

        val prefix = "fact_sprint_snapshot.scope malformed for connection 7 sprint 42: "
        assertEquals(prefix + "item 0 is missing key committed", message("""[{"assigneeAtCommitment":null,"inScopeAtClose":true}]"""))
        assertEquals(prefix + "item 0 key committed is not a boolean", message(item("committed" to "\"yes\"")))
        assertEquals(prefix + "item 0 key inScopeAtClose is not a boolean", message(item("inScopeAtClose" to "null")))
        assertEquals(prefix + "item 0 key estimateAtCloseMd is not a number", message(item("estimateAtCloseMd" to "\"abc\"")))
        assertEquals(prefix + "item 0 key estimateAtCommitmentMd is not a number", message(item("estimateAtCommitmentMd" to "true")))
        assertEquals(prefix + "item 0 key assigneeAtCommitment is not a string", message(item("assigneeAtCommitment" to "5")))
        assertEquals(prefix + "item 0 key estimateAtCloseMd is not a scalar", message(item("estimateAtCloseMd" to "[]")))
        assertEquals(prefix + "item 0 is not an object", message("[1]"))
        assertEquals(prefix + "not a JSON array", message("{}"))
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
