package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
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
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRequest
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.TIME_EXCEEDED_TRIP
import ch.nokillswit.ingest.defaultBackfillFrom
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
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
 * The RECONCILE ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause", V38), the twin of `CeilingPauseTest`:
 * a RECONCILE trip that stored no durable change counts, one that did (a tombstone or an index-gap upsert) resets the count and is still
 * no "resumable" stop, the pause at N in the job's own transaction with the
 * `job = RECONCILE` audit event and ERROR line, the reset by a succeeded RECONCILE, the fences, the scheduler skipping a paused
 * connection for RECONCILE only, and which request or save lifts which pause (the SYNC pause is `CeilingPauseTest`'s).
 */
class ReconcilePauseTest {
    private val workerId = "reconcile-pause-worker-${UUID.randomUUID().toString().take(8)}"
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
                name = unique("rpause"), enabled = enabled, syncIntervalMinutes = 60, backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = TEST_JIRA_SITE_URL, email = "svc-${unique("acct")}@example.com", apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("ENG"), authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
        connections += id
        return id
    }

    /** Requests a job of [kind], hand-claims that REAL row and runs it through the worker (the shared queue holds other tests' jobs). */
    private suspend fun run(
        connId: UInt,
        vararg outcomes: Exception?,
        kind: SyncJobKind = SyncJobKind.RECONCILE,
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

    private suspend fun nextReconcileAt(connId: UInt): Long? = suspendTransaction(sharedDatabaseForTests()) {
        DataSourceService.Connections.let { c -> c.select(c.nextReconcileAt).where { c.id eq connId }.toList().single()[c.nextReconcileAt] }
    }

    private suspend fun forceReconcilePause(connId: UInt, trips: Int = PAUSE_AFTER) {
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connId }) {
                it[reconcileLimitTrips] = trips
                it[reconcilePausedAt] = 1_700_000_000_000
                it[reconcilePauseReason] = TIME_EXCEEDED_TRIP
            }
        }
    }

    private suspend fun forceReconcileCounter(connId: UInt, trips: Int) {
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connId }) { it[reconcileLimitTrips] = trips }
        }
    }

    private suspend fun forceSyncPause(connId: UInt, trips: Int = PAUSE_AFTER) {
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connId }) {
                it[limitTrips] = trips
                it[pausedAt] = 1_700_000_000_000
                it[pauseReason] = LIMIT_EXCEEDED_TRIP
            }
        }
    }

    @Test
    fun `a RECONCILE trip with no durable change counts, one after a tombstone resets the count, the Nth pauses`() = runBlocking<Unit> {
        val connId = connection()
        val errors = LogCapture("ch.nokillswit.ingest.SyncPause")
        try {
            withAuditCapture { audits ->
                val withoutProgress = run(connId, noProgress())
                assertEquals(SyncJobStatus.FAILED, withoutProgress.status)
                assertEquals(LIMIT_EXCEEDED_TRIP, withoutProgress.errorCode)
                assertEquals(1, status(connId).reconcileLimitTrips)
                run(connId, noProgress(TIME_EXCEEDED_TRIP))
                assertEquals(2, status(connId).reconcileLimitTrips)
                assertNotNull(nextReconcileAt(connId), "the ordinary RECONCILE failure back-off is recorded as before")
                // A trip after a durable change (the set of candidates shrank) is not counted and breaks the in-a-row ...
                val withProgress = run(connId, progress(TIME_EXCEEDED_TRIP))
                assertEquals(SyncJobStatus.FAILED, withProgress.status)
                assertEquals(TIME_EXCEEDED_TRIP, withProgress.errorCode, "still a failed job: no RECONCILE stop is resumable")
                assertEquals(0, status(connId).reconcileLimitTrips)
                assertNotNull(nextReconcileAt(connId), "... and still takes the failure back-off")
                run(connId, progress(LIMIT_EXCEEDED_TRIP))
                assertEquals(0, status(connId).reconcileLimitTrips, "a reset on a zero count stays zero")
                // ... so N no-progress trips are needed again.
                run(connId, noProgress())
                run(connId, noProgress())
                assertEquals(2, status(connId).reconcileLimitTrips)
                assertNull(status(connId).reconcilePausedAt)
                assertTrue(audits.events.none { it.message == "data_source.paused" }, "nothing announced before the threshold")

                run(connId, noProgress(TIME_EXCEEDED_TRIP))
                val paused = status(connId)
                assertEquals(PAUSE_AFTER, paused.reconcileLimitTrips)
                assertNotNull(paused.reconcilePausedAt)
                assertEquals(TIME_EXCEEDED_TRIP, paused.reconcilePauseReason, "the reason is the code of the threshold-reaching trip")

                // The SYNC side is untouched: no counter, no pause, no sync failure.
                assertEquals(0, paused.limitTrips)
                assertNull(paused.pausedAt)
                assertNull(paused.pauseReason)
                assertEquals(0, paused.consecutiveFailures)
                assertNull(paused.lastSyncErrorCode)

                val event = assertNotNull(
                    audits.awaitEvent { it.message == "data_source.paused" && it.hasKeyValue("dataSourceId", connId.toLong()) },
                )
                assertTrue(event.hasKeyValue("job", "RECONCILE") && event.hasKeyValue("reason", TIME_EXCEEDED_TRIP))
                assertTrue(event.hasKeyValue("limitTrips", PAUSE_AFTER))
                val error = errors.events.single { it.level == ch.qos.logback.classic.Level.ERROR }
                assertTrue(connId.toString() in error.formattedMessage && "RECONCILE" in error.formattedMessage, error.formattedMessage)
                assertTrue(TIME_EXCEEDED_TRIP in error.formattedMessage)
                assertFalse("cap hit" in error.formattedMessage, "the line carries the constant code and numbers, never exception text")

                // A further trip (a manual run while paused) keeps counting but neither moves nor re-announces the pause.
                val pausedAt = paused.reconcilePausedAt
                run(connId, noProgress())
                assertEquals(pausedAt, status(connId).reconcilePausedAt)
                assertEquals(PAUSE_AFTER + 1, status(connId).reconcileLimitTrips)
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
    fun `a SYNC trip does not count toward the RECONCILE pause`() = runBlocking<Unit> {
        val connId = connection()
        repeat(PAUSE_AFTER) { run(connId, noProgress(), kind = SyncJobKind.SYNC) }
        assertEquals(0, status(connId).reconcileLimitTrips)
        assertNull(status(connId).reconcilePausedAt)
        assertNotNull(status(connId).pausedAt, "the SYNC pause is the SYNC counter's")
    }

    @Test
    fun `a succeeded RECONCILE resets the count, and another RECONCILE failure leaves it alone`() = runBlocking<Unit> {
        val connId = connection()
        run(connId, noProgress())
        run(connId, noProgress())
        run(connId, IllegalStateException("network down"))
        assertEquals(2, status(connId).reconcileLimitTrips, "an ordinary failure says nothing about the ceilings")

        assertEquals(SyncJobStatus.SUCCEEDED, run(connId, null).status)
        assertEquals(0, status(connId).reconcileLimitTrips)
        assertNull(nextReconcileAt(connId), "the success also clears the back-off, as before")

        run(connId, noProgress())
        assertEquals(1, status(connId).reconcileLimitTrips, "the count restarted")
        assertNull(status(connId).reconcilePausedAt)
    }

    @Test
    fun `a stale attempt and a job of an older config revision record no RECONCILE trip`() = runBlocking<Unit> {
        val connId = connection()
        val stale = run(connId, noProgress()) { jobId ->
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[attempt] = 2 }
            }
        }
        assertEquals(SyncJobStatus.RUNNING, stale.status, "the stale run closed nothing")
        assertEquals(0, status(connId).reconcileLimitTrips)

        val put = DataSourceRequest(
            name = assertNotNull(ds().read(connId)).name, enabled = false, syncIntervalMinutes = 60, backfillFrom = defaultBackfillFrom(),
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                TEST_JIRA_SITE_URL, assertNotNull(ds().read(connId)).jira!!.email, null, listOf("ENG"), JiraAuthScheme.BASIC,
            ),
        )
        assertNotNull(ds().update(connId, put))
        val old = run(connId, noProgress(), revision = 1L)
        assertEquals(SyncJobStatus.FAILED, old.status)
        assertEquals(0, status(connId).reconcileLimitTrips, "a run that began before the PUT must not re-impose the pause counter")
        assertNull(nextReconcileAt(connId))
    }

    @Test
    fun `the scheduler skips a RECONCILE-paused connection for RECONCILE only, and sees it again once resumed`() = runBlocking<Unit> {
        val connId = connection(enabled = true)
        val service = ds()
        assertTrue(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id })

        forceReconcilePause(connId)
        assertFalse(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id }, "paused: no scheduled RECONCILE")
        assertTrue(connId in service.dueForSync(SCHEDULER_NOW).map { it.id }, "the RECONCILE pause leaves SYNC running")
        assertTrue(connId in service.enabledActiveConnections().map { it.id }, "DERIVE's scan ignores the pause")

        assertNotNull(service.resumeFromReconcilePause(connId))
        assertTrue(connId in service.dueForReconcile(SCHEDULER_NOW).map { it.id })
        assertEquals(0, status(connId).reconcileLimitTrips)
    }

    @Test
    fun `Reconcile now lifts only the RECONCILE pause, Sync now only the SYNC pause, and a PUT lifts both`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("rpauseadmin", FlowUserRole.ADMIN)
        val created = admin.postJson(
            "/api/v1/data-sources",
            DataSourceRequest(
                name = unique("rpause-http"), enabled = true, syncIntervalMinutes = 60, backfillFrom = null, reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    TEST_JIRA_SITE_URL, "svc-${unique("acct")}@example.com", "token-${UUID.randomUUID()}", uniqueProjectKeys(1),
                    JiraAuthScheme.BASIC,
                ),
            ),
        ).body<DataSourceResponse>()
        connections += created.id
        assertEquals(0, created.status.reconcileLimitTrips)
        assertNull(created.status.reconcilePausedAt)
        assertNull(created.status.reconcilePauseReason)
        suspend fun current() = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().status
        suspend fun request(kind: SyncJobKind) = admin.postJson("/api/v1/data-sources/${created.id}/sync-jobs", SyncJobRequest(kind))

        withAuditCapture { audits ->
            forceReconcilePause(created.id)
            forceSyncPause(created.id, trips = 4)
            val both = current()
            assertEquals(1_700_000_000_000, both.reconcilePausedAt)
            assertEquals(TIME_EXCEEDED_TRIP, both.reconcilePauseReason)
            assertEquals(PAUSE_AFTER, both.reconcileLimitTrips)
            assertEquals(4, both.limitTrips)

            // "Sync now" leaves the RECONCILE pause alone and lifts the SYNC one.
            assertEquals(HttpStatusCode.Accepted, request(SyncJobKind.SYNC).status)
            val afterSyncNow = current()
            assertNull(afterSyncNow.pausedAt)
            assertNotNull(afterSyncNow.reconcilePausedAt, "Sync now does not resume RECONCILE")
            assertEquals(PAUSE_AFTER, afterSyncNow.reconcileLimitTrips)
            assertEquals(
                1,
                audits.events.count { it.message == "data_source.resumed" && it.hasKeyValue("dataSourceId", created.id.toLong()) },
            )

            // "Reconcile now" lifts the RECONCILE one and audits which.
            forceSyncPause(created.id)
            assertEquals(HttpStatusCode.Accepted, request(SyncJobKind.RECONCILE).status)
            val afterReconcileNow = current()
            assertNull(afterReconcileNow.reconcilePausedAt)
            assertNull(afterReconcileNow.reconcilePauseReason)
            assertEquals(0, afterReconcileNow.reconcileLimitTrips)
            assertNotNull(afterReconcileNow.pausedAt, "Reconcile now does not resume SYNC")
            val event = assertNotNull(audits.awaitEvent { it.message == "data_source.resumed" && it.hasKeyValue("via", "RECONCILE_NOW") })
            assertTrue(event.hasKeyValue("job", "RECONCILE") && event.hasKeyValue("dataSourceId", created.id.toLong()))
            assertTrue(event.hasKeyValue("reason", TIME_EXCEEDED_TRIP) && event.hasKeyValue("limitTrips", PAUSE_AFTER))

            // A second Reconcile now on a healthy RECONCILE resumes nothing, so it audits nothing.
            request(SyncJobKind.RECONCILE)
            assertEquals(
                1,
                audits.events.count {
                    it.message == "data_source.resumed" && it.hasKeyValue("via", "RECONCILE_NOW") &&
                        it.hasKeyValue("dataSourceId", created.id.toLong())
                },
            )

            // A bare counter (trips recorded, no pause yet) is cleared silently by "Reconcile now": no audit event.
            forceReconcileCounter(created.id, trips = 2)
            assertEquals(2, current().reconcileLimitTrips)
            request(SyncJobKind.RECONCILE)
            val cleared = current()
            assertEquals(0, cleared.reconcileLimitTrips)
            assertNull(cleared.reconcilePausedAt)
            assertEquals(
                1,
                audits.events.count {
                    it.message == "data_source.resumed" && it.hasKeyValue("via", "RECONCILE_NOW") &&
                        it.hasKeyValue("dataSourceId", created.id.toLong())
                },
                "a counter without a pause resumes nothing, so it audits nothing",
            )

            // A PUT lifts both pauses, one event each.
            forceReconcilePause(created.id, trips = 5)
            val put = admin.putJson(
                "/api/v1/data-sources/${created.id}",
                DataSourceRequest(
                    created.name, true, 60, created.backfillFrom, 3,
                    JiraConnectionRequest(created.jira!!.siteUrl, created.jira!!.email, null, created.jira!!.projectKeys,
                        JiraAuthScheme.BASIC),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            val afterPut = current()
            assertNull(afterPut.pausedAt)
            assertNull(afterPut.reconcilePausedAt)
            assertEquals(0, afterPut.limitTrips)
            assertEquals(0, afterPut.reconcileLimitTrips)
            for (job in listOf("SYNC", "RECONCILE")) {
                assertNotNull(
                    audits.awaitEvent {
                        it.message == "data_source.resumed" && it.hasKeyValue("via", "UPDATE") && it.hasKeyValue("job", job) &&
                            it.hasKeyValue("dataSourceId", created.id.toLong())
                    },
                    "a PUT audits the $job pause it lifted",
                )
            }
        }
    }
}
