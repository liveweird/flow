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
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** MD5 over every persisted `fact_task_delivery` row, ordered by issue id — the reprocess-digest
     * pattern (`.claude/docs/testing.md` "The reprocess digest"), applied to invariant 12 ("every
     * live number is reproducible from `norm` + one configuration revision"): a second DERIVE over
     * UNCHANGED input, under the SAME pinned clock, must write byte-for-byte identical rows. */
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
            clock = { PINNED_NOW },
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

        deriver.derive(SyncJobRunContext(claim) { _, _ -> true })

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
            clock = { PINNED_NOW },
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
        deriver.derive(SyncJobRunContext(claim) { _, _ -> true })

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
            clock = { PINNED_NOW },
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
        deriver.derive(SyncJobRunContext(claim) { _, _ -> true })

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.TaskEpic.selectAll().where { MetricsStore.TaskEpic.connectionId eq connId }
                .orderBy(MetricsStore.TaskEpic.issueId to SortOrder.ASC, MetricsStore.TaskEpic.id to SortOrder.ASC)
                .toList()
        }
        assertTrue(rows.isNotEmpty(), "metrics.task_epic must be non-empty after DERIVE")
        rows.groupBy { it[MetricsStore.TaskEpic.issueId] }.forEach { (issueId, taskRows) ->
            // This commit writes exactly one open-ended [createdAt, null) row per task (the full
            // effective-dated history arrives with a later commit — see MetricsDeriver.kt's own
            // comment on `task_sprint`'s empty bridge) — the invariant still holds vacuously today,
            // and this sweep pins the shape so a later history-aware rewrite cannot silently regress it.
            assertEquals(1, taskRows.size, "issue $issueId must carry exactly one task_epic row today")
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
    fun `invariant 12-lite — a second DERIVE over unchanged input yields a byte-identical fact_task_delivery digest`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
            clock = { PINNED_NOW },
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

        deriver.derive(SyncJobRunContext(claim(4u)) { _, _ -> true })
        val firstDigest = factTaskDeliveryDigest(connId)

        deriver.derive(SyncJobRunContext(claim(5u)) { _, _ -> true })
        val secondDigest = factTaskDeliveryDigest(connId)

        assertEquals(
            firstDigest,
            secondDigest,
            "a re-DERIVE over the SAME norm rows and config revision must write identical fact_task_delivery rows",
        )
    }

    private companion object {
        const val PINNED_NOW = 1_772_668_800_000L // 2026-09-02T00:00:00Z, per the v0.3.0 plan's pinned-clock convention
    }
}
