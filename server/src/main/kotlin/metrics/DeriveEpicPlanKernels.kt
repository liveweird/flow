package ch.nokillswit.metrics

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.norm.FieldChangeRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One point of an epic date field's (start or due) own value timeline (v0.3.0 M3 commit 9b) —
 * mirrors [EstimatePoint] for a date-typed field (`dateMs` = epoch millis at start of day UTC, the
 * `jira/JiraNormalizer.kt` `duedate` convention, or `null` for an unset/unparseable value).
 */
data class DatePoint(val atMs: Long, val dateMs: Long?)

/**
 * One `metrics.fact_epic_plan` row (pure, pre-persistence, v0.3.0 M3 commit 9b,
 * `.claude/docs/domain-model.md` "Plan — PV", D4, D11) — an epic's PV baseline, in effect from
 * [baselinedAtMs] until [supersededAtMs] (`null` = the epic's CURRENT baseline). `budgetSource` is
 * `"OWN"` when the epic's own configured estimate field resolves a nonzero value at
 * [baselinedAtMs], `"CHILDREN"` otherwise (D4's fallback). The caller
 * (`metrics/MetricsDeriver.kt`) assigns `baseline_seq` (1-based, ordered) when persisting — this
 * shape carries only what [epicPlanBaselines] itself computes.
 */
data class EpicPlanBaseline(
    val baselinedAtMs: Long,
    val startAtMs: Long,
    val dueAtMs: Long,
    val budgetMd: Double,
    val budgetSource: String,
    val supersededAtMs: Long?,
)

/** One point of [pvCurve]'s own cumulative curve — one per WORKING day in `[baseline.startAtMs, baseline.dueAtMs]`. */
data class PvPoint(val day: LocalDate, val cumulativeMd: Double)

/**
 * The per-item derivation math (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Analytical
 * model"/"The three dimensions") — pure Kotlin, no DB, the `norm/Tiling.kt` pattern:
 * property-testable, called once per issue by `metrics/MetricsDeriver.kt` over ALREADY-persisted
 * `norm.*` rows and the connection's effective metrics configuration.
 */
internal const val ONE_YEAR_MS = 365L * MILLIS_PER_DAY
internal const val TWO_YEARS_MS = 2 * ONE_YEAR_MS

/** How far back `dim_date` reaches at most, whatever a stale timestamp says (`dimDateRange`). */
internal const val DIM_DATE_FLOOR_YEARS = 50L

/**
 * The PV horizon (A23): an epic's current baseline gets a PV curve only if BOTH its start and due
 * lie within `[today - PV_HORIZON_YEARS, today + PV_HORIZON_YEARS]` (UTC dates, [inPvHorizon]).
 * A placeholder date (9999-12-31, 1900-01-01) would otherwise build millions of `dim_date` and
 * `agg_daily_flow` rows every DERIVE run.
 */
const val PV_HORIZON_YEARS = 10L

/** The inclusive `[fromMs, toMs]` span [dimDateRange] says `metrics.dim_date` must cover. */
data class DimDateRange(val fromMs: Long, val toMs: Long)

/** The PV horizon as `[fromMs, toExclusiveMs)`: whole UTC days, ten years either side of [nowMs]'s UTC date. */
fun pvHorizonMs(nowMs: Long): Pair<Long, Long> {
    val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
    val from = today.minusYears(PV_HORIZON_YEARS).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val toExclusive = today.plusYears(PV_HORIZON_YEARS).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return from to toExclusive
}

/** True when BOTH [startAtMs] and [dueAtMs] lie within [pvHorizonMs] — the only epics that get a PV curve. */
fun inPvHorizon(startAtMs: Long, dueAtMs: Long, nowMs: Long): Boolean {
    val (from, toExclusive) = pvHorizonMs(nowMs)
    return startAtMs in from until toExclusive && dueAtMs in from until toExclusive
}

/**
 * The `metrics.dim_date` span a DERIVE run must cover (pure): from one year before the earliest
 * known fact timestamp ([earliestFactMs] — creation, worklog start, sprint start, done time; null
 * = none, so [nowMs]) floored at [DIM_DATE_FLOOR_YEARS] before [nowMs], to `now + 2 years`; widened
 * by one day of slack at each end for every epic window in [epicWindows] (start, due) that lies in
 * the PV horizon — an out-of-horizon window is ignored, exactly as the PV SQL ignores it.
 */
fun dimDateRange(nowMs: Long, earliestFactMs: Long?, epicWindows: List<Pair<Long, Long>>): DimDateRange {
    val floor = nowMs - DIM_DATE_FLOOR_YEARS * ONE_YEAR_MS
    var from = maxOf(earliestFactMs ?: nowMs, floor) - ONE_YEAR_MS
    var to = nowMs + TWO_YEARS_MS
    epicWindows.filter { (start, due) -> inPvHorizon(start, due, nowMs) }.forEach { (start, due) ->
        from = minOf(from, start - MILLIS_PER_DAY)
        to = maxOf(to, due + MILLIS_PER_DAY)
    }
    return DimDateRange(from, to)
}

/**
 * An epic date field's (start or due) own value timeline (v0.3.0 M3 commit 9b) — the SAME shape
 * [estimateTimeline] builds for a numeric field, applied to a Jira plain-date field
 * (`YYYY-MM-DD`, the `duedate`/"Start date" shape, `.claude/docs/jira-integration.md`
 * "Timestamps" — a plain date carries no offset to parse, unlike a Jira timestamp). [changes]
 * must already be scoped to ONE field id and ordered by `changedAt` ascending
 * (`WorkItemStore.fieldChangesByFieldIds`); [currentDateMs] is the field's CURRENT value
 * (`DeriveContext.epicDateValue`, `metrics/MetricsDeriver.kt`) — the timeline's own ground
 * truth, the SAME convention [estimateTimeline] applies to its own last point. A malformed date
 * string parses to `null` rather than throwing — an unparseable changelog value is simply an
 * unset point here, never a crash.
 */
fun dateFieldTimeline(createdAtMs: Long, changes: List<FieldChangeRow>, currentDateMs: Long?): List<DatePoint> {
    fun parse(raw: String?): Long? =
        raw?.let { text -> runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
    if (changes.isEmpty()) return listOf(DatePoint(createdAtMs, currentDateMs))
    val firstRaw = changes.first().let { it.fromValue ?: it.fromText }
    val points = mutableListOf(DatePoint(createdAtMs, parse(firstRaw)))
    changes.forEach { change ->
        val raw = change.toValue ?: change.toText
        points += DatePoint(maxOf(change.changedAt, createdAtMs), parse(raw))
    }
    points[points.lastIndex] = points.last().copy(dateMs = currentDateMs)
    return points
}

/** The date active at [atMs] — the last timeline point at or before it (the [estimateAt] shape, applied to a date timeline). */
private fun dateAt(timeline: List<DatePoint>, atMs: Long): Long? = timeline.lastOrNull { it.atMs <= atMs }?.dateMs

/**
 * D4's PV baselines (v0.3.0 M3 commit 9b, `.claude/docs/domain-model.md` "Plan — PV", D4, D11;
 * `.claude/docs/metrics.md` "Epic plans and PV") — from the epic's own start/due date timelines
 * ([startTimeline]/[dueTimeline], [dateFieldTimeline]'s own shape), its own configured-estimate
 * timeline ([ownEstimateTimeline], [estimateTimeline]'s own shape — the SAME timeline
 * `fact_epic_delivery`'s own snapshots are read from, never rebuilt here), and [childSumMd] (D4's
 * CHILDREN fallback — a single CURRENT snapshot, `fact_epic_delivery.childSumEstimateMd`'s own
 * value, never a historical timeline of its own: a full historical child-sum roll-up would need
 * every child's own estimate history at every past instant, which this commit does not build).
 *
 * A baseline exists only once BOTH dates are set — `baselinedAtMs` is the instant the LATER of
 * the two first resolves (an epic with no dates at all — or only one of them ever set — gets
 * zero rows). From there, every LATER instant either date or the budget genuinely changes value
 * opens a NEW baseline and supersedes the previous one (`supersededAtMs` = that instant); a
 * "change" that resolves to the SAME start/due/budget/source as the current baseline is a no-op,
 * matching every other kernel's idempotence rule (`sprintScope`, `estimateTimeline`). `0` or a
 * missing OWN estimate both count as unestimated (re-applied here rather than trusted from the
 * caller's own timeline construction) — CHILDREN then applies.
 *
 * **A date becoming unset closes the current baseline too** — if either date resolves to `null`
 * at a later instant, the open baseline is superseded RIGHT THERE (`current` becomes `null`,
 * with no replacement): the epic has no current plan until both dates are set again. A LATER
 * instant where both resolve once more always opens a genuinely NEW baseline, even when its
 * values happen to equal the closed one's — the gap itself is a real discontinuity in the
 * epic's plan, never silently bridged over.
 */
fun epicPlanBaselines(
    startTimeline: List<DatePoint>,
    dueTimeline: List<DatePoint>,
    ownEstimateTimeline: List<EstimatePoint>,
    childSumMd: Double,
): List<EpicPlanBaseline> {
    val instants = (startTimeline.map { it.atMs } + dueTimeline.map { it.atMs } + ownEstimateTimeline.map { it.atMs })
        .distinct().sorted()
    if (instants.isEmpty()) return emptyList()

    fun budgetAt(atMs: Long): Pair<Double, String> {
        val own = estimateAt(ownEstimateTimeline, atMs)?.takeIf { it != UNESTIMATED }
        return if (own != null) own to "OWN" else childSumMd to "CHILDREN"
    }

    val baselines = mutableListOf<EpicPlanBaseline>()
    var current: EpicPlanBaseline? = null
    for (atMs in instants) {
        val start = dateAt(startTimeline, atMs)
        val due = dateAt(dueTimeline, atMs)
        val existing = current
        if (start == null || due == null) {
            if (existing != null) {
                baselines += existing.copy(supersededAtMs = atMs)
                current = null
            }
            continue
        }
        val (budget, source) = budgetAt(atMs)
        val changed = existing != null &&
            (existing.startAtMs != start || existing.dueAtMs != due || existing.budgetMd != budget || existing.budgetSource != source)
        if (existing == null || changed) {
            if (existing != null) baselines += existing.copy(supersededAtMs = atMs)
            current = EpicPlanBaseline(atMs, start, due, budget, source, null)
        }
    }
    current?.let { baselines += it }
    return baselines
}

/**
 * [baseline]'s own PV curve (v0.3.0 M3 commit 9b, `.claude/docs/metrics.md` "Epic plans and
 * PV") — `budgetMd` spread evenly over the WORKING days in `[startAt, dueAt]` (inclusive both
 * ends), one cumulative point per working day. `startAt`/`dueAt` are ZONE-FREE calendar dates
 * (epoch millis at start of day UTC, the `dateFieldTimeline`/`DeriveContext.epicDateValue`
 * convention — the SAME `duedate` shape `jira/JiraNormalizer.kt` stores) — they are read back
 * as `LocalDate`s via [ZoneOffset.UTC], NEVER [calendar]'s own configured zone, which would
 * shift a UTC-midnight date a calendar day EARLIER for any zone behind UTC (e.g.
 * `America/New_York`). Only [calendar]'s weekend/holiday rules (zone-free predicates of their
 * own, [WorkingCalendar.isWorkingDay]) apply to those dates — the configured zone matters only
 * for converting an INSTANT to a day, never a date that was already a plain calendar date. The
 * LAST working day absorbs the rounding remainder, so `PV(due) == budgetMd` exactly regardless
 * of how evenly the division splits — the curve is otherwise non-decreasing by construction
 * (`perDay >= 0` for a non-negative budget). A window with no working day at all (every day in
 * range is a weekend/holiday) returns an empty curve — there is nowhere to place the budget.
 */
fun pvCurve(baseline: EpicPlanBaseline, calendar: WorkingCalendar): List<PvPoint> {
    val startDay = Instant.ofEpochMilli(baseline.startAtMs).atZone(ZoneOffset.UTC).toLocalDate()
    val dueDay = Instant.ofEpochMilli(baseline.dueAtMs).atZone(ZoneOffset.UTC).toLocalDate()
    val workingDays = mutableListOf<LocalDate>()
    var day = startDay
    while (!day.isAfter(dueDay)) {
        if (calendar.isWorkingDay(day)) workingDays += day
        day = day.plusDays(1)
    }
    if (workingDays.isEmpty()) return emptyList()
    val perDay = baseline.budgetMd / workingDays.size
    var cumulative = 0.0
    return workingDays.mapIndexed { index, day2 ->
        cumulative = if (index == workingDays.lastIndex) baseline.budgetMd else cumulative + perDay
        PvPoint(day2, cumulative)
    }
}
