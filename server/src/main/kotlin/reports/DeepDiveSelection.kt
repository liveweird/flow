package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.repeatedLongs
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.singleValue
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.HistoryHorizon
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

// Report 17, the Deep dive (`.claude/docs/reports.md` "Report 17"): the parse of its two selection modes and their resolution against the
// stored facts. The report has its OWN parser, not `parseReportFilter`: that one's `singleValue` rule rejects repeated keys and its 90-day
// default has no meaning here. Every check that needs no data runs in [parseDeepDive] (before any read); the ones that do run in
// [resolveDeepDiveTargets], inside the caller's transaction. Always `400`, never `404`.

internal const val DEEP_DIVE_MAX_SPRINTS = 52
internal const val DEEP_DIVE_MAX_EPICS = 50
internal const val DEEP_DIVE_MAX_TASKS = 500

/** The report's range cap in days, inclusive of both ends (also the plan window clamp, [MAX_WINDOW_DAYS]). */
internal const val DEEP_DIVE_MAX_RANGE_DAYS = MAX_WINDOW_DAYS

/** Which of the two selections ran (`mode` of the response). */
internal enum class DeepDiveSelectionMode { SPRINTS, EPICS }

/** The selection a request names: exactly one of the two modes. Keys are Jira issue keys; sprint ids are Jira sprint ids. */
internal sealed interface DeepDiveSelection {
    val mode: DeepDiveSelectionMode

    /** Mode (a): a domain's level-0 tasks `in_scope_at_close` in at least one of [sprintIds]. */
    data class Sprints(val domain: String, val sprintIds: List<Long>) : DeepDiveSelection {
        override val mode = DeepDiveSelectionMode.SPRINTS
    }

    /** Mode (b): every level-0 task under [epicKeys]. */
    data class Epics(val epicKeys: List<String>) : DeepDiveSelection {
        override val mode = DeepDiveSelectionMode.EPICS
    }
}

/**
 * A parsed request: the [selection], the optional [connectionId], the optional `from`/`to` clip (inclusive, configured zone) and the
 * request's A48 [historyHorizon] (`null` = off), which the range, the `sprintId` check and `meta` read.
 */
internal class DeepDiveRequest(
    val selection: DeepDiveSelection,
    val connectionId: UInt?,
    val from: LocalDate?,
    val to: LocalDate?,
    val historyHorizon: HistoryHorizon?,
)

/** Parses the query into a [DeepDiveRequest] under [historyHorizon]; every `400` that needs no data is thrown here. */
internal fun Parameters.parseDeepDive(historyHorizon: HistoryHorizon?): DeepDiveRequest {
    val sprintIds = repeatedLongs("sprintId", maxCount = DEEP_DIVE_MAX_SPRINTS, minValue = 1)
    val epicKeys = repeatedStrings("epicId", maxCount = DEEP_DIVE_MAX_EPICS).map { sanitizeSingleLine(it, "epicId") }
    val domain = optionalSingleLine("domain")
    val selection = when {
        sprintIds.isNotEmpty() -> {
            if (epicKeys.isNotEmpty()) throw BadRequestException("sprintId cannot be combined with epicId")
            DeepDiveSelection.Sprints(domain ?: throw BadRequestException("domain is required with sprintId"), sprintIds)
        }
        domain != null -> throw BadRequestException("domain takes sprintId (the sprint selection)")
        epicKeys.isNotEmpty() -> DeepDiveSelection.Epics(epicKeys)
        else -> throw BadRequestException("Select by domain and sprintId, or by epicId")
    }
    val from = singleValue("from")?.takeIf { it.isNotBlank() }?.let { parseIsoDate(it.trim(), "from") }
    val to = singleValue("to")?.takeIf { it.isNotBlank() }?.let { parseIsoDate(it.trim(), "to") }
    if (from != null && to != null) requireRange(from, to)
    // A `to` (alone or with `from`) before the horizon names a period lying entirely before it: the shared report parser's `400`.
    if (historyHorizon != null && to != null && to.isBefore(historyHorizon.from)) {
        throw BadRequestException("from/to lies entirely before the history horizon (${historyHorizon.from})")
    }
    return DeepDiveRequest(selection, optionalUInt("connectionId"), from, to, historyHorizon)
}

/**
 * `400` for a GIVEN range (both `from` and `to`) that ends before it starts or spans more than [DEEP_DIVE_MAX_RANGE_DAYS] days (inclusive
 * of both ends: the REQUESTED span, before any clamp). A lone bound, or an implied range, never fails on that: it clips or is clamped
 * (`DeepDiveReport.kt`, [clampRangeToHorizon]).
 */
internal fun requireRange(from: LocalDate, to: LocalDate) {
    if (to.isBefore(from)) throw BadRequestException("to must not be before from")
    if (ChronoUnit.DAYS.between(from, to) + 1 > DEEP_DIVE_MAX_RANGE_DAYS) {
        throw BadRequestException("The range must not exceed $DEEP_DIVE_MAX_RANGE_DAYS days")
    }
}

/** The tasks a selection resolved to, all in ONE connection: level-0 [taskIds]; [epics] = the selected epics (mode b). */
internal class DeepDiveTargets(
    val connectionId: UInt,
    val selection: DeepDiveSelection,
    val taskIds: List<Long>,
    val epics: List<ResolvedEpic>,
)

/**
 * Resolves [request]'s selection against the stored facts in [connectionIds] (already narrowed by `connectionId`): `400` for an unknown
 * sprint, epic or domain, a key present in several connections (narrow with `connectionId`), selections that do not share one
 * connection, and more than [DEEP_DIVE_MAX_TASKS] tasks. In mode (a) the domain narrows the candidate connections first, so a sprint id
 * shared by two connections is ambiguous only when both hold the domain; the one ambiguity left is a domain that spans connections,
 * resolved with `connectionId`.
 */
internal suspend fun resolveDeepDiveTargets(
    request: DeepDiveRequest,
    connectionIds: List<UInt>,
    calendar: WorkingCalendar,
): DeepDiveTargets =
    when (val selection = request.selection) {
        is DeepDiveSelection.Sprints -> resolveSprints(selection, connectionIds, request.historyHorizon, calendar)
        is DeepDiveSelection.Epics -> {
            val epics = requireEpicsByKey(selection.epicKeys, connectionIds)
            val connectionId = epics.first().connectionId
            DeepDiveTargets(connectionId, selection, epicTaskIds(connectionId, epics.map { it.issueId }), epics)
        }
    }

private suspend fun resolveSprints(
    selection: DeepDiveSelection.Sprints,
    connectionIds: List<UInt>,
    horizon: HistoryHorizon?,
    calendar: WorkingCalendar,
): DeepDiveTargets {
    val s = MetricsTables.DimSprint
    val inDomain = connectionsWithDomain(selection.domain, connectionIds)
    val found = s.select(s.connectionId, s.sprintId, s.completeAt)
        .where { (s.connectionId inList inDomain) and (s.sprintId inList selection.sprintIds) }
        .toList()
    val rows = found.groupBy({ it[s.sprintId] }, { it[s.connectionId].value })
    val connectionId = singleConnection("sprintId", selection.sprintIds, rows)
    requireSprintsInHorizon(found.groupBy({ it[s.sprintId] }, { it[s.completeAt] }), horizon, calendar)

    val scope = MetricsTables.FactSprintScope
    val task = MetricsTables.FactTaskDelivery
    val ids = scope
        .join(task, JoinType.INNER, onColumn = scope.issueId, otherColumn = task.issueId) {
            scope.connectionId eq task.connectionId
        }
        .select(scope.issueId)
        .withDistinct()
        .where {
            (scope.connectionId eq connectionId) and (scope.sprintId inList selection.sprintIds) and (scope.inScopeAtClose eq true) and
                (task.isSubtask eq false) and (task.domainKey eq selection.domain)
        }
        .limit(DEEP_DIVE_MAX_TASKS + 1)
        .toList().map { it[scope.issueId] }
    return DeepDiveTargets(connectionId, selection, requireTaskCap(ids), emptyList())
}

private suspend fun epicTaskIds(connectionId: UInt, epicIssueIds: List<Long>): List<Long> {
    val t = MetricsTables.FactTaskDelivery
    val ids = t.select(t.issueId)
        .where { (t.connectionId eq connectionId) and (t.epicId inList epicIssueIds) and (t.isSubtask eq false) }
        .limit(DEEP_DIVE_MAX_TASKS + 1)
        .toList().map { it[t.issueId] }
    return requireTaskCap(ids)
}

private fun requireTaskCap(ids: List<Long>): List<Long> {
    if (ids.size > DEEP_DIVE_MAX_TASKS) throw BadRequestException("The selection resolves to more than $DEEP_DIVE_MAX_TASKS tasks")
    return ids
}

/**
 * `400` for a sprint closed before the [horizon] (A48): the shared `sprintId` rule of the other reports (`SprintPeriods.kt`) -- every
 * stored copy of the id completed before the cutoff; an open sprint (`complete_at` null) is current work and never cut.
 */
private fun requireSprintsInHorizon(completeAtById: Map<Long, List<Long?>>, horizon: HistoryHorizon?, calendar: WorkingCalendar) {
    val cutoffMs = horizon?.let { calendar.dayBoundsMs(it.from).first } ?: return
    completeAtById.entries.firstOrNull { (_, closes) -> closes.all { it != null && it < cutoffMs } }?.let { (id, _) ->
        throw BadRequestException("sprintId $id closed before the history horizon (${horizon.from})")
    }
}
