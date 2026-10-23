package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The Home overview's WIP figures (sections 4 and 5) without loading the open items. The figure of a team is its count and, from
 * `minSampleSize` items on, three percentiles of the items' ages in working days. An age never grows with `started_at`, so the
 * ages sorted ascending are the items' `started_at` sorted descending, and `percentileContinuous` reads a handful of ranks of that
 * list. Postgres counts the items per team and returns the `started_at` at exactly those ranks; the ages are then computed for those
 * few instants only. `percentileContinuous` itself still computes the figure (over an [SparseAges] view that holds only the ranks it
 * is going to touch, found by running it once over an [IndexProbe]), so the interpolation is the one the old read used, bit for bit.
 * The old read (every open item fetched and aged: `legacyOpenTasks`/`legacyOpenEpics` + `legacyWip`) is kept in the tests as the
 * golden reference (`HomeWipOracle.kt`).
 */

/** One kind of open item as rows of a table (or join): its team column (CURRENT team for tasks, OWNER team for epics), start and scope. */
internal class OpenRows(
    val source: ColumnSet,
    val team: Column<EntityID<UInt>?>,
    val startedAt: Column<Long?>,
    val predicate: Op<Boolean>,
)

internal fun openTaskRows(filter: ReportFilter, connectionIds: List<UInt>): OpenRows {
    val t = MetricsTables.FactTaskDelivery
    return OpenRows(t, t.currentTeamId, t.startedAt, openTaskPredicate(filter, connectionIds))
}

internal fun openEpicRows(filter: ReportFilter, connectionIds: List<UInt>): OpenRows {
    val e = MetricsTables.FactEpicDelivery
    return OpenRows(openEpicSource(), e.ownerTeamId, e.startedAt, openEpicStagePredicate(filter, connectionIds))
}

/** The ages (ascending) the percentile fractions of a team of [size] read - a list that knows only those ranks. */
internal class SparseAges(override val size: Int, private val known: Map<Int, Double>) : AbstractList<Double>() {
    override fun get(index: Int): Double = known.getValue(index)
}

/** A list of [size] zeros that records which ranks were read. */
private class IndexProbe(override val size: Int) : AbstractList<Double>() {
    val touched = sortedSetOf<Int>()
    override fun get(index: Int): Double = 0.0.also { touched += index }
}

/** The ranks (0-based, ascending) `percentileContinuous` reads from a list of [size] for each of [fractions]. */
internal fun percentileRanks(size: Int, fractions: List<Double>): Set<Int> {
    val probe = IndexProbe(size)
    fractions.forEach { percentileContinuous(probe, it) }
    return probe.touched
}

/** The open items of the unit counted per team (`null` = no team), and the ages at the percentile ranks of each listed team shown. */
internal class OpenWip(private val counts: Map<UInt?, Long>, private val ages: Map<UInt, Map<Int, Double>>) {

    fun count(team: UInt): Int = (counts[team] ?: 0).toInt()

    /** Open items attributed to no team in [activeTeams] (none at all, or an inactive one). */
    fun countOutside(activeTeams: Set<UInt>): Int = counts.filterKeys { it == null || it !in activeTeams }.values.sum().toInt()

    /** The figure of [team]: hidden under [minSample] items, else the percentiles of [fractions] (in that order). */
    fun percentiles(team: UInt, minSample: Int, fractions: List<Double>): List<Double>? {
        val n = count(team)
        if (n < minSample) return null
        val sorted = SparseAges(n, ages.getValue(team))
        return fractions.map { percentileContinuous(sorted, it) }
    }

    companion object {
        val EMPTY = OpenWip(emptyMap(), emptyMap())
    }
}

/** A concurrent DERIVE can replace the fact rows between the count and the rank read; a changed count is read again. */
private const val OPEN_WIP_ATTEMPTS = 3

/** [OpenWip] of [rows] for the [teams] the overview lists: the percentiles are read for those of at least [minSample] items. */
internal suspend fun readOpenWip(
    rows: OpenRows,
    teams: Collection<UInt>,
    minSample: Int,
    fractions: List<Double>,
    ageOf: (Long) -> Double,
): OpenWip {
    repeat(OPEN_WIP_ATTEMPTS) {
        val counts = countOpenByTeam(rows)
        val wanted = teams.filter { (counts[it] ?: 0) >= minSample }
            .associateWith { percentileRanks((counts.getValue(it)).toInt(), fractions) }
        if (wanted.isEmpty()) return OpenWip(counts, emptyMap())
        val starts = startsAtRanks(rows, wanted)
        val settled = wanted.all { (team, ranks) ->
            starts[team]?.let { found -> found.size == ranks.size && found.values.all { it.first == counts[team]!!.toInt() } } == true
        }
        if (settled) return OpenWip(counts, starts.mapValues { (_, found) -> found.mapValues { ageOf(it.value.second) } })
    }
    error("the open items kept changing under the read")
}

/** `GROUP BY team`: how many open items each team (and, under `null`, no team) has. */
private suspend fun countOpenByTeam(rows: OpenRows): Map<UInt?, Long> {
    val count = rows.startedAt.count()
    return rows.source.select(rows.team, count).where { rows.predicate }.groupBy(rows.team).toList()
        .associate { it[rows.team]?.value to it[count] }
}

/**
 * For each team of [wanted], the `started_at` (with the team's item count in the same statement) at each wanted rank of the team's
 * items ordered by `started_at` DESCENDING: `rank -> (count, started_at)`.
 */
private suspend fun startsAtRanks(rows: OpenRows, wanted: Map<UInt, Set<Int>>): Map<UInt, Map<Int, Pair<Int, Long>>> {
    val total = Count(intLiteral(1)).over().partitionBy(rows.team).alias("team_items")
    val place = rowNumber().over().partitionBy(rows.team).orderBy(rows.startedAt, SortOrder.DESC).alias("place")
    val ranked = rows.source.select(rows.team, rows.startedAt, total, place)
        .where { rows.predicate and (rows.team inList wanted.keys) }.alias("ranked")
    // `place` counts from 1.
    val atRanks = wanted.map { (team, ranks) -> (ranked[rows.team] eq team) and (ranked[place] inList ranks.map { it + 1L }) }
        .reduce<Op<Boolean>, Op<Boolean>> { acc, next -> acc or next }
    val found = ranked.select(ranked[rows.team], ranked[rows.startedAt], ranked[total], ranked[place]).where { atRanks }.toList()
    return found.groupBy({ it[ranked[rows.team]]!!.value }) {
        (it[ranked[place]] - 1).toInt() to (it[ranked[total]].toInt() to it[ranked[rows.startedAt]]!!)
    }.mapValues { (_, byRank) -> byRank.toMap() }
}
