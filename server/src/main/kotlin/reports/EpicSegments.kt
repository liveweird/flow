package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.repeatedLongs
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.metrics.MetricsTables
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The "by epic" stacked-bar machinery shared by every sprint chart that splits its scope by epic (Commitments' `sprints[].byEpic`,
 * Throughput's `bySprint[].byEpic`) and by the sprint-scope drill that opens a segment. ONE rule, so a chart's segment and the drill
 * behind it name the same items:
 *  - an item counts toward its CURRENT epic, `metrics.dim_task.epic_id` -- D2's one-indirection rule (`epicIdOf`: a level-0 task's
 *    own parent epic; sub-tasks and epics never carry sprint scope, A31), no epic = "No epic" ([NO_EPIC_KEY]);
 *  - over the WHOLE response the epics are ranked by their MD (the caller's score), the first [EPIC_SEGMENT_TOP] get a segment of
 *    their own and every other epic folds into ONE [OTHER_EPICS_KEY] segment;
 *  - segments are ordered the top epics by rank, then [OTHER_EPICS_KEY], then [NO_EPIC_KEY] -- the same order in every sprint row, so a
 *    colour stays with its epic -- and a segment with no item is omitted.
 */

/** The epics a by-epic chart lists as segments of their own (Commitments, Throughput); the rest fold into "Other epics". */
internal const val EPIC_SEGMENT_TOP = 6

private const val ID_CHUNK = 10_000

/** The epic issue-id of a sprint-scope item, resolved once (`null` = no epic). */
internal data class EpicScoped(val entry: ScopeEntry, val epicId: Long?)

/**
 * One segment's identity as the wire spells it: [key] is the epic id as a string, [NO_EPIC_KEY] or [OTHER_EPICS_KEY]; [epicId]/[epicKey]
 * only on an epic's own segment; [label] is "KEY Summary" for an epic and the English fallback for the two special keys (the web
 * localises those by [key]).
 */
internal data class EpicSegmentId(val key: String, val epicId: Long?, val epicKey: String?, val label: String)

private val OTHER_SEGMENT = EpicSegmentId(OTHER_EPICS_KEY, null, null, "Other epics")
private val NO_EPIC_SEGMENT = EpicSegmentId(NO_EPIC_KEY, null, null, "No epic")

/** The ranking outcome: the top epics (rank order) with their identities. [split] cuts one sprint's items into its ordered segments. */
internal class EpicSegmentation(private val top: List<EpicSegmentId>) {
    private val topIds = top.mapNotNull { it.epicId }.toSet()
    private val order = top + OTHER_SEGMENT + NO_EPIC_SEGMENT

    /** The segment [epicId] belongs to: its own when it ranked in the top, else the folded rest; `null` = "No epic". */
    private fun keyOf(epicId: Long?): String = when {
        epicId == null -> NO_EPIC_KEY
        epicId in topIds -> epicId.toString()
        else -> OTHER_EPICS_KEY
    }

    /** [items] grouped into segments, in the one order, each built by [build]; a segment with no item is omitted. */
    fun <T, S> split(items: List<T>, epicOf: (T) -> Long?, build: (EpicSegmentId, List<T>) -> S): List<S> {
        val byKey = items.groupBy { keyOf(epicOf(it)) }
        return order.mapNotNull { id -> byKey[id.key]?.let { build(id, it) } }
    }
}

/** Every [entries] row with its current epic (`dim_task.epic_id`); one query per [ID_CHUNK] issues. */
internal suspend fun withCurrentEpics(entries: List<ScopeEntry>): List<EpicScoped> {
    if (entries.isEmpty()) return emptyList()
    val d = MetricsTables.DimTask
    val connectionIds = entries.map { it.connectionId }.distinct()
    val epics = HashMap<Pair<UInt, Long>, Long>()
    entries.map { it.item.issueId }.distinct().chunked(ID_CHUNK).forEach { chunk ->
        d.select(d.connectionId, d.issueId, d.epicId)
            .where { (d.connectionId inList connectionIds) and (d.issueId inList chunk) }
            .toList().forEach { row -> row[d.epicId]?.let { epics[row[d.connectionId].value to row[d.issueId]] = it } }
    }
    return entries.map { EpicScoped(it, epics[it.connectionId to it.item.issueId]) }
}

/**
 * Ranks the epics of [items] by [scoreOf] (their rows' scores compared lexicographically, larger first; ties by the epic's issue key, then
 * its id) and keeps the first [EPIC_SEGMENT_TOP]. Epics merge across connections by their bare id (every data source points at ONE Jira
 * site, as the cost matrix's epic table); an epic is labelled from the lowest connection id that carries it.
 */
internal suspend fun rankEpics(items: List<EpicScoped>, scoreOf: (List<EpicScoped>) -> List<BigDecimal>): EpicSegmentation {
    val byEpic = items.filter { it.epicId != null }.groupBy { it.epicId!! }
    if (byEpic.isEmpty()) return EpicSegmentation(emptyList())
    val lowest = byEpic.mapValues { (_, rows) -> rows.minOf { it.entry.connectionId } }
    val labels = workItemLabels(lowest.map { (epic, connection) -> connection to epic })
    // The ranking tie-break only: an epic with no label source (no `dim_epic`, no `norm.work_items` row) sorts by its id.
    fun keyOf(epic: Long): String = labels[lowest.getValue(epic) to epic]?.first ?: epic.toString()
    val scores = byEpic.mapValues { (_, rows) -> scoreOf(rows) }
    val ranked = byEpic.keys.sortedWith(
        Comparator<Long> { a, b -> compareScoresDescending(scores.getValue(a), scores.getValue(b)) }
            .thenComparing { a, b -> keyOf(a).compareTo(keyOf(b)) }
            .thenComparing { a, b -> a.compareTo(b) },
    )
    return EpicSegmentation(
        ranked.take(EPIC_SEGMENT_TOP).map { epic ->
            val label = labels[lowest.getValue(epic) to epic]
            // No label source = no Jira key on record: `epicKey` stays null (never the bare id) and the label falls back to the id.
            val jiraKey = label?.first
            val text = listOfNotNull(jiraKey ?: epic.toString(), label?.second?.takeIf { it.isNotBlank() }).joinToString(" ")
            EpicSegmentId(epic.toString(), epic, jiraKey, text)
        },
    )
}

private fun compareScoresDescending(a: List<BigDecimal>, b: List<BigDecimal>): Int {
    for (index in a.indices) {
        val byScore = b[index].compareTo(a[index])
        if (byScore != 0) return byScore
    }
    return 0
}

/**
 * The drill's narrowing to one epic segment (`epicSegment`): [key] is an epic id, [NO_EPIC_KEY] or [OTHER_EPICS_KEY]; for
 * [OTHER_EPICS_KEY] the chart's own top epics ([topEpicIds], the segments' `epicId`s) say which epics are NOT in the folded rest -- the
 * ranking runs over the PARENT's whole period, which the drill (one sprint) cannot recompute.
 */
internal class EpicSegmentSelector(val key: String, val topEpicIds: Set<Long>) {
    fun matches(epicId: Long?): Boolean = when (key) {
        NO_EPIC_KEY -> epicId == null
        OTHER_EPICS_KEY -> epicId != null && epicId !in topEpicIds
        else -> epicId == key.toLong()
    }
}

/**
 * `epicSegment` + `topEpicId` of the sprint-scope drill (`400` on a malformed pair): `epicSegment` is a positive epic id,
 * `NO_EPIC` or `OTHER_EPICS`; `topEpicId` (repeated, 1..[EPIC_SEGMENT_TOP] distinct positive ids, digits only -- duplicates collapse
 * before the bound) is required with `OTHER_EPICS` and refused otherwise. `null` = the whole bar.
 */
internal fun Parameters.optionalEpicSegment(): EpicSegmentSelector? {
    val key = optionalString("epicSegment")
    // Digits only, like `epicSegment`: `repeatedLongs` alone would take `+7`.
    repeatedValues("topEpicId").firstOrNull { raw -> !raw.trim().all { it in '0'..'9' } }
        ?.let { throw BadRequestException("Invalid topEpicId: $it") }
    val tops = repeatedLongs("topEpicId", EPIC_SEGMENT_TOP, minValue = 1)
    if (key == null) {
        if (tops.isNotEmpty()) throw BadRequestException("topEpicId needs epicSegment=$OTHER_EPICS_KEY")
        return null
    }
    val isEpicId = key.all { it in '0'..'9' } && key.toLongOrNull()?.let { it > 0 } == true
    if (key != NO_EPIC_KEY && key != OTHER_EPICS_KEY && !isEpicId) {
        throw BadRequestException("epicSegment must be an epic id, $NO_EPIC_KEY or $OTHER_EPICS_KEY")
    }
    if (key == OTHER_EPICS_KEY && tops.isEmpty()) {
        throw BadRequestException("epicSegment=$OTHER_EPICS_KEY needs the chart's top epics as topEpicId (1..$EPIC_SEGMENT_TOP, repeated)")
    }
    if (key != OTHER_EPICS_KEY && tops.isNotEmpty()) throw BadRequestException("topEpicId belongs to epicSegment=$OTHER_EPICS_KEY only")
    return EpicSegmentSelector(key, tops.toSet())
}
