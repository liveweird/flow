package ch.nokillswit.metrics

import ch.nokillswit.norm.FieldChangeRow
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

/**
 * The projects this DATA SOURCE reads, for `task_domain`'s history (A47): [readProjectKeys] is every project key the data source is
 * CONFIGURED to read (`source_connections.settings.projectKeys`) plus every project key a live work item of the connection carries now
 * (a no-op once the connection has synced, but it keeps the scope total for a row stored under a key since removed from the
 * configuration), and the scope adds every key the domain map names. The configured keys are what keeps a project whose issues all
 * moved to another of the connection's projects IN the scope: the scope never shrinks because a project emptied, so that project's
 * history stays as-was. A project outside it is one this data source does not read (a bulk migration out of a foreign project, say).
 * The domain a key stands for is `domainByProject[key] ?: key` (the rule DERIVE reads a live item's domain by).
 */
internal class DomainScope(val domainByProject: Map<String, String>, readProjectKeys: Set<String>) {
    private val read: Set<String> = readProjectKeys + domainByProject.keys

    /** The domain an item of [projectKey] has NOW: its `domain_map` entry, else the project key itself. */
    fun domainOf(projectKey: String): String = domainByProject[projectKey] ?: projectKey

    /**
     * The domain a `task_domain` row for [historyProjectKey] carries on an item whose project is [currentProjectKey] now (A47): the
     * history project's domain when this data source reads that project (a move BETWEEN its own projects is attributed as-was),
     * else the item's CURRENT domain (a move IN from a project this data source does not read re-attributes nothing). The
     * `currentProjectKey !in read` arm (keep the raw rule, there being no "current domain" to prefer) is a defensive total, not an
     * observed case: every live item's own project is in [read] by construction, so DERIVE never reaches it.
     */
    fun historyDomainKey(historyProjectKey: String, currentProjectKey: String): String =
        if (historyProjectKey in read || currentProjectKey !in read) domainOf(historyProjectKey) else domainOf(currentProjectKey)
}

/**
 * `task_domain`'s effective-dated history (review round 2a fix) — see [projectKeyTimeline]. Built for TASKS and for EPICS alike
 * (A47: an epic's history is what a task's `epic_domain_key` and the PV DOMAIN scope read as-of); the key of each row follows
 * [DomainScope.historyDomainKey]. Adjacent rows that end up with the same key (a move out of a project the data source does not read,
 * or between two projects mapped to one domain) are left as the changelog tiled them: every reader is a covering lookup, none counts rows.
 */
internal fun taskDomainHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskDomainRow> =
    domainHistoryRows(
        item.issueId, item.createdAt, item.issueKey, item.projectKey, context.issueKeyChangesByIssue[item.issueId].orEmpty(),
        context.domainScope,
    )

/** [taskDomainHistory]'s pure body: the item's identifiers instead of its row, so a kernel test needs no work-item row. */
internal fun domainHistoryRows(
    issueId: Long,
    createdAtMs: Long,
    currentIssueKey: String,
    currentProjectKey: String,
    issueKeyChanges: List<FieldChangeRow>,
    scope: DomainScope,
): List<TaskDomainRow> {
    val timeline = projectKeyTimeline(createdAtMs, issueKeyChanges, currentIssueKey)
    return timeline.mapIndexed { index, point ->
        val to = timeline.getOrNull(index + 1)?.atMs
        TaskDomainRow(issueId, point.projectKey?.let { scope.historyDomainKey(it, currentProjectKey) }, point.atMs, to)
    }
}

/**
 * An EPIC's `task_domain` history (A47): [rows] ([taskDomainHistory]) with its first row stretched back to instant 0, so every anchor
 * is covered — a worklog, a plan day or a task done BEFORE the epic's `created_at` (a backdated plan start, an early child) reads the
 * domain the epic had when it was created, never a fallback that flips to the current domain and back. An empty row (an `issuekey`
 * change clamped to the creation instant covers nothing) is dropped first, so the stretched row is the first one that covers.
 * Tasks keep their rows as tiled: their own anchors (`done_at`, `started_at`) never precede their creation.
 */
internal fun coverFromTheStart(rows: List<TaskDomainRow>): List<TaskDomainRow> {
    val covering = rows.filter { it.toAtMs == null || it.toAtMs > it.fromAtMs }
    return covering.mapIndexed { index, row -> if (index == 0) row.copy(fromAtMs = 0L) else row }
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
    // A47: the epic's domain AS-WAS at the same anchor, through the epic's own `issuekey` history; no covering row, or one whose
    // key failed to parse, falls back to the epic's CURRENT domain (the same rule as the task's own domain above).
    val epicDomainKey = epicId?.let { id ->
        val epic = context.itemsById[id]
        val currentEpicDomainKey = epic?.let { context.domainScope.domainOf(it.projectKey) }
        epicDomainKeyAt(context.epicDomainHistoryById[id].orEmpty(), atMs, currentEpicDomainKey)
    }
    return AsWasAttribution(domainKey, epicId, epicDomainKey)
}

/**
 * A47: an epic's domain at [atMs] — the covering `task_domain` row of the EPIC (half-open `[from, to)`, [domainRowAt]) wins; no
 * covering row (an epic with no history; the epic pass stretches the first row to 0, [coverFromTheStart]) or a covering row whose key
 * failed to parse falls back to [currentDomainKey], the epic's domain now. A history row's key is already the epic's current domain
 * when the row's project is not read by the data source ([DomainScope]).
 */
internal fun epicDomainKeyAt(history: List<TaskDomainRow>, atMs: Long, currentDomainKey: String?): String? =
    domainRowAt(history, atMs)?.domainKey ?: currentDomainKey
