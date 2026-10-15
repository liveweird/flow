package ch.nokillswit

import ch.nokillswit.FrozenScopeFixtures.scopeItem
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.ThroughputReport
import ch.nokillswit.reports.ThroughputSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A minimal local slice of `sample-data/jira/expected.json`'s `golden.sprint` — just what this test needs. */
@Serializable
private data class ThroughputGoldenSprint(val sprintId: Long, val deliveredMd: Double, val deliveredItems: Int)

@Serializable
private data class ThroughputGoldenFixture(val sprint: ThroughputGoldenSprint)

@Serializable
private data class ThroughputExpectedFixture(val golden: ThroughputGoldenFixture)

private val REPORT_THROUGHPUT_JSON = Json { ignoreUnknownKeys = true }

private val reportThroughputGolden: ThroughputGoldenSprint by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    REPORT_THROUGHPUT_JSON.decodeFromString<ThroughputExpectedFixture>(file.readText()).golden.sprint
}

/** One level-0 DONE task as `fact_task_delivery` stores it — the independent re-derivation the report is graded against. */
private data class DoneTask(
    val doneAt: Long,
    val md: BigDecimal,
    val team: UInt?,
    val account: String?,
    val activityType: String,
    val domainKey: String?,
    val epicDomainKey: String?,
    val workCategory: String?,
)

private fun List<DoneTask>.mdSum(): Double = fold(BigDecimal.ZERO) { acc, task -> acc + task.md }.toDouble()

/**
 * `GET /api/v1/reports/throughput` (v0.3.0 M4 commit 10c, Report 2, `.claude/docs/measures.md`
 * "Report 2 — Throughput"). Reads [DerivedStubFixture]'s connection; every request narrows via
 * `connectionId` so other tests' connections never leak in. The period view is graded against an
 * INDEPENDENT read of `fact_task_delivery` (never the report's own query path).
 */
class ReportThroughputTest {

    private suspend fun zone(): ZoneId = suspendTransaction(sharedDatabaseForTests()) {
        ZoneId.of(MetricsSettingsService.Settings.selectAll().toList().single()[MetricsSettingsService.Settings.timeZone])
    }

    private suspend fun doneTasks(connId: UInt): List<DoneTask> = suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsTables.FactTaskDelivery
        t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) and t.doneAt.isNotNull() }.toList().map {
            DoneTask(
                doneAt = it[t.doneAt]!!,
                md = it[t.estimateAtDoneMd] ?: BigDecimal.ZERO,
                team = it[t.creditTeamId]?.value,
                account = it[t.assigneeAccountIdAtDone],
                activityType = it[t.activityType],
                domainKey = it[t.domainKey],
                epicDomainKey = it[t.epicDomainKey],
                workCategory = it[t.workCategory],
            )
        }
    }

    /** `[fromMs, toMs)` for inclusive ISO dates in [zone] — the report's own exclusive-`to` convention, re-derived. */
    private fun bounds(zone: ZoneId, from: String, to: String): Pair<Long, Long> =
        LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli() to
            LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun List<DoneTask>.inWindow(bounds: Pair<Long, Long>) = filter { it.doneAt >= bounds.first && it.doneAt < bounds.second }

    private suspend fun HttpClient.throughput(query: String): ThroughputReport {
        val response = get("/api/v1/reports/throughput?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput?$query")
        return response.body()
    }

    /** A `fact_sprint` row of one team joined with its `dim_sprint` start, read independently of the report. */
    private data class SprintFacts(
        val sprintId: Long,
        val startAt: Long?,
        val completeAt: Long?,
        val deliveredMd: Double,
        val deliveredItems: Int,
    )

    private suspend fun flowSprints(connId: UInt, teamId: UInt): List<SprintFacts> = suspendTransaction(sharedDatabaseForTests()) {
        val starts = MetricsTables.DimSprint.selectAll().where { MetricsTables.DimSprint.connectionId eq connId }.toList()
            .associate { it[MetricsTables.DimSprint.sprintId] to it[MetricsTables.DimSprint.startAt] }
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.teamId eq teamId) }
            .toList().map {
                SprintFacts(
                    sprintId = it[MetricsTables.FactSprint.sprintId],
                    startAt = starts[it[MetricsTables.FactSprint.sprintId]],
                    completeAt = it[MetricsTables.FactSprint.completeAt],
                    deliveredMd = it[MetricsTables.FactSprint.deliveredMd].toDouble(),
                    deliveredItems = it[MetricsTables.FactSprint.deliveredItems],
                )
            }
    }

    private fun handBuiltTask(
        issueId: Long,
        doneAt: Long?,
        estimateAtDoneMd: Double?,
        domain: String?,
        epicDomain: String?,
        category: String?,
        epicId: Long? = null,
        subtask: Boolean = false,
    ) = FactTaskDeliveryRow(
        issueId = issueId, issueKey = "HB-$issueId", createdAt = 0, startedAt = null, doneAt = doneAt, reopenCount = 0,
        estimateAtStartMd = null, estimateAtDoneMd = estimateAtDoneMd, estimateCurrentMd = null, estimateSource = "OWN",
        estimateChangesAfterStart = 0, estimatedLate = false, actualMd = 0.0, hasWorklogs = false, blockedMs = 0,
        blockedWorkingDays = 0.0, cycleMs = null, cycleWorkingDays = null, leadMs = null, leadWorkingDays = null,
        activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = null, assigneeTeamIdAtDone = null, sprintIdAtDone = null,
        sprintTeamIdAtDone = null, creditTeamId = null, currentTeamId = null, currentAssigneeAccountId = null,
        domainKey = domain, epicId = epicId, epicDomainKey = epicDomain, crossDomain = epicDomain != null && epicDomain != domain,
        activityType = "Story", workCategory = category, isSubtask = subtask, parentTaskId = null,
        currentStage = if (doneAt == null) "IN_PROGRESS" else "DONE", flags = emptyList(),
    )

    private suspend fun floTeamId(connId: UInt, sprintId: Long): UInt = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprint.selectAll()
            .where { (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsTables.FactSprint.teamId]!!.value
    }

    @Test
    fun `bySprint delivered equals fact_sprint and the golden expected json`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val client = seededClient("reports-throughput-sprint")

        val body = client.throughput("connectionId=$connId&sprintId=${golden.sprintId}")
        val sprint = body.bySprint.single()
        assertEquals(golden.sprintId, sprint.sprintId)
        assertEquals(golden.deliveredMd, sprint.deliveredMd, "deliveredMd must match expected.json golden.sprint.deliveredMd")
        assertEquals(golden.deliveredItems, sprint.deliveredItems)
        assertTrue(!sprint.drift, "the fixture's snapshot is reconstructed from the same data — drift must be false")
        assertEquals(golden.deliveredMd, sprint.snapshot!!.deliveredMd)

        val fromFact = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprint.selectAll()
                .where { (MetricsTables.FactSprint.connectionId eq connId) and (MetricsTables.FactSprint.sprintId eq golden.sprintId) }
                .toList().single()
        }
        assertEquals(fromFact[MetricsTables.FactSprint.deliveredMd].toDouble(), sprint.deliveredMd)
        assertEquals(fromFact[MetricsTables.FactSprint.deliveredItems], sprint.deliveredItems)

        // A from/to period lists the same sprint, the whole sprint or nothing (anchor: complete_at).
        val ranged = client.throughput("connectionId=$connId&from=2025-09-01&to=2026-03-06")
        val same = ranged.bySprint.single { it.sprintId == golden.sprintId }
        assertEquals(golden.deliveredMd, same.deliveredMd)
        assertTrue(ranged.bySprint.zipWithNext().all { (a, b) -> a.completedAt!! <= b.completedAt!! }, "bySprint is ordered by completion")
    }

    @Test
    fun `byBucket sums to an independent count and sum straight from fact_task_delivery`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = zone()
        val all = doneTasks(connId)
        assertTrue(all.isNotEmpty(), "the fixture must carry DONE level-0 tasks")
        val client = seededClient("reports-throughput-bucket")

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2025-12-31")) {
            val expected = all.inWindow(bounds(zone, from, to))
            assertTrue(expected.isNotEmpty(), "window $from..$to must contain tasks")
            val body = client.throughput("connectionId=$connId&from=$from&to=$to")
            assertEquals(expected.mdSum(), body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE, "MD over $from..$to")
            assertEquals(expected.size, body.byBucket.sumOf { it.deliveredItems }, "items over $from..$to")
            assertEquals(from, body.meta.from)
        }
    }

    @Test
    fun `sprint-relative period reads the envelope of the resolved sprints`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val all = doneTasks(connId)
        val client = seededClient("reports-throughput-window")

        val (start, complete) = suspendTransaction(sharedDatabaseForTests()) {
            val row = MetricsTables.DimSprint.selectAll()
                .where { (MetricsTables.DimSprint.connectionId eq connId) and (MetricsTables.DimSprint.sprintId eq golden.sprintId) }
                .toList().single()
            row[MetricsTables.DimSprint.startAt]!! to row[MetricsTables.DimSprint.completeAt]!!
        }
        val expected = all.filter { it.doneAt >= start && it.doneAt <= complete }
        val body = client.throughput("connectionId=$connId&sprintId=${golden.sprintId}")
        assertEquals(expected.mdSum(), body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        assertEquals(expected.size, body.byBucket.sumOf { it.deliveredItems })
        assertEquals(listOf(golden.sprintId), body.meta.resolvedSprints.single().sprintIds)
        assertEquals(null, body.meta.from)
    }

    @Test
    fun `groups sum to the byBucket total at UNIT and TEAM level`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-throughput-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"

        val unit = client.throughput(range)
        assertEquals(unit.byBucket.sumOf { it.deliveredMd }, unit.groups.sumOf { it.deliveredMd }, ABS_TOLERANCE, "UNIT Σ groups MD")
        assertEquals(unit.byBucket.sumOf { it.deliveredItems }, unit.groups.sumOf { it.deliveredItems }, "UNIT Σ groups items")
        val floGroup = unit.groups.single { it.teamId == floTeamId }
        assertTrue(floGroup.deliveredItems > 0)
        assertTrue(unit.groups.all { it.accountId == null }, "UNIT groups are teams")
        assertLabelThenIdOrder("UNIT", unit.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })

        val team = client.throughput("$range&teamId=$floTeamId")
        assertEquals(floGroup.deliveredItems, team.byBucket.sumOf { it.deliveredItems }, "TEAM narrows to the credit team")
        assertEquals(team.byBucket.sumOf { it.deliveredMd }, team.groups.sumOf { it.deliveredMd }, ABS_TOLERANCE, "TEAM Σ groups MD")
        assertEquals(team.byBucket.sumOf { it.deliveredItems }, team.groups.sumOf { it.deliveredItems }, "TEAM Σ groups items")
        assertTrue(team.bySprint.all { it.teamId == floTeamId })
        assertLabelThenIdOrder("TEAM", team.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })

        // USER level: the one account's own period view; its single group is its period total.
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.throughput("$range&teamId=$floTeamId&accountId=$account")
        assertEquals(listOf(account), user.groups.map { it.accountId })
        assertEquals(user.byBucket.sumOf { it.deliveredItems }, user.groups.single().deliveredItems)
        assertEquals(team.groups.single { it.accountId == account }.deliveredItems, user.byBucket.sumOf { it.deliveredItems })
    }

    @Test
    fun `teamId 0 selects UNASSIGNED credit for the period view and no sprints`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = zone()
        val all = doneTasks(connId)
        val client = seededClient("reports-throughput-unassigned")

        val expected = all.inWindow(bounds(zone, "2024-01-01", "2026-12-31")).filter { it.team == null }
        assertTrue(expected.isNotEmpty(), "the fixture must carry DONE tasks with no credit team")
        val body = client.throughput("connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=0")
        assertTrue(body.bySprint.isEmpty())
        assertEquals(expected.mdSum(), body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        assertEquals(expected.size, body.byBucket.sumOf { it.deliveredItems })
        assertEquals(body.byBucket.sumOf { it.deliveredItems }, body.groups.sumOf { it.deliveredItems })
    }

    @Test
    fun `activityType and TASK-domain filters slice the period view`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = zone()
        val all = doneTasks(connId).inWindow(bounds(zone, "2024-01-01", "2026-12-31"))
        val client = seededClient("reports-throughput-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"

        fun <T> List<DoneTask>.mostFrequent(key: (DoneTask) -> T?): T = mapNotNull(key).groupingBy { it }.eachCount().maxBy { it.value }.key

        val activity = all.mostFrequent { it.activityType }
        val byActivity = client.throughput("$range&activityType=$activity")
        assertEquals(all.count { it.activityType == activity }, byActivity.byBucket.sumOf { it.deliveredItems })

        val domain = all.mostFrequent { it.domainKey }
        val taskView = client.throughput("$range&domain=$domain")
        assertEquals(all.count { it.domainKey == domain }, taskView.byBucket.sumOf { it.deliveredItems }, "TASK view (the default)")
        assertEquals("TASK", taskView.meta.domainView.name)
    }

    /**
     * The stub fixture never has a work category or an `epic_domain_key` different from `domain_key`, so
     * the EPIC domain view and the category filters are graded on hand-built `fact_task_delivery` rows
     * (a fresh DISABLED connection, cleaned up afterwards) with exactly hand-computed answers.
     */
    @Test
    fun `EPIC domain view and workCategory filters follow hand-built cross-domain and categorized rows`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "throughput-slices", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val inWindow = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneId.of("UTC")).plusHours(12).toInstant().toEpochMilli()
        val outsideWindow = LocalDate.of(2025, 6, 15).atStartOfDay(ZoneId.of("UTC")).plusHours(12).toInstant().toEpochMilli()
        val rows = listOf(
            // A: cross-domain (own AAA, epic BBB), category Product, 3 MD
            handBuiltTask(1, inWindow, 3.0, domain = "AAA", epicDomain = "BBB", category = "Product"),
            // B: own = epic domain AAA, uncategorized, 5 MD
            handBuiltTask(2, inWindow, 5.0, domain = "AAA", epicDomain = "AAA", category = null),
            // C: has an epic but epic_domain_key is null (epic outside the ingested scope), unestimated
            handBuiltTask(3, inWindow, null, domain = "AAA", epicDomain = null, category = null, epicId = 900L),
            // D: BBB/BBB, Product, 2 MD
            handBuiltTask(4, inWindow, 2.0, domain = "BBB", epicDomain = "BBB", category = "Product"),
            // E: own BBB, epic AAA, Maintenance, 1 MD
            handBuiltTask(5, inWindow, 1.0, domain = "BBB", epicDomain = "AAA", category = "Maintenance"),
            // F: own AAA, epic CCC, uncategorized, 4 MD
            handBuiltTask(6, inWindow, 4.0, domain = "AAA", epicDomain = "CCC", category = null),
            // never counted: a sub-task, an open task, a task done outside the window
            handBuiltTask(7, inWindow, 100.0, domain = "AAA", epicDomain = "AAA", category = "Product", subtask = true),
            handBuiltTask(8, null, 100.0, domain = "AAA", epicDomain = "AAA", category = "Product"),
            handBuiltTask(9, outsideWindow, 100.0, domain = "AAA", epicDomain = "AAA", category = "Product"),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-throughput-hand-built")
            val range = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

            suspend fun check(query: String, items: Int, md: Double) {
                val body = client.throughput("$range$query")
                assertEquals(items, body.byBucket.sumOf { it.deliveredItems }, "items for '$query'")
                assertEquals(md, body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE, "MD for '$query'")
            }
            check("", 6, 15.0)
            // TASK view (default): A, B, C, F (own domain AAA).
            check("&domain=AAA", 4, 12.0)
            check("&domain=AAA&domainView=TASK", 4, 12.0)
            // EPIC view: epic domain AAA (B, E) plus the epic-less/null-epic-domain task falling back to its own AAA (C).
            check("&domain=AAA&domainView=EPIC", 3, 6.0)
            check("&domain=BBB", 2, 3.0)
            check("&domain=BBB&domainView=EPIC", 2, 5.0) // A (epic BBB) + D
            // Categories: UNCATEGORIZED = B, C, F; Product = A, D; Maintenance = E; unknown = nothing.
            check("&workCategory=UNCATEGORIZED", 3, 9.0)
            check("&workCategory=Product", 2, 5.0)
            check("&workCategory=Maintenance", 1, 1.0)
            check("&workCategory=Nothing", 0, 0.0)
            check("&domain=AAA&domainView=EPIC&workCategory=UNCATEGORIZED", 2, 5.0) // B, C
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
        }
    }

    @Test
    fun `an open or future sprint is reachable by sprintId with live figures and a now-bounded window`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportThroughputGolden.sprintId)
        val all = doneTasks(connId)
        val client = seededClient("reports-throughput-open-sprint")

        val openSprints = flowSprints(connId, floTeamId).filter { it.completeAt == null }
        assertTrue(openSprints.isNotEmpty(), "the stub fixture must carry an active/future FLO sprint")
        for (open in openSprints) {
            val body = client.throughput("connectionId=$connId&sprintId=${open.sprintId}")
            val sprint = body.bySprint.single()
            assertEquals(null, sprint.completedAt)
            assertEquals(null, sprint.snapshot)
            assertTrue(!sprint.drift)
            assertEquals(open.deliveredMd, sprint.deliveredMd, ABS_TOLERANCE)
            assertEquals(open.deliveredItems, sprint.deliveredItems)
            // USER level: a sprint with no snapshot (D13) has a null per-user snapshot and no drift, whoever the account.
            val userQuery = "connectionId=$connId&teamId=$floTeamId&accountId=any-account&sprintId=${open.sprintId}"
            val user = client.throughput(userQuery).bySprint.single()
            assertEquals(null, user.snapshot)
            assertTrue(!user.drift)
            // Window [start_at, now]: done tasks all predate now, so every one from start_at on counts; no start_at -> empty.
            val expected = open.startAt?.let { start -> all.filter { it.doneAt >= start } }.orEmpty()
            assertEquals(expected.size, body.byBucket.sumOf { it.deliveredItems }, "sprint ${open.sprintId} period view")
            assertEquals(expected.mdSum(), body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        }
    }

    @Test
    fun `lastSprints resolves each team's last closed sprints and reads their envelope`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportThroughputGolden.sprintId)
        val all = doneTasks(connId)
        val client = seededClient("reports-throughput-last-sprints")

        val lastTwo = flowSprints(connId, floTeamId).filter { it.completeAt != null }.sortedByDescending { it.completeAt }.take(2)
        assertEquals(2, lastTwo.size)
        val body = client.throughput("connectionId=$connId&lastSprints=2")

        assertEquals(lastTwo.map { it.sprintId }.toSet(), body.bySprint.map { it.sprintId }.toSet())
        assertEquals(lastTwo.map { it.sprintId }.toSet(), body.meta.resolvedSprints.single { it.teamId == floTeamId }.sprintIds.toSet())
        assertEquals(null, body.meta.from)
        val start = lastTwo.minOf { it.startAt ?: it.completeAt!! }
        val end = lastTwo.maxOf { it.completeAt!! }
        val expected = all.filter { it.doneAt >= start && it.doneAt <= end }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected.size, body.byBucket.sumOf { it.deliveredItems })
        assertEquals(expected.mdSum(), body.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
    }

    @Test
    fun `USER-level bySprint delivered sums over assignees to the TEAM figure`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-throughput-user-sprint")
        val sprintQuery = "connectionId=$connId&sprintId=${golden.sprintId}"

        val team = client.throughput("$sprintQuery&teamId=$floTeamId").bySprint.single()
        val scope = suspendTransaction(sharedDatabaseForTests()) {
            val s = MetricsTables.FactSprintScope
            s.selectAll().where { (s.connectionId eq connId) and (s.sprintId eq golden.sprintId) and (s.doneInSprint eq true) }.toList()
                .map { it[s.assigneeAtCommitment] to (it[s.estimateAtDoneMd] ?: BigDecimal.ZERO) }
        }
        val accounts = scope.mapNotNull { it.first }.distinct()
        assertTrue(accounts.isNotEmpty(), "the golden sprint's deliveries must have assignees at commitment")

        var mdSum = scope.filter { it.first == null }.fold(BigDecimal.ZERO) { acc, row -> acc + row.second }.toDouble()
        var itemSum = scope.count { it.first == null } // the unassigned-at-commitment remainder has no USER level
        for (account in accounts) {
            val user = client.throughput("$sprintQuery&teamId=$floTeamId&accountId=$account").bySprint.single()
            assertEquals(scope.count { it.first == account }, user.deliveredItems, "items for $account")
            mdSum += user.deliveredMd
            itemSum += user.deliveredItems
        }
        assertEquals(team.deliveredMd, mdSum, ABS_TOLERANCE)
        assertEquals(team.deliveredItems, itemSum)
        assertEquals(golden.deliveredItems, itemSum)
    }

    /** One assignee bucket's `done_in_sprint` items summed off the stored JSON (per-item round2), never the production reader. */
    private fun frozenDelivered(stored: List<JsonObject>, account: String?): Pair<BigDecimal, Int> {
        val rows = stored.filter {
            it.getValue("assigneeAtCommitment").jsonPrimitive.contentOrNull == account && it.getValue("doneInSprint").jsonPrimitive.boolean
        }
        val md = rows.fold(BigDecimal.ZERO) { acc, item ->
            val value = item.getValue("estimateAtDoneMd")
            acc + if (value is JsonNull) BigDecimal.ZERO else BigDecimal((value as JsonPrimitive).content).setScale(2, RoundingMode.HALF_UP)
        }
        return md to rows.size
    }

    @Test
    fun `USER-level bySprint snapshot equals the stored scope and sums to the team snapshot`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val golden = reportThroughputGolden
            val floTeamId = floTeamId(connId, golden.sprintId)
            val client = seededClient("reports-throughput-user-frozen")
            val sprintQuery = "connectionId=$connId&sprintId=${golden.sprintId}"

            val team = client.throughput("$sprintQuery&teamId=$floTeamId").bySprint.single()
            val teamSnapshot = assertNotNull(team.snapshot, "the golden sprint is closed and team-mapped, so it is frozen")
            val stored = FrozenScopeFixtures.storedScope(connId, golden.sprintId).map { it.jsonObject }
            val accounts = stored.mapNotNull { it.getValue("assigneeAtCommitment").jsonPrimitive.contentOrNull }.distinct()
            assertTrue(accounts.isNotEmpty(), "the golden sprint has assigned scope")

            val (unassignedMd, unassignedItems) = frozenDelivered(stored, null)
            var mdSum = unassignedMd
            var itemSum = unassignedItems
            for (account in accounts) {
                val sprint = client.throughput("$sprintQuery&teamId=$floTeamId&accountId=$account").bySprint.single()
                val snapshot = assertNotNull(sprint.snapshot, "$account: a snapshotted sprint has per-user frozen figures")
                val (expectedMd, expectedItems) = frozenDelivered(stored, account)
                assertEquals(0, expectedMd.compareTo(BigDecimal.valueOf(snapshot.deliveredMd)), "$account deliveredMd")
                assertEquals(expectedItems, snapshot.deliveredItems, "$account deliveredItems")
                // The fixture's snapshot is frozen from the very rows the live figures read: no drift.
                assertEquals(sprint.deliveredMd, snapshot.deliveredMd, "$account live == frozen")
                assertEquals(sprint.deliveredItems, snapshot.deliveredItems, "$account live == frozen items")
                assertTrue(!sprint.drift, "$account: no drift")
                mdSum += BigDecimal.valueOf(snapshot.deliveredMd)
                itemSum += snapshot.deliveredItems
            }
            // Σ named users + the unassigned remainder (no USER-level query) == the team snapshot, to the cent.
            assertEquals(0, BigDecimal.valueOf(teamSnapshot.deliveredMd).compareTo(mdSum), "Sigma users + unassigned deliveredMd")
            assertEquals(teamSnapshot.deliveredItems, itemSum)
        }

    @Test
    fun `USER-level bySprint snapshot reads the stored scope per connection, with drift`() =
        testApplication {
            usePostgresTestcontainer()
            val sprintId = reportThroughputGolden.sprintId
            val (connA, teamA) = FrozenScopeFixtures.derivedDisabledClone("jira-throughput-frozen", "throughput-frozen-team-a")
            val (connB, teamB) = FrozenScopeFixtures.derivedDisabledClone("jira-throughput-frozen", "throughput-frozen-team-b")
            val client = seededClient("reports-throughput-user-frozen-synthetic")
            fun userUrl(connId: UInt, teamId: UInt, accountId: String) =
                "connectionId=$connId&teamId=$teamId&accountId=$accountId&sprintId=$sprintId"

            // Synthetic accounts own NO live scope row, so their live figures are zero and every frozen one is the JSON's.
            val ann = "synthetic-ann"
            val other = "synthetic-other"
            // A: [ann] two deliveries priced 1000.004 -> 1000.00 and 2.506 -> 2.51, one delivery with NO done estimate (an item
            // worth 0 MD), one item not delivered (ignored), plus a delivery of `other` and of the unassigned bucket.
            FrozenScopeFixtures.overwriteSnapshotScope(
                connA, sprintId,
                buildJsonArray {
                    add(scopeItem(1, ann, doneMd = 1000.004, done = true))
                    add(scopeItem(2, ann, doneMd = 2.506, done = true))
                    add(scopeItem(3, ann, doneMd = null, done = true))
                    add(scopeItem(4, ann, commitMd = 9.0, closeMd = 9.0, carried = true))
                    add(scopeItem(5, other, doneMd = 50.0, done = true))
                    add(scopeItem(6, null, doneMd = 100.0, done = true))
                },
            )
            // B shares the SAME sprint id (one Jira site, two connections) but a different snapshot.
            FrozenScopeFixtures.overwriteSnapshotScope(
                connB, sprintId, buildJsonArray { add(scopeItem(1, ann, doneMd = 1.0, done = true)) },
            )

            val a = client.throughput(userUrl(connA, teamA, ann)).bySprint.single()
            val snapshotA = assertNotNull(a.snapshot)
            assertEquals(1002.51, snapshotA.deliveredMd, ABS_TOLERANCE)
            assertEquals(3, snapshotA.deliveredItems)
            assertEquals(0.0, a.deliveredMd, "the live figures still come from fact_sprint_scope, not the snapshot")
            assertEquals(0, a.deliveredItems)
            assertTrue(a.drift, "the frozen figures differ from the live ones")

            val b = client.throughput(userUrl(connB, teamB, ann)).bySprint.single()
            val snapshotB = assertNotNull(b.snapshot)
            assertEquals(1.0, snapshotB.deliveredMd, ABS_TOLERANCE)
            assertEquals(1, snapshotB.deliveredItems)
            assertTrue(b.drift)

            // An account with no delivery in the stored scope has zero frozen figures (not null: the sprint IS frozen) and no drift.
            val nobody = client.throughput(userUrl(connB, teamB, other)).bySprint.single()
            assertEquals(ThroughputSnapshot(0.0, 0), nobody.snapshot)
            assertTrue(!nobody.drift)
        }

    @Test
    fun `MONTH and WEEK buckets sum to the same totals and start on Mondays and the first`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-throughput-week-month")
        val range = "connectionId=$connId&from=2025-06-01&to=2026-03-05"

        val weekly = client.throughput(range) // WEEK is the default
        val explicitWeekly = client.throughput("$range&bucket=WEEK")
        val monthly = client.throughput("$range&bucket=MONTH")

        assertEquals(weekly.byBucket, explicitWeekly.byBucket)
        assertEquals(weekly.byBucket.sumOf { it.deliveredMd }, monthly.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        assertEquals(weekly.byBucket.sumOf { it.deliveredItems }, monthly.byBucket.sumOf { it.deliveredItems })
        assertTrue(weekly.byBucket.sumOf { it.deliveredItems } > 0)
        assertTrue(weekly.byBucket.all { LocalDate.parse(it.bucketStart).dayOfWeek == DayOfWeek.MONDAY })
        assertTrue(monthly.byBucket.all { LocalDate.parse(it.bucketStart).dayOfMonth == 1 })
        // Zero-filled: consecutive buckets, no gaps.
        assertTrue(
            weekly.byBucket.zipWithNext().all { (a, b) -> LocalDate.parse(a.bucketStart).plusWeeks(1) == LocalDate.parse(b.bucketStart) },
        )
    }

    /** The accounts that delivered inside the golden sprint (`done_in_sprint` scope rows), with each one's MD/items — read via SQL. */
    private suspend fun goldenDeliveries(connId: UInt): Map<String, Pair<BigDecimal, Int>> = suspendTransaction(sharedDatabaseForTests()) {
        val s = MetricsTables.FactSprintScope
        s.selectAll().where { (s.connectionId eq connId) and (s.sprintId eq reportThroughputGolden.sprintId) and (s.doneInSprint eq true) }
            .toList().mapNotNull { row -> row[s.assigneeAtCommitment]?.let { it to (row[s.estimateAtDoneMd] ?: BigDecimal.ZERO) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, md) -> md.fold(BigDecimal.ZERO, BigDecimal::add) to md.size }
    }

    private suspend fun displayNames(accountIds: List<String>): Map<String, String> = suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.People.selectAll().where { WorkItemStore.People.accountId inList accountIds }.toList()
            .associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
    }

    @Test
    fun `several accountIds sum bySprint and its frozen share and split it per user in request order`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-throughput-multi-sprint")
        val deliveries = goldenDeliveries(connId)
        assertTrue(deliveries.size >= 2, "the golden sprint must have at least two named assignees-at-commitment, got ${deliveries.keys}")
        val (a, b) = deliveries.keys.toList()
        val query = "connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}"
        val nobody = "throughput-nobody-account"

        val sprint = client.throughput("$query&accountId=$b&accountId=$a&accountId=$nobody").bySprint.single()
        val sprintA = client.throughput("$query&accountId=$a").bySprint.single()
        val sprintB = client.throughput("$query&accountId=$b").bySprint.single()

        // Live: the sum of the independently read per-account scope rows == the sum of the two single-account runs.
        val (mdA, itemsA) = deliveries.getValue(a)
        val (mdB, itemsB) = deliveries.getValue(b)
        assertEquals(mdA.add(mdB).toDouble(), sprint.deliveredMd, ABS_TOLERANCE)
        assertEquals(itemsA + itemsB, sprint.deliveredItems)
        assertEquals(sprintA.deliveredMd + sprintB.deliveredMd, sprint.deliveredMd, ABS_TOLERANCE)
        assertEquals(sprintA.deliveredItems + sprintB.deliveredItems, sprint.deliveredItems)

        // Frozen: the stored JSON's per-account sums (per-item round2), summed, == the report's snapshot == the single snapshots' sum.
        val stored = FrozenScopeFixtures.storedScope(connId, golden.sprintId).map { it.jsonObject }
        val snapshot = assertNotNull(sprint.snapshot, "the golden sprint is frozen")
        val expectedFrozenMd = frozenDelivered(stored, a).first.add(frozenDelivered(stored, b).first)
        assertEquals(0, expectedFrozenMd.compareTo(BigDecimal.valueOf(snapshot.deliveredMd)), "frozen deliveredMd")
        assertEquals(frozenDelivered(stored, a).second + frozenDelivered(stored, b).second, snapshot.deliveredItems)
        val snapshotA = assertNotNull(sprintA.snapshot)
        val snapshotB = assertNotNull(sprintB.snapshot)
        assertEquals(snapshotA.deliveredMd + snapshotB.deliveredMd, snapshot.deliveredMd, ABS_TOLERANCE)
        assertEquals(snapshotA.deliveredItems + snapshotB.deliveredItems, snapshot.deliveredItems)
        assertTrue(!sprint.drift, "the fixture's snapshot is frozen from the same rows")

        // byUser: request order (b, a, nobody), the independent per-account figures, zeros included, labelled by display name.
        assertEquals(listOf(b, a, nobody), sprint.byUser.map { it.accountId })
        val names = displayNames(listOf(a, b))
        for ((share, expected) in sprint.byUser.zip(listOf(b to (mdB to itemsB), a to (mdA to itemsA)))) {
            assertEquals(expected.second.first.toDouble(), share.deliveredMd, ABS_TOLERANCE)
            assertEquals(expected.second.second, share.deliveredItems)
            assertEquals(names[expected.first] ?: expected.first, share.label)
        }
        val zero = sprint.byUser.last()
        assertEquals(0.0, zero.deliveredMd)
        assertEquals(0, zero.deliveredItems)
        assertEquals(nobody, zero.label, "an account with no known display name is labelled by its id")
        // The team level carries no per-user split.
        val teamLevel = client.throughput("connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}")
        assertTrue(teamLevel.bySprint.single().byUser.isEmpty())
        assertTrue(client.throughput("connectionId=$connId&sprintId=${golden.sprintId}").bySprint.single().byUser.isEmpty())
    }

    private fun bucketKey(zone: ZoneId, doneAt: Long): String =
        Instant.ofEpochMilli(doneAt).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    @Test
    fun `several accountIds sum byBucket, split every bucket per user and list each pick in groups`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportThroughputGolden.sprintId)
        val zone = zone()
        val window = bounds(zone, "2024-01-01", "2026-12-31")
        val teamTasks = doneTasks(connId).inWindow(window).filter { it.team == floTeamId }
        val busiest = teamTasks.mapNotNull { it.account }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val (a, b) = busiest.take(2).map { it.key }
        val nobody = "throughput-nobody-period"
        val client = seededClient("reports-throughput-multi-bucket")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$floTeamId"

        val both = client.throughput("$query&accountId=$b&accountId=$a&accountId=$nobody")
        val oneA = client.throughput("$query&accountId=$a")
        val oneB = client.throughput("$query&accountId=$b")

        // Independent expectation straight off fact_task_delivery: tasks credited to the team, assignee at done IN the picks.
        val picked = teamTasks.filter { it.account == a || it.account == b }
        assertTrue(picked.isNotEmpty())
        assertEquals(picked.mdSum(), both.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        assertEquals(picked.size, both.byBucket.sumOf { it.deliveredItems })
        val singlesMd = oneA.byBucket.sumOf { it.deliveredMd } + oneB.byBucket.sumOf { it.deliveredMd }
        assertEquals(singlesMd, both.byBucket.sumOf { it.deliveredMd }, ABS_TOLERANCE)
        val singlesItems = oneA.byBucket.zip(oneB.byBucket).map { (x, y) -> x.deliveredItems + y.deliveredItems }
        assertEquals(singlesItems, both.byBucket.map { it.deliveredItems })

        // Every bucket splits per pick in request order (b, a, nobody), each share the independent per-bucket figures.
        val names = displayNames(listOf(a, b))
        for (bucket in both.byBucket) {
            assertEquals(listOf(b, a, nobody), bucket.byUser.map { it.accountId })
            for (share in bucket.byUser) {
                val rows = picked.filter { it.account == share.accountId && bucketKey(zone, it.doneAt) == bucket.bucketStart }
                assertEquals(rows.size, share.deliveredItems, "${share.accountId} items in ${bucket.bucketStart}")
                assertEquals(rows.mdSum(), share.deliveredMd, ABS_TOLERANCE, "${share.accountId} MD in ${bucket.bucketStart}")
            }
            assertEquals(bucket.deliveredItems, bucket.byUser.sumOf { it.deliveredItems })
            assertEquals(bucket.deliveredMd, bucket.byUser.sumOf { it.deliveredMd }, ABS_TOLERANCE)
            assertEquals(nobody, bucket.byUser.last().label)
            assertEquals(names[a] ?: a, bucket.byUser.single { it.accountId == a }.label)
        }
        assertTrue(both.byBucket.any { bucket -> bucket.byUser.any { it.deliveredItems > 0 } })

        // groups: one per pick (zero entry for the nobody account), period totals, label-then-id order, Σ groups == Σ byBucket.
        assertEquals(setOf(a, b, nobody), both.groups.map { it.accountId }.toSet())
        assertEquals(3, both.groups.size)
        assertTrue(both.groups.all { it.teamId == null })
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        for (group in both.groups) {
            val rows = picked.filter { it.account == group.accountId }
            assertEquals(rows.size, group.deliveredItems, "${group.accountId} group items")
            assertEquals(rows.mdSum(), group.deliveredMd, ABS_TOLERANCE, "${group.accountId} group MD")
        }
        assertEquals(both.byBucket.sumOf { it.deliveredItems }, both.groups.sumOf { it.deliveredItems })
        // A repeated identical value collapses rather than fails.
        assertEquals(oneA.byBucket, client.throughput("$query&accountId=$a&accountId=$a").byBucket)
    }

    @Test
    fun `one accountId keeps today's figures with a one-entry byUser`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val golden = reportThroughputGolden
        val floTeamId = floTeamId(connId, golden.sprintId)
        val client = seededClient("reports-throughput-one-user")
        val a = goldenDeliveries(connId).keys.first()
        val sprintQuery = "connectionId=$connId&teamId=$floTeamId&sprintId=${golden.sprintId}"

        val sprint = client.throughput("$sprintQuery&accountId=$a").bySprint.single()
        val share = sprint.byUser.single()
        assertEquals(a, share.accountId)
        assertEquals(sprint.deliveredMd, share.deliveredMd)
        assertEquals(sprint.deliveredItems, share.deliveredItems)
        assertEquals(goldenDeliveries(connId).getValue(a).second, sprint.deliveredItems)

        val periodQuery = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$floTeamId&accountId=$a"
        val period = client.throughput(periodQuery)
        for (bucket in period.byBucket) {
            val bucketShare = bucket.byUser.single()
            assertEquals(a, bucketShare.accountId)
            assertEquals(bucket.deliveredMd, bucketShare.deliveredMd)
            assertEquals(bucket.deliveredItems, bucketShare.deliveredItems)
        }
        assertEquals(a, period.groups.single().accountId)
        assertEquals(period.byBucket.sumOf { it.deliveredItems }, period.groups.single().deliveredItems)
    }

    @Test
    fun `at most eight accountIds on throughput, and a repeated accountId on another report stays 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val floTeamId = floTeamId(connId, reportThroughputGolden.sprintId)
        val client = seededClient("reports-throughput-account-bound")
        fun url(report: String, count: Int) = "/api/v1/reports/$report?connectionId=$connId&teamId=$floTeamId" +
            (1..count).joinToString("") { "&accountId=throughput-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(url("throughput", 8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url("throughput", 9)).status)
        assertEquals(HttpStatusCode.OK, client.get(url("blocked-time", 1)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url("blocked-time", 2)).status, "blocked time stays single-account")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/throughput?connectionId=$connId&accountId=a").status)
    }

    @Test
    fun `bad bucket and from after to are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-throughput-400")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/throughput?bucket=DAY").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/throughput?from=2026-01-01&to=2025-01-01").status)
    }

    private companion object {
        const val ABS_TOLERANCE = 0.005
    }
}
