package ch.nokillswit.reports

import kotlinx.serialization.Serializable

/**
 * One team's resolved sprint set for a `lastSprints`/`sprintId`-selected period (plan §7
 * `meta.resolvedSprints`) — e.g. `lastSprints=3` at UNIT level resolves to EACH team's own last 3
 * closed sprints, not one shared window (`.claude/docs/domain-model.md`: "sprint-relative periods
 * … are per team"). `teamId = null` never appears here — every entry names a real team; a report
 * with nothing to resolve (an explicit `from`/`to` period) reports an empty list.
 */
@Serializable
data class ResolvedSprintGroup(val teamId: UInt, val sprintIds: List<Long>)

/**
 * The `meta` block every report response carries beside its own body (plan §7): [derivedAt] is the
 * latest SUCCEEDED `derive_runs.finished_at` across the connection(s) the report actually read (all
 * enabled connections, or just [ReportFilter.connectionId] when the caller narrowed to one) — `null`
 * before any connection has ever completed a DERIVE. [configRevision] is the `config_revision` the served
 * figures were DERIVED under (invariant 12: "every live number is reproducible from `norm` + one
 * configuration revision") — the oldest of the scoped connections' newest SUCCEEDED runs, see [DeriveStamp];
 * `null` with [derivedAt] before any DERIVE. It is NOT the live `metrics.settings.config_revision`, which
 * runs ahead of the data between a configuration change and its DERIVE. [minSampleSize] is carried
 * alongside so a client never has to make its own separate `/reports/filters` round trip just to
 * label a [Distribution]'s `hidden` state.
 */
@Serializable
data class ReportMeta(
    val derivedAt: Long?,
    val configRevision: Long?,
    val from: String?,
    val to: String?,
    val level: ReportLevel,
    val domainView: DomainView,
    val resolvedSprints: List<ResolvedSprintGroup>,
    val minSampleSize: Int,
)

/**
 * Assembles [ReportMeta] from an already-parsed [ReportFilter] plus the figures a report's own
 * service resolves from the database ([derivedAt]/[configRevision]/[resolvedSprints]) — pure, no DB
 * access of its own. `from`/`to` are populated only for a [ReportPeriod.DateRange] filter; a
 * `lastSprints`/`sprintId` period carries no calendar range of its own, so both stay `null` and the
 * period is instead described entirely by [resolvedSprints].
 */
fun ReportFilter.toMeta(
    derivedAt: Long?,
    configRevision: Long?,
    minSampleSize: Int,
    resolvedSprints: List<ResolvedSprintGroup> = emptyList(),
): ReportMeta {
    val (from, to) = when (val resolvedPeriod = period) {
        is ReportPeriod.DateRange -> resolvedPeriod.fromDate.toString() to resolvedPeriod.toDate.toString()
        is ReportPeriod.LastSprints, is ReportPeriod.BySprintId -> null to null
    }
    return ReportMeta(
        derivedAt = derivedAt,
        configRevision = configRevision,
        from = from,
        to = to,
        level = level,
        domainView = domainView,
        resolvedSprints = resolvedSprints,
        minSampleSize = minSampleSize,
    )
}
