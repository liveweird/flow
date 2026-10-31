package ch.nokillswit.gitlab

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.validation.PathGlob
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.CODE_PROCESSING_VERSION
import ch.nokillswit.norm.CodeActivityStore
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** The claim scans' page size: 50 small rows (a page of files is at most 50,000 rows; the stub's worst case is ~1,040). */
private const val PROCESS_PAGE = 50

/** A failed page names at most this many raw keys in its log line (project ids, shas, merge request ids: never a title, email or name). */
private const val MAX_LOGGED_KEYS = 20

private val log = LoggerFactory.getLogger(GitLabProcessStream::class.java)

/**
 * The configuration the stored norm rows were computed under (the `code_process` cursor, never a position): the sha256 of the EFFECTIVE
 * path-exclusion list ([exclusionsFingerprint]) and of the author-address map ([IdentityMap.fingerprint]).
 */
@Serializable
internal data class CodeProcessCursor(val exclusionsSha256: String, val identityMapSha256: String)

/**
 * The GitLab PROCESS stream (`gitlab-integration.md` "PROCESS / PROFILE"; no HTTP): rebuilds the code layer (`norm.code_*`, V44) from the
 * raw GitLab rows. In order:
 *
 *  1. **The fingerprints.** The configuration the counted columns and identity keys depend on is compared with the one stored in the
 *     stream's cursor row. A DIFFERENT exclusion list or author-address map re-flags every COMMIT (`needs_processing`; merge requests'
 *     line counts are not exclusion-aware and their keys are ids) and stores the new fingerprints in the SAME transaction, so an operator's
 *     change, a release that changes the default list and an address added to a user all reach the norm rows at this run with no refetch.
 *     The first run (no row) stores them and re-flags nothing: every row is already flagged.
 *  2. **The reference rows**, wholesale: projects, then identities (each in its own transaction). There is no per-row fallback, unlike
 *     Jira's: the rows are typed copies with no free-text parse, so there is no "bad row" class to isolate and a failure is a real fault.
 *  3. **The commits**, in keyset pages of 50 by `(project_id, sha)`, then 4. **the merge requests**, in pages of 50 by `mr_id`. ONE
 *     transaction per PAGE: the raw rows are read `FOR NO KEY UPDATE` (the files and events reference them), normalized in memory,
 *     written, and marked processed. A concurrent writer that re-flags a row (a files flush, a tombstone, a changed merge request) WAITS
 *     for the page and lands after the mark, so its flag is never overwritten. A row a concurrent PURGE removed is absent and not marked.
 *
 * A page that fails is logged (at most [MAX_LOGGED_KEYS] raw keys) and rethrown: the job fails, the rows stay flagged for the next run.
 * Progress: `commitsProcessed`, `mergeRequestsProcessed`, `identities` (rows written), `exclusionsChanged` / `identityMapChanged` (1
 * when the fingerprint re-flagged). Never calls GitLab, so it is deadline-exempt ([GITLAB_LOCAL_STREAMS]): nothing that calls GitLab
 * may follow it.
 */
internal class GitLabProcessStream(
    private val processStore: GitLabRawProcessStore,
    private val codeStore: CodeActivityStore,
    private val exclusions: List<PathGlob>,
) : Stream {
    override val name: String = CODE_PROCESS_STREAM_NAME

    override suspend fun run(context: StreamContext) {
        val connectionId = context.connectionId
        val users = processStore.userRowsForNorm(connectionId)
        val map = IdentityMap.from(users)
        applyFingerprints(context, map)
        rebuildReferenceRows(context, users, map)
        processCommits(context, map)
        processMergeRequests(context, map)
    }

    private suspend fun applyFingerprints(context: StreamContext, map: IdentityMap) {
        val current = CodeProcessCursor(exclusionsFingerprint(exclusions.map { it.pattern }), map.fingerprint)
        val stored = context.cursor(name)?.cursor?.let { decodeCursor<CodeProcessCursor>(it) }
        if (stored == current) return
        context.transaction {
            if (stored != null) {
                processStore.markAllNeedsProcessing(context.connectionId, commitsOnly = true)
                if (stored.exclusionsSha256 != current.exclusionsSha256) context.incrementProgress("exclusionsChanged")
                if (stored.identityMapSha256 != current.identityMapSha256) context.incrementProgress("identityMapChanged")
            }
            context.putCursor(name, encodeCursor(current), lastCompletedAt = context.clock())
        }
        context.heartbeat(changed = stored != null)
    }

    private suspend fun rebuildReferenceRows(context: StreamContext, users: List<RawUserForNorm>, map: IdentityMap) {
        val connectionId = context.connectionId
        context.transaction { codeStore.replaceProjects(connectionId, processStore.projectRowsForNorm(connectionId)) }
        val identities = context.transaction {
            val authors = processStore.liveCommitAuthors(connectionId)
            val rows = codeIdentities(users, authors, processStore.referencedUserIds(connectionId), map)
            codeStore.replaceIdentities(connectionId, rows)
        }
        context.incrementProgress("identities", identities.toLong())
        context.heartbeat(changed = true)
    }

    private suspend fun processCommits(context: StreamContext, map: IdentityMap) {
        val connectionId = context.connectionId
        val excluded: (String) -> Boolean = { PathGlob.matchesAny(exclusions, it) }
        var after: CommitKey? = null
        while (true) {
            val keys = processStore.commitsToProcess(connectionId, CODE_PROCESSING_VERSION, PROCESS_PAGE, after)
            if (keys.isEmpty()) break
            after = keys.last()
            val processed = guarded(keys.map { "${it.projectId}/${it.sha}" }, "commit") {
                context.transaction {
                    val now = context.clock()
                    val rows = processStore.commitsForProcessing(connectionId, keys)
                    val withFiles = rows.filter { it.parentCount <= 1 && it.filesFetchedAt != null }.map { it.key }
                    val files = processStore.commitFilesForProcessing(connectionId, withFiles)
                    codeStore.replaceCommits(connectionId, rows.map { codeCommit(it, files[it.key].orEmpty(), excluded, map) }, now)
                    processStore.markCommitsProcessed(connectionId, rows.map { it.key }, now, CODE_PROCESSING_VERSION)
                    rows.size
                }
            }
            context.incrementProgress("commitsProcessed", processed.toLong())
            context.heartbeat(changed = processed > 0)
        }
    }

    private suspend fun processMergeRequests(context: StreamContext, map: IdentityMap) {
        val connectionId = context.connectionId
        var after: Long? = null
        while (true) {
            val ids = processStore.mergeRequestsToProcess(connectionId, CODE_PROCESSING_VERSION, PROCESS_PAGE, after)
            if (ids.isEmpty()) break
            after = ids.last()
            val processed = guarded(ids.map { it.toString() }, "merge request") {
                context.transaction {
                    val now = context.clock()
                    val rows = processStore.mergeRequestsForProcessing(connectionId, ids)
                    val events = processStore.mrEventsForProcessing(connectionId, rows.map { it.mrId })
                    codeStore.replaceMergeRequests(connectionId, rows.map { codeMergeRequest(it, events[it.mrId].orEmpty(), map) }, now)
                    processStore.markMergeRequestsProcessed(connectionId, rows.map { it.mrId }, now, CODE_PROCESSING_VERSION)
                    rows.size
                }
            }
            context.incrementProgress("mergeRequestsProcessed", processed.toLong())
            context.heartbeat(changed = processed > 0)
        }
    }

    /** Runs a page; a failure is logged with at most [MAX_LOGGED_KEYS] raw keys and rethrown (cancellation passes untouched). */
    private suspend fun guarded(keys: List<String>, what: String, page: suspend () -> Int): Int = catchingFailures(
        block = { page() },
        onFailure = { failure ->
            val named = keys.take(MAX_LOGGED_KEYS)
            log.warn("PROCESS {} page of {} failed (first keys {}): {}", what, keys.size, named, failure.javaClass.simpleName)
            throw failure
        },
    )
}
