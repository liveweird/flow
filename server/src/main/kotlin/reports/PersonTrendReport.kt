package ch.nokillswit.reports

/**
 * `GET /api/v1/reports/person-trend` (Report 20, `.claude/docs/reports.md` "Report 20"): ONE person's Compare-people cells over rolling
 * [TREND_WINDOW_DAYS]-day windows, one ending every [TREND_STEP_DAYS] days from the request's `to` back to its `from`, read across every
 * team the person belonged to (D12: any signed-in user; no capacity rows, A39 stays a Compare-people thing).
 *
 * ONE TRUTH: every point is Report 18's [cellsOf] -- the very function behind `GET /compare-people` -- over the populations of its window.
 * The reads run ONCE over the whole range (the first window's start to `to`), however many windows: the five fact populations (done
 * tasks, cycle, accuracy, blocked -- from ONE `fact_task_delivery` scan, [fetchDeliveredFacts] -- and worklogs), three WIP
 * statements ([readTrendWip]: the per-day counts, the tasks held on each window's last
 * day, their `started_at`) and the small DERIVE-clock / backfill lookups behind the plan and the coverage labels; a window's
 * populations are the rows anchored inside it ([sliceByAnchor], the half-open test each fetch applies in SQL), so a point equals the
 * Compare-people cell of a request whose `from`/`to` are that window. The estimate adjustments are not read (no Person trend row uses
 * them), and there is no capacity.
 *
 * THE TEAM REFERENCE ([readReferenceTeams], `PersonTrendReference.kt`): each point also carries the median of the person's team peers'
 * cells. The peers' populations ride the SAME two reads and three WIP statements (their account ids join the person's in the
 * filter's `IN`), so the statement count grows only by the membership reads (person, rosters, team names); a peer's cell is [cellsOf]
 * over the same window slice as the person's.
 */
internal suspend fun ReportService.personTrend(filter: ReportFilter, nowMs: Long): PersonTrendReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val period = filter.period as ReportPeriod.DateRange
    val person = filter.accountIds.single()
    val calendar = scope.calendar
    val windows = pointEnds(period.fromDate, period.toDate).map { trendWindowOf(it, calendar) }
    val range = windows.first().bounds.first to windows.last().bounds.second
    val coverage = derivedCoverage(scope.connectionIds, calendar)
    val derived = scope.connectionIds.filter { it !in coverage.notDerived }
    val backfill = backfillStart(derived)?.date
    val references = readReferenceTeams(person, windows, calendar)
    val everyone = (listOf(person) + references.flatMap { it?.peers.orEmpty() }).distinct()
    val wip = readTrendWip(scope.copy(window = range), filter, windows, derived, everyone)
    val populations = fetchTrendPopulations(filter.copy(accountIds = everyone), scope.connectionIds, range)

    val datas = windows.mapIndexed { i, window ->
        populations.dataOf(window, scope, wip.forWindow(window), listOf(person) + references[i]?.peers.orEmpty())
    }
    val cells = datas.map { it.getValue(person).cellsOf(person) }
    val referenceCells = references.indices.map { referencesOf(references[it], datas[it]) }
    val labels = windows.map { coverageOf(it, coverage.day, backfill) }
    PersonTrendReport(
        meta = scope.meta,
        person = ComparePerson(person, pickedAccounts(filter).label(person)),
        windowDays = TREND_WINDOW_DAYS,
        stepDays = TREND_STEP_DAYS,
        windows = windows.mapIndexed { i, w -> PersonTrendWindow(w.end.toString(), w.from.toString(), labels[i], references[i]?.team) },
        rows = TREND_ROWS.map { spec -> trendRowOf(spec, windows, labels, cells, referenceCells, scope.settings.minSampleSize) },
        workMix = PersonTrendWorkMix(
            activityType = mixSeriesOf(windows, datas.map { it.getValue(person).done }, person) { it.activityType },
            workCategory = mixSeriesOf(windows, datas.map { it.getValue(person).done }, person) { it.workCategory ?: UNCATEGORIZED },
        ),
        note = wip.note,
    )
}

private fun trendRowOf(
    spec: CompareRowSpec,
    windows: List<TrendWindow>,
    coverages: List<TrendCoverage>,
    cells: List<Map<String, CompareCell>>,
    references: List<Map<String, PersonTrendReference>?>,
    minSample: Int,
): PersonTrendRow {
    val cellsOfRow = cells.map { it.getValue(spec.key) }
    val ends = windows.map { it.end.toString() }
    return PersonTrendRow(
        key = spec.key, group = spec.group, unit = spec.unit, direction = spec.direction, target = spec.target,
        tolerance = spec.unit.tolerance, drill = spec.drill,
        points = ends.indices.map { i ->
            PersonTrendPoint(ends[i], cellsOfRow[i].value, cellsOfRow[i].n, cellsOfRow[i].hidden, references[i]?.get(spec.key))
        },
        trend = cueOf(spec, ends.indices.map { CuePoint(ends[it], coverages[it], cellsOfRow[it]) }, minSample),
    )
}

/**
 * The work mix of [dones] (one list of the person's done tasks per window): one series per key the person delivered under in ANY window,
 * ordered by total man-days (most first) then key, each with a point for EVERY window. A window's share is [mixSharesOf]'s own (the
 * key's man-days over all the window's), `0` for a key absent from a window that delivered some man-days, omitted when it delivered none.
 */
private fun mixSeriesOf(
    windows: List<TrendWindow>,
    dones: List<List<CompareDoneTask>>,
    person: String,
    keyOf: (CompareDoneTask) -> String,
): List<PersonTrendMixSeries> {
    val perWindow = dones.map { mixSharesOf(it, person, keyOf) }
    val keys = perWindow.flatMap { it.keys }.filterNotNull().distinct()
    fun pointsOf(key: String) = windows.indices.map { i ->
        val shares = perWindow[i]
        val own = shares[key]
        val fallback = if (shares.values.any { it.share != null }) 0.0 else null
        PersonTrendMixPoint(windows[i].end.toString(), own?.md ?: 0.0, own?.share ?: fallback)
    }
    return keys.map { PersonTrendMixSeries(it, pointsOf(it)) }
        .sortedWith(compareByDescending<PersonTrendMixSeries> { series -> series.points.sumOf { it.md } }.thenBy { it.key })
}

/** The five populations Compare people reads from facts, fetched once over the whole range and sorted by anchor for [sliceByAnchor]. */
internal class TrendPopulations(
    val done: List<CompareDoneTask>,
    val cycle: List<DoneCycleTask>,
    val accuracy: List<AccuracyTask>,
    val blocked: List<DoneItem>,
    val worklogs: List<CompareWorklog>,
) {
    /**
     * The [CompareData] of each of [accounts] over one window: that account's rows of every population's slice plus the window's [wip]
     * -- what `loadCompareData` builds for that period (every cell function reads only its own account's rows, so the split is exact).
     */
    fun dataOf(window: TrendWindow, scope: ReportScope, wip: CompareWip, accounts: Collection<String>): Map<String, CompareData> {
        val doneOf = done.sliceByAnchor(window.bounds) { it.doneAt }.groupBy { it.accountId }
        val cycleOf = cycle.sliceByAnchor(window.bounds) { it.doneAt }.groupBy { it.accountId }
        val accuracyOf = accuracy.sliceByAnchor(window.bounds) { it.doneAt }.groupBy { it.accountId }
        val blockedOf = blocked.sliceByAnchor(window.bounds) { it.doneAt }.groupBy { it.account }
        val worklogsOf = worklogs.sliceByAnchor(window.bounds) { it.startedAt }.groupBy { it.accountId }
        return accounts.associateWith { account ->
            CompareData(
                minSample = scope.settings.minSampleSize,
                window = window.bounds,
                calendar = scope.calendar,
                done = doneOf[account].orEmpty(),
                cycle = cycleOf[account].orEmpty(),
                accuracy = accuracyOf[account].orEmpty(),
                adjustments = emptyList(),
                blocked = blockedOf[account].orEmpty(),
                worklogs = worklogsOf[account].orEmpty(),
                wip = wip,
                capacity = null,
            )
        }
    }
}

/**
 * Compare people's own populations over [range] for [filter]'s accounts (person and peers; its slices apply), sorted for [sliceByAnchor]:
 * the four of `fact_task_delivery` from ONE scan ([fetchDeliveredFacts], each exactly its own report's fetch) and the worklogs.
 */
private suspend fun fetchTrendPopulations(
    filter: ReportFilter,
    connections: List<UInt>,
    range: Pair<Long, Long>,
): TrendPopulations {
    val facts = fetchDeliveredFacts(filter, connections, range)
    return TrendPopulations(
        done = facts.done.sortedBy { it.doneAt },
        cycle = facts.cycle.sortedBy { it.doneAt },
        accuracy = facts.accuracy.sortedBy { it.doneAt },
        blocked = facts.blocked.sortedBy { it.doneAt },
        worklogs = fetchCompareWorklogs(filter, connections, range).sortedBy { it.startedAt },
    )
}
