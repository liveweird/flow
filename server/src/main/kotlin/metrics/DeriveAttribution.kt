package ch.nokillswit.metrics

import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.WorkItemStore

// The task/epic ATTRIBUTION kernels `buildTaskRow`/`buildEpicRow` (DeriveTaskRows.kt) and the worklog step compose: the epic of an
// item, the effective-dated `task_epic`/`task_domain` histories, and the as-of team/assignee/domain reads. Pure, no instance state.

/** D2: a sub-task's own epic is its parent TASK's epic (one indirection); a level-0 task's epic is its own `parentIssueId`. */
internal fun epicIdOf(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): Long? {
    val parent = item.parentIssueId?.let { context.itemsById[it] } ?: return null
    val candidate = if (item.isSubtask) parent.parentIssueId else item.parentIssueId
    return candidate?.takeIf { isEpicItem(context.itemsById[it]) }
}

/**
 * `task_epic`'s effective-dated history (review round 2a fix — was one open row carrying only
 * the CURRENT epic). Built from the item's OWN `PARENT` field intervals, kept ONLY when the
 * interval's parent id resolves to an epic ([hierarchyBucket]) (a defensive filter — a
 * level-0 task's PARENT is expected to always be an epic or nothing, never another task).
 * **Sub-tasks get NO `task_epic` row at all** (D2's roll-up, `.claude/docs/domain-model.md`): a
 * sub-task's own PARENT interval names its parent TASK, not an epic, and reconstructing "which
 * epic was my parent TASK under, at each historical instant" needs a second effective-dated join
 * (parent-of-parent, over time) this commit does not build — a sub-task's CURRENT epic stays
 * available via `dim_task.epic_id` (`epicIdOf`, D2's one-indirection rule) for ordinary reads;
 * only the BRIDGE's own history is the part left undone, documented in `.claude/docs/metrics.md`.
 */
internal fun taskEpicHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskEpicRow> {
    if (item.isSubtask) return emptyList()
    return context.parentIntervalsByIssue[item.issueId].orEmpty().map { interval ->
        val parentId = interval.valueId?.toLongOrNull()
        val epicId = parentId?.takeIf { isEpicItem(context.itemsById[it]) }
        TaskEpicRow(item.issueId, epicId, interval.fromAtMs, interval.toAtMs)
    }
}

/** `task_domain`'s effective-dated history (review round 2a fix) — see [projectKeyTimeline]. */
internal fun taskDomainHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskDomainRow> {
    val issueKeyChanges = context.issueKeyChangesByIssue[item.issueId].orEmpty()
    val timeline = projectKeyTimeline(item.createdAt, issueKeyChanges, item.issueKey)
    return timeline.mapIndexed { index, point ->
        val to = timeline.getOrNull(index + 1)?.atMs
        val domainKey = point.projectKey?.let { context.domainByProject[it] ?: it }
        TaskDomainRow(item.issueId, domainKey, point.atMs, to)
    }
}

internal fun valueAt(intervals: List<NormalizedFieldInterval>, atMs: Long): String? =
    intervals.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }?.valueId

internal fun teamAt(accountId: String?, atMs: Long, memberships: Map<String, List<TeamMembershipService.MembershipInterval>>): UInt? {
    if (accountId == null) return null
    return memberships[accountId]?.firstOrNull { it.validFrom <= atMs && (it.validTo == null || atMs < it.validTo) }?.teamId
}

/** The task's assignee (and their team) at [atMs] — the assignee field interval's own value, then team_membership at that instant. */
internal fun assigneeAndTeamAt(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext, atMs: Long): Pair<String?, UInt?> {
    val accountId = valueAt(context.assigneeIntervalsByIssue[item.issueId].orEmpty(), atMs)
    return accountId to teamAt(accountId, atMs, context.membershipsByAccount)
}

/**
 * A21's "sprint team known, else assignee team" rule (D5's delivery-credit fallback, and the
 * `fact_worklog` foreign-work fallback): `true` only when the author's team is known AND it
 * differs from whichever of sprint/assignee team resolves first — never true off an unknown team
 * on either side.
 */
internal fun isForeignWork(authorTeamId: UInt?, sprintTeamId: UInt?, assigneeTeamId: UInt?): Boolean = when {
    authorTeamId == null -> false
    sprintTeamId != null -> authorTeamId != sprintTeamId
    else -> assigneeTeamId != null && authorTeamId != assigneeTeamId
}

/** A21: current-instant D5 team + the open `task_assignee` row — [buildTaskRow]'s own doc. */
internal data class CurrentAttribution(val teamId: UInt?, val assigneeAccountId: String?)

/**
 * A21/A22: D5 evaluated at [DeriveContext.now]. Two A22 corrections over the plain "sprint team,
 * else assignee team" rule: a sprint whose [DeriveContext.sprintClosedById] marks it CLOSED is never
 * an open item's current sprint at all (a not-done task left in a closed sprint is effectively
 * backlog, so this falls straight to the assignee fallback — the closed sprint's team is not simply
 * skipped in favour of a DIFFERENT sprint, since an item is a member of at most one sprint at any
 * instant); and both the sprint's team AND the assignee's team are filtered through
 * [DeriveContext.activeTeamIds] — a board mapping or a stale membership row pointing at a
 * soft-deleted team resolves to NONE here, never a team a report would then have to explain. AS-WAS
 * reads (`credit_team_id`, `sprintTeamIdAtDone`, `fact_worklog`'s author/sprint/assignee teams) are
 * untouched by either correction — only this NOW-evaluated read applies them.
 */
internal fun currentAttribution(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): CurrentAttribution {
    val sprintId = valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), context.now)?.toLongOrNull()
    val openSprintId = sprintId?.takeIf { context.sprintClosedById[it] != true }
    val sprintTeamId = openSprintId?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
        ?.takeIf { it in context.activeTeamIds }
    val (assigneeAccountId, assigneeTeamIdRaw) = assigneeAndTeamAt(item, context, context.now)
    val assigneeTeamId = assigneeTeamIdRaw?.takeIf { it in context.activeTeamIds }
    return CurrentAttribution(sprintTeamId ?: assigneeTeamId, assigneeAccountId)
}

/** A21: domain/epic attribution AS-WAS at [atMs] — [buildTaskRow]'s own doc. */
internal data class AsWasAttribution(val domainKey: String?, val epicId: Long?, val epicDomainKey: String?)

/**
 * (review round 2c bug fix, `.claude/docs/measures.md`) A covering [TaskEpicRow] whose OWN `epicId`
 * is `null` — "this task genuinely had no epic at [atMs]" — must never be conflated with "no history
 * covers [atMs] at all" (a task that has never moved, falling back to its CURRENT epic).
 * [domainRowAt]/[epicRowAt] return the covering row itself (or `null` when none covers [atMs]) so the
 * caller can tell the two cases apart. The DOMAIN is different: a task always has exactly one, so a
 * covering row whose key failed to parse falls back to the current domain (re-review round 2d).
 */
internal fun domainRowAt(history: List<TaskDomainRow>, atMs: Long): TaskDomainRow? =
    history.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }

internal fun epicRowAt(history: List<TaskEpicRow>, atMs: Long): TaskEpicRow? =
    history.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }

internal fun asWasAttribution(
    item: WorkItemStore.DerivationWorkItemRow,
    context: DeriveContext,
    taskDomainRows: List<TaskDomainRow>,
    taskEpicRows: List<TaskEpicRow>,
    currentDomainKey: String,
    currentEpicId: Long?,
    atMs: Long,
): AsWasAttribution {
    val coveringDomain = domainRowAt(taskDomainRows, atMs)
    // A task has exactly one domain at any instant: a covering row whose key failed to parse
    // (a project key longer than the default limit) falls back to the current domain.
    val domainKey = coveringDomain?.domainKey ?: currentDomainKey
    val epicId = if (item.isSubtask) {
        currentEpicId
    } else {
        val coveringEpic = epicRowAt(taskEpicRows, atMs)
        if (coveringEpic != null) coveringEpic.epicId else currentEpicId
    }
    val epicDomainKey = epicId?.let { context.itemsById[it] }?.let { context.domainByProject[it.projectKey] ?: it.projectKey }
    return AsWasAttribution(domainKey, epicId, epicDomainKey)
}
