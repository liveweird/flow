package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.json.replaceNul
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The commit writes (V42). A commit is immutable by SHA, so there is no payload and no hash: [upsertCommits] is the
 * `GitLabRawWrites` page rule without the diff - ONE `SELECT ... IN (...) FOR UPDATE` loads which of the page's SHAs are stored (and
 * tombstoned or not; keys sorted, so rows lock in key order), new SHAs go out as one multi-row `INSERT`, a live one is only touched
 * (`last_seen_at`), a tombstoned one is resurrected (`deleted_at` cleared, `needs_processing` set again so PROCESS re-reads it, a
 * `NOT_FOUND` files stamp reset so the diff is read again).
 */
internal class GitLabCommitWrites(private val database: R2dbcDatabase) {

    suspend fun upsertCommits(connectionId: UInt, projectId: Long, commits: List<RawGitLabCommit>, now: Long): List<RawUpsertOutcome> {
        if (commits.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val stored = commits.map { it.sha }.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                Commits.select(Commits.sha, Commits.deletedAt)
                    .where { (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId) and (Commits.sha inList chunk) }
                    // NO KEY UPDATE, not FOR UPDATE: the key never changes and `raw.gitlab_commit_files` references these rows
                    // (persistence.md "Row locks vs. FK locks").
                    .orderBy(Commits.sha).forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList()
            }.associate { it[Commits.sha] to (it[Commits.deletedAt] != null) }
            val inserts = mutableListOf<RawGitLabCommit>()
            val touches = mutableListOf<String>()
            val resurrections = mutableListOf<String>()
            val seen = HashSet<String>()
            val outcomes = commits.map { commit ->
                if (!seen.add(commit.sha)) return@map RawUpsertOutcome.UNCHANGED
                when (stored[commit.sha]) {
                    null -> RawUpsertOutcome.INSERTED.also { inserts += commit }
                    true -> RawUpsertOutcome.RESURRECTED.also { resurrections += commit.sha }
                    false -> RawUpsertOutcome.UNCHANGED.also { touches += commit.sha }
                }
            }
            insert(connectionId, projectId, inserts, now)
            touches.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                Commits.update({ key(connectionId, projectId) and (Commits.sha inList chunk) }) { it[lastSeenAt] = now }
            }
            resurrections.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                Commits.update({ key(connectionId, projectId) and (Commits.sha inList chunk) }) {
                    it[lastSeenAt] = now
                    it[deletedAt] = null
                    it[needsProcessing] = true
                }
                // NOT_FOUND meant "the commit is gone"; seeing it again disproves that, so it re-enters the files backlog. The files
                // already stored and a LIMIT_EXCEEDED stamp (a property of the diff, not of the commit's presence) stay.
                Commits.update({
                    key(connectionId, projectId) and (Commits.sha inList chunk) and (Commits.filesError eq FILES_ERROR_NOT_FOUND)
                }) {
                    it[filesFetchedAt] = null
                    it[filesError] = null
                }
            }
            outcomes
        }
    }

    private fun key(connectionId: UInt, projectId: Long) = (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId)

    private suspend fun insert(connectionId: UInt, projectId: Long, rows: List<RawGitLabCommit>, now: Long) {
        Commits.insertRows(rows) {
            this[Commits.connectionId] = connectionId
            this[Commits.projectId] = projectId
            this[Commits.sha] = it.sha
            this[Commits.title] = it.title.replaceNul().take(GitLabRawStore.MAX_NAME)
            this[Commits.authorName] = it.authorName.replaceNul().take(GitLabRawStore.MAX_NAME)
            this[Commits.authorEmail] = it.authorEmail.replaceNul().take(GitLabRawStore.MAX_NAME)
            this[Commits.authoredAt] = it.authoredAt
            this[Commits.committedAt] = it.committedAt
            this[Commits.parentCount] = it.parentCount.toShort()
            this[Commits.additions] = it.additions
            this[Commits.deletions] = it.deletions
            this[Commits.firstSeenAt] = now
            this[Commits.lastSeenAt] = now
        }
    }

    private fun project(connectionId: UInt, projectId: Long) = (Projects.connectionId eq connectionId) and (Projects.projectId eq projectId)

    /** One `UPDATE` of the project row: the walked head, and the re-list request set or cleared (clearing it drops the re-list epoch). */
    suspend fun recordCommitsHead(connectionId: UInt, projectId: Long, headSha: String, relistRequested: Boolean) {
        suspendTransaction(database) {
            Projects.update({ project(connectionId, projectId) }) {
                it[commitsHeadSha] = headSha
                it[Projects.relistRequested] = relistRequested
                if (!relistRequested) it[relistStartedAt] = null
            }
        }
    }

    /** Sets `relist_requested` and the epoch (kept if already set, so a re-list that spans runs has ONE epoch); returns the epoch. */
    suspend fun beginRelist(connectionId: UInt, projectId: Long, now: Long): Long = suspendTransaction(database) {
        Projects.update({ project(connectionId, projectId) and Projects.relistStartedAt.isNull() }) { it[relistStartedAt] = now }
        Projects.update({ project(connectionId, projectId) }) { it[relistRequested] = true }
        val row = Projects.select(Projects.relistStartedAt).where { project(connectionId, projectId) }.toList().single()
        row[Projects.relistStartedAt] ?: now
    }

    /** The end of a sweep: the head when there is one, the request as [moreToDo], and the epoch cleared once nothing is left to do. */
    suspend fun finishRelist(connectionId: UInt, projectId: Long, headSha: String?, moreToDo: Boolean) {
        suspendTransaction(database) {
            Projects.update({ project(connectionId, projectId) }) {
                if (headSha != null) it[commitsHeadSha] = headSha
                it[relistRequested] = moreToDo
                if (!moreToDo) it[relistStartedAt] = null
            }
        }
    }

    /** Marks the live [shas] of the project seen now (`last_seen_at`): a re-list sweep found them still on the branch. */
    suspend fun touchCommits(connectionId: UInt, projectId: Long, shas: List<String>, now: Long) {
        if (shas.isEmpty()) return
        suspendTransaction(database) {
            shas.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                Commits.update({ key(connectionId, projectId) and Commits.deletedAt.isNull() and (Commits.sha inList chunk) }) {
                    it[lastSeenAt] = now
                }
            }
        }
    }

    /** Tombstones the live [shas] of the project (`deleted_at`, `needs_processing` set again); returns how many were live. */
    suspend fun tombstoneCommits(connectionId: UInt, projectId: Long, shas: List<String>, now: Long): Int {
        if (shas.isEmpty()) return 0
        return suspendTransaction(database) {
            shas.sorted().chunked(KEY_CHUNK).sumOf { chunk ->
                Commits.update({ key(connectionId, projectId) and Commits.deletedAt.isNull() and (Commits.sha inList chunk) }) {
                    it[deletedAt] = now
                    it[needsProcessing] = true
                }
            }
        }
    }
}
