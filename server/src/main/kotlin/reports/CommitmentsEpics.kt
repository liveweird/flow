package ch.nokillswit.reports

import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.bucketMd
import ch.nokillswit.metrics.inBucket
import ch.nokillswit.metrics.sumMd
import kotlinx.serialization.Serializable

/**
 * One epic segment of a commitments sprint ([CommitmentsSprint.byEpic], `.claude/docs/reports.md` Report 1): the sprint's initial and
 * final scope of the items whose current epic this is (`EpicSegments.kt` for the rule, the top-[EPIC_SEGMENT_TOP] fold and the order).
 * [key] is the epic id, `OTHER_EPICS` or `NO_EPIC`; [epicId]/[epicKey] only on an epic's own segment. The segments add up to the row's
 * `initialMd`/`initialItems`/`finalMd`/`finalItems` exactly.
 */
@Serializable
data class CommitmentsEpicSegment(
    val key: String,
    val epicId: Long?,
    val epicKey: String?,
    val label: String,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

/**
 * Every sprint's by-epic segments, keyed `(connection, sprint)`: the sprint's LIVE scope rows (`fact_sprint_scope`, the very rows
 * `fact_sprint` and the USER-level figures sum, narrowed to the picked [accountIds] at USER level) cut by the kernel's COMMITTED and
 * FINAL predicates and split by current epic. The epics are ranked over ALL the response's sprints: by Σ final MD, then Σ initial MD,
 * then epic key.
 */
internal suspend fun commitmentsByEpic(
    sprintRows: List<SprintRow>,
    accountIds: Collection<String>?,
): Map<Pair<UInt, Long>, List<CommitmentsEpicSegment>> {
    // FINAL contains COMMITTED (committed ∧ inScopeAtClose ⊂ inScopeAtClose), so a segment exists exactly when it has a final item.
    val scoped = withCurrentEpics(
        fetchScopeEntries(sprintRows, accountIds, ScopeSlice(null, null)).filter { it.item.inBucket(SprintBucket.FINAL) },
    )
    val epics = rankEpics(scoped) { rows ->
        listOf(rows.mdOf(SprintBucket.FINAL).toBigDecimal(), rows.mdOf(SprintBucket.COMMITTED).toBigDecimal())
    }
    return scoped.groupBy { it.entry.connectionId to it.entry.sprintId }.mapValues { (_, rows) ->
        epics.split(rows, { it.epicId }) { id, group ->
            CommitmentsEpicSegment(
                key = id.key, epicId = id.epicId, epicKey = id.epicKey, label = id.label,
                initialMd = group.mdOf(SprintBucket.COMMITTED),
                initialItems = group.count { it.entry.item.inBucket(SprintBucket.COMMITTED) },
                finalMd = group.mdOf(SprintBucket.FINAL),
                finalItems = group.size,
            )
        }
    }
}

/** The kernel's rounded sum ([sumMd]) of [bucket]'s estimate column over this group's members of [bucket]. */
private fun List<EpicScoped>.mdOf(bucket: SprintBucket): Double =
    sumMd(filter { it.entry.item.inBucket(bucket) }.map { it.entry.item.bucketMd(bucket) })
