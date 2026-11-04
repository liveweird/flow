package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunCeiling
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
import ch.nokillswit.jira.JIRA_SOURCE
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.ingest.backfillFromEpochMillis
import ch.nokillswit.ingest.ceilingTripOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
private const val UPDATED = "2026-05-20T10:00:00.000+0000"

/**
 * The per-job wall clock and the typing of a ceiling trip on the REAL streams (`.claude/docs/ingestion.md` "Ceiling trips and the
 * pause"): the job stops BETWEEN pages once its `ingest.maxJobMinutes` is up (`TIME_EXCEEDED`, a fake clock), the next run resumes
 * from the cursor the stopped one left, a `LIMIT_EXCEEDED` fetch failure leaves the connector as a [CeilingTripException] saying
 * whether the stream that tripped had stored a change, and any other failure stays what it was. The pause arithmetic itself is
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
                JiraRawStore.Entities.deleteWhere { JiraRawStore.Entities.connectionId eq connId }
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
        context.heartbeat(changed = true) // one millisecond short: no trip
        clock.addAndGet(1)
        val trip = assertFailsWith<CeilingTripException> { context.heartbeat(changed = false) }
        assertEquals(TIME_EXCEEDED_TRIP, trip.code)
        assertTrue(trip.madeProgress, "the run stored a change before the clock stopped it")
        assertNull(trip.underlying)
        assertEquals(2, beats.get(), "the stopping heartbeat flushed its progress before it threw")
        assertTrue("maxJobMinutes" in trip.message.orEmpty() && "http" !in trip.message.orEmpty(), trip.message)
    }

    @Test
    fun `without a deadline a heartbeat never stops the run`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val context = streamContext(1u, clock, maxRunMillis = null)
        clock.addAndGet(1_000 * MINUTE)
        context.heartbeat(changed = true)
    }

    @Test
    fun `a limit trip's progress is its own stream's changed pages, restarted when a stream is named`() = runBlocking<Unit> {
        val context = streamContext(1u, AtomicLong(1), maxRunMillis = null)
        context.currentStreamName = "reference"
        context.heartbeat(changed = true)
        context.heartbeat(changed = true)
        context.currentStreamName = "issues"
        val fresh = context.ceilingTrip(LIMIT_EXCEEDED_TRIP, IllegalStateException("x"))
        assertFalse(fresh.madeProgress, "an earlier stream's changes are not this one's")
        context.heartbeat(changed = true)
        assertTrue(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, IllegalStateException("x")).madeProgress)
        context.currentStreamName = "changelogs"
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress)
        context.markChanged() // rows a stream stored between its heartbeats are a change too
        assertTrue(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress)
    }

    @Test
    fun `a page whose rows are all unchanged is not progress`() = runBlocking<Unit> {
        val context = streamContext(1u, AtomicLong(1), maxRunMillis = null)
        context.currentStreamName = "issues"
        repeat(3) { context.heartbeat(changed = false) }
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress, "three committed pages that stored nothing new")
        assertFalse(context.ceilingTrip(TIME_EXCEEDED_TRIP, null).madeProgress, "nor are they progress across the run")
        context.heartbeat(changed = true)
        assertTrue(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress, "one changed page after them is")
    }

    @Test
    fun `a wall-clock stop reads progress across every stream of the run, a limit trip only its own`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val context = streamContext(1u, clock, maxRunMillis = 5 * MINUTE)
        context.currentStreamName = "reference"
        context.heartbeat(changed = true)
        context.currentStreamName = "issues"
        context.heartbeat(changed = false)
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress, "the limit trip blames the stream it happened in")
        assertTrue(context.ceilingTrip(TIME_EXCEEDED_TRIP, null).madeProgress, "the clock blames nobody: REFERENCE stored a change")
        clock.addAndGet(5 * MINUTE)
        val trip = assertFailsWith<CeilingTripException> { context.heartbeat(changed = false) }
        assertEquals(TIME_EXCEEDED_TRIP, trip.code)
        assertTrue(trip.madeProgress, "the heartbeat that stops the run reads the whole run")

        val idle = streamContext(1u, clock, maxRunMillis = 5 * MINUTE)
        idle.currentStreamName = "issues"
        clock.addAndGet(5 * MINUTE)
        assertFalse(assertFailsWith<CeilingTripException> { idle.heartbeat(changed = false) }.madeProgress, "nothing changed anywhere")
    }

    @Test
    fun `an uncommitted heartbeat enforces the clock but is not progress, and the local streams are exempt`() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000)
        val context = streamContext(1u, clock, maxRunMillis = 5 * MINUTE, exempt = setOf("process"))
        context.currentStreamName = "worklogs"
        context.heartbeat(changed = false)
        assertFalse(context.ceilingTrip(LIMIT_EXCEEDED_TRIP, null).madeProgress, "a fetch that was never flushed is not a stored page")
        clock.addAndGet(5 * MINUTE)
        val trip = assertFailsWith<CeilingTripException> { context.heartbeat(changed = false) }
        assertFalse(trip.madeProgress, "a clock stop with nothing stored in the run counts like any no-progress trip")

        context.currentStreamName = "process"
        context.heartbeat(changed = true) // exempt: local work after the last Jira call never fails the run
        context.currentStreamName = "issues"
        assertFailsWith<CeilingTripException> { context.heartbeat(changed = false) }
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
                ch.nokillswit.jira.RawIssueInput(id, "FLO-$id", 1L, "FLO", 1_700_000_000_000, issue(id)),
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

    @Test
    fun `an ISSUES run that re-stores identical pages before its ceiling is a no-progress trip`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0, chained(lastToken = "t2") { token -> (token?.removePrefix("t")?.toLong() ?: 0) + 1 })
        // Two pages a run: the third page's request trips the run ceiling, with the cursor on the token that asks it.
        val ceiling = RunCeiling(2, 1_000)
        val stream = { JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10, 100, ceiling) }
        val runOnce = suspend {
            val context = streamContext(connId, clock, maxRunMillis = null).also { it.currentStreamName = "issues" }
            val failure = assertFailsWith<FetchException> { stream().run(context) }
            assertEquals(LIMIT_EXCEEDED_TRIP, failure.code)
            assertNotNull(failure.ceilingTripOf(context))
        }

        assertTrue(runOnce().madeProgress, "the first run stored two new issues")
        assertEquals(2L, store.countIssues(connId))

        // A restart from the top (a cursor the host refused, a reset): the same two pages come back byte for byte.
        cursors.clear(connId, "issues")
        assertFalse(runOnce().madeProgress, "the second run re-stored the same two pages: committed, but nothing changed")
        assertEquals(2L, store.countIssues(connId))
    }

    /** Runs the ISSUES stream once over [pages] (each call costs nothing); a LIMIT_EXCEEDED on the page after the first is the trip. */
    private suspend fun issuesTripAfterFirstPage(connId: UInt, clock: AtomicLong, firstPage: List<JsonElement>): CeilingTripException {
        val client = Scripted(clock, 0) { token ->
            if (token == null) Result.success(JiraSearchPage(JsonArray(firstPage), "t1")) else Result.failure(limit())
        }
        val context = streamContext(connId, clock, maxRunMillis = null).also { it.currentStreamName = "issues" }
        val failure = assertFailsWith<FetchException> {
            JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10, 100).run(context)
        }
        return assertNotNull(failure.ceilingTripOf(context))
    }

    @Test
    fun `an ISSUES run whose only change is moving a removed project's issues out of scope made progress`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(1_900_000_000_000)
        store.upsertEntity(connId, "PROJECT", "1", """{"id":"1","key":"FLO"}""", now = 1_700_000_000_000)
        // An issue of a project the connection no longer lists: the run tombstones it before any page is asked.
        val old = ch.nokillswit.jira.RawIssueInput(9, "OLD-9", 9L, "OLD", 1_700_000_000_000, issue(9))
        store.upsertIssue(connId, old, now = 1_700_000_000_000)
        assertTrue(issuesTripAfterFirstPage(connId, clock, emptyList()).madeProgress, "the tombstone is a stored change")
        // The tombstone is stored now: the same run again moves nothing and its empty page stores nothing.
        cursors.clear(connId, "issues")
        assertFalse(issuesTripAfterFirstPage(connId, clock, emptyList()).madeProgress, "nothing left to move out, nothing stored")
    }

    @Test
    fun `an ISSUES page redone issue by issue is progress when any one issue changed, and not when all came back unchanged`() =
        runBlocking<Unit> {
            val connId = connection()
            val clock = AtomicLong(1_900_000_000_000)
            // A key longer than the column is rejected as a bad value: the page is redone one by one and the bad issue skipped.
            val bad = Json.parseToJsonElement(
                """{"id":"2","key":"FLO-${"9".repeat(30)}","fields":{"project":{"id":"1","key":"FLO"},"updated":"$UPDATED"}}""",
            )
            val stored = listOf(issue(1), issue(3))
            // Setup: issues 1 and 3 are stored (a clean page, completed).
            val setup = Scripted(clock, 0) { Result.success(JiraSearchPage(JsonArray(stored), null)) }
            JiraIssuesStream(setup, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10, 100)
                .run(streamContext(connId, clock, maxRunMillis = null).also { it.currentStreamName = "issues" })
            assertEquals(2L, store.countIssues(connId))

            val changed = Json.parseToJsonElement(
                """{"id":"3","key":"FLO-3","fields":{"project":{"id":"1","key":"FLO"},"updated":"$UPDATED","summary":"x"}}""",
            )
            cursors.clear(connId, "issues")
            val withChange = issuesTripAfterFirstPage(connId, clock, listOf(issue(1), bad, changed))
            assertTrue(withChange.madeProgress, "issue 3 changed among an unchanged one and a skipped one")

            cursors.clear(connId, "issues")
            val allSame = issuesTripAfterFirstPage(connId, clock, listOf(issue(1), bad, changed))
            assertFalse(allSame.madeProgress, "the same page again: every surviving issue is unchanged")
            assertEquals(2L, store.countIssues(connId), "the bad issue was never stored")
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

    private fun limit() = FetchException(JIRA_SOURCE, "LIMIT_EXCEEDED", null, "/rest/api/3/search/jql", "answer ceiling: more than 3 pages")

    @Test
    fun `a LIMIT_EXCEEDED before any page committed leaves the connector as a no-progress trip`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { Result.failure(limit()) }
        val trip = assertFailsWith<CeilingTripException> { connector(client, clock).run(runContext(connection(), clock)) }
        assertEquals(LIMIT_EXCEEDED_TRIP, trip.code)
        assertFalse(trip.madeProgress, "the very first page tripped: the next run would repeat it")
        assertIs<FetchException>(trip.underlying)
        assertEquals(trip.underlying.message, trip.message, "the failure it reports is the fetch failure, unchanged")
    }

    @Test
    fun `a RECONCILE LIMIT_EXCEEDED after a committed sweep page made no progress, the page is scratch`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { token ->
            if (token == null) Result.success(JiraSearchPage(JsonArray(emptyList()), "t1")) else Result.failure(limit())
        }
        val trip = assertFailsWith<CeilingTripException> { connector(client, clock).run(runContext(connection(), clock)) }
        assertEquals(LIMIT_EXCEEDED_TRIP, trip.code)
        assertFalse(trip.madeProgress, "page 1 only filled the job's scratch seen-set: the next run starts the sweep over")
        assertEquals(listOf<String?>(null, "t1"), client.asked)
    }

    @Test
    fun `any other fetch failure is not typed as a trip`() = runBlocking<Unit> {
        val clock = AtomicLong(1_900_000_000_000)
        val client = Scripted(clock, 0) { Result.failure(FetchException(JIRA_SOURCE, "RATE_LIMITED", 429, "/rest/api/3/search/jql")) }
        val failure = assertFailsWith<FetchException> { connector(client, clock).run(runContext(connection(), clock)) }
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
        assertTrue(job.errorDetail.orEmpty().startsWith("FetchException: Jira fetch failed: LIMIT_EXCEEDED"), job.errorDetail)
        assertTrue(job.errorDetail.orEmpty().length <= 1000)
    }
}
