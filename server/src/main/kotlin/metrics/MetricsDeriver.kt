package ch.nokillswit.metrics

import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.fieldValueOptions
import io.ktor.util.AttributeKey
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val MetricsDeriverKey = AttributeKey<MetricsDeriver>("MetricsDeriver")

private const val EPIC_HIERARCHY_LEVEL = 1
private const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000
private const val TWO_YEARS_MS = 2 * ONE_YEAR_MS
private const val SECONDS_PER_HOUR = 3600.0
private const val UNMAPPED_STATUS_FLAG = "UNMAPPED_STATUS"
private const val MAX_ERROR_DETAIL_LENGTH = 1000
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** `MetricsDeriver`'s own default (v0.3.0 M3 review round 2b) — mirrors `application.yaml`'s `ingest.jobRetentionDays` default. */
internal const val DEFAULT_JOB_RETENTION_DAYS = 90L

/**
 * The per-item/per-batch work runs in chunks of this size (v0.3.0 M3 review round 2b, plan §5):
 * `MetricsDeriver.derive` never loads every issue's intervals/field changes for the WHOLE
 * connection into memory at once — only one batch's worth, read, computed, and written before the
 * next batch's own read begins, all inside the ONE `context.transaction {}` `derive()` opens.
 */
internal const val DERIVE_BATCH_SIZE = 200

/** The system `duedate` field id — `metrics/MetricsConfigService.kt`'s own default for `fields.epicDue`. */
private const val DUE_DATE_FIELD_ID = "duedate"

/** `jira/JiraNormalizer.kt`'s own tracked field id for the issue-key changelog item — `task_domain`'s history source. */
private const val ISSUE_KEY_FIELD_ID = "issuekey"

/** Per-item derived quantities shared by both the epic and task write paths — computed once per issue. */
private data class ItemDerived(
    val stages: List<StageInterval>,
    val started: Long?,
    val done: Long?,
    val reopenCount: Int,
    val blocked: List<BlockedInterval>,
    val ownSnapshots: EstimateSnapshots,
    /** The item's OWN configured-estimate-field timeline (review round 2a) — empty when no estimate
     * field is configured for this item's role; kept alongside [ownSnapshots] (rather than discarded
     * once the snapshots are taken) so a SUBTASKS-fallback parent can merge its children's OWN
     * timelines ([DeriveKernels.mergeEstimateTimelines]) instead of reusing their CURRENT sum at
     * every past instant. */
    val estimateTimeline: List<EstimatePoint>,
    val ownCategory: String?,
)

/** The connection's configured maps [DeriveContext] needs — split out of it purely to stay under the parameter-count gate. */
private data class ConfigMaps(
    val stageMap: Map<String, ItemStage>,
    val domainByProject: Map<String, String>,
    val activityTypeByIssueType: Map<String, String>,
    val workCategoryMap: Map<String, String>,
    val blockedStatusIds: Set<String>,
    val boardTeamByBoardId: Map<Long, UInt>,
    val workCategoryFieldId: String?,
    val epicDriftDays: Int,
    val epicStartFieldId: String?,
    val epicDueFieldId: String?,
)

/**
 * Every lookup [MetricsDeriver]'s per-issue derivation needs, gathered ONCE per DERIVE run — split
 * into [ConfigMaps]/plain properties purely to stay under detekt's parameter-count gate; every
 * property is exposed as a plain accessor so call sites read `context.stageMap` etc. unchanged.
 *
 * **Batch-scoped fields (review round 2b, plan §5's memory bound) are `var`, reassigned by
 * `MetricsDeriver`'s own batch loops (`runPass1`/`runPass2`) directly before each batch of up to
 * [DERIVE_BATCH_SIZE] issues is processed — NEVER loaded for the whole connection at once.** A
 * lookup for an issue OUTSIDE the currently loaded batch returns nothing; every kernel/composer call
 * site below only ever reads ITS OWN item's `issueId` from these maps (never a sibling's, a child's,
 * or a parent's — the ONE exception, worklog seconds for a task's own sub-tasks, is served instead by
 * [worklogSecondsByIssue], a small connection-wide AGGREGATE the constructor loads once), so
 * reassigning these maps between batches is safe.
 */
private class DeriveContext(
    private val configMaps: ConfigMaps,
    val itemsById: Map<Long, WorkItemStore.DerivationWorkItemRow>,
    val sprintBoardById: Map<Long, Long?>,
    val membershipsByAccount: Map<String, List<TeamMembershipService.MembershipInterval>>,
    /** Every issue's summed worklog seconds, connection-wide (review round 2b) — a small aggregate
     * `Map<Long, Long>`, never the full per-worklog row shape; see [WorkItemStore.worklogSecondsByIssue]. */
    val worklogSecondsByIssue: Map<Long, Long>,
    val calendar: WorkingCalendar,
    val now: Long,
    val hoursPerDay: Double,
) {
    var statusIntervalsByIssue: Map<Long, List<NormalizedStatusInterval>> = emptyMap()
    var flaggedIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var estimateChangesByIssueAndField: Map<Long, List<FieldChangeRow>> = emptyMap()
    var assigneeIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var sprintIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var parentIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var issueKeyChangesByIssue: Map<Long, List<FieldChangeRow>> = emptyMap()

    val stageMap get() = configMaps.stageMap
    val domainByProject get() = configMaps.domainByProject
    val activityTypeByIssueType get() = configMaps.activityTypeByIssueType
    val workCategoryMap get() = configMaps.workCategoryMap
    val blockedStatusIds get() = configMaps.blockedStatusIds
    val boardTeamByBoardId get() = configMaps.boardTeamByBoardId
    val workCategoryFieldId get() = configMaps.workCategoryFieldId
    val epicDriftDays get() = configMaps.epicDriftDays
    val epicStartFieldId get() = configMaps.epicStartFieldId
    val epicDueFieldId get() = configMaps.epicDueFieldId

    /** The epic start/due DATE fields' current value for [item] — `duedate` reads the already-parsed
     * system column (`WorkItemStore.DerivationWorkItemRow.dueAt`); any other configured field id is a
     * plain ISO `YYYY-MM-DD` string in `custom_fields`, parsed to epoch millis at start of day UTC —
     * the SAME convention `jira/JiraNormalizer.kt` already applies to the system `duedate` field. */
    fun epicDateValue(item: WorkItemStore.DerivationWorkItemRow, fieldId: String?): Long? {
        if (fieldId == null) return null
        if (fieldId == DUE_DATE_FIELD_ID) return item.dueAt
        val raw = item.customFields[fieldId]?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** The configured estimate field id for [item] — epics may override tasks' own field. */
    fun estimateFieldIdFor(item: WorkItemStore.DerivationWorkItemRow, config: DataSourceMetricsConfig): String? =
        if (item.hierarchyLevel == EPIC_HIERARCHY_LEVEL) {
            config.fields.estimateEpic ?: config.fields.estimateTask
        } else {
            config.fields.estimateTask
        }
}

/**
 * The `DERIVE` job body (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Analytical model",
 * `.claude/docs/ingestion.md` "Job orders") — reads `norm.*` and the connection's effective metrics
 * configuration, writes `metrics.*`, NEVER touches Jira. Dispatched by `ingest/IngestWorker.kt`
 * BEFORE the connector registry (`claim.kind == DERIVE`), so it runs whether or not the connection's
 * OWN connector kind matters. This commit populates dims (`dim_domain`/`dim_task`/`dim_epic`),
 * every bridge except `task_sprint`'s DB write, and `fact_task_delivery`/`fact_epic_delivery`;
 * `dim_sprint`/`fact_sprint*`/`fact_worklog`/`fact_epic_plan`/`agg_daily_*` writers arrive with
 * commits 8/9 (their table objects already exist — [MetricsStore.purgeAll] already drains them).
 *
 * **Memory (review round 2b, plan §5, `.claude/docs/metrics.md` "The DERIVE run algorithm"):** the
 * per-issue work runs in batches of [DERIVE_BATCH_SIZE] — `runPass1`/`runPass2` each read intervals/
 * field changes for ONE batch of issue ids at a time (`WorkItemStore`'s own `issueIds`-scoped reads),
 * compute that batch's rows, and insert them immediately, rather than holding the whole connection's
 * raw intervals/worklogs/output rows in memory at once. `custom_fields` is trimmed to only the
 * configured field ids right after `workItemsForDerivation` reads it back (`trimCustomFields`) — a
 * real tenant's Rank/ADF-shaped fields otherwise duplicate raw bytes for every issue held in the
 * connection-wide `itemsById` reference map every pass needs for epic/sub-task lookups.
 */
class MetricsDeriver(
    private val workItemStore: WorkItemStore,
    private val metricsConfig: MetricsConfigService,
    private val teamMembership: TeamMembershipService,
    private val metricsStore: MetricsStore,
    private val database: R2dbcDatabase,
    private val jobRetentionDays: Long = DEFAULT_JOB_RETENTION_DAYS,
) {
    /**
     * The DERIVE job's own run — `context.claim.connectionId`/`context.claim.id`; heartbeats once
     * after the write commits. Returns the `metrics.settings.config_revision` this run used
     * (`derive_runs.config_revision`'s own value) — `ingest/IngestWorker.kt`'s `onSucceeded` compares
     * it against the CURRENT revision once this run finishes, so a config change that landed WHILE
     * this run was in flight (and so coalesced into it rather than getting its own job) is never
     * silently lost (review round 1 fix). Settings and the connection's effective config are read
     * together in ONE transaction — a settings write racing between the two independent reads this
     * used to be would let this run stamp a revision NEWER than the config it actually derived under.
     * [SyncJobRunContext.clock] is the SAME injectable clock every stream/the worker itself reads
     * (`ingest/Stream.kt`, `ingest/IngestWorker.kt`) — review round 2a fix, replacing a SEPARATE
     * constructor-level clock this class used to carry on its own.
     */
    suspend fun derive(context: SyncJobRunContext): Long {
        val connectionId = context.claim.connectionId
        val jobId = context.claim.id
        val now = context.clock()
        val (settings, config) = suspendTransaction(database) {
            metricsConfig.read() to metricsConfig.effectiveConfig(connectionId)
        }
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val holidays = settings.holidays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
        val calendar = WorkingCalendar(zone, settings.weekendDays.toSet(), holidays)

        // Hard-deletes old terminal derive_runs rows on every DERIVE (review round 2b) — the
        // `SyncJobsService.prune` shape, `.claude/docs/persistence.md` "Soft delete (convention)".
        suspendTransaction(database) { metricsStore.pruneDeriveRuns(jobRetentionDays * MILLIS_PER_DAY, now) }

        val runId = suspendTransaction(database) {
            MetricsStore.DeriveRuns.insert {
                it[MetricsStore.DeriveRuns.connectionId] = connectionId.toInt()
                it[MetricsStore.DeriveRuns.jobId] = jobId.toInt()
                it[MetricsStore.DeriveRuns.configRevision] = settings.configRevision
                it[MetricsStore.DeriveRuns.processingVersion] = PROCESSING_VERSION
                it[MetricsStore.DeriveRuns.startedAt] = now
                it[MetricsStore.DeriveRuns.status] = "RUNNING"
            }[MetricsStore.DeriveRuns.id]
        }

        try {
            val counts = suspendTransaction(database) {
                runDerivation(connectionId, config, calendar, now, settings.hoursPerDay, settings.epicDriftDays, settings.configRevision)
            }
            markRunSucceeded(runId, counts, context.clock())
            context.heartbeat(null, "derive")
        } catch (failure: Exception) {
            // A genuine coroutine cancellation is an Exception too (`CancellationException`) and
            // must still be able to mark this run FAILED before it propagates — but the DB write
            // itself must run under NonCancellable (review round 2b fix), since the enclosing
            // coroutine's own job is already cancelled by the time this catch runs: a plain
            // `suspendTransaction` call here would otherwise never actually commit, leaving the row
            // RUNNING forever. There is no separate `CANCELLED` status (`.claude/docs/metrics.md`
            // documents this choice) — `derive_runs.status`'s CHECK constraint (V15, immutable bytes)
            // only allows RUNNING/SUCCEEDED/FAILED, and altering it would need a new migration.
            markRunFailed(runId, failure, context.clock())
            throw failure
        }
        return settings.configRevision
    }

    /**
     * Deletes every rebuildable `metrics.*` row for this connection ONCE, then rebuilds dims,
     * bridges and task/epic facts in batches of [DERIVE_BATCH_SIZE] — the whole thing runs inside the
     * CALLER's one transaction (`derive()`'s own `suspendTransaction` wrap). Returns the row counts
     * `markRunSucceeded` stamps onto `derive_runs.row_counts`.
     */
    private suspend fun runDerivation(
        connectionId: UInt,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
        configRevision: Long,
    ): DeriveRowCounts {
        val relevantFieldIds = relevantCustomFieldIds(config)
        val workItems = workItemStore.workItemsForDerivation(connectionId).map { trimCustomFields(it, relevantFieldIds) }
        val dimDateFrom = (workItems.minOfOrNull { it.createdAt } ?: now) - ONE_YEAR_MS
        metricsStore.upsertDimDate(calendar.dimDateRows(dimDateFrom, now + TWO_YEARS_MS), configRevision)

        metricsStore.deleteDims(connectionId)
        metricsStore.deleteBridges(connectionId)
        metricsStore.deleteFactTaskDelivery(connectionId)
        metricsStore.deleteFactEpicDelivery(connectionId)

        val context = buildContext(connectionId, workItems, config, calendar, now, hoursPerDay, epicDriftDays)
        metricsStore.insertDomains(connectionId, domainDims(context), configRevision)

        val derivedById = mutableMapOf<Long, ItemDerived>()
        val blockedByIssue = mutableMapOf<Long, Pair<Long, Double>>()
        runPass1(connectionId, workItems, context, config, derivedById, blockedByIssue)

        val factTasksByIssueId = mutableMapOf<Long, FactTaskDeliveryRow>()
        val taskCount = runPass2(connectionId, workItems, context, derivedById, blockedByIssue, factTasksByIssueId, configRevision)
        val epicCount = runPass3(connectionId, workItems, context, derivedById, blockedByIssue, factTasksByIssueId, configRevision)

        return DeriveRowCounts(tasks = taskCount, epics = epicCount)
    }

    private suspend fun markRunSucceeded(runId: Int, counts: DeriveRowCounts, finishedAt: Long) {
        val countsJson = buildJsonObject {
            put("tasks", JsonPrimitive(counts.tasks))
            put("epics", JsonPrimitive(counts.epics))
        }.toString()
        suspendTransaction(database) {
            MetricsStore.DeriveRuns.update({ MetricsStore.DeriveRuns.id eq runId }) {
                it[status] = "SUCCEEDED"
                it[MetricsStore.DeriveRuns.finishedAt] = finishedAt
                it[rowCounts] = countsJson
            }
        }
    }

    private suspend fun markRunFailed(runId: Int, failure: Exception, finishedAt: Long) = withContext(NonCancellable) {
        suspendTransaction(database) {
            MetricsStore.DeriveRuns.update({ MetricsStore.DeriveRuns.id eq runId }) {
                it[status] = "FAILED"
                it[MetricsStore.DeriveRuns.finishedAt] = finishedAt
                it[errorDetail] = failure.message?.take(MAX_ERROR_DETAIL_LENGTH)
            }
        }
        Unit
    }

    /** The field ids `custom_fields` actually needs to keep for this connection's derivation (review round 2b) — see [trimCustomFields]. */
    private fun relevantCustomFieldIds(config: DataSourceMetricsConfig): Set<String> = setOfNotNull(
        config.fields.estimateTask, config.fields.estimateEpic, config.fields.workCategory, config.fields.epicStart, config.fields.epicDue,
    )

    /**
     * Trims a work item's `custom_fields` JSON object down to only [relevantFieldIds] (review round
     * 2b, plan §5 "read from custom_fields only the configured field ids") — every OTHER
     * `customfield_*` value (Rank, an ADF-shaped rich-text field, …) is dropped right after the read,
     * before it ever lands in the connection-wide `itemsById` reference map every batch's kernels
     * consult for epic/sub-task lookups.
     */
    private fun trimCustomFields(
        item: WorkItemStore.DerivationWorkItemRow,
        relevantFieldIds: Set<String>,
    ): WorkItemStore.DerivationWorkItemRow =
        item.copy(customFields = JsonObject(item.customFields.filterKeys { it in relevantFieldIds }))

    private suspend fun buildContext(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
    ): DeriveContext {
        val configMaps = ConfigMaps(
            stageMap = config.statusStages.associate { it.statusId to ItemStage.valueOf(it.stage.name) },
            domainByProject = config.domains.associate { it.projectKey to it.domainKey },
            activityTypeByIssueType = config.activityTypes.associate { it.issueType to it.activityType },
            workCategoryMap = config.workCategories.associate { it.valueId to it.category },
            blockedStatusIds = config.blockedStatuses.toSet(),
            boardTeamByBoardId = config.boards.associate { it.boardId to it.teamId },
            workCategoryFieldId = config.fields.workCategory,
            epicDriftDays = epicDriftDays,
            epicStartFieldId = config.fields.epicStart,
            epicDueFieldId = config.fields.epicDue,
        )
        return DeriveContext(
            configMaps = configMaps,
            itemsById = workItems.associateBy { it.issueId },
            sprintBoardById = workItemStore.allSprintRefs(connectionId).associate { it.sprintId to it.boardId },
            membershipsByAccount = teamMembership.allMembershipsByAccount(),
            worklogSecondsByIssue = workItemStore.worklogSecondsByIssue(connectionId),
            calendar = calendar,
            now = now,
            hoursPerDay = hoursPerDay,
        )
    }

    /**
     * Pass 1 (review round 2b): item-level bridges shared by tasks AND epics alike (`item_stage`/
     * `item_blocked`/`item_estimate` — `.claude/docs/domain-model.md`'s bridges table has no task/epic
     * split for these three), computed for EVERY item in batches of [DERIVE_BATCH_SIZE] — each
     * batch's status/flagged/estimate-field-change intervals are read scoped to that batch's own
     * issue ids, computed, and inserted before the next batch's own read begins.
     */
    private suspend fun runPass1(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
        derivedById: MutableMap<Long, ItemDerived>,
        blockedByIssue: MutableMap<Long, Pair<Long, Double>>,
    ) {
        val estimateFieldIds = listOfNotNull(config.fields.estimateTask, config.fields.estimateEpic).distinct()
        for (batch in workItems.chunked(DERIVE_BATCH_SIZE)) {
            val ids = batch.map { it.issueId }
            context.statusIntervalsByIssue = workItemStore.statusIntervalsByIssue(connectionId, ids)
            context.flaggedIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.FLAGGED, ids)
            context.estimateChangesByIssueAndField =
                workItemStore.fieldChangesByFieldIds(connectionId, estimateFieldIds, ids).groupBy { it.issueId }

            val itemStageBatch = mutableListOf<ItemStageRow>()
            val itemBlockedBatch = mutableListOf<ItemBlockedRow>()
            val itemEstimateBatch = mutableListOf<ItemEstimateRow>()
            for (item in batch) {
                val derived = deriveItem(item, context, config)
                derivedById[item.issueId] = derived
                val blockedMs = derived.blocked.sumOf { it.toAtMs - it.fromAtMs }
                val blockedWorkingDays = derived.blocked.sumOf { context.calendar.workingDaysBetween(it.fromAtMs, it.toAtMs) }
                blockedByIssue[item.issueId] = blockedMs to blockedWorkingDays
                itemStageBatch += derived.stages.map { ItemStageRow(item.issueId, it.stage.name, it.statusId, it.fromAtMs, it.toAtMs) }
                itemBlockedBatch += derived.blocked.map { ItemBlockedRow(item.issueId, it.reason, it.fromAtMs, it.toAtMs) }
                itemEstimateBatch += buildEstimateBridge(item, derived.estimateTimeline, context.estimateFieldIdFor(item, config))
            }
            metricsStore.insertItemStage(connectionId, itemStageBatch)
            metricsStore.insertItemBlocked(connectionId, itemBlockedBatch)
            metricsStore.insertItemEstimate(connectionId, itemEstimateBatch)
        }
    }

    /**
     * Pass 2 (review round 2b): tasks (review round 2a: "skip epics for task_* bridges" —
     * `task_epic`/`task_domain`/`task_assignee` are TASK-only bridges), in batches of
     * [DERIVE_BATCH_SIZE] — each batch's assignee/sprint/parent field intervals and `issuekey` field
     * changes are read scoped to that batch's own issue ids. `factTasksByIssueId` (a small map of
     * ALREADY-derived fact rows, not raw inputs) accumulates across every batch so pass 3 can roll an
     * epic's cost/estimate up from its children (D2, finding 6) regardless of which batch a child
     * task landed in. Returns the total task row count.
     */
    private suspend fun runPass2(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        blockedByIssue: Map<Long, Pair<Long, Double>>,
        factTasksByIssueId: MutableMap<Long, FactTaskDeliveryRow>,
        configRevision: Long,
    ): Int {
        var count = 0
        val taskItems = workItems.filter { it.hierarchyLevel != EPIC_HIERARCHY_LEVEL }
        for (batch in taskItems.chunked(DERIVE_BATCH_SIZE)) {
            val ids = batch.map { it.issueId }
            context.assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)
            context.sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT, ids)
            context.parentIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.PARENT, ids)
            context.issueKeyChangesByIssue =
                workItemStore.fieldChangesByFieldIds(connectionId, listOf(ISSUE_KEY_FIELD_ID), ids).groupBy { it.issueId }

            val tasksBatch = mutableListOf<DimTaskRow>()
            val factsBatch = mutableListOf<FactTaskDeliveryRow>()
            val taskEpicBatch = mutableListOf<TaskEpicRow>()
            val taskDomainBatch = mutableListOf<TaskDomainRow>()
            val taskAssigneeBatch = mutableListOf<TaskAssigneeRow>()
            for (item in batch) {
                val derived = derivedById.getValue(item.issueId)
                val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
                val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
                val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)

                taskEpicBatch += taskEpicHistory(item, context)
                taskDomainBatch += taskDomainHistory(item, context)
                taskAssigneeBatch += taskAssigneeHistory(item, context)

                val composition =
                    buildTaskRow(item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays)
                tasksBatch += composition.dim
                factsBatch += composition.fact
                factTasksByIssueId[item.issueId] = composition.fact
            }
            metricsStore.insertTasks(connectionId, tasksBatch, configRevision)
            metricsStore.insertTaskEpic(connectionId, taskEpicBatch)
            metricsStore.insertTaskDomain(connectionId, taskDomainBatch)
            metricsStore.insertTaskAssignee(connectionId, taskAssigneeBatch)
            metricsStore.insertFactTaskDelivery(connectionId, factsBatch, configRevision)
            count += factsBatch.size
        }
        return count
    }

    /**
     * Pass 3 (review round 2b): epics, in batches of [DERIVE_BATCH_SIZE] — reads pass 2's cached
     * `factTasksByIssueId` for the D2 roll-up; needs no per-batch interval reads of its own (an
     * epic's own facts come entirely from [derivedById]/[factTasksByIssueId]/config, already in
     * memory). Returns the total epic row count.
     */
    private suspend fun runPass3(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        blockedByIssue: Map<Long, Pair<Long, Double>>,
        factTasksByIssueId: Map<Long, FactTaskDeliveryRow>,
        configRevision: Long,
    ): Int {
        var count = 0
        val epicItems = workItems.filter { it.hierarchyLevel == EPIC_HIERARCHY_LEVEL }
        for (batch in epicItems.chunked(DERIVE_BATCH_SIZE)) {
            val epicsBatch = mutableListOf<DimEpicRow>()
            val factEpicsBatch = mutableListOf<FactEpicDeliveryRow>()
            for (item in batch) {
                val derived = derivedById.getValue(item.issueId)
                val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
                val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
                val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)
                val (dim, fact) = buildEpicRow(
                    item, derived, context, workItems, derivedById, factTasksByIssueId, domainKey, currentStage,
                    blockedMs, blockedWorkingDays,
                )
                epicsBatch += dim
                factEpicsBatch += fact
            }
            metricsStore.insertEpics(connectionId, epicsBatch, configRevision)
            metricsStore.insertFactEpicDelivery(connectionId, factEpicsBatch, configRevision)
            count += factEpicsBatch.size
        }
        return count
    }

    /** The item's OWN configured-estimate-field timeline — empty when no field is configured for its role (epic vs. task). */
    private fun ownEstimateTimeline(
        item: WorkItemStore.DerivationWorkItemRow,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
    ): List<EstimatePoint> {
        val fieldId = context.estimateFieldIdFor(item, config) ?: return emptyList()
        val changes = context.estimateChangesByIssueAndField[item.issueId].orEmpty().filter { it.fieldId == fieldId }
        val current = item.customFields[fieldId]?.jsonPrimitive?.doubleOrNull
        return DeriveKernels.estimateTimeline(item.createdAt, changes, current)
    }

    private fun ownEstimateSnapshots(timeline: List<EstimatePoint>, startedAt: Long?, doneAt: Long?): EstimateSnapshots =
        if (timeline.isEmpty()) {
            EstimateSnapshots(null, null, null, false, 0)
        } else {
            DeriveKernels.estimateSnapshots(timeline, startedAt, doneAt)
        }

    private fun ownWorkCategory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): String? {
        val fieldId = context.workCategoryFieldId ?: return null
        val valueId = fieldValueOptions(item.customFields[fieldId]).firstOrNull()?.first ?: return null
        return context.workCategoryMap[valueId]
    }

    /** D2: a sub-task's own epic is its parent TASK's epic (one indirection); a level-0 task's epic is its own `parentIssueId`. */
    private fun epicIdOf(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): Long? {
        val parent = item.parentIssueId?.let { context.itemsById[it] } ?: return null
        val candidate = if (item.isSubtask) parent.parentIssueId else item.parentIssueId
        return candidate?.takeIf { context.itemsById[it]?.hierarchyLevel == EPIC_HIERARCHY_LEVEL }
    }

    private fun deriveItem(
        item: WorkItemStore.DerivationWorkItemRow,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
    ): ItemDerived {
        val statusIntervals = context.statusIntervalsByIssue[item.issueId].orEmpty()
        val stages = DeriveKernels.stageIntervals(statusIntervals, context.stageMap)
        val startedDone = DeriveKernels.startedDoneAt(stages)
        val blocked = DeriveKernels.blockedIntervals(
            context.flaggedIntervalsByIssue[item.issueId].orEmpty(),
            statusIntervals,
            context.blockedStatusIds,
            startedDone.startedAtMs,
            startedDone.doneAtMs ?: context.now,
        )
        val timeline = ownEstimateTimeline(item, context, config)
        val snapshots = ownEstimateSnapshots(timeline, startedDone.startedAtMs, startedDone.doneAtMs)
        val category = ownWorkCategory(item, context)
        return ItemDerived(
            stages, startedDone.startedAtMs, startedDone.doneAtMs, startedDone.reopenCount, blocked, snapshots, timeline, category,
        )
    }

    private fun domainDims(context: DeriveContext): List<DimDomainRow> =
        context.domainByProject.entries
            .groupBy({ it.value }) { it.key }
            .map { (domainKey, projectKeys) -> DimDomainRow(domainKey, domainKey, projectKeys.distinct()) }

    /**
     * D2's roll-up, applied at the epic level (review round 2a fix): `child_sum_estimate_md` sums
     * each LEVEL-0 child's own COMPOSITE estimate (`estimateCurrentMd` — OWN, else its own SUBTASKS
     * roll-up, never the child's OWN field alone), and `actual_md` is the epic's OWN worklogs PLUS
     * every child's ALREADY-ROLLED-UP `actualMd` (which itself already folds in that child's own
     * sub-tasks) — never a fresh worklog-seconds sum over the direct children alone, which silently
     * dropped every sub-task's worklog time from the epic's own cost entirely. [childFacts] are the
     * SAME `fact_task_delivery` rows already written for this epic's level-0 children — computed in
     * an earlier pass over [workItems] specifically so this rollup never re-derives them.
     */
    private fun buildEpicRow(
        item: WorkItemStore.DerivationWorkItemRow,
        derived: ItemDerived,
        context: DeriveContext,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        derivedById: Map<Long, ItemDerived>,
        factTasksByIssueId: Map<Long, FactTaskDeliveryRow>,
        domainKey: String,
        currentStage: ItemStage,
        blockedMs: Long,
        blockedWorkingDays: Double,
    ): Pair<DimEpicRow, FactEpicDeliveryRow> {
        val ownCategory = derived.ownCategory
        val epicStartAt = context.epicDateValue(item, context.epicStartFieldId)
        val epicDueAt = context.epicDateValue(item, context.epicDueFieldId)
        val dim = DimEpicRow(
            item.issueId, item.issueKey, item.summary, domainKey, ownCategory, currentStage.name, epicStartAt, epicDueAt,
        )
        val childTasks = workItems.filter { !it.isSubtask && it.parentIssueId == item.issueId }
        val childFacts = childTasks.mapNotNull { factTasksByIssueId[it.issueId] }
        val childSum = childFacts.sumOf { it.estimateCurrentMd ?: 0.0 }
        val ownCurrent = derived.ownSnapshots.currentMd
        val childStatuses = childTasks.map { derivedById.getValue(it.issueId).let { d -> ChildDeliveryStatus(d.started, d.done) } }
        val driftFlags = DeriveKernels.epicDriftFlags(currentStage, childStatuses, context.epicDriftDays, context.now)
        val ownWorklogSeconds = context.worklogSecondsByIssue[item.issueId] ?: 0L
        val actualMd = ownWorklogSeconds / SECONDS_PER_HOUR / context.hoursPerDay + childFacts.sumOf { it.actualMd }
        val fact = FactEpicDeliveryRow(
            issueId = item.issueId,
            startedAt = derived.started,
            doneAt = derived.done,
            ownEstimateAtStartMd = derived.ownSnapshots.atStartMd,
            ownEstimateAtDoneMd = derived.ownSnapshots.atDoneMd,
            ownEstimateCurrentMd = ownCurrent,
            estimateChangesAfterStart = derived.ownSnapshots.changesAfterStart,
            childSumEstimateMd = childSum,
            budgetSource = if (ownCurrent != null) "OWN" else "CHILDREN",
            actualMd = actualMd,
            cycleMs = cycleMs(derived),
            cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
            blockedMs = blockedMs,
            blockedWorkingDays = blockedWorkingDays,
            domainKey = domainKey,
            workCategory = ownCategory,
            driftFlags = driftFlags.map { it.name },
        )
        return dim to fact
    }

    /**
     * The task's composite estimate (review round 2a fix): OWN wins outright; otherwise SUBTASKS
     * sums its sub-tasks' OWN timelines AS-OF the PARENT's own `startedAt`/`doneAt`
     * ([DeriveKernels.mergeEstimateTimelines]) — never their CURRENT sum reused at both snapshots,
     * which silently ignored every subtask estimate change and reported `estimatedLate`/
     * `changesAfterStart` off the (usually estimate-less, hence always-false) parent's OWN timeline
     * instead of the subtasks' actual history.
     */
    private fun taskEstimate(
        derived: ItemDerived,
        childSubtasks: List<WorkItemStore.DerivationWorkItemRow>,
        derivedById: Map<Long, ItemDerived>,
        startedAt: Long?,
        doneAt: Long?,
    ): EstimateComposite {
        val ownCurrent = derived.ownSnapshots.currentMd
        if (ownCurrent != null) {
            return EstimateComposite(
                derived.ownSnapshots.atStartMd,
                derived.ownSnapshots.atDoneMd,
                ownCurrent,
                "OWN",
                derived.ownSnapshots.estimatedLate,
                derived.ownSnapshots.changesAfterStart,
            )
        }
        val merged = DeriveKernels.mergeEstimateTimelines(childSubtasks.map { derivedById.getValue(it.issueId).estimateTimeline })
        val current = merged.lastOrNull()?.estimateMd
        if (current == null) return EstimateComposite(null, null, null, "NONE", false, 0)
        val snapshots = DeriveKernels.estimateSnapshots(merged, startedAt, doneAt)
        return EstimateComposite(
            snapshots.atStartMd, snapshots.atDoneMd, current, "SUBTASKS", snapshots.estimatedLate, snapshots.changesAfterStart,
        )
    }

    private fun buildTaskRow(
        item: WorkItemStore.DerivationWorkItemRow,
        derived: ItemDerived,
        context: DeriveContext,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        derivedById: Map<Long, ItemDerived>,
        domainKey: String,
        currentStage: ItemStage,
        blockedMs: Long,
        blockedWorkingDays: Double,
    ): TaskComposition {
        val epicId = epicIdOf(item, context)
        val epic = epicId?.let { context.itemsById[it] }
        val epicDerived = epicId?.let { derivedById[it] }
        val epicDomainKey = epic?.let { context.domainByProject[it.projectKey] ?: it.projectKey }
        val epicCategory = epicDerived?.ownCategory
        val ownCategory = derived.ownCategory
        val workCategory = ownCategory ?: epicCategory
        val workCategorySource = if (ownCategory != null) "OWN" else if (epicCategory != null) "EPIC" else "NONE"
        val activityType = context.activityTypeByIssueType[item.issueType] ?: item.issueType

        val dimTask = DimTaskRow(
            item.issueId, item.issueKey, item.issueType, activityType, workCategory, workCategorySource,
            item.isSubtask, item.parentIssueId?.takeIf { item.isSubtask }, domainKey, epicId,
        )

        val childSubtasks = if (!item.isSubtask) workItems.filter { it.isSubtask && it.parentIssueId == item.issueId } else emptyList()
        val estimate = taskEstimate(derived, childSubtasks, derivedById, derived.started, derived.done)
        val actualMd = actualMdFor(item, childSubtasks, context.worklogSecondsByIssue, context.hoursPerDay)
        val sprintIdAtDone = derived.done?.let { valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), it)?.toLongOrNull() }
        val sprintTeamIdAtDone = sprintIdAtDone?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
        val assigneeAtDone = derived.done?.let { valueAt(context.assigneeIntervalsByIssue[item.issueId].orEmpty(), it) }
        val assigneeTeamAtDone = derived.done?.let { teamAt(assigneeAtDone, it, context.membershipsByAccount) }
        val creditTeamId = sprintTeamIdAtDone ?: assigneeTeamAtDone
        val flags = if (currentStage == ItemStage.UNMAPPED) listOf(UNMAPPED_STATUS_FLAG) else emptyList()

        val fact = FactTaskDeliveryRow(
            issueId = item.issueId,
            issueKey = item.issueKey,
            createdAt = item.createdAt,
            startedAt = derived.started,
            doneAt = derived.done,
            reopenCount = derived.reopenCount,
            estimateAtStartMd = estimate.atStart,
            estimateAtDoneMd = estimate.atDone,
            estimateCurrentMd = estimate.current,
            estimateSource = estimate.source,
            estimateChangesAfterStart = estimate.changesAfterStart,
            estimatedLate = estimate.estimatedLate,
            actualMd = actualMd,
            hasWorklogs = actualMd > 0.0,
            blockedMs = blockedMs,
            blockedWorkingDays = blockedWorkingDays,
            cycleMs = cycleMs(derived),
            cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
            leadMs = derived.done?.let { it - item.createdAt },
            leadWorkingDays = derived.done?.let { context.calendar.workingDaysBetween(item.createdAt, it) },
            activeMs = 0,
            waitMs = 0,
            assigneeAccountIdAtDone = assigneeAtDone,
            assigneeTeamIdAtDone = assigneeTeamAtDone,
            sprintIdAtDone = sprintIdAtDone,
            sprintTeamIdAtDone = sprintTeamIdAtDone,
            creditTeamId = creditTeamId,
            domainKey = domainKey,
            epicId = epicId,
            epicDomainKey = epicDomainKey,
            crossDomain = epicDomainKey != null && epicDomainKey != domainKey,
            activityType = activityType,
            workCategory = workCategory,
            isSubtask = item.isSubtask,
            parentTaskId = item.parentIssueId?.takeIf { item.isSubtask },
            currentStage = currentStage.name,
            flags = flags,
        )
        return TaskComposition(dimTask, fact)
    }

    private fun cycleMs(derived: ItemDerived): Long? =
        if (derived.started != null && derived.done != null) derived.done - derived.started else null

    private fun cycleWorkingDays(derived: ItemDerived, calendar: WorkingCalendar): Double? =
        if (derived.started != null && derived.done != null) calendar.workingDaysBetween(derived.started, derived.done) else null

    /**
     * `task_epic`'s effective-dated history (review round 2a fix — was one open row carrying only
     * the CURRENT epic). Built from the item's OWN `PARENT` field intervals, kept ONLY when the
     * interval's parent id resolves to an item at [EPIC_HIERARCHY_LEVEL] (a defensive filter — a
     * level-0 task's PARENT is expected to always be an epic or nothing, never another task).
     * **Sub-tasks get NO `task_epic` row at all** (D2's roll-up, `.claude/docs/domain-model.md`): a
     * sub-task's own PARENT interval names its parent TASK, not an epic, and reconstructing "which
     * epic was my parent TASK under, at each historical instant" needs a second effective-dated join
     * (parent-of-parent, over time) this commit does not build — a sub-task's CURRENT epic stays
     * available via `dim_task.epic_id` (`epicIdOf`, D2's one-indirection rule) for ordinary reads;
     * only the BRIDGE's own history is the part left undone, documented in `.claude/docs/metrics.md`.
     */
    private fun taskEpicHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskEpicRow> {
        if (item.isSubtask) return emptyList()
        return context.parentIntervalsByIssue[item.issueId].orEmpty().map { interval ->
            val parentId = interval.valueId?.toLongOrNull()
            val epicId = parentId?.takeIf { context.itemsById[it]?.hierarchyLevel == EPIC_HIERARCHY_LEVEL }
            TaskEpicRow(item.issueId, epicId, interval.fromAtMs, interval.toAtMs)
        }
    }

    /** `task_assignee`'s effective-dated history (review round 2a fix) — a direct mirror of `norm`'s own ASSIGNEE field intervals. */
    private fun taskAssigneeHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskAssigneeRow> =
        context.assigneeIntervalsByIssue[item.issueId].orEmpty().map {
            TaskAssigneeRow(item.issueId, it.valueId, it.fromAtMs, it.toAtMs)
        }

    /** `task_domain`'s effective-dated history (review round 2a fix) — see [DeriveKernels.projectKeyTimeline]. */
    private fun taskDomainHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskDomainRow> {
        val issueKeyChanges = context.issueKeyChangesByIssue[item.issueId].orEmpty()
        val timeline = DeriveKernels.projectKeyTimeline(item.createdAt, issueKeyChanges, item.issueKey)
        return timeline.mapIndexed { index, point ->
            val to = timeline.getOrNull(index + 1)?.atMs
            val domainKey = point.projectKey?.let { context.domainByProject[it] ?: it }
            TaskDomainRow(item.issueId, domainKey, point.atMs, to)
        }
    }

    private data class EstimateComposite(
        val atStart: Double?,
        val atDone: Double?,
        val current: Double?,
        val source: String,
        val estimatedLate: Boolean,
        val changesAfterStart: Int,
    )
    private data class TaskComposition(val dim: DimTaskRow, val fact: FactTaskDeliveryRow)

    private fun buildEstimateBridge(
        item: WorkItemStore.DerivationWorkItemRow,
        timeline: List<EstimatePoint>,
        fieldId: String?,
    ): List<ItemEstimateRow> {
        if (fieldId == null) return emptyList()
        return timeline.mapIndexed { index, point ->
            val to = timeline.getOrNull(index + 1)?.atMs
            ItemEstimateRow(item.issueId, point.estimateMd, point.atMs, to)
        }
    }

    private fun valueAt(intervals: List<NormalizedFieldInterval>, atMs: Long): String? =
        intervals.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }?.valueId

    private fun teamAt(accountId: String?, atMs: Long, memberships: Map<String, List<TeamMembershipService.MembershipInterval>>): UInt? {
        if (accountId == null) return null
        return memberships[accountId]?.firstOrNull { it.validFrom <= atMs && (it.validTo == null || atMs < it.validTo) }?.teamId
    }

    /** A task's `actual_md` is its own worklogs plus its sub-tasks' (D2); an epic's is every child's plus its own. */
    private fun actualMdFor(
        item: WorkItemStore.DerivationWorkItemRow,
        children: List<WorkItemStore.DerivationWorkItemRow>,
        worklogSecondsByIssue: Map<Long, Long>,
        hoursPerDay: Double,
    ): Double {
        val ownSeconds = worklogSecondsByIssue[item.issueId] ?: 0L
        val childSeconds = children.sumOf { child -> worklogSecondsByIssue[child.issueId] ?: 0L }
        return (ownSeconds + childSeconds) / SECONDS_PER_HOUR / hoursPerDay
    }
}
