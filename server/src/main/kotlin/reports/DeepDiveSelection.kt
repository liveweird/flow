package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.repeatedLongs
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.singleValue
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsTables
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

// Report 17, the Deep dive (`.claude/docs/reports.md` "Report 17"): the parse of its three selection modes and their resolution against the
// stored facts. The report has its OWN parser, not `parseReportFilter`: that one's `singleValue` rule rejects repeated keys and its 90-day
// default has no meaning here. Every check that needs no data runs in [parseDeepDive] (before any read); the ones that do run in
// [resolveDeepDiveTargets], inside the caller's transaction. Always `400`, never `404`.

internal const val DEEP_DIVE_MAX_SPRINTS = 52
internal const val DEEP_DIVE_MAX_EPICS = 50
internal const val DEEP_DIVE_MAX_ISSUES = 500
internal const val DEEP_DIVE_MAX_TASKS = 500

/** The report's range cap in days, inclusive of both ends (also the plan window clamp, [MAX_WINDOW_DAYS]). */
internal const val DEEP_DIVE_MAX_RANGE_DAYS = MAX_WINDOW_DAYS

/** Which of the three selections ran (`mode` of the response). */
internal enum class DeepDiveSelectionMode { SPRINTS, EPICS, TASKS }

/** The selection a request names: exactly one of the three modes. Keys are Jira issue keys; sprint ids are Jira sprint ids. */
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

    /** Mode (c): exactly [issueKeys], which must all sit under [epicKey]. */
    data class Tasks(val epicKey: String, val issueKeys: List<String>) : DeepDiveSelection {
        override val mode = DeepDiveSelectionMode.TASKS
    }
}

/** A parsed request: the [selection], the optional [connectionId] and the optional `from`/`to` clip (inclusive, configured zone). */
internal class DeepDiveRequest(val selection: DeepDiveSelection, val connectionId: UInt?, val from: LocalDate?, val to: LocalDate?)

/** Parses the query into a [DeepDiveRequest]; every `400` that needs no data is thrown here. */
internal fun Parameters.parseDeepDive(): DeepDiveRequest {
    val sprintIds = repeatedLongs("sprintId", maxCount = DEEP_DIVE_MAX_SPRINTS, minValue = 1)
    val epicKeys = repeatedStrings("epicId", maxCount = DEEP_DIVE_MAX_EPICS).map { sanitizeSingleLine(it, "epicId") }
    val issueKeys = repeatedStrings("issueId", maxCount = DEEP_DIVE_MAX_ISSUES).map { sanitizeSingleLine(it, "issueId") }
    val domain = optionalSingleLine("domain")
    val selection = when {
        sprintIds.isNotEmpty() -> {
            if (epicKeys.isNotEmpty() || issueKeys.isNotEmpty()) {
                throw BadRequestException("sprintId cannot be combined with epicId or issueId")
            }
            DeepDiveSelection.Sprints(domain ?: throw BadRequestException("domain is required with sprintId"), sprintIds)
        }
        domain != null -> throw BadRequestException("domain takes sprintId (the sprint selection)")
        issueKeys.isNotEmpty() -> {
            if (epicKeys.size != 1) throw BadRequestException("issueId takes exactly one epicId")
            DeepDiveSelection.Tasks(epicKeys.single(), issueKeys)
        }
        epicKeys.isNotEmpty() -> DeepDiveSelection.Epics(epicKeys)
        else -> throw BadRequestException("Select by domain and sprintId, by epicId, or by one epicId and issueId")
    }
    val from = singleValue("from")?.takeIf { it.isNotBlank() }?.let { parseIsoDate(it.trim(), "from") }
    val to = singleValue("to")?.takeIf { it.isNotBlank() }?.let { parseIsoDate(it.trim(), "to") }
    if (from != null && to != null) requireRange(from, to)
    return DeepDiveRequest(selection, optionalUInt("connectionId"), from, to)
}

/**
 * `400` for a GIVEN range (both `from` and `to`) that ends before it starts or spans more than [DEEP_DIVE_MAX_RANGE_DAYS] days (inclusive
 * of both ends). A lone bound, or an implied range, never fails: it clips or is clamped (`DeepDiveReport.kt`).
 */
internal fun requireRange(from: LocalDate, to: LocalDate) {
    if (to.isBefore(from)) throw BadRequestException("to must not be before from")
    if (ChronoUnit.DAYS.between(from, to) + 1 > DEEP_DIVE_MAX_RANGE_DAYS) {
        throw BadRequestException("The range must not exceed $DEEP_DIVE_MAX_RANGE_DAYS days")
    }
}

/** The tasks a selection resolved to, all in ONE connection: level-0 [taskIds]; [epics] = the selected epics (modes b, c). */
internal class DeepDiveTargets(
    val connectionId: UInt,
    val selection: DeepDiveSelection,
    val taskIds: List<Long>,
    val epics: List<ResolvedEpic>,
)

/**
 * Resolves [request]'s selection against the stored facts in [connectionIds] (already narrowed by `connectionId`): `400` for an unknown
 * sprint, epic, issue or domain, a key present in several connections (narrow with `connectionId`), selections that do not share one
 * connection, an issue not under the chosen epic, and more than [DEEP_DIVE_MAX_TASKS] tasks.
 */
internal suspend fun resolveDeepDiveTargets(request: DeepDiveRequest, connectionIds: List<UInt>): DeepDiveTargets =
    when (val selection = request.selection) {
        is DeepDiveSelection.Sprints -> resolveSprints(selection, connectionIds)
        is DeepDiveSelection.Epics -> {
            val epics = resolveEpics(selection.epicKeys, connectionIds)
            val connectionId = epics.first().connectionId
            DeepDiveTargets(connectionId, selection, epicTaskIds(connectionId, epics.map { it.issueId }), epics)
        }
        is DeepDiveSelection.Tasks -> resolveHandpicked(selection, connectionIds)
    }

private suspend fun resolveSprints(selection: DeepDiveSelection.Sprints, connectionIds: List<UInt>): DeepDiveTargets {
    val s = MetricsTables.DimSprint
    val rows = s.select(s.connectionId, s.sprintId)
        .where { (s.connectionId inList connectionIds) and (s.sprintId inList selection.sprintIds) }
        .toList().groupBy({ it[s.sprintId] }, { it[s.connectionId].value })
    val connectionId = singleConnection("sprintId", selection.sprintIds, rows)
    val d = MetricsTables.DimDomain
    val known = d.select(d.domainKey)
        .where { (d.connectionId eq connectionId) and (d.domainKey eq selection.domain) }
        .toList().isNotEmpty()
    if (!known) throw BadRequestException("Unknown domain: ${selection.domain}")

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

/** The single connection every one of [requested] was found in, or `400` for an unknown value, an ambiguous one or a split selection. */
private fun <T> singleConnection(name: String, requested: List<T>, foundIn: Map<T, List<UInt>>): UInt {
    for (value in requested) {
        val connections = foundIn[value].orEmpty().distinct()
        if (connections.isEmpty()) throw BadRequestException("Unknown or inactive $name: $value")
        if (connections.size > 1) throw BadRequestException("$name $value exists in several connections; narrow with connectionId")
    }
    val connections = requested.map { foundIn.getValue(it).first() }.distinct()
    if (connections.size > 1) throw BadRequestException("The $name values belong to several connections; narrow with connectionId")
    return connections.single()
}

private suspend fun resolveEpics(epicKeys: List<String>, connectionIds: List<UInt>): List<ResolvedEpic> {
    val e = MetricsTables.DimEpic
    val rows = e.select(e.connectionId, e.issueId, e.issueKey, e.summary, e.startAt, e.dueAt)
        .where { (e.connectionId inList connectionIds) and (e.issueKey inList epicKeys) }
        .toList()
    val byKey = rows.groupBy { it[e.issueKey] }
    val connectionId = singleConnection("epicId", epicKeys, byKey.mapValues { (_, found) -> found.map { it[e.connectionId].value } })
    return epicKeys.map { key ->
        val row = byKey.getValue(key).first { it[e.connectionId].value == connectionId }
        ResolvedEpic(connectionId, row[e.issueId], key, row[e.summary], row[e.startAt], row[e.dueAt])
    }
}

private suspend fun epicTaskIds(connectionId: UInt, epicIssueIds: List<Long>): List<Long> {
    val t = MetricsTables.FactTaskDelivery
    val ids = t.select(t.issueId)
        .where { (t.connectionId eq connectionId) and (t.epicId inList epicIssueIds) and (t.isSubtask eq false) }
        .limit(DEEP_DIVE_MAX_TASKS + 1)
        .toList().map { it[t.issueId] }
    return requireTaskCap(ids)
}

private suspend fun resolveHandpicked(selection: DeepDiveSelection.Tasks, connectionIds: List<UInt>): DeepDiveTargets {
    val epic = resolveEpics(listOf(selection.epicKey), connectionIds).single()
    val t = MetricsTables.FactTaskDelivery
    val found = t.select(t.issueId, t.issueKey, t.epicId)
        .where { (t.connectionId eq epic.connectionId) and (t.issueKey inList selection.issueKeys) and (t.isSubtask eq false) }
        .toList().associateBy { it[t.issueKey] }
    val ids = selection.issueKeys.map { key ->
        val row = found[key] ?: throw BadRequestException("Unknown issueId: $key (not a task of this connection)")
        if (row[t.epicId] != epic.issueId) throw BadRequestException("issueId $key is not under epic ${selection.epicKey}")
        row[t.issueId]
    }
    return DeepDiveTargets(epic.connectionId, selection, requireTaskCap(ids), listOf(epic))
}

private fun requireTaskCap(ids: List<Long>): List<Long> {
    if (ids.size > DEEP_DIVE_MAX_TASKS) throw BadRequestException("The selection resolves to more than $DEEP_DIVE_MAX_TASKS tasks")
    return ids
}
