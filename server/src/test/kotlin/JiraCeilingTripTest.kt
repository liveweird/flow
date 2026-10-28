package ch.nokillswit

import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.LIMIT_EXCEEDED_TRIP
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.TIME_EXCEEDED_TRIP
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.jira.backfillFromEpochMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val BACKFILL = "2026-01-01"

/**
 * The per-job wall clock and the typing of a ceiling trip on the REAL streams (`.claude/docs/ingestion.md` "Ceiling trips and the
 * pause"): the job stops BETWEEN pages once its `ingest.maxJobMinutes` is up (`TIME_EXCEEDED`, a fake clock), the next run resumes
 * from the cursor the stopped one left, a `LIMIT_EXCEEDED` fetch failure leaves the connector as a [CeilingTripException] saying
 * whether the stream that tripped had committed a page, and any other failure stays what it was. The pause arithmetic itself is
 * `CeilingPauseTest`.
 */
class JiraCeilingTripTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()
    private val workerId = "trip-worker-${UUID.randomUUID().toString().take(8)}"

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "ceiling-trip", enabled = false, projectKeys = listOf("FLO"))
            .also { connections += it }
    }

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({
                    (SyncJobsService.Jobs.connectionId eq connId) and
                        (SyncJobsService.Jobs.status inList listOf(SyncJobStatus.PENDING.name, SyncJobStatus.RUNNING.name))
                }) { it[status] = SyncJobStatus.CANCELLED.name }
                JiraRawStore.ReconcileSeen.deleteWhere { JiraRawStore.ReconcileSeen.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private fun issue(id: Long) = Json.parseToJsonElement(
        """{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"},"updated":"2026-05-20T10:00:00.000+0000"}}""",
    )

    /** `searchJql` as a script: [pages] by the token asked ("" = the first page), each call costing [callMillis] on [clock]. */
    private class Scripted(
        private val clock: AtomicLong,
        private val callMillis: Long,
        private val pages: (token: String?) -> Result<JiraSearchPage>,
    ) : UnsupportedJiraClient() {
        val asked = CopyOnWriteArrayList<String?>()
        override suspend fun resolveCloudId(): String = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
            asked += nextPageToken
            clock.addAndGet(callMillis)
            return pages(nextPageToken).getOrThrow()
        }
    }

    private fun chained(lastToken: String, issueFor: (String?) -> Long): (String?) -> Result<JiraSearchPage> = { token ->
        val next = when (token) {
            null -> "t1"
            "t1" -> "t2"
            else -> null
        }
        Result.success(JiraSearchPage(JsonArray(listOf(issue(issueFor(token)))), next.takeIf { token != lastToken }))
    }

    private fun streamContext(
        connId: UInt,
        clock: AtomicLong,
        maxRunMillis: Long?,
        beats: AtomicInteger = AtomicInteger(),
        exempt: Set<String> = emptySet(),
    ) = StreamContext(
        connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> beats.incrementAndGet(); true }, clock = clock::get,
        maxRunMillis = maxRunMillis, deadlineExemptStreams = exempt,
    )

    // --- StreamContext -------------------------------------------------------------------------------------------------

    @Test
    fun `a heartbeat flushes first, then stops the run once the deadline is up`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val beats = AtomicInteger()
        val context = streamContext(1u, clock, maxRunMillis = 5 * MINUTE, beats = beats)

        clock.addAndGet(5 * MINUTE - 1)
        context.heartbeat() // one millisecond short: no trip
        clock.addAndGet(1)
        val trip = assertFailsWith<CeilingTripException> { context.heartbeat() }
        assertEquals(TIME_EXCEEDED_TRIP, trip.code)
        assertTrue(trip.madeProgress, "a heartbeat follows a committed page, so a clock stop has always moved a cursor")
        assertNull(trip.underlying)
        assertEquals(2, beats.get(), "the stopping heartbeat flushed its progress before it threw")
        assertTrue("maxJobMinutes" in trip.message.orEmpty() && "http" !in trip.message.orEmpty(), trip.message)
    }

    @Test
    fun `without a deadline a heartbeat never stops the run`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val context = streamContext(1u, clock, maxRunMillis = null)
        clock.addAndGet(1_000 * MINUTE)
        context.heartbeat()
    }

    @Test
    fun `progress is the tripping stream's own committed pages, restarted when a stream is named`() = runBlocking<Unit> {
        val context = streamContext(1u, AtomicLong(1), maxRunMillis = null)
        context.currentStreamName = "reference"
        context.heartbeat()
        context.heartbeat()
        context.currentStreamName = "issues"
        val fresh = context.ceilingTrip(LIMIT_EXCEEDED_TRIP, IllegalStateException("x"))
        assertFalse(fresh.madeProgress, "an earlier stream's pages are not this one's")
        context.heartbeat()
        assertTrue(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, IllegalStateException("x")).madeProgress)
        context.currentStreamName = "changelogs"
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress)
    }

    @Test
    fun `an uncommitted heartbeat enforces the clock but is not progress, and the local streams are exempt`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val context = streamContext(1u, clock, maxRunMillis = 5 * MINUTE, exempt = setOf("process"))
        context.currentStreamName = "worklogs"
        context.heartbeat(committed = false)
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress, "a fetch that was never flushed is not a stored page")
        clock.addAndGet(5 * MINUTE)
        val trip = assertFailsWith<CeilingTripException> { context.heartbeat(committed = false) }
        assertFalse(trip.madeProgress, "a clock stop with nothing stored in the stream counts like any no-progress trip")

        context.currentStreamName = "process"
        context.heartbeat() // exempt: local work after the last Jira call never fails the run
        context.currentStreamName = "issues"
        assertFailsWith<CeilingTripException> { context.heartbeat() }
    }

    @Test
    fun `the exempt set is the connector's local streams`() {
        assertEquals(setOf("process", "profile"), ch.nokillswit.jira.LOCAL_STREAMS)
    }

    @Test
    fun `a WORKLOGS run stopped by the clock stores the issues it already fetched before it stops`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(1_900_000_000_000)
        (1L..3L).forEach { id ->
            store.upsertIssue(
                connId,
                ch.nokillswit.jira.RawIssueInput(id, "FLO-$id", 1L, "FLO", 1_700_000_000_000, issue(id).toString()),
                now = 1_700_000_000_000,
            )
        }
        val client = object : UnsupportedJiraClient() {
            override suspend fun issueWorklogPage(issueId: String, startAt: Int): ch.nokillswit.jira.JiraWorklogStartAtPage {
                clock.addAndGet(3 * MINUTE)
                return ch.nokillswit.jira.JiraWorklogStartAtPage()
            }
        }
        val context = streamContext(connId, clock, maxRunMillis = 5 * MINUTE).also { it.currentStreamName = "worklogs" }
        val trip = assertFailsWith<CeilingTripException> { ch.nokillswit.jira.JiraWorklogStream(client, store).run(context) }
        assertTrue(trip.madeProgress, "the flush stored issue 1: the trip is rebuilt after it as a trip with progress")
        assertEquals(listOf(2L, 3L), store.staleWorklogIssueIds(connId, 10), "issue 1 was fetched before the stop and is stamped")
    }

    // --- ISSUES: stop on the clock, resume from the cursor ---------------------------------------------------------------

    @Test
    fun `an ISSUES run past its wall clock stops between pages and the next run resumes from the cursor`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(1_900_000_000_000)
        // Each page costs 3 minutes: the 5-minute budget is gone after page 2 of 3.
        val client = Scripted(clock, 3 * MINUTE, chained(lastToken = "t2") { token -> (token?.removePrefix("t")?.toLong() ?: 0) + 1 })
        val stream = { JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10, 100) }

        val first = streamContext(connId, clock, maxRunMillis = 5 * MINUTE).also { it.currentStreamName = "issues" }
        val trip = assertFailsWith<CeilingTripException> { stream().run(first) }
        assertEquals(TIME_EXCEEDED_TRIP, trip.code)
        assertEquals(listOf<String?>(null, "t1"), client.asked, "two pages were asked before the clock stopped the run")
        assertEquals(2L, store.countIssues(connId), "both fetched pages were committed")

        val second = streamContext(connId, clock, maxRunMillis = 5 * MINUTE).also { it.currentStreamName = "issues" }
        stream().run(second)
        assertEquals(listOf<String?>(null, "t1", "t2"), client.asked, "the next run asked for the page after the last committed one")
        assertEquals(3L, store.countIssues(connId))
    }

    // --- The connector: typing and progress ----------------------------------------------------------------------------

    private fun connector(client: Scripted, clock: AtomicLong) = JiraConnector(
        newClient = { _, _, _, _: JiraAuthScheme -> client },
        now = clock::get,
        sync = JiraSyncDependencies(
            dataSources = SyncedStubFixture.dataSources(),
            rawStore = store,
            cursors = cursors,
            database = sharedDatabaseForTests(),
            workItems = SyncedStubFixture.workItems(),
            incrementalOverlapMinutes = 10,
            issuesPageSize = 100,
        ),
    )

    private fun runContext(connId: UInt, clock: AtomicLong, maxRunMillis: Long? = null) = SyncJobRunContext(
        claim = SyncJobClaim(
            1u, connId, DataSourceKind.JIRA_CLOUD, SyncJobKind.RECONCILE, attempt = 1, maxAttempts = 3, syncIntervalMinutes = 60,
        ),
        clock = clock::get,
        maxRunMillis = maxRunMillis,
        heartbeat = { _, _ -> true },
    )

    private fun limit() = JiraFetchException("LIMIT_EXCEEDED", null, "/rest/api/3/search/jql", "answer ceiling: more than 3 pages")

    @Test
    fun `a LIMIT_EXCEEDED before any page committed leaves the connector as a no-progress trip`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { Result.failure(limit()) }
        val trip = assertFailsWith<CeilingTripException> { connector(client, clock).run(runContext(connection(), clock)) }
        assertEquals(LIMIT_EXCEEDED_TRIP, trip.code)
        assertFalse(trip.madeProgress, "the very first page tripped: the next run would repeat it")
        assertIs<JiraFetchException>(trip.underlying)
        assertEquals(trip.underlying.message, trip.message, "the failure it reports is the fetch failure, unchanged")
    }

    @Test
    fun `a LIMIT_EXCEEDED after a committed page is a trip that made progress`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { token ->
            if (token == null) Result.success(JiraSearchPage(JsonArray(emptyList()), "t1")) else Result.failure(limit())
        }
        val trip = assertFailsWith<CeilingTripException> { connector(client, clock).run(runContext(connection(), clock)) }
        assertEquals(LIMIT_EXCEEDED_TRIP, trip.code)
        assertTrue(trip.madeProgress, "page 1 was committed before the ceiling tripped")
        assertEquals(listOf<String?>(null, "t1"), client.asked)
    }

    @Test
    fun `any other fetch failure is not typed as a trip`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { Result.failure(JiraFetchException("RATE_LIMITED", 429, "/rest/api/3/search/jql")) }
        val failure = assertFailsWith<JiraFetchException> { connector(client, clock).run(runContext(connection(), clock)) }
        assertEquals("RATE_LIMITED", failure.code)
    }

    // --- The worker: the deadline it computes, the job it closes -----------------------------------------------------------

    private suspend fun runReconcile(connId: UInt, connector: JiraConnector, clock: AtomicLong, maxJobMinutes: Long): SyncJobResponse {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val worker = IngestWorker(
            jobs, SyncedStubFixture.dataSources(), JobHandlerRegistry(), mapOf(DataSourceKind.JIRA_CLOUD to connector),
            IngestConfig(
                schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7,
                workerId = workerId, maxJobMinutes = maxJobMinutes,
            ),
            clock::get,
        )
        val jobId = jobs.requestJob(connId, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L).jobId
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[attempt] = 1
                it[leaseOwner] = workerId
                it[leaseUntil] = PARKED_LEASE_UNTIL
            }
        }
        worker.runJob(SyncJobClaim(jobId, connId, DataSourceKind.JIRA_CLOUD, SyncJobKind.RECONCILE, 1, 3, 60))
        return assertNotNull(jobs.read(connId, jobId))
    }

    @Test
    fun `a RECONCILE past its wall clock closes TIME_EXCEEDED between pages, and the next run succeeds`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(System.currentTimeMillis())
        // A first page that takes 6 minutes against a 5-minute budget; the retry's calls are instant and end at once.
        val slow = Scripted(clock, 6 * MINUTE) { Result.success(JiraSearchPage(JsonArray(emptyList()), "t1")) }
        val stopped = runReconcile(connId, connector(slow, clock), clock, maxJobMinutes = 5)
        assertEquals(SyncJobStatus.FAILED, stopped.status)
        assertEquals(TIME_EXCEEDED_TRIP, stopped.errorCode)
        assertTrue(stopped.errorDetail.orEmpty().startsWith("CeilingTripException:"), stopped.errorDetail)
        assertEquals(1, slow.asked.size, "the stop came after the first page's heartbeat, before a second page was asked")
        assertEquals(1L, stopped.progress?.get("pages")?.toString()?.toLong(), "the stopping heartbeat's progress is on the job")

        val quick = Scripted(clock, 0) { Result.success(JiraSearchPage(JsonArray(emptyList()), null)) }
        val finished = runReconcile(connId, connector(quick, clock), clock, maxJobMinutes = 5)
        assertEquals(SyncJobStatus.SUCCEEDED, finished.status, "${finished.errorCode} ${finished.errorDetail}")
    }

    @Test
    fun `a typed LIMIT_EXCEEDED closes the job with its own code and the fetch failure as the detail`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(System.currentTimeMillis())
        val job = runReconcile(connId, connector(Scripted(clock, 0) { Result.failure(limit()) }, clock), clock, maxJobMinutes = 60)
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals(LIMIT_EXCEEDED_TRIP, job.errorCode)
        assertTrue(job.errorDetail.orEmpty().startsWith("JiraFetchException: Jira fetch failed: LIMIT_EXCEEDED"), job.errorDetail)
        assertTrue(job.errorDetail.orEmpty().length <= 1000)
    }
}
