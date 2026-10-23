package ch.nokillswit.metrics

import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket

/**
 * The worklog step's own body (`.claude/docs/metrics.md` "Worklog cost facts (fact_worklog)", `.claude/docs/domain-model.md`
 * "Cross-team time"/D3): one `fact_worklog` row per `norm.work_item_worklogs` row of a live item, carrying the author's team AND
 * the task's domain/epic, BOTH as-of the worklog's own `started_at` — never the item's current/done-time values.
 *
 * **Two writers, split by what the item is to the model (A31).** The worklogs of every TASK, sub-task and EPIC come from ONE
 * `INSERT ... SELECT` ([worklogFactSql]) over the bridges and dimensions this run has already written — no worklog row is ever
 * materialized in the JVM for them. The worklogs of an item ABOVE the epic level (a Premium "Initiative", rare) have no `dim_*`
 * row to read, and their work category is a JSON read through a kernel, so they are attributed here in the JVM, batch by batch,
 * like every worklog was before (the review round 2b memory bound still applies to them).
 *
 * Returns the connection's `fact_worklog` row count.
 */
internal suspend fun runWorklogStep(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    configRevision: Long,
): Int {
    val sprintTeams = context.sprintBoardById.mapNotNull { (sprintId, boardId) ->
        boardId?.let { context.boardTeamByBoardId[it] }?.let { sprintId to it }
    }.toMap()
    metricsStore.insertFactWorklogFromBridges(
        worklogFactSql(connectionId, configRevision, context.hoursPerDay, sprintTeams, context.activityTypeByIssueType),
    )

    // `context.worklogSecondsByIssue` has a key for every issue with at least one worklog row, so it picks the items to batch; each
    // batch then reads only ITS issues' worklog rows (a row that vanished between the two statements just yields no fact rows).
    val aboveEpic = workItems.filter { hierarchyBucket(it.hierarchyLevel) == null && context.worklogSecondsByIssue.containsKey(it.issueId) }
    for (batch in aboveEpic.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        val worklogsByIssue = workItemStore.worklogsByIssue(connectionId, ids)
        // The assignee half of the informational columns needs each batch's OWN assignee intervals.
        context.assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)
        val rowsBatch = batch.flatMap { aboveEpicWorklogRows(it, worklogsByIssue[it.issueId].orEmpty(), context) }
        metricsStore.insertFactWorklog(connectionId, rowsBatch, configRevision)
    }
    return metricsStore.countFactWorklog(connectionId)
}

/** `time_spent_seconds` in man-days: `seconds / 3600 / hoursPerDay`, in doubles in this order (the SQL statement does the same). */
internal fun worklogMd(timeSpentSeconds: Long, hoursPerDay: Double): Double = (timeSpentSeconds / SECONDS_PER_HOUR) / hoursPerDay

/**
 * An above-epic item's `fact_worklog` rows (A31): attributed like an epic-logged worklog, minus the epic — the item's own (current)
 * domain, no `epic_id`/`epic_domain_key`/sprint, the item's own work category (there is no epic to fall back to), and A22's foreign
 * work against the owner team of the item's domain. The assignee at `started_at` is still recorded (an informational column).
 */
private fun aboveEpicWorklogRows(
    item: WorkItemStore.DerivationWorkItemRow,
    worklogs: List<WorkItemStore.DerivationWorklogRow>,
    context: DeriveContext,
): List<FactWorklogRow> {
    val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
    val activityType = context.activityTypeByIssueType[item.issueType] ?: item.issueType
    val workCategory = ownWorkCategory(item, context)
    val ownerTeamId = context.ownerTeamByDomain[domainKey]
    return worklogs.map { wl ->
        val authorTeamId = teamAt(wl.authorAccountId, wl.startedAt, context.membershipsByAccount)
        val (assigneeAtStarted, assigneeTeamAtStarted) = assigneeAndTeamAt(item, context, wl.startedAt)
        FactWorklogRow(
            worklogId = wl.worklogId,
            issueId = item.issueId,
            authorAccountId = wl.authorAccountId,
            authorTeamId = authorTeamId,
            startedAt = wl.startedAt,
            createdAt = wl.createdAt,
            lateMs = wl.createdAt?.let { createdAt -> maxOf(0L, createdAt - wl.startedAt) },
            md = worklogMd(wl.timeSpentSeconds, context.hoursPerDay),
            taskDomainKey = domainKey,
            epicId = null,
            epicDomainKey = null,
            activityType = activityType,
            workCategory = workCategory,
            sprintIdAtStarted = null,
            sprintTeamIdAtStarted = null,
            foreignWork = isForeignWork(authorTeamId, null, ownerTeamId),
            assigneeAccountIdAtStarted = assigneeAtStarted,
            assigneeTeamIdAtStarted = assigneeTeamAtStarted,
        )
    }
}
