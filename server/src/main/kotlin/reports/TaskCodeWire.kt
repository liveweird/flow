package ch.nokillswit.reports

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * The wire shapes of `GET /api/v1/reports/task-code` (Report 22, `.claude/docs/reports.md` "Report 22", domain-model A51). No behaviour
 * here: `TaskCodeKernels.kt` computes, `TaskCodeReads.kt` reads, `TaskCodeReport.kt` assembles. The report is verdict-free: the page
 * colours the lower-is-better figures (code lead time, merged after done); `tasks.withoutShare` is NEUTRAL context.
 */

/**
 * The done tasks of the period and how many of them have a MERGED merge request naming them. [withoutShare] is
 * [withoutMergeRequest] over [done] (absent while no task is done): a neutral figure, no verdict is drawn on it (a task may
 * legitimately need no code).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskCodeTasks(
    val done: Int,
    val withMergeRequest: Int,
    val withoutMergeRequest: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val withoutShare: Double? = null,
)

/**
 * The done tasks whose LAST linked merge request merged after the task was done: [count], its [share] of the tasks with a merged merge
 * request (absent when there is none) and the [lagWorkingDays] from the task's done instant to that last merge.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskCodeMergedAfterDone(
    val count: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val share: Double? = null,
    val lagWorkingDays: Distribution,
)

/**
 * What the two layers hold that no figure above can use, all counts of done tasks of the period: [neverStarted] (no code lead time),
 * [onlyUnmergedMrs] (linked merge requests, none merged) and [tasksWithAmbiguousKey] (a key of the task names more than one live issue,
 * so it linked nothing through that key).
 */
@Serializable
data class TaskCodeQuality(
    val neverStarted: Int,
    val onlyUnmergedMrs: Int,
    val tasksWithAmbiguousKey: Int,
)

/**
 * `GET /api/v1/reports/task-code`. [meta] is the Jira star's (the roster, names and sprints come from it); the freshness of the merge
 * requests is [sources] (the GitLab sources read, as Report 21 prints them).
 */
@Serializable
data class TaskCodeReport(
    val meta: ReportMeta,
    val sources: List<CodeActivitySource>,
    val tasks: TaskCodeTasks,
    val codeLeadTimeWorkingDays: Distribution,
    val mergedAfterDone: TaskCodeMergedAfterDone,
    val quality: TaskCodeQuality,
)
