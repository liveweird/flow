package ch.nokillswit

import ch.nokillswit.reports.CommitmentsReport
import ch.nokillswit.reports.CostsReport
import ch.nokillswit.reports.CostsSprint
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.ReportDrillItems
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
 * Costs' SPRINT widgets (`sprints[]`, `outsideSprintsMd`, domain-model A44) and their drill on the HAND-BUILT world
 * ([withCostsSprintsWorld]: the layout and the placement of every worklog are documented there), so each rule has a literal answer:
 * Commitments' sprint selection, the half-open sprint window (an abutting boundary instant belongs to the later sprint), a worklog inside
 * two overlapping sprints counted in both, non-members excluded, a sub-task / an epic-logged / an above-epic worklog on the current-epic
 * split with its top-6 fold, the slices and USER level, capacity for every viewer, the team-less levels, and `bar == chart` for the whole
 * bar and every segment. The derived fixture is graded in `ReportCostsSprintsFixtureTest`.
 */
class ReportCostsSprintsTest {

    private val epic = CostsSprintsWorld.EPIC_BASE

    private suspend fun HttpClient.costs(query: String): CostsReport {
        val response = get("/api/v1/reports/costs?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET costs?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/costs/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET costs/items?$query")
        return response.body()
    }

    private suspend fun HttpClient.status(query: String): HttpStatusCode = get("/api/v1/reports/costs/items?$query").status

    private fun CostsReport.sprint(id: Long): CostsSprint = sprints.single { it.sprintId == id }

    private fun CostsSprint.segments() = byEpic.map { it.key to it.acMd }

    @Test
    fun `the team's closed sprints carry their AC, a boundary instant goes to the later sprint and an overlap counts in both`() =
        testApplication {
            usePostgresTestcontainer()
            withCostsSprintsWorld { w ->
                val client = seededClient("cs-ac")
                val report = client.costs("${w.range}&teamId=${w.team}")
                assertEquals(listOf(CostsSprintsWorld.S1, CostsSprintsWorld.S2, CostsSprintsWorld.S3), report.sprints.map { it.sprintId })
                // S1: every T member's time Jan 5 .. Jan 19 (carol's 8 and dave's 6 are not T's; b1 at exactly Jan 19 00:00Z is S2's).
                assertEquals(15.5, report.sprint(CostsSprintsWorld.S1).acMd)
                // S2 [Jan 19, Feb 2): b1 + b2 + b3 + b4 and the two it shares with S3 (b5, b6).
                assertEquals(4.75, report.sprint(CostsSprintsWorld.S2).acMd)
                // S3 [Jan 28, Feb 6): b5, b6 (also in S2), c1, c2.
                assertEquals(4.25, report.sprint(CostsSprintsWorld.S3).acMd)
                // Σ AC counts the 1.0 of b5 + b6 twice; the time in no listed sprint is o1 + o2 + o3 + d1 (S4 is open: not listed).
                assertEquals(4.75, report.outsideSprintsMd)
                assertEquals(28.25, report.totalMd, "23.5 in the listed sprints (once) + 4.75 outside; x1 (December) is out")
                val s1 = report.sprint(CostsSprintsWorld.S1)
                assertEquals(w.team, s1.teamId)
                assertEquals(w.connId, s1.connectionId)
                assertEquals(utc("2026-01-05T00:00:00Z"), s1.startAt)
                assertEquals(utc("2026-01-19T00:00:00Z"), s1.completedAt)
                // The sprints are exactly Commitments' for the same request.
                val commitments: CommitmentsReport = client.get("/api/v1/reports/commitments?${w.range}&teamId=${w.team}").body()
                assertEquals(commitments.sprints.map { it.sprintId }.toSet(), report.sprints.map { it.sprintId }.toSet())
            }
        }

    @Test
    fun `each sprint splits by current epic - a sub-task takes its parent's epic, an epic-logged worklog its own, top 6 fold`() =
        testApplication {
            usePostgresTestcontainer()
            withCostsSprintsWorld { w ->
                val report = seededClient("cs-epics").costs("${w.range}&teamId=${w.team}")
                fun e(n: Int) = (epic + n).toString()
                // Ranked over the three listed sprints by Σ AC: E1 5.75, E2 5.5, E4 4.5, E3 3.0, E7 1.5, E8 1.25 | E5 1.0, E6 0.75.
                assertEquals(
                    listOf(
                        e(1) to 5.0, // 101 (4.0) + the sub-task 109 (1.0): the sub-task's row carries its parent task's epic
                        e(2) to 3.5, // 102 (3.0) + the worklog logged ON epic E2 itself (0.5)
                        e(4) to 1.5, e(3) to 2.0, e(7) to 0.5, e(8) to 0.25,
                        "OTHER_EPICS" to 1.75, // E5 (1.0) + E6 (0.75)
                        "NO_EPIC" to 1.0, // task 110 (0.75) + the issue above the epic level (0.25)
                    ),
                    report.sprint(CostsSprintsWorld.S1).segments(),
                )
                assertEquals(
                    listOf(e(1) to 0.75, e(2) to 2.0, e(3) to 0.5, e(7) to 1.0, e(8) to 0.5),
                    report.sprint(CostsSprintsWorld.S2).segments(),
                    "segments with nothing logged are omitted, the order is the same in every sprint",
                )
                assertEquals(
                    listOf(e(4) to 3.0, e(3) to 0.5, e(8) to 0.5, "NO_EPIC" to 0.25), report.sprint(CostsSprintsWorld.S3).segments(),
                )
                for (sprint in report.sprints) {
                    assertEquals(sprint.acMd, sprint.byEpic.sumOf { it.acMd }, 1e-9, "Σ segments == AC of ${sprint.sprintId}")
                }
                val first = report.sprint(CostsSprintsWorld.S1).byEpic.first()
                assertEquals(epic + 1, first.epicId)
                assertEquals("CST-1", first.epicKey)
                assertEquals("CST-1 Epic 1", first.label)
                val other = report.sprint(CostsSprintsWorld.S1).byEpic.single { it.key == "OTHER_EPICS" }
                assertNull(other.epicId)
                assertNull(other.epicKey)
                assertEquals("Other epics", other.label)
            }
        }

    @Test
    fun `the slices and the USER level narrow every sprint and the outside figure alike`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val client = seededClient("cs-slices")
            val team = "${w.range}&teamId=${w.team}"
            // Bug: only a3 (S1, 3.0) and o2 (outside, 2.0).
            val bug = client.costs("$team&activityType=Bug")
            assertEquals(listOf(3.0, 0.0, 0.0), bug.sprints.map { it.acMd })
            assertEquals(2.0, bug.outsideSprintsMd)
            assertEquals(5.0, bug.totalMd)
            assertTrue(bug.sprint(CostsSprintsWorld.S2).byEpic.isEmpty(), "a sprint with no AC has no segment")
            // One person (USER level): alice's own time; capacity is the team's, so it is hidden.
            val alice = client.costs("$team&accountId=${w.alice}")
            assertEquals(listOf(11.25, 1.75, 3.0), alice.sprints.map { it.acMd })
            assertEquals(4.25, alice.outsideSprintsMd, "o1 + o2 + d1")
            assertEquals(20.25, alice.totalMd)
            assertTrue(alice.sprints.all { it.capacityMd == null && it.capacitySource == null }, "no capacity at USER level")
            // The domain slice: every worklog is domain AAA, so BBB selects nothing.
            val none = client.costs("$team&domain=BBB")
            assertEquals(listOf(0.0, 0.0, 0.0), none.sprints.map { it.acMd })
            assertEquals(0.0, none.outsideSprintsMd)
        }
    }

    @Test
    fun `a regular user sees every sprint's capacity, null where the sprint has none`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val report = seededClient("cs-capacity").costs("${w.range}&teamId=${w.team}")
            assertEquals(20.0 to "CALENDAR", report.sprint(CostsSprintsWorld.S1).let { it.capacityMd to it.capacitySource })
            assertEquals(18.0 to "DEFAULT", report.sprint(CostsSprintsWorld.S2).let { it.capacityMd to it.capacitySource })
            assertEquals(null to null, report.sprint(CostsSprintsWorld.S3).let { it.capacityMd to it.capacitySource })
        }
    }

    @Test
    fun `without a real team there are no sprint widgets, and a team with no listed sprint has all its time outside`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val client = seededClient("cs-levels")
            val unit = client.costs(w.range)
            assertTrue(unit.sprints.isEmpty())
            assertNull(unit.outsideSprintsMd)
            val unassigned = client.costs("${w.range}&teamId=0")
            assertTrue(unassigned.sprints.isEmpty())
            assertNull(unassigned.outsideSprintsMd)
            // Carol's team owns no sprint here: its 8.0 is the whole period, outside.
            val other = client.costs("${w.range}&teamId=${w.otherTeam}")
            assertTrue(other.sprints.isEmpty())
            assertEquals(8.0, other.outsideSprintsMd)
            assertEquals(other.totalMd, other.outsideSprintsMd)
        }
    }

    @Test
    fun `sprint-relative periods list their resolved sprints, an open one only by its id and running to now`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val client = seededClient("cs-periods")
            val base = "connectionId=${w.connId}&teamId=${w.team}"
            // The last two closed sprints (S2, S3): their envelope has no gap, so nothing is outside; the period is Jan 19 .. Feb 6.
            val last = client.costs("$base&lastSprints=2")
            assertEquals(listOf(CostsSprintsWorld.S2, CostsSprintsWorld.S3), last.sprints.map { it.sprintId })
            assertEquals(0.0, last.outsideSprintsMd)
            assertEquals(8.0, last.totalMd, "S2's 4.75 + S3's own 3.25")
            // An open sprint is listed by an explicit sprintId only; it has no completedAt and its AC runs to now.
            val open = client.costs("$base&sprintId=${CostsSprintsWorld.S4}")
            val s4 = open.sprints.single()
            assertEquals(CostsSprintsWorld.S4, s4.sprintId)
            assertNull(s4.completedAt)
            assertEquals(1.75, s4.acMd, "d1 (Feb 11) + o3 (Feb 20)")
            assertEquals(listOf(epic + 1, epic + 3), s4.byEpic.map { it.epicId })
            assertEquals(0.0, open.outsideSprintsMd)
            assertTrue(client.costs("${w.range}&teamId=${w.team}").sprints.none { it.sprintId == CostsSprintsWorld.S4 })
        }
    }

    @Test
    fun `the whole-bar drill lists the sprint's tasks and its bar equals the chart, overlaps included`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val client = seededClient("cs-drill")
            val report = client.costs("${w.range}&teamId=${w.team}")
            for (sprint in report.sprints) {
                val drill = client.items("connectionId=${w.connId}&teamId=${w.team}&sprintId=${sprint.sprintId}")
                assertEquals(sprint.acMd, drill.bar.sumMd, "bar == chart for ${sprint.sprintId}")
                assertEquals(drill.bar.count, drill.total)
                assertEquals(drill.items.sumOf { it.figure!! }, drill.bar.sumMd!!, 0.011)
            }
            val s1 = client.items("connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S1}")
            // 11 items: the sub-task rolled up into task 101 (4.0 + 1.0), the epic-logged worklog listed as the EPIC, 9999 as a TASK.
            assertEquals(11, s1.bar.count)
            assertEquals(5.0, s1.items.first().figure, "task 101 with its sub-task, the largest")
            assertTrue(s1.items.first().assignee!!.contains(w.alice) && s1.items.first().assignee!!.contains(w.bob))
            val epicItem = s1.items.single { it.itemKind == DrillItemKind.EPIC }
            assertEquals(0.5, epicItem.figure)
            assertEquals("CST-2", epicItem.issueKey)
            // The open sprint and the slice filters follow the chart too.
            val open = client.items("connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S4}")
            assertEquals(1.75, open.bar.sumMd)
            val bug = client.items("connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S1}&activityType=Bug")
            assertEquals(3.0, bug.bar.sumMd)
            val alice = client.items("connectionId=${w.connId}&teamId=${w.team}&accountId=${w.alice}&sprintId=${CostsSprintsWorld.S1}")
            assertEquals(11.25, alice.bar.sumMd)
        }
    }

    @Test
    fun `every epic segment's drill bar equals the segment - an epic, OTHER_EPICS with the chart's top epics, and NO_EPIC`() =
        testApplication {
            usePostgresTestcontainer()
            withCostsSprintsWorld { w ->
                val client = seededClient("cs-drill-segments")
                val report = client.costs("${w.range}&teamId=${w.team}")
                val tops = report.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.distinct()
                assertEquals(6, tops.size)
                var drilled = 0
                for (sprint in report.sprints) {
                    val one = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${sprint.sprintId}&by=EPIC"
                    for (segment in sprint.byEpic) {
                        val topIds = tops.joinToString("") { "&topEpicId=$it" }
                        val narrow = "epicSegment=${segment.key}" + if (segment.key == "OTHER_EPICS") topIds else ""
                        val drill = client.items("$one&$narrow")
                        assertEquals(segment.acMd, drill.bar.sumMd, "${sprint.sprintId}/${segment.key}")
                        drilled++
                    }
                }
                assertEquals(17, drilled)
                val s1 = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S1}&by=EPIC"
                assertEquals(1, client.items("$s1&epicSegment=${epic + 1}").bar.count, "E1: task 101 with its sub-task rolled up")
                assertEquals(2, client.items("$s1&epicSegment=${epic + 2}").bar.count, "E2: task 102 and the epic itself")
                assertEquals(2, client.items("$s1&epicSegment=NO_EPIC").bar.count)
                val other = client.items("$s1&epicSegment=OTHER_EPICS" + tops.joinToString("") { "&topEpicId=$it" })
                assertEquals(1.75, other.bar.sumMd)
                assertEquals(2, other.bar.count)
                // An epic nobody logged on in the sprint lists nothing (E5 has time in S1 only).
                val s2 = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S2}&by=EPIC"
                assertEquals(0, client.items("$s2&epicSegment=${epic + 5}").bar.count)
            }
        }

    @Test
    fun `the drill 400s - the shape, the combination of by, key and the epic parameters, and the sprint bar's scope`() = testApplication {
        usePostgresTestcontainer()
        withCostsSprintsWorld { w ->
            val client = seededClient("cs-drill-400")
            val sprint = "connectionId=${w.connId}&teamId=${w.team}&sprintId=${CostsSprintsWorld.S1}"
            val bad = HttpStatusCode.BadRequest
            assertEquals(HttpStatusCode.OK, client.status(sprint), "the whole bar")
            assertEquals(HttpStatusCode.OK, client.status("$sprint&by=EPIC&epicSegment=NO_EPIC"), "an epic segment")
            assertEquals(HttpStatusCode.OK, client.status("$sprint&by=ACTIVITY_TYPE&key=Story"), "a donut slice inside a sprint period")
            assertEquals(bad, client.status("$sprint&by=EPIC"), "by=EPIC needs epicSegment")
            assertEquals(bad, client.status("$sprint&epicSegment=NO_EPIC"), "epicSegment needs by=EPIC")
            assertEquals(bad, client.status("$sprint&by=EPIC&epicSegment=NO_EPIC&key=Story"), "key belongs to a donut slice")
            assertEquals(bad, client.status("$sprint&key=Story"), "key without by")
            assertEquals(bad, client.status("$sprint&by=ACTIVITY_TYPE&key=Story&epicSegment=NO_EPIC"), "epicSegment belongs to by=EPIC")
            assertEquals(bad, client.status("$sprint&by=ACTIVITY_TYPE"), "a donut slice needs a key")
            assertEquals(bad, client.status("$sprint&by="), "a blank by is not the whole bar")
            assertEquals(bad, client.status("$sprint&by=DOMAIN"), "an unknown by")
            assertEquals(bad, client.status("$sprint&by=EPIC&epicSegment=OTHER_EPICS"), "OTHER_EPICS needs the top epics")
            assertEquals(bad, client.status("$sprint&by=EPIC&epicSegment=NO_EPIC&topEpicId=1"), "topEpicId belongs to OTHER_EPICS")
            assertEquals(bad, client.status("$sprint&by=EPIC&epicSegment=abc"), "a malformed segment")
            assertEquals(bad, client.status("$sprint&topEpicId=1"), "topEpicId without a segment")
            // The sprint bar needs ONE sprint of a TEAM.
            assertEquals(bad, client.status("${w.range}&teamId=${w.team}"), "a date range names no sprint")
            assertEquals(bad, client.status("connectionId=${w.connId}&teamId=${w.team}&lastSprints=2"), "lastSprints names no sprint")
            assertEquals(bad, client.status("connectionId=${w.connId}&sprintId=${CostsSprintsWorld.S1}"), "no team")
            assertEquals(bad, client.status("connectionId=${w.connId}&teamId=0&sprintId=${CostsSprintsWorld.S1}"), "the UNASSIGNED team")
            assertEquals(bad, client.status("connectionId=${w.connId}&teamId=${w.team}&sprintId=999999999"), "an unknown sprint")
            // Another team's request for this sprint lists nothing (the selector narrows to the team's own sprints).
            assertEquals(0, client.items("connectionId=${w.connId}&teamId=${w.otherTeam}&sprintId=${CostsSprintsWorld.S1}").bar.count)
        }
    }
}
