package ch.nokillswit.reports

import kotlinx.serialization.Serializable

/** One `metrics.dim_sprint` row, named for a report's own sprint picker — empty for a team until at least one DERIVE has run. */
@Serializable
data class ReportFilterSprint(val sprintId: Long, val name: String, val state: String, val startAt: Long?, val completeAt: Long?)

/** One team's CURRENT (as of the request) Jira member — D1, paired with a display name (`norm.people`). */
@Serializable
data class ReportFilterMember(val accountId: String, val displayName: String)

/** One active team, with the sprints its board has ever produced and its current Jira roster. */
@Serializable
data class ReportFilterTeam(
    val id: UInt,
    val name: String,
    val sprints: List<ReportFilterSprint>,
    val members: List<ReportFilterMember>,
)

/**
 * One domain actually observed by a DERIVE run (`metrics.dim_domain`) — `domainName` may read
 * differently than `domainKey` once an admin renames it.
 */
@Serializable
data class ReportFilterDomain(val domainKey: String, val domainName: String)

/** One active connection — id and name ONLY, never `settings`/the encrypted token (`.claude/docs/security.md`). */
@Serializable
data class ReportFilterConnection(val id: UInt, val name: String)

/**
 * `GET /api/v1/reports/filters` (v0.3.0 M4 commit 10a, plan §7) — the reference data every report's
 * own filter bar reads before a report is even requested: any signed-in user (D12), read-only, no
 * query params. `domains`/`activityTypes`/`workCategories` are the values a real DERIVE run has
 * ACTUALLY produced (`metrics.dim_domain`/`dim_task`/`dim_epic`) — never every value a per-connection
 * config COULD map to, since an admin's configured-but-unused mapping would otherwise clutter a
 * filter dropdown with a value no report can ever return a row for.
 */
@Serializable
data class ReportFilters(
    val teams: List<ReportFilterTeam>,
    val domains: List<ReportFilterDomain>,
    val activityTypes: List<String>,
    val workCategories: List<String>,
    val connections: List<ReportFilterConnection>,
    val derivedAt: Long?,
    /** The revision the served figures were derived under (the oldest connection's, see [DeriveStamp]); `null` before any DERIVE. */
    val configRevision: Long?,
    val minSampleSize: Int,
    /** `metrics.settings.time_zone` (IANA) — the zone `from`/`to` are read in, so a client renders and defaults dates in it. */
    val timeZone: String,
)
