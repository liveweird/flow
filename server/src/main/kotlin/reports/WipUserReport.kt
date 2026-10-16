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
 * The four open bands (`NOT_STARTED`, `IN_PROGRESS`, `WAITING`, `UNMAPPED`) as one statement: rows are
 * `(day, status_id, stage, account_id, count)`. Set-based like the DERIVE step — the sprint and the assignee's team are LEFT JOINs
 * onto the (day, task) rows, never a correlated sub-select (`build-times.md` WHY 16; `WipPlanShapeTest` pins the plan). The two
 * `MATERIALIZED` CTEs fix the join ORDER on purpose: pair the picks' assignee intervals with their tasks' stage intervals first (a few
 * thousand rows), keep only the (day, pair) rows both cover (the held task-days), and only then resolve their team — left to itself
 * the planner resolves the team for every (day, assignee interval) pair, done tasks included, and runs ~25 times more index probes.
 * None can multiply a row: the SPRINT field is one tiled timeline, the assignee is tiled, an account is in at most one team at an instant.
 * Only numbers and validated ISO dates are interpolated; the account ids are bound.
 */
internal fun wipUserOpenBandSql(request: WipUserRequest): WipUserSql {
    require(request.connectionIds.isNotEmpty() && request.accountIds.isNotEmpty()) { "wipUserOpenBandSql needs a connection, an account" }
    val connections = request.connectionIds.joinToString(", ")
    val accounts = request.accountIds.joinToString(", ") { "?" }
    val first = LocalDate.parse(request.firstDay)
    val last = LocalDate.parse(request.lastDay)
    val team = if (request.teamId == UNASSIGNED_TEAM_ID) "IS NULL" else "= ${request.teamId}"
    val sql = """
        WITH day_range AS (SELECT day, day_end_ms FROM metrics.dim_date WHERE day >= '$first' AND day <= '$last'),
        held AS MATERIALIZED (
            SELECT ta.connection_id, ta.issue_id, ta.account_id, ta.valid_from AS a_from, ta.valid_to AS a_to,
                   s.status_id, s.stage, s.valid_from AS s_from, s.valid_to AS s_to
            FROM metrics.task_assignee ta
            JOIN metrics.dim_task t ON t.connection_id = ta.connection_id AND t.issue_id = ta.issue_id AND t.is_subtask = false
            JOIN metrics.item_stage s ON s.connection_id = ta.connection_id AND s.issue_id = ta.issue_id AND s.stage <> 'DONE'
            WHERE ta.connection_id IN ($connections) AND ta.account_id IN ($accounts)
              AND ta.valid_from <= COALESCE(s.valid_to, $OPEN_END) AND s.valid_from <= COALESCE(ta.valid_to, $OPEN_END)
        ),
        held_days AS MATERIALIZED (
            SELECT d.day, d.day_end_ms, h.connection_id, h.issue_id, h.account_id, h.status_id, h.stage
            FROM day_range d
            JOIN held h ON ${coveringAt("h", "a_from", "a_to")} AND ${coveringAt("h", "s_from", "s_to")}
        )
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

/** The statement text with `?` markers for the picked account ids, and those ids in order. */
internal class WipUserSql(val sql: String, val arguments: List<String>)

/**
 * The picked people's WIP cells for the period, inside the caller's report transaction: the four open bands ([wipUserOpenBandSql],
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
