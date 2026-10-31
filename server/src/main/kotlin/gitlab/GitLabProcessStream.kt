package ch.nokillswit.gitlab

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.validation.PathGlob
import ch.nokillswit.ingest.LoggedFailure
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.CODE_PROCESSING_VERSION
import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.NormCodeMergeRequest
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** The claim scans' page size: 50 small rows (a page of files is at most 50,000 rows; the stub's worst case is ~1,040). */
private const val PROCESS_PAGE = 50

/** More failed pages than this in ONE run is an outage, not an outlier: the last failure is rethrown and the job fails. */
internal const val PROCESS_FAILED_PAGES_MAX = 3

private val log = LoggerFactory.getLogger(GitLabProcessStream::class.java)

/**
 * The configuration the stored norm rows were computed under (the `code_process` cursor, never a position): the sha256 of the EFFECTIVE
 * path-exclusion list ([exclusionsFingerprint]), of the author-address map ([IdentityMap.fingerprint]) and of the username map
 * ([IdentityMap.usernameFingerprint]).
 */
@Serializable
internal data class CodeProcessCursor(val exclusionsSha256: String, val identityMapSha256: String, val usernameMapSha256: String)

/**
 * The normalization functions PROCESS applies - a seam so a test can make one throw (a poisoned page) without a row no real source
 * produces. Production uses the defaults.
 */
internal class ProcessNormalizers(
    val identities: (List<RawUserForNorm>, List<CommitAuthor>, Set<Long>, IdentityMap) -> List<NormCodeIdentity> = ::codeIdentities,
    val commit: (RawCommitForProcessing, List<RawCommitFileForProcessing>, (String) -> Boolean, IdentityMap) -> NormCodeCommit =
        ::codeCommit,
    val mergeRequest: (RawMergeRequestForProcessing, List<RawMrEventForProcessing>, IdentityMap) -> NormCodeMergeRequest =
        ::codeMergeRequest,
)

/**
 * The GitLab PROCESS stream (`gitlab-integration.md` "PROCESS / PROFILE"; no HTTP): rebuilds the code layer (`norm.code_*`, V44) from the
 * raw GitLab rows. In order:
 *
 *  1. **The fingerprints.** The configuration the norm rows depend on is compared with the one stored in the stream's cursor row. A
 *     DIFFERENT exclusion list or author-address map re-flags every COMMIT (`needs_processing`; a merge request's line counts are not
 *     exclusion-aware and its keys are ids); a different username map (it resolves a review request's `@username` targets) re-flags the
 *     merge requests too. The new fingerprints are stored in the SAME transaction, so an operator's change, a release that changes the
 *     default list and an address or user added later all reach the norm rows at this run with no refetch. An absent or undecodable
 *     cursor re-flags everything (the configuration the rows were computed under is unknown; on a first run they are all flagged anyway).
 *  2. **The reference rows**, wholesale: projects, then identities (each in its own transaction). A failure here is a real fault of the
 *     run: nothing below it can be trusted without the identities.
 *  3. **The commits**, in keyset pages of 50 by `(project_id, sha)`, then 4. **the merge requests**, in pages of 50 by `mr_id`. ONE
 *     transaction per PAGE: the raw rows are read `FOR NO KEY UPDATE` (the files and events reference them), normalized in memory,
 *     written, and marked processed. A concurrent writer that re-flags a row (a files flush, a tombstone, a changed merge request) waits
 *     for the page and lands after the mark, so its flag is never overwritten. (A files flush INSERTS file rows, which only take
 *     `FOR KEY SHARE` and do not wait; they are invisible until the flush commits, and its UPDATE of the commit row is what waits.) A row a
 *     concurrent PURGE removed is absent and not marked.
 *
 * **A failing page is bounded, not fatal.** A page whose rows fault (the normalizer throws, or the database refuses a value, SQLSTATE class
 * 22/23) rolls back, keeps its rows flagged, is counted (`commitPagesFailed` / `mergeRequestPagesFailed`), logs ONE WARN (stage and
 * raw keys only) and the pass moves on - the merge requests run even when a commit page failed. More than [PROCESS_FAILED_PAGES_MAX] failed
 * pages in one run is an outage: the last failure is rethrown. Any other failure (a connection that cannot start a transaction,
 * cancellation, a lost lease) propagates at once. Every failure leaves as a [ProcessFailure]: stage, keys and the cause's class, never a
 * bound value.
 *
 * 5. **The closing `ANALYZE`** of `norm.code_commits`, `norm.code_merge_requests` and `norm.code_mr_events`
 *    ([CodeActivityStore.analyzeAfterProcess], its own short transaction) when the run wrote rows AND a table was never analyzed, the
 *    changes since the last ANALYZE reach a tenth of what their statistics describe, or this connection is absent from the
 *    `connection_id` most common values: the PROFILE's event count (and a second source's reads) would otherwise plan the new rows
 *    at one row (build-times WHY 60). A failure or a lock timeout is one WARN, never a failed run. `code_projects` and
 *    `code_identities` are left out: hundreds of rows, rebuilt wholesale, read only by `connection_id`-filtered counts.
 *
 * Progress: `commitsProcessed`, `mergeRequestsProcessed`, `identities` (rows written), `exclusionsChanged` / `identityMapChanged` /
 * `usernameMapChanged` (1 when that fingerprint re-flagged), the failed-page counters, `normAnalyzed` / `normAnalyzeMs` /
 * `normAnalyzeReason` (the closing ANALYZE, present only when it ran). Never calls GitLab, so it is deadline-exempt
 * ([GITLAB_LOCAL_STREAMS]): nothing that calls GitLab may follow it.
 */
internal class GitLabProcessStream(
    private val processStore: GitLabRawProcessStore,
    private val codeStore: CodeActivityStore,
    private val exclusions: List<PathGlob>,
    private val normalizers: ProcessNormalizers = ProcessNormalizers(),
) : Stream {
    override val name: String = CODE_PROCESS_STREAM_NAME

    /** The failed pages of ONE run, across both passes, and the rows it wrote into the analyzed `norm.code_*` tables. */
    private class RunTally(var failedPages: Int = 0, var rowsWritten: Long = 0)

    override suspend fun run(context: StreamContext) {
        val users = staged("users read") { processStore.userRowsForNorm(context.connectionId) }
        val map = IdentityMap.from(users)
        if (staged("fingerprints") { applyFingerprints(context, map) }) context.heartbeat(changed = true)
        staged("reference rows") { rebuildReferenceRows(context, users, map) }
        context.heartbeat(changed = true)
        val tally = RunTally()
        processCommits(context, map, tally)
        processMergeRequests(context, map, tally)
        codeStore.analyzeAfterProcess(context.connectionId, tally.rowsWritten).recordIn(context)
    }

    private suspend fun <T> staged(stage: String, block: suspend () -> T): T = catchingFailures(
        block = { block() },
        onFailure = { throw ProcessFailure(stage, emptyList(), it) },
    )

    /** True when the stored configuration differed (the rows were re-flagged and the new fingerprints stored). */
    private suspend fun applyFingerprints(context: StreamContext, map: IdentityMap): Boolean {
        val current = CodeProcessCursor(exclusionsFingerprint(exclusions.map { it.pattern }), map.fingerprint, map.usernameFingerprint)
        val stored = context.cursor(name)?.cursor?.let { text -> catchingFailures({ decodeCursor<CodeProcessCursor>(text) }, { null }) }
        if (stored == current) return false
        context.transaction {
            // Merge requests only follow a username change; commits follow every change. No usable stored cursor: re-flag everything.
            val commitsOnly = stored != null && stored.usernameMapSha256 == current.usernameMapSha256
            processStore.markAllNeedsProcessing(context.connectionId, commitsOnly)
            if (stored != null) {
                if (stored.exclusionsSha256 != current.exclusionsSha256) context.incrementProgress("exclusionsChanged")
                if (stored.identityMapSha256 != current.identityMapSha256) context.incrementProgress("identityMapChanged")
                if (stored.usernameMapSha256 != current.usernameMapSha256) context.incrementProgress("usernameMapChanged")
            }
            context.putCursor(name, encodeCursor(current), lastCompletedAt = context.clock())
        }
        return true
    }

    private suspend fun rebuildReferenceRows(context: StreamContext, users: List<RawUserForNorm>, map: IdentityMap) {
        val connectionId = context.connectionId
        context.transaction { codeStore.replaceProjects(connectionId, processStore.projectRowsForNorm(connectionId)) }
        val identities = context.transaction {
            val authors = processStore.liveCommitAuthors(connectionId)
            val rows = normalizers.identities(users, authors, processStore.referencedUserIds(connectionId), map)
            codeStore.replaceIdentities(connectionId, rows)
        }
        context.incrementProgress("identities", identities.toLong())
    }

    private suspend fun processCommits(context: StreamContext, map: IdentityMap, tally: RunTally) {
        val connectionId = context.connectionId
        val excluded: (String) -> Boolean = { PathGlob.matchesAny(exclusions, it) }
        var after: CommitKey? = null
        while (true) {
            val keys = staged("commit claim") { processStore.commitsToProcess(connectionId, CODE_PROCESSING_VERSION, PROCESS_PAGE, after) }
            if (keys.isEmpty()) break
            after = keys.last()
            val processed = page(context, tally, "commit page", "commitPagesFailed", keys.map { "${it.projectId}/${it.sha}" }) {
                context.transaction {
                    val now = context.clock()
                    val rows = processStore.commitsForProcessing(connectionId, keys)
                    val withFiles = rows.filter { it.parentCount <= 1 && it.filesFetchedAt != null }.map { it.key }
                    val files = processStore.commitFilesForProcessing(connectionId, withFiles)
                    val normalized = normalizing { rows.map { normalizers.commit(it, files[it.key].orEmpty(), excluded, map) } }
                    codeStore.replaceCommits(connectionId, normalized, now)
                    processStore.markCommitsProcessed(connectionId, rows.map { it.key }, now, CODE_PROCESSING_VERSION)
                    rows.size
                }
            }
            tally.rowsWritten += processed
            context.incrementProgress("commitsProcessed", processed.toLong())
            context.heartbeat(changed = processed > 0)
        }
    }

    private suspend fun processMergeRequests(context: StreamContext, map: IdentityMap, tally: RunTally) {
        val connectionId = context.connectionId
        var after: Long? = null
        while (true) {
            val ids = staged("merge request claim") {
                processStore.mergeRequestsToProcess(connectionId, CODE_PROCESSING_VERSION, PROCESS_PAGE, after)
            }
            if (ids.isEmpty()) break
            after = ids.last()
            var pageRows = 0L
            val processed = page(context, tally, "merge request page", "mergeRequestPagesFailed", ids.map { it.toString() }) {
                context.transaction {
                    val now = context.clock()
                    val rows = processStore.mergeRequestsForProcessing(connectionId, ids)
                    val events = processStore.mrEventsForProcessing(connectionId, rows.map { it.mrId })
                    val normalized = normalizing { rows.map { normalizers.mergeRequest(it, events[it.mrId].orEmpty(), map) } }
                    codeStore.replaceMergeRequests(connectionId, normalized, now)
                    processStore.markMergeRequestsProcessed(connectionId, rows.map { it.mrId }, now, CODE_PROCESSING_VERSION)
                    pageRows = normalized.sumOf { 1L + it.events.size }
                    rows.size
                }
            }
            // A failed page returns 0 and rolled back: only a committed page counts.
            if (processed > 0) tally.rowsWritten += pageRows
            context.incrementProgress("mergeRequestsProcessed", processed.toLong())
            context.heartbeat(changed = processed > 0)
        }
    }

    /** Marks a throw of the normalizer as a row fault of the page (cancellation passes untouched). */
    private inline fun <T> normalizing(block: () -> T): T = catchingFailures(block, { throw NormalizationFault(it) })

    /**
     * Runs one page. A row fault is tolerated up to [PROCESS_FAILED_PAGES_MAX] times a run (the page rolled back, its rows stay flagged):
     * 0 rows processed. Anything else, and the fault past the bound, leaves as a [ProcessFailure].
     */
    private suspend fun page(
        context: StreamContext,
        tally: RunTally,
        stage: String,
        counter: String,
        keys: List<String>,
        work: suspend () -> Int,
    ): Int =
        catchingFailures(
            block = { work() },
            onFailure = { failure ->
                val wrapped = ProcessFailure(stage, keys, failure)
                if (!failure.isRowFault() || ++tally.failedPages > PROCESS_FAILED_PAGES_MAX) throw wrapped
                // The stack and class names of the causes, never their messages (`LoggedFailure`).
                log.warn("{}; the rows stay flagged for the next run", wrapped.message, LoggedFailure(failure, wrapped.message.orEmpty()))
                context.incrementProgress(counter)
                0
            },
        )
}
