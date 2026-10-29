package ch.nokillswit.ingest

import ch.nokillswit.infra.catchingFailures
import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.R2dbcTransientResourceException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Deliberately the `IngestWorker` logger name, like `LeaseHeartbeat.kt`: the worker's own lines are filtered on it.
private val log = LoggerFactory.getLogger(IngestWorker::class.java)

private const val MAX_CAUSE_DEPTH = 16

/** SQLSTATEs the server reports while it goes away or refuses a session: admin or crash shutdown, recovery/startup, too many clients. */
private val CONNECTION_LOSS_SQLSTATES = setOf("57P01", "57P02", "57P03", "53300")

/**
 * Whether [failure] (or a cause of it: Exposed wraps the driver's exception) is the loss of the database CONNECTION rather than a
 * statement the database refused. Matched, from what r2dbc-postgresql 1.1.3 / r2dbc-pool 1.0.2 throw (measured, `TerminalWriteRetryTest`):
 *  - an `R2dbcNonTransientResourceException` with no server SQLSTATE (`PostgresConnectionClosedException`, a dead pooled connection
 *    not yet evicted) or with class `08` (connection exception: "Connection unexpectedly closed" `08006`, "Cannot connect" `08003`);
 *  - a server error that ends or refuses sessions - `57P01` (`pg_terminate_backend` / admin shutdown), `57P02` (the postmaster's
 *    crash reset, i.e. a backend killed for memory), `57P03` (the database is starting or in recovery), `53300` (too many clients);
 *  - an `R2dbcTransientResourceException`, an `R2dbcTimeoutException` (the pool's acquire timeout) and a plain `IOException` (a socket).
 * NEVER matched: any other SQLSTATE - a constraint (`23`), a syntax/data error (`22`, `42`), a lock timeout (`55P03`), a statement
 * timeout (`57014`) - nor a shut-down pool (`PoolShutdownException`), so retrying is only ever done where a later attempt can differ.
 */
internal fun isConnectionLoss(failure: Throwable): Boolean {
    var cause: Throwable? = failure
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (matchesConnectionLoss(cause)) return true
        cause = cause.cause?.takeIf { it !== cause }
    }
    return false
}

private fun matchesConnectionLoss(e: Throwable): Boolean {
    val sqlState = (e as? R2dbcException)?.sqlState
    return when {
        sqlState in CONNECTION_LOSS_SQLSTATES -> true
        e is R2dbcNonTransientResourceException -> sqlState == null || sqlState.startsWith("08")
        else -> e is R2dbcTransientResourceException || e is R2dbcTimeoutException || e is IOException
    }
}

/** The class names of [failure] and its causes, never a message (a database exception's message inlines its statement's bound values). */
internal fun failureClassNames(failure: Throwable): String =
    generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH).joinToString(" <- ") { it.javaClass.simpleName }

/**
 * The retry of the worker's fenced terminal job writes (`SyncJobLeases.finish`/`fail`/`failTripped`/`markCancelled`/`release`, through
 * `SyncJobsService`) when the database CONNECTION was lost ([isConnectionLoss]). A postmaster reset (a backend killed for memory) drops
 * every session at once and the pool is dead or refused for seconds: the job body then fails with a connection error, and the failure
 * write - the one that closes the row - hits the same outage. Without a retry the row stayed RUNNING until its lease expired.
 *
 * Back-off: [initialDelayMillis], doubling to [maxDelayMillis], until [budgetMillis] have elapsed since the FIRST failure (the clock starts
 * when that failure is caught; the last pause is cut to the budget); the exhausting failure is rethrown unchanged. The budget is checked
 * between attempts, so an attempt itself (up to a ~20 s pool acquire) is not cut: a write can take the first such attempt, the budget
 * and one last attempt, i.e. up to ~70 s. A failure that is not a connection loss is rethrown at once, and cancellation always
 * (`catchingFailures`; a sleeping retry is cancelled by `delay`).
 *
 * **Why repeating the write is safe.** Each write is fenced by `(id, attempt, status = RUNNING)` (`SyncJobLeases`): a first attempt that
 * DID commit before its reply was lost leaves the row terminal, so the repeat matches no row and answers `false` - nothing is applied
 * twice (a `fail`'s back-off is recorded in the same transaction as its status change, so it commits or not together with it). The cost
 * of the ambiguous case is only that the worker then logs the "stale" WARN and skips the follow-ups it would have run on `true`.
 */
internal class TerminalWriteRetry(
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** The pause between attempts - `delay`; a test records the back-off and skips the wait. */
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
) {
    suspend fun <T> run(jobId: UInt, write: String, block: suspend () -> T): T {
        var firstFailure: TimeMark? = null
        var pause = initialDelayMillis
        var attempt = 1
        while (true) {
            val outcome = catchingFailures({ Result.success(block()) }) { Result.failure(it) }
            val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
            val left = budgetMillis - (firstFailure ?: timeSource.markNow().also { firstFailure = it }).elapsedNow().inWholeMilliseconds
            if (!isConnectionLoss(failure) || left <= 0) throw failure
            val wait = minOf(pause, left)
            log.info(
                "Sync job {} {} write lost its database connection on attempt {} ({}); retrying in {} ms",
                jobId, write, attempt, failureClassNames(failure), wait,
            )
            sleep(wait)
            pause = minOf(pause * 2, maxDelayMillis)
            attempt++
        }
    }

    private companion object {
        const val DEFAULT_INITIAL_DELAY_MILLIS = 500L
        const val DEFAULT_MAX_DELAY_MILLIS = 8_000L
        const val DEFAULT_BUDGET_MILLIS = 30_000L
    }
}

/**
 * Runs the best-effort [write] of a run that already failed or was cancelled (the row's close, or its release) and never lets ITS failure
 * escape: after the retries ([TerminalWriteRetry]) the row is left to the lease expiry (the claim re-claims it, or closes it
 * `RETRIES_EXHAUSTED`) exactly as when the worker JVM dies, with one WARN naming ids and exception CLASSES only. Cancellation propagates.
 */
internal suspend fun SyncJobClaim.closeBestEffort(write: String, block: suspend () -> Unit) {
    catchingFailures({ block() }) { warnLeftToLease(write, it) }
}

/**
 * The success write ([block] = `SyncJobsService.finish`, which retries a connection loss itself) of a run whose body ALREADY succeeded:
 * if it still exhausts on a connection loss the run is NOT a failure - every page committed - so this answers null after the same
 * one WARN as [closeBestEffort] and the row is left to the lease expiry (the claim re-runs the idempotent job). A failure that is not a
 * connection loss is rethrown (the worker's failure path, as before); cancellation propagates.
 */
internal suspend fun SyncJobClaim.finishOrLeaveToLease(block: suspend () -> Boolean): Boolean? =
    catchingFailures({ block() }) {
        if (!isConnectionLoss(it)) throw it
        warnLeftToLease("finish", it)
        null
    }

/**
 * A fenced terminal write matched no row: a newer attempt owns the job, or this run's own first try of the write committed before its
 * reply was lost and the retry found the row already terminal (no exception text).
 */
internal fun SyncJobClaim.warnStale(write: String) {
    log.warn(
        "Sync job {} attempt {}: {} matched no row: a newer attempt owns the job, or this run's first try of it already committed",
        id, attempt, write,
    )
}

private fun SyncJobClaim.warnLeftToLease(write: String, failure: Throwable) {
    log.warn(
        "Sync job {} ({}) for data source {}: the {} write failed ({}); the row stays RUNNING until its lease expires",
        id, kind, connectionId, write, failureClassNames(failure),
    )
}
