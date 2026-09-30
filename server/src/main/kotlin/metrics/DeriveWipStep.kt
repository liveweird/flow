package ch.nokillswit.metrics

/**
 * The WIP step's own body (v0.3.0 M3 commit 9f, `.claude/docs/domain-model.md` "Reports" report 9,
 * `.claude/docs/measures.md` "Report 9 — WIP", `.claude/docs/metrics.md` "Daily WIP aggregate") —
 * `metrics.agg_daily_wip`: one row per `(scope_kind, scope_id, day, item_kind, status_id, stage)`,
 * `item_count` the number of items whose `metrics.item_stage` interval covers the END of that day
 * (`valid_from < day_end_ms AND (valid_to IS NULL OR valid_to >= day_end_ms)`), for every calendar
 * day from the connection's earliest `created_at` day (read straight off `MIN(item_stage.valid_from)`
 * — the first status interval always starts at `created_at`) up to and including the day of the
 * run's own [now]. Only non-zero counts get a row, which a plain `GROUP BY` already guarantees (a
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
 * - **TEAM/EPIC** ([teamEpicWipSql]) — the epic's domain's resolved owner team
 *   (`dim_domain.owner_team_id`, as-is), else `UNOWNED`.
 * - **DOMAIN/TASK and DOMAIN/EPIC** ([domainWipSql]) — a task reads the covering `task_domain` row,
 *   else its current domain (`dim_task.domain_key`); a `LEFT JOIN` naturally reads NULL for BOTH
 *   "no covering row" and "a covering row whose own value is null", so `COALESCE` folds either case
 *   onto the current value alike. An epic reads its own (current) domain.
 * - **EPIC/TASK only** ([epicWipSql]) — the covering `task_epic` row's epic id; no row when it (or
 *   its absence) resolves to no epic — epics carry no epic scope of their own.
 */
internal suspend fun runWipStep(metricsStore: MetricsStore, connectionId: UInt, now: Long, configRevision: Long): Int {
    metricsStore.execAggDailyWip(teamTaskWipSql(connectionId, now, configRevision))
    metricsStore.execAggDailyWip(teamEpicWipSql(connectionId, now, configRevision))
    metricsStore.execAggDailyWip(domainWipSql(connectionId, now, configRevision))
    metricsStore.execAggDailyWip(epicWipSql(connectionId, now, configRevision))
    return metricsStore.countAggDailyWip(connectionId)
}

/** Every calendar day from the connection's earliest `created_at` day through the day of [now] (inclusive). */
internal fun dayRangeCte(connectionId: UInt, now: Long): String = """
    day_range AS (
        SELECT day, day_end_ms
        FROM metrics.dim_date
        WHERE day_end_ms > (SELECT MIN(valid_from) FROM metrics.item_stage WHERE connection_id = $connectionId)
          AND day_start_ms <= $now
    )
""".trimIndent()

/**
 * The end-of-day covering predicate every WIP (and backlog) read shares: the interval `[alias.from,
 * alias.to)` covers the END instant of the current `day_range` row (`d.day_end_ms`) — started before
 * it, and still open or ended at/after it. `from`/`to` default to the `metrics.*` effective-dating
 * columns; the `norm.work_item_field_intervals` read passes `from_at`/`to_at`.
 */
internal fun coveringAt(alias: String, from: String = "valid_from", to: String = "valid_to"): String =
    "$alias.$from < d.day_end_ms AND ($alias.$to IS NULL OR $alias.$to >= d.day_end_ms)"

/** `INSERT INTO metrics.agg_daily_wip (…)` — mirrors `flowInsertHead`; the SELECT after it supplies the columns in order. */
private fun wipInsertHead(): String =
    "INSERT INTO metrics.agg_daily_wip " +
        "(connection_id, scope_kind, scope_id, day, item_kind, status_id, stage, item_count, config_revision)"

private fun teamTaskWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now)},
    rows AS (
        SELECT
            d.day AS day, s.status_id AS status_id, s.stage AS stage,
            COALESCE(
                (SELECT ds.team_id
                 FROM norm.work_item_field_intervals fi
                 JOIN metrics.dim_sprint ds ON ds.connection_id = $connectionId AND ds.sprint_id = fi.value_id::bigint
                 WHERE fi.connection_id = $connectionId AND fi.issue_id = t.issue_id AND fi.field = 'SPRINT'
                   AND ${coveringAt("fi", "from_at", "to_at")}
                   AND ds.team_id IS NOT NULL AND (ds.complete_at IS NULL OR ds.complete_at > d.day_end_ms)
                 LIMIT 1),
                (SELECT tm.team_id
                 FROM metrics.task_assignee ta
                 JOIN metrics.team_membership tm ON tm.account_id = ta.account_id
                     AND ${coveringAt("tm")}
                 WHERE ta.connection_id = $connectionId AND ta.issue_id = t.issue_id
                   AND ${coveringAt("ta")}
                 LIMIT 1)
            ) AS team_id
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND ${coveringAt("s")}
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNASSIGNED'), day, 'TASK', status_id, stage, COUNT(*), $configRevision
    FROM rows
    GROUP BY team_id, day, status_id, stage
""".trimIndent()

private fun teamEpicWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now)},
    rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, dom.owner_team_id AS team_id
        FROM day_range d
        JOIN metrics.dim_epic e ON e.connection_id = $connectionId
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = e.issue_id
            AND ${coveringAt("s")}
        LEFT JOIN metrics.dim_domain dom ON dom.connection_id = $connectionId AND dom.domain_key = e.domain_key
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNOWNED'), day, 'EPIC', status_id, stage, COUNT(*), $configRevision
    FROM rows
    GROUP BY team_id, day, status_id, stage
""".trimIndent()

private fun domainWipSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now)},
    task_rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage,
               COALESCE(td.domain_key, t.domain_key) AS domain_key
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND ${coveringAt("s")}
        LEFT JOIN metrics.task_domain td ON td.connection_id = $connectionId AND td.issue_id = t.issue_id
            AND ${coveringAt("td")}
    ),
    epic_rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, e.domain_key AS domain_key
        FROM day_range d
        JOIN metrics.dim_epic e ON e.connection_id = $connectionId
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = e.issue_id
            AND ${coveringAt("s")}
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
    WITH ${dayRangeCte(connectionId, now)},
    rows AS (
        SELECT d.day AS day, s.status_id AS status_id, s.stage AS stage, te.epic_id AS epic_id
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id
            AND ${coveringAt("s")}
        LEFT JOIN metrics.task_epic te ON te.connection_id = $connectionId AND te.issue_id = t.issue_id
            AND ${coveringAt("te")}
    )
    ${wipInsertHead()}
    SELECT $connectionId, 'EPIC', epic_id::text, day, 'TASK', status_id, stage, COUNT(*), $configRevision
    FROM rows
    WHERE epic_id IS NOT NULL
    GROUP BY epic_id, day, status_id, stage
""".trimIndent()
