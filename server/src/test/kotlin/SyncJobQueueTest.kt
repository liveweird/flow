package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.db.connectPooledDatabase
import io.r2dbc.spi.ConnectionFactoryOptions
import java.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.HeartbeatOutcome
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.defaultBackfillFrom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): claim/lease/heartbeat, coalescing,
 * the one-RUNNING-per-connection rule, the claim-time terminal checks (`RETRIES_EXHAUSTED`,
 * `CONFIG_CHANGED`) and retention pruning — direct-against-`SyncJobsService` tests (no HTTP layer;
 * `SyncJobRoutesTest` covers the API). No `testApplication` is booted (that would also start a live
 * ingest worker in the default `all` role and race these tests' own claims); the shared container is
 * already migrated by [PostgresTestSupport] the moment [sharedDatabaseForTests] first touches it.
 */
/** The clock the racing-claim tests claim (and fence) with; far below every real lease, so only their own rows count. */
private const val CLAIM_CLOCK_MILLIS = 5_000L

/** Bound on every wait in the racing-claim tests — never an expected duration. */
private const val CLAIM_RACE_TIMEOUT_MS = 20_000L

class SyncJobQueueTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))

    private fun syncJobs(maxAttempts: Int = 3, clock: () -> Long = System::currentTimeMillis) =
        SyncJobsService(sharedDatabaseForTests(), maxAttempts, clock)

    /** Every connection this test created: [cleanUpOpenJobs] closes whatever its jobs left RUNNING or PENDING in the shared queue. */
    private val createdConnections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUpOpenJobs() = runBlocking {
        val jobs = syncJobs()
        for (connId in createdConnections) {
            for (job in jobs.list(connId, SyncJobListFilter(), pagingAll()).items) {
                when (job.status) {
                    SyncJobStatus.RUNNING -> jobs.finish(job.id, job.attempt)
                    SyncJobStatus.PENDING -> jobs.requestCancel(connId, job.id)
                    else -> Unit
                }
            }
        }
    }

    /** Claims [connId]'s one job behind the queue fence (the scan is global) and finishes it — asserting both really happened. */
    private suspend fun SyncJobsService.claimAndFinish(connId: UInt, jobId: UInt, workerId: String, now: Long) {
        val claim = assertNotNull(withOnlyConnections(setOf(connId), now) { claim(workerId, leaseSeconds = 60, now = now) })
        assertEquals(jobId, claim.id)
        assertTrue(finish(claim.id, claim.attempt, now), "the claimed job was RUNNING, so its finish must land")
    }

    private suspend fun createConnection(dataSources: DataSourceService, syncIntervalMinutes: Int = 60, enabled: Boolean = true): UInt {
        val request = DataSourceRequest(
            name = unique("conn"),
            enabled = enabled,
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
        return dataSources.create(request).also { createdConnections += it }
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
            finish(claim.id, claim.attempt, now)
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
                finish(claim.id, claim.attempt, now)
            }
            val status = list(connId, SyncJobListFilter(), pagingAll()).items.single { it.id == targetJobId }.status
            if (status != SyncJobStatus.PENDING || claim == null) return status
        }
        error("drainUntilTerminal: job $targetJobId never left PENDING within 500 attempts")
    }

    @Test
    fun `two concurrent claimers race for one job - exactly one wins`() = runBlocking {
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
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = jobs.claimSpecific("worker-a", leaseSeconds = 10, now = 1000L, targetJobId = jobId)
        assertNotNull(claim)

        assertTrue(jobs.heartbeat(claim.id, "worker-a", claim.attempt, leaseSeconds = 10, now = 1005L), "a live lease heartbeats fine")

        // worker-b reclaims THIS job specifically after expiry.
        assertNotNull(jobs.claimSpecific("worker-b", leaseSeconds = 10, now = 1000L + 11_000L, targetJobId = jobId))

        // worker-a's heartbeat now fails: it is no longer the lease owner.
        assertEquals(false, jobs.heartbeat(claim.id, "worker-a", claim.attempt, leaseSeconds = 10, now = 1000L + 12_000L))
    }

    @Test
    fun `a stale run's terminal and release writes after a reclaim leave the new run untouched`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val stale = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 10, now = 1000L, targetJobId = jobId))
        // The SAME worker id reclaims after expiry (a stale run of the same process): owner matching alone cannot tell the runs apart.
        val current = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L + 11_000L, targetJobId = jobId))
        assertEquals(2, current.attempt)

        assertEquals(false, jobs.heartbeat(stale.id, "worker-a", stale.attempt, leaseSeconds = 60, now = 1000L + 12_000L))
        assertEquals(false, jobs.finish(stale.id, stale.attempt, now = 1000L + 12_000L))
        assertEquals(false, jobs.fail(stale.id, stale.attempt, "RUN_FAILED", "stale", now = 1000L + 12_000L))
        assertEquals(false, jobs.markCancelled(stale.id, stale.attempt, now = 1000L + 12_000L))
        assertEquals(false, jobs.release(stale.id, "worker-a", stale.attempt))

        val row = assertNotNull(jobs.read(connId, jobId))
        assertEquals(SyncJobStatus.RUNNING, row.status, "the new run's row is untouched")
        assertEquals(2, row.attempt)
        assertNull(row.errorCode)
        assertTrue(jobs.finish(current.id, current.attempt, now = 1000L + 13_000L), "the current attempt still finishes its own row")
    }

    @Test
    fun `a shutdown release after a committed finish leaves the job SUCCEEDED`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId))

        assertTrue(jobs.finish(claim.id, claim.attempt, now = 1500L))
        // ApplicationStopping lands between finish and the end of onSucceeded: runJob's cancellation handler releases under NonCancellable.
        assertEquals(false, jobs.release(claim.id, "worker-a", claim.attempt), "a finished job is never reopened")

        assertEquals(SyncJobStatus.SUCCEEDED, assertNotNull(jobs.read(connId, jobId)).status)
    }

    @Test
    fun `a stale finish on a row the claimer closed itself is a no-op`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs(maxAttempts = 1)
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val stale = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 10, now = 1000L, targetJobId = jobId))
        // The lease expires; a claimer finds the RUNNING row at max_attempts and closes it inline — WITHOUT bumping the attempt.
        val reclaimClock = 1000L + 11_000L
        assertNull(withOnlyConnections(setOf(connId), reclaimClock) { jobs.claim("worker-b", leaseSeconds = 10, now = reclaimClock) })
        assertEquals(SyncJobStatus.FAILED, assertNotNull(jobs.read(connId, jobId)).status)

        assertEquals(false, jobs.finish(stale.id, stale.attempt, now = 1000L + 12_000L), "a stale run cannot finish a claimer-failed row")
        assertEquals(false, jobs.release(stale.id, "worker-a", stale.attempt), "nor release it to PENDING")
        assertEquals(false, jobs.fail(stale.id, stale.attempt, "RUN_FAILED", now = 1000L + 12_000L))
        val row = assertNotNull(jobs.read(connId, jobId))
        assertEquals(SyncJobStatus.FAILED, row.status)
        assertEquals("RETRIES_EXHAUSTED", row.errorCode)
    }

    /**
     * A renewal cancelled mid-transaction (the worker cancels a renewal that outlived its slack), with the job row's lock held.
     * exposed-r2dbc 1.5.0 has no `NonCancellable`, so this pins what actually happens: the transaction rolls back and its
     * pooled connection comes back — otherwise the following heartbeat would block on the row lock and the pool would leak
     * (a dedicated 2-connection pool makes a leak visible as an acquire timeout). Measured: cancelled BETWEEN statements the
     * coroutine returns at once (~0.4 s here, the timeout); cancelled DURING a statement the cancellation only lands when that
     * statement ends (3 s for the `pg_sleep(3)`), which is why `IngestWorker` awaits a renewal from a detached scope instead
     * of relying on the cancellation to bound it (`IngestWorkerTest`'s "stuck in a statement" case).
     */
    @Test
    fun `a transaction cancelled mid-flight releases its row lock and its pooled connection`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId))

        val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()
        val (smallDb, pool) = connectPooledDatabase(
            options,
            maxSize = 2,
            initialSize = 1,
            maxAcquireTime = Duration.ofSeconds(3),
            maxIdleTime = Duration.ofMinutes(1),
        )
        try {
            val hangs = listOf<suspend R2dbcTransaction.() -> Unit>(
                { awaitCancellation() }, // cancelled BETWEEN statements, idle in transaction
                { exec("SELECT pg_sleep(3)") }, // cancelled DURING a statement
            )
            for (hang in hangs) {
                val result = withTimeoutOrNull(400) {
                    suspendTransaction(smallDb) {
                        SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[heartbeatAt] = 1L } // takes the row lock
                        hang()
                    }
                }
                assertNull(result, "the transaction was cancelled by the timeout")
                // A leaked transaction would still hold the row lock: this write (another pool) would block until the 5 s guard.
                assertTrue(withTimeout(5_000) { jobs.heartbeat(claim.id, "worker-a", claim.attempt, leaseSeconds = 60, now = 2000L) })
            }
            // Both cancelled transactions returned their connection: the 2-connection pool still serves two at once.
            withTimeout(10_000) {
                val both = (1..2).map {
                    async(Dispatchers.IO) { suspendTransaction(smallDb) { SyncJobsService.Jobs.selectAll().limit(1).toList() } }
                }
                both.awaitAll()
            }
            Unit
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `a bounded lease renewal ends server-side when the job row is locked`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = assertNotNull(jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId))

        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq jobId }) { it[heartbeatAt] = 1L } // holds the row lock
                locked.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(CLAIM_RACE_TIMEOUT_MS) { locked.await() }
            val started = System.nanoTime()
            // 1 s bound: lock_timeout 1000 ms + queryTimeout 1 s — the renewal fails by itself instead of waiting for the holder.
            assertFails {
                withTimeout(CLAIM_RACE_TIMEOUT_MS) {
                    jobs.renewLease(claim.id, "worker-a", claim.attempt, 60, now = 2000L, boundMillis = 1000L)
                }
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMillis < 8_000, "the renewal ended server-side long before the holder released, took $elapsedMillis ms")
        } finally {
            release.complete(Unit)
            holder.await()
        }
        // The holder rolled back nothing of ours: with the lock gone the same renewal succeeds.
        assertEquals(HeartbeatOutcome.RENEWED, jobs.renewLease(claim.id, "worker-a", claim.attempt, 60, now = 3000L, boundMillis = 1000L))
    }

    @Test
    fun `release on cancellation puts a RUNNING job back to PENDING`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id
        val claim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId)
        assertNotNull(claim)

        jobs.release(claim.id, "worker-a", claim.attempt)

        val reclaimed = jobs.claimSpecific("worker-b", leaseSeconds = 60, now = 1001L, targetJobId = jobId)
        assertNotNull(reclaimed, "a released job is immediately PENDING again, reclaimable without waiting for lease expiry")
        assertEquals(claim.id, reclaimed.id)
    }

    @Test
    fun `a second SYNC request while one is open coalesces onto the same job`() = runBlocking {
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

    /**
     * Two claimers = two worker processes (or slots), modelled as two [SyncJobsService] instances over the one database.
     * The first is held INSIDE its claim transaction, right after it took the connection's claim lock (the
     * `afterClaimLock` seam), while the second claims: without the lock the second would read "nothing RUNNING" (the
     * first has not updated its row yet) and claim the other kind too. Fenced with [withOnlyConnections] so the global
     * claim scan can only see this test's own connections' jobs.
     */
    private suspend fun <T> racingClaimers(
        connectionIds: Set<UInt>,
        heldConnection: UInt,
        block: suspend (
            first: SyncJobsService,
            second: SyncJobsService,
            firstLocked: CompletableDeferred<Unit>,
            releaseFirst: CompletableDeferred<Unit>,
        ) -> T,
    ): T {
        val firstLocked = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val database = sharedDatabaseForTests()
        val first = SyncJobsService(database, 3, System::currentTimeMillis, afterClaimLock = { connectionId ->
            if (connectionId == heldConnection) {
                firstLocked.complete(Unit)
                releaseFirst.await()
            }
        })
        val second = SyncJobsService(database, 3)
        return withOnlyConnections(connectionIds, CLAIM_CLOCK_MILLIS) {
            try {
                block(first, second, firstLocked, releaseFirst)
            } finally {
                releaseFirst.complete(Unit) // never leave the held claim hanging when an assertion fails
            }
        }
    }

    @Test
    fun `two concurrent claimers on different kinds of one connection - at most one goes RUNNING`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds, enabled = false)
        val setup = syncJobs()
        setup.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)

        racingClaimers(setOf(connId), heldConnection = connId) { first, second, firstLocked, releaseFirst ->
            val firstClaim = async(Dispatchers.IO) { first.claim("worker-a", leaseSeconds = 60, now = CLAIM_CLOCK_MILLIS) }
            withTimeout(CLAIM_RACE_TIMEOUT_MS) { firstLocked.await() }
            // The first claimer's scan locked only the rows that existed then (the SYNC): this DERIVE is a row it never saw
            // and does not hold — exactly what a second claimer interleaved with it would pick.
            setup.enqueueScheduled(connId, SyncJobKind.DERIVE, configRevision = 1L, now = 1001L)

            // The first claimer sits inside its transaction holding the lock: the DERIVE must NOT be claimed under it.
            assertNull(
                // withTimeout: a regression to a BLOCKING lock would hang here — it must fail the test instead of the fork
                withTimeout(CLAIM_RACE_TIMEOUT_MS) { second.claim("worker-b", leaseSeconds = 60, now = CLAIM_CLOCK_MILLIS) },
                "the DERIVE is skipped: the connection's claim lock is held by the first claimer, whose SYNC is not RUNNING yet",
            )
            releaseFirst.complete(Unit)

            val won = assertNotNull(withTimeout(CLAIM_RACE_TIMEOUT_MS) { firstClaim.await() })
            assertEquals(SyncJobKind.SYNC, won.kind)
            val running = setup.list(connId, SyncJobListFilter(status = SyncJobStatus.RUNNING), pagingAll()).items
            assertEquals(listOf(won.id), running.map { it.id }, "exactly one RUNNING job for the connection")
            // Lock released at commit: the loser still gets nothing, now because the connection has a RUNNING job.
            assertNull(withTimeout(CLAIM_RACE_TIMEOUT_MS) { second.claim("worker-b", leaseSeconds = 60, now = CLAIM_CLOCK_MILLIS) })
        }
    }

    @Test
    fun `a claim lock held on one connection never blocks a claim on another`() = runBlocking {
        val ds = dataSources()
        val heldConn = createConnection(ds, enabled = false)
        val otherConn = createConnection(ds, enabled = false)
        val setup = syncJobs()
        setup.enqueueScheduled(heldConn, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)

        racingClaimers(setOf(heldConn, otherConn), heldConnection = heldConn) { first, second, firstLocked, releaseFirst ->
            val firstClaim = async(Dispatchers.IO) { first.claim("worker-a", leaseSeconds = 60, now = CLAIM_CLOCK_MILLIS) }
            withTimeout(CLAIM_RACE_TIMEOUT_MS) { firstLocked.await() }
            // Enqueued after the first claimer's scan, so its SKIP LOCKED row locks do not cover it (see the test above).
            setup.enqueueScheduled(otherConn, SyncJobKind.SYNC, configRevision = 1L, now = 1001L)

            val secondClaim = assertNotNull(
                withTimeout(CLAIM_RACE_TIMEOUT_MS) { second.claim("worker-b", leaseSeconds = 60, now = CLAIM_CLOCK_MILLIS) },
                "another connection's job is claimed while the first connection's claim is in flight",
            )
            assertEquals(otherConn, secondClaim.connectionId)
            releaseFirst.complete(Unit)

            assertEquals(heldConn, assertNotNull(withTimeout(CLAIM_RACE_TIMEOUT_MS) { firstClaim.await() }).connectionId)
        }
    }

    @Test
    fun `a config_revision mismatch cancels the job as CONFIG_CHANGED`() = runBlocking {
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
        val ds = dataSources()
        val jobs = syncJobs(maxAttempts = 2)
        val connId = createConnection(ds)
        jobs.enqueueScheduled(connId, SyncJobKind.SYNC, configRevision = 1L, now = 1000L)
        val jobId = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single().id

        // Claim + release twice to exhaust attempts (attempt increments on every claim; release keeps it).
        val firstClaim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1000L, targetJobId = jobId)
        assertNotNull(firstClaim)
        assertEquals(1, firstClaim.attempt)
        jobs.release(firstClaim.id, "worker-a", firstClaim.attempt)
        val secondClaim = jobs.claimSpecific("worker-a", leaseSeconds = 60, now = 1001L, targetJobId = jobId)
        assertNotNull(secondClaim)
        assertEquals(2, secondClaim.attempt)
        jobs.release(secondClaim.id, "worker-a", secondClaim.attempt)

        // A third claim attempt finds attempt(2) >= maxAttempts(2) and fails it instead.
        val status = jobs.drainUntilTerminal(connId, jobId, "worker-a", leaseSeconds = 60, now = 1002L)
        assertEquals(SyncJobStatus.FAILED, status)

        val job = jobs.list(connId, SyncJobListFilter(), pagingAll()).items.single { it.id == jobId }
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals("RETRIES_EXHAUSTED", job.errorCode)
    }

    @Test
    fun `prune hard-deletes terminal rows past retention but keeps recent and open ones`() = runBlocking {
        val ds = dataSources()
        val jobs = syncJobs()
        val connId = createConnection(ds)
        val old = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        jobs.claimAndFinish(connId, old, "worker-a", now = 1000L)

        val recentConn = createConnection(ds)
        val recent = jobs.requestJob(recentConn, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
        jobs.claimAndFinish(recentConn, recent, "worker-b", now = 500_000_000L)

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
