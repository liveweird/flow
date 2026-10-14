package ch.nokillswit

import ch.nokillswit.metrics.MetricsSprintCapacity
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.asRequest
import ch.nokillswit.reports.CommitmentsReport
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
private data class CommitmentsGoldenSprint(
    val sprintId: Long,
    val committedMd: Double,
    val committedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

@Serializable
private data class CommitmentsGoldenFixture(val sprint: CommitmentsGoldenSprint)

@Serializable
private data class CommitmentsExpectedFixture(val golden: CommitmentsGoldenFixture)

private val REPORT_COMMITMENTS_JSON = Json { ignoreUnknownKeys = true }

private val reportCommitmentsGolden: CommitmentsGoldenSprint by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    REPORT_COMMITMENTS_JSON.decodeFromString<CommitmentsExpectedFixture>(file.readText()).golden.sprint
}

/**
 * `GET /api/v1/reports/commitments` (v0.3.0 M4 commit 10b, Report 1, `.claude/docs/measures.md`
 * "Report 1 — Commitments"). Reads [DerivedStubFixture]'s connection (one FLO-mapped team, one
 * completed DERIVE). Every request narrows via `connectionId` to that one connection, so other
 * tests' connections in the shared database never leak into the figures. `seededClient` defaults to a
 * non-admin (`UserRole.USER`) caller, so every `200` below also proves D12 (`.claude/docs/reports.md`
 * "Access posture").
 */
class ReportCommitmentsTest {

    private suspend fun floTeamId(connectionId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connectionId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
    }

    @Test
    fun `UNIT level lists the FLO team's sprints with finalMd matching fact_sprint`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportCommitmentsGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-commitments-unit")

        val response = client.get(
            "/api/v1/reports/commitments?connectionId=$connId&from=2025-09-01&to=2026-03-06",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CommitmentsReport>()

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
        val golden = reportCommitmentsGolden
        val client = seededClient("reports-commitments-sprint-id")

        val response = client.get("/api/v1/reports/commitments?connectionId=$connId&sprintId=${golden.sprintId}")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CommitmentsReport>()

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
        val golden = reportCommitmentsGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-commitments-team")

        val response = client.get(
            "/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CommitmentsReport>()

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
        val floTeamId = floTeamId(connId, reportCommitmentsGolden.sprintId)
        val client = seededClient("reports-commitments-open-sprint")

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
            val response = client.get("/api/v1/reports/commitments?connectionId=$connId&sprintId=${row[MetricsTables.FactSprint.sprintId]}")
            assertEquals(HttpStatusCode.OK, response.status)
            val sprint = response.body<CommitmentsReport>().sprints.single()
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
        val golden = reportCommitmentsGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-commitments-user-frozen")

        val team = client.get("/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
            .body<CommitmentsReport>()
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
                "/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&accountId=$accountId&sprintId=${golden.sprintId}",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val sprint = response.body<CommitmentsReport>().sprints.single()
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
        val golden = reportCommitmentsGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-commitments-user-no-snapshot")

        val accountId = client.get("/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
            .body<CommitmentsReport>().groups.mapNotNull { it.accountId }.first()
        val open = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprint.selectAll()
                .where {
                    (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.teamId eq floTeamId) and
                        MetricsTables.FactSprint.completeAt.isNull()
                }
                .toList().first()[MetricsTables.FactSprint.sprintId]
        }
        val sprint = client.get(
            "/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&accountId=$accountId&sprintId=$open",
        ).body<CommitmentsReport>().sprints.single()
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
        val sprintId = reportCommitmentsGolden.sprintId
        val (connA, teamA) = FrozenScopeFixtures.derivedDisabledClone("jira-commitments-frozen", "commitments-frozen-team-a")
        val (connB, teamB) = FrozenScopeFixtures.derivedDisabledClone("jira-commitments-frozen", "commitments-frozen-team-b")
        val client = seededClient("reports-commitments-user-frozen-synthetic")

        fun userUrl(connId: UInt, teamId: UInt, accountId: String) =
            "/api/v1/reports/commitments?connectionId=$connId&teamId=$teamId&accountId=$accountId&sprintId=$sprintId"
        val ann = client.get("/api/v1/reports/commitments?connectionId=$connA&teamId=$teamA&sprintId=$sprintId")
            .body<CommitmentsReport>().groups.mapNotNull { it.accountId }.first()
        val before = client.get(userUrl(connA, teamA, ann)).body<CommitmentsReport>().sprints.single()
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

        val a = client.get(userUrl(connA, teamA, ann)).body<CommitmentsReport>().sprints.single()
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

        val b = client.get(userUrl(connB, teamB, ann)).body<CommitmentsReport>().sprints.single()
        val snapshotB = assertNotNull(b.snapshot)
        assertEquals(1.0, snapshotB.initialMd, ABS_TOLERANCE)
        assertEquals(1, snapshotB.initialItems)
        assertEquals(1.0, snapshotB.finalMd, ABS_TOLERANCE)
        assertEquals(1, snapshotB.finalItems)
        assertTrue(b.drift)

        // An account with no item in the stored scope has zero frozen figures (not null: the sprint IS frozen).
        val nobody = assertNotNull(client.get(userUrl(connB, teamB, other)).body<CommitmentsReport>().sprints.single().snapshot)
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

    /** The stored `(capacity_md, capacity_source)` of every sprint of [connectionId], off `fact_sprint`/`dim_sprint`, not the report. */
    private suspend fun storedCapacities(connectionId: UInt): Map<Long, Pair<Double?, String?>> =
        suspendTransaction(sharedDatabaseForTests()) {
            val fact = MetricsTables.FactSprint.selectAll().where { MetricsTables.FactSprint.connectionId eq connectionId }.toList()
                .associate { it[MetricsTables.FactSprint.sprintId] to it[MetricsTables.FactSprint.capacityMd]?.toDouble() }
            MetricsTables.DimSprint.selectAll().where { MetricsTables.DimSprint.connectionId eq connectionId }.toList()
                .filter { it[MetricsTables.DimSprint.sprintId] in fact }
                .associate {
                    val id = it[MetricsTables.DimSprint.sprintId]
                    id to (fact.getValue(id) to it[MetricsTables.DimSprint.capacitySource])
                }
        }

    @Test
    fun `capacity is the stored fact_sprint capacity and source at UNIT and TEAM level, and null at USER level`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportCommitmentsGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-commitments-capacity")
        val stored = storedCapacities(connId)

        val unit = client.get("/api/v1/reports/commitments?connectionId=$connId&from=2025-09-01&to=2026-03-06").body<CommitmentsReport>()
        assertTrue(unit.sprints.isNotEmpty())
        for (sprint in unit.sprints) {
            val (capacityMd, source) = stored.getValue(sprint.sprintId)
            assertEquals(capacityMd, sprint.capacityMd, "UNIT sprint ${sprint.sprintId} capacityMd")
            assertEquals(source, sprint.capacitySource, "UNIT sprint ${sprint.sprintId} capacitySource")
            assertTrue(sprint.byUser.isEmpty(), "byUser is USER level only")
        }
        // The shared fixture configures no override: the FLO team's sprints carry the members x working days DEFAULT.
        val (goldenCapacity, goldenSource) = stored.getValue(golden.sprintId)
        assertEquals("DEFAULT", goldenSource)
        assertNotNull(goldenCapacity)

        val team = client.get("/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
            .body<CommitmentsReport>()
        val teamSprint = team.sprints.single()
        assertEquals(goldenCapacity, teamSprint.capacityMd)
        assertEquals("DEFAULT", teamSprint.capacitySource)
        assertTrue(teamSprint.byUser.isEmpty())

        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.get(
            "/api/v1/reports/commitments?connectionId=$connId&teamId=$floTeamId&accountId=$account&sprintId=${golden.sprintId}",
        ).body<CommitmentsReport>().sprints.single()
        assertEquals(null, user.capacityMd, "capacity is team-only: hidden when users are picked")
        assertEquals(null, user.capacitySource)
    }

    @Test
    fun `a configured sprint capacity overrides the default at UNIT and TEAM level`() = testApplication {
        usePostgresTestcontainer()
        val sprintId = reportCommitmentsGolden.sprintId
        val (connId, teamId) = FrozenScopeFixtures.derivedDisabledClone("jira-commitments-capacity", "commitments-capacity-team")
        val configured = 42.5
        val config = DerivedStubFixture.metricsConfig()
        val current = config.effectiveConfig(connId)
        config.replaceConfig(connId, current.asRequest().copy(sprintCapacities = listOf(MetricsSprintCapacity(sprintId, configured))))
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 2u) }
        val client = seededClient("reports-commitments-capacity-configured")

        // The expectation is what the admin configured, cross-checked against the stored row — not the report.
        assertEquals(configured to "CONFIGURED", storedCapacities(connId).getValue(sprintId))
        val unit = client.get("/api/v1/reports/commitments?connectionId=$connId&sprintId=$sprintId")
            .body<CommitmentsReport>().sprints.single()
        assertEquals(configured, unit.capacityMd)
        assertEquals("CONFIGURED", unit.capacitySource)
        val team = client.get("/api/v1/reports/commitments?connectionId=$connId&teamId=$teamId&sprintId=$sprintId")
            .body<CommitmentsReport>().sprints.single()
        assertEquals(configured, team.capacityMd)
        assertEquals("CONFIGURED", team.capacitySource)
    }

    private data class PickedAccounts(val connId: UInt, val teamId: UInt, val sprintId: Long, val accounts: List<String>)

    private suspend fun io.ktor.client.HttpClient.pickedAccounts(): PickedAccounts {
        val connId = DerivedStubFixture.connectionId()
        val sprintId = reportCommitmentsGolden.sprintId
        val teamId = floTeamId(connId, sprintId)
        val accounts = get("/api/v1/reports/commitments?connectionId=$connId&teamId=$teamId&sprintId=$sprintId")
            .body<CommitmentsReport>().groups.mapNotNull { it.accountId }
        assertTrue(accounts.size >= 2, "the golden sprint must have at least two named assignees-at-commitment, got $accounts")
        return PickedAccounts(connId, teamId, sprintId, accounts)
    }

    private fun userUrl(p: PickedAccounts, accounts: List<String>) =
        "/api/v1/reports/commitments?connectionId=${p.connId}&teamId=${p.teamId}&sprintId=${p.sprintId}" +
            accounts.joinToString("") { "&accountId=$it" }

    private fun md(vararg values: Double) = values.fold(BigDecimal.ZERO) { acc, v -> acc + BigDecimal.valueOf(v) }

    @Test
    fun `several accountIds sum the sprint and split it per user in request order`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-commitments-multi-user")
        val p = client.pickedAccounts()
        val (a, b) = p.accounts
        val one = client.get(userUrl(p, listOf(a))).body<CommitmentsReport>()
        val two = client.get(userUrl(p, listOf(b))).body<CommitmentsReport>()
        val both = client.get(userUrl(p, listOf(b, a))).body<CommitmentsReport>()

        val sprintA = one.sprints.single()
        val sprintB = two.sprints.single()
        val sprint = both.sprints.single()
        assertEquals(0, md(sprintA.initialMd, sprintB.initialMd).compareTo(md(sprint.initialMd)), "initialMd is the sum of the single runs")
        assertEquals(0, md(sprintA.finalMd, sprintB.finalMd).compareTo(md(sprint.finalMd)), "finalMd is the sum of the single runs")
        assertEquals(sprintA.initialItems + sprintB.initialItems, sprint.initialItems)
        assertEquals(sprintA.finalItems + sprintB.finalItems, sprint.finalItems)
        val snapshotA = assertNotNull(sprintA.snapshot)
        val snapshotB = assertNotNull(sprintB.snapshot)
        val snapshot = assertNotNull(sprint.snapshot, "the golden sprint is frozen")
        assertEquals(0, md(snapshotA.initialMd, snapshotB.initialMd).compareTo(md(snapshot.initialMd)), "frozen initialMd sums")
        assertEquals(0, md(snapshotA.finalMd, snapshotB.finalMd).compareTo(md(snapshot.finalMd)), "frozen finalMd sums")
        assertEquals(snapshotA.initialItems + snapshotB.initialItems, snapshot.initialItems)
        assertEquals(snapshotA.finalItems + snapshotB.finalItems, snapshot.finalItems)
        assertTrue(!sprint.drift)
        assertEquals(null, sprint.capacityMd)
        assertEquals(null, sprint.capacitySource)

        // byUser: request order (b, a), each entry the single-account run's own figures, labelled like the TEAM-level groups.
        assertEquals(listOf(b, a), sprint.byUser.map { it.accountId })
        val teamLabels = client.get("/api/v1/reports/commitments?connectionId=${p.connId}&teamId=${p.teamId}&sprintId=${p.sprintId}")
            .body<CommitmentsReport>().groups.filter { it.accountId != null }.associate { it.accountId!! to it.label }
        for ((share, single) in sprint.byUser.zip(listOf(sprintB, sprintA))) {
            assertEquals(teamLabels.getValue(share.accountId), share.label)
            assertEquals(single.initialMd, share.initialMd)
            assertEquals(single.initialItems, share.initialItems)
            assertEquals(single.finalMd, share.finalMd)
            assertEquals(single.finalItems, share.finalItems)
        }
        // groups: each picked user's period totals (the whole request here is one sprint, so they equal byUser), label order.
        assertEquals(setOf(a, b), both.groups.map { it.accountId }.toSet())
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        for (group in both.groups) {
            val share = sprint.byUser.single { it.accountId == group.accountId }
            assertEquals(share.finalMd, group.finalMd)
            assertEquals(share.finalItems, group.finalItems)
            assertEquals(share.initialMd, group.initialMd)
            assertEquals(share.initialItems, group.initialItems)
        }
    }

    @Test
    fun `one accountId keeps the figures with a one-entry byUser and a user with no scope is a zero entry`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-commitments-one-user")
        val p = client.pickedAccounts()
        val a = p.accounts.first()
        val single = client.get(userUrl(p, listOf(a))).body<CommitmentsReport>()
        val sprint = single.sprints.single()
        val share = sprint.byUser.single()
        assertEquals(a, share.accountId)
        assertEquals(sprint.initialMd, share.initialMd)
        assertEquals(sprint.finalMd, share.finalMd)
        assertEquals(sprint.initialItems, share.initialItems)
        assertEquals(sprint.finalItems, share.finalItems)
        assertEquals(1, single.groups.size)
        assertEquals(a, single.groups.single().accountId)
        assertEquals(sprint.finalMd, single.groups.single().finalMd)
        // A repeated identical value collapses rather than fails.
        assertEquals(sprint, client.get(userUrl(p, listOf(a, a))).body<CommitmentsReport>().sprints.single())

        val nobody = "commitments-nobody-account"
        val both = client.get(userUrl(p, listOf(nobody, a))).body<CommitmentsReport>()
        val zero = both.sprints.single().byUser.first()
        assertEquals(nobody, zero.accountId)
        assertEquals(nobody, zero.label, "an account with no known display name is labelled by its id")
        assertEquals(0.0, zero.initialMd)
        assertEquals(0, zero.finalItems)
        assertEquals(sprint.finalMd, both.sprints.single().finalMd, "a zero account does not move the sum")
        assertEquals(setOf(nobody, a), both.groups.map { it.accountId }.toSet())
    }

    @Test
    fun `at most eight accountIds on commitments, and a repeated accountId on another report stays 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-commitments-account-bound")
        val p = client.pickedAccounts()
        fun ids(count: Int) = (1..count).map { "commitments-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(userUrl(p, ids(8))).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(userUrl(p, ids(9))).status)
        val withoutTeam = client.get("/api/v1/reports/commitments?connectionId=${p.connId}&accountId=a")
        assertEquals(HttpStatusCode.BadRequest, withoutTeam.status, "accountId requires teamId")
        val cycleTime = "/api/v1/reports/cycle-time?connectionId=${p.connId}&teamId=${p.teamId}&sprintId=${p.sprintId}"
        assertEquals(HttpStatusCode.OK, client.get("$cycleTime&accountId=${p.accounts[0]}").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$cycleTime&accountId=${p.accounts[0]}&accountId=${p.accounts[1]}").status)
    }

    @Test
    fun `unknown sprintId is 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-commitments-unknown-sprint")

        val response = client.get("/api/v1/reports/commitments?connectionId=$connId&sprintId=999999999")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `from after to is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-commitments-bad-range")

        val response = client.get("/api/v1/reports/commitments?from=2026-01-01&to=2025-01-01")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private companion object {
        const val ABS_TOLERANCE = 0.005
    }
}
