package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.ThroughputReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
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
        ZoneId.of(MetricsConfigService.Settings.selectAll().toList().single()[MetricsConfigService.Settings.timeZone])
    }

    private suspend fun doneTasks(connId: UInt): List<DoneTask> = suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsStore.FactTaskDelivery
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
        val starts = MetricsStore.DimSprint.selectAll().where { MetricsStore.DimSprint.connectionId eq connId }.toList()
            .associate { it[MetricsStore.DimSprint.sprintId] to it[MetricsStore.DimSprint.startAt] }
        MetricsStore.FactSprint.selectAll()
            .where { (MetricsStore.FactSprint.connectionId eq connId) and (MetricsStore.FactSprint.teamId eq teamId) }
            .toList().map {
                SprintFacts(
                    sprintId = it[MetricsStore.FactSprint.sprintId],
                    startAt = starts[it[MetricsStore.FactSprint.sprintId]],
                    completeAt = it[MetricsStore.FactSprint.completeAt],
                    deliveredMd = it[MetricsStore.FactSprint.deliveredMd].toDouble(),
                    deliveredItems = it[MetricsStore.FactSprint.deliveredItems],
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
        MetricsStore.FactSprint.selectAll()
            .where { (MetricsStore.FactSprint.connectionId eq connId) and (MetricsStore.FactSprint.sprintId eq sprintId) }
            .toList().single()[MetricsStore.FactSprint.teamId]!!.value
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
            MetricsStore.FactSprint.selectAll()
                .where { (MetricsStore.FactSprint.connectionId eq connId) and (MetricsStore.FactSprint.sprintId eq golden.sprintId) }
                .toList().single()
        }
        assertEquals(fromFact[MetricsStore.FactSprint.deliveredMd].toDouble(), sprint.deliveredMd)
        assertEquals(fromFact[MetricsStore.FactSprint.deliveredItems], sprint.deliveredItems)

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
            val row = MetricsStore.DimSprint.selectAll()
                .where { (MetricsStore.DimSprint.connectionId eq connId) and (MetricsStore.DimSprint.sprintId eq golden.sprintId) }
                .toList().single()
            row[MetricsStore.DimSprint.startAt]!! to row[MetricsStore.DimSprint.completeAt]!!
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

        // USER level: the one account's own period view, no groups.
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.throughput("$range&teamId=$floTeamId&accountId=$account")
        assertTrue(user.groups.isEmpty())
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
            val s = MetricsStore.FactSprintScope
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
