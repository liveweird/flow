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

/** The column width of V42's `path` and `old_path`: a longer value is cut, never a failing insert. */
private const val MAX_PATH = 1024

/**
 * The COMMIT FILES flush (V42): finished diffs land in ONE transaction - every file row of every outcome that has a result
 * (`ON CONFLICT DO NOTHING`: a file is stored once, a re-flush or a path cut to the same 1,024 characters changes nothing), then each
 * commit's stamp. A commit is only ever stamped with its files already in, so a run cut anywhere leaves it pending, never half done.
 */
internal class GitLabCommitFileWrites(private val database: R2dbcDatabase) {

    /**
     * Stamps each commit `files_fetched_at = now` with `needs_processing = true`; `files_changed` is the rows STORED (distinct paths;
     * 1,000 for a capped commit, never GitLab's total) or null when the outcome carries an [CommitFilesOutcome.error] instead of a result.
     */
    suspend fun flushCommitFiles(connectionId: UInt, outcomes: List<CommitFilesOutcome>, now: Long) {
        if (outcomes.isEmpty()) return
        suspendTransaction(database) {
            val files = outcomes.flatMap { outcome -> outcome.result?.files.orEmpty().map { outcome to it } }
            CommitFiles.insertRowsIgnoringConflicts(files) { (outcome, file) ->
                this[CommitFiles.connectionId] = connectionId
                this[CommitFiles.projectId] = outcome.projectId
                this[CommitFiles.sha] = outcome.sha
                this[CommitFiles.path] = file.path.replaceNul().take(MAX_PATH)
                this[CommitFiles.oldPath] = file.oldPath?.replaceNul()?.take(MAX_PATH)
                this[CommitFiles.additions] = file.additions
                this[CommitFiles.deletions] = file.deletions
                this[CommitFiles.newFile] = file.newFile
                this[CommitFiles.deletedFile] = file.deletedFile
                this[CommitFiles.renamedFile] = file.renamedFile
                this[CommitFiles.generatedFile] = file.generatedFile
            }
            outcomes.forEach { outcome ->
                Commits.update({
                    (Commits.connectionId eq connectionId) and (Commits.projectId eq outcome.projectId) and (Commits.sha eq outcome.sha)
                }) {
                    it[filesChanged] = outcome.result?.files?.distinctBy { file -> file.path }?.size
                    it[filesCapped] = outcome.result?.capped ?: false
                    it[filesFetchedAt] = now
                    it[filesError] = outcome.error
                    it[needsProcessing] = true
                }
            }
        }
    }
}
