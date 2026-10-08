package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.HeartbeatOutcome
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestConnectorOverrideKey
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.RECONCILE_RETRY_BASE_MILLIS
import ch.nokillswit.ingest.backoffMillis
import ch.nokillswit.ingest.MAX_BACKOFF_MILLIS
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.metrics.DomainOwnerResolver
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.registerMetricsHandlers
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.measureTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A bound on the retry loops that keep ticking a scoped [IngestWorker] until ITS OWN job finishes — never an expected real-world count. */
private const val MAX_TICK_ATTEMPTS = 50

/** The manual clock every worker in this class ticks with (2024-01-01T01:00:00Z) — also what the queue fence compares leases against. */
/**
 * A thread-safe virtual monotonic source: the lease-budget tests say how late a failure is observed by [advance]-ing it from
 * the (IO-thread) renewal, which the ticker then reads after its own timeout — so the arithmetic never depends on real timing.
 */
private class VirtualTime : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val nanos = AtomicLong()
    override fun read(): Long = nanos.get()
    fun advance(by: Duration) {
        nanos.addAndGet(by.inWholeNanoseconds)
    }
}

/** `syncJobs()`'s default `max_attempts`; [exhaustAttempts] sets a row's `attempt` to it. */
private const val SYNC_JOB_MAX_ATTEMPTS = 3

private val TICK_CLOCK_MILLIS = java.time.Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()

/**
 * `ingest/IngestWorker.kt` (v0.2.0 plan §5/§11): the scheduler tick, claim+run loop, success/
 * failure scheduling (incl. the backoff cap) and cancellation. Most cases construct [IngestWorker]
 * directly against a fake [Connector] and a manual clock — `tick`/`runJob` are `internal` for
 * exactly this — so a `coroutineScope { worker.tick(this) }` call deterministically waits for
 * every claimed job's launched coroutine to finish before returning (no timing/`delay` races).
 * Shutdown release is the one case that needs the real `configureIngestWorker` Ktor wiring, via
 * the [IngestConnectorOverrideKey] attribute seam.
 */
class IngestWorkerTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))

    /** The PURGE step's generic config-drain dependency (v0.3.0 M1 commit 4) — a fresh instance is fine, it is stateless. */
    private fun metricsConfig(dataSources: DataSourceService) = MetricsConfigService(
        sharedDatabaseForTests(),
        WorkItemStore(sharedDatabaseForTests()),
        dataSources,
        metricsSettings(dataSources),
        domainOwners(),
    )

    /** The shared-revision dependency (`currentRevision` — the DERIVE `CONFIG_CHANGED` check) — a fresh, stateless instance. */
    private fun metricsSettings(dataSources: DataSourceService) =
        MetricsSettingsService(sharedDatabaseForTests(), dataSources, SyncJobsService(sharedDatabaseForTests(), 3))

    private fun domainOwners() = DomainOwnerResolver(sharedDatabaseForTests(), WorkItemStore(sharedDatabaseForTests()))

    /** The PURGE step's OTHER generic dependency (round 1 review: `MetricsStore.purgeAll` had no caller) — a fresh, stateless instance. */
    private fun metricsStore() = MetricsStore(sharedDatabaseForTests())

    /**
     * The job-handler registry the worker dispatches through (checkup D5): the REAL metrics wiring
     * (`registerMetricsHandlers` — the same call `configureMetrics` makes) over fresh, stateless services.
     */
    private fun handlers(
        dataSources: DataSourceService,
        config: MetricsConfigService = metricsConfig(dataSources),
        store: MetricsStore = metricsStore(),
    ) = JobHandlerRegistry().apply {
        registerMetricsHandlers(deriver(dataSources), config, metricsSettings(dataSources), store)
    }

    /** The DERIVE job's dependency (v0.3.0 M3 commit 7) — a fresh instance per test, it is stateless. */
    private fun deriver(dataSources: DataSourceService) = MetricsDeriver(
        WorkItemStore(sharedDatabaseForTests()),
        metricsSettings(dataSources),
        metricsConfig(dataSources),
        domainOwners(),
        TeamMembershipService(sharedDatabaseForTests(), metricsSettings(dataSources)),
        metricsStore(),
        sharedDatabaseForTests(),
    )

    /**
     * One row in EACH of the eight per-connection `metrics.*` config tables (v0.3.0 M1 commit 4
     * review fix) — a direct Exposed insert against `MetricsConfigService`'s own nested table
     * objects (the `TeamService.Teams`/`DataSourceService.Connections` precedent: accessed via the
     * class name, no instance needed), bypassing `replaceConfig`'s reference-data validation
     * entirely since this test only cares that the PURGE drain removes whatever is there.
     */
    private suspend fun seedAllMetricsConfigTables(connId: UInt, teamId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsConfigService.StatusStageMap.insert {
                it[connectionId] = connId
                it[statusId] = "10001"
                it[domainKey] = ""
                it[stage] = "NOT_STARTED"
            }
            MetricsConfigService.FieldConfig.insert {
                it[connectionId] = connId
                it[role] = "EPIC_DUE"
                it[fieldId] = "duedate"
            }
            MetricsConfigService.DomainMap.insert {
                it[connectionId] = connId
                it[projectKey] = "ENG"
                it[domainKey] = "eng"
                it[domainName] = "Engineering"
            }
            MetricsConfigService.BoardTeamMap.insert {
                it[connectionId] = connId
                it[boardId] = 555L
                it[MetricsConfigService.BoardTeamMap.teamId] = teamId
            }
            MetricsConfigService.TeamSprintCapacity.insert {
                it[connectionId] = connId
                it[sprintId] = 999L
                it[capacityMd] = "10.00".toBigDecimal()
            }
            MetricsConfigService.ActivityTypeMap.insert {
                it[connectionId] = connId
                it[issueType] = "Story"
                it[activityType] = "Story"
            }
            MetricsConfigService.WorkCategoryMap.insert {
                it[connectionId] = connId
                it[valueId] = "opt-1"
                it[valueName] = "Product"
                it[category] = "Product Development"
            }
            MetricsConfigService.BlockedStatuses.insert {
                it[connectionId] = connId
                it[statusId] = "10002"
            }
        }
    }

    /** The total row count across all eight per-connection `metrics.*` config tables for [connId]. */
    private suspend fun countAllMetricsConfigRows(connId: UInt): Long = suspendTransaction(sharedDatabaseForTests()) {
        MetricsConfigService.StatusStageMap.selectAll().where { MetricsConfigService.StatusStageMap.connectionId eq connId }.count() +
            MetricsConfigService.FieldConfig.selectAll().where { MetricsConfigService.FieldConfig.connectionId eq connId }.count() +
            MetricsConfigService.DomainMap.selectAll().where { MetricsConfigService.DomainMap.connectionId eq connId }.count() +
            MetricsConfigService.BoardTeamMap.selectAll().where { MetricsConfigService.BoardTeamMap.connectionId eq connId }.count() +
            MetricsConfigService.TeamSprintCapacity.selectAll()
                .where { MetricsConfigService.TeamSprintCapacity.connectionId eq connId }.count() +
            MetricsConfigService.ActivityTypeMap.selectAll().where { MetricsConfigService.ActivityTypeMap.connectionId eq connId }.count() +
            MetricsConfigService.WorkCategoryMap.selectAll().where { MetricsConfigService.WorkCategoryMap.connectionId eq connId }.count() +
            MetricsConfigService.BlockedStatuses.selectAll().where { MetricsConfigService.BlockedStatuses.connectionId eq connId }.count()
    }

    private fun syncJobs(maxAttempts: Int = SYNC_JOB_MAX_ATTEMPTS, clock: () -> Long = System::currentTimeMillis) =
        SyncJobsService(sharedDatabaseForTests(), maxAttempts, clock)

    /** One worker id per test instance, so a hand-claimed row ([claimFor]) is leased to the worker the test builds. */
    private val workerId = "test-worker-${unique("id")}"

    private fun testConfig(
        leaseSeconds: Long = 300,
        workerSlots: Int = 2,
        purgeGraceDays: Long = 7,
    ) = IngestConfig(
        schedulerTickSeconds = 15,
        workerSlots = workerSlots,
        leaseSeconds = leaseSeconds,
        jobRetentionDays = 90,
        purgeGraceDays = purgeGraceDays,
        workerId = workerId,
    )

    /**
     * DISABLED unless [enabled]: only a test that needs the worker's own scheduler tick or claim loop to
     * pick the connection up says so — a disabled connection is never re-derived by another test's
     * worker after a config bump (`.claude/docs/testing.md`), and tests driving `runJob`/`onSucceeded`
     * with a hand-built claim never need the scheduler to see it.
     */
    private suspend fun createConnection(dataSources: DataSourceService, syncIntervalMinutes: Int = 30, enabled: Boolean = false): UInt =
        createConnectionRow(dataSources, syncIntervalMinutes, enabled).also { createdConnections += it }

    /** Every connection this test created: [cleanUpOpenJobs] closes whatever its jobs left RUNNING or PENDING in the shared queue. */
    private val createdConnections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUpOpenJobs() = runBlocking {
        val jobs = syncJobs()
        for (connId in createdConnections) {
            for (job in jobs.list(connId, SyncJobListFilter(), pagingAll()).items) {
                when (job.status) {
                    SyncJobStatus.RUNNING -> jobs.finish(job.id, job.attempt)
                    SyncJobStatus.PENDING -> jobs.requestCancel(connId, job.id)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun createConnectionRow(dataSources: DataSourceService, syncIntervalMinutes: Int, enabled: Boolean): UInt =
        dataSources.create(
            DataSourceRequest(
                name = unique("conn"),
                enabled = enabled,
                syncIntervalMinutes = syncIntervalMinutes,
                backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                    email = "svc-${unique("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("ENG"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )

    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())

    /**
     * A fixed instant well before the default `reconcileHourUtc` (3) boundary on its own day —
     * deterministic regardless of wall-clock time, so `enqueueDue` enqueues ONLY the due SYNC job
     * for a freshly created connection (never also a RECONCILE, which real-time `now` could
     * nondeterministically make due too).
     */
    private fun fixedEarlyMorningClock(): () -> Long = { TICK_CLOCK_MILLIS }

    private class FakeConnector(private val onRun: suspend (SyncJobRunContext) -> Unit = {}) : Connector {
        override val kind = DataSourceKind.JIRA_CLOUD
        override suspend fun testConnection(
            siteUrl: String,
            email: String,
            apiToken: String,
            projectKeys: List<String>,
            authScheme: JiraAuthScheme,
        ): ConnectionTestResult = error("not used by IngestWorkerTest")

        override suspend fun run(context: SyncJobRunContext) = onRun(context)
    }

    /** A job still pending or in flight — the retry loops below keep ticking while this holds. */
    private fun isOpen(status: SyncJobStatus?) = status == null || status == SyncJobStatus.PENDING || status == SyncJobStatus.RUNNING

    @Test
    fun `a tick enqueues a due SYNC job, claims and runs it to success`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 45, enabled = true)
        var ran = false
        val connector = FakeConnector { ran = true }
        // Scoped to just this test's own connection (review round 1: a flat workerSlots = 500 drove
        // tick() to claim-and-RUN up to 500 jobs from the ENTIRE shared test database on every call —
        // now that a claimed DERIVE/PURGE actually does real work (MetricsStore.purgeAll, the
        // config-revision re-derive check), that made the suite slow and its runtime dependent on
        // whatever backlog other test classes happened to leave behind). A modest workerSlots keeps
        // each tick() call cheap; the bounded retry loop (rather than one huge slot count) is what
        // guarantees THIS connection's own jobs are eventually reached regardless of backlog size,
        // so the test stays fast AND order-independent.
        val worker = IngestWorker(
            jobs,
            ds,
            handlers(ds),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 5),
            fixedEarlyMorningClock(),
        )

        // A successful SYNC now also chains a DERIVE job for this connection (v0.3.0 M3 commit 7)
        // — filter to the SYNC kind so this assertion stays about the job under test.
        suspend fun syncJob() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.singleOrNull()
        // v0.3.0 M3 commit 7: a successful SYNC chains a scheduled DERIVE job for the SAME
        // connection — keep ticking (bounded) until each reaches a terminal status, then assert both
        // actually SUCCEEDED (review round 1: a bare "!= FAILED" tolerated a DERIVE job that simply
        // had not been claimed yet within a single tick).
        suspend fun deriveJob() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items.singleOrNull()
        var job = syncJob()
        var derive = deriveJob()
        var attempts = 0
        // ONE fence around both tick loops — see withOnlyConnections (TestEnvironment.kt).
        withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) {
            while (isOpen(job?.status) && attempts < MAX_TICK_ATTEMPTS) {
                coroutineScope { worker.tick(this) }
                job = syncJob()
                attempts++
            }
            attempts = 0
            derive = deriveJob()
            while (isOpen(derive?.status) && attempts < MAX_TICK_ATTEMPTS) {
                coroutineScope { worker.tick(this) }
                derive = deriveJob()
                attempts++
            }
        }

        assertTrue(ran, "the claimed job's connector.run() must have executed")
        assertEquals(SyncJobStatus.SUCCEEDED, job?.status)
        val connection = assertNotNull(ds.read(connId))
        assertNotNull(connection.status.lastSyncSucceededAt)
        assertEquals(0, connection.status.consecutiveFailures)
        assertEquals(SyncJobStatus.SUCCEEDED, derive?.status, "the chained DERIVE job must actually succeed, not merely avoid FAILED")
    }

    @Test
    fun `a failing run schedules a backoff and is FAILED, not silently dropped`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        // The cause's message embeds a URL query, as Ktor's connect-timeout IOException's does: it must never reach the log.
        val transport = java.io.IOException("Connect timeout has expired [url=https://x.atlassian.net/rest/api/3/search/jql?jql=SECRET]")
        val connector = FakeConnector { throw IllegalStateException("simulated stream failure", transport) }
        val logs = LogCapture("ch.nokillswit.ingest.IngestWorker")
        // See the identical workerSlots note in the "success" test above.
        val worker = IngestWorker(
            jobs,
            ds,
            handlers(ds),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        try {
            withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) { coroutineScope { worker.tick(this) } }
        } finally {
            logs.detach()
        }

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single()
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals("RUN_FAILED", job.errorCode)
        assertEquals("IllegalStateException: simulated stream failure", job.errorDetail)
        val logged = assertNotNull(logs.events.singleOrNull { it.formattedMessage.startsWith("Sync job ${job.id} (SYNC)") }?.throwableProxy)
        assertEquals("IllegalStateException: simulated stream failure", logged.message)
        // Every cause is logged as its class name alone (coroutine stack-trace recovery may add a copy of the top one).
        val causeMessages = generateSequence(logged.cause) { it.cause }.map { it.message }.toList()
        assertTrue("java.io.IOException" in causeMessages, "the transport cause is still named: $causeMessages")
        assertTrue(causeMessages.all { it == it?.trim() && it?.contains(' ') == false }, "only class names: $causeMessages")
        val connection = assertNotNull(ds.read(connId))
        assertEquals(1, connection.status.consecutiveFailures)
        assertEquals("RUN_FAILED", connection.status.lastSyncErrorCode)
    }

    @Test
    fun `a stream's heartbeat writes progress and current_stream onto the sync job`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, enabled = true)
        val connector = FakeConnector { context -> context.heartbeat("""{"pages":1}""", "issues") }
        val worker = IngestWorker(
            jobs,
            ds,
            handlers(ds),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) { coroutineScope { worker.tick(this) } }

        // Same DERIVE-chaining note as the "success" test above.
        val job = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.single()
        assertEquals("issues", job.currentStream)
        assertEquals(1L, job.progress?.get("pages")?.jsonPrimitive?.long)
    }

    /**
     * A [SyncJobClaim] for a REAL, just-`requestJob`'d row, which this helper puts into the state a claim would have
     * (RUNNING, `attempt = 1`, a lease no test clock reaches) — deliberately NOT `jobs.claim(...)`: this shared test database
     * accumulates other tests'/classes' own still-PENDING manual jobs across the whole suite run, and a bare `claim()` call can
     * pick up one of THOSE instead of ours. The terminal writes are fenced on `(id, attempt, status = RUNNING)`, so the row
     * must really be RUNNING for `IngestWorker.runJob`/`onSucceeded` driven by this claim to be exactly as real as going
     * through the queue for what these tests check (the job's terminal status, the generic drain).
     */
    private suspend fun claimFor(jobId: UInt, connId: UInt, kind: SyncJobKind): SyncJobClaim {
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[attempt] = 1
                it[leaseOwner] = workerId
                it[leaseUntil] = PARKED_LEASE_UNTIL
            }
        }
        return SyncJobClaim(
            id = jobId,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = kind,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )
    }

    @Test
    fun `a PURGE job drains all eight metrics config tables, and a second PURGE is a no-op`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val teamId = TestTeams.seed(unique("purge-team"))
        seedAllMetricsConfigTables(connId, teamId)
        assertEquals(8L, countAllMetricsConfigRows(connId), "fixture must seed exactly one row per table")

        val config = testConfig(workerSlots = 500)
        val connector = FakeConnector { } // succeeds trivially — the connector's own purgeSteps are empty here
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis,
        )

        val firstJobId = jobs.requestJob(connId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(firstJobId, connId, SyncJobKind.PURGE))
        assertEquals(0L, countAllMetricsConfigRows(connId), "PURGE must drain every per-connection metrics config row")
        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, firstJobId)?.status)

        // A second PURGE (nothing left to drain) must still succeed — the deletes are no-ops, not errors.
        val secondJobId = jobs.requestJob(connId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(secondJobId, connId, SyncJobKind.PURGE))
        assertEquals(0L, countAllMetricsConfigRows(connId))
        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, secondJobId)?.status)
    }

    /** A minimal, valid `metrics.fact_task_delivery` row for [connId] — every NOT NULL column filled. */
    private fun minimalFactTaskDeliveryRow(issueId: Long) = ch.nokillswit.metrics.FactTaskDeliveryRow(
        issueId = issueId,
        issueKey = "ENG-$issueId",
        createdAt = 0,
        startedAt = null,
        doneAt = null,
        reopenCount = 0,
        estimateAtStartMd = null,
        estimateAtDoneMd = null,
        estimateCurrentMd = null,
        estimateSource = "NONE",
        estimateChangesAfterStart = 0,
        estimatedLate = false,
        actualMd = 0.0,
        hasWorklogs = false,
        blockedMs = 0,
        blockedWorkingDays = 0.0,
        cycleMs = null,
        cycleWorkingDays = null,
        leadMs = null,
        leadWorkingDays = null,
        activeMs = 0,
        waitMs = 0,
        assigneeAccountIdAtDone = null,
        assigneeTeamIdAtDone = null,
        sprintIdAtDone = null,
        sprintTeamIdAtDone = null,
        creditTeamId = null,
        currentTeamId = null,
        currentAssigneeAccountId = null,
        domainKey = null,
        epicId = null,
        epicDomainKey = null,
        crossDomain = false,
        activityType = "Task",
        workCategory = null,
        isSubtask = false,
        parentTaskId = null,
        currentStage = "NOT_STARTED",
        flags = emptyList(),
    )

    /** A raw `metrics.fact_sprint_snapshot` row for [connId] — the Exposed table object (`MetricsTables.FactSprintSnapshot`)
     * only declares its PK columns (commit 7 has no writer for this table yet), so every other NOT NULL column is filled
     * via a literal `exec` insert instead. */
    private suspend fun insertRawSnapshotRow(connId: UInt, sprintId: Long) {
        suspendTransaction(sharedDatabaseForTests()) {
            exec(
                """
                INSERT INTO metrics.fact_sprint_snapshot
                    (connection_id, sprint_id, config_revision, processing_version, snapshot_at)
                VALUES ($connId, $sprintId, 1, 1, 0)
                """.trimIndent(),
            )
        }
    }

    private suspend fun countSnapshotRows(connId: UInt): Long = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprintSnapshot.selectAll().where { MetricsTables.FactSprintSnapshot.connectionId eq connId }.count()
    }

    @Test
    fun `a PURGE job also drains metrics star rows and fact_sprint_snapshot rows for the connection`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val store = metricsStore()
        val connId = createConnection(ds)

        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, listOf(minimalFactTaskDeliveryRow(1L)), configRevision = 1L)
        }
        insertRawSnapshotRow(connId, sprintId = 999L)
        val factCountBefore = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactTaskDelivery.selectAll().where { MetricsTables.FactTaskDelivery.connectionId eq connId }.count()
        }
        assertEquals(1L, factCountBefore, "fixture must seed exactly one fact_task_delivery row")
        assertEquals(1L, countSnapshotRows(connId), "fixture must seed exactly one fact_sprint_snapshot row")

        val config = testConfig(workerSlots = 500)
        val connector = FakeConnector { }
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics, store),
            mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(jobId, connId, SyncJobKind.PURGE))

        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, jobId)?.status)
        val factCountAfter = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactTaskDelivery.selectAll().where { MetricsTables.FactTaskDelivery.connectionId eq connId }.count()
        }
        assertEquals(0L, factCountAfter, "PURGE must drain the derived metrics star (review round 1: MetricsStore.purgeAll had no caller)")
        assertEquals(0L, countSnapshotRows(connId), "PURGE must drain fact_sprint_snapshot rows too, via the allow-delete bypass")
    }

    @Test
    fun `fact_sprint_snapshot rows are immutable outside the PURGE bypass`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        insertRawSnapshotRow(connId, sprintId = 111L)

        // An ordinary UPDATE must raise — the trigger rejects it unless `metrics.allow_snapshot_delete` is SET LOCAL 'on'.
        assertFailsWith<Exception>("an ordinary UPDATE on a snapshot row must raise, not silently succeed") {
            suspendTransaction(sharedDatabaseForTests()) {
                exec("UPDATE metrics.fact_sprint_snapshot SET processing_version = 2 WHERE connection_id = $connId AND sprint_id = 111")
            }
        }

        // An ordinary DELETE must raise too (review round 1: a BEFORE ROW trigger returning NULL
        // unconditionally would silently SKIP even this permitted-by-app-code DELETE rather than
        // actually deleting nothing while raising for UPDATE — this pins the DELETE side explicitly).
        assertFailsWith<Exception>("an ordinary DELETE on a snapshot row must raise, not silently no-op") {
            suspendTransaction(sharedDatabaseForTests()) {
                exec("DELETE FROM metrics.fact_sprint_snapshot WHERE connection_id = $connId AND sprint_id = 111")
            }
        }
        assertEquals(1L, countSnapshotRows(connId), "the row must still be there after both rejected mutations")

        // The PURGE bypass: SET LOCAL the allow flag, then the SAME delete succeeds.
        suspendTransaction(sharedDatabaseForTests()) {
            exec("SET LOCAL metrics.allow_snapshot_delete = 'on'")
            exec("DELETE FROM metrics.fact_sprint_snapshot WHERE connection_id = $connId AND sprint_id = 111")
        }
        assertEquals(0L, countSnapshotRows(connId), "the bypass must actually delete the row, not silently skip it too")
    }

    @Test
    fun `onSucceeded enqueues a fresh DERIVE when the run's own config revision is now stale`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )

        val staleJobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val revisionAtStart = metricsSettings(ds).currentRevision()
        // Simulate a config PUT landing WHILE this DERIVE run was in flight — the exact race
        // `uq_sync_jobs_open_per_kind` coalescing would otherwise swallow (review round 1 fix).
        // The settings singleton is suite-global: restore it afterwards (withMetricsSettings) so
        // no later test derives under a leaked hoursPerDay.
        withMetricsSettings(metricsSettings(ds), { it.copy(hoursPerDay = it.hoursPerDay + 1) }) {
            assertTrue(metricsSettings(ds).currentRevision() > revisionAtStart, "the bump must actually move the shared revision")
            worker.onSucceeded(claimFor(staleJobId, connId, SyncJobKind.DERIVE), deriveRevisionUsed = revisionAtStart)
        }

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(2, deriveJobs.size, "a fresh DERIVE must be enqueued once this run's own revision is found stale")
        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, staleJobId)?.status, "the original run itself still finishes normally")
    }

    @Test
    fun `onSucceeded does not re-enqueue DERIVE when the run's revision is still current`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val currentRevision = metricsSettings(ds).currentRevision()

        worker.onSucceeded(claimFor(jobId, connId, SyncJobKind.DERIVE), deriveRevisionUsed = currentRevision)

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(1, deriveJobs.size, "no fresh DERIVE should be enqueued when nothing about the config changed")
    }

    @Test
    fun `a DERIVE job with no registered handler FAILS instead of silently succeeding`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, JobHandlerRegistry(),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(jobId, connId, SyncJobKind.DERIVE))

        assertEquals(SyncJobStatus.FAILED, jobs.read(connId, jobId)?.status, "a missing DERIVE handler is a wiring error, not a success")
    }

    @Test
    fun `onSucceeded chains a DERIVE after a successful RECONCILE`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )
        val reconcileJobId = jobs.requestJob(connId, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L).jobId

        worker.onSucceeded(claimFor(reconcileJobId, connId, SyncJobKind.RECONCILE))

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(1, deriveJobs.size, "a successful RECONCILE must chain exactly one DERIVE job")
    }

    @Test
    fun `onSucceeded chains a DERIVE after a successful REPROCESS`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )
        val reprocessJobId = jobs.requestJob(connId, SyncJobKind.REPROCESS, requestedByUserId = 1u, configRevision = 1L).jobId

        worker.onSucceeded(claimFor(reprocessJobId, connId, SyncJobKind.REPROCESS))

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(1, deriveJobs.size, "a successful REPROCESS must chain exactly one DERIVE job")
    }

    @Test
    fun `a connector purge failure prevents the generic metrics-config drain and FAILS the job`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val teamId = TestTeams.seed(unique("purge-fail-team"))
        seedAllMetricsConfigTables(connId, teamId)

        val config = testConfig(workerSlots = 500)
        val connector = FakeConnector { error("simulated connector purge failure") }
        val worker = IngestWorker(
            jobs, ds, handlers(ds, metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(jobId, connId, SyncJobKind.PURGE))

        assertEquals(SyncJobStatus.FAILED, jobs.read(connId, jobId)?.status, "retry-safe: the job must be retryable, not silently lost")
        assertEquals(
            8L,
            countAllMetricsConfigRows(connId),
            "the generic drain must never run when the connector's OWN purge step throws first",
        )
    }

    @Test
    fun `backoff is interval times 2 pow failures, capped at six hours`() {
        val oneMinute = 60_000L
        assertEquals(oneMinute, backoffMillis(oneMinute, failures = 0))
        assertEquals(oneMinute * 2, backoffMillis(oneMinute, failures = 1))
        assertEquals(oneMinute * 4, backoffMillis(oneMinute, failures = 2))
        assertEquals(MAX_BACKOFF_MILLIS, backoffMillis(oneMinute, failures = 20), "must cap rather than overflow or grow unbounded")
    }

    /** The connection's RECONCILE back-off columns and `last_reconcile_at`, read straight from the row. */
    private data class ReconcileState(val failures: Int, val nextAt: Long?, val lastAt: Long?)

    private suspend fun reconcileState(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
        val row = DataSourceService.Connections.selectAll().where { DataSourceService.Connections.id eq connId }.toList().single()
        ReconcileState(
            row[DataSourceService.Connections.reconcileFailures],
            row[DataSourceService.Connections.nextReconcileAt],
            row[DataSourceService.Connections.lastReconcileAt],
        )
    }

    /** Pushes `next_sync_at` far out so a tick enqueues ONLY the RECONCILE under test (never a SYNC and its chained DERIVE). */
    private suspend fun silenceSync(connId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connId }) {
                it[nextSyncAt] = PARKED_LEASE_UNTIL
            }
        }
    }

    @Test
    fun `recordReconcileFailed backs off 15 minutes doubling to the six hour cap, and a success resets it`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        val now = TICK_CLOCK_MILLIS
        val delays = (1..7).map {
            ds.recordReconcileFailed(connId, now)
            val state = reconcileState(connId)
            assertEquals(it, state.failures)
            assertNotNull(state.nextAt) - now
        }
        assertEquals((0..6).map { backoffMillis(RECONCILE_RETRY_BASE_MILLIS, it) }, delays)
        assertEquals(RECONCILE_RETRY_BASE_MILLIS, delays.first())
        assertEquals(MAX_BACKOFF_MILLIS, delays.last())
        assertEquals(null, reconcileState(connId).lastAt, "a failure never stamps last_reconcile_at")

        ds.recordReconcileSucceeded(connId, now)
        assertEquals(ReconcileState(failures = 0, nextAt = null, lastAt = now), reconcileState(connId))
    }

    @Test
    fun `a failed RECONCILE is not re-enqueued on the next tick, but is once its back-off elapsed`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, enabled = true)
        silenceSync(connId)
        val runs = AtomicInteger()
        val connector = FakeConnector { runs.incrementAndGet(); error("simulated reconcile failure") }
        // 05:00 UTC: two hours past the connection's 03:00 reconcile boundary, so RECONCILE is due (and a SYNC is not).
        val start = TICK_CLOCK_MILLIS + 4 * 60 * MILLIS_PER_MINUTE
        val clock = AtomicLong(start)
        val worker = IngestWorker(
            jobs, ds, handlers(ds), mapOf(DataSourceKind.JIRA_CLOUD to connector), testConfig(workerSlots = 5), clock::get,
        )
        suspend fun reconcileJobs() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.RECONCILE), pagingAll()).items

        withOnlyConnections(setOf(connId), start + 60 * MILLIS_PER_MINUTE) {
            coroutineScope { worker.tick(this) }
            assertEquals(1, runs.get())
            assertEquals(listOf(SyncJobStatus.FAILED), reconcileJobs().map { it.status })
            assertEquals(ReconcileState(1, start + RECONCILE_RETRY_BASE_MILLIS, null), reconcileState(connId))

            // The next scheduler ticks (15 s later, and just before the 15 minute back-off ends) enqueue nothing.
            for (offset in listOf(15_000L, RECONCILE_RETRY_BASE_MILLIS - 1)) {
                clock.set(start + offset)
                coroutineScope { worker.tick(this) }
                assertEquals(1, reconcileJobs().size, "no new RECONCILE inside the back-off (offset $offset ms)")
                assertEquals(1, runs.get())
            }

            // The back-off has elapsed: exactly one more attempt, which fails again and doubles the delay.
            val retryAt = start + RECONCILE_RETRY_BASE_MILLIS
            clock.set(retryAt)
            coroutineScope { worker.tick(this) }
            assertEquals(2, runs.get())
            assertEquals(2, reconcileJobs().size)
            assertEquals(ReconcileState(2, retryAt + 2 * RECONCILE_RETRY_BASE_MILLIS, null), reconcileState(connId))
        }
    }

    @Test
    fun `a successful RECONCILE after failures resets the back-off and stamps last_reconcile_at`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, enabled = true)
        silenceSync(connId)
        val start = TICK_CLOCK_MILLIS + 4 * 60 * MILLIS_PER_MINUTE
        ds.recordReconcileFailed(connId, start)
        ds.recordReconcileFailed(connId, start)
        val retryAt = start + 2 * RECONCILE_RETRY_BASE_MILLIS // the second failure's delay (30 minutes) from the same instant
        assertEquals(ReconcileState(2, retryAt, null), reconcileState(connId))
        val worker = IngestWorker(
            jobs, ds, handlers(ds), mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(workerSlots = 5), { retryAt },
        )

        withOnlyConnections(setOf(connId), retryAt + 60 * MILLIS_PER_MINUTE) { coroutineScope { worker.tick(this) } }

        val job = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.RECONCILE), pagingAll()).items.single()
        assertEquals(SyncJobStatus.SUCCEEDED, job.status)
        assertEquals(ReconcileState(0, null, retryAt), reconcileState(connId))
    }

    /** The connection's SYNC back-off columns and last error code, read straight from the row. */
    private data class SyncState(val failures: Int, val nextAt: Long?, val errorCode: String?)

    private suspend fun syncState(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
        val row = DataSourceService.Connections.selectAll().where { DataSourceService.Connections.id eq connId }.toList().single()
        SyncState(
            row[DataSourceService.Connections.consecutiveFailures],
            row[DataSourceService.Connections.nextSyncAt],
            row[DataSourceService.Connections.lastSyncErrorCode],
        )
    }

    /** Puts [jobId] (a PENDING row) at `max_attempts`, as a worker crash loop leaves it: the next claim fails it `RETRIES_EXHAUSTED`. */
    private suspend fun exhaustAttempts(jobId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[attempt] = SYNC_JOB_MAX_ATTEMPTS }
        }
    }

    @Test
    fun `a SYNC job that exhausts its attempts records the back-off, so the next ticks enqueue no fresh job`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        val runs = AtomicInteger()
        val clock = AtomicLong(TICK_CLOCK_MILLIS)
        val worker = IngestWorker(
            jobs, ds, handlers(ds), mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { runs.incrementAndGet() }),
            testConfig(workerSlots = 5), clock::get,
        )
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = TICK_CLOCK_MILLIS)
        exhaustAttempts(jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id)
        suspend fun syncJobsOf() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items

        withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) {
            coroutineScope { worker.tick(this) }
            val closed = syncJobsOf().single()
            assertEquals(SyncJobStatus.FAILED, closed.status)
            assertEquals("RETRIES_EXHAUSTED", closed.errorCode)
            assertEquals(0, runs.get(), "an exhausted job is never run")
            assertEquals(SyncState(1, TICK_CLOCK_MILLIS + backoffMillis(30 * MILLIS_PER_MINUTE, 0), "RETRIES_EXHAUSTED"), syncState(connId))

            clock.set(TICK_CLOCK_MILLIS + 15_000L)
            coroutineScope { worker.tick(this) }
            assertEquals(1, syncJobsOf().size, "the next scheduler tick must not enqueue a fresh SYNC inside the back-off")
        }
        assertTrue(ds.dueForSync(TICK_CLOCK_MILLIS + 30 * MILLIS_PER_MINUTE).any { it.id == connId }, "due again once the back-off elapsed")
        assertTrue(ds.dueForSync(TICK_CLOCK_MILLIS + 30 * MILLIS_PER_MINUTE - 1).none { it.id == connId })
    }

    @Test
    fun `a RECONCILE job that exhausts its attempts records the reconcile back-off, so no fresh job is enqueued`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, enabled = true)
        silenceSync(connId)
        val start = TICK_CLOCK_MILLIS + 4 * 60 * MILLIS_PER_MINUTE // 05:00 UTC, past the 03:00 boundary, so RECONCILE is due
        val clock = AtomicLong(start)
        val runs = AtomicInteger()
        val worker = IngestWorker(
            jobs, ds, handlers(ds), mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { runs.incrementAndGet() }),
            testConfig(workerSlots = 5), clock::get,
        )
        jobs.enqueueScheduled(connId, SyncJobKind.RECONCILE, configRevision = 1L, now = start)
        exhaustAttempts(jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id)
        suspend fun reconcileJobs() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.RECONCILE), pagingAll()).items

        withOnlyConnections(setOf(connId), start) {
            coroutineScope { worker.tick(this) }
            val closed = reconcileJobs().single()
            assertEquals(SyncJobStatus.FAILED, closed.status)
            assertEquals("RETRIES_EXHAUSTED", closed.errorCode)
            assertEquals(0, runs.get())
            assertEquals(ReconcileState(1, start + RECONCILE_RETRY_BASE_MILLIS, null), reconcileState(connId))
            assertEquals(SyncState(0, PARKED_LEASE_UNTIL, null), syncState(connId), "a RECONCILE failure never touches SYNC's back-off")

            clock.set(start + 15_000L)
            coroutineScope { worker.tick(this) }
            assertEquals(1, reconcileJobs().size, "the next scheduler tick must not enqueue a fresh RECONCILE inside the back-off")
        }
        assertTrue(ds.dueForReconcile(start + RECONCILE_RETRY_BASE_MILLIS).any { it.id == connId })
    }

    @Test
    fun `a failed job records its SYNC back-off in the closing transaction, a stale failure none, DERIVE has no schedule`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 30)
        val syncJobId = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        val syncClaim = claimFor(syncJobId, connId, SyncJobKind.SYNC)

        assertTrue(jobs.fail(syncClaim.id, syncClaim.attempt, "RUN_FAILED", now = TICK_CLOCK_MILLIS))
        assertEquals(SyncJobStatus.FAILED, jobs.read(connId, syncClaim.id)?.status)
        // Visible the moment the job is FAILED: there is no window where the job is closed but the connection is still due.
        val expected = SyncState(1, TICK_CLOCK_MILLIS + backoffMillis(30 * MILLIS_PER_MINUTE, 0), "RUN_FAILED")
        assertEquals(expected, syncState(connId))

        val staleFail = jobs.fail(syncClaim.id, syncClaim.attempt, "RUN_FAILED", now = TICK_CLOCK_MILLIS + 1)
        assertEquals(false, staleFail, "a stale failure is fenced out")
        assertEquals(expected, syncState(connId), "and records no back-off")

        val deriveJobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val deriveClaim = claimFor(deriveJobId, connId, SyncJobKind.DERIVE)
        assertTrue(jobs.fail(deriveClaim.id, deriveClaim.attempt, "RUN_FAILED", now = TICK_CLOCK_MILLIS))
        assertEquals(expected, syncState(connId), "DERIVE has no schedule on the connection")
        assertEquals(ReconcileState(0, null, null), reconcileState(connId))
    }

    /** A full-replace PUT body equal to the stored connection (same site, no token): only what the caller `copy`s changes. */
    private suspend fun DataSourceService.putRequest(connId: UInt): DataSourceRequest {
        val stored = assertNotNull(read(connId))
        return DataSourceRequest(
            name = stored.name,
            enabled = stored.enabled,
            syncIntervalMinutes = stored.syncIntervalMinutes,
            backfillFrom = stored.backfillFrom,
            reconcileHourUtc = stored.reconcileHourUtc,
            jira = JiraConnectionRequest(
                siteUrl = stored.jira.siteUrl,
                email = stored.jira.email,
                apiToken = null,
                projectKeys = stored.jira.projectKeys,
                authScheme = stored.jira.authScheme,
            ),
        )
    }

    @Test
    fun `a config PUT lifts the SYNC and RECONCILE failure back-off, so the fixed connection is due on the next tick`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        val now = TICK_CLOCK_MILLIS + 4 * 60 * MILLIS_PER_MINUTE // 05:00 UTC: past the reconcile boundary
        repeat(2) {
            ds.recordSyncOutcome(connId, succeeded = false, errorCode = "RUN_FAILED", now = now)
            ds.recordReconcileFailed(connId, now)
        }
        assertEquals(2, syncState(connId).failures)
        assertTrue(ds.dueForSync(now).none { it.id == connId } && ds.dueForReconcile(now).none { it.id == connId }, "backing off")

        // A rotated token (any save bumps config_revision, so it lifts the back-off with it).
        val put = ds.putRequest(connId)
        assertNotNull(ds.update(connId, put.copy(jira = put.jira.copy(apiToken = "rotated-${UUID.randomUUID()}"))))

        assertEquals(SyncState(0, null, "RUN_FAILED"), syncState(connId), "the last error stays; the streak and schedule are cleared")
        assertEquals(ReconcileState(0, null, null), reconcileState(connId))
        assertTrue(ds.dueForSync(now).any { it.id == connId }, "SYNC is due on the very next tick")
        assertTrue(ds.dueForReconcile(now).any { it.id == connId }, "and so is RECONCILE")
    }

    @Test
    fun `a config PUT clears only the back-off that is set - SYNC alone, RECONCILE alone`() = runBlocking {
        val ds = dataSources()
        val now = TICK_CLOCK_MILLIS + 4 * 60 * MILLIS_PER_MINUTE
        val syncOnly = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        ds.recordSyncOutcome(syncOnly, succeeded = true, errorCode = null, now = now)
        ds.recordSyncOutcome(syncOnly, succeeded = false, errorCode = "RUN_FAILED", now = now)
        val reconcileOnly = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        ds.recordSyncOutcome(reconcileOnly, succeeded = true, errorCode = null, now = now)
        ds.recordReconcileFailed(reconcileOnly, now)
        val reconcileOnlySync = syncState(reconcileOnly)
        val failedReconcile = reconcileState(reconcileOnly)
        assertEquals(1, failedReconcile.failures)

        assertNotNull(ds.update(syncOnly, ds.putRequest(syncOnly)))
        assertNotNull(ds.update(reconcileOnly, ds.putRequest(reconcileOnly)))

        assertEquals(SyncState(0, null, "RUN_FAILED"), syncState(syncOnly))
        assertEquals(ReconcileState(0, null, null), reconcileState(syncOnly), "RECONCILE had no back-off, so it is untouched")
        assertEquals(reconcileOnlySync, syncState(reconcileOnly), "SYNC had no back-off, so its schedule is untouched")
        assertEquals(ReconcileState(0, null, null), reconcileState(reconcileOnly), "the RECONCILE back-off is cleared")
    }

    @Test
    fun `a job that started before a config PUT does not re-impose the back-off the PUT cleared`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        ds.recordSyncOutcome(connId, succeeded = false, errorCode = "EARLIER", now = TICK_CLOCK_MILLIS)
        val staleJobId = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        val staleClaim = claimFor(staleJobId, connId, SyncJobKind.SYNC)

        assertNotNull(ds.update(connId, ds.putRequest(connId))) // config_revision 1 -> 2, the back-off is cleared
        assertEquals(SyncState(0, null, "EARLIER"), syncState(connId))
        assertTrue(jobs.fail(staleClaim.id, staleClaim.attempt, "RUN_FAILED", now = TICK_CLOCK_MILLIS + 1))

        assertEquals(SyncJobStatus.FAILED, jobs.read(connId, staleJobId)?.status, "the job itself is still closed")
        assertEquals(SyncState(0, null, "EARLIER"), syncState(connId), "but the connection stays due")
        assertTrue(ds.dueForSync(TICK_CLOCK_MILLIS + 1).any { it.id == connId })
    }

    @Test
    fun `an exhausted job of a stale config revision records no back-off, and an exhausted DERIVE writes nothing`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val staleConn = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        val deriveConn = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        jobs.enqueueScheduled(staleConn, SyncJobKind.SYNC, configRevision = 1L, now = TICK_CLOCK_MILLIS)
        jobs.enqueueScheduled(deriveConn, SyncJobKind.DERIVE, configRevision = 1L, now = TICK_CLOCK_MILLIS)
        exhaustAttempts(jobs.list(staleConn, SyncJobListFilter(), pagingAll()).items.single().id)
        exhaustAttempts(jobs.list(deriveConn, SyncJobListFilter(), pagingAll()).items.single().id)
        assertNotNull(ds.update(staleConn, ds.putRequest(staleConn))) // the job was enqueued for revision 1; the connection is at 2

        withOnlyConnections(setOf(staleConn, deriveConn), TICK_CLOCK_MILLIS) {
            assertEquals(null, jobs.claim(workerId, leaseSeconds = 60, now = TICK_CLOCK_MILLIS))
        }

        for ((connId, kind) in listOf(staleConn to SyncJobKind.SYNC, deriveConn to SyncJobKind.DERIVE)) {
            val job = jobs.list(connId, SyncJobListFilter(kind = kind), pagingAll()).items.single()
            assertEquals(SyncJobStatus.FAILED, job.status)
            assertEquals("RETRIES_EXHAUSTED", job.errorCode)
            assertEquals(SyncState(0, null, null), syncState(connId), "no back-off for $kind")
            assertEquals(ReconcileState(0, null, null), reconcileState(connId))
        }
    }

    @Test
    fun `a config PUT leaves a healthy connection's schedule alone`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds, syncIntervalMinutes = 30, enabled = true)
        ds.recordSyncOutcome(connId, succeeded = true, errorCode = null, now = TICK_CLOCK_MILLIS)
        val scheduled = syncState(connId)
        assertEquals(TICK_CLOCK_MILLIS + 30 * MILLIS_PER_MINUTE, scheduled.nextAt)

        assertNotNull(ds.update(connId, ds.putRequest(connId).let { it.copy(name = unique("renamed")) }))

        assertEquals(scheduled, syncState(connId), "a rename must not trigger an extra sync")
    }

    @Test
    fun `a cancel request during a run is honoured - the job stops and is CANCELLED`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val requested = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        // lease 6 s: the first renewal is due at 2 s; even one that is cut by its 0.6 s slack is retried inside the budget (E < 3.8 s).
        val config = testConfig(leaseSeconds = 6)
        // The claim's lease_owner MUST match config.workerId — otherwise the worker's own
        // heartbeat() call (keyed on config.workerId) never matches this row and throws
        // LeaseLostException instead of ever reaching the cancel-request check.
        // Fenced (testing.md): claim() scans the WHOLE shared queue, and earlier tests in this class leave manual PENDING jobs behind.
        val claimClock = System.currentTimeMillis()
        val claim = withOnlyConnections(setOf(connId), claimClock) { jobs.claim(config.workerId, leaseSeconds = 6, now = claimClock) }
        assertNotNull(claim)
        assertEquals(requested.jobId, claim.id)

        val started = CompletableDeferred<Unit>()
        val connector = FakeConnector {
            started.complete(Unit)
            awaitCancellation()
        }
        val worker = IngestWorker(
            jobs,
            ds,
            handlers(ds),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            config,
            System::currentTimeMillis,
        )

        val running = async { worker.runJob(claim) }
        started.await()
        jobs.requestCancel(connId, claim.id)
        withTimeout(10_000) { running.await() }

        val job = jobs.read(connId, claim.id)
        assertEquals(SyncJobStatus.CANCELLED, job?.status)
    }

    /**
     * Claims a fresh SYNC through a real [SyncJobsService] (so the row is RUNNING under the test worker's id), then runs it
     * on a worker whose ticker renews the lease through [renewal] — `call` counts the ticker's attempts from 0, `time` is the
     * VIRTUAL monotonic source the lease budget reads (the renewal advances it to say how late a failure is observed, so the
     * budget arithmetic never depends on real timing), `real` is the genuine [SyncJobsService.renewLease]. Returns the final
     * row, the wall time `runJob` took and the number of renewal attempts.
     *
     * Lease 3 s: renewal every 1 s, retry 1 s, slack 0.5 s (the floor) — a failure observed at virtual `E` is tolerated only
     * while `E + 1 s + 2 x 0.5 s < 3 s`, i.e. `E < 1 s`.
     */
    private suspend fun runSyncWithRenewal(
        onRun: suspend () -> Unit,
        renewal: suspend (call: Int, time: VirtualTime, real: suspend () -> HeartbeatOutcome) -> HeartbeatOutcome,
    ): RenewalRun {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val requested = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        val config = testConfig(leaseSeconds = 3)
        val time = VirtualTime()
        val claim = withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) {
            jobs.claim(config.workerId, leaseSeconds = 3, now = TICK_CLOCK_MILLIS)
        }
        assertNotNull(claim)
        assertEquals(requested.jobId, claim.id)
        val calls = AtomicInteger()
        val worker = IngestWorker(
            jobs, ds, handlers(ds), mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { onRun() }), config, System::currentTimeMillis,
            renewLease = { c, now, bound ->
                renewal(calls.getAndIncrement(), time) {
                    jobs.renewLease(c.id, config.workerId, c.attempt, config.leaseSeconds, now, bound)
                }
            },
            timeSource = time,
        )
        val elapsed = try {
            measureTime { withTimeout(30_000) { worker.runJob(claim, time.markNow()) } }
        } finally {
            worker.close()
        }
        return RenewalRun(jobs.read(connId, claim.id), elapsed, calls.get())
    }

    private class RenewalRun(val job: SyncJobResponse?, val elapsed: Duration, val renewalAttempts: Int)

    @Test
    fun `one failed heartbeat is tolerated - the long job still SUCCEEDS`() = runBlocking {
        // Real timeline: the failed renewal at 1 s, its retry at 2 s — the job runs to 3.2 s, over a second past the retry.
        val run = runSyncWithRenewal(onRun = { delay(3_200) }) { call, time, real ->
            if (call == 0) {
                time.advance(400.milliseconds) // observed at E = 0.4 s < 1 s: tolerated
                error("simulated transient heartbeat failure")
            }
            real()
        }
        assertEquals(SyncJobStatus.SUCCEEDED, run.job?.status)
        assertTrue(run.renewalAttempts >= 2, "the failed heartbeat was retried")
    }

    @Test
    fun `heartbeats failing until the lease edge stop the job - FAILED instead of running on`() = runBlocking {
        val run = runSyncWithRenewal(onRun = { awaitCancellation() }) { call, time, _ ->
            time.advance(if (call == 0) 400.milliseconds else 1_500.milliseconds) // E = 0.4 s tolerated, then E = 1.9 s: past the threshold
            error("simulated transient heartbeat failure")
        }
        assertEquals(SyncJobStatus.FAILED, run.job?.status)
        assertEquals("RUN_FAILED", run.job?.errorCode)
        assertEquals(2, run.renewalAttempts)
    }

    @Test
    fun `a heartbeat that HANGS is cut by its timeout - the job stops instead of waiting on it`() = runBlocking {
        val run = runSyncWithRenewal(onRun = { awaitCancellation() }) { _, time, _ ->
            time.advance(2.seconds) // by the time the 0.5 s timeout cuts it, the budget is spent
            awaitCancellation()
        }
        assertEquals(SyncJobStatus.FAILED, run.job?.status)
        assertEquals(1, run.renewalAttempts)
        // Real time: the first renewal is due at 1 s and is cut after 0.5 s; an uncut hang would only end at the 30 s guard.
        assertTrue(run.elapsed < 3.seconds, "the hung attempt was cut by its timeout, took ${run.elapsed}")
    }

    @Test
    fun `a heartbeat stuck inside a database statement does not hold the job past its timeout`() = runBlocking {
        val run = runSyncWithRenewal(onRun = { awaitCancellation() }) { _, time, _ ->
            time.advance(2.seconds)
            // Blocked in the DATABASE (a stalled statement), where cancellation is only honoured once the statement ends (4 s).
            suspendTransaction(sharedDatabaseForTests()) { exec("SELECT pg_sleep(4)") }
            HeartbeatOutcome.RENEWED
        }
        assertEquals(SyncJobStatus.FAILED, run.job?.status)
        // Real time: first renewal due at 1 s, abandoned at 1.5 s; waiting for the statement would end the run at >= 5 s.
        assertTrue(run.elapsed < 3.seconds, "the stalled statement was abandoned at the timeout, took ${run.elapsed}")
    }

    @Test
    fun `a heartbeat answering lease-lost is fatal at once - the job is left untouched`() = runBlocking {
        val run = runSyncWithRenewal(onRun = { awaitCancellation() }) { _, _, _ -> HeartbeatOutcome.LOST }
        assertEquals(SyncJobStatus.RUNNING, run.job?.status, "lease lost: the run stops without finishing or failing the row")
        assertEquals(1, run.renewalAttempts)
    }

    @Test
    fun `a cancel request seen by the lease renewal stops the job as CANCELLED`() = runBlocking {
        val run = runSyncWithRenewal(onRun = { awaitCancellation() }) { _, _, _ -> HeartbeatOutcome.CANCEL_REQUESTED }
        assertEquals(SyncJobStatus.CANCELLED, run.job?.status)
    }

    @Test
    fun `a stale run's success write after the job was reclaimed records nothing`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val requested = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        val config = testConfig()
        // attempt 1 is claimed, its lease expires, the SAME worker id reclaims as attempt 2 — then attempt 1 reports success.
        val stale = withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS) {
            jobs.claim(config.workerId, leaseSeconds = 30, now = TICK_CLOCK_MILLIS)
        }
        assertNotNull(stale)
        val current = withOnlyConnections(setOf(connId), TICK_CLOCK_MILLIS + 31_000) {
            jobs.claim(config.workerId, leaseSeconds = 300, now = TICK_CLOCK_MILLIS + 31_000)
        }
        assertNotNull(current)
        assertEquals(2, current.attempt)
        val worker = IngestWorker(jobs, ds, handlers(ds), emptyMap(), config, System::currentTimeMillis)

        worker.onSucceeded(stale)

        val row = assertNotNull(jobs.read(connId, requested.jobId))
        assertEquals(SyncJobStatus.RUNNING, row.status, "the stale success must not finish the new run's row")
        assertEquals(0, jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items.size, "and chains no DERIVE")
    }

    @Test
    fun `an ApplicationStopping shutdown releases a RUNNING job back to PENDING`() {
        var connId2: UInt? = null
        var jobId2: UInt? = null
        val started = CompletableDeferred<Unit>()
        val connector = FakeConnector {
            started.complete(Unit)
            awaitCancellation()
        }

        testApplication {
            configureApp("app.role" to "worker", "ingest.schedulerTickSeconds" to "5", "ingest.leaseSeconds" to "30")
            val ds = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
            val jobs = SyncJobsService(sharedDatabaseForTests(), defaultMaxAttempts = 3)
            runBlocking {
                val newConnId = createConnection(ds, enabled = true)
                connId2 = newConnId
                jobId2 = jobs.requestJob(newConnId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
            }
            application {
                attributes.put(IngestConnectorOverrideKey, mapOf(DataSourceKind.JIRA_CLOUD to connector))
            }
            startApplication()
            runBlocking { withTimeout(15_000) { started.await() } }
            // The block ending here fires ApplicationStopping (Ktor's test engine teardown),
            // which ingest/IngestWorker.kt's shutdown hook cancels + joins (up to 5s) — each
            // in-flight runJob releases its own claim back to PENDING under NonCancellable first.
        }

        runBlocking {
            val jobs = SyncJobsService(sharedDatabaseForTests(), defaultMaxAttempts = 3)
            val job = jobs.read(requireNotNull(connId2), requireNotNull(jobId2))
            assertEquals(SyncJobStatus.PENDING, job?.status)
        }
    }
}
