package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.WorkItemStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.select

// Report 17, the Deep dive (`.claude/docs/reports.md` "Report 17", A29, A40): plan (PV), Approximate EV and cost (AC) as SPARSE DAILY
// series per task, in man-days, for the tasks a selection resolves to (the wire shapes: `DeepDiveWire.kt`). The kernels
// (`DeepDiveKernels.kt`) compute every series from a fixed provisional origin; the range is only known once every mark has been seen, so
// they are shifted onto `range.from` at the end. Approximate EV is a split of the EV over the task's WHOLE-LIFE in-progress working days
// (`inProgressDays`, a per-day walk: the days are the output), done before the range exists: the range only CLIPS what is drawn, so a
// clipped range shows each day's whole-life share. The envelope still reads a span by its first and last day. Reads one query per fact
// table for the whole task set (no per-task reads); the client sums days into weeks, months and epic rows.

/** Every series is computed from this origin and shifted onto `range.from` once the range is known. */
private val PROVISIONAL_ORIGIN: LocalDate = LocalDate.EPOCH

/** `note` of an answer whose IMPLIED range was cut to its last 1100 days; the leading code is stable for clients. */
internal const val DEEP_DIVE_RANGE_CLAMPED_NOTE =
    "RANGE_CLAMPED: the selection spans more than 1100 days, so only 1100 days are shown (give from and to to choose them); " +
        "totals are whole-life"

private const val STAGE_IN_PROGRESS = "IN_PROGRESS" // Approximate EV: IN_PROGRESS only (A40); WAITING (A30) and BLOCKED (A36) are not
private const val ID_CHUNK = 10_000

private class TaskFact(val issueId: Long, val key: String, val epicIssueId: Long?, val doneAt: Long?, val evMd: BigDecimal?)

private class DiveSprint(val sprintId: Long, val name: String, val startAt: Long?, val endAt: Long?, val completeAt: Long?)

private class ScopeFact(val sprintId: Long, val estimateMd: BigDecimal?)

private class EpicLabel(val key: String, val summary: String?)

private class EpicWindow(val startAtMs: Long?, val dueAtMs: Long?, val budgetMd: BigDecimal?)

/** Everything one selection reads, one query per fact table. */
private class DeepDiveFacts(
    val tasks: List<TaskFact>,
    val summaries: Map<Long, String?>,
    val epicLabels: Map<Long, EpicLabel>,
    val scopeByTask: Map<Long, List<ScopeFact>>,
    val sprints: Map<Long, DiveSprint>,
    val spansByTask: Map<Long, List<StageSpan>>,
    val costByTask: Map<Long, List<CostEntry>>,
    val ownCostByEpic: Map<Long, List<CostEntry>>,
    val windows: Map<Long, EpicWindow>,
)

/** One task's series at the provisional origin, before the range is known. */
private class TaskSeries(
    val fact: TaskFact,
    val epicKey: String?,
    val plan: TaskPlan,
    /** The IN_PROGRESS spans with an open one already cut at the DERIVE clock (empty spans dropped). */
    val spans: List<StageSpan>,
    /** The working days the task spent IN_PROGRESS over its whole life ([inProgressDays]), ascending: what Approximate EV spreads over. */
    val workedDays: List<LocalDate>,
    /** The task's EV spread over [workedDays] (A40), whole-life; empty unless [evState] is EARNED. */
    val approxEv: List<DayAmount>,
    val evState: EvState,
    val done: DayAmount?,
    val cost: List<AuthorDayCost>,
)

/** Every selected task's series, plus the epics' own cost (mode EPICS) by epic issue id, all at the provisional origin. */
private class DeepDiveSeries(val tasks: List<TaskSeries>, val epicOwn: Map<Long, List<AuthorDayCost>>)

/**
 * `GET /api/v1/reports/deep-dive`: the report for [request]'s selection. Nothing derived in scope yet is the empty answer with
 * [NOT_DERIVED_NOTE] (never a validation `400` against dimensions that do not exist yet).
 */
internal suspend fun ReportService.deepDive(request: DeepDiveRequest, nowMs: Long): DeepDiveReport = reportTransaction {
    val (settings, calendar) = metricsSettings.readWithCalendar()
    val connectionIds = resolveConnectionScope(request.connectionId)
    val stamp = deriveStamp(connectionIds)
    val clocks = deriveClocks(connectionIds)
    fun metaOf(range: DeepDiveRange) = deepDiveMeta(stamp, range, settings.minSampleSize, request.historyHorizon)
    if (clocks.isEmpty()) return@reportTransaction notDerived(request, calendar, nowMs, ::metaOf)
    val targets = resolveDeepDiveTargets(request, connectionIds, calendar)
    // The selected connection's own DERIVE clock: where an open in-progress interval stops and the day `range.asOfDay` names. A
    // connection with dimension rows but no successful run reads as not derived, never as "now".
    val clockMs = clocks[targets.connectionId] ?: return@reportTransaction notDerived(request, calendar, nowMs, ::metaOf)
    val facts = readFacts(targets)
    val series = seriesOf(facts, calendar, clockMs)
    val choice = resolveRange(request, targets, facts, series, calendar, nowMs)
    val assembly = Assembly(targets, facts, series, choice.range, calendar, clockMs)
    assemble(assembly, choice.note, ::metaOf)
}

/** The empty answer for a scope with no successful DERIVE: the range echoes `from`/`to` (a single day without them). */
private fun notDerived(
    request: DeepDiveRequest,
    calendar: WorkingCalendar,
    nowMs: Long,
    metaOf: (DeepDiveRange) -> ReportMeta,
): DeepDiveReport {
    val from = request.from ?: request.to ?: calendar.dayOf(nowMs)
    val range = DeepDiveRange(from.toString(), (request.to ?: from).toString(), null)
    return DeepDiveReport(
        meta = metaOf(range), mode = request.selection.mode.toWire(), range = range, nonWorkingDays = emptyList(),
        sprints = emptyList(), authors = emptyList(), epics = emptyList(), tasks = emptyList(),
        quality = DeepDiveQuality(
            neverInSprint = 0, noEstimate = 0, noWorkingDay = 0, epicsWithoutWindow = 0, laterFallback = 0,
            openInProgress = 0, doneUnestimated = 0, epicOwnCostMd = 0.0,
        ),
        note = NOT_DERIVED_NOTE,
    )
}

/** The resolved range: [from]..[to] inclusive, [shift] the provisional offset of [from]. */
private class ResolvedRange(val from: LocalDate, val to: LocalDate) {
    val shift: Int = dayOffset(PROVISIONAL_ORIGIN, from)
    val days: Int = (ChronoUnit.DAYS.between(from, to) + 1).toInt()

    /** The offset from [from] of a provisional [offset]. */
    fun rebase(offset: Int): Int = offset - shift

    fun contains(rebased: Int): Boolean = rebased in 0 until days
}

private class Assembly(
    val targets: DeepDiveTargets,
    val facts: DeepDiveFacts,
    val series: DeepDiveSeries,
    val range: ResolvedRange,
    val calendar: WorkingCalendar,
    /** The selected connection's DERIVE clock. */
    val clockMs: Long,
)

// ---- reads --------------------------------------------------------------------------------------------

private suspend fun readFacts(targets: DeepDiveTargets): DeepDiveFacts {
    val connectionId = targets.connectionId
    val taskIds = targets.taskIds
    val tasks = readTasks(connectionId, taskIds)
    val summaries = readSummaries(connectionId, taskIds)
    val epicIds = (tasks.mapNotNull { it.epicIssueId } + targets.epics.map { it.issueId }).distinct()
    val scopeByTask = readScope(connectionId, taskIds)
    val selectedSprintIds = (targets.selection as? DeepDiveSelection.Sprints)?.sprintIds.orEmpty()
    val epicOwn = if (targets.selection is DeepDiveSelection.Epics) targets.epics.map { it.issueId } else emptyList()
    val (costByTask, ownCostByEpic) = readWorklogs(connectionId, taskIds, epicOwn)
    return DeepDiveFacts(
        tasks = tasks,
        summaries = summaries,
        epicLabels = readEpicLabels(connectionId, epicIds),
        scopeByTask = scopeByTask,
        sprints = readSprints(connectionId, scopeByTask.values.flatten().map { it.sprintId } + selectedSprintIds),
        spansByTask = readSpans(connectionId, taskIds),
        costByTask = costByTask,
        ownCostByEpic = ownCostByEpic,
        windows = readEpicWindows(connectionId, epicIds),
    )
}

private suspend fun readTasks(connectionId: UInt, taskIds: List<Long>): List<TaskFact> {
    if (taskIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    return t.select(t.issueId, t.issueKey, t.epicId, t.doneAt, t.estimateAtDoneMd)
        .where { (t.connectionId eq connectionId) and (t.issueId inList taskIds) }
        .toList().map { TaskFact(it[t.issueId], it[t.issueKey], it[t.epicId], it[t.doneAt], it[t.estimateAtDoneMd]) }
}

private suspend fun readSummaries(connectionId: UInt, taskIds: List<Long>): Map<Long, String?> {
    if (taskIds.isEmpty()) return emptyMap()
    val w = WorkItemStore.WorkItems
    return w.select(w.issueId, w.summary)
        .where { (w.connectionId eq connectionId) and (w.issueId inList taskIds) }
        .toList().associate { it[w.issueId] to it[w.summary] }
}

private suspend fun readEpicLabels(connectionId: UInt, epicIds: List<Long>): Map<Long, EpicLabel> {
    if (epicIds.isEmpty()) return emptyMap()
    val e = MetricsTables.DimEpic
    return e.select(e.issueId, e.issueKey, e.summary)
        .where { (e.connectionId eq connectionId) and (e.issueId inList epicIds) }
        .toList().associate { it[e.issueId] to EpicLabel(it[e.issueKey], it[e.summary]) }
}

private suspend fun readScope(connectionId: UInt, taskIds: List<Long>): Map<Long, List<ScopeFact>> {
    if (taskIds.isEmpty()) return emptyMap()
    val s = MetricsTables.FactSprintScope
    return s.select(s.issueId, s.sprintId, s.estimateAtCommitmentMd)
        .where { (s.connectionId eq connectionId) and (s.issueId inList taskIds) and (s.inScopeAtClose eq true) }
        .toList().groupBy({ it[s.issueId] }, { ScopeFact(it[s.sprintId], it[s.estimateAtCommitmentMd]) })
}

private suspend fun readSprints(connectionId: UInt, sprintIds: List<Long>): Map<Long, DiveSprint> {
    val ids = sprintIds.distinct()
    if (ids.isEmpty()) return emptyMap()
    val s = MetricsTables.DimSprint
    return s.select(s.sprintId, s.name, s.startAt, s.endAt, s.completeAt)
        .where { (s.connectionId eq connectionId) and (s.sprintId inList ids) }
        .toList().associate { it[s.sprintId] to DiveSprint(it[s.sprintId], it[s.name], it[s.startAt], it[s.endAt], it[s.completeAt]) }
}

private suspend fun readSpans(connectionId: UInt, taskIds: List<Long>): Map<Long, List<StageSpan>> {
    if (taskIds.isEmpty()) return emptyMap()
    val s = MetricsTables.ItemStage
    return s.select(s.issueId, s.validFrom, s.validTo)
        .where { (s.connectionId eq connectionId) and (s.issueId inList taskIds) and (s.stage eq STAGE_IN_PROGRESS) }
        .toList().groupBy({ it[s.issueId] }, { StageSpan(it[s.validFrom], it[s.validTo]) })
}

private suspend fun readEpicWindows(connectionId: UInt, epicIds: List<Long>): Map<Long, EpicWindow> {
    if (epicIds.isEmpty()) return emptyMap()
    val p = MetricsTables.FactEpicPlan
    return p.select(p.issueId, p.startAt, p.dueAt, p.budgetMd, p.baselineSeq)
        .where { (p.connectionId eq connectionId) and (p.issueId inList epicIds) and p.supersededAt.isNull() }
        .toList().sortedBy { it[p.baselineSeq] }
        .associate { it[p.issueId] to EpicWindow(it[p.startAt], it[p.dueAt], it[p.budgetMd]) }
}

/**
 * The worklogs of [taskIds] with each sub-task's rolled up to its parent through `dim_task.parent_task_id` (D2, invariant 7), and the
 * ones logged on the [epicIds] themselves, keyed by epic.
 */
private suspend fun readWorklogs(
    connectionId: UInt,
    taskIds: List<Long>,
    epicIds: List<Long>,
): Pair<Map<Long, List<CostEntry>>, Map<Long, List<CostEntry>>> {
    if (taskIds.isEmpty() && epicIds.isEmpty()) return emptyMap<Long, List<CostEntry>>() to emptyMap()
    val parentOf = mutableMapOf<Long, Long>()
    if (taskIds.isNotEmpty()) {
        val d = MetricsTables.DimTask
        d.select(d.issueId, d.parentTaskId)
            .where { (d.connectionId eq connectionId) and (d.isSubtask eq true) and (d.parentTaskId inList taskIds) }
            .toList().forEach { row -> row[d.parentTaskId]?.let { parentOf[row[d.issueId]] = it } }
    }
    val w = MetricsTables.FactWorklog
    val ids = (taskIds + parentOf.keys + epicIds).distinct()
    val rows = ids.chunked(ID_CHUNK).flatMap { chunk ->
        w.select(w.issueId, w.authorAccountId, w.startedAt, w.md)
            .where { (w.connectionId eq connectionId) and (w.issueId inList chunk) }
            .toList()
    }
    val byOwner = rows.groupBy(
        { parentOf[it[w.issueId]] ?: it[w.issueId] },
        { CostEntry(it[w.startedAt], it[w.authorAccountId], it[w.md]) },
    )
    val epicSet = epicIds.toSet()
    return byOwner.filterKeys { it !in epicSet } to byOwner.filterKeys { it in epicSet }
}


// ---- series -------------------------------------------------------------------------------------------

private fun seriesOf(facts: DeepDiveFacts, calendar: WorkingCalendar, clockMs: Long): DeepDiveSeries {
    // planDays depends only on the windows and the calendar: one computation per distinct window set.
    val memo = HashMap<Set<Triple<Long?, Long?, Long?>>, List<LocalDate>>()
    val daysOf: (List<SprintWindow>) -> List<LocalDate> = { windows ->
        memo.getOrPut(windows.map { Triple(it.startAtMs, it.completeAtMs, it.endAtMs) }.toSet()) { planDays(windows, calendar) }
    }
    val tasks = facts.tasks.map { task ->
        val windows = facts.scopeByTask[task.issueId].orEmpty()
            .sortedWith(compareBy({ earliestMarkOf(facts.sprints[it.sprintId]) }, { it.sprintId }))
            .map { scope ->
                val sprint = facts.sprints[scope.sprintId]
                SprintWindow(sprint?.startAt, sprint?.completeAt, sprint?.endAt, scope.estimateMd)
            }
        val spans = facts.spansByTask[task.issueId].orEmpty()
            .map { StageSpan(it.fromMs, it.toMs ?: clockMs) }.filter { it.toMs!! > it.fromMs }
        val workedDays = inProgressDays(spans, clockMs, calendar)
        val doneDay = task.doneAt?.let(calendar::dayOf)
        TaskSeries(
            fact = task,
            epicKey = task.epicIssueId?.let { facts.epicLabels[it]?.key },
            plan = taskPlan(windows, calendar, PROVISIONAL_ORIGIN, daysOf),
            spans = spans,
            workedDays = workedDays,
            approxEv = approximateEv(task.evMd, doneDay, workedDays, PROVISIONAL_ORIGIN),
            evState = evStateOf(doneDay, task.evMd),
            done = doneDay?.let { DayAmount(dayOffset(PROVISIONAL_ORIGIN, it), task.evMd ?: BigDecimal.ZERO) },
            cost = costDays(facts.costByTask[task.issueId].orEmpty(), calendar, PROVISIONAL_ORIGIN),
        )
    }
    return DeepDiveSeries(tasks, facts.ownCostByEpic.mapValues { (_, entries) -> costDays(entries, calendar, PROVISIONAL_ORIGIN) })
}

/** What orders a task's sprints earliest first: the sprint's start, else its close; a sprint with neither sorts last. */
private fun earliestMarkOf(sprint: DiveSprint?): Long = sprint?.let { it.startAt ?: it.completeAt ?: it.endAt } ?: Long.MAX_VALUE

/** A sprint's plan window in days ([planWindow]), or `null` when it contributes no day. */
private fun windowOf(sprint: DiveSprint, calendar: WorkingCalendar): Pair<LocalDate, LocalDate>? =
    planWindow(sprint.startAt, completeMs = sprint.completeAt, endMs = sprint.endAt, calendar = calendar)

/** An epic baseline's planned window; `fact_epic_plan` stores UTC-midnight millis of calendar dates. */
private fun epicWindowDays(window: EpicWindow?): Pair<LocalDate, LocalDate>? {
    val start = window?.startAtMs ?: return null
    val due = window.dueAtMs ?: return null
    fun utcDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    return utcDay(start) to utcDay(due)
}

// ---- range --------------------------------------------------------------------------------------------

/** The resolved range and the `note` it earned (an implied span cut to [MAX_WINDOW_DAYS] days, the history horizon). */
private class RangeChoice(val range: ResolvedRange, val note: String?)

/**
 * The range (`.claude/docs/reports.md` "Range"): a given `from`/`to` CLIPS the envelope, so a lone bound outside the envelope pulls the
 * implied other end onto itself (a single, empty day) rather than failing; both given were already validated (`from` not after `to`, at
 * most 1100 days). An implied span over 1100 days keeps its LAST 1100 days (a given `from` or `to` is kept, the other end follows it) and
 * says so ([DEEP_DIVE_RANGE_CLAMPED_NOTE]). With no marks at all the range is the single day `from ?: to ?: today`.
 * The A48 history horizon then clips it ([clampRangeToHorizon]: only the drawn series, never `totals`).
 */
private fun resolveRange(
    request: DeepDiveRequest,
    targets: DeepDiveTargets,
    facts: DeepDiveFacts,
    series: DeepDiveSeries,
    calendar: WorkingCalendar,
    nowMs: Long,
): RangeChoice {
    val sprintEnvelope = (targets.selection as? DeepDiveSelection.Sprints)?.let { selection ->
        val windows = selection.sprintIds.mapNotNull { id -> facts.sprints[id]?.let { windowOf(it, calendar) } }
        windows.minOfOrNull { it.first }?.let { first -> first to windows.maxOf { it.second } }
    }
    val envelope = sprintEnvelope ?: marksEnvelope(facts, series, calendar)
    var from: LocalDate
    var to: LocalDate
    when {
        request.from != null && request.to != null -> { from = request.from; to = request.to }
        request.from != null -> { from = request.from; to = envelope?.second?.let { maxOf(it, request.from) } ?: request.from }
        request.to != null -> { to = request.to; from = envelope?.first?.let { minOf(it, request.to) } ?: request.to }
        else -> {
            from = envelope?.first ?: calendar.dayOf(nowMs)
            to = envelope?.second ?: from
        }
    }
    val implied = request.from == null || request.to == null
    val clamped = implied && ChronoUnit.DAYS.between(from, to) + 1 > DEEP_DIVE_MAX_RANGE_DAYS
    if (clamped) {
        if (request.from != null) to = from.plusDays(DEEP_DIVE_MAX_RANGE_DAYS - 1L) else from = to.minusDays(DEEP_DIVE_MAX_RANGE_DAYS - 1L)
    }
    val cut = clampRangeToHorizon(from, to, request.historyHorizon, clamped)
    return RangeChoice(ResolvedRange(cut.from, cut.to), cut.note)
}

/**
 * The envelope of every mark that would be drawn: plan, done and cost days, the first and last day of each in-progress span (every
 * Approximate EV and in-progress day lies inside one, or is the done day), the epics' own cost and planned windows.
 */
private fun marksEnvelope(facts: DeepDiveFacts, series: DeepDiveSeries, calendar: WorkingCalendar): Pair<LocalDate, LocalDate>? {
    val offsets = mutableListOf<Int>()
    val days = mutableListOf<LocalDate>()
    for (task in series.tasks) {
        task.plan.pv.mapTo(offsets) { it.offset }
        task.done?.let { offsets += it.offset }
        task.cost.mapTo(offsets) { it.offset }
        task.spans.forEach { days += listOf(calendar.dayOf(it.fromMs), calendar.dayOf(it.toMs!! - 1)) }
    }
    series.epicOwn.values.flatten().mapTo(offsets) { it.offset }
    offsets.mapTo(days) { PROVISIONAL_ORIGIN.plusDays(it.toLong()) }
    facts.windows.values.mapNotNull { epicWindowDays(it) }.forEach { days += listOf(it.first, it.second) }
    return days.minOrNull()?.let { it to days.max() }
}

// ---- assembly -----------------------------------------------------------------------------------------

/** Issue keys in their natural order: the project prefix, then the number (`FLO-2` before `FLO-10`). */
private val KEY_ORDER: Comparator<String> = Comparator { a, b ->
    fun number(key: String) = key.substringAfterLast('-', "").toLongOrNull() ?: Long.MAX_VALUE
    a.substringBeforeLast('-').compareTo(b.substringBeforeLast('-')).takeIf { it != 0 }
        ?: number(a).compareTo(number(b)).takeIf { it != 0 }
        ?: a.compareTo(b)
}

private suspend fun assemble(a: Assembly, note: String?, metaOf: (DeepDiveRange) -> ReportMeta): DeepDiveReport {
    val range = a.range
    fun clipped(entries: List<AuthorDayCost>) = entries.filter { range.contains(range.rebase(it.offset)) }
    val accountIds = (a.series.tasks.flatMap { clipped(it.cost) } + a.series.epicOwn.values.flatMap { clipped(it) })
        .mapNotNull { it.author }.distinct()
    val names = accountDisplayNames(accountIds)
    val authors = accountIds.map { DeepDiveAuthor(it, names[it] ?: it) }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.accountId }))
    val authorIndex = authors.withIndex().associate { it.value.accountId to it.index }
    fun costOut(entries: List<AuthorDayCost>) = clipped(entries).map {
        DeepDiveCostDay(range.rebase(it.offset), it.author?.let(authorIndex::getValue), it.md.md())
    }

    val tasks = a.series.tasks.sortedWith(compareBy(KEY_ORDER) { it.fact.key }).map { taskOf(it, a, ::costOut) }
    val epics = epicsOf(a, ::costOut)
    val ownCostMd = a.series.epicOwn.values.sumOf { entries -> entries.sumOf { it.md } }
    val rangeOut = DeepDiveRange(range.from.toString(), range.to.toString(), a.calendar.dayOf(a.clockMs).toString())
    return DeepDiveReport(
        meta = metaOf(rangeOut),
        mode = a.targets.selection.mode.toWire(),
        range = rangeOut,
        nonWorkingDays = (0 until range.days).filter { !a.calendar.isWorkingDay(range.from.plusDays(it.toLong())) },
        sprints = sprintsOf(a),
        authors = authors,
        epics = epics,
        tasks = tasks,
        quality = DeepDiveQuality(
            neverInSprint = a.series.tasks.count { it.plan.noPlanReason == NoPlanReason.NEVER_IN_SPRINT },
            noEstimate = a.series.tasks.count { it.plan.noPlanReason == NoPlanReason.NO_ESTIMATE },
            noWorkingDay = a.series.tasks.count { it.plan.noPlanReason == NoPlanReason.NO_WORKING_DAY },
            epicsWithoutWindow = epics.count { it.key != null && it.plannedStart == null },
            laterFallback = a.series.tasks.count { it.plan.source == PlanSource.LATER_FALLBACK },
            openInProgress = a.series.tasks.count { it.evState == EvState.OPEN && it.workedDays.isNotEmpty() },
            doneUnestimated = a.series.tasks.count { it.evState == EvState.UNESTIMATED },
            epicOwnCostMd = ownCostMd.md(),
        ),
        note = note,
    )
}

private fun taskOf(series: TaskSeries, a: Assembly, costOut: (List<AuthorDayCost>) -> List<DeepDiveCostDay>): DeepDiveTask {
    val range = a.range
    val plan = series.plan
    fun inRange(offset: Int) = range.contains(range.rebase(offset))
    return DeepDiveTask(
        key = series.fact.key,
        summary = a.facts.summaries[series.fact.issueId],
        epicKey = series.epicKey,
        planBasisMd = plan.basisMd?.md(),
        planSource = plan.source.toWire(),
        noPlanReason = plan.noPlanReason?.toWire(),
        pv = plan.pv.filter { inRange(it.offset) }.map { DeepDiveMdDay(range.rebase(it.offset), it.md.md()) },
        approxEv = series.approxEv.filter { inRange(it.offset) }.map { DeepDiveMdDay(range.rebase(it.offset), it.md.md()) },
        inProgress = series.workedDays.map { dayOffset(PROVISIONAL_ORIGIN, it) }.filter(::inRange).map(range::rebase),
        evState = series.evState.toWire(),
        done = series.done?.takeIf { inRange(it.offset) }?.let { DeepDiveDone(range.rebase(it.offset), it.md.md()) },
        cost = costOut(series.cost),
        totals = DeepDiveTotals(
            pvMd = plan.pv.sumOf { it.md }.md(),
            evMd = (series.done?.md ?: BigDecimal.ZERO).md(),
            costMd = series.cost.sumOf { it.md }.md(),
        ),
    )
}

private fun DeepDiveSelectionMode.toWire(): DeepDiveMode = when (this) {
    DeepDiveSelectionMode.SPRINTS -> DeepDiveMode.SPRINTS
    DeepDiveSelectionMode.EPICS -> DeepDiveMode.EPICS
}

private fun EvState.toWire(): DeepDiveEvState = when (this) {
    EvState.EARNED -> DeepDiveEvState.EARNED
    EvState.OPEN -> DeepDiveEvState.OPEN
    EvState.UNESTIMATED -> DeepDiveEvState.UNESTIMATED
}

private fun PlanSource.toWire(): DeepDivePlanSource = when (this) {
    PlanSource.EARLIEST -> DeepDivePlanSource.EARLIEST
    PlanSource.LATER_FALLBACK -> DeepDivePlanSource.LATER_FALLBACK
    PlanSource.NONE -> DeepDivePlanSource.NONE
}

private fun NoPlanReason.toWire(): DeepDiveNoPlanReason = when (this) {
    NoPlanReason.NEVER_IN_SPRINT -> DeepDiveNoPlanReason.NEVER_IN_SPRINT
    NoPlanReason.NO_ESTIMATE -> DeepDiveNoPlanReason.NO_ESTIMATE
    NoPlanReason.NO_WORKING_DAY -> DeepDiveNoPlanReason.NO_WORKING_DAY
}

/** The selected sprints (mode SPRINTS), earliest first, with their plan windows as offsets from `range.from`. */
private fun sprintsOf(a: Assembly): List<DeepDiveSprint> {
    val selection = a.targets.selection as? DeepDiveSelection.Sprints ?: return emptyList()
    return selection.sprintIds.mapNotNull { a.facts.sprints[it] }
        .sortedWith(compareBy({ earliestMarkOf(it) }, { it.sprintId }))
        .map { sprint ->
            val window = windowOf(sprint, a.calendar)
            DeepDiveSprint(
                sprint.sprintId, sprint.name,
                window?.let { a.range.rebase(dayOffset(PROVISIONAL_ORIGIN, it.first)) },
                window?.let { a.range.rebase(dayOffset(PROVISIONAL_ORIGIN, it.second)) },
            )
        }
}

/**
 * The epic rows: the selected epics first (in request order), then the epics the tasks sit under by key, then the `(no epic)` row when a
 * task has none. A baseline outline and the own cost (mode EPICS only) ride on each real epic.
 */
private fun epicsOf(
    a: Assembly,
    costOut: (List<AuthorDayCost>) -> List<DeepDiveCostDay>,
): List<DeepDiveEpic> {
    val labels = a.facts.epicLabels
    val taskEpics = a.facts.tasks.mapNotNull { it.epicIssueId }.filter { it in labels }.distinct()
        .sortedWith(compareBy(KEY_ORDER) { labels.getValue(it).key })
    val ordered = (a.targets.epics.map { it.issueId } + taskEpics).distinct().filter { it in labels }
    val rows = ordered.map { id ->
        val label = labels.getValue(id)
        val window = a.facts.windows[id]
        val days = epicWindowDays(window)
        val own = a.series.epicOwn[id].orEmpty()
        DeepDiveEpic(
            key = label.key,
            summary = label.summary,
            plannedStart = days?.let { a.range.rebase(dayOffset(PROVISIONAL_ORIGIN, it.first)) },
            plannedDue = days?.let { a.range.rebase(dayOffset(PROVISIONAL_ORIGIN, it.second)) },
            budgetMd = window?.budgetMd?.md(),
            ownCost = own.takeIf { it.isNotEmpty() }?.let { DeepDiveOwnCost(costOut(it), it.sumOf { entry -> entry.md }.md()) },
        )
    }
    val epicLess = a.series.tasks.any { it.epicKey == null }
    return if (epicLess) rows + DeepDiveEpic(null, null, null, null, null, null) else rows
}
