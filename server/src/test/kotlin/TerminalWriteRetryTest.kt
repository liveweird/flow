package ch.nokillswit

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.TerminalWriteRetry
import ch.nokillswit.ingest.isConnectionLoss
import ch.nokillswit.infra.paging.PageRequest
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.R2dbcTransientResourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The job-close writes surviving a lost database connection (`ingest/TerminalWriteRetry.kt`; `ingestion.md` "Sync-job queue (V9)", the
 * paragraph "A job whose database connection is lost is closed promptly").
 */
class TerminalWriteRetryTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /** Virtual time the recorded sleeps advance, so the budget arithmetic never depends on the machine. */
    private class VirtualTime : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
        var now = 0L
        override fun read(): Long = now
    }

    // --- classification and schedule -------------------------------------------------------------------------------------

    @Test
    fun `connection losses are told from statements the database refused`() {
        val lost = listOf(
            R2dbcNonTransientResourceException("Connection unexpectedly closed", "08006"),
            R2dbcNonTransientResourceException("Cannot connect", "08003"),
            R2dbcNonTransientResourceException("closed, no server state"),
            R2dbcNonTransientResourceException("terminating connection due to administrator command", "57P01"),
            R2dbcNonTransientResourceException("terminating connection because of crash of another server process", "57P02"),
            R2dbcNonTransientResourceException("the database system is in recovery mode", "57P03"),
            R2dbcNonTransientResourceException("sorry, too many clients already", "53300"),
            R2dbcTransientResourceException("transient"),
            R2dbcTimeoutException("Connection acquisition timed out"),
            IOException("Connection reset"),
        )
        lost.forEach { assertTrue(isConnectionLoss(it), "$it") }
        assertTrue(isConnectionLoss(RuntimeException("wrapped by Exposed", RuntimeException("deeper", lost[0]))), "a cause is matched")

        val refused = listOf(
            R2dbcDataIntegrityViolationException("duplicate key", "23505"),
            R2dbcBadGrammarException("syntax error", "42601"),
            R2dbcNonTransientResourceException("canceling statement due to statement timeout", "57014"),
            R2dbcNonTransientResourceException("could not obtain lock", "55P03"),
            IllegalStateException("Pool has been shut down"),
        )
        refused.forEach { assertFalse(isConnectionLoss(it), "$it") }
    }

    @Test
    fun `the back-off doubles to its cap and ends at the budget with the last failure rethrown`() = runBlocking<Unit> {
        val time = VirtualTime()
        val sleeps = mutableListOf<Long>()
        val retry = TerminalWriteRetry(500, 8_000, 30_000, time) { sleeps += it; time.now += it }
        var attempts = 0
        val thrown = assertFailsWith<R2dbcNonTransientResourceException> {
            retry.run(1u, "fail") { attempts++; throw R2dbcNonTransientResourceException("gone", "08006") }
        }
        assertEquals("gone", thrown.message)
        assertEquals(listOf(500L, 1_000, 2_000, 4_000, 8_000, 8_000, 6_500), sleeps, "the last pause is cut to the 30 s budget")
        assertEquals(sleeps.size + 1, attempts)

        sleeps.clear()
        var calls = 0
        assertEquals("ok", retry.run(1u, "fail") { if (++calls < 3) throw IOException("reset") else "ok" })
        assertEquals(listOf(500L, 1_000), sleeps)
    }

    @Test
    fun `a failure that is not a connection loss is never retried, and cancellation always propagates`() = runBlocking<Unit> {
        val sleeps = mutableListOf<Long>()
        val retry = TerminalWriteRetry(sleep = { sleeps += it })
        var attempts = 0
        assertFailsWith<R2dbcDataIntegrityViolationException> {
            retry.run(1u, "fail") { attempts++; throw R2dbcDataIntegrityViolationException("duplicate key", "23505") }
        }
        assertFailsWith<CancellationException> { retry.run(1u, "fail") { attempts++; throw CancellationException("cancelled") } }
        assertEquals(2, attempts)
        assertTrue(sleeps.isEmpty(), "neither was retried: $sleeps")
    }

    // --- a real worker over a pool whose database goes away ---------------------------------------------------------------

    /**
     * A pooled database of its own (its backends carry a unique `application_name`) whose connection factory can be switched DOWN: while
     * down, a new connection is refused by the driver exactly as when the postmaster is gone ([outage] also terminates the pooled
     * backends with `pg_terminate_backend`, what a postmaster reset does to every session). [recover] brings it back.
     */
    private class FlakyDatabase : AutoCloseable {
        val appName = unique("flaky")
        private val down = AtomicBoolean(false)
        private val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, appName)
            .build()
        private val real = ConnectionFactories.get(options)
        private val refusing = ConnectionFactories.get(options.mutate().option(ConnectionFactoryOptions.PORT, 1).build())
        private val factory = object : ConnectionFactory {
            // Decided at SUBSCRIPTION: the pool calls create() once and re-subscribes that publisher for every connection it opens.
            override fun create(): Publisher<out Connection> =
                Mono.defer { Mono.from(if (down.get()) refusing.create() else real.create()) }
            override fun getMetadata(): ConnectionFactoryMetadata = real.metadata
        }
        private val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(factory).maxSize(3).initialSize(0).maxAcquireTime(Duration.ofSeconds(3)).build(),
        )
        val database: R2dbcDatabase = R2dbcDatabase.connect(
            connectionFactory = pool,
            databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                connectionFactoryOptions = options
                defaultMaxAttempts = 1
            },
        )

        suspend fun warm() = suspendTransaction(database) { exec("SELECT 1") }

        fun outage() {
            down.set(true)
            PostgresTestSupport.connect().use { c ->
                c.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = ?").use { st ->
                    st.setString(1, appName)
                    st.executeQuery().close()
                }
            }
        }

        fun recover() = down.set(false)

        override fun close() {
            pool.dispose()
        }

        private companion object {
            fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"
        }
    }

    private class OutageConnector(private val onRun: suspend (SyncJobRunContext) -> Unit) : Connector {
        override val kind = DataSourceKind.JIRA_CLOUD
        override suspend fun testConnection(
            siteUrl: String,
            email: String,
            apiToken: String,
            projectKeys: List<String>,
            authScheme: JiraAuthScheme,
        ): ConnectionTestResult = error("not used")

        override suspend fun run(context: SyncJobRunContext) = onRun(context)
    }

    private val workerId = "test-worker-${unique("id")}"
    private val createdConnections = mutableListOf<UInt>()

    private fun dataSources() = SyncedStubFixture.dataSources()
    private fun sharedJobs() = SyncJobsService(sharedDatabaseForTests(), 3)
    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())

    private fun config() = IngestConfig(
        schedulerTickSeconds = 15, workerSlots = 1, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7, workerId = workerId,
    )

    /** Disabled (no scheduler touches it), one SYNC job requested and hand-claimed as attempt 1 by this test's worker. */
    private suspend fun claimedSync(): SyncJobClaim {
        val connId = SyncedStubFixture.createConnection(dataSources(), namePrefix = "retry", enabled = false)
        createdConnections += connId
        val jobId = sharedJobs().requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[attempt] = 1
                it[leaseOwner] = workerId
                it[leaseUntil] = System.currentTimeMillis() + MILLIS_PER_MINUTE
            }
        }
        return SyncJobClaim(
            id = jobId, connectionId = connId, connectorKind = DataSourceKind.JIRA_CLOUD, kind = SyncJobKind.SYNC,
            attempt = 1, maxAttempts = 3, syncIntervalMinutes = 60,
        )
    }

    private suspend fun jobOf(claim: SyncJobClaim): SyncJobResponse = assertNotNull(sharedJobs().read(claim.connectionId, claim.id))

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        val jobs = sharedJobs()
        for (connId in createdConnections) {
            dataSources().delete(connId)
            for (job in jobs.list(connId, SyncJobListFilter(), pagingAll()).items) {
                when (job.status) {
                    SyncJobStatus.RUNNING -> jobs.finish(job.id, job.attempt)
                    SyncJobStatus.PENDING -> jobs.requestCancel(connId, job.id)
                    else -> Unit
                }
            }
        }
    }

    /** The back-off a test observed, with the recovery wired in: the database comes back during the [recoverAfter]-th pause. */
    private class Outage(private val flaky: FlakyDatabase, private val recoverAfter: Int) {
        val sleeps = mutableListOf<Long>()
        val retry = TerminalWriteRetry(initialDelayMillis = 40, maxDelayMillis = 160, budgetMillis = 20_000) { millis ->
            sleeps += millis
            if (sleeps.size >= recoverAfter) flaky.recover()
            delay(millis)
        }
    }

    private fun worker(flaky: FlakyDatabase, retry: TerminalWriteRetry, connector: Connector): IngestWorker = IngestWorker(
        SyncJobsService(flaky.database, 3, System::currentTimeMillis, {}, 0L, retry),
        dataSources(), JobHandlerRegistry(), mapOf(DataSourceKind.JIRA_CLOUD to connector), config(), System::currentTimeMillis,
    )

    @Test
    fun `a job whose connection is lost mid-run is closed FAILED within seconds, not at lease expiry`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            val outage = Outage(flaky, recoverAfter = 2)
            flaky.warm()
            val connector = OutageConnector {
                flaky.outage()
                suspendTransaction(flaky.database) { exec("SELECT 1") } // the run's own statement: refused, so the body fails
            }
            val worker = worker(flaky, outage.retry, connector)
            withAuditCapture { audits ->
                val took = measureTime { worker.runJob(claim) }
                val job = jobOf(claim)
                assertEquals(SyncJobStatus.FAILED, job.status, "closed by the worker, not left RUNNING for the 5 min lease")
                assertEquals("RUN_FAILED", job.errorCode)
                assertTrue(took < 15.seconds, "closed within seconds: $took")
                assertEquals(listOf(40L, 80L), outage.sleeps.take(2), "the back-off doubles: ${outage.sleeps}")
                val failures = assertNotNull(dataSources().read(claim.connectionId)).status.consecutiveFailures
                assertEquals(1, failures, "the back-off rode the same write")
                assertTrue(audits.events.any { it.message == "sync_job.failed" && it.hasKeyValue("jobId", claim.id.toLong()) })
            }
        }
    }

    @Test
    fun `a success write that loses its connection is retried and the job ends SUCCEEDED`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            val outage = Outage(flaky, recoverAfter = 2)
            flaky.warm()
            val worker = worker(flaky, outage.retry, OutageConnector { flaky.outage() })
            worker.runJob(claim)
            assertEquals(SyncJobStatus.SUCCEEDED, jobOf(claim).status)
            assertEquals(listOf(40L, 80L), outage.sleeps.take(2))
            assertNotNull(dataSources().read(claim.connectionId)?.status?.lastSyncSucceededAt, "the follow-ups of a true finish ran")
        }
    }

    @Test
    fun `a cancelled job's release is retried through a lost connection and the row returns to PENDING`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            val outage = Outage(flaky, recoverAfter = 2)
            flaky.warm()
            val inOutage = CompletableDeferred<Unit>()
            val worker = worker(flaky, outage.retry, OutageConnector { flaky.outage(); inOutage.complete(Unit); awaitCancellation() })
            val run = launch(Dispatchers.IO) { worker.runJob(claim) }
            withTimeout(10_000) { inOutage.await() }
            run.cancel()
            withTimeout(15_000) { run.join() }
            assertTrue(run.isCancelled, "the cancellation is not replaced by a database error")
            assertEquals(SyncJobStatus.PENDING, jobOf(claim).status)
            assertEquals(listOf(40L, 80L), outage.sleeps.take(2))
        }
    }

    @Test
    fun `when the database stays gone the failure write gives up with one WARN and the row is left to its lease`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            flaky.warm()
            val sleeps = mutableListOf<Long>()
            val retry = TerminalWriteRetry(initialDelayMillis = 20, maxDelayMillis = 40, budgetMillis = 250) { sleeps += it; delay(it) }
            val worker = worker(flaky, retry, OutageConnector {
                flaky.outage()
                suspendTransaction(flaky.database) { exec("SELECT 1") }
            })
            val logs = LogCapture("ch.nokillswit.ingest.IngestWorker")
            try {
                worker.runJob(claim) // returns: the failed close is not an exception of the job
                assertEquals(SyncJobStatus.RUNNING, jobOf(claim).status, "no lease expiry yet: the row is exactly as today")
                assertTrue(sleeps.size >= 2, "it did retry: $sleeps")
                val warns = logs.events.filter { it.level.toString() == "WARN" && it.formattedMessage.contains("stays RUNNING") }
                assertEquals(1, warns.size, "one WARN: ${logs.events.map { it.formattedMessage }}")
                val text = warns.single().formattedMessage
                assertTrue(text.contains(claim.id.toString()) && text.contains(claim.connectionId.toString()), text)
                assertFalse(text.contains("SELECT") || text.contains("localhost"), "class names only, never a message: $text")
            } finally {
                logs.detach()
            }
        }
    }

    @Test
    fun `a success write that exhausts on a lost connection is no failure - the row is left to its lease`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            flaky.warm()
            val sleeps = mutableListOf<Long>()
            val retry = TerminalWriteRetry(initialDelayMillis = 20, maxDelayMillis = 40, budgetMillis = 250) { sleeps += it; delay(it) }
            // The body SUCCEEDS (it only takes the database down afterwards), so the first write that meets the outage is the finish.
            val worker = worker(flaky, retry, OutageConnector { flaky.outage() })
            val logs = LogCapture("ch.nokillswit.ingest.IngestWorker")
            try {
                withAuditCapture { audits ->
                    worker.runJob(claim)
                    flaky.recover()
                    assertEquals(SyncJobStatus.RUNNING, jobOf(claim).status, "not FAILED: every page of the run committed")
                    val failures = assertNotNull(dataSources().read(claim.connectionId)).status.consecutiveFailures
                    assertEquals(0, failures, "no back-off was recorded")
                    assertTrue(sleeps.size >= 2, "the finish did retry: $sleeps")
                    assertFalse(audits.events.any { it.message == "sync_job.failed" && it.hasKeyValue("jobId", claim.id.toLong()) })
                    assertFalse(audits.events.any { it.message == "sync_job.succeeded" && it.hasKeyValue("jobId", claim.id.toLong()) })
                }
                val messages = logs.events.map { it.formattedMessage }
                assertTrue(messages.none { it.contains(" fail write lost") }, "no fail write was attempted: $messages")
                val warns = logs.events.filter { it.level.toString() == "WARN" && it.formattedMessage.contains("stays RUNNING") }
                assertEquals(1, warns.size, "one WARN: $messages")
                assertTrue(warns.single().formattedMessage.contains("the finish write failed"), warns.single().formattedMessage)
            } finally {
                logs.detach()
            }
        }
    }

    @Test
    fun `a cancellation whose release gives up is still a cancellation`() = runBlocking<Unit> {
        FlakyDatabase().use { flaky ->
            val claim = claimedSync()
            flaky.warm()
            val inOutage = CompletableDeferred<Unit>()
            val retry = TerminalWriteRetry(initialDelayMillis = 20, maxDelayMillis = 40, budgetMillis = 150)
            val worker = worker(flaky, retry, OutageConnector { flaky.outage(); inOutage.complete(Unit); awaitCancellation() })
            val run: Job = launch(Dispatchers.IO) { worker.runJob(claim) }
            withTimeout(10_000) { inOutage.await() }
            run.cancel()
            withTimeout(15_000) { run.join() }
            assertTrue(run.isCancelled)
            assertEquals(SyncJobStatus.RUNNING, jobOf(claim).status)
        }
    }

    @Test
    fun `a write the database refuses is not retried`() = runBlocking<Unit> {
        val claim = claimedSync()
        val sleeps = mutableListOf<Long>()
        val retry = TerminalWriteRetry(sleep = { sleeps += it })
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, System::currentTimeMillis, {}, 0L, retry)
        // U+0000 cannot be stored in `text` (22021): a data error of the statement, not of the connection.
        val failure = assertFailsWith<Exception> { jobs.fail(claim.id, claim.attempt, "RUN_FAILED", "a\u0000b") }
        assertFalse(isConnectionLoss(failure), "$failure")
        assertTrue(sleeps.isEmpty(), "a refused statement is thrown at once: $sleeps")
        assertEquals(SyncJobStatus.RUNNING, jobOf(claim).status)
    }
}
