package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnTypeAlias
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LessEqOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.doubleParam
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/*
 * The Home overview's done-task statements (`HomeDoneFigures.kt`): the listed teams' figures are aggregated in Postgres and no done-task
 * row is loaded (build-times WHY 38). Statement 1 groups by (team, window, week) and counts; statement 2 returns the few values at the
 * ranks the medians and percentiles read. Every comparison and every number the Kotlin kernels use is mirrored term for term; the golden
 * test (`HomeDoneFiguresGoldenTest`) grades both against the old per-row figures over a randomized world.
 */

/** `CASE WHEN condition THEN 1 END`: counted by `COUNT(..)`, null (not counted) while the condition is false or unknown. */
private fun flag(condition: Expression<*>): Expression<*> = valueWhen(condition, intLiteral(1))

/** An expression whose SQL [render] writes (typed so a [ResultRow] decodes it; a nullable result is read with `getOrNull`). */
private class SqlExpr<T>(override val columnType: IColumnType<T & Any>, private val render: QueryBuilder.() -> Unit) :
    ExpressionWithColumnType<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.render()
    }
}

private val TASKS = MetricsTables.FactTaskDelivery

/**
 * `1` for a task done in the current window, `0` for the previous one (the windows are contiguous: the previous ends where the current
 * starts, so one comparison splits the rows both windows' read keeps). The bound is inlined, never a parameter: the same text is also
 * a `GROUP BY` item, and Postgres matches a grouped expression by its parse tree, where two parameters never match.
 */
private fun windowOf(currentFromMs: Long): ExpressionWithColumnType<Int> = SqlExpr<Int>(IntegerColumnType()) {
    append("CASE WHEN ")
    append(TASKS.doneAt)
    append(" >= $currentFromMs THEN 1 ELSE 0 END")
}

/**
 * The 1-based index of the week of a current-window task among [startsMs] (each Monday's first instant, ascending: `width_bucket` is the
 * count of starts at or before `done_at`, the Monday `bucketStart` names); `NULL` for the previous window. Longs inlined, like [windowOf].
 */
private fun weekOf(currentFromMs: Long, startsMs: List<Long>): ExpressionWithColumnType<Int?> = SqlExpr<Int?>(IntegerColumnType()) {
    append("CASE WHEN ")
    append(TASKS.doneAt)
    append(" >= $currentFromMs THEN width_bucket(")
    append(TASKS.doneAt)
    append(", ARRAY[${startsMs.joinToString(",")}]::bigint[]) END")
}

/**
 * `|1 - 1 ÷ (actual ÷ estimate at start)|` of a task in the PRED sample (worked, started, estimated at start), else `NULL`: the
 * `taskRatioOf` ratio and `predWithin`'s relative error, in float8 like the doubles they are. The `CASE` guard is load-bearing: SQL `AND`
 * does not short-circuit, so without it a zero estimate would divide inside a row that is not in the sample.
 */
private fun relativeError(): ExpressionWithColumnType<Double?> = SqlExpr<Double?>(DoubleColumnType()) {
    append("CASE WHEN ")
    append(TASKS.hasWorklogs)
    append(" AND ")
    append(TASKS.actualMd)
    append(" > 0 AND ")
    append(TASKS.startedAt)
    append(" IS NOT NULL AND ")
    append(TASKS.estimateAtStartMd)
    append(" > 0 THEN abs(1.0::float8 - 1.0::float8 / (")
    append(TASKS.actualMd)
    append("::float8 / ")
    append(TASKS.estimateAtStartMd)
    append("::float8)) END")
}

/** The `PRED(threshold)` numerator: the sample items with `|1 - 1/r| <= threshold + epsilon` (the Kotlin rule's float comparison). */
private fun within(error: Expression<*>, threshold: Double): Expression<*> =
    flag(LessEqOp(error, doubleParam(threshold + PRED_EPSILON)))

/** What statement 1 counts for one (team, window, week) group; a window's figure is the sum of its groups. */
internal data class HomeDoneWindowAgg(
    val nAll: Int,
    val sumEstimateAtDone: BigDecimal,
    val nRatio: Int,
    val within10: Int,
    val within25: Int,
    val within50: Int,
    val noEstimate: Int,
    val unassigned: Int,
    val late: Int,
) {
    operator fun plus(other: HomeDoneWindowAgg) = HomeDoneWindowAgg(
        nAll + other.nAll, sumEstimateAtDone + other.sumEstimateAtDone, nRatio + other.nRatio, within10 + other.within10,
        within25 + other.within25, within50 + other.within50, noEstimate + other.noEstimate, unassigned + other.unassigned,
        late + other.late,
    )

    companion object {
        val ZERO = HomeDoneWindowAgg(0, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0)
    }
}

/** One group of statement 1: [week] is the 1-based index among the current window's Mondays, `null` for the previous window. */
internal class HomeDoneGroup(val team: UInt, val current: Boolean, val week: Int?, val agg: HomeDoneWindowAgg)

/**
 * Statement 1: the listed [teams]' done tasks of [window] (both windows), counted per (team, window, week) with [currentFromMs] the
 * current window's first instant and [weekStartsMs] its Mondays' first instants (`weekStartsMs`).
 */
internal suspend fun readHomeDoneGroups(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    teams: Collection<UInt>,
    currentFromMs: Long,
    weekStartsMs: List<Long>,
): List<HomeDoneGroup> {
    val t = TASKS
    val win = windowOf(currentFromMs)
    val week = weekOf(currentFromMs, weekStartsMs)
    val error = relativeError()
    val counts = listOf(
        t.issueId.count(), t.estimateAtDoneMd.sum(), Count(error), Count(within(error, PRED_10)), Count(within(error, PRED_25)),
        Count(within(error, PRED_50)), Count(flag(t.estimateSource eq NO_ESTIMATE_SOURCE)),
        Count(flag(t.assigneeAccountIdAtDone.isNull())), Count(flag(t.estimatedLate)),
    )
    val predicate = doneInWindow(filter, connectionIds, window) and (t.creditTeamId inList teams)
    val rows = t.select(listOf(t.creditTeamId, win, week) + counts).where { predicate }.groupBy(t.creditTeamId, win, week).toList()
    return rows.map { row ->
        fun count(i: Int) = (row[counts[i]] as Long).toInt()
        HomeDoneGroup(
            team = row[t.creditTeamId]!!.value,
            current = row[win] == 1,
            week = row.getOrNull(week),
            agg = HomeDoneWindowAgg(
                nAll = count(0), sumEstimateAtDone = (row[counts[1]] as BigDecimal?) ?: BigDecimal.ZERO, nRatio = count(2),
                within10 = count(3), within25 = count(4), within50 = count(5), noEstimate = count(6), unassigned = count(7),
                late = count(8),
            ),
        )
    }
}

private const val MEDIAN_FRACTION = 0.5
private const val CYCLE_P85_FRACTION = 0.85

internal const val SAMPLE_ESTIMATE = "estimate"
internal const val SAMPLE_CYCLE = "cycle"
internal const val SAMPLE_COST = "cost"

/** One sample the medians and percentiles are taken over: its value per task (`NULL` = not in it) and the fractions read from it. */
private class RankedSample(val key: String, val value: ExpressionWithColumnType<out BigDecimal?>, val fractions: List<Double>)

/** The three samples of `estimateMdOf`/`cycleDaysOf`/`costMdOf`, with the fractions the overview shows of each. */
private fun rankedSamples(): List<RankedSample> {
    val t = TASKS
    val zero = BigDecimal.ZERO
    return listOf(
        RankedSample(SAMPLE_ESTIMATE, valueWhen(t.estimateAtDoneMd greater zero, t.estimateAtDoneMd), listOf(MEDIAN_FRACTION)),
        RankedSample(SAMPLE_CYCLE, valueWhen(t.cycleMs.isNotNull(), t.cycleWorkingDays), listOf(MEDIAN_FRACTION, CYCLE_P85_FRACTION)),
        RankedSample(SAMPLE_COST, valueWhen(t.hasWorklogs and (t.actualMd greater zero), t.actualMd), listOf(MEDIAN_FRACTION)),
    )
}

/**
 * The SQL of the 1-based place `percentileContinuous` reads for [fraction] of a sample of [nSql] values: `floor` (or, with [ceil], `ceil`)
 * of `fraction × (n − 1)` plus one. ONE template for the statement and for its test. The product is a float8 one (`::float8` on both
 * factors), like the Kotlin double product: a numeric product would be exact and could name another place when the double one lands a
 * hair under an integer.
 */
internal fun rankSql(fraction: Double, nSql: String, ceil: Boolean): String =
    "${if (ceil) "ceil" else "floor"}($fraction::float8 * ($nSql - 1)::float8)::bigint + 1"

/** `place IN (the places [fractions] read from a sample of [n] values)`, written by [rankSql]. */
private class AtRanks(private val place: Expression<*>, private val n: Expression<*>, private val fractions: List<Double>) :
    Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val nSql = QueryBuilder(false).also { it.append(n) }.toString()
        queryBuilder.append(place)
        queryBuilder.append(" IN (")
        queryBuilder.append(fractions.flatMap { listOf(rankSql(it, nSql, false), rankSql(it, nSql, true)) }.joinToString(", "))
        queryBuilder.append(")")
    }
}

/** A sample of one (team, window): [n] values, and the values at the ranks (0-based) its fractions read. */
internal class HomeDoneSample(val n: Int, val atRanks: Map<Int, Double>) {
    /** The [fraction] percentile figure (hidden under [minSample] values), over the ranks held (`percentileContinuous` itself). */
    fun figure(fraction: Double, minSample: Int): Measured = percentileFigureSorted(SparseValues(n, atRanks), fraction, minSample)

    companion object {
        val EMPTY = HomeDoneSample(0, emptyMap())
    }
}

/** The key of a sample of statement 2. */
internal data class HomeDoneKey(val team: UInt, val current: Boolean, val sample: String)

/** The columns one sample adds to statement 2's inner select. */
private class SampleColumns(
    val key: String,
    val value: ExpressionWithColumnTypeAlias<out BigDecimal?>,
    val place: ExpressionWithColumnTypeAlias<Long>,
    val n: ExpressionWithColumnTypeAlias<Long>,
    val fractions: List<Double>,
)

/**
 * Statement 2: for each (team, window) and each of the three samples, the sample's size (`COUNT(value) OVER`) and the values at the places
 * the overview's fractions read from it (`row_number() OVER (ORDER BY value NULLS LAST)`; the places are computed from that same count by
 * [rankSql]). A separate snapshot from statement 1: a DERIVE committing between the two can skew one against the other (the request is
 * still one transaction, `reports.md` "Home overview"). A sample with no values has no key (read it as [HomeDoneSample.EMPTY]).
 */
internal suspend fun readHomeDoneSamples(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    teams: Collection<UInt>,
    currentFromMs: Long,
): Map<HomeDoneKey, HomeDoneSample> {
    val t = TASKS
    val windowExpr = windowOf(currentFromMs)
    val win = windowExpr.alias("win")
    val columns = rankedSamples().map { s ->
        SampleColumns(
            key = s.key,
            value = s.value.alias("v_${s.key}"),
            place = rowNumber().over().partitionBy(t.creditTeamId, windowExpr).orderBy(s.value, SortOrder.ASC_NULLS_LAST)
                .alias("place_${s.key}"),
            n = Count(s.value).over().partitionBy(t.creditTeamId, windowExpr).alias("n_${s.key}"),
            fractions = s.fractions,
        )
    }
    val predicate = doneInWindow(filter, connectionIds, window) and (t.creditTeamId inList teams)
    val inner = t.select(listOf(t.creditTeamId, win) + columns.flatMap { listOf(it.value, it.place, it.n) }).where { predicate }
    val ranked = inner.alias("ranked")
    val atRanks = columns.map { c -> ranked[c.value].isNotNull() and AtRanks(ranked[c.place], ranked[c.n], c.fractions) }
        .reduce<Op<Boolean>, Op<Boolean>> { acc, next -> acc or next }
    val query = ranked.select(
        listOf(ranked[t.creditTeamId], ranked[win]) + columns.flatMap { listOf(ranked[it.value], ranked[it.place], ranked[it.n]) },
    ).where { atRanks }
    // Rendered once and read by position (team, win, then value/place/n per sample in [columns] order): a ResultRow lookup hashes its
    // expression by rendering it, and these CASE and window expressions cost ~30 KB a render — ~11 MiB a request for ~40 rows (WHY 38).
    val tx = TransactionManager.current()
    val found = tx.exec(query.prepareSQL(tx, true), query.arguments().single(), StatementType.SELECT) { row ->
        Array(2 + 3 * columns.size) { row.get(it) }
    }?.toList().orEmpty().filterNotNull()
    val values = HashMap<HomeDoneKey, Pair<Int, MutableMap<Int, Double>>>()
    for (row in found) {
        val team = (row[0] as Number).toLong().toUInt()
        val current = (row[1] as Number).toInt() == 1
        columns.forEachIndexed { i, c ->
            val n = (row[2 + 3 * i + 2] as Number).toInt()
            val (_, atRank) = values.getOrPut(HomeDoneKey(team, current, c.key)) { n to HashMap() }
            val value = row[2 + 3 * i] as BigDecimal? ?: return@forEachIndexed
            val place = ((row[2 + 3 * i + 1] as Number).toLong() - 1).toInt()
            // The row was kept for SOME sample's place: only this sample's own places are its values.
            if (place in percentileRanks(n, c.fractions)) atRank[place] = value.toDouble()
        }
    }
    return values.mapValues { (_, found) -> HomeDoneSample(found.first, found.second) }
}
