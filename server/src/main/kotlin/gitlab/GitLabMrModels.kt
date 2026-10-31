package ch.nokillswit.gitlab

import kotlinx.serialization.json.JsonElement

// The merge-request row shapes the raw store takes and hands back (V43): what the MERGE REQUESTS and MR DETAILS streams write and read.
// Nothing here carries a description, a note body, a label or a URL - the store has no column for them.

/** The terminal `details_error` values (V43): notes 404 (RECONCILE decides, PR 9); a page over the ceiling; a read that keeps failing. */
const val DETAILS_ERROR_NOT_FOUND = "NOT_FOUND"
const val DETAILS_ERROR_LIMIT_EXCEEDED = "LIMIT_EXCEEDED"

/** A merge request's own REST read kept failing `UPSTREAM_UNAVAILABLE` / `INVALID_RESPONSE` after the transport's retries (MR DETAILS). */
const val DETAILS_ERROR_FETCH_FAILED = "FETCH_FAILED"
private val DETAILS_ERRORS = setOf(DETAILS_ERROR_NOT_FOUND, DETAILS_ERROR_LIMIT_EXCEEDED, DETAILS_ERROR_FETCH_FAILED)

/** The two action codes that may carry `targets` (the @usernames a review-request note names): the only place a username is kept. */
const val MR_ACTION_REVIEW_REQUESTED = "REVIEW_REQUESTED"
const val MR_ACTION_REVIEW_REQUEST_REMOVED = "REVIEW_REQUEST_REMOVED"
val REVIEW_REQUEST_ACTIONS: Set<String> = setOf(MR_ACTION_REVIEW_REQUESTED, MR_ACTION_REVIEW_REQUEST_REMOVED)

/** At most this many `targets` per review-request note, each cut to [MAX_TARGET_CHARS]. */
const val MAX_TARGETS = 20
const val MAX_TARGET_CHARS = 255

/**
 * One merge request as the MERGE REQUESTS stream hands it over. [payload] is the SLIM projection (`MR_KEYS`, no description, labels or
 * URLs), hashed as given; the typed fields duplicate it for the queryable columns. [headSha], [mergeCommitSha] and [squashCommitSha]
 * are stored only when they are full commit ids. A `data class`: a pure row shape (detekt's wide-constructor rule exempts it).
 */
data class RawGitLabMergeRequest(
    val mrId: Long,
    val projectId: Long,
    val iid: Long,
    val state: String,
    val draft: Boolean,
    val authorId: Long?,
    val mergeUserId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val mergedAt: Long?,
    val closedAt: Long?,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val headSha: String?,
    val mergeCommitSha: String?,
    val squashCommitSha: String?,
    val reviewerIds: List<Long>,
    val assigneeIds: List<Long>,
    val userNotesCount: Int?,
    val payload: JsonElement,
)

/** Where an event row came from: a note, the legacy `/approvals` object, or the `/reviewers` list (the `source` column). */
enum class MrEventSource { NOTE, APPROVAL, REVIEWER }

/**
 * One row of `raw.gitlab_mr_events`. [sourceId] is the note id, or the approver's / reviewer's user id; [eventAt] is null for an approver
 * (the endpoint has no time). [action] is a code (never a body). [targets] are the @usernames of a review-request NOTE and nothing else.
 */
class RawGitLabMrEvent(
    val source: MrEventSource,
    val sourceId: Long,
    val actorId: Long?,
    val eventAt: Long?,
    val system: Boolean,
    val noteType: String?,
    val action: String,
    val targets: List<String> = emptyList(),
) {
    init {
        require(targets.isEmpty() || (source == MrEventSource.NOTE && action in REVIEW_REQUEST_ACTIONS)) {
            "targets belong to a review-request note only"
        }
    }
}

/** GraphQL's `diffStatsSummary` of one merge request. */
class GitLabDiffStats(val additions: Int, val deletions: Int, val fileCount: Int) {
    init {
        require(additions >= 0 && deletions >= 0 && fileCount >= 0) { "diff stats are counts" }
    }
}

/**
 * What the MR DETAILS stream settled for one merge request: a COMPLETE read ([error] null; [stats] null = a GraphQL gap, [notesCapped] =
 * the 1,000-note cap cut the events), or a terminal [error] ([DETAILS_ERROR_NOT_FOUND], [DETAILS_ERROR_LIMIT_EXCEEDED]) that carries
 * neither stats nor a cap - a stamp with an error leaves the stored stats and cap as they were.
 */
class MrDetailsOutcome(val notesCapped: Boolean = false, val stats: GitLabDiffStats? = null, val error: String? = null) {
    init {
        require(error == null || error in DETAILS_ERRORS) { "unknown details error" }
        require(error == null || (stats == null && !notesCapped)) { "a terminal details error carries no stats and no notes cap" }
    }
}

/** A live merge request whose details are stale (the MR DETAILS backlog), newest `updated_at` first. */
class StaleMergeRequest(val mrId: Long, val projectId: Long, val iid: Long)

/** The five merge-request figures of the status view (A5 puts them on the DTO). */
class MrCounts(
    val mergeRequests: Long,
    val mergeRequestsMerged: Long,
    val mrEvents: Long,
    val mrDetailsPending: Long,
    val mrDiffStatsMissing: Long,
)
