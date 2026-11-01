package ch.nokillswit.reports

import ch.nokillswit.authz.TooManyRequestsException
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.r2dbc.Query

/*
 * The row budget of Report 21's code reads (`.claude/docs/reports.md` "Report 21"): a request may load at most `reports.codeMaxRows`
 * commit, merge request and review event rows (the rows the JVM holds and attributes), whatever the level -- the whole team, a USER's
 * picks or the UNASSIGNED bucket's every key. Every row statement is limited to ONE row more than the budget has left, so an
 * over-budget read is DETECTED (the extra row arrives), never silently cut; the charge after the statement then refuses the request
 * with a 400 that tells the caller to narrow it. A request exactly at the budget answers as it always did. The row budget bounds ONE
 * request's heap; [CodeReadPermits] bounds how many such requests run at once, so the product is bounded too.
 */

/** The default of `reports.codeMaxRows`: well above any real selection (a 70-developer quarter is far below it), far below a heap. */
internal const val DEFAULT_CODE_MAX_ROWS = 100_000

/** The default of `reports.codeReadPermits`: Report 21 computations (and drills) that may run at once in one web process. */
internal const val DEFAULT_CODE_READ_PERMITS = 2

/** The ceiling of `reports.codeReadPermits`. */
internal const val MAX_CODE_READ_PERMITS = 64

/** The ceiling of `reports.codeMaxRows` (a larger budget is no bound at all for the heap it protects). */
internal const val MAX_CODE_MAX_ROWS = 5_000_000

/**
 * One request's running row count against [maxRows]. Not thread-safe by design: a request's reads are sequential inside its one report
 * transaction.
 */
internal class CodeRowBudget(val maxRows: Int = DEFAULT_CODE_MAX_ROWS) {
    /** The rows charged so far (an over-budget read shows `maxRows + 1` here: the statement's limit, not the table's size). */
    var used = 0L
        private set

    /** The `LIMIT` of the next row statement: one more than the rows still allowed (at least 1), so an over-budget read shows itself. */
    val limit: Int get() = (maxRows - used + 1).coerceAtLeast(1).toInt()

    /** Counts [n] loaded rows; a total above [maxRows] refuses the request (400). */
    fun charge(n: Int) {
        used += n
        if (used > maxRows) {
            throw BadRequestException(
                "The selection reads more than $maxRows code rows (commits, merge requests and review events) - " +
                    "narrow the period or pick fewer people",
            )
        }
    }
}

/**
 * Streams the rows of this query through [map] into a list, never more than [budget] allows: the statement is `LIMIT budget.limit`
 * (rendered before the first row is fetched), the rows are mapped as they arrive (no intermediate `List<ResultRow>`), the order is
 * the query's own, and the rows are charged to [budget] afterwards.
 */
internal suspend fun <T> Query.toBudgetedList(budget: CodeRowBudget, map: (ResultRow) -> T): List<T> {
    val rows = ArrayList<T>()
    limit(budget.limit).collect { rows += map(it) }
    budget.charge(rows.size)
    return rows
}

/**
 * The per-process gate of Report 21's computation and its drill (the only reads that can hold [CodeRowBudget]'s worth of rows): at most
 * `count` run at once; a request waits up to [waitMs] (the statement budget) for a permit, then answers a 429. Taken BEFORE the report
 * transaction opens, so a waiter holds no pooled connection. Precedent: `ingest/RawIssueInspectorRoutes.kt`'s semaphore.
 */
class CodeReadPermits(count: Int, private val waitMs: Long) {
    private val gate = Semaphore(count)

    /**
     * Runs [block] holding a permit. The permit is released in `finally`, and a cancellation (a client that left) while waiting takes
     * none: `acquired` is set in the statement right after `acquire` returns, with no suspension point between, and a cancelled
     * `acquire` gives its permit back itself.
     */
    internal suspend fun <T> withPermit(block: suspend () -> T): T {
        var acquired = false
        try {
            withTimeoutOrNull(waitMs) {
                gate.acquire()
                acquired = true
            }
            if (!acquired) throw TooManyRequestsException("Too many code activity reports are being computed at once - retry in a moment")
            return block()
        } finally {
            if (acquired) gate.release()
        }
    }
}
