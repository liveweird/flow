package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.sprintTotals
import ch.nokillswit.reports.CommitmentsEpicSegment
import ch.nokillswit.reports.CommitmentsReport
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ThroughputEpicSegment
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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The by-epic split of the sprint charts on a HAND-BUILT world, so the ranking is known to the cent: two closed sprints of one team over
 * nine epics (a tie for the last top-6 place, an epic whose only item was removed, an item added mid-sprint, items without an estimate
 * and without an epic). Pins, for Commitments and Throughput at TEAM and USER level: the segments' literal figures, the top-6 /
 * `OTHER_EPICS` / `NO_EPIC` fold with its tie-breaks (each report ranks by its OWN measure), the one segment order in every sprint, that
 * the segments add up to the row, and that the sprint-scope drill's `epicSegment` bar equals the segment's figure for every kind
 * (an epic, `OTHER_EPICS` with the chart's top ids, `NO_EPIC`) — plus the drill's `400`s. A disabled connection, so no worker
 * re-derives it.
 */
class ReportSprintEpicSegmentsHandBuiltTest {

    private val s1 = 920_101L
    private val s2 = 920_102L
    private val s1Closed = 1_770_724_800_000L // 2026-02-10T12:00:00Z
    private val s2Closed = s1Closed + FORTNIGHT_MS
    private val ann = "acc-epic-ann"
    private val bob = "acc-epic-bob"

    private fun item(
        issue: Long,
        assignee: String,
        commit: Double?,
        close: Double?,
        done: Double? = null,
        added: Boolean = false,
        removed: Boolean = false,
    ) = SprintScopeItem(
        issueId = issue, addedAtMs = if (added) 1L else null, removedAtMs = if (removed) 2L else null,
        committed = !added, inScopeAtClose = !removed,
        estimateAtCommitmentMd = commit, estimateAtCloseMd = if (removed) null else close, estimateAtDoneMd = done,
        assigneeAtCommitment = assignee, doneInSprint = done != null && !removed, carriedOver = false, dropped = false,
    )

    /** Issue ids 1xx are the task ids too; the epic of each task is [epicOf]. */
    private val sprint1 = listOf(
        item(101, ann, 5.0, 5.0, done = 5.0), item(102, bob, 3.0, 3.0), // epic 7001
        item(103, ann, 4.0, 4.0, done = 4.0), // 7002
        item(104, bob, 3.0, 3.0, done = 3.0), // 7003
        item(105, ann, 2.0, 2.0), // 7004, not done
        item(106, ann, 2.0, 2.0, done = 2.0), // 7005
        item(107, bob, 2.0, 3.0, done = 3.0), // 7006, re-estimated 2 -> 3
        item(108, ann, 3.0, 3.0, done = 3.0), // 7007
        item(109, ann, 1.0, 1.0, done = 1.0), // no epic
        item(110, bob, null, null), // no epic, unestimated
        item(111, bob, 9.0, null, removed = true), // 7009, removed: in no figure, no segment
        item(112, ann, 3.0, 3.0, done = 3.0), // 7008
    )
    private val sprint2 = listOf(
        item(113, ann, 5.0, 5.0, done = 5.0), // 7001
        item(114, bob, 5.0, 5.0, done = 5.0), // 7002
        item(115, ann, 5.0, 5.0, done = 5.0), // 7003
        item(116, bob, 5.0, 5.0, done = 5.0), // 7004
        item(117, ann, 4.0, 4.0, done = 4.0), // 7005
        item(118, bob, 2.0, 2.0, done = 2.0), // no epic
        item(119, bob, null, 1.0, done = 1.0, added = true), // 7001, added mid-sprint
    )

    private val epicOf = mapOf(
        101L to 7001L, 102L to 7001L, 103L to 7002L, 104L to 7003L, 105L to 7004L, 106L to 7005L, 107L to 7006L, 108L to 7007L,
        109L to null, 110L to null, 111L to 7009L, 112L to 7008L,
        113L to 7001L, 114L to 7002L, 115L to 7003L, 116L to 7004L, 117L to 7005L, 118L to null, 119L to 7001L,
    )

    private fun tasks() = epicOf.map { (issue, epic) ->
        DimTaskRow(issue, "EPX-T$issue", "Story", "Development", "Run", "NONE", false, null, "AAA", epic)
    }

    private fun epics() = (7001L..7009L).map { DimEpicRow(it, "EPX-$it", "Epic $it", "AAA", "Run", "IN_PROGRESS", null, null) }

    private suspend fun MetricsStore.seedSprint(connId: UInt, teamId: UInt, sprintId: Long, closedAt: Long, scope: List<SprintScopeItem>) {
        val startAt = closedAt - FORTNIGHT_MS
        val dim = DimSprintRow(sprintId, sprintId, teamId, "Sprint $sprintId", "closed", startAt, closedAt, closedAt, null, null)
        insertDimSprints(connId, listOf(dim), configRevision = 1L)
        insertFactSprint(connId, listOf(FactSprintRow(sprintId, teamId, closedAt, sprintTotals(scope), capacityMd = null, load = null)), 1L)
        insertFactSprintScope(connId, scope.map { FactSprintScopeRow(sprintId, it) }, 1L)
    }

    private class SeededSprint(val id: Long, val closedAt: Long, val scope: List<SprintScopeItem>)

    private suspend fun withWorld(
        taskRows: List<DimTaskRow> = tasks(),
        epicRows: List<DimEpicRow> = epics(),
        sprints: List<SeededSprint> = listOf(SeededSprint(s1, s1Closed, sprint1), SeededSprint(s2, s2Closed, sprint2)),
        block: suspend (connId: UInt, teamId: UInt) -> Unit,
    ) {
        val connId = SyncedStubFixture.createConnection(namePrefix = "sprint-epics", enabled = false)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("sprint-epics-team"))
        val store = MetricsStore(sharedDatabaseForTests())
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertTasks(connId, taskRows, configRevision = 1L)
            store.insertEpics(connId, epicRows, configRevision = 1L)
            sprints.forEach { store.seedSprint(connId, teamId, it.id, it.closedAt, it.scope) }
        }
        try {
            block(connId, teamId)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteSprintFacts(connId)
                store.deleteDims(connId)
            }
            cleanUpTeams(listOf(teamId))
        }
    }

    private suspend inline fun <reified T> HttpClient.report(path: String): T {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, "GET $path")
        return response.body()
    }

    private fun CommitmentsEpicSegment.figures() = listOf(initialMd, initialItems.toDouble(), finalMd, finalItems.toDouble())

    private fun ThroughputEpicSegment.figures() = listOf(deliveredMd, deliveredItems.toDouble())

    @Test
    fun `commitments split each sprint by current epic, ranking by final MD over the whole response`() = testApplication {
        usePostgresTestcontainer()
        withWorld { connId, teamId ->
            val client = seededClient("reports-epic-seg-commit")
            val report = client.report<CommitmentsReport>(
                "/api/v1/reports/commitments?connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01",
            )
            val (first, second) = report.sprints.sortedBy { it.sprintId }
            // Rank by Σ final MD: 7001 14, 7002 9, 7003 8, 7004 7, 7005 6, then a three-way tie at 3 (7006, 7007, 7008) -> initial MD
            // (7006 has 2, 7007 and 7008 have 3) -> the tie between 7007 and 7008 falls to the epic key: 7007 takes the sixth place,
            // 7006 and 7008 fold.
            assertEquals(
                listOf("7001", "7002", "7003", "7004", "7005", "7007", "OTHER_EPICS", "NO_EPIC"), first.byEpic.map { it.key },
                "sprint 1 segments in rank order, then OTHER_EPICS, then NO_EPIC (7009's only item was removed: no segment)",
            )
            assertEquals(
                mapOf(
                    "7001" to listOf(8.0, 2.0, 8.0, 2.0), "7002" to listOf(4.0, 1.0, 4.0, 1.0), "7003" to listOf(3.0, 1.0, 3.0, 1.0),
                    "7004" to listOf(2.0, 1.0, 2.0, 1.0), "7005" to listOf(2.0, 1.0, 2.0, 1.0), "7007" to listOf(3.0, 1.0, 3.0, 1.0),
                    "OTHER_EPICS" to listOf(5.0, 2.0, 6.0, 2.0), "NO_EPIC" to listOf(1.0, 2.0, 1.0, 2.0),
                ),
                first.byEpic.associate { it.key to it.figures() },
            )
            // Sprint 2 has no 7007 / 7006 / 7008 item: those segments are omitted, the order of the rest is unchanged.
            assertEquals(listOf("7001", "7002", "7003", "7004", "7005", "NO_EPIC"), second.byEpic.map { it.key })
            assertEquals(listOf(5.0, 1.0, 6.0, 2.0), second.byEpic.first().figures(), "the added item counts toward final only")
            val top = first.byEpic.first()
            assertEquals(7001L, top.epicId)
            assertEquals("EPX-7001", top.epicKey)
            assertEquals("EPX-7001 Epic 7001", top.label)
            val other = first.byEpic.single { it.key == "OTHER_EPICS" }
            assertNull(other.epicId)
            assertNull(other.epicKey)
            val none = first.byEpic.single { it.key == "NO_EPIC" }
            assertNull(none.epicId)
            for (sprint in report.sprints) {
                assertEquals(sprint.initialMd, sprint.byEpic.sumOf { it.initialMd }, MD_TOLERANCE, "${sprint.sprintId} Σ initialMd")
                assertEquals(sprint.initialItems, sprint.byEpic.sumOf { it.initialItems }, "${sprint.sprintId} Σ initialItems")
                assertEquals(sprint.finalMd, sprint.byEpic.sumOf { it.finalMd }, MD_TOLERANCE, "${sprint.sprintId} Σ finalMd")
                assertEquals(sprint.finalItems, sprint.byEpic.sumOf { it.finalItems }, "${sprint.sprintId} Σ finalItems")
                assertTrue(sprint.byEpic.all { it.finalItems > 0 }, "no empty segment")
            }
            assertEquals(28.0, first.initialMd, MD_TOLERANCE)
            assertEquals(29.0, first.finalMd, MD_TOLERANCE)
        }
    }

    @Test
    fun `throughput splits the delivered figures by current epic and ranks by delivered MD, not as commitments do`() = testApplication {
        usePostgresTestcontainer()
        withWorld { connId, teamId ->
            val client = seededClient("reports-epic-seg-through")
            val report = client.report<ThroughputReport>(
                "/api/v1/reports/throughput?connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01",
            )
            val (first, second) = report.bySprint.sortedBy { it.sprintId }
            // Delivered MD: 7001 11, 7002 9, 7003 8, 7005 6, 7004 5, then a tie at 3 (7006, 7007, 7008) broken by epic key: 7006 is sixth.
            assertEquals(listOf("7001", "7002", "7003", "7005", "7006", "OTHER_EPICS", "NO_EPIC"), first.byEpic.map { it.key })
            assertEquals(
                mapOf(
                    "7001" to listOf(5.0, 1.0), "7002" to listOf(4.0, 1.0), "7003" to listOf(3.0, 1.0), "7005" to listOf(2.0, 1.0),
                    "7006" to listOf(3.0, 1.0), "OTHER_EPICS" to listOf(6.0, 2.0), "NO_EPIC" to listOf(1.0, 1.0),
                ),
                first.byEpic.associate { it.key to it.figures() },
                "7004's only sprint-1 item is not done: no zero segment",
            )
            assertEquals(listOf("7001", "7002", "7003", "7005", "7004", "NO_EPIC"), second.byEpic.map { it.key })
            for (sprint in report.bySprint) {
                assertEquals(sprint.deliveredMd, sprint.byEpic.sumOf { it.deliveredMd }, MD_TOLERANCE, "${sprint.sprintId} Σ deliveredMd")
                assertEquals(sprint.deliveredItems, sprint.byEpic.sumOf { it.deliveredItems }, "${sprint.sprintId} Σ deliveredItems")
            }
            assertTrue(report.byBucket.isNotEmpty(), "the period view is untouched")
        }
    }

    @Test
    fun `picking people narrows the split to their items and re-ranks over what is left`() = testApplication {
        usePostgresTestcontainer()
        withWorld { connId, teamId ->
            val client = seededClient("reports-epic-seg-user")
            val picks = "connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01&accountId=$ann"
            val commitments = client.report<CommitmentsReport>("/api/v1/reports/commitments?$picks")
            // Ann's final MD per epic: 7001 10, 7005 6, 7003 5, 7002 4, then 7007 / 7008 at 3 (initial 3 both: key order), 7004 2 folds.
            val first = commitments.sprints.single { it.sprintId == s1 }
            assertEquals(listOf("7001", "7005", "7002", "7007", "7008", "OTHER_EPICS", "NO_EPIC"), first.byEpic.map { it.key })
            assertEquals(
                mapOf(
                    "7001" to listOf(5.0, 1.0, 5.0, 1.0), "7005" to listOf(2.0, 1.0, 2.0, 1.0), "7002" to listOf(4.0, 1.0, 4.0, 1.0),
                    "7007" to listOf(3.0, 1.0, 3.0, 1.0), "7008" to listOf(3.0, 1.0, 3.0, 1.0),
                    "OTHER_EPICS" to listOf(2.0, 1.0, 2.0, 1.0), "NO_EPIC" to listOf(1.0, 1.0, 1.0, 1.0),
                ),
                first.byEpic.associate { it.key to it.figures() },
            )
            assertEquals(listOf("7001", "7005", "7003"), commitments.sprints.single { it.sprintId == s2 }.byEpic.map { it.key })
            for (sprint in commitments.sprints) {
                assertEquals(sprint.finalMd, sprint.byEpic.sumOf { it.finalMd }, MD_TOLERANCE, "${sprint.sprintId} Σ finalMd, USER")
                assertEquals(sprint.finalItems, sprint.byEpic.sumOf { it.finalItems }, "${sprint.sprintId} Σ finalItems")
                assertEquals(sprint.initialMd, sprint.byEpic.sumOf { it.initialMd }, MD_TOLERANCE, "${sprint.sprintId} Σ initialMd")
                assertEquals(sprint.initialItems, sprint.byEpic.sumOf { it.initialItems }, "${sprint.sprintId} Σ initialItems")
            }
            val throughput = client.report<ThroughputReport>("/api/v1/reports/throughput?$picks")
            // Ann delivered 7001 10, 7005 6, 7003 5, 7002 4, 7007 3, 7008 3, no epic 1: seven groups, so 7004 (not done) never shows.
            assertEquals(
                listOf("7001", "7005", "7002", "7007", "7008", "NO_EPIC"),
                throughput.bySprint.single { it.sprintId == s1 }.byEpic.map { it.key },
            )
            for (sprint in throughput.bySprint) {
                assertEquals(sprint.deliveredMd, sprint.byEpic.sumOf { it.deliveredMd }, MD_TOLERANCE, "${sprint.sprintId} Σ MD, USER")
                assertEquals(sprint.deliveredItems, sprint.byEpic.sumOf { it.deliveredItems }, "${sprint.sprintId} Σ deliveredItems")
            }
        }
    }

    /** The issue ids of one sprint's [bucket] that fall in the epic segment [key] — written out here, never through the production rule. */
    private fun expectedIssues(scope: List<SprintScopeItem>, bucket: String, key: String, topIds: Set<Long>): Set<String> = scope
        .filter {
            when (bucket) {
                "COMMITTED" -> it.committed && it.inScopeAtClose
                "FINAL" -> it.inScopeAtClose
                else -> it.doneInSprint
            }
        }
        .filter {
            val epic = epicOf.getValue(it.issueId)
            when (key) {
                "NO_EPIC" -> epic == null
                "OTHER_EPICS" -> epic != null && epic !in topIds
                else -> epic == key.toLong()
            }
        }
        .map { it.issueId.toString() }.toSet()

    private fun narrow(key: String, top: Set<Long>) =
        "epicSegment=$key" + if (key == "OTHER_EPICS") top.joinToString("") { "&topEpicId=$it" } else ""

    @Test
    fun `the drill's epicSegment bar equals the segment figure for an epic, OTHER_EPICS and NO_EPIC, through both sprint endpoints`() =
        testApplication {
            usePostgresTestcontainer()
            withWorld { connId, teamId ->
                val client = seededClient("reports-epic-seg-drill")
                val base = "connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01"
                val commitments = client.report<CommitmentsReport>("/api/v1/reports/commitments?$base")
                val throughput = client.report<ThroughputReport>("/api/v1/reports/throughput?$base")
                val commitTop = commitments.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
                val throughTop = throughput.bySprint.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
                assertEquals(6, commitTop.size)
                assertEquals(6, throughTop.size)
                assertTrue(7007L in commitTop && 7006L !in commitTop && 7008L !in commitTop, "commitments' sixth epic is 7007")
                assertTrue(7006L in throughTop && 7007L !in throughTop, "throughput's sixth epic is 7006")
                val scopes = mapOf(s1 to sprint1, s2 to sprint2)
                var checked = 0

                for (sprint in commitments.sprints) {
                    val one = "connectionId=$connId&teamId=$teamId&sprintId=${sprint.sprintId}"
                    for (segment in sprint.byEpic) {
                        for ((bucket, md, count) in listOf(
                            Triple("COMMITTED", segment.initialMd, segment.initialItems),
                            Triple("FINAL", segment.finalMd, segment.finalItems),
                        )) {
                            val drill = client.report<ReportDrillItems>(
                                "/api/v1/reports/sprint-consistency/items?$one&bucket=$bucket&${narrow(segment.key, commitTop)}",
                            )
                            val label = "commitments ${sprint.sprintId}/${segment.key}/$bucket"
                            assertEquals(count, drill.bar.count, "$label bar.count == the segment")
                            assertEquals(md, drill.bar.sumMd!!, MD_TOLERANCE, "$label bar.sumMd == the segment")
                            assertEquals(count, drill.total, label)
                            assertEquals(
                                expectedIssues(scopes.getValue(sprint.sprintId), bucket, segment.key, commitTop),
                                drill.items.map { it.issueKey }.toSet(), "$label lists exactly the segment's items",
                            )
                            checked++
                        }
                    }
                }
                for (sprint in throughput.bySprint) {
                    val one = "connectionId=$connId&teamId=$teamId&sprintId=${sprint.sprintId}"
                    for (segment in sprint.byEpic) {
                        val query = "$one&${narrow(segment.key, throughTop)}"
                        val label = "throughput ${sprint.sprintId}/${segment.key}"
                        val viaConsistency = client.report<ReportDrillItems>(
                            "/api/v1/reports/sprint-consistency/items?$query&bucket=DELIVERED",
                        )
                        val viaThroughput = client.report<ReportDrillItems>("/api/v1/reports/throughput/items?$query")
                        assertEquals(segment.deliveredItems, viaConsistency.bar.count, "$label bar.count")
                        assertEquals(segment.deliveredMd, viaConsistency.bar.sumMd!!, MD_TOLERANCE, "$label bar.sumMd")
                        assertEquals(viaConsistency, viaThroughput, "$label sprint mode forwards to the same drill")
                        assertEquals(
                            expectedIssues(scopes.getValue(sprint.sprintId), "DELIVERED", segment.key, throughTop),
                            viaThroughput.items.map { it.issueKey }.toSet(), "$label lists exactly the segment's items",
                        )
                        checked++
                    }
                }
                assertTrue(checked >= 30, "every segment of every sprint was drilled ($checked)")

                // The epic segment composes with the person segment: ann's share of epic 7001's final scope in sprint 2 is item 113 alone.
                val composed = client.report<ReportDrillItems>(
                    "/api/v1/reports/sprint-consistency/items?connectionId=$connId&teamId=$teamId&sprintId=$s2" +
                        "&accountId=$ann&accountId=$bob&bucket=FINAL&segmentAccountId=$ann&epicSegment=7001",
                )
                assertEquals(listOf("113"), composed.items.map { it.issueKey })
                assertEquals(1, composed.bar.count)
                assertEquals(5.0, composed.bar.sumMd!!, MD_TOLERANCE)
            }
        }

    @Test
    fun `OTHER_EPICS composes with segmentAccountId and with several picked accounts, and reconciles with the segment`() = testApplication {
        usePostgresTestcontainer()
        withWorld { connId, teamId ->
            val client = seededClient("reports-epic-seg-other-user")
            val scope = "connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01"
            val one = "connectionId=$connId&teamId=$teamId&sprintId=$s1&bucket=FINAL"

            // Two picked people: the ranking runs over what is left, which is everybody here, so OTHER_EPICS is 7006 (bob) + 7008 (ann).
            val both = client.report<CommitmentsReport>("/api/v1/reports/commitments?$scope&accountId=$ann&accountId=$bob")
            val bothFirst = both.sprints.single { it.sprintId == s1 }
            val segment = bothFirst.byEpic.single { it.key == "OTHER_EPICS" }
            assertEquals(listOf(6.0, 2.0), listOf(segment.finalMd, segment.finalItems.toDouble()))
            val bothTop = both.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
            val picks = "accountId=$ann&accountId=$bob"
            val whole = client.report<ReportDrillItems>(
                "/api/v1/reports/sprint-consistency/items?$one&$picks&${narrow("OTHER_EPICS", bothTop)}",
            )
            assertEquals(segment.finalItems, whole.bar.count)
            assertEquals(segment.finalMd, whole.bar.sumMd!!, MD_TOLERANCE)
            val shares = listOf(ann to setOf("112"), bob to setOf("107")).map { (account, issues) ->
                val share = client.report<ReportDrillItems>(
                    "/api/v1/reports/sprint-consistency/items?$one&$picks&segmentAccountId=$account&${narrow("OTHER_EPICS", bothTop)}",
                )
                assertEquals(issues, share.items.map { it.issueKey }.toSet(), "$account's share of the folded epics")
                share
            }
            assertEquals(whole.bar.count, shares.sumOf { it.bar.count }, "the people's shares add up to the segment")
            assertEquals(whole.bar.sumMd!!, shares.sumOf { it.bar.sumMd!! }, MD_TOLERANCE)

            // One picked person: the chart re-ranks over her items alone, so ITS top ids (and its OTHER_EPICS) differ from the team's.
            val alone = client.report<CommitmentsReport>("/api/v1/reports/commitments?$scope&accountId=$ann")
            val aloneSegment = alone.sprints.single { it.sprintId == s1 }.byEpic.single { it.key == "OTHER_EPICS" }
            assertEquals(listOf(2.0, 1.0), listOf(aloneSegment.finalMd, aloneSegment.finalItems.toDouble()), "ann's 7004 item folds")
            val aloneTop = alone.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
            val aloneDrill = client.report<ReportDrillItems>(
                "/api/v1/reports/sprint-consistency/items?$one&accountId=$ann&segmentAccountId=$ann&${narrow("OTHER_EPICS", aloneTop)}",
            )
            assertEquals(aloneSegment.finalItems, aloneDrill.bar.count)
            assertEquals(aloneSegment.finalMd, aloneDrill.bar.sumMd!!, MD_TOLERANCE)
            assertEquals(setOf("105"), aloneDrill.items.map { it.issueKey }.toSet())

            // Duplicated topEpicIds are the same request as one of each.
            val doubled = client.report<ReportDrillItems>(
                "/api/v1/reports/sprint-consistency/items?$one&$picks&${narrow("OTHER_EPICS", bothTop)}" +
                    bothTop.joinToString("") { "&topEpicId=$it" },
            )
            assertEquals(whole, doubled, "topEpicId=a&topEpicId=a collapses to topEpicId=a")
        }
    }

    @Test
    fun `an item whose task has no dim_task row lands in NO_EPIC and an epic with no label source has no Jira key`() = testApplication {
        usePostgresTestcontainer()
        val known = DimTaskRow(201, "EPX-T201", "Story", "Development", "Run", "NONE", false, null, "AAA", 7001L)
        val unlabelled = DimTaskRow(203, "EPX-T203", "Story", "Development", "Run", "NONE", false, null, "AAA", 7050L)
        val epicless = DimTaskRow(204, "EPX-T204", "Story", "Development", "Run", "NONE", false, null, "AAA", null)
        // Task 202 has NO dim_task row at all; epic 7050 has neither a dim_epic row nor a norm.work_items row.
        val scope = listOf(
            item(201, ann, 4.0, 4.0, done = 4.0), item(202, bob, 3.0, 3.0, done = 3.0),
            item(203, ann, 2.0, 2.0, done = 2.0), item(204, bob, 1.0, 1.0, done = 1.0),
        )
        val sprints = listOf(SeededSprint(s1, s1Closed, scope))
        withWorld(listOf(known, unlabelled, epicless), epics().take(1), sprints) { connId, teamId ->
            val client = seededClient("reports-epic-seg-nodim")
            val base = "connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01"
            val commitments = client.report<CommitmentsReport>("/api/v1/reports/commitments?$base")
            val row = commitments.sprints.single()
            assertEquals(listOf("7001", "7050", "NO_EPIC"), row.byEpic.map { it.key })
            assertEquals(listOf(4.0, 2.0, 4.0), row.byEpic.map { it.finalMd }, "NO_EPIC holds the dim_task-less 202 (3) and 204 (1)")
            assertEquals(listOf(1, 1, 2), row.byEpic.map { it.finalItems })
            assertEquals(row.finalMd, row.byEpic.sumOf { it.finalMd }, MD_TOLERANCE, "the segments still add up")
            assertEquals(row.finalItems, row.byEpic.sumOf { it.finalItems })
            val noKey = row.byEpic.single { it.key == "7050" }
            assertEquals(7050L, noKey.epicId)
            assertNull(noKey.epicKey, "no label source: no Jira key, never the bare id")
            assertEquals("7050", noKey.label, "the label falls back to the id")
            assertEquals("EPX-7001", row.byEpic.first().epicKey)
            val drill = client.report<ReportDrillItems>(
                "/api/v1/reports/sprint-consistency/items?connectionId=$connId&teamId=$teamId&sprintId=$s1" +
                    "&bucket=FINAL&epicSegment=NO_EPIC",
            )
            assertEquals(2, drill.bar.count)
            assertEquals(4.0, drill.bar.sumMd!!, MD_TOLERANCE)
            assertEquals(2, drill.items.size)
            val through = client.report<ThroughputReport>("/api/v1/reports/throughput?$base").bySprint.single()
            assertEquals(through.deliveredMd, through.byEpic.sumOf { it.deliveredMd }, MD_TOLERANCE)
            assertNull(through.byEpic.single { it.key == "7050" }.epicKey)
        }
    }

    @Test
    fun `a malformed epicSegment, a topEpicId out of place and an epicSegment on a period bar are 400s`() = testApplication {
        usePostgresTestcontainer()
        withWorld { connId, teamId ->
            val client = seededClient("reports-epic-seg-400")
            val one = "connectionId=$connId&teamId=$teamId&sprintId=$s1"

            suspend fun status(path: String) = client.get(path).status

            suspend fun items(query: String) = status("/api/v1/reports/sprint-consistency/items?$one&bucket=FINAL&$query")
            assertEquals(HttpStatusCode.OK, items("epicSegment=7001"))
            assertEquals(HttpStatusCode.OK, items("epicSegment=NO_EPIC"))
            assertEquals(HttpStatusCode.OK, items("epicSegment=OTHER_EPICS&topEpicId=7001&topEpicId=7002"))
            // Duplicates collapse before the bound (the shared `repeatedLongs` rule): seven copies of one id are one id, not a 400.
            assertEquals(HttpStatusCode.OK, items("epicSegment=OTHER_EPICS" + "&topEpicId=7001".repeat(7)))
            for ((what, query) in mapOf(
                "zero is no epic id" to "epicSegment=0",
                "negative" to "epicSegment=-5",
                "text" to "epicSegment=abc",
                "the special keys are exact" to "epicSegment=other_epics",
                "a sign is not a digit" to "epicSegment=%2B7001",
                "a repeated epicSegment" to "epicSegment=7001&epicSegment=7002",
                "OTHER_EPICS needs the top epics" to "epicSegment=OTHER_EPICS",
                "topEpicId without OTHER_EPICS" to "epicSegment=7001&topEpicId=7002",
                "topEpicId alone" to "topEpicId=7002",
                "NO_EPIC takes no top epics" to "epicSegment=NO_EPIC&topEpicId=7002",
                "a topEpicId of zero" to "epicSegment=OTHER_EPICS&topEpicId=0",
                "a non-numeric topEpicId" to "epicSegment=OTHER_EPICS&topEpicId=x",
                "a sign is not a digit in topEpicId either" to "epicSegment=OTHER_EPICS&topEpicId=%2B7001",
                "seven top epics" to "epicSegment=OTHER_EPICS" + (1..7).joinToString("") { "&topEpicId=$it" },
            )) {
                assertEquals(HttpStatusCode.BadRequest, items(query), what)
            }
            // The period bars are not split by epic: throughput/items with a bucketStart refuses the narrowing.
            val period = "connectionId=$connId&teamId=$teamId&from=2026-02-01&to=2026-03-01&bucketStart=2026-02-09"
            assertEquals(HttpStatusCode.OK, status("/api/v1/reports/throughput/items?$period"))
            assertEquals(HttpStatusCode.BadRequest, status("/api/v1/reports/throughput/items?$period&epicSegment=7001"))
        }
    }

    private companion object {
        const val MD_TOLERANCE = 0.005
        const val FORTNIGHT_MS = 14 * 86_400_000L
    }
}
