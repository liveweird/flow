package ch.nokillswit.ingest

import ch.nokillswit.fetch.CALL_TALLY_KEYS
import ch.nokillswit.fetch.CallTally
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.isOversized
import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/** How far down a failure's causes a `LIMIT_EXCEEDED` is looked for (a stream may wrap the fetch failure once or twice). */
private const val MAX_CAUSE_DEPTH = 5

/**
 * [this] failure of a SYNC/RECONCILE run as the ceiling trip the pause counts (`.claude/docs/ingestion.md` "Ceiling trips and the
 * pause"), or null when it is an ordinary failure: a [CeilingTripException] already (the job's wall clock, thrown by
 * [StreamContext.heartbeat]) is returned as it is; a `LIMIT_EXCEEDED` [FetchException] anywhere in the cause chain (a run
 * ceiling, an answer ceiling, the oversized skip budget) is wrapped, with whether the stream that tripped had stored a change.
 */
internal fun Exception.ceilingTripOf(context: StreamContext): CeilingTripException? = when {
    this is CeilingTripException -> this
    generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is FetchException && it.isOversized() } ->
        context.ceilingTrip(LIMIT_EXCEEDED_TRIP, this)
    else -> null
}

/** The run scaffolding every connector's SYNC shares: the [CallTally] around the streams, the carried counters, the typed trip. */
object ConnectorRun {
    /**
     * Runs [block] with the job's [StreamContext] inside a fresh [CallTally] (the job's rate-limiting cost, `fetch/CallTally.kt`):
     * every `SourceHttp` call under it - the streams' fetch-ahead children included - counts into it, and the context's heartbeats
     * flush it onto `sync_jobs.progress`. REPROCESS never calls the upstream, so it takes none.
     *
     * A RETRIED attempt (attempt > 1) starts from the sticky keys the previous attempt flushed ([stickyKeys]: the tally's counters,
     * which add up, plus whatever else the connector keeps across attempts, e.g. Jira's `backfill`), because its first heartbeat
     * replaces the job's `progress`. An attempt that FAILS makes one last best-effort heartbeat before the failure propagates (under
     * [NonCancellable]; a lost lease or a database error is swallowed; also for a `StackOverflowError`, the one `Error` handled), so
     * the throttling that caused a `RATE_LIMITED` give-up is on the job row; a cancellation propagates untouched. A `LIMIT_EXCEEDED`
     * failure leaves as a [CeilingTripException] ([ceilingTripOf]); the job's wall clock already is one, graceful (between pages) or
     * hard ([StreamContext.withHardStop], which wraps the whole run here: a call or page still in flight at the budget plus its grace
     * is cancelled and the run leaves as the same `TIME_EXCEEDED` trip).
     */
    suspend fun withTally(
        context: SyncJobRunContext,
        database: R2dbcDatabase,
        cursors: SyncCursorsService,
        now: () -> Long,
        deadlineExemptStreams: Set<String>,
        stickyKeys: Set<String> = CALL_TALLY_KEYS,
        block: suspend (StreamContext) -> Unit,
    ) {
        val carried = carriedProgress(context.claim, stickyKeys)
        val tally = CallTally(carried)
        // The sticky keys outside the tally ride along unchanged (the tally snapshot re-adds its own).
        val passThrough = carried.filterKeys { it !in CALL_TALLY_KEYS }
        val streamContext = StreamContext(
            context.claim.connectionId, context.claim.id, database, cursors, context.heartbeat, now,
            extraProgress = { passThrough + tally.snapshot() }, maxRunMillis = context.maxRunMillis,
            deadlineExemptStreams = deadlineExemptStreams, hardStopMillis = context.hardStopMillis,
        )
        // A StackOverflowError (an Error, which `catchingFailures` leaves alone) is flushed and rethrown like any failure.
        val failure = try {
            catchingFailures<Throwable?>({ withContext(tally) { streamContext.withHardStop { block(streamContext) } }; null }) { it }
        } catch (overflow: StackOverflowError) {
            overflow
        }
        if (failure != null) {
            // Typed BEFORE the last heartbeat below, which is no change; the worker closes the job on the typed trip (`SyncPause.kt`).
            val thrown = (failure as? Exception)?.ceilingTripOf(streamContext) ?: failure
            withContext(NonCancellable) { catchingFailures({ streamContext.heartbeat(changed = false) }) { } }
            throw thrown
        }
    }

    /** The sticky counters the claim's previous attempt flushed (see [withTally]); empty on a first attempt. */
    private fun carriedProgress(claim: SyncJobClaim, stickyKeys: Set<String>): Map<String, Long> {
        val prior = claim.priorProgress?.takeIf { claim.attempt > 1 } ?: return emptyMap()
        val parsed = catchingFailures({ Json.parseToJsonElement(prior) as? JsonObject }) { null } ?: return emptyMap()
        return parsed.filterKeys { it in stickyKeys }
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }.toMap()
    }
}
