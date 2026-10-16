package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.backlogRowsCte
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/*
 * The estimated backlog narrowed to RECENTLY TOUCHED items (`touchedWithinDays`, domain-model A38, `.claude/docs/measures.md`
 * "Reports 10, 13"), computed AT QUERY TIME: DERIVE stores no per-item backlog membership or touch, so nothing it writes
 * moves and no migration is needed.
 *
 * The rule, per trend day D with end instant `E = dim_date.day_end_ms`: an item of the backlog at the end of D counts when its
 * LATEST touch strictly before E satisfies `touch >= E - N x MILLIS_PER_DAY` (a fixed-24h window, the repo's one day constant;
 * a touch exactly at E belongs to the next day, like every "day of an event"). A touch is the item's creation
 * (`fact_task_delivery.created_at`), any Jira history of it (`raw.jira_changelogs.created_at` -- any field), or any worklog on it
 * (`norm.work_item_worklogs.started_at`, the worklog anchor). The item's own history only: a sub-task's change does not touch
 * its parent. Who is in the backlog is DERIVE's own rule, [backlogRowsCte] shared verbatim.
 *
 * Shape (ONE statement per connection): the covering day x interval join of [backlogRowsCte] narrowed to the scope; one
 * index-only probe per CANDIDATE item for its touches; then only the gaps LONGER than N between consecutive touches are kept
 * (`stale`) -- a day end E is stale iff `t_i + N < E <= t_(i+1)` for the gap `(t_i, t_(i+1)]` it falls in -- so each (item, day)
 * row is compared with a handful of gap rows, never with every touch of the item. The `item_stage` side of the covering join is
 * pruned to the period (`stagesOverlappingDayRange`): the unpruned plan multiplies every task by every day.
 */

/** The longest window `touchedWithinDays` accepts (days); ten years, far past any backfill. */
const val MAX_TOUCHED_WITHIN_DAYS = 3650

/**
 * `touchedWithinDays`: `null` = no filter (absent or `0`), `1..3650` = the window in days. `400` for a non-integer, a negative
 * number, a repeat (the strict readers) or a value above [MAX_TOUCHED_WITHIN_DAYS].
 */
internal fun Parameters.parseTouchedWithinDays(): Int? {
    val days = optionalUInt("touchedWithinDays") ?: return null
    if (days > MAX_TOUCHED_WITHIN_DAYS.toUInt()) {
        throw BadRequestException("touchedWithinDays must be between 0 and $MAX_TOUCHED_WITHIN_DAYS")
    }
    return days.toInt().takeIf { it > 0 }
}

/** One day's backlog, unfiltered ([allItems]/[allMd]) and recently touched only ([items]/[md]), summed over the connections. */
internal data class TouchedBacklogDay(val allItems: Int, val allMd: BigDecimal, val items: Int, val md: BigDecimal)

private data class TouchedRow(val day: String, val cell: TouchedBacklogDay)

/**
 * Runs the touched-backlog statement for every connection inside the caller's report transaction: the per-day figures of
 * [firstDay]..[lastDay] (ISO dates, rows of the global `dim_date`) in [scope]. A day with no backlog row is absent (the caller
 * zero-fills it).
 */
internal suspend fun R2dbcTransaction.readTouchedBacklog(
    connectionIds: List<UInt>,
    scope: SnapshotScope,
    firstDay: String,
    lastDay: String,
    touchedWithinDays: Int,
): Map<String, TouchedBacklogDay> {
    val perDay = HashMap<String, TouchedBacklogDay>()
    for (connectionId in connectionIds) {
        val built = touchedBacklogSql(connectionId, scope, firstDay, lastDay, touchedWithinDays)
        val arguments: List<Pair<IColumnType<*>, Any?>> = built.arguments.map { TextColumnType() to it }
        val rows = exec(built.sql, arguments, StatementType.SELECT) { row ->
            TouchedRow(
                row.get(0, String::class.java)!!,
                TouchedBacklogDay(
                    row.get(1, Long::class.javaObjectType)!!.toInt(),
                    row.get(2, BigDecimal::class.java)!!,
                    row.get(3, Long::class.javaObjectType)!!.toInt(),
                    row.get(4, BigDecimal::class.java)!!,
                ),
            )
        }
        for ((day, cell) in rows?.toList().orEmpty().filterNotNull()) {
            perDay.merge(day, cell) { a, b ->
                TouchedBacklogDay(a.allItems + b.allItems, a.allMd + b.allMd, a.items + b.items, a.md + b.md)
            }
        }
    }
    return perDay
}

/**
 * The statement for one connection; rows are `(day, all items, all md, fresh items, fresh md)`, oldest day first. Only numbers
 * and validated ISO dates are interpolated; the domain key is bound.
 */
internal fun touchedBacklogSql(
    connectionId: UInt,
    scope: SnapshotScope,
    firstDay: String,
    lastDay: String,
    touchedWithinDays: Int,
): RawSql {
    require(touchedWithinDays in 1..MAX_TOUCHED_WITHIN_DAYS) { "touchedWithinDays must be in 1..$MAX_TOUCHED_WITHIN_DAYS" }
    val arguments = ArrayList<String>()
    val where = when (scope) {
        SnapshotScope.AllTeams -> "TRUE"
        is SnapshotScope.Team -> "COALESCE(dom.owner_team_id::text, 'UNOWNED') = '${scope.teamId}'"
        SnapshotScope.Unassigned -> "dom.owner_team_id IS NULL"
        is SnapshotScope.Domain -> {
            arguments += scope.key
            "r.domain_key = ?"
        }
    }
    val windowMs = touchedWithinDays * MILLIS_PER_DAY
    val sql = """
        WITH day_range AS (
            SELECT day, day_end_ms FROM metrics.dim_date WHERE day >= '${isoDay(firstDay)}' AND day <= '${isoDay(lastDay)}'
        ),
        ${backlogRowsCte(connectionId, stagesOverlappingDayRange = true)},
        scoped AS (
            SELECT r.day AS day, r.day_end_ms AS day_end_ms, r.issue_id AS issue_id, r.estimate_md AS estimate_md
            FROM rows r
            LEFT JOIN metrics.dim_domain dom ON dom.connection_id = $connectionId AND dom.domain_key = r.domain_key
            WHERE $where
        ),
        touches AS (
            SELECT c.issue_id AS issue_id, x.ts AS ts
            FROM (SELECT DISTINCT issue_id FROM scoped) c
            CROSS JOIN LATERAL (
                SELECT f.created_at AS ts FROM metrics.fact_task_delivery f
                WHERE f.connection_id = $connectionId AND f.issue_id = c.issue_id
                UNION ALL
                SELECT ch.created_at FROM raw.jira_changelogs ch
                WHERE ch.connection_id = $connectionId AND ch.issue_id = c.issue_id
                UNION ALL
                SELECT w.started_at FROM norm.work_item_worklogs w
                WHERE w.connection_id = $connectionId AND w.issue_id = c.issue_id
            ) x
        ),
        stale AS (
            SELECT issue_id, ts + $windowMs AS stale_from_ms, next_ts
            FROM (SELECT issue_id, ts, LEAD(ts) OVER (PARTITION BY issue_id ORDER BY ts) AS next_ts FROM touches) g
            WHERE next_ts IS NULL OR next_ts > ts + $windowMs
        ),
        all_by_day AS (
            SELECT day, COUNT(*) AS items, SUM(estimate_md) AS md FROM scoped GROUP BY day
        ),
        fresh_by_day AS (
            SELECT sc.day AS day, COUNT(*) AS items, SUM(sc.estimate_md) AS md
            FROM scoped sc
            WHERE NOT EXISTS (
                SELECT 1 FROM stale st
                WHERE st.issue_id = sc.issue_id AND st.stale_from_ms < sc.day_end_ms
                  AND (st.next_ts IS NULL OR sc.day_end_ms <= st.next_ts)
            )
            GROUP BY sc.day
        )
        SELECT a.day, a.items, a.md, COALESCE(f.items, 0), COALESCE(f.md, 0)
        FROM all_by_day a
        LEFT JOIN fresh_by_day f ON f.day = a.day
        ORDER BY a.day
    """.trimIndent()
    return RawSql(sql, arguments)
}
