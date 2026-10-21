package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.CommitmentsReport
import ch.nokillswit.reports.CommitmentsSprint
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ThroughputReport
import ch.nokillswit.reports.ThroughputSprint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * The by-epic split of Commitments' and Throughput's sprint rows on the DERIVED stub fixture (read-only): at UNIT, TEAM and USER level
 * (two picked people) the segments add up to every sprint row, each epic segment equals an INDEPENDENT re-derivation from the stored
 * scope rows and `dim_task.epic_id` (never the production rule), the keys come in the one order, and the drill's `epicSegment` bar
 * equals the segment's figure. The top-6 fold's tie-breaks and `OTHER_EPICS` with more than six epics are pinned on a hand-built world
 * (`ReportSprintEpicSegmentsHandBuiltTest`).
 */
class ReportSprintEpicSegmentsTest {

    private class Scope(
        val connectionId: UInt,
        val sprintId: Long,
        val issueId: Long,
        val assignee: String?,
        val committed: Boolean,
        val inScopeAtClose: Boolean,
        val commitMd: BigDecimal?,
        val closeMd: BigDecimal?,
        val doneMd: BigDecimal?,
        val doneInSprint: Boolean,
        val epicId: Long?,
    )

    private suspend fun storedScope(connId: UInt): List<Scope> = suspendTransaction(sharedDatabaseForTests()) {
        val s = MetricsTables.FactSprintScope
        val d = MetricsTables.DimTask
        val epics = d.selectAll().where { d.connectionId eq connId }.toList().associate { it[d.issueId] to it[d.epicId] }
        s.selectAll().where { s.connectionId eq connId }.toList().map {
            Scope(
                connId, it[s.sprintId], it[s.issueId], it[s.assigneeAtCommitment], it[s.committed], it[s.inScopeAtClose],
                it[s.estimateAtCommitmentMd], it[s.estimateAtCloseMd], it[s.estimateAtDoneMd], it[s.doneInSprint], epics[it[s.issueId]],
            )
        }
    }

    private fun List<BigDecimal?>.md(): Double =
        fold(BigDecimal.ZERO) { acc, v -> acc + (v ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP) }.toDouble()

    private suspend inline fun <reified T> HttpClient.report(path: String): T {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, "GET $path")
        return response.body()
    }

    private suspend fun floTeamId(connId: UInt): UInt = suspendTransaction(sharedDatabaseForTests()) {
        val f = MetricsTables.FactSprint
        f.selectAll().where { f.connectionId eq connId }.toList().mapNotNull { it[f.teamId]?.value }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }!!.key
    }

    /** The order rule: epic segments first (any order among them), then `OTHER_EPICS`, then `NO_EPIC`. */
    private fun assertOrder(label: String, keys: List<String>) {
        assertEquals(keys.distinct(), keys, "$label segment keys are unique")
        val epics = keys.filter { it != "OTHER_EPICS" && it != "NO_EPIC" }
        assertTrue(epics.size <= 6, "$label at most six epic segments")
        val special = keys.filter { it == "OTHER_EPICS" } + keys.filter { it == "NO_EPIC" }
        assertEquals(epics + special, keys, "$label epics, OTHER_EPICS, NO_EPIC")
    }

    /** The expected `key -> epic` mapping of a response: the segments' own epic ids are its top epics. */
    private fun keyOf(epicId: Long?, top: Set<Long>): String = when {
        epicId == null -> "NO_EPIC"
        epicId in top -> epicId.toString()
        else -> "OTHER_EPICS"
    }

    private fun narrow(key: String, top: Set<Long>) =
        "epicSegment=$key" + if (key == "OTHER_EPICS") top.joinToString("") { "&topEpicId=$it" } else ""

    private fun checkCommitments(label: String, sprints: List<CommitmentsSprint>, stored: List<Scope>, accounts: Set<String>?) {
        val top = sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
        for (sprint in sprints) {
            val rows = stored.filter { it.sprintId == sprint.sprintId && (accounts == null || it.assignee in accounts) }
            val final = rows.filter { it.inScopeAtClose }
            val expected = final.groupBy { keyOf(it.epicId, top) }
            assertOrder("$label/${sprint.sprintId}", sprint.byEpic.map { it.key })
            assertEquals(expected.keys, sprint.byEpic.map { it.key }.toSet(), "$label/${sprint.sprintId} a segment per final item")
            for (segment in sprint.byEpic) {
                val inSegment = expected.getValue(segment.key)
                val committed = inSegment.filter { it.committed }
                assertEquals(inSegment.size, segment.finalItems, "$label/${sprint.sprintId}/${segment.key} finalItems")
                assertEquals(inSegment.map { it.closeMd }.md(), segment.finalMd, MD_TOLERANCE, "$label/${segment.key} finalMd")
                assertEquals(committed.size, segment.initialItems, "$label/${sprint.sprintId}/${segment.key} initialItems")
                assertEquals(committed.map { it.commitMd }.md(), segment.initialMd, MD_TOLERANCE, "$label/${segment.key} initialMd")
                if (segment.epicId != null) {
                    assertEquals(segment.epicId.toString(), segment.key)
                    val epicKey = segment.epicKey!!
                    assertTrue(epicKey.isNotBlank() && segment.label.startsWith(epicKey), "$label ${segment.key} identity")
                }
            }
            assertEquals(sprint.initialMd, sprint.byEpic.sumOf { it.initialMd }, MD_TOLERANCE, "$label/${sprint.sprintId} Σ initialMd")
            assertEquals(sprint.initialItems, sprint.byEpic.sumOf { it.initialItems }, "$label/${sprint.sprintId} Σ initialItems")
            assertEquals(sprint.finalMd, sprint.byEpic.sumOf { it.finalMd }, MD_TOLERANCE, "$label/${sprint.sprintId} Σ finalMd")
            assertEquals(sprint.finalItems, sprint.byEpic.sumOf { it.finalItems }, "$label/${sprint.sprintId} Σ finalItems")
        }
    }

    private fun checkThroughput(label: String, sprints: List<ThroughputSprint>, stored: List<Scope>, accounts: Set<String>?) {
        val top = sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
        for (sprint in sprints) {
            val delivered = stored.filter {
                it.sprintId == sprint.sprintId && it.doneInSprint && (accounts == null || it.assignee in accounts)
            }
            val expected = delivered.groupBy { keyOf(it.epicId, top) }
            assertOrder("$label/${sprint.sprintId}", sprint.byEpic.map { it.key })
            assertEquals(expected.keys, sprint.byEpic.map { it.key }.toSet(), "$label/${sprint.sprintId} a segment per delivery")
            for (segment in sprint.byEpic) {
                assertEquals(expected.getValue(segment.key).size, segment.deliveredItems, "$label/${segment.key} deliveredItems")
                val inSegment = expected.getValue(segment.key)
                assertEquals(inSegment.map { it.doneMd }.md(), segment.deliveredMd, MD_TOLERANCE, "$label/${segment.key} MD")
            }
            assertEquals(sprint.deliveredMd, sprint.byEpic.sumOf { it.deliveredMd }, MD_TOLERANCE, "$label/${sprint.sprintId} Σ MD")
            assertEquals(sprint.deliveredItems, sprint.byEpic.sumOf { it.deliveredItems }, "$label/${sprint.sprintId} Σ deliveredItems")
        }
    }

    @Test
    fun `every sprint's epic segments add up to its row and equal an independent re-derivation at UNIT, TEAM and USER level`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val stored = storedScope(connId)
            val team = floTeamId(connId)
            val client = seededClient("reports-epic-seg-fixture")
            val base = "connectionId=$connId&lastSprints=52"
            val teamSprintIds = client.report<CommitmentsReport>("/api/v1/reports/commitments?$base&teamId=$team").sprints
                .map { it.sprintId }.toSet()
            val busiest = stored.filter { it.sprintId in teamSprintIds }.mapNotNull { it.assignee }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
            assertEquals(2, busiest.size)

            val unitCommit = client.report<CommitmentsReport>("/api/v1/reports/commitments?$base")
            val unitThrough = client.report<ThroughputReport>("/api/v1/reports/throughput?$base")
            assertTrue(unitCommit.sprints.size > 1)
            assertTrue(unitCommit.sprints.any { sprint -> sprint.byEpic.any { it.epicId != null } }, "the fixture has epic-bound scope")
            checkCommitments("UNIT", unitCommit.sprints, stored, null)
            checkThroughput("UNIT", unitThrough.bySprint, stored, null)

            val teamBase = "$base&teamId=$team"
            checkCommitments("TEAM", client.report<CommitmentsReport>("/api/v1/reports/commitments?$teamBase").sprints, stored, null)
            checkThroughput("TEAM", client.report<ThroughputReport>("/api/v1/reports/throughput?$teamBase").bySprint, stored, null)

            val userBase = "$teamBase&" + busiest.joinToString("&") { "accountId=$it" }
            val userCommit = client.report<CommitmentsReport>("/api/v1/reports/commitments?$userBase")
            checkCommitments("USER", userCommit.sprints, stored, busiest.toSet())
            val userThrough = client.report<ThroughputReport>("/api/v1/reports/throughput?$userBase")
            checkThroughput("USER", userThrough.bySprint, stored, busiest.toSet())
            assertTrue(userCommit.sprints.any { it.byEpic.isNotEmpty() }, "the picks have scope")
        }

    @Test
    fun `the drill's epicSegment bar equals the segment's figure for every segment of every fixture sprint`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val team = floTeamId(connId)
        val client = seededClient("reports-epic-seg-fixture-drill")
        var drilled = 0

        for (scope in listOf("", "&teamId=$team")) {
            val base = "connectionId=$connId&lastSprints=52$scope"
            val commitments = client.report<CommitmentsReport>("/api/v1/reports/commitments?$base")
            val throughput = client.report<ThroughputReport>("/api/v1/reports/throughput?$base")
            val commitTop = commitments.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
            val throughTop = throughput.bySprint.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
            for (sprint in commitments.sprints) {
                val one = "connectionId=$connId&sprintId=${sprint.sprintId}&teamId=${sprint.teamId}"
                for (segment in sprint.byEpic) {
                    for ((bucket, md, items) in listOf(
                        Triple("COMMITTED", segment.initialMd, segment.initialItems), Triple("FINAL", segment.finalMd, segment.finalItems),
                    )) {
                        val drill = client.report<ReportDrillItems>(
                            "/api/v1/reports/sprint-consistency/items?$one&bucket=$bucket&${narrow(segment.key, commitTop)}",
                        )
                        assertEquals(items, drill.bar.count, "${sprint.sprintId}/${segment.key}/$bucket count")
                        assertEquals(md, drill.bar.sumMd!!, MD_TOLERANCE, "${sprint.sprintId}/${segment.key}/$bucket MD")
                        assertEquals(items, drill.items.size)
                        drilled++
                    }
                }
            }
            for (sprint in throughput.bySprint) {
                val one = "connectionId=$connId&sprintId=${sprint.sprintId}&teamId=${sprint.teamId}"
                for (segment in sprint.byEpic) {
                    val drill = client.report<ReportDrillItems>("/api/v1/reports/throughput/items?$one&${narrow(segment.key, throughTop)}")
                    assertEquals(segment.deliveredItems, drill.bar.count, "${sprint.sprintId}/${segment.key} delivered count")
                    assertEquals(segment.deliveredMd, drill.bar.sumMd!!, MD_TOLERANCE, "${sprint.sprintId}/${segment.key} delivered MD")
                    drilled++
                }
            }
        }
        assertTrue(drilled > 20, "the fixture exercised the drill ($drilled)")
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        const val MD_TOLERANCE = 0.005
    }
}
