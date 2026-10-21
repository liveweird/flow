package ch.nokillswit.reports

import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.bucketMd
import ch.nokillswit.metrics.inBucket
import ch.nokillswit.metrics.sumMd
import kotlinx.serialization.Serializable

/**
 * One epic segment of a throughput sprint ([ThroughputSprint.byEpic], `.claude/docs/reports.md` Report 2): what the sprint delivered
 * (`done_in_sprint`, priced at done) of the items whose current epic this is (`EpicSegments.kt`: the rule, the top-[EPIC_SEGMENT_TOP]
 * fold, the order). [key] is the epic id, `OTHER_EPICS` or `NO_EPIC`; [epicId]/[epicKey] only on an epic's own segment. The segments add
 * up to the row's `deliveredMd`/`deliveredItems` exactly. The period view (`byBucket`) is not split.
 */
@Serializable
data class ThroughputEpicSegment(
    val key: String,
    val epicId: Long?,
    val epicKey: String?,
    val label: String,
    val deliveredMd: Double,
    val deliveredItems: Int,
)

/**
 * Every sprint's by-epic delivered segments, keyed `(connection, sprint)`: the LIVE `done_in_sprint` scope rows (narrowed to the picked
 * [accountIds] at USER level, by `assignee_at_commitment` like the row's own figure) split by current epic, the epics ranked over ALL the
 * response's sprints by Σ delivered MD, then epic key.
 */
internal suspend fun throughputByEpic(
    sprintRows: List<SprintRow>,
    accountIds: Collection<String>?,
): Map<Pair<UInt, Long>, List<ThroughputEpicSegment>> {
    val scoped = withCurrentEpics(
        fetchScopeEntries(sprintRows, accountIds, ScopeSlice(null, null)).filter { it.item.inBucket(SprintBucket.DELIVERED) },
    )
    val epics = rankEpics(scoped) { rows -> listOf(rows.deliveredMd().toBigDecimal()) }
    return scoped.groupBy { it.entry.connectionId to it.entry.sprintId }.mapValues { (_, rows) ->
        epics.split(rows, { it.epicId }) { id, group ->
            ThroughputEpicSegment(
                key = id.key, epicId = id.epicId, epicKey = id.epicKey, label = id.label,
                deliveredMd = group.deliveredMd(), deliveredItems = group.size,
            )
        }
    }
}

private fun List<EpicScoped>.deliveredMd(): Double = sumMd(map { it.entry.item.bucketMd(SprintBucket.DELIVERED) })
