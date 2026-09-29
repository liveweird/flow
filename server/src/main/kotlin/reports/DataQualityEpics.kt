package ch.nokillswit.reports

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.MetricsStore
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The EPIC side of the data-quality report (report 14, `.claude/docs/measures.md` "Report 14"): epics that are open or
 * done in the period, attributed to the owner team (A19; `teamId=0` = UNOWNED). Epics carry no user, so a USER-level read
 * has none. Runs inside the caller's `suspendTransaction`.
 */

private const val BUDGET_FROM_CHILDREN = "CHILDREN"

/**
 * One epic of the population. [owner] is the domain's owner team, [startAt]/[dueAt] the epic's own dates (`dim_epic`),
 * [outsideHorizon] = both dates are set but at least one lies outside the ±10-year PV horizon of the connection's last
 * DERIVE (so no PV curve was built), [flags] the D11 drift codes.
 */
internal data class DqEpic(
    val connectionId: UInt,
    val issueId: Long,
    val issueKey: String,
    val summary: String?,
    val owner: UInt?,
    val domainKey: String?,
    val startAt: Long?,
    val dueAt: Long?,
    val doneAt: Long?,
    val budgetSource: String,
    val outsideHorizon: Boolean,
    val flags: List<String>,
) {
    val withoutEstimate: Boolean get() = budgetSource == BUDGET_FROM_CHILDREN
    val withoutDates: Boolean get() = startAt == null || dueAt == null
    val drifting: Boolean get() = flags.isNotEmpty()
}

/** One epic a finding points at. [flags] is the D11 drift codes (empty outside the drift finding's own rows). */
@Serializable
data class EpicRef(
    val issueKey: String,
    val summary: String?,
    val ownerTeamId: UInt?,
    val domainKey: String?,
    val startAt: Long?,
    val dueAt: Long?,
    val doneAt: Long?,
    val flags: List<String>,
)

internal val DQ_EPIC_ORDER: Comparator<DqEpic> = compareBy<DqEpic> { it.connectionId }.thenBy { it.issueId }

/**
 * The epics open now or done in [window] (none of the done ones without a window), scoped by the epic slice (owner team,
 * `domain`, `workCategory`). [clocks] is each connection's DERIVE clock — the `now` its PV horizon was cut around; a
 * connection that never derived falls back to [nowMs]. USER level has no epics.
 */
internal suspend fun fetchDqEpics(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>?,
    clocks: Map<UInt, Long>,
    nowMs: Long,
): List<DqEpic> {
    if (connectionIds.isEmpty() || filter.level == ReportLevel.USER) return emptyList()
    val e = MetricsStore.FactEpicDelivery
    val period = if (window == null) {
        e.doneAt.isNull()
    } else {
        e.doneAt.isNull() or ((e.doneAt greaterEq window.first) and (e.doneAt less window.second))
    }
    val predicate = epicFactSlice(filter, connectionIds) and period
    val facts = e.select(
        e.connectionId, e.issueId, e.ownerTeamId, e.domainKey, e.doneAt, e.budgetSource, e.driftFlags,
    ).where { predicate }.toList()
    if (facts.isEmpty()) return emptyList()
    val d = MetricsStore.DimEpic
    val dims = d.select(d.connectionId, d.issueId, d.issueKey, d.summary, d.startAt, d.dueAt)
        .where {
            (d.connectionId inList facts.map { it[e.connectionId].value }.distinct()) and
                (d.issueId inList facts.map { it[e.issueId] }.distinct())
        }
        .toList().associateBy { it[d.connectionId].value to it[d.issueId] }
    return facts.map { fact ->
        val connectionId = fact[e.connectionId].value
        val dim = dims[connectionId to fact[e.issueId]]
        val start = dim?.get(d.startAt)
        val due = dim?.get(d.dueAt)
        val clock = clocks[connectionId] ?: nowMs
        DqEpic(
            connectionId = connectionId, issueId = fact[e.issueId], issueKey = dim?.get(d.issueKey) ?: fact[e.issueId].toString(),
            summary = dim?.get(d.summary), owner = fact[e.ownerTeamId]?.value, domainKey = fact[e.domainKey],
            startAt = start, dueAt = due, doneAt = fact[e.doneAt], budgetSource = fact[e.budgetSource],
            outsideHorizon = start != null && due != null && !DeriveKernels.inPvHorizon(start, due, clock),
            flags = Json.decodeFromString<List<String>>(fact[e.driftFlags]),
        )
    }
}

internal fun DqEpic.toRef(withFlags: Boolean) = EpicRef(
    issueKey = issueKey, summary = summary, ownerTeamId = owner, domainKey = domainKey, startAt = startAt, dueAt = dueAt,
    doneAt = doneAt, flags = if (withFlags) flags else emptyList(),
)
