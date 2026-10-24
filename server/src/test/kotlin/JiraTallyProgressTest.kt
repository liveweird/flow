package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraSyncDependencies
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.junit.After
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOUD_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

/** A client whose only call is a sweep page, fetched through a REAL [JiraHttp] over a scripted [MockEngine]. */
private class HttpBackedClient(private val http: JiraHttp, private val cloudIdViaHttp: Boolean) : UnsupportedJiraClient() {
    override suspend fun resolveCloudId(): String {
        if (cloudIdViaHttp) http.request(HttpMethod.Get, "https://tenant.test/_edge/tenant_info", pauseKey = CLOUD_ID)
        return CLOUD_ID
    }

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        http.request(HttpMethod.Get, "https://tenant.test/rest/api/3/search/jql", pauseKey = CLOUD_ID)
        return JiraSearchPage(JsonArray(emptyList()), null)
    }
}

/**
 * The Jira call tally reaching an operator (`.claude/docs/ingestion.md` "Progress counters"): a RECONCILE job run by the REAL
 * `IngestWorker` through the REAL `JiraConnector`, whose client calls a real `JiraHttp` (a scripted 429 with `Retry-After`), ends with
 * `jiraRateLimited` / `jiraBackoffMs` in its `sync_jobs.progress`; a job that was not rate limited carries none of the keys.
 */
class JiraTallyProgressTest {
    private val workerId = "tally-worker-${UUID.randomUUID().toString().take(8)}"

    /** How the scripted Jira answers: one 429 and then fine, always fine, or 429 on every call (the first call is `resolveCloudId`). */
    private enum class Mode { THROTTLED_ONCE, QUIET, ALWAYS_LIMITED }

    private val connections = mutableListOf<UInt>()

    /** The test's jobs must not linger in the shared queue: a finished RECONCILE/SYNC enqueues a DERIVE for its connection. */
    @After
    fun cancelOpenJobs() = runBlocking {
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({
                (SyncJobsService.Jobs.connectionId inList connections) and
                    (SyncJobsService.Jobs.status inList listOf(SyncJobStatus.PENDING.name, SyncJobStatus.RUNNING.name))
            }) { it[status] = SyncJobStatus.CANCELLED.name }
        }
        Unit
    }

    private fun connector(mode: Mode): JiraConnector {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (mode == Mode.ALWAYS_LIMITED || (mode == Mode.THROTTLED_ONCE && calls.getAndIncrement() == 0)) {
                respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        // A virtual clock the sleeper moves: the call's own back-off then also ends the tenant pause it set, as real time would.
        val clock = AtomicLong(1_000)
        val http = JiraHttp(
            HttpClient(engine) { expectSuccess = false; followRedirects = false },
            maxRetries = if (mode == Mode.ALWAYS_LIMITED) 1 else 2, maxResponseBytes = 1_000_000, maxConcurrentRequests = 2,
            sleeper = { clock.addAndGet(it) }, clock = { clock.get() },
        )
        return JiraConnector(
            newClient = { _, _, _, _: JiraAuthScheme -> HttpBackedClient(http, cloudIdViaHttp = mode == Mode.ALWAYS_LIMITED) },
            sync = JiraSyncDependencies(
                dataSources = SyncedStubFixture.dataSources(),
                rawStore = SyncedStubFixture.rawStore(),
                cursors = SyncedStubFixture.cursors(),
                database = sharedDatabaseForTests(),
                workItems = SyncedStubFixture.workItems(),
                incrementalOverlapMinutes = 10,
                issuesPageSize = 100,
            ),
        )
    }

    /** Runs one job of [kind] through the real worker with a hand-built claim of a real row; returns the job as the database holds it. */
    private suspend fun runJob(
        connector: JiraConnector,
        connId: UInt,
        kind: SyncJobKind,
        attempt: Int = 1,
        priorProgress: String? = null,
    ): SyncJobResponse {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val worker = IngestWorker(
            jobs, SyncedStubFixture.dataSources(), JobHandlerRegistry(), mapOf(DataSourceKind.JIRA_CLOUD to connector),
            IngestConfig(
                schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7,
                workerId = workerId,
            ),
            System::currentTimeMillis,
        )
        val jobId = jobs.requestJob(connId, kind, requestedByUserId = 1u, configRevision = 1L).jobId
        // A hand-built claim of a REAL row (the shared queue may hold other tests' PENDING jobs, so no `claim()`).
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[SyncJobsService.Jobs.attempt] = attempt
                it[leaseOwner] = workerId
                it[leaseUntil] = PARKED_LEASE_UNTIL
            }
        }
        val claim = SyncJobClaim(
            jobId, connId, DataSourceKind.JIRA_CLOUD, kind, attempt = attempt, maxAttempts = 3, syncIntervalMinutes = 60,
            priorProgress = priorProgress,
        )
        worker.runJob(claim)
        return assertNotNull(jobs.read(connId, jobId))
    }

    private suspend fun runReconcile(connector: JiraConnector, connId: UInt): Map<String, Long> {
        val job = runJob(connector, connId, SyncJobKind.RECONCILE)
        assertEquals(SyncJobStatus.SUCCEEDED, job.status, "${job.errorCode} ${job.errorDetail}")
        return assertNotNull(job.progress).mapValues { it.value.jsonPrimitive.long }
    }

    private suspend fun connection(prefix: String): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false).also { connections += it }
    }

    @Test
    fun `a rate-limited job's counters land in its sync_jobs progress`() = runBlocking {
        val progress = runReconcile(connector(Mode.THROTTLED_ONCE), connection("tally-limited"))
        assertEquals(1L, progress["jiraRateLimited"])
        assertEquals(2_000L, progress["jiraBackoffMs"], "the Retry-After of 2 s")
        assertEquals(1L, progress["pages"], "beside the stream's own counters")
        assertNull(progress["jiraTenantPausedMs"])
        assertNull(progress["jiraNearLimitPauses"])
    }

    @Test
    fun `a job that was never throttled carries none of the keys`() = runBlocking {
        val progress = runReconcile(connector(Mode.QUIET), connection("tally-quiet"))
        assertEquals(1L, progress["pages"])
        assertEquals(emptySet(), progress.keys.filter { it.startsWith("jira") }.toSet())
    }

    @Test
    fun `a SYNC that gives up with RATE_LIMITED still shows its 429s on the job`() = runBlocking {
        val connId = connection("tally-giveup")
        val job = runJob(connector(Mode.ALWAYS_LIMITED), connId, SyncJobKind.SYNC)
        assertEquals("RUN_FAILED", job.errorCode)
        assertTrue(job.errorDetail.orEmpty().contains("RATE_LIMITED"), "${job.errorDetail}")
        val progress = assertNotNull(job.progress, "the failed attempt flushed a final heartbeat").mapValues { it.value.jsonPrimitive.long }
        assertEquals(2L, progress["jiraRateLimited"], "the first attempt and the single retry")
        assertEquals(2_000L, progress["jiraBackoffMs"], "one 2 s back-off; the last 429 gave up without sleeping")
    }

    @Test
    fun `a retried attempt keeps the sticky keys of the attempt before it and adds its own`() = runBlocking {
        val connId = connection("tally-retry")
        val prior = """{"backfill":1,"jiraRateLimited":3,"jiraBackoffMs":6000,"pages":9,"issuesUpserted":40}"""
        val job = runJob(connector(Mode.THROTTLED_ONCE), connId, SyncJobKind.RECONCILE, attempt = 2, priorProgress = prior)
        assertEquals(SyncJobStatus.SUCCEEDED, job.status, "${job.errorCode} ${job.errorDetail}")
        val progress = assertNotNull(job.progress).mapValues { it.value.jsonPrimitive.long }
        assertEquals(1L, progress["backfill"])
        assertEquals(4L, progress["jiraRateLimited"], "attempt 1's three plus this attempt's one")
        assertEquals(8_000L, progress["jiraBackoffMs"], "6 s carried plus this attempt's 2 s")
        assertEquals(1L, progress["pages"], "a stream's own counters restart with the attempt")
        assertNull(progress["issuesUpserted"])

        val fresh = connection("tally-first")
        val first = runJob(connector(Mode.THROTTLED_ONCE), fresh, SyncJobKind.RECONCILE, attempt = 1, priorProgress = prior)
        assertNull(assertNotNull(first.progress)["backfill"], "a first attempt carries nothing over")
    }
}
