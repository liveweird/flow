package ch.nokillswit.reports

import ch.nokillswit.metrics.HistoryHorizon
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The `lastSprints` / `sprintId` / `from`-`to` period resolution of the sprint-anchored reports. A sprint is whole-or-nothing against
 * the history horizon (domain-model A48, measures.md "Period membership"): it belongs to a report only when it COMPLETED on or after
 * the horizon's first day, so a sprint straddling the cutoff is kept whole and one closed before it is gone. An open sprint
 * (`complete_at` null) is current work and never cut.
 */

/**
 * The sprint rows [period] selects for [connectionIds] (optionally one team). [horizon] (read in [calendar]'s zone; `null` = off) drops the
 * sprints completed before it from `lastSprints` and refuses a `sprintId` naming one; a `from`/`to` period was already clamped by the
 * parser.
 */
internal suspend fun resolveSprintRows(
    period: ReportPeriod,
    connectionIds: List<UInt>,
    narrowTeamId: UInt?,
    horizon: HistoryHorizon?,
    calendar: WorkingCalendar,
): List<SprintRow> {
    if (connectionIds.isEmpty()) return emptyList()
    val horizonMs = horizon?.let { calendar.dayBoundsMs(it.from).first }
    return when (period) {
        is ReportPeriod.DateRange -> sprintRowsInRange(connectionIds, narrowTeamId, period.fromMs, period.toMs)
        is ReportPeriod.LastSprints -> sprintRowsLastN(connectionIds, narrowTeamId, period.count, horizonMs)
        is ReportPeriod.BySprintId -> sprintRowsForId(connectionIds, narrowTeamId, period.sprintId, horizon, horizonMs)
    }
}

private suspend fun sprintRowsInRange(connectionIds: List<UInt>, narrowTeamId: UInt?, fromMs: Long, toMs: Long): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and
        (MetricsTables.FactSprint.completeAt greaterEq fromMs) and
        (MetricsTables.FactSprint.completeAt less toMs) // toMs is exclusive: the day after `to` starts
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}

private suspend fun sprintRowsLastN(connectionIds: List<UInt>, narrowTeamId: UInt?, count: Int, horizonMs: Long?): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and MetricsTables.FactSprint.completeAt.isNotNull()
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    horizonMs?.let { predicate = predicate and (MetricsTables.FactSprint.completeAt greaterEq it) }
    val rows = sprintJoinQuery().where { predicate }
        .orderBy(MetricsTables.FactSprint.teamId to SortOrder.ASC, MetricsTables.FactSprint.completeAt to SortOrder.DESC)
        .toList().map { it.toSprintRow() }
    return rows.groupBy { it.teamId }.values.flatMap { it.take(count) }
}

private suspend fun sprintRowsForId(
    connectionIds: List<UInt>,
    narrowTeamId: UInt?,
    sprintId: Long,
    horizon: HistoryHorizon?,
    horizonMs: Long?,
): List<SprintRow> {
    val dim = MetricsTables.DimSprint
    val known = dim.select(dim.completeAt)
        .where { (dim.connectionId inList connectionIds) and (dim.sprintId eq sprintId) }
        .toList()
    if (known.isEmpty()) throw BadRequestException("Unknown sprintId: $sprintId")
    if (horizon != null && horizonMs != null && known.all { row -> row[dim.completeAt]?.let { it < horizonMs } == true }) {
        throw BadRequestException("sprintId $sprintId closed before the history horizon (${horizon.from})")
    }
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and (MetricsTables.FactSprint.sprintId eq sprintId)
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    // A sprint id shared by two connections: the copy completed before the cutoff drops out, like `lastSprints` drops it.
    horizonMs?.let {
        predicate = predicate and (MetricsTables.FactSprint.completeAt.isNull() or (MetricsTables.FactSprint.completeAt greaterEq it))
    }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}
