package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.replaceNul
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** The most keys one `IN (...)` list carries (one bind parameter each); a page is 100, so one chunk in practice. */
internal const val KEY_CHUNK = 1_000

/** The column widths of V41: a longer value is cut, never a failing insert (GitLab caps these far below). */
private const val MAX_NAME = 255
private const val MAX_PROJECT_PATH = 1024
private const val MAX_STATE = 30

/**
 * The raw upserts (V41) and the end-of-pass sweeps, the `JiraRawBatchWrites.upsertEntities` shape for a 100-row page: ONE
 * `SELECT ... IN (...) FOR UPDATE` loads the stored hashes and markers (keys sorted, so rows lock in key order), the shared pure rule
 * ([diffPage]) decides each row, new rows go out as one multi-row `INSERT`, unchanged live rows get ONE `UPDATE ... SET
 * last_seen_at`, and a changed or tombstoned row is updated on its own (payload, hash, `changed_at`, markers cleared). Outcomes come
 * back in input order. A key repeated within one call is judged against the first occurrence's write (the second pass sees it stored).
 */
internal class GitLabRawWrites(private val database: R2dbcDatabase) {

    suspend fun upsertProjects(connectionId: UInt, projects: List<RawGitLabProject>, now: Long): List<RawUpsertOutcome> {
        if (projects.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val prepared = projects.map {
                val canonical = canonicalJson(slimProject(it.payload))
                Prepared(it, it.projectId, canonical, sha256Hex(canonical))
            }
            // group_path/group_id sit outside the hashed payload: a project listed under another configured group is a change all the same.
            val moved = { row: Prepared<RawGitLabProject>, stored: StoredRow ->
                stored.groupPath != row.input.groupPath || stored.groupId != row.input.groupId
            }
            val diff = diffPage(prepared, loadProjects(connectionId, prepared.map { it.key }), moved) { changeProject(connectionId, it,
                now) }
            insertProjects(connectionId, diff.inserts, now)
            touched(diff).forEach { chunk ->
                Projects.update({ (Projects.connectionId eq connectionId) and (Projects.projectId inList chunk) }) { it[lastSeenAt] = now }
            }
            diff.outcomes
        }
    }

    private suspend fun loadProjects(connectionId: UInt, keys: List<Long>): Map<Long, StoredRow> =
        keys.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
            Projects.select(Projects.projectId, Projects.sha256, Projects.deletedAt, Projects.movedOutAt, Projects.groupPath,
                Projects.groupId)
                .where { (Projects.connectionId eq connectionId) and (Projects.projectId inList chunk) }
                .orderBy(Projects.projectId).forUpdate().toList()
        }.associate {
            it[Projects.projectId] to StoredRow(
                it[Projects.sha256], it[Projects.deletedAt] != null || it[Projects.movedOutAt] != null, it[Projects.groupPath],
                    it[Projects.groupId],
            )
        }

    private suspend fun insertProjects(connectionId: UInt, rows: List<Prepared<RawGitLabProject>>, now: Long) {
        Projects.insertRows(rows) {
            this[Projects.connectionId] = connectionId
            this[Projects.projectId] = it.input.projectId
            this[Projects.pathWithNamespace] = it.input.pathWithNamespace.replaceNul().take(MAX_PROJECT_PATH)
            this[Projects.groupPath] = it.input.groupPath.take(MAX_NAME)
            this[Projects.groupId] = it.input.groupId
            this[Projects.defaultBranch] = it.input.defaultBranch?.replaceNul()
            this[Projects.archived] = it.input.archived
            this[Projects.payload] = it.canonical
            this[Projects.sha256] = it.hash
            this[Projects.firstSeenAt] = now
            this[Projects.lastSeenAt] = now
            this[Projects.changedAt] = now
        }
    }

    /** A genuine change (or a resurrection): refresh the columns and the payload, clear both markers. */
    private suspend fun changeProject(connectionId: UInt, row: Prepared<RawGitLabProject>, now: Long) {
        Projects.update({ (Projects.connectionId eq connectionId) and (Projects.projectId eq row.key) }) {
            it[pathWithNamespace] = row.input.pathWithNamespace.replaceNul().take(MAX_PROJECT_PATH)
            it[groupPath] = row.input.groupPath.take(MAX_NAME)
            it[groupId] = row.input.groupId
            it[defaultBranch] = row.input.defaultBranch?.replaceNul()
            it[archived] = row.input.archived
            it[payload] = row.canonical
            it[sha256] = row.hash
            it[lastSeenAt] = now
            it[changedAt] = now
            it[movedOutAt] = null
            it[deletedAt] = null
        }
    }

    suspend fun upsertUsers(connectionId: UInt, users: List<RawGitLabUser>, now: Long): List<RawUpsertOutcome> {
        if (users.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val prepared = users.map {
                val emails = it.emails.map(String::lowercase).distinct().sorted()
                val canonical = canonicalJson(slimUser(it.payload, emails))
                Prepared(it, it.userId, canonical, sha256Hex(canonical))
            }
            val diff = diffPage(prepared, loadUsers(connectionId, prepared.map { it.key })) { changeUser(connectionId, it, now) }
            insertUsers(connectionId, diff.inserts, now)
            touched(diff).forEach { chunk ->
                Users.update({ (Users.connectionId eq connectionId) and (Users.userId inList chunk) }) { it[lastSeenAt] = now }
            }
            diff.outcomes
        }
    }

    private suspend fun loadUsers(connectionId: UInt, keys: List<Long>): Map<Long, StoredRow> =
        keys.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
            Users.select(Users.userId, Users.sha256, Users.deletedAt)
                .where { (Users.connectionId eq connectionId) and (Users.userId inList chunk) }
                .orderBy(Users.userId).forUpdate().toList()
        }.associate { it[Users.userId] to StoredRow(it[Users.sha256], it[Users.deletedAt] != null) }

    private fun emailsJson(emails: List<String>): String = canonicalJson(JsonArray(emails.map(::JsonPrimitive)))

    private suspend fun insertUsers(connectionId: UInt, rows: List<Prepared<RawGitLabUser>>, now: Long) {
        Users.insertRows(rows) {
            this[Users.connectionId] = connectionId
            this[Users.userId] = it.input.userId
            this[Users.username] = it.input.username.replaceNul().take(MAX_NAME)
            this[Users.name] = it.input.name.replaceNul().take(MAX_NAME)
            this[Users.state] = it.input.state.replaceNul().take(MAX_STATE)
            this[Users.bot] = it.input.bot
            this[Users.emails] = emailsJson(it.input.emails.map(String::lowercase).distinct().sorted())
            this[Users.payload] = it.canonical
            this[Users.sha256] = it.hash
            this[Users.firstSeenAt] = now
            this[Users.lastSeenAt] = now
            this[Users.changedAt] = now
        }
    }

    private suspend fun changeUser(connectionId: UInt, row: Prepared<RawGitLabUser>, now: Long) {
        Users.update({ (Users.connectionId eq connectionId) and (Users.userId eq row.key) }) {
            it[username] = row.input.username.replaceNul().take(MAX_NAME)
            it[name] = row.input.name.replaceNul().take(MAX_NAME)
            it[state] = row.input.state.replaceNul().take(MAX_STATE)
            it[bot] = row.input.bot
            it[emails] = emailsJson(row.input.emails.map(String::lowercase).distinct().sorted())
            it[payload] = row.canonical
            it[sha256] = row.hash
            it[lastSeenAt] = now
            it[changedAt] = now
            it[deletedAt] = null
        }
    }

    suspend fun markProjectsMovedOutNotSeenSince(connectionId: UInt, passStartedAt: Long, now: Long): Int =
        suspendTransaction(database) {
            Projects.update({
                (Projects.connectionId eq connectionId) and (Projects.lastSeenAt less passStartedAt) and
                    Projects.movedOutAt.isNull() and Projects.deletedAt.isNull()
            }) { it[movedOutAt] = now }
        }

    suspend fun markUsersDeletedNotSeenSince(connectionId: UInt, passStartedAt: Long, now: Long): Int =
        suspendTransaction(database) {
            Users.update({ (Users.connectionId eq connectionId) and (Users.lastSeenAt less passStartedAt) and Users.deletedAt.isNull() }) {
                it[deletedAt] = now
            }
        }

    /**
     * RECONCILE's deletion mark: a MOVED-OUT project whose `GET /projects/:id` answered 404. Guarded on `moved_out_at IS NOT NULL` (a
     * live project is never marked: PROJECTS owns its state) and `deleted_at IS NULL`, so a repeated pass is a no-op; true when a row
     * changed. Only the marker is written (no payload, no hash): PROJECTS resurrects the project when it is listed again
     * ([changeProject] clears both markers).
     */
    suspend fun markProjectDeleted(connectionId: UInt, projectId: Long, now: Long): Boolean = suspendTransaction(database) {
        Projects.update({
            (Projects.connectionId eq connectionId) and (Projects.projectId eq projectId) and Projects.movedOutAt.isNotNull() and
                Projects.deletedAt.isNull()
        }) {
            it[deletedAt] = now
        } > 0
    }

    /**
     * RECONCILE's archival mark for a MOVED-OUT project that now answers `archived` (true when a row changed): only `archived` is set,
     * guarded on `moved_out_at IS NOT NULL` and `archived = false`. It touches no marker, no payload and no hash on purpose:
     * [changeProject] would resurrect the project (clear `moved_out_at`/`deleted_at`) on the archived body's changed hash, and PROJECTS
     * (which lists `archived=false`) is what marks an archived project moved out and brings it back if it is listed again.
     */
    suspend fun markProjectArchived(connectionId: UInt, projectId: Long): Boolean = suspendTransaction(database) {
        Projects.update({
            (Projects.connectionId eq connectionId) and (Projects.projectId eq projectId) and Projects.movedOutAt.isNotNull() and
                (Projects.archived eq false)
        }) {
            it[archived] = true
        } > 0
    }
}
