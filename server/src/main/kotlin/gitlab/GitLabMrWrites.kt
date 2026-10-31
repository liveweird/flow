package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.CustomLongFunction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

private fun sortedIdsJson(ids: List<Long>): String = canonicalJson(JsonArray(ids.distinct().sorted().map(::JsonPrimitive)))

/** A commit id is stored only as one (40 or 64 hex); anything else is dropped, never cut. */
private fun String?.asSha(): String? = this?.takeIf { SHA_PATTERN.matches(it) }

/**
 * The merge-request writes (V43). [upsertMergeRequests] is the shared hashed page rule ([diffPage]) over a 100-row page: ONE key load
 * (`mr_id, sha256, deleted_at`), new rows in one multi-row `INSERT`, unchanged live rows ONE `UPDATE ... SET last_seen_at`, a changed or
 * tombstoned row rewritten on its own (`changed_at = now`, which is what makes its details stale). Nothing here tombstones a merge
 * request - `deleted_at` is RECONCILE's (PR 9) - and a listing that does not mention a row is no evidence about it.
 *
 * Every stored text is NUL-replaced and cut on a code-point boundary ([cutText]); no column of either table that is part of a key holds
 * upstream text (the keys are numeric ids and the fixed `source` code), so no byte cap beyond the column widths is needed.
 */
internal class GitLabMrWrites(private val database: R2dbcDatabase) {

    suspend fun upsertMergeRequests(connectionId: UInt, mrs: List<RawGitLabMergeRequest>, now: Long): List<RawUpsertOutcome> {
        if (mrs.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val prepared = mrs.map {
                val canonical = canonicalJson(it.payload)
                Prepared(it, it.mrId, canonical, sha256Hex(canonical))
            }
            val diff = diffPage(prepared, loadMergeRequests(connectionId, prepared.map { it.key })) {
                changeMergeRequest(connectionId, it, now)
            }
            insertMergeRequests(connectionId, diff.inserts, now)
            touched(diff).forEach { chunk ->
                MergeRequests.update({ (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId inList chunk) }) {
                    it[lastSeenAt] = now
                }
            }
            diff.outcomes
        }
    }

    private suspend fun loadMergeRequests(connectionId: UInt, keys: List<Long>): Map<Long, StoredRow> =
        keys.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
            MergeRequests.select(MergeRequests.mrId, MergeRequests.sha256, MergeRequests.deletedAt)
                .where { (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId inList chunk) }
                // NO KEY UPDATE, not FOR UPDATE: the key never changes and `raw.gitlab_mr_events` references these rows from the
                // details stream's transactions (persistence.md "Row locks vs. FK locks").
                .orderBy(MergeRequests.mrId).forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList()
        }.associate { it[MergeRequests.mrId] to StoredRow(it[MergeRequests.sha256], it[MergeRequests.deletedAt] != null) }

    private suspend fun insertMergeRequests(connectionId: UInt, rows: List<Prepared<RawGitLabMergeRequest>>, now: Long) {
        MergeRequests.insertRows(rows) {
            val mr = it.input
            this[MergeRequests.connectionId] = connectionId
            this[MergeRequests.mrId] = mr.mrId
            this[MergeRequests.projectId] = mr.projectId
            this[MergeRequests.iid] = mr.iid
            this[MergeRequests.state] = cutText(mr.state, GitLabRawStore.MAX_STATE)
            this[MergeRequests.draft] = mr.draft
            this[MergeRequests.authorId] = mr.authorId
            this[MergeRequests.mergeUserId] = mr.mergeUserId
            this[MergeRequests.createdAt] = mr.createdAt
            this[MergeRequests.updatedAt] = mr.updatedAt
            this[MergeRequests.mergedAt] = mr.mergedAt
            this[MergeRequests.closedAt] = mr.closedAt
            this[MergeRequests.sourceBranch] = cutText(mr.sourceBranch, GitLabRawStore.MAX_BRANCH)
            this[MergeRequests.targetBranch] = cutText(mr.targetBranch, GitLabRawStore.MAX_BRANCH)
            this[MergeRequests.title] = cutText(mr.title, GitLabRawStore.MAX_NAME)
            this[MergeRequests.headSha] = mr.headSha.asSha()
            this[MergeRequests.mergeCommitSha] = mr.mergeCommitSha.asSha()
            this[MergeRequests.squashCommitSha] = mr.squashCommitSha.asSha()
            this[MergeRequests.reviewerIds] = sortedIdsJson(mr.reviewerIds)
            this[MergeRequests.assigneeIds] = sortedIdsJson(mr.assigneeIds)
            this[MergeRequests.userNotesCount] = mr.userNotesCount
            this[MergeRequests.payload] = it.canonical
            this[MergeRequests.sha256] = it.hash
            this[MergeRequests.firstSeenAt] = now
            this[MergeRequests.lastSeenAt] = now
            this[MergeRequests.changedAt] = now
        }
    }

    /**
     * A genuine change (or a resurrection): refresh every column and the payload, move `changed_at` (the details are stale again) and
     * flag PROCESS. A `NOT_FOUND` stamp is cleared - seeing the merge request again disproves "gone"; its stored details and a
     * `LIMIT_EXCEEDED` stamp stay until the next details read replaces them.
     */
    private suspend fun changeMergeRequest(connectionId: UInt, row: Prepared<RawGitLabMergeRequest>, now: Long) {
        val mr = row.input
        val key = (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId eq row.key)
        MergeRequests.update({ key }) {
            // project_id and iid are the row's identity (GitLab never moves a merge request): the unique index would refuse a change.
            it[state] = cutText(mr.state, GitLabRawStore.MAX_STATE)
            it[draft] = mr.draft
            it[authorId] = mr.authorId
            it[mergeUserId] = mr.mergeUserId
            it[createdAt] = mr.createdAt
            it[updatedAt] = mr.updatedAt
            it[mergedAt] = mr.mergedAt
            it[closedAt] = mr.closedAt
            it[sourceBranch] = cutText(mr.sourceBranch, GitLabRawStore.MAX_BRANCH)
            it[targetBranch] = cutText(mr.targetBranch, GitLabRawStore.MAX_BRANCH)
            it[title] = cutText(mr.title, GitLabRawStore.MAX_NAME)
            it[headSha] = mr.headSha.asSha()
            it[mergeCommitSha] = mr.mergeCommitSha.asSha()
            it[squashCommitSha] = mr.squashCommitSha.asSha()
            it[reviewerIds] = sortedIdsJson(mr.reviewerIds)
            it[assigneeIds] = sortedIdsJson(mr.assigneeIds)
            it[userNotesCount] = mr.userNotesCount
            it[payload] = row.canonical
            it[sha256] = row.hash
            it[lastSeenAt] = now
            it[changedAt] = now
            it[deletedAt] = null
            it[needsProcessing] = true
        }
        MergeRequests.update({ key and (MergeRequests.detailsError eq DETAILS_ERROR_NOT_FOUND) }) { it[detailsError] = null }
    }

    /**
     * Settles one merge request's details read in ONE transaction, so the events and the stamp can never disagree (a stamp without its
     * events would take the merge request out of the backlog with the old set, or none). The row is locked first, `FOR NO KEY UPDATE`
     * (the key never changes, and the events reference it: "Row locks vs. FK locks"), which also keeps a concurrent PURGE from deleting it
     * between the read of its project and the insert of its events; a row that is gone settles nothing (returns false).
     *
     * A complete read ([MrDetailsOutcome.error] null) REPLACES the events (every old row goes, [events] go in `ON CONFLICT DO NOTHING`: a
     * note a page shift listed twice is one row), writes `notes_capped` and the diff stats (null on a GraphQL gap), and clears
     * `details_error`. A terminal error writes no events and leaves the stored events, stats and cap as they were - structurally:
     * [events] must then be empty. The stamp is `details_synced_at = GREATEST(now, changed_at)`: a clock that did not advance past the
     * change (the same millisecond, or a worker clock behind the database writer's) still takes the row out of the backlog instead of
     * re-selecting it forever. `project_id` of the events is the row's own. PROCESS is flagged either way.
     */
    suspend fun settleMrDetails(
        connectionId: UInt,
        mrId: Long,
        events: List<RawGitLabMrEvent>,
        now: Long,
        outcome: MrDetailsOutcome,
    ): Boolean {
        require(outcome.error == null || events.isEmpty()) { "a terminal details error writes no events" }
        return suspendTransaction(database) {
            val key = (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId eq mrId)
            val projectId = MergeRequests.select(MergeRequests.projectId).where { key }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull()?.get(MergeRequests.projectId)
                ?: return@suspendTransaction false
            if (outcome.error == null) replaceEvents(connectionId, mrId, projectId, events)
            MergeRequests.update({ key }) {
                it[detailsSyncedAt] = CustomLongFunction("GREATEST", longLiteral(now), MergeRequests.changedAt)
                it[detailsError] = outcome.error
                if (outcome.error == null) {
                    it[notesCapped] = outcome.notesCapped
                    it[additions] = outcome.stats?.additions
                    it[deletions] = outcome.stats?.deletions
                    it[fileCount] = outcome.stats?.fileCount
                }
                it[needsProcessing] = true
            }
            true
        }
    }

    private suspend fun replaceEvents(connectionId: UInt, mrId: Long, projectId: Long, events: List<RawGitLabMrEvent>) {
        MrEvents.deleteWhere { (MrEvents.connectionId eq connectionId) and (MrEvents.mrId eq mrId) }
        MrEvents.insertRowsIgnoringConflicts(events) {
            this[MrEvents.connectionId] = connectionId
            this[MrEvents.mrId] = mrId
            this[MrEvents.eventSource] = it.source.name
            this[MrEvents.sourceId] = it.sourceId
            this[MrEvents.projectId] = projectId
            this[MrEvents.actorId] = it.actorId
            this[MrEvents.eventAt] = it.eventAt
            this[MrEvents.system] = it.system
            this[MrEvents.noteType] = it.noteType?.let { type -> cutText(type, GitLabRawStore.MAX_NOTE_TYPE) }
            this[MrEvents.action] = cutText(it.action, GitLabRawStore.MAX_ACTION)
            this[MrEvents.targets] = targetsJson(it.targets)
        }
    }

    /** Usernames cut to their width FIRST, then empties dropped and the rest made distinct (long names share a cut), ≤[MAX_TARGETS]. */
    private fun targetsJson(targets: List<String>): String? =
        targets.map { cutText(it, MAX_TARGET_CHARS) }.filter { it.isNotEmpty() }.distinct().take(MAX_TARGETS).takeIf { it.isNotEmpty() }
            ?.let { canonicalJson(JsonArray(it.map(::JsonPrimitive))) }
}
