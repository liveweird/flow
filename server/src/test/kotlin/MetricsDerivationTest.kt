package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.JiraProfileStream
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
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
private data class GoldenFixture(val epic: GoldenEpicFixture)

@Serializable
private data class MetricsExpectedFixture(val golden: GoldenFixture)

private val METRICS_DERIVATION_FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private val metricsDerivationGoldenEpic: GoldenEpicFixture by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    METRICS_DERIVATION_FIXTURE_JSON.decodeFromString<MetricsExpectedFixture>(file.readText()).golden.epic
}

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

    private companion object {
        const val PINNED_NOW = 1_772_668_800_000L // 2026-09-02T00:00:00Z, per the v0.3.0 plan's pinned-clock convention
    }
}
