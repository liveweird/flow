package ch.nokillswit.metrics

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.metrics.MetricsTables.DimDomain
import ch.nokillswit.metrics.MetricsTables.DimEpic
import ch.nokillswit.metrics.MetricsTables.DimTask
import ch.nokillswit.metrics.MetricsTables.ItemBlocked
import ch.nokillswit.metrics.MetricsTables.ItemEstimate
import ch.nokillswit.metrics.MetricsTables.ItemStage
import ch.nokillswit.metrics.MetricsTables.TaskAssignee
import ch.nokillswit.metrics.MetricsTables.TaskDomain
import ch.nokillswit.metrics.MetricsTables.TaskEpic
import ch.nokillswit.metrics.MetricsTables.TaskSprint
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.deleteWhere

/**
 * The wholesale-rebuild writes of the DERIVE task step's dimensions (`dim_domain`/`dim_task`/`dim_epic`) and
 * effective-dated bridges ([MetricsStore]'s delegations). Holds no database: every method runs in the CALLER's
 * enclosing transaction (`MetricsDeriver`'s), exactly as before the split.
 */
internal object MetricsDimWrites {

    /**
     * Deletes `dim_domain`/`dim_task`/`dim_epic` for one connection — call ONCE, before the batched
     * [insertTasks]/[insertEpics] inserts below (v0.3.0 M3 review round 2b: `MetricsDeriver.kt` now
     * writes tasks/epics in batches of 200 rather than one final list, so delete and insert are
     * split into their own methods instead of one `replaceDims` doing both).
     */
    suspend fun deleteDims(connectionId: UInt) {
        DimDomain.deleteWhere { DimDomain.connectionId eq connectionId }
        DimTask.deleteWhere { DimTask.connectionId eq connectionId }
        DimEpic.deleteWhere { DimEpic.connectionId eq connectionId }
    }

    suspend fun insertDomains(connectionId: UInt, domains: List<DimDomainRow>, configRevision: Long) {
        if (domains.isEmpty()) return
        DimDomain.insertRows(domains) {
            this[DimDomain.connectionId] = connectionId
            this[DimDomain.domainKey] = it.domainKey
            this[DimDomain.name] = it.name
            this[DimDomain.projectKeys] = stringArrayJson(it.projectKeys)
            this[DimDomain.ownerTeamId] = it.ownerTeamId
            this[DimDomain.configRevision] = configRevision
        }
    }

    /** One batch's worth of `dim_task` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertTasks(connectionId: UInt, tasks: List<DimTaskRow>, configRevision: Long) {
        if (tasks.isEmpty()) return
        DimTask.insertRows(tasks) {
            this[DimTask.connectionId] = connectionId
            this[DimTask.issueId] = it.issueId
            this[DimTask.issueKey] = it.issueKey
            this[DimTask.issueType] = it.issueType
            this[DimTask.activityType] = it.activityType
            this[DimTask.workCategory] = it.workCategory
            this[DimTask.workCategorySource] = it.workCategorySource
            this[DimTask.isSubtask] = it.isSubtask
            this[DimTask.parentTaskId] = it.parentTaskId
            this[DimTask.domainKey] = it.domainKey
            this[DimTask.epicId] = it.epicId
            this[DimTask.configRevision] = configRevision
        }
    }

    /** One batch's worth of `dim_epic` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertEpics(connectionId: UInt, epics: List<DimEpicRow>, configRevision: Long) {
        if (epics.isEmpty()) return
        DimEpic.insertRows(epics) {
            this[DimEpic.connectionId] = connectionId
            this[DimEpic.issueId] = it.issueId
            this[DimEpic.issueKey] = it.issueKey
            this[DimEpic.summary] = it.summary
            this[DimEpic.domainKey] = it.domainKey
            this[DimEpic.workCategory] = it.workCategory
            this[DimEpic.currentStage] = it.currentStage
            this[DimEpic.startAt] = it.startAt
            this[DimEpic.dueAt] = it.dueAt
            this[DimEpic.configRevision] = configRevision
        }
    }

    /**
     * Wholesale-rebuilds `dim_domain`/`dim_task`/`dim_epic` for one connection in ONE call — the
     * pre-batching shape, kept for a caller (a test, or a future small connection) that has every
     * row in memory already; `MetricsDeriver.kt`'s own batched write calls [deleteDims]/
     * [insertDomains]/[insertTasks]/[insertEpics] directly instead.
     */
    suspend fun replaceDims(
        connectionId: UInt,
        domains: List<DimDomainRow>,
        tasks: List<DimTaskRow>,
        epics: List<DimEpicRow>,
        configRevision: Long,
    ) {
        deleteDims(connectionId)
        insertDomains(connectionId, domains, configRevision)
        insertTasks(connectionId, tasks, configRevision)
        insertEpics(connectionId, epics, configRevision)
    }

    /** Deletes every bridge this commit populates — call ONCE, before the batched insert methods below. */
    suspend fun deleteBridges(connectionId: UInt) {
        TaskEpic.deleteWhere { TaskEpic.connectionId eq connectionId }
        TaskDomain.deleteWhere { TaskDomain.connectionId eq connectionId }
        TaskAssignee.deleteWhere { TaskAssignee.connectionId eq connectionId }
        TaskSprint.deleteWhere { TaskSprint.connectionId eq connectionId }
        ItemEstimate.deleteWhere { ItemEstimate.connectionId eq connectionId }
        ItemStage.deleteWhere { ItemStage.connectionId eq connectionId }
        ItemBlocked.deleteWhere { ItemBlocked.connectionId eq connectionId }
    }

    suspend fun insertTaskEpic(connectionId: UInt, rows: List<TaskEpicRow>) {
        if (rows.isEmpty()) return
        TaskEpic.insertRows(rows) {
            this[TaskEpic.connectionId] = connectionId
            this[TaskEpic.issueId] = it.issueId
            this[TaskEpic.epicId] = it.epicId
            this[TaskEpic.validFrom] = it.fromAtMs
            this[TaskEpic.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskDomain(connectionId: UInt, rows: List<TaskDomainRow>) {
        if (rows.isEmpty()) return
        TaskDomain.insertRows(rows) {
            this[TaskDomain.connectionId] = connectionId
            this[TaskDomain.issueId] = it.issueId
            this[TaskDomain.domainKey] = it.domainKey
            this[TaskDomain.validFrom] = it.fromAtMs
            this[TaskDomain.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskAssignee(connectionId: UInt, rows: List<TaskAssigneeRow>) {
        if (rows.isEmpty()) return
        TaskAssignee.insertRows(rows) {
            this[TaskAssignee.connectionId] = connectionId
            this[TaskAssignee.issueId] = it.issueId
            this[TaskAssignee.accountId] = it.accountId
            this[TaskAssignee.validFrom] = it.fromAtMs
            this[TaskAssignee.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskSprint(connectionId: UInt, rows: List<TaskSprintRow>) {
        if (rows.isEmpty()) return
        TaskSprint.insertRows(rows) {
            this[TaskSprint.connectionId] = connectionId
            this[TaskSprint.issueId] = it.issueId
            this[TaskSprint.sprintId] = it.sprintId
            this[TaskSprint.validFrom] = it.fromAtMs
            this[TaskSprint.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemEstimate(connectionId: UInt, rows: List<ItemEstimateRow>) {
        if (rows.isEmpty()) return
        ItemEstimate.insertRows(rows) {
            this[ItemEstimate.connectionId] = connectionId
            this[ItemEstimate.issueId] = it.issueId
            this[ItemEstimate.estimateMd] = it.estimateMd?.toBigDecimal()
            this[ItemEstimate.validFrom] = it.fromAtMs
            this[ItemEstimate.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemStage(connectionId: UInt, rows: List<ItemStageRow>) {
        if (rows.isEmpty()) return
        ItemStage.insertRows(rows) {
            this[ItemStage.connectionId] = connectionId
            this[ItemStage.issueId] = it.issueId
            this[ItemStage.stage] = it.stage
            this[ItemStage.statusId] = it.statusId
            this[ItemStage.validFrom] = it.fromAtMs
            this[ItemStage.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemBlocked(connectionId: UInt, rows: List<ItemBlockedRow>) {
        if (rows.isEmpty()) return
        ItemBlocked.insertRows(rows) {
            this[ItemBlocked.connectionId] = connectionId
            this[ItemBlocked.issueId] = it.issueId
            this[ItemBlocked.reason] = it.reason
            this[ItemBlocked.validFrom] = it.fromAtMs
            this[ItemBlocked.validTo] = it.toAtMs
        }
    }
}
