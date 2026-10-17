package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.CostBucketFigures
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.CostRow
import ch.nokillswit.reports.CostSplit
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROUNDING = 0.005

private fun at(isoInstant: String): Long = Instant.parse(isoInstant).toEpochMilli()

/**
 * The hand-built world of one split test: a fresh DISABLED connection with two known people, the filter team [t] and another team
 * [u], an admin user. Everything it seeded is removed in the `finally` (worklogs, dims, sprints, calendars, teams).
 */
private class SplitWorld(
    val connId: UInt,
    val t: UInt,
    val u: UInt,
    val alice: String,
    val bob: String,
    val adminId: UInt,
) {
    val store = MetricsStore(sharedDatabaseForTests())
    private var nextWorklogId = 1L

    fun range(from: String, to: String) = "connectionId=$connId&from=$from&to=$to"

    /** One worklog of [team]'s [account] started at [startedAtMs] (noon UTC of [day] when absent), in domain [domain]. */
    suspend fun log(
        team: UInt?,
        account: String?,
        md: Double,
        domain: String,
        day: String,
        startedAtMs: Long? = null,
        foreign: Boolean = false,
    ) {
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertFactWorklog(
                connId,
                listOf(wl(nextWorklogId++, team, account, day, md, domain, foreign = foreign, startedAtMs = startedAtMs)),
                configRevision = 1L,
            )
        }
    }

    suspend fun sprint(team: UInt?, sprintId: Long, start: Long?, end: Long?) =
        CapacityFixtures.insertSprint(connId, team, sprintId, start, end)

    suspend fun member(team: UInt, account: String) {
        TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            .create(team, TeamMembershipCreateRequest(account, validFrom = 0L))
    }

    val calendars: SprintCalendarService = CapacityFixtures.service()
}

private suspend fun withSplitWorld(block: suspend (SplitWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-split", enabled = false)
    val people = CapacityFixtures.people(connId, 2)
    val t = TestTeams.seed(SyncedStubFixture.unique("cs-t"))
    val u = TestTeams.seed(SyncedStubFixture.unique("cs-u"))
    val adminId = TestUsers.seed(uniqueEmail("cs-admin"), "pw")
    val world = SplitWorld(connId, t, u, people[0], people[1], adminId)
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            world.store.deleteFactWorklog(connId)
            world.store.deleteDims(connId)
            MetricsTables.DimSprint.deleteWhere { MetricsTables.DimSprint.connectionId eq connId }
        }
        world.calendars.purgeConnection(connId)
        cleanUpTeams(listOf(t, u))
    }
}

/**
 * The cost matrix's time split (report 16, domain-model A39 part 2): week and sprint buckets over ONE day -> bucket map, the
 * partition of the period's cost, the capacity per bucket, the `NONE` identity and the `400`s. January 2026, Europe/Warsaw (UTC+1).
 */
class ReportCostMatrixSplitTest {

    private suspend fun HttpClient.cost(query: String): CostMatrixReport {
        val response = get("/api/v1/reports/cost-matrix?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix?$query")
        return response.body()
    }

    private fun CostMatrixReport.row(account: String): CostRow = rows.single { it.accountId == account }

    private fun List<CostBucketFigures>.totals() = map { it.totalMd }

    @Test
    fun `split=WEEK zero-fills Monday-start weeks in the configured zone, every worklog in exactly one bucket by its day`() =
        testApplication {
            usePostgresTestcontainer()
            withSplitWorld { w ->
                // Period Wed 2026-01-07 .. Sun 2026-02-08: five Monday-start weeks, the first one cut at the 7th, the third week-end free.
                // 00:30 Warsaw on the 7th: the first day of the period
                w.log(w.t, w.alice, 0.5, "AAA", "2026-01-06", at("2026-01-06T23:30:00Z"))
                w.log(w.t, w.alice, 1.0, "AAA", "2026-01-08") // week of Jan 5
                w.log(w.t, w.alice, 2.0, "BBB", "2026-01-18", at("2026-01-18T22:30:00Z")) // 23:30 Warsaw on Sunday the 18th: week of Jan 12
                w.log(w.t, w.alice, 4.0, "AAA", "2026-01-18", at("2026-01-18T23:30:00Z")) // 00:30 Warsaw on Monday the 19th: week of Jan 19
                w.log(w.t, w.alice, 8.0, "BBB", "2026-02-03") // week of Feb 2; nothing in the week of Jan 26
                w.log(w.t, w.alice, 99.0, "AAA", "2026-01-06") // noon UTC on the 6th: before the period
                val admin = seededClient("cs-week-admin", UserRole.ADMIN)

                val report = admin.cost("${w.range("2026-01-07", "2026-02-08")}&split=WEEK")
                val split = assertNotNull(report.split)
                assertEquals(CostSplit.WEEK, split.kind)
                assertEquals(
                    listOf("2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26", "2026-02-02"), split.buckets.map { it.key },
                )
                assertEquals(
                    listOf(
                        "2026-01-07" to "2026-01-11", "2026-01-12" to "2026-01-18", "2026-01-19" to "2026-01-25",
                        "2026-01-26" to "2026-02-01", "2026-02-02" to "2026-02-08",
                    ),
                    split.buckets.map { it.from to it.to },
                )
                assertTrue(split.buckets.all { it.label == null && it.sprintId == null })

                val row = report.rows.single()
                assertEquals(15.5, row.totalMd)
                val byBucket = assertNotNull(row.byBucket)
                assertEquals(split.buckets.size, byBucket.size, "dense: one entry per bucket")
                assertEquals(listOf(1.5, 2.0, 4.0, 0.0, 8.0), byBucket.totals())
                assertEquals(row.totalMd, byBucket.totals().sum(), "the buckets partition the row")
                assertEquals(listOf(0.0, 0.0), byBucket[3].cells.map { it.md }, "the empty week is all zeros over BOTH columns")
                assertEquals(report.columns.map { it.domain }, byBucket[3].cells.map { it.domain })
                // The domain columns of the worklog at 00:30 Warsaw: AAA in week 1 (0.5 + 1.0), BBB in week 2 and 5, AAA in week 3.
                assertEquals(listOf(1.5, 0.0), byBucket[0].cells.map { it.md })
                assertEquals(listOf(0.0, 2.0), byBucket[1].cells.map { it.md })
                assertEquals(listOf(4.0, 0.0), byBucket[2].cells.map { it.md })
                assertEquals(listOf(0.0, 8.0), byBucket[4].cells.map { it.md })
                assertNull(row.capacityMd, "UNIT level: no capacity, not even for an admin")
                assertNull(byBucket.first().capacityMd)
                assertNull(report.capacity)
                assertEquals(listOf(1.5, 2.0, 4.0, 0.0, 8.0), assertNotNull(report.bucketTotals).map { it.totalMd })
            }
        }

    @Test
    fun `split=SPRINT buckets the team's sprints in start order, outside time last, a day of two sprints to the earlier one`() =
        testApplication {
            usePostgresTestcontainer()
            withSplitWorld { w ->
                w.sprint(w.t, 101L, at("2026-01-06T09:00:00Z"), at("2026-01-12T15:00:00Z")) // Jan 6 .. 12
                // Jan 12 .. 16: the 12th is the earlier sprint's
                w.sprint(w.t, 102L, at("2026-01-12T09:00:00Z"), at("2026-01-16T15:00:00Z"))
                w.sprint(w.t, 103L, null, null) // no start: no bucket
                w.sprint(w.u, 104L, at("2026-01-19T09:00:00Z"), at("2026-01-23T15:00:00Z")) // another team's sprint: not a bucket
                w.log(w.t, w.alice, 1.0, "AAA", "2026-01-06", at("2026-01-06T07:00:00Z")) // 08:00 Warsaw on the first day: sprint 101
                w.log(w.t, w.alice, 2.0, "AAA", "2026-01-12") // both sprints cover it: the earlier one
                w.log(w.t, w.bob, 4.0, "AAA", "2026-01-13") // sprint 102
                w.log(w.t, w.bob, 8.0, "AAA", "2026-01-20") // outside every sprint of the team
                w.log(w.t, w.bob, 16.0, "AAA", "2026-01-05") // outside, before the first sprint
                val admin = seededClient("cs-sprint-admin", UserRole.ADMIN)

                val report = admin.cost("${w.range("2026-01-05", "2026-01-30")}&teamId=${w.t}&split=SPRINT")
                val buckets = assertNotNull(report.split).buckets
                assertEquals(listOf("101", "102", "outside"), buckets.map { it.key })
                assertEquals(listOf("Sprint 101", "Sprint 102", null), buckets.map { it.label })
                assertEquals(listOf(101L, 102L, null), buckets.map { it.sprintId })
                assertEquals(
                    listOf("2026-01-06" to "2026-01-12", "2026-01-13" to "2026-01-16", "2026-01-05" to "2026-01-30"),
                    buckets.map { it.from to it.to },
                )
                assertEquals(listOf(3.0, 0.0, 0.0), assertNotNull(report.row(w.alice).byBucket).totals())
                assertEquals(listOf(0.0, 4.0, 24.0), assertNotNull(report.row(w.bob).byBucket).totals())

                // Every period day owned: no Outside bucket.
                val owned = admin.cost("${w.range("2026-01-07", "2026-01-15")}&teamId=${w.t}&split=SPRINT")
                assertEquals(listOf("101", "102"), assertNotNull(owned.split).buckets.map { it.key })
            }
        }

    @Test
    fun `bucket figures reconcile to the period figures within the rounding bound, and capacity splits per bucket`() = testApplication {
        usePostgresTestcontainer()
        withSplitWorld { w ->
            // Mon 2026-01-05 .. Fri 2026-01-23. Sprint 201: Tue 6 .. Fri 9 (no calendar; Tue the 6th is a public holiday),
            // sprint 202: Mon 12 .. Fri 16 with a stored calendar (alice at 0.5, bob at 1). Outside: Jan 5, 10, 11, 17..23.
            w.sprint(w.t, 201L, at("2026-01-06T09:00:00Z"), at("2026-01-09T15:00:00Z"))
            w.sprint(w.t, 202L, at("2026-01-12T09:00:00Z"), at("2026-01-16T15:00:00Z"))
            w.member(w.t, w.alice)
            w.member(w.t, w.bob)
            val days = (12..16).map { "2026-01-$it" }
            val document = SprintCalendarDocument(
                1.0,
                days.map { SprintCalendarDay(it, true) },
                listOf(w.alice to 0.5, w.bob to 1.0).map { (account, coefficient) ->
                    SprintCalendarPerson(account, true, coefficient, days.map { SprintCalendarCell(it, 1.0) })
                },
            )
            val calendar = DerivedStubFixture.metricsSettings().readCalendar()
            w.calendars.replace(w.t, 202L, document, w.adminId, callerIsAdmin = true, calendar = calendar)
            w.log(w.t, w.alice, 1.0, "AAA", "2026-01-06")
            w.log(w.t, w.alice, 2.0, "BBB", "2026-01-13", foreign = true)
            w.log(w.t, w.alice, 0.3333, "AAA", "2026-01-20")
            w.log(w.t, w.bob, 1.5, "AAA", "2026-01-07")
            w.log(w.t, w.bob, 0.3333, "AAA", "2026-01-14")
            w.log(w.t, w.bob, 0.3333, "BBB", "2026-01-21")
            val admin = seededClient("cs-recon-admin", UserRole.ADMIN)

            val report = admin.cost("${w.range("2026-01-05", "2026-01-23")}&teamId=${w.t}&split=SPRINT")
            assertEquals(listOf("201", "202", "outside"), assertNotNull(report.split).buckets.map { it.key })
            val capacity = assertNotNull(report.capacity)
            assertEquals(19, capacity.days)
            assertEquals(5, capacity.calendarDays, "only sprint 202's days read a saved calendar")

            // Per-bucket capacity: 201 has the 7th, 8th and 9th (the 6th is a holiday): 3 each; 202: alice 5 x 0.5, bob 5; outside: the
            // 5th and 19th..23rd = 6 each. The buckets add up to the period figure (alice 11.5, bob 14).
            val alice = report.row(w.alice)
            val bob = report.row(w.bob)
            assertEquals(listOf(3.0, 2.5, 6.0), assertNotNull(alice.byBucket).map { it.capacityMd })
            assertEquals(listOf(3.0, 5.0, 6.0), assertNotNull(bob.byBucket).map { it.capacityMd })
            assertEquals(11.5, alice.capacityMd)
            assertEquals(14.0, bob.capacityMd)
            val footers = assertNotNull(report.bucketTotals)
            assertEquals(listOf(6.0, 7.5, 12.0), footers.map { it.capacityMd })
            assertEquals(capacity.totalMd, footers.sumOf { it.capacityMd!! })

            for (row in report.rows) {
                val byBucket = assertNotNull(row.byBucket)
                assertTrue(Math.abs(row.totalMd - byBucket.sumOf { it.totalMd }) <= ROUNDING * byBucket.size, "${row.label}: total")
                assertTrue(Math.abs(row.foreignMd - byBucket.sumOf { it.foreignMd }) <= ROUNDING * byBucket.size, "${row.label}: foreign")
                row.cells.forEachIndexed { i, cell ->
                    assertTrue(Math.abs(cell.md - byBucket.sumOf { it.cells[i].md }) <= ROUNDING * byBucket.size, "${row.label}: cell $i")
                }
            }
            val bucketCount = footers.size
            assertTrue(Math.abs(report.totalMd - footers.sumOf { it.totalMd }) <= ROUNDING * bucketCount)
            assertTrue(Math.abs(report.foreignMd - footers.sumOf { it.foreignMd }) <= ROUNDING * bucketCount)
            report.columns.forEachIndexed { i, column ->
                assertEquals(report.columns.map { it.domain }, footers.first().columns.map { it.domain })
                assertTrue(Math.abs(column.totalMd - footers.sumOf { it.columns[i].totalMd }) <= ROUNDING * bucketCount, "column $i")
            }
            // The footer is the exact sum over every row: sprint 201 holds alice 1.0 + bob 1.5, 202 holds 2.0 + 0.3333, outside 0.6666.
            assertEquals(listOf(2.5, 2.33, 0.67), footers.map { it.totalMd })
            assertEquals(listOf(0.0, 2.0, 0.0), footers.map { it.foreignMd })
        }
    }

    @Test
    fun `the footer capacity is present (zero) whenever the response carries capacity, null only when it is not shown`() = testApplication {
        usePostgresTestcontainer()
        withSplitWorld { w ->
            w.member(w.t, w.alice) // a roster member who logged nothing: no row, so the rows' people sum to zero
            w.log(w.t, null, 3.0, "AAA", "2026-01-13") // the only worklog has no author
            val admin = seededClient("cs-footer-admin", UserRole.ADMIN)
            val plain = seededClient("cs-footer-plain", UserRole.USER)
            val team = "${w.range("2026-01-12", "2026-01-18")}&teamId=${w.t}"

            for (split in listOf("WEEK", "SPRINT")) {
                val report = admin.cost("$team&split=$split")
                assertNotNull(report.capacity, "$split: the block is shown")
                assertEquals(0.0, report.capacity.totalMd, "$split: no person in the rows")
                val row = report.rows.single()
                assertNull(row.accountId)
                assertNull(row.capacityMd, "$split: the no-author row is no person")
                assertTrue(assertNotNull(row.byBucket).all { it.capacityMd == null }, "$split: nor are its buckets")
                val footers = assertNotNull(report.bucketTotals)
                assertTrue(footers.isNotEmpty() && footers.all { it.capacityMd == 0.0 }, "$split: the footer says 0, not a dash: $footers")

                // A period with no worklog at all: still shown for the admin.
                val empty = admin.cost("${w.range("2026-01-19", "2026-01-25")}&teamId=${w.t}&split=$split")
                assertTrue(empty.rows.isEmpty())
                assertNotNull(empty.capacity)
                assertTrue(assertNotNull(empty.bucketTotals).all { it.capacityMd == 0.0 }, "$split: empty period")

                // Not shown to a plain user (and not to the admin at UNIT level): the footer carries no figure.
                assertTrue(assertNotNull(plain.cost("$team&split=$split").bucketTotals).all { it.capacityMd == null }, "$split: plain user")
            }
            val unit = admin.cost("${w.range("2026-01-12", "2026-01-18")}&split=WEEK")
            assertNull(unit.capacity)
            assertTrue(assertNotNull(unit.bucketTotals).all { it.capacityMd == null }, "UNIT level")
        }
    }

    @Test
    fun `split=NONE is byte-identical to the unsplit response, and SPRINT without a team, over 160 buckets and a bad split are 400`() =
        testApplication {
            usePostgresTestcontainer()
            withSplitWorld { w ->
                w.member(w.t, w.alice)
                w.log(w.t, w.alice, 1.0, "AAA", "2026-01-12")
                w.log(null, w.bob, 2.0, "BBB", "2026-01-13")
                val admin = seededClient("cs-none-admin", UserRole.ADMIN)
                val range = w.range("2026-01-05", "2026-01-23")

                for (query in listOf(range, "$range&teamId=${w.t}", "$range&teamId=${w.t}&accountId=${w.alice}")) {
                    val plain = admin.get("/api/v1/reports/cost-matrix?$query").bodyAsText()
                    val none = admin.get("/api/v1/reports/cost-matrix?$query&split=NONE").bodyAsText()
                    assertEquals(plain, none, "split=NONE is the unsplit response, byte for byte: $query")
                    for (key in listOf("\"split\"", "\"byBucket\"", "\"bucketTotals\"")) {
                        assertFalse(key in none, "$key must be omitted: $query")
                    }
                }
                assertFalse("capacity" in admin.get("/api/v1/reports/cost-matrix?$range").bodyAsText(), "UNIT level: no capacity block")
                val drill = admin.get("/api/v1/reports/cost-matrix?$range&teamId=${w.t}").bodyAsText()
                assertTrue("\"capacity\"" in drill, "the admin's team drill still has it")

                val bad = listOf("$range&split=SPRINT", "$range&teamId=0&split=SPRINT", "$range&split=DAY", "$range&split=WEEK&split=WEEK")
                for (query in bad) {
                    assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/reports/cost-matrix?$query").status, query)
                }
                assertTrue("needs a team" in admin.get("/api/v1/reports/cost-matrix?$range&split=SPRINT").bodyAsText())

                // 161 one-day sprints on consecutive days of the team: 161 buckets over a 161-day period is one too many, 160 is fine.
                val first = LocalDate.parse("2026-01-01")
                for (i in 0..160) {
                    val day = first.plusDays(i.toLong())
                    w.sprint(w.t, 1000L + i, at("${day}T09:00:00Z"), at("${day}T15:00:00Z"))
                }
                val overRange = w.range("2026-01-01", first.plusDays(160).toString())
                val over = admin.get("/api/v1/reports/cost-matrix?$overRange&teamId=${w.t}&split=SPRINT")
                assertEquals(HttpStatusCode.BadRequest, over.status)
                assertTrue("161 buckets" in over.bodyAsText() && "at most 160" in over.bodyAsText(), over.bodyAsText())
                val atCap = admin.cost("${w.range("2026-01-01", first.plusDays(159).toString())}&teamId=${w.t}&split=SPRINT")
                assertEquals(160, assertNotNull(atCap.split).buckets.size)
            }
        }
}
