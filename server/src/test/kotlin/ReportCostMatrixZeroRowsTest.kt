package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.CostRow
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The UNASSIGNED sentinel team id on the wire (authors in no team). */
private const val UNASSIGNED = 0u

private fun at(isoInstant: String): Long = Instant.parse(isoInstant).toEpochMilli()

/**
 * The hand-built world of one zero-row test: a fresh DISABLED connection with five known people (`Person 1` .. `Person 5`, so the
 * display-name order is the order of [people]), the filter team [t] and another team [u]. Removed in the `finally`.
 */
private class ZeroRowWorld(val connId: UInt, val t: UInt, val u: UInt, val people: List<String>) {
    val store = MetricsStore(sharedDatabaseForTests())
    private var nextWorklogId = 1L

    fun range(from: String, to: String) = "connectionId=$connId&from=$from&to=$to"

    suspend fun log(team: UInt?, account: String?, md: Double, domain: String, day: String) {
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertFactWorklog(connId, listOf(wl(nextWorklogId++, team, account, day, md, domain)), configRevision = 1L)
        }
    }

    suspend fun member(team: UInt, account: String, validFrom: Long = 0L, validTo: Long? = null) {
        TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            .create(team, TeamMembershipCreateRequest(account, validFrom, validTo))
    }
}

private suspend fun withZeroRowWorld(block: suspend (ZeroRowWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-zero", enabled = false)
    // Known people named so that the display-name order is the list order (CapacityFixtures.people sorts the ids, not the names).
    val people = List(5) { "account-${UUID.randomUUID()}" }
    SyncedStubFixture.workItems().replacePeople(
        connId, people.mapIndexed { i, id -> PersonRef(id, "Person ${i + 1}", null, active = true) },
    )
    val t = TestTeams.seed(SyncedStubFixture.unique("cz-t"))
    val u = TestTeams.seed(SyncedStubFixture.unique("cz-u"))
    val world = ZeroRowWorld(connId, t, u, people)
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            world.store.deleteFactWorklog(connId)
            world.store.deleteDims(connId)
        }
        cleanUpTeams(listOf(t, u))
    }
}

/**
 * The cost matrix's zero rows (report 16, domain-model A39 as amended 2026-10-17): at TEAM and USER level of a real team the rows are
 * the authors who logged plus the team's members whose membership covers a working day of the period, the same set for every viewer
 * and independent of capacity. January 2026 (Jan 1 and Jan 6 are seeded public holidays), Europe/Warsaw (UTC+1), fresh DISABLED
 * connection.
 */
class ReportCostMatrixZeroRowsTest {

    private suspend fun HttpClient.cost(query: String): CostMatrixReport {
        val response = get("/api/v1/reports/cost-matrix?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix?$query")
        return response.body()
    }

    private fun CostRow.assertZero(label: String) {
        assertEquals(0.0, totalMd, label)
        assertEquals(0.0, foreignMd, label)
        assertNull(foreignShare, label)
        assertTrue(cells.all { it.md == 0.0 }, "$label: every cell is 0")
    }

    @Test
    fun `a member with no worklogs is a zero row for a plain user and an admin alike, only the capacity differs`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (idle, author, other) = w.people
            w.member(w.t, idle)
            w.member(w.t, author)
            w.member(w.u, other)
            w.log(w.t, author, 2.0, "AAA", "2026-01-13")
            w.log(w.t, author, 1.0, "BBB", "2026-01-14")
            w.log(w.u, other, 5.0, "AAA", "2026-01-13")
            val admin = seededClient("cz-zero-admin", UserRole.ADMIN)
            val plain = seededClient("cz-zero-plain", UserRole.USER)
            val query = "${w.range("2026-01-12", "2026-01-16")}&teamId=${w.t}"

            val forAdmin = admin.cost(query)
            val forPlain = plain.cost(query)
            // "Person 1" (the idle member) sorts before "Person 2" (the author): the ONE row order, label first.
            assertEquals(listOf(idle, author), forPlain.rows.map { it.accountId }, "the zero row sorts in by display name")
            assertEquals(forPlain.rows.map { it.accountId }, forAdmin.rows.map { it.accountId }, "the same rows for every viewer")
            assertEquals(forPlain.rows.map { it.label }, forAdmin.rows.map { it.label })
            assertTrue(forAdmin.rows.all { it.teamId == w.t })

            val zero = forPlain.rows.first()
            zero.assertZero("plain")
            assertEquals(listOf("AAA", "BBB"), zero.cells.map { it.domain }, "dense over the period's columns")
            assertEquals(3.0, forPlain.totalMd, "a zero row adds nothing to the grand figures")
            assertEquals(listOf(2.0, 1.0), forPlain.columns.map { it.totalMd })
            assertEquals(3.0, forPlain.rows.sumOf { it.totalMd })

            // The capacity is the only difference: each member had five working days available.
            assertNull(forPlain.capacity)
            assertTrue(forPlain.rows.all { it.capacityMd == null })
            val raw = plain.get("/api/v1/reports/cost-matrix?$query").bodyAsText()
            assertFalse("capacity" in raw, "a plain user's JSON carries no capacity key")
            assertEquals(5.0, forAdmin.rows.first().capacityMd)
            assertEquals(5.0, forAdmin.rows.last().capacityMd)
            assertEquals(10.0, assertNotNull(forAdmin.capacity).totalMd, "the totals row counts the zero row's person")
            forAdmin.rows.first().assertZero("admin")
        }
    }

    @Test
    fun `a membership covering no working day of the period, or ended before it, gets no row`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (weekendOnly, former, holidayOnly) = w.people
            val (joiner, otherTeam) = w.people.drop(3)
            w.member(w.t, weekendOnly, at("2026-01-16T23:00:00Z"), at("2026-01-18T23:00:00Z")) // Sat 17th and Sun 18th, Warsaw
            w.member(w.t, former, 0L, at("2026-01-09T12:00:00Z")) // left before the period
            w.member(w.t, holidayOnly, at("2026-01-05T23:00:00Z"), at("2026-01-06T23:00:00Z")) // Tue Jan 6, a public holiday, only
            w.member(w.t, joiner, at("2026-01-21T08:00:00Z")) // joins mid-period on Wednesday the 21st
            w.member(w.u, otherTeam) // another team's member is nobody's zero row here
            val admin = seededClient("cz-edge-admin", UserRole.ADMIN)
            val plain = seededClient("cz-edge-plain", UserRole.USER)

            // Mon 12th .. Sun 25th: the weekend-only member's days are in it but are not working days; the 21st..23rd are.
            val query = "${w.range("2026-01-12", "2026-01-25")}&teamId=${w.t}"
            for ((who, client) in listOf("plain" to plain, "admin" to admin)) {
                assertEquals(listOf(joiner), client.cost(query).rows.map { it.accountId }, who)
            }
            assertEquals(3.0, admin.cost(query).rows.single().capacityMd, "Wed 21st .. Fri 23rd")

            // Tue Jan 6 is a public holiday: a membership covering only that day is no working day of a period that is only that day,
            // and Wed the 7th (which the former member covers, the holiday-only member does not) makes the former member's row appear.
            assertTrue(plain.cost("${w.range("2026-01-06", "2026-01-06")}&teamId=${w.t}").rows.isEmpty())
            assertEquals(listOf(former), plain.cost("${w.range("2026-01-06", "2026-01-07")}&teamId=${w.t}").rows.map { it.accountId })
        }
    }

    @Test
    fun `USER level lists the picked person with a zero row, UNIT level and the unassigned bucket are unchanged`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (idle, author, outsider) = w.people
            w.member(w.t, idle)
            w.member(w.t, author)
            w.log(w.t, author, 2.0, "AAA", "2026-01-13")
            w.log(null, outsider, 4.0, "AAA", "2026-01-13")
            val admin = seededClient("cz-user-admin", UserRole.ADMIN)
            val plain = seededClient("cz-user-plain", UserRole.USER)
            val range = w.range("2026-01-12", "2026-01-16")

            val picked = plain.cost("$range&teamId=${w.t}&accountId=$idle")
            val row = picked.rows.single()
            assertEquals(idle, row.accountId)
            assertEquals(w.t, row.teamId)
            row.assertZero("USER level")
            assertEquals(0.0, picked.totalMd)
            assertNull(picked.foreignShare)
            assertNull(row.capacityMd)
            assertEquals(5.0, admin.cost("$range&teamId=${w.t}&accountId=$idle").rows.single().capacityMd)

            // A person who logged keeps their one row, with their figures.
            assertEquals(2.0, plain.cost("$range&teamId=${w.t}&accountId=$author").rows.single().totalMd)

            // UNIT level: one row per author team that logged -- the roster adds nothing.
            val unit = plain.cost(range)
            assertEquals(listOf(w.t, UNASSIGNED), unit.rows.map { it.teamId })
            // teamId=0: the authors in no team, no roster to add from, and a pick without a worklog stays absent.
            assertEquals(listOf(outsider), plain.cost("$range&teamId=0").rows.map { it.accountId })
            assertTrue(plain.cost("$range&teamId=0&accountId=$idle").rows.isEmpty())
        }
    }

    @Test
    fun `with a split a zero row carries dense zero buckets, its capacity per bucket only for an editor`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (idle, author) = w.people
            w.member(w.t, idle)
            w.member(w.t, author)
            w.log(w.t, author, 2.0, "AAA", "2026-01-13") // week of Jan 12
            w.log(w.t, author, 1.0, "BBB", "2026-01-21") // week of Jan 19
            val admin = seededClient("cz-split-admin", UserRole.ADMIN)
            val plain = seededClient("cz-split-plain", UserRole.USER)
            val query = "${w.range("2026-01-12", "2026-01-25")}&teamId=${w.t}&split=WEEK"

            val forPlain = plain.cost(query)
            assertEquals(listOf(idle, author), forPlain.rows.map { it.accountId })
            val zero = forPlain.rows.first()
            val buckets = assertNotNull(zero.byBucket)
            assertEquals(2, buckets.size, "one entry per week, in bucket order")
            for (bucket in buckets) {
                assertEquals(0.0, bucket.totalMd)
                assertEquals(0.0, bucket.foreignMd)
                assertNull(bucket.foreignShare)
                assertEquals(listOf("AAA", "BBB"), bucket.cells.map { it.domain }, "dense over the period's columns")
                assertTrue(bucket.cells.all { it.md == 0.0 })
                assertNull(bucket.capacityMd)
            }
            assertEquals(listOf(2.0, 1.0), assertNotNull(forPlain.rows.last().byBucket).map { it.totalMd })

            val forAdmin = admin.cost(query)
            assertEquals(forPlain.rows.map { it.accountId }, forAdmin.rows.map { it.accountId })
            assertEquals(listOf(5.0, 5.0), assertNotNull(forAdmin.rows.first().byBucket).map { it.capacityMd })
            assertEquals(listOf(10.0, 10.0), assertNotNull(forAdmin.bucketTotals).map { it.capacityMd }, "the footer sums both people")
            // The footer figures stay the logged ones: the zero row adds no cost.
            assertEquals(listOf(2.0, 1.0), assertNotNull(forPlain.bucketTotals).map { it.totalMd })
        }
    }

    @Test
    fun `members who logged nothing at all are zero rows with no cells, with and without a split`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (first, second) = w.people
            w.member(w.t, first)
            w.member(w.t, second)
            w.log(w.t, first, 9.0, "AAA", "2026-02-10") // outside the period
            val plain = seededClient("cz-none-plain", UserRole.USER)
            val admin = seededClient("cz-none-admin", UserRole.ADMIN)
            val query = "${w.range("2026-01-12", "2026-01-16")}&teamId=${w.t}"

            for ((who, client) in listOf("plain" to plain, "admin" to admin)) {
                val report = client.cost(query)
                assertTrue(report.columns.isEmpty(), "$who: no column when nothing was logged")
                assertEquals(listOf(first, second), report.rows.map { it.accountId }, who)
                report.rows.forEach { row ->
                    assertTrue(row.cells.isEmpty(), "$who: no cells")
                    row.assertZero(who)
                }
                assertEquals(0.0, report.totalMd)
                assertNull(report.foreignShare)
            }
            assertEquals(listOf(5.0, 5.0), admin.cost(query).rows.map { it.capacityMd })

            val split = admin.cost("$query&split=WEEK")
            assertEquals(listOf(first, second), split.rows.map { it.accountId })
            for (row in split.rows) {
                val buckets = assertNotNull(row.byBucket)
                assertEquals(1, buckets.size)
                assertTrue(buckets.all { it.cells.isEmpty() && it.totalMd == 0.0 && it.foreignShare == null })
            }
            val footer = assertNotNull(split.bucketTotals).single()
            assertTrue(footer.columns.isEmpty())
            assertEquals(0.0, footer.totalMd)
            assertEquals(10.0, footer.capacityMd)
        }
    }

    @Test
    fun `the zero rows apply on top of the slices, and a mover is a zero row in both teams for the days each covers`() = testApplication {
        usePostgresTestcontainer()
        withZeroRowWorld { w ->
            val (author, onlyOutside, mover) = w.people
            w.member(w.t, author)
            w.member(w.t, onlyOutside)
            w.member(w.t, mover, 0L, at("2026-01-14T23:00:00Z")) // in T until Thu 15th 00:00 Warsaw
            w.member(w.u, mover, at("2026-01-14T23:00:00Z")) // in U from Thu 15th
            w.log(w.t, author, 2.0, "AAA", "2026-01-13")
            w.log(w.t, onlyOutside, 3.0, "BBB", "2026-01-13")
            val plain = seededClient("cz-slice-plain", UserRole.USER)
            val range = w.range("2026-01-12", "2026-01-16")

            // domain=AAA: the member who logged only BBB has nothing in the slice, so a zero row; columns are the slice's.
            val sliced = plain.cost("$range&teamId=${w.t}&domain=AAA")
            assertEquals(listOf("AAA"), sliced.columns.map { it.domain })
            assertEquals(2.0, sliced.totalMd)
            val bySlice = sliced.rows.associateBy { it.accountId }
            assertEquals(setOf(author, onlyOutside, mover), bySlice.keys)
            assertEquals(2.0, bySlice.getValue(author).totalMd)
            bySlice.getValue(onlyOutside).assertZero("logged only outside the slice")

            // The mover is in T for Mon 12th..Wed 14th and in U for Thu 15th..Fri 16th: a row in each team's view.
            assertTrue(mover in plain.cost("$range&teamId=${w.t}").rows.map { it.accountId })
            assertEquals(listOf(mover), plain.cost("$range&teamId=${w.u}").rows.map { it.accountId })
        }
    }
}
