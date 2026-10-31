package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.CommitFiles
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.infra.json.replaceNul
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The byte cap of a stored `path`/`old_path`: `path` is part of the primary key, and a B-tree entry over ~2,704 bytes fails the insert,
 * so the key (connection 4 + project 8 + sha up to 64 + path 2,000, plus headers) always fits. The column width (1,024 characters) is
 * the other cap.
 */
internal const val MAX_PATH_BYTES = 2_000

private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private const val THREE_BYTE_LIMIT = 0x10000

/** The UTF-8 size of one code point. */
private fun utf8Size(codePoint: Int): Int = when {
    codePoint < ONE_BYTE_LIMIT -> 1
    codePoint < TWO_BYTE_LIMIT -> 2
    codePoint < THREE_BYTE_LIMIT -> 3
    else -> 4
}

/**
 * A stored file path: the NUL-replaced text cut to at most [GitLabRawStore.MAX_PATH] characters AND [MAX_PATH_BYTES] UTF-8 bytes,
 * always on a code-point boundary (never half a surrogate pair). The result is what the column and the key hold, so two long paths
 * sharing the cut prefix are ONE file.
 */
internal fun cutPath(path: String): String {
    val text = path.replaceNul()
    var end = 0
    var chars = 0
    var bytes = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        bytes += utf8Size(codePoint)
        if (chars == GitLabRawStore.MAX_PATH || bytes > MAX_PATH_BYTES) break
        chars++
        end += Character.charCount(codePoint)
    }
    return text.substring(0, end)
}

/**
 * The COMMIT FILES flush (V42): finished diffs land in ONE transaction - every file row of every outcome that has a result
 * (`ON CONFLICT DO NOTHING`: a file is stored once, a re-flush changes nothing; within one outcome the files are first collapsed by
 * their CUT path, the first wins), then each commit's stamp. A commit is only ever stamped with its files already in, so a run cut
 * anywhere leaves it pending, never half done.
 */
internal class GitLabCommitFileWrites(private val database: R2dbcDatabase) {

    /**
     * Stamps each commit `files_fetched_at = now` with `needs_processing = true`; `files_changed` is the rows STORED (distinct paths AFTER
     * [cutPath]; 1,000 for a capped commit, never GitLab's total) or null when the outcome carries an [CommitFilesOutcome.error] instead
     * of a result.
     */
    suspend fun flushCommitFiles(connectionId: UInt, outcomes: List<CommitFilesOutcome>, now: Long) {
        if (outcomes.isEmpty()) return
        suspendTransaction(database) {
            val stored = outcomes.map { outcome -> outcome to outcome.result?.files?.distinctBy { cutPath(it.path) } }
            val files = stored.flatMap { (outcome, kept) -> kept.orEmpty().map { outcome to it } }
            CommitFiles.insertRowsIgnoringConflicts(files) { (outcome, file) ->
                this[CommitFiles.connectionId] = connectionId
                this[CommitFiles.projectId] = outcome.projectId
                this[CommitFiles.sha] = outcome.sha
                this[CommitFiles.path] = cutPath(file.path)
                this[CommitFiles.oldPath] = file.oldPath?.let(::cutPath)
                this[CommitFiles.additions] = file.additions
                this[CommitFiles.deletions] = file.deletions
                this[CommitFiles.newFile] = file.newFile
                this[CommitFiles.deletedFile] = file.deletedFile
                this[CommitFiles.renamedFile] = file.renamedFile
                this[CommitFiles.generatedFile] = file.generatedFile
            }
            stored.forEach { (outcome, kept) ->
                Commits.update({
                    (Commits.connectionId eq connectionId) and (Commits.projectId eq outcome.projectId) and (Commits.sha eq outcome.sha)
                }) {
                    it[filesChanged] = kept?.size
                    it[filesCapped] = outcome.result?.capped ?: false
                    it[filesFetchedAt] = now
                    it[filesError] = outcome.error
                    it[needsProcessing] = true
                }
            }
        }
    }
}
