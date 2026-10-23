package ch.nokillswit.metrics

// The ANALYZE policy of a DERIVE run: the two timeouts of the post-commit ANALYZE and the rule that picks which ANALYZE a
// run gets (`MetricsDeriver.runDerivation` / `analyzeAfterCommit`; the statements themselves are `MetricsAnalyzer`'s).

/**
 * How long the post-commit `ANALYZE` of a re-derive waits for a table lock another session holds (a manual `VACUUM`,
 * another `ANALYZE`, DDL) before giving up with a WARN. A healthy ANALYZE takes 16-90 ms, and the worker heartbeats a
 * claim every `leaseSeconds / 3` — at least 10 s (`ingest.leaseSeconds` min 30) — so 5 s keeps the job's end, and with
 * it the slot, bounded well inside one heartbeat period. Never applied to the first derive's in-transaction ANALYZE.
 */
internal const val DEFAULT_ANALYZE_LOCK_TIMEOUT_MS = 5_000L

/**
 * Bounds the WHOLE post-commit `ANALYZE` (16 tables, so up to 16 lock waits of [DEFAULT_ANALYZE_LOCK_TIMEOUT_MS] each
 * otherwise): the statement timeout also covers the lock waits. A healthy one is 16-90 ms; the bound exists so a stuck
 * ANALYZE can never hold a worker slot for long (the claim's heartbeat is a concurrent ticker, so this is slot
 * occupancy, not lease safety). Applied as the transaction's Exposed `queryTimeout`, i.e. rounded UP to whole seconds
 * (`MetricsStore.analyzeDerivedTables` says why it cannot be a `SET LOCAL statement_timeout`); kept in milliseconds
 * like its lock-timeout sibling, an internal default rather than an operator knob.
 */
internal const val DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS = 30_000L

/** A table's statistics still describe a derive while its row count is at most this multiple of the previous derive's. */
private const val STATISTICS_GROWTH_FACTOR = 2

/**
 * Whether the planner statistics the connection's newest SUCCEEDED derive left ([previous] = its `row_counts`; null = no
 * such run) still describe a derive that is about to write [current] rows (the same keys: `tasks`, `epics`, `sprints`,
 * `worklogs`, `epicPlans`, `estimates` — each one the row count of tables in `ANALYZED_TABLES`). The test that picks the
 * in-transaction ANALYZE (they do NOT: some table the WIP/flow `INSERT … SELECT`s join would be planned at default
 * `rows=1`, build-times WHY 1) or the post-commit one (they do). Decided PER KEY, like with like, because config-dependent
 * tables move independently of the tasks: an admin's first config PUT mapping the estimate field fills `item_estimate`
 * (the backlog flow joins it) and mapping the sprint field fills the sprint tables while the task count stays flat. A key
 * passes when [current] is 0 (nothing to plan) or the previous run wrote more than 0 and [current] is at most
 * [STATISTICS_GROWTH_FACTOR] times as many; a key the previous run did not record (an older build) fails, once.
 */
internal fun statisticsDescribeRows(previous: Map<String, Int>?, current: Map<String, Int>): Boolean =
    previous != null && current.all { (key, now) ->
        now == 0 || previous[key]?.let { before -> before > 0 && now <= STATISTICS_GROWTH_FACTOR * before } == true
    }
