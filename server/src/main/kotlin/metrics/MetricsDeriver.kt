package ch.nokillswit.metrics

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

/** Shared across every `Derive*.kt` file (`DeriveModel.kt`/`DeriveTaskRows.kt`/`DeriveWorklogStep.kt`/
 * `DeriveSprintStep.kt`/`DeriveEpicPlanStep.kt`) — an epic is level 1, never "type name = Epic". */
internal const val EPIC_HIERARCHY_LEVEL = 1
private const val MAX_ERROR_DETAIL_LENGTH = 1000
private val log = LoggerFactory.getLogger(MetricsDeriver::class.java)
private const val ABANDONED_RUN_DETAIL = "abandoned: worker lost its lease"

/** `MetricsDeriver`'s own default (v0.3.0 M3 review round 2b) — mirrors `application.yaml`'s `ingest.jobRetentionDays` default. */
internal const val DEFAULT_JOB_RETENTION_DAYS = 90L

/**
 * How long the post-commit `ANALYZE` of a re-derive waits for a table lock another session holds (a manual `VACUUM`,
 * another `ANALYZE`, DDL) before giving up with a WARN. A healthy ANALYZE takes 16-90 ms, and the worker heartbeats a
 * claim every `leaseSeconds / 3` — at least 10 s (`ingest.leaseSeconds` min 30) — so 5 s keeps the job's end, and with
 * it the slot, bounded well inside one heartbeat period. Never applied to the first derive's in-transaction ANALYZE.
 */
internal const val DEFAULT_ANALYZE_LOCK_TIMEOUT_MS = 5_000L

/**
 * Bounds the WHOLE post-commit `ANALYZE` (16 tables, so up to 16 lock waits of [DEFAULT_ANALYZE_LOCK_TIMEOUT_MS] each
 * otherwise): `statement_timeout` also covers the lock waits. A healthy one is 16-90 ms; the bound exists so a stuck
 * ANALYZE can never hold a worker slot for long (the claim's heartbeat is a concurrent ticker, so this is slot
 * occupancy, not lease safety).
 */
internal const val DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS = 30_000L

/** A table's statistics still describe a derive while its row count is at most this multiple of the previous derive's. */
private const val STATISTICS_GROWTH_FACTOR = 2

/**
 * Whether the planner statistics the connection's newest SUCCEEDED derive left ([previous] = its `row_counts`; null = no
 * such run) still describe a derive that is about to write [current] rows (the same keys: `tasks`, `epics`, `sprints`,
 * `worklogs`, `epicPlans`, `estimates` — each one the row count of tables in `ANALYZED_TABLES`). The test that picks the
 * in-transaction ANALYZE (they do NOT: some table the WIP/flow `INSERT … SELECT`s join would be planned at default
 * `rows=1`, build-times WHY 1) or the post-commit one (they do). Decided PER KEY, like with like, because config-dependent
 * tables move independently of the tasks: an admin's first config PUT mapping the estimate field fills `item_estimate`
 * (the backlog flow joins it) and mapping the sprint field fills the sprint tables while the task count stays flat. A key
 * passes when [current] is 0 (nothing to plan) or the previous run wrote more than 0 and [current] is at most
 * [STATISTICS_GROWTH_FACTOR] times as many; a key the previous run did not record (an older build) fails, once.
 */
internal fun statisticsDescribeRows(previous: Map<String, Int>?, current: Map<String, Int>): Boolean =
    previous != null && current.all { (key, now) ->
        now == 0 || previous[key]?.let { before -> before > 0 && now <= STATISTICS_GROWTH_FACTOR * before } == true
    }

/**
 * The per-item/per-batch work runs in chunks of this size (v0.3.0 M3 review round 2b, plan §5):
 * `MetricsDeriver.derive` never loads every issue's intervals/field changes for the WHOLE
 * connection into memory at once — only one batch's worth, read, computed, and written before the
 * next batch's own read begins, all inside the ONE `context.transaction {}` `derive()` opens.
 */
internal const val DERIVE_BATCH_SIZE = 200

/** `jira/JiraNormalizer.kt`'s own tracked field id for the issue-key changelog item — `task_domain`'s history source. */
internal const val ISSUE_KEY_FIELD_ID = "issuekey"

/**
 * The `DERIVE` job body (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Analytical model",
 * `.claude/docs/ingestion.md` "Job orders") — reads `norm.*` and the connection's effective metrics
 * configuration, writes `metrics.*`, NEVER touches Jira. Dispatched by `ingest/IngestWorker.kt`
 * BEFORE the connector registry (`claim.kind == DERIVE`), so it runs whether or not the connection's
 * OWN connector kind matters. It populates dims (`dim_domain`/`dim_task`/`dim_epic`/`dim_sprint`),
 * every bridge, `fact_task_delivery`/`fact_epic_delivery`, the sprint facts (D13), `fact_worklog`
 * (commit 9) and `fact_epic_plan` (D4's PV baselines, commit 9b, `runEpicPlanStep`); `agg_daily_*`'s
 * writer still awaits its own commit (its table objects already exist — [MetricsStore.purgeAll]
 * already drains them).
 *
 * **Memory (review round 2b, plan §5, `.claude/docs/metrics.md` "The DERIVE run algorithm"):** the
 * per-issue work runs in batches of [DERIVE_BATCH_SIZE] — `runPass1`/`runPass2` each read intervals/
 * field changes for ONE batch of issue ids at a time (`WorkItemStore`'s own `issueIds`-scoped reads),
 * compute that batch's rows, and insert them immediately, rather than holding the whole connection's
 * raw intervals/worklogs/output rows in memory at once. `custom_fields` is trimmed to only the
 * configured field ids right after `workItemsForDerivation` reads it back (`trimCustomFields`) — a
 * real tenant's Rank/ADF-shaped fields otherwise duplicate raw bytes for every issue held in the
 * connection-wide `itemsById` reference map every pass needs for epic/sub-task lookups.
 */
class MetricsDeriver(
    private val workItemStore: WorkItemStore,
    private val metricsSettings: MetricsSettingsService,
    private val metricsConfig: MetricsConfigService,
    private val domainOwners: DomainOwnerResolver,
    private val teamMembership: TeamMembershipService,
    private val metricsStore: MetricsStore,
    private val database: R2dbcDatabase,
    private val jobRetentionDays: Long = DEFAULT_JOB_RETENTION_DAYS,
    private val analyzeLockTimeoutMs: Long = DEFAULT_ANALYZE_LOCK_TIMEOUT_MS,
    private val analyzeStatementTimeoutMs: Long = DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS,
) {
    /**
     * The DERIVE job's own run — `context.claim.connectionId`/`context.claim.id`; heartbeats once
     * after the write commits. Returns the `metrics.settings.config_revision` this run used
     * (`derive_runs.config_revision`'s own value) — `ingest/IngestWorker.kt`'s `onSucceeded` compares
     * it against the CURRENT revision once this run finishes, so a config change that landed WHILE
     * this run was in flight (and so coalesced into it rather than getting its own job) is never
     * silently lost (review round 1 fix). Settings and the connection's effective config are read
     * together in ONE transaction — a settings write racing between the two independent reads this
     * used to be would let this run stamp a revision NEWER than the config it actually derived under.
     * [SyncJobRunContext.clock] is the SAME injectable clock every stream/the worker itself reads
     * (`ingest/Stream.kt`, `ingest/IngestWorker.kt`) — review round 2a fix, replacing a SEPARATE
     * constructor-level clock this class used to carry on its own.
     */
    suspend fun derive(context: SyncJobRunContext): Long {
        val connectionId = context.claim.connectionId
        val jobId = context.claim.id
        val now = context.clock()
        val (settings, config) = suspendTransaction(database) {
            metricsSettings.read() to metricsConfig.effectiveConfig(connectionId)
        }
        val calendar = WorkingCalendar.of(settings)

        // Hard-deletes old terminal derive_runs rows on every DERIVE (review round 2b) — the
        // `SyncJobsService.prune` shape, `.claude/docs/persistence.md` "Soft delete (convention)".
        suspendTransaction(database) { metricsStore.pruneDeriveRuns(jobRetentionDays * MILLIS_PER_DAY, now) }

        val runId = suspendTransaction(database) {
            // Only one DERIVE per connection can run (the job queue's lease guarantees it), so any
            // OTHER RUNNING row for this connection belongs to a worker that died mid-DERIVE (SIGKILL,
            // lost lease) and can never finish — nothing else would ever mark it terminal, and
            // `pruneDeriveRuns` only deletes SUCCEEDED/FAILED rows. Same transaction as our own insert.
            MetricsTables.DeriveRuns.update({
                (MetricsTables.DeriveRuns.connectionId eq connectionId.toInt()) and (MetricsTables.DeriveRuns.status eq "RUNNING")
            }) {
                it[status] = "FAILED"
                it[MetricsTables.DeriveRuns.finishedAt] = now
                it[errorDetail] = ABANDONED_RUN_DETAIL
            }
            MetricsTables.DeriveRuns.insert {
                it[MetricsTables.DeriveRuns.connectionId] = connectionId.toInt()
                it[MetricsTables.DeriveRuns.jobId] = jobId.toInt()
                it[MetricsTables.DeriveRuns.configRevision] = settings.configRevision
                it[MetricsTables.DeriveRuns.processingVersion] = PROCESSING_VERSION
                it[MetricsTables.DeriveRuns.startedAt] = now
                it[MetricsTables.DeriveRuns.status] = "RUNNING"
            }[MetricsTables.DeriveRuns.id]
        }

        try {
            val graceMs = settings.commitmentGraceMinutes * MILLIS_PER_MINUTE
            val derivation = suspendTransaction(database) {
                runDerivation(
                    connectionId, config, calendar, now, settings.hoursPerDay, settings.epicDriftDays, graceMs, settings.configRevision,
                )
            }
            markRunSucceeded(runId, derivation.counts, context.clock())
            if (!derivation.analyzedInTransaction) analyzeAfterCommit(connectionId)
            context.heartbeat(null, "derive")
        } catch (failure: Exception) {
            // A genuine coroutine cancellation is an Exception too (`CancellationException`) and
            // must still be able to mark this run FAILED before it propagates — but the DB write
            // itself must run under NonCancellable (review round 2b fix), since the enclosing
            // coroutine's own job is already cancelled by the time this catch runs: a plain
            // `suspendTransaction` call here would otherwise never actually commit, leaving the row
            // RUNNING forever. There is no separate `CANCELLED` status (`.claude/docs/metrics.md`
            // documents this choice) — `derive_runs.status`'s CHECK constraint (V15, immutable bytes)
            // only allows RUNNING/SUCCEEDED/FAILED, and altering it would need a new migration.
            markRunFailed(runId, failure, context.clock())
            throw failure
        }
        return settings.configRevision
    }

    /**
     * Deletes every rebuildable `metrics.*` row for this connection ONCE, then rebuilds dims,
     * bridges and task/epic facts in batches of [DERIVE_BATCH_SIZE] — the whole thing runs inside the
     * CALLER's one transaction (`derive()`'s own `suspendTransaction` wrap). Returns the row counts
     * `markRunSucceeded` stamps onto `derive_runs.row_counts`, and whether the ANALYZE already ran in the
     * transaction ([Derivation.analyzedInTransaction]) — see [statisticsDescribeRows].
     */
    private suspend fun runDerivation(
        connectionId: UInt,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
        graceMs: Long,
        configRevision: Long,
    ): Derivation {
        val relevantFieldIds = relevantCustomFieldIds(config)
        val workItems = workItemStore.workItemsForDerivation(connectionId).map { trimCustomFields(it, relevantFieldIds) }
        val createdMin = workItems.minOfOrNull { it.createdAt }
        val initialRange = DeriveKernels.dimDateRange(now, createdMin, emptyList())
        metricsStore.ensureDimDate(calendar, initialRange, configRevision)

        metricsStore.deleteDims(connectionId)
        metricsStore.deleteBridges(connectionId)
        metricsStore.deleteFactTaskDelivery(connectionId)
        metricsStore.deleteFactEpicDelivery(connectionId)
        metricsStore.deleteSprintFacts(connectionId)
        metricsStore.deleteFactWorklog(connectionId)
        metricsStore.deleteFactEpicPlan(connectionId)
        metricsStore.deleteAggDailyWip(connectionId)
        metricsStore.deleteAggDailyFlow(connectionId)

        val context = buildContext(connectionId, workItems, config, calendar, now, hoursPerDay, epicDriftDays)
        metricsStore.insertDomains(connectionId, domainDims(context), configRevision)

        val derivedById = mutableMapOf<Long, ItemDerived>()
        val blockedByIssue = mutableMapOf<Long, Pair<Long, Double>>()
        val estimateCount = runPass1(connectionId, workItems, context, config, derivedById, blockedByIssue)

        val factTasksByIssueId = mutableMapOf<Long, FactTaskDeliveryRow>()
        val factEpicsByIssueId = mutableMapOf<Long, FactEpicDeliveryRow>()
        val taskCount = runPass2(connectionId, workItems, context, derivedById, blockedByIssue, factTasksByIssueId, configRevision)
        val epicCount =
            runPass3(connectionId, workItems, context, derivedById, blockedByIssue, factTasksByIssueId, factEpicsByIssueId, configRevision)
        val sprintFieldId = metricsConfig.detectedSprintFieldId(connectionId)
        val sprintOutcome = runSprintStep(connectionId, workItems, context, config, derivedById, graceMs, configRevision, sprintFieldId)
        val worklogCount = runWorklogStep(connectionId, workItems, context, derivedById, configRevision)
        val epicPlanCount = runEpicPlanStep(connectionId, workItems, context, derivedById, factEpicsByIssueId, configRevision)
        widenDimDate(connectionId, calendar, createdMin, now, configRevision)
        // Statistics that do not describe this connection's rows (no SUCCEEDED derive yet, or some table that was empty or
        // is now more than twice as big as last time — `statisticsDescribeRows`) would have the WIP/flow INSERT..SELECTs
        // below plan at default rows=1 estimates (autovacuum never sees uncommitted rows), so such a derive ANALYZEs in
        // this transaction (legal in a transaction block, counts its own rows) and holds the table locks to the commit.
        // Every other derive plans on the previous committed state's statistics and ANALYZEs after the commit.
        val statisticsRowCounts = mapOf(
            "tasks" to taskCount, "epics" to epicCount, "sprints" to sprintOutcome.sprintCount, "worklogs" to worklogCount,
            "epicPlans" to epicPlanCount, "estimates" to estimateCount,
        )
        val analyzeInTransaction = !statisticsDescribeRows(metricsStore.newestSucceededRunRowCounts(connectionId), statisticsRowCounts)
        if (analyzeInTransaction) metricsStore.analyzeDerivedTables()
        val wipCount = runWipStep(connectionId, now, configRevision)
        val flowCount = runFlowStep(metricsStore, connectionId, now, configRevision)

        val counts = DeriveRowCounts(
            tasks = taskCount,
            epics = epicCount,
            sprints = sprintOutcome.sprintCount,
            sprintFieldUnresolved = sprintOutcome.fieldUnresolved,
            worklogs = worklogCount,
            epicPlans = epicPlanCount,
            estimates = estimateCount,
            aggWipRows = wipCount,
            aggFlowRows = flowCount,
        )
        return Derivation(counts, analyzeInTransaction)
    }

    /** [runDerivation]'s result: the run's row counts plus whether the ANALYZE already ran inside its transaction. */
    private class Derivation(val counts: DeriveRowCounts, val analyzedInTransaction: Boolean)

    /**
     * The post-commit `ANALYZE` of every derive whose predecessor's statistics still describe its rows
     * ([statisticsDescribeRows]), in its OWN short transaction (this is a top-level call, so
     * [MetricsStore.analyzeDerivedTables] opens one) — the derive's data is already committed and marked SUCCEEDED, so a
     * failure here is a WARN, never a FAILED run (the next derive plans on statistics one more derive old). The wait is
     * bounded twice: [analyzeLockTimeoutMs] per table lock and [analyzeStatementTimeoutMs] for the whole statement
     * (`SET LOCAL`s inside that transaction), so a lock someone else holds — a manual VACUUM, another ANALYZE, a first
     * derive's in-transaction one — occupies the worker slot for that long at most; a timeout is just another failure to
     * WARN about. The statement timeout does not cover acquiring a pooled connection (the pool's own acquire timeout, 30 s
     * by default), so the worst case outside shutdown is that plus the statement bound. Deliberately NOT under
     * `NonCancellable`: a shutdown must be able to interrupt it, because the worker's bounded join on shutdown has to see
     * the claim released.
     */
    private suspend fun analyzeAfterCommit(connectionId: UInt) {
        catchingFailures({ metricsStore.analyzeDerivedTables(analyzeLockTimeoutMs, analyzeStatementTimeoutMs) }) { failure ->
            log.warn("post-commit ANALYZE of the metrics tables failed for connection {}", connectionId, failure)
        }
    }

    /**
     * Widens `metrics.dim_date` beyond the initial (creation-based) range once the facts exist: every
     * flow-aggregate join on `dim_date` silently drops an event whose day has no row, so the range
     * must also reach the earliest worklog start, sprint start and done time
     * ([MetricsStore.earliestFactEventMs], floored at 50 years back) and every epic window inside the
     * PV horizon ([MetricsStore.currentEpicPlanWindows]) — the pure rule is [DeriveKernels.dimDateRange].
     * The two READS stay in the caller's transaction (the facts are not committed yet); the write is
     * [MetricsStore.ensureDimDate] over the FULL range — its own short, serialized, committed
     * transaction that writes only the missing or changed rows, so this DERIVE holds no `dim_date`
     * lock and a repeat call over a settled table writes nothing.
     */
    private suspend fun widenDimDate(connectionId: UInt, calendar: WorkingCalendar, createdMin: Long?, now: Long, configRevision: Long) {
        val factMin = metricsStore.earliestFactEventMs(connectionId)
        val earliest = listOfNotNull(createdMin, factMin).minOrNull()
        val range = DeriveKernels.dimDateRange(now, earliest, metricsStore.currentEpicPlanWindows(connectionId))
        metricsStore.ensureDimDate(calendar, range, configRevision)
    }

    private suspend fun markRunSucceeded(runId: Int, counts: DeriveRowCounts, finishedAt: Long) {
        val countsJson = buildJsonObject {
            put("tasks", JsonPrimitive(counts.tasks))
            put("epics", JsonPrimitive(counts.epics))
            put("sprints", JsonPrimitive(counts.sprints))
            put("worklogs", JsonPrimitive(counts.worklogs))
            put("epicPlans", JsonPrimitive(counts.epicPlans))
            put("estimates", JsonPrimitive(counts.estimates))
            put("aggWipRows", JsonPrimitive(counts.aggWipRows))
            put("aggFlowRows", JsonPrimitive(counts.aggFlowRows))
            if (counts.sprintFieldUnresolved) put("sprintFieldUnresolved", JsonPrimitive(true))
        }.toString()
        suspendTransaction(database) {
            // Only while still RUNNING: a newer DERIVE of the same connection may already have swept this run
            // FAILED as abandoned (a lease-expired zombie) — its late write must not overwrite that.
            MetricsTables.DeriveRuns.update({ (MetricsTables.DeriveRuns.id eq runId) and (MetricsTables.DeriveRuns.status eq "RUNNING") }) {
                it[status] = "SUCCEEDED"
                it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
                it[rowCounts] = countsJson
            }
        }
    }

    private suspend fun markRunFailed(runId: Int, failure: Exception, finishedAt: Long) = withContext(NonCancellable) {
        suspendTransaction(database) {
            // Only while still RUNNING: a newer DERIVE of the same connection may already have swept this run
            // FAILED as abandoned (a lease-expired zombie) — its late write must not overwrite that.
            MetricsTables.DeriveRuns.update({ (MetricsTables.DeriveRuns.id eq runId) and (MetricsTables.DeriveRuns.status eq "RUNNING") }) {
                it[status] = "FAILED"
                it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
                it[errorDetail] = failure.message?.take(MAX_ERROR_DETAIL_LENGTH)
            }
        }
        Unit
    }

    /** The field ids `custom_fields` actually needs to keep for this connection's derivation (review round 2b) — see [trimCustomFields]. */
    private fun relevantCustomFieldIds(config: DataSourceMetricsConfig): Set<String> = setOfNotNull(
        config.fields.estimateTask, config.fields.estimateEpic, config.fields.workCategory, config.fields.epicStart, config.fields.epicDue,
    )

    /**
     * Trims a work item's `custom_fields` JSON object down to only [relevantFieldIds] (review round
     * 2b, plan §5 "read from custom_fields only the configured field ids") — every OTHER
     * `customfield_*` value (Rank, an ADF-shaped rich-text field, …) is dropped right after the read,
     * before it ever lands in the connection-wide `itemsById` reference map every batch's kernels
     * consult for epic/sub-task lookups.
     */
    private fun trimCustomFields(
        item: WorkItemStore.DerivationWorkItemRow,
        relevantFieldIds: Set<String>,
    ): WorkItemStore.DerivationWorkItemRow =
        item.copy(customFields = JsonObject(item.customFields.filterKeys { it in relevantFieldIds }))

    /**
     * A22: a sprint counts as CLOSED for "current sprint excludes closed sprints" — either its own
     * Jira `state == "closed"`, or a `complete_at` at or before the DERIVE run's own pinned clock
     * [now] (the same `state`/`completeAtMs` OR the sprint step's own `closedAndMapped` check
     * already applies for the D13 snapshot decision, `runSprintStep`).
     */
    private fun isSprintClosed(sprint: SprintRef, now: Long): Boolean =
        sprint.state.equals("closed", ignoreCase = true) || (sprint.completeAtMs != null && sprint.completeAtMs <= now)

    private suspend fun buildContext(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        config: DataSourceMetricsConfig,
        calendar: WorkingCalendar,
        now: Long,
        hoursPerDay: Double,
        epicDriftDays: Int,
    ): DeriveContext {
        val stageMap = config.statusStages.associate { it.statusId to ItemStage.valueOf(it.stage.name) }
        val configMaps = ConfigMaps(
            stageMap = stageMap,
            stageMapByDomain = config.domainStatusStages.groupBy { it.domainKey }.mapValues { (_, overrides) ->
                stageMap + overrides.associate { it.statusId to ItemStage.valueOf(it.stage.name) }
            },
            domainByProject = config.domains.associate { it.projectKey to it.domainKey },
            activityTypeByIssueType = config.activityTypes.associate { it.issueType to it.activityType },
            workCategoryMap = config.workCategories.associate { it.valueId to it.category },
            blockedStatusIds = config.blockedStatuses.toSet(),
            boardTeamByBoardId = config.boards.associate { it.boardId to it.teamId },
            workCategoryFieldId = config.fields.workCategory,
            epicDriftDays = epicDriftDays,
            epicStartFieldId = config.fields.epicStart,
            epicDueFieldId = config.fields.epicDue,
        )
        val sprints = workItemStore.allSprintRefs(connectionId)
        val activeTeamIds = activeTeamIds()
        return DeriveContext(
            configMaps = configMaps,
            itemsById = workItems.associateBy { it.issueId },
            sprintBoardById = sprints.associate { it.sprintId to it.boardId },
            sprintClosedById = sprints.associate { it.sprintId to isSprintClosed(it, now) },
            membershipsByAccount = teamMembership.allMembershipsByAccount(),
            worklogSecondsByIssue = workItemStore.worklogSecondsByIssue(connectionId),
            ownerTeamByDomain = ownerTeamByDomain(connectionId, config, configMaps.boardTeamByBoardId, activeTeamIds),
            activeTeamIds = activeTeamIds,
            calendar = calendar,
            now = now,
            hoursPerDay = hoursPerDay,
        )
    }

    /**
     * A22: every team id currently ACTIVE (not soft-deleted) — read INSIDE `derive()`'s own
     * transaction (`buildContext` is called from `runDerivation`, itself run inside `derive()`'s
     * `suspendTransaction` at its ONE call site), so a config/team edit racing this run always sees a
     * consistent snapshot. The SAME `TeamService.Teams`/`active()` read `MetricsConfigService
     * .referenceData` already uses for the metrics-config PUT's own validation.
     */
    private suspend fun activeTeamIds(): Set<UInt> =
        TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
            .toList().map { it[TeamService.Teams.id].value }.toSet()

    /**
     * A19/A22 (`.claude/docs/domain-model.md` "Amendments", `.claude/docs/metrics.md`): each
     * configured DOMAIN's (not project's — several project rows may share one `domainKey`) owner
     * team, resolved via the ONE shared implementation, `DomainOwnerResolver
     * .resolveOwnerTeamByDomain` (moved there in v0.3.0 M3 commit 9e so the metrics-config GET's
     * own owner-default filling never duplicates this algorithm) — see that function's own doc for
     * the agreement/fallback rules. [configuredOwners] is read UNFILTERED by team activity: a
     * configured owner that is now soft-deleted still BLOCKS the board fallback and resolves to
     * none (A22) — `resolveOwnerTeamByDomain` itself applies the activity filter.
     */
    private suspend fun ownerTeamByDomain(
        connectionId: UInt,
        config: DataSourceMetricsConfig,
        boardTeamByBoardId: Map<Long, UInt>,
        activeTeamIds: Set<UInt>,
    ): Map<String, UInt> {
        val configuredOwners = domainOwners.domainOwnerTeamIds(connectionId)
        val boardsByProject = workItemStore.allBoardRefs(connectionId).filter { it.projectKey != null }
            .groupBy({ it.projectKey!! }, { it.boardId })
        val projectKeysByDomain = config.domains.groupBy({ it.domainKey }, { it.projectKey })
        return domainOwners.resolveOwnerTeamByDomain(
            projectKeysByDomain, configuredOwners, boardsByProject, boardTeamByBoardId, activeTeamIds,
        )
    }

    /**
     * Pass 1 (review round 2b): item-level bridges shared by tasks AND epics alike (`item_stage`/
     * `item_blocked`/`item_estimate` — `.claude/docs/domain-model.md`'s bridges table has no task/epic
     * split for these three), computed for EVERY item in batches of [DERIVE_BATCH_SIZE] — each
     * batch's status/flagged/estimate-field-change intervals are read scoped to that batch's own
     * issue ids, computed, and inserted before the next batch's own read begins. Returns the `item_estimate` row count.
     */
    private suspend fun runPass1(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
        derivedById: MutableMap<Long, ItemDerived>,
        blockedByIssue: MutableMap<Long, Pair<Long, Double>>,
    ): Int {
        var estimateCount = 0
        val estimateFieldIds = listOfNotNull(config.fields.estimateTask, config.fields.estimateEpic).distinct()
        for (batch in workItems.chunked(DERIVE_BATCH_SIZE)) {
            val ids = batch.map { it.issueId }
            context.statusIntervalsByIssue = workItemStore.statusIntervalsByIssue(connectionId, ids)
            context.flaggedIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.FLAGGED, ids)
            context.estimateChangesByIssueAndField =
                workItemStore.fieldChangesByFieldIds(connectionId, estimateFieldIds, ids).groupBy { it.issueId }

            val itemStageBatch = mutableListOf<ItemStageRow>()
            val itemBlockedBatch = mutableListOf<ItemBlockedRow>()
            val itemEstimateBatch = mutableListOf<ItemEstimateRow>()
            for (item in batch) {
                val derived = deriveItem(item, context, config)
                derivedById[item.issueId] = derived
                val blockedMs = derived.blocked.sumOf { it.toAtMs - it.fromAtMs }
                val blockedWorkingDays = derived.blocked.sumOf { context.calendar.workingDaysBetween(it.fromAtMs, it.toAtMs) }
                blockedByIssue[item.issueId] = blockedMs to blockedWorkingDays
                itemStageBatch += derived.stages.map { ItemStageRow(item.issueId, it.stage.name, it.statusId, it.fromAtMs, it.toAtMs) }
                itemBlockedBatch += derived.blocked.map { ItemBlockedRow(item.issueId, it.reason, it.fromAtMs, it.toAtMs) }
                itemEstimateBatch += buildEstimateBridge(item, derived.estimateTimeline, context.estimateFieldIdFor(item, config))
            }
            metricsStore.insertItemStage(connectionId, itemStageBatch)
            metricsStore.insertItemBlocked(connectionId, itemBlockedBatch)
            metricsStore.insertItemEstimate(connectionId, itemEstimateBatch)
            estimateCount += itemEstimateBatch.size
        }
        return estimateCount
    }

    /**
     * Pass 2 (review round 2b): tasks (review round 2a: "skip epics for task_* bridges" —
     * `task_epic`/`task_domain`/`task_assignee` are TASK-only bridges), in batches of
     * [DERIVE_BATCH_SIZE] — each batch's assignee/sprint/parent field intervals and `issuekey` field
     * changes are read scoped to that batch's own issue ids. `factTasksByIssueId` (a small map of
     * ALREADY-derived fact rows, not raw inputs) accumulates across every batch so pass 3 can roll an
     * epic's cost/estimate up from its children (D2, finding 6) regardless of which batch a child
     * task landed in. Returns the total task row count.
     */
    private suspend fun runPass2(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        blockedByIssue: Map<Long, Pair<Long, Double>>,
        factTasksByIssueId: MutableMap<Long, FactTaskDeliveryRow>,
        configRevision: Long,
    ): Int {
        var count = 0
        val taskItems = workItems.filter { it.hierarchyLevel != EPIC_HIERARCHY_LEVEL }
        for (batch in taskItems.chunked(DERIVE_BATCH_SIZE)) {
            val ids = batch.map { it.issueId }
            context.assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)
            context.sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT, ids)
            context.parentIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.PARENT, ids)
            context.issueKeyChangesByIssue =
                workItemStore.fieldChangesByFieldIds(connectionId, listOf(ISSUE_KEY_FIELD_ID), ids).groupBy { it.issueId }

            val tasksBatch = mutableListOf<DimTaskRow>()
            val factsBatch = mutableListOf<FactTaskDeliveryRow>()
            val taskEpicBatch = mutableListOf<TaskEpicRow>()
            val taskDomainBatch = mutableListOf<TaskDomainRow>()
            val taskAssigneeBatch = mutableListOf<TaskAssigneeRow>()
            for (item in batch) {
                val derived = derivedById.getValue(item.issueId)
                val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
                val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
                val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)

                val itemTaskEpicRows = taskEpicHistory(item, context)
                val itemTaskDomainRows = taskDomainHistory(item, context)
                taskEpicBatch += itemTaskEpicRows
                taskDomainBatch += itemTaskDomainRows
                taskAssigneeBatch += taskAssigneeHistory(item, context)

                val composition = buildTaskRow(
                    item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays,
                    itemTaskDomainRows, itemTaskEpicRows,
                )
                tasksBatch += composition.dim
                factsBatch += composition.fact
                factTasksByIssueId[item.issueId] = composition.fact
            }
            metricsStore.insertTasks(connectionId, tasksBatch, configRevision)
            metricsStore.insertTaskEpic(connectionId, taskEpicBatch)
            metricsStore.insertTaskDomain(connectionId, taskDomainBatch)
            metricsStore.insertTaskAssignee(connectionId, taskAssigneeBatch)
            metricsStore.insertFactTaskDelivery(connectionId, factsBatch, configRevision)
            count += factsBatch.size
        }
        return count
    }

    /**
     * Pass 3 (review round 2b): epics, in batches of [DERIVE_BATCH_SIZE] — reads pass 2's cached
     * `factTasksByIssueId` for the D2 roll-up; needs no per-batch interval reads of its own (an
     * epic's own facts come entirely from [derivedById]/[factTasksByIssueId]/config, already in
     * memory). [factEpicsByIssueId] accumulates every computed `fact_epic_delivery` row across every
     * batch (v0.3.0 M3 commit 9b) — the epic plan step (`runEpicPlanStep`) reads its
     * `childSumEstimateMd` back for the D4 CHILDREN fallback, so it is never re-derived a second way.
     * Returns the total epic row count.
     */
    private suspend fun runPass3(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        blockedByIssue: Map<Long, Pair<Long, Double>>,
        factTasksByIssueId: Map<Long, FactTaskDeliveryRow>,
        factEpicsByIssueId: MutableMap<Long, FactEpicDeliveryRow>,
        configRevision: Long,
    ): Int {
        var count = 0
        val epicItems = workItems.filter { it.hierarchyLevel == EPIC_HIERARCHY_LEVEL }
        for (batch in epicItems.chunked(DERIVE_BATCH_SIZE)) {
            val epicsBatch = mutableListOf<DimEpicRow>()
            val factEpicsBatch = mutableListOf<FactEpicDeliveryRow>()
            for (item in batch) {
                val derived = derivedById.getValue(item.issueId)
                val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
                val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
                val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)
                val (dim, fact) = buildEpicRow(
                    item, derived, context, workItems, derivedById, factTasksByIssueId, domainKey, currentStage,
                    blockedMs, blockedWorkingDays,
                )
                epicsBatch += dim
                factEpicsBatch += fact
                factEpicsByIssueId[item.issueId] = fact
            }
            metricsStore.insertEpics(connectionId, epicsBatch, configRevision)
            metricsStore.insertFactEpicDelivery(connectionId, factEpicsBatch, configRevision)
            count += factEpicsBatch.size
        }
        return count
    }

    /**
     * The worklog step (v0.3.0 M3 commit 9, `.claude/docs/domain-model.md` "Cross-team time"/D3,
     * `.claude/docs/metrics.md` "Worklog cost facts"): one `fact_worklog` row per
     * `norm.work_item_worklogs` row, carrying the author's team AND the task's domain/epic, BOTH
     * as-of the worklog's own `started_at` — never the item's current/done-time values. Batches over
     * only the items that actually carry a worklog, re-reading each batch's PARENT/`issuekey`/SPRINT
     * intervals the same way pass 2 does (the raw intervals are batch-scoped, never held for the
     * whole connection at once — the review round 2b memory bound applies here too).
     */
    private suspend fun runWorklogStep(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        configRevision: Long,
    ): Int = ch.nokillswit.metrics.runWorklogStep(
        workItemStore, metricsStore, connectionId, workItems, context, derivedById, configRevision,
    )

    /**
     * The sprint step (v0.3.0 M3 commit 8, `.claude/docs/domain-model.md` "Plan — PV"/"Glossary",
     * `.claude/docs/metrics.md` "Sprint scope") — delegates to the top-level [runSprintStep] (kept
     * in `DeriveSprintStep.kt`, the `LargeClass` idiom: it needs only [workItemStore]/[metricsStore]
     * from this instance, passed explicitly, so it carries none of this class's own line-count
     * weight). Returns the [SprintStepOutcome] for `derive_runs.row_counts`.
     */
    private suspend fun runSprintStep(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        config: DataSourceMetricsConfig,
        derivedById: Map<Long, ItemDerived>,
        graceMs: Long,
        configRevision: Long,
        sprintFieldId: String?,
    ): SprintStepOutcome = ch.nokillswit.metrics.runSprintStep(
        workItemStore, metricsStore, connectionId, workItems, context, config, derivedById, graceMs, configRevision, sprintFieldId,
    )

    /**
     * The epic plan step (v0.3.0 M3 commit 9b, `.claude/docs/domain-model.md` "Plan — PV", D4, D11,
     * `.claude/docs/metrics.md` "Epic plans and PV") — delegates to the top-level [runEpicPlanStep]
     * (kept in `DeriveEpicPlanStep.kt`, the sprint/worklog steps' own `LargeClass` idiom). Returns the
     * epic-plan row count for `derive_runs.row_counts`.
     */
    private suspend fun runEpicPlanStep(
        connectionId: UInt,
        workItems: List<WorkItemStore.DerivationWorkItemRow>,
        context: DeriveContext,
        derivedById: Map<Long, ItemDerived>,
        factEpicsByIssueId: Map<Long, FactEpicDeliveryRow>,
        configRevision: Long,
    ): Int = ch.nokillswit.metrics.runEpicPlanStep(
        workItemStore, metricsStore, connectionId, workItems, context, derivedById, factEpicsByIssueId, configRevision,
    )

    /**
     * The WIP step (v0.3.0 M3 commit 9f, `.claude/docs/domain-model.md` "Reports" report 9,
     * `.claude/docs/metrics.md` "Daily WIP aggregate") — delegates to the top-level [runWipStep]
     * (`DeriveWipStep.kt`, the sprint/worklog/epic-plan steps' own `LargeClass` idiom). Unlike every
     * other step, it reads no batch-scoped [DeriveContext] state of its own — it runs entirely as raw
     * SQL over rows every earlier step in THIS SAME run already persisted. Returns the `agg_daily_wip`
     * row count for `derive_runs.row_counts`.
     */
    private suspend fun runWipStep(connectionId: UInt, now: Long, configRevision: Long): Int =
        ch.nokillswit.metrics.runWipStep(metricsStore, connectionId, now, configRevision)
}
