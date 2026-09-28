package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.JiraProfileStream
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsBoardTeamMapping
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsSprintCapacity
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipCreateRequest
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
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import java.io.File
import java.security.MessageDigest
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
import org.jetbrains.exposed.v1.core.isNotNull
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

/**
 * `metrics/MetricsDeriver.kt` (v0.3.0 M3 commit 7): a full SYNC → PROCESS → DERIVE over a CLONE of
 * `SyncedStubFixture`'s shared connection (never the shared connection itself —
 * `.claude/docs/testing.md` "Shared synced fixture") with a pinned clock, asserting invariant 3
 * (status/stage intervals tile each item's lifetime) and invariant 4 (`started_at <= done_at`;
 * `done_at` set only while the current stage is DONE) as SQL sweeps over the PERSISTED
 * `metrics.item_stage`/`metrics.fact_task_delivery` rows. Runs with the computed DEFAULT
 * configuration (no admin metrics-config/team-membership setup) — `MetricsConfigService
 * .effectiveConfig` already falls back to safe defaults derived from `norm`/the data profile, so a
 * DERIVE run needs no prior admin configuration to succeed.
 */
class MetricsDerivationTest {
    private fun workItems() = SyncedStubFixture.workItems()
    private fun dataSources() = SyncedStubFixture.dataSources()
    private fun metricsConfig() =
        MetricsConfigService(sharedDatabaseForTests(), workItems(), dataSources(), SyncJobsService(sharedDatabaseForTests(), 3))
    private fun teamMembership(config: MetricsConfigService) = TeamMembershipService(sharedDatabaseForTests(), config)
    private fun metricsStore() = MetricsStore(sharedDatabaseForTests())

    /** MD5 over every persisted `fact_task_delivery` row PLUS every TASK bridge row (`task_epic`/
     * `task_domain`/`task_assignee`), ordered by issue id (bridges additionally by their own
     * `valid_from`, preserving each issue's own history order — NEVER by the surrogate `id` column
     * itself, which is a fresh `autoIncrement()` value on every DERIVE's delete+insert and would
     * make the digest spuriously differ across two otherwise-identical runs; the `id` column
     * itself is excluded from the hashed line for the same reason) — the reprocess-digest pattern
     * (`.claude/docs/testing.md` "The reprocess digest"), applied to invariant 12 ("every live
     * number is reproducible from `norm` + one configuration revision", review round 2a widens it
     * from `fact_task_delivery` alone to the bridges too, since those are now real history rather
     * than a single current-value row): a second DERIVE over UNCHANGED input, under the SAME pinned
     * clock, must write byte-for-byte identical rows. */
    private suspend fun factTaskDeliveryDigest(connId: UInt): String {
        val digest = MessageDigest.getInstance("MD5")
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }
                .orderBy(MetricsStore.FactTaskDelivery.issueId to SortOrder.ASC)
                .toList()
                .forEach { row ->
                    val line = MetricsStore.FactTaskDelivery.columns.joinToString("|") { column -> row[column].toString() }
                    digest.update((line + "\n").toByteArray())
                }
            MetricsStore.TaskEpic.selectAll().where { MetricsStore.TaskEpic.connectionId eq connId }
                .orderBy(MetricsStore.TaskEpic.issueId to SortOrder.ASC, MetricsStore.TaskEpic.validFrom to SortOrder.ASC)
                .toList()
                .forEach { row ->
                    val line = listOf(row[MetricsStore.TaskEpic.issueId], row[MetricsStore.TaskEpic.epicId],
                        row[MetricsStore.TaskEpic.validFrom], row[MetricsStore.TaskEpic.validTo]).joinToString("|")
                    digest.update((line + "\n").toByteArray())
                }
            MetricsStore.TaskDomain.selectAll().where { MetricsStore.TaskDomain.connectionId eq connId }
                .orderBy(MetricsStore.TaskDomain.issueId to SortOrder.ASC, MetricsStore.TaskDomain.validFrom to SortOrder.ASC)
                .toList()
                .forEach { row ->
                    val line = listOf(row[MetricsStore.TaskDomain.issueId], row[MetricsStore.TaskDomain.domainKey],
                        row[MetricsStore.TaskDomain.validFrom], row[MetricsStore.TaskDomain.validTo]).joinToString("|")
                    digest.update((line + "\n").toByteArray())
                }
            MetricsStore.TaskAssignee.selectAll().where { MetricsStore.TaskAssignee.connectionId eq connId }
                .orderBy(MetricsStore.TaskAssignee.issueId to SortOrder.ASC, MetricsStore.TaskAssignee.validFrom to SortOrder.ASC)
                .toList()
                .forEach { row ->
                    val line = listOf(row[MetricsStore.TaskAssignee.issueId], row[MetricsStore.TaskAssignee.accountId],
                        row[MetricsStore.TaskAssignee.validFrom], row[MetricsStore.TaskAssignee.validTo]).joinToString("|")
                    digest.update((line + "\n").toByteArray())
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun clonedProcessedConnection(): UInt {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-derive-clone")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        val store = SyncedStubFixture.rawStore()
        val items = workItems()
        store.markAllNeedsProcessing(connId)
        JiraProcessStream(store, items).run(SyncedStubFixture.freshContext(connId))
        // PROFILE, same as a real SYNC job's own PROCESS -> PROFILE order (.claude/docs/ingestion.md
        // "Job orders") — MetricsConfigService's computed DEFAULTS (estimateTask/estimateEpic/
        // epicStart) are detected off the STORED profile, so a DERIVE run against this clone's
        // computed defaults needs one, exactly like a real connection would have after its first sync.
        JiraProfileStream(store, items, dataSources()).run(SyncedStubFixture.freshContext(connId))
        return connId
    }

    @Test
    fun `DERIVE over a freshly processed connection writes non-empty fact_task_delivery satisfying invariants 3 and 4`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
        )
        val claim = SyncJobClaim(
            id = 1u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )

        deriver.derive(SyncJobRunContext(claim, clock = { PINNED_NOW }) { _, _ -> true })

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
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
        )
        val claim = SyncJobClaim(
            id = 2u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )
        deriver.derive(SyncJobRunContext(claim, clock = { PINNED_NOW }) { _, _ -> true })

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
    fun `invariant 2 — task_epic never lets a task belong to more than one epic at any instant`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
        )
        val claim = SyncJobClaim(
            id = 3u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )
        deriver.derive(SyncJobRunContext(claim, clock = { PINNED_NOW }) { _, _ -> true })

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
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
        )
        val claim = SyncJobClaim(
            id = 6u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )
        deriver.derive(SyncJobRunContext(claim, clock = { PINNED_NOW }) { _, _ -> true })

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
        val firstDigest = factTaskDeliveryDigest(connId)

        deriver.derive(SyncJobRunContext(claim(5u), clock = { PINNED_NOW }) { _, _ -> true })
        val secondDigest = factTaskDeliveryDigest(connId)

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
        val connId = SyncedStubFixture.createConnection(dataSources = ds, namePrefix = "jira-unmapped")
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
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        mapFloBoardToTeam(connId, config)
        deriver(config).derive(SyncJobRunContext(deriveClaim(10u, connId), clock = { PINNED_NOW }) { _, _ -> true })

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
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        mapFloBoardToTeam(connId, config)
        deriver(config).derive(SyncJobRunContext(deriveClaim(11u, connId), clock = { PINNED_NOW }) { _, _ -> true })

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
        // The shared golden-sprint tests above (`clonedProcessedConnection` + `mapFloBoardToTeam`)
        // already run PROCESS -> PROFILE, so the connection's profile carries a detected SPRINT-role
        // field id; this test only re-asserts the specific fix: DERIVE resolves that id
        // (`MetricsConfigService.detectedSprintFieldId`) and reads `norm.work_item_field_changes` by
        // it (`WorkItemStore.fieldChangesByFieldIds`), never by the display text `"Sprint"` — a
        // renamed/localized Sprint field on a real tenant must not go silently unread.
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        mapFloBoardToTeam(connId, config)
        deriver(config).derive(SyncJobRunContext(deriveClaim(50u, connId), clock = { PINNED_NOW }) { _, _ -> true })

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
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-nosprintfield")
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
        membershipService.create(teamId, TeamMembershipCreateRequest(accountA, membershipStart, null))
        membershipService.create(teamId, TeamMembershipCreateRequest(accountB, membershipStart, null))

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
            teamMembership(config).create(teamId, TeamMembershipCreateRequest(targetAccountId, doneAt - THIRTY_DAYS_MS, null))

            deriver(config).derive(SyncJobRunContext(deriveClaim(31u, connId), clock = { PINNED_NOW }) { _, _ -> true })

            suspend fun reread(issueId: Long): ResultRow = suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore.FactTaskDelivery.selectAll()
                    .where { (MetricsStore.FactTaskDelivery.connectionId eq connId) and (MetricsStore.FactTaskDelivery.issueId eq issueId) }
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
            val connId = clonedProcessedConnection()
            val config = metricsConfig()
            deriver(config).derive(SyncJobRunContext(deriveClaim(60u, connId), clock = { PINNED_NOW }) { _, _ -> true })

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

            // hoursPerDay: the default metrics.settings value (8.0) — md = seconds / 3600 / 8.0.
            val hoursPerDay = 8.0
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
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        deriver(config).derive(SyncJobRunContext(deriveClaim(61u, connId), clock = { PINNED_NOW }) { _, _ -> true })

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
            (secondsFor(task.issueId) + childSeconds) / 3600.0 / 8.0
        }
        val epicIssueIds = liveItems.filter { it.hierarchyLevel == 1 }.map { it.issueId }.toSet()
        val epicsOwnMd = epicIssueIds.sumOf { epicId -> secondsFor(epicId) / 3600.0 / 8.0 }

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

    private companion object {
        const val PINNED_NOW = 1_772_668_800_000L // 2026-09-02T00:00:00Z, per the v0.3.0 plan's pinned-clock convention
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        const val CONFIGURED_CAPACITY_MD = 42.0
        const val CAPACITY_TOLERANCE = 0.01
        const val FLO_BOARD_ID = 1L
    }
}
