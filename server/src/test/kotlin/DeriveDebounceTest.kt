package ch.nokillswit

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobPageResponse
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.SyncStatusResponse
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DERIVE debounce (V27 `sync_jobs.not_before`, `.claude/docs/ingestion.md` "Worker scheduler"): a configuration-caused
 * `DERIVE` is scheduled `quiet period` after the last edit, so a burst of edits becomes one DERIVE, while a DERIVE chained after a
 * SYNC and a manual one stay immediate. Direct service calls against [sharedDatabaseForTests] under an injected clock (no sleeps): the
 * connection is ENABLED (a revision bump only targets those), is the only one the worker may claim ([withOnlyConnections]), and its
 * jobs, team and row are torn down afterwards. The DERIVE handler is a counter — what is pinned here is WHEN a DERIVE runs and how
 * many, not what it computes.
 */
class DeriveDebounceTest {
    private val clock = AtomicLong(T0)
    private val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { clock.get() }, deriveQuietMillis = QUIET_MILLIS)
    private val dataSources = SyncedStubFixture.dataSources()
    private val settings = MetricsSettingsService(sharedDatabaseForTests(), dataSources, jobs)
    private val workerId = "debounce-worker-${UUID.randomUUID().toString().take(8)}"
    private val derivesRun = AtomicInteger()

    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())

    /** The connection's DERIVE jobs, minus the CANCELLED ones the setup drains ([withConnection], the fixtures' own fan-out). */
    private suspend fun derives(connectionId: UInt): List<SyncJobResponse> =
        jobs.list(connectionId, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
            .filter { it.status != SyncJobStatus.CANCELLED }

    private suspend fun onlyDerive(connectionId: UInt): SyncJobResponse = derives(connectionId).single()

    /** A worker whose DERIVE handler only counts and reports the live revision (so no stale-revision follow-up unless a test makes one). */
    private fun worker(): IngestWorker {
        val handlers = JobHandlerRegistry().apply {
            register(SyncJobKind.DERIVE) { derivesRun.incrementAndGet(); settings.currentRevision() }
            registerConfigRevisionSource { settings.currentRevision() }
        }
        val config = IngestConfig(
            schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7, workerId = workerId,
        )
        return IngestWorker(jobs, dataSources, handlers, emptyMap(), config) { clock.get() }
    }

    /** What the worker's claim loop would take for [connectionId] at [at] — fenced, because the claim scan is global. */
    private suspend fun claimAt(connectionId: UInt, at: Long): SyncJobClaim? =
        withOnlyConnections(setOf(connectionId), at) { jobs.claim(workerId, leaseSeconds = 300, now = at) }

    /** Claims and runs the connection's next due job at [at] (the clock the worker also reads); null when nothing was due. */
    private suspend fun runNextAt(worker: IngestWorker, connectionId: UInt, at: Long): SyncJobClaim? {
        clock.set(at)
        return claimAt(connectionId, at)?.also { worker.runJob(it) }
    }

    private suspend fun <T> withConnection(prefix: String, block: suspend (UInt) -> T): T {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = prefix, enabled = true)
        try {
            jobs.cancelOpenForConnection(connectionId)
            return block(connectionId)
        } finally {
            jobs.cancelOpenForConnection(connectionId)
            dataSources.delete(connectionId)
        }
    }

    @Test
    fun `three capacity saves within the quiet period make ONE DERIVE, run a quiet period after the last, and a save outside it its own`() =
        runBlocking {
            val teamId = TestTeams.seed(SyncedStubFixture.unique("debounce-cap"))
            val admin = TestUsers.seed(uniqueEmail("debounce-cap"), "pw")
            try {
                withConnection("debounce-cap") { conn ->
                    val membership = TeamMembershipService(sharedDatabaseForTests(), settings)
                    val calendars = SprintCalendarService(sharedDatabaseForTests(), settings, membership, TestTeams.service)
                    CapacityFixtures.addMember(teamId, CapacityFixtures.people(conn, 1).single())
                    CapacityFixtures.insertSprint(conn, teamId, 1L, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON)
                    jobs.cancelOpenForConnection(conn) // the member add above fanned out (debounced) already
                    val calendar = settings.readCalendar()

                    suspend fun save(coefficient: Double) {
                        val grid = calendars.read(teamId, 1L, calendar, canEdit = true)
                        val document = SprintCalendarDocument(
                            coefficient, grid.days.map { SprintCalendarDay(it.day, it.enabled) },
                            grid.people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
                        )
                        assertTrue(calendars.replace(teamId, 1L, document, admin, callerIsAdmin = true, calendar = calendar).changed)
                    }

                    val worker = worker()
                    clock.set(T0)
                    save(1.1)
                    clock.set(T0 + 10_000)
                    save(1.2)
                    clock.set(T0 + 20_000)
                    save(1.3)

                    val pending = onlyDerive(conn)
                    assertEquals(SyncJobStatus.PENDING, pending.status, "three saves, one job")
                    assertEquals(T0 + 20_000 + QUIET_MILLIS, pending.notBefore, "scheduled a quiet period after the LAST edit")

                    assertNull(runNextAt(worker, conn, T0 + 20_000 + QUIET_MILLIS - 1), "not due a millisecond early")
                    assertEquals(0, derivesRun.get())
                    assertNotNull(runNextAt(worker, conn, T0 + 20_000 + QUIET_MILLIS), "due at last edit + quiet period")
                    assertEquals(1, derivesRun.get(), "ONE derive for the whole burst")
                    assertEquals(SyncJobStatus.SUCCEEDED, onlyDerive(conn).status)

                    // A save well outside the period of the first burst gets a DERIVE of its own.
                    clock.set(T0 + 300_000)
                    save(1.4)
                    assertEquals(2, derives(conn).size)
                    assertNull(runNextAt(worker, conn, T0 + 300_000 + QUIET_MILLIS - 1))
                    assertNotNull(runNextAt(worker, conn, T0 + 300_000 + QUIET_MILLIS))
                    assertEquals(2, derivesRun.get())
                }
            } finally {
                TestTeams.service.delete(teamId)
            }
        }

    @Test
    fun `a global settings bump debounces every enabled connection, each into one job`() = runBlocking {
        withConnection("debounce-a") { a ->
            withConnection("debounce-b") { b ->
                clock.set(T0)
                settings.bumpRevision()
                clock.set(T0 + 5_000)
                settings.bumpRevision()
                for (conn in listOf(a, b)) {
                    assertEquals(T0 + 5_000 + QUIET_MILLIS, onlyDerive(conn).notBefore)
                }
                settings.bumpRevision(onlyConnectionId = a)
                assertEquals(1, derives(a).size)
                assertEquals(1, derives(b).size)
            }
        }
    }

    @Test
    fun `the claim skips a job that is not due yet and still takes a due one behind it`() = runBlocking {
        withConnection("debounce-skip") { conn ->
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn) // DERIVE, requested first, due at T0 + quiet
            clock.set(T0 + 1)
            jobs.enqueueScheduled(conn, SyncJobKind.RECONCILE, dataSources.read(conn)!!.configRevision, now = T0 + 1)

            val first = assertNotNull(claimAt(conn, T0 + 2), "the RECONCILE is due although the DERIVE (older, same priority) is not")
            assertEquals(SyncJobKind.RECONCILE, first.kind)
            assertTrue(jobs.finish(first.id, first.attempt, T0 + 3))
            assertNull(claimAt(conn, T0 + QUIET_MILLIS - 1), "the DERIVE is still not due")
            assertEquals(SyncJobKind.DERIVE, assertNotNull(claimAt(conn, T0 + QUIET_MILLIS)).kind)
        }
    }

    @Test
    fun `a SYNC-chained DERIVE is not delayed, and it pulls a pending debounced DERIVE forward`() = runBlocking {
        withConnection("debounce-chain") { conn ->
            val worker = worker()
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn)
            assertEquals(T0 + QUIET_MILLIS, onlyDerive(conn).notBefore)

            // A manual SYNC runs and succeeds: its chained DERIVE coalesces into the pending debounced one - which must run now.
            val revision = dataSources.read(conn)!!.configRevision
            jobs.requestJob(conn, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = revision)
            assertEquals(SyncJobKind.SYNC, assertNotNull(runNextAt(worker, conn, T0 + 1_000)).kind)
            assertNull(onlyDerive(conn).notBefore, "the chain pulled the debounced DERIVE back to now")
            assertNotNull(runNextAt(worker, conn, T0 + 2_000), "so it is claimable long before the quiet period ends")
            assertEquals(1, derivesRun.get())

            // With no debounced DERIVE queued, a chained one is inserted due at once even though the quiet period is configured.
            jobs.requestJob(conn, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = revision)
            runNextAt(worker, conn, T0 + 3_000)
            val chained = derives(conn).single { it.status == SyncJobStatus.PENDING }
            assertNull(chained.notBefore)
            assertNotNull(runNextAt(worker, conn, T0 + 3_001))
            assertEquals(2, derivesRun.get())
        }
    }

    @Test
    fun `a manual DERIVE request runs at once and a config bump never delays a DERIVE that is already due`() = runBlocking {
        withConnection("debounce-manual") { conn ->
            val revision = dataSources.read(conn)!!.configRevision
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn)
            jobs.requestJob(conn, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = revision)
            val job = onlyDerive(conn)
            assertNull(job.notBefore, "the manual request coalesced into the debounced job and pulled it to now")
            val claimed = assertNotNull(claimAt(conn, T0 + 1), "due at once")
            assertTrue(jobs.finish(claimed.id, claimed.attempt, T0 + 2))

            // The other way round: an already-due PENDING DERIVE (manual, notBefore null) stays due when a config edit follows.
            clock.set(T0 + 10_000)
            val manual = jobs.requestJob(conn, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = revision)
            settings.bumpRevision(onlyConnectionId = conn)
            val pending = derives(conn).single { it.id == manual.jobId }
            assertEquals(SyncJobStatus.PENDING, pending.status)
            assertNull(pending.notBefore, "an edit does not push out a DERIVE that is already due")
        }
    }

    @Test
    fun `an edit during a RUNNING DERIVE coalesces and the follow-up honours the quiet period`() = runBlocking {
        withConnection("debounce-running") { conn ->
            val worker = worker()
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn)
            val running = assertNotNull(claimAt(conn, T0 + QUIET_MILLIS))
            val revisionAtStart = settings.currentRevision()

            clock.set(T0 + QUIET_MILLIS + 1_000)
            settings.bumpRevision(onlyConnectionId = conn) // lands while the DERIVE is RUNNING
            assertEquals(1, derives(conn).size, "coalesced into the running job, no second row")
            assertEquals(SyncJobStatus.RUNNING, onlyDerive(conn).status)

            clock.set(T0 + QUIET_MILLIS + 2_000)
            worker.onSucceeded(running, deriveRevisionUsed = revisionAtStart)
            val followUp = derives(conn).single { it.status == SyncJobStatus.PENDING }
            assertEquals(T0 + QUIET_MILLIS + 2_000 + QUIET_MILLIS, followUp.notBefore, "the follow-up is debounced too")
        }
    }

    @Test
    fun `a data-source PUT inside the quiet window keeps the delayed DERIVE alive and it runs at not_before`() = runBlocking {
        withConnection("debounce-put") { conn ->
            val worker = worker()
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn)
            val before = dataSources.read(conn)!!
            // A rename alone bumps source_connections.config_revision - and used to make the claim cancel the delayed DERIVE.
            val updated = dataSources.update(
                conn,
                DataSourceRequest(
                    name = before.name + "-renamed", enabled = true, syncIntervalMinutes = before.syncIntervalMinutes,
                    backfillFrom = before.backfillFrom, reconcileHourUtc = before.reconcileHourUtc,
                    jira = JiraConnectionRequest(
                        siteUrl = before.jira.siteUrl, email = before.jira.email, projectKeys = before.jira.projectKeys,
                        authScheme = before.jira.authScheme,
                    ),
                ),
            )
            assertNotNull(updated)
            val revision = dataSources.read(conn)!!.configRevision
            assertEquals(before.configRevision + 1, revision)
            assertEquals(revision, onlyDerive(conn).configRevision, "the delayed DERIVE was restamped with the connection's new revision")

            assertNull(runNextAt(worker, conn, T0 + QUIET_MILLIS - 1))
            assertNotNull(runNextAt(worker, conn, T0 + QUIET_MILLIS), "and the claim at not_before runs it, not CONFIG_CHANGED")
            assertEquals(1, derivesRun.get())
            assertEquals(SyncJobStatus.SUCCEEDED, onlyDerive(conn).status)
        }
    }

    @Test
    fun `a coalesced enqueue raises the job's revision and never lowers it or pulls not_before back`() = runBlocking {
        withConnection("debounce-greatest") { conn ->
            clock.set(T0 + 10_000)
            jobs.enqueueDebouncedDerive(conn, configRevision = 50)
            assertEquals(T0 + 10_000 + QUIET_MILLIS, onlyDerive(conn).notBefore)

            jobs.enqueueDebouncedDerive(conn, configRevision = 60, now = T0 + 20_000)
            onlyDerive(conn).let {
                assertEquals(60, it.configRevision, "restamped on coalesce")
                assertEquals(T0 + 20_000 + QUIET_MILLIS, it.notBefore)
            }

            // An EARLIER enqueue (a lagging bump committing late) neither pulls the time back nor lowers the revision.
            jobs.enqueueDebouncedDerive(conn, configRevision = 55, now = T0 + 5_000)
            onlyDerive(conn).let {
                assertEquals(60, it.configRevision)
                assertEquals(T0 + 20_000 + QUIET_MILLIS, it.notBefore)
            }
        }
    }

    @Test
    fun `the open job is the due one, not a delayed DERIVE that was requested earlier`() = runBlocking {
        withConnection("debounce-open") { conn ->
            clock.set(T0)
            settings.bumpRevision(onlyConnectionId = conn) // delayed DERIVE, requested first
            clock.set(T0 + 1)
            jobs.enqueueScheduled(conn, SyncJobKind.RECONCILE, dataSources.read(conn)!!.configRevision, now = T0 + 1)

            clock.set(T0 + 2)
            assertEquals(SyncJobKind.RECONCILE, assertNotNull(jobs.openJob(conn)).kind, "what a claim would take")
            clock.set(T0 + QUIET_MILLIS)
            assertEquals(SyncJobKind.DERIVE, assertNotNull(jobs.openJob(conn)).kind, "once the DERIVE is due, requested-first wins again")
        }
    }

    @Test
    fun `a zero quiet period leaves every DERIVE due at once`() = runBlocking {
        val undebounced = SyncJobsService(sharedDatabaseForTests(), 3, { clock.get() })
        val plainSettings = MetricsSettingsService(sharedDatabaseForTests(), dataSources, undebounced)
        withConnection("debounce-off") { conn ->
            plainSettings.bumpRevision(onlyConnectionId = conn)
            assertNull(onlyDerive(conn).notBefore)
        }
    }

    @Test
    fun `the app's quiet period comes from ingest-deriveQuietSeconds and a notBefore reaches the sync-jobs and status endpoints`() =
        testApplication {
            configureApp("ingest.deriveQuietSeconds" to "60")
            startApplication()
            val admin = seededClient("debounce-http", FlowUserRole.ADMIN)
            val created = admin.postJson(
                "/api/v1/data-sources",
                DataSourceRequest(
                    name = SyncedStubFixture.unique("debounce-http"), enabled = true, syncIntervalMinutes = 60, backfillFrom = null,
                    reconcileHourUtc = 3,
                    jira = JiraConnectionRequest(
                        siteUrl = TEST_JIRA_SITE_URL,
                        email = "svc-${UUID.randomUUID()}@example.com", apiToken = "token-${UUID.randomUUID()}",
                        projectKeys = uniqueProjectKeys(1), authScheme = JiraAuthScheme.BASIC,
                    ),
                ),
            ).body<DataSourceResponse>()
            val id = created.id
            try {
                val before = System.currentTimeMillis()
                val put = admin.putJson("/api/v1/data-sources/$id/metrics-config", DataSourceMetricsConfigRequest())
                assertEquals(HttpStatusCode.NoContent, put.status)
                val after = System.currentTimeMillis()

                val list = admin.get("/api/v1/data-sources/$id/sync-jobs?kind=DERIVE").body<SyncJobPageResponse>()
                val job = list.items.single()
                assertEquals(SyncJobStatus.PENDING, job.status)
                assertTrue(job.notBefore in (before + 60_000)..(after + 60_000), "scheduled 60 s after the PUT, got ${job.notBefore}")

                val status = admin.get("/api/v1/data-sources/$id/status").body<SyncStatusResponse>()
                assertEquals(job.id, status.currentJob?.id, "a waiting DERIVE is the open job")
                assertEquals(job.notBefore, status.currentJob?.notBefore)
            } finally {
                SyncJobsService(sharedDatabaseForTests(), 3).cancelOpenForConnection(id)
                admin.delete("/api/v1/data-sources/$id") // soft delete: the ENABLED fake connection must not outlive the test
            }
        }

    @Test
    fun `an out-of-range or non-numeric ingest-deriveQuietSeconds refuses to start`() {
        for (bad in listOf("-1", "soon", "3601")) {
            testApplication {
                configureApp("ingest.deriveQuietSeconds" to bad)
                assertStartupFails("ingest.deriveQuietSeconds") { startApplication() }
            }
        }
    }

    private companion object {
        const val T0 = 10_000_000L
        const val QUIET_MILLIS = 60_000L
    }
}
