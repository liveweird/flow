package ch.nokillswit.norm

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.StreamContext
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

// Planner statistics of the normalized tables a PROCESS has just filled (`.claude/docs/persistence.md` "Planner statistics of `norm`",
// build-times WHY 60). Autovacuum analyzes a table only after ~10 % of it changed AND its naptime (a minute) passed, so a DERIVE
// queued right behind a PROCESS plans against statistics that describe another connection's rows (or none): `connection_id = N`
// estimated at 1 row, nested loops over the whole table, 41M buffer hits. The helper below closes that gap per CONNECTION: a PROCESS
// that wrote rows ANALYZEs when the tables were never analyzed, when the changes accumulated since the last ANALYZE are material, or
// when the statistics do not know this connection at all.

private val log = LoggerFactory.getLogger("ch.nokillswit.norm.NormAnalyze")

/**
 * How long the post-PROCESS `ANALYZE` waits for a table lock another session holds (a manual `VACUUM`, another `ANALYZE`, DDL) before
 * giving up with a WARN: the value and the rationale of `metrics/`'s `DEFAULT_ANALYZE_LOCK_TIMEOUT_MS` (a healthy ANALYZE of these
 * tables is 40-270 ms; the worker heartbeats a claim every `leaseSeconds / 3`, at least 10 s), declared here because `norm/` never
 * imports `metrics/`.
 */
internal const val NORM_ANALYZE_LOCK_TIMEOUT_MS = 5_000L

/**
 * Bounds the WHOLE `ANALYZE` (each of the tables could otherwise wait [NORM_ANALYZE_LOCK_TIMEOUT_MS] for its lock): applied as the
 * transaction's Exposed `queryTimeout`, i.e. rounded UP to whole seconds, never as a `SET LOCAL statement_timeout` (only the
 * `queryTimeout` may set the statement timeout - `StatementTimeoutCache.kt`, `persistence.md` "Statement timeouts").
 */
internal const val NORM_ANALYZE_STATEMENT_TIMEOUT_MS = 30_000L

/** The changes since the last ANALYZE make the statistics stale at this share, in percent, of the tables' `pg_class.reltuples` sum. */
internal const val NORM_ANALYZE_MATERIAL_PERCENT = 10L

private const val MILLIS_PER_SECOND = 1_000L
private const val PERCENT = 100L

/** The tables the Jira DERIVE's worklog fact statement joins (`norm.work_item_*`), analyzed after Jira's PROCESS. */
internal val NORM_WORK_ITEM_ANALYZED_TABLES: List<String> =
    listOf("norm.work_items", "norm.work_item_worklogs", "norm.work_item_field_intervals")

/**
 * The tables the GitLab PROFILE's event count joins and aggregates (`norm.code_*`), analyzed after GitLab's PROCESS. Deliberately NOT
 * `code_projects`/`code_identities`: both are rebuilt wholesale per PROCESS, hold hundreds of rows, and are only read by
 * `connection_id`-filtered counts and the identity page's primary-key reads, so a stale estimate costs nothing.
 */
internal val NORM_CODE_ANALYZED_TABLES: List<String> =
    listOf("norm.code_commits", "norm.code_merge_requests", "norm.code_mr_events")

/**
 * The bounds of one post-PROCESS `ANALYZE` in milliseconds ([lockMs], [statementMs]) and the share of the described rows that the
 * changes since the last ANALYZE must reach to make the statistics stale ([materialPercent], 1..100). Internal defaults, not operator
 * knobs. A test overrides them: it shortens the waits to hit a lock timeout or the statement budget.
 */
data class NormAnalyzeSettings(
    val lockMs: Long = NORM_ANALYZE_LOCK_TIMEOUT_MS,
    val statementMs: Long = NORM_ANALYZE_STATEMENT_TIMEOUT_MS,
    val materialPercent: Long = NORM_ANALYZE_MATERIAL_PERCENT,
) {
    init {
        require(lockMs > 0 && statementMs > 0 && materialPercent in 1..PERCENT) {
            "norm ANALYZE settings: lockMs and statementMs must be positive and materialPercent within 1..100"
        }
    }
}

/**
 * What [analyzeIfMaterial] did: [ran] is true only when the `ANALYZE` completed; [millis] is the helper's whole wall time; [reason] is
 * why the rule called the statistics stale (a `NORM_ANALYZE_REASON_*` code; 0 when it did not, and also when the ANALYZE then failed).
 */
data class NormAnalyzeResult(val ran: Boolean, val millis: Long, val reason: Long = 0) {
    /**
     * Records a run that happened in the job's progress (`normAnalyzed` = 1, `normAnalyzeMs`, `normAnalyzeReason`) and flushes it with
     * one heartbeat (a RECONCILE ends with PROCESS, so no later stream would): nothing at all when it did not run, the "only non-zero
     * keys" convention.
     */
    suspend fun recordIn(context: StreamContext) {
        if (!ran) return
        context.incrementProgress("normAnalyzed")
        context.incrementProgress("normAnalyzeMs", millis)
        context.incrementProgress("normAnalyzeReason", reason)
        context.heartbeat(changed = false)
    }
}

/**
 * The rows of one normalized issue in the [NORM_WORK_ITEM_ANALYZED_TABLES]: the `work_items` row, its worklogs and its field
 * intervals (the other child tables are not analyzed here).
 */
internal fun NormalizedIssue.analyzedRowCount(): Long = 1L + worklogs.size + fieldIntervals.size

/** The `normAnalyzeReason` codes: a table of the set was never analyzed (`reltuples < 0`). */
internal const val NORM_ANALYZE_REASON_NEVER_ANALYZED = 1L

/** The changes accumulated since the tables' last ANALYZE reached [NORM_ANALYZE_MATERIAL_PERCENT] of the rows they describe. */
internal const val NORM_ANALYZE_REASON_ACCUMULATED = 2L

/** The connection is absent from the `most_common_vals` of `connection_id` on the first table: the planner estimates it at one row. */
internal const val NORM_ANALYZE_REASON_CONNECTION_ABSENT = 3L

/**
 * Why a PROCESS that wrote [rowsWritten] rows (0 = nothing, so nothing to refresh) leaves the statistics of its tables stale, as a
 * `NORM_ANALYZE_REASON_*` code, or 0 when it does not. In this precedence: (a) some table was never analyzed ([neverAnalyzed]: its
 * `reltuples` is -1, the planner knows nothing); (b) the modifications since the last ANALYZE ([modSinceAnalyzeSum], the tables'
 * `n_mod_since_analyze` summed: it accumulates many small runs and a failed attempt's committed rows, which a per-run count would not)
 * are at least [materialPercent] percent of the [reltuplesSum] the statistics describe; (c) THIS connection is not among the
 * `most_common_vals` of `connection_id` ([connectionInMcv]) - a new connection under a tenth of the tables, or the eleventh equal-size
 * source. (c) costs an ANALYZE on every run that writes rows for a connection too small for the sample to catch (about 0.35 s per SYNC).
 */
internal fun normAnalyzeReason(
    neverAnalyzed: Boolean,
    modSinceAnalyzeSum: Long,
    reltuplesSum: Long,
    connectionInMcv: Boolean,
    rowsWritten: Long,
    materialPercent: Long = NORM_ANALYZE_MATERIAL_PERCENT,
): Long = when {
    rowsWritten <= 0 -> 0L
    neverAnalyzed -> NORM_ANALYZE_REASON_NEVER_ANALYZED
    modSinceAnalyzeSum > 0 && modSinceAnalyzeSum * PERCENT >= reltuplesSum * materialPercent -> NORM_ANALYZE_REASON_ACCUMULATED
    !connectionInMcv -> NORM_ANALYZE_REASON_CONNECTION_ABSENT
    else -> 0L
}

private val TABLE_NAME = Regex("[a-z_]+\\.[a-z_]+")

/**
 * `ANALYZE [tables]` when the PROCESS of [connectionId] that just ended wrote rows into [tables] and the rule says their statistics are
 * stale ([normAnalyzeReason]); the caller passes the rows it wrote ([rowsWritten], only the "> 0" guard). Runs in ITS OWN short
 * transaction, so call it at the end of the stream, never inside a PROCESS page transaction or DERIVE's: it refuses (`check`) to run
 * inside an enclosing one, which would carry its `SET LOCAL lock_timeout` and `queryTimeout` into that transaction. Why it is not
 * DERIVE's job: DERIVE reads `norm.*` in the first statements of its rebuild, long before its own `ANALYZE` of `metrics.*`, and a
 * second connection derived right behind its PROCESS planned the worklog statement from statistics of the first connection alone
 * (35.8 s against 0.5-0.8 s).
 *
 * Bounded twice, like `metrics/`'s post-commit ANALYZE: `SET LOCAL lock_timeout` ([NormAnalyzeSettings.lockMs], per table lock) and the
 * transaction's `queryTimeout` ([NormAnalyzeSettings.statementMs], whole seconds rounded up, the read and the statement). A lock
 * timeout or any other failure logs ONE WARN (the constant table names, the exception's class and its SQLSTATE when it has one, never
 * its message) and returns `ran = false`: the data is committed and the statistics fall back to autovacuum, so it never fails the job.
 * Cancellation passes through (not under `NonCancellable`: a shutdown must be able to interrupt it). [tables] are constants, never
 * user input (checked: `schema.table` of lowercase letters and underscores); the connection check reads the FIRST of them.
 */
internal suspend fun analyzeIfMaterial(
    database: R2dbcDatabase,
    tables: List<String>,
    connectionId: UInt,
    rowsWritten: Long,
    settings: NormAnalyzeSettings = NormAnalyzeSettings(),
): NormAnalyzeResult {
    require(tables.isNotEmpty() && tables.all { TABLE_NAME.matches(it) }) { "norm ANALYZE tables are fixed constants" }
    check(TransactionManager.currentOrNull() == null) { "analyzeIfMaterial opens its own transaction: never call it inside one" }
    val started = TimeSource.Monotonic.markNow()
    var reason = 0L
    val ran = catchingFailures(
        block = {
            suspendTransaction(database) {
                queryTimeout = Math.ceilDiv(settings.statementMs, MILLIS_PER_SECOND).toInt()
                exec("SET LOCAL lock_timeout = ${settings.lockMs}")
                val statistics = statisticsOf(tables, connectionId)
                reason = normAnalyzeReason(
                    statistics.neverAnalyzed, statistics.modSinceAnalyzeSum, statistics.reltuplesSum, statistics.connectionInMcv,
                    rowsWritten, settings.materialPercent,
                )
                if (reason != 0L) exec("ANALYZE ${tables.joinToString(", ")}")
                reason != 0L
            }
        },
        onFailure = { failure ->
            log.warn(
                "post-PROCESS ANALYZE of {} failed ({}); the statistics fall back to autovacuum",
                tables.joinToString(", "), failure.safeLabel(),
            )
            false
        },
    )
    return NormAnalyzeResult(ran, started.elapsedNow().inWholeMilliseconds, if (ran) reason else 0L)
}

/** The exception's class and the first SQLSTATE along its cause chain (55P03 lock timeout, 57014 statement timeout), never a message. */
private fun Throwable.safeLabel(): String {
    var current: Throwable? = this
    while (current != null) {
        (current as? R2dbcException)?.sqlState?.let { return "${this::class.java.simpleName}, SQLSTATE $it" }
        current = current.cause
    }
    return this::class.java.simpleName
}

private class TableStatistics(
    val reltuplesSum: Long,
    val neverAnalyzed: Boolean,
    val modSinceAnalyzeSum: Long,
    val connectionInMcv: Boolean,
)

/**
 * What the planner and the statistics views know about [tables] together, from the catalog: the `reltuples` sum (-1 until a table is
 * first vacuumed or analyzed), the modifications since the last ANALYZE, and whether [connectionId] is among the most common
 * `connection_id` values of the FIRST table (`most_common_vals` is `anyarray`, read through `::text::bigint[]`; no `pg_stats` row, an
 * unanalyzed or empty table, counts as absent).
 */
private suspend fun R2dbcTransaction.statisticsOf(tables: List<String>, connectionId: UInt): TableStatistics {
    val regclasses = tables.joinToString(", ") { "'$it'::regclass" }
    val (schema, table) = tables.first().split('.')
    val rows: Flow<TableStatistics?>? = exec(
        "SELECT COALESCE(sum(GREATEST(c.reltuples, 0)), 0)::bigint, COALESCE(bool_or(c.reltuples < 0), false), " +
            "COALESCE(sum(s.n_mod_since_analyze), 0)::bigint, " +
            "EXISTS (SELECT 1 FROM pg_stats p WHERE p.schemaname = '$schema' AND p.tablename = '$table' " +
            "AND p.attname = 'connection_id' AND p.most_common_vals IS NOT NULL " +
            "AND $connectionId = ANY (p.most_common_vals::text::bigint[])) " +
            "FROM pg_class c LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid WHERE c.oid IN ($regclasses)",
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        TableStatistics(
            row.get(0, Long::class.javaObjectType)!!,
            row.get(1, Boolean::class.javaObjectType)!!,
            row.get(2, Long::class.javaObjectType)!!,
            row.get(3, Boolean::class.javaObjectType)!!,
        )
    }
    return rows?.toList()?.filterNotNull()?.single() ?: error("statisticsOf: the catalog read returned no row")
}
