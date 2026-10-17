package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ThroughputReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `GET /api/v1/reports/throughput/items` (the drill behind one bar of the Throughput period chart,
 * `.claude/docs/reports.md` "Drill to items"). The reconciliation gate: for EVERY bucket of the derived stub fixture the drill's
 * bar equals the report's bar (count and Σ MD), the listed items add up to it, and every listed task lies in the bucket by an
 * INDEPENDENT bucket rule; with picked accounts the per-person segments equal `byUser` and sum to the union. A failing
 * reconciliation is a real divergence between a bar and its items, never grounds to loosen a test.
 */
class ReportDrillThroughputTest {

    private data class Done(val doneAt: Long, val md: BigDecimal, val team: UInt?, val account: String?)

    private suspend fun zone(): ZoneId = suspendTransaction(sharedDatabaseForTests()) {
        ZoneId.of(MetricsSettingsService.Settings.selectAll().toList().single()[MetricsSettingsService.Settings.timeZone])
    }

    private suspend fun doneTasks(connId: UInt): List<Done> = suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsTables.FactTaskDelivery
        t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) and t.doneAt.isNotNull() }.toList().map {
            Done(it[t.doneAt]!!, it[t.estimateAtDoneMd] ?: BigDecimal.ZERO, it[t.creditTeamId]?.value, it[t.assigneeAccountIdAtDone])
        }
    }

    /** The first day of the bucket holding [atMs] — re-derived here with java.time, never the production `bucketStart`. */
    private fun bucketOf(atMs: Long, zone: ZoneId, bucket: String): LocalDate {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        return if (bucket == "MONTH") day.withDayOfMonth(1) else day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    private suspend fun floTeamId(connId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
    }

    private suspend fun HttpClient.throughput(query: String): ThroughputReport {
        val response = get("/api/v1/reports/throughput?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/throughput/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput/items?$query")
        return response.body()
    }

    /** The golden sprint's team id, read from `sample-data/jira/expected.json` the way `ReportThroughputTest` does. */
    private fun goldenSprintId(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("golden").jsonObject.getValue("sprint").jsonObject
            .getValue("sprintId").jsonPrimitive.content.toLong()
    }

    /**
     * Every shape the drill must honour for ONE bar: the bar equals the report's [expectedItems]/[expectedMd] and the independent
     * re-derivation ([independent], the tasks the oracle puts in this bucket), the list adds up to it, is sorted, lies in the bucket.
     */
    private fun assertBarReconciles(
        label: String,
        drill: ReportDrillItems,
        expectedItems: Int,
        expectedMd: Double,
        independent: List<Done>,
        start: String,
        bucket: String,
        zone: ZoneId,
    ) {
        assertEquals(expectedItems, drill.bar.count, "$label bar.count == the report's deliveredItems")
        assertEquals(expectedMd, drill.bar.sumMd!!, MD_TOLERANCE, "$label bar.sumMd == the report's deliveredMd")
        assertEquals(independent.size, drill.bar.count, "$label bar.count == the independent count")
        assertEquals(independent.fold(BigDecimal.ZERO) { acc, task -> acc + task.md }.toDouble(), drill.bar.sumMd, MD_TOLERANCE, label)
        assertEquals(null, drill.bar.from)
        assertEquals(null, drill.bar.to)
        assertEquals(drill.bar.count, drill.total, "$label total == bar.count")
        assertEquals(drill.total, drill.items.size, "$label every item is listed below the cap")
        assertEquals(false, drill.truncated)
        assertEquals(expectedMd, drill.items.sumOf { it.figure ?: 0.0 }, MD_TOLERANCE, "$label Σ figure == the bar's MD")
        assertEquals(independent.count { it.md.signum() == 0 }, drill.items.count { it.figure == null || it.figure == 0.0 }, label)
        assertEquals(independent.map { it.doneAt }.sorted(), drill.items.map { it.anchorAt!! }.sorted(), "$label done_at multiset")
        assertTrue(drill.items.all { bucketOf(it.anchorAt!!, zone, bucket).toString() == start }, "$label every item lies in $start")
        assertEquals(drill.items.size, drill.items.map { it.issueKey }.distinct().size, "$label distinct issue keys")
        assertTrue(drill.items.all { it.issueKey.isNotBlank() && it.itemKind.name == "TASK" }, label)
        // Largest figure first, nulls last.
        val figures = drill.items.map { it.figure }
        assertEquals(figures.filterNotNull().sortedDescending() + figures.filter { it == null }, figures, "$label figure order")
    }

    @Test
    fun `every bucket of the period chart reconciles with its drill at UNIT and TEAM level, WEEK and MONTH`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = zone()
        val floTeamId = floTeamId(connId, goldenSprintId())
        val all = doneTasks(connId)
        val client = seededClient("reports-drill-throughput-levels")
        val range = "connectionId=$connId&from=2025-06-01&to=2026-03-05"

        for (bucket in listOf("WEEK", "MONTH")) {
            for ((level, scope) in listOf("UNIT" to "", "TEAM" to "&teamId=$floTeamId")) {
                val report = client.throughput("$range&bucket=$bucket$scope")
                assertTrue(report.byBucket.sumOf { it.deliveredItems } > 0, "$level/$bucket must have deliveries")
                val inScope = if (level == "TEAM") all.filter { it.team == floTeamId } else all
                for (row in report.byBucket) {
                    val drill = client.items("$range&bucket=$bucket$scope&bucketStart=${row.bucketStart}")
                    val oracle = inScope.filter { bucketOf(it.doneAt, zone, bucket).toString() == row.bucketStart }
                        .filter { it.doneAt in windowOf(zone, "2025-06-01", "2026-03-05") }
                    val label = "$level/$bucket/${row.bucketStart}"
                    assertBarReconciles(label, drill, row.deliveredItems, row.deliveredMd, oracle, row.bucketStart, bucket, zone)
                    assertEquals(report.meta, drill.meta, "$level/$bucket meta equals the report's")
                    if (level == "TEAM") assertTrue(drill.items.all { it.teamId == floTeamId })
                }
            }
        }
    }

    private fun windowOf(zone: ZoneId, from: String, to: String): LongRange =
        LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli() until
            LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `at USER level with two picks the union and both segments reconcile with the bucket and its byUser split`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = zone()
        val floTeamId = floTeamId(connId, goldenSprintId())
        val teamTasks = doneTasks(connId).filter { it.team == floTeamId }
        val (a, b) = teamTasks.mapNotNull { it.account }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .take(2).map { it.key }
        val client = seededClient("reports-drill-throughput-user")
        val range = "connectionId=$connId&from=2025-06-01&to=2026-03-05&teamId=$floTeamId"
        val picks = "accountId=$a&accountId=$b"

        for (bucket in listOf("WEEK", "MONTH")) {
            val report = client.throughput("$range&$picks&bucket=$bucket")
            assertTrue(report.byBucket.any { row -> row.byUser.all { it.deliveredItems > 0 } }, "both picks deliver in one $bucket")
            for (row in report.byBucket) {
                val base = "$range&$picks&bucket=$bucket&bucketStart=${row.bucketStart}"
                val union = client.items(base)
                val oracle = teamTasks.filter { it.account == a || it.account == b }
                    .filter { it.doneAt in windowOf(zone, "2025-06-01", "2026-03-05") }
                    .filter { bucketOf(it.doneAt, zone, bucket).toString() == row.bucketStart }
                assertBarReconciles(
                    "USER/$bucket/${row.bucketStart}", union, row.deliveredItems, row.deliveredMd, oracle, row.bucketStart, bucket, zone,
                )
                assertTrue(union.items.all { it.accountId == a || it.accountId == b })
                // Each segment is that person's byUser share; the segments partition the union.
                var segmentItems = 0
                var segmentMd = 0.0
                for (share in row.byUser) {
                    val segment = client.items("$base&segmentAccountId=${share.accountId}")
                    assertEquals(share.deliveredItems, segment.bar.count, "${share.accountId} ${row.bucketStart} segment count")
                    assertEquals(share.deliveredMd, segment.bar.sumMd!!, MD_TOLERANCE, "${share.accountId} ${row.bucketStart} segment MD")
                    assertEquals(segment.bar.count, segment.total)
                    assertEquals(segment.total, segment.items.size)
                    assertTrue(segment.items.all { it.accountId == share.accountId }, "a segment lists only its person")
                    segmentItems += segment.bar.count
                    segmentMd += segment.bar.sumMd
                }
                assertEquals(union.bar.count, segmentItems, "${row.bucketStart} Σ segments == union count")
                assertEquals(union.bar.sumMd!!, segmentMd, MD_TOLERANCE, "${row.bucketStart} Σ segments == union MD")
            }
        }
    }

    private fun handBuilt(issueId: Long, doneAtMs: Long, md: Double?) = FactTaskDeliveryRow(
        issueId = issueId, issueKey = "HB-$issueId", createdAt = 0, startedAt = null, doneAt = doneAtMs, reopenCount = 0,
        estimateAtStartMd = null, estimateAtDoneMd = md, estimateCurrentMd = null, estimateSource = "OWN",
        estimateChangesAfterStart = 0, estimatedLate = false, estimatedAfterDone = false, actualMd = 0.0, hasWorklogs = false,
        blockedMs = 0,
        blockedWorkingDays = 0.0, cycleMs = null, cycleWorkingDays = null, leadMs = null, leadWorkingDays = null,
        activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = null, assigneeTeamIdAtDone = null, sprintIdAtDone = null,
        sprintTeamIdAtDone = null, creditTeamId = null, currentTeamId = null, currentAssigneeAccountId = null,
        domainKey = "HB", epicId = null, epicDomainKey = "HB", crossDomain = false,
        activityType = "Story", workCategory = null, isSubtask = false, parentTaskId = null,
        currentStage = "DONE", flags = emptyList(),
    )

    private fun localMs(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a task done Monday 00-30 local belongs to that week even though it is Sunday in UTC, and the 501st item truncates`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "drill-throughput", enabled = false)
            val store = MetricsStore(sharedDatabaseForTests())
            val zone = zone()
            val mondayEarly = handBuilt(1, localMs(zone, 2026, 1, 12, 0, 30), 3.0)
            val sundayLate = handBuilt(2, localMs(zone, 2026, 1, 11, 23, 30), 2.0)
            val sundayEnd = handBuilt(3, localMs(zone, 2026, 1, 18, 23, 59), null)
            val crowdedAt = localMs(zone, 2026, 2, 4, 12, 0)
            val crowded = (0 until CROWDED).map { handBuilt(100L + it, crowdedAt, (it % 7).toDouble().takeIf { md -> md > 0 }) }
            storeRows(store, connId, listOf(mondayEarly, sundayLate, sundayEnd) + crowded)
            try {
                val client = seededClient("reports-drill-throughput-hand-built")
                val range = "connectionId=$connId&from=2026-01-01&to=2026-02-28"

                val week = client.items("$range&bucket=WEEK&bucketStart=2026-01-12")
                assertEquals(setOf("1", "3"), week.items.map { it.issueKey }.toSet(), "keys fall back to the issue id without a work item")
                assertEquals(2, week.bar.count)
                assertEquals(3.0, week.bar.sumMd!!, MD_TOLERANCE)
                assertEquals(listOf(3.0, null), week.items.map { it.figure }, "the unestimated task is listed, last, without a figure")
                val earlier = client.items("$range&bucket=WEEK&bucketStart=2026-01-05")
                assertEquals(listOf("2"), earlier.items.map { it.issueKey })
                val month = client.items("$range&bucket=MONTH&bucketStart=2026-01-01")
                assertEquals(3, month.bar.count)
                assertEquals(5.0, month.bar.sumMd!!, MD_TOLERANCE)
                val empty = client.items("$range&bucket=WEEK&bucketStart=2026-01-19")
                assertEquals(0, empty.bar.count, "an empty bucket answers 200 and nothing")

                val big = client.items("$range&bucket=WEEK&bucketStart=2026-02-02")
                val expectedMd = crowded.sumOf { it.estimateAtDoneMd ?: 0.0 }
                assertEquals(CROWDED, big.bar.count)
                assertEquals(CROWDED, big.total)
                assertEquals(500, big.items.size)
                assertTrue(big.truncated)
                assertEquals(expectedMd, big.bar.sumMd!!, MD_TOLERANCE, "the bar covers every item, not just the listed 500")
                assertEquals(6.0, big.items.first().figure)
                val smallest = big.items.last().figure
                assertTrue(smallest == null || smallest > 0.0, "the largest 500 are listed, unestimated last")
            } finally {
                suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            }
        }

    private suspend fun storeRows(store: MetricsStore, connId: UInt, rows: List<FactTaskDeliveryRow>) =
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }

    @Test
    fun `the drill rejects what the parent report cannot answer with a 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, goldenSprintId())
        val client = seededClient("reports-drill-throughput-400")
        val range = "connectionId=$connId&from=2025-06-01&to=2026-03-05"
        val account = doneTasks(connId).first { it.team == floTeamId && it.account != null }.account
        val start = "bucketStart=2025-06-02"

        fun path(query: String) = "/api/v1/reports/throughput/items?$query"
        assertEquals(HttpStatusCode.BadRequest, client.get(path(range)).status, "bucketStart (or a sprintId period) is required")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("connectionId=$connId&lastSprints=3")).status, "several sprints")
        assertEquals(HttpStatusCode.OK, client.get(path("connectionId=$connId&sprintId=${goldenSprintId()}")).status, "the sprint mode")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&bucketStart=yesterday")).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&$start&bucket=DAY")).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&bucketStart=2025-06-03")).status, "a Tuesday is no week start")
        val notAMonth = client.get(path("$range&bucketStart=2025-06-02&bucket=MONTH"))
        assertEquals(HttpStatusCode.BadRequest, notAMonth.status, "no month starts there")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&bucketStart=2024-01-01")).status, "a Monday before the window")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&bucketStart=2030-01-07")).status, "a Monday after the window")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&$start&segmentAccountId=$account")).status, "UNIT level")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&$start&teamId=$floTeamId&segmentAccountId=$account")).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get(path("$range&$start&teamId=$floTeamId&accountId=$account&segmentAccountId=somebody-else")).status,
            "a stranger to the picks",
        )
        // The parent's own 400s come through unchanged.
        assertEquals(HttpStatusCode.BadRequest, client.get(path("$range&$start&accountId=$account")).status, "accountId needs teamId")
        assertEquals(HttpStatusCode.BadRequest, client.get(path("connectionId=$connId&from=2026-01-01&to=2025-01-01&$start")).status)
        // A pick that IS among the picked accounts is accepted.
        val ok = client.get(path("$range&$start&teamId=$floTeamId&accountId=$account&segmentAccountId=$account"))
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    private companion object {
        const val MD_TOLERANCE = 0.005
        const val CROWDED = 501
    }
}
