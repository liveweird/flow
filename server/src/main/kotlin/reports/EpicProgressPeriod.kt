package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

/**
 * What the epic-progress curves and headline are measured over (A37): [PERIOD] = only what was planned, earned and spent INSIDE the
 * period (`[first day, asOf.day]`, the series rebased to start at 0); [CUMULATIVE] = everything from the beginning of time (the
 * textbook bounded-project view). The per-level default is [defaultBasis].
 */
@Serializable
enum class EpicProgressBasis { PERIOD, CUMULATIVE }

/** A single epic has a life to read in full; a domain and a team never finish, so their default is the period. */
internal fun EpicProgressLevel.defaultBasis(): EpicProgressBasis =
    if (this == EpicProgressLevel.EPIC) EpicProgressBasis.CUMULATIVE else EpicProgressBasis.PERIOD

/**
 * The smallest PLANNED (SPI) or SPENT (CPI) man-days a period or a trend window needs before its index is shown: EV arrives in lumps
 * (a task's whole value lands on its done day), so a ratio over a sliver of PV or AC is noise (`ΔEV ÷ 0.01`). Below it the index is
 * `null`. A constant, not a setting (A37); the cumulative `asOf` indices keep their old rule (`null` only at exactly 0).
 */
internal const val MIN_PERIOD_DENOMINATOR_MD = 1.0

internal const val DEFAULT_TREND_WINDOW_DAYS = 28
internal const val MIN_TREND_WINDOW_DAYS = 7
internal const val MAX_TREND_WINDOW_DAYS = 182

/** The two epic-progress parameters outside the shared filter: [basis] (`null` = the level's default) and [trendWindowDays]. */
internal class EpicProgressOptions(val basis: EpicProgressBasis? = null, val trendWindowDays: Int = DEFAULT_TREND_WINDOW_DAYS)

/** `400` for an unknown `basis`, a `trendWindowDays` that is not an integer or lies outside 7..182. */
internal fun parseEpicProgressOptions(params: Parameters): EpicProgressOptions {
    val basis = params.optionalEnum<EpicProgressBasis>("basis")
    val window = params.optionalString("trendWindowDays")?.let {
        it.toIntOrNull() ?: throw BadRequestException("trendWindowDays must be an integer")
    } ?: DEFAULT_TREND_WINDOW_DAYS
    if (window !in MIN_TREND_WINDOW_DAYS..MAX_TREND_WINDOW_DAYS) {
        throw BadRequestException("trendWindowDays must be between $MIN_TREND_WINDOW_DAYS and $MAX_TREND_WINDOW_DAYS")
    }
    return EpicProgressOptions(basis, window)
}

/**
 * The headline over the PERIOD (A37): the figures inside `[firstDay, asOf.day]`, `ΔPV`/`ΔEV`/`ΔAC` with [sv] = ΔEV − ΔPV,
 * [spi] = ΔEV ÷ ΔPV and [cpi] = ΔEV ÷ ΔAC — each index `null` when its denominator is under [MIN_PERIOD_DENOMINATOR_MD]. [firstDay] is
 * the period's first listed day (`null`, with every figure 0, when the period lies after `asOf.day`).
 */
@Serializable
data class EpicProgressPeriod(
    val firstDay: String?,
    val pv: Double,
    val ev: Double,
    val ac: Double,
    val sv: Double,
    val spi: Double?,
    val cv: Double,
    val cpi: Double?,
)

/** A drill row's period figures: the same as [EpicProgressPeriod] without the day. */
@Serializable
data class EpicProgressRowPeriod(
    val pv: Double,
    val ev: Double,
    val ac: Double,
    val sv: Double,
    val spi: Double?,
    val cv: Double,
    val cpi: Double?,
)

/** One trend point: SPI and CPI over the trailing window ending at [day] (a week end, or `asOf.day` for the last, partial week). */
@Serializable
data class EpicProgressTrendPoint(val day: String, val spi: Double?, val cpi: Double?)

internal operator fun Evm.minus(other: Evm) = Evm(pv - other.pv, ev - other.ev, ac - other.ac)

/** SPI over a window or period: `null` below [MIN_PERIOD_DENOMINATOR_MD] of planned value (this also covers 0 and a negative delta). */
internal val Evm.periodSpi: Double? get() = if (pv.toDouble() < MIN_PERIOD_DENOMINATOR_MD) null else ev.toDouble() / pv.toDouble()

/** CPI over a window or period: `null` below [MIN_PERIOD_DENOMINATOR_MD] of actual cost. */
internal val Evm.periodCpi: Double? get() = if (ac.toDouble() < MIN_PERIOD_DENOMINATOR_MD) null else ev.toDouble() / ac.toDouble()

internal fun Evm.toPeriod(firstDay: LocalDate?) =
    EpicProgressPeriod(firstDay?.toString(), pv.md(), ev.md(), ac.md(), sv.md(), periodSpi, cv.md(), periodCpi)

internal fun Evm.toRowPeriod() = EpicProgressRowPeriod(pv.md(), ev.md(), ac.md(), sv.md(), periodSpi, cv.md(), periodCpi)

/** The all-zero period of an answer with nothing to read. */
internal val NO_PERIOD: EpicProgressPeriod = NO_EVM.toPeriod(null)

/**
 * The period delta: everything in `[firstDay, asOfDay]` = the running sum at [asOfDay] minus the running sum the day before
 * [firstDay] (`null` [firstDay] = an empty period). Returns (the sum BEFORE the period, the delta). Pure over [increments].
 */
internal fun periodDelta(increments: List<DayIncrement>, firstDay: LocalDate?, asOfDay: LocalDate): Pair<Evm, Evm> {
    if (firstDay == null) return NO_EVM to NO_EVM
    val (before, through) = totalsThrough(increments, listOf(firstDay.minusDays(1), asOfDay))
    return before to (through - before)
}

/**
 * The series points: [days]' running [totals] minus [base] (the sum before the period when rebasing, [NO_EVM] otherwise). With
 * [rebaseAfter] set (the day before the period's first day) the EPIC level's `pvOriginal` is rebased by the same day's value.
 */
internal fun progressSeries(
    days: List<LocalDate>,
    totals: List<Evm>,
    base: Evm,
    rebaseAfter: LocalDate?,
    detail: EpicDetail?,
): List<EpicProgressPoint> {
    val originalBase = rebaseAfter?.let { detail?.pvOriginalAt(it) } ?: BigDecimal.ZERO
    return days.mapIndexed { index, day ->
        val shown = totals[index] - base
        val original = detail?.pvOriginalAt(day)?.let { (it - originalBase).md() }
        EpicProgressPoint(day.toString(), shown.pv.md(), shown.ev.md(), shown.ac.md(), original)
    }
}

/**
 * The trend's days: the END of every Monday-start week (Sunday, the configured-zone dates being zone-free here) that overlaps
 * `[firstDay, asOfDay]` — the throughput report's week rule — the last one cut at [asOfDay].
 */
internal fun trendDays(firstDay: LocalDate, asOfDay: LocalDate): List<LocalDate> {
    val days = mutableListOf<LocalDate>()
    var monday = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    while (!monday.isAfter(asOfDay)) {
        days += minOf(monday.plusDays(WEEK_LAST_DAY_OFFSET), asOfDay)
        monday = monday.plusWeeks(1)
    }
    return days
}

private const val WEEK_LAST_DAY_OFFSET = 6L

/**
 * SPI and CPI over the trailing window `(day − windowDays, day]` for every [days] entry (ascending), from ONE fetch's [increments]:
 * `ΔEV ÷ ΔPV` and `ΔEV ÷ ΔAC`, each `null` under [MIN_PERIOD_DENOMINATOR_MD]. The window reaches before the period when it must.
 */
internal fun trendOf(increments: List<DayIncrement>, days: List<LocalDate>, windowDays: Int): List<EpicProgressTrendPoint> {
    val ends = totalsThrough(increments, days)
    val starts = totalsThrough(increments, days.map { it.minusDays(windowDays.toLong()) })
    return days.mapIndexed { index, day ->
        val delta = ends[index] - starts[index]
        EpicProgressTrendPoint(day.toString(), delta.periodSpi, delta.periodCpi)
    }
}
