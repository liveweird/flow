package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.EpicPlanBaseline
import ch.nokillswit.metrics.FactEpicPlanRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.EpicProgressBasis
import ch.nokillswit.reports.EpicProgressKind
import ch.nokillswit.reports.EpicProgressLevel
import ch.nokillswit.reports.EpicProgressReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val EPS = 1e-9

/** The thinnest denominator (man-days) a period or trend index is shown for — pinned independently of the constant. */
private const val MIN_MD = 1.0

private fun Totals.minus(other: Totals) = Totals(pv - other.pv, ev - other.ev, ac - other.ac)

private fun Totals.spiOrNull(): Double? = if (pv.toDouble() < MIN_MD) null else ev.toDouble() / pv.toDouble()

private fun Totals.cpiOrNull(): Double? = if (ac.toDouble() < MIN_MD) null else ev.toDouble() / ac.toDouble()

private fun day(iso: String, minus: Long) = LocalDate.parse(iso).minusDays(minus).toString()

/** The period delta of the matching rows over `[first, last]`, an INDEPENDENT read of the stored increments. */
private fun List<FlowEvmRow>.delta(first: String, last: String, match: (FlowEvmRow) -> Boolean) =
    totalsThrough(last, match).minus(totalsThrough(day(first, 1), match))

/** Independent trend: a week end is a Sunday in `[first, asOf]`, plus `asOf` itself; each over `(day - window, day]`. */
private fun List<FlowEvmRow>.expectedTrend(first: String, asOf: String, window: Long, match: (FlowEvmRow) -> Boolean) =
    isoDays(first, asOf).filter { LocalDate.parse(it).dayOfWeek == DayOfWeek.SUNDAY || it == asOf }.map { end ->
        val delta = totalsThrough(end, match).minus(totalsThrough(day(end, window), match))
        Triple(end, delta.spiOrNull(), delta.cpiOrNull())
    }

/**
 * `GET /api/v1/reports/epic-progress`'s period view (A37): the `basis`, the `period` block, the rebased series, the trend, the
 * period foreign-work share and the rows' `period`. The stub fixture grades every level against an INDEPENDENT read of the
 * persisted `agg_daily_flow` rows; the exact answers, the thin-index nulls and the derive cut-off are pinned on hand-built rows
 * in a fresh DISABLED connection. The cumulative path stays pinned by `ReportEpicProgressTest`.
 */
class ReportEpicProgressPeriodTest {

    private suspend fun HttpClient.epicProgress(query: String): EpicProgressReport {
        val response = get("/api/v1/reports/epic-progress?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-progress?$query")
        return response.body()
    }

    private fun assertPeriod(label: String, expected: Totals, firstDay: String, actual: EpicProgressReport) {
        val period = actual.period
        assertEquals(firstDay, period.firstDay, "$label: period first day")
        assertEquals(expected.pv.toDouble(), period.pv, EPS, "$label: period pv")
        assertEquals(expected.ev.toDouble(), period.ev, EPS, "$label: period ev")
        assertEquals(expected.ac.toDouble(), period.ac, EPS, "$label: period ac")
        assertEquals((expected.ev - expected.pv).toDouble(), period.sv, EPS, "$label: period sv")
        assertEquals((expected.ev - expected.ac).toDouble(), period.cv, EPS, "$label: period cv")
        assertNullable(expected.spiOrNull(), period.spi, "$label: period spi")
        assertNullable(expected.cpiOrNull(), period.cpi, "$label: period cpi")
    }

    private fun assertNullable(expected: Double?, actual: Double?, label: String) {
        if (expected == null) assertNull(actual, label) else assertEquals(expected, actual!!, EPS, label)
    }

    /** Every PERIOD point is the independent running sum minus the sum the day before the period. */
    private fun assertRebased(
        label: String,
        body: EpicProgressReport,
        rows: List<FlowEvmRow>,
        first: String,
        match: (FlowEvmRow) -> Boolean,
    ) {
        val before = rows.totalsThrough(day(first, 1), match)
        for (point in body.series) {
            val shown = rows.totalsThrough(point.date, match).minus(before)
            assertEquals(shown.pv.toDouble(), point.pv, EPS, "$label ${point.date} pv")
            assertEquals(shown.ev.toDouble(), point.ev, EPS, "$label ${point.date} ev")
            assertEquals(shown.ac.toDouble(), point.ac, EPS, "$label ${point.date} ac")
        }
    }

    private fun assertTrend(label: String, body: EpicProgressReport, expected: List<Triple<String, Double?, Double?>>) {
        assertEquals(expected.map { it.first }, body.trend.map { it.day }, "$label: one trend point per week end")
        body.trend.zip(expected).forEach { (actual, want) ->
            assertNullable(want.second, actual.spi, "$label ${want.first} trend spi")
            assertNullable(want.third, actual.cpi, "$label ${want.first} trend cpi")
        }
    }

    @Test
    fun `fixture - the period view of every level equals an independent read of agg_daily_flow`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-period-fixture")
        val rows = readFlowEvmRows(connId)
        val first = "2025-10-01"
        val asOfDay = "2026-03-05"
        val base = "connectionId=$connId&from=$first&to=$asOfDay"
        assertEquals(dayOfInstant(DerivedStubFixture.PINNED_NOW, reportZone()), asOfDay, "the range must end on the pinned DERIVE day")

        // UNIT: PERIOD by default, no series, the headline period = Σ the DOMAIN rows' period figures, the asOf stays cumulative.
        val allDomains: (FlowEvmRow) -> Boolean = { it.scopeKind == "DOMAIN" }
        val unit = client.epicProgress(base)
        assertEquals(EpicProgressBasis.PERIOD, unit.basis)
        assertTrue(unit.series.isEmpty())
        assertPeriod("UNIT", rows.delta(first, asOfDay, allDomains), first, unit)
        assertEquals(rows.totalsThrough(asOfDay, allDomains).ev.toDouble(), unit.asOf.ev, EPS, "UNIT: asOf stays cumulative")
        assertTrue(unit.period.pv > 0.0 && unit.period.ev > 0.0 && unit.period.ac > 0.0, "the fixture must carry period figures")
        assertTrue(unit.period.ev < unit.asOf.ev || unit.period.ac < unit.asOf.ac, "some figures predate the period")
        assertTrend("UNIT", unit, rows.expectedTrend(first, asOfDay, 28, allDomains))
        val unitDomains = unit.rows.filter { it.kind == EpicProgressKind.DOMAIN }
        for (row in unit.rows) {
            val scope: (FlowEvmRow) -> Boolean = if (row.kind == EpicProgressKind.DOMAIN) {
                { it.scopeKind == "DOMAIN" && it.scopeId == row.key }
            } else {
                { it.scopeKind == "TEAM" && it.scopeId == if (row.id == 0u) "UNASSIGNED" else row.id.toString() }
            }
            val expected = rows.delta(first, asOfDay, scope)
            assertEquals(expected.pv.toDouble(), row.period.pv, EPS, "${row.kind} ${row.key ?: row.id} period pv")
            assertEquals(expected.ev.toDouble(), row.period.ev, EPS, "${row.kind} ${row.key ?: row.id} period ev")
            assertEquals(expected.ac.toDouble(), row.period.ac, EPS, "${row.kind} ${row.key ?: row.id} period ac")
            assertNullable(expected.spiOrNull(), row.period.spi, "${row.kind} ${row.key ?: row.id} period spi")
            assertNullable(expected.cpiOrNull(), row.period.cpi, "${row.kind} ${row.key ?: row.id} period cpi")
        }
        assertEquals(unit.period.pv, unitDomains.sumOf { it.period.pv }, 1e-6, "the unit's period = Σ the domain rows' period (pv)")
        assertEquals(unit.period.ev, unitDomains.sumOf { it.period.ev }, 1e-6, "the unit's period = Σ the domain rows' period (ev)")
        assertEquals(unit.period.ac, unitDomains.sumOf { it.period.ac }, 1e-6, "the unit's period = Σ the domain rows' period (ac)")
        // The CUMULATIVE basis leaves the headline and the rows exactly as before: asOf unchanged, still Σ the domain rows.
        val unitCumulative = client.epicProgress("$base&basis=CUMULATIVE")
        assertEquals(EpicProgressBasis.CUMULATIVE, unitCumulative.basis)
        assertEquals(unit.asOf, unitCumulative.asOf)
        assertEquals(unit.period, unitCumulative.period, "the period block is returned whatever the basis")

        // DOMAIN: the busiest one; PERIOD by default and rebased, CUMULATIVE on request.
        val domain = rows.filter { it.scopeKind == "DOMAIN" }.groupBy { it.scopeId }.maxBy { (_, group) -> group.sumOf { it.ev } }.key
        val inDomain: (FlowEvmRow) -> Boolean = { it.scopeKind == "DOMAIN" && it.scopeId == domain }
        val domainBody = client.epicProgress("$base&domain=$domain")
        assertEquals(EpicProgressBasis.PERIOD, domainBody.basis)
        assertEquals(isoDays(first, asOfDay), domainBody.series.map { it.date })
        assertRebased("domain $domain", domainBody, rows, first, inDomain)
        assertPeriod("domain $domain", rows.delta(first, asOfDay, inDomain), first, domainBody)
        assertEquals(rows.totalsThrough(asOfDay, inDomain).pv.toDouble(), domainBody.asOf.pv, EPS, "domain: asOf stays cumulative")
        val lastShown = domainBody.series.last()
        assertEquals(domainBody.period.pv, lastShown.pv, EPS, "the rebased curve ends at the period delta")
        assertEquals(domainBody.period.ev, lastShown.ev, EPS)
        assertEquals(domainBody.period.ac, lastShown.ac, EPS)
        assertTrend("domain $domain", domainBody, rows.expectedTrend(first, asOfDay, 28, inDomain))
        assertEquals(domainBody.period.pv, domainBody.rows.sumOf { it.period.pv }, 1e-6, "the epic rows' period sums to the DOMAIN period")
        assertEquals(domainBody.period.ev, domainBody.rows.sumOf { it.period.ev }, 1e-6)
        assertEquals(domainBody.period.ac, domainBody.rows.sumOf { it.period.ac }, 1e-6)
        val domainCumulative = client.epicProgress("$base&domain=$domain&basis=CUMULATIVE")
        assertEquals(rows.totalsThrough(asOfDay, inDomain).ev.toDouble(), domainCumulative.series.last().ev, EPS)
        assertEquals(domainBody.asOf, domainCumulative.asOf)
        val wide = client.epicProgress("$base&domain=$domain&trendWindowDays=91")
        assertTrend("domain $domain, 91 days", wide, rows.expectedTrend(first, asOfDay, 91, inDomain))

        // TEAM: the sprint-based scope, PERIOD by default.
        val teamId = rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()
        val inTeam: (FlowEvmRow) -> Boolean = { it.scopeKind == "TEAM" && it.scopeId == teamId }
        val team = client.epicProgress("$base&teamId=$teamId")
        assertEquals(EpicProgressBasis.PERIOD, team.basis)
        assertRebased("team $teamId", team, rows, first, inTeam)
        assertPeriod("team $teamId", rows.delta(first, asOfDay, inTeam), first, team)
        assertTrend("team $teamId", team, rows.expectedTrend(first, asOfDay, 28, inTeam))
        assertTrue(team.rows.isEmpty())

        // EPIC (the golden one): CUMULATIVE by default; PERIOD on request rebases the curves AND pvOriginal.
        val epic = client.epicProgress("$base&epicId=${epicWithPlanOf(client, base)}")
        assertEquals(EpicProgressLevel.EPIC, epic.level)
        assertEquals(EpicProgressBasis.CUMULATIVE, epic.basis)
        val epicPeriod = client.epicProgress("$base&epicId=${epic.scope!!.key}&basis=PERIOD")
        assertEquals(EpicProgressBasis.PERIOD, epicPeriod.basis)
        assertEquals(epic.asOf, epicPeriod.asOf)
        assertEquals(epic.period, epicPeriod.period)
        val beforeFirst = client.epicProgress("connectionId=$connId&from=${day(first, 400)}&to=${day(first, 1)}&epicId=${epic.scope.key}")
        val atBefore = beforeFirst.series.last()
        assertEquals(day(first, 1), atBefore.date)
        for ((cumulative, rebased) in epic.series.zip(epicPeriod.series)) {
            assertEquals(cumulative.pv - atBefore.pv, rebased.pv, 1e-6, "${cumulative.date} pv rebased")
            assertEquals(cumulative.ev - atBefore.ev, rebased.ev, 1e-6, "${cumulative.date} ev rebased")
            assertEquals(cumulative.ac - atBefore.ac, rebased.ac, 1e-6, "${cumulative.date} ac rebased")
            if (cumulative.pvOriginal == null) {
                assertNull(rebased.pvOriginal)
            } else {
                val expectedOriginal = cumulative.pvOriginal - (atBefore.pvOriginal ?: 0.0)
                assertEquals(expectedOriginal, rebased.pvOriginal!!, 1e-6, "${cumulative.date} pvOriginal")
            }
        }
        assertEquals(first, epic.series.first().date)
    }

    /** An epic of the fixture's FLO domain that has planned value, found through the domain drill. */
    private suspend fun epicWithPlanOf(client: HttpClient, base: String): String =
        client.epicProgress("$base&domain=${DerivedStubFixture.DOMAIN}").rows.first { it.pv > 0.0 }.key!!

    @Test
    fun `fixture - the team foreign-work share of the period equals an independent worklog sum`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-period-foreign")
        val rows = readFlowEvmRows(connId)
        val teamId = rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()
        val first = "2025-12-01"
        val asOfDay = "2026-03-05"
        val body = client.epicProgress("connectionId=$connId&from=$first&to=$asOfDay&teamId=$teamId")
        val logs = suspendTransaction(sharedDatabaseForTests()) {
            val w = MetricsTables.FactWorklog
            w.selectAll().where { (w.connectionId eq connId) and (w.authorTeamId eq teamId.toUInt()) }.toList()
                .map { Triple(dayOfInstant(it[w.startedAt], reportZone()), it[w.md], it[w.foreignWork]) }
        }
        fun share(inWindow: (String) -> Boolean): Double? {
            val chosen = logs.filter { inWindow(it.first) }
            val total = chosen.sumOf { it.second }
            return if (total.signum() == 0) null else chosen.filter { it.third }.sumOf { it.second }.toDouble() / total.toDouble()
        }
        assertNullable(share { it <= asOfDay }, body.foreignWorkShare, "cumulative share")
        assertNullable(share { it in first..asOfDay }, body.foreignWorkSharePeriod, "period share")
        assertNull(client.epicProgress("connectionId=$connId&from=$first&to=$asOfDay").foreignWorkSharePeriod, "UNIT carries no share")
    }

    /**
     * Epics PE-1 and PE-2 sit in domain AAA, PE-3 in BBB; the period is Mon 2026-02-02 .. Wed 2026-02-18, so the weeks end on
     * Sun Feb 8, Sun Feb 15 and (cut at asOf) Wed Feb 18. A DERIVE through Feb 18 cuts off the Feb 20 rows even though the
     * requested period reaches Feb 28. Hand-computed AAA (period = Feb 2..18): ΔPV 10.4, ΔEV 11, ΔAC 7, SV 0.6, CV 4; before it
     * PV 12, EV 7, AC 6. Trend over 7 days: Feb 8 = Feb 2..8 (PV 7, EV 9, AC 3.5), Feb 15 = Feb 9..15 (PV 3, EV 0, AC 3), Feb 18 =
     * Feb 12..18 (PV 0.4, EV 2, AC 0.5 — both denominators thin, so both indices null). Over 14 days the Feb 8 window reaches back
     * to Jan 26 and takes in the Feb 1 rows (PV 9, EV 9, AC 4.5).
     */
    @Test
    fun `hand-built rows pin the period, the thin-index nulls, the trend, the rows and the derive cut-off exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "evm-period", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-02-18"))
        val store = MetricsStore(sharedDatabaseForTests())
        val teamT = TestTeams.seed(SyncedStubFixture.unique("evm-period-t"))
        val epicRows = listOf(
            evmRow("EPIC", "201", "2026-01-12", pv = "10.00", ev = "4.00", ac = "5.00"),
            evmRow("EPIC", "201", "2026-02-01", pv = "2.00", ac = "1.00"),
            evmRow("EPIC", "201", "2026-02-02", pv = "3.00", ac = "0.50"),
            evmRow("EPIC", "201", "2026-02-04", pv = "3.00", ev = "8.00", ac = "2.00"),
            evmRow("EPIC", "201", "2026-02-10", pv = "3.00", ac = "3.00"),
            evmRow("EPIC", "201", "2026-02-17", pv = "0.40", ev = "2.00", ac = "0.50"),
            evmRow("EPIC", "201", "2026-02-20", pv = "100.00", ev = "100.00", ac = "100.00"), // after the derive cut-off
            evmRow("EPIC", "202", "2026-01-20", ev = "3.00"),
            evmRow("EPIC", "202", "2026-02-03", pv = "1.00", ev = "1.00", ac = "1.00"),
            evmRow("EPIC", "203", "2026-01-30", pv = "1.00", ev = "1.00"),
            evmRow("EPIC", "203", "2026-02-05", pv = "5.00", ev = "2.50", ac = "5.00"),
        )
        val domainOf = mapOf("201" to "AAA", "202" to "AAA", "203" to "BBB")
        val domainRows = epicRows.groupBy { domainOf.getValue(it.scopeId) to it.day }.map { (key, group) ->
            FlowEvmRow("DOMAIN", key.first, key.second, group.sumOf { it.pv }, group.sumOf { it.ev }, group.sumOf { it.ac })
        }
        val teamRows = listOf(
            evmRow("TEAM", teamT.toString(), "2026-01-20", pv = "10.00"),
            evmRow("TEAM", teamT.toString(), "2026-02-03", ac = "8.00"),
            evmRow("TEAM", teamT.toString(), "2026-02-04", ev = "4.00"),
            evmRow("TEAM", teamT.toString(), "2026-02-12", pv = "0.50", ac = "0.50"),
        )
        fun log(id: Long, day: String, md: Double, foreign: Boolean) = FactWorklogRow(
            worklogId = id, issueId = 1, authorAccountId = "acc", authorTeamId = teamT, startedAt = noonUtc(day),
            createdAt = null, lateMs = null,
            md = md, taskDomainKey = "AAA", epicId = null, epicDomainKey = null, activityType = "Story", workCategory = null,
            sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = foreign, assigneeAccountIdAtStarted = null,
            assigneeTeamIdAtStarted = null,
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(
                connId,
                listOf(DimDomainRow("AAA", "Domain A", emptyList(), null), DimDomainRow("BBB", "Domain B", emptyList(), null)),
                configRevision = 1L,
            )
            store.insertEpics(
                connId,
                listOf(
                    DimEpicRow(201, "PE-1", "Epic One", "AAA", null, "IN_PROGRESS", utcMidnight("2026-01-26"), utcMidnight("2026-02-13")),
                    DimEpicRow(202, "PE-2", "Epic Two", "AAA", null, "IN_PROGRESS", null, null),
                    DimEpicRow(203, "PE-3", "Epic Three", "BBB", null, "IN_PROGRESS", null, null),
                ),
                configRevision = 1L,
            )
            store.insertFactEpicPlan(
                connId,
                listOf(
                    FactEpicPlanRow(
                        201,
                        1,
                        EpicPlanBaseline(noonUtc("2026-01-01"), utcMidnight("2026-01-26"), utcMidnight("2026-02-13"), 10.0, "OWN", null),
                    ),
                ),
                configRevision = 1L,
            )
            store.insertFactWorklog(
                connId,
                listOf(
                    log(1, "2026-02-03", 4.0, true), log(2, "2026-02-10", 6.0, false), log(3, "2026-01-20", 100.0, true),
                    log(4, "2026-02-20", 50.0, false), // after asOf
                ),
                configRevision = 1L,
            )
        }
        insertFlowEvmRows(connId, epicRows + domainRows + teamRows)
        try {
            val client = seededClient("reports-evm-period-hand-built")
            val base = "connectionId=$connId&from=2026-02-02&to=2026-02-28"

            // DOMAIN AAA: the period figures, the cumulative asOf next to them, the trend with a 7-day and a 14-day window.
            val aaa = client.epicProgress("$base&domain=AAA&trendWindowDays=7")
            assertEquals(EpicProgressBasis.PERIOD, aaa.basis)
            assertEquals("2026-02-18", aaa.asOf.day, "the DERIVE through Feb 18 cuts the period off")
            assertEquals(22.4, aaa.asOf.pv, EPS)
            assertEquals(18.0, aaa.asOf.ev, EPS)
            assertEquals(13.0, aaa.asOf.ac, EPS)
            assertEquals("2026-02-02", aaa.period.firstDay)
            assertEquals(10.4, aaa.period.pv, EPS)
            assertEquals(11.0, aaa.period.ev, EPS)
            assertEquals(7.0, aaa.period.ac, EPS)
            assertEquals(0.6, aaa.period.sv, EPS)
            assertEquals(4.0, aaa.period.cv, EPS)
            assertEquals(11.0 / 10.4, aaa.period.spi!!, EPS)
            assertEquals(11.0 / 7.0, aaa.period.cpi!!, EPS)
            assertEquals(isoDays("2026-02-02", "2026-02-18"), aaa.series.map { it.date })
            assertEquals(3.0, aaa.series[0].pv, EPS) // Feb 2: the Feb 1 rows are NOT in the rebased curve
            assertEquals(4.0, aaa.series[1].pv, EPS) // Feb 3
            assertEquals(7.0, aaa.series[2].pv, EPS) // Feb 4
            assertEquals(listOf(0.0, 1.0, 9.0), aaa.series.take(3).map { it.ev })
            assertEquals(10.4, aaa.series.last().pv, EPS)
            assertEquals(listOf("2026-02-08", "2026-02-15", "2026-02-18"), aaa.trend.map { it.day })
            assertEquals(9.0 / 7.0, aaa.trend[0].spi!!, EPS)
            assertEquals(9.0 / 3.5, aaa.trend[0].cpi!!, EPS)
            assertEquals(0.0, aaa.trend[1].spi!!, EPS)
            assertEquals(0.0, aaa.trend[1].cpi!!, EPS)
            assertNull(aaa.trend[2].spi, "ΔPV 0.4 is under the minimum")
            assertNull(aaa.trend[2].cpi, "ΔAC 0.5 is under the minimum")
            val wide = client.epicProgress("$base&domain=AAA&trendWindowDays=14")
            assertEquals(9.0 / 9.0, wide.trend[0].spi!!, EPS, "the window reaches before the period start")
            assertEquals(9.0 / 4.5, wide.trend[0].cpi!!, EPS)
            assertNull(aaa.foreignWorkShare)
            assertNull(aaa.foreignWorkSharePeriod)

            // The drill: cumulative beside period; PE-2's ΔPV is exactly the minimum (1.0), so its indices are shown.
            assertEquals(listOf("PE-1", "PE-2"), aaa.rows.map { it.key })
            val (one, two) = aaa.rows
            assertEquals(21.4, one.pv, EPS)
            assertEquals(14.0, one.ev, EPS)
            assertEquals(9.4, one.period.pv, EPS)
            assertEquals(10.0, one.period.ev, EPS)
            assertEquals(6.0, one.period.ac, EPS)
            assertEquals(10.0 / 9.4, one.period.spi!!, EPS)
            assertEquals(10.0 / 6.0, one.period.cpi!!, EPS)
            assertEquals(4.0, two.ev, EPS)
            assertEquals(1.0, two.period.pv, EPS)
            assertEquals(1.0, two.period.spi!!, EPS)
            assertEquals(1.0, two.period.cpi!!, EPS)
            assertEquals(aaa.period.pv, aaa.rows.sumOf { it.period.pv }, 1e-6)
            assertEquals(aaa.period.ev, aaa.rows.sumOf { it.period.ev }, 1e-6)
            assertEquals(aaa.period.ac, aaa.rows.sumOf { it.period.ac }, 1e-6)

            // The same domain on the CUMULATIVE basis: the series are the old running sums, the asOf and period blocks unchanged.
            val cumulative = client.epicProgress("$base&domain=AAA&basis=CUMULATIVE")
            assertEquals(EpicProgressBasis.CUMULATIVE, cumulative.basis)
            assertEquals(12.0 + 3.0, cumulative.series.first().pv, EPS)
            assertEquals(22.4, cumulative.series.last().pv, EPS)
            assertEquals(aaa.asOf, cumulative.asOf)
            assertEquals(aaa.period, cumulative.period)
            assertEquals(aaa.rows, cumulative.rows)

            // UNIT: the headline period = Σ the domain rows' period (AAA 10.4/11/7 + BBB 5/2.5/5), for the cumulative basis too.
            for (suffix in listOf("", "&basis=CUMULATIVE")) {
                val unit = client.epicProgress("$base$suffix")
                val domains = unit.rows.filter { it.kind == EpicProgressKind.DOMAIN }
                assertEquals(listOf("AAA", "BBB"), domains.map { it.key })
                assertEquals(15.4, unit.period.pv, EPS)
                assertEquals(13.5, unit.period.ev, EPS)
                assertEquals(12.0, unit.period.ac, EPS)
                assertEquals(unit.period.pv, domains.sumOf { it.period.pv }, 1e-6)
                assertEquals(unit.period.ev, domains.sumOf { it.period.ev }, 1e-6)
                assertEquals(unit.period.ac, domains.sumOf { it.period.ac }, 1e-6)
                assertEquals(listOf(5.0, 2.5, 5.0), domains.last().period.let { listOf(it.pv, it.ev, it.ac) })
                assertEquals(0.5, domains.last().period.spi!!, EPS)
                assertEquals(0.5, domains.last().period.cpi!!, EPS)
                assertTrue(unit.series.isEmpty())
            }

            // TEAM T: ΔPV 0.5 is thin (SPI null), ΔAC 8.5 is not; the period share reads only the period's worklogs.
            val team = client.epicProgress("$base&teamId=$teamT")
            assertEquals(EpicProgressBasis.PERIOD, team.basis)
            assertEquals(0.5, team.period.pv, EPS)
            assertEquals(4.0, team.period.ev, EPS)
            assertEquals(8.5, team.period.ac, EPS)
            assertNull(team.period.spi)
            assertEquals(4.0 / 8.5, team.period.cpi!!, EPS)
            assertEquals(104.0 / 110.0, team.foreignWorkShare!!, EPS, "cumulative: the Jan 20 worklog counts, the Feb 20 one is after asOf")
            assertEquals(4.0 / 10.0, team.foreignWorkSharePeriod!!, EPS, "period: only Feb 3 and Feb 10")

            // EPIC PE-1: CUMULATIVE by default (unchanged series); an explicit PERIOD rebases the curves and pvOriginal.
            val epic = client.epicProgress("$base&epicId=PE-1")
            assertEquals(EpicProgressBasis.CUMULATIVE, epic.basis)
            assertEquals(15.0, epic.series.first().pv, EPS)
            assertEquals(21.4, epic.asOf.pv, EPS)
            val epicPeriod = client.epicProgress("$base&epicId=PE-1&basis=PERIOD")
            assertEquals(EpicProgressBasis.PERIOD, epicPeriod.basis)
            assertEquals(3.0, epicPeriod.series.first().pv, EPS)
            assertEquals(9.4, epicPeriod.series.last().pv, EPS)
            assertEquals(10.0, epicPeriod.series.last().ev, EPS)
            assertNotEquals(epic.series.first().pvOriginal, epicPeriod.series.first().pvOriginal, "the original plan is rebased too")
            val beforeFirst = client.epicProgress("connectionId=$connId&from=2026-01-01&to=2026-02-01&epicId=PE-1").series.last()
            for ((c, p) in epic.series.zip(epicPeriod.series)) {
                assertEquals(c.pvOriginal!! - beforeFirst.pvOriginal!!, p.pvOriginal!!, 1e-6, "${c.date} pvOriginal rebased")
            }
            assertEquals(listOf("2026-02-08", "2026-02-15", "2026-02-18"), epic.trend.map { it.day }, "the trend is present at EPIC level")

            // A period with no PV at all: SPI null (and, with no AC either, CPI null); a period after asOf: empty, zero, no trend.
            val quiet = client.epicProgress("connectionId=$connId&from=2026-02-05&to=2026-02-09&domain=AAA")
            assertEquals(0.0, quiet.period.pv, EPS)
            assertNull(quiet.period.spi)
            assertNull(quiet.period.cpi)
            val after = client.epicProgress("connectionId=$connId&from=2026-03-01&to=2026-03-31&domain=AAA")
            assertNull(after.period.firstDay)
            assertEquals(0.0, after.period.pv, EPS)
            assertTrue(after.trend.isEmpty() && after.series.isEmpty())
            assertEquals(22.4, after.asOf.pv, EPS)
            assertNull(client.epicProgress("connectionId=$connId&from=2026-03-01&to=2026-03-31&teamId=$teamT").foreignWorkSharePeriod)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteDims(connId)
                store.deleteFactEpicPlan(connId)
                store.deleteFactWorklog(connId)
            }
            deleteFlowRows(connId)
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamT))
        }
    }

    @Test
    fun `the basis defaults per level, an explicit one wins and is echoed, and a bad basis or trend window is 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val never = SyncedStubFixture.createConnection(namePrefix = "evm-period-never", enabled = false)
        val client = seededClient("reports-evm-period-400")
        val rows = readFlowEvmRows(connId)
        val domain = rows.first { it.scopeKind == "DOMAIN" && it.ev.signum() != 0 }.scopeId
        val team = rows.first { it.scopeKind == "TEAM" && it.scopeId.all(Char::isDigit) }.scopeId
        val known = "connectionId=$connId&from=2026-01-01&to=2026-03-05"
        run {
            val epicKey = client.epicProgress("$known&domain=$domain").rows.first().key!!
            val levels = mapOf(
                "" to EpicProgressBasis.PERIOD,
                "&domain=$domain" to EpicProgressBasis.PERIOD,
                "&teamId=$team" to EpicProgressBasis.PERIOD,
                "&epicId=$epicKey" to EpicProgressBasis.CUMULATIVE,
            )
            for ((scope, default) in levels) {
                val other = EpicProgressBasis.entries.single { it != default }
                assertEquals(default, client.epicProgress("$known$scope").basis, "default for '$scope'")
                assertEquals(default, client.epicProgress("$known$scope&basis=$default").basis)
                assertEquals(other, client.epicProgress("$known$scope&basis=$other").basis, "an explicit basis wins for '$scope'")
            }
            // Nothing derived: still the effective basis, a null first day, no trend.
            val empty = client.epicProgress("connectionId=$never&domain=ANY")
            assertEquals(EpicProgressBasis.PERIOD, empty.basis)
            assertNull(empty.period.firstDay)
            assertTrue(empty.trend.isEmpty())
            assertNull(empty.foreignWorkSharePeriod)
            assertEquals(EpicProgressBasis.CUMULATIVE, client.epicProgress("connectionId=$never&epicId=NOPE-1&basis=CUMULATIVE").basis)

            val scoped = "$known&domain=$domain"
            for (query in listOf(
                "$scoped&basis=WEEKLY", "$scoped&basis=period", "$scoped&basis=PERIOD&basis=CUMULATIVE",
                "$scoped&trendWindowDays=6", "$scoped&trendWindowDays=183", "$scoped&trendWindowDays=0", "$scoped&trendWindowDays=-7",
                "$scoped&trendWindowDays=abc", "$scoped&trendWindowDays=28.5", "$scoped&trendWindowDays=28&trendWindowDays=30",
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-progress?$query").status, query)
            }
            for (window in listOf(7, 28, 182)) {
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/epic-progress?$scoped&trendWindowDays=$window").status)
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/epic-progress?$scoped&basis=").status, "a blank basis = default")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
