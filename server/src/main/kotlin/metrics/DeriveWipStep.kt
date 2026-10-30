package ch.nokillswit.metrics

/**
 * The WIP step's own body (v0.3.0 M3 commit 9f, `.claude/docs/domain-model.md` "Reports" report 9,
 * `.claude/docs/measures.md` "Report 9 — WIP", `.claude/docs/metrics.md` "Daily WIP aggregate") —
 * `metrics.agg_daily_wip`: one row per `(scope_kind, scope_id, day, item_kind, status_id, stage)`,
 * `item_count` the number of items whose `metrics.item_stage` interval covers the END of that day
 * (`valid_from < day_end_ms AND (valid_to IS NULL OR valid_to >= day_end_ms)`), for every calendar
 * day from the connection's earliest `created_at` day (read straight off `MIN(item_stage.valid_from)`
 * — the first status interval always starts at `created_at`) OR its `backfill_from` day, whichever is LATER (decision C,
 * `.claude/docs/metrics.md` "Daily WIP aggregate": before the backfill window the history is incomplete by construction —
 * an item finished long before and not updated since was never fetched), up to and including the day of the
 * run's own [now]. **No `stage = DONE` row is written** (the storage-optimization review's decision B): report 9
 * computes its DONE band at query time, attributed as at the item's done time (`reports/WipDoneBand.kt`, domain-model A34).
 * Only non-zero counts get a row, which a plain `GROUP BY` already guarantees (a
 * group only exists when at least one item landed in it).
 *
 * **Raw SQL, not the batched Kotlin loop every other step uses** (plan §5): a per-item-per-day WIP
 * join over `item_stage`/`dim_task`/`dim_epic`/`task_domain`/`task_epic`/`task_assignee`/
 * `dim_sprint`/`dim_domain`/`team_membership` — ALL freshly rebuilt earlier in THIS SAME run — is
 * exactly the shape the database, not the JVM, should compute for ~24k issues x years of days.
 * [MetricsStore.execAggDailyWip] is the `exec("SET LOCAL ...")` idiom `MetricsStore.purgeAll`
 * already uses, reused here for a plain `INSERT ... SELECT` body instead. Only NUMBERS
 * ([connectionId]/[now]/[configRevision]) are interpolated into the SQL text — never user-supplied
 * strings.
 *
 * Every scope is read AS-WAS at that day's own end instant (`.claude/docs/domain-model.md`'s
 * "Stance" — everything effective-dated reads as-was):
 * - **TEAM/TASK** ([teamTaskWipSql]) — the sprint in the task's `norm` SPRINT field interval
 *   (its board's mapped team, only while that sprint is not yet closed at the instant), else the
 *   assignee's team (`task_assignee` + `team_membership`), else `UNASSIGNED`.
 * - **TEAM/EPIC** ([teamEpicWipSql]) — the resolved owner team (`dim_domain.owner_team_id`, as-is) of the
 *   epic's domain at the day's end (the covering `task_domain` row of the epic, else its current domain, A49),
 *   else `UNOWNED`.
 * - **DOMAIN/TASK and DOMAIN/EPIC** ([domainWipSql]) — a task reads the covering `task_domain` row,
 *   else its current domain (`dim_task.domain_key`); a `LEFT JOIN` naturally reads NULL for BOTH
 *   "no covering row" and "a covering row whose own value is null", so `COALESCE` folds either case
 *   onto the current value alike. An epic reads its own covering `task_domain` row the same way (A49,
 *   else `dim_epic.domain_key`): a move exactly at a day's end leaves that day in the OLD domain.
 * - **EPIC/TASK only** ([epicWipSql]) — the covering `task_epic` row's epic id; no row when it (or
 *   its absence) resolves to no epic — epics carry no epic scope of their own.
 */
internal suspend fun runWipStep(metricsStore: MetricsStore, connectionId: UInt, now: Long, configRevision: Long): Int {
    wipStatements(connectionId, now, configRevision).forEach { metricsStore.execAggDailyWip(it) }
    return metricsStore.countAggDailyWip(connectionId)
}

/** The step's four `INSERT … SELECT` statements, in run order (a test strips their DONE filter to prove it is the only change). */
internal fun wipStatements(connectionId: UInt, now: Long, configRevision: Long): List<String> = listOf(
    teamTaskWipSql(connectionId, now, configRevision),
    teamEpicWipSql(connectionId, now, configRevision),
    domainWipSql(connectionId, now, configRevision),
    epicWipSql(connectionId, now, configRevision),
)

/**
 * Every calendar day from the connection's earliest `created_at` day through the day of [now] (inclusive). With
 * [fromBackfill] (the WIP statements) the first day is also floored at the connection's `backfill_from` (an ISO date,
 * compared with the ISO `dim_date.day` text — the same lexicographic order the report's day filters use): a
 * connection with no readable `backfill_from` keeps the creation-based start. The backlog statements keep the
 * creation-based start (decision C covers `agg_daily_wip` only).
 */
internal fun dayRangeCte(connectionId: UInt, now: Long, fromBackfill: Boolean = false): String = """
    day_range AS (
        SELECT day, day_end_ms
        FROM metrics.dim_date
        WHERE day_end_ms > (SELECT MIN(valid_from) FROM metrics.item_stage WHERE connection_id = $connectionId)
          AND day_start_ms <= $now${if (fromBackfill) backfillFloor(connectionId) else ""}
    )
""".trimIndent()

/** The extra `day_range` predicate of [dayRangeCte]'s `fromBackfill`: `day` no earlier than the connection's `backfill_from`. */
private fun backfillFloor(connectionId: UInt): String =
    "\n          AND day >= COALESCE((SELECT backfill_from FROM public.source_connections WHERE id = $connectionId), '')"

/**
 * The covering predicate every WIP (and backlog) read shares: the interval `[alias.from, alias.to)` covers the instant
 * [at] — by default the END instant of the current `day_range` row (`d.day_end_ms`) — started before it, and still open or
 * ended at/after it (`(from, to]`). `from`/`to` default to the `metrics.*` effective-dating columns; the
 * `norm.work_item_field_intervals` read passes `from_at`/`to_at`; the report 9 DONE band (`reports/WipDoneBand.kt`) passes
 * a task's `done_at` for the domain-at-done read.
 */
internal fun coveringAt(alias: String, from: String = "valid_from", to: String = "valid_to", at: String = "d.day_end_ms"): String =
    "$alias.$from < $at AND ($alias.$to IS NULL OR $alias.$to >= $at)"

/**
 * The DONE band is not stored (decision B): a finished item would be counted again on every later day, forever. Report 9
 * computes it at query time (`reports/WipDoneBand.kt`), so every statement here reads the other stages only.
 */
internal const val NOT_DONE = "s.stage <> 'DONE'"

/** `INSERT INTO metrics.agg_daily_wip (…)` — mirrors `flowInsertHead`; the SELECT after it supplies the columns in order. */
private fun wipInsertHead(): String =
    "INSERT INTO metrics.agg_daily_wip " +
        "(connection_id, scope_kind, scope_id, day, item_kind, status_id, stage, item_count, config_revision)"

/**
 * Set-based, never a per-row sub-select: `sprint_team` joins the SPRINT field intervals to their mapped sprint ONCE and
 * `assignee_team` joins the assignee intervals to the memberships ONCE, then both are `LEFT JOIN`ed per (day, task).
 * The earlier correlated sub-selects ran ~4.8 M times at scale 20 and read `dim_sprint` by index or by seq scan at a
 * near-equal cost, so a DERIVE took ~32 s or ~53 s depending on the plan (`build-times.md` WHY 16); `WipPlanShapeTest`
 * pins the shape. Neither join can multiply a row: the SPRINT field is one tiled timeline (the LAST id of a carry-over
 * set, `JiraNormalizer`), the assignee is tiled too, and an account is in at most one team at any instant. That
 * relies on [coveringAt]'s strict lower bound (`(from, to]`): adjacent intervals never both cover one day-end instant.
 */
internal fun teamTaskWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now, fromBackfill = true)},
    sprint_team AS (
        SELECT fi.issue_id, fi.from_at, fi.to_at, ds.team_id, ds.complete_at
        FROM norm.work_item_field_intervals fi
        JOIN metrics.dim_sprint ds ON ds.connection_id = $connectionId AND ds.sprint_id = fi.value_id::bigint
        WHERE fi.connection_id = $connectionId AND fi.field = 'SPRINT' AND ds.team_id IS NOT NULL
    ),
    assignee_team AS (
        SELECT ta.issue_id, ta.valid_from, ta.valid_to,
               tm.valid_from AS member_from, tm.valid_to AS member_to, tm.team_id
        FROM metrics.task_assignee ta
        JOIN metrics.team_membership tm ON tm.account_id = ta.account_id
        WHERE ta.connection_id = $connectionId
    ),
    rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, COALESCE(st.team_id, ast.team_id) AS team_id
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND $NOT_DONE AND ${coveringAt("s")}
        LEFT JOIN sprint_team st ON st.issue_id = t.issue_id
            AND ${coveringAt("st", "from_at", "to_at")}
            AND (st.complete_at IS NULL OR st.complete_at > d.day_end_ms)
        LEFT JOIN assignee_team ast ON ast.issue_id = t.issue_id
            AND ${coveringAt("ast")}
            AND ${coveringAt("ast", "member_from", "member_to")}
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNASSIGNED'), day, 'TASK', status_id, stage, COUNT(*), $configRevision
    FROM rows
    GROUP BY team_id, day, status_id, stage
""".trimIndent()

/** The epic's domain at the END of the day (A49): its covering `task_domain` row, else the current `dim_epic.domain_key`. */
private const val EPIC_DOMAIN_AT_DAY_END = "COALESCE(ed.domain_key, e.domain_key)"

private fun teamEpicWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now, fromBackfill = true)},
    rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, dom.owner_team_id AS team_id
        FROM day_range d
        JOIN metrics.dim_epic e ON e.connection_id = $connectionId
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = e.issue_id
            AND $NOT_DONE AND ${coveringAt("s")}
        LEFT JOIN metrics.task_domain ed ON ed.connection_id = $connectionId AND ed.issue_id = e.issue_id
            AND ${coveringAt("ed")}
        LEFT JOIN metrics.dim_domain dom ON dom.connection_id = $connectionId AND dom.domain_key = $EPIC_DOMAIN_AT_DAY_END
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNOWNED'), day, 'EPIC', status_id, stage, COUNT(*), $configRevision
    FROM rows
    GROUP BY team_id, day, status_id, stage
""".trimIndent()

private fun domainWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now, fromBackfill = true)},
    task_rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage,
               COALESCE(td.domain_key, t.domain_key) AS domain_key
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND $NOT_DONE AND ${coveringAt("s")}
        LEFT JOIN metrics.task_domain td ON td.connection_id = $connectionId AND td.issue_id = t.issue_id
            AND ${coveringAt("td")}
    ),
    epic_rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, $EPIC_DOMAIN_AT_DAY_END AS domain_key
        FROM day_range d
        JOIN metrics.dim_epic e ON e.connection_id = $connectionId
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = e.issue_id
            AND $NOT_DONE AND ${coveringAt("s")}
        LEFT JOIN metrics.task_domain ed ON ed.connection_id = $connectionId AND ed.issue_id = e.issue_id
            AND ${coveringAt("ed")}
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'DOMAIN', domain_key, day, 'TASK', status_id, stage, COUNT(*), $configRevision
    FROM task_rows
    WHERE domain_key IS NOT NULL
    GROUP BY domain_key, day, status_id, stage
    UNION ALL
    SELECT $connectionId, 'DOMAIN', domain_key, day, 'EPIC', status_id, stage, COUNT(*), $configRevision
    FROM epic_rows
    WHERE domain_key IS NOT NULL
    GROUP BY domain_key, day, status_id, stage
""".trimIndent()

private fun epicWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now, fromBackfill = true)},
    rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, te.epic_id AS epic_id
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND $NOT_DONE AND ${coveringAt("s")}
        LEFT JOIN metrics.task_epic te ON te.connection_id = $connectionId AND te.issue_id = t.issue_id
            AND ${coveringAt("te")}
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'EPIC', epic_id::text, day, 'TASK', status_id, stage, COUNT(*), $configRevision
    FROM rows
    WHERE epic_id IS NOT NULL
    GROUP BY epic_id, day, status_id, stage
""".trimIndent()
