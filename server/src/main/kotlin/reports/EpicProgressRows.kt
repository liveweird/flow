package ch.nokillswit.reports

import ch.nokillswit.metrics.acLoggedCte
import ch.nokillswit.metrics.coveringAt
import ch.nokillswit.metrics.evEpicDoneCte
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

// ---- the drill tables --------------------------------------------------------------------------

/**
 * One epic that was in the drilled domain at some point up to asOf: [keyCount] distinct domains it had up to the end of the asOf day
 * (more than one = it moved, A49), [endsHere] whether the drilled domain is the one it has at that instant, [hasBaseline] a current
 * (`superseded_at IS NULL`) baseline.
 */
private class DomainEpic(
    val connectionId: Int,
    val issueId: Long,
    val key: String,
    val name: String,
    val keyCount: Int,
    val endsHere: Boolean,
    val hasBaseline: Boolean,
)

/** An epic's cumulative figures and those inside the period (A37); [NONE] for an epic the statement returned no row for. */
private class Figures(val total: Evm, val period: Evm)

private val NONE = Figures(NO_EVM, NO_EVM)

private typealias EpicId = Pair<Int, Long>

/**
 * The instant the asOf day ends, as an SQL scalar: the stored `dim_date.day_end_ms` (the very value the PV join compares the
 * `task_domain` rows against, so the history and the PV agree by construction), or the calendar's [fallbackMs] when `dim_date` has no
 * row for the day. Inlined into the statements, no extra read.
 */
private fun dayEndSql(asOfDay: String, fallbackMs: Long): String =
    "COALESCE((SELECT day_end_ms FROM metrics.dim_date WHERE day = '$asOfDay'), $fallbackMs)"

/**
 * The epic's domain history up to the END of the asOf day (`task_domain` rows started before it, `COALESCE`d with the epic's current
 * domain exactly like PV DOMAIN, A47) and, per epic, the drilled domain's place in it: `seen` keeps the epics that were in the domain
 * (`HAVING BOOL_OR`), `key_count` counts their distinct domains, `ends_here` is the domain at that instant (the covering rule
 * `(from, to]`, [coveringAt]). An epic with no `task_domain` row is read as its current domain only. Two `?` (the domain, twice).
 */
private fun seenCte(connections: String, endMs: String): String = """
    hist AS (
        SELECT e.connection_id AS connection_id, e.issue_id AS issue_id, COALESCE(ed.domain_key, e.domain_key) AS domain_key,
               (ed.valid_to IS NULL OR ed.valid_to >= $endMs) AS at_end
        FROM metrics.dim_epic e
        LEFT JOIN metrics.task_domain ed ON ed.connection_id = e.connection_id AND ed.issue_id = e.issue_id AND ed.valid_from < $endMs
        WHERE e.connection_id IN ($connections)
    ),
    seen AS (
        SELECT connection_id, issue_id, COUNT(DISTINCT domain_key) AS key_count, COALESCE(BOOL_OR(domain_key = ? AND at_end), false) AS ends_here
        FROM hist
        GROUP BY connection_id, issue_id
        HAVING BOOL_OR(domain_key = ?)
    )""".trimIndent()

/** `SUM(column)` over the rows from [firstDay] on (the period, A37), or 0 when the period is empty; [extra] narrows the sum further. */
private fun periodSum(column: String, firstDay: String?, extra: String? = null): String {
    if (firstDay == null) return "0::numeric"
    val condition = listOfNotNull("f.day >= '$firstDay'", extra).joinToString(" AND ")
    return "COALESCE(SUM($column) FILTER (WHERE $condition), 0)"
}

/** Runs [sql] with the [domain] bound to each of its [domainArguments] `?` and returns every row's [width] cells, read by position. */
private suspend fun <T> readRows(sql: String, domainArguments: Int, domain: String, width: Int, map: (Array<Any?>) -> T): List<T> {
    val tx = TransactionManager.current()
    val arguments: List<Pair<IColumnType<*>, Any?>> = List(domainArguments) { TextColumnType() to domain }
    return tx.exec(sql, arguments, StatementType.SELECT) { row -> Array(width) { row.get(it) } }
        ?.toList().orEmpty().filterNotNull().map(map)
}

private fun Any?.dec(): BigDecimal = this as BigDecimal

/** The epics of [domain] and what they are (see [DomainEpic]); one grouped read over `dim_epic` and `task_domain`. */
private suspend fun domainEpics(connections: String, endMs: String, domain: String): List<DomainEpic> {
    val sql = """
        WITH ${seenCte(connections, endMs)}
        SELECT s.connection_id, s.issue_id, e.issue_key, e.summary, s.key_count, s.ends_here,
               EXISTS (
                   SELECT 1 FROM metrics.fact_epic_plan p
                   WHERE p.connection_id = s.connection_id AND p.issue_id = s.issue_id AND p.superseded_at IS NULL
               ) AS has_baseline
        FROM seen s
        JOIN metrics.dim_epic e ON e.connection_id = s.connection_id AND e.issue_id = s.issue_id""".trimIndent()
    return readRows(sql, 2, domain, 7) { r ->
        DomainEpic(
            (r[0] as Number).toInt(), (r[1] as Number).toLong(), r[2] as String, (r[3] as String?) ?: (r[2] as String),
            (r[4] as Number).toInt(), r[5] as Boolean, r[6] as Boolean,
        )
    }
}

/**
 * PV of the domain, from the epic's EPIC-scope increments: each planned day counts in the domain the epic had at the END of THAT day
 * (the covering row of `task_domain`, [coveringAt] at `dim_date.day_end_ms` -- the very rule of the PV DOMAIN scope, A47, so the epics'
 * shares add up to the domain's PV exactly). For an epic that stayed in the domain the same statement reads its stored EV and AC
 * (its EPIC scope, as before); a moved epic's EV and AC come from the facts ([movedEv], [movedAc]). Three `?` (the seen CTE's two
 * and the day filter).
 */
private suspend fun epicScopeFigures(
    connections: String,
    endMs: String,
    domain: String,
    asOfDay: String,
    firstDay: String?,
): Map<EpicId, Figures> {
    val sql = """
        WITH ${seenCte(connections, endMs)}
        SELECT s.connection_id, s.issue_id,
               COALESCE(SUM(f.pv_md), 0), ${periodSum("f.pv_md", firstDay)},
               COALESCE(SUM(f.ev_md) FILTER (WHERE s.key_count = 1), 0), ${periodSum("f.ev_md", firstDay, "s.key_count = 1")},
               COALESCE(SUM(f.ac_md) FILTER (WHERE s.key_count = 1), 0), ${periodSum("f.ac_md", firstDay, "s.key_count = 1")}
        FROM seen s
        JOIN metrics.dim_epic e ON e.connection_id = s.connection_id AND e.issue_id = s.issue_id
        JOIN metrics.agg_daily_flow f ON f.connection_id = s.connection_id AND f.scope_kind = '$SCOPE_KIND_EPIC'
            AND f.scope_id = s.issue_id::text AND f.day <= '$asOfDay'
        LEFT JOIN metrics.dim_date d ON d.day = f.day
        LEFT JOIN metrics.task_domain ed ON ed.connection_id = s.connection_id AND ed.issue_id = s.issue_id
            AND ${coveringAt("ed", at = "d.day_end_ms")}
        WHERE COALESCE(ed.domain_key, e.domain_key) = ?
        GROUP BY s.connection_id, s.issue_id""".trimIndent()
    return readRows(sql, 3, domain, 8) { r ->
        (((r[0] as Number).toInt()) to (r[1] as Number).toLong()) to
            Figures(Evm(r[2].dec(), r[4].dec(), r[6].dec()), Evm(r[3].dec(), r[5].dec(), r[7].dec()))
    }.toMap()
}

/**
 * A moved epic's EV (`ev = true`) or AC share of the domain: the done tasks' `estimate_at_done_md` ([evEpicDoneCte], DERIVE's own rows)
 * or the worklogs' `md` ([acLoggedCte]) whose `epic_domain_key` is the domain (the epic's domain at each row's anchor, A47). Returns the
 * cumulative and the period share per epic. Three `?` (the seen CTE's two and the domain filter).
 */
private suspend fun movedShares(
    connectionIds: List<UInt>,
    connections: String,
    endMs: String,
    domain: String,
    asOfDay: String,
    firstDay: String?,
    ev: Boolean,
): Map<EpicId, Pair<BigDecimal, BigDecimal>> {
    val (cte, source) = if (ev) evEpicDoneCte(connectionIds) to "done" else acLoggedCte(connectionIds) to "logged"
    val period = if (firstDay == null) "0::numeric" else "COALESCE(SUM(x.md) FILTER (WHERE x.day >= '$firstDay'), 0)"
    val sql = """
        WITH ${seenCte(connections, endMs)},
        $cte
        SELECT x.connection_id, x.epic_id, SUM(x.md), $period
        FROM $source x
        JOIN seen s ON s.connection_id = x.connection_id AND s.issue_id = x.epic_id AND s.key_count > 1
        WHERE x.epic_domain_key = ? AND x.day <= '$asOfDay'
        GROUP BY x.connection_id, x.epic_id""".trimIndent()
    return readRows(sql, 3, domain, 4) { r ->
        (((r[0] as Number).toInt()) to (r[1] as Number).toLong()) to (r[2].dec() to r[3].dec())
    }.toMap()
}

/**
 * A domain's epics, as of the asOf day, each with its own EPIC-scope figures -- cumulative, and (A37) inside the period beside them.
 * An epic is listed under EVERY domain it was in up to the end of the asOf day (domain-model A49), holding only that domain's share, so
 * the rows of a domain add up to the domain's totals: PV by the domain at the END of each planned day, EV by the domain at each done
 * task's `done_at` (`fact_task_delivery.epic_domain_key`), AC by the domain at each worklog's `started_at`
 * (`fact_worklog.epic_domain_key`), all A47's anchors. An epic that was in more than one domain up to then is `partial`. One that
 * stayed in one reads its stored EPIC scope, which is the same figures (AC to the stored rounding). It is listed when it has any share
 * in the domain, or a current baseline while the domain is the one it has at the end of the asOf day.
 */
internal suspend fun domainRows(ctx: ProgressContext, domain: String): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    if (connectionIds.isEmpty()) return emptyList()
    val connections = connectionIds.joinToString(", ")
    val asOfDay = ctx.asOfDay.toString()
    val endMs = dayEndSql(asOfDay, ctx.calendar.dayBoundsMs(ctx.asOfDay).second)
    val firstDay = ctx.firstDay?.toString()
    val epics = domainEpics(connections, endMs, domain)
    if (epics.isEmpty()) return emptyList()
    val scoped = epicScopeFigures(connections, endMs, domain, asOfDay, firstDay)
    // Only a moved epic reads the facts; a domain whose epics all stayed needs neither read.
    val anyMoved = epics.any { it.keyCount > 1 }
    val movedEv = if (anyMoved) movedShares(connectionIds, connections, endMs, domain, asOfDay, firstDay, ev = true) else emptyMap()
    val movedAc = if (anyMoved) movedShares(connectionIds, connections, endMs, domain, asOfDay, firstDay, ev = false) else emptyMap()
    return epics.mapNotNull { epic ->
        val id = epic.connectionId to epic.issueId
        val base = scoped[id] ?: NONE
        val moved = epic.keyCount > 1
        val ev = if (moved) movedEv[id] else null
        val ac = if (moved) movedAc[id] else null
        val total = Evm(base.total.pv, ev?.first ?: base.total.ev, ac?.first ?: base.total.ac)
        val period = Evm(base.period.pv, ev?.second ?: base.period.ev, ac?.second ?: base.period.ac)
        val listed = (epic.hasBaseline && epic.endsHere) || !total.isZero
        if (!listed) return@mapNotNull null
        total.toRow(EpicProgressKind.EPIC, null, epic.key, epic.name, period, partial = if (moved) true else null)
    }.sortedWith(compareBy({ it.key }, { it.name }))
}
