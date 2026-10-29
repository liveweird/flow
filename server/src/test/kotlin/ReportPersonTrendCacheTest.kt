package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.PeerCellCache
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.ReportServiceKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.AfterClass
import org.junit.BeforeClass

private const val FROM = "2025-03-06"
private const val TO = "2026-03-05"
private const val WINDOWS = 27
private const val ONE_DAY_MS = 86_400_000L

/**
 * Person trend's peer-cell cache (`reports/PersonTrendCache.kt`, WHY 49) over a derived clone with a six-person team: every body the
 * cache-ON app answers -- the first request, a repeat, a TEAMMATE's request served from the person's cells, a slice, a long span -- is
 * compared as a RAW STRING with the cache-OFF oracle app on the same database (`reports.peerCellCache.maxEntries = 0`, the code path of
 * before the cache); every input of the generation (the data stamp) is moved in turn and must swap the generation and still equal the
 * oracle; the entry cap, the off switch, eight racing cold requests and the knob's boot validation. Compare people and the drill never
 * touch it.
 */
class ReportPersonTrendCacheTest {

    /** The six teammates (and one outsider) of the class's world; the clone is derived once, the memberships are open-ended. */
    private class World(
        val connId: UInt,
        val teamId: UInt,
        val person: String,
        val peers: List<String>,
        val outsider: String,
        val memberships: TeamMembershipService,
        val created: MutableList<UInt>,
    )

    /** The cache-ON app's client and service beside the cache-OFF oracle's, both answering for the same signed-in user. */
    private class Apps(val on: HttpClient, val off: HttpClient, val onService: ReportService, val offService: ReportService) {
        val cache: PeerCellCache get() = onService.peerCells
    }

    private class Counts(val served: Long, val fetched: Long, val writes: Long, val refused: Long, val swaps: Long)

    private fun PeerCellCache.counts() = Counts(
        stats.servedPeers.get(), stats.fetchedPeers.get(), stats.writes.get(), stats.refused.get(), stats.swaps.get(),
    )

    private val world get() = built

    private fun query(account: String, extra: String = "", from: String = FROM) =
        "connectionId=${world.connId}&from=$from&to=$TO&accountId=$account$extra"

    private fun withApps(onMaxEntries: String? = null, block: suspend Apps.() -> Unit) = testApplication {
        if (onMaxEntries == null) {
            usePostgresTestcontainer()
        } else {
            configureApp("reports.peerCellCache.maxEntries" to onMaxEntries)
            startApplication()
        }
        val oracle = TestApplication { configureApp("reports.peerCellCache.maxEntries" to "0") }
        oracle.start()
        try {
            val email = uniqueEmail("trend-cache")
            TestUsers.seed(email, "pw", role = FlowUserRole.USER)
            val token = oracle.createClient { flowTestClientDefaults() }.login(email, "pw").body<LoginResponse>().token
            val off = oracle.createClient {
                flowTestClientDefaults()
                install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
            }
            val pinned = System.currentTimeMillis()
            val onService = application.attributes[ReportServiceKey].also { it.clock = { pinned } }
            val offService = oracle.application.attributes[ReportServiceKey].also { it.clock = { pinned } }
            Apps(authedClient(email, "pw"), off, onService, offService).block()
        } finally {
            oracle.stop()
        }
    }

    /** Both apps' GET of [query], required to be the very same string; returns it. */
    private suspend fun Apps.sameBody(query: String, label: String = query): String {
        val oracle = off.personTrendBody(query)
        val cached = on.personTrendBody(query)
        assertEquals(oracle, cached, "cache ON == cache OFF, byte for byte: $label")
        return cached
    }

    private fun decode(body: String): PersonTrendReport = Json { ignoreUnknownKeys = true }.decodeFromString(body)

    /** A request that fills the cache, then one that proves the cache is warm (every teammate served) -- so a later swap is not vacuous. */
    private suspend fun Apps.warm(query: String, teammates: Int = world.peers.size): String {
        on.personTrendBody(query)
        val before = cache.counts()
        val body = on.personTrendBody(query)
        val after = cache.counts()
        assertEquals(teammates.toLong(), after.served - before.served, "warm: every teammate is served from the cache")
        assertEquals(0L, after.fetched - before.fetched)
        return body
    }

    /** The first request after a change of the data stamp: one swap, nothing served from the old generation, the body the oracle's. */
    private suspend fun Apps.assertMoved(label: String, query: String = query(world.person)): String {
        val before = cache.counts()
        val body = sameBody(query, label)
        val after = cache.counts()
        assertEquals(1L, after.swaps - before.swaps, "$label: the generation swapped")
        assertEquals(0L, after.served - before.served, "$label: no teammate is served from the old generation")
        return body
    }

    private suspend fun Apps.step(label: String, mutate: suspend () -> Unit): String {
        warm(query(world.person))
        mutate()
        return assertMoved(label)
    }

    @Test
    fun `the first, the repeated and a teammate's request equal the cache-off oracle byte for byte, and the teammate's reads one person`() =
        withApps {
            val person = query(world.person)
            val oracle = off.personTrendBody(person)
            val startedAt = System.nanoTime()
            off.personTrendBody(person)
            // An in-test target (build-times.md), not a gate: today's figure -- the whole team's rows on every request.
            println("in-test\tperson-trend-365d-team\t${(System.nanoTime() - startedAt) / 1_000_000L}")

            val cold = on.personTrendBody(person)
            assertEquals(oracle, cold, "the cold request")
            val afterCold = cache.counts()
            assertEquals(0L, afterCold.served)
            assertEquals(world.peers.size.toLong(), afterCold.fetched)
            assertEquals(((world.peers.size + 1) * WINDOWS).toLong(), afterCold.writes, "the person and every teammate, every window")
            assertEquals(0L, afterCold.refused)
            assertEquals(1L, afterCold.swaps)

            assertEquals(oracle, on.personTrendBody(person), "the repeat, every teammate served")
            val afterRepeat = cache.counts()
            assertEquals(world.peers.size.toLong(), afterRepeat.served - afterCold.served)
            assertEquals(afterCold.writes, afterRepeat.writes, "nothing new to write")

            val report = decode(oracle)
            assertEquals(WINDOWS, report.windows.size)
            val shown = report.rows.flatMap { row -> row.points.mapNotNull { it.reference?.value } }
            assertTrue(shown.size > 20, "not vacuous: real team medians in the body (${shown.size})")

            // Every teammate's own request: the person and the other four are all cached, so each reads exactly its own rows.
            for (peer in world.peers) {
                val before = cache.counts()
                val body = sameBody(query(peer), "teammate $peer")
                val after = cache.counts()
                assertEquals(world.peers.size.toLong(), after.served - before.served, "$peer: the person and the others are served")
                assertEquals(0L, after.fetched - before.fetched)
            }

            // Compare people and the drill are not cache clients: they neither read nor write it.
            val before = cache.counts()
            val twoPeople = "connectionId=${world.connId}&from=2025-12-06&to=$TO&accountId=${world.person}&accountId=${world.peers[0]}"
            on.comparePeopleReport(twoPeople)
            val drill = on.get("/api/v1/reports/person-trend/items?$person&view=DELIVERED&pointEnd=$TO")
            assertEquals(HttpStatusCode.OK, drill.status)
            val after = cache.counts()
            assertEquals(listOf(before.served, before.fetched, before.writes, before.refused, before.swaps),
                listOf(after.served, after.fetched, after.writes, after.refused, after.swaps))
        }

    @Test
    fun `every slice, the whole unit and a long span keep their own entries and equal the oracle`() = withApps {
        val person = query(world.person)
        val unsliced = sameBody(person)
        val slices = sliceParameters()
        val bodies = slices.map { slice -> sameBody(query(world.person, slice), "cold $slice") }
        // The same slices again, now every teammate cached under its own slice signature.
        slices.forEachIndexed { i, slice ->
            val before = cache.counts()
            assertEquals(bodies[i], on.personTrendBody(query(world.person, slice)), "warm $slice")
            assertEquals(world.peers.size.toLong(), cache.counts().served - before.served, "$slice: its own entries are served")
        }
        assertTrue(bodies.any { it != unsliced }, "at least one slice changes the figures: the keys are not vacuous")
        assertEquals(unsliced, on.personTrendBody(person), "the unsliced entries survived the sliced requests")

        // No `connectionId`: the scope is every active connection, a different key (never the one connection's entries).
        val unit = "from=$FROM&to=$TO&accountId=${world.person}"
        warm(person)
        val before = cache.counts()
        sameBody(unit, "every connection")
        val after = cache.counts()
        assertEquals(0L, after.served - before.served, "another connection scope shares no entry")
        assertEquals(0L, after.swaps - before.swaps, "but it is the same generation")
        warm(person) // and the one connection's entries are still there

        // The longest span (1100 days, 79 windows): a different grid over the same cells.
        val long = query(world.person, from = LocalDate.parse(TO).minusDays(1099).toString())
        val longOracle = sameBody(long, "1100 days")
        assertEquals(79, decode(longOracle).windows.size)
        assertEquals(longOracle, on.personTrendBody(long), "1100 days, warm")
    }

    /** Three slices that exist in the clone's data: its team's least frequent activity type, work category and domain. */
    private suspend fun sliceParameters(): List<String> {
        val bounds = windowBounds(reportZone(), FROM, TO)
        val people = (listOf(world.person) + world.peers).joinToString(",") { "'$it'" }
        suspend fun rarest(column: String): String? = jdbcRows(
            """SELECT $column, count(*) c FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ? AND $column IS NOT NULL
                 AND assignee_account_id_at_done IN ($people)
               GROUP BY 1 ORDER BY c, 1 LIMIT 1""",
            world.connId.toInt(), bounds.first, bounds.second,
        ).firstOrNull()?.first() as String?
        return listOfNotNull(
            rarest("activity_type")?.let { "&activityType=$it" },
            rarest("work_category")?.let { "&workCategory=$it" },
            rarest("domain_key")?.let { "&domain=$it" },
            "&workCategory=UNCATEGORIZED",
        )
    }

    @Test
    fun `a settings change - the minimum sample size, a public holiday - swaps the generation and the body follows`() = withApps {
        val person = query(world.person)
        val before = warm(person)
        val hidden = withMinSampleSize(100) { assertMoved("minSampleSize") }
        assertNotEquals(decode(before).rows, decode(hidden).rows, "the larger minimum hides cells, so the cached ones would be wrong")
        val restored = assertMoved("minSampleSize restored")
        assertEquals(decode(before).rows, decode(restored).rows)

        warm(person)
        val holiday = TestHolidays.with("2025-12-10") { assertMoved("public holiday") }
        assertNotEquals(decode(before).rows, decode(holiday).rows, "a working day fewer moves the WIP figures")
        assertMoved("public holiday removed")
    }

    @Test
    fun `a roster change and a second DERIVE swap the generation`() = withApps {
        val person = query(world.person)
        val rows = decode(warm(person)).rows
        val joined = try {
            step("a teammate joins") {
                world.created += world.memberships.create(world.teamId, TeamMembershipCreateRequest(world.outsider, 0L, null)).id
            }.also {
                assertTrue(decode(it).windows.all { window -> window.team != null })
                // The six-person roster is served in full on the repeat (the outsider's rows were fetched and cached too).
                warm(person, teammates = world.peers.size + 1)
            }
        } finally {
            world.memberships.delete(world.teamId, world.created.removeAt(world.created.lastIndex))
        }
        assertMoved("a teammate leaves")
        assertEquals(rows.size, decode(joined).rows.size)

        step("a second DERIVE") {
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
                DerivedStubFixture.derivePinned(world.connId, jobId = 2u)
            }
        }.let { assertEquals(rows, decode(it).rows, "the same facts again: the same figures") }
    }

    @Test
    fun `a data-source PUT moving backfillFrom inside the span swaps the generation and the body follows`() = withApps {
        // The WIP series start at the connection's backfill day (`planSnapshotDays`), so moving it inside FROM..TO clips avgWip and the
        // held-task ages of the windows before it; a PUT bumps only the connection's own revision, which `connection:` carries.
        val person = query(world.person)
        val before = warm(person)
        try {
            SyncedStubFixture.putBackfillFrom(world.connId, "2025-12-01")
            val moved = assertMoved("backfillFrom moved to 2025-12-01")
            assertNotEquals(decode(before).rows, decode(moved).rows, "fewer WIP days are listed, so the cached cells would be wrong")
            warm(person)
        } finally {
            SyncedStubFixture.putBackfillFrom(world.connId, SyncedStubFixture.BACKFILL_FROM)
        }
        assertEquals(decode(before).rows, decode(assertMoved("backfillFrom restored")).rows)
    }

    @Test
    fun `a cold request writes exactly the cells it computes - the team's windows only, never the others`() = withApps {
        // The outsider joins the six-person team on 2025-09-20: the point has a team only from then on, so only some windows need peers.
        val joining = TeamMembershipCreateRequest(world.outsider, noonUtc("2025-09-20"), null)
        world.created += world.memberships.create(world.teamId, joining).id
        try {
            val query = query(world.outsider)
            val body = sameBody(query)
            val windows = decode(body).windows
            val withTeam = windows.count { it.team != null }
            assertTrue(withTeam in 1 until windows.size, "the team exists in some windows only ($withTeam of ${windows.size})")
            val peers = world.peers.size + 1
            // The person and the six peers in the windows with a team: all the cache-off side computes.
            val computed = withTeam * (peers + 1)
            assertEquals(computed, cache.entryCount, "one entry per computed cell")
            assertEquals(computed.toLong(), cache.counts().writes)
            assertEquals(peers.toLong(), cache.counts().fetched)
        } finally {
            world.memberships.delete(world.teamId, world.created.removeAt(world.created.lastIndex))
        }
    }

    @Test
    fun `a person with no team writes nothing, so made-up accounts cannot fill the cap`() = withApps {
        val teamless = query(world.outsider)
        sameBody(teamless)
        assertTrue(decode(on.personTrendBody(teamless)).windows.all { it.team == null }, "the outsider has no team in any window")
        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.counts().writes)
        assertEquals(0L, cache.counts().refused)
    }

    @Test
    fun `ingest activity and a day roll swap the generation`() = withApps {
        val admin = TestUsers.seed(uniqueEmail("trend-cache-admin"), "pw", role = FlowUserRole.ADMIN)
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
        val j = SyncJobsService.Jobs
        var job = 0u
        step("a job is enqueued") { job = jobs.requestJob(world.connId, SyncJobKind.SYNC, admin, configRevision = 1L).jobId }
        step("a committed page's heartbeat") {
            suspendTransaction(sharedDatabaseForTests()) { j.update({ j.id eq job }) { it[heartbeatAt] = 1_000L } }
        }
        step("the run ends") {
            suspendTransaction(sharedDatabaseForTests()) { j.update({ j.id eq job }) { it[finishedAt] = 3_000L; it[status] = "FAILED" } }
        }
        step("the next local day") {
            val tomorrow = System.currentTimeMillis() + ONE_DAY_MS
            onService.clock = { tomorrow }
            offService.clock = { tomorrow }
        }
    }

    @Test
    fun `the cap refuses writes and never evicts - exact capacity is served, less is correct and misses`() {
        withApps(onMaxEntries = "162") { // the person and five teammates, 27 windows
            sameBody(query(world.person))
            assertEquals(162, cache.entryCount)
            assertEquals(0L, cache.counts().refused)
            warm(query(world.person))
        }
        withApps(onMaxEntries = "10") {
            val person = query(world.person)
            sameBody(person)
            assertEquals(10, cache.entryCount, "never more than the cap")
            assertEquals(10L, cache.counts().writes)
            assertTrue(cache.counts().refused > 0)
            val before = cache.counts()
            sameBody(person, "second request")
            assertEquals(0L, cache.counts().served - before.served, "no teammate is complete in 10 entries, so every one is fetched again")
            assertEquals(10, cache.entryCount)
        }
    }

    @Test
    fun `the off switch stores, serves and counts nothing`() = withApps(onMaxEntries = "0") {
        val person = query(world.person)
        repeat(2) { sameBody(person) }
        sameBody(query(world.peers[0]))
        val counts = cache.counts()
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), listOf(counts.served, counts.fetched, counts.writes, counts.refused, counts.swaps))
        assertEquals(0, cache.entryCount)
    }

    @Test
    fun `eight cold requests at once answer identical bodies and leave one entry per key`() = withApps {
        val person = query(world.person)
        val oracle = off.personTrendBody(person)
        val bodies = coroutineScope { (1..8).map { async { on.personTrendBody(person) } }.awaitAll() }
        bodies.forEachIndexed { i, body -> assertEquals(oracle, body, "request ${i + 1}") }
        assertEquals(((world.peers.size + 1) * WINDOWS), cache.entryCount, "one entry per account and window")
        assertEquals(cache.entryCount.toLong(), cache.counts().writes, "idempotent writes: each key written once")
        assertEquals(1L, cache.counts().swaps, "racing requests of one stamp share one generation")
    }

    @Test
    fun `a malformed or out-of-range cache size refuses startup`() {
        listOf("-1", "abc", "50001").forEach { bad ->
            testApplication {
                configureApp("reports.peerCellCache.maxEntries" to bad)
                assertStartupFails("reports.peerCellCache.maxEntries") { startApplication() }
            }
        }
    }

    companion object {
        private lateinit var built: World

        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            built = runBlocking { buildWorld() }
        }

        @JvmStatic
        @AfterClass
        fun removeWorld() = runBlocking {
            if (!::built.isInitialized) return@runBlocking
            try {
                built.created.forEach { built.memberships.delete(built.teamId, it) }
            } finally {
                TestTeams.service.delete(built.teamId)
            }
        }

        /**
         * The derived-clone recipe of `ReportPersonTrendFixtureTest`: a private DISABLED clone derived under the pinned clock, a team of
         * the busiest assignee and the next five from the beginning of time, and the seventh as the outsider. The memberships are global
         * by account id, so each is deleted explicitly in [removeWorld] (`TeamService.delete` only closes the open ones).
         */
        private suspend fun buildWorld(): World {
            val connId = SyncedStubFixture.createConnection(namePrefix = "trend-cache-clone", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
            val bounds = windowBounds(reportZone(), FROM, TO)
            val busiest = jdbcRows(
                """SELECT assignee_account_id_at_done, count(*) c FROM metrics.fact_task_delivery
                   WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                     AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
                   GROUP BY 1 ORDER BY c DESC, 1 LIMIT 7""",
                connId.toInt(), bounds.first, bounds.second,
            ).map { it[0] as String }
            check(busiest.size == 7) { "the derived stub has fewer than seven assignees with a cycle time" }
            val teamId = TestTeams.seed(SyncedStubFixture.unique("trend-cache-team"))
            val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            val created = mutableListOf<UInt>()
            try {
                for (account in busiest.take(6)) created += memberships.create(teamId, TeamMembershipCreateRequest(account, 0L, null)).id
            } catch (failure: Throwable) {
                created.forEach { memberships.delete(teamId, it) }
                TestTeams.service.delete(teamId)
                throw failure
            }
            return World(connId, teamId, busiest[0], busiest.slice(1..5), busiest[6], memberships, created)
        }
    }
}
