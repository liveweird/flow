package ch.nokillswit.metrics

/**
 * The flow-aggregate step's own body (v0.3.0 M3 commit 9f, `.claude/docs/measures.md` "Estimated
 * backlog (D9)" and "Throughput, period view", `.claude/docs/metrics.md` "Daily flow aggregate") —
 * `metrics.agg_daily_flow`: one sparse row per `(scope_kind, scope_id, day)`.
 *
 * **Storage (plan amendment A23).** `throughput_items`/`throughput_md` hold the day's own INCREMENT
 * (the report sums them into a curve at query time); `backlog_items`/`backlog_md` hold the
 * END-of-day snapshot. `pv_md`/`ev_md`/`ac_md` (part B) are likewise the day's INCREMENT: PV the
 * budget/committed scope PLANNED on that day, EV the estimate of items DONE that day, AC the worklog
 * MD LOGGED that day. No row means zeros — nothing is written for idle days.
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
    metricsStore.execAggDailyFlow(pvTeamFlowSql(connectionId, configRevision))
    metricsStore.execAggDailyFlow(pvEpicDomainFlowSql(connectionId, now, configRevision))
    metricsStore.execAggDailyFlow(evTeamFlowSql(connectionId, configRevision))
    metricsStore.execAggDailyFlow(evEpicDomainFlowSql(connectionId, configRevision))
    metricsStore.execAggDailyFlow(acFlowSql(connectionId, configRevision))
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
private val PV_COLUMNS = listOf("pv_md")
private val EV_COLUMNS = listOf("ev_md")
private val AC_COLUMNS = listOf("ac_md")

/**
 * Estimated backlog (D9) at the END of every day of the WIP step's own range: a level-0 task whose
 * covering `item_stage` is NOT_STARTED, whose covering `item_estimate` is > 0 (null/0 =
 * unestimated) and that sits in no sprint that has already started (`start_at < day_end_ms` — a
 * future sprint still counts as backlog; an active or closed one takes the task out). "Covering"
 * is the WIP step's end-of-day rule, the one shared [coveringAt] predicate (`valid_from <
 * day_end_ms AND (valid_to IS NULL OR valid_to >= day_end_ms)`). DOMAIN is the as-was domain (the WIP `domainWipSql`
 * rule), TEAM that domain's resolved `owner_team_id` (as-is; else `UNOWNED`, including a task with no domain), EPIC the
 * covering `task_epic` epic. Set-based: joins on the covering predicates plus one `NOT EXISTS`.
 */
private fun backlogFlowSql(connectionId: UInt, now: Long, configRevision: Long): String = """
    WITH ${dayRangeCte(connectionId, now)},
    rows AS (
        SELECT d.day AS day, COALESCE(td.domain_key, t.domain_key) AS domain_key, te.epic_id AS epic_id, e.estimate_md AS estimate_md
        FROM day_range d
        JOIN metrics.dim_task t ON t.connection_id = $connectionId AND t.is_subtask = false
        JOIN metrics.item_stage s ON s.connection_id = $connectionId AND s.issue_id = t.issue_id AND s.stage = 'NOT_STARTED'
            AND ${coveringAt("s")}
        JOIN metrics.item_estimate e ON e.connection_id = $connectionId AND e.issue_id = t.issue_id AND e.estimate_md > 0
            AND ${coveringAt("e")}
        LEFT JOIN metrics.task_domain td ON td.connection_id = $connectionId AND td.issue_id = t.issue_id
            AND ${coveringAt("td")}
        LEFT JOIN metrics.task_epic te ON te.connection_id = $connectionId AND te.issue_id = t.issue_id
            AND ${coveringAt("te")}
        WHERE NOT EXISTS (
            SELECT 1
            FROM metrics.task_sprint ts
            JOIN metrics.dim_sprint sp ON sp.connection_id = $connectionId AND sp.sprint_id = ts.sprint_id
            WHERE ts.connection_id = $connectionId AND ts.issue_id = t.issue_id
              AND ${coveringAt("ts")}
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

/**
 * PV, TEAM (A20): a started sprint's `fact_sprint.committed_md` lands on the day containing
 * `dim_sprint.start_at`, for a sprint mapped to a team (`team_id`) with a start. A zero total writes
 * no row (sparse).
 */
private fun pvTeamFlowSql(connectionId: UInt, configRevision: Long): String = """
    ${flowInsertHead(PV_COLUMNS)}
    SELECT $connectionId, 'TEAM', sp.team_id::text, d.day, SUM(fs.committed_md), $configRevision
    FROM metrics.fact_sprint fs
    JOIN metrics.dim_sprint sp ON sp.connection_id = $connectionId AND sp.sprint_id = fs.sprint_id
        AND sp.team_id IS NOT NULL AND sp.start_at IS NOT NULL
    JOIN metrics.dim_date d ON d.day_start_ms <= sp.start_at AND sp.start_at < d.day_end_ms
    WHERE fs.connection_id = $connectionId
    GROUP BY sp.team_id, d.day
    HAVING SUM(fs.committed_md) <> 0
    ${flowMergeTail(PV_COLUMNS)}
""".trimIndent()

/**
 * PV, EPIC and DOMAIN: every epic's CURRENT baseline (`superseded_at IS NULL`, start/due/budget set)
 * spread over the WORKING days of `[start, due]` — [pvCurve]'s rule exactly: start and
 * due are read as UTC dates, the day key is that ISO date, and a working day is the `dim_date` row
 * with that key and `is_working_day`. Cumulative-rounded so the day increments SUM to the budget
 * exactly at scale 2 (`round(budget*i/n) - round(budget*(i-1)/n)`, never `budget/n` rounded per day);
 * no working day at all → no rows. **Horizon (A23):** only a baseline whose start AND due both lie in
 * the PV horizon ([pvHorizonMs], ±[PV_HORIZON_YEARS] years of `now`) gets a curve —
 * outside it the epic is treated like "no dates", never clamped, so Σ PV = budget holds for every
 * epic that has a curve. EPIC = the epic's issue id, DOMAIN = `dim_epic.domain_key` (the
 * epic's own current domain; none → no DOMAIN row), summed from the SAME rounded increments so it
 * equals the sum of its EPIC rows exactly.
 */
private fun pvEpicDomainFlowSql(connectionId: UInt, now: Long, configRevision: Long): String {
    val (horizonFrom, horizonToExclusive) = pvHorizonMs(now)
    return """
    WITH cur AS (
        SELECT p.issue_id AS issue_id, p.budget_md AS budget_md,
               to_char(to_timestamp(p.start_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS start_day,
               to_char(to_timestamp(p.due_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS due_day
        FROM metrics.fact_epic_plan p
        WHERE p.connection_id = $connectionId AND p.superseded_at IS NULL
          AND p.start_at IS NOT NULL AND p.due_at IS NOT NULL AND p.budget_md IS NOT NULL
          AND p.start_at >= $horizonFrom AND p.start_at < $horizonToExclusive
          AND p.due_at >= $horizonFrom AND p.due_at < $horizonToExclusive
    ),
    working AS (
        SELECT c.issue_id AS issue_id, c.budget_md AS budget_md, d.day AS day,
               ROW_NUMBER() OVER (PARTITION BY c.issue_id ORDER BY d.day) AS rn,
               COUNT(*) OVER (PARTITION BY c.issue_id) AS n
        FROM cur c
        JOIN metrics.dim_date d ON d.is_working_day AND d.day BETWEEN c.start_day AND c.due_day
    ),
    inc AS (
        SELECT issue_id, day, ROUND(budget_md * rn / n, 2) - ROUND(budget_md * (rn - 1) / n, 2) AS pv
        FROM working
    )
    ${flowInsertHead(PV_COLUMNS)}
    SELECT $connectionId, 'EPIC', issue_id::text, day, pv, $configRevision
    FROM inc
    WHERE pv <> 0
    UNION ALL
    SELECT $connectionId, 'DOMAIN', e.domain_key, i.day, SUM(i.pv), $configRevision
    FROM inc i
    JOIN metrics.dim_epic e ON e.connection_id = $connectionId AND e.issue_id = i.issue_id AND e.domain_key IS NOT NULL
    GROUP BY e.domain_key, i.day
    HAVING SUM(i.pv) <> 0
    ${flowMergeTail(PV_COLUMNS)}
""".trimIndent()
}

/**
 * EV, TEAM (A20): each `done_in_sprint` scope row of a sprint mapped to a team counts its
 * `estimate_at_done_md` (null = 0) on the day of its task's `fact_task_delivery.done_at`. Per team
 * this totals `fact_sprint.delivered_md`.
 */
private fun evTeamFlowSql(connectionId: UInt, configRevision: Long): String = """
    ${flowInsertHead(EV_COLUMNS)}
    SELECT $connectionId, 'TEAM', sp.team_id::text, d.day, SUM(COALESCE(sc.estimate_at_done_md, 0)), $configRevision
    FROM metrics.fact_sprint_scope sc
    JOIN metrics.dim_sprint sp ON sp.connection_id = $connectionId AND sp.sprint_id = sc.sprint_id AND sp.team_id IS NOT NULL
    JOIN metrics.fact_task_delivery f ON f.connection_id = $connectionId AND f.issue_id = sc.issue_id AND f.done_at IS NOT NULL
    JOIN metrics.dim_date d ON d.day_start_ms <= f.done_at AND f.done_at < d.day_end_ms
    WHERE sc.connection_id = $connectionId AND sc.done_in_sprint
    GROUP BY sp.team_id, d.day
    HAVING SUM(COALESCE(sc.estimate_at_done_md, 0)) <> 0
    ${flowMergeTail(EV_COLUMNS)}
""".trimIndent()

/**
 * EV, EPIC and DOMAIN: only epic-attributed work counts (A23 — an epic-less task has no PV to
 * compare against): each level-0 done task with an epic contributes `COALESCE(estimate_at_done_md,
 * 0)` on its `done_at` day, to its epic at done and that epic's domain (`epic_domain_key`; none →
 * no DOMAIN row).
 */
private fun evEpicDomainFlowSql(connectionId: UInt, configRevision: Long): String = """
    WITH done AS (
        SELECT d.day AS day, f.epic_id AS epic_id, f.epic_domain_key AS epic_domain_key,
               COALESCE(f.estimate_at_done_md, 0) AS md
        FROM metrics.fact_task_delivery f
        JOIN metrics.dim_date d ON d.day_start_ms <= f.done_at AND f.done_at < d.day_end_ms
        WHERE f.connection_id = $connectionId AND f.is_subtask = false AND f.done_at IS NOT NULL AND f.epic_id IS NOT NULL
    )
    ${flowInsertHead(EV_COLUMNS)}
    SELECT $connectionId, 'EPIC', epic_id::text, day, SUM(md), $configRevision
    FROM done
    GROUP BY epic_id, day
    HAVING SUM(md) <> 0
    UNION ALL
    SELECT $connectionId, 'DOMAIN', epic_domain_key, day, SUM(md), $configRevision
    FROM done
    WHERE epic_domain_key IS NOT NULL
    GROUP BY epic_domain_key, day
    HAVING SUM(md) <> 0
    ${flowMergeTail(EV_COLUMNS)}
""".trimIndent()

/**
 * AC: `fact_worklog.md` on the day of `started_at`. TEAM = the author's team (else `UNASSIGNED`, never
 * dropped — invariant 6), so the TEAM total equals the whole `fact_worklog` total. EPIC = the
 * worklog's `epic_id` (an epic-logged worklog carries the epic's own id), DOMAIN = its
 * `epic_domain_key`; worklogs with no epic are left out of both (A23 — epic-attributed cost only).
 */
private fun acFlowSql(connectionId: UInt, configRevision: Long): String = """
    WITH logged AS (
        SELECT d.day AS day, w.author_team_id AS team_id, w.epic_id AS epic_id, w.epic_domain_key AS epic_domain_key, w.md AS md
        FROM metrics.fact_worklog w
        JOIN metrics.dim_date d ON d.day_start_ms <= w.started_at AND w.started_at < d.day_end_ms
        WHERE w.connection_id = $connectionId
    )
    ${flowInsertHead(AC_COLUMNS)}
    SELECT $connectionId, 'TEAM', COALESCE(team_id::text, 'UNASSIGNED'), day, SUM(md), $configRevision
    FROM logged
    GROUP BY team_id, day
    HAVING SUM(md) <> 0
    UNION ALL
    SELECT $connectionId, 'EPIC', epic_id::text, day, SUM(md), $configRevision
    FROM logged
    WHERE epic_id IS NOT NULL
    GROUP BY epic_id, day
    HAVING SUM(md) <> 0
    UNION ALL
    SELECT $connectionId, 'DOMAIN', epic_domain_key, day, SUM(md), $configRevision
    FROM logged
    WHERE epic_id IS NOT NULL AND epic_domain_key IS NOT NULL
    GROUP BY epic_domain_key, day
    HAVING SUM(md) <> 0
    ${flowMergeTail(AC_COLUMNS)}
""".trimIndent()
