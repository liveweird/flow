package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar

/*
 * Report 22's figures (`.claude/docs/measures.md` "Report 22", domain-model A51), pure: no database, no clock. The reads
 * (`TaskCodeReads.kt`) hand over the done tasks, the live merge requests that name one of their keys and what each key names in Jira;
 * everything below decides which merge request belongs to which task and computes the numbers. The verdicts (lower is better) are the
 * page's.
 */

/** A Jira issue: its connection and id. */
internal data class IssueRef(val connectionId: UInt, val issueId: Long)

/** One DONE level-0 task of the period: [issueKey] is its key as of the last DERIVE, [startedAt] is null when it never started. */
internal data class TaskCodeTask(val ref: IssueRef, val issueKey: String, val startedAt: Long?, val doneAt: Long)

/** One live merge request that names [key]; [mergedAt] is set exactly when the merge request is MERGED (state `merged` with a date). */
internal data class TaskCodeLink(val key: String, val mr: CodeMrRef, val mergedAt: Long?)

/** A done task and the distinct merge requests linked to it: [merged] and [unmerged] of them, [lastMergedAt] the latest merge. */
internal data class TaskMergeRequests(val task: TaskCodeTask, val merged: Int, val unmerged: Int, val lastMergedAt: Long?) {
    /** At least one linked merge request is merged: the task is "with a merge request". */
    val hasMerged: Boolean get() = merged > 0

    /** Linked merge requests exist but none is merged (open, closed unmerged or locked): "without", and counted apart. */
    val onlyUnmerged: Boolean get() = merged == 0 && unmerged > 0

    /** The last linked merge request merged AFTER the task was done. */
    val mergedAfterDone: Boolean get() = lastMergedAt != null && lastMergedAt > task.doneAt

    /**
     * D6's code lead time: the task's start to its LAST merged merge request, in working days. Null for a task that never started or has
     * no merged merge request. A merge before the start is 0 working days (the calendar clamps a non-positive span), never negative.
     */
    fun codeLeadWorkingDays(calendar: WorkingCalendar): Double? {
        val started = task.startedAt ?: return null
        val merged = lastMergedAt ?: return null
        return calendar.workingDaysBetween(started, merged)
    }

    /** The lag of a task merged after done: its done instant to the last merge, in working days; null for any other task. */
    fun mergeLagWorkingDays(calendar: WorkingCalendar): Double? =
        if (mergedAfterDone) calendar.workingDaysBetween(task.doneAt, lastMergedAt!!) else null
}

/**
 * The distinct merge requests of each task. A merge request belongs to a task through a key the task has ([keysOf]: its key and every
 * old or new key of its `issuekey` changes) that names THAT task alone: a key naming several issues ([resolution], across Jira sources or
 * after a project reused a key) is ambiguous and links to none, and a key that names no live issue links to none. A merge request that
 * names two keys of one task counts once.
 */
internal fun linkTasks(
    tasks: List<TaskCodeTask>,
    keysOf: Map<IssueRef, Set<String>>,
    linksByKey: Map<String, List<TaskCodeLink>>,
    resolution: Map<String, Set<IssueRef>>,
): List<TaskMergeRequests> = tasks.map { task ->
    val linked = keysOf[task.ref].orEmpty()
        .filter { resolution[it] == setOf(task.ref) }
        .flatMap { linksByKey[it].orEmpty() }
        .associateBy { it.mr }.values
    val merged = linked.mapNotNull { it.mergedAt }
    TaskMergeRequests(task, merged.size, linked.size - merged.size, merged.maxOrNull())
}

/** The counts and the raw values behind Report 22's figures; the distributions are built from [leadTimes] and [lags]. */
internal class TaskCodeFigures(
    val done: Int,
    val withMergeRequest: Int,
    val onlyUnmerged: Int,
    val neverStarted: Int,
    val mergedAfterDone: Int,
    val leadTimes: List<Double>,
    val lags: List<Double>,
) {
    val withoutMergeRequest: Int get() = done - withMergeRequest

    /** Without over done; absent while no task is done. */
    val withoutShare: Double? get() = if (done == 0) null else withoutMergeRequest.toDouble() / done

    /** Merged after done over the tasks with a merged merge request; absent when there is none. */
    val mergedAfterDoneShare: Double? get() = if (withMergeRequest == 0) null else mergedAfterDone.toDouble() / withMergeRequest
}

internal fun taskCodeFigures(rows: List<TaskMergeRequests>, calendar: WorkingCalendar): TaskCodeFigures = TaskCodeFigures(
    done = rows.size,
    withMergeRequest = rows.count { it.hasMerged },
    onlyUnmerged = rows.count { it.onlyUnmerged },
    neverStarted = rows.count { it.task.startedAt == null },
    mergedAfterDone = rows.count { it.mergedAfterDone },
    leadTimes = rows.mapNotNull { it.codeLeadWorkingDays(calendar) },
    lags = rows.mapNotNull { it.mergeLagWorkingDays(calendar) },
)

/** How many of the distinct [candidates] name no issue ([unresolved]) and how many name more than one ([ambiguous]). */
internal data class KeyQuality(val unresolved: Int, val ambiguous: Int)

internal fun keyQuality(candidates: Collection<String>, resolution: Map<String, Set<IssueRef>>): KeyQuality {
    val sizes = candidates.map { resolution[it]?.size ?: 0 }
    return KeyQuality(unresolved = sizes.count { it == 0 }, ambiguous = sizes.count { it > 1 })
}
