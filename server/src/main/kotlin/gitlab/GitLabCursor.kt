package ch.nokillswit.gitlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ONE JSON configuration of the GitLab streams' `sync_cursors` text (the `JiraCursor.kt` shape): defaults written, unknown
 * keys ignored.
 */
private val CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal inline fun <reified T> encodeCursor(cursor: T): String = CURSOR_JSON.encodeToString(cursor)

internal inline fun <reified T> decodeCursor(text: String): T = CURSOR_JSON.decodeFromString(text)

/**
 * The `projects` stream's position inside one pass: [passStartedAt] (the sweep's reference instant), the index of the configured
 * group being read and the next page of it. Cleared when the pass ends.
 */
@Serializable
internal data class ProjectsCursor(
    val passStartedAt: Long,
    val groupIndex: Int = 0,
    val page: Int = 1,
    /** The configured group paths the pass started with: a cursor is only resumed against the SAME list, in the same order. */
    val groups: List<String> = emptyList(),
)

/** The `users` stream's position inside one pass: [passStartedAt] and the next page of `/users`. Cleared when the pass ends. */
@Serializable
internal data class UsersCursor(val passStartedAt: Long, val page: Int = 1)

/** How a project's commits are walked (`gitlab-integration.md` "Streams"): the first run, a range from the stored head, or a re-list. */
@Serializable
internal enum class WalkMode { FULL, RANGE, RELIST }

/**
 * The `commits` stream's position inside one pass: [passStartedAt] (the re-list sweep's reference instant), the last project whose walk
 * completed (projects are visited in id order, so the pass resumes after it) and the [walk] in progress, if any. Cleared when the
 * pass ends.
 */
@Serializable
internal data class CommitsCursor(
    val passStartedAt: Long,
    val lastCompletedProjectId: Long? = null,
    val walk: CommitsWalkCursor? = null,
)

/**
 * One project's walk: [refName] is the branch, or `<storedHead>..<branch>` for [WalkMode.RANGE]; [since] (ISO) and [sinceMillis] are
 * set for FULL (the backfill floor) and RELIST (the oldest live stored commit, the tombstone sweep's lower bound); [tipSha] is the first
 * commit of page 1, which becomes the stored head when the walk completes.
 */
@Serializable
internal data class CommitsWalkCursor(
    val projectId: Long,
    val mode: WalkMode,
    val refName: String,
    val since: String? = null,
    val page: Int = 1,
    val tipSha: String? = null,
    val sinceMillis: Long? = null,
    /** A RELIST whose listing is complete and whose sweep (the confirmations) is still to do. */
    val listingDone: Boolean = false,
    /** A RELIST's epoch (`raw.gitlab_projects.relist_started_at`): the sweep's candidates are the live commits nothing has seen since. */
    val epochMillis: Long? = null,
) {
    /** The branch being walked: the part after `..` of a range (git refs cannot contain `..`). */
    val branch: String get() = refName.substringAfter("..")
}
