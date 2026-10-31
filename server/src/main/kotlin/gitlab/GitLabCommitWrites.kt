package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.json.replaceNul
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** The column width of V42's `title`, `author_name` and `author_email`: a longer value is cut, never a failing insert. */
private const val MAX_TEXT = 255

/**
 * The commit writes (V42). A commit is immutable by SHA, so there is no payload and no hash: [upsertCommits] is the
 * `GitLabRawWrites` page rule without the diff - ONE `SELECT ... IN (...) FOR UPDATE` loads which of the page's SHAs are stored (and
 * tombstoned or not; keys sorted, so rows lock in key order), new SHAs go out as one multi-row `INSERT`, a live one is only touched
 * (`last_seen_at`), a tombstoned one is resurrected (`deleted_at` cleared, `needs_processing` set again so PROCESS re-reads it).
 */
internal class GitLabCommitWrites(private val database: R2dbcDatabase) {

    suspend fun upsertCommits(connectionId: UInt, projectId: Long, commits: List<RawGitLabCommit>, now: Long): List<RawUpsertOutcome> {
        if (commits.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val stored = commits.map { it.sha }.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                Commits.select(Commits.sha, Commits.deletedAt)
                    .where { (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId) and (Commits.sha inList chunk) }
                    .orderBy(Commits.sha).forUpdate().toList()
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
            this[Commits.title] = it.title.replaceNul().take(MAX_TEXT)
            this[Commits.authorName] = it.authorName.replaceNul().take(MAX_TEXT)
            this[Commits.authorEmail] = it.authorEmail.replaceNul().take(MAX_TEXT)
            this[Commits.authoredAt] = it.authoredAt
            this[Commits.committedAt] = it.committedAt
            this[Commits.parentCount] = it.parentCount.toShort()
            this[Commits.additions] = it.additions
            this[Commits.deletions] = it.deletions
            this[Commits.firstSeenAt] = now
            this[Commits.lastSeenAt] = now
        }
    }

    /** One `UPDATE` of the project row: the walked head, and the re-list request set or cleared. */
    suspend fun recordCommitsHead(connectionId: UInt, projectId: Long, headSha: String, relistRequested: Boolean) {
        suspendTransaction(database) {
            Projects.update({ (Projects.connectionId eq connectionId) and (Projects.projectId eq projectId) }) {
                it[commitsHeadSha] = headSha
                it[Projects.relistRequested] = relistRequested
            }
        }
    }

    suspend fun requestRelist(connectionId: UInt, projectId: Long) {
        suspendTransaction(database) {
            Projects.update({ (Projects.connectionId eq connectionId) and (Projects.projectId eq projectId) }) {
                it[relistRequested] = true
            }
        }
    }

    /** Tombstones the live commits of the project at or after [sinceCommittedAt] that the pass starting at [passStartedAt] did not see. */
    suspend fun tombstoneCommitsNotSeenSince(
        connectionId: UInt,
        projectId: Long,
        sinceCommittedAt: Long,
        passStartedAt: Long,
        now: Long,
    ): Int = suspendTransaction(database) {
        Commits.update({
            key(connectionId, projectId) and Commits.deletedAt.isNull() and (Commits.committedAt greaterEq sinceCommittedAt) and
                (Commits.lastSeenAt less passStartedAt)
        }) {
            it[deletedAt] = now
            it[needsProcessing] = true
        }
    }
}
