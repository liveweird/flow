package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.EpicPlanBaseline
import ch.nokillswit.metrics.FactEpicPlanRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.DeepDiveMode
import ch.nokillswit.reports.DeepDiveNoPlanReason
import ch.nokillswit.reports.DeepDivePlanSource
import ch.nokillswit.reports.DeepDiveReport
import ch.nokillswit.reports.DeepDiveTask
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DD_EPS = 1e-9
private const val DD_BASE = "/api/v1/reports/deep-dive"
private const val DD_STAGE = "IN_PROGRESS"

// ---- the independent read of the stored facts ---------------------------------------------------------

private class DdTask(
    val issueId: Long,
    val key: String,
    val domain: String?,
    val epicId: Long?,
    val isSubtask: Boolean,
    val parentId: Long?,
    val doneAt: Long?,
    val estimateAtDone: BigDecimal?,
)

private class DdScope(val sprintId: Long, val issueId: Long, val inScope: Boolean, val commitment: BigDecimal?)

private class DdSprint(val id: Long, val start: Long?, val end: Long?, val complete: Long?, val name: String)

private class DdSpan(val issueId: Long, val from: Long, val to: Long?)

private class DdLog(val issueId: Long, val author: String?, val startedAt: Long, val md: BigDecimal)

private class DdEpic(val issueId: Long, val key: String)

private class DdPlan(val issueId: Long, val start: Long?, val due: Long?, val budget: BigDecimal?, val superseded: Long?)

/** Every fact the deep dive reads, straight from the tables (never through `reports/`) plus the settings calendar. */
private class DdStored(
    val tasks: Map<Long, DdTask>,
    val scope: List<DdScope>,
    val sprints: Map<Long, DdSprint>,
    val spans: List<DdSpan>,
    val logs: List<DdLog>,
    val epics: List<DdEpic>,
    val plans: List<DdPlan>,
    val names: Map<String, String>,
    val clockMs: Long,
    val calendar: WorkingCalendar,
)

private suspend fun ddReadStored(connId: UInt): DdStored {
    val calendar = WorkingCalendar.of(DerivedStubFixture.metricsSettings().read())
    return suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsTables.FactTaskDelivery
        val tasks = t.selectAll().where { t.connectionId eq connId }.toList().associate {
            it[t.issueId] to DdTask(
                it[t.issueId], it[t.issueKey], it[t.domainKey], it[t.epicId], it[t.isSubtask], it[t.parentTaskId], it[t.doneAt],
                it[t.estimateAtDoneMd],
            )
        }
        val sc = MetricsTables.FactSprintScope
        val scope = sc.selectAll().where { sc.connectionId eq connId }.toList()
            .map { DdScope(it[sc.sprintId], it[sc.issueId], it[sc.inScopeAtClose], it[sc.estimateAtCommitmentMd]) }
        val sp = MetricsTables.DimSprint
        val sprints = sp.selectAll().where { sp.connectionId eq connId }.toList().associate {
            it[sp.sprintId] to DdSprint(it[sp.sprintId], it[sp.startAt], it[sp.endAt], it[sp.completeAt], it[sp.name])
        }
        val st = MetricsTables.ItemStage
        val spans = st.selectAll().where { (st.connectionId eq connId) and (st.stage eq DD_STAGE) }.toList()
            .map { DdSpan(it[st.issueId], it[st.validFrom], it[st.validTo]) }
        val w = MetricsTables.FactWorklog
        val logs = w.selectAll().where { w.connectionId eq connId }.toList()
            .map { DdLog(it[w.issueId], it[w.authorAccountId], it[w.startedAt], it[w.md]) }
        val e = MetricsTables.DimEpic
        val epics = e.selectAll().where { e.connectionId eq connId }.toList().map { DdEpic(it[e.issueId], it[e.issueKey]) }
        val p = MetricsTables.FactEpicPlan
        val plans = p.selectAll().where { p.connectionId eq connId }.toList()
            .map { DdPlan(it[p.issueId], it[p.startAt], it[p.dueAt], it[p.budgetMd], it[p.supersededAt]) }
        val pe = WorkItemStore.People
        val names = pe.selectAll().where { pe.connectionId eq connId }.toList().associate { it[pe.accountId] to it[pe.displayName] }
        val r = MetricsTables.DeriveRuns
        val clock = r.startedAt.max()
        val runs = r.select(clock).where { (r.connectionId eq connId.toInt()) and (r.status eq "SUCCEEDED") }.toList().single()[clock]
        DdStored(tasks, scope, sprints, spans, logs, epics, plans, names, assertNotNull(runs), calendar)
    }
}

// ---- the independent expectations ---------------------------------------------------------------------

private fun DdStored.dayOf(ms: Long): LocalDate = calendar.dayOf(ms)

/** The sprints (earliest first) a task was in scope at close in. */
private fun DdStored.sprintsOf(issueId: Long): List<Pair<DdScope, DdSprint>> =
    scope.filter { it.issueId == issueId && it.inScope }
        .map { it to sprints.getValue(it.sprintId) }
        .sortedWith(compareBy({ it.second.start ?: it.second.complete ?: it.second.end ?: Long.MAX_VALUE }, { it.second.id }))

private fun DdStored.unionDays(sprintList: List<DdSprint>): List<LocalDate> {
    val days = sortedSetOf<LocalDate>()
    for (s in sprintList) {
        val close = s.complete ?: s.end ?: continue
        val first = dayOf(s.start ?: close)
        val last = minOf(dayOf(close), first.plusDays(1099))
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
            .filter { calendar.isWorkingDay(it) }.forEach { days += it }
    }
    return days.toList()
}

/** A task's expected plan: (basis, source, reason, pv by day). */
private class DdExpectedPlan(
    val basis: BigDecimal?,
    val source: DeepDivePlanSource,
    val reason: DeepDiveNoPlanReason?,
    val pv: Map<LocalDate, BigDecimal>,
)

private fun DdStored.expectedPlan(issueId: Long): DdExpectedPlan {
    val list = sprintsOf(issueId)
    if (list.isEmpty()) return DdExpectedPlan(null, DeepDivePlanSource.NONE, DeepDiveNoPlanReason.NEVER_IN_SPRINT, emptyMap())
    val first = list.first().first.commitment
    val later = list.drop(1).firstNotNullOfOrNull { it.first.commitment }
    val basis = first ?: later
        ?: return DdExpectedPlan(null, DeepDivePlanSource.NONE, DeepDiveNoPlanReason.NO_ESTIMATE, emptyMap())
    val source = if (first != null) DeepDivePlanSource.EARLIEST else DeepDivePlanSource.LATER_FALLBACK
    val days = unionDays(list.map { it.second })
    if (days.isEmpty()) return DdExpectedPlan(basis, source, DeepDiveNoPlanReason.NO_WORKING_DAY, emptyMap())
    var previous = BigDecimal.ZERO.setScale(2)
    val pv = linkedMapOf<LocalDate, BigDecimal>()
    days.forEachIndexed { index, day ->
        val running = basis.multiply(BigDecimal(index + 1)).divide(BigDecimal(days.size), 2, RoundingMode.HALF_UP)
        if ((running - previous).signum() != 0) pv[day] = running - previous
        previous = running
    }
    return DdExpectedPlan(basis, source, null, pv)
}

/** Per day: the fraction of a working day each IN_PROGRESS span covers, summed. */
private fun DdStored.expectedExec(issueId: Long): Map<LocalDate, Double> {
    val perDay = sortedMapOf<LocalDate, Double>()
    for (span in spans.filter { it.issueId == issueId }) {
        val to = span.to ?: clockMs
        if (to <= span.from) continue
        var day = dayOf(span.from)
        while (calendar.dayBoundsMs(day).first < to) {
            val (dayStart, dayEnd) = calendar.dayBoundsMs(day)
            val fraction = calendar.workingDaysBetween(maxOf(dayStart, span.from), minOf(dayEnd, to))
            if (fraction > 0.0) perDay.merge(day, fraction, Double::plus)
            day = day.plusDays(1)
        }
    }
    return perDay
}

/** The whole-life execution sum straight from `workingDaysBetween` over each span (no per-day split at all). */
private fun DdStored.expectedExecTotal(issueId: Long): Double =
    spans.filter { it.issueId == issueId }.sumOf { calendar.workingDaysBetween(it.from, it.to ?: clockMs) }

/** The worklogs a task owns: its own plus its sub-tasks' (read off `fact_task_delivery.parent_task_id`, not `dim_task`). */
private fun DdStored.logsOf(issueId: Long): List<DdLog> {
    val subs = tasks.values.filter { it.isSubtask && it.parentId == issueId }.map { it.issueId }.toSet()
    return logs.filter { it.issueId == issueId || it.issueId in subs }
}

private fun DdStored.expectedCost(issueId: Long): Map<Pair<LocalDate, String?>, Double> =
    logsOf(issueId).groupBy({ dayOf(it.startedAt) to it.author }, { it.md })
        .mapValues { (_, mds) -> mds.fold(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP).toDouble() }

private fun Collection<BigDecimal>.rounded(): Double = fold(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP).toDouble()

// ---- reading a response in absolute days ---------------------------------------------------------------

private fun DeepDiveReport.day(offset: Int): LocalDate = LocalDate.parse(range.from).plusDays(offset.toLong())

private fun DeepDiveReport.contains(day: LocalDate) = !day.isBefore(LocalDate.parse(range.from)) && !day.isAfter(LocalDate.parse(range.to))

private fun DeepDiveReport.pvOf(task: DeepDiveTask): Map<LocalDate, Double> = task.pv.associate { day(it.d) to it.md }

private fun DeepDiveReport.execOf(task: DeepDiveTask): Map<LocalDate, Double> = task.exec.associate { day(it.d) to it.td }

private fun DeepDiveReport.costOf(task: DeepDiveTask): Map<Pair<LocalDate, String?>, Double> =
    task.cost.associate { (day(it.d) to it.a?.let { index -> authors[index].accountId }) to it.md }

private fun ddAssertSeries(label: String, expected: Map<LocalDate, Double>, actual: Map<LocalDate, Double>, tolerance: Double = 0.0) {
    assertEquals(expected.keys, actual.keys, "$label: the days")
    expected.forEach { (day, value) -> assertEquals(value, actual.getValue(day), tolerance + DD_EPS, "$label on $day") }
}

private fun utcDay(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

private fun ddNoonZ(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant().toEpochMilli()

private fun ddUtcMidnightMs(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * `GET /api/v1/reports/deep-dive` (Report 17, `.claude/docs/reports.md`, A29). The shared derived fixture is graded read-only (always with
 * its `connectionId`: other classes leave clones with the same keys) against INDEPENDENT reads of the stored facts; the carry-over,
 * fallback, never-in-sprint, window-less epic, epic-logged, unknown-author, 501-task, over-range and never-derived cases run on
 * hand-built rows in fresh DISABLED connections. Every request goes through a non-admin `seededClient` (D12); the `401` comes from the
 * spec sweep (`AnonymousAccessTest`).
 */
class ReportDeepDiveTest {

    private suspend fun HttpClient.dive(query: String): DeepDiveReport {
        val response = get("$DD_BASE?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET deep-dive?$query")
        return response.body()
    }

    private suspend fun HttpClient.assertBadRequest(query: String) {
        val response: HttpResponse = get("$DD_BASE?$query")
        assertEquals(HttpStatusCode.BadRequest, response.status, "GET deep-dive?$query")
    }

    private fun sprintQuery(conn: UInt, domain: String, ids: List<Long>) =
        "connectionId=$conn&domain=$domain&" + ids.joinToString("&") { "sprintId=$it" }

    /** The domain with the most sprints and its three busiest sprints (by in-scope level-0 tasks of that domain). */
    private fun busiestSelection(stored: DdStored): Pair<String, List<Long>> {
        val level0 = stored.tasks.filterValues { !it.isSubtask }
        fun counts(domain: String) = stored.scope.filter { it.inScope && level0[it.issueId]?.domain == domain }
            .groupBy({ it.sprintId }, { it.issueId }).mapValues { it.value.distinct().size }
        val domain = level0.values.mapNotNull { it.domain }.distinct().maxBy { counts(it).size }
        val sprints = counts(domain).entries.sortedWith(compareBy({ -it.value }, { it.key })).take(3).map { it.key }
        assertTrue(sprints.size >= 2, "the fixture must hold a domain in at least two sprints (got ${sprints.size})")
        return domain to sprints
    }

    /** Grades [report]'s every task row against the independent expectations (the range's clipping included). */
    private fun assertTasks(stored: DdStored, report: DeepDiveReport, connIds: Map<String, Long>) {
        for (task in report.tasks) {
            val issueId = connIds.getValue(task.key)
            val plan = stored.expectedPlan(issueId)
            val label = task.key
            assertEquals(plan.source, task.planSource, "$label plan source")
            assertEquals(plan.reason, task.noPlanReason, "$label plan reason")
            assertEquals(plan.basis?.setScale(2)?.toDouble(), task.planBasisMd, "$label plan basis")
            // A task's PV total is its basis whenever it has a plan; the in-range series is the expected one cut to the range.
            val pvTotal = plan.pv.values.toList().rounded()
            assertEquals(pvTotal, task.totals.pvMd, DD_EPS, "$label PV total")
            if (plan.reason == null) {
                assertEquals(plan.basis!!.setScale(2).toDouble(), task.totals.pvMd, DD_EPS, "$label PV total = its basis")
            }
            ddAssertSeries("$label pv", plan.pv.filterKeys { report.contains(it) }.mapValues { it.value.toDouble() }, report.pvOf(task))
            // Nothing of the task lies outside the range: the in-range series IS the total (else the rest is clipped, in the totals only).
            if (plan.pv.keys.all { report.contains(it) }) {
                assertEquals(pvTotal, task.pv.sumOf { it.md }, 1e-6, "$label in-range PV = the total")
            }

            val exec = stored.expectedExec(issueId)
            val execInRange = exec.filterKeys { report.contains(it) }.mapValues { it.value.roundTo4() }
            ddAssertSeries("$label exec", execInRange, report.execOf(task), 1e-4)
            val execTotal = stored.expectedExecTotal(issueId)
            assertEquals(execTotal, task.totals.execTaskDays, 1e-3, "$label execution = workingDaysBetween over item_stage")

            val fact = stored.tasks.getValue(issueId)
            val doneDay = fact.doneAt?.let { stored.dayOf(it) }
            val evMd = (fact.estimateAtDone ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toDouble()
            if (doneDay == null || !report.contains(doneDay)) {
                assertNull(task.done, "$label done marker outside the range / not done")
            } else {
                assertEquals(doneDay, report.day(assertNotNull(task.done).d), "$label EV marker day")
                assertEquals(evMd, task.done.evMd, DD_EPS, "$label EV marker = estimate_at_done_md")
            }
            assertEquals(if (doneDay == null) 0.0 else evMd, task.totals.evMd, DD_EPS, "$label EV total")

            val cost = stored.expectedCost(issueId)
            assertEquals(cost.filterKeys { report.contains(it.first) }, report.costOf(task), "$label cost series")
            assertEquals(stored.logsOf(issueId).map { it.md }.rounded(), task.totals.costMd, DD_EPS, "$label cost total")
            if (cost.keys.all { report.contains(it.first) }) {
                assertEquals(task.totals.costMd, task.cost.sumOf { it.md }, 0.005 * (task.cost.size + 1), "$label in-range cost = total")
            }
        }
    }

    private fun Double.roundTo4() = BigDecimal.valueOf(this).setScale(4, RoundingMode.HALF_UP).toDouble()

    @Test
    fun `fixture - SPRINTS mode selects the domain's in-scope tasks and every layer equals an independent read`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-sprints")
        val stored = ddReadStored(connId)
        val (domain, sprintIds) = busiestSelection(stored)
        val report = client.dive(sprintQuery(connId, domain, sprintIds))

        assertEquals(DeepDiveMode.SPRINTS, report.mode)
        // The task set is fact_sprint_scope(in_scope_at_close) joined to fact_task_delivery(domain, level 0).
        val expectedKeys = stored.scope.filter { it.inScope && it.sprintId in sprintIds }.map { it.issueId }.distinct()
            .mapNotNull { stored.tasks[it] }.filter { !it.isSubtask && it.domain == domain }.map { it.key }.toSet()
        assertTrue(expectedKeys.isNotEmpty())
        assertEquals(expectedKeys, report.tasks.map { it.key }.toSet())
        assertEquals(sprintIds.toSet(), report.sprints.map { it.id }.toSet())
        assertNull(report.note)
        // The range is the envelope of the selected sprints: the first start day to the last close day.
        val windows = sprintIds.map { stored.sprints.getValue(it) }.mapNotNull { s ->
            (s.complete ?: s.end)?.let { stored.dayOf(s.start ?: it) to stored.dayOf(it) }
        }
        assertEquals(windows.minOf { it.first }.toString(), report.range.from)
        assertEquals(windows.maxOf { it.second }.toString(), report.range.to)
        assertEquals(stored.dayOf(stored.clockMs).toString(), report.range.asOfDay)
        val from = LocalDate.parse(report.range.from)
        val to = LocalDate.parse(report.range.to)
        assertEquals(
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter { !stored.calendar.isWorkingDay(it) }
                .map { it.toEpochDay().toInt() - from.toEpochDay().toInt() }.toList(),
            report.nonWorkingDays,
        )
        // Selected sprints carry their plan windows as offsets from range.from.
        report.sprints.forEach { sprint ->
            val s = stored.sprints.getValue(sprint.id)
            val close = s.complete ?: s.end
            if (close != null) assertEquals(stored.dayOf(close), report.day(assertNotNull(sprint.endDay)), "${s.name} window end")
        }
        val byKey = stored.tasks.values.associate { it.key to it.issueId }
        assertTasks(stored, report, byKey)
        // The comparison is not vacuous: the fixture's sprint tasks carry every layer.
        assertTrue(report.tasks.any { it.pv.isNotEmpty() } && report.tasks.any { it.exec.isNotEmpty() })
        assertTrue(report.tasks.any { it.cost.isNotEmpty() } && report.tasks.any { it.done != null })
        // Every epic the tasks sit under is listed (the epic-less ones fall into the key-less row).
        val epicKeys = report.tasks.mapNotNull { it.epicKey }.toSet()
        assertEquals(epicKeys, report.epics.mapNotNull { it.key }.toSet())
        assertEquals(report.tasks.any { it.epicKey == null }, report.epics.any { it.key == null })
        assertTrue(report.epics.all { it.ownCost == null }, "epic-logged cost is shown in mode EPICS only")
        // The quality counters equal the independent plan reasons.
        val plans = report.tasks.map { stored.expectedPlan(byKey.getValue(it.key)) }
        assertEquals(plans.count { it.reason == DeepDiveNoPlanReason.NEVER_IN_SPRINT }, report.quality.neverInSprint)
        assertEquals(plans.count { it.reason == DeepDiveNoPlanReason.NO_ESTIMATE }, report.quality.noEstimate)
        assertEquals(plans.count { it.reason == DeepDiveNoPlanReason.NO_WORKING_DAY }, report.quality.noWorkingDay)
        assertEquals(plans.count { it.source == DeepDivePlanSource.LATER_FALLBACK }, report.quality.laterFallback)
    }

    @Test
    fun `fixture - a wide range keeps every figure in the series and a narrow one moves the rest into the totals only`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-clip")
        val stored = ddReadStored(connId)
        val (domain, sprintIds) = busiestSelection(stored)
        val query = sprintQuery(connId, domain, sprintIds)
        val byKey = stored.tasks.values.associate { it.key to it.issueId }
        val base = client.dive(query)
        val from = LocalDate.parse(base.range.from)
        val to = LocalDate.parse(base.range.to)
        val span = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        assertTrue(span < 1000, "the selected sprints span $span days")
        val pad = minOf(500L, (1100 - span) / 2)

        val wide = client.dive("$query&from=${from.minusDays(pad)}&to=${to.plusDays(pad)}")
        assertEquals(from.minusDays(pad).toString(), wide.range.from)
        assertTasks(stored, wide, byKey)
        // Totals never depend on the range.
        assertEquals(base.tasks.map { it.totals }, wide.tasks.map { it.totals })

        val middle = from.plusDays(10)
        val narrow = client.dive("$query&from=$middle&to=${middle.plusDays(9)}")
        assertEquals(middle.toString(), narrow.range.from)
        assertEquals(10, narrow.nonWorkingDays.size + (0 until 10).count { stored.calendar.isWorkingDay(middle.plusDays(it.toLong())) })
        assertTasks(stored, narrow, byKey)
        // In-range plus clipped equals the total: the wide series split at the narrow range loses nothing.
        for (task in wide.tasks) {
            val cut = narrow.tasks.first { it.key == task.key }
            val inRange = wide.pvOf(task).filterKeys { narrow.contains(it) }
            ddAssertSeries("${task.key} pv", inRange, narrow.pvOf(cut))
            val outside = wide.pvOf(task).filterKeys { !narrow.contains(it) }.values.sum()
            val rebuilt = narrow.pvOf(cut).values.sum() + outside
            assertEquals(task.totals.pvMd, rebuilt, 1e-6, "${task.key} in-range PV + clipped PV = the total")
            assertEquals(task.totals, cut.totals)
        }
    }

    @Test
    fun `fixture - EPICS mode costs equal the worklog sums per author, the outline is the current baseline`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-epics")
        val stored = ddReadStored(connId)
        val level0 = stored.tasks.values.filter { !it.isSubtask }
        val epicTasks = level0.filter { it.epicId != null }.groupBy { it.epicId!! }
        val epicOwnLogs = stored.logs.filter { log -> stored.epics.any { it.issueId == log.issueId } }.map { it.issueId }.toSet()
        val withOwn = stored.epics.firstOrNull { it.issueId in epicOwnLogs && epicTasks.containsKey(it.issueId) }
        val busiest = stored.epics.filter { epicTasks.containsKey(it.issueId) }.maxBy { epicTasks.getValue(it.issueId).size }
        val chosen = listOfNotNull(withOwn, busiest).distinctBy { it.issueId }
        assertTrue(chosen.isNotEmpty())
        val report = client.dive(chosen.joinToString("&", "connectionId=$connId&") { "epicId=${it.key}" })

        assertEquals(DeepDiveMode.EPICS, report.mode)
        val expected = chosen.flatMap { epicTasks.getValue(it.issueId) }
        assertEquals(expected.map { it.key }.toSet(), report.tasks.map { it.key }.toSet())
        assertEquals(chosen.map { it.key }, report.epics.mapNotNull { it.key }.filter { it in chosen.map { c -> c.key } }, "request order")
        val byKey = stored.tasks.values.associate { it.key to it.issueId }
        assertTasks(stored, report, byKey)

        // Σ cost = Σ fact_worklog.md over the tasks, their sub-tasks and the epics themselves, per author, with names.
        val owned = expected.flatMap { stored.logsOf(it.issueId) } + stored.logs.filter { log -> chosen.any { it.issueId == log.issueId } }
        val ownTotal = report.epics.sumOf { it.ownCost?.totalMd ?: 0.0 }
        val ownLogs = stored.logs.filter { log -> chosen.any { it.issueId == log.issueId } }
        assertEquals(ownLogs.map { it.md }.rounded(), ownTotal, 0.005 * chosen.size)
        assertEquals(ownTotal, report.quality.epicOwnCostMd, 0.005 * chosen.size)
        val seriesByAuthor = (report.tasks.flatMap { it.cost } + report.epics.flatMap { it.ownCost?.cost.orEmpty() })
            .groupBy({ it.a?.let { index -> report.authors[index].accountId } }, { it.md })
        val storedByAuthor = owned.groupBy({ it.author }, { it.md })
        assertEquals(storedByAuthor.keys, seriesByAuthor.keys)
        storedByAuthor.forEach { (author, mds) ->
            val entries = seriesByAuthor.getValue(author).size
            val exact = mds.fold(BigDecimal.ZERO, BigDecimal::add).toDouble()
            assertEquals(exact, seriesByAuthor.getValue(author).sum(), 0.005 * entries + DD_EPS, "cost of $author")
        }
        report.authors.forEach { assertEquals(stored.names[it.accountId] ?: it.accountId, it.displayName, "name of ${it.accountId}") }
        assertEquals(report.authors.size, report.authors.map { it.accountId }.distinct().size)

        // The outline is the CURRENT baseline (superseded ones ignored) as offsets from range.from, and no PV sum includes the budget.
        chosen.forEach { epic ->
            val row = report.epics.first { it.key == epic.key }
            val current = stored.plans.filter { it.issueId == epic.issueId && it.superseded == null }.lastOrNull()
            if (current?.start != null && current.due != null) {
                assertEquals(utcDay(current.start), report.day(assertNotNull(row.plannedStart)))
                assertEquals(utcDay(current.due), report.day(assertNotNull(row.plannedDue)))
            } else {
                assertNull(row.plannedStart)
            }
            assertEquals(current?.budget?.setScale(2)?.toDouble(), row.budgetMd)
        }
        val taskPlanTotal = report.tasks.sumOf { it.totals.pvMd }
        assertEquals(report.tasks.sumOf { it.pv.sumOf { day -> day.md } }, taskPlanTotal, 1e-6, "the epic budgets are in no PV sum")
        assertEquals(chosen.size, report.epics.count { it.key != null })
    }

    @Test
    fun `fixture - TASKS mode shows exactly the picked tasks and its range is the narrower envelope`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-tasks")
        val stored = ddReadStored(connId)
        val level0 = stored.tasks.values.filter { !it.isSubtask && it.epicId != null }
        val epic = stored.epics.maxBy { e -> level0.count { it.epicId == e.issueId } }
        val tasks = level0.filter { it.epicId == epic.issueId }
        val picked = tasks.filter { stored.logsOf(it.issueId).isNotEmpty() }.sortedBy { it.key }.take(3)
        assertTrue(picked.size >= 2, "the epic must hold at least two worklogged tasks")
        val tasksReport = client.dive("connectionId=$connId&epicId=${epic.key}&" + picked.joinToString("&") { "issueId=${it.key}" })
        val epicReport = client.dive("connectionId=$connId&epicId=${epic.key}")

        assertEquals(DeepDiveMode.TASKS, tasksReport.mode)
        assertEquals(picked.map { it.key }.toSet(), tasksReport.tasks.map { it.key }.toSet())
        assertEquals(tasks.map { it.key }.toSet(), epicReport.tasks.map { it.key }.toSet())
        val byKey = stored.tasks.values.associate { it.key to it.issueId }
        assertTasks(stored, tasksReport, byKey)
        // The picked tasks' envelope sits inside the whole epic's, and a task's series is the same on the same days in both.
        assertTrue(!LocalDate.parse(tasksReport.range.from).isBefore(LocalDate.parse(epicReport.range.from)))
        assertTrue(!LocalDate.parse(tasksReport.range.to).isAfter(LocalDate.parse(epicReport.range.to)))
        for (task in tasksReport.tasks) {
            val whole = epicReport.tasks.first { it.key == task.key }
            assertEquals(epicReport.pvOf(whole), tasksReport.pvOf(task), "${task.key} pv")
            assertEquals(epicReport.execOf(whole), tasksReport.execOf(task), "${task.key} exec")
            assertEquals(epicReport.costOf(whole), tasksReport.costOf(task), "${task.key} cost")
            assertEquals(whole.totals, task.totals)
        }
        assertEquals(listOf(epic.key), tasksReport.epics.mapNotNull { it.key })
        assertNull(tasksReport.epics.single().ownCost, "epic-logged cost is shown in mode EPICS only")
        assertTrue(tasksReport.sprints.isEmpty())
    }

    @Test
    fun `fixture - the golden sprint answers within the documented size`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-golden")
        val stored = ddReadStored(connId)
        val golden = 3003L
        assertTrue(golden in stored.sprints, "the stub fixture holds the golden sprint")
        val level0 = stored.tasks.filterValues { !it.isSubtask }
        val domain = stored.scope.filter { it.sprintId == golden && it.inScope }.mapNotNull { level0[it.issueId]?.domain }
            .groupingBy { it }.eachCount().maxBy { it.value }.key
        val response = client.get("$DD_BASE?${sprintQuery(connId, domain, listOf(golden))}")
        assertEquals(HttpStatusCode.OK, response.status)
        val raw = response.body<String>()
        val report = kotlinx.serialization.json.Json.decodeFromString<DeepDiveReport>(raw)
        assertTrue(report.tasks.isNotEmpty())
        // One sprint of one domain stays far below the ~400 KB the contract calls typical (the measured size is in reports.md).
        assertTrue(raw.length < 200_000, "response size ${raw.length}")
    }

    // ---- hand-built rows ------------------------------------------------------------------------------

    private fun taskRow(
        issueId: Long,
        key: String,
        domain: String?,
        epicId: Long?,
        doneAt: Long? = null,
        estimateAtDone: Double? = null,
    ) = FactTaskDeliveryRow(
        issueId = issueId, issueKey = key, createdAt = 0, startedAt = null, doneAt = doneAt, reopenCount = 0, estimateAtStartMd = null,
        estimateAtDoneMd = estimateAtDone, estimateCurrentMd = null, estimateSource = "NONE", estimateChangesAfterStart = 0,
        estimatedLate = false, actualMd = 0.0, hasWorklogs = false, blockedMs = 0, blockedWorkingDays = 0.0, cycleMs = null,
        cycleWorkingDays = null, leadMs = null, leadWorkingDays = null, activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = null,
        assigneeTeamIdAtDone = null, sprintIdAtDone = null, sprintTeamIdAtDone = null, creditTeamId = null, currentTeamId = null,
        currentAssigneeAccountId = null, domainKey = domain, epicId = epicId, epicDomainKey = null, crossDomain = false,
        activityType = "Story", workCategory = null, isSubtask = false, parentTaskId = null, currentStage = "NOT_STARTED",
        flags = emptyList(),
    )

    private fun scopeRow(sprintId: Long, issueId: Long, commitment: Double?) = FactSprintScopeRow(
        sprintId,
        SprintScopeItem(
            issueId = issueId, addedAtMs = null, removedAtMs = null, committed = true, inScopeAtClose = true,
            estimateAtCommitmentMd = commitment, estimateAtCloseMd = null, estimateAtDoneMd = null, assigneeAtCommitment = null,
            doneInSprint = false, carriedOver = false, dropped = false,
        ),
    )

    private fun log(id: Long, issueId: Long, author: String?, day: String, md: Double) = FactWorklogRow(
        worklogId = id, issueId = issueId, authorAccountId = author, authorTeamId = null, startedAt = ddNoonZ(day), createdAt = null,
        lateMs = null, md = md, taskDomainKey = "DD1", epicId = null, epicDomainKey = null, activityType = "Story", workCategory = null,
        sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = false, assigneeAccountIdAtStarted = null,
        assigneeTeamIdAtStarted = null,
    )

    private fun sprintRow(id: Long, name: String, start: String?, complete: String?) =
        DimSprintRow(
            id, null, null, name, "closed", start?.let { ddNoonZ(it) }, complete?.let { ddNoonZ(it) },
            complete?.let { ddNoonZ(it) }, null, null,
        )

    private fun plan(issueId: Long, seq: Int, start: String, due: String, budget: Double, superseded: String?) = FactEpicPlanRow(
        issueId, seq,
        EpicPlanBaseline(
            ddNoonZ("2026-01-01"), ddUtcMidnightMs(start), ddUtcMidnightMs(due), budget, "OWN", superseded?.let { ddNoonZ(it) },
        ),
    )

    private suspend fun cleanUp(store: MetricsStore, connId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactWorklog(connId)
            store.deleteFactEpicPlan(connId)
            store.deleteFactTaskDelivery(connId)
            store.deleteSprintFacts(connId)
            store.deleteBridges(connId)
            store.deleteDims(connId)
        }
        deleteDeriveRuns(connId)
    }

    /**
     * One hand-built connection, derived through 2026-02-17 (so an open interval stops there), domain DD1 and the default Warsaw calendar
     * (Mon-Fri). Sprints: S1 Mon Feb 2 - Fri Feb 6, S2 Wed Feb 4 - Wed Feb 11 (overlaps S1), S3 Mon Feb 16 - Fri Feb 20, S4 Sat Feb 7 -
     * Sun Feb 8 (no working day). Epics: E1 (current baseline Feb 2 - Feb 13, budget 20; a superseded one before it) and E2 (no baseline).
     * Tasks: T1 (S1 3.0 then S2 5.0: carry-over, done Feb 6 with 2.5), T2 (S1 no estimate, S3 4.0: the later fallback), T3 (never in a
     * sprint), T4 (S1, no estimate anywhere), T5 (S4 2.0: nowhere to sit), T6 (E2, S3 1.0), T7 (S1 1.0, no epic); sub-task ST1 under T1.
     */
    private suspend fun buildWorld(store: MetricsStore, connId: UInt) {
        insertSucceededDerive(connId, ddNoonZ("2026-02-17"))
        val epics = listOf(
            DimEpicRow(
                501, "DDX-1", "First epic", "DD1", null, "IN_PROGRESS", ddUtcMidnightMs("2026-02-02"), ddUtcMidnightMs("2026-02-13"),
            ),
            DimEpicRow(502, "DDX-2", "Second epic", "DD1", null, "IN_PROGRESS", null, null),
        )
        fun subtask(id: Long, key: String, parent: Long) =
            DimTaskRow(id, key, "Sub-task", "Story", null, "NONE", true, parent, "DD1", 501)
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("DD1", "Deep dive", emptyList(), null)), 1L)
            store.insertEpics(connId, epics, 1L)
            store.insertTasks(connId, listOf(subtask(11, "DDT-11", 1)), 1L)
            store.insertDimSprints(
                connId,
                listOf(
                    sprintRow(1, "S1", "2026-02-02", "2026-02-06"), sprintRow(2, "S2", "2026-02-04", "2026-02-11"),
                    sprintRow(3, "S3", "2026-02-16", "2026-02-20"), sprintRow(4, "S4", "2026-02-07", "2026-02-08"),
                ),
                1L,
            )
            store.insertFactTaskDelivery(
                connId,
                listOf(
                    taskRow(1, "DDT-1", "DD1", 501, doneAt = ddNoonZ("2026-02-06"), estimateAtDone = 2.5),
                    taskRow(2, "DDT-2", "DD1", 501), taskRow(3, "DDT-3", "DD1", 501), taskRow(4, "DDT-4", "DD1", 501),
                    taskRow(5, "DDT-5", "DD1", 501), taskRow(6, "DDT-6", "DD1", 502), taskRow(7, "DDT-7", "DD1", null),
                ),
                1L,
            )
            store.insertFactSprintScope(
                connId,
                listOf(
                    scopeRow(1, 1, 3.0), scopeRow(2, 1, 5.0), scopeRow(1, 2, null), scopeRow(3, 2, 4.0), scopeRow(1, 4, null),
                    scopeRow(4, 5, 2.0), scopeRow(3, 6, 1.0), scopeRow(1, 7, 1.0),
                ),
                1L,
            )
            store.insertItemStage(
                connId,
                listOf(
                    ItemStageRow(1, DD_STAGE, "10", ddNoonZ("2026-02-02"), ddNoonZ("2026-02-03")),
                    ItemStageRow(1, "DONE", "30", ddNoonZ("2026-02-03"), null),
                    ItemStageRow(2, DD_STAGE, "10", ddNoonZ("2026-02-13"), null),
                ),
            )
            store.insertFactWorklog(
                connId,
                listOf(
                    log(1, 1, "acc-a", "2026-02-02", 0.5), log(2, 11, "acc-b", "2026-02-03", 0.25), log(3, 501, "acc-a", "2026-02-04", 1.5),
                    log(4, 2, null, "2026-02-09", 0.75), log(5, 1, "acc-ghost", "2026-02-07", 0.5), log(6, 1, "acc-a", "2026-03-02", 1.0),
                ),
                1L,
            )
            store.insertFactEpicPlan(
                connId,
                listOf(plan(501, 1, "2026-01-26", "2026-02-27", 8.0, "2026-01-15"), plan(501, 2, "2026-02-02", "2026-02-13", 20.0, null)),
                1L,
            )
        }
    }

    private suspend fun assertCalendarAssumption() {
        val settings = DerivedStubFixture.metricsSettings().read()
        assertEquals(listOf(6, 7), settings.weekendDays.sorted(), "the hand-built weeks assume the default Saturday/Sunday weekend")
        assertTrue(settings.holidays.none { it.startsWith("2026-02") || it.startsWith("2026-03-02") }, "and no holiday in February")
        assertEquals("Europe/Warsaw", settings.timeZone)
    }

    @Test
    fun `hand-built rows pin carry-over, the later fallback, an unknown author and the clipped series`() = testApplication {
        usePostgresTestcontainer()
        assertCalendarAssumption()
        val store = MetricsStore(sharedDatabaseForTests())
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-hand-a", enabled = false)
        try {
            buildWorld(store, connId)
            val client = seededClient("reports-dd-hand-a")
            val sprints = client.dive("connectionId=$connId&domain=DD1&sprintId=1&sprintId=2")
            assertEquals(DeepDiveMode.SPRINTS, sprints.mode)
            // T1, T2, T4 and T7 were in scope in S1 or S2; T5 (S4) and T6 (S3 only) were not.
            assertEquals(listOf("DDT-1", "DDT-2", "DDT-4", "DDT-7"), sprints.tasks.map { it.key })
            assertEquals("2026-02-02", sprints.range.from)
            assertEquals("2026-02-11", sprints.range.to)
            assertEquals("2026-02-17", sprints.range.asOfDay)
            assertEquals(listOf(5, 6), sprints.nonWorkingDays, "Sat Feb 7 and Sun Feb 8")
            assertEquals(listOf("S1" to (0 to 4), "S2" to (2 to 9)), sprints.sprints.map { it.name to (it.startDay!! to it.endDay!!) })
            assertNull(sprints.note)

            // T1 carries over S1 -> S2: the EARLIEST sprint's 3.0 over the UNION of both windows - 8 working days, the overlap Feb 4-6
            // counted once - by cumulative rounding ROUND(3 * i / 8, 2): 0.38 0.75 1.13 1.50 1.88 2.25 2.63 3.00.
            val t1 = sprints.tasks.first { it.key == "DDT-1" }
            assertEquals(3.0, t1.planBasisMd)
            assertEquals(DeepDivePlanSource.EARLIEST, t1.planSource)
            assertNull(t1.noPlanReason)
            assertEquals(listOf(0, 1, 2, 3, 4, 7, 8, 9), t1.pv.map { it.d })
            assertEquals(listOf(0.38, 0.37, 0.38, 0.37, 0.38, 0.37, 0.38, 0.37), t1.pv.map { it.md })
            assertEquals(3.0, t1.totals.pvMd, DD_EPS)
            assertEquals("DDX-1", t1.epicKey)
            // Execution Mon Feb 2 13:00 -> Tue Feb 3 13:00 local: 11/24 of Monday and 13/24 of Tuesday, 1 task-day in all.
            assertEquals(listOf(0, 1), t1.exec.map { it.d })
            assertEquals(11.0 / 24.0, t1.exec[0].td, 1e-4)
            assertEquals(13.0 / 24.0, t1.exec[1].td, 1e-4)
            assertEquals(1.0, t1.totals.execTaskDays, 1e-3)
            // The EV marker is estimate_at_done_md on the day of done_at (Fri Feb 6 = offset 4).
            assertEquals(4, assertNotNull(t1.done).d)
            assertEquals(2.5, t1.done.evMd, DD_EPS)
            assertEquals(2.5, t1.totals.evMd, DD_EPS)
            // Cost: acc-a Feb 2 (0.5), acc-b Feb 3 via the SUB-TASK (0.25), the unknown acc-ghost on the non-working Sat Feb 7 (0.5);
            // acc-a's Mar 2 worklog is after the sprints' envelope: out of the series, in the total (2.25).
            assertEquals(
                listOf(Triple(0, "acc-a", 0.5), Triple(1, "acc-b", 0.25), Triple(5, "acc-ghost", 0.5)),
                t1.cost.map { Triple(it.d, sprints.authors[it.a!!].accountId, it.md) },
            )
            assertEquals(2.25, t1.totals.costMd, DD_EPS)
            assertEquals(1.25, t1.cost.sumOf { it.md }, DD_EPS)
            assertEquals(listOf("acc-a", "acc-b", "acc-ghost"), sprints.authors.map { it.accountId })
            val names = sprints.authors.map { it.displayName }
            assertEquals(listOf("acc-a", "acc-b", "acc-ghost"), names, "an unknown author is named by its id")

            // T2: S1 has no estimate, the first LATER sprint with one (S3, 4.0) supplies it, spread over S1 UNION S3 (10 working days).
            val t2 = sprints.tasks.first { it.key == "DDT-2" }
            assertEquals(4.0, t2.planBasisMd)
            assertEquals(DeepDivePlanSource.LATER_FALLBACK, t2.planSource)
            assertEquals(listOf(0, 1, 2, 3, 4), t2.pv.map { it.d }, "only S1's days are inside the range; S3's are clipped")
            assertEquals(4.0, t2.totals.pvMd, DD_EPS)
            assertEquals(2.0, t2.pv.sumOf { it.md }, DD_EPS)
            // Its open interval (Fri Feb 13 13:00 local) is cut at the derive clock (Tue Feb 17 13:00 local): 11/24 + 1 + 13/24 = 2.0.
            assertEquals(2.0, t2.totals.execTaskDays, 1e-3)
            assertTrue(t2.exec.isEmpty(), "the whole interval lies after the range")
            assertNull(t2.done)
            // An author-less worklog is kept, with no author index.
            assertEquals(listOf(7), t2.cost.map { it.d })
            assertNull(t2.cost.single().a)
            assertEquals(0.75, t2.totals.costMd, DD_EPS)

            val t4 = sprints.tasks.first { it.key == "DDT-4" }
            assertEquals(DeepDivePlanSource.NONE, t4.planSource)
            assertEquals(DeepDiveNoPlanReason.NO_ESTIMATE, t4.noPlanReason)
            assertNull(t4.planBasisMd)
            assertTrue(t4.pv.isEmpty())
            // T7 has no epic: the key-less row; E1 carries its CURRENT baseline (Feb 2 - Feb 13, 20.0), never the superseded one.
            assertNull(sprints.tasks.first { it.key == "DDT-7" }.epicKey)
            assertEquals(2, sprints.epics.size)
            val e1 = sprints.epics.first()
            assertEquals("DDX-1", e1.key)
            assertEquals("First epic", e1.summary)
            assertEquals(0, e1.plannedStart)
            assertEquals(11, e1.plannedDue)
            assertEquals(20.0, e1.budgetMd)
            assertNull(e1.ownCost, "the epic's own worklog is mode EPICS only")
            assertNull(sprints.epics.last().key)
            assertEquals(0, sprints.quality.neverInSprint)
            assertEquals(1, sprints.quality.noEstimate)
            assertEquals(1, sprints.quality.laterFallback)
            assertEquals(0.0, sprints.quality.epicOwnCostMd)

            // from/to clip the range; offsets rebase, totals do not move.
            val clipped = client.dive("connectionId=$connId&domain=DD1&sprintId=1&sprintId=2&from=2026-02-04&to=2026-02-06")
            assertEquals("2026-02-04", clipped.range.from)
            val c1 = clipped.tasks.first { it.key == "DDT-1" }
            assertEquals(listOf(0, 1, 2), c1.pv.map { it.d })
            assertEquals(listOf(0.38, 0.37, 0.38), c1.pv.map { it.md })
            assertEquals(t1.totals, c1.totals)
            assertEquals(listOf(2), c1.done?.let { listOf(it.d) })
            assertTrue(c1.cost.isEmpty())
            assertTrue(clipped.nonWorkingDays.isEmpty())
            assertTrue(clipped.authors.isEmpty(), "authors are the ones the sent cost entries name")
        } finally {
            cleanUp(store, connId)
        }
    }

    @Test
    fun `hand-built rows pin the epic modes - own worklog, window-less epic, no-plan reasons and the envelope`() = testApplication {
        usePostgresTestcontainer()
        assertCalendarAssumption()
        val store = MetricsStore(sharedDatabaseForTests())
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-hand-b", enabled = false)
        try {
            buildWorld(store, connId)
            val client = seededClient("reports-dd-hand-b")
            val epics = client.dive("connectionId=$connId&epicId=DDX-1&epicId=DDX-2")
            assertEquals(DeepDiveMode.EPICS, epics.mode)
            assertEquals(listOf("DDT-1", "DDT-2", "DDT-3", "DDT-4", "DDT-5", "DDT-6"), epics.tasks.map { it.key })
            // The envelope of every mark: Feb 2 (plan, worklog, E1's window) to Mar 2 (T1's last worklog).
            assertEquals("2026-02-02", epics.range.from)
            assertEquals("2026-03-02", epics.range.to)
            assertEquals(listOf("DDX-1", "DDX-2"), epics.epics.map { it.key }, "request order, no key-less row without an epic-less task")

            val byKey = epics.tasks.associateBy { it.key }
            assertEquals(DeepDiveNoPlanReason.NEVER_IN_SPRINT, byKey.getValue("DDT-3").noPlanReason)
            assertEquals(DeepDivePlanSource.NONE, byKey.getValue("DDT-3").planSource)
            assertNull(byKey.getValue("DDT-3").planBasisMd)
            // T5: an estimate with no working day keeps its basis and source, with no PV.
            val t5 = byKey.getValue("DDT-5")
            assertEquals(DeepDiveNoPlanReason.NO_WORKING_DAY, t5.noPlanReason)
            assertEquals(DeepDivePlanSource.EARLIEST, t5.planSource)
            assertEquals(2.0, t5.planBasisMd)
            assertTrue(t5.pv.isEmpty())
            assertEquals(0.0, t5.totals.pvMd, DD_EPS)
            // T2's fallback plan reaches Feb 20, its open interval is cut at the derive clock: both inside the range now.
            val t2 = byKey.getValue("DDT-2")
            assertEquals(10, t2.pv.size)
            assertEquals(4.0, t2.pv.sumOf { it.md }, DD_EPS)
            assertEquals(listOf(11, 14, 15), t2.exec.map { it.d })
            assertEquals(11.0 / 24.0, t2.exec[0].td, 1e-4)
            assertEquals(1.0, t2.exec[1].td, 1e-4)
            assertEquals(13.0 / 24.0, t2.exec[2].td, 1e-4)
            // Nothing is clipped: every task's series equals its totals.
            epics.tasks.forEach { task ->
                assertEquals(task.totals.pvMd, task.pv.sumOf { it.md }, 1e-6, "${task.key} pv")
                assertEquals(task.totals.execTaskDays, task.exec.sumOf { it.td }, 1e-3, "${task.key} exec")
                assertEquals(task.totals.costMd, task.cost.sumOf { it.md }, 1e-6, "${task.key} cost")
            }
            // The worklog logged on E1 itself is on its own line (Wed Feb 4 = offset 2, acc-a), never spread over its tasks.
            val e1 = epics.epics.first()
            val own = assertNotNull(e1.ownCost)
            assertEquals(1.5, own.totalMd, DD_EPS)
            assertEquals(listOf(Triple(2, "acc-a", 1.5)), own.cost.map { Triple(it.d, epics.authors[it.a!!].accountId, it.md) })
            val taskCost = epics.tasks.sumOf { it.totals.costMd }
            assertEquals(2.25 + 0.75, taskCost, DD_EPS, "T1 (incl. its sub-task) and T2; the epic's 1.5 is in no task")
            assertNull(epics.epics.last().ownCost)
            // E2 has no baseline: no outline, counted; E1's outline is the current baseline.
            assertNull(epics.epics.last().plannedStart)
            assertNull(epics.epics.last().budgetMd)
            assertEquals(0, e1.plannedStart)
            assertEquals(11, e1.plannedDue)
            assertEquals(1, epics.quality.epicsWithoutWindow)
            assertEquals(1, epics.quality.neverInSprint)
            assertEquals(1, epics.quality.noEstimate)
            assertEquals(1, epics.quality.noWorkingDay)
            assertEquals(1, epics.quality.laterFallback)
            assertEquals(1.5, epics.quality.epicOwnCostMd, DD_EPS)
            assertEquals(listOf("acc-a", "acc-b", "acc-ghost"), epics.authors.map { it.accountId })

            // Handpicked tasks of one epic: the envelope narrows to T2's marks and E1's window (Feb 2 - Feb 20).
            val picked = client.dive("connectionId=$connId&epicId=DDX-1&issueId=DDT-2")
            assertEquals(DeepDiveMode.TASKS, picked.mode)
            assertEquals(listOf("DDT-2"), picked.tasks.map { it.key })
            assertEquals("2026-02-02", picked.range.from)
            assertEquals("2026-02-20", picked.range.to)
            assertEquals(listOf("DDX-1"), picked.epics.map { it.key })
            assertNull(picked.epics.single().ownCost)
            assertEquals(0.0, picked.quality.epicOwnCostMd)
            assertEquals(t2.totals, picked.tasks.single().totals)
            // ST1 (acc-b, 0.25) hangs under T1, which is not picked: its worklog is in no row, no total and no author list.
            assertEquals(0.75, picked.tasks.single().totals.costMd, DD_EPS)
            assertTrue(picked.authors.isEmpty(), "the unselected parent's sub-task names no author")
        } finally {
            cleanUp(store, connId)
        }
    }

    @Test
    fun `hand-built rows - over 500 tasks, a given range over 1100 days and the control cases are 400`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-hand-c", enabled = false)
        val tag = SyncedStubFixture.unique("ddc").uppercase()
        try {
            insertSucceededDerive(connId, ddNoonZ("2026-02-17"))
            val epics = listOf(
                DimEpicRow(701, "$tag-1", "Many", "DD1", null, "IN_PROGRESS", null, null),
                DimEpicRow(703, "$tag-3", "Exactly five hundred", "DD1", null, "IN_PROGRESS", null, null),
            )
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertDomains(connId, listOf(DimDomainRow("DD1", "Deep dive", emptyList(), null)), 1L)
                store.insertEpics(connId, epics, 1L)
                store.insertFactTaskDelivery(connId, (1..501).map { taskRow(10_000L + it, "$tag-T$it", "DD1", 701) }, 1L)
                store.insertFactTaskDelivery(connId, (1..500).map { taskRow(20_000L + it, "$tag-U$it", "DD1", 703) }, 1L)
                // Sprint 1 holds all 501 of the first epic's tasks at close: the sprint selection's own 500-task cap.
                store.insertDimSprints(connId, listOf(sprintRow(1, "S1", "2026-02-02", "2026-02-06")), 1L)
                store.insertFactSprintScope(connId, (1..501).map { scopeRow(1, 10_000L + it, 1.0) }, 1L)
            }
            val client = seededClient("reports-dd-hand-c")
            val scope = "connectionId=$connId"
            client.assertBadRequest("$scope&epicId=$tag-1")
            client.assertBadRequest("$scope&domain=DD1&sprintId=1")
            val exactly = client.dive("$scope&epicId=$tag-3")
            assertEquals(500, exactly.tasks.size, "exactly 500 tasks is allowed")
            // 501 handpicked issue keys of one epic: too many values.
            client.assertBadRequest("$scope&epicId=$tag-1&" + (1..501).joinToString("&") { "issueId=$tag-T$it" })

            // A GIVEN range over 1100 days (1100 allowed, 1101 not) and a reversed one; an implied one is clamped, not refused.
            client.assertBadRequest("$scope&epicId=$tag-3&from=2020-01-01&to=2023-12-31")
            client.dive("$scope&epicId=$tag-3&from=2020-01-01&to=2023-01-04")
            client.assertBadRequest("$scope&epicId=$tag-3&from=2020-01-01&to=2023-01-05")
            client.assertBadRequest("$scope&epicId=$tag-3&from=2026-02-02&to=2026-02-01")

            // Mode mixing and counts.
            client.assertBadRequest(scope)
            client.assertBadRequest("$scope&sprintId=1&epicId=$tag-3")
            client.assertBadRequest("$scope&domain=DD1")
            client.assertBadRequest("$scope&domain=DD1&epicId=$tag-3")
            client.assertBadRequest("$scope&sprintId=1")
            client.assertBadRequest("$scope&issueId=$tag-T1")
            client.assertBadRequest("$scope&epicId=$tag-1&epicId=$tag-3&issueId=$tag-T1")
            client.assertBadRequest("$scope&domain=DD1&" + (1..53).joinToString("&") { "sprintId=$it" })
            client.assertBadRequest("$scope&" + (1..51).joinToString("&") { "epicId=E$it" })
            // Unknown things (sprint 1 exists, so NOPE is the unknown domain), malformed values, repeated scalars, control characters.
            client.assertBadRequest("$scope&domain=NOPE&sprintId=1")
            client.assertBadRequest("$scope&domain=DD1&sprintId=2")
            client.assertBadRequest("$scope&epicId=NOPE-1")
            client.assertBadRequest("$scope&epicId=$tag-3&issueId=NOPE-9")
            client.assertBadRequest("$scope&epicId=$tag-3&issueId=$tag-T1")
            client.assertBadRequest("$scope&epicId=$tag-3&from=2026-13-01")
            client.assertBadRequest("$scope&epicId=$tag-3&from=2026-02-01&from=2026-02-02")
            client.assertBadRequest("$scope&epicId=$tag-3&connectionId=$connId")
            client.assertBadRequest("connectionId=999999&epicId=$tag-3")
            client.assertBadRequest("$scope&domain=DD1&sprintId=0")
            client.assertBadRequest("$scope&domain=DD1&sprintId=abc")
            client.assertBadRequest("$scope&domain=DD%00&sprintId=1")
            client.assertBadRequest("$scope&epicId=A%00B")
            client.assertBadRequest("$scope&epicId=$tag-3&issueId=A%00B")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteSprintFacts(connId)
                store.deleteDims(connId)
            }
            deleteDeriveRuns(connId)
        }
    }

    /**
     * On the shared hand-built world (EPICS DDX-1 + DDX-2 span Feb 2 - Mar 2 2026): a lone `from`/`to` CLIPS, so one outside the envelope
     * gives a single empty day with the totals intact; a range that is not fully given and would exceed 1100 days keeps its LAST 1100 days
     * (RANGE_CLAMPED) while the totals stay whole-life, with no per-day work over the years; no marks at all is today.
     */
    @Test
    fun `hand-built rows - one-sided from and to clip, an implied range over 1100 days is clamped, no marks is today`() = testApplication {
        usePostgresTestcontainer()
        assertCalendarAssumption()
        val store = MetricsStore(sharedDatabaseForTests())
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-hand-f", enabled = false)
        try {
            buildWorld(store, connId)
            val longAgo = ddNoonZ("2020-03-02")
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertEpics(
                    connId,
                    listOf(
                        DimEpicRow(503, "DDX-3", "Years long", "DD1", null, "IN_PROGRESS", null, null),
                        DimEpicRow(504, "DDX-4", "Open for years", "DD1", null, "IN_PROGRESS", null, null),
                        DimEpicRow(505, "DDX-5", "Nothing at all", "DD1", null, "IN_PROGRESS", null, null),
                    ),
                    1L,
                )
                store.insertFactEpicPlan(connId, listOf(plan(503, 1, "2020-01-01", "2026-01-01", 5.0, null)), 1L)
                store.insertFactTaskDelivery(connId, listOf(taskRow(804, "DDT-804", "DD1", 504)), 1L)
                store.insertItemStage(connId, listOf(ItemStageRow(804, DD_STAGE, "10", longAgo, null)))
            }
            val client = seededClient("reports-dd-hand-f")
            val both = "connectionId=$connId&epicId=DDX-1&epicId=DDX-2"

            // A lone bound outside the envelope: one empty day, the totals intact, never a 400.
            val after = client.dive("$both&from=2026-04-01")
            assertEquals("2026-04-01" to "2026-04-01", after.range.from to after.range.to)
            assertNull(after.note)
            assertEquals("2026-02-17", after.range.asOfDay)
            assertEquals(6, after.tasks.size)
            assertTrue(after.tasks.all { it.pv.isEmpty() && it.exec.isEmpty() && it.cost.isEmpty() && it.done == null })
            assertEquals(2.25, after.tasks.first { it.key == "DDT-1" }.totals.costMd, DD_EPS)
            assertEquals(4.0, after.tasks.first { it.key == "DDT-2" }.totals.pvMd, DD_EPS)
            assertEquals(2.0, after.tasks.first { it.key == "DDT-2" }.totals.execTaskDays, 1e-3)
            val before = client.dive("$both&to=2026-01-01")
            assertEquals("2026-01-01" to "2026-01-01", before.range.from to before.range.to)
            assertTrue(before.tasks.all { it.pv.isEmpty() && it.exec.isEmpty() && it.cost.isEmpty() })
            assertEquals(before.tasks.map { it.totals }, after.tasks.map { it.totals })
            // A lone bound inside the envelope keeps the other implied end.
            assertEquals("2026-02-10" to "2026-03-02", client.dive("$both&from=2026-02-10").range.let { it.from to it.to })
            val upTo = client.dive("$both&to=2026-02-10")
            assertEquals("2026-02-02" to "2026-02-10", upTo.range.from to upTo.range.to)
            assertEquals(listOf(0, 1, 2, 3, 4, 7, 8), upTo.tasks.first { it.key == "DDT-1" }.pv.map { it.d })

            // An epic window of six years: implied, so clamped to its last 1100 days; the outline keeps its true offsets.
            val clamped = client.dive("connectionId=$connId&epicId=DDX-3")
            assertTrue(assertNotNull(clamped.note).startsWith("RANGE_CLAMPED"), clamped.note)
            assertEquals("2026-01-01", clamped.range.to)
            assertEquals(LocalDate.parse("2026-01-01").minusDays(1099).toString(), clamped.range.from)
            val epic = clamped.epics.single()
            assertEquals(1099, epic.plannedDue)
            val startOffset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(clamped.range.from), LocalDate.parse("2020-01-01"))
            assertEquals(startOffset.toInt(), epic.plannedStart)
            assertEquals(5.0, epic.budgetMd)
            // Half-given: the given bound is honoured and the other end follows it.
            val fromGiven = client.dive("connectionId=$connId&epicId=DDX-3&from=2021-01-01")
            val fromEnd = LocalDate.parse("2021-01-01").plusDays(1099).toString()
            assertEquals("2021-01-01" to fromEnd, fromGiven.range.from to fromGiven.range.to)
            assertTrue(assertNotNull(fromGiven.note).startsWith("RANGE_CLAMPED"))
            // A lone `to` over a long implied start: from = envelope start (2020-01-01), to = 2025-06-01: 2000+ days, clamped to 1100.
            val toGiven = client.dive("connectionId=$connId&epicId=DDX-3&to=2025-06-01")
            assertEquals(LocalDate.parse("2025-06-01").minusDays(1099).toString() to "2025-06-01", toGiven.range.from to toGiven.range.to)
            assertTrue(assertNotNull(toGiven.note).startsWith("RANGE_CLAMPED"))
            client.assertBadRequest("connectionId=$connId&epicId=DDX-3&from=2020-01-01&to=2024-01-01")

            // A task IN_PROGRESS since 2020: the range is its last 1100 days, the totals are whole-life, the series only the range.
            val calendar = WorkingCalendar.of(DerivedStubFixture.metricsSettings().read())
            val clockMs = ddNoonZ("2026-02-17")
            val years = client.dive("connectionId=$connId&epicId=DDX-4")
            assertTrue(assertNotNull(years.note).startsWith("RANGE_CLAMPED"))
            assertEquals("2026-02-17", years.range.to)
            assertEquals(LocalDate.parse("2026-02-17").minusDays(1099).toString(), years.range.from)
            val task = years.tasks.single()
            assertEquals(calendar.workingDaysBetween(longAgo, clockMs), task.totals.execTaskDays, 1e-3)
            val rangeStartMs = calendar.dayBoundsMs(LocalDate.parse(years.range.from)).first
            assertEquals(calendar.workingDaysBetween(rangeStartMs, clockMs), task.exec.sumOf { it.td }, 1e-2)
            assertTrue(task.exec.sumOf { it.td } < task.totals.execTaskDays)
            assertTrue(task.exec.all { it.d in 0 until 1100 })
            assertEquals(DeepDiveNoPlanReason.NEVER_IN_SPRINT, task.noPlanReason)
            // The same task with the range given is not clamped and keeps the same whole-life total.
            val given = client.dive("connectionId=$connId&epicId=DDX-4&from=2026-02-01&to=2026-02-17")
            assertNull(given.note)
            assertEquals(task.totals, given.tasks.single().totals)

            // No marks on a derived connection: the single day today.
            val zone = reportZone()
            val nobody = client.dive("connectionId=$connId&epicId=DDX-5")
            val today = listOf(LocalDate.now(zone), LocalDate.now(zone).minusDays(1)).map { it.toString() }
            assertTrue(nobody.range.from in today && nobody.range.to == nobody.range.from, "no marks -> ${nobody.range}")
            assertTrue(nobody.tasks.isEmpty())
            assertEquals(listOf("DDX-5"), nobody.epics.map { it.key })
            assertNull(nobody.note)
        } finally {
            cleanUp(store, connId)
        }
    }

    @Test
    fun `hand-built rows - the same issue in two connections keeps each connection's own figures`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val connA = SyncedStubFixture.createConnection(namePrefix = "dd-hand-g1", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "dd-hand-g2", enabled = false)
        val tag = SyncedStubFixture.unique("ddg").uppercase()
        try {
            for (conn in listOf(connA, connB)) insertSucceededDerive(conn, ddNoonZ("2026-02-17"))
            suspendTransaction(sharedDatabaseForTests()) {
                for (conn in listOf(connA, connB)) {
                    store.insertDomains(conn, listOf(DimDomainRow("DD1", "Deep dive", emptyList(), null)), 1L)
                    store.insertEpics(conn, listOf(DimEpicRow(501, "$tag-E", "Same epic", "DD1", null, "IN_PROGRESS", null, null)), 1L)
                    store.insertDimSprints(conn, listOf(sprintRow(1, "S1", "2026-02-02", "2026-02-06")), 1L)
                }
                // The SAME issue id, key, sprint id and worklog id in both connections, with different figures in each.
                store.insertTasks(connB, listOf(DimTaskRow(11, "$tag-T11", "Sub-task", "Story", null, "NONE", true, 1, "DD1", 501)), 1L)
                store.insertFactTaskDelivery(
                    connA, listOf(taskRow(1, "$tag-T1", "DD1", 501, doneAt = ddNoonZ("2026-02-06"), estimateAtDone = 2.0)), 1L,
                )
                store.insertFactTaskDelivery(connB, listOf(taskRow(1, "$tag-T1", "DD1", 501)), 1L)
                store.insertFactSprintScope(connA, listOf(scopeRow(1, 1, 3.0)), 1L)
                store.insertFactSprintScope(connB, listOf(scopeRow(1, 1, 6.0)), 1L)
                store.insertItemStage(connB, listOf(ItemStageRow(1, DD_STAGE, "10", ddNoonZ("2026-02-02"), ddNoonZ("2026-02-03"))))
                store.insertFactWorklog(connA, listOf(log(1, 1, "acc-a", "2026-02-02", 0.5)), 1L)
                store.insertFactWorklog(connB, listOf(log(1, 1, "acc-b", "2026-02-03", 1.0), log(2, 11, "acc-b", "2026-02-03", 0.25)), 1L)
            }
            val client = seededClient("reports-dd-hand-g")
            for (mode in listOf("epicId=$tag-E", "domain=DD1&sprintId=1")) {
                val a = client.dive("connectionId=$connA&$mode").tasks.single()
                assertEquals(3.0, a.totals.pvMd, DD_EPS, mode)
                assertEquals(0.0, a.totals.execTaskDays, DD_EPS, mode)
                assertEquals(0.5, a.totals.costMd, DD_EPS, mode)
                assertEquals(2.0, a.totals.evMd, DD_EPS, mode)
                val b = client.dive("connectionId=$connB&$mode").tasks.single()
                assertEquals(6.0, b.totals.pvMd, DD_EPS, mode)
                assertEquals(1.0, b.totals.execTaskDays, 1e-3, mode)
                assertEquals(1.25, b.totals.costMd, DD_EPS, mode)
                assertEquals(0.0, b.totals.evMd, DD_EPS, mode)
                assertNull(b.done)
            }
            val reportA = client.dive("connectionId=$connA&epicId=$tag-E")
            assertEquals(listOf("acc-a"), reportA.authors.map { it.accountId })
            val reportB = client.dive("connectionId=$connB&epicId=$tag-E")
            assertEquals(listOf("acc-b"), reportB.authors.map { it.accountId })
            assertEquals(listOf(0.25 + 1.0), listOf(reportB.tasks.single().cost.sumOf { it.md }))
        } finally {
            for (conn in listOf(connA, connB)) cleanUp(store, conn)
        }
    }

    @Test
    fun `hand-built rows - unknown sprint or domain and keys ambiguous across connections are 400`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val connA = SyncedStubFixture.createConnection(namePrefix = "dd-hand-d1", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "dd-hand-d2", enabled = false)
        val tag = SyncedStubFixture.unique("ddd").uppercase()
        try {
            insertSucceededDerive(connA, ddNoonZ("2026-02-17"))
            insertSucceededDerive(connB, ddNoonZ("2026-02-17"))
            suspendTransaction(sharedDatabaseForTests()) {
                for (conn in listOf(connA, connB)) {
                    store.insertDomains(conn, listOf(DimDomainRow("DD1", "Deep dive", emptyList(), null)), 1L)
                    store.insertEpics(conn, listOf(DimEpicRow(1, "$tag-1", "Same key", "DD1", null, "IN_PROGRESS", null, null)), 1L)
                }
                store.insertEpics(connB, listOf(DimEpicRow(2, "$tag-2", "Only in B", "DD1", null, "IN_PROGRESS", null, null)), 1L)
                store.insertDimSprints(connA, listOf(sprintRow(9, "Nine", "2026-02-02", "2026-02-06")), 1L)
                store.insertDimSprints(connB, listOf(sprintRow(9, "Nine", "2026-02-02", "2026-02-06")), 1L)
            }
            val client = seededClient("reports-dd-hand-d")
            // A key (or a sprint id) present in several connections in scope is ambiguous; a connectionId resolves it.
            client.assertBadRequest("epicId=$tag-1")
            client.assertBadRequest("domain=DD1&sprintId=9")
            assertEquals(listOf("$tag-1"), client.dive("connectionId=$connA&epicId=$tag-1").epics.map { it.key })
            assertEquals(DeepDiveMode.SPRINTS, client.dive("connectionId=$connB&domain=DD1&sprintId=9").mode)
            // The unambiguous key of a connection the narrowing excludes is unknown there.
            client.assertBadRequest("connectionId=$connA&epicId=$tag-2")
            assertEquals(listOf("$tag-2"), client.dive("connectionId=$connB&epicId=$tag-2").epics.map { it.key })
            // An unknown sprint and an unknown domain on a derived connection.
            client.assertBadRequest("connectionId=$connA&domain=DD1&sprintId=10")
            client.assertBadRequest("connectionId=$connA&domain=OTHER&sprintId=9")
            // A selection with a sprint known but no task of the domain is an empty valid answer, not a 400.
            val empty = client.dive("connectionId=$connA&domain=DD1&sprintId=9")
            assertTrue(empty.tasks.isEmpty() && empty.epics.isEmpty() && empty.authors.isEmpty())
            assertEquals("2026-02-02", empty.range.from)
            assertEquals("2026-02-06", empty.range.to)
        } finally {
            for (conn in listOf(connA, connB)) {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteSprintFacts(conn)
                    store.deleteDims(conn)
                }
            }
            deleteDeriveRuns(connA)
            deleteDeriveRuns(connB)
        }
    }

    @Test
    fun `hand-built rows - a connection that never derived answers empty with the note, never a range of zeros`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-hand-e", enabled = false)
        val client = seededClient("reports-dd-hand-e")
        // Nothing validates against dimensions that do not exist yet: unknown keys and sprints are the empty answer too.
        val sprints = client.dive("connectionId=$connId&domain=NOPE&sprintId=77")
        assertEquals(DeepDiveMode.SPRINTS, sprints.mode)
        assertTrue(sprints.tasks.isEmpty() && sprints.epics.isEmpty() && sprints.authors.isEmpty() && sprints.sprints.isEmpty())
        assertTrue(sprints.nonWorkingDays.isEmpty())
        assertNull(sprints.range.asOfDay)
        assertNull(sprints.meta.derivedAt)
        assertNull(sprints.meta.configRevision)
        assertEquals(ch.nokillswit.reports.NOT_DERIVED_NOTE, sprints.note)
        assertEquals(0, sprints.quality.neverInSprint + sprints.quality.noEstimate + sprints.quality.noWorkingDay)
        val epics = client.dive("connectionId=$connId&epicId=NOPE-1&from=2026-02-02&to=2026-02-08")
        assertEquals(DeepDiveMode.EPICS, epics.mode)
        assertEquals("2026-02-02", epics.range.from)
        assertEquals("2026-02-08", epics.range.to)
        assertTrue(epics.tasks.isEmpty())
        assertTrue(epics.note!!.startsWith("Not derived yet"))
        assertEquals(DeepDiveMode.TASKS, client.dive("connectionId=$connId&epicId=NOPE-1&issueId=NOPE-2").mode)
        // The shape checks still come first, and an unknown connection is still a 400.
        client.assertBadRequest("connectionId=$connId")
        client.assertBadRequest("connectionId=$connId&epicId=NOPE-1&from=2020-01-01&to=2024-12-31")
        client.assertBadRequest("connectionId=999999&epicId=NOPE-1")
    }
}
