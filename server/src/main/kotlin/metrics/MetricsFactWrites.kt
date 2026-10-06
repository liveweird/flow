package ch.nokillswit.metrics

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.metrics.MetricsTables.FactEpicDelivery
import ch.nokillswit.metrics.MetricsTables.FactEpicPlan
import ch.nokillswit.metrics.MetricsTables.FactTaskDelivery
import ch.nokillswit.metrics.MetricsTables.FactWorklog
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.deleteWhere

/**
 * The wholesale-rebuild writes of the per-connection facts the task, worklog and epic-plan steps produce
 * (`fact_task_delivery`, `fact_epic_delivery`, `fact_worklog`, `fact_epic_plan`; [MetricsStore]'s delegations). Holds
 * no database: every method runs in the CALLER's enclosing transaction (`MetricsDeriver`'s).
 */
internal object MetricsFactWrites {

    suspend fun deleteFactTaskDelivery(connectionId: UInt) {
        FactTaskDelivery.deleteWhere { FactTaskDelivery.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_task_delivery` rows — call per batch, AFTER [deleteFactTaskDelivery] ran once. */
    suspend fun insertFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactTaskDelivery.insertRows(rows) {
            this[FactTaskDelivery.connectionId] = connectionId
            this[FactTaskDelivery.issueId] = it.issueId
            this[FactTaskDelivery.issueKey] = it.issueKey
            this[FactTaskDelivery.createdAt] = it.createdAt
            this[FactTaskDelivery.startedAt] = it.startedAt
            this[FactTaskDelivery.doneAt] = it.doneAt
            this[FactTaskDelivery.reopenCount] = it.reopenCount
            this[FactTaskDelivery.estimateAtStartMd] = it.estimateAtStartMd?.toBigDecimal()
            this[FactTaskDelivery.estimateAtDoneMd] = it.estimateAtDoneMd?.toBigDecimal()
            this[FactTaskDelivery.estimateCurrentMd] = it.estimateCurrentMd?.toBigDecimal()
            this[FactTaskDelivery.estimateSource] = it.estimateSource
            this[FactTaskDelivery.estimateChangesAfterStart] = it.estimateChangesAfterStart
            this[FactTaskDelivery.estimatedLate] = it.estimatedLate
            this[FactTaskDelivery.actualMd] = it.actualMd.toBigDecimal()
            this[FactTaskDelivery.hasWorklogs] = it.hasWorklogs
            this[FactTaskDelivery.blockedMs] = it.blockedMs
            this[FactTaskDelivery.blockedWorkingDays] = it.blockedWorkingDays.toBigDecimal()
            this[FactTaskDelivery.cycleMs] = it.cycleMs
            this[FactTaskDelivery.cycleWorkingDays] = it.cycleWorkingDays?.toBigDecimal()
            this[FactTaskDelivery.leadMs] = it.leadMs
            this[FactTaskDelivery.leadWorkingDays] = it.leadWorkingDays?.toBigDecimal()
            this[FactTaskDelivery.activeMs] = it.activeMs
            this[FactTaskDelivery.waitMs] = it.waitMs
            this[FactTaskDelivery.assigneeAccountIdAtDone] = it.assigneeAccountIdAtDone
            this[FactTaskDelivery.assigneeTeamIdAtDone] = it.assigneeTeamIdAtDone
            this[FactTaskDelivery.sprintIdAtDone] = it.sprintIdAtDone
            this[FactTaskDelivery.sprintTeamIdAtDone] = it.sprintTeamIdAtDone
            this[FactTaskDelivery.creditTeamId] = it.creditTeamId
            this[FactTaskDelivery.currentTeamId] = it.currentTeamId
            this[FactTaskDelivery.currentAssigneeAccountId] = it.currentAssigneeAccountId
            this[FactTaskDelivery.domainKey] = it.domainKey
            this[FactTaskDelivery.epicId] = it.epicId
            this[FactTaskDelivery.epicDomainKey] = it.epicDomainKey
            this[FactTaskDelivery.crossDomain] = it.crossDomain
            this[FactTaskDelivery.activityType] = it.activityType
            this[FactTaskDelivery.workCategory] = it.workCategory
            this[FactTaskDelivery.isSubtask] = it.isSubtask
            this[FactTaskDelivery.parentTaskId] = it.parentTaskId
            this[FactTaskDelivery.currentStage] = it.currentStage
            this[FactTaskDelivery.flags] = stringArrayJson(it.flags)
            this[FactTaskDelivery.configRevision] = configRevision
        }
    }

    suspend fun deleteFactEpicDelivery(connectionId: UInt) {
        FactEpicDelivery.deleteWhere { FactEpicDelivery.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_epic_delivery` rows — call per batch, AFTER [deleteFactEpicDelivery] ran once. */
    suspend fun insertFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactEpicDelivery.insertRows(rows) {
            this[FactEpicDelivery.connectionId] = connectionId
            this[FactEpicDelivery.issueId] = it.issueId
            this[FactEpicDelivery.startedAt] = it.startedAt
            this[FactEpicDelivery.doneAt] = it.doneAt
            this[FactEpicDelivery.ownEstimateAtStartMd] = it.ownEstimateAtStartMd?.toBigDecimal()
            this[FactEpicDelivery.ownEstimateAtDoneMd] = it.ownEstimateAtDoneMd?.toBigDecimal()
            this[FactEpicDelivery.ownEstimateCurrentMd] = it.ownEstimateCurrentMd?.toBigDecimal()
            this[FactEpicDelivery.estimateChangesAfterStart] = it.estimateChangesAfterStart
            this[FactEpicDelivery.childSumEstimateMd] = it.childSumEstimateMd.toBigDecimal()
            this[FactEpicDelivery.budgetSource] = it.budgetSource
            this[FactEpicDelivery.actualMd] = it.actualMd.toBigDecimal()
            this[FactEpicDelivery.cycleMs] = it.cycleMs
            this[FactEpicDelivery.cycleWorkingDays] = it.cycleWorkingDays?.toBigDecimal()
            this[FactEpicDelivery.blockedMs] = it.blockedMs
            this[FactEpicDelivery.blockedWorkingDays] = it.blockedWorkingDays.toBigDecimal()
            this[FactEpicDelivery.domainKey] = it.domainKey
            this[FactEpicDelivery.workCategory] = it.workCategory
            this[FactEpicDelivery.driftFlags] = stringArrayJson(it.driftFlags)
            this[FactEpicDelivery.ownerTeamId] = it.ownerTeamId
            this[FactEpicDelivery.configRevision] = configRevision
        }
    }

    suspend fun deleteFactWorklog(connectionId: UInt) {
        FactWorklog.deleteWhere { FactWorklog.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_worklog` rows (v0.3.0 M3 commit 9) — call per batch, AFTER [deleteFactWorklog] ran once. */
    suspend fun insertFactWorklog(connectionId: UInt, rows: List<FactWorklogRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactWorklog.insertRows(rows) {
            this[FactWorklog.connectionId] = connectionId
            this[FactWorklog.worklogId] = it.worklogId
            this[FactWorklog.issueId] = it.issueId
            this[FactWorklog.authorAccountId] = it.authorAccountId
            this[FactWorklog.authorTeamId] = it.authorTeamId
            this[FactWorklog.startedAt] = it.startedAt
            this[FactWorklog.createdAt] = it.createdAt
            this[FactWorklog.lateMs] = it.lateMs
            this[FactWorklog.md] = it.md.toBigDecimal()
            this[FactWorklog.taskDomainKey] = it.taskDomainKey
            this[FactWorklog.epicId] = it.epicId
            this[FactWorklog.epicDomainKey] = it.epicDomainKey
            this[FactWorklog.activityType] = it.activityType
            this[FactWorklog.workCategory] = it.workCategory
            this[FactWorklog.sprintIdAtStarted] = it.sprintIdAtStarted
            this[FactWorklog.sprintTeamIdAtStarted] = it.sprintTeamIdAtStarted
            this[FactWorklog.foreignWork] = it.foreignWork
            this[FactWorklog.assigneeAccountIdAtStarted] = it.assigneeAccountIdAtStarted
            this[FactWorklog.assigneeTeamIdAtStarted] = it.assigneeTeamIdAtStarted
            this[FactWorklog.configRevision] = configRevision
        }
    }

    suspend fun deleteFactEpicPlan(connectionId: UInt) {
        FactEpicPlan.deleteWhere { FactEpicPlan.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_epic_plan` rows (v0.3.0 M3 commit 9b) — call per batch, AFTER [deleteFactEpicPlan] ran once. */
    suspend fun insertFactEpicPlan(connectionId: UInt, rows: List<FactEpicPlanRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactEpicPlan.insertRows(rows) {
            this[FactEpicPlan.connectionId] = connectionId
            this[FactEpicPlan.issueId] = it.issueId
            this[FactEpicPlan.baselineSeq] = it.baselineSeq
            this[FactEpicPlan.baselinedAt] = it.baseline.baselinedAtMs
            this[FactEpicPlan.startAt] = it.baseline.startAtMs
            this[FactEpicPlan.dueAt] = it.baseline.dueAtMs
            this[FactEpicPlan.budgetMd] = it.baseline.budgetMd.toBigDecimal()
            this[FactEpicPlan.budgetSource] = it.baseline.budgetSource
            this[FactEpicPlan.supersededAt] = it.baseline.supersededAtMs
            this[FactEpicPlan.configRevision] = configRevision
        }
    }
}
