package ch.nokillswit.reports

import ch.nokillswit.metrics.coveringAt
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/*
 * Report 9's DONE band, computed AT QUERY TIME (brief 03 decision B, `.claude/docs/measures.md` "Report 9 — WIP",
 * `.claude/docs/domain-model.md` A34): DERIVE writes no `stage = DONE` row into `agg_daily_wip` (a finished item would be
 * counted again on every later day, forever — 60 % of the table), so the band is read off `metrics.item_stage` itself, in
 * ONE set-based statement per request, never a per-day loop and never a correlated sub-select per (day, item)
 * (`build-times.md` WHY 16; `WipPlanShapeTest` pins the plan).
 *
 * The rule, frozen from the done time on:
 * - **Counting** — an item is in the band on day `d` when a DONE-stage `item_stage` interval covers the END of `d`
 *   ([coveringAt], the very predicate the WIP step uses), so a reopened item leaves the band while it is reopened.
 * - **Status / column key** — the `status_id` of that covering interval (a Done -> Closed change still moves the item between
 *   status keys: both intervals are DONE-stage rows). The intervals of one item are half-open on the same instants
 *   (`(from, to]`), so only ONE DONE interval covers a given day end — even when `Done -> Closed` happens exactly at that
 *   end — which is why the item keys to one status and is counted once.
 * - **TEAM scope** — a task belongs to `fact_task_delivery.credit_team_id` (A25's done-time team: the sprint team, else the
 *   assignee's team, at `done_at`; null -> `UNASSIGNED`), an epic to its domain's owner team (`dim_domain.owner_team_id`, as-is
 *   like the WIP step, else `UNOWNED`).
 * - **DOMAIN scope** — a task to `fact_task_delivery.domain_key` (the `task_domain` row DERIVE resolved at `done_at` with its
 *   `[from, to)` rule, `domainRowAt` — one rule, so a move and a Done in the same changelog entry counts under the NEW domain;
 *   else the current `dim_task.domain_key`, the WIP step's `COALESCE`), an epic to its own domain.
 *
 * A task that is NOT currently done (a reopened one) has a null `done_at` and so a null `credit_team_id`: its earlier DONE
 * days are attributed `UNASSIGNED` (TEAM) and to its current domain (DOMAIN), because the fact stores the done-time
 * attribution of the CURRENT done run only (`startedDoneAt`).
 */

/** One DONE-band cell: [count] items sat in the DONE-stage status [statusId] at the end of [day]. */
internal data class WipDoneCell(val day: String, val statusId: String, val count: Int)

/**
 * What [readWipDoneBand] reads. [firstDay]..[lastDay] are ISO dates (inclusive, rows of the global `dim_date`);
 * [includeTasks]/[includeEpics] are the report's item kinds. [accountIds] (null = everyone) narrows the TASKS to those done
 * with one of these assignees (`fact_task_delivery.assignee_account_id_at_done`) and, as an epic has no assignee, drops the
 * epics — the per-person WIP read (brief 04) is this same statement with the filter set.
 */
internal data class WipDoneRequest(
    val connectionIds: List<UInt>,
    val scope: SnapshotScope,
    val includeTasks: Boolean,
    val includeEpics: Boolean,
    val firstDay: String,
    val lastDay: String,
    val accountIds: List<String>? = null,
)

/** The statement text with `?` markers, and the values bound to them in order (only user-supplied strings are bound). */
internal class WipDoneSql(val sql: String, val arguments: List<String>)

/** The DONE-stage `item_stage` rows of the connections, joined to each `day_range` day whose end they cover. */
private fun doneIntervals(connections: String): String = """
        FROM day_range d
        JOIN metrics.item_stage s ON s.connection_id IN ($connections) AND s.stage = 'DONE' AND ${coveringAt("s")}"""

/**
 * The DONE band as one `UNION ALL` of a task part and an epic part, each already grouped by `(day, status_id)`: rows are
 * `(day, status_id, count)`. Only numbers and validated ISO dates are interpolated; the domain key and the account ids are bound.
 */
internal fun wipDoneBandSql(request: WipDoneRequest): WipDoneSql {
    require(request.connectionIds.isNotEmpty()) { "wipDoneBandSql needs at least one connection" }
    val connections = request.connectionIds.joinToString(", ") { it.toString() }
    val arguments = ArrayList<String>()
    val parts = ArrayList<String>()
    if (request.includeTasks) parts += taskPart(request, connections, arguments)
    if (request.includeEpics && request.accountIds == null) parts += epicPart(request, connections, arguments)
    require(parts.isNotEmpty()) { "wipDoneBandSql: no item kind to read" }
    val first = isoDay(request.firstDay)
    val last = isoDay(request.lastDay)
    val days = "SELECT day, day_end_ms FROM metrics.dim_date WHERE day >= '$first' AND day <= '$last'"
    return WipDoneSql("WITH day_range AS ($days)\n" + parts.joinToString("\nUNION ALL\n"), arguments)
}

private fun isoDay(day: String): String = java.time.LocalDate.parse(day).toString()

private fun taskPart(request: WipDoneRequest, connections: String, arguments: MutableList<String>): String {
    val scope = request.scope
    val where = ArrayList<String>()
    when (scope) {
        SnapshotScope.AllTeams -> Unit
        is SnapshotScope.Team -> where += "f.credit_team_id = ${scope.teamId}"
        SnapshotScope.Unassigned -> where += "f.credit_team_id IS NULL"
        is SnapshotScope.Domain -> {
            // The domain DERIVE resolved AT DONE ([from, to), `domainRowAt`) and stored in the fact; a task with no fact row
            // (never delivered) falls back to its current domain.
            where += "COALESCE(f.domain_key, t.domain_key) = ?"
            arguments += scope.key
        }
    }
    request.accountIds?.let { accounts ->
        where += "f.assignee_account_id_at_done IN (${accounts.joinToString(", ") { "?" }})"
        arguments += accounts
    }
    return """
        SELECT d.day AS day, s.status_id AS status_id, COUNT(*) AS item_count
        ${doneIntervals(connections).trimStart()}
        JOIN metrics.dim_task t ON t.connection_id = s.connection_id AND t.issue_id = s.issue_id AND t.is_subtask = false
        LEFT JOIN metrics.fact_task_delivery f ON f.connection_id = s.connection_id AND f.issue_id = s.issue_id
        ${whereClause(where)}
        GROUP BY d.day, s.status_id""".trimIndent()
}

private fun epicPart(request: WipDoneRequest, connections: String, arguments: MutableList<String>): String {
    val where = ArrayList<String>()
    when (val scope = request.scope) {
        SnapshotScope.AllTeams -> Unit
        is SnapshotScope.Team -> where += "dom.owner_team_id = ${scope.teamId}"
        SnapshotScope.Unassigned -> where += "dom.owner_team_id IS NULL"
        is SnapshotScope.Domain -> {
            where += "e.domain_key = ?"
            arguments += scope.key
        }
    }
    return """
        SELECT d.day AS day, s.status_id AS status_id, COUNT(*) AS item_count
        ${doneIntervals(connections).trimStart()}
        JOIN metrics.dim_epic e ON e.connection_id = s.connection_id AND e.issue_id = s.issue_id
        LEFT JOIN metrics.dim_domain dom ON dom.connection_id = e.connection_id AND dom.domain_key = e.domain_key
        ${whereClause(where)}
        GROUP BY d.day, s.status_id""".trimIndent()
}

private fun whereClause(conditions: List<String>): String = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")

/**
 * Runs [wipDoneBandSql] inside the caller's report transaction: the DONE band of [request]'s period, oldest day first. The
 * result has no zero cells (a group exists only when an item landed in it) and may hold two cells for one `(day, status)`
 * when both item kinds are read — the report sums them.
 */
internal suspend fun R2dbcTransaction.readWipDoneBand(request: WipDoneRequest): List<WipDoneCell> {
    // Nothing to read: no connection, an empty account list (`IN ()` is a syntax error; nobody matches), or a person filter with
    // tasks excluded (an epic has no assignee, so no item kind is left).
    if (request.connectionIds.isEmpty() || request.accountIds?.isEmpty() == true) return emptyList()
    if (!request.includeTasks && request.accountIds != null) return emptyList()
    val built = wipDoneBandSql(request)
    val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
    val rows = exec(built.sql, arguments, StatementType.SELECT) { row ->
        WipDoneCell(row.get(0, String::class.java)!!, row.get(1, String::class.java)!!, row.get(2, Long::class.javaObjectType)!!.toInt())
    }
    return rows?.toList().orEmpty().filterNotNull().sortedWith(compareBy({ it.day }, { it.statusId }))
}
