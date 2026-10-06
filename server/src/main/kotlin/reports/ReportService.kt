package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `metrics.derive_runs.status`'s SUCCEEDED value — the plain-string idiom `MetricsStore`'s own readers
 * already use (no Kotlin enum wraps this CHECK-constrained column). Internal, not private — reused by
 * `VelocityReport.kt`'s own [ReportService.database]-scoped `derivedAt` read.
 */
internal const val DERIVE_RUN_SUCCEEDED = "SUCCEEDED"

/** The default `reports.statementTimeoutSeconds`: generous for the heaviest legitimate report, far below "forever". */
const val DEFAULT_REPORT_STATEMENT_TIMEOUT_SECONDS: Int = 30

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
    internal val metricsSettings: MetricsSettingsService,
    private val teamMembership: TeamMembershipService,
    /** The per-statement budget [reportTransaction] applies, whole seconds (`reports.statementTimeoutSeconds`). */
    private val statementTimeoutSeconds: Int = DEFAULT_REPORT_STATEMENT_TIMEOUT_SECONDS,
) {
    /**
     * The ONE transaction every report read opens (checkup 2A3): a plain `suspendTransaction` under a PostgreSQL
     * statement timeout, so a pathological selection (500 tasks x 1100 days) is cancelled by the server (SQLSTATE
     * 57014, answered by `plugins/ErrorHandling.kt`) instead of holding one of the pool's connections indefinitely.
     * Never open a report read with a bare `suspendTransaction` — it would silently run unbudgeted.
     *
     * The budget is Exposed's own `queryTimeout` (WHOLE SECONDS), not a hand-rolled `SET LOCAL statement_timeout`:
     * Exposed's R2DBC executor re-applies the transaction's `queryTimeout` (default 0 = none; the getter never
     * returns null) with a session-level `SET statement_timeout` before EVERY statement, which silently undoes a
     * `SET LOCAL statement_timeout` (measured: `SHOW statement_timeout` read back `0` right after it, while
     * `SET LOCAL work_mem`/`lock_timeout` survived). Because the same executor resets it to 0 before the next
     * borrower's first statement, the value never leaks to another caller of the pool (`ReportQueryBudgetTest`).
     */
    internal suspend fun <T> reportTransaction(block: suspend R2dbcTransaction.() -> T): T =
        suspendTransaction(database) {
            queryTimeout = statementTimeoutSeconds
            block()
        }

    suspend fun filters(nowMs: Long): ReportFilters = reportTransaction {
        val settings = metricsSettings.read()

        val teamRows = TeamService.Teams.selectAll().where { TeamService.Teams.active() }
            .orderBy(TeamService.Teams.name)
            .toList()
        val teamIds = teamRows.map { it[TeamService.Teams.id].value }

        val sprintsByTeam = if (teamIds.isEmpty()) {
            emptyMap()
        } else {
            MetricsTables.DimSprint.selectAll()
                .where { MetricsTables.DimSprint.teamId inList teamIds }
                .toList()
                .groupBy { it[MetricsTables.DimSprint.teamId]!!.value }
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

        val domains = MetricsTables.DimDomain.select(MetricsTables.DimDomain.domainKey, MetricsTables.DimDomain.name)
            .withDistinct()
            .orderBy(MetricsTables.DimDomain.domainKey)
            .toList()
            .distinctBy { it[MetricsTables.DimDomain.domainKey] }
            .map { ReportFilterDomain(it[MetricsTables.DimDomain.domainKey], it[MetricsTables.DimDomain.name]) }

        val activityTypes = MetricsTables.DimTask.select(MetricsTables.DimTask.activityType)
            .withDistinct()
            .toList()
            .map { it[MetricsTables.DimTask.activityType] }
            .distinct().sorted()

        val taskWorkCategories = MetricsTables.DimTask.select(MetricsTables.DimTask.workCategory)
            .where { MetricsTables.DimTask.workCategory.isNotNull() }
            .withDistinct()
            .toList().mapNotNull { it[MetricsTables.DimTask.workCategory] }
        val epicWorkCategories = MetricsTables.DimEpic.select(MetricsTables.DimEpic.workCategory)
            .where { MetricsTables.DimEpic.workCategory.isNotNull() }
            .withDistinct()
            .toList().mapNotNull { it[MetricsTables.DimEpic.workCategory] }
        val workCategories = (taskWorkCategories + epicWorkCategories).distinct().sorted()

        // Active only — NOT `enabled` too: a connection an admin has paused from syncing still owns
        // whatever it already derived, and its history stays a legitimate filter choice.
        val connections = DataSourceService.Connections.select(DataSourceService.Connections.id, DataSourceService.Connections.name)
            .where { DataSourceService.Connections.active() }
            .orderBy(DataSourceService.Connections.name)
            .toList()
            .map { ReportFilterConnection(it[DataSourceService.Connections.id].value, it[DataSourceService.Connections.name]) }

        val stamp = deriveStamp(connections.map { it.id })

        ReportFilters(
            teams = teams,
            domains = domains,
            activityTypes = activityTypes,
            workCategories = workCategories,
            connections = connections,
            derivedAt = stamp.derivedAt,
            configRevision = stamp.configRevision,
            minSampleSize = settings.minSampleSize,
            timeZone = settings.timeZone,
        )
    }

    private fun ResultRow.toSprint(): ReportFilterSprint = ReportFilterSprint(
        sprintId = this[MetricsTables.DimSprint.sprintId],
        name = this[MetricsTables.DimSprint.name],
        state = this[MetricsTables.DimSprint.state],
        startAt = this[MetricsTables.DimSprint.startAt],
        completeAt = this[MetricsTables.DimSprint.completeAt],
    )
}
