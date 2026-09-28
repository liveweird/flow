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
    private fun metricsConfig(dataSources: DataSourceService) =
        MetricsConfigService(sharedDatabaseForTests(), WorkItemStore(sharedDatabaseForTests()), dataSources)

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

    @Test
    fun `a tick enqueues a due SYNC job, claims and runs it to success`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds, syncIntervalMinutes = 45)
        var ran = false
        val connector = FakeConnector { ran = true }
        // workerSlots is generous: this shared test database accumulates due-but-unclaimed SYNC
        // jobs from other tests/classes across the whole suite run, and tick() only claims up to
        // workerSlots per call — this must be large enough that OUR connection's job is reached
        // within this single tick (jobs.list(connId, ...) below only inspects OUR OWN connection,
        // so incidentally draining others' backlog through this same no-op-succeeding connector is harmless).
        val worker = IngestWorker(
            jobs,
            ds,
            metricsConfig(ds),
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        coroutineScope { worker.tick(this) }

        assertTrue(ran, "the claimed job's connector.run() must have executed")
        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single()
        assertEquals(SyncJobStatus.SUCCEEDED, job.status)
        val connection = assertNotNull(ds.read(connId))
        assertNotNull(connection.status.lastSyncSucceededAt)
        assertEquals(0, connection.status.consecutiveFailures)
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
            mapOf(DataSourceKind.JIRA_CLOUD to connector),
            testConfig(workerSlots = 500),
            fixedEarlyMorningClock(),
        )

        coroutineScope { worker.tick(this) }

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single()
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
        val worker = IngestWorker(jobs, ds, metrics, mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis)

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
        val worker = IngestWorker(jobs, ds, metrics, mapOf(DataSourceKind.JIRA_CLOUD to connector), config, System::currentTimeMillis)

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
