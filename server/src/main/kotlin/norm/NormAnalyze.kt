package ch.nokillswit.norm

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.StreamContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

// Planner statistics of the normalized tables a PROCESS has just filled (`.claude/docs/persistence.md` "Planner statistics of `norm`",
// build-times WHY 60). Autovacuum analyzes a table only after ~10 % of it changed AND its naptime (a minute) passed, so a DERIVE
// queued right behind a PROCESS plans against statistics that describe another connection's rows (or none): `connection_id = N`
// estimated at 1 row, nested loops over the whole table, 41M buffer hits. The helper below closes that gap for the one case that
// matters: a PROCESS that wrote a material share of the tables.

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

/** A PROCESS is "material" for a table set when it wrote at least this share, in percent, of the tables' `pg_class.reltuples` sum. */
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
 * The bounds of one post-PROCESS `ANALYZE` in milliseconds ([lockMs], [statementMs]) and the share of the described rows that makes a
 * PROCESS material ([materialPercent]). Internal defaults, not operator knobs. A test overrides them: it shortens the waits to hit a
 * lock timeout, and lowers the share because the shared test database's statistics describe whatever other classes of the fork left
 * behind, so a full rewrite of one clone is a tenth of them only by luck of the class order.
 */
data class NormAnalyzeSettings(
    val lockMs: Long = NORM_ANALYZE_LOCK_TIMEOUT_MS,
    val statementMs: Long = NORM_ANALYZE_STATEMENT_TIMEOUT_MS,
    val materialPercent: Long = NORM_ANALYZE_MATERIAL_PERCENT,
)

/** What [analyzeIfMaterial] did: [ran] is true only when the `ANALYZE` completed; [millis] is the helper's whole wall time. */
data class NormAnalyzeResult(val ran: Boolean, val millis: Long) {
    /**
     * Records a run that happened in the job's progress (`normAnalyzed` = 1, `normAnalyzeMs`) and flushes it with one heartbeat (a
     * RECONCILE ends with PROCESS, so no later stream would): nothing at all when it did not run, the "only non-zero keys" convention.
     */
    suspend fun recordIn(context: StreamContext) {
        if (!ran) return
        context.incrementProgress("normAnalyzed")
        context.incrementProgress("normAnalyzeMs", millis)
        context.heartbeat(changed = false)
    }
}

/**
 * The rows of one normalized issue in the [NORM_WORK_ITEM_ANALYZED_TABLES]: the `work_items` row, its worklogs and its field
 * intervals (the other child tables are not analyzed here).
 */
internal fun NormalizedIssue.analyzedRowCount(): Long = 1L + worklogs.size + fieldIntervals.size

/**
 * Whether a PROCESS that wrote [rowsWritten] rows into tables holding [reltuplesSum] (the sum of their `pg_class.reltuples`, a table
 * never vacuumed or analyzed counting 0) leaves their statistics stale: some table was never analyzed ([neverAnalyzed]: its
 * `reltuples` is -1, so the planner knows nothing), or the run wrote at least [materialPercent] percent of what the
 * statistics describe. A run that wrote nothing changes no statistics.
 */
internal fun normStatisticsStale(
    rowsWritten: Long,
    reltuplesSum: Long,
    neverAnalyzed: Boolean,
    materialPercent: Long = NORM_ANALYZE_MATERIAL_PERCENT,
): Boolean = neverAnalyzed || (rowsWritten > 0 && rowsWritten * PERCENT >= reltuplesSum * materialPercent)

private val TABLE_NAME = Regex("[a-z_]+\\.[a-z_]+")

/**
 * `ANALYZE [tables]` when the PROCESS that just ended wrote a material share of them ([normStatisticsStale]); the caller passes the
 * rows it wrote into [tables]. Runs in ITS OWN short transaction, so call it at the end of the stream, never inside a PROCESS page
 * transaction or DERIVE's. Why it is not DERIVE's job: DERIVE reads `norm.*` in the first statements of its rebuild, long before its
 * own `ANALYZE` of `metrics.*`, and a second connection derived right behind its PROCESS planned the worklog statement from statistics
 * of the first connection alone (35.8 s against 0.5-0.8 s).
 *
 * Bounded twice, like `metrics/`'s post-commit ANALYZE: `SET LOCAL lock_timeout` ([NormAnalyzeSettings.lockMs], per table lock) and the
 * transaction's `queryTimeout` ([NormAnalyzeSettings.statementMs], whole seconds rounded up, the read and the statement). A lock
 * timeout or any other failure logs ONE WARN (the constant table names and the exception's class, never its message) and returns
 * `ran = false`: the data is committed and the statistics fall back to autovacuum, so it never fails the job. Cancellation passes
 * through (not under `NonCancellable`: a shutdown must be able to interrupt it). [tables] are constants, never user input
 * (checked: `schema.table` of lowercase letters and underscores).
 */
internal suspend fun analyzeIfMaterial(
    database: R2dbcDatabase,
    tables: List<String>,
    rowsWritten: Long,
    settings: NormAnalyzeSettings = NormAnalyzeSettings(),
): NormAnalyzeResult {
    require(tables.isNotEmpty() && tables.all { TABLE_NAME.matches(it) }) { "norm ANALYZE tables are fixed constants" }
    val started = TimeSource.Monotonic.markNow()
    val ran = catchingFailures(
        block = {
            suspendTransaction(database) {
                queryTimeout = Math.ceilDiv(settings.statementMs, MILLIS_PER_SECOND).toInt()
                exec("SET LOCAL lock_timeout = ${settings.lockMs}")
                val statistics = reltuplesOf(tables)
                if (normStatisticsStale(rowsWritten, statistics.reltuplesSum, statistics.neverAnalyzed, settings.materialPercent)) {
                    exec("ANALYZE ${tables.joinToString(", ")}")
                    true
                } else {
                    false
                }
            }
        },
        onFailure = { failure ->
            log.warn(
                "post-PROCESS ANALYZE of {} failed ({}); the statistics fall back to autovacuum",
                tables.joinToString(", "), failure::class.java.simpleName,
            )
            false
        },
    )
    return NormAnalyzeResult(ran, started.elapsedNow().inWholeMilliseconds)
}

private class TableStatistics(val reltuplesSum: Long, val neverAnalyzed: Boolean)

/** The planner's row estimate of [tables] together, from the catalog (`reltuples` is -1 until a table is first vacuumed or analyzed). */
private suspend fun R2dbcTransaction.reltuplesOf(tables: List<String>): TableStatistics {
    val regclasses = tables.joinToString(", ") { "'$it'::regclass" }
    val rows: Flow<TableStatistics?>? = exec(
        "SELECT COALESCE(sum(GREATEST(reltuples, 0)), 0)::bigint, COALESCE(bool_or(reltuples < 0), false) " +
            "FROM pg_class WHERE oid IN ($regclasses)",
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        TableStatistics(row.get(0, Long::class.javaObjectType)!!, row.get(1, Boolean::class.javaObjectType)!!)
    }
    return rows?.toList()?.filterNotNull()?.single() ?: error("reltuplesOf: the catalog read returned no row")
}
