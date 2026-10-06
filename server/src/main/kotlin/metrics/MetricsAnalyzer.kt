package ch.nokillswit.metrics

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The tables `DeriveWipStep.kt`/`DeriveFlowStep.kt`'s SQL reads and `MetricsDeriver` has just
 * rebuilt in its transaction ([MetricsStore.analyzeDerivedTables], in the transaction when the previous statistics do not
 * describe the connection's rows — see [statisticsDescribeRows] —, after the commit otherwise): the connection-scoped dims,
 * bridges and facts, plus the global `dim_date` (ensured in the same run, in its own transaction; every step joins it).
 * Deliberately NOT here: `agg_daily_*` (written by those steps, never read by them),
 * `fact_epic_delivery`/`item_blocked`/`fact_sprint_snapshot` (read by neither), `team_membership`
 * (config, not rebuilt by DERIVE) and `norm.*` (PROCESS already committed those rows, so autovacuum
 * analyzes them). `MetricsAnalyzeTest` checks the list against the steps' SQL sources.
 */
internal val ANALYZED_TABLES: List<String> = listOf(
    "metrics.dim_date", "metrics.dim_domain", "metrics.dim_task", "metrics.dim_epic", "metrics.dim_sprint",
    "metrics.task_epic", "metrics.task_domain", "metrics.task_assignee", "metrics.task_sprint",
    "metrics.item_estimate", "metrics.item_stage",
    "metrics.fact_task_delivery", "metrics.fact_sprint", "metrics.fact_sprint_scope", "metrics.fact_worklog", "metrics.fact_epic_plan",
)

/** The planner-statistics concern of [MetricsStore]: `ANALYZE` over [ANALYZED_TABLES], in or after the DERIVE transaction. */
internal class MetricsAnalyzer(private val database: R2dbcDatabase) {

    /**
     * `ANALYZE` over [ANALYZED_TABLES] — every table the WIP and flow `INSERT ... SELECT`s read. DERIVE rewrites them
     * inside ONE transaction, and autovacuum can neither see uncommitted rows nor run in time for the very next
     * statement, so tables whose statistics do not describe the connection's rows would be planned at default `rows=1`
     * estimates: nested loops over tens of thousands of rows (build-times WHY 1: 7.5 s, then 12.3 s, 18.9 s per derive).
     * Two call sites, one per situation ([statisticsDescribeRows] picks):
     *
     * - A derive with NO usable statistics (the connection's first, or any analyzed table that was empty last time or is
     *   now more than twice as big — tasks, epics, sprints, worklogs, epic plans, item estimates) calls it INSIDE the
     *   derive transaction (reusing the caller's transaction like [MetricsAggregateStore.execAggDailyWip]). Unlike
     *   `VACUUM`, `ANALYZE` is legal in a transaction block and counts the transaction's own inserted rows as live, so
     *   the statistics describe the rebuilt state. Its `SHARE UPDATE EXCLUSIVE`
     *   locks, which conflict with themselves, are held to the commit, so two such derives (rare: a new
     *   connection, or one after PURGE) serialize from this statement to their commit — the second's ANALYZE waits for
     *   the first's commit (no deadlock: same tables, same order). No timeouts: it must see fresh statistics.
     * - Every OTHER derive calls it AFTER the commit, as a top-level call, so it is its own short transaction, with
     *   `SET LOCAL lock_timeout` ([lockTimeoutMs], per table) and the transaction's `queryTimeout` ([statementTimeoutMs]
     *   rounded UP to whole seconds, the whole statement; it does not cover acquiring a pooled connection — and it is the
     *   `queryTimeout`, not a `SET LOCAL statement_timeout`, which Exposed overwrites before every statement) so a lock
     *   another session holds occupies the worker slot for that long at most (the timeout error reaches the caller, which
     *   WARNs; the data is already committed).
     *   Those derives plan on the previous committed state's statistics (the same connection's rows: same `connection_id`
     *   share, `issue_id` n_distinct, validity-range histograms; the row count is rescaled by the actual block count) —
     *   the planner's failure mode is ABSENT or wrong-sized statistics, not one-derive-old ones — and the post-
     *   commit ANALYZE keeps them at most one derive old. The lock is held for milliseconds, never for a derive,
     *   so concurrent derives overlap fully. It can WARN when it overlaps another connection's in-transaction
     *   ANALYZE (that derive holds the locks to its commit) and times out.
     *
     * Table names are fixed constants (no user input); the timeouts are numbers, not text, and `null` means wait as long
     * as it takes. Never make it skippable (`SKIP_LOCKED`): the in-transaction call has to see the transaction's
     * uncommitted rows, and a skipped post-commit ANALYZE would silently age the statistics. The locks also conflict
     * with VACUUM/autovacuum and DDL: autovacuum on these tables is skipped or cancelled meanwhile, and an
     * anti-wraparound vacuum would make the ANALYZE wait (rare).
     */
    suspend fun analyzeDerivedTables(lockTimeoutMs: Long? = null, statementTimeoutMs: Long? = null) = suspendTransaction(database) {
        if (lockTimeoutMs != null) exec("SET LOCAL lock_timeout = $lockTimeoutMs")
        // NOT `SET LOCAL statement_timeout`: Exposed's R2DBC executor re-applies the transaction's `queryTimeout` (whole
        // seconds, default 0) with `SET statement_timeout` before EVERY statement, which overwrites it (this call used to
        // have no statement budget at all). So the budget is the `queryTimeout`, rounded UP to a whole second (a sub-second
        // bound would become 0 = unbounded). `lock_timeout` is not touched by that reset, so its `SET LOCAL` is effective.
        if (statementTimeoutMs != null) queryTimeout = Math.ceilDiv(statementTimeoutMs, MILLIS_PER_SECOND).toInt()
        exec("ANALYZE ${ANALYZED_TABLES.joinToString(", ")}")
    }
}

private const val MILLIS_PER_SECOND = 1_000L
