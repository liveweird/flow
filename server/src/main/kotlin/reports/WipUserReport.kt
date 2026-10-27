package ch.nokillswit.reports

import ch.nokillswit.metrics.coveringAt
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/*
 * Report 9 at USER level (brief 04, `.claude/docs/reports.md` "Reports 9, 10, 13"): one to eight people's WIP over time,
 * computed AT QUERY TIME because `agg_daily_wip` has no per-user scope (it would add ~one row per task-day to a table that is
 * already the biggest). One person's tasks over a period are a small, set-based read over the bridges DERIVE already wrote.
 *
 * The rule, per picked person and calendar day `d` (the day list `planSnapshotDays` builds, the same derived-coverage cut-off):
 * - **Counting** — a level-0 task (`dim_task.is_subtask = false`; the table holds only the model's tasks, A31) counts on `d` when an
 *   `item_stage` interval covers the END of `d` AND the `task_assignee` interval covering that same instant is the person's. Both
 *   use [coveringAt] — the very predicate the DERIVE WIP step uses — so a reassignment exactly at a day-end instant counts for
 *   exactly one person (`(from, to]`: the adjacent intervals never both cover it).
 * - **Team narrowing** — a person's number is read INSIDE the page's team, the rule `taskFactSlice` applies to every other
 *   USER-level report (`teamId` AND `accountId` intersect): an OPEN band counts a task-day only when its as-was D5 team is that
 *   team — the sprint's team while the sprint is not yet closed at `d`, else the assignee's team, exactly `teamTaskWipSql`'s
 *   resolution — and the DONE band (decision B, [readWipDoneBand] with the account filter) counts a task whose credit team
 *   (`credit_team_id`, the done-time team) is that team and whose assignee at done is the person. `teamId=0` is the "no team" bucket
 *   (`team_id IS NULL`, `credit_team_id IS NULL`). So Σ over every assignee + the tasks of the team nobody holds = the TEAM aggregate.
 * - **Stage/status keys** — straight from `item_stage.stage` / `status_id` (the per-domain stage overrides are already in it).
 */

/** A `valid_to` never reached, for the overlap pre-filter of an open interval (a superset test: the day join applies [coveringAt]). */
private const val OPEN_END = Long.MAX_VALUE

/** What [readWipUserCells] reads: the tasks [accountIds] hold in team [teamId] (`0` = no team), [firstDay]..[lastDay] (ISO, inclusive). */
internal data class WipUserRequest(
    val connectionIds: List<UInt>,
    val teamId: UInt,
    val accountIds: List<String>,
    val firstDay: String,
    val lastDay: String,
)

/**
 * What [readWipUserTaskDays] reads (compare-people, report 18): the tasks [accountIds] held in a working stage, whatever team they were in,
 * [firstDay]..[lastDay] (ISO, inclusive). No team field on purpose: this read has no team join and no team narrowing. With [days] (ISO
 * dates) only those days of the range are read -- Person trend (report 20) asks for the one last day of each of its windows.
 */
internal data class WipTaskDaysRequest(
    val connectionIds: List<UInt>,
    val accountIds: List<String>,
    val firstDay: String,
    val lastDay: String,
    val days: List<String>? = null,
)

/**
 * The five open bands (`NOT_STARTED`, `IN_PROGRESS`, `WAITING`, `BLOCKED`, `UNMAPPED`) as one statement: rows are
 * `(day, status_id, stage, account_id, count)`. Set-based like the DERIVE step — the sprint and the assignee's team are LEFT JOINs
 * onto the (day, task) rows, never a correlated sub-select (`build-times.md` WHY 16; `WipPlanShapeTest` pins the plan). The two
 * `MATERIALIZED` CTEs fix the join ORDER on purpose: pair the picks' assignee intervals with their tasks' stage intervals first (a few
 * thousand rows), keep only the (day, pair) rows both cover (the held task-days), and only then resolve their team — left to itself
 * the planner resolves the team for every (day, assignee interval) pair, done tasks included, and runs ~25 times more index probes.
 * None can multiply a row: the SPRINT field is one tiled timeline, the assignee is tiled, an account is in at most one team at an instant.
 * Only numbers and validated ISO dates are interpolated; the account ids are bound.
 */
internal fun wipUserOpenBandSql(request: WipUserRequest): WipUserSql {
    val ctes = wipUserHeldCtes(request.connectionIds, request.accountIds, request.firstDay, request.lastDay, stages = null, days = null)
    val team = if (request.teamId == UNASSIGNED_TEAM_ID) "IS NULL" else "= ${request.teamId}"
    val sql = """$ctes
        SELECT d.day AS day, d.status_id AS status_id, d.stage AS stage, d.account_id AS account_id, COUNT(*) AS item_count
        FROM held_days d
        LEFT JOIN norm.work_item_field_intervals fi ON fi.connection_id = d.connection_id AND fi.issue_id = d.issue_id
            AND fi.field = 'SPRINT' AND ${coveringAt("fi", "from_at", "to_at")}
        LEFT JOIN metrics.dim_sprint ds ON ds.connection_id = fi.connection_id AND ds.sprint_id = fi.value_id::bigint
            AND ds.team_id IS NOT NULL AND (ds.complete_at IS NULL OR ds.complete_at > d.day_end_ms)
        LEFT JOIN metrics.team_membership tm ON tm.account_id = d.account_id AND ${coveringAt("tm")}
        WHERE COALESCE(ds.team_id, tm.team_id) $team
        GROUP BY d.day, d.status_id, d.stage, d.account_id""".trimIndent()
    return WipUserSql(sql, request.accountIds)
}

/**
 * Compare-people's statement: one row per (day, task, person) held in a [WIP_WORKING_STAGES] stage at a day end, over the very `held`/
 * `held_days` CTEs of [wipUserOpenBandSql] but with NO team resolution (no sprint or membership join). A task has one stage and one
 * assignee at an instant, so no row repeats. `WipPlanShapeTest` pins the plan.
 */
internal fun wipUserTaskDaysSql(request: WipTaskDaysRequest): WipUserSql {
    val ctes = request.workingStageCtes()
    val sql = ctes + "\nSELECT d.day AS day, d.connection_id AS connection_id, d.issue_id AS issue_id, d.account_id AS account_id, " +
        "d.stage AS stage FROM held_days d"
    return WipUserSql(sql, request.accountIds)
}

/**
 * Compare-people's per-day figure as one statement: rows are `(day, account_id, count)` -- how many tasks each person held in a
 * [WIP_WORKING_STAGES] stage at each day end -- aggregated in Postgres over the very `held_days` rows [wipUserTaskDaysSql] lists, so
 * the response holds one row per (day, person) instead of one per (day, task, person). Set-based like the rest (no `SubPlan`,
 * `WipPlanShapeTest` pins the plan); a day nobody held anything on has no row.
 */
internal fun wipUserDayCountsSql(request: WipTaskDaysRequest): WipUserSql {
    val ctes = request.workingStageCtes()
    val sql = ctes + "\nSELECT d.day AS day, d.account_id AS account_id, COUNT(*) AS item_count FROM held_days d " +
        "GROUP BY d.day, d.account_id"
    return WipUserSql(sql, request.accountIds)
}

/**
 * The compare-people drill's per-task figure: rows are `(connection_id, issue_id, count)` -- on how many of the listed [days] each task
 * was held in a [WIP_WORKING_STAGES] stage -- over the same `held_days` rows. [days] are validated dates, interpolated as ISO literals.
 */
internal fun wipUserTaskCountsSql(request: WipTaskDaysRequest, days: List<LocalDate>): WipUserSql {
    require(days.isNotEmpty()) { "wipUserTaskCountsSql needs at least one day" }
    val ctes = request.workingStageCtes()
    val listed = days.joinToString(", ") { "'$it'" }
    val sql = ctes + "\nSELECT d.connection_id AS connection_id, d.issue_id AS issue_id, COUNT(*) AS item_count FROM held_days d " +
        "WHERE d.day IN ($listed) GROUP BY d.connection_id, d.issue_id"
    return WipUserSql(sql, request.accountIds)
}

/** The shared CTEs of the compare-people statements: [WIP_WORKING_STAGES] only, narrowed to [WipTaskDaysRequest.days] when given. */
private fun WipTaskDaysRequest.workingStageCtes(): String =
    wipUserHeldCtes(connectionIds, accountIds, firstDay, lastDay, WIP_WORKING_STAGES, days)

/**
 * The two shared CTEs of every USER-level WIP statement: `held` (the picks' assignee intervals paired with their tasks' non-DONE stage
 * intervals; only [stages], when given, are kept) and `held_days` (the (day, pair) rows both cover — the held task-days). The account
 * ids are `?` markers; only numbers, validated ISO dates and the [WIP_STAGES] constants are interpolated. [days], when given, narrows
 * `day_range` to those dates of [firstDay]..[lastDay] (none at all: no day).
 */
private fun wipUserHeldCtes(
    connectionIds: List<UInt>,
    accountIds: List<String>,
    firstDay: String,
    lastDay: String,
    stages: List<String>?,
    days: List<String>?,
): String {
    require(connectionIds.isNotEmpty() && accountIds.isNotEmpty()) { "a WIP user statement needs a connection, an account" }
    require(stages == null || stages.all { it in WIP_STAGES }) { "unknown WIP stage in $stages" }
    val connections = connectionIds.joinToString(", ")
    val accounts = accountIds.joinToString(", ") { "?" }
    val first = LocalDate.parse(firstDay)
    val last = LocalDate.parse(lastDay)
    val onlyDays = days?.let { listed ->
        if (listed.isEmpty()) " AND FALSE" else " AND day IN (${listed.joinToString(", ") { "'${LocalDate.parse(it)}'" }})"
    }.orEmpty()
    val onlyStages = stages?.let { " AND s.stage IN (${it.joinToString(", ") { stage -> "'$stage'" }})" }.orEmpty()
    return """
        WITH day_range AS (SELECT day, day_end_ms FROM metrics.dim_date WHERE day >= '$first' AND day <= '$last'$onlyDays),
        held AS MATERIALIZED (
            SELECT ta.connection_id, ta.issue_id, ta.account_id, ta.valid_from AS a_from, ta.valid_to AS a_to,
                   s.status_id, s.stage, s.valid_from AS s_from, s.valid_to AS s_to
            FROM metrics.task_assignee ta
            JOIN metrics.dim_task t ON t.connection_id = ta.connection_id AND t.issue_id = ta.issue_id AND t.is_subtask = false
            JOIN metrics.item_stage s ON s.connection_id = ta.connection_id AND s.issue_id = ta.issue_id AND s.stage <> 'DONE'$onlyStages
            WHERE ta.connection_id IN ($connections) AND ta.account_id IN ($accounts)
              AND ta.valid_from <= COALESCE(s.valid_to, $OPEN_END) AND s.valid_from <= COALESCE(ta.valid_to, $OPEN_END)
        ),
        held_days AS MATERIALIZED (
            SELECT d.day, d.day_end_ms, h.connection_id, h.issue_id, h.account_id, h.status_id, h.stage
            FROM day_range d
            JOIN held h ON ${coveringAt("h", "a_from", "a_to")} AND ${coveringAt("h", "s_from", "s_to")}
        )""".trimIndent()
}

/** The statement text with `?` markers for the picked account ids, and those ids in order. */
internal class WipUserSql(val sql: String, val arguments: List<String>)

/**
 * The picked people's WIP cells for the period, inside the caller's report transaction: the five open bands ([wipUserOpenBandSql],
 * ONE statement for all picks) plus the DONE band, which is the shared query-time read ([readWipDoneBand]) run once per pick with
 * its account filter (at most eight small statements; the helper's cells carry no account, so the pick is the loop variable).
 * No zero cells; the report zero-fills.
 */
internal suspend fun R2dbcTransaction.readWipUserCells(request: WipUserRequest): List<WipCell> {
    if (request.connectionIds.isEmpty() || request.accountIds.isEmpty()) return emptyList()
    val built = wipUserOpenBandSql(request)
    val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
    val open = exec(built.sql, arguments, StatementType.SELECT) { row ->
        WipCell(
            row.get(0, String::class.java)!!, row.get(1, String::class.java)!!, row.get(2, String::class.java)!!,
            row.get(4, Long::class.javaObjectType)!!.toInt(), row.get(3, String::class.java),
        )
    }?.toList().orEmpty().filterNotNull()
    val scope = if (request.teamId == UNASSIGNED_TEAM_ID) SnapshotScope.Unassigned else SnapshotScope.Team(request.teamId)
    val done = request.accountIds.flatMap { account ->
        readWipDoneBand(WipDoneRequest(request.connectionIds, scope, true, false, request.firstDay, request.lastDay, listOf(account)))
            .map { WipCell(it.day, it.statusId, STAGE_DONE, it.count, account) }
    }
    return open + done
}

/**
 * One task a person held in a working stage at the END of [day] (ISO). Compare-people reads these for ONE day only (the last listed
 * one: the tasks it lists and ages); the per-day figure and the drill's per-task figure are aggregated in Postgres instead
 * ([readWipUserDayCounts], [readWipUserTaskCounts]), so no object is built per task per day.
 */
internal data class WipTaskDay(val day: String, val connectionId: UInt, val issueId: Long, val accountId: String, val stage: String)

/** The stages that count as work in progress for compare-people's WIP figures (the DONE and not-started bands are not WIP). */
internal val WIP_WORKING_STAGES = listOf("IN_PROGRESS", "WAITING", "BLOCKED")

/** The [wipUserTaskDaysSql] rows for the period, inside the caller's report transaction (one day: the tasks held at its end). */
internal suspend fun R2dbcTransaction.readWipUserTaskDays(request: WipTaskDaysRequest): List<WipTaskDay> {
    if (request.connectionIds.isEmpty() || request.accountIds.isEmpty()) return emptyList()
    val built = wipUserTaskDaysSql(request)
    val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
    return exec(built.sql, arguments, StatementType.SELECT) { row ->
        WipTaskDay(
            row.get(0, String::class.java)!!, row.get(1, Int::class.javaObjectType)!!.toUInt(), row.get(2, Long::class.javaObjectType)!!,
            row.get(3, String::class.java)!!, row.get(4, String::class.java)!!,
        )
    }?.toList().orEmpty().filterNotNull()
}

/** The [wipUserDayCountsSql] rows for the period: each person's held-task count per day (days with none are absent). */
internal suspend fun R2dbcTransaction.readWipUserDayCounts(request: WipTaskDaysRequest): Map<String, Map<LocalDate, Int>> {
    if (request.connectionIds.isEmpty() || request.accountIds.isEmpty()) return emptyMap()
    val built = wipUserDayCountsSql(request)
    val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
    val rows = exec(built.sql, arguments, StatementType.SELECT) { row ->
        val day = LocalDate.parse(row.get(0, String::class.java)!!)
        Triple(row.get(1, String::class.java)!!, day, row.get(2, Long::class.javaObjectType)!!.toInt())
    }?.toList().orEmpty().filterNotNull()
    return rows.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, days) -> days.toMap() }
}

/** The [wipUserTaskCountsSql] rows: for each task, on how many of [days] the picks held it in a working stage (never held: absent). */
internal suspend fun R2dbcTransaction.readWipUserTaskCounts(
    request: WipTaskDaysRequest,
    days: List<LocalDate>,
): Map<Pair<UInt, Long>, Int> {
    if (request.connectionIds.isEmpty() || request.accountIds.isEmpty() || days.isEmpty()) return emptyMap()
    val built = wipUserTaskCountsSql(request, days)
    val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
    return exec(built.sql, arguments, StatementType.SELECT) { row ->
        (row.get(0, Int::class.javaObjectType)!!.toUInt() to row.get(1, Long::class.javaObjectType)!!) to
            row.get(2, Long::class.javaObjectType)!!.toInt()
    }?.toList().orEmpty().filterNotNull().toMap()
}
