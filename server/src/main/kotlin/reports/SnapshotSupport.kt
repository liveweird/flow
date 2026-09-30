package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsStore
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
    /** UNIT level: every TEAM scope summed — including UNASSIGNED (tasks) and UNOWNED (epics, backlog). */
    data object AllTeams : SnapshotScope

    /** TEAM level: one team's own TEAM scope. */
    data class Team(val teamId: UInt) : SnapshotScope

    /** `teamId=0`: UNASSIGNED tasks, UNOWNED epics / backlog. */
    data object Unassigned : SnapshotScope

    /** UNIT level narrowed by `domain`: the DOMAIN scope of that key (the task's as-was domain, or an epic's own). */
    data class Domain(val key: String) : SnapshotScope
}

/**
 * The scope [filter] selects, or `400` for a combination the aggregates cannot answer: `activityType` and
 * `workCategory` are not stored per day, and `domain` cannot combine with `teamId` (no team × domain split).
 * `breakdown` and `domainView` are accepted and ignored, as in the other reports. USER level (`accountId`) is not
 * rejected here — the report answers it empty, with a documented note — but the same combinations still 400.
 */
internal fun snapshotScopeOf(filter: ReportFilter): SnapshotScope {
    if (filter.activityType != null || filter.workCategory != null) {
        throw BadRequestException("activityType and workCategory are not available for the daily WIP/backlog aggregates")
    }
    val teamId = filter.teamId
    val domain = filter.domain
    if (domain != null && teamId != null) {
        throw BadRequestException("domain cannot be combined with teamId for the daily WIP/backlog aggregates (no team x domain split)")
    }
    return when {
        domain != null -> SnapshotScope.Domain(domain)
        teamId == null -> SnapshotScope.AllTeams
        teamId == UNASSIGNED_TEAM_ID -> SnapshotScope.Unassigned
        else -> SnapshotScope.Team(teamId)
    }
}

/** `agg_daily_wip` rows of [scope]; [Unassigned][SnapshotScope.Unassigned] is UNASSIGNED on the task side and UNOWNED on the epic side. */
internal fun wipScopePredicate(scope: SnapshotScope): Op<Boolean> {
    val w = MetricsStore.AggDailyWip
    return when (scope) {
        SnapshotScope.AllTeams -> w.scopeKind eq SCOPE_KIND_TEAM
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
    val f = MetricsStore.AggDailyFlow
    return when (scope) {
        SnapshotScope.AllTeams -> f.scopeKind eq SCOPE_KIND_TEAM
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
    val runs = MetricsStore.DeriveRuns
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
 * The days a daily-snapshot report lists and the note it carries: USER level is empty by design ([userNote]); no
 * successful DERIVE for any connection in scope is empty with "not derived yet" (never a series of zeros); otherwise
 * the period's days cut off at [derivedCoverage], with a note when no sprint resolved (`teamId=0` gets its own wording)
 * or when some connections in scope have never derived.
 */
internal suspend fun planSnapshotDays(scope: ReportScope, filter: ReportFilter, calendar: WorkingCalendar, userNote: String): SnapshotPlan {
    if (filter.level == ReportLevel.USER) return SnapshotPlan(emptyList(), userNote)
    val coverage = derivedCoverage(scope.connectionIds, calendar)
    val coveredThrough = coverage.day ?: return SnapshotPlan(emptyList(), NOT_DERIVED_NOTE)
    val notes = snapshotNotes(scope, filter, coverage)
    return SnapshotPlan(snapshotDays(scope.window, calendar, coveredThrough), notes.joinToString(". ").ifEmpty { null })
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
 * is an empty list.
 */
internal fun snapshotDays(window: Pair<Long, Long>?, calendar: WorkingCalendar, coveredThrough: LocalDate?): List<LocalDate> {
    if (window == null || window.second <= window.first) return emptyList()
    val first = calendar.dayOf(window.first)
    var last = calendar.dayOf(window.second - 1)
    if (coveredThrough != null && coveredThrough.isBefore(last)) last = coveredThrough
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}
