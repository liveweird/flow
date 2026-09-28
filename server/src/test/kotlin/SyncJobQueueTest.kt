package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.defaultBackfillFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests construct [ch.nokillswit.ingest.SyncJobsService]/[DataSourceService] directly
 * against [sharedDatabaseForTests] WITHOUT ever booting a `testApplication` (no HTTP layer is
 * exercised) — so, unlike every other suspend-only DB test (`JsonbColumnTest`'s own scratch
 * table aside), nothing else in this JVM fork is guaranteed to have run Flyway against the shared
 * Testcontainer first (Gradle's `test` task may fork workers, each starting its OWN container
 * lazily; `--tests`-filtered runs can pick a fork where no other class's `startApplication()` runs
 * before this one). [ensureMigrated] replicates `infra/db/Flyway.kt`'s `configureFlyway()` call
 * directly (idempotent — Flyway no-ops once applied) rather than booting a whole app, which would
 * also start a live ingest worker in the default `all` role and race these tests' own claims.
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
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): claim/lease/heartbeat, coalescing,
 * the one-RUNNING-per-connection rule, the claim-time terminal checks (`RETRIES_EXHAUSTED`,
 * `CONFIG_CHANGED`) and retention pruning — direct-against-`SyncJobsService` tests (no HTTP layer;
 * `SyncJobRoutesTest` covers the API).
 */
class SyncJobQueueTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))

    private fun syncJobs(maxAttempts: Int = 3, clock: () -> Long = System::currentTimeMillis) =
        SyncJobsService(sharedDatabaseForTests(), maxAttempts, clock)

    private suspend fun createConnection(dataSources: DataSourceService, syncIntervalMinutes: Int = 60): UInt {
        val request = DataSourceRequest(
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
        )
        return dataSources.create(request)
    }

    /**
     * `SyncJobsService.claim()`'s scan is intentionally GLOBAL (any worker claims any due job
     * across the whole database) — but this class's tests share ONE Testcontainer database across
     * many methods (and, within a Gradle fork, possibly other test classes too), so a test
     * asserting a property of ONE specific job must not assume it is the only claimable row.
     * Drains and finishes every OTHER currently-claimable job (so the shared database does not
     * keep accumulating a PENDING backlog across the suite) until [targetJobId] itself is
     * returned, or nothing else is claimable at all — proof that [targetJobId] is not (yet)
     * claimable, for the tests asserting exactly that.
     */
    private suspend fun SyncJobsService.claimSpecific(
        workerId: String,
        leaseSeconds: Long,
        now: Long,
        targetJobId: UInt,
    ): ch.nokillswit.ingest.SyncJobClaim? {
        repeat(500) {
            val claim = claim(workerId, leaseSeconds, now) ?: return null
            if (claim.id == targetJobId) return claim
            finish(claim.id, now)
        }
        error("claimSpecific: did not reach job $targetJobId within 500 attempts")
    }

    /**
     * For claim-time TERMINAL checks (`RETRIES_EXHAUSTED`/`CONFIG_CHANGED`): `claim()` never
     * RETURNS such a job (it is terminal-ized internally via `continue` and the scan moves on),
     * so — same shared-database caveat as [claimSpecific] — drains other claimable jobs (finishing
     * them) until [targetJobId]'s own status stops being `PENDING`.
     */
    private suspend fun SyncJobsService.drainUntilTerminal(
        connId: UInt,
        targetJobId: UInt,
        workerId: String,
        leaseSeconds: Long,
        now: Long,
    ): SyncJobStatus {
        repeat(500) {
            val claim = claim(workerId, leaseSeconds, now)
            if (claim != null && !(claim.connectionId == connId && claim.id == targetJobId)) {
                finish(claim.id, now)
            }
            val status = list(connId, SyncJobListFilter(), pagingAll()).items.single { it.id == targetJobId }.status
            if (status != SyncJobStatus.PENDING || claim == null) return status
        }
        error("drainUntilTerminal: job $targetJobId never left PENDING within 500 attempts")
    }

    @Test
    fun `two concurrent claimers race for one job - exactly one wins`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id

        val results = (1..8).map { n ->
            async(Dispatchers.IO) { jobs.claimSpecific("worker-$n", leaseSeconds = 60, now = 2000L, targetJobId = jobId) }
        }.awaitAll()

        val winners = results.filterNotNull()
        assertEquals(1, winners.size, "exactly one claimer must win the single PENDING job")
        assertEquals(connId, winners.single().connectionId)
    }

    @Test
    fun `an expired lease is re-claimed by another worker`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val first = jobs.claimSpecific("worker-a", leaseSeconds = 30, now = 1000L, targetJobId = jobId)
        assertNotNull(first)

        // Before the lease expires, nobody else can claim THIS job (something else, maybe).
        val tooEarly = jobs.claimSpecific("worker-b", leaseSeconds = 30, now = 1000L + 5_000L, targetJobId = jobId)
        assertNull(tooEarly, "the job's own lease must not be reclaimable before it expires")

        // Past lease_until, a second worker reclaims the SAME job.
        val reclaimed = jobs.claimSpecific("worker-b", leaseSeconds = 30, now = 1000L + 31_000L, targetJobId = jobId)
        assertNotNull(reclaimed)
        assertEquals(first.id, reclaimed.id)
        assertEquals(2, reclaimed.attempt, "reclaiming increments the attempt counter")
    }

    @Test
    fun `heartbeat after the lease is lost returns false`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = jobs.claimSpecific("worker-a", leaseSeconds = 10, now = 1000L, targetJobId = jobId)
        assertNotNull(claim)

        assertTrue(jobs.heartbeat(claim.id, "worker-a", leaseSeconds = 10, now = 1005L), "a live lease heartbeats fine")

        // worker-b reclaims THIS job specifically after expiry.
        assertNotNull(jobs.claimSpecific("worker-b", leaseSeconds = 10, now = 1000L + 11_000L, targetJobId = jobId))

        // worker-a's heartbeat now fails: it is no longer the lease owner.
        assertEquals(false, jobs.heartbeat(claim.id, "worker-a", leaseSeconds = 10, now = 1000L + 12_000L))
    }

    @Test
    fun `release on cancellation puts a RUNNING job back to PENDING`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId)
        assertNotNull(claim)

        jobs.release(claim.id)

        val reclaimed = jobs.claimSpecific("worker-b", leaseSeconds = 60, now = 1001L, targetJobId = jobId)
        assertNotNull(reclaimed, "a released job is immediately PENDING again, reclaimable without waiting for lease expiry")
        assertEquals(claim.id, reclaimed.id)
    }

    @Test
    fun `a second SYNC request while one is open coalesces onto the same job`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val first = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        assertEquals(false, first.coalesced)

        val second = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L)
        assertEquals(true, second.coalesced)
        assertEquals(first.jobId, second.jobId)

        // A different KIND is NOT coalesced — its own open slot.
        val reconcile = jobs.requestJob(connId, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L)
        assertEquals(false, reconcile.coalesced)
    }

    @Test
    fun `at most one RUNNING job per connection, even across different kinds`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        jobs.enqueueScheduled(connId, SyncJobKind.RECONCILE, configRevision = 1L, now = 1000L)
        val jobsForConn = jobs.list(connId, SyncJobListFilter(), pagingAll()).items
        val syncJobId = jobsForConn.single { it.kind == SyncJobKind.SYNC }.id
        val reconcileJobId = jobsForConn.single { it.kind == SyncJobKind.RECONCILE }.id

        val firstClaim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = syncJobId)
        assertNotNull(firstClaim)
        // The RECONCILE job (different kind, same connection) must NOT be claimable while the SYNC job is RUNNING.
        val secondClaim = jobs.claimSpecific("worker-b", leaseSeconds = 60, now = 1000L, targetJobId = reconcileJobId)
        assertNull(secondClaim, "only one RUNNING job per connection, regardless of kind")
    }

    @Test
    fun `a config_revision mismatch cancels the job as CONFIG_CHANGED`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        // Enqueued against config_revision=1, but the connection has since moved to revision=2
        // (an update bumps it) — the claim finds the mismatch and cancels rather than running.
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        ds.update(
            connId,
            DataSourceRequest(
                name = ds.read(connId)!!.name,
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = ds.read(connId)!!.jira.siteUrl,
                    email = "changed-${unique("acct")}@example.com",
                    apiToken = null,
                    projectKeys = listOf("ENG"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
        assertEquals(2L, ds.read(connId)!!.configRevision)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id

        val status = jobs.drainUntilTerminal(connId, jobId, "worker-a", leaseSeconds = 60, now = 1000L)
        assertEquals(SyncJobStatus.CANCELLED, status, "a stale config_revision must not be run")

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single { it.id == jobId }
        assertEquals(SyncJobStatus.CANCELLED, job.status)
        assertEquals("CONFIG_CHANGED", job.errorCode)
    }

    @Test
    fun `a job already at max_attempts is failed RETRIES_EXHAUSTED at claim time`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs(maxAttempts = 2)
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id

        // Claim + release twice to exhaust attempts (attempt increments on every claim; release keeps it).
        val firstClaim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId)
        assertNotNull(firstClaim)
        assertEquals(1, firstClaim.attempt)
        jobs.release(firstClaim.id)
        val secondClaim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1001L, targetJobId = jobId)
        assertNotNull(secondClaim)
        assertEquals(2, secondClaim.attempt)
        jobs.release(secondClaim.id)

        // A third claim attempt finds attempt(2) >= maxAttempts(2) and fails it instead.
        val status = jobs.drainUntilTerminal(connId, jobId, "worker-a", leaseSeconds = 60, now = 1002L)
        assertEquals(SyncJobStatus.FAILED, status)

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single { it.id == jobId }
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals("RETRIES_EXHAUSTED", job.errorCode)
    }

    @Test
    fun `prune hard-deletes terminal rows past retention but keeps recent and open ones`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val old = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        jobs.claim("worker-a", leaseSeconds = 60, now = 1000L)
        jobs.finish(old, now = 1000L)

        val recentConn = createConnection(ds)
        val recent = jobs.requestJob(recentConn, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        jobs.claim("worker-b", leaseSeconds = 60, now = 500_000_000L)
        jobs.finish(recent, now = 500_000_000L)

        val openConn = createConnection(ds)
        val open = jobs.requestJob(openConn, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L).jobId

        val retentionMillis = 1000L
        val deleted = jobs.prune(retentionMillis, now = 500_000_000L + 500L)
        assertTrue(deleted >= 1)
        assertNull(jobs.read(connId, old), "an old finished job past retention is hard-deleted")
        assertNotNull(jobs.read(recentConn, recent), "a recently finished job is kept")
        assertNotNull(jobs.read(openConn, open), "an open (PENDING) job is never pruned regardless of age")
        Unit
    }

    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())
}
