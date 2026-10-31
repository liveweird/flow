package ch.nokillswit.ingest

import kotlinx.serialization.Serializable

// The GitLab section of the data profile (`DataProfile.gitlab`, `GET /api/v1/data-sources/{id}/profile`): facts about the stored code
// layer, computed by the PROFILE stream (`gitlab/GitLabProfileStream.kt`) from `norm.code_*` and the raw markers. Counts only - no
// per-person figure, no name, no address, no title or path.
// Every field has a default so a profile stored by an older build still decodes.

/** The projects by marker: [live] neither moved out nor deleted, [movedOut] (not deleted), [deleted], and [archived] among the live. */
@Serializable
data class GitLabProjectsProfile(
    val live: Long = 0,
    val movedOut: Long = 0,
    val deleted: Long = 0,
    val archived: Long = 0,
)

/**
 * The live commits: [count] non-merge, [merges], the commit-time range of all live commits (epoch millis), [filesPending] (the COMMIT FILES
 * backlog), [filesUnavailable] (a terminal diff error), [filesCapped] (more files than stored) and [commitsWithCounts] (non-merge commits
 * whose counted lines are known: the coverage of the exclusion-aware figures).
 */
@Serializable
data class GitLabCommitsProfile(
    val count: Long = 0,
    val merges: Long = 0,
    val earliestCommittedAt: Long? = null,
    val latestCommittedAt: Long? = null,
    val filesPending: Long = 0,
    val filesUnavailable: Long = 0,
    val filesCapped: Long = 0,
    val commitsWithCounts: Long = 0,
)

/** The live merge requests: [byState] (GitLab's state names), those with diff stats and their share, and the two non-READ states. */
@Serializable
data class GitLabMergeRequestsProfile(
    val byState: Map<String, Long> = emptyMap(),
    val withDiffStats: Long = 0,
    val withDiffStatsPercent: Double = 0.0,
    val detailsPending: Long = 0,
    val detailsUnreadable: Long = 0,
)

/** The review events of the live merge requests by kind: every kind is present, zero-filled. */
@Serializable
data class GitLabEventsProfile(val byKind: Map<String, Long> = emptyMap())

/**
 * The identities: [users] listed and live (bots included), [bots], [usersDeleted], [usersWithEmail] with its share of [users], [ghosts]
 * (a user id the facts name that the users listing never returned), [emailOnly] (commit authors matching no user) and [commitsUnmatched]
 * (live non-merge commits they authored).
 */
@Serializable
data class GitLabIdentitiesProfile(
    val users: Long = 0,
    val bots: Long = 0,
    val usersDeleted: Long = 0,
    val usersWithEmail: Long = 0,
    val usersWithEmailPercent: Double = 0.0,
    val ghosts: Long = 0,
    val emailOnly: Long = 0,
    val commitsUnmatched: Long = 0,
)

/** What the PROFILE stream stores for a GITLAB data source. */
@Serializable
data class GitLabDataProfile(
    val projects: GitLabProjectsProfile = GitLabProjectsProfile(),
    val commits: GitLabCommitsProfile = GitLabCommitsProfile(),
    val mergeRequests: GitLabMergeRequestsProfile = GitLabMergeRequestsProfile(),
    val events: GitLabEventsProfile = GitLabEventsProfile(),
    val identities: GitLabIdentitiesProfile = GitLabIdentitiesProfile(),
)
