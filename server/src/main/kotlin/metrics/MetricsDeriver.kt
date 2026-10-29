package ch.nokillswit.metrics

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(MetricsDeriver::class.java)


/** `MetricsDeriver`'s own default (v0.3.0 M3 review round 2b) — mirrors `application.yaml`'s `ingest.jobRetentionDays` default. */
internal const val DEFAULT_JOB_RETENTION_DAYS = 90L

/**
 * The per-item/per-batch work runs in chunks of this size (v0.3.0 M3 review round 2b, plan §5):
 * `MetricsDeriver.derive` never loads every issue's intervals/field changes for the WHOLE
 * connection into memory at once — only one batch's worth, read, computed, and written before the
 * next batch's own read begins, all inside the ONE `context.transaction {}` `derive()` opens.
 */
internal const val DERIVE_BATCH_SIZE = 200

/**
 * `lock_timeout` of the DERIVE transaction (`SET LOCAL`, so every lock wait of the rebuild — its deletes, inserts, FK checks, an
 * in-transaction ANALYZE and the run's SUCCEEDED stamp — is bounded; `.claude/docs/metrics.md` "Failure and cancellation").
 * Without it a DERIVE queued behind a session that never ends its transaction waits forever and holds a worker slot with it
 * (build-times WHY 24). The longest legitimate wait is an in-transaction ANALYZE queued behind another connection's
 * in-transaction derive until that one commits — a whole DERIVE at most (`metrics.md` "Performance", scale 20: 18-29 s warm, 38
 * s for a cold first derive with its in-transaction ANALYZE) — so 5 minutes is ~8x the slowest. The configured value is never
 * below the pool's `idle_in_transaction_session_timeout` ([deriveLockTimeoutMs]): a Flow session left idle in a transaction is
 * ended before a DERIVE queued behind it gives up, so this bound only fires for a holder the server does not end (another
 * client's session, a backend stuck active). A timeout fails the run like any other error: `derive_runs` FAILED, the job
 * `RUN_FAILED`.
 */
internal const val DEFAULT_DERIVE_LOCK_TIMEOUT_MS = 300_000L

/**
 * The DERIVE rebuild's `lock_timeout` for a pool whose `idle_in_transaction_session_timeout` is [idleInTransactionTimeoutMs]
 * (`postgres.pool.idleInTransactionTimeoutSeconds`; 0 = off): [DEFAULT_DERIVE_LOCK_TIMEOUT_MS], raised to the idle timeout
 * when that is longer, so an idle Flow holder is always ended first.
 */
internal fun deriveLockTimeoutMs(idleInTransactionTimeoutMs: Long): Long = maxOf(DEFAULT_DERIVE_LOCK_TIMEOUT_MS, idleInTransactionTimeoutMs)

/**
 * The time bounds of one DERIVE run, in milliseconds: the post-commit ANALYZE's per-table lock wait and whole statement
 * ([DEFAULT_ANALYZE_LOCK_TIMEOUT_MS], [DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS]) and the rebuild transaction's lock wait
 * ([DEFAULT_DERIVE_LOCK_TIMEOUT_MS]). Internal defaults, not operator knobs; tests shorten them.
 */
data class DeriveTimeouts(
    val analyzeLockMs: Long = DEFAULT_ANALYZE_LOCK_TIMEOUT_MS,
    val analyzeStatementMs: Long = DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS,
    val rebuildLockMs: Long = DEFAULT_DERIVE_LOCK_TIMEOUT_MS,
)

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
 * configured field ids INSIDE `workItemsForDerivation`'s own SELECT (`relevantCustomFieldIds`) — a
 * real tenant's Rank/ADF-shaped fields otherwise duplicate raw bytes for every issue held in the
 * connection-wide `itemsById` reference map every pass needs for epic/sub-task lookups. The worklog step reads
 * its worklogs per batch of issue ids as well.
 */
class MetricsDeriver(
    private val workItemStore: WorkItemStore,
    private val metricsSettings: MetricsSettingsService,
    private val metricsConfig: MetricsConfigService,
    private val domainOwners: DomainOwnerResolver,
    private val teamMembership: TeamMembershipService,
    private val sprintCalendars: SprintCalendarService,
    private val metricsStore: MetricsStore,
    private val database: R2dbcDatabase,
    private val jobRetentionDays: Long = DEFAULT_JOB_RETENTION_DAYS,
    private val timeouts: DeriveTimeouts = DeriveTimeouts(),
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
        val (loaded, config) = suspendTransaction(database) {
            metricsSettings.readWithCalendar() to metricsConfig.effectiveConfig(connectionId)
        }
        val (settings, calendar) = loaded

        // Hard-deletes old terminal derive_runs rows on every DERIVE (review round 2b) — the
        // `SyncJobsService.prune` shape, `.claude/docs/persistence.md` "Soft delete (convention)".
        suspendTransaction(database) { metricsStore.pruneDeriveRuns(jobRetentionDays * MILLIS_PER_DAY, now) }

        val runId = openDeriveRun(database, connectionId, jobId, settings.configRevision, now)

        try {
            val graceMs = settings.commitmentGraceMinutes * MILLIS_PER_MINUTE
            // ONE transaction for the rebuild AND its SUCCEEDED stamp: the figures and the run row that vouches for them commit together,
            // so `meta.derivedAt`/`configRevision` and the snapshot reports' DERIVE clock (`deriveClocks`) never read new figures under
            // the previous run, nor a SUCCEEDED run over figures that were rolled back (`MetricsDeriveAtomicityTest`).
            val derivation = suspendTransaction(database) {
                exec("SET LOCAL lock_timeout = ${timeouts.rebuildLockMs}")
                val rebuilt = runDerivation(
                    connectionId, config, calendar, now, settings.hoursPerDay, settings.epicDriftDays, graceMs, settings.configRevision,
                )
                markRunSucceeded(runId, rebuilt.counts, context.clock())
                rebuilt
            }
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
            markRunFailed(database, runId, failure, context.clock())
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
        val workItems = workItemStore.workItemsForDerivation(connectionId, relevantFieldIds)
        // A31: an issue above the epic level is outside the model — no row in any task/epic/sprint table; only `buildContext`'s
        // lookup map and the worklog step still see it (invariant 6: its worklogs are kept).
        val modelItems = workItems.filter { hierarchyBucket(it.hierarchyLevel) != null }
        val createdMin = workItems.minOfOrNull { it.createdAt }
        val initialRange = dimDateRange(now, createdMin, emptyList())
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

        val context = buildContext(
            workItemStore, teamMembership, domainOwners, connectionId, workItems, config, calendar, now, hoursPerDay, epicDriftDays,
        )
        metricsStore.insertDomains(connectionId, domainDims(context), configRevision)

        val derivedById = mutableMapOf<Long, ItemDerived>()
        val blockedByIssue = mutableMapOf<Long, Pair<Long, Double>>()
        val estimateCount = runPass1(workItemStore, metricsStore, connectionId, modelItems, context, config, derivedById, blockedByIssue)

        runEpicDomainPass(workItemStore, metricsStore, connectionId, modelItems, context)
        val factTasksByIssueId = mutableMapOf<Long, FactTaskDeliveryRow>()
        val factEpicsByIssueId = mutableMapOf<Long, FactEpicDeliveryRow>()
        val taskCount = runPass2(
            workItemStore, metricsStore, connectionId, modelItems, context, derivedById, blockedByIssue, factTasksByIssueId, configRevision,
        )
        val epicCount = runPass3(
            metricsStore, connectionId, modelItems, context, derivedById, blockedByIssue, factTasksByIssueId,
            factEpicsByIssueId, configRevision,
        )
        val sprintFieldId = metricsConfig.detectedSprintFieldId(connectionId)
        val sprintOutcome = runSprintStep(
            workItemStore, metricsStore, connectionId, modelItems, context, sprintCalendars, derivedById, graceMs, configRevision,
            sprintFieldId,
        )
        val worklogCount = runWorklogStep(workItemStore, metricsStore, connectionId, workItems, context, configRevision)
        val epicPlanCount = runEpicPlanStep(
            workItemStore, metricsStore, connectionId, modelItems, context, derivedById, factEpicsByIssueId, configRevision,
        )
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
        val wipCount = runWipStep(metricsStore, connectionId, now, configRevision)
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
            aboveEpicItems = workItems.size - modelItems.size,
            unknownLevelItems = modelItems.count { it.hierarchyLevel == null },
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
     * bounded twice: [DeriveTimeouts.analyzeLockMs] per table lock (`SET LOCAL lock_timeout`) and
     * [DeriveTimeouts.analyzeStatementMs] for the whole
     * statement (the transaction's `queryTimeout`, whole seconds, rounded up), so a lock someone else holds — a manual VACUUM,
     * another ANALYZE, a first derive's in-transaction one — occupies the worker slot for that long at most; a timeout is
     * just another failure to WARN about. The statement timeout does not cover acquiring a pooled connection (the pool's own
     * acquire timeout, 10 s by default, about 20 s with r2dbc-pool's one retry), so the worst case outside shutdown is that
     * plus the statement bound. Deliberately NOT under `NonCancellable`: a shutdown must be able to interrupt it, because
     * the worker's bounded join on shutdown has to see the claim released.
     */
    private suspend fun analyzeAfterCommit(connectionId: UInt) {
        catchingFailures({ metricsStore.analyzeDerivedTables(timeouts.analyzeLockMs, timeouts.analyzeStatementMs) }) { failure ->
            log.warn("post-commit ANALYZE of the metrics tables failed for connection {}", connectionId, failure)
        }
    }

    /**
     * Widens `metrics.dim_date` beyond the initial (creation-based) range once the facts exist: every
     * flow-aggregate join on `dim_date` silently drops an event whose day has no row, so the range
     * must also reach the earliest worklog start, sprint start and done time
     * ([MetricsStore.earliestFactEventMs], floored at 50 years back) and every epic window inside the
     * PV horizon ([MetricsStore.currentEpicPlanWindows]) — the pure rule is [dimDateRange].
     * The two READS stay in the caller's transaction (the facts are not committed yet); the write is
     * [MetricsStore.ensureDimDate] over the FULL range — its own short, serialized, committed
     * transaction that writes only the missing or changed rows, so this DERIVE holds no `dim_date`
     * lock and a repeat call over a settled table writes nothing.
     */
    private suspend fun widenDimDate(connectionId: UInt, calendar: WorkingCalendar, createdMin: Long?, now: Long, configRevision: Long) {
        val factMin = metricsStore.earliestFactEventMs(connectionId)
        val earliest = listOfNotNull(createdMin, factMin).minOrNull()
        val range = dimDateRange(now, earliest, metricsStore.currentEpicPlanWindows(connectionId))
        metricsStore.ensureDimDate(calendar, range, configRevision)
    }
}
