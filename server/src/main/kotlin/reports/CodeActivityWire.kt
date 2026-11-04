package ch.nokillswit.reports

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * The wire shapes of `GET /api/v1/reports/code-activity` (Report 21, `.claude/docs/reports.md` "Report 21", domain-model A50). No
 * behaviour here: `CodeActivityKernels.kt` / `CodeActivityMembers.kt` compute, `CodeActivityReport.kt` assembles. The per-member
 * volumes ([CodeActivityGroup]) are NEUTRAL context: nothing here carries a tint, a rank or a verdict.
 */

/**
 * One GitLab source in scope and how fresh its code figures are: [lastSyncAt] is its last successful sync (epoch millis; absent before
 * the first), [commitFilesPending] the commits whose files are still unread and [mrDetailsPending] the merge requests whose details are
 * (both as of the source's last data profile). [baseUrl] is the organisation's own host (D12); the drill links build on it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivitySource(
    val connectionId: UInt,
    val name: String,
    val baseUrl: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastSyncAt: Long? = null,
    val commitFilesPending: Long,
    val mrDetailsPending: Long,
)

/** What the sources hold that no figure can use, connection-wide for the period (`measures.md` "Unattributed work"). */
@Serializable
data class CodeActivityQuality(
    val unlinkedIdentities: Int,
    val unlinkedCommits: Long,
    val unlinkedMergeRequests: Long,
    val unlinkedEvents: Long,
    val commitsLinesUnknown: Long,
    val mrsSizeUnknown: Long,
    val mrsDetailsNotRead: Long,
    val eventsUndated: Long,
)

/** A count and its share of the population it is read against ([share] is absent when that population is empty). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivityShare(val count: Int, @EncodeDefault(EncodeDefault.Mode.NEVER) val share: Double? = null)

/** One Monday-started week of the configured zone ([bucketStart] is its ISO date) and the merge requests merged in it. */
@Serializable
data class CodeActivityWeek(val bucketStart: String, val merged: Int)

/** The team's merge-request flow: the figures the page may colour (lower is better for all but [weekly]). */
@Serializable
data class CodeActivityFlow(
    val merged: Int,
    val closedUnmerged: Int,
    val cycleWorkingDays: Distribution,
    val cycleElapsedDays: Distribution,
    val timeToFirstReviewWorkingDays: Distribution,
    val sizeLines: Distribution,
    val sizeFiles: Distribution,
    val mergedWithoutReview: CodeActivityShare,
    val mergedWithoutApproval: CodeActivityShare,
    val weekly: List<CodeActivityWeek>,
)

/** The age of the waiting open merge requests in working days; the percentiles are absent while [hidden] (under `minSampleSize`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivityOpenAge(
    val n: Int,
    val hidden: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val p50: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val p85: Double? = null,
)

/** The open merge requests now: [count] is the non-draft ones, [drafts] the drafts, counted apart. */
@Serializable
data class CodeActivityOpen(val count: Int, val drafts: Int, val age: CodeActivityOpenAge)

/** Review concentration over [n] merged merge requests whose details were read; both figures are absent under `minSampleSize`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivityReview(
    val n: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reviewersPerMergedMrP50: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val topReviewerShare: Double? = null,
)

/**
 * One member's volumes. [accountId] is the LINKED Jira account; an unlinked identity (`teamId=0` only) has none and is named by
 * [label]. [teamId] is never sent today (every group sits in the one team of the request). Counts are never hidden; the two p50 cells
 * are absent under `minSampleSize`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivityGroup(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val teamId: UInt? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val accountId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
    val commits: Int,
    val filesChanged: Long,
    val linesAdded: Long,
    val linesDeleted: Long,
    val commitsLinesUnknown: Int,
    val mrsMerged: Int,
    val mrsOpened: Int,
    val openMrs: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mrCycleWorkingDaysP50: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mrSizeLinesP50: Double? = null,
    val reviewsGiven: Int,
    val approvalsGiven: Int,
    val changesRequestedGiven: Int,
    val comments: Int,
    val reviewRequestsReceived: Int,
)

/**
 * `GET /api/v1/reports/code-activity`. [meta] is the Jira star's (the roster, names and sprints come from it); the freshness of the
 * code figures is [sources]. [review] is absent while no merged merge request has its details read.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CodeActivityReport(
    val meta: ReportMeta,
    val sources: List<CodeActivitySource>,
    val quality: CodeActivityQuality,
    val flow: CodeActivityFlow,
    val open: CodeActivityOpen,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val review: CodeActivityReview? = null,
    val groups: List<CodeActivityGroup>,
)
