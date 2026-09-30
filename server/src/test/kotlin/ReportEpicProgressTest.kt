package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.EpicPlanBaseline
import ch.nokillswit.metrics.FactEpicPlanRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.EpicProgressKind
import ch.nokillswit.reports.EpicProgressLevel
import ch.nokillswit.reports.EpicProgressReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/** One `agg_daily_flow` row's EVM columns as stored — a per-day INCREMENT (A23). */
private data class FlowEvmRow(
    val scopeKind: String,
    val scopeId: String,
    val day: String,
    val pv: BigDecimal = BigDecimal.ZERO,
    val ev: BigDecimal = BigDecimal.ZERO,
    val ac: BigDecimal = BigDecimal.ZERO,
)

private fun evmRow(kind: String, id: String, day: String, pv: String = "0", ev: String = "0", ac: String = "0") =
    FlowEvmRow(kind, id, day, BigDecimal(pv), BigDecimal(ev), BigDecimal(ac))

/** An independent running total: the sum of every matching increment on or before a day. */
private data class Totals(val pv: BigDecimal, val ev: BigDecimal, val ac: BigDecimal)

private fun List<FlowEvmRow>.totalsThrough(day: String, match: (FlowEvmRow) -> Boolean): Totals {
    val rows = filter { it.day <= day && match(it) }
    return Totals(rows.sumOf { it.pv }, rows.sumOf { it.ev }, rows.sumOf { it.ac })
}

private suspend fun readFlowEvmRows(connId: UInt): List<FlowEvmRow> = suspendTransaction(sharedDatabaseForTests()) {
    val f = MetricsTables.AggDailyFlow
    f.selectAll().where { f.connectionId eq connId }.toList().map {
        FlowEvmRow(it[f.scopeKind], it[f.scopeId], it[f.day], it[f.pvMd], it[f.evMd], it[f.acMd])
    }
}

private suspend fun insertFlowEvmRows(connId: UInt, rows: List<FlowEvmRow>) = suspendTransaction(sharedDatabaseForTests()) {
    val f = MetricsTables.AggDailyFlow
    f.batchInsert(rows) {
        this[f.connectionId] = connId
        this[f.scopeKind] = it.scopeKind
        this[f.scopeId] = it.scopeId
        this[f.day] = it.day
        this[f.pvMd] = it.pv
        this[f.evMd] = it.ev
        this[f.acMd] = it.ac
        this[f.configRevision] = 1L
    }
}

private fun utcMidnight(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The golden epic's key, id and plan figures straight out of `sample-data/jira/expected.json`. */
private class GoldenEpic(val issueId: String, val issueKey: String, val budgetMd: Double, val startDate: String, val dueDate: String)

private val goldenEpic: GoldenEpic by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    val epic = Json.parseToJsonElement(file.readText()).jsonObject.getValue("golden").jsonObject.getValue("epic").jsonObject
    GoldenEpic(
        epic.getValue("issueId").jsonPrimitive.content,
        epic.getValue("issueKey").jsonPrimitive.content,
        epic.getValue("budgetMd").jsonPrimitive.doubleOrNull!!,
        epic.getValue("startDate").jsonPrimitive.content,
        epic.getValue("dueDate").jsonPrimitive.content,
    )
}

/**
 * `GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, Report 15, `.claude/docs/measures.md` "Report 15 — EVM").
 * The stub fixture grades every level against an INDEPENDENT running sum of the persisted `agg_daily_flow` rows; the exact
 * SV/SPI/CV/CPI, the superseded baseline, the out-of-horizon epic, the drill rows, the derive cut-off and the foreign-work
 * share are pinned on hand-built rows in fresh DISABLED connections with hand-computed answers. Every request runs through a
 * non-admin `seededClient` (D12).
 */
class ReportEpicProgressTest {

    private suspend fun HttpClient.epicProgress(query: String): EpicProgressReport {
        val response = get("/api/v1/reports/epic-progress?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-progress?$query")
        return response.body()
    }

    private fun assertFigures(label: String, expected: Totals, day: String, actual: EpicProgressReport) {
        val asOf = actual.asOf
        assertEquals(day, asOf.day, "$label: asOf day")
        assertEquals(expected.pv.toDouble(), asOf.pv, EPS, "$label: asOf pv")
        assertEquals(expected.ev.toDouble(), asOf.ev, EPS, "$label: asOf ev")
        assertEquals(expected.ac.toDouble(), asOf.ac, EPS, "$label: asOf ac")
        assertEquals((expected.ev - expected.pv).toDouble(), asOf.sv, EPS, "$label: sv")
        assertEquals((expected.ev - expected.ac).toDouble(), asOf.cv, EPS, "$label: cv")
        val spi = if (expected.pv.signum() == 0) null else expected.ev.toDouble() / expected.pv.toDouble()
        val cpi = if (expected.ac.signum() == 0) null else expected.ev.toDouble() / expected.ac.toDouble()
        if (spi == null) assertNull(asOf.spi, "$label: spi") else assertEquals(spi, asOf.spi!!, EPS, "$label: spi")
        if (cpi == null) assertNull(asOf.cpi, "$label: cpi") else assertEquals(cpi, asOf.cpi!!, EPS, "$label: cpi")
    }

    /** Every point equals the independent running sum (from the beginning of time), the series is monotone and ends at asOf. */
    private fun assertSeries(
        label: String,
        body: EpicProgressReport,
        rows: List<FlowEvmRow>,
        from: String,
        asOfDay: String,
        match: (FlowEvmRow) -> Boolean,
    ) {
        assertEquals(isoDays(from, asOfDay), body.series.map { it.date }, "$label: one point per calendar day up to asOf")
        for (point in body.series) {
            val expected = rows.totalsThrough(point.date, match)
            assertEquals(expected.pv.toDouble(), point.pv, EPS, "$label ${point.date} pv")
            assertEquals(expected.ev.toDouble(), point.ev, EPS, "$label ${point.date} ev")
            assertEquals(expected.ac.toDouble(), point.ac, EPS, "$label ${point.date} ac")
        }
        body.series.zipWithNext().forEach { (a, b) ->
            assertTrue(b.pv >= a.pv && b.ev >= a.ev && b.ac >= a.ac, "$label: cumulative curves never decrease (${a.date} -> ${b.date})")
        }
        val last = body.series.last()
        assertEquals(body.asOf.pv, last.pv, EPS, "$label: the last point is asOf")
        assertEquals(body.asOf.ev, last.ev, EPS)
        assertEquals(body.asOf.ac, last.ac, EPS)
    }

    private val from = "2025-06-01"
    private val to = "2026-03-05"

    @Test
    fun `each level's asOf, series and drill rows equal an independent sum of agg_daily_flow`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-fixture")
        val rows = readFlowEvmRows(connId)
        val base = "connectionId=$connId&from=$from&to=$to"
        assertEquals(dayOfInstant(DerivedStubFixture.PINNED_NOW, reportZone()), to, "the test range must end on the pinned DERIVE day")

        // UNIT: every DOMAIN scope summed (the epic basis), no series, a drill of domains and teams.
        val allDomains: (FlowEvmRow) -> Boolean = { it.scopeKind == "DOMAIN" }
        val unit = client.epicProgress(base)
        assertEquals(EpicProgressLevel.UNIT, unit.level)
        assertEquals("EPIC", unit.meta.domainView.name)
        assertNull(unit.scope)
        assertTrue(unit.series.isEmpty())
        assertFigures("UNIT", rows.totalsThrough(to, allDomains), to, unit)
        assertTrue(unit.asOf.pv > 0.0 && unit.asOf.ev > 0.0 && unit.asOf.ac > 0.0, "the fixture must carry planned, earned and spent MD")
        val unitDomains = unit.rows.filter { it.kind == EpicProgressKind.DOMAIN }
        val unitTeams = unit.rows.filter { it.kind == EpicProgressKind.TEAM }
        assertTrue(unitDomains.isNotEmpty() && unitTeams.isNotEmpty())
        assertEquals(unit.rows, unitDomains + unitTeams, "domains first, then teams")
        for (row in unitDomains) {
            val expected = rows.totalsThrough(to) { it.scopeKind == "DOMAIN" && it.scopeId == row.key }
            assertEquals(expected.pv.toDouble(), row.pv, EPS, "domain ${row.key} pv")
            assertEquals(expected.ev.toDouble(), row.ev, EPS, "domain ${row.key} ev")
            assertEquals(expected.ac.toDouble(), row.ac, EPS, "domain ${row.key} ac")
        }
        for (row in unitTeams) {
            val scopeId = if (row.id == 0u) "UNASSIGNED" else row.id.toString()
            val expected = rows.totalsThrough(to) { it.scopeKind == "TEAM" && it.scopeId == scopeId }
            assertEquals(expected.pv.toDouble(), row.pv, EPS, "team $scopeId pv")
            assertEquals(expected.ev.toDouble(), row.ev, EPS, "team $scopeId ev")
            assertEquals(expected.ac.toDouble(), row.ac, EPS, "team $scopeId ac")
        }
        // The headline is Σ DOMAIN scopes, so the domain rows add up to it; the team rows are another basis (sprint scope and
        // author-team cost), each graded above against its own scope, and they are not expected to sum to it.
        assertEquals(unit.asOf.pv, unitDomains.sumOf { it.pv }, 1e-6, "domain rows sum to the unit's asOf")
        assertEquals(unit.asOf.ev, unitDomains.sumOf { it.ev }, 1e-6)
        assertEquals(unit.asOf.ac, unitDomains.sumOf { it.ac }, 1e-6)
        assertTrue(unitTeams.all { it.active == true }, "the fixture's teams are all active")
        assertNotNull(unitTeams.singleOrNull { it.id == 0u }, "author-less work is its own UNASSIGNED row, never dropped")

        // DOMAIN: the busiest domain; epic-attributed work only; the rows are its epics and sum to its asOf.
        val domain = rows.filter { it.scopeKind == "DOMAIN" }.groupBy { it.scopeId }.maxBy { (_, group) -> group.sumOf { it.ev } }.key
        val domainScope: (FlowEvmRow) -> Boolean = { it.scopeKind == "DOMAIN" && it.scopeId == domain }
        val domainBody = client.epicProgress("$base&domain=$domain")
        assertEquals(EpicProgressLevel.DOMAIN, domainBody.level)
        assertEquals(EpicProgressKind.DOMAIN, domainBody.scope!!.kind)
        assertEquals(domain, domainBody.scope.key)
        assertFigures("domain $domain", rows.totalsThrough(to, domainScope), to, domainBody)
        assertSeries("domain $domain", domainBody, rows, from, to, domainScope)
        assertTrue(domainBody.rows.isNotEmpty() && domainBody.rows.all { it.kind == EpicProgressKind.EPIC })
        assertEquals(domainBody.asOf.pv, domainBody.rows.sumOf { it.pv }, 1e-6, "epic rows sum to the DOMAIN pv")
        assertEquals(domainBody.asOf.ev, domainBody.rows.sumOf { it.ev }, 1e-6, "epic rows sum to the DOMAIN ev")
        assertEquals(domainBody.asOf.ac, domainBody.rows.sumOf { it.ac }, 1e-6, "epic rows sum to the DOMAIN ac")
        assertEquals(domainBody.rows.map { it.key }, domainBody.rows.map { it.key }.sortedBy { it }, "rows are ordered by key")
        for (row in domainBody.rows) {
            val issueId = suspendTransaction(sharedDatabaseForTests()) {
                val e = MetricsTables.DimEpic
                e.selectAll().where { (e.connectionId eq connId) and (e.issueKey eq row.key!!) }.toList().single()[e.issueId]
            }
            val expected = rows.totalsThrough(to) { it.scopeKind == "EPIC" && it.scopeId == issueId.toString() }
            assertEquals(expected.pv.toDouble(), row.pv, EPS, "epic ${row.key} pv")
            assertEquals(expected.ev.toDouble(), row.ev, EPS, "epic ${row.key} ev")
            assertEquals(expected.ac.toDouble(), row.ac, EPS, "epic ${row.key} ac")
        }

        // TEAM: the mapped team's sprint-based PV/EV and author-based AC; teamId=0 is the UNASSIGNED scope (AC only).
        val teamId = rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()
        val teamScopeOf: (String) -> (FlowEvmRow) -> Boolean = { id -> { it.scopeKind == "TEAM" && it.scopeId == id } }
        val team = client.epicProgress("$base&teamId=$teamId")
        assertEquals(EpicProgressLevel.TEAM, team.level)
        assertEquals(teamId.toUInt(), team.scope!!.id)
        assertFigures("team $teamId", rows.totalsThrough(to, teamScopeOf(teamId)), to, team)
        assertSeries("team $teamId", team, rows, from, to, teamScopeOf(teamId))
        assertTrue(team.rows.isEmpty())
        val unassigned = client.epicProgress("$base&teamId=0")
        assertEquals(0u, unassigned.scope!!.id)
        assertFigures("UNASSIGNED", rows.totalsThrough(to, teamScopeOf("UNASSIGNED")), to, unassigned)
        assertSeries("UNASSIGNED", unassigned, rows, from, to, teamScopeOf("UNASSIGNED"))
        assertEquals(0.0, unassigned.asOf.pv, EPS, "no sprint plans author-less work")
    }

    @Test
    fun `the golden epic's PV reaches its budget on its due date and pvOriginal redraws its first baseline`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-golden")
        val rows = readFlowEvmRows(connId)
        val golden = goldenEpic
        val epicScope: (FlowEvmRow) -> Boolean = { it.scopeKind == "EPIC" && it.scopeId == golden.issueId }

        val body = client.epicProgress("connectionId=$connId&from=$from&to=$to&epicId=${golden.issueKey}")
        assertEquals(EpicProgressLevel.EPIC, body.level)
        assertEquals("EPIC", body.meta.domainView.name)
        assertEquals(EpicProgressKind.EPIC, body.scope!!.kind)
        assertEquals(golden.issueKey, body.scope.key)
        assertTrue(body.rows.isEmpty())
        assertFigures("golden epic", rows.totalsThrough(to, epicScope), to, body)
        assertSeries("golden epic", body, rows, from, to, epicScope)
        assertEquals(golden.budgetMd, body.series.single { it.date == golden.dueDate }.pv, EPS, "PV on the due date is the budget")
        assertEquals(golden.budgetMd, body.series.last().pv, EPS, "and stays there")

        val plans = suspendTransaction(sharedDatabaseForTests()) {
            val p = MetricsTables.FactEpicPlan
            p.selectAll().where { (p.connectionId eq connId) and (p.issueId eq golden.issueId.toLong()) }.toList()
                .sortedBy { it[p.baselineSeq] }
                .map { listOf(it[p.startAt], it[p.dueAt], it[p.budgetMd]?.toDouble(), it[p.supersededAt], it[p.baselinedAt]) }
        }
        val epic = assertNotNull(body.epic)
        assertEquals(golden.budgetMd, epic.budgetMd)
        assertEquals("OWN", epic.budgetSource)
        assertEquals(utcMidnight(golden.startDate), epic.startAt)
        assertEquals(utcMidnight(golden.dueDate), epic.dueAt)
        assertTrue(epic.inPvHorizon)
        assertEquals(1, plans.size, "the golden epic's dates never changed: exactly one baseline")
        assertEquals(plans.size, epic.baselines.size, "every stored baseline is listed")
        assertEquals(plans.map { it[4] }, epic.baselines.map { it.effectiveFrom }, "oldest first")
        assertEquals(1, epic.baselines.count { it.supersededAt == null }, "exactly one current baseline")
        val first = plans.first()
        val current = plans.single { it[3] == null }
        assertEquals(plans.size > 1 && (first[0] != current[0] || first[1] != current[1]), epic.drift.dates)
        assertEquals(plans.size > 1 && first[2] != current[2], epic.drift.budget)
        assertTrue(epic.hasPvCurve)
        assertTrue(!epic.drift.dates && !epic.drift.budget, "one baseline: nothing to drift from")
        assertTrue(
            body.series.all { it.pvOriginal != null && Math.abs(it.pvOriginal - it.pv) < EPS },
            "one baseline: the original IS the plan, day by day",
        )
        assertTrue(body.series.all { it.pvOriginal != null }, "the golden epic's first baseline has a curve")
        val firstDue = LocalDate.ofEpochDay((first[1] as Long) / 86_400_000L).toString()
        body.series.firstOrNull { it.date == firstDue }?.let {
            assertEquals(first[2] as Double, it.pvOriginal!!, EPS, "the first baseline reaches ITS budget on ITS due date")
        }
        assertTrue(body.series.zipWithNext().all { (a, b) -> b.pvOriginal!! >= a.pvOriginal!! }, "the redrawn curve never decreases")
    }

    @Test
    fun `lastSprints reads the sprint envelope but asOf never passes the last derived day`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-sprints")
        val rows = readFlowEvmRows(connId)

        // The newest sprints closed months after the pinned DERIVE day: nothing to list, asOf stays on the last derived day.
        val body = client.epicProgress("connectionId=$connId&lastSprints=2&teamId=${readClosedSprints(connId).first().teamId}")
        assertTrue(body.meta.resolvedSprints.flatMap { it.sprintIds }.isNotEmpty())
        assertTrue(body.series.isEmpty())
        assertEquals(to, body.asOf.day)
        assertNotNull(body.note)
        assertTrue(body.note.contains("after"), body.note)
        val teamId = body.scope!!.id!!.toString()
        assertFigures("sprint period", rows.totalsThrough(to) { it.scopeKind == "TEAM" && it.scopeId == teamId }, to, body)

        // A period reaching past the derive day is cut there and the running sum includes every earlier increment.
        val clamped = client.epicProgress("connectionId=$connId&from=2026-02-20&to=2026-12-31")
        assertEquals(to, clamped.asOf.day)
        assertFigures("clamped", rows.totalsThrough(to) { it.scopeKind == "DOMAIN" }, to, clamped)
        val ahead = client.epicProgress("connectionId=$connId&from=2026-06-01&to=2026-06-30&teamId=$teamId")
        assertTrue(ahead.series.isEmpty())
        assertEquals(to, ahead.asOf.day)
        assertTrue(ahead.asOf.pv > 0.0, "asOf is still the running sum, whatever the period")
    }

    /**
     * Hand-built rows (fresh DISABLED connection derived through 2026-06-01), period Mon 2026-01-05 .. Fri 2026-01-09.
     * Epic HB-1 (domain AAA): baseline 1 was 10 MD over Jan 5-9 (2/day), superseded by baseline 2 = 24 MD over Jan 5-14
     * (3/day over eight working days), so `pv` and `pvOriginal` differ and both drift flags are set. EV 2 on Dec 15 and 4 on
     * Jan 6 and 6 on Jan 8, AC 1 on Dec 16 and 3.5 on Jan 5 and Jan 7 — the December increments prove the sum starts before
     * `from`. Cumulative through Jan 5..9: pv 3/6/9/12/15, ev 2/6/6/12/12, ac 4.5/4.5/8/8/8.
     */
    @Test
    fun `hand-built rows pin SV, SPI, CV, CPI, the superseded baseline, the horizon and the drill rows exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "evm-hand", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val d = listOf("2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09")
        val more = listOf("2026-01-12", "2026-01-13", "2026-01-14")
        val teamT = TestTeams.seed(SyncedStubFixture.unique("evm-t"))
        val teamD = TestTeams.seed(SyncedStubFixture.unique("evm-deleted"))
        val epicRows = listOf(
            evmRow("EPIC", "101", "2025-12-15", ev = "2.00"), evmRow("EPIC", "101", "2025-12-16", ac = "1.00"),
            evmRow("EPIC", "101", d[0], pv = "3.00", ac = "3.50"), evmRow("EPIC", "101", d[1], pv = "3.00", ev = "4.00"),
            evmRow("EPIC", "101", d[2], pv = "3.00", ac = "3.50"), evmRow("EPIC", "101", d[3], pv = "3.00", ev = "6.00"),
            evmRow("EPIC", "101", d[4], pv = "3.00"),
        ) + more.map { evmRow("EPIC", "101", it, pv = "3.00") } + listOf(
            evmRow("EPIC", "102", d[2], ev = "5.00"), // HB-2: earned, never planned, no cost
            evmRow("EPIC", "104", d[0], pv = "1.00"), evmRow("EPIC", "104", d[1], pv = "1.00"), evmRow("EPIC", "104", d[2], pv = "1.00"),
            evmRow("EPIC", "104", d[3], pv = "1.00", ac = "2.00"), evmRow("EPIC", "104", d[4], pv = "1.00"), // HB-4: spent, not earned
            evmRow("EPIC", "105", d[3], ev = "100.00"), // HB-5 lives in another domain
        )
        val domainOf = mapOf("101" to "AAA", "102" to "AAA", "103" to "AAA", "104" to "AAA", "105" to "BBB")
        val domainRows = epicRows.groupBy { domainOf.getValue(it.scopeId) to it.day }.map { (key, group) ->
            FlowEvmRow("DOMAIN", key.first, key.second, group.sumOf { it.pv }, group.sumOf { it.ev }, group.sumOf { it.ac })
        }
        val teamRows = listOf(
            evmRow("TEAM", teamT.toString(), d[0], pv = "10.00"), evmRow("TEAM", teamT.toString(), d[1], ac = "8.00"),
            evmRow("TEAM", teamT.toString(), d[2], ev = "4.00"), evmRow("TEAM", "UNASSIGNED", d[1], ac = "2.00"),
            evmRow("TEAM", "UNOWNED", d[1]), // a backlog-only scope: all zero, never a row
            evmRow("TEAM", teamD.toString(), d[1], ac = "1.00"), // a team deleted below, before the unit is read
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
                    DimEpicRow(101, "HB-1", "Epic One", "AAA", null, "IN_PROGRESS", utcMidnight("2026-01-05"), utcMidnight("2026-01-14")),
                    DimEpicRow(102, "HB-2", null, "AAA", null, "IN_PROGRESS", null, null),
                    DimEpicRow(103, "HB-3", "Far away", "AAA", null, "NOT_STARTED", utcMidnight("2099-01-01"), utcMidnight("2099-02-01")),
                    DimEpicRow(
                        104, "HB-4", "Late spender", "AAA", null, "IN_PROGRESS", utcMidnight("2026-01-05"), utcMidnight("2026-01-09"),
                    ),
                    DimEpicRow(105, "HB-5", "Elsewhere", "BBB", null, "IN_PROGRESS", null, null),
                ),
                configRevision = 1L,
            )
            fun plan(issueId: Long, seq: Int, start: String, due: String, budget: Double, superseded: String?) = FactEpicPlanRow(
                issueId, seq,
                EpicPlanBaseline(
                    noonUtc("2025-12-01"), utcMidnight(start), utcMidnight(due), budget, "OWN", superseded?.let { noonUtc(it) },
                ),
            )
            store.insertFactEpicPlan(
                connId,
                listOf(
                    plan(101, 1, "2026-01-05", "2026-01-09", 10.0, "2026-01-02"), plan(101, 2, "2026-01-05", "2026-01-14", 24.0, null),
                    plan(103, 1, "2099-01-01", "2099-02-01", 5.0, null), plan(104, 1, "2026-01-05", "2026-01-09", 5.0, null),
                ),
                configRevision = 1L,
            )
        }
        insertFlowEvmRows(connId, epicRows + domainRows + teamRows)
        try {
            val client = seededClient("reports-evm-hand-built")
            val base = "connectionId=$connId&from=${d.first()}&to=${d.last()}"

            // EPIC HB-1: the exact curves, both baselines, the drift flags.
            val one = client.epicProgress("$base&epicId=HB-1")
            assertEquals("Epic One", one.scope!!.name)
            assertNull(one.note)
            assertEquals(d, one.series.map { it.date })
            assertEquals(listOf(3.0, 6.0, 9.0, 12.0, 15.0), one.series.map { it.pv })
            assertEquals(listOf(2.0, 6.0, 6.0, 12.0, 12.0), one.series.map { it.ev })
            assertEquals(listOf(4.5, 4.5, 8.0, 8.0, 8.0), one.series.map { it.ac })
            assertEquals(listOf(2.0, 4.0, 6.0, 8.0, 10.0), one.series.map { it.pvOriginal }, "the FIRST baseline, redrawn")
            assertEquals(d.last(), one.asOf.day)
            assertEquals(15.0, one.asOf.pv)
            assertEquals(12.0, one.asOf.ev)
            assertEquals(8.0, one.asOf.ac)
            assertEquals(-3.0, one.asOf.sv)
            assertEquals(0.8, one.asOf.spi!!, EPS)
            assertEquals(4.0, one.asOf.cv)
            assertEquals(1.5, one.asOf.cpi!!, EPS)
            val epic = one.epic!!
            assertEquals(24.0, epic.budgetMd)
            assertEquals("OWN", epic.budgetSource)
            assertEquals(utcMidnight("2026-01-05"), epic.startAt)
            assertEquals(utcMidnight("2026-01-14"), epic.dueAt)
            assertTrue(epic.inPvHorizon && epic.hasPvCurve)
            assertEquals(listOf(10.0, 24.0), epic.baselines.map { it.budgetMd })
            assertEquals(listOf(noonUtc("2026-01-02"), null), epic.baselines.map { it.supersededAt })
            assertEquals(utcMidnight("2026-01-09"), epic.baselines.first().dueAt)
            assertTrue(epic.drift.dates && epic.drift.budget, "the current baseline moved its due date and grew its budget")
            assertNotEquals(one.series.last().pvOriginal, one.series.last().pv)

            // HB-2: no PV -> SPI null; no AC -> CPI null. HB-3: an out-of-horizon epic has no PV curve and no original.
            val two = client.epicProgress("$base&epicId=HB-2")
            assertEquals(0.0, two.asOf.pv)
            assertEquals(5.0, two.asOf.ev)
            assertEquals(5.0, two.asOf.sv)
            assertNull(two.asOf.spi)
            assertNull(two.asOf.cpi)
            assertEquals("HB-2", two.scope!!.name, "an epic without a summary is named by its key")
            assertNull(two.epic!!.budgetMd)
            assertNull(two.epic.budgetSource)
            assertTrue(!two.epic.inPvHorizon && !two.epic.hasPvCurve && two.epic.baselines.isEmpty())
            assertTrue(!two.epic.drift.dates && !two.epic.drift.budget)
            val three = client.epicProgress("$base&epicId=HB-3")
            assertTrue(!three.epic!!.inPvHorizon && !three.epic.hasPvCurve, "2099 is outside the ±10 years PV horizon")
            assertEquals(5.0, three.epic.budgetMd)
            assertEquals(1, three.epic.baselines.size)
            assertTrue(three.series.all { it.pv == 0.0 }, "no PV rows for an out-of-horizon epic")
            assertTrue(three.series.all { it.pvOriginal == null }, "and its first baseline has no curve either")
            val four = client.epicProgress("$base&epicId=HB-4")
            assertEquals(5.0, four.asOf.pv)
            assertEquals(0.0, four.asOf.ev)
            assertEquals(2.0, four.asOf.ac)
            assertEquals(0.0, four.asOf.spi!!, EPS)
            assertEquals(0.0, four.asOf.cpi!!, EPS)
            assertEquals(-2.0, four.asOf.cv)

            // DOMAIN AAA: pv 20 / ev 17 / ac 10, and one row per epic with a current baseline or any EV/AC — never HB-5.
            val domain = client.epicProgress("$base&domain=AAA")
            assertEquals("Domain A", domain.scope!!.name)
            assertEquals(20.0, domain.asOf.pv)
            assertEquals(17.0, domain.asOf.ev)
            assertEquals(10.0, domain.asOf.ac)
            assertEquals(-3.0, domain.asOf.sv)
            assertEquals(0.85, domain.asOf.spi!!, EPS)
            assertEquals(7.0, domain.asOf.cv)
            assertEquals(1.7, domain.asOf.cpi!!, EPS)
            assertEquals(listOf("HB-1", "HB-2", "HB-3", "HB-4"), domain.rows.map { it.key })
            assertEquals(listOf(15.0, 0.0, 0.0, 5.0), domain.rows.map { it.pv })
            assertEquals(listOf(12.0, 5.0, 0.0, 0.0), domain.rows.map { it.ev })
            assertEquals(listOf(8.0, 0.0, 0.0, 2.0), domain.rows.map { it.ac })
            assertEquals("Far away", domain.rows[2].name)
            assertNull(domain.rows[1].spi)
            assertNull(domain.rows[1].cpi)
            assertEquals(0.0, domain.rows[3].spi!!, EPS)
            assertEquals(domain.asOf.pv, domain.rows.sumOf { it.pv }, EPS)
            assertEquals(domain.asOf.ev, domain.rows.sumOf { it.ev }, EPS)
            assertEquals(domain.asOf.ac, domain.rows.sumOf { it.ac }, EPS)
            assertEquals(listOf(3.0 + 1.0, 6.0 + 2.0, 9.0 + 3.0, 12.0 + 4.0, 15.0 + 5.0), domain.series.map { it.pv })
            assertEquals(listOf(2.0, 6.0, 11.0, 17.0, 17.0), domain.series.map { it.ev })
            assertTrue(domain.series.all { it.pvOriginal == null }, "pvOriginal is an EPIC-level figure")

            // TEAM: sprint PV/EV, author AC. teamId=0 is the author-less bucket.
            val team = client.epicProgress("$base&teamId=$teamT")
            assertEquals(EpicProgressLevel.TEAM, team.level)
            assertEquals(listOf(10.0, 10.0, 10.0, 10.0, 10.0), team.series.map { it.pv })
            assertEquals(listOf(0.0, 0.0, 4.0, 4.0, 4.0), team.series.map { it.ev })
            assertEquals(listOf(0.0, 8.0, 8.0, 8.0, 8.0), team.series.map { it.ac })
            assertEquals(-6.0, team.asOf.sv)
            assertEquals(0.4, team.asOf.spi!!, EPS)
            assertEquals(-4.0, team.asOf.cv)
            assertEquals(0.5, team.asOf.cpi!!, EPS)
            val unassigned = client.epicProgress("$base&teamId=0")
            assertEquals("Unassigned", unassigned.scope!!.name)
            assertEquals(2.0, unassigned.asOf.ac)
            assertNull(unassigned.asOf.spi)
            assertEquals(0.0, unassigned.asOf.cpi!!, EPS)

            cleanUpTeams(listOf(teamD))
            // UNIT: every DOMAIN scope (AAA 20/17/10 + BBB 0/100/0 = 20/117/10), no series; domain rows on the epic basis add
            // up to it, team rows are the sprint/author basis and do not.
            val unit = client.epicProgress(base)
            assertTrue(unit.series.isEmpty())
            assertEquals(20.0, unit.asOf.pv)
            assertEquals(117.0, unit.asOf.ev)
            assertEquals(10.0, unit.asOf.ac)
            assertEquals(97.0, unit.asOf.sv)
            assertEquals(5.85, unit.asOf.spi!!, EPS)
            assertEquals(107.0, unit.asOf.cv)
            assertEquals(11.7, unit.asOf.cpi!!, EPS)
            assertEquals(listOf("AAA", "BBB"), unit.rows.filter { it.kind == EpicProgressKind.DOMAIN }.map { it.key })
            val aaa = unit.rows.single { it.key == "AAA" }
            assertEquals("Domain A", aaa.name)
            assertEquals(listOf(20.0, 17.0, 10.0), listOf(aaa.pv, aaa.ev, aaa.ac))
            assertEquals(listOf(0.0, 100.0, 0.0), unit.rows.single { it.key == "BBB" }.let { listOf(it.pv, it.ev, it.ac) })
            val teams = unit.rows.filter { it.kind == EpicProgressKind.TEAM }
            val rowT = teams.single { it.id == teamT }
            assertEquals(listOf(10.0, 4.0, 8.0), listOf(rowT.pv, rowT.ev, rowT.ac))
            assertEquals(0.4, rowT.spi!!, EPS)
            assertEquals(0.5, rowT.cpi!!, EPS)
            val rowU = teams.single { it.id == 0u }
            assertEquals(listOf(0.0, 0.0, 2.0), listOf(rowU.pv, rowU.ev, rowU.ac))
            assertEquals(teams.last(), rowU, "UNASSIGNED sorts last")
            val domainRowsOfUnit = unit.rows.filter { it.kind == EpicProgressKind.DOMAIN }
            assertEquals(unit.asOf.pv, domainRowsOfUnit.sumOf { it.pv }, EPS)
            assertEquals(unit.asOf.ev, domainRowsOfUnit.sumOf { it.ev }, EPS)
            assertEquals(unit.asOf.ac, domainRowsOfUnit.sumOf { it.ac }, EPS)
            assertEquals(10.0, teams.sumOf { it.pv }, EPS, "the team rows are a different basis: their pv is the sprint scope")
            assertTrue(teams.none { it.name == "UNOWNED" }, "a scope with no figures is not a team row")
            assertTrue(rowT.active == true && rowU.active == true, "a live team and the UNASSIGNED bucket are drillable")
            val rowD = teams.single { it.id == teamD }
            assertEquals(1.0, rowD.ac, EPS)
            assertEquals(false, rowD.active, "a soft-deleted team keeps its figures but is marked: its own drill is a 400")
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-progress?$base&teamId=$teamD").status)
            assertTrue(unit.rows.filter { it.kind != EpicProgressKind.TEAM }.all { it.active == null })

            // The period cap: asOf is the last derived day (2026-06-01) with everything planned so far — HB-1's full 24 MD.
            val long = client.epicProgress("connectionId=$connId&from=2026-01-05&to=2026-12-31&epicId=HB-1")
            assertEquals("2026-06-01", long.asOf.day)
            assertEquals(24.0, long.asOf.pv)
            assertEquals(isoDays("2026-01-05", "2026-06-01"), long.series.map { it.date })
            assertEquals(10.0, long.series.last().pvOriginal, "the first baseline stays at its own 10 MD after its due date")
            val after = client.epicProgress("connectionId=$connId&from=2026-07-01&to=2026-07-31&epicId=HB-1")
            assertTrue(after.series.isEmpty())
            assertEquals("2026-06-01", after.asOf.day)
            assertEquals(24.0, after.asOf.pv)
            assertTrue(after.note!!.contains("after 2026-06-01"))
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteDims(connId)
                store.deleteFactEpicPlan(connId)
            }
            deleteFlowRows(connId)
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamT))
        }
    }

    /**
     * Team T's authors logged 100 MD of foreign work in December, 4 MD of foreign work and 6 MD of their own on Jan 6-7, and 50
     * MD of their own on Jan 12. The share is CUMULATIVE — the same window as CPI, from the beginning of time to `asOf.day` —
     * so it is 100/100 as of Dec 31, 104/110 as of Jan 9 (the Jan 12 work is after asOf and never counted, and neither period
     * start matters), 104/160 as of Jan 14. Team U logged nothing (null, never 0). Authors in no team (`teamId=0`) logged 3 MD,
     * never foreign (0.0). The share is a TEAM-level field only.
     */
    @Test
    fun `team foreignWorkShare is the foreign share of the authors' logged MD up to asOf, the same window as CPI`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "evm-foreign", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val teamT = TestTeams.seed(SyncedStubFixture.unique("evm-foreign-t"))
        val teamU = TestTeams.seed(SyncedStubFixture.unique("evm-foreign-u"))
        fun log(id: Long, team: UInt?, day: String, md: Double, foreign: Boolean) = FactWorklogRow(
            worklogId = id, issueId = 1, authorAccountId = "acc", authorTeamId = team, startedAt = noonUtc(day),
            createdAt = null, lateMs = null,
            md = md, taskDomainKey = "AAA", epicId = null, epicDomainKey = null, activityType = "Story", workCategory = null,
            sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = foreign, assigneeAccountIdAtStarted = null,
            assigneeTeamIdAtStarted = null,
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertFactWorklog(
                connId,
                listOf(
                    log(1, teamT, "2026-01-06", 4.0, true), log(2, teamT, "2026-01-07", 6.0, false),
                    log(3, teamT, "2025-12-10", 100.0, true), log(4, null, "2026-01-08", 3.0, false),
                    log(5, teamT, "2026-01-12", 50.0, false),
                ),
                configRevision = 1L,
            )
        }
        try {
            val client = seededClient("reports-evm-foreign")
            val base = "connectionId=$connId&from=2026-01-05&to=2026-01-09"
            assertEquals(104.0 / 110.0, client.epicProgress("$base&teamId=$teamT").foreignWorkShare!!, EPS)
            suspend fun share(from: String, to: String) =
                client.epicProgress("connectionId=$connId&from=$from&to=$to&teamId=$teamT").foreignWorkShare!!
            assertEquals(104.0 / 110.0, share("2026-01-08", "2026-01-09"), EPS, "the period start does not matter, asOf does")
            assertEquals(1.0, share("2025-12-01", "2025-12-31"), EPS)
            assertEquals(104.0 / 160.0, share("2026-01-05", "2026-01-14"), EPS)
            assertNull(client.epicProgress("$base&teamId=$teamU").foreignWorkShare, "no logged work: undefined, not 0")
            assertEquals(0.0, client.epicProgress("$base&teamId=0").foreignWorkShare!!, EPS)
            assertNull(client.epicProgress(base).foreignWorkShare, "the unit level carries no share")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactWorklog(connId) }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamT, teamU))
        }
    }

    /** Two connections derived through different days read together stop at the OLDEST; a never-derived one is named and ignored. */
    @Test
    fun `the cut-off is the oldest last derived day across the connections in scope and never-derived reads as empty with a note`() =
        testApplication {
            usePostgresTestcontainer()
            val connA = SyncedStubFixture.createConnection(namePrefix = "evm-lag-a", enabled = false)
            val connB = SyncedStubFixture.createConnection(namePrefix = "evm-lag-b", enabled = false)
            val connC = SyncedStubFixture.createConnection(namePrefix = "evm-lag-c", enabled = false)
            val store = MetricsStore(sharedDatabaseForTests())
            val domain = "ZZ-evm-lag"
            fun day(n: Int) = "2020-01-%02d".format(n)
            insertSucceededDerive(connA, noonUtc("2020-01-09"))
            insertSucceededDerive(connB, noonUtc("2020-01-07"))
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertDomains(connA, listOf(DimDomainRow(domain, "Lag", emptyList(), null)), configRevision = 1L)
                store.insertDomains(connC, listOf(DimDomainRow(domain, "Lag", emptyList(), null)), configRevision = 1L)
            }
            insertFlowEvmRows(connA, (5..9).map { evmRow("DOMAIN", domain, day(it), pv = "1.50", ev = "1.00", ac = "0.50") })
            insertFlowEvmRows(connB, (5..7).map { evmRow("DOMAIN", domain, day(it), pv = "10.00", ev = "2.00", ac = "1.00") })
            try {
                val client = seededClient("reports-evm-lag")
                val body = client.epicProgress("from=2020-01-05&to=2020-01-11&domain=$domain")
                assertEquals(listOf(day(5), day(6), day(7)), body.series.map { it.date })
                assertEquals(listOf(11.5, 23.0, 34.5), body.series.map { it.pv })
                assertEquals(day(7), body.asOf.day)
                assertEquals(34.5, body.asOf.pv)
                assertEquals(9.0, body.asOf.ev)
                assertEquals(4.5, body.asOf.ac)
                assertTrue(assertNotNull(body.note).contains(connC.toString()), "the never-derived connection is named")
                val onlyA = client.epicProgress("connectionId=$connA&from=2020-01-05&to=2020-01-11&domain=$domain")
                assertEquals((5..9).map { day(it) }, onlyA.series.map { it.date })
                assertEquals(7.5, onlyA.asOf.pv)
                assertNull(onlyA.note)

                // connC never derived: empty, the same note the snapshot reports use, and nothing is validated against dimensions
                // that do not exist yet (the domain is unknown there only because nothing derived).
                val never = client.epicProgress("connectionId=$connC&from=2020-01-05&to=2020-01-11&domain=nowhere")
                assertTrue(never.series.isEmpty() && never.rows.isEmpty())
                assertNull(never.asOf.day)
                assertEquals(0.0, never.asOf.pv)
                assertNull(never.asOf.spi)
                assertTrue(assertNotNull(never.note).startsWith("Not derived yet"))
                assertEquals("nowhere", never.scope!!.key)
                assertEquals(EpicProgressLevel.UNIT, client.epicProgress("connectionId=$connC").level)
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteDims(connA)
                    store.deleteDims(connC)
                }
                deleteFlowRows(connA)
                deleteFlowRows(connB)
                deleteDeriveRuns(connA)
                deleteDeriveRuns(connB)
            }
        }

    /**
     * `pvOriginal` redraws the FIRST baseline with the working days DERIVE stamped (`dim_date.is_working_day`), rounded like the
     * stored increments. HB-7 (9 MD, Mon Feb 2 - Wed Feb 4 2026): `dim_date` says Feb 3 is NOT a working day, though the settings
     * calendar would say it is — two working days, 4.50 / 4.50 / 9.00, not 3 / 6 / 9. HB-8 (10 MD over three working days, Feb 9-11)
     * is not divisible: ROUND(10 * i / 3, 2) = 3.33 / 6.67 / 10.00, and the stored increments 3.33 / 3.34 / 3.33 give `pv`
     * exactly the same curve. HB-9 (Sat Feb 14 - Sun Feb 15) lies in the horizon but holds no working day: no PV curve at all.
     * The three `dim_date` rows of HB-7 are overwritten for the test and restored afterwards (the table is global).
     */
    @Test
    fun `pvOriginal redraws with the derive-time working days and the stored cumulative rounding, and hasPvCurve needs a working day`() =
        testApplication {
            usePostgresTestcontainer()
            DerivedStubFixture.connectionId() // stamps dim_date for the 2026 days below (the settings calendar); restored after
            val connId = SyncedStubFixture.createConnection(namePrefix = "evm-original", enabled = false)
            insertSucceededDerive(connId, noonUtc("2026-06-01"))
            val store = MetricsStore(sharedDatabaseForTests())
            val stampDays = listOf("2026-02-02", "2026-02-03", "2026-02-04")
            val dd = MetricsTables.DimDate
            val originals = suspendTransaction(sharedDatabaseForTests()) { dd.selectAll().where { dd.day inList stampDays }.toList() }
            val originalRevision = originals.first()[dd.configRevision]
            val originalStamps = originals.map {
                WorkingCalendar.DimDateRow(it[dd.day], it[dd.dayStartMs], it[dd.dayEndMs], it[dd.isWorkingDay])
            }
            val zone = reportZone()
            val stamped = stampDays.map { day ->
                val start = LocalDate.parse(day).atStartOfDay(zone)
                WorkingCalendar.DimDateRow(
                    day, start.toInstant().toEpochMilli(), start.plusDays(1).toInstant().toEpochMilli(), day != "2026-02-03",
                )
            }
            DerivedStubFixture.stampDimDate(stamped, configRevision = originalRevision)
            fun plan(issueId: Long, start: String, due: String, budget: Double) = FactEpicPlanRow(
                issueId, 1, EpicPlanBaseline(noonUtc("2026-01-01"), utcMidnight(start), utcMidnight(due), budget, "OWN", null),
            )
            val epics = listOf(
                DimEpicRow(107, "HB-7", null, "CCC", null, "IN_PROGRESS", utcMidnight("2026-02-02"), utcMidnight("2026-02-04")),
                DimEpicRow(108, "HB-8", null, "CCC", null, "IN_PROGRESS", utcMidnight("2026-02-09"), utcMidnight("2026-02-11")),
                DimEpicRow(109, "HB-9", null, "CCC", null, "IN_PROGRESS", utcMidnight("2026-02-14"), utcMidnight("2026-02-15")),
            )
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertEpics(connId, epics, configRevision = 1L)
                store.insertFactEpicPlan(
                    connId,
                    listOf(
                        plan(107, "2026-02-02", "2026-02-04", 9.0), plan(108, "2026-02-09", "2026-02-11", 10.0),
                        plan(109, "2026-02-14", "2026-02-15", 4.0),
                    ),
                    configRevision = 1L,
                )
            }
            insertFlowEvmRows(
                connId,
                listOf(
                    evmRow("EPIC", "107", "2026-02-02", pv = "4.50"), evmRow("EPIC", "107", "2026-02-04", pv = "4.50"),
                    evmRow("EPIC", "108", "2026-02-09", pv = "3.33"), evmRow("EPIC", "108", "2026-02-10", pv = "3.34"),
                    evmRow("EPIC", "108", "2026-02-11", pv = "3.33"),
                ),
            )
            try {
                val client = seededClient("reports-evm-original")
                val seven = client.epicProgress("connectionId=$connId&from=2026-02-02&to=2026-02-04&epicId=HB-7")
                assertEquals(listOf(4.5, 4.5, 9.0), seven.series.map { it.pv })
                assertEquals(listOf(4.5, 4.5, 9.0), seven.series.map { it.pvOriginal }, "the stamped calendar, not the settings one")
                assertTrue(seven.epic!!.inPvHorizon && seven.epic.hasPvCurve)

                // Independently: ROUND(budget * i / n, 2) per working day, half up.
                val eight = client.epicProgress("connectionId=$connId&from=2026-02-09&to=2026-02-11&epicId=HB-8")
                val expected = (1..3).map {
                    BigDecimal(10).multiply(BigDecimal(it)).divide(BigDecimal(3), 2, RoundingMode.HALF_UP).toDouble()
                }
                assertEquals(listOf(3.33, 6.67, 10.0), expected)
                assertEquals(expected, eight.series.map { it.pvOriginal }, "pvOriginal is ROUND(b*i/n, 2) day by day")
                assertEquals(expected, eight.series.map { it.pv }, "and equals the stored cumulative pv exactly")

                val nine = client.epicProgress("connectionId=$connId&from=2026-02-14&to=2026-02-15&epicId=HB-9")
                assertTrue(nine.epic!!.inPvHorizon, "both dates are within the horizon")
                assertTrue(!nine.epic.hasPvCurve, "but a weekend holds no working day to spread the budget over")
                assertTrue(nine.series.all { it.pvOriginal == null && it.pv == 0.0 })
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteDims(connId)
                    store.deleteFactEpicPlan(connId)
                }
                DerivedStubFixture.stampDimDate(originalStamps, configRevision = originalRevision)
                deleteFlowRows(connId)
                deleteDeriveRuns(connId)
            }
        }

    @Test
    fun `bad filters are 400, never 404, and a plain user is allowed`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-evm-400")
        val team = TestTeams.seed(SyncedStubFixture.unique("evm-400"))
        val dupA = SyncedStubFixture.createConnection(namePrefix = "evm-dup-a", enabled = false)
        val dupB = SyncedStubFixture.createConnection(namePrefix = "evm-dup-b", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        suspendTransaction(sharedDatabaseForTests()) {
            for (id in listOf(dupA, dupB)) {
                val dup = DimEpicRow(900, "DUP-1", "Same key", "AAA", null, "IN_PROGRESS", null, null)
                store.insertEpics(id, listOf(dup), configRevision = 1L)
            }
        }
        insertSucceededDerive(dupA, noonUtc("2026-06-01"))
        try {
            val known = "connectionId=$connId"
            for (query in listOf(
                "$known&epicId=${goldenEpic.issueKey}&domain=FLO", // more than one scope
                "$known&epicId=${goldenEpic.issueKey}&teamId=$team",
                "$known&domain=FLO&teamId=$team",
                "$known&teamId=$team&accountId=someone", // no user level
                "$known&accountId=someone", // accountId needs teamId (the shared parser)
                "$known&epicId=", // a blank epicId is a mistake, never "the whole unit"
                "$known&epicId=%20",
                "$known&domainView=TASK", // EVM is always the EPIC view
                "$known&activityType=Story",
                "$known&workCategory=UNCATEGORIZED",
                "$known&epicId=NOPE-1", // unknown epic
                "$known&domain=NOPE",
                "$known&teamId=999999",
                "connectionId=999999",
                "$known&sprintId=999999999",
                "$known&from=2026-01-01&to=2025-01-01",
                "$known&lastSprints=0",
                "epicId=DUP-1&connectionId=$dupA&epicId=DUP-2", // a repeated scalar key
                "epicId=DUP-1", // the same key in two connections in scope: ambiguous
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-progress?$query").status, query)
            }
            // The repeated-key case above is a parser 400 too; the ambiguity resolves once narrowed to one connection.
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/epic-progress?epicId=DUP-1&connectionId=$dupA").status)
            // An explicit EPIC view is fine (it is the default), and a plain user reads every level (D12).
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/epic-progress?$known&domainView=EPIC").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/epic-progress?$known&teamId=$team").status)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteDims(dupA)
                store.deleteDims(dupB)
            }
            deleteDeriveRuns(dupA)
            cleanUpTeams(listOf(team))
        }
    }
}
