package ch.nokillswit

import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ThroughputReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * Throughput's ACTUAL COST on the derived stub fixture (read-only): every `actualCostMd` -- the period buckets at UNIT / TEAM / USER level
 * for WEEK and MONTH, the activity-type slice, the closed sprints at TEAM / USER level -- equals the [ActualCostOracle]'s independent
 * re-derivation from `fact_worklog` and the item sets, and every bar's `measure=ACTUAL_COST` drill reconciles with its figure and (at USER
 * level) with each person's `byUser` share. The rules themselves are pinned on a hand-built world in
 * `ReportThroughputActualCostHandBuiltTest`.
 */
class ReportThroughputActualCostTest {

    private val from = LocalDate.parse("2025-06-01")
    private val to = LocalDate.parse("2026-03-05")
    private val range get() = "from=$from&to=$to"

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

    private fun assertBuckets(expected: Map<String, Double>, report: ThroughputReport, label: String) {
        assertEquals(expected.keys.toList(), report.byBucket.map { it.bucketStart }, "$label buckets")
        for (row in report.byBucket) assertEquals(expected.getValue(row.bucketStart), row.actualCostMd, 1e-9, "$label ${row.bucketStart}")
    }

    @Test
    fun `the period actual cost equals an independent re-derivation at UNIT and TEAM level, WEEK and MONTH`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val oracle = ActualCostOracle.load(connId)
        val team = oracle.busiestTeam
        val client = seededClient("reports-throughput-ac-fixture-levels")
        for (bucket in listOf("WEEK", "MONTH")) {
            val unit = client.throughput("connectionId=$connId&$range&bucket=$bucket")
            assertBuckets(oracle.period(from, to, bucket), unit, "UNIT/$bucket")
            assertTrue(unit.byBucket.sumOf { it.actualCostMd } > 0.0, "the fixture logs time")
            assertTrue(unit.byBucket.all { it.byUser.isEmpty() })
            val teamLevel = client.throughput("connectionId=$connId&$range&bucket=$bucket&teamId=$team")
            assertBuckets(oracle.period(from, to, bucket, team = team), teamLevel, "TEAM/$bucket")
            assertTrue(teamLevel.byBucket.sumOf { it.actualCostMd } <= unit.byBucket.sumOf { it.actualCostMd } + 1e-9)
        }
    }

    @Test
    fun `the activity-type slice selects the items for the period view and is ignored by the sprint view`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val oracle = ActualCostOracle.load(connId)
        val team = oracle.busiestTeam
        val client = seededClient("reports-throughput-ac-fixture-slice")
        assertTrue(oracle.activityTypes.isNotEmpty())
        for (activity in oracle.activityTypes) {
            val sliced = client.throughput("connectionId=$connId&$range&teamId=$team&activityType=$activity")
            assertBuckets(oracle.period(from, to, "WEEK", team = team, activityType = activity), sliced, "slice $activity")
        }
        val sprint = oracle.sprints.first { it.teamId == team && it.completeAt != null }
        val plain = client.throughput("connectionId=$connId&teamId=$team&sprintId=${sprint.sprintId}").bySprint.single()
        val slice = "activityType=${oracle.activityTypes.first()}"
        val sliced = client.throughput("connectionId=$connId&teamId=$team&sprintId=${sprint.sprintId}&$slice")
        assertEquals(plain.actualCostMd, sliced.bySprint.single().actualCostMd, 1e-9, "the sprint view carries no slice")
    }

    @Test
    fun `at USER level the picked authors' time and each person's share equal the independent re-derivation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val oracle = ActualCostOracle.load(connId)
        val team = oracle.busiestTeam
        val (a, b) = oracle.topAuthors(2)
        val client = seededClient("reports-throughput-ac-fixture-user")
        for (bucket in listOf("WEEK", "MONTH")) {
            val report = client.throughput("connectionId=$connId&$range&bucket=$bucket&teamId=$team&accountId=$a&accountId=$b")
            assertBuckets(oracle.period(from, to, bucket, team = team, authors = setOf(a, b)), report, "USER/$bucket")
            val single = oracle.period(from, to, bucket, team = team, authors = setOf(a))
            val other = oracle.period(from, to, bucket, team = team, authors = setOf(b))
            for (row in report.byBucket) {
                assertEquals(listOf(a, b), row.byUser.map { it.accountId }, "request order")
                assertEquals(single.getValue(row.bucketStart), row.byUser[0].actualCostMd, 1e-9, "$a ${row.bucketStart}")
                assertEquals(other.getValue(row.bucketStart), row.byUser[1].actualCostMd, 1e-9, "$b ${row.bucketStart}")
            }
            assertTrue(report.byBucket.sumOf { it.actualCostMd } > 0.0, "the busiest authors logged time on the team's items")
        }
    }

    @Test
    fun `every closed sprint's actual cost equals the final scope's time between its dates at TEAM and USER level`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val oracle = ActualCostOracle.load(connId)
        val (a, b) = oracle.topAuthors(2)
        val client = seededClient("reports-throughput-ac-fixture-sprints")
        val closed = oracle.sprints.filter { it.completeAt != null }
        assertTrue(closed.isNotEmpty())
        var positive = 0
        for (sprint in closed) {
            val query = "connectionId=$connId&teamId=${sprint.teamId}&sprintId=${sprint.sprintId}"
            val row = client.throughput(query).bySprint.single()
            assertEquals(oracle.sprint(sprint), row.actualCostMd, 1e-9, "sprint ${sprint.sprintId}")
            if (row.actualCostMd > 0.0) positive++
            val users = client.throughput("$query&accountId=$a&accountId=$b").bySprint.single()
            assertEquals(oracle.sprint(sprint, setOf(a, b)), users.actualCostMd, 1e-9, "sprint ${sprint.sprintId} USER")
            assertEquals(oracle.sprint(sprint, setOf(a)), users.byUser[0].actualCostMd, 1e-9)
            assertEquals(oracle.sprint(sprint, setOf(b)), users.byUser[1].actualCostMd, 1e-9)
            val drill = client.items(query)
            assertEquals(row.actualCostMd, drill.bar.sumMd!!, 1e-9, "sprint ${sprint.sprintId} drill bar")
            assertEquals(drill.bar.count, drill.total)
            assertEquals(row.actualCostMd, drill.items.sumOf { it.figure!! }, 0.005 * (drill.items.size + 1), "Σ figure")
        }
        assertTrue(positive > 0, "the fixture's sprints log time")
    }

    @Test
    fun `every bar of the period chart reconciles with its actual-cost drill at UNIT, TEAM and USER level`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val oracle = ActualCostOracle.load(connId)
        val team = oracle.busiestTeam
        val (a, b) = oracle.topAuthors(2)
        val client = seededClient("reports-throughput-ac-fixture-drill")
        val scopes = listOf("UNIT" to "", "TEAM" to "&teamId=$team", "USER" to "&teamId=$team&accountId=$a&accountId=$b")
        for ((level, scope) in scopes) {
            val query = "connectionId=$connId&$range&bucket=WEEK$scope"
            val report = client.throughput(query)
            var listed = 0
            for (row in report.byBucket) {
                val drill = client.items("$query&bucketStart=${row.bucketStart}")
                assertEquals(row.actualCostMd, drill.bar.sumMd!!, 1e-9, "$level ${row.bucketStart} bar")
                assertEquals(drill.bar.count, drill.total, "$level ${row.bucketStart} total")
                assertEquals(drill.total, drill.items.size)
                assertEquals(row.actualCostMd, drill.items.sumOf { it.figure!! }, 0.005 * (drill.items.size + 1), "$level Σ figure")
                listed += drill.items.size
                if (level == "USER") {
                    var segments = 0.0
                    for (share in row.byUser) {
                        val segment = client.items("$query&bucketStart=${row.bucketStart}&segmentAccountId=${share.accountId}")
                        assertEquals(share.actualCostMd, segment.bar.sumMd!!, 1e-9, "${share.accountId} ${row.bucketStart}")
                        segments += segment.bar.sumMd
                    }
                    assertEquals(row.actualCostMd, segments, 0.005 * 3, "${row.bucketStart} the segments add up to the bar")
                }
            }
            assertTrue(listed > 0, "$level lists tasks")
        }
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
