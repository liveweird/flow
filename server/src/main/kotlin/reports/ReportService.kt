package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `metrics.derive_runs.status`'s SUCCEEDED value — the plain-string idiom `MetricsStore`'s own readers
 * already use (no Kotlin enum wraps this CHECK-constrained column). Internal, not private — reused by
 * `VelocityReport.kt`'s own [ReportService.database]-scoped `derivedAt` read.
 */
internal const val DERIVE_RUN_SUCCEEDED = "SUCCEEDED"

/**
 * `reports/` package's read-only assembly of `GET /api/v1/reports/filters` (v0.3.0 M4 commit 10a,
 * plan §7) — the reference data every report's own filter bar/validation reads before a report is
 * even requested. Nothing here is paged (`.claude/docs/list-endpoints.md`'s bounded-admin-curated-set
 * exception: an active team/connection registry and a DERIVE run's own dimension rows are all small,
 * unbounded growth is not expected).
 *
 * **Three cross-feature reads**, all inside [filters]' own transaction, listed per
 * `.claude/docs/persistence.md`'s cross-feature-read rule: `teams/TeamService.Teams` (active teams),
 * `norm/WorkItemStore.People` (display names for a team's CURRENT Jira members) and
 * `ingest/DataSourceService.Connections` (id+name for active connections — never `settings`/the
 * encrypted API token).
 */
class ReportService(
    // internal, not private: `VelocityReport.kt`'s `ReportService.velocity()` extension (same
    // package, the `.claude/docs/reports.md` "past ~120 lines -> a new file" idiom) needs both.
    internal val database: R2dbcDatabase,
    internal val metricsConfig: MetricsConfigService,
    private val teamMembership: TeamMembershipService,
) {
    suspend fun filters(nowMs: Long): ReportFilters = suspendTransaction(database) {
        val settings = metricsConfig.read()

        val teamRows = TeamService.Teams.selectAll().where { TeamService.Teams.active() }
            .orderBy(TeamService.Teams.name)
            .toList()
        val teamIds = teamRows.map { it[TeamService.Teams.id].value }

        val sprintsByTeam = if (teamIds.isEmpty()) {
            emptyMap()
        } else {
            MetricsStore.DimSprint.selectAll()
                .where { MetricsStore.DimSprint.teamId inList teamIds }
                .toList()
                .groupBy { it[MetricsStore.DimSprint.teamId]!!.value }
        }

        // D1's CURRENT membership, one DB round trip per team (TeamMembershipService's own read) —
        // teams are few and admin-curated, so this stays cheap; a future perf pass could batch it.
        val membersByTeam = teamIds.associateWith { teamMembership.currentAccountIds(it, nowMs) }
        val allAccountIds = membersByTeam.values.flatten().toSet()
        val displayNameByAccountId = if (allAccountIds.isEmpty()) {
            emptyMap()
        } else {
            WorkItemStore.People.select(WorkItemStore.People.accountId, WorkItemStore.People.displayName)
                .where { WorkItemStore.People.accountId inList allAccountIds }
                // One account can appear under several connections: order so the pick is stable
                // (the lowest connection id's name wins, `associate` keeps the last duplicate).
                .orderBy(WorkItemStore.People.connectionId to SortOrder.DESC)
                .toList()
                .associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
        }

        val teams = teamRows.map { row ->
            val id = row[TeamService.Teams.id].value
            val sprints = sprintsByTeam[id].orEmpty()
                .map { it.toSprint() }
                .sortedByDescending { it.startAt ?: Long.MIN_VALUE }
            val members = membersByTeam[id].orEmpty().sorted()
                .map { accountId -> ReportFilterMember(accountId, displayNameByAccountId[accountId] ?: accountId) }
            ReportFilterTeam(id, row[TeamService.Teams.name], sprints, members)
        }

        val domains = MetricsStore.DimDomain.select(MetricsStore.DimDomain.domainKey, MetricsStore.DimDomain.name)
            .withDistinct()
            .orderBy(MetricsStore.DimDomain.domainKey)
            .toList()
            .distinctBy { it[MetricsStore.DimDomain.domainKey] }
            .map { ReportFilterDomain(it[MetricsStore.DimDomain.domainKey], it[MetricsStore.DimDomain.name]) }

        val activityTypes = MetricsStore.DimTask.select(MetricsStore.DimTask.activityType)
            .withDistinct()
            .toList()
            .map { it[MetricsStore.DimTask.activityType] }
            .distinct().sorted()

        val taskWorkCategories = MetricsStore.DimTask.select(MetricsStore.DimTask.workCategory)
            .where { MetricsStore.DimTask.workCategory.isNotNull() }
            .withDistinct()
            .toList().mapNotNull { it[MetricsStore.DimTask.workCategory] }
        val epicWorkCategories = MetricsStore.DimEpic.select(MetricsStore.DimEpic.workCategory)
            .where { MetricsStore.DimEpic.workCategory.isNotNull() }
            .withDistinct()
            .toList().mapNotNull { it[MetricsStore.DimEpic.workCategory] }
        val workCategories = (taskWorkCategories + epicWorkCategories).distinct().sorted()

        // Active only — NOT `enabled` too: a connection an admin has paused from syncing still owns
        // whatever it already derived, and its history stays a legitimate filter choice.
        val connections = DataSourceService.Connections.select(DataSourceService.Connections.id, DataSourceService.Connections.name)
            .where { DataSourceService.Connections.active() }
            .orderBy(DataSourceService.Connections.name)
            .toList()
            .map { ReportFilterConnection(it[DataSourceService.Connections.id].value, it[DataSourceService.Connections.name]) }

        val derivedAt = MetricsStore.DeriveRuns.select(MetricsStore.DeriveRuns.finishedAt)
            .where { MetricsStore.DeriveRuns.status eq DERIVE_RUN_SUCCEEDED }
            .toList()
            .mapNotNull { it[MetricsStore.DeriveRuns.finishedAt] }
            .maxOrNull()

        ReportFilters(
            teams = teams,
            domains = domains,
            activityTypes = activityTypes,
            workCategories = workCategories,
            connections = connections,
            derivedAt = derivedAt,
            configRevision = settings.configRevision,
            minSampleSize = settings.minSampleSize,
            timeZone = settings.timeZone,
        )
    }

    private fun ResultRow.toSprint(): ReportFilterSprint = ReportFilterSprint(
        sprintId = this[MetricsStore.DimSprint.sprintId],
        name = this[MetricsStore.DimSprint.name],
        state = this[MetricsStore.DimSprint.state],
        startAt = this[MetricsStore.DimSprint.startAt],
        completeAt = this[MetricsStore.DimSprint.completeAt],
    )
}
