package ch.nokillswit.ingest

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A resumable phase within a sync job (v0.2.0 plan §7): REFERENCE/ISSUES/CHANGELOGS/WORKLOGS/
 * RECONCILE land as of plan commit 7; PROCESS/PROFILE arrive in later commits — every one of them
 * implements this same shape. [name] is the `sync_cursors.stream` key that owns its cursor's shape
 * (`ingest/SyncCursors.kt`) and doubles as `sync_jobs.current_stream` while it runs
 * (`jira/JiraConnector.kt` sets [StreamContext.currentStreamName] before each stream's `run`).
 */
interface Stream {
    val name: String
    suspend fun run(context: StreamContext)
}

/**
 * What a stream needs from its enclosing job — cursor read/write scoped to [connectionId], a
 * [jobHeartbeat] that throws [LeaseLostException] the moment the lease is gone (a stream never has
 * to check a `Boolean` itself and risk writing a cursor afterward), a [transaction] wrapper so a
 * page's raw-store writes and its cursor advance land in ONE transaction (`.claude/docs/
 * persistence.md` "Sync cursors" — Exposed reuses an enclosing `suspendTransaction` for nested
 * calls against the SAME [R2dbcDatabase], so [JiraRawStore]/[SyncCursorsService]'s own
 * `suspendTransaction` calls join this one), and an injectable [clock].
 *
 * [incrementProgress]/[currentStreamName] (v0.2.0 plan §12 item 7): a small per-stream counter map
 * flushed onto `sync_jobs.progress`/`current_stream` on every [heartbeat] call
 * (`ingest/SyncJobsService.kt`'s `heartbeat`) — a lightweight, best-effort sync-progress signal for
 * the status endpoint (`GET …/{id}/status`), not itself part of any correctness invariant.
 */
class StreamContext(
    val connectionId: UInt,
    val jobId: UInt,
    private val database: R2dbcDatabase,
    private val cursors: SyncCursorsService,
    private val jobHeartbeat: suspend (progress: String?, currentStream: String?) -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
    /**
     * Counters the connector keeps outside the streams (the Jira call tally: 429s, back-off and pause time), merged into every
     * heartbeat's progress JSON beside the stream-local ones. Only non-zero keys are expected, the [incrementProgress] convention.
     */
    private val extraProgress: () -> Map<String, Long> = { emptyMap() },
    /**
     * The job's wall-clock ceiling as a DURATION (`ingest.maxJobMinutes`, `SyncJobRunContext.maxRunMillis`), or null for none; the
     * deadline is this context's own [clock] at construction plus it (one clock for the start and every check). The first
     * [heartbeat] at or past it throws a `TIME_EXCEEDED` [CeilingTripException]. Heartbeats come between pages, so the run always
     * stops BETWEEN pages and the next run resumes from the cursors.
     */
    maxRunMillis: Long? = null,
    /**
     * Streams that never call the host (`process`, `profile`): their heartbeats do not enforce the deadline, so a run that finished
     * every Jira-calling stream is never recorded `TIME_EXCEEDED` for the local work after it.
     */
    private val deadlineExemptStreams: Set<String> = emptySet(),
) {
    private val deadlineAt: Long? = maxRunMillis?.let { clock() + it }

    /**
     * Set by the job runner before each stream's `run` (`jira/JiraConnector.kt`) — flushed onto `sync_jobs.current_stream`. Naming a
     * new stream restarts the per-stream page count that [ceilingTrip] reads as "made progress".
     */
    var currentStreamName: String? = null
        set(value) {
            field = value
            pagesInStream = 0
        }

    /** COMMITTED [heartbeat]s of the current stream - each is after a page's transaction, so any means its rows and cursor moved. */
    private var pagesInStream = 0

    private val progress = mutableMapOf<String, Long>()

    /**
     * Bumps a named progress counter (e.g. `pages`, `issuesUpserted`, `entities`, `changelogs`,
     * `worklogs`, `worklogsOutOfScope`, `issuesSkipped`, `changelogsSkipped`, `worklogsSkipped`, `worklogsIssuesSkipped`, `tombstoned`,
     * `movedOutOfScope`, `indexGapSkipped`, `projectFieldsSkipped`, `usersSkipped`, PROCESS's
     * `issuesProcessed`/`issuesFailed`, and `referenceRowsSkipped` — reference rows PROCESS left out, where a reference
     * table that failed whole on a bad value counts as ONE).
     */
    fun incrementProgress(key: String, by: Long = 1) {
        progress[key] = (progress[key] ?: 0L) + by
    }

    /**
     * The counters accumulated so far in this job run - the streams' own plus the connector's [extraProgress]: the heartbeat's
     * progress JSON is built from it, and tests inspect it.
     */
    fun progressSnapshot(): Map<String, Long> = progress.toMap() + extraProgress()

    private fun progressJson(): String =
        buildJsonObject { progressSnapshot().forEach { (key, value) -> put(key, JsonPrimitive(value)) } }.toString()

    suspend fun cursor(stream: String): SyncCursorRow? = cursors.get(connectionId, stream)

    suspend fun putCursor(
        stream: String,
        cursor: String,
        watermarkAt: Long? = null,
        lastCompletedAt: Long? = null,
        now: Long = clock(),
    ) = cursors.put(connectionId, stream, cursor, watermarkAt, lastCompletedAt, now)

    suspend fun clearCursor(stream: String) = cursors.clear(connectionId, stream)

    /** Wraps [block] in one transaction against the SAME [database] every raw-store/cursor call in [block] uses. */
    suspend fun <T> transaction(block: suspend () -> T): T = suspendTransaction(database) { block() }

    /**
     * Call once per page, AFTER that page's writes are committed (so a lease loss here never
     * un-writes anything) — throws [LeaseLostException] once the lease is gone, which every stream
     * lets propagate rather than catching (`ingest/IngestWorker.kt`'s `runJob` handles it). Also
     * flushes the accumulated progress counters and [currentStreamName] onto the job row. [committed] = false for the two heartbeats that
     * keep the lease alive BEFORE anything is written (the boards pages, a worklog fetch ahead of its flush): they still enforce the
     * deadline but do not count as a stream's progress ([ceilingTrip]).
     */
    suspend fun heartbeat(committed: Boolean = true) {
        if (!jobHeartbeat(progressJson(), currentStreamName)) throw LeaseLostException(jobId)
        if (committed) pagesInStream++
        if (deadlineAt != null && currentStreamName !in deadlineExemptStreams && clock() >= deadlineAt) {
            throw ceilingTrip(TIME_EXCEEDED_TRIP, failure = null)
        }
    }

    /**
     * A stream wrote rows in a transaction of its own BETWEEN heartbeats (the WORKLOGS early or on-failure flush): that is a stored
     * page for [ceilingTrip] too, whatever the next heartbeat says.
     */
    fun markCommitted() {
        pagesInStream++
    }

    /**
     * [failure] (null for the wall clock) as a ceiling trip of [code]. It made progress when the stream that is running committed a
     * page in this run - the rule the pause counts by (`.claude/docs/ingestion.md` "Ceiling trips and the pause"). Call it BEFORE the
     * failure's last best-effort [heartbeat], which would count as a page.
     */
    fun ceilingTrip(code: String, failure: Exception?): CeilingTripException = CeilingTripException(code, pagesInStream > 0, failure)
}
