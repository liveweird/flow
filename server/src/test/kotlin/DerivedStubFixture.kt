package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsBoardTeamMapping
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.assertEquals

/**
 * A suite-wide, derive-once fixture (`.claude/docs/testing.md` "Shared synced fixture" — "The
 * derived fixture"): a CHEAP [SyncedStubFixture.cloneProcessedData] clone of the shared synced
 * connection, the FLO board (id 1) mapped to one freshly seeded team, derived exactly ONCE per JVM
 * fork (a `Mutex`-guarded lazy init, [SyncedStubFixture.connectionId]'s own shape) under
 * [PINNED_NOW] and `hoursPerDay = 8.0` — the SAME pinned-clock convention `MetricsDerivationTest`
 * itself uses for the tests that keep their own clone.
 *
 * Every `MetricsDerivationTest` assertion that only READS the result of a single DERIVE under the
 * computed DEFAULT per-connection config (no config/membership/settings mutation, one derive only)
 * reads THIS connection instead of deriving its own — mirroring [SyncedStubFixture]'s own read-only
 * rule for the sync pipeline. A test whose SUBJECT is a second derive, a config/membership/settings
 * mutation, or a raw-row simulation must clone its OWN connection via
 * [SyncedStubFixture.cloneProcessedData] instead — never touch this one; [assertUnchanged] (driven
 * by `DerivedStubFixtureTest`) is the tripwire.
 */
object DerivedStubFixture {
    /** 2026-03-05T00:00:00Z — the v0.3.0 plan's own pinned-clock convention; matches `MetricsDerivationTest`'s private constant. */
    const val PINNED_NOW = 1_772_668_800_000L
    private const val HOURS_PER_DAY = 8.0
    private const val FLO_BOARD_ID = 1L

    private val initLock = Mutex()

    @Volatile
    private var derivedConnectionId: UInt? = null

    private lateinit var baselineDigest: String

    private fun metricsConfig() = MetricsConfigService(
        sharedDatabaseForTests(),
        SyncedStubFixture.workItems(),
        SyncedStubFixture.dataSources(),
        SyncJobsService(sharedDatabaseForTests(), 3),
    )

    private fun teamMembership(config: MetricsConfigService) = TeamMembershipService(sharedDatabaseForTests(), config)

    private fun deriver(config: MetricsConfigService) = MetricsDeriver(
        SyncedStubFixture.workItems(),
        config,
        teamMembership(config),
        MetricsStore(sharedDatabaseForTests()),
        sharedDatabaseForTests(),
    )

    private fun deriveClaim(connId: UInt) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = SyncJobKind.DERIVE,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    /**
     * Runs the cheap processed clone + FLO-board mapping + one pinned DERIVE exactly once per JVM
     * fork and returns its connection id — idempotent under concurrent callers, the same
     * double-checked-lock shape as [SyncedStubFixture.connectionId].
     */
    suspend fun connectionId(): UInt {
        derivedConnectionId?.let { return it }
        return initLock.withLock {
            derivedConnectionId?.let { return@withLock it }
            SyncedStubFixture.ensureMigrated()
            val sourceConnId = SyncedStubFixture.connectionId()
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-derived-fixture", enabled = false)
            SyncedStubFixture.cloneProcessedData(sourceConnId, connId)

            val config = metricsConfig()
            val teamId = TestTeams.seed(SyncedStubFixture.unique("flo-derived-team"))
            // Preserves the COMPUTED defaults (estimate field detection etc.) — a bare replaceConfig
            // with only `boards` set would otherwise wipe every other field back to its bare
            // unconfigured null, since a stored config is a FULL replace, not a patch (the same
            // reason `MetricsDerivationTest.mapFloBoardToTeam` reads `effectiveConfig` first).
            val current = config.effectiveConfig(connId)
            config.replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields,
                    domains = current.domains,
                    boards = listOf(MetricsBoardTeamMapping(FLO_BOARD_ID, teamId)),
                    activityTypes = current.activityTypes,
                    workCategories = current.workCategories,
                    blockedStatuses = current.blockedStatuses,
                    sprintCapacities = current.sprintCapacities,
                ),
            )

            withMetricsSettings(config, { it.copy(hoursPerDay = HOURS_PER_DAY) }) {
                deriver(config).derive(SyncJobRunContext(deriveClaim(connId), clock = { PINNED_NOW }) { _, _ -> true })
            }

            baselineDigest = digest(connId)
            derivedConnectionId = connId
            connId
        }
    }

    /** Guard against accidental mutation of the shared derived connection — driven by `DerivedStubFixtureTest`. */
    suspend fun assertUnchanged() {
        val connId = connectionId()
        assertEquals(
            baselineDigest,
            digest(connId),
            "the shared derived fixture's connection $connId must never be mutated by a read-only test",
        )
    }

    /**
     * MD5 over every persisted `fact_task_delivery` row PLUS every TASK bridge row (`task_epic`/
     * `task_domain`/`task_assignee`) — the reprocess-digest pattern (`.claude/docs/testing.md` "The
     * reprocess digest"), moved here from `MetricsDerivationTest` so both this fixture's own
     * tripwire digest and the test's own "a second DERIVE writes identical rows" case
     * (`invariant 12-lite`) share one implementation. Ordered by issue id (bridges additionally by
     * their own `valid_from`, preserving each issue's own history order); the surrogate `id` column
     * is excluded from every hashed line — it is a fresh `autoIncrement()` value on every DERIVE's
     * delete+insert and would make the digest spuriously differ across two otherwise-identical runs.
     */
    suspend fun factTaskDeliveryDigest(connId: UInt): String {
        val digest = MessageDigest.getInstance("MD5")
        suspendTransaction(sharedDatabaseForTests()) {
            digest.hashRows(
                MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }
                    .orderBy(MetricsStore.FactTaskDelivery.issueId to SortOrder.ASC)
                    .toList(),
                MetricsStore.FactTaskDelivery.columns,
            )
            digest.hashRows(
                MetricsStore.TaskEpic.selectAll().where { MetricsStore.TaskEpic.connectionId eq connId }
                    .orderBy(MetricsStore.TaskEpic.issueId to SortOrder.ASC, MetricsStore.TaskEpic.validFrom to SortOrder.ASC)
                    .toList(),
                listOf(
                    MetricsStore.TaskEpic.issueId,
                    MetricsStore.TaskEpic.epicId,
                    MetricsStore.TaskEpic.validFrom,
                    MetricsStore.TaskEpic.validTo,
                ),
            )
            digest.hashRows(
                MetricsStore.TaskDomain.selectAll().where { MetricsStore.TaskDomain.connectionId eq connId }
                    .orderBy(MetricsStore.TaskDomain.issueId to SortOrder.ASC, MetricsStore.TaskDomain.validFrom to SortOrder.ASC)
                    .toList(),
                listOf(
                    MetricsStore.TaskDomain.issueId,
                    MetricsStore.TaskDomain.domainKey,
                    MetricsStore.TaskDomain.validFrom,
                    MetricsStore.TaskDomain.validTo,
                ),
            )
            digest.hashRows(
                MetricsStore.TaskAssignee.selectAll().where { MetricsStore.TaskAssignee.connectionId eq connId }
                    .orderBy(MetricsStore.TaskAssignee.issueId to SortOrder.ASC, MetricsStore.TaskAssignee.validFrom to SortOrder.ASC)
                    .toList(),
                listOf(
                    MetricsStore.TaskAssignee.issueId,
                    MetricsStore.TaskAssignee.accountId,
                    MetricsStore.TaskAssignee.validFrom,
                    MetricsStore.TaskAssignee.validTo,
                ),
            )
        }
        return digest.hex()
    }

    /**
     * Extends [factTaskDeliveryDigest] with `fact_sprint`/`fact_sprint_scope`/`fact_worklog` —
     * none of the three carries a surrogate id (all three key on real, composite natural columns
     * — see `metrics/MetricsStore.kt`), so every column is hashed as-is.
     */
    private suspend fun digest(connId: UInt): String {
        val sprintDigest = MessageDigest.getInstance("MD5")
        suspendTransaction(sharedDatabaseForTests()) {
            sprintDigest.hashRows(
                MetricsStore.FactSprint.selectAll().where { MetricsStore.FactSprint.connectionId eq connId }
                    .orderBy(MetricsStore.FactSprint.sprintId to SortOrder.ASC)
                    .toList(),
                MetricsStore.FactSprint.columns,
            )
            sprintDigest.hashRows(
                MetricsStore.FactSprintScope.selectAll().where { MetricsStore.FactSprintScope.connectionId eq connId }
                    .orderBy(MetricsStore.FactSprintScope.sprintId to SortOrder.ASC, MetricsStore.FactSprintScope.issueId to SortOrder.ASC)
                    .toList(),
                MetricsStore.FactSprintScope.columns,
            )
            sprintDigest.hashRows(
                MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }
                    .orderBy(MetricsStore.FactWorklog.worklogId to SortOrder.ASC)
                    .toList(),
                MetricsStore.FactWorklog.columns,
            )
            // `fact_epic_plan` (v0.3.0 M3 commit 9b) carries a surrogate `id` like `fact_task_delivery`'s
            // own bridges above — excluded from the hashed columns for the same reason; ordered by its
            // own natural key `(issue_id, baseline_seq)`.
            sprintDigest.hashRows(
                MetricsStore.FactEpicPlan.selectAll().where { MetricsStore.FactEpicPlan.connectionId eq connId }
                    .orderBy(MetricsStore.FactEpicPlan.issueId to SortOrder.ASC, MetricsStore.FactEpicPlan.baselineSeq to SortOrder.ASC)
                    .toList(),
                listOf(
                    MetricsStore.FactEpicPlan.issueId,
                    MetricsStore.FactEpicPlan.baselineSeq,
                    MetricsStore.FactEpicPlan.baselinedAt,
                    MetricsStore.FactEpicPlan.startAt,
                    MetricsStore.FactEpicPlan.dueAt,
                    MetricsStore.FactEpicPlan.budgetMd,
                    MetricsStore.FactEpicPlan.budgetSource,
                    MetricsStore.FactEpicPlan.supersededAt,
                ),
            )
            // `fact_epic_delivery` (v0.3.0 M3 commit 9d/9e, A19/A22's `owner_team_id`) — PK
            // `(connection_id, issue_id)`, no surrogate id, ordered by issue id.
            sprintDigest.hashRows(
                MetricsStore.FactEpicDelivery.selectAll().where { MetricsStore.FactEpicDelivery.connectionId eq connId }
                    .orderBy(MetricsStore.FactEpicDelivery.issueId to SortOrder.ASC)
                    .toList(),
                MetricsStore.FactEpicDelivery.columns,
            )
            // `dim_domain` (V17, A19/A22's `owner_team_id`) — PK `(connection_id, domain_key)`, no
            // surrogate id, ordered by domain key (its own natural key).
            sprintDigest.hashRows(
                MetricsStore.DimDomain.selectAll().where { MetricsStore.DimDomain.connectionId eq connId }
                    .orderBy(MetricsStore.DimDomain.domainKey to SortOrder.ASC)
                    .toList(),
                MetricsStore.DimDomain.columns,
            )
            // `agg_daily_wip` (v0.3.0 M3 commit 9f, report 9) — no surrogate id, PK
            // `(connection_id, scope_kind, scope_id, day, item_kind, status_id, stage)`, ordered by
            // that same natural key.
            sprintDigest.hashRows(
                MetricsStore.AggDailyWip.selectAll().where { MetricsStore.AggDailyWip.connectionId eq connId }
                    .orderBy(
                        MetricsStore.AggDailyWip.scopeKind to SortOrder.ASC,
                        MetricsStore.AggDailyWip.scopeId to SortOrder.ASC,
                        MetricsStore.AggDailyWip.day to SortOrder.ASC,
                        MetricsStore.AggDailyWip.itemKind to SortOrder.ASC,
                        MetricsStore.AggDailyWip.statusId to SortOrder.ASC,
                        MetricsStore.AggDailyWip.stage to SortOrder.ASC,
                    )
                    .toList(),
                MetricsStore.AggDailyWip.columns,
            )
        }
        return "${factTaskDeliveryDigest(connId)}:${sprintDigest.hex()}"
    }

    private fun MessageDigest.hashRows(rows: List<ResultRow>, columns: List<Column<*>>) {
        rows.forEach { row ->
            val line = columns.joinToString("|") { row[it].toString() }
            update((line + "\n").toByteArray())
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
}
