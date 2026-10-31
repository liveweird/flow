package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.format.DateTimeParseException

// The slices of GitLab's commit answers the COMMITS and COMMIT FILES streams read, each reduced to small values INSIDE the response
// transform (`HttpGitLabClient.getPaged`'s convert): the parsed tree is garbage before the caller makes its next call, and a diff's text
// never leaves `summarizeDiffFile`.

/** The failure code of a ref (a branch, tag or SHA) GitLab does not know: `merge_base` answers 400/404 for it. */
const val REF_NOT_FOUND = "REF_NOT_FOUND"

/** A full commit id: 40 hex (SHA-1) or 64 hex (SHA-256 repositories). Anything else is never spliced into a path or stored. */
val SHA_PATTERN = Regex("^[0-9a-f]{40}$|^[0-9a-f]{64}$")

private const val COMMITS_ENDPOINT = "/projects/{id}/repository/commits"
private const val DIFF_ENDPOINT = "/projects/{id}/repository/commits/{sha}/diff"

/**
 * One commit of `/repository/commits?with_stats=true` ([parentCount] > 1 = a merge; [additions]/[deletions] are `stats`, null when
 * GitLab sent none). [title] is GitLab's first line, no message body is kept.
 */
class GitLabCommitSummary(
    val sha: String,
    val title: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAt: Long,
    val committedAt: Long,
    val parentCount: Int,
    val additions: Int?,
    val deletions: Int?,
)

/** One page of commits with the paging fields of [GitLabPage]. */
class GitLabCommitPage(val commits: List<GitLabCommitSummary>, val nextPage: Int?, val total: Long?)

/** One page of a commit's `/diff`, every file reduced to its path, flags and counted lines (the stored shape). */
class GitLabDiffPage(val files: List<RawGitLabCommitFile>, val nextPage: Int?)

private fun invalid(endpoint: String, detail: String? = null) = FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, endpoint, detail)

private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.millis(key: String): Long {
    val text = text(key) ?: throw invalid(COMMITS_ENDPOINT, "a commit without $key")
    return try {
        parseGitLabInstantMillis(text)
    } catch (_: DateTimeParseException) {
        throw invalid(COMMITS_ENDPOINT, "a commit with an unreadable $key")
    }
}

private fun JsonObject.statLines(key: String): Int? = ((get("stats") as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

internal fun summarizeCommit(item: JsonElement): GitLabCommitSummary {
    val commit = item as? JsonObject ?: throw invalid(COMMITS_ENDPOINT)
    val sha = commit.text("id")?.takeIf { SHA_PATTERN.matches(it) } ?: throw invalid(COMMITS_ENDPOINT, "a commit without a valid id")
    val parents = commit["parent_ids"] as? JsonArray ?: throw invalid(COMMITS_ENDPOINT, "a commit without parent_ids")
    return GitLabCommitSummary(
        sha = sha,
        title = commit.text("title") ?: commit.text("message")?.lineSequence()?.firstOrNull().orEmpty(),
        authorName = commit.text("author_name").orEmpty(),
        authorEmail = commit.text("author_email").orEmpty(),
        authoredAt = commit.millis("authored_date"),
        committedAt = commit.millis("committed_date"),
        parentCount = parents.size,
        additions = commit.statLines("additions"),
        deletions = commit.statLines("deletions"),
    )
}

/**
 * One `/diff` entry reduced to [RawGitLabCommitFile]: `new_path` is the key, `old_path` is kept for a rename only, and the lines are
 * [countHunkLines] of the `diff` text - or null for a file GitLab sent `too_large` or `collapsed` (it sends no hunks for those) or
 * without a `diff` string. The text is read once here and not kept.
 */
internal fun summarizeDiffFile(item: JsonElement): RawGitLabCommitFile {
    val file = item as? JsonObject ?: throw invalid(DIFF_ENDPOINT)
    val path = file.text("new_path") ?: throw invalid(DIFF_ENDPOINT, "a file without new_path")
    val renamed = file.flag("renamed_file")
    // No hunks to count (too_large / collapsed) or no `diff` string at all: the lines are UNKNOWN (null), never 0 - an undercount is
    // worse than a gap. An empty string is a real empty diff (a pure rename, a mode change): 0/0.
    val text = file.text("diff")
    val lines = if (file.flag("too_large") || file.flag("collapsed") || text == null) null else countHunkLines(text)
    return RawGitLabCommitFile(
        path = path,
        oldPath = if (renamed) file.text("old_path") else null,
        additions = lines?.additions,
        deletions = lines?.deletions,
        newFile = file.flag("new_file"),
        deletedFile = file.flag("deleted_file"),
        renamedFile = renamed,
        generatedFile = file.flag("generated_file"),
    )
}

/**
 * One row per path: GitLab can list a path twice in one commit (a file deleted and added again at the same path, e.g. a type change),
 * and the store keys a file by `(commit, path)`. The entries are merged, in first-seen order: lines are SUMMED (unknown when either is),
 * `new_file` / `deleted_file` hold only when EVERY entry says so (delete + add is a modified file), `renamed` / `generated` when any does.
 */
internal fun List<RawGitLabCommitFile>.mergedByPath(): List<RawGitLabCommitFile> {
    val byPath = LinkedHashMap<String, RawGitLabCommitFile>()
    for (file in this) {
        byPath[file.path] = byPath[file.path]?.let { first ->
            RawGitLabCommitFile(
                path = file.path,
                oldPath = first.oldPath ?: file.oldPath,
                additions = sumOrNull(first.additions, file.additions),
                deletions = sumOrNull(first.deletions, file.deletions),
                newFile = first.newFile && file.newFile,
                deletedFile = first.deletedFile && file.deletedFile,
                renamedFile = first.renamedFile || file.renamedFile,
                generatedFile = first.generatedFile || file.generatedFile,
            )
        } ?: file
    }
    return byPath.values.toList()
}

private fun sumOrNull(a: Int?, b: Int?): Int? = if (a == null || b == null) null else a + b

// The commit row shapes the raw store takes and hands back (V42): what the COMMITS and COMMIT FILES streams write and read.

/** One default-branch commit as the COMMITS stream hands it over (no body, committer or diff); [additions]/[deletions] are `stats`. */
class RawGitLabCommit(
    val sha: String,
    val title: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAt: Long,
    val committedAt: Long,
    val parentCount: Int,
    val additions: Int?,
    val deletions: Int?,
)

/** One file of a commit's `/diff`: [additions]/[deletions] null = GitLab sent it too large or collapsed; a binary file is 0/0. */
class RawGitLabCommitFile(
    val path: String,
    val oldPath: String?,
    val additions: Int?,
    val deletions: Int?,
    val newFile: Boolean,
    val deletedFile: Boolean,
    val renamedFile: Boolean,
    val generatedFile: Boolean,
)

/** A commit's complete file list; [capped] = the 1,000-file cap cut it. */
class CommitFilesResult(val files: List<RawGitLabCommitFile>, val capped: Boolean)

/** The terminal `files_error` values (V42): a diff page over the response ceiling, and a commit GitLab no longer has. */
internal const val FILES_ERROR_LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
internal const val FILES_ERROR_NOT_FOUND = "NOT_FOUND"

/** What the COMMIT FILES stream settled for one commit: its [result], or a terminal [error] (`LIMIT_EXCEEDED`, `NOT_FOUND`) with none. */
class CommitFilesOutcome(val projectId: Long, val sha: String, val result: CommitFilesResult?, val error: String?) {
    init {
        require((result == null) != (error == null)) { "a commit-files outcome carries exactly one of a result and an error" }
    }
}

/** A non-merge live commit whose `/diff` has not been read. */
class PendingCommit(val projectId: Long, val sha: String)

/** A live project as the COMMITS stream reads it: its default branch, the stored head and whether a re-list was requested. */
class CommitsProjectRow(val projectId: Long, val defaultBranch: String?, val headSha: String?, val relistRequested: Boolean)
