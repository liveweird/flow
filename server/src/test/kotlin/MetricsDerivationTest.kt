package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.MetricsBoardTeamMapping
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsSprintCapacity
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipResponse
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class GoldenEpicFixture(
    val issueId: String,
    val issueKey: String,
    val budgetMd: Double,
    val startDate: String,
    val dueDate: String,
    val childSumMd: Double,
    val childCount: Int,
)

@Serializable
private data class GoldenSprintFixture(
    val sprintId: Long,
    val name: String,
    val projectKey: String,
    val startDate: String,
    val endDate: String,
    val completeDate: String,
    val committedMd: Double,
    val committedItems: Int,
    val committedIssueKeys: List<String>,
    val addedMd: Double,
    val addedItems: Int,
    val addedIssueKeys: List<String>,
    val removedMd: Double,
    val removedItems: Int,
    val removedIssueKeys: List<String>,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val deliveredIssueKeys: List<String>,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val carriedOverIssueKeys: List<String>,
    val droppedMd: Double,
    val droppedItems: Int,
    val droppedIssueKeys: List<String>,
)

@Serializable
private data class GoldenFixture(val epic: GoldenEpicFixture, val sprint: GoldenSprintFixture)

@Serializable
private data class MetricsExpectedFixture(val golden: GoldenFixture)

private val METRICS_DERIVATION_FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private val metricsDerivationGolden: GoldenFixture by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    METRICS_DERIVATION_FIXTURE_JSON.decodeFromString<MetricsExpectedFixture>(file.readText()).golden
}

private val metricsDerivationGoldenEpic: GoldenEpicFixture by lazy { metricsDerivationGolden.epic }
private val metricsDerivationGoldenSprint: GoldenSprintFixture by lazy { metricsDerivationGolden.sprint }

private fun isoDateEpochMillis(isoDate: String): Long = LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** A generic `[fromAtMs, toAtMs)` bridge-table interval, for an independent re-derivation of a
 * "value as of now" read against a persisted `metrics.*` bridge table — the same shape
 * `metrics/MetricsDeriver.kt`'s own `valueAt`/`teamAt` read over in-memory intervals. */
private data class BridgeInterval<T>(val value: T, val fromAtMs: Long, val toAtMs: Long?)

/** The interval CONTAINING [atMs] — `metrics/MetricsDeriver.kt`'s `valueAt` predicate, applied to
 * rows read back from a `metrics.*` bridge table rather than an in-memory interval list. */
private fun <T> valueAtBridge(rows: List<BridgeInterval<T>>, atMs: Long): T? =
    rows.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }?.value

/** The interval in effect at the LAST instant of a day — `agg_daily_wip`'s own end-of-day rule
 * (`valid_from < day_end AND (valid_to IS NULL OR valid_to >= day_end)`, where `day_end` is the
 * exclusive start of the next day). A change landing exactly at midnight belongs to the next day. */
private fun <T> valueAtDayEnd(rows: List<BridgeInterval<T>>, dayEndMs: Long): T? =
    rows.firstOrNull { it.fromAtMs < dayEndMs && (it.toAtMs == null || it.toAtMs >= dayEndMs) }?.value

/**
 * `metrics/MetricsDeriver.kt` (v0.3.0 M3 commit 7): DERIVE over a CLONE of `SyncedStubFixture`'s
 * shared connection (never the shared connection itself — `.claude/docs/testing.md` "Shared synced
 * fixture") with a pinned clock, asserting invariants 2/3/4/8/12 as SQL sweeps over the PERSISTED
 * `metrics.*` rows. Runs with the computed DEFAULT configuration (no admin metrics-config/
 * team-membership setup, unless a test's own SUBJECT is exactly that mutation) —
 * `MetricsConfigService.effectiveConfig` already falls back to safe defaults derived from
 * `norm`/the data profile, so a DERIVE run needs no prior admin configuration to succeed.
 *
 * **`.claude/docs/testing.md` "The derived fixture".** A test that only READS the result of a
 * single DERIVE under the default per-connection config (config/membership/settings unchanged,
 * one derive only) reads [DerivedStubFixture]'s own connection instead of deriving its own — the
 * `SyncedStubFixture` read-only rule, one layer up the pipeline. A test whose SUBJECT is a second
 * derive, a config/membership/settings mutation, or a raw-row simulation clones its OWN connection
 * via [clonedProcessedConnection] instead — never touches the shared derived connection.
 */
class MetricsDerivationTest {
    private fun workItems() = SyncedStubFixture.workItems()
    private fun dataSources() = SyncedStubFixture.dataSources()
    private fun metricsConfig() =
        MetricsConfigService(sharedDatabaseForTests(), workItems(), dataSources(), SyncJobsService(sharedDatabaseForTests(), 3))
    private fun teamMembership(config: MetricsConfigService) = TeamMembershipService(sharedDatabaseForTests(), config)
    private fun metricsStore() = MetricsStore(sharedDatabaseForTests())

    /**
     * A CHEAP [SyncedStubFixture.cloneProcessedData] clone of the shared synced connection — the
     * raw AND already-processed `norm.*`/profile rows, copied verbatim, never re-running PROCESS/
     * PROFILE (`.claude/docs/testing.md` "Shared synced fixture" — "The derived fixture"). Only a
     * test whose SUBJECT is a second derive, a config/membership/settings mutation, or a raw-row
     * simulation needs its own clone at all — a single-derive read belongs on [DerivedStubFixture]
     * instead.
     */
    private suspend fun clonedProcessedConnection(): UInt {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-derive-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(sharedConnId, connId)
        return connId
    }

    @Test
    fun `DERIVE over a freshly processed connection writes non-empty fact_task_delivery satisfying invariants 3 and 4`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val factRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }.toList()
        }
        assertTrue(factRows.isNotEmpty(), "fact_task_delivery must be non-empty after deriving a real processed connection")

        // Invariant 4 (review round 1: relaxed to the REAL invariant): started_at <= done_at ONLY
        // when BOTH are set — `startedAt` is null for a legal To Do -> Done issue that never visited
        // an IN_PROGRESS stage (`DeriveKernels.startedDoneAt`'s own doc: doneAt depends only on the
        // CURRENT stage being DONE, never on whether the item was ever started); done_at is set only
        // while the current stage is DONE, unconditionally.
        factRows.forEach { row ->
            val startedAt = row[MetricsStore.FactTaskDelivery.startedAt]
            val doneAt = row[MetricsStore.FactTaskDelivery.doneAt]
            val currentStage = row[MetricsStore.FactTaskDelivery.currentStage]
            if (doneAt != null) {
                if (startedAt != null) {
                    assertTrue(startedAt <= doneAt, "done_at requires a started_at no later than it, when both are set")
                }
                assertEquals("DONE", currentStage, "done_at is set only while the current stage is DONE")
            }
        }

        // Invariant 3: metrics.item_stage tiles each issue's lifetime the same way norm's status
        // intervals do — contiguous, exactly one open (valid_to IS NULL) interval per issue.
        // Ordered by (issueId, id) rather than (issueId, validFrom): zero-length intervals are
        // allowed (the same rule `norm`'s own status tiling follows), so two rows can legitimately
        // share one `validFrom` — the surrogate `id` preserves each issue's own insertion order
        // (`MetricsDeriver.compose` appends one issue's stage rows contiguously before the next).
        val stageRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                .orderBy(MetricsStore.ItemStage.issueId to SortOrder.ASC, MetricsStore.ItemStage.id to SortOrder.ASC)
                .toList()
        }
        assertTrue(stageRows.isNotEmpty(), "metrics.item_stage must be non-empty after DERIVE")
        val byIssue = stageRows.groupBy { it[MetricsStore.ItemStage.issueId] }
        byIssue.forEach { (issueId, rows) ->
            val openCount = rows.count { it[MetricsStore.ItemStage.validTo] == null }
            assertEquals(1, openCount, "issue $issueId must have exactly one open item_stage interval")
            rows.zipWithNext().forEach { (a, b) ->
                assertEquals(
                    a[MetricsStore.ItemStage.validTo],
                    b[MetricsStore.ItemStage.validFrom],
                    "issue $issueId's item_stage intervals must tile contiguously",
                )
            }
        }
    }

    @Test
    fun `DERIVE writes the golden epic's own budget, start-due dates and child sum matching expected_json`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val golden = metricsDerivationGoldenEpic
        val epicIssueId = golden.issueId.toLong()

        val dimRow = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.DimEpic.selectAll()
                .where { (MetricsStore.DimEpic.connectionId eq connId) and (MetricsStore.DimEpic.issueId eq epicIssueId) }
                .toList()
                .single()
        }
        assertEquals(golden.issueKey, dimRow[MetricsStore.DimEpic.issueKey], "sample-data/jira/expected.json golden.epic.issueKey")
        assertEquals(
            isoDateEpochMillis(golden.startDate),
            dimRow[MetricsStore.DimEpic.startAt],
            "sample-data/jira/expected.json golden.epic.startDate (the epic's own configured start-date field)",
        )
        assertEquals(
            isoDateEpochMillis(golden.dueDate),
            dimRow[MetricsStore.DimEpic.dueAt],
            "sample-data/jira/expected.json golden.epic.dueDate (duedate)",
        )

        val factRow = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactEpicDelivery.selectAll()
                .where { (MetricsStore.FactEpicDelivery.connectionId eq connId) and (MetricsStore.FactEpicDelivery.issueId eq epicIssueId) }
                .toList()
                .single()
        }
        assertEquals(
            golden.budgetMd,
            factRow[MetricsStore.FactEpicDelivery.ownEstimateCurrentMd]?.toDouble(),
            "sample-data/jira/expected.json golden.epic.budgetMd (D4: the epic's own current estimate)",
        )
        assertEquals(
            "OWN",
            factRow[MetricsStore.FactEpicDelivery.budgetSource],
            "an epic carrying its own estimate never falls back to the CHILDREN budget source",
        )
        assertEquals(
            golden.childSumMd,
            factRow[MetricsStore.FactEpicDelivery.childSumEstimateMd].toDouble(),
            "sample-data/jira/expected.json golden.epic.childSumMd",
        )

        // Direct (level-0) children only, matching generate.mjs's own `childrenOf` definition
        // (`issues.filter(i => i.parent === epic)`, never counting a child's own sub-tasks) —
        // `metrics.task_epic` additionally carries one row per SUBTASK (D2's one-indirection rule:
        // a sub-task's own "epic" resolves through its parent task), so counting THAT table here
        // would double the fixture's own count; `dim_task.is_subtask` is the same flag `DimTaskRow`
        // was built from, so filtering on it reproduces the generator's direct-children figure.
        val childCount = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.DimTask.selectAll()
                .where {
                    (MetricsStore.DimTask.connectionId eq connId) and
                        (MetricsStore.DimTask.epicId eq epicIssueId) and
                        (MetricsStore.DimTask.isSubtask eq false)
                }
                .count()
        }
        assertEquals(golden.childCount.toLong(), childCount, "sample-data/jira/expected.json golden.epic.childCount")
    }

    @Test
    fun `DERIVE writes the golden epic's PV baseline matching expected_json, and its PV curve ends at the budget`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val golden = metricsDerivationGoldenEpic
        val epicIssueId = golden.issueId.toLong()

        val planRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactEpicPlan.selectAll()
                .where { (MetricsStore.FactEpicPlan.connectionId eq connId) and (MetricsStore.FactEpicPlan.issueId eq epicIssueId) }
                .orderBy(MetricsStore.FactEpicPlan.baselineSeq to SortOrder.ASC)
                .toList()
        }
        assertTrue(planRows.isNotEmpty(), "the golden epic must carry at least one fact_epic_plan baseline")

        // Whether the epic's dates changed mid-history (~25% of epics do, `generate.mjs`) or not,
        // its own story-point estimate never changes — only the CURRENT (unsuperseded) baseline is
        // guaranteed to carry `expected.json`'s own current start/due/budget values.
        val current = planRows.single { it[MetricsStore.FactEpicPlan.supersededAt] == null }
        assertEquals(
            isoDateEpochMillis(golden.startDate),
            current[MetricsStore.FactEpicPlan.startAt],
            "sample-data/jira/expected.json golden.epic.startDate (the CURRENT baseline)",
        )
        assertEquals(
            isoDateEpochMillis(golden.dueDate),
            current[MetricsStore.FactEpicPlan.dueAt],
            "sample-data/jira/expected.json golden.epic.dueDate (the CURRENT baseline)",
        )
        assertEquals(
            golden.budgetMd,
            current[MetricsStore.FactEpicPlan.budgetMd]?.toDouble(),
            "sample-data/jira/expected.json golden.epic.budgetMd",
        )
        assertEquals("OWN", current[MetricsStore.FactEpicPlan.budgetSource], "the golden epic always carries its own estimate")

        // Reconstructs the SAME calendar `DerivedStubFixture` derived under (Europe/Warsaw, the V15
        // seed default, only hoursPerDay is overridden there) and re-runs the ALREADY-unit-tested
        // pvCurve kernel over the PERSISTED baseline — an end-to-end check that the stored row wires
        // correctly into a real PV curve, not a re-proof of the kernel's own math (DeriveKernelsTest).
        val calendar = WorkingCalendar(ZoneId.of("Europe/Warsaw"), setOf(6, 7), emptySet())
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = current[MetricsStore.FactEpicPlan.baselinedAt],
            startAtMs = current[MetricsStore.FactEpicPlan.startAt]!!,
            dueAtMs = current[MetricsStore.FactEpicPlan.dueAt]!!,
            budgetMd = current[MetricsStore.FactEpicPlan.budgetMd]!!.toDouble(),
            budgetSource = current[MetricsStore.FactEpicPlan.budgetSource],
            supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, calendar)
        assertTrue(curve.isNotEmpty(), "the golden epic's start-due window must contain at least one working day")
        assertEquals(baseline.budgetMd, curve.last().cumulativeMd, "PV at the epic's due date must equal its budget exactly")
    }

    @Test
    fun `invariant 2 — task_epic never lets a task belong to more than one epic at any instant`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.TaskEpic.selectAll().where { MetricsStore.TaskEpic.connectionId eq connId }
                .orderBy(MetricsStore.TaskEpic.issueId to SortOrder.ASC, MetricsStore.TaskEpic.id to SortOrder.ASC)
                .toList()
        }
        assertTrue(rows.isNotEmpty(), "metrics.task_epic must be non-empty after DERIVE")
        val byIssue = rows.groupBy { it[MetricsStore.TaskEpic.issueId] }
        // Review round 2a fix: `task_epic` is now built from the PARENT field's REAL history, not a
        // single open row carrying only the current epic — the sample dataset carries at least one
        // genuine epic reassignment (`sample-data/jira/generate.mjs`'s own `{ field: "Parent", ... }`
        // changelog item), so at least one task must show more than one row; a regression back to
        // "always exactly one row" would pass every OTHER assertion below yet silently lose history.
        assertTrue(
            byIssue.values.any { it.size > 1 },
            "at least one task must carry real epic-reassignment history (more than one task_epic row)",
        )
        byIssue.forEach { (issueId, taskRows) ->
            val openCount = taskRows.count { it[MetricsStore.TaskEpic.validTo] == null }
            assertEquals(1, openCount, "issue $issueId must have exactly one OPEN task_epic interval")
            taskRows.zipWithNext().forEach { (a, b) ->
                val aFrom = a[MetricsStore.TaskEpic.validFrom]
                val aTo = a[MetricsStore.TaskEpic.validTo] ?: Long.MAX_VALUE
                val bFrom = b[MetricsStore.TaskEpic.validFrom]
                assertTrue(aTo <= bFrom, "issue $issueId's task_epic intervals must never overlap (aTo=$aTo, bFrom=$bFrom, aFrom=$aFrom)")
            }
        }
    }

    @Test
    fun `invariant 2 — task_assignee never lets a task carry more than one assignee at any instant`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.TaskAssignee.selectAll().where { MetricsStore.TaskAssignee.connectionId eq connId }
                .orderBy(MetricsStore.TaskAssignee.issueId to SortOrder.ASC, MetricsStore.TaskAssignee.id to SortOrder.ASC)
                .toList()
        }
        assertTrue(rows.isNotEmpty(), "metrics.task_assignee must be non-empty after DERIVE")
        val byIssue = rows.groupBy { it[MetricsStore.TaskAssignee.issueId] }
        // Review round 2a fix: built from `norm`'s own ASSIGNEE field intervals rather than a single
        // current-value row — real reassignment history over ~1,200 issues is expected.
        assertTrue(byIssue.values.any { it.size > 1 }, "at least one task must show real assignee-reassignment history")
        byIssue.forEach { (issueId, taskRows) ->
            val openCount = taskRows.count { it[MetricsStore.TaskAssignee.validTo] == null }
            assertEquals(1, openCount, "issue $issueId must have exactly one OPEN task_assignee interval")
            taskRows.zipWithNext().forEach { (a, b) ->
                val aTo = a[MetricsStore.TaskAssignee.validTo] ?: Long.MAX_VALUE
                val bFrom = b[MetricsStore.TaskAssignee.validFrom]
                assertTrue(aTo <= bFrom, "issue $issueId's task_assignee intervals must never overlap")
            }
        }

        // task_epic/task_domain/task_assignee are TASK-only bridges (review round 2a: "skip epics
        // for task_* bridges") — an epic issue id must never appear as the issueId of a task_assignee row.
        val epicIds = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.DimEpic.selectAll().where { MetricsStore.DimEpic.connectionId eq connId }
                .toList().map { it[MetricsStore.DimEpic.issueId] }.toSet()
        }
        assertTrue(byIssue.keys.none { it in epicIds }, "no epic issue id may appear in task_assignee")
    }

    @Test
    fun `invariant 12-lite — a second DERIVE over unchanged input yields a byte-identical fact_task_delivery digest`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
        )
        fun claim(id: UInt) = SyncJobClaim(
            id = id,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )

        deriver.derive(SyncJobRunContext(claim(4u), clock = { PINNED_NOW }) { _, _ -> true })
        val firstDigest = DerivedStubFixture.factTaskDeliveryDigest(connId)

        deriver.derive(SyncJobRunContext(claim(5u), clock = { PINNED_NOW }) { _, _ -> true })
        val secondDigest = DerivedStubFixture.factTaskDeliveryDigest(connId)

        assertEquals(
            firstDigest,
            secondDigest,
            "a re-DERIVE over the SAME norm rows and config revision must write identical fact_task_delivery rows",
        )
    }

    /** A single simple task on a FRESH (never-stub-synced) connection, its current status carrying the UNKNOWN Jira category. */
    private suspend fun seedUnmappedStatusIssue(connId: UInt) {
        workItems().replaceStatuses(connId, listOf(StatusRef("77", "Weird", StatusCategory.UNKNOWN)))
        val createdAt = PINNED_NOW - 1000L
        val facts = WorkItemFacts(
            issueKey = "UNM-1",
            projectKey = "UNM",
            issueType = "Task",
            isSubtask = false,
            parentIssueId = null,
            summary = "An unmapped-status task",
            currentStatusId = "77",
            resolution = null,
            priority = null,
            assigneeAccountId = null,
            reporterAccountId = null,
            createdAtMs = createdAt,
            updatedAtMs = createdAt,
            resolvedAtMs = null,
            storyPoints = null,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            customFieldsJson = "{}",
            tombstone = TombstoneKind.NONE,
        )
        val normalized = NormalizedIssue(
            issueId = 900_001L,
            facts = facts,
            currentStatusName = "Weird",
            currentStatusCategory = StatusCategory.UNKNOWN,
            statusIntervals = listOf(
                NormalizedStatusInterval(1, "77", "Weird", StatusCategory.UNKNOWN, createdAt, null, IntervalSource.CREATED),
            ),
            fieldIntervals = emptyList(),
            fieldChanges = emptyList(),
            worklogs = emptyList(),
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        workItems().replaceWorkItem(connId, normalized, createdAt)
    }

    @Test
    fun `DERIVE flags a deliberately UNMAPPED status - never counted as started or done, per the domain-model rule`() = runBlocking {
        val ds = dataSources()
        val connId = SyncedStubFixture.createConnection(dataSources = ds, namePrefix = "jira-unmapped", enabled = false)
        seedUnmappedStatusIssue(connId)

        val config = metricsConfig()
        val deriver = MetricsDeriver(workItems(), config, teamMembership(config), metricsStore(), sharedDatabaseForTests())
        val claim = SyncJobClaim(
            id = 7u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )
        deriver.derive(SyncJobRunContext(claim, clock = { PINNED_NOW }) { _, _ -> true })

        val factRow = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll()
                .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq 900_001L) }
                .toList()
                .single()
        }
        assertEquals(
            "UNMAPPED",
            factRow[MetricsStore.FactTaskDelivery.currentStage],
            "a status carrying no configured stage must flag the task's own stage UNMAPPED, never guessed",
        )
        val flags = Json.parseToJsonElement(factRow[MetricsStore.FactTaskDelivery.flags]).jsonArray
        assertTrue(
            flags.any { it.jsonPrimitive.content == "UNMAPPED_STATUS" },
            "an UNMAPPED current stage must carry the UNMAPPED_STATUS flag",
        )
        // The domain-model rule (`.claude/docs/domain-model.md` "an unmapped status is flagged,
        // never guessed"): UNMAPPED counts as neither IN_PROGRESS nor DONE, so an item that has
        // ONLY ever sat in an unmapped status is never started and never done.
        assertNull(factRow[MetricsStore.FactTaskDelivery.startedAt], "an UNMAPPED-only status history must never be treated as started")
        assertNull(factRow[MetricsStore.FactTaskDelivery.doneAt], "an UNMAPPED-only status history must never be treated as done")

        val stageRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.ItemStage.selectAll()
                .where { (MetricsStore.ItemStage.connectionId eq connId) and (MetricsStore.ItemStage.issueId eq 900_001L) }
                .toList()
        }
        assertEquals(1, stageRows.size)
        assertEquals("UNMAPPED", stageRows.single()[MetricsStore.ItemStage.stage])
    }

    /** Maps the FLO project's own board (id 1, `sample-data/README.md`) to a fresh team, via ONE
     * PUT — the sprint step's own team-mapping input. */
    /** Preserves the COMPUTED defaults (estimate field detection etc. — `effectiveConfig`, `.claude/docs/metrics.md`
     * "Configuration model") — a bare `replaceConfig` with only `boards` set would otherwise wipe every other
     * field back to its bare unconfigured null, since a stored config is a FULL replace, not a patch. */
    private suspend fun mapFloBoardToTeam(connId: UInt, config: MetricsConfigService): UInt {
        val teamId = TestTeams.seed(uniqueEmail("flo-sprint-team"))
        val current = config.effectiveConfig(connId)
        config.replaceConfig(
            connId,
            DataSourceMetricsConfigRequest(
                statusStages = current.statusStages,
                fields = current.fields,
                domains = current.domains,
                boards = listOf(MetricsBoardTeamMapping(1L, teamId)),
                activityTypes = current.activityTypes,
                workCategories = current.workCategories,
                blockedStatuses = current.blockedStatuses,
                sprintCapacities = current.sprintCapacities,
            ),
        )
        return teamId
    }

    private fun deriver(config: MetricsConfigService) =
        MetricsDeriver(workItems(), config, teamMembership(config), metricsStore(), sharedDatabaseForTests())

    private fun deriveClaim(id: UInt, connId: UInt) = SyncJobClaim(
        id = id, connectionId = connId, connectorKind = DataSourceKind.JIRA_CLOUD, kind = SyncJobKind.DERIVE,
        attempt = 1, maxAttempts = 3, syncIntervalMinutes = 60,
    )

    @Test
    fun `DERIVE's fact_sprint and fact_sprint_scope reproduce the golden FLO sprint's buckets and issue keys`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val golden = metricsDerivationGoldenSprint
        val sprintId = golden.sprintId

        val factRow = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactSprint.selectAll()
                .where { (MetricsStore.FactSprint.connectionId eq connId) and (MetricsStore.FactSprint.sprintId eq sprintId) }
                .toList().single()
        }
        assertEquals(golden.committedMd, factRow[MetricsStore.FactSprint.committedMd].toDouble(), "expected.json golden.sprint.committedMd")
        assertEquals(golden.committedItems, factRow[MetricsStore.FactSprint.committedItems])
        assertEquals(golden.addedMd, factRow[MetricsStore.FactSprint.addedMd].toDouble(), "expected.json golden.sprint.addedMd")
        assertEquals(golden.addedItems, factRow[MetricsStore.FactSprint.addedItems])
        assertEquals(golden.removedMd, factRow[MetricsStore.FactSprint.removedMd].toDouble(), "expected.json golden.sprint.removedMd")
        assertEquals(golden.removedItems, factRow[MetricsStore.FactSprint.removedItems])
        assertEquals(golden.finalMd, factRow[MetricsStore.FactSprint.finalMd].toDouble(), "expected.json golden.sprint.finalMd")
        assertEquals(golden.finalItems, factRow[MetricsStore.FactSprint.finalItems])
        assertEquals(golden.deliveredMd, factRow[MetricsStore.FactSprint.deliveredMd].toDouble(), "expected.json golden.sprint.deliveredMd")
        assertEquals(golden.deliveredItems, factRow[MetricsStore.FactSprint.deliveredItems])
        assertEquals(
            golden.carriedOverMd,
            factRow[MetricsStore.FactSprint.carriedOverMd].toDouble(),
            "expected.json golden.sprint.carriedOverMd",
        )
        assertEquals(golden.carriedOverItems, factRow[MetricsStore.FactSprint.carriedOverItems])
        assertEquals(golden.droppedMd, factRow[MetricsStore.FactSprint.droppedMd].toDouble(), "expected.json golden.sprint.droppedMd")
        assertEquals(golden.droppedItems, factRow[MetricsStore.FactSprint.droppedItems])

        suspend fun issueKeysFor(predicate: Op<Boolean>): Set<String> = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactSprintScope.join(
                MetricsStore.FactTaskDelivery,
                JoinType.INNER,
                onColumn = MetricsStore.FactSprintScope.issueId,
                otherColumn = MetricsStore.FactTaskDelivery.issueId,
                additionalConstraint = { MetricsStore.FactSprintScope.connectionId eq MetricsStore.FactTaskDelivery.connectionId },
            )
                .select(MetricsStore.FactTaskDelivery.issueKey)
                .where {
                    (MetricsStore.FactSprintScope.connectionId eq connId) and
                        (MetricsStore.FactSprintScope.sprintId eq sprintId) and predicate
                }
                .toList().map { it[MetricsStore.FactTaskDelivery.issueKey] }.toSet()
        }

        fun failureMessage(bucket: String, expected: List<String>, actual: Set<String>): String {
            val expectedSet = expected.toSet()
            return "sprint $sprintId $bucket bucket diverges from expected.json — " +
                "missing=${expectedSet - actual}, unexpected=${actual - expectedSet}"
        }

        val committedKeys = issueKeysFor(
            (MetricsStore.FactSprintScope.committed eq true) and (MetricsStore.FactSprintScope.inScopeAtClose eq true),
        )
        assertEquals(
            golden.committedIssueKeys.toSet(), committedKeys,
            failureMessage("committed", golden.committedIssueKeys, committedKeys),
        )
        val addedKeys = issueKeysFor(MetricsStore.FactSprintScope.addedAt.isNotNull())
        assertEquals(golden.addedIssueKeys.toSet(), addedKeys, failureMessage("added", golden.addedIssueKeys, addedKeys))
        val removedKeys = issueKeysFor(MetricsStore.FactSprintScope.removedAt.isNotNull())
        assertEquals(golden.removedIssueKeys.toSet(), removedKeys, failureMessage("removed", golden.removedIssueKeys, removedKeys))
        val deliveredKeys = issueKeysFor(MetricsStore.FactSprintScope.doneInSprint eq true)
        assertEquals(
            golden.deliveredIssueKeys.toSet(), deliveredKeys,
            failureMessage("delivered", golden.deliveredIssueKeys, deliveredKeys),
        )
        val carriedKeys = issueKeysFor(MetricsStore.FactSprintScope.carriedOver eq true)
        assertEquals(
            golden.carriedOverIssueKeys.toSet(), carriedKeys,
            failureMessage("carriedOver", golden.carriedOverIssueKeys, carriedKeys),
        )
        val droppedKeys = issueKeysFor(MetricsStore.FactSprintScope.dropped eq true)
        assertEquals(golden.droppedIssueKeys.toSet(), droppedKeys, failureMessage("dropped", golden.droppedIssueKeys, droppedKeys))
    }

    @Test
    fun `invariant 8 — fact_sprint's totals equal the Σ of its own fact_sprint_scope rows`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        val (factRows, scopeSums) = suspendTransaction(sharedDatabaseForTests()) {
            val facts = MetricsStore.FactSprint.selectAll().where { MetricsStore.FactSprint.connectionId eq connId }
                .toList().associateBy { it[MetricsStore.FactSprint.sprintId] }
            val scopeRows = MetricsStore.FactSprintScope.selectAll().where { MetricsStore.FactSprintScope.connectionId eq connId }.toList()
            facts to scopeRows
        }
        assertTrue(factRows.isNotEmpty(), "fact_sprint must be non-empty after DERIVE")

        val bySprint = scopeSums.groupBy { it[MetricsStore.FactSprintScope.sprintId] }
        factRows.forEach { (sprintId, row) ->
            val scopeForSprint = bySprint[sprintId].orEmpty()
            val committedMdSum = scopeForSprint
                .filter { it[MetricsStore.FactSprintScope.committed] && it[MetricsStore.FactSprintScope.inScopeAtClose] }
                .sumOf { it[MetricsStore.FactSprintScope.estimateAtCommitmentMd]?.toDouble() ?: 0.0 }
            assertEquals(
                row[MetricsStore.FactSprint.committedMd].toDouble(), committedMdSum,
                "sprint $sprintId: fact_sprint.committed_md must equal Σ fact_sprint_scope",
            )
            val finalMdSum = scopeForSprint.filter { it[MetricsStore.FactSprintScope.inScopeAtClose] }
                .sumOf { it[MetricsStore.FactSprintScope.estimateAtCloseMd]?.toDouble() ?: 0.0 }
            assertEquals(
                row[MetricsStore.FactSprint.finalMd].toDouble(), finalMdSum,
                "sprint $sprintId: fact_sprint.final_md must equal Σ fact_sprint_scope",
            )
            val deliveredMdSum = scopeForSprint.filter { it[MetricsStore.FactSprintScope.doneInSprint] }
                .sumOf { it[MetricsStore.FactSprintScope.estimateAtDoneMd]?.toDouble() ?: 0.0 }
            assertEquals(
                row[MetricsStore.FactSprint.deliveredMd].toDouble(), deliveredMdSum,
                "sprint $sprintId: fact_sprint.delivered_md must equal Σ fact_sprint_scope",
            )
        }
    }

    @Test
    fun `invariant 8 (A17) — every fact_sprint row partitions as final = delivered + carried + dropped, committed + added = final`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()

            val factRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprint.selectAll().where { MetricsStore.FactSprint.connectionId eq connId }.toList()
            }
            assertTrue(factRows.isNotEmpty(), "fact_sprint must be non-empty after DERIVE")

            // Item counts: `committed + added = final = delivered + carried + dropped` holds EXACTLY,
            // in both items and MD — every row reaching `sprintTotals` sits in the SAME
            // in-scope-at-close set, and `committed`/`added`/`final` are literal partitions of it by
            // construction (`DeriveKernels.sprintScope`). The MD identity is checked ONLY for
            // `final = delivered + carried + dropped`, not `committed + added = final`: committed/
            // added MD is read at the task's OWN commit/entry instant, while final/delivered/carried/
            // dropped MD are all read at the SAME `sprintCloseAt` instant — a re-estimate between
            // entry and close makes the committed+added MD sum genuinely diverge from final MD (not a
            // rounding artifact — confirmed against the real fixture, `.claude/docs/metrics.md`
            // "Sprint scope, facts and snapshots (D13)"); delivered/carried/dropped, by contrast, all
            // read the identical `estimateAtCloseMd` value over an exact partition, so their MD sum
            // equals final MD up to BigDecimal storage rounding only.
            val mdTolerance = 0.01
            factRows.forEach { row ->
                val sprintId = row[MetricsStore.FactSprint.sprintId]
                val committedItems = row[MetricsStore.FactSprint.committedItems]
                val addedItems = row[MetricsStore.FactSprint.addedItems]
                val finalItems = row[MetricsStore.FactSprint.finalItems]
                val deliveredItems = row[MetricsStore.FactSprint.deliveredItems]
                val carriedOverItems = row[MetricsStore.FactSprint.carriedOverItems]
                val droppedItems = row[MetricsStore.FactSprint.droppedItems]
                assertEquals(
                    finalItems, committedItems + addedItems,
                    "sprint $sprintId: committed + added items must equal final items (A17)",
                )
                assertEquals(
                    finalItems, deliveredItems + carriedOverItems + droppedItems,
                    "sprint $sprintId: delivered + carried + dropped items must equal final items (A17)",
                )

                val finalMd = row[MetricsStore.FactSprint.finalMd].toDouble()
                val deliveredMd = row[MetricsStore.FactSprint.deliveredMd].toDouble()
                val carriedOverMd = row[MetricsStore.FactSprint.carriedOverMd].toDouble()
                val droppedMd = row[MetricsStore.FactSprint.droppedMd].toDouble()
                assertTrue(
                    kotlin.math.abs(finalMd - (deliveredMd + carriedOverMd + droppedMd)) <= mdTolerance,
                    "sprint $sprintId: delivered + carried + dropped MD ($deliveredMd + $carriedOverMd + $droppedMd) " +
                        "must equal final MD ($finalMd) within tolerance (A17)",
                )
            }
        }

    @Test
    fun `fact_sprint_snapshot is written for a closed team-mapped sprint, reconstructed first, never updated afterwards`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            mapFloBoardToTeam(connId, config)
            val sprintId = metricsDerivationGoldenSprint.sprintId

            deriver(config).derive(SyncJobRunContext(deriveClaim(12u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val snapshotRow = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintSnapshot.selectAll()
                    .where {
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and
                            (MetricsStore.FactSprintSnapshot.sprintId eq sprintId)
                    }
                    .toList().singleOrNull()
            }
            requireNotNull(snapshotRow) { "no snapshot row for the closed, team-mapped golden sprint" }
            assertTrue(
                snapshotRow[MetricsStore.FactSprintSnapshot.reconstructed],
                "the connection's very first DERIVE run has no earlier successful run to have processed this sprint live",
            )
            assertTrue(
                Json.parseToJsonElement(snapshotRow[MetricsStore.FactSprintSnapshot.scope]).jsonArray.isNotEmpty(),
                "the frozen scope JSON must carry the sprint's own scope rows",
            )

            // A raw UPDATE against an existing snapshot row must raise (the immutability trigger) —
            // never silently succeed.
            val updateAttempt = runCatching {
                suspendTransaction(sharedDatabaseForTests()) {
                    MetricsStore.FactSprintSnapshot.update({
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and (MetricsStore.FactSprintSnapshot.sprintId eq sprintId)
                    }) {
                        it[committedMd] = java.math.BigDecimal.valueOf(999)
                    }
                }
            }
            assertTrue(updateAttempt.isFailure, "an UPDATE against fact_sprint_snapshot must raise, per the immutability trigger")

            // A second DERIVE must not touch the existing snapshot row at all — same count, same digest.
            deriver(config).derive(SyncJobRunContext(deriveClaim(13u, connId), clock = { PINNED_NOW }) { _, _ -> true })
            val snapshotRowsAfterSecondDerive = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintSnapshot.selectAll()
                    .where {
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and
                            (MetricsStore.FactSprintSnapshot.sprintId eq sprintId)
                    }
                    .toList()
            }
            assertEquals(1, snapshotRowsAfterSecondDerive.size, "a second DERIVE must never insert a second snapshot for the same sprint")
            assertEquals(
                snapshotRow[MetricsStore.FactSprintSnapshot.committedMd],
                snapshotRowsAfterSecondDerive.single()[MetricsStore.FactSprintSnapshot.committedMd],
                "the surviving snapshot row's own figures must be byte-for-byte unchanged by the second DERIVE",
            )
        }

    @Test
    fun `DERIVE resolves the Sprint field by its detected id, not display name — golden sprint still matches`() = runBlocking {
        // DerivedStubFixture's own clone already ran PROCESS -> PROFILE, so its connection's profile
        // carries a detected SPRINT-role field id; this test only re-asserts the specific fix: DERIVE
        // resolves that id (`MetricsConfigService.detectedSprintFieldId`) and reads
        // `norm.work_item_field_changes` by it (`WorkItemStore.fieldChangesByFieldIds`), never by the
        // display text `"Sprint"` — a renamed/localized Sprint field on a real tenant must not go
        // silently unread.
        val connId = DerivedStubFixture.connectionId()

        val sprintId = metricsDerivationGoldenSprint.sprintId
        val committedKeys = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactSprintScope.join(
                MetricsStore.FactTaskDelivery,
                JoinType.INNER,
                onColumn = MetricsStore.FactSprintScope.issueId,
                otherColumn = MetricsStore.FactTaskDelivery.issueId,
                additionalConstraint = { MetricsStore.FactSprintScope.connectionId eq MetricsStore.FactTaskDelivery.connectionId },
            )
                .select(MetricsStore.FactTaskDelivery.issueKey)
                .where {
                    (MetricsStore.FactSprintScope.connectionId eq connId) and (MetricsStore.FactSprintScope.sprintId eq sprintId) and
                        (MetricsStore.FactSprintScope.committed eq true) and (MetricsStore.FactSprintScope.inScopeAtClose eq true)
                }
                .toList().map { it[MetricsStore.FactTaskDelivery.issueKey] }.toSet()
        }
        assertEquals(
            metricsDerivationGoldenSprint.committedIssueKeys.toSet(), committedKeys,
            "id-based Sprint field resolution must still reproduce the golden sprint's committed scope",
        )
    }

    @Test
    fun `a connection whose profile detects no Sprint field gets no fabricated sprint scope, flagged sprintFieldUnresolved`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-nosprintfield", enabled = false)
            // A sprint exists (so the step doesn't short-circuit on `sprints.isEmpty()`), but this
            // connection never ran PROCESS/PROFILE — `source_connections.profile` is null, so
            // `MetricsConfigService.detectedSprintFieldId` returns null: no SPRINT-role field is known.
            workItems().replaceSprints(
                connId,
                listOf(
                    SprintRef(
                        sprintId = 900_500L, boardId = null, name = "No-field sprint", state = "closed",
                        startAtMs = 0L, endAtMs = 1L, goal = null,
                    ),
                ),
            )
            val config = metricsConfig()
            deriver(config).derive(SyncJobRunContext(deriveClaim(51u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val sprintRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DimSprint.selectAll().where { MetricsStore.DimSprint.connectionId eq connId }.toList()
            }
            assertTrue(sprintRows.isEmpty(), "the whole sprint step must be skipped, never fabricating dim_sprint/fact_sprint rows")
            val scopeRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintScope.selectAll().where { MetricsStore.FactSprintScope.connectionId eq connId }.toList()
            }
            assertTrue(scopeRows.isEmpty(), "no fact_sprint_scope row may be fabricated without a resolved Sprint field")

            val runCounts = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DeriveRuns.selectAll().where { MetricsStore.DeriveRuns.connectionId eq connId.toInt() }
                    .orderBy(MetricsStore.DeriveRuns.id to SortOrder.DESC)
                    .toList().first()[MetricsStore.DeriveRuns.rowCounts]
            }
            assertNotNull(runCounts, "derive_runs.row_counts must be recorded")
            assertTrue(
                Json.parseToJsonElement(runCounts).jsonObject["sprintFieldUnresolved"]?.jsonPrimitive?.content == "true",
                "the run must record that the Sprint field could not be resolved, rather than silently guessing",
            )
        }

    @Test
    fun `sprint capacity defaults to Sigma members x working days (A3), then honors a configured override`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val teamId = mapFloBoardToTeam(connId, config)
        val sprintId = metricsDerivationGoldenSprint.sprintId

        // Two fresh team members, effective for the whole sprint window, on a connection-agnostic
        // `norm.people` row — `TeamMembershipService.create`'s account-existence check has no
        // connection filter, so any connection's own `replacePeople` satisfies it.
        val peopleConnId = SyncedStubFixture.createConnection(namePrefix = "capacity-people")
        val accountA = "capacity-acct-${UUID.randomUUID()}"
        val accountB = "capacity-acct-${UUID.randomUUID()}"
        workItems().replacePeople(
            peopleConnId,
            listOf(PersonRef(accountA, "Capacity A", null, active = true), PersonRef(accountB, "Capacity B", null, active = true)),
        )
        val sprintBefore = workItems().allSprintRefs(connId).single { it.sprintId == sprintId }
        val membershipStart = sprintBefore.startAtMs!! - THIRTY_DAYS_MS
        val membershipService = teamMembership(config)
        val membershipA = membershipService.create(teamId, TeamMembershipCreateRequest(accountA, membershipStart, null))
        val membershipB = membershipService.create(teamId, TeamMembershipCreateRequest(accountB, membershipStart, null))
        // `metrics.team_membership` is GLOBAL by account id (`.claude/docs/persistence.md` "metrics.team_membership
        // (D1)") — even a synthetic UUID-keyed account row must be cleaned up so a later test's own
        // "every membership row" sweep (`TeamMembershipService.allMembershipsByAccount`) never sees it (review
        // round 2c fix).
        try {
            deriver(config).derive(SyncJobRunContext(deriveClaim(20u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            // Computed independently of `MetricsDeriver`'s own private `sprintCapacity` — the SAME
            // `WorkingCalendar` class DERIVE itself uses (`WorkingCalendarTest` already proves its own
            // math), fed the connection's CURRENT stored settings and OUR OWN two membership rows.
            val settings = config.read()
            val calendar = WorkingCalendar(
                ZoneId.of(settings.timeZone),
                settings.weekendDays.toSet(),
                settings.holidays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet(),
            )
            val sprintStartAt = sprintBefore.startAtMs!!
            val endAt = sprintBefore.endAtMs ?: sprintBefore.completeAtMs ?: sprintStartAt
            val expectedWorkingDays = calendar.workingDaysBetween(sprintStartAt, endAt)
            val expectedDefaultCapacity = 2 * expectedWorkingDays

            val dimRowDefault = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DimSprint.selectAll()
                    .where { (MetricsStore.DimSprint.connectionId eq connId) and (MetricsStore.DimSprint.sprintId eq sprintId) }
                    .toList().single()
            }
            assertEquals("DEFAULT", dimRowDefault[MetricsStore.DimSprint.capacitySource])
            assertTrue(
                abs(expectedDefaultCapacity - dimRowDefault[MetricsStore.DimSprint.capacityMd]!!.toDouble()) < CAPACITY_TOLERANCE,
                "default capacity must be Sigma members x working days over the sprint window " +
                    "(expected $expectedDefaultCapacity, got ${dimRowDefault[MetricsStore.DimSprint.capacityMd]})",
            )

            // An admin now configures an explicit override for this one sprint.
            val current = config.effectiveConfig(connId)
            config.replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields,
                    domains = current.domains,
                    boards = listOf(MetricsBoardTeamMapping(1L, teamId)),
                    activityTypes = current.activityTypes,
                    workCategories = current.workCategories,
                    blockedStatuses = current.blockedStatuses,
                    sprintCapacities = listOf(MetricsSprintCapacity(sprintId, CONFIGURED_CAPACITY_MD)),
                ),
            )
            deriver(config).derive(SyncJobRunContext(deriveClaim(21u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val dimRowConfigured = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DimSprint.selectAll()
                    .where { (MetricsStore.DimSprint.connectionId eq connId) and (MetricsStore.DimSprint.sprintId eq sprintId) }
                    .toList().single()
            }
            val factRowConfigured = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprint.selectAll()
                    .where { (MetricsStore.FactSprint.connectionId eq connId) and (MetricsStore.FactSprint.sprintId eq sprintId) }
                    .toList().single()
            }
            assertEquals("CONFIGURED", dimRowConfigured[MetricsStore.DimSprint.capacitySource])
            assertEquals(CONFIGURED_CAPACITY_MD, dimRowConfigured[MetricsStore.DimSprint.capacityMd]!!.toDouble())
            assertEquals(CONFIGURED_CAPACITY_MD, factRowConfigured[MetricsStore.FactSprint.capacityMd]!!.toDouble())
            val expectedLoad = factRowConfigured[MetricsStore.FactSprint.committedMd].toDouble() / CONFIGURED_CAPACITY_MD
            assertTrue(
                abs(expectedLoad - factRowConfigured[MetricsStore.FactSprint.load]!!.toDouble()) < CAPACITY_TOLERANCE,
                "load must equal committed / capacity",
            )
        } finally {
            membershipService.delete(teamId, membershipA.id)
            membershipService.delete(teamId, membershipB.id)
        }
    }

    @Test
    fun `D5 - the OPS Kanban project's DONE tasks carry no sprint credit, falling back to the assignee's team at done`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            deriver(config).derive(SyncJobRunContext(deriveClaim(30u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val doneOpsRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactTaskDelivery.selectAll()
                    .where {
                        (MetricsStore.FactTaskDelivery.connectionId eq connId) and
                            (MetricsStore.FactTaskDelivery.domainKey eq "OPS") and
                            (MetricsStore.FactTaskDelivery.doneAt.isNotNull())
                    }
                    .toList()
            }
            assertTrue(doneOpsRows.isNotEmpty(), "the OPS (Kanban) project must have at least one DONE task")
            doneOpsRows.forEach { row ->
                assertNull(
                    row[MetricsStore.FactTaskDelivery.sprintIdAtDone],
                    "OPS is a Kanban project (no sprints) — sprint_id_at_done must always be NULL",
                )
                // Before any team membership is configured, every assignee is in no team, so the
                // fallback credit is null too (the "0/UNASSIGNED" case).
                assertNull(
                    row[MetricsStore.FactTaskDelivery.creditTeamId],
                    "with no configured team membership, credit_team_id must be null (assignee in no team)",
                )
            }

            val withAssignee = doneOpsRows.filter { it[MetricsStore.FactTaskDelivery.assigneeAccountIdAtDone] != null }
            assertTrue(withAssignee.size >= 2, "need at least two distinctly-assigned DONE OPS tasks to prove the fallback")
            val target = withAssignee.first()
            val targetAccountId = target[MetricsStore.FactTaskDelivery.assigneeAccountIdAtDone]!!
            val untouched = withAssignee.first { it[MetricsStore.FactTaskDelivery.assigneeAccountIdAtDone] != targetAccountId }

            val teamId = TestTeams.seed(uniqueEmail("ops-credit-team"))
            val doneAt = target[MetricsStore.FactTaskDelivery.doneAt]!!
            val membershipService = teamMembership(config)
            val membership = membershipService.create(teamId, TeamMembershipCreateRequest(targetAccountId, doneAt - THIRTY_DAYS_MS, null))
            // `targetAccountId` is a REAL stub account id (an actual assignee in the sample dataset), not a
            // synthetic UUID — `metrics.team_membership` is GLOBAL by account id, so a leftover open
            // membership here would corrupt EVERY OTHER test's "current team" sweep for this same account,
            // including the SHARED `DerivedStubFixture` connection (`.claude/docs/testing.md`'s tripwire
            // rationale, review round 2c fix). Clean up regardless of assertion outcome.
            try {
                deriver(config).derive(SyncJobRunContext(deriveClaim(31u, connId), clock = { PINNED_NOW }) { _, _ -> true })

                suspend fun reread(issueId: Long): ResultRow = suspendTransaction(sharedDatabaseForTests()) {
                    MetricsStore.FactTaskDelivery.selectAll()
                        .where {
                            (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq issueId)
                        }
                        .toList().single()
                }

                val targetAfter = reread(target[MetricsStore.FactTaskDelivery.issueId])
                assertNull(
                    targetAfter[MetricsStore.FactTaskDelivery.sprintIdAtDone],
                    "OPS still has no sprints — sprint_id_at_done stays NULL even once the assignee has a team",
                )
                assertEquals(
                    teamId,
                    targetAfter[MetricsStore.FactTaskDelivery.creditTeamId]?.value,
                    "credit_team_id must fall back to the assignee's own team at done, once one exists",
                )

                val untouchedAfter = reread(untouched[MetricsStore.FactTaskDelivery.issueId])
                assertNull(
                    untouchedAfter[MetricsStore.FactTaskDelivery.creditTeamId],
                    "an assignee still in no team keeps a null (0/UNASSIGNED) credit_team_id",
                )
            } finally {
                membershipService.delete(teamId, membership.id)
            }
        }

    @Test
    fun `reconstructed - a sprint closed before the first derive is reconstructed, one closed later is not, earlier snapshots untouched`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            val teamId = mapFloBoardToTeam(connId, config)
            val goldenSprintId = metricsDerivationGoldenSprint.sprintId

            deriver(config).derive(SyncJobRunContext(deriveClaim(40u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val goldenSnapshotBefore = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintSnapshot.selectAll()
                    .where {
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and
                            (MetricsStore.FactSprintSnapshot.sprintId eq goldenSprintId)
                    }
                    .toList().single()
            }
            assertTrue(
                goldenSnapshotBefore[MetricsStore.FactSprintSnapshot.reconstructed],
                "every stub sprint closed before this connection's first successful derive must be reconstructed",
            )

            // An active/future FLO sprint this same board is mapped to a team, never yet snapshotted.
            val notYetClosed = workItems().allSprintRefs(connId)
                .single { it.boardId == FLO_BOARD_ID && it.state.equals("future", ignoreCase = true) }
            suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.Sprints.update({
                    (WorkItemStore.Sprints.connectionId eq connId) and (WorkItemStore.Sprints.sprintId eq notYetClosed.sprintId)
                }) {
                    it[state] = "closed"
                    it[completeAt] = PINNED_NOW + THIRTY_DAYS_MS
                }
            }

            deriver(config).derive(SyncJobRunContext(deriveClaim(41u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val newlyClosedSnapshot = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintSnapshot.selectAll()
                    .where {
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and
                            (MetricsStore.FactSprintSnapshot.sprintId eq notYetClosed.sprintId)
                    }
                    .toList().single()
            }
            // `teamId` (the FLO board mapping `mapFloBoardToTeam` set up) is what makes this sprint
            // eligible for a snapshot at all — `closedAndMapped` in `MetricsDeriver.kt`'s sprint step.
            assertEquals(teamId, newlyClosedSnapshot[MetricsStore.FactSprintSnapshot.teamId]?.value)
            assertTrue(
                !newlyClosedSnapshot[MetricsStore.FactSprintSnapshot.reconstructed],
                "a sprint closed AFTER the connection's first successful derive must not be reconstructed",
            )

            val goldenSnapshotAfter = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactSprintSnapshot.selectAll()
                    .where {
                        (MetricsStore.FactSprintSnapshot.connectionId eq connId) and
                            (MetricsStore.FactSprintSnapshot.sprintId eq goldenSprintId)
                    }
                    .toList().single()
            }
            assertEquals(
                goldenSnapshotBefore[MetricsStore.FactSprintSnapshot.committedMd],
                goldenSnapshotAfter[MetricsStore.FactSprintSnapshot.committedMd],
                "an already-existing snapshot must never be touched by a later DERIVE",
            )
            assertEquals(
                goldenSnapshotBefore[MetricsStore.FactSprintSnapshot.scope],
                goldenSnapshotAfter[MetricsStore.FactSprintSnapshot.scope],
            )
            assertEquals(
                goldenSnapshotBefore[MetricsStore.FactSprintSnapshot.reconstructed],
                goldenSnapshotAfter[MetricsStore.FactSprintSnapshot.reconstructed],
            )
            assertEquals(
                goldenSnapshotBefore[MetricsStore.FactSprintSnapshot.snapshotAt],
                goldenSnapshotAfter[MetricsStore.FactSprintSnapshot.snapshotAt],
            )
        }

    @Test
    fun `fact_worklog - invariant 6 (none dropped, well-defined pair, late_ms, hoursPerDay)`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()

            val factWorklogRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }.toList()
            }
            assertTrue(factWorklogRows.isNotEmpty(), "fact_worklog must be non-empty for a connection with worklogs")

            // Invariant 6: none dropped — every LIVE norm.work_item_worklogs row for this
            // connection has exactly one fact_worklog row (the (author team, task domain) pair is
            // always well-defined, UNASSIGNED represented by a null author_team_id).
            val liveIssueIds = workItems().workItemsForDerivation(connId).map { it.issueId }.toSet()
            val worklogsByIssue = workItems().worklogsByIssue(connId)
            val liveWorklogCount = worklogsByIssue.filterKeys { it in liveIssueIds }.values.sumOf { it.size }
            assertEquals(
                liveWorklogCount.toLong(),
                factWorklogRows.size.toLong(),
                "invariant 6: every live-issue worklog gets exactly one fact_worklog row, none dropped",
            )

            // md = seconds / 3600 / hoursPerDay, under the hoursPerDay pinned for the derive above.
            val hoursPerDay = HOURS_PER_DAY
            factWorklogRows.forEach { row ->
                val worklog = worklogsByIssue.getValue(row[MetricsStore.FactWorklog.issueId])
                    .single { it.worklogId == row[MetricsStore.FactWorklog.worklogId] }
                val expectedMd = worklog.timeSpentSeconds / 3600.0 / hoursPerDay
                assertTrue(
                    abs(expectedMd - row[MetricsStore.FactWorklog.md].toDouble()) < CAPACITY_TOLERANCE,
                    "md must be seconds / 3600 / hoursPerDay",
                )
                // late_ms: created - started, clamped to >= 0, null only when created is unknown
                // (the generator always sets created here, so every row is checked).
                val expectedLateMs = worklog.createdAt?.let { maxOf(0L, it - worklog.startedAt) }
                assertEquals(expectedLateMs, row[MetricsStore.FactWorklog.lateMs], "late_ms must equal max(0, created - started)")
                assertTrue((row[MetricsStore.FactWorklog.lateMs] ?: 0) >= 0, "late_ms must never be negative")
            }
            assertTrue(
                factWorklogRows.any { (it[MetricsStore.FactWorklog.lateMs] ?: 0) > 0 },
                "the generator's worklog created/updated skew must produce at least one late-logged worklog",
            )
            Unit
        }

    @Test
    fun `fact_worklog - invariant 7`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()

        // An INDEPENDENT re-derivation straight off norm.work_item_worklogs + the live item set
        // (never off fact_task_delivery/fact_worklog themselves — the invariant sweep pattern,
        // `.claude/docs/testing.md`) — bypasses fact_task_delivery.actual_md's own decimal(10,2)
        // rounding, which would otherwise accumulate a spurious drift across ~1,200 issues.
        val liveItems = workItems().workItemsForDerivation(connId)
        val worklogsByIssue = workItems().worklogsByIssue(connId)
        fun secondsFor(issueId: Long) = worklogsByIssue[issueId].orEmpty().sumOf { it.timeSpentSeconds }
        val levelZeroTasks = liveItems.filter { !it.isSubtask && it.hierarchyLevel != 1 }
        val levelZeroActualMdSum = levelZeroTasks.sumOf { task ->
            val childSeconds = liveItems.filter { it.isSubtask && it.parentIssueId == task.issueId }.sumOf { secondsFor(it.issueId) }
            (secondsFor(task.issueId) + childSeconds) / 3600.0 / HOURS_PER_DAY
        }
        val epicIssueIds = liveItems.filter { it.hierarchyLevel == 1 }.map { it.issueId }.toSet()
        val epicsOwnMd = epicIssueIds.sumOf { epicId -> secondsFor(epicId) / 3600.0 / HOURS_PER_DAY }

        val factWorklogMdSum = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }
                .toList()
                .sumOf { it[MetricsStore.FactWorklog.md].toDouble() }
        }

        assertTrue(
            abs((levelZeroActualMdSum + epicsOwnMd) - factWorklogMdSum) < CAPACITY_TOLERANCE,
            "invariant 7: Σ level-0 fact_task_delivery.actual_md + epics' own worklogs must equal Σ fact_worklog.md " +
                "(level0=$levelZeroActualMdSum epicsOwn=$epicsOwnMd total=$factWorklogMdSum)",
        )
    }

    // ---- Commit 9d: the measure-contract corrections (A18/A19/A21) ----------------------------

    @Test
    fun `fact_task_delivery - active plus wait equals cycle, both within 0 and cycle, for every DONE task (A18)`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val rows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll()
                .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and MetricsStore.FactTaskDelivery.doneAt.isNotNull() }
                .toList()
        }
        assertTrue(rows.isNotEmpty(), "the golden connection must have at least one DONE task")
        rows.forEach { row ->
            val cycleMs = row[MetricsStore.FactTaskDelivery.cycleMs]!!
            val activeMs = row[MetricsStore.FactTaskDelivery.activeMs]
            val waitMs = row[MetricsStore.FactTaskDelivery.waitMs]
            val issueId = row[MetricsStore.FactTaskDelivery.issueId]
            assertEquals(cycleMs, activeMs + waitMs, "active + wait must equal cycle for issue $issueId")
            assertTrue(activeMs in 0..cycleMs, "active must stay within [0, cycle]")
            assertTrue(waitMs >= 0, "wait must never be negative")
        }
    }

    @Test
    fun `fact_task_delivery - current_team_id matches D5 evaluated now, via the open task_sprint or task_assignee bridge (A21)`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            val now = DerivedStubFixture.PINNED_NOW
            suspendTransaction(sharedDatabaseForTests()) {
                // The current sprint is re-derived from the SAME source production reads — the norm
                // SPRINT field interval containing `now` (its last sprint id), tiled and
                // non-overlapping by construction, ordered by `seq` — NOT `metrics.task_sprint`,
                // whose carried-over memberships overlap (both sprints stay open), which would make a
                // `firstOrNull` pick depend on row order. The assignee/membership reads below are
                // non-overlapping too (tiled intervals; the EXCLUDE constraint), so their
                // `.orderBy` only keeps the reads deterministic.
                val sprintRowsByIssue = workItems().fieldIntervalsByIssue(connId, TrackedField.SPRINT)
                    .mapValues { (_, intervals) ->
                        intervals.sortedBy { it.seq }.map { BridgeInterval(it.valueId?.toLongOrNull(), it.fromAtMs, it.toAtMs) }
                    }
                // A22: a CLOSED sprint (Jira `state == "closed"`, or `complete_at <= now`) is never an
                // open item's CURRENT sprint — the independent re-derivation must apply the same
                // exclusion `MetricsDeriver.isSprintClosed`/`currentAttribution` do, or it would
                // spuriously disagree wherever the golden dataset's own task_sprint bridge leaves a
                // task's open membership interval pointing at an already-closed sprint (a task added
                // to a sprint and never moved to a later one once that sprint itself closed).
                val sprintTeamById = MetricsStore.DimSprint.selectAll().where { MetricsStore.DimSprint.connectionId eq connId }
                    .toList().associate {
                        val closed = it[MetricsStore.DimSprint.state].equals("closed", ignoreCase = true) ||
                            (it[MetricsStore.DimSprint.completeAt] != null && it[MetricsStore.DimSprint.completeAt]!! <= now)
                        it[MetricsStore.DimSprint.sprintId] to (it[MetricsStore.DimSprint.teamId]?.value.takeIf { !closed })
                    }
                val assigneeRowsByIssue = MetricsStore.TaskAssignee.selectAll()
                    .where { MetricsStore.TaskAssignee.connectionId eq connId }
                    .orderBy(MetricsStore.TaskAssignee.validFrom to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[MetricsStore.TaskAssignee.issueId] }) {
                        BridgeInterval(
                            it[MetricsStore.TaskAssignee.accountId],
                            it[MetricsStore.TaskAssignee.validFrom],
                            it[MetricsStore.TaskAssignee.validTo],
                        )
                    }
                val membership = TeamMembershipService.TeamMembership
                val membershipRowsByAccount = membership.selectAll()
                    .orderBy(membership.validFrom to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[membership.accountId] }) {
                        BridgeInterval(it[membership.teamId].value, it[membership.validFrom], it[membership.validTo])
                    }

                // Level-0 only (D2): a sub-task carries no `task_sprint` bridge row of its own
                // (`.claude/docs/metrics.md` "Sprint scope, facts and snapshots") and would otherwise
                // spuriously disagree with this independent, bridge-only re-derivation.
                val rows = MetricsStore.FactTaskDelivery.selectAll()
                    .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.isSubtask eq false) }
                    .toList()
                assertTrue(rows.isNotEmpty())
                var checkedWithTeam = 0
                rows.forEach { row ->
                    val issueId = row[MetricsStore.FactTaskDelivery.issueId]
                    // "Current" is the bridge interval CONTAINING the DERIVE run's own clock
                    // (`valueAt`'s predicate, `metrics/MetricsDeriver.kt`) — NOT simply "the row
                    // with `valid_to IS NULL`": the sample dataset's own day2/incremental data
                    // carries changelog events dated AFTER `DerivedStubFixture.PINNED_NOW`, so an
                    // issue's LATEST-tiled (open) interval can start in what is, relative to the
                    // pinned clock, the future — the interval that actually contains `now` is an
                    // earlier, already-closed one. Re-deriving "current" any other way would
                    // spuriously disagree with production's own `now`-anchored read.
                    val expectedAssignee = valueAtBridge(assigneeRowsByIssue[issueId].orEmpty(), now)
                    assertEquals(
                        expectedAssignee,
                        row[MetricsStore.FactTaskDelivery.currentAssigneeAccountId],
                        "current_assignee_account_id must equal the task_assignee interval containing now for issue $issueId",
                    )
                    val sprintTeam = valueAtBridge(sprintRowsByIssue[issueId].orEmpty(), now)?.let { sprintTeamById[it] }
                    val expectedTeam = sprintTeam ?: expectedAssignee?.let { accountId ->
                        valueAtBridge(membershipRowsByAccount[accountId].orEmpty(), now)
                    }
                    assertEquals(
                        expectedTeam,
                        row[MetricsStore.FactTaskDelivery.currentTeamId]?.value,
                        "current_team_id must equal D5 evaluated now for issue $issueId",
                    )
                    if (expectedTeam != null) checkedWithTeam++
                }
                assertTrue(checkedWithTeam > 0, "at least one task must resolve a current team, or this sweep proves nothing")
            }
            Unit
        }

    @Test
    fun `fact_worklog - task_domain_key is never null (invariant 6 strengthened, commit 9d)`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val rows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }.toList()
        }
        assertTrue(rows.isNotEmpty())
        assertTrue(
            rows.none { it[MetricsStore.FactWorklog.taskDomainKey] == null },
            "every worklog must carry a domain — an epic-logged one gets the epic's own domain, never null",
        )
        // Where a worklog IS logged directly on an epic (epic_id == issue_id, by construction),
        // its task_domain_key must equal its own epic_domain_key.
        val epicLoggedRows = rows.filter { it[MetricsStore.FactWorklog.epicId] == it[MetricsStore.FactWorklog.issueId] }
        assertTrue(
            epicLoggedRows.isNotEmpty(),
            "at least one epic-logged worklog must exist in the fixture, or the assertion above proves nothing (review round 2c fix)",
        )
        epicLoggedRows.forEach { row ->
            assertEquals(row[MetricsStore.FactWorklog.epicDomainKey], row[MetricsStore.FactWorklog.taskDomainKey])
        }
    }

    @Test
    fun `fact_worklog - assignee_team_id_at_started, and foreign_work's task-vs-epic rule, match A21-A22`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            suspendTransaction(sharedDatabaseForTests()) {
                // Re-derived INDEPENDENTLY from the raw ASSIGNEE field intervals + team_membership —
                // never trusting the stored `assignee_team_id_at_started` column itself (review round
                // 2c fix). Read straight off `norm.work_item_field_intervals` rather than the
                // `metrics.task_assignee` bridge: that bridge is TASK-only (`MetricsDeriver.runPass2`
                // skips epics), and an epic-logged worklog still needs its own assignee-at-started —
                // `worklogRow` itself reads the SAME raw intervals, not the bridge, for exactly this
                // reason.
                // Explicit `.orderBy` (review round 2c fix, the same rationale as the current_team_id
                // sweep above): `.seq` is `norm.work_item_field_intervals`' own chronological order,
                // the SAME order `WorkItemStore.fieldIntervalsByIssue`'s `.sortedBy { it.seq }` gives
                // production's own in-memory list.
                val assigneeRowsByIssue = WorkItemStore.FieldIntervals.selectAll()
                    .where { (WorkItemStore.FieldIntervals.connectionId eq connId) and (WorkItemStore.FieldIntervals.field eq "ASSIGNEE") }
                    .orderBy(WorkItemStore.FieldIntervals.seq to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[WorkItemStore.FieldIntervals.issueId] }) {
                        BridgeInterval(
                            it[WorkItemStore.FieldIntervals.valueId],
                            it[WorkItemStore.FieldIntervals.fromAt],
                            it[WorkItemStore.FieldIntervals.toAt],
                        )
                    }
                val membership = TeamMembershipService.TeamMembership
                val membershipRowsByAccount = membership.selectAll()
                    .orderBy(membership.validFrom to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[membership.accountId] }) {
                        BridgeInterval(it[membership.teamId].value, it[membership.validFrom], it[membership.validTo])
                    }
                val ownerTeamByDomain = MetricsStore.DimDomain.selectAll().where { MetricsStore.DimDomain.connectionId eq connId }
                    .toList().associate { it[MetricsStore.DimDomain.domainKey] to it[MetricsStore.DimDomain.ownerTeamId]?.value }

                val rows = MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }.toList()
                assertTrue(rows.isNotEmpty())
                var checkedEpicLogged = 0
                rows.forEach { row ->
                    val worklogId = row[MetricsStore.FactWorklog.worklogId]
                    val issueId = row[MetricsStore.FactWorklog.issueId]
                    val startedAt = row[MetricsStore.FactWorklog.startedAt]
                    val expectedAssigneeAccountId = valueAtBridge(assigneeRowsByIssue[issueId].orEmpty(), startedAt)
                    val expectedAssigneeTeamId = expectedAssigneeAccountId
                        ?.let { accountId -> valueAtBridge(membershipRowsByAccount[accountId].orEmpty(), startedAt) }
                    assertEquals(
                        expectedAssigneeAccountId,
                        row[MetricsStore.FactWorklog.assigneeAccountIdAtStarted],
                        "assignee_account_id_at_started must equal the task_assignee interval containing started_at for worklog $worklogId",
                    )
                    assertEquals(
                        expectedAssigneeTeamId,
                        row[MetricsStore.FactWorklog.assigneeTeamIdAtStarted]?.value,
                        "assignee_team_id_at_started must equal the assignee's own team_membership at started_at for worklog $worklogId",
                    )

                    val authorTeamId = row[MetricsStore.FactWorklog.authorTeamId]?.value
                    // A worklog logged directly on an epic has epic_id == its own issue_id, by construction.
                    val isEpicLogged = row[MetricsStore.FactWorklog.epicId] == issueId
                    val expectedForeignWork = if (isEpicLogged) {
                        checkedEpicLogged++
                        // A22: epic-logged foreign work compares the author against the epic's OWN
                        // domain's owner team (persisted on dim_domain by this same DERIVE run) —
                        // never the epic's assignee's team.
                        val ownerTeamId = row[MetricsStore.FactWorklog.epicDomainKey]?.let { ownerTeamByDomain[it] }
                        authorTeamId != null && ownerTeamId != null && authorTeamId != ownerTeamId
                    } else {
                        val sprintTeamId = row[MetricsStore.FactWorklog.sprintTeamIdAtStarted]?.value
                        when {
                            authorTeamId == null -> false
                            sprintTeamId != null -> authorTeamId != sprintTeamId
                            else -> expectedAssigneeTeamId != null && authorTeamId != expectedAssigneeTeamId
                        }
                    }
                    assertEquals(
                        expectedForeignWork,
                        row[MetricsStore.FactWorklog.foreignWork],
                        "foreign_work must match A21/A22's rule for worklog $worklogId",
                    )
                }
                assertTrue(checkedEpicLogged > 0, "at least one epic-logged worklog must exist, or the owner-team branch proves nothing")
            }
            Unit
        }

    @Test
    fun `fact_epic_delivery - owner_team_id equals the FLO board's team for FLO epics (A19)`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        suspendTransaction(sharedDatabaseForTests()) {
            val floBoard = workItems().allBoardRefs(connId).single { it.boardId == FLO_BOARD_ID }
            assertEquals("FLO", floBoard.projectKey, "board 1 must map to the FLO project in the sample dataset")

            val boardTeamMap = MetricsConfigService.BoardTeamMap
            val floTeamId = boardTeamMap.selectAll()
                .where { (boardTeamMap.connectionId eq connId) and (boardTeamMap.boardId eq FLO_BOARD_ID) }
                .toList().single()[boardTeamMap.teamId].value

            val floEpicRows = MetricsStore.FactEpicDelivery
                .join(
                    MetricsStore.DimEpic,
                    JoinType.INNER,
                    onColumn = MetricsStore.FactEpicDelivery.issueId,
                    otherColumn = MetricsStore.DimEpic.issueId,
                    additionalConstraint = { MetricsStore.DimEpic.connectionId eq MetricsStore.FactEpicDelivery.connectionId },
                )
                .select(MetricsStore.FactEpicDelivery.issueId, MetricsStore.FactEpicDelivery.ownerTeamId)
                .where { (MetricsStore.FactEpicDelivery.connectionId eq connId) and (MetricsStore.DimEpic.domainKey eq "FLO") }
                .toList()
            assertTrue(floEpicRows.isNotEmpty(), "the FLO project must carry at least one epic")
            floEpicRows.forEach { row ->
                assertEquals(
                    floTeamId,
                    row[MetricsStore.FactEpicDelivery.ownerTeamId]?.value,
                    "a FLO epic's owner_team_id must equal the FLO board's own configured team",
                )
            }
        }
        Unit
    }

    /**
     * A19/A22's owner-resolution algorithm (`MetricsDeriver.ownerTeamByDomain`), on its own clone —
     * four cases in one DERIVE, over four of the fixture's own (real) boarded projects, remapped
     * into three synthetic domains:
     * - **`OWNERA`** (project A alone): a `metrics.domain_map.owner_team_id` set DIRECTLY (a raw
     *   write, not the PUT — see below) must OVERRIDE project A's own mapped board's team.
     * - **`OWNERB`** (projects B + C together): two boards mapped to two DIFFERENT teams, no
     *   configured owner for either project — must resolve to NO owner (disagreement/ambiguity).
     * - **`OWNERC`** (project D alone, deliberately left OUT of `boards[]`): a configured owner
     *   whose team is then SOFT-DELETED must resolve to NO owner (A22), not fall back to a board
     *   default that does not exist here either.
     * - Whatever `OWNERA` resolves to must also be exactly what `fact_epic_delivery.owner_team_id`
     *   carries for every one of project A's own epics (`dim_domain`/`fact_epic_delivery` agreement).
     */
    @Test
    fun `owner team resolution (A19, A22) - a configured override, a disagreeing board pair, and a soft-deleted configured team`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            val current = config.effectiveConfig(connId)

            val boardsByProject = workItems().allBoardRefs(connId).filter { it.projectKey != null }
                .groupBy { it.projectKey!! }
                .mapValues { (_, boards) -> boards.first().boardId }
            assertTrue(boardsByProject.size >= 3, "the fixture must map at least 3 distinct projects to boards for this test")
            val boardedProjects = boardsByProject.keys.toList()
            val projectA = boardedProjects[0]
            val projectB = boardedProjects[1]
            val projectC = boardedProjects[2]
            val allProjects = workItems().distinctProjectKeys(connId)
            val projectD = allProjects.firstOrNull { it !in setOf(projectA, projectB, projectC) }
                ?: boardedProjects.getOrElse(3) { projectA }

            val teamA = TestTeams.seed(uniqueEmail("owner-board-a"))
            val teamB = TestTeams.seed(uniqueEmail("owner-board-b"))
            val teamC = TestTeams.seed(uniqueEmail("owner-board-c"))
            val teamOverride = TestTeams.seed(uniqueEmail("owner-override"))
            val teamSoftDeleted = TestTeams.seed(uniqueEmail("owner-soft-deleted"))

            val domains = current.domains.map { mapping ->
                when (mapping.projectKey) {
                    projectA -> mapping.copy(domainKey = "OWNERA", domainName = "OWNERA")
                    projectB -> mapping.copy(domainKey = "OWNERB", domainName = "OWNERB")
                    projectC -> mapping.copy(domainKey = "OWNERB", domainName = "OWNERB")
                    projectD -> mapping.copy(domainKey = "OWNERC", domainName = "OWNERC")
                    else -> mapping
                }
            }
            val boards = listOf(
                MetricsBoardTeamMapping(boardsByProject.getValue(projectA), teamA),
                MetricsBoardTeamMapping(boardsByProject.getValue(projectB), teamB),
                MetricsBoardTeamMapping(boardsByProject.getValue(projectC), teamC),
                // projectD's own board (if it has one) is deliberately NOT mapped here — case (c)
                // needs no board fallback available at all.
            )
            config.replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields,
                    domains = domains,
                    boards = boards,
                    activityTypes = current.activityTypes,
                    workCategories = current.workCategories,
                    blockedStatuses = current.blockedStatuses,
                    sprintCapacities = current.sprintCapacities,
                ),
            )

            // Written directly rather than through the now-existing PUT (v0.3.0 M3 commit 9e,
            // `.claude/docs/metrics.md` "Configuration model"): a soft-deleted team id would be
            // rejected by the PUT's own `activeTeamIds` validation, so simulating "a team that WAS
            // active when configured, then soft-deleted" needs a raw write, same as `teamOverride`
            // here for symmetry.
            suspendTransaction(sharedDatabaseForTests()) {
                val dm = MetricsConfigService.DomainMap
                dm.update({ (dm.connectionId eq connId) and (dm.projectKey eq projectA) }) { it[ownerTeamId] = teamOverride }
                dm.update({ (dm.connectionId eq connId) and (dm.projectKey eq projectD) }) { it[ownerTeamId] = teamSoftDeleted }
            }
            TestTeams.service.delete(teamSoftDeleted)

            deriver(config).derive(SyncJobRunContext(deriveClaim(90u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val domainRows = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DimDomain.selectAll().where { MetricsStore.DimDomain.connectionId eq connId }
                    .toList().associateBy { it[MetricsStore.DimDomain.domainKey] }
            }
            assertEquals(
                teamOverride,
                domainRows.getValue("OWNERA")[MetricsStore.DimDomain.ownerTeamId]?.value,
                "a directly-configured owner must OVERRIDE project A's own mapped board's team",
            )
            assertNull(
                domainRows.getValue("OWNERB")[MetricsStore.DimDomain.ownerTeamId]?.value,
                "two boards on one domain mapped to two DIFFERENT teams, with no configured owner, must resolve to NO owner",
            )
            assertNull(
                domainRows.getValue("OWNERC")[MetricsStore.DimDomain.ownerTeamId]?.value,
                "a soft-deleted configured owner team must resolve to NO owner (A22), with no board fallback available either",
            )

            val epicsInA = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactEpicDelivery
                    .join(
                        MetricsStore.DimEpic,
                        JoinType.INNER,
                        onColumn = MetricsStore.FactEpicDelivery.issueId,
                        otherColumn = MetricsStore.DimEpic.issueId,
                        additionalConstraint = { MetricsStore.DimEpic.connectionId eq MetricsStore.FactEpicDelivery.connectionId },
                    )
                    .select(MetricsStore.FactEpicDelivery.ownerTeamId)
                    .where { (MetricsStore.FactEpicDelivery.connectionId eq connId) and (MetricsStore.DimEpic.domainKey eq "OWNERA") }
                    .toList()
            }
            assertTrue(
                epicsInA.isNotEmpty(),
                "project A must carry at least one epic, or the dim_domain/fact_epic_delivery agreement proves nothing",
            )
            epicsInA.forEach { row ->
                assertEquals(
                    teamOverride,
                    row[MetricsStore.FactEpicDelivery.ownerTeamId]?.value,
                    "fact_epic_delivery.owner_team_id must equal dim_domain's own resolved owner for the SAME domain",
                )
            }
        }

    @Test
    fun `fact_task_delivery - domain is AS-WAS at done_at, not the current project (A21)`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        deriver(config).derive(SyncJobRunContext(deriveClaim(70u, connId), clock = { PINNED_NOW }) { _, _ -> true })

        val target = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll()
                .where {
                    (MetricsStore.FactTaskDelivery.connectionId eq connId) and
                        (MetricsStore.FactTaskDelivery.domainKey eq "OPS") and
                        MetricsStore.FactTaskDelivery.doneAt.isNotNull()
                }
                .toList().first()
        }
        val issueId = target[MetricsStore.FactTaskDelivery.issueId]
        val doneAt = target[MetricsStore.FactTaskDelivery.doneAt]!!
        val originalKey = target[MetricsStore.FactTaskDelivery.issueKey]
        val newKey = "FLO-" + (900_000_000L + issueId)

        // The sample dataset has no real cross-project move to exercise this on — simulate one: a
        // fake `issuekey` field change AFTER done_at (`DeriveKernels.projectKeyTimeline`'s own
        // source) plus the CURRENT `norm.work_items` row's own issue_key/project_key updated
        // directly, the exact shape a real project move leaves.
        suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.FieldChanges.insert {
                it[WorkItemStore.FieldChanges.connectionId] = connId
                it[WorkItemStore.FieldChanges.issueId] = issueId
                it[WorkItemStore.FieldChanges.seq] = 999
                it[WorkItemStore.FieldChanges.field] = "Key"
                it[WorkItemStore.FieldChanges.fieldId] = "issuekey"
                it[WorkItemStore.FieldChanges.changedAt] = doneAt + THIRTY_DAYS_MS
                it[WorkItemStore.FieldChanges.fromText] = originalKey
                it[WorkItemStore.FieldChanges.toText] = newKey
            }
            val predicate = (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.issueId eq issueId)
            WorkItemStore.WorkItems.update({ predicate }) {
                it[issueKey] = newKey
                it[projectKey] = "FLO"
            }
        }

        deriver(config).derive(SyncJobRunContext(deriveClaim(71u, connId), clock = { PINNED_NOW }) { _, _ -> true })

        val after = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll()
                .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq issueId) }
                .toList().single()
        }
        assertEquals(
            "OPS",
            after[MetricsStore.FactTaskDelivery.domainKey],
            "a task moved to a new project AFTER its own done_at must keep its done-time domain (A21, as-was)",
        )
        assertEquals(newKey, after[MetricsStore.FactTaskDelivery.issueKey], "the row's own issueKey still tracks the CURRENT (moved) value")
    }

    @Test
    fun `fact_task_delivery - epic is AS-WAS at done_at, a genuinely null covering value is never flattened into the current epic`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            deriver(config).derive(SyncJobRunContext(deriveClaim(80u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val target = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactTaskDelivery.selectAll()
                    .where {
                        (MetricsStore.FactTaskDelivery.connectionId eq connId) and MetricsStore.FactTaskDelivery.doneAt.isNotNull() and
                            (MetricsStore.FactTaskDelivery.isSubtask eq false)
                    }
                    .toList().first()
            }
            val issueId = target[MetricsStore.FactTaskDelivery.issueId]
            val createdAt = target[MetricsStore.FactTaskDelivery.createdAt]
            val doneAt = target[MetricsStore.FactTaskDelivery.doneAt]!!
            val taskDomainKey = target[MetricsStore.FactTaskDelivery.domainKey]
            val epicIssueId = metricsDerivationGoldenEpic.issueId.toLong()
            val epicChangedAt = doneAt + THIRTY_DAYS_MS

            // The sample dataset has no task whose parent history reads "no epic, then an epic
            // assigned only AFTER done_at" — simulate it directly on `norm.work_item_field_intervals`
            // (what `MetricsDeriver.taskEpicHistory` actually replays, unlike the domain test above,
            // which mutates raw `work_item_field_changes`): ONE interval covering `done_at` with a
            // genuinely NULL `value_id` (no epic at that instant), then a SECOND interval opening
            // strictly AFTER `done_at` naming the golden epic.
            suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.FieldIntervals.deleteWhere {
                    (WorkItemStore.FieldIntervals.connectionId eq connId) and (WorkItemStore.FieldIntervals.issueId eq issueId) and
                        (WorkItemStore.FieldIntervals.field eq TrackedField.PARENT.name)
                }
                WorkItemStore.FieldIntervals.insert {
                    it[WorkItemStore.FieldIntervals.connectionId] = connId
                    it[WorkItemStore.FieldIntervals.issueId] = issueId
                    it[WorkItemStore.FieldIntervals.field] = TrackedField.PARENT.name
                    it[WorkItemStore.FieldIntervals.seq] = 1
                    it[WorkItemStore.FieldIntervals.valueId] = null
                    it[WorkItemStore.FieldIntervals.valueText] = null
                    it[WorkItemStore.FieldIntervals.fromAt] = createdAt
                    it[WorkItemStore.FieldIntervals.toAt] = epicChangedAt
                }
                WorkItemStore.FieldIntervals.insert {
                    it[WorkItemStore.FieldIntervals.connectionId] = connId
                    it[WorkItemStore.FieldIntervals.issueId] = issueId
                    it[WorkItemStore.FieldIntervals.field] = TrackedField.PARENT.name
                    it[WorkItemStore.FieldIntervals.seq] = 2
                    it[WorkItemStore.FieldIntervals.valueId] = epicIssueId.toString()
                    it[WorkItemStore.FieldIntervals.valueText] = metricsDerivationGoldenEpic.issueKey
                    it[WorkItemStore.FieldIntervals.fromAt] = epicChangedAt
                    it[WorkItemStore.FieldIntervals.toAt] = null
                }
                val predicate = (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.issueId eq issueId)
                WorkItemStore.WorkItems.update({ predicate }) { it[parentIssueId] = epicIssueId }
            }

            deriver(config).derive(SyncJobRunContext(deriveClaim(81u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val after = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactTaskDelivery.selectAll()
                    .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq issueId) }
                    .toList().single()
            }
            assertNull(
                after[MetricsStore.FactTaskDelivery.epicId],
                "the covering task_epic interval at done_at genuinely has no epic — the fix must NOT fall back to the current epic",
            )
            assertNull(after[MetricsStore.FactTaskDelivery.epicDomainKey], "no epic at done_at means no epic domain either")
            assertEquals(
                false,
                after[MetricsStore.FactTaskDelivery.crossDomain],
                "cross_domain requires a non-null epic_domain_key — never true with no epic",
            )
            // The task's own domain is untouched by this simulation (only PARENT history changed).
            assertEquals(taskDomainKey, after[MetricsStore.FactTaskDelivery.domainKey])
            // And the CURRENT epic (read via dim_task.epic_id, the one-indirection "now" view) IS the
            // golden epic — proving the as-was fix is genuinely about `done_at`, not a plumbing miss.
            val dimTask = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.DimTask.selectAll()
                    .where { (MetricsStore.DimTask.connectionId eq connId) and (MetricsStore.DimTask.issueId eq issueId) }
                    .toList().single()
            }
            assertEquals(
                epicIssueId,
                dimTask[MetricsStore.DimTask.epicId],
                "dim_task.epic_id is the CURRENT epic, unaffected by the as-was fix",
            )
        }

    @Test
    fun `fact_task_delivery - current_team_id ignores a CLOSED current sprint, falling back to the assignee's team (A22)`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val sprintTeamId = mapFloBoardToTeam(connId, config)
        val closedSprint = workItems().allSprintRefs(connId)
            .first { it.boardId == FLO_BOARD_ID && it.state.equals("closed", ignoreCase = true) }

        val peopleConnId = SyncedStubFixture.createConnection(namePrefix = "current-team-people")
        val assigneeAccountId = "current-team-assignee-${UUID.randomUUID()}"
        workItems().replacePeople(peopleConnId, listOf(PersonRef(assigneeAccountId, "Current Team Assignee", null, active = true)))
        val teamAssignee = TestTeams.seed(uniqueEmail("current-team-assignee"))
        val membershipService = teamMembership(config)
        val membership =
            membershipService.create(teamAssignee, TeamMembershipCreateRequest(assigneeAccountId, PINNED_NOW - THIRTY_DAYS_MS, null))
        try {
            val target = suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.WorkItems.selectAll()
                    .where {
                        (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.projectKey eq "FLO") and
                            (WorkItemStore.WorkItems.isSubtask eq false) and (WorkItemStore.WorkItems.issueType neq "Epic")
                    }
                    .limit(1).toList().single()
            }
            val issueId = target[WorkItemStore.WorkItems.issueId]
            val createdAt = target[WorkItemStore.WorkItems.createdAt]

            // Replace the task's SPRINT/ASSIGNEE bridge history: a SINGLE open interval each, so the
            // task is "currently" (as-of now) a member of an ALREADY-CLOSED sprint, and "currently"
            // assigned to our own synthetic account.
            suspendTransaction(sharedDatabaseForTests()) {
                listOf(TrackedField.SPRINT, TrackedField.ASSIGNEE).forEach { field ->
                    WorkItemStore.FieldIntervals.deleteWhere {
                        (WorkItemStore.FieldIntervals.connectionId eq connId) and (WorkItemStore.FieldIntervals.issueId eq issueId) and
                            (WorkItemStore.FieldIntervals.field eq field.name)
                    }
                }
                WorkItemStore.FieldIntervals.insert {
                    it[WorkItemStore.FieldIntervals.connectionId] = connId
                    it[WorkItemStore.FieldIntervals.issueId] = issueId
                    it[WorkItemStore.FieldIntervals.field] = TrackedField.SPRINT.name
                    it[WorkItemStore.FieldIntervals.seq] = 1
                    it[WorkItemStore.FieldIntervals.valueId] = closedSprint.sprintId.toString()
                    it[WorkItemStore.FieldIntervals.valueText] = closedSprint.name
                    it[WorkItemStore.FieldIntervals.fromAt] = createdAt
                    it[WorkItemStore.FieldIntervals.toAt] = null
                }
                WorkItemStore.FieldIntervals.insert {
                    it[WorkItemStore.FieldIntervals.connectionId] = connId
                    it[WorkItemStore.FieldIntervals.issueId] = issueId
                    it[WorkItemStore.FieldIntervals.field] = TrackedField.ASSIGNEE.name
                    it[WorkItemStore.FieldIntervals.seq] = 1
                    it[WorkItemStore.FieldIntervals.valueId] = assigneeAccountId
                    it[WorkItemStore.FieldIntervals.valueText] = "Current Team Assignee"
                    it[WorkItemStore.FieldIntervals.fromAt] = createdAt
                    it[WorkItemStore.FieldIntervals.toAt] = null
                }
            }

            deriver(config).derive(SyncJobRunContext(deriveClaim(95u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            val after = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactTaskDelivery.selectAll()
                    .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq issueId) }
                    .toList().single()
            }
            assertEquals(
                assigneeAccountId,
                after[MetricsStore.FactTaskDelivery.currentAssigneeAccountId],
                "current_assignee_account_id must reflect the open task_assignee interval",
            )
            assertTrue(sprintTeamId != teamAssignee, "the two teams must genuinely differ for this test to prove anything")
            assertEquals(
                teamAssignee,
                after[MetricsStore.FactTaskDelivery.currentTeamId]?.value,
                "a CLOSED current sprint must never resolve current_team_id — it must fall back to the assignee's own team (A22)",
            )
        } finally {
            membershipService.delete(teamAssignee, membership.id)
        }
    }

    @Test
    fun `fact_worklog - foreign_work is true when author and assignee teams differ, with no sprint team known at started_at (A21)`() =
        runBlocking {
            val connId = clonedProcessedConnection()
            val config = metricsConfig()

            val peopleConnId = SyncedStubFixture.createConnection(namePrefix = "foreign-work-people")
            val authorAccountId = "foreign-work-author-${UUID.randomUUID()}"
            val assigneeAccountId = "foreign-work-assignee-${UUID.randomUUID()}"
            workItems().replacePeople(
                peopleConnId,
                listOf(
                    PersonRef(authorAccountId, "Foreign Work Author", null, active = true),
                    PersonRef(assigneeAccountId, "Foreign Work Assignee", null, active = true),
                ),
            )
            val teamAuthor = TestTeams.seed(uniqueEmail("foreign-work-author"))
            val teamAssignee = TestTeams.seed(uniqueEmail("foreign-work-assignee"))
            val membershipService = teamMembership(config)
            var membershipAuthor: TeamMembershipResponse? = null
            var membershipAssignee: TeamMembershipResponse? = null
            try {
                // OPS is Kanban (`.claude/docs/domain-model.md` D10, the D5-OPS test above's own
                // precedent) — none of its issues carry a SPRINT field interval, so
                // `sprint_team_id_at_started` is null by construction here, cleanly isolating the
                // assignee fallback (A21) from the sprint-team branch.
                val target = suspendTransaction(sharedDatabaseForTests()) {
                    WorkItemStore.WorkItems.selectAll()
                        .where {
                            (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.projectKey eq "OPS") and
                                (WorkItemStore.WorkItems.isSubtask eq false) and (WorkItemStore.WorkItems.issueType neq "Epic")
                        }
                        .limit(1).toList().single()
                }
                val issueId = target[WorkItemStore.WorkItems.issueId]
                val createdAt = target[WorkItemStore.WorkItems.createdAt]
                val startedAt = createdAt + THIRTY_DAYS_MS
                val worklogId = issueId * 1_000_000L + 1

                // Membership must cover the WORKLOG's own started_at (createdAt + 30d), not just
                // "now" — the task's own createdAt can sit up to a year before PINNED_NOW, so a
                // membership starting only 30 days before now would leave started_at uncovered.
                membershipAuthor = membershipService.create(teamAuthor, TeamMembershipCreateRequest(authorAccountId, createdAt, null))
                membershipAssignee = membershipService.create(teamAssignee, TeamMembershipCreateRequest(assigneeAccountId, createdAt, null))

                suspendTransaction(sharedDatabaseForTests()) {
                    WorkItemStore.FieldIntervals.deleteWhere {
                        (WorkItemStore.FieldIntervals.connectionId eq connId) and (WorkItemStore.FieldIntervals.issueId eq issueId) and
                            (WorkItemStore.FieldIntervals.field eq TrackedField.ASSIGNEE.name)
                    }
                    WorkItemStore.FieldIntervals.insert {
                        it[WorkItemStore.FieldIntervals.connectionId] = connId
                        it[WorkItemStore.FieldIntervals.issueId] = issueId
                        it[WorkItemStore.FieldIntervals.field] = TrackedField.ASSIGNEE.name
                        it[WorkItemStore.FieldIntervals.seq] = 1
                        it[WorkItemStore.FieldIntervals.valueId] = assigneeAccountId
                        it[WorkItemStore.FieldIntervals.valueText] = "Foreign Work Assignee"
                        it[WorkItemStore.FieldIntervals.fromAt] = createdAt
                        it[WorkItemStore.FieldIntervals.toAt] = null
                    }
                    WorkItemStore.Worklogs.insert {
                        it[WorkItemStore.Worklogs.connectionId] = connId
                        it[WorkItemStore.Worklogs.worklogId] = worklogId
                        it[WorkItemStore.Worklogs.issueId] = issueId
                        it[WorkItemStore.Worklogs.authorAccountId] = authorAccountId
                        it[WorkItemStore.Worklogs.startedAt] = startedAt
                        it[WorkItemStore.Worklogs.timeSpentSeconds] = 3600L
                        it[WorkItemStore.Worklogs.createdAt] = startedAt
                        it[WorkItemStore.Worklogs.updatedAt] = startedAt
                    }
                }

                deriver(config).derive(SyncJobRunContext(deriveClaim(96u, connId), clock = { PINNED_NOW }) { _, _ -> true })

                val row = suspendTransaction(sharedDatabaseForTests()) {
                    MetricsStore.FactWorklog.selectAll()
                        .where { (MetricsStore.FactWorklog.connectionId eq connId) and (MetricsStore.FactWorklog.worklogId eq worklogId) }
                        .toList().single()
                }
                assertNull(
                    row[MetricsStore.FactWorklog.sprintTeamIdAtStarted],
                    "OPS carries no sprint — sprint_team_id_at_started must be null",
                )
                assertEquals(teamAssignee, row[MetricsStore.FactWorklog.assigneeTeamIdAtStarted]?.value)
                assertEquals(teamAuthor, row[MetricsStore.FactWorklog.authorTeamId]?.value)
                assertTrue(
                    row[MetricsStore.FactWorklog.foreignWork],
                    "author team != assignee team, no sprint team known at started_at -> foreign_work must be true (A21)",
                )
            } finally {
                membershipAuthor?.let { membershipService.delete(teamAuthor, it.id) }
                membershipAssignee?.let { membershipService.delete(teamAssignee, it.id) }
            }
        }

    /** Every DISTINCT day carrying a TEAM/TASK `agg_daily_wip` row for [connId], ascending — the
     * population [sampleWipDays] samples from. */
    private suspend fun allWipDays(connId: UInt): List<String> = suspendTransaction(sharedDatabaseForTests()) {
        MetricsStore.AggDailyWip.select(MetricsStore.AggDailyWip.day)
            .where {
                (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.scopeKind eq "TEAM") and
                    (MetricsStore.AggDailyWip.itemKind eq "TASK")
            }
            .toList().map { it[MetricsStore.AggDailyWip.day] }.distinct().sorted()
    }

    /** [SAMPLED_WIP_DAY_COUNT] evenly-spaced days across [connId]'s own WIP range (first, last, and
     * evenly-spaced in between) — "spread across the fixture's range" per the plan. */
    private suspend fun sampleWipDays(connId: UInt): List<String> {
        val allDays = allWipDays(connId)
        if (allDays.isEmpty()) return emptyList()
        return (0 until SAMPLED_WIP_DAY_COUNT).map { allDays[(it * (allDays.size - 1)) / (SAMPLED_WIP_DAY_COUNT - 1)] }.distinct()
    }

    private suspend fun dimDateDayEndMs(days: List<String>): Map<String, Long> = suspendTransaction(sharedDatabaseForTests()) {
        MetricsStore.DimDate.selectAll().where { MetricsStore.DimDate.day inList days }
            .toList().associate { it[MetricsStore.DimDate.day] to it[MetricsStore.DimDate.dayEndMs] }
    }

    /** Σ `item_count` grouped by `stage`, for one `(scopeKind, day)` slice of TASK rows — the shared
     * "does this scope partition the per-stage count" read both [sampleWipDays]-driven tests use. */
    private suspend fun wipStageCounts(connId: UInt, scopeKind: String, day: String): Map<String, Int> =
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.AggDailyWip.selectAll().where {
                (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.scopeKind eq scopeKind) and
                    (MetricsStore.AggDailyWip.itemKind eq "TASK") and (MetricsStore.AggDailyWip.day eq day)
            }.toList().groupBy({ it[MetricsStore.AggDailyWip.stage] }) { it[MetricsStore.AggDailyWip.itemCount] }
                .mapValues { (_, counts) -> counts.sum() }
        }

    @Test
    fun `agg_daily_wip - TEAM (incl UNASSIGNED) and DOMAIN both sum to an independently re-derived per-stage TASK count`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            val days = sampleWipDays(connId)
            assertEquals(SAMPLED_WIP_DAY_COUNT, days.size, "the fixture must carry $SAMPLED_WIP_DAY_COUNT distinct WIP days to sample")
            val dayEndMsByDay = dimDateDayEndMs(days)

            suspendTransaction(sharedDatabaseForTests()) {
                val taskIds = MetricsStore.DimTask.selectAll()
                    .where { (MetricsStore.DimTask.connectionId eq connId) and (MetricsStore.DimTask.isSubtask eq false) }
                    .toList().map { it[MetricsStore.DimTask.issueId] }
                val stageRowsByIssue = MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                    .toList().groupBy({ it[MetricsStore.ItemStage.issueId] }) {
                        BridgeInterval(
                            it[MetricsStore.ItemStage.stage], it[MetricsStore.ItemStage.validFrom], it[MetricsStore.ItemStage.validTo],
                        )
                    }

                days.forEach { day ->
                    val dayEndMs = dayEndMsByDay.getValue(day)
                    val expected = taskIds.mapNotNull { valueAtDayEnd(stageRowsByIssue[it].orEmpty(), dayEndMs) }
                        .groupingBy { it }.eachCount()
                    assertEquals(expected, wipStageCounts(connId, "TEAM", day), "TEAM must partition the per-stage TASK count on $day")
                    assertEquals(expected, wipStageCounts(connId, "DOMAIN", day), "DOMAIN must partition the per-stage TASK count on $day")
                }
            }
            Unit
        }

    @Test
    fun `agg_daily_wip - the TEAM split on one sampled day matches an independent re-derivation from the SPRINT-ASSIGNEE bridges`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            val days = sampleWipDays(connId)
            assertTrue(days.isNotEmpty(), "the fixture must carry at least one WIP day to sample")
            val day = days[days.size / 2]
            val dayEndMs = dimDateDayEndMs(listOf(day)).getValue(day)

            suspendTransaction(sharedDatabaseForTests()) {
                val taskIds = MetricsStore.DimTask.selectAll()
                    .where { (MetricsStore.DimTask.connectionId eq connId) and (MetricsStore.DimTask.isSubtask eq false) }
                    .toList().map { it[MetricsStore.DimTask.issueId] }
                val stageRowsByIssue = MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                    .toList().groupBy({ it[MetricsStore.ItemStage.issueId] }) {
                        BridgeInterval(
                            it[MetricsStore.ItemStage.stage], it[MetricsStore.ItemStage.validFrom], it[MetricsStore.ItemStage.validTo],
                        )
                    }
                val coveredTaskIds = taskIds.filter { valueAtDayEnd(stageRowsByIssue[it].orEmpty(), dayEndMs) != null }

                // The norm SPRINT field interval covering the instant — NOT metrics.task_sprint, whose
                // carried-over rows overlap (WIP-report §12's own rule, `DeriveWipStep.kt`).
                val sprintRowsByIssue = workItems().fieldIntervalsByIssue(connId, TrackedField.SPRINT)
                    .mapValues { (_, intervals) ->
                        intervals.sortedBy { it.seq }.map { BridgeInterval(it.valueId?.toLongOrNull(), it.fromAtMs, it.toAtMs) }
                    }
                val sprintTeamAndCloseById = MetricsStore.DimSprint.selectAll().where { MetricsStore.DimSprint.connectionId eq connId }
                    .toList().associate { row ->
                        val team = row[MetricsStore.DimSprint.teamId]?.value
                        row[MetricsStore.DimSprint.sprintId] to (team to row[MetricsStore.DimSprint.completeAt])
                    }
                val assigneeRowsByIssue = MetricsStore.TaskAssignee.selectAll()
                    .where { MetricsStore.TaskAssignee.connectionId eq connId }
                    .orderBy(MetricsStore.TaskAssignee.validFrom to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[MetricsStore.TaskAssignee.issueId] }) {
                        BridgeInterval(
                            it[MetricsStore.TaskAssignee.accountId], it[MetricsStore.TaskAssignee.validFrom],
                            it[MetricsStore.TaskAssignee.validTo],
                        )
                    }
                val membership = TeamMembershipService.TeamMembership
                val membershipRowsByAccount = membership.selectAll()
                    .orderBy(membership.validFrom to SortOrder.ASC)
                    .toList()
                    .groupBy({ it[membership.accountId] }) {
                        BridgeInterval(it[membership.teamId].value, it[membership.validFrom], it[membership.validTo])
                    }

                val expected = coveredTaskIds.map { issueId ->
                    val sprintId = valueAtDayEnd(sprintRowsByIssue[issueId].orEmpty(), dayEndMs)
                    // Only while the sprint was not yet closed at the instant (complete_at IS NULL OR
                    // complete_at > instant) — the WIP contract's own rule, distinct from A22's
                    // NOW-evaluated `current_team_id`'s extra Jira-`state` check above.
                    val sprintTeam = sprintId?.let { id ->
                        sprintTeamAndCloseById[id]?.let { (team, completeAt) ->
                            team.takeIf { completeAt == null || completeAt > dayEndMs }
                        }
                    }
                    val assignee = valueAtDayEnd(assigneeRowsByIssue[issueId].orEmpty(), dayEndMs)
                    val fallbackTeam = assignee?.let { valueAtDayEnd(membershipRowsByAccount[it].orEmpty(), dayEndMs) }
                    (sprintTeam ?: fallbackTeam)?.toString() ?: "UNASSIGNED"
                }.groupingBy { it }.eachCount()

                val actual = MetricsStore.AggDailyWip.selectAll().where {
                    (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.scopeKind eq "TEAM") and
                        (MetricsStore.AggDailyWip.itemKind eq "TASK") and (MetricsStore.AggDailyWip.day eq day)
                }.toList().groupBy({ it[MetricsStore.AggDailyWip.scopeId] }) { it[MetricsStore.AggDailyWip.itemCount] }
                    .mapValues { (_, counts) -> counts.sum() }

                assertEquals(expected, actual, "the TEAM split on $day must match D5-as-was evaluated at that day's own end instant")
            }
            Unit
        }

    @Test
    fun `agg_daily_wip - excludes sub-tasks, has no rows before the connection's history begins, and its last day is PINNED_NOW's day`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            suspendTransaction(sharedDatabaseForTests()) {
                val lastDay = MetricsStore.AggDailyWip.select(MetricsStore.AggDailyWip.day)
                    .where {
                        (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.scopeKind eq "TEAM") and
                            (MetricsStore.AggDailyWip.itemKind eq "TASK")
                    }
                    .toList().map { it[MetricsStore.AggDailyWip.day] }.max()
                val dayEndMs = MetricsStore.DimDate.selectAll().where { MetricsStore.DimDate.day eq lastDay }
                    .toList().single()[MetricsStore.DimDate.dayEndMs]

                val stageRowsByIssue = MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                    .toList().groupBy({ it[MetricsStore.ItemStage.issueId] }) {
                        BridgeInterval(
                            it[MetricsStore.ItemStage.stage], it[MetricsStore.ItemStage.validFrom], it[MetricsStore.ItemStage.validTo],
                        )
                    }
                val allTaskRows = MetricsStore.DimTask.selectAll().where { MetricsStore.DimTask.connectionId eq connId }.toList()
                val nonSubtaskIds = allTaskRows.filterNot { it[MetricsStore.DimTask.isSubtask] }.map { it[MetricsStore.DimTask.issueId] }
                val subtaskIds = allTaskRows.filter { it[MetricsStore.DimTask.isSubtask] }.map { it[MetricsStore.DimTask.issueId] }
                fun coveredCount(ids: List<Long>) = ids.count { valueAtDayEnd(stageRowsByIssue[it].orEmpty(), dayEndMs) != null }
                val nonSubtaskCovered = coveredCount(nonSubtaskIds)
                assertTrue(coveredCount(subtaskIds) > 0, "the fixture must have covered sub-tasks on $lastDay, else this proves nothing")

                val actualTotal = MetricsStore.AggDailyWip.selectAll().where {
                    (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.scopeKind eq "TEAM") and
                        (MetricsStore.AggDailyWip.itemKind eq "TASK") and (MetricsStore.AggDailyWip.day eq lastDay)
                }.toList().sumOf { it[MetricsStore.AggDailyWip.itemCount] }
                assertEquals(nonSubtaskCovered, actualTotal, "TEAM's TASK total on $lastDay must equal the non-subtask covering count")

                // A day with no WIP has no rows: a day whose own end predates the connection's earliest
                // created_at (item_stage's own first interval always starts at created_at).
                val minCreatedAt = MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                    .toList().minOf { it[MetricsStore.ItemStage.validFrom] }
                val emptyDay = MetricsStore.DimDate.selectAll().where { MetricsStore.DimDate.dayEndMs lessEq minCreatedAt }
                    .orderBy(MetricsStore.DimDate.day to SortOrder.DESC).limit(1).toList().singleOrNull()
                    ?.get(MetricsStore.DimDate.day)
                assertNotNull(emptyDay, "dim_date must carry at least one day before the connection's earliest creation")
                val emptyDayRowCount = MetricsStore.AggDailyWip.selectAll()
                    .where { (MetricsStore.AggDailyWip.connectionId eq connId) and (MetricsStore.AggDailyWip.day eq emptyDay) }
                    .count()
                assertEquals(0L, emptyDayRowCount, "a day before the connection's history began must carry no agg_daily_wip rows")

                // The last day equals the day of PINNED_NOW in the derive run's own zone (Europe/Warsaw,
                // the metrics.settings default — DerivedStubFixture never overrides the time zone).
                val expectedLastDay =
                    WorkingCalendar(ZoneId.of("Europe/Warsaw"), emptySet(), emptySet()).dayOf(DerivedStubFixture.PINNED_NOW).toString()
                assertEquals(expectedLastDay, lastDay, "the last WIP day must be the day of PINNED_NOW in Europe/Warsaw")
            }
            Unit
        }

    @Test
    fun `agg_daily_flow - throughput sums per scope equal the level-0 done tasks of fact_task_delivery`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        suspendTransaction(sharedDatabaseForTests()) {
            val f = MetricsStore.FactTaskDelivery
            val done = f.selectAll()
                .where { (f.connectionId eq connId) and (f.isSubtask eq false) and f.doneAt.isNotNull() }
                .toList()
            assertTrue(done.isNotEmpty(), "the fixture must carry level-0 done tasks, else this proves nothing")
            fun md(rows: List<ResultRow>) = rows.fold(java.math.BigDecimal.ZERO) { acc, row ->
                acc + (row[f.estimateAtDoneMd] ?: java.math.BigDecimal.ZERO)
            }

            val flow = MetricsStore.AggDailyFlow
            val flowRows = flow.selectAll().where { flow.connectionId eq connId }.toList()
            fun sums(kind: String): Pair<Int, java.math.BigDecimal> {
                val rows = flowRows.filter { it[flow.scopeKind] == kind }
                return rows.sumOf { it[flow.throughputItems] } to rows.fold(java.math.BigDecimal.ZERO) { acc, row ->
                    acc + row[flow.throughputMd]
                }
            }
            fun assertSums(kind: String, expectedRows: List<ResultRow>) {
                val (items, mdSum) = sums(kind)
                assertEquals(expectedRows.size, items, "$kind throughput_items must sum to the level-0 done tasks it covers")
                assertEquals(0, md(expectedRows).setScale(2).compareTo(mdSum), "$kind throughput_md must sum to their estimate_at_done_md")
            }
            assertSums("TEAM", done)
            assertSums("DOMAIN", done.filter { it[f.domainKey] != null })
            assertSums("EPIC", done.filter { it[f.epicId] != null })
            assertTrue(
                flowRows.any { it[flow.scopeKind] == "TEAM" && it[flow.scopeId] == "UNASSIGNED" && it[flow.throughputItems] > 0 } ||
                    done.none { it[f.creditTeamId] == null },
                "delivery without a credit team must land on TEAM/UNASSIGNED",
            )

            // Per-day placement: on three sampled done-days, each domain's throughput_items equals the level-0
            // done tasks whose done_at falls in THAT dim_date day's [day_start_ms, day_end_ms).
            val domainFlowDays = flowRows.filter { it[flow.scopeKind] == "DOMAIN" && it[flow.throughputItems] > 0 }
                .map { it[flow.day] }.distinct().sorted()
            assertTrue(domainFlowDays.size >= 3, "the fixture needs at least three days with DOMAIN throughput to sample")
            val sampledDays = listOf(domainFlowDays.first(), domainFlowDays[domainFlowDays.size / 2], domainFlowDays.last())
            val bounds = MetricsStore.DimDate.selectAll().where { MetricsStore.DimDate.day inList sampledDays }
                .toList().associate {
                    it[MetricsStore.DimDate.day] to (it[MetricsStore.DimDate.dayStartMs] to it[MetricsStore.DimDate.dayEndMs])
                }
            sampledDays.forEach { day ->
                val (startMs, endMs) = bounds.getValue(day)
                val expected = done.filter { row -> row[f.domainKey] != null && row[f.doneAt]!! in startMs until endMs }
                    .groupingBy { it[f.domainKey]!! }.eachCount()
                val actual = flowRows.filter { it[flow.scopeKind] == "DOMAIN" && it[flow.day] == day && it[flow.throughputItems] > 0 }
                    .associate { it[flow.scopeId] to it[flow.throughputItems] }
                assertEquals(expected, actual, "DOMAIN throughput_items placement on $day")
            }
        }
        Unit
    }

    @Test
    fun `agg_daily_flow - backlog on sampled days matches an independent re-derivation from the bridges`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val days = sampleWipDays(connId)
        assertTrue(days.isNotEmpty(), "the fixture must carry WIP days to sample")
        val dayEndMsByDay = dimDateDayEndMs(days)

        suspendTransaction(sharedDatabaseForTests()) {
            val tasks = MetricsStore.DimTask.selectAll()
                .where { (MetricsStore.DimTask.connectionId eq connId) and (MetricsStore.DimTask.isSubtask eq false) }
                .toList()
            val stage = MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                .toList().groupBy({ it[MetricsStore.ItemStage.issueId] }) {
                    BridgeInterval(
                        it[MetricsStore.ItemStage.stage], it[MetricsStore.ItemStage.validFrom], it[MetricsStore.ItemStage.validTo],
                    )
                }
            val estimate = MetricsStore.ItemEstimate.selectAll().where { MetricsStore.ItemEstimate.connectionId eq connId }
                .toList().groupBy({ it[MetricsStore.ItemEstimate.issueId] }) {
                    BridgeInterval(
                        it[MetricsStore.ItemEstimate.estimateMd], it[MetricsStore.ItemEstimate.validFrom],
                        it[MetricsStore.ItemEstimate.validTo],
                    )
                }
            val taskDomain = MetricsStore.TaskDomain.selectAll().where { MetricsStore.TaskDomain.connectionId eq connId }
                .toList().groupBy({ it[MetricsStore.TaskDomain.issueId] }) {
                    BridgeInterval(
                        it[MetricsStore.TaskDomain.domainKey], it[MetricsStore.TaskDomain.validFrom],
                        it[MetricsStore.TaskDomain.validTo],
                    )
                }
            val taskEpic = MetricsStore.TaskEpic.selectAll().where { MetricsStore.TaskEpic.connectionId eq connId }
                .toList().groupBy({ it[MetricsStore.TaskEpic.issueId] }) {
                    BridgeInterval(it[MetricsStore.TaskEpic.epicId], it[MetricsStore.TaskEpic.validFrom], it[MetricsStore.TaskEpic.validTo])
                }
            val taskSprint = MetricsStore.TaskSprint.selectAll().where { MetricsStore.TaskSprint.connectionId eq connId }
                .toList().groupBy({ it[MetricsStore.TaskSprint.issueId] }) {
                    BridgeInterval(
                        it[MetricsStore.TaskSprint.sprintId], it[MetricsStore.TaskSprint.validFrom],
                        it[MetricsStore.TaskSprint.validTo],
                    )
                }
            val sprintStart = MetricsStore.DimSprint.selectAll().where { MetricsStore.DimSprint.connectionId eq connId }
                .toList().associate { it[MetricsStore.DimSprint.sprintId] to it[MetricsStore.DimSprint.startAt] }
            val ownerByDomain = MetricsStore.DimDomain.selectAll().where { MetricsStore.DimDomain.connectionId eq connId }
                .toList().associate { it[MetricsStore.DimDomain.domainKey] to it[MetricsStore.DimDomain.ownerTeamId]?.value }

            val flow = MetricsStore.AggDailyFlow
            var nonVacuous = false
            days.forEach { day ->
                val dayEndMs = dayEndMsByDay.getValue(day)
                // (domain, epic, md) of every task in the estimated backlog at this day's end (D9).
                val backlog = tasks.mapNotNull { task ->
                    val issueId = task[MetricsStore.DimTask.issueId]
                    if (valueAtDayEnd(stage[issueId].orEmpty(), dayEndMs) != "NOT_STARTED") return@mapNotNull null
                    val md = valueAtDayEnd(estimate[issueId].orEmpty(), dayEndMs)
                    if (md == null || md.signum() <= 0) return@mapNotNull null
                    val inStartedSprint = taskSprint[issueId].orEmpty().any { row ->
                        row.fromAtMs < dayEndMs && (row.toAtMs == null || row.toAtMs >= dayEndMs) &&
                            sprintStart[row.value]?.let { it < dayEndMs } == true
                    }
                    if (inStartedSprint) return@mapNotNull null
                    val domain = valueAtDayEnd(taskDomain[issueId].orEmpty(), dayEndMs) ?: task[MetricsStore.DimTask.domainKey]
                    Triple(domain, valueAtDayEnd(taskEpic[issueId].orEmpty(), dayEndMs), md)
                }
                if (backlog.isNotEmpty()) nonVacuous = true

                fun expectedBy(
                    key: (Triple<String?, Long?, java.math.BigDecimal>) -> String?,
                ): Map<String, Pair<Int, java.math.BigDecimal>> =
                    backlog.mapNotNull { row -> key(row)?.let { it to row.third } }
                        .groupBy({ it.first }) { it.second }
                        .mapValues { (_, mds) -> mds.size to mds.fold(java.math.BigDecimal.ZERO) { a, b -> a + b } }

                suspend fun actual(kind: String): Map<String, Pair<Int, java.math.BigDecimal>> =
                    flow.selectAll().where { (flow.connectionId eq connId) and (flow.scopeKind eq kind) and (flow.day eq day) }
                        .toList().filter { it[flow.backlogItems] > 0 }
                        .associate { it[flow.scopeId] to (it[flow.backlogItems] to it[flow.backlogMd]) }

                suspend fun assertScope(kind: String, expected: Map<String, Pair<Int, java.math.BigDecimal>>) {
                    val actual = actual(kind)
                    assertEquals(expected.keys, actual.keys, "$kind backlog scopes on $day")
                    expected.forEach { (scope, want) ->
                        val got = actual.getValue(scope)
                        assertEquals(want.first, got.first, "$kind/$scope backlog_items on $day")
                        assertEquals(0, want.second.setScale(2).compareTo(got.second), "$kind/$scope backlog_md on $day")
                    }
                }
                assertScope("DOMAIN", expectedBy { it.first })
                assertScope("TEAM", expectedBy { row -> row.first?.let { ownerByDomain[it] }?.toString() ?: "UNOWNED" })
                assertScope("EPIC", expectedBy { it.second?.toString() })
            }
            assertTrue(nonVacuous, "at least one sampled day must carry an estimated backlog, else this proves nothing")
            val ownedBacklogTeams = flow.selectAll().where {
                (flow.connectionId eq connId) and (flow.scopeKind eq "TEAM") and (flow.backlogItems greater 0)
            }.toList().map { it[flow.scopeId] }.filter { it != "UNOWNED" }.distinct()
            assertTrue(ownedBacklogTeams.isNotEmpty(), "at least one TEAM backlog scope must be a real owner team, not UNOWNED")
        }
        Unit
    }

    @Test
    fun `agg_daily_flow - invariant 9 estimated backlog never exceeds NOT_STARTED WIP for the same domain and day`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        suspendTransaction(sharedDatabaseForTests()) {
            val flow = MetricsStore.AggDailyFlow
            val wip = MetricsStore.AggDailyWip
            val backlogRows = flow.selectAll().where {
                (flow.connectionId eq connId) and (flow.scopeKind eq "DOMAIN") and (flow.backlogItems greater 0)
            }.toList()
            assertTrue(backlogRows.isNotEmpty(), "the fixture must carry DOMAIN backlog rows, else this proves nothing")
            val notStartedWip = wip.selectAll().where {
                (wip.connectionId eq connId) and (wip.scopeKind eq "DOMAIN") and (wip.itemKind eq "TASK") and
                    (wip.stage eq "NOT_STARTED")
            }.toList().groupBy({ it[wip.scopeId] to it[wip.day] }) { it[wip.itemCount] }
                .mapValues { (_, counts) -> counts.sum() }
            backlogRows.forEach { row ->
                val key = row[flow.scopeId] to row[flow.day]
                val notStarted = notStartedWip[key] ?: 0
                assertTrue(
                    row[flow.backlogItems] <= notStarted,
                    "backlog ${row[flow.backlogItems]} exceeds NOT_STARTED WIP $notStarted for domain/day $key",
                )
            }
        }
        Unit
    }

    private data class FlowKey(val kind: String, val scopeId: String, val day: String)

    private suspend fun flowRowsByKey(connId: UInt): Map<FlowKey, ResultRow> = suspendTransaction(sharedDatabaseForTests()) {
        val flow = MetricsStore.AggDailyFlow
        flow.selectAll().where { flow.connectionId eq connId }.toList()
            .associateBy { FlowKey(it[flow.scopeKind], it[flow.scopeId], it[flow.day]) }
    }

    private fun bd(value: java.math.BigDecimal?): java.math.BigDecimal = value ?: java.math.BigDecimal.ZERO

    @Test
    fun `agg_daily_flow - team PV and EV total the team's fact_sprint committed and delivered`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val rows = flowRowsByKey(connId).filterKeys { it.kind == "TEAM" }
        suspendTransaction(sharedDatabaseForTests()) {
            val ds = MetricsStore.DimSprint
            val fs = MetricsStore.FactSprint
            val dimBySprint = ds.selectAll().where { ds.connectionId eq connId }.toList().associateBy { it[ds.sprintId] }
            val sprints = fs.selectAll().where { fs.connectionId eq connId }.toList()
            fun teamOf(row: ResultRow) = dimBySprint.getValue(row[fs.sprintId])[ds.teamId]?.value?.toString()
            val flow = MetricsStore.AggDailyFlow
            fun total(team: String, column: org.jetbrains.exposed.v1.core.Column<java.math.BigDecimal>) =
                rows.filterKeys { it.scopeId == team }.values.fold(java.math.BigDecimal.ZERO) { acc, row -> acc + row[column] }

            val teams = sprints.mapNotNull(::teamOf).distinct()
            assertTrue(teams.isNotEmpty(), "the fixture must carry team-mapped sprints")
            var bothPositive = 0
            teams.forEach { team ->
                val mine = sprints.filter { teamOf(it) == team }
                val committed = mine.filter { dimBySprint.getValue(it[fs.sprintId])[ds.startAt] != null }
                    .fold(java.math.BigDecimal.ZERO) { acc, row -> acc + row[fs.committedMd] }
                val delivered = mine.fold(java.math.BigDecimal.ZERO) { acc, row -> acc + row[fs.deliveredMd] }
                val pv = total(team, flow.pvMd)
                val ev = total(team, flow.evMd)
                assertEquals(0, committed.compareTo(pv), "team $team: Σ pv_md $pv must equal Σ fact_sprint.committed_md $committed")
                assertEquals(0, delivered.compareTo(ev), "team $team: Σ ev_md $ev must equal Σ fact_sprint.delivered_md $delivered")
                if (pv.signum() > 0 && ev.signum() > 0) bothPositive++
            }
            assertTrue(bothPositive > 0, "at least one team must carry both PV and EV, else this proves nothing")
            // Per-day placement: on sampled days, each team's PV is the committed MD of its sprints STARTING that day and
            // its EV the done_in_sprint MD of tasks whose done_at falls on that day (dim_date [start, end) bounds).
            val scopeRows = MetricsStore.FactSprintScope.selectAll().where { MetricsStore.FactSprintScope.connectionId eq connId }
                .toList().filter { it[MetricsStore.FactSprintScope.doneInSprint] }
            val doneAtByIssue = MetricsStore.FactTaskDelivery.selectAll()
                .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and MetricsStore.FactTaskDelivery.doneAt.isNotNull() }
                .toList().associate { it[MetricsStore.FactTaskDelivery.issueId] to it[MetricsStore.FactTaskDelivery.doneAt]!! }
            fun sampledDays(column: org.jetbrains.exposed.v1.core.Column<java.math.BigDecimal>): List<String> {
                val days = rows.filter { it.value[column].signum() != 0 }.keys.map { it.day }.distinct().sorted()
                assertTrue(days.size >= 3, "the fixture needs at least three days with team ${column.name} to sample")
                return listOf(days.first(), days[days.size / 2], days.last())
            }
            val pvDays = sampledDays(flow.pvMd)
            val evDays = sampledDays(flow.evMd)
            val dayBounds = MetricsStore.DimDate.selectAll().where { MetricsStore.DimDate.day inList (pvDays + evDays) }.toList()
                .associate {
                    it[MetricsStore.DimDate.day] to (it[MetricsStore.DimDate.dayStartMs] to it[MetricsStore.DimDate.dayEndMs])
                }
            pvDays.forEach { day ->
                val (startMs, endMs) = dayBounds.getValue(day)
                val expected = sprints.filter { row ->
                    val dim = dimBySprint.getValue(row[fs.sprintId])
                    dim[ds.teamId] != null && dim[ds.startAt]?.let { it in startMs until endMs } == true
                }.groupBy { teamOf(it)!! }.mapValues { (_, v) -> v.fold(java.math.BigDecimal.ZERO) { a, b -> a + b[fs.committedMd] } }
                    .filterValues { it.signum() != 0 }
                val actual = rows.filter { it.key.day == day && it.value[flow.pvMd].signum() != 0 }
                    .mapKeys { it.key.scopeId }.mapValues { it.value[flow.pvMd] }
                assertEquals(expected.keys, actual.keys, "team PV scopes on $day")
                expected.forEach { (team, want) -> assertEquals(0, want.compareTo(actual.getValue(team)), "team $team PV on $day") }
            }
            evDays.forEach { day ->
                val (startMs, endMs) = dayBounds.getValue(day)
                val expected = scopeRows.filter { row ->
                    dimBySprint.getValue(row[MetricsStore.FactSprintScope.sprintId])[ds.teamId] != null &&
                        doneAtByIssue[row[MetricsStore.FactSprintScope.issueId]]?.let { it in startMs until endMs } == true
                }.groupBy { dimBySprint.getValue(it[MetricsStore.FactSprintScope.sprintId])[ds.teamId]!!.value.toString() }
                    .mapValues { (_, v) ->
                        v.fold(java.math.BigDecimal.ZERO) { a, b -> a + bd(b[MetricsStore.FactSprintScope.estimateAtDoneMd]) }
                    }
                    .filterValues { it.signum() != 0 }
                val actual = rows.filter { it.key.day == day && it.value[flow.evMd].signum() != 0 }
                    .mapKeys { it.key.scopeId }.mapValues { it.value[flow.evMd] }
                assertEquals(expected.keys, actual.keys, "team EV scopes on $day")
                expected.forEach { (team, want) -> assertEquals(0, want.compareTo(actual.getValue(team)), "team $team EV on $day") }
            }
            // A sprint with no team contributes to no team scope: every PV/EV TEAM scope is a mapped sprint team.
            assertTrue(
                rows.filterValues { it[flow.pvMd].signum() != 0 }.keys.all { it.scopeId in teams },
                "PV TEAM rows must belong to teams that own a sprint",
            )
        }
        Unit
    }

    @Test
    fun `agg_daily_flow - epic PV matches pvCurve of the current baseline day by day`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val flowRows = flowRowsByKey(connId)
        val calendar = WorkingCalendar(ZoneId.of("Europe/Warsaw"), setOf(6, 7), emptySet())
        suspendTransaction(sharedDatabaseForTests()) {
            val plan = MetricsStore.FactEpicPlan
            val flow = MetricsStore.AggDailyFlow
            // The oracle iterates the SAME set the PV SQL reads (A23): current baselines with start, due and budget all
            // set AND both dates inside the PV horizon (an out-of-horizon epic gets no PV at all, never a clamped curve).
            val current = plan.selectAll().where { (plan.connectionId eq connId) and plan.supersededAt.isNull() }.toList()
                .filter { it[plan.startAt] != null && it[plan.dueAt] != null && it[plan.budgetMd] != null }
                .filter { DeriveKernels.inPvHorizon(it[plan.startAt]!!, it[plan.dueAt]!!, DerivedStubFixture.PINNED_NOW) }
            val dimDates = MetricsStore.DimDate.selectAll().toList().map { it[MetricsStore.DimDate.day] }.toSet()
            var withCurve = 0
            current.forEach { row ->
                val epicId = row[plan.issueId].toString()
                val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
                    baselinedAtMs = row[plan.baselinedAt], startAtMs = row[plan.startAt]!!, dueAtMs = row[plan.dueAt]!!,
                    budgetMd = row[plan.budgetMd]!!.toDouble(), budgetSource = row[plan.budgetSource], supersededAtMs = null,
                )
                val curve = DeriveKernels.pvCurve(baseline, calendar)
                assertTrue(curve.all { it.day.toString() in dimDates }, "dim_date must cover every working day of epic $epicId's window")
                val actual = flowRows.filterKeys { it.kind == "EPIC" && it.scopeId == epicId }
                    .mapKeys { it.key.day }.mapValues { it.value[flow.pvMd] }.filterValues { it.signum() != 0 }
                if (curve.isEmpty() || baseline.budgetMd == 0.0) {
                    assertTrue(actual.isEmpty(), "epic $epicId has no PV curve, so it must have no PV rows")
                    return@forEach
                }
                withCurve++
                val expectedCumulative = curve.associate { it.day.toString() to it.cumulativeMd }
                assertTrue(actual.keys.all { it in expectedCumulative }, "epic $epicId: PV only on working days of its window")
                var running = java.math.BigDecimal.ZERO
                curve.forEach { point ->
                    running += actual[point.day.toString()] ?: java.math.BigDecimal.ZERO
                    assertTrue(
                        abs(running.toDouble() - point.cumulativeMd) <= PV_CUMULATIVE_TOLERANCE,
                        "epic $epicId on ${point.day}: cumulative pv_md $running vs pvCurve ${point.cumulativeMd}",
                    )
                }
                assertEquals(0, row[plan.budgetMd]!!.compareTo(running), "epic $epicId: Σ pv_md must equal its budget exactly")
            }
            assertTrue(withCurve >= 1, "at least one epic must carry a PV curve, else this proves nothing")
            val epicsWithPv = flowRows.filter { it.key.kind == "EPIC" && it.value[flow.pvMd].signum() != 0 }.keys.map { it.scopeId }.toSet()
            val expectedEpicsWithPv = current.filter {
                it[plan.budgetMd]!!.signum() != 0 && DeriveKernels.pvCurve(
                    ch.nokillswit.metrics.EpicPlanBaseline(
                        it[plan.baselinedAt], it[plan.startAt]!!, it[plan.dueAt]!!, it[plan.budgetMd]!!.toDouble(),
                        it[plan.budgetSource], null,
                    ),
                    calendar,
                ).isNotEmpty()
            }.map { it[plan.issueId].toString() }.toSet()
            assertEquals(expectedEpicsWithPv, epicsWithPv, "EPIC PV scopes must be exactly the epics with a current in-horizon baseline")
        }
        Unit
    }

    @Test
    fun `agg_daily_flow - DOMAIN PV, EV and AC equal the sum of the domain's EPIC rows, and team AC totals fact_worklog`() =
        runBlocking {
            val connId = DerivedStubFixture.connectionId()
            val flowRows = flowRowsByKey(connId)
            suspendTransaction(sharedDatabaseForTests()) {
                val flow = MetricsStore.AggDailyFlow
                val epicDomainByDimEpic = MetricsStore.DimEpic.selectAll().where { MetricsStore.DimEpic.connectionId eq connId }
                    .toList().associate { it[MetricsStore.DimEpic.issueId].toString() to it[MetricsStore.DimEpic.domainKey] }
                val ft = MetricsStore.FactTaskDelivery
                val wl = MetricsStore.FactWorklog
                val doneTasks = ft.selectAll()
                    .where { (ft.connectionId eq connId) and (ft.isSubtask eq false) and ft.doneAt.isNotNull() and ft.epicId.isNotNull() }
                    .toList()
                val worklogs = wl.selectAll().where { wl.connectionId eq connId }.toList()
                val dayBounds = MetricsStore.DimDate.selectAll().toList()
                    .map { Triple(it[MetricsStore.DimDate.day], it[MetricsStore.DimDate.dayStartMs], it[MetricsStore.DimDate.dayEndMs]) }
                fun dayOf(ts: Long) = dayBounds.firstOrNull { ts >= it.second && ts < it.third }?.first
                    ?: error("timestamp $ts falls on no dim_date day — an event would be dropped from agg_daily_flow")

                // EPIC -> DOMAIN as the facts themselves carry it (epic_domain_key): EV and AC group by that key.
                val epicDomainByFacts = (
                    doneTasks.map { it[ft.epicId]!!.toString() to it[ft.epicDomainKey] } +
                        worklogs.filter { it[wl.epicId] != null }.map { it[wl.epicId]!!.toString() to it[wl.epicDomainKey] }
                    ).toMap()

                fun assertDomainEqualsEpics(
                    label: String,
                    column: org.jetbrains.exposed.v1.core.Column<java.math.BigDecimal>,
                    domainOf: (String) -> String?,
                    tolerancePerEpic: Double,
                ): Int {
                    val epicSums = flowRows.filterKeys { it.kind == "EPIC" }.entries
                        .filter { it.value[column].signum() != 0 }
                        .groupBy({ domainOf(it.key.scopeId) to it.key.day }) { it.value[column] }
                    val domainRows = flowRows.filterKeys { it.kind == "DOMAIN" }.entries.filter { it.value[column].signum() != 0 }
                        .associate { (it.key.scopeId to it.key.day) to it.value[column] }
                    val expectedKeys = epicSums.keys.filter { it.first != null }.map { it.first!! to it.second }.toSet()
                    assertEquals(expectedKeys, domainRows.keys, "$label: DOMAIN rows must exist exactly where the domain has EPIC rows")
                    domainRows.forEach { (key, value) ->
                        val parts = epicSums.getValue(key)
                        val sum = parts.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
                        assertTrue(
                            abs(sum.toDouble() - value.toDouble()) <= tolerancePerEpic * (parts.size + 1),
                            "$label DOMAIN ${key.first} on ${key.second}: $value vs Σ EPIC rows $sum",
                        )
                    }
                    return domainRows.size
                }
                val pvRows = assertDomainEqualsEpics("PV", flow.pvMd, { epicDomainByDimEpic[it] }, EXACT_TOLERANCE)
                val evRows = assertDomainEqualsEpics("EV", flow.evMd, { epicDomainByFacts[it] }, EXACT_TOLERANCE)
                val acRows = assertDomainEqualsEpics("AC", flow.acMd, { epicDomainByFacts[it] }, ROUNDING_TOLERANCE)
                assertTrue(pvRows > 0 && evRows > 0 && acRows > 0, "PV/EV/AC DOMAIN rows must all exist (pv=$pvRows ev=$evRows ac=$acRows)")

                // The fact-derived oracle for DOMAIN EV and AC — independent of the EPIC rows.
                val evByDomainDay = doneTasks.filter { it[ft.epicDomainKey] != null }
                    .groupBy({ it[ft.epicDomainKey]!! to dayOf(it[ft.doneAt]!!) }) { bd(it[ft.estimateAtDoneMd]) }
                    .mapValues { (_, v) -> v.fold(java.math.BigDecimal.ZERO) { a, b -> a + b } }.filterValues { it.signum() != 0 }
                val flowEv = flowRows.filterKeys { it.kind == "DOMAIN" }.entries.filter { it.value[flow.evMd].signum() != 0 }
                    .associate { (it.key.scopeId to it.key.day) to it.value[flow.evMd] }
                assertEquals(evByDomainDay.keys, flowEv.keys, "DOMAIN EV rows vs the done epic-attributed tasks")
                evByDomainDay.forEach { (key, want) -> assertEquals(0, want.compareTo(flowEv.getValue(key)), "DOMAIN EV $key") }

                val acByDomainDay = worklogs.filter { it[wl.epicId] != null && it[wl.epicDomainKey] != null }
                    .groupBy({ it[wl.epicDomainKey]!! to dayOf(it[wl.startedAt]) }) { it[wl.md] }
                    .mapValues { (_, v) -> v.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }.setScale(2, java.math.RoundingMode.HALF_UP) }
                    .filterValues { it.signum() != 0 }
                val flowAc = flowRows.filterKeys { it.kind == "DOMAIN" }.entries.filter { it.value[flow.acMd].signum() != 0 }
                    .associate { (it.key.scopeId to it.key.day) to it.value[flow.acMd] }
                assertEquals(acByDomainDay.keys, flowAc.keys, "DOMAIN AC rows vs the epic-attributed worklogs")
                acByDomainDay.forEach { (key, want) -> assertEquals(0, want.compareTo(flowAc.getValue(key)), "DOMAIN AC $key") }

                // Team AC (incl. UNASSIGNED) totals fact_worklog: per (team, day) exactly, and in total.
                val acByTeamDay = worklogs.groupBy { (it[wl.authorTeamId]?.value?.toString() ?: "UNASSIGNED") to dayOf(it[wl.startedAt]) }
                    .mapValues { (_, v) ->
                        v.fold(java.math.BigDecimal.ZERO) { a, b -> a + b[wl.md] }.setScale(2, java.math.RoundingMode.HALF_UP)
                    }
                    .filterValues { it.signum() != 0 }
                val flowTeamAc = flowRows.filterKeys { it.kind == "TEAM" }.entries.filter { it.value[flow.acMd].signum() != 0 }
                    .associate { (it.key.scopeId to it.key.day) to it.value[flow.acMd] }
                assertEquals(acByTeamDay.keys, flowTeamAc.keys, "TEAM AC rows vs fact_worklog by author team and day")
                acByTeamDay.forEach { (key, want) -> assertEquals(0, want.compareTo(flowTeamAc.getValue(key)), "TEAM AC $key") }
                // No worklog is dropped: every fact_worklog row matches a dim_date day (the join the AC SQL performs).
                val matchedWorklogs = wl.join(
                    MetricsStore.DimDate, JoinType.INNER,
                    additionalConstraint = {
                        (MetricsStore.DimDate.dayStartMs lessEq wl.startedAt) and (wl.startedAt less MetricsStore.DimDate.dayEndMs)
                    },
                ).selectAll().where { wl.connectionId eq connId }.count()
                assertEquals(worklogs.size.toLong(), matchedWorklogs, "every worklog must land on a dim_date day (none dropped from AC)")
                val totalWorklog = worklogs.fold(java.math.BigDecimal.ZERO) { a, b -> a + b[wl.md] }
                val totalTeamAc = flowTeamAc.values.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
                assertTrue(totalWorklog.signum() > 0, "the fixture must carry worklogs")
                assertTrue(
                    abs(totalWorklog.toDouble() - totalTeamAc.toDouble()) <= ROUNDING_TOLERANCE * flowTeamAc.size,
                    "Σ TEAM ac_md $totalTeamAc vs Σ fact_worklog.md $totalWorklog",
                )
            }
            Unit
        }

    private companion object {
        const val PINNED_NOW = 1_772_668_800_000L // 2026-03-05T00:00:00Z, per the v0.3.0 plan's pinned-clock convention
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        const val CONFIGURED_CAPACITY_MD = 42.0
        const val CAPACITY_TOLERANCE = 0.01
        const val HOURS_PER_DAY = 8.0
        const val FLO_BOARD_ID = 1L
        const val SAMPLED_WIP_DAY_COUNT = 5
        // Cumulative rounding keeps the running pv_md at round(budget*i/n, 2), i.e. within 0.005 of the exact
        // cumulative; the epsilon only absorbs pvCurve's floating-point accumulation.
        const val PV_CUMULATIVE_TOLERANCE = 0.005 + 1e-9
        const val EXACT_TOLERANCE = 0.0001
        const val ROUNDING_TOLERANCE = 0.005
    }
}
