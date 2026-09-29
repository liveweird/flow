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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.Query
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

    fun metricsConfig() = MetricsConfigService(
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

    private fun deriveClaim(connId: UInt, jobId: UInt) = SyncJobClaim(
        id = jobId,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = SyncJobKind.DERIVE,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    /**
     * Maps the FLO board (id 1) to a freshly seeded team on [connId] through ONE full-replace PUT
     * that preserves the COMPUTED defaults (estimate field detection etc.) — a bare `replaceConfig`
     * with only `boards` set would otherwise wipe every other field back to its bare unconfigured
     * null, since a stored config is a FULL replace, not a patch (the same reason
     * `MetricsDerivationTest.mapFloBoardToTeam` reads `effectiveConfig` first). Returns the team id.
     */
    suspend fun mapFloBoardToNewTeam(connId: UInt, config: MetricsConfigService, teamPrefix: String): UInt {
        val teamId = TestTeams.seed(SyncedStubFixture.unique(teamPrefix))
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
        return teamId
    }

    /**
     * ONE DERIVE of [connId] under [PINNED_NOW] — the caller owns the surrounding
     * `withMetricsSettings { }` (`hoursPerDay = 8.0`): wrapping each call separately would bump
     * `metrics.settings.config_revision` twice per call, and every derived row is stamped with the
     * revision, so two derives that must compare equal share ONE wrapper.
     */
    suspend fun derivePinned(connId: UInt, config: MetricsConfigService, jobId: UInt = 1u) {
        deriver(config).derive(SyncJobRunContext(deriveClaim(connId, jobId), clock = { PINNED_NOW }) { _, _ -> true })
    }

    /** [withMetricsSettings] with the fixture's pinned `hoursPerDay = 8.0`. */
    suspend fun <T> withPinnedSettings(config: MetricsConfigService, block: suspend () -> T): T =
        withMetricsSettings(config, { it.copy(hoursPerDay = HOURS_PER_DAY) }, block)

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
            mapFloBoardToNewTeam(connId, config, "flo-derived-team")
            withPinnedSettings(config) { derivePinned(connId, config) }

            baselineDigest = metricsDigest(connId)
            derivedConnectionId = connId
            connId
        }
    }

    /** Guard against accidental mutation of the shared derived connection — driven by `DerivedStubFixtureTest`. */
    suspend fun assertUnchanged() {
        val connId = connectionId()
        assertEquals(
            baselineDigest,
            metricsDigest(connId),
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
                    .orderBy(
                        // A total order: zero-length intervals tie on (issue, valid_from), and a
                        // tie's physical row order is not stable across runs.
                        MetricsStore.TaskEpic.issueId to SortOrder.ASC,
                        MetricsStore.TaskEpic.validFrom to SortOrder.ASC,
                        MetricsStore.TaskEpic.validTo to SortOrder.ASC_NULLS_LAST,
                        MetricsStore.TaskEpic.epicId to SortOrder.ASC_NULLS_LAST,
                    )
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
                    .orderBy(
                        // A total order: zero-length intervals tie on (issue, valid_from), and a
                        // tie's physical row order is not stable across runs.
                        MetricsStore.TaskDomain.issueId to SortOrder.ASC,
                        MetricsStore.TaskDomain.validFrom to SortOrder.ASC,
                        MetricsStore.TaskDomain.validTo to SortOrder.ASC_NULLS_LAST,
                        MetricsStore.TaskDomain.domainKey to SortOrder.ASC_NULLS_LAST,
                    )
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
                    .orderBy(
                        // A total order: zero-length intervals tie on (issue, valid_from), and a
                        // tie's physical row order is not stable across runs.
                        MetricsStore.TaskAssignee.issueId to SortOrder.ASC,
                        MetricsStore.TaskAssignee.validFrom to SortOrder.ASC,
                        MetricsStore.TaskAssignee.validTo to SortOrder.ASC_NULLS_LAST,
                        MetricsStore.TaskAssignee.accountId to SortOrder.ASC_NULLS_LAST,
                    )
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

    /** One table's slice of [metricsDigest]: the connection-scoping predicate and any bookkeeping columns left out of the hash. */
    private class DigestSpec(val table: Table, val scope: Op<Boolean>, val exclude: Set<Column<*>> = emptySet())

    /**
     * MD5 over EVERY derived `metrics.*` table for one connection (invariant 12's proof — the
     * reprocess-digest pattern, `.claude/docs/testing.md`): the dimensions, every bridge, both
     * accumulating facts, the sprint facts, worklog and epic-plan facts and both daily aggregates —
     * plus, only when [includeDimDate] is set, the `dim_date` days the connection's own WIP
     * aggregate spans. `dim_date` is GLOBAL and every DERIVE by ANY connection upserts it stamped
     * with the then-current `config_revision`, so it is opt-in: only a caller whose derives all sit
     * inside one settings wrapper in a sequential suite (`MetricsDigestTest`) may hash it — the
     * shared-fixture tripwire must not, or it would go red whenever another deriving test ran first.
     * Left out on purpose:
     *
     * - `derive_runs` (run bookkeeping — a second DERIVE legitimately adds a row);
     * - the surrogate `id` column of every bridge/`fact_epic_plan` (a fresh `autoIncrement()` value
     *   on every DERIVE's delete+insert would make two identical runs spuriously differ);
     * - `fact_sprint_snapshot.snapshot_at`/`reconstructed` (write-time bookkeeping of an append-only
     *   row; every OTHER snapshot column — the frozen figures, the scope JSON, the config revision —
     *   is hashed). NOTE the snapshot slice is trivially equal across re-derives (append-only, never
     *   rewritten): the LIVE `fact_sprint` is what proves the frozen figures are reproducible.
     *
     * Row order is deterministic: the table's own primary key where it has a real one, else every
     * hashed column in declaration order (the surrogate-id tables have no natural key of their own,
     * so their whole row is the key). Each table contributes a `#name/rowCount` header line, so an
     * empty table can never be mistaken for a shifted neighbour.
     */
    suspend fun metricsDigest(connId: UInt, includeDimDate: Boolean = false): String {
        val digest = MessageDigest.getInstance("MD5")
        val specs = listOf(
            DigestSpec(MetricsStore.DimDomain, MetricsStore.DimDomain.connectionId eq connId),
            DigestSpec(MetricsStore.DimTask, MetricsStore.DimTask.connectionId eq connId),
            DigestSpec(MetricsStore.DimEpic, MetricsStore.DimEpic.connectionId eq connId),
            DigestSpec(MetricsStore.DimSprint, MetricsStore.DimSprint.connectionId eq connId),
            DigestSpec(MetricsStore.TaskEpic, MetricsStore.TaskEpic.connectionId eq connId),
            DigestSpec(MetricsStore.TaskDomain, MetricsStore.TaskDomain.connectionId eq connId),
            DigestSpec(MetricsStore.TaskAssignee, MetricsStore.TaskAssignee.connectionId eq connId),
            DigestSpec(MetricsStore.TaskSprint, MetricsStore.TaskSprint.connectionId eq connId),
            DigestSpec(MetricsStore.ItemEstimate, MetricsStore.ItemEstimate.connectionId eq connId),
            DigestSpec(MetricsStore.ItemStage, MetricsStore.ItemStage.connectionId eq connId),
            DigestSpec(MetricsStore.ItemBlocked, MetricsStore.ItemBlocked.connectionId eq connId),
            DigestSpec(MetricsStore.FactTaskDelivery, MetricsStore.FactTaskDelivery.connectionId eq connId),
            DigestSpec(MetricsStore.FactEpicDelivery, MetricsStore.FactEpicDelivery.connectionId eq connId),
            DigestSpec(MetricsStore.FactSprintScope, MetricsStore.FactSprintScope.connectionId eq connId),
            DigestSpec(MetricsStore.FactSprint, MetricsStore.FactSprint.connectionId eq connId),
            DigestSpec(
                MetricsStore.FactSprintSnapshot,
                MetricsStore.FactSprintSnapshot.connectionId eq connId,
                setOf(MetricsStore.FactSprintSnapshot.snapshotAt, MetricsStore.FactSprintSnapshot.reconstructed),
            ),
            DigestSpec(MetricsStore.FactWorklog, MetricsStore.FactWorklog.connectionId eq connId),
            DigestSpec(MetricsStore.FactEpicPlan, MetricsStore.FactEpicPlan.connectionId eq connId),
            DigestSpec(MetricsStore.AggDailyWip, MetricsStore.AggDailyWip.connectionId eq connId),
            DigestSpec(MetricsStore.AggDailyFlow, MetricsStore.AggDailyFlow.connectionId eq connId),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            for (spec in specs) {
                digest.hashTable(spec.table, spec.table.selectAll().where { spec.scope }, spec.exclude)
            }
            // Opt-in (see the KDoc): hash only the days this connection's own WIP aggregate spans,
            // so another connection's wider range can never leak into this connection's digest.
            val wipDays = if (!includeDimDate) emptyList() else MetricsStore.AggDailyWip.selectAll()
                .where { MetricsStore.AggDailyWip.connectionId eq connId }
                .toList().map { it[MetricsStore.AggDailyWip.day] }
            if (wipDays.isNotEmpty()) {
                val firstDay = wipDays.min()
                val lastDay = wipDays.max()
                digest.hashTable(
                    MetricsStore.DimDate,
                    MetricsStore.DimDate.selectAll()
                        .where { (MetricsStore.DimDate.day greaterEq firstDay) and (MetricsStore.DimDate.day lessEq lastDay) },
                    emptySet(),
                )
            }
        }
        return digest.hex()
    }

    private suspend fun MessageDigest.hashTable(table: Table, query: Query, exclude: Set<Column<*>>) {
        val hashed = table.columns.filter { it.name != "id" && it !in exclude }
        val keyColumns = table.primaryKey?.columns.orEmpty().filter { it.name != "id" }.ifEmpty { hashed }
        val rows = query.orderBy(*keyColumns.map { it to SortOrder.ASC }.toTypedArray()).toList()
        update("#${table.tableName}/${rows.size}\n".toByteArray())
        hashRows(rows, hashed)
    }

    private fun MessageDigest.hashRows(rows: List<ResultRow>, columns: List<Column<*>>) {
        rows.forEach { row ->
            val line = columns.joinToString("|") { row[it].toString() }
            update((line + "\n").toByteArray())
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
}
