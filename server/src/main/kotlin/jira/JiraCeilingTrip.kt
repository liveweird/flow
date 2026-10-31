package ch.nokillswit.jira

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.isOversized
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.LIMIT_EXCEEDED_TRIP
import ch.nokillswit.ingest.StreamContext

/**
 * The streams that never call Jira ([PROCESS_STREAM_NAME], [PROFILE_STREAM_NAME]): they do not enforce the job's wall clock
 * ([ch.nokillswit.ingest.StreamContext]), so a run that finished every Jira-calling stream cannot end `TIME_EXCEEDED`.
 */
internal val LOCAL_STREAMS = setOf(PROCESS_STREAM_NAME, PROFILE_STREAM_NAME)

internal const val PROCESS_STREAM_NAME = "process"
internal const val PROFILE_STREAM_NAME = "profile"

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
