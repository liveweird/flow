package ch.nokillswit.ingest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
     * [heartbeat] at or past it throws a `TIME_EXCEEDED` [CeilingTripException]. Heartbeats come between pages, so this graceful stop is
     * always BETWEEN pages and the next run resumes from the cursors; [withHardStop] is the backstop for a run that never reaches one.
     */
    maxRunMillis: Long? = null,
    /**
     * Streams that never call the host (`process`, `profile`): their heartbeats do not enforce the deadline, so a run that finished
     * every Jira-calling stream is never recorded `TIME_EXCEEDED` for the local work after it.
     */
    private val deadlineExemptStreams: Set<String> = emptySet(),
    /**
     * The HARD stop, as a duration from the start of [withHardStop] (`SyncJobRunContext.hardStopMillis`: the budget plus
     * `ingest.hardStopGraceSeconds`), or null for none. Timed on the coroutine clock, not on [clock]: it exists for the case where no
     * heartbeat is coming (a call or a page that does not return), which a fake or frozen [clock] cannot model.
     */
    private val hardStopMillis: Long? = null,
) {
    private val deadlineAt: Long? = maxRunMillis?.let { clock() + it }

    /** Completed when the run enters a [deadlineExemptStreams] stream: the local tail, which the hard stop must not interrupt either. */
    private val localPhase = CompletableDeferred<Unit>()

    /** Set just before [withHardStop] cancels its work; tells that cancellation apart from the worker's own (shutdown). */
    @Volatile
    private var hardStopFired = false

    /**
     * Set by the job runner before each stream's `run` (`jira/JiraConnector.kt`) — flushed onto `sync_jobs.current_stream`. Naming a
     * new stream restarts the per-stream change count that [ceilingTrip] reads as "made progress" for a limit trip.
     */
    var currentStreamName: String? = null
        set(value) {
            field = value
            changedInStream = 0
            if (value in deadlineExemptStreams) localPhase.complete(Unit)
        }

    /**
     * Pages (or flushes) of the current stream that CHANGED stored data: a [heartbeat] with `changed = true` or a [markChanged]. A
     * page whose rows all came back identical (`RawUpsertOutcome.UNCHANGED`) is not counted, so a loop that re-stores the same
     * pages every run is no progress.
     */
    private var changedInStream = 0

    /** The same count over the whole run, across every stream; never reset. A wall-clock stop reads it ([ceilingTrip]). */
    private var changedInRun = 0

    private val progress = mutableMapOf<String, Long>()

    /**
     * Bumps a named progress counter (e.g. `pages`, `issuesUpserted`, `entities`, `changelogs`,
     * `worklogs`, `worklogsOutOfScope`, `issuesSkipped`, `changelogsSkipped`, `worklogsSkipped`, `worklogsIssuesSkipped`, `tombstoned`,
     * `movedOutOfScope`, `indexGapSkipped`, `projectFieldsSkipped`, `usersSkipped`, PROCESS's
     * `issuesProcessed`/`issuesFailed`, the closing `ANALYZE` of both PROCESS streams (`normAnalyzed`, `normAnalyzeMs`,
     * `normAnalyzeReason`: 1 never analyzed, 2 accumulated changes, 3 connection absent from the statistics; all absent when it did not
     * run), and `referenceRowsSkipped` — reference rows PROCESS left out, where a reference
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
     * flushes the accumulated progress counters and [currentStreamName] onto the job row. [changed] is whether the page that was just
     * committed CHANGED stored data, derived from the write's own outcome (`RawUpsertOutcome`); it has no default so every call site
     * decides. False for a page whose rows all came back unchanged and for the heartbeats that keep the lease alive BEFORE anything is
     * written (the boards pages, a worklog fetch ahead of its flush): they still enforce the deadline but are no progress
     * ([ceilingTrip]). A stream that cannot re-commit identical pages (it drains a stale set or advances a monotonic cursor) passes true.
     */
    suspend fun heartbeat(changed: Boolean) {
        if (!jobHeartbeat(progressJson(), currentStreamName)) throw LeaseLostException(jobId)
        if (changed) markChanged()
        if (deadlineAt != null && currentStreamName !in deadlineExemptStreams && clock() >= deadlineAt) {
            throw ceilingTrip(TIME_EXCEEDED_TRIP, failure = null)
        }
    }

    /**
     * Runs [block] (the whole run) and cancels it IN FLIGHT once [hardStopMillis] has elapsed, whatever it is doing: a Jira call that
     * does not return, a 429 wait, a page's transaction (which rolls back, so a cursor never moves past a page that did not commit).
     * It is the backstop of the graceful check in [heartbeat], which only runs between pages. The stop surfaces as a
     * `TIME_EXCEEDED` [CeilingTripException] with [CeilingTripException.hardStop] set and the run's own progress reading
     * ([ceilingTrip]), so the worker closes the job exactly as for the graceful trip. The timer is disarmed for good when the run
     * enters a [deadlineExemptStreams] stream (`process`/`profile`: local work, never a reason to fail a run that finished its Jira
     * work), like the graceful check. A cancellation from OUTSIDE (the worker's shutdown) is not this stop and propagates untouched.
     */
    suspend fun <T> withHardStop(block: suspend () -> T): T {
        val limit = hardStopMillis ?: return block()
        var work: Deferred<T>? = null
        try {
            return coroutineScope {
                val started = async { block() }.also { work = it }
                val watchdog = launch {
                    if (withTimeoutOrNull(limit) { localPhase.await() } == null && started.isActive) {
                        hardStopFired = true
                        started.cancel(CancellationException("job hard stop after $limit ms"))
                    }
                }
                try {
                    started.await()
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            // Our own stop only while the caller itself is still live; a shutdown that races it wins and propagates as it is.
            if (!hardStopFired || !currentCoroutineContext().isActive) throw cancelled
            throw hardStopTrip()
        } catch (failure: Exception) {
            // The cancelled work may end in an ordinary failure instead of a CancellationException (a transport that reports its cancelled
            // call as an IOException, retried into a terminal fetch failure): still the hard stop's doing, the failure kept as suppressed.
            if (hardStopFired && work?.isCancelled == true && currentCoroutineContext().isActive) {
                throw hardStopTrip().also { it.addSuppressed(failure) }
            }
            throw failure
        }
    }

    private fun hardStopTrip() = CeilingTripException(TIME_EXCEEDED_TRIP, changedInRun > 0, null, hardStop = true)

    /**
     * A stream changed stored data in a transaction of its own BETWEEN heartbeats (the WORKLOGS early or on-failure flush, ISSUES'
     * moved-out sweep): that is a change for [ceilingTrip] too, whatever the next heartbeat says.
     */
    fun markChanged() {
        changedInStream++
        changedInRun++
    }

    /**
     * [failure] (null for the wall clock) as a ceiling trip of [code]. "Progress" means stored CHANGES (the rule the pause counts by,
     * `.claude/docs/ingestion.md` "Ceiling trips and the pause"): a wall-clock stop ([TIME_EXCEEDED_TRIP]) reads the whole run, since
     * the clock does not blame the stream it happened to stop in; a limit trip reads the tripping stream's own changes, deliberately
     * not the run's (REFERENCE changes something every day, which would mask a stuck ISSUES). Call it BEFORE the failure's last
     * best-effort [heartbeat].
     */
    fun ceilingTrip(code: String, failure: Exception?): CeilingTripException =
        CeilingTripException(code, if (code == TIME_EXCEEDED_TRIP) changedInRun > 0 else changedInStream > 0, failure)
}
