package ch.nokillswit.metrics

import ch.nokillswit.infra.db.jsonb
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
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

/** Every per-issue read [DeriveContext] needs, loaded ONCE per DERIVE run — see [ConfigMaps]' own note. */
private data class PerIssueData(
    val itemsById: Map<Long, WorkItemStore.DerivationWorkItemRow>,
    val sprintBoardById: Map<Long, Long?>,
    val membershipsByAccount: Map<String, List<TeamMembershipService.MembershipInterval>>,
    val statusIntervalsByIssue: Map<Long, List<NormalizedStatusInterval>>,
    val assigneeIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>>,
    val flaggedIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>>,
    val sprintIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>>,
    /** PARENT field intervals (review round 2a) — `task_epic`'s own effective-dated source. */
    val parentIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>>,
    val worklogsByIssue: Map<Long, List<WorkItemStore.DerivationWorklogRow>>,
    val estimateChangesByIssueAndField: Map<Long, List<FieldChangeRow>>,
    /** `issuekey` field changes (review round 2a) — `task_domain`'s own effective-dated source, see [DeriveKernels.projectKeyTimeline]. */
    val issueKeyChangesByIssue: Map<Long, List<FieldChangeRow>>,
)

/**
 * Every lookup [MetricsDeriver.compose] needs, gathered ONCE per DERIVE run rather than per issue —
 * split into [ConfigMaps]/[PerIssueData] purely to stay under detekt's parameter-count gate; every
 * property below is exposed as a plain delegated accessor so call sites read `context.stageMap`
 * etc. unchanged.
 */
private class DeriveContext(
    private val configMaps: ConfigMaps,
    private val perIssue: PerIssueData,
    val calendar: WorkingCalendar,
    val now: Long,
    val hoursPerDay: Double,
) {
    val itemsById get() = perIssue.itemsById
    val stageMap get() = configMaps.stageMap
    val domainByProject get() = configMaps.domainByProject
    val activityTypeByIssueType get() = configMaps.activityTypeByIssueType
    val workCategoryMap get() = configMaps.workCategoryMap
    val blockedStatusIds get() = configMaps.blockedStatusIds
    val boardTeamByBoardId get() = configMaps.boardTeamByBoardId
    val sprintBoardById get() = perIssue.sprintBoardById
    val membershipsByAccount get() = perIssue.membershipsByAccount
    val statusIntervalsByIssue get() = perIssue.statusIntervalsByIssue
    val assigneeIntervalsByIssue get() = perIssue.assigneeIntervalsByIssue
    val flaggedIntervalsByIssue get() = perIssue.flaggedIntervalsByIssue
    val sprintIntervalsByIssue get() = perIssue.sprintIntervalsByIssue
    val parentIntervalsByIssue get() = perIssue.parentIntervalsByIssue
    val worklogsByIssue get() = perIssue.worklogsByIssue
    val estimateChangesByIssueAndField get() = perIssue.estimateChangesByIssueAndField
    val issueKeyChangesByIssue get() = perIssue.issueKeyChangesByIssue
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
 */
class MetricsDeriver(
    private val workItemStore: WorkItemStore,
    private val metricsConfig: MetricsConfigService,
    private val teamMembership: TeamMembershipService,
    private val metricsStore: MetricsStore,
    private val database: R2dbcDatabase,
) {
    object DeriveRuns : Table("metrics.derive_runs") {
        val id = integer("id").autoIncrement()
        val connectionId = integer("connection_id")
        val jobId = integer("job_id").nullable()
        val configRevision = long("config_revision")
        val processingVersion = integer("processing_version")
        val startedAt = long("started_at")
        val finishedAt = long("finished_at").nullable()
        val status = varchar("status", 20)
        val rowCounts = jsonb("row_counts").nullable()
        val errorDetail = text("error_detail").nullable()
        override val primaryKey = PrimaryKey(id)
    }

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

        val runId = suspendTransaction(database) {
            DeriveRuns.insert {
                it[DeriveRuns.connectionId] = connectionId.toInt()
                it[DeriveRuns.jobId] = jobId.toInt()
                it[DeriveRuns.configRevision] = settings.configRevision
                it[DeriveRuns.processingVersion] = PROCESSING_VERSION
                it[DeriveRuns.startedAt] = now
                it[DeriveRuns.status] = "RUNNING"
            }[DeriveRuns.id]
        }

        try {
            val workItems = workItemStore.workItemsForDerivation(connectionId)
            val dimDateFrom = (workItems.minOfOrNull { it.createdAt } ?: now) - ONE_YEAR_MS
            metricsStore.upsertDimDate(calendar.dimDateRows(dimDateFrom, now + TWO_YEARS_MS), settings.configRevision)

            val result = compose(connectionId, workItems, config, calendar, now, settings.hoursPerDay, settings.epicDriftDays)
            writeResult(connectionId, result, settings.configRevision)
            markRunSucceeded(runId, result, context.clock())
            context.heartbeat(null, "derive")
        } catch (failure: Exception) {
            markRunFailed(runId, failure, context.clock())
            throw failure
        }
        return settings.configRevision
    }

    private suspend fun writeResult(connectionId: UInt, result: ComposedResult, configRevision: Long) = suspendTransaction(database) {
        metricsStore.replaceDims(connectionId, result.domains, result.tasks, result.epics, configRevision)
        metricsStore.replaceBridges(
            connectionId,
            result.taskEpic,
            result.taskDomain,
            result.taskAssignee,
            result.taskSprint,
            result.itemEstimate,
            result.itemStage,
            result.itemBlocked,
        )
        metricsStore.replaceFactTaskDelivery(connectionId, result.factTasks, configRevision)
        metricsStore.replaceFactEpicDelivery(connectionId, result.factEpics, configRevision)
    }

    private suspend fun markRunSucceeded(runId: Int, result: ComposedResult, finishedAt: Long) {
        val counts = buildJsonObject {
            put("tasks", JsonPrimitive(result.factTasks.size))
            put("epics", JsonPrimitive(result.factEpics.size))
        }.toString()
        suspendTransaction(database) {
            DeriveRuns.update({ DeriveRuns.id eq runId }) {
                it[status] = "SUCCEEDED"
                it[DeriveRuns.finishedAt] = finishedAt
                it[rowCounts] = counts
            }
        }
    }

    private suspend fun markRunFailed(runId: Int, failure: Exception, finishedAt: Long) {
        suspendTransaction(database) {
            DeriveRuns.update({ DeriveRuns.id eq runId }) {
                it[status] = "FAILED"
                it[DeriveRuns.finishedAt] = finishedAt
                it[errorDetail] = failure.message?.take(MAX_ERROR_DETAIL_LENGTH)
            }
        }
    }

    /** Everything one DERIVE run writes for tasks/epics — assembled purely in memory before the ONE write transaction. */
    private data class ComposedResult(
        val domains: List<DimDomainRow>,
        val tasks: List<DimTaskRow>,
        val epics: List<DimEpicRow>,
        val taskEpic: List<TaskEpicRow>,
        val taskDomain: List<TaskDomainRow>,
        val taskAssignee: List<TaskAssigneeRow>,
        val taskSprint: List<TaskSprintRow>,
        val itemEstimate: List<ItemEstimateRow>,
        val itemStage: List<ItemStageRow>,
        val itemBlocked: List<ItemBlockedRow>,
        val factTasks: List<FactTaskDeliveryRow>,
        val factEpics: List<FactEpicDeliveryRow>,
    )

    private suspend fun buildContext(
        connectionId: UInt,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
    ): DeriveContext {
        val estimateFieldIds = listOfNotNull(config.fields.estimateTask, config.fields.estimateEpic).distinct()
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
        val perIssue = PerIssueData(
            itemsById = workItems.associateBy { it.issueId },
            sprintBoardById = workItemStore.allSprintRefs(connectionId).associate { it.sprintId to it.boardId },
            membershipsByAccount = teamMembership.allMembershipsByAccount(),
            statusIntervalsByIssue = workItemStore.statusIntervalsByIssue(connectionId),
            assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE),
            flaggedIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.FLAGGED),
            sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT),
            parentIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.PARENT),
            worklogsByIssue = workItemStore.worklogsByIssue(connectionId),
            estimateChangesByIssueAndField = workItemStore.fieldChangesByFieldIds(connectionId, estimateFieldIds).groupBy { it.issueId },
            issueKeyChangesByIssue = workItemStore.fieldChangesByFieldIds(connectionId, listOf(ISSUE_KEY_FIELD_ID)).groupBy { it.issueId },
        )
        return DeriveContext(configMaps, perIssue, calendar, now, hoursPerDay)
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
        val ownWorklogSeconds = context.worklogsByIssue[item.issueId].orEmpty().sumOf { it.timeSpentSeconds }
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
        val actualMd = actualMdFor(item, childSubtasks, context.worklogsByIssue, context.hoursPerDay)
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

    private suspend fun compose(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
    ): ComposedResult {
        val context = buildContext(connectionId, config, calendar, now, hoursPerDay, epicDriftDays, workItems)
        val derivedById = workItems.associate { it.issueId to deriveItem(it, context, config) }

        val tasks = mutableListOf<DimTaskRow>()
        val epics = mutableListOf<DimEpicRow>()
        val taskEpic = mutableListOf<TaskEpicRow>()
        val taskDomain = mutableListOf<TaskDomainRow>()
        val taskAssignee = mutableListOf<TaskAssigneeRow>()
        val itemEstimate = mutableListOf<ItemEstimateRow>()
        val itemStage = mutableListOf<ItemStageRow>()
        val itemBlocked = mutableListOf<ItemBlockedRow>()
        val factTasks = mutableListOf<FactTaskDeliveryRow>()
        val factEpics = mutableListOf<FactEpicDeliveryRow>()
        val blockedByIssue = mutableMapOf<Long, Pair<Long, Double>>()
        val factTasksByIssueId = mutableMapOf<Long, FactTaskDeliveryRow>()

        // Pass 1: item-level bridges shared by tasks AND epics alike (item_stage/item_blocked/
        // item_estimate — `.claude/docs/domain-model.md`'s bridges table has no task/epic split for
        // these three) — computed for every item up front, regardless of role.
        for (item in workItems) {
            val derived = derivedById.getValue(item.issueId)
            val blockedMs = derived.blocked.sumOf { it.toAtMs - it.fromAtMs }
            val blockedWorkingDays = derived.blocked.sumOf { calendar.workingDaysBetween(it.fromAtMs, it.toAtMs) }
            blockedByIssue[item.issueId] = blockedMs to blockedWorkingDays
            itemStage += derived.stages.map { ItemStageRow(item.issueId, it.stage.name, it.statusId, it.fromAtMs, it.toAtMs) }
            itemBlocked += derived.blocked.map { ItemBlockedRow(item.issueId, "FLAGGED", it.fromAtMs, it.toAtMs) }
            itemEstimate += buildEstimateBridge(item, derived.estimateTimeline, context.estimateFieldIdFor(item, config))
        }

        // Pass 2: tasks (review round 2a: "skip epics for task_* bridges" — task_epic/task_domain/
        // task_assignee are TASK-only bridges) — builds fact_task_delivery too, caching it so pass 3
        // can roll an epic's cost/estimate up from its ALREADY-derived children (D2, finding 6).
        for (item in workItems) {
            if (item.hierarchyLevel == EPIC_HIERARCHY_LEVEL) continue
            val derived = derivedById.getValue(item.issueId)
            val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
            val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
            val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)

            taskEpic += taskEpicHistory(item, context)
            taskDomain += taskDomainHistory(item, context)
            taskAssignee += taskAssigneeHistory(item, context)

            val composition =
                buildTaskRow(item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays)
            tasks += composition.dim
            factTasks += composition.fact
            factTasksByIssueId[item.issueId] = composition.fact
        }

        // Pass 3: epics — reads pass 2's cached `factTasksByIssueId` for the D2 roll-up.
        for (item in workItems) {
            if (item.hierarchyLevel != EPIC_HIERARCHY_LEVEL) continue
            val derived = derivedById.getValue(item.issueId)
            val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
            val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
            val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)

            val (dim, fact) = buildEpicRow(
                item, derived, context, workItems, derivedById, factTasksByIssueId, domainKey, currentStage, blockedMs, blockedWorkingDays,
            )
            epics += dim
            factEpics += fact
        }

        return ComposedResult(
            domainDims(context), tasks, epics, taskEpic, taskDomain, taskAssignee, emptyList(), itemEstimate, itemStage, itemBlocked,
            factTasks, factEpics,
        )
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
        worklogsByIssue: Map<Long, List<WorkItemStore.DerivationWorklogRow>>,
        hoursPerDay: Double,
    ): Double {
        val ownSeconds = worklogsByIssue[item.issueId].orEmpty().sumOf { it.timeSpentSeconds }
        val childSeconds = children.sumOf { child -> worklogsByIssue[child.issueId].orEmpty().sumOf { it.timeSpentSeconds } }
        return (ownSeconds + childSeconds) / SECONDS_PER_HOUR / hoursPerDay
    }
}
