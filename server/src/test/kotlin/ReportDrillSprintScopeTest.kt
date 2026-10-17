package ch.nokillswit

import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.sprintTotals
import ch.nokillswit.reports.CommitmentsReport
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.SprintConsistencyReport
import ch.nokillswit.reports.SprintConsistencySprint
import ch.nokillswit.reports.ThroughputReport
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * `GET /api/v1/reports/sprint-consistency/items` (the ONE sprint-scope drill, `.claude/docs/reports.md` "Drill to items") and
 * `GET /api/v1/reports/throughput/items` in its sprint mode. The reconciliation gate: for EVERY sprint of the derived stub fixture
 * (closed and open) and EVERY bucket, the drill's bar equals the Sprint consistency report's figure pair, COMMITTED/FINAL equal
 * Commitments' `initial`/`final`, DELIVERED equals Throughput's `bySprint`, and the listed items equal an independent
 * re-derivation of the bucket from the stored scope rows; with picked accounts the union and the per-person segments reconcile
 * with every report's `byUser`. A failing reconciliation is a real divergence between a bar and its items, never grounds to
 * loosen a test.
 */
class ReportDrillSprintScopeTest {

    private val buckets = SprintBucket.entries

    private suspend fun floTeamId(connId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
    }

    private fun goldenSprintId(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("golden").jsonObject.getValue("sprint").jsonObject
            .getValue("sprintId").jsonPrimitive.content.toLong()
    }

    private suspend fun HttpClient.consistency(query: String): SprintConsistencyReport {
        val response = get("/api/v1/reports/sprint-consistency?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET sprint-consistency?$query")
        return response.body()
    }

    private suspend fun HttpClient.commitments(query: String): CommitmentsReport {
        val response = get("/api/v1/reports/commitments?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET commitments?$query")
        return response.body()
    }

    private suspend fun HttpClient.throughput(query: String): ThroughputReport {
        val response = get("/api/v1/reports/throughput?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/sprint-consistency/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET sprint-consistency/items?$query")
        return response.body()
    }

    private suspend fun HttpClient.assertBadRequest(path: String, what: String) =
        assertEquals(HttpStatusCode.BadRequest, get(path).status, what)

    private suspend fun HttpClient.deliveredItems(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/throughput/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput/items?$query")
        return response.body()
    }

    /** The bucket's figure pair `(MD, items)` of a Sprint consistency row. */
    private fun SprintConsistencySprint.pair(bucket: SprintBucket): Pair<Double, Int> = when (bucket) {
        SprintBucket.COMMITTED -> committedMd to committedItems
        SprintBucket.ADDED -> addedMd to addedItems
        SprintBucket.REMOVED -> removedMd to removedItems
        SprintBucket.FINAL -> finalMd to finalItems
        SprintBucket.DELIVERED -> deliveredMd to deliveredItems
        SprintBucket.CARRIED_OVER -> carriedOverMd to carriedOverItems
        SprintBucket.DROPPED -> droppedMd to droppedItems
    }

    /** The stored scope rows of one sprint (optionally of the picked assignees), the way the kernel wrote them. */
    private suspend fun storedScope(connId: UInt, sprintId: Long, accounts: Set<String>? = null): List<SprintScopeItem> =
        suspendTransaction(sharedDatabaseForTests()) {
            val s = MetricsTables.FactSprintScope
            s.selectAll().where { (s.connectionId eq connId) and (s.sprintId eq sprintId) }.toList().map {
                SprintScopeItem(
                    issueId = it[s.issueId], addedAtMs = it[s.addedAt], removedAtMs = it[s.removedAt], committed = it[s.committed],
                    inScopeAtClose = it[s.inScopeAtClose], estimateAtCommitmentMd = it[s.estimateAtCommitmentMd]?.toDouble(),
                    estimateAtCloseMd = it[s.estimateAtCloseMd]?.toDouble(), estimateAtDoneMd = it[s.estimateAtDoneMd]?.toDouble(),
                    assigneeAtCommitment = it[s.assigneeAtCommitment], doneInSprint = it[s.doneInSprint],
                    carriedOver = it[s.carriedOver], dropped = it[s.dropped],
                )
            }.filter { accounts == null || it.assigneeAtCommitment in accounts }
        }

    /** The bucket's membership and estimate column, written out from `.claude/docs/reports.md` Report 6 — never `inBucket`/`bucketMd`. */
    private fun SprintScopeItem.member(bucket: SprintBucket): Boolean = when (bucket) {
        SprintBucket.COMMITTED -> committed && inScopeAtClose
        SprintBucket.ADDED -> addedAtMs != null
        SprintBucket.REMOVED -> removedAtMs != null
        SprintBucket.FINAL -> inScopeAtClose
        SprintBucket.DELIVERED -> doneInSprint
        SprintBucket.CARRIED_OVER -> carriedOver
        SprintBucket.DROPPED -> dropped
    }

    private fun SprintScopeItem.md(bucket: SprintBucket): Double? = when (bucket) {
        SprintBucket.COMMITTED, SprintBucket.ADDED, SprintBucket.REMOVED -> estimateAtCommitmentMd
        SprintBucket.FINAL, SprintBucket.CARRIED_OVER, SprintBucket.DROPPED -> estimateAtCloseMd
        SprintBucket.DELIVERED -> estimateAtDoneMd
    }

    private fun mdOf(values: List<Double?>): Double =
        values.fold(BigDecimal.ZERO) { acc, v -> acc + (v ?: 0.0).toBigDecimal().setScale(2, RoundingMode.HALF_UP) }.toDouble()

    /**
     * Everything one drill must honour: the bar is the expected `(MD, items)` pair AND the independent re-derivation from
     * [stored] rows, the list adds up to it (below the cap), it is sorted, and it lists only [accounts] when picked.
     */
    private fun assertBarReconciles(
        label: String,
        drill: ReportDrillItems,
        bucket: SprintBucket,
        expected: Pair<Double, Int>,
        stored: List<SprintScopeItem>,
        completedAt: Long?,
        teamId: UInt,
    ) {
        val members = stored.filter { it.member(bucket) }
        assertEquals(expected.second, drill.bar.count, "$label bar.count == the report's items")
        assertEquals(expected.first, drill.bar.sumMd!!, MD_TOLERANCE, "$label bar.sumMd == the report's MD")
        assertEquals(members.size, drill.bar.count, "$label bar.count == the independent count")
        assertEquals(mdOf(members.map { it.md(bucket) }), drill.bar.sumMd, MD_TOLERANCE, "$label bar.sumMd == the independent MD")
        assertNull(drill.bar.from)
        assertNull(drill.bar.to)
        assertEquals(drill.bar.count, drill.total, "$label total == bar.count")
        assertEquals(drill.total, drill.items.size, "$label every item is listed below the cap")
        assertEquals(false, drill.truncated)
        assertEquals(expected.first, mdOf(drill.items.map { it.figure }), MD_TOLERANCE, "$label Σ figure == the bar's MD")
        assertEquals(
            members.map { it.md(bucket) }.sortedBy { it ?: -1.0 },
            drill.items.map { it.figure }.sortedBy { it ?: -1.0 },
            "$label the figures are the bucket's estimate column",
        )
        assertEquals(members.count { it.md(bucket) == null }, drill.items.count { it.figure == null }, "$label unestimated items")
        assertEquals(
            members.map { it.assigneeAtCommitment }.sortedBy { it ?: "" }, drill.items.map { it.accountId }.sortedBy { it ?: "" },
            "$label assignee at commitment",
        )
        assertTrue(drill.items.all { it.anchorAt == completedAt && it.teamId == teamId && it.itemKind.name == "TASK" }, label)
        assertEquals(drill.items.size, drill.items.map { it.issueKey }.distinct().size, "$label distinct issue keys")
        assertTrue(drill.items.all { it.issueKey.isNotBlank() }, label)
        val figures = drill.items.map { it.figure }
        assertEquals(figures.filterNotNull().sortedDescending() + figures.filter { it == null }, figures, "$label figure order")
    }

    @Test
    fun `every bucket of every sprint reconciles with its drill at UNIT and TEAM level, Commitments and Throughput included`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val floTeamId = floTeamId(connId, goldenSprintId())
            val client = seededClient("reports-drill-sprint-levels")
            val openSprintIds = suspendTransaction(sharedDatabaseForTests()) {
                val f = MetricsTables.FactSprint
                f.selectAll().where { (f.connectionId eq connId) and f.completeAt.isNull() and f.teamId.isNotNull() }.toList()
                    .map { it[f.sprintId] }
            }
            assertTrue(openSprintIds.isNotEmpty(), "the stub fixture must carry an open sprint")

            for ((level, scope) in listOf("UNIT" to "", "TEAM" to "&teamId=$floTeamId")) {
                val closed = client.consistency("connectionId=$connId&lastSprints=52$scope").sprints
                assertTrue(closed.size > 1, "$level has several closed sprints")
                val sprints = closed + openSprintIds.flatMap { client.consistency("connectionId=$connId&sprintId=$it$scope").sprints }
                for (sprint in sprints) {
                    val one = "connectionId=$connId&sprintId=${sprint.sprintId}$scope"
                    val stored = storedScope(connId, sprint.sprintId)
                    val commitments = client.commitments(one).sprints.single()
                    val throughput = client.throughput(one).bySprint.single()
                    for (bucket in buckets) {
                        val label = "$level/sprint ${sprint.sprintId}/$bucket"
                        val drill = client.items("$one&bucket=$bucket")
                        assertBarReconciles(label, drill, bucket, sprint.pair(bucket), stored, sprint.completedAt, sprint.teamId)
                        assertEquals(client.consistency(one).meta.resolvedSprints, drill.meta.resolvedSprints, "$label resolvedSprints")
                    }
                    // Reports 1 and 2 draw the SAME rows: their bars are these buckets.
                    val committed = client.items("$one&bucket=COMMITTED").bar
                    val final = client.items("$one&bucket=FINAL").bar
                    assertEquals(commitments.initialItems, committed.count, "$level/${sprint.sprintId} Commitments initialItems")
                    assertEquals(commitments.initialMd, committed.sumMd!!, MD_TOLERANCE, "$level/${sprint.sprintId} Commitments initialMd")
                    assertEquals(commitments.finalItems, final.count, "$level/${sprint.sprintId} Commitments finalItems")
                    assertEquals(commitments.finalMd, final.sumMd!!, MD_TOLERANCE, "$level/${sprint.sprintId} Commitments finalMd")
                    val delivered = client.items("$one&bucket=DELIVERED")
                    assertEquals(throughput.deliveredItems, delivered.bar.count, "$level/${sprint.sprintId} Throughput deliveredItems")
                    assertEquals(
                        throughput.deliveredMd, delivered.bar.sumMd!!, MD_TOLERANCE, "$level/${sprint.sprintId} Throughput deliveredMd",
                    )
                    // Throughput's own sprint mode (no bucketStart) lists the very same delivered items.
                    val viaThroughput = client.deliveredItems(one)
                    assertEquals(delivered.bar, viaThroughput.bar, "$level/${sprint.sprintId} throughput/items sprint mode bar")
                    assertEquals(delivered.items, viaThroughput.items, "$level/${sprint.sprintId} throughput/items sprint mode items")
                }
            }
        }

    @Test
    fun `at USER level with two picks the union and both segments reconcile with every report's byUser split`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = goldenSprintId()
        val floTeamId = floTeamId(connId, golden)
        val (a, b) = storedScope(connId, golden).mapNotNull { it.assigneeAtCommitment }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(2).map { it.key }
        val client = seededClient("reports-drill-sprint-user")
        val picks = "accountId=$a&accountId=$b"
        val sprintIds = client.consistency("connectionId=$connId&teamId=$floTeamId&lastSprints=52").sprints.map { it.sprintId }
        var withBoth = 0

        for (sprintId in sprintIds) {
            val base = "connectionId=$connId&teamId=$floTeamId&sprintId=$sprintId&$picks"
            val commitments = client.commitments(base).sprints.single()
            val throughput = client.throughput(base).bySprint.single()
            val stored = storedScope(connId, sprintId)
            val completedAt = client.consistency("connectionId=$connId&sprintId=$sprintId").sprints.single().completedAt
            val perPick = listOf(a, b).associateWith {
                client.consistency("connectionId=$connId&teamId=$floTeamId&sprintId=$sprintId&accountId=$it").sprints.single()
            }
            if (perPick.values.all { it.finalItems > 0 }) withBoth++
            // A sprint holding none of the picks' work would only repeat empty bars (their zero reports still reconcile above).
            if (perPick.values.all { it.finalItems == 0 && it.removedItems == 0 }) continue
            for (bucket in buckets) {
                val label = "USER/sprint $sprintId/$bucket"
                // The union is the picks' rows together = Σ of each pick's own single-account report.
                val union = client.items("$base&bucket=$bucket")
                val expectedUnion = mdOf(perPick.values.map { it.pair(bucket).first }) to perPick.values.sumOf { it.pair(bucket).second }
                assertBarReconciles(
                    label, union, bucket, expectedUnion, stored.filter { it.assigneeAtCommitment == a || it.assigneeAtCommitment == b },
                    completedAt, floTeamId,
                )
                assertTrue(union.items.all { it.accountId == a || it.accountId == b }, "$label lists only the picks")
                var segmentItems = 0
                var segmentMd = 0.0
                for (pick in listOf(a, b)) {
                    val segment = client.items("$base&bucket=$bucket&segmentAccountId=$pick")
                    val expected = perPick.getValue(pick).pair(bucket)
                    assertEquals(expected.second, segment.bar.count, "$label $pick segment count == its single-account report")
                    assertEquals(expected.first, segment.bar.sumMd!!, MD_TOLERANCE, "$label $pick segment MD")
                    assertEquals(segment.bar.count, segment.total)
                    assertEquals(segment.total, segment.items.size)
                    assertTrue(segment.items.all { it.accountId == pick }, "$label a segment lists only its person")
                    segmentItems += segment.bar.count
                    segmentMd += segment.bar.sumMd
                }
                assertEquals(union.bar.count, segmentItems, "$label Σ segments == union count")
                assertEquals(union.bar.sumMd!!, segmentMd, MD_TOLERANCE, "$label Σ segments == union MD")
            }
            // Reports 1 and 2 stack these very segments: their byUser entries are the segments' bars.
            for (share in commitments.byUser) {
                val committed = client.items("$base&bucket=COMMITTED&segmentAccountId=${share.accountId}").bar
                val final = client.items("$base&bucket=FINAL&segmentAccountId=${share.accountId}").bar
                assertEquals(share.initialItems, committed.count, "$sprintId ${share.accountId} Commitments byUser initialItems")
                assertEquals(share.initialMd, committed.sumMd!!, MD_TOLERANCE, "$sprintId ${share.accountId} initialMd")
                assertEquals(share.finalItems, final.count, "$sprintId ${share.accountId} Commitments byUser finalItems")
                assertEquals(share.finalMd, final.sumMd!!, MD_TOLERANCE, "$sprintId ${share.accountId} finalMd")
            }
            for (share in throughput.byUser) {
                val delivered = client.items("$base&bucket=DELIVERED&segmentAccountId=${share.accountId}").bar
                val viaThroughput = client.deliveredItems("$base&segmentAccountId=${share.accountId}").bar
                assertEquals(share.deliveredItems, delivered.count, "$sprintId ${share.accountId} Throughput byUser deliveredItems")
                assertEquals(share.deliveredMd, delivered.sumMd!!, MD_TOLERANCE, "$sprintId ${share.accountId} deliveredMd")
                assertEquals(delivered, viaThroughput, "$sprintId ${share.accountId} throughput/items sprint mode segment")
            }
        }
        assertTrue(withBoth > 0, "at least one sprint holds work of both picks")
    }

    @Test
    fun `a sliced request lists exactly the live sliced figures, and the throughput sprint mode ignores the slice`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, goldenSprintId())
        val client = seededClient("reports-drill-sprint-sliced")
        val base = "connectionId=$connId&teamId=$floTeamId"
        val sprints = client.consistency("$base&lastSprints=52").sprints
        var sliced = 0

        for (slice in listOf("activityType=Bug", "workCategory=UNCATEGORIZED", "activityType=Bug&workCategory=UNCATEGORIZED")) {
            for (sprint in sprints) {
                val one = "$base&sprintId=${sprint.sprintId}&$slice"
                val report = client.consistency(one).sprints.single()
                for (bucket in buckets) {
                    val drill = client.items("$one&bucket=$bucket")
                    val label = "$slice/sprint ${sprint.sprintId}/$bucket"
                    assertEquals(report.pair(bucket).second, drill.bar.count, "$label count == the sliced figure")
                    assertEquals(report.pair(bucket).first, drill.bar.sumMd!!, MD_TOLERANCE, "$label MD == the sliced figure")
                    assertEquals(drill.bar.count, drill.items.size, label)
                    if (drill.bar.count > 0) sliced++
                }
                // Throughput's sprint view carries no slice: its sprint mode lists the UNSLICED delivered items.
                val plain = client.items("$base&sprintId=${sprint.sprintId}&bucket=DELIVERED")
                val viaThroughput = client.deliveredItems(one)
                assertEquals(plain.bar, viaThroughput.bar, "$slice/sprint ${sprint.sprintId} sprint mode ignores the slice")
                assertEquals(client.throughput(one).bySprint.single().deliveredItems, viaThroughput.bar.count)
            }
        }
        assertTrue(sliced > 0, "the slices are not all empty")
        // The slice partitions the unsliced bar: Σ over the activity types of a bucket's count is the whole bucket's.
        val golden = sprints.first { it.sprintId == goldenSprintId() }
        val types = suspendTransaction(sharedDatabaseForTests()) {
            val t = MetricsTables.FactTaskDelivery
            t.selectAll().where { t.connectionId eq connId }.toList().map { it[t.activityType] }.distinct()
        }
        for (bucket in buckets) {
            val total = types.sumOf { client.items("$base&sprintId=${golden.sprintId}&bucket=$bucket&activityType=$it").bar.count }
            assertEquals(golden.pair(bucket).second, total, "golden/$bucket: Σ activity types == the unsliced bar")
        }
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

    @Test
    fun `hand-built removed and added scope list under their own bucket only, with exact figures and assignees`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-sprint-scope", enabled = false)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("drill-sprint-team"))
        val sprintId = 910_001L
        val closedAt = 1_770_724_800_000L // 2026-02-10T12:00:00Z
        val startAt = closedAt - FORTNIGHT_MS
        val items = listOf(
            scopeItem(1, "acc-1", commitMd = 3.0, closeMd = 3.0, done = true),
            scopeItem(2, "acc-1", commitMd = 2.0, closeMd = 2.0, carried = true),
            // Added mid-sprint at 4 MD, re-estimated to 5 MD by the close, then dropped.
            scopeItem(3, "acc-2", committed = false, addedAtMs = closedAt - 1_000, commitMd = 4.0, closeMd = 5.0, dropped = true),
            // Committed at 1.5 MD (the flag stays true), taken out before the close: the REMOVED bucket only.
            scopeItem(4, "acc-2", inScopeAtClose = false, removedAtMs = closedAt - 500, commitMd = 1.5, closeMd = null),
            // Unestimated and unassigned: an item worth 0 MD.
            scopeItem(5, null, commitMd = null, closeMd = null, carried = true),
        )
        val store = MetricsStore(sharedDatabaseForTests())
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDimSprints(
                connId,
                listOf(DimSprintRow(sprintId, 1L, teamId, "Drill sprint", "closed", startAt, closedAt, closedAt, null, null)),
                configRevision = 1L,
            )
            val fact = FactSprintRow(sprintId, teamId, closedAt, sprintTotals(items), capacityMd = null, load = null)
            store.insertFactSprint(connId, listOf(fact), 1L)
            store.insertFactSprintScope(connId, items.map { FactSprintScopeRow(sprintId, it) }, 1L)
        }
        try {
            val client = seededClient("reports-drill-sprint-hand-built")
            val base = "connectionId=$connId&teamId=$teamId&sprintId=$sprintId"
            fun keys(drill: ReportDrillItems) = drill.items.map { it.issueKey }.toSet()
            val expected = mapOf(
                SprintBucket.COMMITTED to setOf("1", "2", "5"),
                SprintBucket.ADDED to setOf("3"),
                SprintBucket.REMOVED to setOf("4"),
                SprintBucket.FINAL to setOf("1", "2", "3", "5"),
                SprintBucket.DELIVERED to setOf("1"),
                SprintBucket.CARRIED_OVER to setOf("2", "5"),
                SprintBucket.DROPPED to setOf("3"),
            )
            for (bucket in buckets) {
                val drill = client.items("$base&bucket=$bucket")
                assertEquals(expected.getValue(bucket), keys(drill), "$bucket lists these issues (keys fall back to the issue id)")
                assertEquals(expected.getValue(bucket).size, drill.bar.count, bucket.name)
            }
            val removed = client.items("$base&bucket=REMOVED")
            assertEquals(listOf(1.5), removed.items.map { it.figure }, "REMOVED is priced at commitment")
            assertEquals(1.5, removed.bar.sumMd!!, MD_TOLERANCE)
            assertEquals(listOf("acc-2"), removed.items.map { it.accountId })
            assertEquals(closedAt, removed.items.single().anchorAt)
            assertEquals(teamId, removed.items.single().teamId)
            // ADDED is priced at entry (4), DROPPED at close (5): one item, two buckets, two figures.
            assertEquals(listOf(4.0), client.items("$base&bucket=ADDED").items.map { it.figure })
            assertEquals(listOf(5.0), client.items("$base&bucket=DROPPED").items.map { it.figure })
            val committed = client.items("$base&bucket=COMMITTED")
            assertEquals(5.0, committed.bar.sumMd!!, MD_TOLERANCE)
            assertEquals(listOf(3.0, 2.0, null), committed.items.map { it.figure }, "the unestimated item is listed last without a figure")
            assertEquals(listOf("acc-1", "acc-1", null), committed.items.map { it.accountId })
            // Picked accounts narrow by assignee at commitment; an unassigned item is in nobody's share.
            val picks = "$base&accountId=acc-1&accountId=acc-2"
            assertEquals(setOf("1", "2"), keys(client.items("$picks&bucket=COMMITTED")))
            assertEquals(setOf("4"), keys(client.items("$picks&bucket=REMOVED&segmentAccountId=acc-2")))
            assertEquals(emptySet(), keys(client.items("$picks&bucket=REMOVED&segmentAccountId=acc-1")))
            assertEquals(0, client.items("$picks&bucket=REMOVED&segmentAccountId=acc-1").bar.count)
            assertEquals(setOf("3", "4"), keys(client.items("$picks&bucket=ADDED")) + keys(client.items("$picks&bucket=REMOVED")))
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteSprintFacts(connId) }
        }
    }

    @Test
    fun `the drill rejects what the parent report cannot answer with a 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = goldenSprintId()
        val floTeamId = floTeamId(connId, golden)
        val account = storedScope(connId, golden).first { it.assigneeAtCommitment != null }.assigneeAtCommitment!!
        val client = seededClient("reports-drill-sprint-400")
        val sprint = "connectionId=$connId&sprintId=$golden"

        fun path(query: String) = "/api/v1/reports/sprint-consistency/items?$query"
        assertEquals(HttpStatusCode.OK, client.get(path("$sprint&bucket=FINAL")).status)
        client.assertBadRequest(path(sprint), "bucket is required")
        client.assertBadRequest(path("$sprint&bucket=NOPE"), "an unknown bucket")
        client.assertBadRequest(path("$sprint&bucket=final"), "bucket names are exact")
        client.assertBadRequest(path("connectionId=$connId&bucket=FINAL"), "no period")
        client.assertBadRequest(path("connectionId=$connId&lastSprints=3&bucket=FINAL"), "several sprints")
        client.assertBadRequest(path("connectionId=$connId&from=2025-06-01&to=2026-03-05&bucket=FINAL"), "a date range")
        client.assertBadRequest(path("connectionId=$connId&sprintId=999999999&bucket=FINAL"), "unknown sprint")
        client.assertBadRequest(path("$sprint&bucket=FINAL&segmentAccountId=$account"), "UNIT level")
        client.assertBadRequest(path("$sprint&teamId=$floTeamId&bucket=FINAL&segmentAccountId=$account"), "TEAM level")
        client.assertBadRequest(
            path("$sprint&teamId=$floTeamId&accountId=$account&bucket=FINAL&segmentAccountId=somebody-else"), "a stranger to the picks",
        )
        // The parent's own 400s come through unchanged.
        client.assertBadRequest(path("$sprint&accountId=$account&bucket=FINAL"), "accountId needs teamId")
        val nine = (1..9).joinToString("&") { "accountId=acc-$it" }
        client.assertBadRequest(path("$sprint&teamId=$floTeamId&$nine&bucket=FINAL"), "nine accounts")
        // A pick that IS among the picked accounts is accepted.
        assertEquals(
            HttpStatusCode.OK,
            client.get(path("$sprint&teamId=$floTeamId&accountId=$account&bucket=FINAL&segmentAccountId=$account")).status,
        )
        // Throughput's sprint mode: a sprintId period is required without a bucketStart.
        fun tp(query: String) = "/api/v1/reports/throughput/items?$query"
        assertEquals(HttpStatusCode.OK, client.get(tp(sprint)).status)
        client.assertBadRequest(tp("connectionId=$connId&lastSprints=3"), "several sprints, no bucketStart")
        client.assertBadRequest(tp("$sprint&segmentAccountId=$account"), "sprint mode honours the segment rule")
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        const val MD_TOLERANCE = 0.005
        const val FORTNIGHT_MS = 14 * 86_400_000L
    }
}
