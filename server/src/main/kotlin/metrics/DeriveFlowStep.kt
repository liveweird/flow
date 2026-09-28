package ch.nokillswit.metrics

/**
 * The flow-aggregate step's own body (v0.3.0 M3 commit 9f, `.claude/docs/measures.md` "Estimated
 * backlog (D9)" and "Throughput, period view", `.claude/docs/metrics.md` "Daily flow aggregate") —
 * `metrics.agg_daily_flow`: one sparse row per `(scope_kind, scope_id, day)`.
 *
 * **Storage (plan amendment A23).** `throughput_items`/`throughput_md` hold the day's own INCREMENT
 * (the report sums them into a curve at query time); `backlog_items`/`backlog_md` hold the
 * END-of-day snapshot. `pv_md`/`ev_md`/`ac_md` stay 0 here (part B adds their contributions). No
 * row means zeros — nothing is written for idle days.
 *
 * **Merge mechanism.** Every contribution is its own `INSERT ... SELECT ... ON CONFLICT (PK) DO
 * UPDATE SET <col> = agg_daily_flow.<col> + EXCLUDED.<col>` statement ([flowInsertHead]/
 * [flowMergeTail]): a backlog row and a throughput row for the same scope/day merge by addition,
 * whichever lands first, and a later contribution (part B's PV/EV/AC) needs no coordination with
 * this one. Raw SQL for the same reason as [runWipStep]; only NUMBERS ([connectionId]/[now]/
 * [configRevision]) are interpolated into the text.
 *
 * **Day of an event** is the configured-zone day containing its timestamp
 * (`dim_date.day_start_ms <= ts < day_end_ms`).
 */
internal suspend fun runFlowStep(metricsStore: MetricsStore, connectionId: UInt, now: Long, configRevision: Long): Int {
    metricsStore.execAggDailyFlow(backlogFlowSql(connectionId, now, configRevision))
    metricsStore.execAggDailyFlow(throughputFlowSql(connectionId, configRevision))
    return metricsStore.countAggDailyFlow(connectionId)
}

/** `INSERT INTO metrics.agg_daily_flow (<key>, <additive columns>, config_revision)` — the SELECT that follows supplies them in order. */
internal fun flowInsertHead(columns: List<String>): String =
    "INSERT INTO metrics.agg_daily_flow (connection_id, scope_kind, scope_id, day, ${columns.joinToString(", ")}, config_revision)"

/** The additive merge: each contributed column is ADDED to whatever a sibling contribution already wrote for the same key. */
internal fun flowMergeTail(columns: List<String>): String =
    "ON CONFLICT (connection_id, scope_kind, scope_id, day) DO UPDATE SET " +
        columns.joinToString(", ") { "$it = metrics.agg_daily_flow.$it + EXCLUDED.$it" }

private val BACKLOG_COLUMNS = listOf("backlog_items", "backlog_md")
private val THROUGHPUT_COLUMNS = listOf("throughput_items", "throughput_md")

/**
 * Estimated backlog (D9) at the END of every day of the WIP step's own range: a level-0 task whose
 * covering `item_stage` is NOT_STARTED, whose covering `item_estimate` is > 0 (null/0 =
 * unestimated) and that sits in no sprint that has already started (`start_at < day_end_ms` — a
 * future sprint still counts as backlog; an active or closed one takes the task out). "Covering"
 * is [runWipStep]'s end-of-day rule (`valid_from < day_end_ms AND (valid_to IS NULL OR valid_to >=
 * day_end_ms)`). DOMAIN is the as-was domain (the WIP `domainWipSql` rule), TEAM that domain's
 * resolved `owner_team_id` (as-is; else `UNOWNED`, including a task with no domain), EPIC the
 * covering `task_epic` epic. Set-based: joins on the covering predicates plus one `NOT EXISTS`.
 */
private fun backlogFlowSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now)},
    rows AS (
        SELECT d.day AS day, COALESCE(td.domain_key, t.domain_key) AS domain_key, te.epic_id AS epic_id, e.estimate_md AS estimate_md
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id AND s.stage = 'NOT_STARTED'
            AND s.valid_from < d.day_end_ms AND (s.valid_to IS NULL OR s.valid_to >= d.day_end_ms)
        JOIN metrics.item_estimate e ON e.connection_id = $connectionId AND e.issue_id = t.issue_id AND e.estimate_md > 0
            AND e.valid_from < d.day_end_ms AND (e.valid_to IS NULL OR e.valid_to >= d.day_end_ms)
        LEFT JOIN metrics.task_domain td ON td.connection_id = $connectionId AND td.issue_id = t.issue_id
            AND td.valid_from < d.day_end_ms AND (td.valid_to IS NULL OR td.valid_to >= d.day_end_ms)
        LEFT JOIN metrics.task_epic te ON te.connection_id = $connectionId AND te.issue_id = t.issue_id
            AND te.valid_from < d.day_end_ms AND (te.valid_to IS NULL OR te.valid_to >= d.day_end_ms)
        WHERE NOT EXISTS (
            SELECT 1
            FROM metrics.task_sprint ts
            JOIN metrics.dim_sprint sp ON sp.connection_id = $connectionId AND sp.sprint_id = ts.sprint_id
            WHERE ts.connection_id = $connectionId AND ts.issue_id = t.issue_id
              AND ts.valid_from < d.day_end_ms AND (ts.valid_to IS NULL OR ts.valid_to >= d.day_end_ms)
              AND sp.start_at IS NOT NULL AND sp.start_at < d.day_end_ms
        )
    ),
    team_rows AS (
        SELECT r.day AS day, COALESCE(dom.owner_team_id::text, 'UNOWNED') AS scope_id, r.estimate_md AS estimate_md
        FROM rows r
        LEFT JOIN metrics.dim_domain dom ON dom.connection_id = $connectionId AND dom.domain_key = r.domain_key
    )
    ${flowInsertHead(BACKLOG_COLUMNS)}
    SELECT $connectionId, 'TEAM', scope_id, day, COUNT(*), SUM(estimate_md), $configRevision
    FROM team_rows
    GROUP BY scope_id, day
    UNION ALL
    SELECT $connectionId, 'DOMAIN', domain_key, day, COUNT(*), SUM(estimate_md), $configRevision
    FROM rows
    WHERE domain_key IS NOT NULL
    GROUP BY domain_key, day
    UNION ALL
    SELECT $connectionId, 'EPIC', epic_id::text, day, COUNT(*), SUM(estimate_md), $configRevision
    FROM rows
    WHERE epic_id IS NOT NULL
    GROUP BY epic_id, day
    ${flowMergeTail(BACKLOG_COLUMNS)}
""".trimIndent()

/**
 * Throughput, period view: every level-0 task counted once on the day of its `done_at`, measured as
 * `estimate_at_done_md` (an unestimated task is an item worth 0 MD). TEAM = the delivery-credit
 * team (else `UNASSIGNED`), DOMAIN the task's own domain (D3's flow view), EPIC its epic.
 * Epics live in `fact_epic_delivery`; `fact_task_delivery` also carries sub-task rows, so `is_subtask = false`
 * keeps them out (D2 — they roll up into their parent).
 */
private fun throughputFlowSql(connectionId: UInt, configRevision: Long): String = """
    WITH done AS (
        SELECT d.day AS day, f.credit_team_id AS team_id, f.domain_key AS domain_key, f.epic_id AS epic_id,
               COALESCE(f.estimate_at_done_md, 0) AS md
        FROM metrics.fact_task_delivery f
        JOIN metrics.dim_date d ON d.day_start_ms <= f.done_at AND f.done_at < d.day_end_ms
        WHERE f.connection_id = $connectionId AND f.is_subtask = false AND f.done_at IS NOT NULL
    )
    ${flowInsertHead(THROUGHPUT_COLUMNS)}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNASSIGNED'), day, COUNT(*), SUM(md), $configRevision
    FROM done
    GROUP BY team_id, day
    UNION ALL
    SELECT $connectionId, 'DOMAIN', domain_key, day, COUNT(*), SUM(md), $configRevision
    FROM done
    WHERE domain_key IS NOT NULL
    GROUP BY domain_key, day
    UNION ALL
    SELECT $connectionId, 'EPIC', epic_id::text, day, COUNT(*), SUM(md), $configRevision
    FROM done
    WHERE epic_id IS NOT NULL
    GROUP BY epic_id, day
    ${flowMergeTail(THROUGHPUT_COLUMNS)}
""".trimIndent()
