package ch.nokillswit.reports

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The plumbing the two DAILY-SNAPSHOT reports share — WIP (report 9, `agg_daily_wip`) and the estimated
 * backlog (reports 10 + 13, `agg_daily_flow.backlog_*`): which aggregate scope a filter reads, the
 * calendar days of a period, and how far the aggregates actually reach. Every function runs inside the
 * CALLER's `suspendTransaction`.
 */

/** The `scope_id` of a task with no team in `agg_daily_wip`/`agg_daily_flow` TASK-side TEAM rows (`teamId=0` for tasks). */
internal const val SCOPE_UNASSIGNED = "UNASSIGNED"

/** The `scope_id` of an epic / backlog item whose domain has no owner team (`teamId=0` for epics and the backlog). */
internal const val SCOPE_UNOWNED = "UNOWNED"

internal const val SCOPE_KIND_TEAM = "TEAM"
internal const val SCOPE_KIND_DOMAIN = "DOMAIN"
internal const val SCOPE_KIND_EPIC = "EPIC"

/**
 * Which aggregate rows a daily-snapshot report reads (`.claude/docs/reports.md` "Reports 9, 10, 13"): the daily
 * aggregates are stored per TEAM scope and per DOMAIN scope only — never per user, never team × domain.
 */
internal sealed interface SnapshotScope {
    /** TEAM level: one team's own TEAM scope. */
    data class Team(val teamId: UInt) : SnapshotScope

    /** `teamId=0`: UNASSIGNED tasks, UNOWNED epics / backlog. */
    data object Unassigned : SnapshotScope

    /** UNIT level, a `domain` with no `teamId`: the DOMAIN scope of that key (the task's as-was domain, or an epic's own). */
    data class Domain(val key: String) : SnapshotScope
}

/**
 * The scope [filter] selects, or `400` for a combination the aggregates cannot answer: `activityType` and
 * `workCategory` are not stored per day, and `domain` cannot combine with `teamId` (no team × domain split). A filter names a team or a
 * domain (the scope guard of `parseReportFilter` refuses a read naming neither, so there is no whole-unit scope).
 * `domainView` is accepted and ignored, as in the other reports. USER level (`accountId`) is not
 * rejected here — the backlog answers it empty, with a documented note, WIP reads it at query time — but the same combinations
 * still 400 (and `accountId` requires `teamId`, so USER level never carries a `domain`). The one team-less USER-level filter
 * (compare-people, report 18) must never get here: it is refused, not answered with a unit-wide scope.
 */
internal fun snapshotScopeOf(filter: ReportFilter): SnapshotScope {
    require(!(filter.level == ReportLevel.USER && filter.teamId == null)) { "a team-less USER-level filter has no snapshot scope" }
    if (filter.activityType != null || filter.workCategory != null) {
        throw BadRequestException("activityType and workCategory are not available for the daily WIP/backlog aggregates")
    }
    val teamId = filter.teamId
    val domain = filter.domain
    if (domain != null && teamId != null) {
        throw BadRequestException("domain cannot be combined with teamId for the daily WIP/backlog aggregates (no team x domain split)")
    }
    if (domain != null) return SnapshotScope.Domain(domain)
    requireNotNull(teamId) { "a filter naming neither a team nor a domain has no snapshot scope (the scope guard refuses it)" }
    return if (teamId == UNASSIGNED_TEAM_ID) SnapshotScope.Unassigned else SnapshotScope.Team(teamId)
}

/** `agg_daily_wip` rows of [scope]; [Unassigned][SnapshotScope.Unassigned] is UNASSIGNED on the task side and UNOWNED on the epic side. */
internal fun wipScopePredicate(scope: SnapshotScope): Op<Boolean> {
    val w = MetricsTables.AggDailyWip
    return when (scope) {
        is SnapshotScope.Team -> (w.scopeKind eq SCOPE_KIND_TEAM) and (w.scopeId eq scope.teamId.toString())
        SnapshotScope.Unassigned -> (w.scopeKind eq SCOPE_KIND_TEAM) and (
            ((w.itemKind eq WIP_KIND_TASK) and (w.scopeId eq SCOPE_UNASSIGNED)) or
                ((w.itemKind eq WIP_KIND_EPIC) and (w.scopeId eq SCOPE_UNOWNED))
            )
        is SnapshotScope.Domain -> (w.scopeKind eq SCOPE_KIND_DOMAIN) and (w.scopeId eq scope.key)
    }
}

/** `agg_daily_flow` rows of [scope] — the backlog side, where the TEAM scope is the OWNER team, so `teamId=0` is UNOWNED. */
internal fun flowScopePredicate(scope: SnapshotScope): Op<Boolean> {
    val f = MetricsTables.AggDailyFlow
    return when (scope) {
        is SnapshotScope.Team -> (f.scopeKind eq SCOPE_KIND_TEAM) and (f.scopeId eq scope.teamId.toString())
        SnapshotScope.Unassigned -> (f.scopeKind eq SCOPE_KIND_TEAM) and (f.scopeId eq SCOPE_UNOWNED)
        is SnapshotScope.Domain -> (f.scopeKind eq SCOPE_KIND_DOMAIN) and (f.scopeId eq scope.key)
    }
}

/** `agg_daily_wip.item_kind` values. */
internal const val WIP_KIND_TASK = "TASK"
internal const val WIP_KIND_EPIC = "EPIC"

/**
 * How far the daily aggregates reach for a set of connections: [day] is the OLDEST, over the connections that have
 * derived, of the configured-zone day of their newest SUCCEEDED DERIVE run's start (`derive_runs.started_at` IS the run's
 * `now`, and both aggregates are written through the day of `now`) — so a lagging connection's missing days are never
 * read as zeros. `null` when no connection in scope has ever derived. [notDerived] are the in-scope connections with no
 * successful run: ignored for the cut-off (they have no rows either) but reported to the caller.
 */
internal data class DerivedCoverage(val day: LocalDate?, val notDerived: List<UInt>)

internal suspend fun derivedCoverage(connectionIds: List<UInt>, calendar: WorkingCalendar): DerivedCoverage {
    if (connectionIds.isEmpty()) return DerivedCoverage(null, emptyList())
    val newestByConnection = deriveClocks(connectionIds)
    val oldest = newestByConnection.values.minOrNull()
    return DerivedCoverage(oldest?.let { calendar.dayOf(it) }, connectionIds.filter { it !in newestByConnection })
}

/**
 * The DERIVE clock of each connection that has derived: its newest SUCCEEDED `derive_runs.started_at` (the run's `now` —
 * `MetricsDeriver.derive` stamps `startedAt = now`, the same instant it closes every still-open interval at). Connections
 * that never derived successfully are absent. One SQL `max()` per connection.
 */
internal suspend fun deriveClocks(connectionIds: List<UInt>): Map<UInt, Long> {
    if (connectionIds.isEmpty()) return emptyMap()
    val runs = MetricsTables.DeriveRuns
    val newestStart = runs.startedAt.max()
    return runs.select(runs.connectionId, newestStart)
        .where { (runs.status eq DERIVE_RUN_SUCCEEDED) and (runs.connectionId inList connectionIds.map { it.toInt() }) }
        .groupBy(runs.connectionId)
        .toList().mapNotNull { row -> row[newestStart]?.let { row[runs.connectionId].toUInt() to it } }.toMap()
}

/** The answer's days plus the [note] explaining an empty or partial one (`null` when there is nothing to say). */
internal data class SnapshotPlan(val days: List<LocalDate>, val note: String?)

internal const val NOT_DERIVED_NOTE = "Not derived yet: no connection in scope has a successful DERIVE run"
private const val UNASSIGNED_SPRINTS_NOTE = "teamId=0 (UNASSIGNED) resolves no sprint, so a sprint-relative period has nothing to read"
private const val NO_SPRINT_NOTE = "The sprint-relative period resolved no sprint, so there is nothing to read"

/**
 * The days a daily-snapshot report lists and the note it carries: USER level is empty by design with [userNote] (the
 * estimated backlog), or read like any other level when it is `null` (WIP answers USER level at query time, brief 04); no
 * successful DERIVE for any connection in scope is empty with "not derived yet" (never a series of zeros); otherwise
 * the period's days cut off at [derivedCoverage], with a note when no sprint resolved (`teamId=0` gets its own wording)
 * or when some connections in scope have never derived. With [startAtBackfill] (the WIP report, decision C) the series
 * also starts no earlier than the LATEST `backfill_from` among the derived connections in scope ([backfillStart]), and a
 * period that begins earlier says why in the note.
 */
internal suspend fun planSnapshotDays(
    scope: ReportScope,
    filter: ReportFilter,
    calendar: WorkingCalendar,
    userNote: String?,
    startAtBackfill: Boolean = false,
): SnapshotPlan {
    if (filter.level == ReportLevel.USER && userNote != null) return SnapshotPlan(emptyList(), userNote)
    val coverage = derivedCoverage(scope.connectionIds, calendar)
    val coveredThrough = coverage.day ?: return SnapshotPlan(emptyList(), NOT_DERIVED_NOTE)
    val notes = snapshotNotes(scope, filter, coverage).toMutableList()
    val start = if (startAtBackfill) backfillStart(scope.connectionIds - coverage.notDerived.toSet()) else null
    val window = scope.window
    if (start != null && window != null && window.second > window.first && calendar.dayOf(window.first).isBefore(start.date)) {
        notes += backfillNote(start)
    }
    return SnapshotPlan(snapshotDays(window, calendar, coveredThrough, start?.date), notes.joinToString(". ").ifEmpty { null })
}

/** The first day a WIP series may list: the LATEST `backfill_from` [date] of the [connections] in scope. */
internal data class BackfillStart(val date: LocalDate, val connections: Int)

/**
 * The latest `source_connections.backfill_from` among [connectionIds] (decision C: before it a connection's history is
 * incomplete by construction — items finished long before and not updated since were never fetched — so with several
 * connections in scope every shown day counts every one of them), `null` for none. A value that is not an ISO date is
 * ignored (the column is validated on write; this only keeps a hand-edited row from failing a report).
 */
internal suspend fun backfillStart(connectionIds: List<UInt>): BackfillStart? {
    if (connectionIds.isEmpty()) return null
    val c = DataSourceService.Connections
    val dates = c.select(c.backfillFrom).where { c.id inList connectionIds }.toList()
        .mapNotNull { runCatching { LocalDate.parse(it[c.backfillFrom]) }.getOrNull() }
    return dates.maxOrNull()?.let { BackfillStart(it, dates.size) }
}

private fun backfillNote(start: BackfillStart): String =
    if (start.connections == 1) {
        "Before ${start.date} the connection's history is incomplete (backfill window)"
    } else {
        "Before ${start.date} the history of at least one connection in scope is incomplete " +
            "(backfill window: the latest of their backfill dates)"
    }

/**
 * What makes a daily-aggregate answer empty or partial, in the order a client reads it: nothing derived at all
 * ([NOT_DERIVED_NOTE], alone), no sprint resolved for a sprint-relative period (`teamId=0` gets its own wording), and the
 * connections in scope that have never derived (named, and ignored for the cut-off). Shared by the snapshot reports and EVM.
 */
internal fun snapshotNotes(scope: ReportScope, filter: ReportFilter, coverage: DerivedCoverage): List<String> {
    if (coverage.day == null) return listOf(NOT_DERIVED_NOTE)
    return buildList {
        if (scope.window == null) add(if (filter.teamId == UNASSIGNED_TEAM_ID) UNASSIGNED_SPRINTS_NOTE else NO_SPRINT_NOTE)
        if (coverage.notDerived.isNotEmpty()) {
            val ids = coverage.notDerived.joinToString()
            add("Connection(s) $ids have no successful DERIVE run yet and are ignored for the last-derived-day cut-off")
        }
    }
}

/** Both snapshot reports read the TASK's own domain (D3 flow view) — `domainView=EPIC` is accepted, but the response says TASK. */
internal fun ReportMeta.forTaskDomain(): ReportMeta = copy(domainView = DomainView.TASK)

/**
 * The calendar days a daily-snapshot report lists, oldest first: every day of the item-anchored [window] (`[fromMs,
 * toMsExclusive)`, [periodWindow]), cut off after [coveredThrough] when the period reaches beyond the aggregates —
 * a day nothing was derived for yet is unknown, not zero (`.claude/docs/reports.md`). No window or an inverted one
 * is an empty list. [notBefore] (the backfill start) drops the days before it.
 */
internal fun snapshotDays(
    window: Pair<Long, Long>?,
    calendar: WorkingCalendar,
    coveredThrough: LocalDate?,
    notBefore: LocalDate? = null,
): List<LocalDate> {
    if (window == null || window.second <= window.first) return emptyList()
    val first = calendar.dayOf(window.first).let { if (notBefore != null && notBefore.isAfter(it)) notBefore else it }
    var last = calendar.dayOf(window.second - 1)
    if (coveredThrough != null && coveredThrough.isBefore(last)) last = coveredThrough
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}
