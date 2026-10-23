package ch.nokillswit.metrics

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType

/**
 * `fact_worklog` for every worklog logged on a TASK or an EPIC, as ONE `INSERT ... SELECT` over rows this same DERIVE run has
 * already written (`.claude/docs/metrics.md` "Worklog cost facts"; build-times WHY 31) — the WIP step's idiom ([runWipStep]).
 *
 * **Which rules are read, which are restated.** The attribution that needs a kernel is READ from what the kernels already wrote,
 * never re-implemented: the as-of domain is `metrics.task_domain` ([taskDomainHistory], the `issuekey` timeline), the as-of epic
 * is `metrics.task_epic` ([taskEpicHistory]), the current domain/epic/activity type/own work category are `dim_task`'s and
 * `dim_epic`'s ([buildTaskRow], [buildEpicRow]), the owner team is `dim_domain`'s. What the statement restates is the part
 * that is arithmetic or an interval lookup, each pinned against the Kotlin kernel by `FactWorklogSqlTest`:
 * - the `[from, to)` covering lookup ([valueAt], [domainRowAt], [epicRowAt], [teamAt]) — first covering row by `seq`/`id`,
 *   the order the kernels scan in (`team_membership` cannot overlap per account: `excl_metrics_team_membership_overlap`);
 * - [isForeignWork] ([FOREIGN_WORK_SQL]);
 * - `md` (`seconds / 3600 / hoursPerDay` in IEEE doubles, then through its shortest text like Kotlin's `Double.toBigDecimal`, so
 *   the stored `numeric(8, 4)` rounds the very same decimal) and `late_ms`.
 *
 * **Not in this statement: a worklog on an item ABOVE the epic level (A31)** — such an item has no `dim_*` row, and its work
 * category is a JSON read through `fieldValueOptions`; [runWorklogStep] writes those few rows in the JVM.
 *
 * Configuration that has no table of its own after defaulting (`defaultConfig` fills what the admin never saved) is handed over as
 * bound values from the [DeriveContext]: the sprint-to-team map (numbers, inlined) and the issue-type-to-activity-type map (text,
 * bound). Everything else interpolated is a number ([connectionId], [configRevision], [hoursPerDay]).
 */
class WorklogFactSql(val sql: String, val arguments: List<Pair<IColumnType<*>, Any?>>)

/**
 * Run before the statement, in the same transaction: `md` goes through the float8's TEXT form, whose digit count depends on the
 * session's `extra_float_digits` (the driver sends 2 today, but a pooler that drops the startup parameter in front of the server
 * default 0 would round the 15-digit text instead of the shortest one: 135 s at 6 h/day stores 0.0063, the kernel 0.0062). 1 is the
 * shortest-round-trip form that `Double.toBigDecimal` also reads. `SET LOCAL` ends with the DERIVE transaction.
 */
internal const val PIN_FLOAT_TEXT_SQL = "SET LOCAL extra_float_digits = 1"

/**
 * The half-open `[from, to)` covering predicate every lookup below restates ([valueAt], [teamAt], [domainRowAt], [epicRowAt]): `from <= at`
 * and the interval open or ending AFTER `at`. NOT [coveringAt] (the WIP statements' `(from, to]`, opposite edges).
 */
internal fun coversInstant(alias: String, from: String, to: String, at: String): String =
    "$alias.$from <= $at AND ($alias.$to IS NULL OR $at < $alias.$to)"

/** [isForeignWork] for the columns `a` (author team), `s` (sprint team) and `f` (the fallback: assignee's or the owner team). */
internal fun foreignWorkSql(a: String, s: String, f: String): String =
    "CASE WHEN $a IS NULL THEN FALSE WHEN $s IS NOT NULL THEN $a <> $s ELSE $f IS NOT NULL AND $a <> $f END"

internal fun worklogFactSql(
    connectionId: UInt,
    configRevision: Long,
    hoursPerDay: Double,
    sprintTeams: Map<Long, UInt>,
    activityTypes: Map<String, String>,
): WorklogFactSql {
    val sprintTeamRows = if (sprintTeams.isEmpty()) {
        "SELECT NULL::bigint, NULL::integer WHERE FALSE"
    } else {
        "VALUES " + sprintTeams.entries.joinToString(", ") { (sprintId, teamId) -> "($sprintId::bigint, $teamId::integer)" }
    }
    val activityRows = if (activityTypes.isEmpty()) {
        "SELECT NULL::text, NULL::text WHERE FALSE"
    } else {
        "VALUES " + activityTypes.entries.joinToString(", ") { "(?::text, ?::text)" }
    }
    val arguments = activityTypes.entries.flatMap { listOf(TextColumnType() to it.key, TextColumnType() to it.value) }
    val c = connectionId
    val sql = """
        WITH sprint_team (sprint_id, team_id) AS ($sprintTeamRows),
        activity_map (issue_type, activity_type) AS ($activityRows),
        logged AS (
            SELECT wl.worklog_id, wl.issue_id, wl.author_account_id, wl.started_at, wl.created_at, wl.time_spent_seconds,
                   COALESCE(e.domain_key, t.domain_key) AS current_domain,
                   cd.domain_key AS covering_domain,
                   CASE
                       WHEN e.issue_id IS NOT NULL THEN wl.issue_id
                       WHEN t.is_subtask OR ce.id IS NULL THEN t.epic_id
                       ELSE ce.epic_id
                   END AS epic_id,
                   (e.issue_id IS NOT NULL) AS is_epic,
                   COALESCE(t.activity_type, am.activity_type, wi.issue_type) AS activity_type,
                   CASE WHEN t.work_category_source = 'OWN' THEN t.work_category END AS own_category,
                   CASE WHEN sp.value_id ~ '^[+-]?[0-9]{1,18}$' THEN sp.value_id::bigint END AS sprint_id,
                   asg.value_id AS assignee_account_id
            FROM norm.work_item_worklogs wl
            LEFT JOIN metrics.dim_task t ON t.connection_id = wl.connection_id AND t.issue_id = wl.issue_id
            LEFT JOIN metrics.dim_epic e ON e.connection_id = wl.connection_id AND e.issue_id = wl.issue_id
            LEFT JOIN norm.work_items wi ON wi.connection_id = wl.connection_id AND wi.issue_id = wl.issue_id
            LEFT JOIN activity_map am ON am.issue_type = wi.issue_type
            LEFT JOIN LATERAL (
                SELECT td.domain_key FROM metrics.task_domain td
                WHERE t.issue_id IS NOT NULL AND td.connection_id = wl.connection_id AND td.issue_id = wl.issue_id
                  AND ${coversInstant("td", "valid_from", "valid_to", "wl.started_at")}
                ORDER BY td.id LIMIT 1
            ) cd ON TRUE
            LEFT JOIN LATERAL (
                SELECT te.id, te.epic_id FROM metrics.task_epic te
                WHERE t.issue_id IS NOT NULL AND NOT t.is_subtask AND te.connection_id = wl.connection_id AND te.issue_id = wl.issue_id
                  AND ${coversInstant("te", "valid_from", "valid_to", "wl.started_at")}
                ORDER BY te.id LIMIT 1
            ) ce ON TRUE
            LEFT JOIN LATERAL (
                SELECT fi.value_id FROM norm.work_item_field_intervals fi
                WHERE fi.connection_id = wl.connection_id AND fi.issue_id = wl.issue_id AND fi.field = 'SPRINT'
                  AND ${coversInstant("fi", "from_at", "to_at", "wl.started_at")}
                ORDER BY fi.seq LIMIT 1
            ) sp ON TRUE
            LEFT JOIN LATERAL (
                SELECT fi.value_id FROM norm.work_item_field_intervals fi
                WHERE fi.connection_id = wl.connection_id AND fi.issue_id = wl.issue_id AND fi.field = 'ASSIGNEE'
                  AND ${coversInstant("fi", "from_at", "to_at", "wl.started_at")}
                ORDER BY fi.seq LIMIT 1
            ) asg ON TRUE
            WHERE wl.connection_id = $c AND (t.issue_id IS NOT NULL OR e.issue_id IS NOT NULL)
        )
        INSERT INTO metrics.fact_worklog (
            connection_id, worklog_id, issue_id, author_account_id, author_team_id, started_at, created_at, late_ms, md,
            task_domain_key, epic_id, epic_domain_key, activity_type, work_category, sprint_id_at_started,
            sprint_team_id_at_started, foreign_work, assignee_account_id_at_started, assignee_team_id_at_started, config_revision
        )
        SELECT $c, l.worklog_id, l.issue_id, l.author_account_id, author.team_id, l.started_at, l.created_at,
               CASE WHEN l.created_at IS NULL THEN NULL ELSE GREATEST(0, l.created_at - l.started_at) END,
               ${worklogMdSql("l.time_spent_seconds", hoursPerDay)},
               COALESCE(l.covering_domain, l.current_domain), l.epic_id, ep.domain_key, l.activity_type,
               COALESCE(l.own_category, ep.work_category), l.sprint_id, st.team_id,
               ${foreignWorkSql("author.team_id", "CASE WHEN l.is_epic THEN NULL ELSE st.team_id END", FALLBACK_TEAM)},
               l.assignee_account_id, assignee.team_id, $configRevision
        FROM logged l
        LEFT JOIN metrics.team_membership author ON author.account_id = l.author_account_id
            AND ${coversInstant("author", "valid_from", "valid_to", "l.started_at")}
        LEFT JOIN metrics.team_membership assignee ON assignee.account_id = l.assignee_account_id
            AND ${coversInstant("assignee", "valid_from", "valid_to", "l.started_at")}
        LEFT JOIN metrics.dim_epic ep ON ep.connection_id = $c AND ep.issue_id = l.epic_id
        LEFT JOIN metrics.dim_domain dom ON dom.connection_id = $c AND dom.domain_key = l.current_domain
        LEFT JOIN sprint_team st ON st.sprint_id = l.sprint_id
    """.trimIndent()
    return WorklogFactSql(sql, arguments)
}

/** [worklogMd] in SQL: the same doubles in the same order, then through their shortest text like `Double.toBigDecimal`. */
internal fun worklogMdSql(seconds: String, hoursPerDay: Double): String =
    "(($seconds::float8 / $SECONDS_PER_HOUR::float8) / $hoursPerDay::float8)::text::numeric"

/** An epic-logged worklog (A22) falls back to its domain's owner team, a task-logged one to its assignee's team at `started_at`. */
private const val FALLBACK_TEAM = "CASE WHEN l.is_epic THEN dom.owner_team_id ELSE assignee.team_id END"
