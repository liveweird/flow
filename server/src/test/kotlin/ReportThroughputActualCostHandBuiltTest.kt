package ch.nokillswit

import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ThroughputReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Throughput's ACTUAL COST (`actualCostMd`, `.claude/docs/reports.md` "Report 2 -- Throughput") on a HAND-BUILT world
 * ([withActualCostWorld]: its layout and the placement of every worklog are documented there), so each rule has a literal answer:
 * a worklog outside the bucket is excluded; an in-progress (undelivered) item's in-bucket time is included; an item done before the
 * bucket is excluded; a sub-task's worklog counts toward its task; an item running across two buckets is split by log date; the slice
 * (team, activity type) selects the ITEMS; USER level counts the picked AUTHORS' time; the sprint view takes the FINAL scope's time between
 * the sprint's dates and ignores the slice filters; and the drill (`measure=ACTUAL_COST`) reconciles with every figure.
 */
class ReportThroughputActualCostHandBuiltTest {

    private suspend fun HttpClient.throughput(query: String): ThroughputReport {
        val response = get("/api/v1/reports/throughput?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/throughput/items?$query&measure=ACTUAL_COST")
        assertEquals(HttpStatusCode.OK, response.status, "GET throughput/items?$query")
        return response.body()
    }

    private fun ThroughputReport.weeks() = byBucket.associate { it.bucketStart to it.actualCostMd }

    @Test
    fun `the weekly actual cost follows delivered and in-progress items and splits a running item by log date`() = testApplication {
        usePostgresTestcontainer()
        withActualCostWorld { w ->
            val client = seededClient("reports-throughput-ac-weeks")
            // TEAM level: week Jan 5: A delivered (1 + 0.5 + 0.5 unknown author + 0.25 after done) + B in progress (1.5). Out: the 9 on C
            // (logged before C started), the 7 on D (done before the range), E (never started), F (the other team's), issue 9999 (no
            // task). Week Jan 12: A is done before the week (its 2 is out), B delivered (2 + the sub-task's 0.75), C in progress (1).
            // Week Jan 19: only C is still running (3).
            val team = client.throughput("${w.range}&teamId=${w.team}")
            assertEquals(mapOf("2026-01-05" to 3.75, "2026-01-12" to 3.75, "2026-01-19" to 3.0), team.weeks())
            // UNIT level adds the other team's delivery (F's 4) in its week.
            val unit = client.throughput(w.range)
            assertEquals(mapOf("2026-01-05" to 7.75, "2026-01-12" to 3.75, "2026-01-19" to 3.0), unit.weeks())
            assertTrue(team.byBucket.all { it.byUser.isEmpty() }, "no per-person split below USER level")
            // Delivered is unchanged by the new figure: A delivered in week 1, B in week 2, nothing in week 3.
            assertEquals(listOf(1, 1, 0), team.byBucket.map { it.deliveredItems })
        }
    }

    @Test
    fun `a month bucket takes everything logged in the month on its items, and the range clips the first bucket like Delivered`() =
        testApplication {
            usePostgresTestcontainer()
            withActualCostWorld { w ->
                val client = seededClient("reports-throughput-ac-month")
                // January: A (1 + 0.5 + 0.5 + 0.25 + 2) + B (1.5 + 2 + 0.75) + C (9 + 1 + 3); D done before the range, E not started.
                val month = client.throughput("${w.range}&teamId=${w.team}&bucket=MONTH")
                assertEquals(listOf(21.5), month.byBucket.map { it.actualCostMd })
                // The range starts on Wednesday Jan 7: the first week is clipped like Delivered, so what was logged on Jan 6 is out.
                val clipped = client.throughput("connectionId=${w.connId}&from=2026-01-07&to=2026-01-25&teamId=${w.team}")
                val firstWeek = clipped.byBucket.first().actualCostMd
                assertEquals(2.75, firstWeek, 1e-9, "A: 0.5 + 0.5 + 0.25; B: 1.5; Jan 6's 1.0 is before the range")
            }
        }

    @Test
    fun `the activity-type slice selects the items, so only the open Bug counts, and domain and category slices apply to the items too`() =
        testApplication {
            usePostgresTestcontainer()
            withActualCostWorld { w ->
                val client = seededClient("reports-throughput-ac-slice")
                val bug = client.throughput("${w.range}&teamId=${w.team}&activityType=Bug")
                assertEquals(mapOf("2026-01-05" to 0.0, "2026-01-12" to 1.0, "2026-01-19" to 3.0), bug.weeks())
                val story = client.throughput("${w.range}&teamId=${w.team}&activityType=Story")
                assertEquals(mapOf("2026-01-05" to 3.75, "2026-01-12" to 2.75, "2026-01-19" to 0.0), story.weeks())
                // Domain AAA holds every item; a domain nobody has, and an uncategorized-only filter, behave like Delivered's.
                assertEquals(3.75, client.throughput("${w.range}&teamId=${w.team}&domain=AAA").byBucket.first().actualCostMd, 1e-9)
                assertEquals(0.0, client.throughput("${w.range}&teamId=${w.team}&domain=ZZZ").byBucket.sumOf { it.actualCostMd }, 1e-9)
                val uncategorized = client.throughput("${w.range}&teamId=${w.team}&workCategory=UNCATEGORIZED")
                assertEquals(10.5, uncategorized.byBucket.sumOf { it.actualCostMd }, 1e-9)
            }
        }

    @Test
    fun `at USER level the picked authors' own time is counted on the team's items and split per person`() = testApplication {
        usePostgresTestcontainer()
        withActualCostWorld { w ->
            val client = seededClient("reports-throughput-ac-user")
            val both = client.throughput("${w.range}&teamId=${w.team}&accountId=${w.alice}&accountId=${w.bob}")
            // Week Jan 5: alice 1 + 0.25 + 1.5, bob 0.5 (the unknown author's 0.5 is nobody's). Week Jan 12: alice 0.75, bob 2 + 1.
            assertEquals(listOf(3.25, 3.75, 3.0), both.byBucket.map { it.actualCostMd })
            val shares = both.byBucket.map { row -> row.byUser.associate { it.accountId to it.actualCostMd } }
            assertEquals(listOf(2.75, 0.75, 0.0), shares.map { it.getValue(w.alice) })
            assertEquals(listOf(0.5, 3.0, 3.0), shares.map { it.getValue(w.bob) })
            // Alice alone: her time on ALL of the team's items, not only those assigned to her (Delivered: by assignee, AC: by author).
            val alice = client.throughput("${w.range}&teamId=${w.team}&accountId=${w.alice}")
            assertEquals(listOf(2.75, 0.75, 0.0), alice.byBucket.map { it.actualCostMd })
            assertEquals(listOf(2.75, 0.75, 0.0), alice.byBucket.map { it.byUser.single().actualCostMd })
            // A person who logged nothing is a zero entry.
            val nobody = client.throughput("${w.range}&teamId=${w.team}&accountId=nobody")
            assertEquals(listOf(0.0, 0.0, 0.0), nobody.byBucket.map { it.actualCostMd })
            assertEquals(listOf(0.0, 0.0, 0.0), nobody.byBucket.map { it.byUser.single().actualCostMd })
        }
    }

    @Test
    fun `the sprint view takes the final scope's time between the sprint's dates, ignores the slice and counts nobody twice`() =
        testApplication {
            usePostgresTestcontainer()
            withActualCostWorld { w ->
                val client = seededClient("reports-throughput-ac-sprint")
                val query = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${w.sprintId}"
                // A 4.25 (1 + 0.5 + 0.5 + 0.25 + 2), B 4.25 (1.5 + 2 + the sub-task's 0.75), C 10 (9 + 1; the 3 on Jan 20 is after the
                // sprint ended). R was removed from the sprint (its 6 counts for no sprint), E and F and issue 9999 are in no scope.
                val sprint = client.throughput(query).bySprint.single()
                assertEquals(18.5, sprint.actualCostMd, 1e-9)
                assertEquals(2, sprint.deliveredItems, "A and B were delivered in the sprint: the cost covers the undelivered C as well")
                assertNull(sprint.snapshot, "AC is live: the frozen snapshot carries none")
                // The sprint view carries no slice, exactly as Delivered.
                val sliced = client.throughput("$query&activityType=Bug&domain=ZZZ&workCategory=Nothing").bySprint.single()
                assertEquals(18.5, sliced.actualCostMd, 1e-9)
                assertEquals(sprint.deliveredMd, sliced.deliveredMd, 1e-9)
                // USER level: the picked authors' own time on the scope: alice 3.25 + 2.25 + 9 and bob 0.5 + 2 + 1.
                val users = client.throughput("$query&accountId=${w.alice}&accountId=${w.bob}").bySprint.single()
                assertEquals(listOf(14.5, 3.5), users.byUser.map { it.actualCostMd })
                assertEquals(18.0, users.actualCostMd, 1e-9)
                // An ACTIVE sprint ends now: C's 3 on Jan 20 is inside [Jan 15, now) and its 1 on Jan 14 is before it. A sprint that
                // has not started has an empty window: nothing is logged in it.
                val open = client.throughput("connectionId=${w.connId}&teamId=${w.team}&sprintId=${w.openSprintId}").bySprint.single()
                assertEquals(3.0, open.actualCostMd, 1e-9)
                assertNull(open.completedAt)
                val future = client.throughput("connectionId=${w.connId}&teamId=${w.team}&sprintId=${w.futureSprintId}").bySprint.single()
                assertEquals(0.0, future.actualCostMd, 1e-9)
            }
        }

    @Test
    fun `the period drill lists the tasks behind a bar and reconciles with the figure at every level`() = testApplication {
        usePostgresTestcontainer()
        withActualCostWorld { w ->
            val client = seededClient("reports-throughput-ac-drill")
            val team = "${w.range}&teamId=${w.team}"
            val week2 = client.items("$team&bucketStart=2026-01-12")
            assertEquals(2, week2.bar.count)
            assertEquals(3.75, week2.bar.sumMd!!, 1e-9)
            // B first (2.75: bob 2 + alice's 0.75 on the sub-task, so two authors, largest first), then the open C (1.0, one author).
            assertEquals(listOf("102", "104"), week2.items.map { it.issueKey }, "keys fall back to the issue id")
            assertEquals(listOf(2.75, 1.0), week2.items.map { it.figure })
            assertEquals(listOf("${w.bob}, ${w.alice}", w.bob), week2.items.map { it.assignee })
            assertEquals(listOf(null, w.bob), week2.items.map { it.accountId })
            assertEquals(listOf(noon("2026-01-14"), noon("2026-01-14")), week2.items.map { it.anchorAt })
            assertTrue(week2.items.all { it.itemKind.name == "TASK" && it.teamId == null })
            assertEquals(week2.bar.count, week2.total)
            assertEquals(false, week2.truncated)
            // Every bar equals the report's actualCostMd.
            val report = client.throughput(team)
            for (row in report.byBucket) {
                val drill = client.items("$team&bucketStart=${row.bucketStart}")
                assertEquals(row.actualCostMd, drill.bar.sumMd!!, 1e-9, "bar ${row.bucketStart}")
                assertEquals(row.actualCostMd, drill.items.sumOf { it.figure!! }, 0.005, "Σ figure ${row.bucketStart}")
            }
            // USER level: the segments are the byUser shares and add up to the union.
            val picks = "$team&accountId=${w.alice}&accountId=${w.bob}"
            val users = client.throughput(picks)
            for (row in users.byBucket) {
                val union = client.items("$picks&bucketStart=${row.bucketStart}")
                assertEquals(row.actualCostMd, union.bar.sumMd!!, 1e-9, "union ${row.bucketStart}")
                for (share in row.byUser) {
                    val segment = client.items("$picks&bucketStart=${row.bucketStart}&segmentAccountId=${share.accountId}")
                    assertEquals(share.actualCostMd, segment.bar.sumMd!!, 1e-9, "${share.accountId} ${row.bucketStart}")
                    assertTrue(segment.items.all { it.accountId == share.accountId }, "a segment lists only its author's time")
                }
            }
            // UNIT level includes the other team's delivery.
            assertEquals(7.75, client.items("${w.range}&bucketStart=2026-01-05").bar.sumMd!!, 1e-9)
            // An empty bucket answers 200 and nothing.
            assertEquals(0, client.items("$team&bucketStart=2026-01-12&activityType=Nothing").bar.count)
        }
    }

    @Test
    fun `the sprint drill lists the final scope's tasks and equals bySprint and its byUser shares`() = testApplication {
        usePostgresTestcontainer()
        withActualCostWorld { w ->
            val client = seededClient("reports-throughput-ac-sprint-drill")
            val query = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${w.sprintId}"
            val drill = client.items(query)
            assertEquals(3, drill.bar.count)
            assertEquals(18.5, drill.bar.sumMd!!, 1e-9)
            assertEquals(listOf(10.0, 4.25, 4.25), drill.items.map { it.figure })
            val picks = "$query&accountId=${w.alice}&accountId=${w.bob}"
            val report = client.throughput(picks).bySprint.single()
            val union = client.items(picks)
            assertEquals(report.actualCostMd, union.bar.sumMd!!, 1e-9)
            for (share in report.byUser) {
                val segment = client.items("$picks&segmentAccountId=${share.accountId}")
                assertEquals(share.actualCostMd, segment.bar.sumMd!!, 1e-9, share.accountId)
                assertEquals(segment.bar.count, segment.items.size)
            }
            // The same drill without the team pin is the union of every team's sprint of that id: here the one.
            assertEquals(18.5, client.items("connectionId=${w.connId}&sprintId=${w.sprintId}").bar.sumMd!!, 1e-9)
        }
    }

    @Test
    fun `the actual-cost drill rejects what its bar cannot answer with a 400`() = testApplication {
        usePostgresTestcontainer()
        withActualCostWorld { w ->
            val client = seededClient("reports-throughput-ac-400")
            val team = "${w.range}&teamId=${w.team}"
            fun path(query: String) = "/api/v1/reports/throughput/items?$query"
            assertEquals(HttpStatusCode.OK, client.get(path("$team&bucketStart=2026-01-05&measure=DELIVERED")).status)
            assertEquals(HttpStatusCode.BadRequest, client.get(path("$team&bucketStart=2026-01-05&measure=NONSENSE")).status)
            assertEquals(HttpStatusCode.BadRequest, client.get(path("$team&measure=ACTUAL_COST")).status, "no bar named")
            val tuesday = client.get(path("$team&bucketStart=2026-01-06&measure=ACTUAL_COST"))
            assertEquals(HttpStatusCode.BadRequest, tuesday.status, "a Tuesday")
            assertEquals(HttpStatusCode.BadRequest, client.get(path("$team&bucketStart=2025-12-29&measure=ACTUAL_COST")).status, "before")
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get(path("$team&bucketStart=2026-01-05&measure=ACTUAL_COST&segmentAccountId=${w.alice}")).status,
                "a segment needs USER level",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get(path("$team&accountId=${w.alice}&bucketStart=2026-01-05&measure=ACTUAL_COST&segmentAccountId=${w.bob}")).status,
                "a stranger to the picks",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get(path("$team&bucketStart=2026-01-05&measure=ACTUAL_COST&epicSegment=NO_EPIC")).status,
                "epicSegment belongs to the delivered bars",
            )
            val sprint = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${w.sprintId}"
            val epic = client.get(path("$sprint&measure=ACTUAL_COST&epicSegment=NO_EPIC"))
            assertEquals(HttpStatusCode.BadRequest, epic.status, "no epic split of cost")
        }
    }
}
