package ch.nokillswit.jira

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.ReconcileSeen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

// Keeps the facade's logger name: `JiraRawStore` is what a log reader (and any capture) has always seen.
private val log = LoggerFactory.getLogger(JiraRawStore::class.java)

/**
 * How far past the RECONCILE sweep's window start a stored issue must have been updated to count as
 * an anti-join candidate (`JiraRawStore.issuesMissingFromSeen`) — covers the relative `-Nm` bound's
 * drift while the pass runs; rows inside it are a small permanent blind spot, and a pass longer than
 * this only costs no-op probes.
 */
internal const val RECONCILE_CANDIDATE_SLACK_MILLIS = 5 * MILLIS_PER_MINUTE

/** An anti-join candidate: a stored issue the sweep did not list, with its stored project id. */
data class ReconcileCandidate(val issueId: Long, val projectId: Long)

/**
 * The RECONCILE stream's raw-store half (v0.2.0 plan §7/§12 item 7): the id-sweep scratch table
 * (`raw.jira_reconcile_seen`), the anti-join, the scope resolution it and the ISSUES stream's out-of-scope catch-up
 * share, and the deleted/moved-out tombstones. [reads] supplies the live `PROJECT` entities [resolveProjectIds] reads.
 */
internal class JiraRawReconcile(private val database: R2dbcDatabase, private val reads: JiraRawViewReads) {

    /**
     * The RECONCILE stream's id-sweep write (v0.2.0 plan §7/§12 item 7): `ON CONFLICT DO NOTHING`
     * on the `(connectionId, jobId, issueId)` PK makes a resumed page (same job, after a lease
     * loss/reclaim) idempotent rather than duplicating.
     */
    suspend fun insertReconcileSeen(connectionId: UInt, jobId: UInt, issueId: Long) {
        suspendTransaction(database) {
            ReconcileSeen.insertIgnore {
                it[ReconcileSeen.connectionId] = connectionId
                it[ReconcileSeen.jobId] = jobId.toInt()
                it[ReconcileSeen.issueId] = issueId
            }
        }
    }

    /**
     * The project ids of [projectKeys], resolved against the REFERENCE stream's live `PROJECT`
     * entities (`raw.jira_entities`, refreshed earlier in the same SYNC), or `null` when ANY key does
     * not resolve to a current (non-tombstoned) project — a renamed, mistyped or deleted project.
     * Scope is decided by project ID, never by the `project_key` text stored on an issue row: a Jira
     * project rename changes the key on every re-fetched issue while the id stays, so key matching
     * would wrongly call a renamed project's issues out of scope. Callers treat `null` as "scope
     * unknown" and must fail safe (never tombstone on it).
     */
    suspend fun resolveProjectIds(connectionId: UInt, projectKeys: List<String>): Set<Long>? {
        val idByKey = reads.entityRowsByKind(connectionId, JiraEntityKind.PROJECT.name).mapNotNull { (_, payload) ->
            val project = Json.parseToJsonElement(payload).jsonObject
            val key = project["key"]?.jsonPrimitive?.content
            val id = project["id"]?.jsonPrimitive?.content?.toLongOrNull()
            if (key == null || id == null) null else key to id
        }.toMap()
        val ids = projectKeys.map { idByKey[it] ?: return null }
        return ids.toSet()
    }

    /**
     * The RECONCILE stream's anti-join (v0.2.0 plan §7): non-tombstoned raw issues for
     * [connectionId] absent from [jobId]'s seen set — Jira either deleted or moved them out of
     * scope while Flow wasn't looking. Candidates are restricted to the in-scope [projectIds]
     * ([resolveProjectIds]; `null` = scope unresolved, so no project restriction) and to rows last
     * updated at or after [windowStartMillis] plus [RECONCILE_CANDIDATE_SLACK_MILLIS]: the sweep's
     * `updated >= "-Nm"` window starts at [windowStartMillis] (Jira evaluates the relative bound per
     * request, so it drifts forward by the pass's own duration), and the few minutes of slack keep a
     * row on the window's edge from becoming a false-positive probe (a longer pass costs only no-op
     * probes that answer 200, still in scope). Rows older than the window are never candidates.
     */
    suspend fun issuesMissingFromSeen(
        connectionId: UInt,
        jobId: UInt,
        projectIds: Set<Long>?,
        windowStartMillis: Long,
    ): List<ReconcileCandidate> = suspendTransaction(database) {
        val seenIds = ReconcileSeen.select(ReconcileSeen.issueId)
            .where { (ReconcileSeen.connectionId eq connectionId) and (ReconcileSeen.jobId eq jobId.toInt()) }
            .map { it[ReconcileSeen.issueId] }.toList().toSet()
        Issues.select(Issues.issueId, Issues.projectId)
            .where {
                val candidate = (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.movedOutAt.isNull() and
                    (Issues.issueUpdatedAt greaterEq windowStartMillis + RECONCILE_CANDIDATE_SLACK_MILLIS)
                if (projectIds == null) candidate else candidate and (Issues.projectId inList projectIds)
            }
            .map { ReconcileCandidate(it[Issues.issueId], it[Issues.projectId]) }.toList()
            .filterNot { it.issueId in seenIds }
    }

    /**
     * Tombstones, locally and with no HTTP, every stored issue of a project that is no longer in
     * [projectKeys] (an admin removed the project from the connection's scope): `moved_out_at = now`
     * and `needs_processing`, the key/project columns untouched — the row keeps the project it
     * really belongs to. Scope is decided by project ID ([resolveProjectIds]); FAIL SAFE: when any
     * configured key does not resolve to a live project (renamed, mistyped, deleted) the step is
     * skipped entirely with a WARN and returns 0 — never tombstone on an unresolved scope.
     * Already-tombstoned rows are left alone, so a repeat call is a no-op. A re-added project's rows
     * are resurrected by the next SYNC's scope catch-up (the project is searched again from
     * `backfillFrom`; [JiraRawWrites.upsertIssue]'s tombstoned branch clears the tombstones). The ISSUES stream
     * calls this at the start of every SYNC, which keeps RECONCILE from probing a removed project's
     * stored issues one by one (`issuesMissingFromSeen` only considers in-scope projects).
     */
    suspend fun markOutOfScopeProjects(connectionId: UInt, projectKeys: List<String>, now: Long): Int {
        val projectIds = resolveProjectIds(connectionId, projectKeys)
        if (projectIds == null) {
            log.warn(
                "skipping the out-of-scope tombstone for connection {}: a configured project key does not resolve to a live " +
                    "PROJECT entity (renamed, mistyped or deleted project)",
                connectionId,
            )
            return 0
        }
        return suspendTransaction(database) {
            Issues.update({
                (Issues.connectionId eq connectionId) and (Issues.projectId notInList projectIds) and
                    Issues.deletedAt.isNull() and Issues.movedOutAt.isNull()
            }) {
                it[movedOutAt] = now
                it[needsProcessing] = true
            }
        }
    }

    /** The RECONCILE stream's index-gap case (v0.2.0 plan §7): ids [jobId]'s sweep saw that `raw.jira_issues` never stored. */
    suspend fun seenButUnknownIds(connectionId: UInt, jobId: UInt): List<Long> = suspendTransaction(database) {
        val seenIds = ReconcileSeen.select(ReconcileSeen.issueId)
            .where { (ReconcileSeen.connectionId eq connectionId) and (ReconcileSeen.jobId eq jobId.toInt()) }
            .map { it[ReconcileSeen.issueId] }.toList().toSet()
        val knownIds = Issues.select(Issues.issueId).where { Issues.connectionId eq connectionId }
            .map { it[Issues.issueId] }.toList().toSet()
        (seenIds - knownIds).toList()
    }

    /** Drains every scratch row for [connectionId] (v0.2.0 plan §7): called once a pass's anti-join step completes. */
    suspend fun clearReconcileSeen(connectionId: UInt): Int = suspendTransaction(database) {
        ReconcileSeen.deleteWhere { ReconcileSeen.connectionId eq connectionId }
    }

    /**
     * The RECONCILE stream's "deleted" tombstone (v0.2.0 plan §7): a 404 on `GET /issue/{id}`.
     * Guarded on `deletedAt.isNull()` so a repeat RECONCILE pass (idempotence) is a no-op once set.
     */
    suspend fun markIssueDeleted(connectionId: UInt, issueId: Long, now: Long): Int = suspendTransaction(database) {
        Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) and Issues.deletedAt.isNull() }) {
            it[deletedAt] = now
            it[needsProcessing] = true
        }
    }

    /**
     * The RECONCILE stream's "moved out of scope" tombstone (v0.2.0 plan §7): a 200 on
     * `GET /issue/{id}` whose project is no longer in the connection's scope — the key/project
     * columns are refreshed to their new value alongside the tombstone. Guarded on
     * `movedOutAt.isNull()` so a repeat RECONCILE pass (idempotence) is a no-op once set.
     */
    suspend fun markIssueMovedOut(
        connectionId: UInt,
        issueId: Long,
        newKey: String,
        newProjectId: Long,
        newProjectKey: String,
        now: Long,
    ): Int = suspendTransaction(database) {
        Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) and Issues.movedOutAt.isNull() }) {
            it[issueKey] = newKey
            it[projectId] = newProjectId
            it[projectKey] = newProjectKey
            it[movedOutAt] = now
            it[needsProcessing] = true
        }
    }
}
