package ch.nokillswit

import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.TIME_EXCEEDED_TRIP
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.ingest.backfillFromEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private const val HARD_BACKFILL = "2026-01-01"

/** Real milliseconds the tests give a run before the hard stop fires (the budget itself is minutes, on a fake clock). */
private const val HARD_STOP_MS = 700L

/** The test's own bound on a run that must end by the hard stop: far beyond [HARD_STOP_MS], far below a hang. */
private const val STOPPED_WITHIN_MS = 20_000L

/**
 * The hard stop behind the per-job wall clock (`.claude/docs/ingestion.md` "Ceiling trips and the pause"): a run whose page call does
 * not return is cancelled IN FLIGHT once its budget plus the grace has elapsed, and ends exactly like the graceful trip between pages
 * (`TIME_EXCEEDED`, the job closed, the cursors on the last committed page). The graceful check itself is `JiraCeilingTripTest`. The
 * budget is on a fake clock that never reaches it, so only the hard stop (real time, [HARD_STOP_MS]) can end these runs.
 */
class CeilingHardStopTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()
    private val workerId = "hardstop-worker-${UUID.randomUUID().toString().take(8)}"

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "hard-stop", enabled = false, projectKeys = listOf("FLO"))
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

    /**
     * A Jira client whose `search/jql` answers a first page (issue 1, next token `t1`) and then, for the page asked with `t1`, either
     * suspends for good ([hangs], signalling [stuck] first) or answers a last page (issue 2). [hangOnFirst]: the very first page hangs.
     */
    private inner class Hanging(var hangs: Boolean = true, private val hangOnFirst: Boolean = false) : UnsupportedJiraClient() {
        val asked = CopyOnWriteArrayList<String?>()
        val stuck = CompletableDeferred<Unit>()
        override suspend fun resolveCloudId(): String = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
            asked += nextPageToken
            if (hangs && (if (hangOnFirst) nextPageToken == null else nextPageToken == "t1")) {
                stuck.complete(Unit)
                awaitCancellation()
            }
            return if (nextPageToken == null) {
                JiraSearchPage(JsonArray(listOf(issue(1))), "t1")
            } else {
                JiraSearchPage(JsonArray(listOf(issue(2))), null)
            }
        }
    }

    private fun streamContext(connId: UInt, hardStopMillis: Long?, clock: AtomicLong = AtomicLong(1_900_000_000_000)) = StreamContext(
        connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = clock::get,
        maxRunMillis = 5 * 60_000L, deadlineExemptStreams = setOf("process"), hardStopMillis = hardStopMillis,
    )

    private fun issuesStream(client: Hanging) =
        JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(HARD_BACKFILL), HARD_BACKFILL, 10, 100)

    // --- StreamContext.withHardStop ----------------------------------------------------------------------------------

    @Test
    fun `a page call that never returns is cancelled at the hard stop, the cursor stays on the last page and the next run resumes`() =
        runBlocking<Unit> {
            val connId = connection()
            val client = Hanging()
            val context = streamContext(connId, HARD_STOP_MS).also { it.currentStreamName = "issues" }
            val trip = assertFailsWith<CeilingTripException> {
                withTimeout(STOPPED_WITHIN_MS) { context.withHardStop { issuesStream(client).run(context) } }
            }
            assertEquals(TIME_EXCEEDED_TRIP, trip.code)
            assertTrue(trip.hardStop, "the hard stop says so")
            assertTrue(trip.madeProgress, "page 1 was committed before the second call hung")
            assertEquals(listOf<String?>(null, "t1"), client.asked)
            assertEquals(1L, store.countIssues(connId), "only the committed page is stored")

            val resumed = Hanging(hangs = false)
            issuesStream(resumed).run(streamContext(connId, null).also { it.currentStreamName = "issues" })
            assertEquals("t1", resumed.asked.first(), "the next run resumes from the token of the last committed page")
            assertEquals(2L, store.countIssues(connId))
        }

    @Test
    fun `a hang on the very first page is a hard stop without progress`() = runBlocking<Unit> {
        val connId = connection()
        val context = streamContext(connId, HARD_STOP_MS).also { it.currentStreamName = "issues" }
        val trip = assertFailsWith<CeilingTripException> {
            withTimeout(STOPPED_WITHIN_MS) { context.withHardStop { issuesStream(Hanging(hangOnFirst = true)).run(context) } }
        }
        assertTrue(trip.hardStop)
        assertFalse(trip.madeProgress, "nothing was stored: counted by the pause rule like any no-progress trip")
        assertEquals(0L, store.countIssues(connId))
    }

    @Test
    fun `a page transaction in flight at the hard stop rolls back and releases its locks`() = runBlocking<Unit> {
        val connId = connection()
        val context = streamContext(connId, HARD_STOP_MS)
        val inTransaction = CompletableDeferred<Unit>()
        val trip = assertFailsWith<CeilingTripException> {
            withTimeout(STOPPED_WITHIN_MS) {
                context.withHardStop {
                    context.transaction {
                        store.insertReconcileSeen(connId, 1u, 11L)
                        inTransaction.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
        }
        assertTrue(inTransaction.isCompleted && trip.hardStop)
        // The same key again: it would wait forever on the abandoned transaction's uncommitted row if that transaction were left open.
        withTimeout(STOPPED_WITHIN_MS) { store.insertReconcileSeen(connId, 1u, 11L) }
    }

    @Test
    fun `the hard stop does not interrupt the local streams, and a run inside its budget is untouched`() = runBlocking<Unit> {
        val connId = connection()
        val context = streamContext(connId, HARD_STOP_MS)
        val done = withTimeout(STOPPED_WITHIN_MS) {
            context.withHardStop {
                delay(HARD_STOP_MS / 7)
                context.currentStreamName = "process"
                delay(HARD_STOP_MS * 2) // local work past the hard stop: not a reason to fail a run that finished its Jira work
                "finished"
            }
        }
        assertEquals("finished", done)
        assertEquals("quick", streamContext(connId, HARD_STOP_MS).withHardStop { "quick" })
        assertEquals("unarmed", streamContext(connId, null).withHardStop { delay(HARD_STOP_MS / 3); "unarmed" })
    }

    @Test
    fun `a cancellation from outside is not the hard stop and propagates untouched`() = runBlocking<Unit> {
        val context = streamContext(connection(), 60_000L)
        val started = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Throwable>()
        val run = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                context.withHardStop { started.complete(Unit); awaitCancellation() }
            } catch (thrown: Throwable) {
                outcome.complete(thrown)
                throw thrown
            }
        }
        started.await()
        run.cancel()
        val thrown = withTimeout(STOPPED_WITHIN_MS) { outcome.await() }
        assertIs<CancellationException>(thrown, "shutdown stays a cancellation, never a TIME_EXCEEDED trip")
    }

    @Test
    fun `a cancelled run that ends in an ordinary failure is still the hard stop, the failure kept as suppressed`() = runBlocking<Unit> {
        val context = streamContext(connection(), HARD_STOP_MS)
        val trip = assertFailsWith<CeilingTripException> {
            withTimeout(STOPPED_WITHIN_MS) {
                context.withHardStop {
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        // A transport reporting its cancelled call as an I/O failure, thrown without suspending again.
                        throw IllegalStateException("call cancelled", cancelled)
                    }
                }
            }
        }
        assertTrue(trip.hardStop)
        assertTrue(trip.suppressed.any { it is IllegalStateException }, "the failure the cancelled work ended in is kept")
    }

    @Test
    fun `a failure or cancellation the hard stop did not cause is reported as it is`() = runBlocking<Unit> {
        val context = streamContext(connection(), 60_000L)
        val failure = assertFailsWith<IllegalStateException> { context.withHardStop { throw IllegalStateException("plain") } }
        assertEquals("plain", failure.message)
        // The caller is live and the stop never fired: an inner cancellation must not be dressed up as TIME_EXCEEDED.
        val inner = assertFailsWith<CancellationException> { context.withHardStop { throw CancellationException("inner") } }
        assertTrue("inner" in inner.message.orEmpty(), "the inner cancellation itself: ${inner.message}")
    }

    @Test
    fun `a shutdown that races a fired hard stop wins and propagates as a plain cancellation`() = runBlocking<Unit> {
        val context = streamContext(connection(), HARD_STOP_MS)
        val gate = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Throwable>()
        val run = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // Held past the stop by a non-cancellable section, so the stop has fired when the shutdown arrives.
                context.withHardStop { withContext(NonCancellable) { gate.await() } }
            } catch (thrown: Throwable) {
                outcome.complete(thrown)
                throw thrown
            }
        }
        delay(HARD_STOP_MS * 2)
        run.cancel()
        gate.complete(Unit)
        val thrown = withTimeout(STOPPED_WITHIN_MS) { outcome.await() }
        assertIs<CancellationException>(thrown, "the worker's own cancellation is not turned into a trip: $thrown")
    }

    // --- The worker: the job it closes -------------------------------------------------------------------------------

    private fun connector(client: Hanging, clock: AtomicLong) = JiraConnector(
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

    private fun worker(jobs: SyncJobsService, connector: JiraConnector, clock: AtomicLong, hardStopMillis: Long) = IngestWorker(
        jobs, SyncedStubFixture.dataSources(), JobHandlerRegistry(), mapOf(DataSourceKind.JIRA_CLOUD to connector),
        IngestConfig(
            schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7,
            workerId = workerId, maxJobMinutes = 5,
        ),
        clock::get, onIdle = {}, hardStopAfterMillis = { hardStopMillis },
    )

    /** Requests a RECONCILE and hand-claims that real row, so [IngestWorker.runJob] closes it like a claimed job. */
    private suspend fun claimReconcile(jobs: SyncJobsService, connId: UInt): SyncJobClaim {
        val jobId = jobs.requestJob(connId, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L).jobId
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[attempt] = 1
                it[leaseOwner] = workerId
                it[leaseUntil] = PARKED_LEASE_UNTIL
            }
        }
        return SyncJobClaim(jobId, connId, DataSourceKind.JIRA_CLOUD, SyncJobKind.RECONCILE, 1, 3, 60)
    }

    private suspend fun runReconcile(connId: UInt, client: Hanging, clock: AtomicLong, hardStopMillis: Long): SyncJobResponse {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val claim = claimReconcile(jobs, connId)
        withTimeout(STOPPED_WITHIN_MS) { worker(jobs, connector(client, clock), clock, hardStopMillis).runJob(claim) }
        return assertNotNull(jobs.read(connId, claim.id))
    }

    @Test
    fun `a RECONCILE hung mid-page closes TIME_EXCEEDED by the hard stop, audited, cursor on the last page, next run succeeds`() =
        runBlocking<Unit> {
            val connId = connection()
            val clock = AtomicLong(System.currentTimeMillis())
            val client = Hanging()
            val stopped = withAuditCapture { audits ->
                runReconcile(connId, client, clock, HARD_STOP_MS).also { job ->
                    val event = assertNotNull(
                        audits.awaitEvent { it.message == "sync_job.failed" && it.hasKeyValue("jobId", job.id.toLong()) },
                    )
                    assertTrue(event.hasKeyValue("errorCode", TIME_EXCEEDED_TRIP) && event.hasKeyValue("hardStop", true), "audited as hard")
                }
            }
            assertEquals(SyncJobStatus.FAILED, stopped.status, "closed, not left RUNNING until the lease expires")
            assertEquals(TIME_EXCEEDED_TRIP, stopped.errorCode)
            assertTrue("mid-page" in stopped.errorDetail.orEmpty(), "the detail says the stop was hard: ${stopped.errorDetail}")
            assertNotNull(stopped.finishedAt)
            assertNull(stopped.leaseUntil, "the lease is released with the close")
            assertEquals(listOf<String?>(null, "t1"), client.asked)
            assertEquals(1L, stopped.progress?.get("pages")?.toString()?.toLong(), "the one committed page is on the job")
            assertTrue("t1" in assertNotNull(cursors.get(connId, "reconcile")).cursor, "the cursor names the last committed page's token")

            client.hangs = false
            val finished = runReconcile(connId, client, clock, HARD_STOP_MS)
            assertEquals(SyncJobStatus.SUCCEEDED, finished.status, "${finished.errorCode} ${finished.errorDetail}")
        }

    @Test
    fun `a graceful trip is not marked hard, and a run inside its budget is not stopped`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(System.currentTimeMillis())
        val finished = runReconcile(connId, Hanging(hangs = false), clock, hardStopMillis = 60_000L)
        assertEquals(SyncJobStatus.SUCCEEDED, finished.status, "${finished.errorCode} ${finished.errorDetail}")
        val graceful = CeilingTripException(TIME_EXCEEDED_TRIP, madeProgress = true)
        assertFalse(graceful.hardStop)
        assertTrue("between pages" in graceful.message.orEmpty() && "mid-page" !in graceful.message.orEmpty())
        assertNull(CeilingTripException(TIME_EXCEEDED_TRIP, madeProgress = false).underlying)
    }

    @Test
    fun `worker shutdown during a hung page releases the job to PENDING and is not a TIME_EXCEEDED`() = runBlocking<Unit> {
        val connId = connection()
        val clock = AtomicLong(System.currentTimeMillis())
        val client = Hanging()
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val claim = claimReconcile(jobs, connId)
        val worker = worker(jobs, connector(client, clock), clock, hardStopMillis = 60_000L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val run = scope.launch { worker.runJob(claim) }
        withTimeout(STOPPED_WITHIN_MS) { client.stuck.await() }
        scope.cancel()
        withTimeout(STOPPED_WITHIN_MS) { run.join() }
        val job = assertNotNull(jobs.read(connId, claim.id))
        assertEquals(SyncJobStatus.PENDING, job.status, "released for the next worker, as before the hard stop existed")
        assertNull(job.errorCode)
        assertTrue(run.isCancelled, "the worker's own cancellation still ends the job's coroutine")
    }
}
