package ch.nokillswit

import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.LIMIT_EXCEEDED_TRIP
import ch.nokillswit.ingest.SyncJobActionResult
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRequest
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.TIME_EXCEEDED_TRIP
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.call.body
import kotlinx.coroutines.flow.toList
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2024-01-01T05:00:00Z: past the 03:00 UTC reconcile boundary, so a connection with no RECONCILE yet is due for one. */
private val SCHEDULER_NOW = java.time.Instant.parse("2024-01-01T05:00:00Z").toEpochMilli()

private const val PAUSE_AFTER = 3

/**
 * The SYNC ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause", V37; RECONCILE's own pause is
 * `ReconcilePauseTest`): the progress rule (a trip counts only when it stored no change: in the stream that tripped for a limit,
 * anywhere in the run for the clock), the pause at N in the job's own transaction, the reset by a succeeded SYNC, the scheduler
 * skipping a paused connection (SYNC only: the SYNC pause leaves RECONCILE running), "Sync now" and the PUT resuming it, the audit
 * events, and the fences (a stale attempt, a job of an older config revision). The real streams' side (wall clock,
 * `LIMIT_EXCEEDED` typing) is `JiraCeilingTripTest`.
 */
class CeilingPauseTest {
    private val workerId = "pause-worker-${UUID.randomUUID().toString().take(8)}"
    private val connections = mutableListOf<UInt>()

    /** What a scripted run does: throw the given failure, or return (succeed). */
    private class Scripted(private val outcomes: ArrayDeque<Exception?>) : Connector {
        override val kind = DataSourceKind.JIRA_CLOUD
        override suspend fun testConnection(target: ConnectionTarget): ConnectionTestResult = error("not used")

        override suspend fun run(context: SyncJobRunContext) {
            outcomes.removeFirst()?.let { throw it }
        }
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun ds() = SyncedStubFixture.dataSources()

    private fun config() = IngestConfig(
        schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7,
        workerId = workerId, maxJobMinutes = 60, pauseAfterLimitTrips = PAUSE_AFTER,
    )

    private fun noProgress(code: String = LIMIT_EXCEEDED_TRIP) =
        CeilingTripException(code, madeProgress = false, IllegalStateException("cap hit"))

    private fun progress(code: String = LIMIT_EXCEEDED_TRIP) =
        CeilingTripException(code, madeProgress = true, IllegalStateException("cap hit"))

    /** The test's jobs must not linger in the shared queue (a succeeded SYNC chains a DERIVE), nor its connections stay enabled. */
    @After
    fun cleanUp() = runBlocking<Unit> {
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({
                (SyncJobsService.Jobs.connectionId inList connections) and
                    (SyncJobsService.Jobs.status inList listOf(SyncJobStatus.PENDING.name, SyncJobStatus.RUNNING.name))
            }) { it[status] = SyncJobStatus.CANCELLED.name }
        }
        connections.forEach { ds().delete(it) }
    }

    private suspend fun connection(enabled: Boolean = false): UInt {
        SyncedStubFixture.ensureMigrated()
        val id = ds().create(
            DataSourceRequest(
                name = unique("pause"),
                enabled = enabled,
                syncIntervalMinutes = 60,
                backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = TEST_JIRA_SITE_URL,
                    email = "svc-${unique("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("ENG"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
        connections += id
        return id
    }

    /** Requests a job of [kind], hand-claims that REAL row (the shared queue holds other tests' jobs) and runs it through the worker. */
    private suspend fun run(
        connId: UInt,
        vararg outcomes: Exception?,
        kind: SyncJobKind = SyncJobKind.SYNC,
        attempt: Int = 1,
        revision: Long? = null,
        beforeRun: suspend (jobId: UInt) -> Unit = {},
    ): SyncJobResponse {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val worker = IngestWorker(
            jobs, ds(), JobHandlerRegistry(), mapOf(DataSourceKind.JIRA_CLOUD to Scripted(ArrayDeque(outcomes.toList()))), config(),
            System::currentTimeMillis,
        )
        val configRevision = revision ?: assertNotNull(ds().read(connId)).configRevision
        val jobId = jobs.requestJob(connId, kind, requestedByUserId = 1u, configRevision = configRevision).jobId
        suspendTransaction(sharedDatabaseForTests()) {
            SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[SyncJobsService.Jobs.attempt] = attempt
                it[leaseOwner] = workerId
                it[leaseUntil] = PARKED_LEASE_UNTIL
            }
        }
        beforeRun(jobId)
        worker.runJob(SyncJobClaim(jobId, connId, DataSourceKind.JIRA_CLOUD, kind, attempt, maxAttempts = 3, syncIntervalMinutes = 60))
        return assertNotNull(jobs.read(connId, jobId))
    }

    private suspend fun status(connId: UInt) = assertNotNull(ds().read(connId)).status

    /** Marks [connId] as paused the way a worker would have, for the resume tests. */
    private suspend fun forcePause(connId: UInt, trips: Int = PAUSE_AFTER) {
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connId }) {
                it[limitTrips] = trips
                it[pausedAt] = 1_700_000_000_000
                it[pauseReason] = LIMIT_EXCEEDED_TRIP
            }
        }
    }

    @Test
    fun `no-progress trips count, the Nth pauses with an ERROR line and one audit event, and the pause stays put`() = runBlocking<Unit> {
        val connId = connection()
        val errors = LogCapture("ch.nokillswit.ingest.SyncPause")
        try {
            withAuditCapture { audits ->
                repeat(PAUSE_AFTER - 1) { n ->
                    val job = run(connId, noProgress())
                    assertEquals(SyncJobStatus.FAILED, job.status)
                    assertEquals(LIMIT_EXCEEDED_TRIP, job.errorCode, "the job carries the trip's constant code")
                    assertEquals("IllegalStateException: cap hit", job.errorDetail, "the detail names the failure the trip wraps")
                    assertEquals(n + 1, status(connId).limitTrips)
                    assertNull(status(connId).pausedAt, "below the threshold the connection is not paused")
                }
                assertTrue(audits.events.none { it.message == "data_source.paused" }, "nothing announced before the threshold")

                run(connId, noProgress(TIME_EXCEEDED_TRIP))
                val paused = status(connId)
                assertEquals(PAUSE_AFTER, paused.limitTrips)
                assertNotNull(paused.pausedAt)
                assertEquals(TIME_EXCEEDED_TRIP, paused.pauseReason, "the reason is the code of the trip that reached the threshold")
                assertEquals(TIME_EXCEEDED_TRIP, paused.lastSyncErrorCode)
                assertEquals(PAUSE_AFTER, paused.consecutiveFailures, "the ordinary failure back-off is recorded as before")

                val event = assertNotNull(
                    audits.awaitEvent { it.message == "data_source.paused" && it.hasKeyValue("dataSourceId", connId.toLong()) },
                )
                assertTrue(event.hasKeyValue("reason", TIME_EXCEEDED_TRIP) && event.hasKeyValue("limitTrips", PAUSE_AFTER))
                assertTrue(event.hasKeyValue("job", "SYNC"), "the event names which job paused")
                val error = errors.events.single { it.level == ch.qos.logback.classic.Level.ERROR }
                assertTrue("${connId}" in error.formattedMessage && TIME_EXCEEDED_TRIP in error.formattedMessage, error.formattedMessage)
                assertFalse("cap hit" in error.formattedMessage, "the line carries the constant code and numbers, never exception text")

                // A further trip (a manual run while paused) keeps counting but neither moves nor re-announces the pause.
                val pausedAt = paused.pausedAt
                run(connId, noProgress())
                assertEquals(pausedAt, status(connId).pausedAt)
                assertEquals(PAUSE_AFTER + 1, status(connId).limitTrips)
                assertEquals(
                    1,
                    audits.events.count { it.message == "data_source.paused" && it.hasKeyValue("dataSourceId", connId.toLong()) },
                )
            }
        } finally {
            errors.detach()
        }
    }

    @Test
    fun `a trip that stored pages is not counted and breaks the in-a-row, so scattered trips never pause`() = runBlocking<Unit> {
        val connId = connection()
        run(connId, noProgress())
        run(connId, noProgress())
        assertEquals(2, status(connId).limitTrips)

        val failuresBefore = status(connId).consecutiveFailures
        val advanced = run(connId, progress(LIMIT_EXCEEDED_TRIP))
        assertEquals(LIMIT_EXCEEDED_TRIP, advanced.errorCode)
        assertEquals(0, status(connId).limitTrips, "a ceiling stop that stored pages is not counted and resets the count")
        assertEquals(failuresBefore + 1, status(connId).consecutiveFailures, "a LIMIT_EXCEEDED keeps the failure back-off")
        assertNull(status(connId).pausedAt)

        run(connId, noProgress())
        run(connId, noProgress())
        assertEquals(2, status(connId).limitTrips)
        assertNull(status(connId).pausedAt, "two no-progress trips after a progress one are not three in a row")
        run(connId, noProgress())
        assertNotNull(status(connId).pausedAt, "the third in a row pauses")
    }

    @Test
    fun `a wall-clock stop after stored pages reschedules at the normal interval without a failure or a count`() = runBlocking<Unit> {
        val connId = connection()
        run(connId, noProgress())
        val before = status(connId)
        val stoppedAt = System.currentTimeMillis()
        val job = withAuditCapture { audits ->
            run(connId, progress(TIME_EXCEEDED_TRIP)).also { stopped ->
                val event = assertNotNull(
                    audits.awaitEvent { it.message == "sync_job.failed" && it.hasKeyValue("jobId", stopped.id.toLong()) },
                )
                assertTrue(event.hasKeyValue("resumable", true) && event.hasKeyValue("errorCode", TIME_EXCEEDED_TRIP), "marked resumable")
            }
        }
        assertEquals(SyncJobStatus.FAILED, job.status, "the job row stays FAILED / TIME_EXCEEDED for the record")
        assertEquals(TIME_EXCEEDED_TRIP, job.errorCode)
        val after = status(connId)
        assertEquals(before.consecutiveFailures, after.consecutiveFailures, "no failure recorded")
        assertEquals(before.lastSyncErrorCode, after.lastSyncErrorCode)
        assertEquals(0, after.limitTrips, "a trip with progress breaks the in-a-row")
        val nextSyncAt = nextSyncAt(connId)
        val interval = 60 * 60_000L
        assertNotNull(nextSyncAt)
        assertTrue(nextSyncAt in (stoppedAt + interval)..(System.currentTimeMillis() + interval), "next_sync_at = now + the sync interval")

        // The same stop with nothing stored behaves like any no-progress trip: counted, with the back-off - and, with no failure
        // behind it, a WARN without a stack trace (the resumable one above was an INFO).
        val log = LogCapture("ch.nokillswit.ingest.IngestWorker")
        try {
            run(connId, CeilingTripException(TIME_EXCEEDED_TRIP, madeProgress = false))
            val warn = log.events.single { it.level == ch.qos.logback.classic.Level.WARN && "failed" in it.formattedMessage }
            assertNull(warn.throwableProxy, "the clock has no stack trace worth logging")
        } finally {
            log.detach()
        }
        assertEquals(1, status(connId).limitTrips)
        assertEquals(before.consecutiveFailures + 1, status(connId).consecutiveFailures)
    }

    private suspend fun nextSyncAt(connId: UInt): Long? = suspendTransaction(sharedDatabaseForTests()) {
        DataSourceService.Connections.let { c -> c.select(c.nextSyncAt).where { c.id eq connId }.toList().single()[c.nextSyncAt] }
    }

    @Test
    fun `a succeeded SYNC resets the count, and another failure leaves it alone`() = runBlocking<Unit> {
        val connId = connection()
        run(connId, noProgress())
        run(connId, noProgress())
        run(connId, IllegalStateException("network down"))
        assertEquals(2, status(connId).limitTrips, "an ordinary failure says nothing about the ceilings")
        assertEquals("RUN_FAILED", status(connId).lastSyncErrorCode)

        assertEquals(SyncJobStatus.SUCCEEDED, run(connId, null).status)
        assertEquals(0, status(connId).limitTrips)

        run(connId, noProgress())
        assertEquals(1, status(connId).limitTrips, "the count restarted")
        assertNull(status(connId).pausedAt)
    }

    @Test
    fun `the scheduler skips a paused connection for SYNC but not RECONCILE, and sees it again once resumed`() = runBlocking<Unit> {
        val connId = connection(enabled = true)
        val service = ds()
        assertTrue(connId in service.dueForSync(SCHEDULER_NOW).map { it.id }, "a fresh enabled connection is due for SYNC")
        assertTrue(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id }, "... and for RECONCILE past its boundary")

        forcePause(connId)
        assertFalse(connId in service.dueForSync(SCHEDULER_NOW).map { it.id }, "paused: no scheduled SYNC")
        assertTrue(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id }, "paused: RECONCILE has its own back-off and goes on")
        assertTrue(connId in service.enabledActiveConnections().map { it.id }, "DERIVE's scan ignores the pause: reports keep working")

        assertNotNull(service.resumeFromPause(connId))
        assertTrue(connId in service.dueForSync(SCHEDULER_NOW).map { it.id })
        assertTrue(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id })
    }

    @Test
    fun `a stale attempt and a job of an older config revision record no trip`() = runBlocking<Unit> {
        val connId = connection()
        // Stale: the row is moved on to attempt 2 before the run (attempt 1) closes, so the fenced write matches nothing.
        val stale = run(connId, noProgress()) { jobId ->
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[attempt] = 2 }
            }
        }
        assertEquals(SyncJobStatus.RUNNING, stale.status, "the stale run closed nothing")
        assertEquals(0, status(connId).limitTrips)
        assertEquals(0, status(connId).consecutiveFailures)
        // ... and the same on the resumable path: a stale clock stop reschedules nothing.
        run(connId, progress(TIME_EXCEEDED_TRIP)) { jobId ->
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[attempt] = 2 }
            }
        }
        assertNull(nextSyncAt(connId), "a fenced-out resumable stop writes no schedule")

        // Older revision: a job requested at revision 1 closes after the connection moved to 2 (a PUT) - no back-off, no count.
        val put = DataSourceRequest(
            name = assertNotNull(ds().read(connId)).name, enabled = false, syncIntervalMinutes = 60, backfillFrom = defaultBackfillFrom(),
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                TEST_JIRA_SITE_URL, assertNotNull(ds().read(connId)).jira.email, null, listOf("ENG"), JiraAuthScheme.BASIC,
            ),
        )
        assertNotNull(ds().update(connId, put))
        val old = run(connId, noProgress(), revision = 1L)
        assertEquals(SyncJobStatus.FAILED, old.status)
        assertEquals(0, status(connId).limitTrips, "a run that began before the PUT must not re-impose the pause counter")
        assertEquals(0, status(connId).consecutiveFailures)
        run(connId, progress(TIME_EXCEEDED_TRIP), revision = 1L)
        assertNull(nextSyncAt(connId), "nor does a resumable stop of an older config revision")
    }

    @Test
    fun `Sync now and the config PUT resume a paused connection and audit it, a Reconcile now does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("pauseadmin", FlowUserRole.ADMIN)
        val created = admin.postJson(
            "/api/v1/data-sources",
            DataSourceRequest(
                name = unique("pause-http"), enabled = true, syncIntervalMinutes = 60, backfillFrom = null, reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    TEST_JIRA_SITE_URL, "svc-${unique("acct")}@example.com", "token-${UUID.randomUUID()}", uniqueProjectKeys(1),
                    JiraAuthScheme.BASIC,
                ),
            ),
        ).body<DataSourceResponse>()
        connections += created.id
        assertEquals(0, created.status.limitTrips)
        assertNull(created.status.pausedAt)

        withAuditCapture { audits ->
            forcePause(created.id)
            val paused = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>()
            assertEquals(1_700_000_000_000, paused.status.pausedAt)
            assertEquals(LIMIT_EXCEEDED_TRIP, paused.status.pauseReason)
            assertEquals(PAUSE_AFTER, paused.status.limitTrips)

            val reconcile = admin.postJson("/api/v1/data-sources/${created.id}/sync-jobs", SyncJobRequest(SyncJobKind.RECONCILE))
            assertEquals(HttpStatusCode.Accepted, reconcile.status)
            val stillPaused = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().status.pausedAt
            assertNotNull(stillPaused, "Reconcile now does not resume")

            val syncNow = admin.postJson("/api/v1/data-sources/${created.id}/sync-jobs", SyncJobRequest(SyncJobKind.SYNC))
            assertEquals(HttpStatusCode.Accepted, syncNow.status)
            assertEquals(SyncJobKind.SYNC, syncNow.body<SyncJobActionResult>().job.kind)
            val afterSyncNow = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().status
            assertNull(afterSyncNow.pausedAt)
            assertNull(afterSyncNow.pauseReason)
            assertEquals(0, afterSyncNow.limitTrips)
            val syncEvent = assertNotNull(audits.awaitEvent { it.message == "data_source.resumed" && it.hasKeyValue("via", "SYNC_NOW") })
            assertTrue(syncEvent.hasKeyValue("dataSourceId", created.id.toLong()) && syncEvent.hasKeyValue("limitTrips", PAUSE_AFTER))
            assertTrue(syncEvent.hasKeyValue("reason", LIMIT_EXCEEDED_TRIP) && syncEvent.hasKeyValue("job", "SYNC"))

            // A second Sync now on a healthy connection resumes nothing, so it audits nothing.
            admin.postJson("/api/v1/data-sources/${created.id}/sync-jobs", SyncJobRequest(SyncJobKind.SYNC))
            assertEquals(
                1,
                audits.events.count { it.message == "data_source.resumed" && it.hasKeyValue("dataSourceId", created.id.toLong()) },
            )

            // A bare counter (no pause) lifted by a save is silent: nothing was paused, so nothing is resumed.
            suspendTransaction(sharedDatabaseForTests()) {
                DataSourceService.Connections.update({ DataSourceService.Connections.id eq created.id }) { it[limitTrips] = 2 }
            }
            val bare = DataSourceRequest(
                created.name, true, 60, created.backfillFrom, 3,
                JiraConnectionRequest(created.jira.siteUrl, created.jira.email, null, created.jira.projectKeys, JiraAuthScheme.BASIC),
            )
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/${created.id}", bare).status)
            assertEquals(0, admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().status.limitTrips)
            assertTrue(audits.events.none { it.message == "data_source.resumed" && it.hasKeyValue("via", "UPDATE") })

            forcePause(created.id, trips = 4)
            val put = admin.putJson(
                "/api/v1/data-sources/${created.id}",
                DataSourceRequest(
                    created.name, true, 60, created.backfillFrom, 3,
                    JiraConnectionRequest(created.jira.siteUrl, created.jira.email, null, created.jira.projectKeys, JiraAuthScheme.BASIC),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            val afterPut = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().status
            assertNull(afterPut.pausedAt)
            assertEquals(0, afterPut.limitTrips)
            val putEvent = assertNotNull(audits.awaitEvent { it.message == "data_source.resumed" && it.hasKeyValue("via", "UPDATE") })
            assertTrue(putEvent.hasKeyValue("limitTrips", 4) && putEvent.hasKeyValue("dataSourceId", created.id.toLong()))
            assertTrue(putEvent.hasKeyValue("job", "SYNC"))
        }
    }
}
