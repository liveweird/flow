package ch.nokillswit

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsSprintCapacity
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.DataQualityReport
import ch.nokillswit.reports.TaskFinding
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9
private const val DAY_MS = 86_400_000L
private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"

/** Every raw row the fixture test grades the report against — read straight from the tables, never through the report's queries. */
private class FixtureRows(
    val tasks: List<ResultRow>,
    val worklogs: List<ResultRow>,
    val epics: List<ResultRow>,
    val epicDims: Map<Long, ResultRow>,
)

private suspend fun readFixtureRows(connId: UInt): FixtureRows = suspendTransaction(sharedDatabaseForTests()) {
    val t = MetricsStore.FactTaskDelivery
    val d = MetricsStore.DimEpic
    FixtureRows(
        tasks = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList(),
        worklogs = MetricsStore.FactWorklog.selectAll().where { MetricsStore.FactWorklog.connectionId eq connId }.toList(),
        epics = MetricsStore.FactEpicDelivery.selectAll().where { MetricsStore.FactEpicDelivery.connectionId eq connId }.toList(),
        epicDims = d.selectAll().where { d.connectionId eq connId }.toList().associateBy { it[d.issueId] },
    )
}

/**
 * `GET /api/v1/reports/data-quality` (v0.3.0 M5 commit 17, Report 14, `.claude/docs/measures.md` "Report 14"). The stub
 * fixture grades every section against independent counts over the raw fact rows; a private DERIVED clone pins the
 * sprint-snapshot drift and the unmapped-status finding; hand-built rows in fresh DISABLED connections pin the populations,
 * the org drill, the logged hours, late logging, the epic findings, the horizon, the domains and the DERIVE warnings with
 * hand-computed answers. Every request runs through a non-admin `seededClient` (D12).
 */
class ReportDataQualityTest {

    private suspend fun HttpClient.dq(query: String): DataQualityReport {
        val response = get("/api/v1/reports/data-quality?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET data-quality?$query")
        return response.body()
    }

    private fun fixtureQuery(connId: UInt, extra: String = "") = "connectionId=$connId&from=$FROM&to=$TO$extra"

    /** The shared derived fixture read over [FROM]..[TO], with the raw rows the findings are graded against. */
    private class FixtureRead(
        val connId: UInt,
        val client: HttpClient,
        val rows: FixtureRows,
        val bounds: Pair<Long, Long>,
        val body: DataQualityReport,
    ) {
        private val t = MetricsStore.FactTaskDelivery
        val done: List<ResultRow> = rows.tasks.filter { (it[t.doneAt] ?: -1L) in bounds.first until bounds.second }
        val open: List<ResultRow> = rows.tasks.filter { it[t.doneAt] == null && it[t.startedAt] != null }
        private val e = MetricsStore.FactEpicDelivery
        val epics: List<ResultRow> = rows.epics.filter { it[e.doneAt] == null || it[e.doneAt]!! in bounds.first until bounds.second }
    }

    private suspend fun ApplicationTestBuilder.readFixture(prefix: String): FixtureRead {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient(prefix)
        return FixtureRead(connId, client, readFixtureRows(connId), windowBounds(reportZone(), FROM, TO), client.dq(fixtureQuery(connId)))
    }

    private fun assertTaskFinding(
        label: String,
        finding: TaskFinding,
        fixture: FixtureRead,
        pick: (ResultRow) -> Boolean,
    ) {
        val t = MetricsStore.FactTaskDelivery
        val d = fixture.done.filter(pick)
        val o = fixture.open.filter(pick)
        fun estimate(row: ResultRow) = (if (row[t.doneAt] == null) row[t.estimateCurrentMd] else row[t.estimateAtDoneMd])?.toDouble() ?: 0.0
        assertEquals(d.size, finding.done, "$label done")
        assertEquals(o.size, finding.open, "$label open")
        assertEquals(d.size + o.size, finding.total, "$label total")
        assertEquals((d + o).sumOf { estimate(it) }, finding.md, 1e-6, "$label md")
        assertEquals(minOf(50, finding.total), finding.items.size, "$label listed")
        val keys = (d + o).map { it[t.issueKey] }.toSet()
        assertTrue(finding.items.all { it.issueKey in keys }, "$label items are matches")
    }

    @Test
    fun `the fixture's task findings equal independent counts over the fact rows`() = testApplication {
        val fixture = readFixture("dq-fixture-tasks")
        val body = fixture.body
        val done = fixture.done
        val t = MetricsStore.FactTaskDelivery

        assertEquals(done.size, body.populations.doneTasks)
        assertEquals(fixture.open.size, body.populations.openStartedTasks)
        assertEquals(fixture.rows.worklogs.size, body.populations.worklogs)
        assertEquals(fixture.epics.size, body.populations.epics)
        assertTrue(done.isNotEmpty() && fixture.open.isNotEmpty(), "the fixture must have DONE and open started tasks")
        assertEquals("UNIT", body.meta.level.name)

        // Worklog coverage (D14) and the task findings, DONE and open counted separately.
        val without = done.filter { !it[t.hasWorklogs] }
        assertTrue(without.isNotEmpty() && without.size < done.size)
        assertEquals(done.size - without.size, body.worklogCoverage.withWorklogs)
        assertEquals((done.size - without.size).toDouble() / done.size, body.worklogCoverage.coverage!!, EPS)
        assertEquals(without.size, body.worklogCoverage.without.total)
        assertEquals(without.size, body.worklogCoverage.without.done)
        assertEquals(0, body.worklogCoverage.without.open)
        assertEquals(minOf(50, without.size), body.worklogCoverage.without.items.size)
        val withoutKeys = without.map { it[t.issueKey] }.toSet()
        assertTrue(body.worklogCoverage.without.items.all { it.issueKey in withoutKeys && it.doneAt != null })

        assertTaskFinding("noEstimate", body.missing.noEstimate, fixture) { it[t.estimateSource] == "NONE" }
        assertTaskFinding("noEpic", body.missing.noEpic, fixture) { it[t.epicId] == null }
        assertTrue(body.missing.noEstimate.total > 0 && body.missing.noEpic.total > 0)
        assertFalse(body.missing.workCategoryConfigured, "the stub fixture's default config has no work-category field")
        assertEquals(0, body.missing.noWorkCategory.total)
        assertTaskFinding("unassigned", body.missing.unassigned, fixture) { it[t.doneAt] != null && it[t.assigneeAccountIdAtDone] == null }
        assertEquals(0, body.missing.unassigned.open)
        assertTaskFinding("outsideSprint", body.outsideSprint, fixture) { it[t.doneAt] != null && it[t.sprintIdAtDone] == null }
        assertTrue(body.outsideSprint.done > 0, "the OPS Kanban project's DONE tasks are outside any sprint")
        assertEquals(0, body.outsideSprint.open)
        assertTaskFinding("crossDomain", body.crossDomain, fixture) { it[t.doneAt] != null && it[t.crossDomain] }
    }

    @Test
    fun `the fixture's worklog, epic and mapping findings equal independent counts over the fact rows`() = testApplication {
        val fixture = readFixture("dq-fixture-rest")
        val body = fixture.body
        val rows = fixture.rows
        val w = MetricsStore.FactWorklog
        val e = MetricsStore.FactEpicDelivery
        val d = MetricsStore.DimEpic

        // Late logging (worklog created vs started): the generator delays ~20% of worklogs by 1..5 days.
        val late = rows.worklogs.mapNotNull { it[w.lateMs] }
        assertEquals(rows.worklogs.size, body.lateLogging.worklogs)
        assertEquals(late.size, body.lateLogging.measurable)
        assertEquals(late.count { it > DAY_MS }, body.lateLogging.over1Day)
        assertEquals(late.count { it > 7 * DAY_MS }, body.lateLogging.over7Days)
        assertTrue(body.lateLogging.over1Day > 0 && body.lateLogging.over7Days == 0, "delays are 1..5 days")
        assertTrue(late.count { it > 0 } <= 240, "expected.json worklogTimestamps.createdLaterCount bounds the in-scope late worklogs")
        assertDistribution("lateness", late.map { it.toDouble() / DAY_MS }, body.lateLogging.distribution, body.meta.minSampleSize)
        assertEquals(minOf(50, late.count { it > 0 }), body.lateLogging.worst.size)
        assertEquals(late.max().toDouble() / DAY_MS, body.lateLogging.worst.first().lateDays, EPS)
        assertEquals(body.lateLogging.worst.map { it.lateDays }.sortedDescending(), body.lateLogging.worst.map { it.lateDays })

        // Authors without a team: the derived fixture has no roster at all, so every author is teamless.
        val teamless = rows.worklogs.filter { it[w.authorTeamId] == null }.groupBy { it[w.authorAccountId] }
        assertTrue(teamless.size >= 2)
        assertEquals(teamless.size, body.authorsWithoutTeam.total)
        if (teamless.size <= 50) {
            val expectedMd = teamless.values.sumOf { list -> list.sumOf { it[w.md].toDouble() } }
            assertEquals(expectedMd, body.authorsWithoutTeam.items.sumOf { it.md }, 1e-6)
        }
        val firstAuthor = body.authorsWithoutTeam.items.first()
        assertEquals(teamless.getValue(firstAuthor.accountId).size, firstAuthor.worklogs)
        assertEquals(0.0, body.loggedHours.memberDays, EPS)
        assertNull(body.loggedHours.hoursPerMemberDay)
        assertEquals(0.0, body.loggedHours.hours, EPS, "hours of authors in no team are not comparable to a roster")

        // Epics: independent counts over fact_epic_delivery + dim_epic.
        val epics = fixture.epics
        fun dim(r: ResultRow) = rows.epicDims.getValue(r[e.issueId])
        assertEquals(epics.count { it[e.budgetSource] == "CHILDREN" }, body.missing.epicsWithoutEstimate.total)
        assertEquals(epics.count { dim(it)[d.startAt] == null || dim(it)[d.dueAt] == null }, body.missing.epicsWithoutDates.total)
        val outsideHorizon = epics.count {
            val start = dim(it)[d.startAt]
            val due = dim(it)[d.dueAt]
            start != null && due != null && !DeriveKernels.inPvHorizon(start, due, DerivedStubFixture.PINNED_NOW)
        }
        assertEquals(outsideHorizon, body.missing.epicsOutsidePvHorizon.total)
        assertEquals(epics.count { it[e.driftFlags] != "[]" }, body.epicDrift.total)
        assertTrue(body.epicDrift.items.all { it.flags.isNotEmpty() })
        assertTrue(epics.isNotEmpty())

        assertUnmappedBoards(fixture)

        // Domains without an owner: FLO's board is mapped, so its domain has an owner; the others have none.
        val domains = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.DimDomain.selectAll().where { MetricsStore.DimDomain.connectionId eq fixture.connId }.toList()
        }
        val unowned = domains.filter { it[MetricsStore.DimDomain.ownerTeamId] == null }.map { it[MetricsStore.DimDomain.domainKey] }.toSet()
        assertTrue(unowned.isNotEmpty() && unowned.size < domains.size)
        assertEquals(unowned, body.domainsWithoutOwner.items.map { it.domainKey }.toSet())
        for (item in body.domainsWithoutOwner.items) {
            assertEquals(rows.epicDims.values.count { it[d.domainKey] == item.domainKey }, item.epics, "epics of ${item.domainKey}")
        }

        // Nothing drifted and no mapping gap of the stage kind in the untouched fixture.
        assertEquals(0, body.snapshotDrift.total)
        assertEquals(0, body.unmappedStatuses.total)
        assertTrue(body.deriveWarnings.isEmpty())
    }

    /** Unmapped boards: the fixture maps only the FLO board, so the other in-scope boards are the finding. */
    private suspend fun assertUnmappedBoards(fixture: FixtureRead) {
        val t = MetricsStore.FactTaskDelivery
        val body = fixture.body
        val mapped = suspendTransaction(sharedDatabaseForTests()) {
            MetricsConfigService.BoardTeamMap.selectAll().where { MetricsConfigService.BoardTeamMap.connectionId eq fixture.connId }
                .toList().map { it[MetricsConfigService.BoardTeamMap.boardId] }.toSet()
        }
        val (boards, sprintsByBoard) = suspendTransaction(sharedDatabaseForTests()) {
            val b = WorkItemStore.Boards
            val s = WorkItemStore.Sprints
            b.selectAll().where { b.connectionId eq fixture.connId }.toList() to
                s.selectAll().where { s.connectionId eq fixture.connId }.toList().groupBy { it[s.boardId] }
        }
        val unmapped = boards.filter { it[WorkItemStore.Boards.boardId] !in mapped }
        assertTrue(unmapped.size >= 3, "PLT, GTM and OPS boards are unmapped in the fixture")
        assertEquals(unmapped.size, body.unmappedBoards.total)
        val doneInUnmappedSprint = fixture.done.filter { it[t.sprintIdAtDone] != null && it[t.sprintTeamIdAtDone] == null }
        for (item in body.unmappedBoards.items) {
            val sprintIds = sprintsByBoard[item.boardId].orEmpty().map { it[WorkItemStore.Sprints.sprintId] }.toSet()
            assertEquals(sprintIds.size, item.sprints, "sprints of board ${item.boardId}")
            val doneThere = doneInUnmappedSprint.count { it[t.sprintIdAtDone] in sprintIds }
            assertEquals(doneThere, item.doneTasks, "done tasks of board ${item.boardId}")
        }
        assertTrue(body.unmappedBoards.items.sumOf { it.doneTasks } > 0, "PLT and GTM sprints carry DONE tasks whose board has no team")
        assertEquals(doneInUnmappedSprint.size, body.unmappedBoards.items.sumOf { it.doneTasks })
    }

    @Test
    fun `the UNIT org drill equals an independent grouping of the fact rows by team`() = testApplication {
        val fixture = readFixture("dq-groups-unit")
        val unit = fixture.body
        val rows = fixture.rows
        val t = MetricsStore.FactTaskDelivery
        val w = MetricsStore.FactWorklog
        val e = MetricsStore.FactEpicDelivery
        val teams = (
            fixture.done.map { it[t.creditTeamId]?.value } + fixture.open.map { it[t.currentTeamId]?.value } +
                fixture.epics.map { it[e.ownerTeamId]?.value } + rows.worklogs.map { it[w.authorTeamId]?.value }
            ).toSet()
        assertEquals(teams, unit.groups.map { it.teamId }.toSet(), "one group per team with any row (null = UNASSIGNED/UNOWNED)")
        assertTrue(teams.size >= 2 && null in teams)
        for (group in unit.groups) {
            assertNull(group.accountId)
            val done = fixture.done.filter { it[t.creditTeamId]?.value == group.teamId }
            val open = fixture.open.filter { it[t.currentTeamId]?.value == group.teamId }
            val label = "team ${group.teamId}"
            assertEquals(done.size, group.tasks.done, "done of $label")
            assertEquals(open.size, group.tasks.openStarted, "open of $label")
            assertEquals(done.count { !it[t.hasWorklogs] }, group.tasks.withoutWorklogs, "no worklogs of $label")
            assertEquals(done.count { it[t.sprintIdAtDone] == null }, group.tasks.outsideSprint, "outside of $label")
            assertEquals(done.count { it[t.estimateSource] == "NONE" }, group.tasks.noEstimate.done, "no estimate of $label")
            assertEquals(open.count { it[t.epicId] == null }, group.tasks.noEpic.open, "open no epic of $label")
            val logs = rows.worklogs.filter { it[w.authorTeamId]?.value == group.teamId }
            assertEquals(logs.size, group.worklogs.worklogs)
            assertEquals(logs.sumOf { it[w.md].toDouble() }, group.worklogs.md, 1e-6)
            assertEquals(logs.count { (it[w.lateMs] ?: 0L) > DAY_MS }, group.worklogs.over1Day)
            val owned = fixture.epics.filter { it[e.ownerTeamId]?.value == group.teamId }
            assertEquals(owned.size, assertNotNull(group.epics).epics, "epics of $label")
            assertEquals(owned.count { it[e.budgetSource] == "CHILDREN" }, group.epics.withoutEstimate)
        }
        assertEquals(unit.populations.doneTasks, unit.groups.sumOf { it.tasks.done })
        assertEquals(unit.populations.openStartedTasks, unit.groups.sumOf { it.tasks.openStarted })
        assertEquals(unit.worklogCoverage.without.total, unit.groups.sumOf { it.tasks.withoutWorklogs })
        assertEquals(unit.missing.noEstimate.done, unit.groups.sumOf { it.tasks.noEstimate.done })
        assertEquals(unit.missing.noEstimate.open, unit.groups.sumOf { it.tasks.noEstimate.open })
        assertEquals(unit.outsideSprint.total, unit.groups.sumOf { it.tasks.outsideSprint })
        assertEquals(unit.populations.worklogs, unit.groups.sumOf { it.worklogs.worklogs })
        assertEquals(unit.populations.epics, unit.groups.sumOf { it.epics!!.epics })
        assertEquals(unit.lateLogging.over1Day, unit.groups.sumOf { it.worklogs.over1Day })
    }

    @Test
    fun `the TEAM USER and teamId 0 drills and the domain and sprint slices equal independent readings of the fact rows`() =
        testApplication {
            val fixture = readFixture("dq-groups-team")
            val client = fixture.client
            val connId = fixture.connId
            val rows = fixture.rows
            val t = MetricsStore.FactTaskDelivery
            val w = MetricsStore.FactWorklog
            val e = MetricsStore.FactEpicDelivery
            val done = fixture.done
            val open = fixture.open

            // TEAM level: that team's tasks by assignee (done: at done, open: now), no epic counts in the groups.
            val floTeam = done.mapNotNull { it[t.creditTeamId]?.value }.toSet().single()
            val team = client.dq(fixtureQuery(connId, "&teamId=$floTeam"))
            val teamDone = done.filter { it[t.creditTeamId]?.value == floTeam }
            val teamOpen = open.filter { it[t.currentTeamId]?.value == floTeam }
            assertEquals("TEAM", team.meta.level.name)
            assertEquals(teamDone.size, team.populations.doneTasks)
            assertEquals(teamOpen.size, team.populations.openStartedTasks)
            val assignees = teamDone.map { it[t.assigneeAccountIdAtDone] }.toSet() + teamOpen.map { it[t.currentAssigneeAccountId] }.toSet()
            assertEquals(assignees, team.groups.map { it.accountId }.toSet())
            assertTrue(team.groups.all { it.teamId == null && it.epics == null })
            assertEquals(teamDone.size, team.groups.sumOf { it.tasks.done })
            assertEquals(teamOpen.size, team.groups.sumOf { it.tasks.openStarted })
            for (group in team.groups) {
                assertEquals(teamDone.count { it[t.assigneeAccountIdAtDone] == group.accountId }, group.tasks.done)
                assertEquals(teamOpen.count { it[t.currentAssigneeAccountId] == group.accountId }, group.tasks.openStarted)
            }
            // A real team owns no teamless author and no ownerless domain; its authors are none in this roster-less fixture.
            assertEquals(0, team.authorsWithoutTeam.total)
            assertEquals(0, team.domainsWithoutOwner.total)
            assertEquals(fixture.epics.count { it[e.ownerTeamId]?.value == floTeam }, team.populations.epics)

            // USER level: the one assignee, no groups, no epics, no sprint findings.
            val account = teamDone.mapNotNull { it[t.assigneeAccountIdAtDone] }.first()
            val user = client.dq(fixtureQuery(connId, "&teamId=$floTeam&accountId=$account"))
            assertEquals("USER", user.meta.level.name)
            assertTrue(user.groups.isEmpty())
            assertEquals(teamDone.count { it[t.assigneeAccountIdAtDone] == account }, user.populations.doneTasks)
            assertEquals(0, user.populations.epics)
            assertEquals(0, user.snapshotDrift.total)

            // teamId=0: UNASSIGNED tasks, the teamless authors and the ownerless domains; epics without an owner.
            val unassigned = client.dq(fixtureQuery(connId, "&teamId=0"))
            assertEquals(done.count { it[t.creditTeamId] == null }, unassigned.populations.doneTasks)
            assertEquals(open.count { it[t.currentTeamId] == null }, unassigned.populations.openStartedTasks)
            assertEquals(fixture.body.authorsWithoutTeam.total, unassigned.authorsWithoutTeam.total)
            assertEquals(fixture.body.domainsWithoutOwner.total, unassigned.domainsWithoutOwner.total)
            assertEquals(fixture.epics.count { it[e.ownerTeamId] == null }, unassigned.populations.epics)

            // The domain slice narrows tasks, worklogs, epics and the ownerless domains; a sprint-relative period reads the envelope.
            val domainKey = done.mapNotNull { it[t.domainKey] }.first()
            val sliced = client.dq(fixtureQuery(connId, "&domain=$domainKey"))
            assertEquals(done.count { it[t.domainKey] == domainKey }, sliced.populations.doneTasks)
            assertEquals(rows.worklogs.count { it[w.taskDomainKey] == domainKey }, sliced.populations.worklogs)
            assertTrue(sliced.domainsWithoutOwner.items.all { it.domainKey == domainKey })
            val sprintPeriod = client.dq("connectionId=$connId&lastSprints=2")
            assertTrue(sprintPeriod.meta.resolvedSprints.isNotEmpty())
            assertTrue(sprintPeriod.populations.doneTasks < fixture.body.populations.doneTasks)
        }

    /** A private, disabled, FLO-board-mapped processed clone of the synced fixture — `DerivedStubFixture`'s own setup. */
    private suspend fun derivedClone(): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "dq-clone-team")
        return connId
    }

    /** One expected drift figure as an independent comparison of the stored rows sees it. */
    private data class ExpectedDrift(val live: Double?, val frozen: Double?, val reconstructed: Boolean)

    /** Every `(sprintId, field)` whose live value differs from its `fact_sprint_snapshot` twin — the test's own comparison. */
    private suspend fun expectedDrift(connId: UInt): Map<Pair<Long, String>, ExpectedDrift> {
        val l = MetricsStore.FactSprint
        val f = MetricsStore.FactSprintSnapshot
        val md = 0.005
        val figures: List<Triple<String, Double, Pair<Column<out Number?>, Column<out Number?>>>> = listOf(
            Triple("committedMd", md, l.committedMd to f.committedMd), Triple("committedItems", 0.0, l.committedItems to f.committedItems),
            Triple("addedMd", md, l.addedMd to f.addedMd), Triple("addedItems", 0.0, l.addedItems to f.addedItems),
            Triple("removedMd", md, l.removedMd to f.removedMd), Triple("removedItems", 0.0, l.removedItems to f.removedItems),
            Triple("finalMd", md, l.finalMd to f.finalMd), Triple("finalItems", 0.0, l.finalItems to f.finalItems),
            Triple("deliveredMd", md, l.deliveredMd to f.deliveredMd), Triple("deliveredItems", 0.0, l.deliveredItems to f.deliveredItems),
            Triple("carriedOverMd", md, l.carriedOverMd to f.carriedOverMd),
            Triple("carriedOverItems", 0.0, l.carriedOverItems to f.carriedOverItems),
            Triple("droppedMd", md, l.droppedMd to f.droppedMd), Triple("droppedItems", 0.0, l.droppedItems to f.droppedItems),
            Triple("capacityMd", md, l.capacityMd to f.capacityMd), Triple("load", 0.0005, l.load to f.load),
        )
        return suspendTransaction(sharedDatabaseForTests()) {
            val live = l.selectAll().where { l.connectionId eq connId }.toList().associateBy { it[l.sprintId] }
            f.selectAll().where { f.connectionId eq connId }.toList().flatMap { frozen ->
                val liveRow = live[frozen[f.sprintId]] ?: return@flatMap emptyList()
                figures.mapNotNull { (name, tolerance, columns) ->
                    val lv = liveRow[columns.first]?.toDouble()
                    val fv = frozen[columns.second]?.toDouble()
                    val drifted = if (lv == null || fv == null) lv != fv else kotlin.math.abs(lv - fv) > tolerance
                    if (drifted) (frozen[f.sprintId] to name) to ExpectedDrift(lv, fv, frozen[f.reconstructed]) else null
                }
            }.toMap()
        }
    }

    private fun assertDriftEquals(expected: Map<Pair<Long, String>, ExpectedDrift>, actual: List<ch.nokillswit.reports.SnapshotDrift>) {
        assertEquals(expected.keys, actual.map { it.sprintId to it.field }.toSet(), "the drifted (sprint, figure) pairs")
        assertEquals(expected.size, actual.size, "each pair once")
        for (item in actual) {
            val want = expected.getValue(item.sprintId to item.field)
            assertEquals(want.live, item.live, "live ${item.field} of ${item.sprintId}")
            assertEquals(want.frozen, item.frozen, "frozen ${item.field} of ${item.sprintId}")
            assertEquals(want.reconstructed, item.reconstructed, "reconstructed flag of ${item.sprintId}")
            val delta = item.delta
            if (want.live != null && want.frozen != null) assertEquals(want.live - want.frozen, delta!!, EPS) else assertNull(delta)
        }
    }

    /** One short app session with a signed-in USER — the heavy DERIVE work stays outside it, out of `testApplication`'s 1-minute cap. */
    private fun withClient(prefix: String, block: suspend (HttpClient) -> Unit) = testApplication {
        usePostgresTestcontainer()
        block(seededClient(prefix))
    }

    @Test
    fun `snapshot drift and an unmapped status appear after a config change and a re-derive, and not before`() {
        val config = DerivedStubFixture.metricsConfig()
        val snapshot = MetricsStore.FactSprintSnapshot
        val live = MetricsStore.FactSprint
        val connId = runBlocking {
            val id = derivedClone()
            DerivedStubFixture.withPinnedSettings(config) { DerivedStubFixture.derivePinned(id, config, jobId = 1u) }
            id
        }
        try {
        val frozenRows = runBlocking {
            suspendTransaction(sharedDatabaseForTests()) {
                snapshot.selectAll().where { snapshot.connectionId eq connId }.toList()
            }
        }
        assertTrue(frozenRows.isNotEmpty(), "the FLO board is team-mapped, so DERIVE froze closed sprints")
        val frozen = frozenRows.first()
        val sprintId = frozen[snapshot.sprintId]

        // Before: the frozen and live figures are the same numbers; every status has a stage.
        withClient("dq-drift-before") { client ->
            val before = client.dq(fixtureQuery(connId))
            assertEquals(0, before.snapshotDrift.total)
            assertEquals(0, before.unmappedStatuses.total)
        }

        // Change the configuration the way an admin would — a capacity override for one sprint, and no stage for "Waiting" — and
        // re-derive: the LIVE rows follow the new configuration, the FROZEN ones never move (D13).
        val waitingId = runBlocking {
            val waiting = suspendTransaction(sharedDatabaseForTests()) {
                val s = WorkItemStore.Statuses
                s.selectAll().where { (s.connectionId eq connId) and (s.name eq "Waiting") }.toList().single()[s.statusId]
            }
            val current = config.effectiveConfig(connId).asRequest()
            config.replaceConfig(
                connId,
                current.copy(
                    statusStages = current.statusStages.filter { it.statusId != waiting },
                    sprintCapacities = listOf(MetricsSprintCapacity(sprintId, 77.0)),
                ),
            )
            DerivedStubFixture.withPinnedSettings(config) { DerivedStubFixture.derivePinned(connId, config, jobId = 2u) }
            waiting
        }

        withClient("dq-drift-after") { client ->
            val afterDerive = client.dq(fixtureQuery(connId))
            val capacity = afterDerive.snapshotDrift.items.single { it.sprintId == sprintId && it.field == "capacityMd" }
            assertEquals(77.0, capacity.live!!, EPS)
            assertEquals(frozen[snapshot.capacityMd]?.toDouble(), capacity.frozen)
            assertNotEquals(capacity.live, capacity.frozen)
            // Exactly the figures an independent comparison of the stored live and frozen rows flags — no more, no fewer.
            assertDriftEquals(expectedDrift(connId), afterDerive.snapshotDrift.items)
            val capacityFigures = setOf("capacityMd", "load")
            val onlyCapacity = afterDerive.snapshotDrift.items.all { it.field in capacityFigures }
            assertTrue(onlyCapacity, "a capacity change moves capacity and load only")

            // The unmapped status: the items that ever sat in "Waiting" and those sitting in it now, from the stage intervals.
            val i = MetricsStore.ItemStage
            val tiled = suspendTransaction(sharedDatabaseForTests()) {
                i.selectAll().where { (i.connectionId eq connId) and (i.stage eq "UNMAPPED") }.toList()
            }
            assertTrue(tiled.isNotEmpty(), "GTM tasks passed through Waiting")
            val status = afterDerive.unmappedStatuses.items.single()
            assertEquals(1, afterDerive.unmappedStatuses.total)
            assertEquals(waitingId, status.statusId)
            assertEquals("Waiting", status.name)
            assertEquals(tiled.map { it[i.issueId] }.distinct().size, status.items)
            assertEquals(tiled.filter { it[i.validTo] == null }.map { it[i.issueId] }.distinct().size, status.openItems)

            // Then move two live figures by hand: the delta is live minus frozen, per figure, and only the moved ones are listed.
            suspendTransaction(sharedDatabaseForTests()) {
                live.update({ (live.connectionId eq connId) and (live.sprintId eq sprintId) }) {
                    it[committedMd] = frozen[snapshot.committedMd] + BigDecimal("3.00")
                    it[deliveredItems] = frozen[snapshot.deliveredItems] + 1
                }
            }
            val moved = client.dq(fixtureQuery(connId))
            assertDriftEquals(expectedDrift(connId), moved.snapshotDrift.items)
            val figures = moved.snapshotDrift.items.filter { it.sprintId == sprintId }.associateBy { it.field }
            assertEquals(frozen[snapshot.committedMd].toDouble() + 3.0, figures.getValue("committedMd").live!!, EPS)
            assertEquals(frozen[snapshot.committedMd].toDouble(), figures.getValue("committedMd").frozen!!, EPS)
            assertEquals(3.0, figures.getValue("committedMd").delta!!, EPS)
            assertEquals(1.0, figures.getValue("deliveredItems").delta!!, EPS)
            assertTrue(figures.getValue("committedMd").name.isNotBlank())
            assertEquals(moved.snapshotDrift.total, moved.snapshotDrift.items.size)
            assertEquals(afterDerive.snapshotDrift.total + 2, moved.snapshotDrift.total)
            // The sprint filter narrows to the sprint; the team drill of another team and the USER level see nothing.
            val bySprint = client.dq("connectionId=$connId&sprintId=$sprintId")
            assertEquals(moved.snapshotDrift.items.count { it.sprintId == sprintId }, bySprint.snapshotDrift.total)
            val otherTeam = TestTeams.seed(SyncedStubFixture.unique("dq-other"))
            try {
                assertEquals(0, client.dq(fixtureQuery(connId, "&teamId=$otherTeam")).snapshotDrift.total)
            } finally {
                cleanUpTeams(listOf(otherTeam))
            }
        }
        } finally {
            runBlocking { MetricsStore(sharedDatabaseForTests()).purgeAll(connId) }
        }
    }

    private suspend fun insertDeriveRun(connId: UInt, startedAt: Long, rowCounts: String?): Int =
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.DeriveRuns.insert {
                it[MetricsStore.DeriveRuns.connectionId] = connId.toInt()
                it[configRevision] = 1L
                it[processingVersion] = 1
                it[MetricsStore.DeriveRuns.startedAt] = startedAt
                it[status] = "SUCCEEDED"
                it[MetricsStore.DeriveRuns.rowCounts] = rowCounts
            }[MetricsStore.DeriveRuns.id]
        }

    @Test
    fun `derive warnings come from the latest successful run's sprintFieldUnresolved flag`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-warn", enabled = false)
        val client = seededClient("dq-warnings")
        val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
        try {
            assertTrue(client.dq(query).deriveWarnings.isEmpty(), "no run at all: nothing to warn about")
            val unresolved = """{"tasks":3,"epics":0,"sprints":0,"sprintFieldUnresolved":true}"""
            val flagged = insertDeriveRun(connId, noonUtc("2026-01-10"), unresolved)
            val warnings = client.dq(query).deriveWarnings
            assertEquals(1, warnings.size)
            assertEquals(connId, warnings.single().connectionId)
            assertEquals(flagged, warnings.single().runId)
            assertEquals(listOf("sprintFieldUnresolved"), warnings.single().warnings)
            assertEquals(noonUtc("2026-01-10"), warnings.single().startedAt)
            assertTrue(warnings.single().connectionName.startsWith("dq-warn"))
            // A NEWER successful run without the flag supersedes it.
            insertDeriveRun(connId, noonUtc("2026-01-12"), """{"tasks":3,"epics":0,"sprints":2}""")
            assertTrue(client.dq(query).deriveWarnings.isEmpty(), "the latest successful run carries no warning")
        } finally {
            deleteDeriveRuns(connId)
        }
    }

    @Test
    fun `hand-built epics pin the populations, the estimate and date findings, the horizon, the drift and the owner drill`() =
        testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-epics", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("dq-ex"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("dq-ey"))
        val deriveClock = noonUtc("2026-01-14")
        try {
        insertSucceededDerive(connId, deriveClock)
        val started = noonUtc("2026-01-05")
        val drift = listOf("EPIC_DONE_WITH_OPEN_CHILDREN")
        val facts = listOf(
            handEpic(1, started, null, ownCurrent = 5.0, ownerTeamId = teamX),
            handEpic(2, started, null, childSum = 3.0, ownerTeamId = teamX),
            handEpic(3, started, noonUtc("2026-01-20"), ownCurrent = 2.0, ownerTeamId = teamY).copy(driftFlags = drift),
            handEpic(4, started, null, childSum = 1.0, ownerTeamId = null)
                .copy(driftFlags = listOf("EPIC_OPEN_AFTER_CHILDREN_DONE", drift.single())),
            handEpic(5, started, null, ownCurrent = 1.0, ownerTeamId = teamX),
            handEpic(6, started, noonUtc("2025-05-01"), ownCurrent = 1.0, ownerTeamId = teamX),
            handEpic(7, started, null, ownCurrent = 1.0, ownerTeamId = teamX),
        )
        fun dim(id: Long, start: String?, due: String?) =
            DimEpicRow(id, "EP-$id", "Epic $id", "AAA", null, "IN_PROGRESS", start?.let { noonUtc(it) }, due?.let { noonUtc(it) })
        val dims = listOf(
            dim(1, "2026-01-10", "2026-03-01"), // inside the horizon
            dim(2, "1900-01-01", "2026-02-01"), // start before the horizon: no PV curve
            dim(3, "2026-01-01", "9999-12-31"), // due after it
            dim(4, "2026-01-01", null), // half dated: "without dates", not "outside the horizon"
            dim(5, null, null), // undated
            dim(6, "2026-01-01", "2026-01-15"), // done long before the period
            // Inside a horizon cut around the REQUEST's clock, outside the one around the connection's last DERIVE (2026-01-14).
            dim(7, "2036-03-01", "2036-04-01"),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactEpicDelivery(connId, facts, configRevision = 1L)
            store.insertEpics(connId, dims, configRevision = 1L)
            val domains = listOf(
                DimDomainRow("AAA", "Alpha", listOf("AAA", "AAB"), null),
                DimDomainRow("BBB", "Beta", listOf("BBB"), teamX),
            )
            store.insertDomains(connId, domains, 1L)
        }
            val client = seededClient("dq-epics-hand")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            fun keys(list: ch.nokillswit.reports.QualityList<ch.nokillswit.reports.EpicRef>) = list.items.map { it.issueKey }.sorted()
            val body = client.dq(query)
            assertEquals(6, body.populations.epics, "open epics and the one done in January; e6 was done before the period")
            assertEquals(listOf("EP-2", "EP-4"), keys(body.missing.epicsWithoutEstimate))
            assertEquals(listOf("EP-4", "EP-5"), keys(body.missing.epicsWithoutDates))
            assertEquals(listOf("EP-2", "EP-3", "EP-7"), keys(body.missing.epicsOutsidePvHorizon))
            assertEquals(3, body.missing.epicsOutsidePvHorizon.total)
            assertEquals(listOf("EP-3", "EP-4"), keys(body.epicDrift))
            val flags4 = body.epicDrift.items.single { it.issueKey == "EP-4" }.flags
            assertEquals(listOf("EPIC_OPEN_AFTER_CHILDREN_DONE", "EPIC_DONE_WITH_OPEN_CHILDREN"), flags4)
            assertTrue(body.missing.epicsWithoutEstimate.items.all { it.flags.isEmpty() && it.summary!!.startsWith("Epic ") })
            assertEquals(teamY, body.epicDrift.items.single { it.issueKey == "EP-3" }.ownerTeamId)
            assertEquals(noonUtc("2026-01-20"), body.epicDrift.items.single { it.issueKey == "EP-3" }.doneAt)

            // The owner drill: X owns e1 e2 e5 e7, Y owns e3, e4 has no owner (null = UNOWNED).
            val x = body.groups.single { it.teamId == teamX }.epics!!
            assertEquals(listOf(4, 1, 1, 2, 0), listOf(x.epics, x.withoutEstimate, x.withoutDates, x.outsidePvHorizon, x.drifting))
            val y = body.groups.single { it.teamId == teamY }.epics!!
            assertEquals(listOf(1, 0, 0, 1, 1), listOf(y.epics, y.withoutEstimate, y.withoutDates, y.outsidePvHorizon, y.drifting))
            val unowned = body.groups.single { it.teamId == null }.epics!!
            assertEquals(
                listOf(1, 1, 1, 0, 1),
                listOf(unowned.epics, unowned.withoutEstimate, unowned.withoutDates, unowned.outsidePvHorizon, unowned.drifting),
            )
            assertEquals(6, body.groups.sumOf { it.epics!!.epics })

            // TEAM level narrows to the owner, USER level has no epics, teamId=0 is UNOWNED.
            val team = client.dq("$query&teamId=$teamX")
            assertEquals(4, team.populations.epics)
            assertEquals(listOf("EP-5"), keys(team.missing.epicsWithoutDates))
            assertTrue(team.groups.all { it.epics == null })
            assertEquals(0, client.dq("$query&teamId=$teamX&accountId=nobody").populations.epics)
            assertEquals(listOf("EP-4"), keys(client.dq("$query&teamId=0").epicDrift))

            // Domains: AAA has no owner (its 7 epics land in UNOWNED), BBB has one. A real team sees none; teamId=0 does.
            val domains = body.domainsWithoutOwner
            assertEquals(1, domains.total)
            assertEquals("AAA", domains.items.single().domainKey)
            assertEquals("Alpha", domains.items.single().name)
            assertEquals(listOf("AAA", "AAB"), domains.items.single().projectKeys)
            assertEquals(7, domains.items.single().epics)
            assertEquals(connId, domains.items.single().connectionId)
            assertEquals(0, team.domainsWithoutOwner.total)
            assertEquals(1, client.dq("$query&teamId=0").domainsWithoutOwner.total)
            assertEquals(1, client.dq("$query&domain=AAA").domainsWithoutOwner.total)
            assertEquals(0, client.dq("$query&domain=BBB").domainsWithoutOwner.total)
            assertEquals(0, client.dq("$query&domain=BBB").populations.epics, "no epic is in BBB")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactEpicDelivery(connId)
                store.deleteDims(connId)
            }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    /** Working days of `[from, toExclusive)` by plain weekday arithmetic — deliberately not `WorkingCalendar`. */
    private fun workingDays(from: String, toExclusive: String, weekend: Set<Int>, holidays: Set<LocalDate>): Int =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { it.isBefore(LocalDate.parse(toExclusive)) }
            .count { it.dayOfWeek.value !in weekend && it !in holidays }

    private fun startOfDay(zone: ZoneId, date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `hand-built tasks and worklogs pin the populations, every task finding, late logging, logged hours and the drills`() =
        testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-hand", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("dq-tx"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("dq-ty"))
        // The roster is global: a team with members but no finding row in this connection must not appear in, or dilute, its UNIT read.
        val teamZ = TestTeams.seed(SyncedStubFixture.unique("dq-tz"))
        val az = SyncedStubFixture.unique("acc-az")
        val a1 = SyncedStubFixture.unique("acc-a1")
        val a2 = SyncedStubFixture.unique("acc-a2")
        val a3 = SyncedStubFixture.unique("acc-a3")
        val a9 = SyncedStubFixture.unique("acc-a9")
        val b1 = SyncedStubFixture.unique("acc-b1")
        val zone = reportZone()
        val started = noonUtc("2025-12-15")
        val membership = TeamMembershipService.TeamMembership
        try {

        fun done(
            id: Long,
            day: String,
            team: UInt?,
            account: String?,
            worklogs: Boolean = true,
            estimate: Double? = 1.0,
            epic: Long? = 21,
            category: String? = "c",
            sprint: Long? = 100,
            sprintTeam: UInt? = team,
            subtask: Boolean = false,
            epicDomain: String? = null,
        ) = handTask(
            id, started, noonUtc(day), hasWorklogs = worklogs, estimateAtDoneMd = estimate, creditTeamId = team, account = account,
            category = category, subtask = subtask, epicDomain = epicDomain,
        ).copy(
            epicId = epic, sprintIdAtDone = sprint, sprintTeamIdAtDone = sprintTeam,
            estimateSource = if (estimate == null) "NONE" else "OWN",
        )
        fun open(
            id: Long,
            team: UInt?,
            account: String?,
            estimate: Double?,
            epic: Long?,
            category: String?,
            startedDay: String? = "2026-01-05",
        ) =
            handTask(
                id, startedDay?.let { noonUtc(it) }, null, estimateCurrentMd = estimate, currentTeamId = team, currentAssignee = account,
                category = category,
            ).copy(epicId = epic, estimateSource = if (estimate == null) "NONE" else "OWN")
        val tasks = listOf(
            done(1, "2026-01-06", teamX, a1, estimate = 3.0),
            done(2, "2026-01-07", teamX, a1, worklogs = false, estimate = null, epic = null, category = null, sprint = null),
            done(3, "2026-01-08", teamX, a2, estimate = 5.0, category = null),
            done(4, "2026-01-09", teamY, b1, estimate = 2.0, epic = null, sprint = null, epicDomain = "BBB"),
            done(5, "2026-01-12", teamY, null, worklogs = false, estimate = 4.0, epic = 22, sprint = 200, sprintTeam = null),
            done(6, "2026-01-13", null, a3, estimate = 1.0, epic = 22, sprint = 200, sprintTeam = null),
            // Never counted: a sub-task, a task done before the period.
            done(7, "2026-01-14", teamX, a1, subtask = true), done(8, "2025-05-01", teamX, a1),
            open(11, teamX, a1, null, null, null), open(12, teamY, b1, 2.0, 21, "c"),
            // An open task that never started is not in the population.
            open(13, teamX, a1, null, null, null, startedDay = null),
        )
        fun wl(id: Long, account: String?, team: UInt?, day: String, lateMs: Long?, md: Double) = FactWorklogRow(
            worklogId = id, issueId = 500 + id, authorAccountId = account, authorTeamId = team, startedAt = noonUtc(day),
            createdAt = lateMs?.let { noonUtc(day) + it }, lateMs = lateMs, md = md, taskDomainKey = "AAA", epicId = null,
            epicDomainKey = null, activityType = "Story", workCategory = null, sprintIdAtStarted = null, sprintTeamIdAtStarted = null,
            foreignWork = false, assigneeAccountIdAtStarted = null, assigneeTeamIdAtStarted = null,
        )
        val worklogs = listOf(
            wl(1, a1, teamX, "2026-01-05", 2 * DAY_MS, 1.0), wl(2, a1, teamX, "2026-01-06", 8 * DAY_MS, 0.5),
            wl(3, a2, teamX, "2026-01-20", 0L, 1.5), wl(4, b1, teamY, "2026-01-07", DAY_MS, 1.0),
            wl(5, a9, null, "2026-01-08", 3 * DAY_MS, 2.0), wl(6, a1, teamX, "2026-01-09", null, 1.0),
            wl(7, a1, teamX, "2025-12-20", DAY_MS, 9.0), // before the period
        )
        val settingsConfig = DerivedStubFixture.metricsConfig()
        val settings = settingsConfig.read()
        val weekend = settings.weekendDays.toSet()
        val holidays = settings.holidays.map { LocalDate.parse(it) }.toSet()
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.insertFactWorklog(connId, worklogs, configRevision = 1L)
            MetricsConfigService.FieldConfig.insert {
                it[MetricsConfigService.FieldConfig.connectionId] = connId
                it[role] = "WORK_CATEGORY"
                it[fieldId] = "customfield_99999"
            }
            suspend fun member(account: String, team: UInt, from: String, to: String?) = membership.insert {
                it[accountId] = account
                it[teamId] = team
                it[validFrom] = startOfDay(zone, from)
                it[validTo] = to?.let { day -> startOfDay(zone, day) }
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
            member(a1, teamX, "2025-12-01", null)
            member(a2, teamX, "2026-01-15", null)
            member(b1, teamY, "2025-12-01", "2026-01-21")
            member(az, teamZ, "2025-12-01", null)
        }
            val client = seededClient("dq-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            // Every read pins hoursPerDay and the minimum sample size: the hours and the lateness distribution depend on them.
            suspend fun read(q: String) = withMetricsSettings(settingsConfig, { it.copy(hoursPerDay = 8.0, minSampleSize = 2) }) {
                client.dq(q)
            }
            val body = read(query)

            assertEquals(6, body.populations.doneTasks)
            assertEquals(2, body.populations.openStartedTasks)
            assertEquals(6, body.populations.worklogs)
            assertEquals(8.0, body.hoursPerDay, EPS)
            assertEquals(4.0 / 6.0, body.worklogCoverage.coverage!!, EPS)
            assertEquals(4, body.worklogCoverage.withWorklogs)
            assertEquals(listOf("HB-5", "HB-2"), body.worklogCoverage.without.items.map { it.issueKey }, "DONE newest first")
            assertEquals(2, body.worklogCoverage.without.total)

            // Task findings: DONE counted apart from open; sub-tasks, out-of-period and never-started tasks are not in it.
            fun counts(f: ch.nokillswit.reports.TaskFinding) = listOf(f.done, f.open, f.total)
            assertEquals(listOf(1, 1, 2), counts(body.missing.noEstimate))
            assertEquals(listOf("HB-2", "HB-11"), body.missing.noEstimate.items.map { it.issueKey }, "DONE first, then open")
            assertEquals(listOf(2, 1, 3), counts(body.missing.noEpic))
            assertEquals(listOf("HB-4", "HB-2", "HB-11"), body.missing.noEpic.items.map { it.issueKey }, "DONE newest first, then open")
            assertTrue(body.missing.workCategoryConfigured)
            assertEquals(listOf(2, 1, 3), counts(body.missing.noWorkCategory))
            assertEquals(listOf(1, 0, 1), counts(body.missing.unassigned))
            assertEquals(listOf("HB-5"), body.missing.unassigned.items.map { it.issueKey })
            assertEquals(4.0, body.missing.unassigned.md, EPS, "the unassigned task's estimate at done")
            assertEquals(listOf(2, 0, 2), counts(body.outsideSprint))
            assertEquals(2.0, body.outsideSprint.md, EPS)
            assertEquals(listOf(1, 0, 1), counts(body.crossDomain))
            assertEquals(listOf("HB-4"), body.crossDomain.items.map { it.issueKey })
            val ref = body.outsideSprint.items.single { it.issueKey == "HB-4" }
            assertEquals(listOf(teamY, b1, 2.0), listOf(ref.teamId, ref.assigneeAccountId, ref.estimateMd))
            assertEquals(noonUtc("2026-01-09"), ref.doneAt)
            val openRef = body.missing.noEstimate.items.single { it.issueKey == "HB-11" }
            assertNull(openRef.doneAt)
            assertEquals(noonUtc("2026-01-05"), openRef.startedAt)
            assertEquals(a1, openRef.assigneeAccountId)

            // Late logging: 6 worklogs in the period, 5 know their creation time (2, 8, 0, 1 and 3 days); exactly 1 day is not "over".
            assertEquals(6, body.lateLogging.worklogs)
            assertEquals(5, body.lateLogging.measurable)
            assertEquals(3, body.lateLogging.over1Day)
            assertEquals(1, body.lateLogging.over7Days)
            assertDistribution("lateness", listOf(2.0, 8.0, 0.0, 1.0, 3.0), body.lateLogging.distribution, 2)
            assertEquals(listOf(8.0, 3.0, 2.0, 1.0), body.lateLogging.worst.map { it.lateDays })
            val worstKeys = body.lateLogging.worst.map { it.issueKey }
            assertEquals(listOf("502", "505", "501", "504"), worstKeys, "hand-built issues have no key rows")
            assertEquals(listOf(a1, a9, a1, b1), body.lateLogging.worst.map { it.authorAccountId })
            assertEquals(listOf(teamX, null, teamX, teamY), body.lateLogging.worst.map { it.teamId })

            // Authors without a team: one, by account.
            assertEquals(1, body.authorsWithoutTeam.total)
            assertEquals(a9, body.authorsWithoutTeam.items.single().accountId)
            assertEquals(2.0, body.authorsWithoutTeam.items.single().md, EPS)
            assertEquals(1, body.authorsWithoutTeam.items.single().worklogs)

            // Logged hours per member-day: team members' MD × 8 over their roster working days (a2 joins on Jan 15, b1 leaves on Jan 21).
            val a1Days = workingDays("2026-01-01", "2026-02-01", weekend, holidays)
            val a2Days = workingDays("2026-01-15", "2026-02-01", weekend, holidays)
            val xDays = a1Days + a2Days
            val yDays = workingDays("2026-01-01", "2026-01-21", weekend, holidays)
            assertEquals((xDays + yDays).toDouble(), body.loggedHours.memberDays, 1e-6)
            assertEquals(5.0 * 8, body.loggedHours.hours, 1e-6)
            assertEquals(5.0 * 8 / (xDays + yDays), body.loggedHours.hoursPerMemberDay!!, 1e-9)

            // The UNIT drill: X, Y and the teamless bucket.
            fun taskCounts(g: ch.nokillswit.reports.DataQualityGroup) = g.tasks.let {
                listOf(it.done, it.openStarted, it.withoutWorklogs, it.unassigned, it.outsideSprint, it.crossDomain)
            }
            val gx = body.groups.single { it.teamId == teamX }
            assertEquals(listOf(3, 1, 1, 0, 1, 0), taskCounts(gx))
            val xPairs = listOf(gx.tasks.noEstimate, gx.tasks.noEpic, gx.tasks.noWorkCategory).map { it.done to it.open }
            assertEquals(listOf(1 to 1, 1 to 1, 2 to 1), xPairs)
            assertEquals(4, gx.worklogs.worklogs)
            assertEquals(4.0, gx.worklogs.md, EPS)
            assertEquals(2, gx.worklogs.over1Day)
            assertEquals(1, gx.worklogs.over7Days)
            assertEquals(32.0, gx.worklogs.hours, 1e-6)
            assertEquals(xDays.toDouble(), gx.worklogs.memberDays, 1e-6)
            assertEquals(32.0 / xDays, gx.worklogs.hoursPerMemberDay!!, 1e-9)
            val gy = body.groups.single { it.teamId == teamY }
            assertEquals(listOf(2, 1, 1, 1, 1, 1), taskCounts(gy))
            val yPairs = listOf(gy.tasks.noEstimate, gy.tasks.noEpic, gy.tasks.noWorkCategory).map { it.done to it.open }
            assertEquals(listOf(0 to 0, 1 to 0, 0 to 0), yPairs)
            assertEquals(8.0, gy.worklogs.hours, 1e-6)
            assertEquals(yDays.toDouble(), gy.worklogs.memberDays, 1e-6)
            val gnull = body.groups.single { it.teamId == null }
            assertEquals(listOf(1, 0, 0, 0, 0, 0), taskCounts(gnull))
            assertEquals(16.0, gnull.worklogs.hours, 1e-6)
            assertEquals(0.0, gnull.worklogs.memberDays, EPS)
            assertNull(gnull.worklogs.hoursPerMemberDay, "no roster to compare the teamless author's hours with")
            assertEquals(3, body.groups.size, "X, Y and the teamless bucket -- not the roster-only team Z")
            assertTrue(body.groups.none { it.teamId == teamZ })
            assertEquals(6, body.groups.sumOf { it.tasks.done })
            assertTrue(body.groups.all { it.epics?.epics == 0 })

            // TEAM level (X): per member, roster members included; USER level (X, a1): no groups.
            val team = read("$query&teamId=$teamX")
            assertEquals(3, team.populations.doneTasks)
            assertEquals(1, team.populations.openStartedTasks)
            assertEquals(setOf(a1, a2), team.groups.map { it.accountId }.toSet())
            val m1 = team.groups.single { it.accountId == a1 }
            assertEquals(listOf(2, 1, 1), listOf(m1.tasks.done, m1.tasks.openStarted, m1.tasks.withoutWorklogs))
            assertEquals(20.0, m1.worklogs.hours, 1e-6)
            assertEquals(workingDays("2026-01-01", "2026-02-01", weekend, holidays).toDouble(), m1.worklogs.memberDays, 1e-6)
            val m2 = team.groups.single { it.accountId == a2 }
            assertEquals(12.0, m2.worklogs.hours, 1e-6)
            assertEquals(workingDays("2026-01-15", "2026-02-01", weekend, holidays).toDouble(), m2.worklogs.memberDays, 1e-6)
            assertTrue(team.groups.all { it.teamId == null && it.epics == null })
            assertEquals(1, team.lateLogging.over7Days)
            assertEquals(0, team.authorsWithoutTeam.total)
            val user = read("$query&teamId=$teamX&accountId=$a1")
            assertTrue(user.groups.isEmpty())
            assertEquals(2, user.populations.doneTasks)
            assertEquals(1, user.populations.openStartedTasks)
            assertEquals(3, user.populations.worklogs)
            assertEquals(20.0, user.loggedHours.hours, 1e-6)

            // teamId=0: the UNASSIGNED task, the teamless author's worklog.
            val unassigned = read("$query&teamId=0")
            assertEquals(1, unassigned.populations.doneTasks)
            assertEquals(1, unassigned.populations.worklogs)
            assertEquals(1, unassigned.authorsWithoutTeam.total)
            assertEquals(0.0, unassigned.loggedHours.memberDays, EPS)
            // A period with no DONE task and no worklog still lists the open started tasks.
            val empty = client.dq("connectionId=$connId&from=2026-03-01&to=2026-03-31")
            assertEquals(0, empty.populations.doneTasks)
            assertEquals(2, empty.populations.openStartedTasks)
            assertEquals(0, empty.populations.worklogs)
            assertNull(empty.worklogCoverage.coverage)
            assertEquals(listOf(0, 1, 1), counts(empty.missing.noEpic))

            // The EPIC domain view slices by the epic's domain, else their own (A21): BBB is only HB-4's epic.
            val epicBbb = read("$query&domain=BBB&domainView=EPIC")
            val bbb = epicBbb.populations
            assertEquals(listOf(1, 0, 0), listOf(bbb.doneTasks, bbb.openStartedTasks, bbb.worklogs))
            assertEquals(listOf(1, 0, 1), counts(epicBbb.crossDomain))
            val epicAaa = read("$query&domain=AAA&domainView=EPIC")
            assertEquals(5, epicAaa.populations.doneTasks, "HB-4's epic is in BBB, the other five DONE tasks keep their own domain")
            assertEquals(2, epicAaa.populations.openStartedTasks)
            assertEquals(6, epicAaa.populations.worklogs)
            // UNCATEGORIZED selects items with no work category: DONE HB-2 and HB-3, open HB-11, and every (categoryless) worklog.
            val uncategorized = read("$query&workCategory=UNCATEGORIZED")
            val none = uncategorized.populations
            assertEquals(listOf(2, 1, 6), listOf(none.doneTasks, none.openStartedTasks, none.worklogs))
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteFactWorklog(connId)
                MetricsConfigService.FieldConfig.deleteWhere { MetricsConfigService.FieldConfig.connectionId eq connId }
            }
            cleanUpTeams(listOf(teamX, teamY, teamZ))
            suspendTransaction(sharedDatabaseForTests()) {
                membership.deleteWhere { membership.accountId inList listOf(a1, a2, b1, az) }
            }
        }
    }

    private suspend fun insertMembership(account: String, team: UInt, from: Long) = suspendTransaction(sharedDatabaseForTests()) {
        TeamMembershipService.TeamMembership.insert {
            it[accountId] = account
            it[teamId] = team
            it[validFrom] = from
            it[validTo] = null
            it[createdAt] = 1L
            it[updatedAt] = 1L
        }
    }

    @Test
    fun `the default read keeps a roster-only team with its member-days and zero hours, a connection-scoped read drops it`() =
        testApplication {
            usePostgresTestcontainer()
            val zone = reportZone()
            val team = TestTeams.seed(SyncedStubFixture.unique("dq-roster"))
            val account = SyncedStubFixture.unique("acc-roster")
            val other = SyncedStubFixture.createConnection(namePrefix = "dq-roster-other", enabled = false)
            try {
                insertMembership(account, team, startOfDay(zone, "2025-12-01"))
                val config = DerivedStubFixture.metricsConfig()
                val settings = config.read()
                val client = seededClient("dq-roster")
                val body = withMetricsSettings(config, { it.copy(hoursPerDay = 8.0) }) {
                    client.dq("from=2026-01-01&to=2026-01-31")
                }
                val silent = body.groups.single { it.teamId == team }
                val holidays = settings.holidays.map { LocalDate.parse(it) }.toSet()
                val days = workingDays("2026-01-01", "2026-02-01", settings.weekendDays.toSet(), holidays)
                assertEquals(days.toDouble(), silent.worklogs.memberDays, 1e-6)
                assertEquals(0, silent.worklogs.worklogs)
                assertEquals(0.0, silent.worklogs.hoursPerMemberDay!!, EPS, "a silent team logged nothing over its member-days")
                assertEquals(0, silent.tasks.done)
                assertEquals(0, silent.epics!!.epics)
                // Scoped to a connection where the team has no finding row, the global roster is pruned (the documented caveat).
                val scoped = client.dq("connectionId=$other&from=2026-01-01&to=2026-01-31")
                assertTrue(scoped.groups.none { it.teamId == team })
                assertEquals(0.0, scoped.loggedHours.memberDays, EPS)
            } finally {
                cleanUpTeams(listOf(team))
                suspendTransaction(sharedDatabaseForTests()) {
                    TeamMembershipService.TeamMembership.deleteWhere { TeamMembershipService.TeamMembership.accountId eq account }
                }
            }
        }

    @Test
    fun `mapping gaps - default and stored status maps, unmapped boards and the done tasks in teamless sprints nobody lists`() =
        testApplication {
            usePostgresTestcontainer()
            val store = MetricsStore(sharedDatabaseForTests())
            val statusConn = SyncedStubFixture.createConnection(namePrefix = "dq-statuses", enabled = false)
            val boardConn = SyncedStubFixture.createConnection(namePrefix = "dq-boards", enabled = false)
            val team = TestTeams.seed(SyncedStubFixture.unique("dq-map"))
            try {
                suspendTransaction(sharedDatabaseForTests()) {
                    val st = WorkItemStore.Statuses
                    val statuses = listOf(
                        Triple("1", "To Do", "TODO"), Triple("2", "Weird", "UNKNOWN"), Triple("3", "Working", "IN_PROGRESS"),
                    )
                    statuses.forEach { (id, name, category) ->
                        st.insert {
                            it[connectionId] = statusConn
                            it[statusId] = id
                            it[st.name] = name
                            it[st.category] = category
                        }
                    }
                    val b = WorkItemStore.Boards
                    listOf(9L to "Unmapped board", 10L to "Mapped board").forEach { (id, name) ->
                        b.insert {
                            it[connectionId] = boardConn
                            it[boardId] = id
                            it[b.name] = name
                            it[boardType] = "scrum"
                            it[projectKey] = "AAA"
                        }
                    }
                    val sp = WorkItemStore.Sprints
                    listOf(900L to 9L, 901L to null, 902L to 10L).forEach { (id, board) ->
                        sp.insert {
                            it[connectionId] = boardConn
                            it[sprintId] = id
                            it[boardId] = board
                            it[sp.name] = "Sprint $id"
                            it[state] = "closed"
                        }
                    }
                    MetricsConfigService.BoardTeamMap.insert {
                        it[MetricsConfigService.BoardTeamMap.connectionId] = boardConn
                        it[boardId] = 10L
                        it[teamId] = team
                    }
                }
                val started = noonUtc("2025-12-15")
                fun done(id: Long, sprint: Long?, sprintTeam: UInt?) =
                    handTask(id, started, noonUtc("2026-01-10") + id, creditTeamId = team)
                        .copy(sprintIdAtDone = sprint, sprintTeamIdAtDone = sprintTeam)
                suspendTransaction(sharedDatabaseForTests()) {
                    store.replaceFactTaskDelivery(
                        boardConn,
                        listOf(
                            done(1, 900, null), done(2, 900, null), // in the unmapped board's sprint
                            done(3, 901, null), // a sprint with no board at all
                            done(4, 902, null), // a MAPPED board whose sprint nevertheless carries no team
                            done(5, 900, team), // a sprint with a team: counted by neither
                            done(6, null, null), // no sprint at all: "outside any sprint", not here
                        ),
                        configRevision = 1L,
                    )
                }
                val client = seededClient("dq-mapping")
                val query = "from=2026-01-01&to=2026-01-31"

                val boards = client.dq("connectionId=$boardConn&$query").unmappedBoards
                assertEquals(1, boards.total)
                val board = boards.items.single()
                assertEquals(9L, board.boardId)
                assertEquals(1, board.sprints, "only sprint 900 is on board 9")
                assertEquals(2, board.doneTasks, "tasks 1 and 2; task 5 was done in a sprint WITH a team")
                assertEquals(2, boards.unattributedDoneTasks, "tasks 3 (no board) and 4 (mapped board, teamless sprint)")

                // No stored configuration: the computed defaults map every status whose Jira category has a default stage.
                val defaults = client.dq("connectionId=$statusConn&$query").unmappedStatuses
                assertEquals(listOf("2"), defaults.items.map { it.statusId })
                val weird = defaults.items.single()
                assertEquals(listOf("Weird", "UNKNOWN"), listOf(weird.name, weird.category))
                assertEquals(listOf(0, 0), listOf(weird.items, weird.openItems))
                // One stored row makes it a stored configuration: only its stage map counts, so the other statuses are unmapped.
                suspendTransaction(sharedDatabaseForTests()) {
                    MetricsConfigService.StatusStageMap.insert {
                        it[MetricsConfigService.StatusStageMap.connectionId] = statusConn
                        it[statusId] = "1"
                        it[stage] = "NOT_STARTED"
                    }
                }
                val stored = client.dq("connectionId=$statusConn&$query").unmappedStatuses
                assertEquals(listOf("2", "3"), stored.items.map { it.statusId })
                assertEquals(listOf("Weird", "Working"), stored.items.map { it.name })
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteFactTaskDelivery(boardConn)
                    WorkItemStore.Statuses.deleteWhere { WorkItemStore.Statuses.connectionId eq statusConn }
                    WorkItemStore.Boards.deleteWhere { WorkItemStore.Boards.connectionId eq boardConn }
                    WorkItemStore.Sprints.deleteWhere { WorkItemStore.Sprints.connectionId eq boardConn }
                    MetricsConfigService.BoardTeamMap.deleteWhere { MetricsConfigService.BoardTeamMap.connectionId eq boardConn }
                    MetricsConfigService.StatusStageMap.deleteWhere { MetricsConfigService.StatusStageMap.connectionId eq statusConn }
                }
                cleanUpTeams(listOf(team))
            }
        }

    @Test
    fun `a list longer than the cap reports its total and the newest fifty`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val connId = SyncedStubFixture.createConnection(namePrefix = "dq-cap", enabled = false)
        try {
            val tasks = (1L..55L).map { id ->
                handTask(id, noonUtc("2025-12-15"), noonUtc("2026-01-10") + id, estimateAtDoneMd = 1.0)
                    .copy(epicId = null, sprintIdAtDone = 1, estimateSource = "OWN")
            }
            suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L) }
            val body = seededClient("dq-cap").dq("connectionId=$connId&from=2026-01-01&to=2026-01-31")
            assertEquals(55, body.missing.noEpic.total)
            assertEquals(55, body.missing.noEpic.done)
            assertEquals(50, body.missing.noEpic.items.size)
            assertEquals(55.0, body.missing.noEpic.md, EPS)
            assertEquals((55L downTo 6L).map { "HB-$it" }, body.missing.noEpic.items.map { it.issueKey }, "the 50 newest, newest first")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
        }
    }

    @Test
    fun `work category is counted only for connections that configure the field`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val configured = SyncedStubFixture.createConnection(namePrefix = "dq-wc-yes", enabled = false)
        val plain = SyncedStubFixture.createConnection(namePrefix = "dq-wc-no", enabled = false)
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsConfigService.FieldConfig.insert {
                    it[MetricsConfigService.FieldConfig.connectionId] = configured
                    it[role] = "WORK_CATEGORY"
                    it[fieldId] = "customfield_99999"
                }
                for (connId in listOf(configured, plain)) {
                    store.replaceFactTaskDelivery(
                        connId, listOf(handTask(1, noonUtc("2025-12-15"), noonUtc("2026-01-10"), creditTeamId = null)), configRevision = 1L,
                    )
                }
            }
            val client = seededClient("dq-wc")
            val query = "from=2026-01-01&to=2026-01-31"
            val yes = client.dq("connectionId=$configured&$query")
            assertTrue(yes.missing.workCategoryConfigured)
            assertEquals(1, yes.missing.noWorkCategory.done)
            val no = client.dq("connectionId=$plain&$query")
            assertFalse(no.missing.workCategoryConfigured)
            assertEquals(0, no.missing.noWorkCategory.total, "the same task on a connection without the field is not a finding")
            // Both connections in scope (every connection): only the configured ones' categoryless tasks count.
            val t = MetricsStore.FactTaskDelivery
            val bounds = windowBounds(reportZone(), "2026-01-01", "2026-01-31")
            val expected = suspendTransaction(sharedDatabaseForTests()) {
                val configuredIds = MetricsConfigService.FieldConfig.selectAll()
                    .where { MetricsConfigService.FieldConfig.role eq "WORK_CATEGORY" }.toList()
                    .map { it[MetricsConfigService.FieldConfig.connectionId].value }.toSet()
                t.selectAll().where { t.isSubtask eq false }.toList().count {
                    val doneAt = it[t.doneAt]
                    it[t.connectionId].value in configuredIds && doneAt != null && doneAt >= bounds.first && doneAt < bounds.second &&
                        it[t.workCategory] == null
                }
            }
            val all = client.dq(query)
            assertTrue(all.missing.workCategoryConfigured)
            assertEquals(expected, all.missing.noWorkCategory.done)
            assertTrue(expected >= 1)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(configured)
                store.deleteFactTaskDelivery(plain)
                MetricsConfigService.FieldConfig.deleteWhere { MetricsConfigService.FieldConfig.connectionId eq configured }
            }
        }
    }

    @Test
    fun `a user role reads the report and every bad filter is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("dq-400")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/data-quality").status, "D12: any signed-in user, default period")
        val badQueries = listOf(
            "from=2026-01-01&to=2025-01-01", "accountId=abc", "connectionId=999999", "teamId=999999", "sprintId=999999999",
            "lastSprints=0", "domainView=NOPE",
        )
        for (query in badQueries) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/data-quality?$query").status, query)
        }
    }
}
