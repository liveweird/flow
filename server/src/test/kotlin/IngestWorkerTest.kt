package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestConnectorOverrideKey
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.backoffMillis
import ch.nokillswit.ingest.MAX_BACKOFF_MILLIS
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The direct-construction tests below hit [sharedDatabaseForTests] without booting a
 * `testApplication` — see `SyncJobQueueTest.kt`'s identical note: nothing else in this JVM fork
 * is guaranteed to have run Flyway first (Gradle test forking, `--tests` filtering), so
 * [ensureMigrated] replicates `infra/db/Flyway.kt`'s migrate() call directly (idempotent) instead
 * of booting a whole app — which would also start a live, competing ingest worker in `all` role.
 */
private val migrated = AtomicBoolean(false)

/** A bound on the retry loops that keep ticking a scoped [IngestWorker] until ITS OWN job finishes — never an expected real-world count. */
private const val MAX_TICK_ATTEMPTS = 50

private fun ensureMigrated() {
    if (migrated.compareAndSet(false, true)) {
        org.flywaydb.core.Flyway.configure()
            .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

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
        SyncJobsService(sharedDatabaseForTests(), 3),
    )

    /** The PURGE step's OTHER generic dependency (round 1 review: `MetricsStore.purgeAll` had no caller) — a fresh, stateless instance. */
    private fun metricsStore() = MetricsStore(sharedDatabaseForTests())

    /** The DERIVE job's dependency (v0.3.0 M3 commit 7) — a fresh instance per test, it is stateless. */
    private fun deriver(metrics: MetricsConfigService) = MetricsDeriver(
        WorkItemStore(sharedDatabaseForTests()),
        metrics,
        TeamMembershipService(sharedDatabaseForTests(), metrics),
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

    private fun syncJobs(maxAttempts: Int = 3, clock: () -> Long = System::currentTimeMillis) =
        SyncJobsService(sharedDatabaseForTests(), maxAttempts, clock)

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
        workerId = "test-worker-${unique("id")}",
    )

    private suspend fun createConnection(dataSources: DataSourceService, syncIntervalMinutes: Int = 30): UInt =
        dataSources.create(
            DataSourceRequest(
                name = unique("conn"),
                enabled = true,
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
    private fun fixedEarlyMorningClock(): () -> Long {
        val fixed = java.time.Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()
        return { fixed }
    }

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
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 45)
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
            metricsConfig(ds),
            metricsStore(),
            deriver(metricsConfig(ds)),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 5),
            fixedEarlyMorningClock(),
        )

        // A successful SYNC now also chains a DERIVE job for this connection (v0.3.0 M3 commit 7)
        // — filter to the SYNC kind so this assertion stays about the job under test.
        suspend fun syncJob() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.singleOrNull()
        var job = syncJob()
        var attempts = 0
        while (isOpen(job?.status) && attempts < MAX_TICK_ATTEMPTS) {
            coroutineScope { worker.tick(this) }
            job = syncJob()
            attempts++
        }

        assertTrue(ran, "the claimed job's connector.run() must have executed")
        assertEquals(SyncJobStatus.SUCCEEDED, job?.status)
        val connection = assertNotNull(ds.read(connId))
        assertNotNull(connection.status.lastSyncSucceededAt)
        assertEquals(0, connection.status.consecutiveFailures)

        // v0.3.0 M3 commit 7: a successful SYNC chains a scheduled DERIVE job for the SAME
        // connection — keep ticking (bounded) until it too reaches a terminal status, then assert it
        // actually SUCCEEDED (review round 1: a bare "!= FAILED" tolerated a DERIVE job that simply
        // had not been claimed yet within a single tick).
        suspend fun deriveJob() = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items.singleOrNull()
        var derive = deriveJob()
        attempts = 0
        while (isOpen(derive?.status) && attempts < MAX_TICK_ATTEMPTS) {
            coroutineScope { worker.tick(this) }
            derive = deriveJob()
            attempts++
        }
        assertEquals(SyncJobStatus.SUCCEEDED, derive?.status, "the chained DERIVE job must actually succeed, not merely avoid FAILED")
    }

    @Test
    fun `a failing run schedules a backoff and is FAILED, not silently dropped`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 30)
        val connector = FakeConnector { error("simulated stream failure") }
        // See the identical workerSlots note in the "success" test above.
        val worker = IngestWorker(
            jobs,
            ds,
            metricsConfig(ds),
            metricsStore(),
            deriver(metricsConfig(ds)),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        coroutineScope { worker.tick(this) }

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single()
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals("RUN_FAILED", job.errorCode)
        val connection = assertNotNull(ds.read(connId))
        assertEquals(1, connection.status.consecutiveFailures)
        assertEquals("RUN_FAILED", connection.status.lastSyncErrorCode)
    }

    @Test
    fun `a stream's heartbeat writes progress and current_stream onto the sync job`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val connector = FakeConnector { context -> context.heartbeat("""{"pages":1}""", "issues") }
        val worker = IngestWorker(
            jobs,
            ds,
            metricsConfig(ds),
            metricsStore(),
            deriver(metricsConfig(ds)),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        coroutineScope { worker.tick(this) }

        // Same DERIVE-chaining note as the "success" test above.
        val job = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.single()
        assertEquals("issues", job.currentStream)
        assertEquals(1L, job.progress?.get("pages")?.jsonPrimitive?.long)
    }

    /**
     * A [SyncJobClaim] built directly from a REAL, just-`requestJob`'d row's id (the
     * `SyncedStubFixture.claimFor`/`JiraSyncPipelineTest.claimFor` shape) — deliberately NOT
     * `jobs.claim(...)`: this shared test database accumulates other tests'/classes' own
     * still-PENDING manual jobs across the whole suite run, and a bare `claim()` call can pick up
     * one of THOSE instead of ours. `finish`/`fail` update by id alone (no status precondition), so
     * driving `IngestWorker.runJob` against a claim built this way is exactly as real for what these
     * tests check (the job's terminal status, the generic drain) as going through the queue.
     */
    private fun claimFor(jobId: UInt, connId: UInt, kind: SyncJobKind) = SyncJobClaim(
        id = jobId,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = kind,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    @Test
    fun `a PURGE job drains all eight metrics config tables, and a second PURGE is a no-op`() = runBlocking {
        ensureMigrated()
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
            jobs, ds, metrics, metricsStore(), deriver(metrics),
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

    /** A raw `metrics.fact_sprint_snapshot` row for [connId] — the Exposed table object (`MetricsStore.FactSprintSnapshot`)
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
        MetricsStore.FactSprintSnapshot.selectAll().where { MetricsStore.FactSprintSnapshot.connectionId eq connId }.count()
    }

    @Test
    fun `a PURGE job also drains metrics star rows and fact_sprint_snapshot rows for the connection`() = runBlocking {
        ensureMigrated()
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
            MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }.count()
        }
        assertEquals(1L, factCountBefore, "fixture must seed exactly one fact_task_delivery row")
        assertEquals(1L, countSnapshotRows(connId), "fixture must seed exactly one fact_sprint_snapshot row")

        val config = testConfig(workerSlots = 500)
        val connector = FakeConnector { }
        val worker = IngestWorker(
            jobs, ds, metrics, store, deriver(metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        worker.runJob(claimFor(jobId, connId, SyncJobKind.PURGE))

        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, jobId)?.status)
        val factCountAfter = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }.count()
        }
        assertEquals(0L, factCountAfter, "PURGE must drain the derived metrics star (review round 1: MetricsStore.purgeAll had no caller)")
        assertEquals(0L, countSnapshotRows(connId), "PURGE must drain fact_sprint_snapshot rows too, via the allow-delete bypass")
    }

    @Test
    fun `fact_sprint_snapshot rows are immutable outside the PURGE bypass`() = runBlocking {
        ensureMigrated()
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
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, metrics, metricsStore(), deriver(metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )

        val staleJobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val revisionAtStart = metrics.currentRevision()
        // Simulate a config PUT landing WHILE this DERIVE run was in flight — the exact race
        // `uq_sync_jobs_open_per_kind` coalescing would otherwise swallow (review round 1 fix).
        val current = metrics.read()
        metrics.replace(current.asRequest().copy(hoursPerDay = current.hoursPerDay + 1), byUserId = 1u)
        assertTrue(metrics.currentRevision() > revisionAtStart, "the bump must actually move the shared revision")

        worker.onSucceeded(claimFor(staleJobId, connId, SyncJobKind.DERIVE), deriveRevisionUsed = revisionAtStart)

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(2, deriveJobs.size, "a fresh DERIVE must be enqueued once this run's own revision is found stale")
        assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connId, staleJobId)?.status, "the original run itself still finishes normally")
    }

    @Test
    fun `onSucceeded does not re-enqueue DERIVE when the run's revision is still current`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val worker = IngestWorker(
            jobs, ds, metrics, metricsStore(), deriver(metrics),
            mapOf(DataSourceKind.JIRA_CLOUD to FakeConnector { }), testConfig(), System::currentTimeMillis,
        )

        val jobId = jobs.requestJob(connId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val currentRevision = metrics.currentRevision()

        worker.onSucceeded(claimFor(jobId, connId, SyncJobKind.DERIVE), deriveRevisionUsed = currentRevision)

        val deriveJobs = jobs.list(connId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(1, deriveJobs.size, "no fresh DERIVE should be enqueued when nothing about the config changed")
    }

    @Test
    fun `a connector purge failure prevents the generic metrics-config drain and FAILS the job`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val metrics = metricsConfig(ds)
        val connId = createConnection(ds)
        val teamId = TestTeams.seed(unique("purge-fail-team"))
        seedAllMetricsConfigTables(connId, teamId)

        val config = testConfig(workerSlots = 500)
        val connector = FakeConnector { error("simulated connector purge failure") }
        val worker = IngestWorker(
            jobs, ds, metrics, metricsStore(), deriver(metrics),
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

    @Test
    fun `a cancel request during a run is honoured - the job stops and is CANCELLED`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val requested = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        val config = testConfig(leaseSeconds = 3)
        // The claim's lease_owner MUST match config.workerId — otherwise the worker's own
        // heartbeat() call (keyed on config.workerId) never matches this row and throws
        // LeaseLostException instead of ever reaching the cancel-request check.
        val claim = jobs.claim(config.workerId, leaseSeconds = 3)
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
            metricsConfig(ds),
            metricsStore(),
            deriver(metricsConfig(ds)),
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
                val newConnId = createConnection(ds)
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
