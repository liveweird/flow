package ch.nokillswit.ingest

import ch.nokillswit.infra.catchingFailures
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MIN_HEARTBEAT_INTERVAL_MS = 1_000L
/** Floor of the heartbeat slack — only a test lease below 5 s ever reaches it (a configured lease is at least 30 s, so slack >= 3 s). */
private const val MIN_HEARTBEAT_SLACK_MS = 500L

// Deliberately the `IngestWorker` logger name: the heartbeat warnings predate the split and operators/tests filter on it.
private val log = LoggerFactory.getLogger(IngestWorker::class.java)

/**
 * The lease ticker of one [IngestWorker] (`runJob` launches [heartbeatLoop] next to the connector's run): renewal
 * cadence, the per-attempt bound and the lease budget live here, apart from the scheduler/claim/run path.
 * [renewLease] is [IngestWorker]'s seam, [timeSource] its monotonic source.
 */
internal class LeaseHeartbeat(
    private val config: IngestConfig,
    private val clock: () -> Long,
    private val timeSource: TimeSource,
    private val renewLease: suspend (claim: SyncJobClaim, now: Long, boundMillis: Long) -> HeartbeatOutcome,
) {
    private val renewalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The ticker body: every `max(1s, leaseSeconds/3)` it renews the lease and checks `cancel_requested_at` in ONE call
     * ([renewLease], one pooled connection, one failure policy). [HeartbeatOutcome.LOST] is fatal at once
     * ([LeaseLostException]); [HeartbeatOutcome.CANCEL_REQUESTED] throws [JobCancelRequestedException].
     *
     * **A failing renewal (it throws, or hangs) is tolerated only while the renewal can still be stopped
     * strictly BEFORE `lease_until`** — a DERIVE must survive one pool hiccup, but the worker must not keep renewing
     * (and running) as if nothing were wrong until another claimer may take the row. With `L` the lease,
     * `slack = max(500 ms, L/10)` and `E` the monotonic time since [leaseStart] / the START of the last successful
     * renewal (taken before the call, like the `now` the lease is computed from, so it can only be early):
     *  - every attempt is bounded by `withTimeoutOrNull(slack)` on the client (a timeout counts as a failure) and, in the
     *    database, by `lock_timeout` + `queryTimeout` of about `slack` (`renewLease`'s `boundMillis`);
     *  - after a failure observed at `E`, the retry (after `retry = max(1s, interval/2)`) is tolerated only if
     *    `E + retry + 2·slack < L`. A tolerated retry therefore starts at `E + retry` and its failure is observed by
     *    `E + retry + slack < L - slack`: **renewal stops and the job's cancellation is REQUESTED at least `slack`
     *    before `lease_until`** (that `slack` is the allowance for the stop itself and for clock skew against the
     *    reclaimer, which compares `lease_until` with ITS wall clock). The first failure is observed by
     *    `interval + slack`, well inside that. Otherwise the failure is rethrown (`runJob` → `onFailed`) at once.
     *
     * What this does NOT promise: the lease bounds a run, it does not exclude a stale one. Cancelling the job scope
     * only requests the body to stop — a body inside a database statement unwinds when that statement returns (its
     * open transaction then rolls back), and a frozen worker (a long GC pause, a stopped container) does not unwind
     * at all. Every `sync_jobs` write of such a stale run is a no-op through the fences (`SyncJobsService`), but its
     * stream DATA writes are not fenced, so it can overlap a reclaimed run in that window (the streams are re-runnable by
     * design — cursors, per-scope replace — but nothing excludes the overlap).
     *
     * Which failure is the fatal one depends on how long the failures take: at the defaults (L = 300 s, 100 s interval,
     * 50 s retry, slack 30 s) the threshold is `E < 190 s`, so FAST failures (observed at 100 s and 150 s) are tolerated
     * and a third, at 200 s, is fatal; failures that each take the whole slack (observed at 130 s, then 210 s) make the
     * second one fatal. Cancellation is never swallowed (`catchingFailures`).
     */
    suspend fun heartbeatLoop(claim: SyncJobClaim, leaseStart: TimeMark): Nothing {
        val leaseMillis = config.leaseSeconds * 1000
        val intervalMillis = maxOf(MIN_HEARTBEAT_INTERVAL_MS, leaseMillis / 3)
        val retryMillis = maxOf(MIN_HEARTBEAT_INTERVAL_MS, intervalMillis / 2)
        val slackMillis = maxOf(MIN_HEARTBEAT_SLACK_MS, leaseMillis / 10)
        var lastRenewalStart = leaseStart
        var waitMillis = intervalMillis
        while (true) {
            delay(waitMillis)
            val attemptStart = timeSource.markNow()
            var failure: Exception? = null
            val outcome = catchingFailures({ attemptRenewal(claim, slackMillis) }) {
                failure = it
                null
            }
            if (outcome == null) {
                val sinceRenewalMillis = lastRenewalStart.elapsedNow().inWholeMilliseconds
                if (sinceRenewalMillis + retryMillis + 2 * slackMillis >= leaseMillis) {
                    throw failure ?: TimeoutException(
                        "Heartbeat for sync job ${claim.id} timed out ($slackMillis ms) at $sinceRenewalMillis of $leaseMillis ms",
                    )
                }
                log.warn(
                    "Heartbeat for sync job {} did not complete ({} ms since the last renewal started, lease {} ms); retrying in {} ms",
                    claim.id, sinceRenewalMillis, leaseMillis, retryMillis, failure,
                )
                waitMillis = retryMillis
                continue
            }
            when (outcome) {
                HeartbeatOutcome.LOST -> throw LeaseLostException(claim.id)
                HeartbeatOutcome.CANCEL_REQUESTED -> throw JobCancelRequestedException(claim.id)
                HeartbeatOutcome.RENEWED -> {
                    lastRenewalStart = attemptStart
                    waitMillis = intervalMillis
                }
            }
        }
    }

    /**
     * One renewal attempt, bounded by [slackMillis] — `null` on timeout. The renewal runs in the worker's own [renewalScope],
     * NOT as a child of the job: a coroutine blocked inside a database statement does not react to cancellation until that
     * statement ends (exposed-r2dbc's transaction waits for its rollback — measured in `SyncJobQueueTest`), so a
     * `withTimeoutOrNull` around the call itself would not return on time, and a failing ticker could not cancel the job
     * scope either (it waits for its children). Awaiting a detached [Deferred] returns at the timeout; the abandoned attempt
     * is cancelled and rolls back whenever its statement ends — and the renewal carries `slackMillis` as its server-side
     * bound too, so that is within about a slack (`lock_timeout`/`queryTimeout`), unless the connection itself is stalled.
     */
    private suspend fun attemptRenewal(claim: SyncJobClaim, slackMillis: Long): HeartbeatOutcome? {
        val attempt = renewalScope.async { renewLease(claim, clock(), slackMillis) }
        try {
            return withTimeoutOrNull(slackMillis) { attempt.await() }
        } finally {
            attempt.cancel()
        }
    }

    /** Cancels the renewal attempts still running (shutdown); the job scopes are cancelled by the caller's own scope. */
    fun close() {
        renewalScope.cancel()
    }
}
