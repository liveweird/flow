package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.CostsReport
import ch.nokillswit.reports.CostsTrendWeek
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val EPS = 1e-9

/**
 * Costs' work-category widgets (`categoryTargets`, `categoryShareTrend`, domain-model A45) on the HAND-BUILT world
 * ([withCostsCategoriesWorld]: layout and every worklog documented there; the stub maps no work category): the target-vs-actual rows with
 * their delta, order and unmapped flag, the unconfigured team, the weekly cumulative share (zero week, year boundary, the same keys in
 * every week, the configured zone), the rounding rule, the slices and levels, the 160-week bound and the totals reconciling. The derived
 * fixture is graded in `ReportCostsSprintsFixtureTest`.
 */
class ReportCostsCategoriesTest {

    private suspend fun HttpClient.costs(query: String): CostsReport {
        val response = get("/api/v1/reports/costs?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET costs?$query")
        return response.body()
    }

    /** A week as `(weekStart, cumulativeMd, [each category's cumulativeMd in the response's key order])`. */
    private fun CostsTrendWeek.figures() = Triple(weekStart, cumulativeMd, byCategory.map { it.cumulativeMd })

    /** The reconciliation every team read must satisfy: the table's total, the last week's cumulative and `totalMd` are one figure. */
    private fun assertReconciles(report: CostsReport) {
        report.categoryTargets?.let { assertEquals(report.totalMd, it.totalMd, "targets total == totalMd") }
        val weeks = report.categoryShareTrend?.weeks.orEmpty()
        if (weeks.isNotEmpty()) assertEquals(report.totalMd, weeks.last().cumulativeMd, "last cumulative == totalMd")
        for (week in weeks) {
            val parts = week.byCategory.sumOf { it.cumulativeMd }
            assertEquals(week.cumulativeMd, parts, 0.006 * week.byCategory.size, "week ${week.weekStart}")
        }
        report.categoryTargets?.let { targets ->
            val byKey = report.byWorkCategory.associate { it.key to it.md }
            for (row in targets.rows) assertEquals(byKey[row.key] ?: 0.0, row.actualMd, EPS, "${row.key} == byWorkCategory")
        }
    }

    @Test
    fun `the team's targets sit beside its actual share - delta in points, target order, the unmapped flag`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val report = seededClient("cc-targets", FlowUserRole.USER).costs("${w.range}&teamId=${w.team}")
            assertEquals(9.5, report.totalMd, "T's members only: carol's 9.0 and the two 7.0 outside the period are not in it")
            val targets = assertNotNull(report.categoryTargets)
            assertTrue(targets.configured)
            assertEquals(9.5, targets.totalMd)
            // Order: target descending (none = 0), then actual MD descending, then key (Spike and "no category" tie on 0.5 at target 0).
            assertEquals(listOf(w.run, w.build, w.retired, w.spike, "UNCATEGORIZED"), targets.rows.map { it.key })
            fun row(key: String) = targets.rows.single { it.key == key }
            // Run: 5.5 / 9.5 = 57.8947 % against 60 -> -2.1053 -> -2.1; Build: 3 / 9.5 = 31.5789 % against 30 -> +1.6.
            assertEquals(60, row(w.run).targetPercent)
            assertEquals(5.5, row(w.run).actualMd)
            assertEquals(5.5 / 9.5, row(w.run).actualShare!!, EPS)
            assertEquals(-2.1, row(w.run).deltaPoints)
            assertEquals(30, row(w.build).targetPercent)
            assertEquals(3.0, row(w.build).actualMd)
            assertEquals(1.6, row(w.build).deltaPoints)
            // A stored target nobody logged on, whose category lost its mapping: share 0 (not null), delta = -target, flagged unmapped.
            assertEquals(10, row(w.retired).targetPercent)
            assertEquals(0.0, row(w.retired).actualMd)
            assertEquals(0.0, row(w.retired).actualShare)
            assertEquals(-10.0, row(w.retired).deltaPoints)
            assertEquals(listOf(true, true, false, false, true), targets.rows.map { it.mapped })
            // Logged but not targeted: a configured set counts it as target 0; 0.5 / 9.5 = 5.2632 % -> +5.3.
            assertEquals(0, row(w.spike).targetPercent)
            assertEquals(0.5, row(w.spike).actualMd)
            assertEquals(5.3, row(w.spike).deltaPoints)
            assertEquals(5.3, row("UNCATEGORIZED").deltaPoints)
            assertReconciles(report)
        }
    }

    @Test
    fun `the weekly cumulative share is zero-filled, crosses the year, and every week lists every category in the same order`() =
        testApplication {
            usePostgresTestcontainer()
            withCostsCategoriesWorld { w ->
                val report = seededClient("cc-trend", FlowUserRole.USER).costs("${w.range}&teamId=${w.team}")
                val weeks = assertNotNull(report.categoryShareTrend).weeks
                // Key order = byWorkCategory's: Run 5.5, Build 3.0, then the 0.5 tie by key -> Spike, UNCATEGORIZED.
                assertEquals(listOf(w.run, w.build, w.spike, "UNCATEGORIZED"), report.byWorkCategory.map { it.key })
                assertEquals(
                    listOf(
                        Triple("2025-12-15", 4.0, listOf(3.0, 1.0, 0.0, 0.0)),
                        Triple("2025-12-22", 6.0, listOf(4.5, 1.0, 0.0, 0.5)),
                        Triple("2025-12-29", 6.0, listOf(4.5, 1.0, 0.0, 0.5)), // no time this week: the cumulative carries over
                        Triple("2026-01-05", 8.5, listOf(4.5, 3.0, 0.5, 0.5)),
                        Triple("2026-01-12", 9.5, listOf(5.5, 3.0, 0.5, 0.5)),
                    ),
                    weeks.map { it.figures() },
                )
                for (week in weeks) assertEquals(listOf(w.run, w.build, w.spike, "UNCATEGORIZED"), week.byCategory.map { it.key })
                assertEquals(0.75, weeks[0].byCategory[0].share!!, EPS)
                assertEquals(0.25, weeks[0].byCategory[1].share!!, EPS)
                assertEquals(0.0, weeks[0].byCategory[2].share!!, EPS)
                assertEquals(5.5 / 9.5, weeks[4].byCategory[0].share!!, EPS)
                assertReconciles(report)
            }
        }

    @Test
    fun `a team with no targets configured still carries its actuals, with no target and no delta`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val report = seededClient("cc-unconfigured", FlowUserRole.USER).costs("${w.range}&teamId=${w.otherTeam}")
            val targets = assertNotNull(report.categoryTargets)
            assertEquals(false, targets.configured)
            assertEquals(9.0, targets.totalMd)
            val row = targets.rows.single()
            assertEquals(w.run, row.key)
            assertNull(row.targetPercent)
            assertEquals(9.0, row.actualMd)
            assertEquals(1.0, row.actualShare)
            assertNull(row.deltaPoints)
            assertTrue(row.mapped)
            assertEquals(listOf("2025-12-15" to 9.0), assertNotNull(report.categoryShareTrend).weeks.filter { it.cumulativeMd > 0 }.take(1)
                .map { it.weekStart to it.cumulativeMd })
            assertReconciles(report)
        }
    }

    @Test
    fun `the delta is rounded half-up once from the exact figures, the rounded actuals may not add up to the total`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val report = seededClient("cc-round", FlowUserRole.USER).costs("${w.range}&teamId=${w.thirdsTeam}")
            val targets = assertNotNull(report.categoryTargets)
            assertEquals(10.0, targets.totalMd)
            // Run 1.255 / 10 = 12.55 % against 13 -> -0.45 -> -0.5 (half away from zero);
            // Build 8.745 -> 87.45 % against 87 -> +0.45 -> +0.5.
            assertEquals(listOf(w.build, w.run), targets.rows.map { it.key })
            assertEquals(87, targets.rows[0].targetPercent)
            assertEquals(8.75, targets.rows[0].actualMd, "8.7450 rounds up")
            assertEquals(0.5, targets.rows[0].deltaPoints)
            assertEquals(1.26, targets.rows[1].actualMd, "1.2550 rounds up")
            assertEquals(-0.5, targets.rows[1].deltaPoints)
            assertEquals(10.01, targets.rows.sumOf { it.actualMd }, EPS, "the rounded parts exceed the rounded total by up to 0.005 each")
            assertReconciles(report)
        }
    }

    @Test
    fun `the slices narrow both widgets and the targets stay the team's`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val report = seededClient("cc-slice", FlowUserRole.USER).costs("${w.range}&teamId=${w.team}&workCategory=${w.run}")
            assertEquals(5.5, report.totalMd)
            val targets = assertNotNull(report.categoryTargets)
            assertEquals(5.5, targets.totalMd)
            val byKey = targets.rows.associateBy { it.key }
            assertEquals(40.0, byKey.getValue(w.run).deltaPoints, "100 % against 60")
            assertEquals(-30.0, byKey.getValue(w.build).deltaPoints, "no Build time in the slice")
            assertEquals(0.0, byKey.getValue(w.build).actualMd)
            val cumulative = assertNotNull(report.categoryShareTrend).weeks.map { it.cumulativeMd }
            assertEquals(listOf(3.0, 4.5, 4.5, 4.5, 5.5), cumulative, "Run only: 3.0, +1.5, nothing, nothing, +1.0")
            assertReconciles(report)
        }
    }

    @Test
    fun `without a real team there are no category widgets, and at USER level only the trend of that person`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val client = seededClient("cc-levels", FlowUserRole.USER)
            for (query in listOf(w.range, "${w.range}&teamId=0")) {
                val report = client.costs(query)
                assertNull(report.categoryTargets, query)
                assertNull(report.categoryShareTrend, query)
            }
            val alice = client.costs("${w.range}&teamId=${w.team}&accountId=${w.alice}")
            assertNull(alice.categoryTargets, "the targets are the team's, not one person's")
            val weeks = assertNotNull(alice.categoryShareTrend).weeks
            // alice: Run 3.0 (Dec 17), no category 0.5 (Dec 24), Spike 0.5 (Jan 8), Run 1.0 (Jan 13) -> 5.0; Build is bob's, so not a key.
            assertEquals(5.0, alice.totalMd)
            assertEquals(
                listOf(
                    Triple("2025-12-15", 3.0, listOf(3.0, 0.0, 0.0)),
                    Triple("2025-12-22", 3.5, listOf(3.0, 0.0, 0.5)),
                    Triple("2025-12-29", 3.5, listOf(3.0, 0.0, 0.5)),
                    Triple("2026-01-05", 4.0, listOf(3.0, 0.5, 0.5)),
                    Triple("2026-01-12", 5.0, listOf(4.0, 0.5, 0.5)),
                ),
                weeks.map { it.figures() },
            )
            assertEquals(listOf(w.run, w.spike, "UNCATEGORIZED"), weeks.first().byCategory.map { it.key })
            assertReconciles(alice)
        }
    }

    @Test
    fun `a period with no time still lists the stored targets and zero-filled weeks`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val report = seededClient("cc-empty", FlowUserRole.USER)
                .costs("connectionId=${w.connId}&teamId=${w.team}&from=2026-03-04&to=2026-03-17")
            assertEquals(0.0, report.totalMd)
            val targets = assertNotNull(report.categoryTargets)
            assertTrue(targets.configured)
            assertEquals(0.0, targets.totalMd)
            assertEquals(listOf(w.run, w.build, w.retired), targets.rows.map { it.key })
            assertTrue(targets.rows.all { it.actualMd == 0.0 && it.actualShare == null && it.deltaPoints == null })
            val weeks = assertNotNull(report.categoryShareTrend).weeks
            assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16"), weeks.map { it.weekStart })
            assertTrue(weeks.all { it.cumulativeMd == 0.0 && it.byCategory.isEmpty() })
        }
    }

    @Test
    fun `the trend is drawn up to 160 weeks and is null beyond`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val client = seededClient("cc-160", FlowUserRole.USER)
            val first = LocalDate.of(2020, 1, 6) // a Monday
            fun ms(day: LocalDate, hour: Int) = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(w.connId, listOf(wl(800, w.team, w.alice, "2020-01-07", 2.0, "AAA", category = w.run)), 1L)
            }
            // 160 Monday-start weeks: the first Monday .. the Monday 159 weeks later (plus three days into it).
            w.addSprint(7_000_001L, ms(first, 0), ms(first.plusWeeks(159).plusDays(3), 12))
            val within = client.costs("connectionId=${w.connId}&teamId=${w.team}&sprintId=7000001")
            val weeks = assertNotNull(within.categoryShareTrend).weeks
            assertEquals(160, weeks.size)
            assertEquals("2020-01-06", weeks.first().weekStart)
            assertEquals(first.plusWeeks(159).toString(), weeks.last().weekStart)
            assertEquals(2.0, weeks.last().cumulativeMd)
            assertEquals(2.0, within.totalMd)
            assertNotNull(within.categoryTargets, "the targets table has no week bound")
            // One more week touched: the trend goes, everything else stays.
            w.addSprint(7_000_002L, ms(first, 0), ms(first.plusWeeks(160).plusDays(3), 12))
            val beyond = client.costs("connectionId=${w.connId}&teamId=${w.team}&sprintId=7000002")
            assertNull(beyond.categoryShareTrend)
            assertEquals(2.0, assertNotNull(beyond.categoryTargets).totalMd)
        }
    }

    @Test
    fun `weeks are Monday-start weeks in the configured zone`() = testApplication {
        usePostgresTestcontainer()
        withCostsCategoriesWorld { w ->
            val client = seededClient("cc-zone", FlowUserRole.USER)
            val store = MetricsStore(sharedDatabaseForTests())
            // Sunday Jan 4, 23:30Z: still the week of Dec 29 in UTC, but Monday Jan 5 00:30 in UTC+1.
            val sundayNight = LocalDate.of(2026, 1, 4).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(
                    w.connId, listOf(wl(900, w.team, w.alice, "2026-01-04", 0.25, "AAA", category = w.run, startedAtMs = sundayNight)), 1L,
                )
            }
            suspend fun decemberLastWeek(): CostsTrendWeek = assertNotNull(
                client.costs("${w.range}&teamId=${w.team}").categoryShareTrend,
            ).weeks.single { it.weekStart == "2025-12-29" }
            val settings = DerivedStubFixture.metricsSettings()
            withMetricsSettings(settings, { it.copy(timeZone = "UTC") }) {
                assertEquals(6.25, decemberLastWeek().cumulativeMd, "the Sunday-night worklog is in the week of Dec 29")
            }
            withMetricsSettings(settings, { it.copy(timeZone = "Etc/GMT-1") }) {
                assertEquals(6.0, decemberLastWeek().cumulativeMd, "it is Monday Jan 5 local: the week of Dec 29 stays as it was")
            }
        }
    }
}
