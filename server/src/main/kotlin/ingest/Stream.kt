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
) {
    /** Set by the job runner before each stream's `run` (`jira/JiraConnector.kt`) — flushed onto `sync_jobs.current_stream`. */
    var currentStreamName: String? = null

    private val progress = mutableMapOf<String, Long>()

    /**
     * Bumps a named progress counter (e.g. `pages`, `issuesUpserted`, `entities`, `changelogs`,
     * `worklogs`, `worklogsOutOfScope`, `tombstoned`, `movedOutOfScope`, `indexGapSkipped`, PROCESS's
     * `issuesProcessed`/`issuesFailed`, and `referenceRowsSkipped` — reference rows PROCESS left out, where a reference
     * table that failed whole on a bad value counts as ONE).
     */
    fun incrementProgress(key: String, by: Long = 1) {
        progress[key] = (progress[key] ?: 0L) + by
    }

    /** Test-only inspection of the counters accumulated so far in this stream run (`JiraSyncPipelineTest`, `IngestWorkerTest`). */
    fun progressSnapshot(): Map<String, Long> = progress.toMap()

    private fun progressJson(): String = buildJsonObject { progress.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }.toString()

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
     * flushes the accumulated progress counters and [currentStreamName] onto the job row.
     */
    suspend fun heartbeat() {
        if (!jobHeartbeat(progressJson(), currentStreamName)) throw LeaseLostException(jobId)
    }
}
