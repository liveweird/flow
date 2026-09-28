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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** Per-item derived quantities shared by both the epic and task write paths — computed once per issue. */
private data class ItemDerived(
    val stages: List<StageInterval>,
    val started: Long?,
    val done: Long?,
    val reopenCount: Int,
    val blocked: List<BlockedInterval>,
    val ownSnapshots: EstimateSnapshots,
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
    val worklogsByIssue: Map<Long, List<WorkItemStore.DerivationWorklogRow>>,
    val estimateChangesByIssueAndField: Map<Long, List<FieldChangeRow>>,
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
    val worklogsByIssue get() = perIssue.worklogsByIssue
    val estimateChangesByIssueAndField get() = perIssue.estimateChangesByIssueAndField
    val workCategoryFieldId get() = configMaps.workCategoryFieldId

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
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** The DERIVE job's own run — `context.claim.connectionId`/`context.claim.id`; heartbeats once after the write commits. */
    suspend fun derive(context: SyncJobRunContext) {
        val connectionId = context.claim.connectionId
        val jobId = context.claim.id
        val now = clock()
        val settings = metricsConfig.read()
        val config = metricsConfig.effectiveConfig(connectionId)
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

            val result = compose(connectionId, workItems, config, calendar, now, settings.hoursPerDay)
            writeResult(connectionId, result, settings.configRevision)
            markRunSucceeded(runId, result)
            context.heartbeat(null, "derive")
        } catch (failure: Exception) {
            markRunFailed(runId, failure)
            throw failure
        }
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

    private suspend fun markRunSucceeded(runId: Int, result: ComposedResult) {
        val counts = buildJsonObject {
            put("tasks", JsonPrimitive(result.factTasks.size))
            put("epics", JsonPrimitive(result.factEpics.size))
        }.toString()
        suspendTransaction(database) {
            DeriveRuns.update({ DeriveRuns.id eq runId }) {
                it[status] = "SUCCEEDED"
                it[finishedAt] = clock()
                it[rowCounts] = counts
            }
        }
    }

    private suspend fun markRunFailed(runId: Int, failure: Exception) {
        suspendTransaction(database) {
            DeriveRuns.update({ DeriveRuns.id eq runId }) {
                it[status] = "FAILED"
                it[finishedAt] = clock()
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
        )
        val perIssue = PerIssueData(
            itemsById = workItems.associateBy { it.issueId },
            sprintBoardById = workItemStore.allSprintRefs(connectionId).associate { it.sprintId to it.boardId },
            membershipsByAccount = teamMembership.allMembershipsByAccount(),
            statusIntervalsByIssue = workItemStore.statusIntervalsByIssue(connectionId),
            assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE),
            flaggedIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.FLAGGED),
            sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT),
            worklogsByIssue = workItemStore.worklogsByIssue(connectionId),
            estimateChangesByIssueAndField = workItemStore.fieldChangesByFieldIds(connectionId, estimateFieldIds).groupBy { it.issueId },
        )
        return DeriveContext(configMaps, perIssue, calendar, now, hoursPerDay)
    }

    private fun ownEstimateSnapshots(
        item: WorkItemStore.DerivationWorkItemRow,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
        startedAt: Long?,
        doneAt: Long?,
    ): EstimateSnapshots {
        val fieldId = context.estimateFieldIdFor(item, config) ?: return EstimateSnapshots(null, null, null, false, 0)
        val changes = context.estimateChangesByIssueAndField[item.issueId].orEmpty().filter { it.fieldId == fieldId }
        val current = item.customFields[fieldId]?.jsonPrimitive?.doubleOrNull
        val timeline = DeriveKernels.estimateTimeline(item.createdAt, changes, current)
        return DeriveKernels.estimateSnapshots(timeline, startedAt, doneAt)
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
        val snapshots = ownEstimateSnapshots(item, context, config, startedDone.startedAtMs, startedDone.doneAtMs)
        val category = ownWorkCategory(item, context)
        return ItemDerived(stages, startedDone.startedAtMs, startedDone.doneAtMs, startedDone.reopenCount, blocked, snapshots, category)
    }

    private fun domainDims(context: DeriveContext): List<DimDomainRow> =
        context.domainByProject.entries
            .groupBy({ it.value }) { it.key }
            .map { (domainKey, projectKeys) -> DimDomainRow(domainKey, domainKey, projectKeys.distinct()) }

    private fun buildEpicRow(
        item: WorkItemStore.DerivationWorkItemRow,
        derived: ItemDerived,
        context: DeriveContext,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        derivedById: Map<Long, ItemDerived>,
        domainKey: String,
        currentStage: ItemStage,
        blockedMs: Long,
        blockedWorkingDays: Double,
    ): Pair<DimEpicRow, FactEpicDeliveryRow> {
        val ownCategory = derived.ownCategory
        val dim = DimEpicRow(
            item.issueId, item.issueKey, item.summary, domainKey, ownCategory, currentStage.name, derived.started, item.dueAt,
        )
        val childTasks = workItems.filter { !it.isSubtask && it.parentIssueId == item.issueId }
        val childSum = childTasks.sumOf { derivedById.getValue(it.issueId).ownSnapshots.currentMd ?: 0.0 }
        val ownCurrent = derived.ownSnapshots.currentMd
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
            actualMd = actualMdFor(item, childTasks, context.worklogsByIssue, context.hoursPerDay),
            cycleMs = cycleMs(derived),
            cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
            blockedMs = blockedMs,
            blockedWorkingDays = blockedWorkingDays,
            domainKey = domainKey,
            workCategory = ownCategory,
            driftFlags = emptyList(),
        )
        return dim to fact
    }

    private fun taskEstimate(
        derived: ItemDerived,
        childSubtasks: List<WorkItemStore.DerivationWorkItemRow>,
        derivedById: Map<Long, ItemDerived>,
    ): EstimateComposite {
        val ownCurrent = derived.ownSnapshots.currentMd
        if (ownCurrent != null) {
            return EstimateComposite(derived.ownSnapshots.atStartMd, derived.ownSnapshots.atDoneMd, ownCurrent, "OWN")
        }
        val subtaskSum = childSubtasks.sumOf { derivedById.getValue(it.issueId).ownSnapshots.currentMd ?: 0.0 }
        return if (childSubtasks.isNotEmpty() && subtaskSum > 0.0) {
            EstimateComposite(subtaskSum, subtaskSum, subtaskSum, "SUBTASKS")
        } else {
            EstimateComposite(null, null, null, "NONE")
        }
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
        val taskEpic = TaskEpicRow(item.issueId, epicId, item.createdAt, null)

        val childSubtasks = if (!item.isSubtask) workItems.filter { it.isSubtask && it.parentIssueId == item.issueId } else emptyList()
        val estimate = taskEstimate(derived, childSubtasks, derivedById)
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
            estimateChangesAfterStart = derived.ownSnapshots.changesAfterStart,
            estimatedLate = derived.ownSnapshots.estimatedLate,
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
        return TaskComposition(dimTask, taskEpic, fact)
    }

    private fun cycleMs(derived: ItemDerived): Long? =
        if (derived.started != null && derived.done != null) derived.done - derived.started else null

    private fun cycleWorkingDays(derived: ItemDerived, calendar: WorkingCalendar): Double? =
        if (derived.started != null && derived.done != null) calendar.workingDaysBetween(derived.started, derived.done) else null

    private suspend fun compose(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
    ): ComposedResult {
        val context = buildContext(connectionId, config, calendar, now, hoursPerDay, workItems)
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

        for (item in workItems) {
            val derived = derivedById.getValue(item.issueId)
            val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
            val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
            val blockedMs = derived.blocked.sumOf { it.toAtMs - it.fromAtMs }
            val blockedWorkingDays = derived.blocked.sumOf { calendar.workingDaysBetween(it.fromAtMs, it.toAtMs) }

            itemStage += derived.stages.map { ItemStageRow(item.issueId, it.stage.name, it.statusId, it.fromAtMs, it.toAtMs) }
            itemBlocked += derived.blocked.map { ItemBlockedRow(item.issueId, "FLAGGED", it.fromAtMs, it.toAtMs) }
            itemEstimate += buildEstimateBridge(item, context.estimateChangesByIssueAndField, context.estimateFieldIdFor(item, config))
            taskDomain += TaskDomainRow(item.issueId, domainKey, item.createdAt, null)
            taskAssignee += TaskAssigneeRow(item.issueId, item.assigneeAccountId, item.createdAt, null)
            // `task_sprint`'s full carry-over-aware history (`DeriveKernels.sprintMembership`) is
            // written by commit 8's own sprint step, alongside `dim_sprint` — this commit's bridge
            // table exists (`MetricsStore.replaceBridges` accepts it) but stays empty until then.

            if (item.hierarchyLevel == EPIC_HIERARCHY_LEVEL) {
                val (dim, fact) =
                    buildEpicRow(item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays)
                epics += dim
                factEpics += fact
                continue
            }

            val composition =
                buildTaskRow(item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays)
            tasks += composition.dim
            taskEpic += composition.taskEpic
            factTasks += composition.fact
        }

        return ComposedResult(
            domainDims(context), tasks, epics, taskEpic, taskDomain, taskAssignee, emptyList(), itemEstimate, itemStage, itemBlocked,
            factTasks, factEpics,
        )
    }

    private data class EstimateComposite(val atStart: Double?, val atDone: Double?, val current: Double?, val source: String)
    private data class TaskComposition(val dim: DimTaskRow, val taskEpic: TaskEpicRow, val fact: FactTaskDeliveryRow)

    private fun buildEstimateBridge(
        item: WorkItemStore.DerivationWorkItemRow,
        estimateChangesByIssueAndField: Map<Long, List<FieldChangeRow>>,
        fieldId: String?,
    ): List<ItemEstimateRow> {
        if (fieldId == null) return emptyList()
        val changes = estimateChangesByIssueAndField[item.issueId].orEmpty().filter { it.fieldId == fieldId }
        val current = item.customFields[fieldId]?.jsonPrimitive?.doubleOrNull
        val timeline = DeriveKernels.estimateTimeline(item.createdAt, changes, current)
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
