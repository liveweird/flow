package ch.nokillswit.gitlab

// The raw-row shapes the PROCESS step reads (`GitLabRawProcessStore`, V44's writer): exactly the columns the normalizer needs. Pure data;
// nothing here carries a payload, a description or a note body (the raw store has no column for them).

/** A raw commit's primary key within a connection; the keyset the commit claim scan pages by (project, then sha). */
data class CommitKey(val projectId: Long, val sha: String)

/** One `raw.gitlab_commits` row as PROCESS reads it (a tombstone included: [deletedAt] is mirrored). [parentCount] > 1 = a merge. */
data class RawCommitForProcessing(
    val projectId: Long,
    val sha: String,
    val title: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAt: Long,
    val committedAt: Long,
    val parentCount: Int,
    val additions: Int?,
    val deletions: Int?,
    val filesChanged: Int?,
    val filesCapped: Boolean,
    val filesFetchedAt: Long?,
    val filesError: String?,
    val deletedAt: Long?,
) {
    val key: CommitKey get() = CommitKey(projectId, sha)
}

/** One stored file of a commit: the NEW path (never `old_path`) and its lines, null = unknown (too large, collapsed, no diff). */
data class RawCommitFileForProcessing(val path: String, val additions: Int?, val deletions: Int?)

/** One `raw.gitlab_merge_requests` row as PROCESS reads it; the id lists are the sorted JSON arrays decoded. */
data class RawMergeRequestForProcessing(
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
    val additions: Int?,
    val deletions: Int?,
    val fileCount: Int?,
    val notesCapped: Boolean,
    val changedAt: Long,
    val detailsSyncedAt: Long?,
    val detailsError: String?,
    val deletedAt: Long?,
)

/** One `raw.gitlab_mr_events` row; [source] is the stored code (`NOTE`, `APPROVAL`, `REVIEWER`), [action] the action or reviewer state. */
data class RawMrEventForProcessing(
    val source: String,
    val sourceId: Long,
    val actorId: Long?,
    val eventAt: Long?,
    val system: Boolean,
    val action: String,
    val targets: List<String>,
)

/** One `raw.gitlab_users` row (live or deleted) for the identity map and the identity rows. */
data class RawUserForNorm(
    val userId: Long,
    val username: String,
    val name: String,
    val state: String,
    val bot: Boolean,
    val emails: List<String>,
    val deletedAt: Long?,
)

/**
 * A distinct commit author address of the live commits (as stored) with the name and the commit time and sha of its NEWEST commit, so the
 * caller folds spellings of one mailbox and still picks the newest name (ties: the smallest sha).
 */
data class CommitAuthor(val email: String, val name: String, val committedAt: Long, val sha: String)
