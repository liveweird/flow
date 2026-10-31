package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The MR DETAILS backlog (the V43 partial index `idx_raw_gitlab_mrs_details_backlog` plus the project): live, details never read or read
 * before the last change, and of a LIVE project (`moved_out_at`/`deleted_at` null; the `FILES_BACKLOG` shape - a moved-out project's merge
 * requests cost no call and are not counted as pending). The index's own predicate is reproduced term for term and carries no bound
 * value: `IS NULL` tests and one column-to-column comparison, so nothing is rendered as a bind parameter that a cached generic plan could
 * not match against the index (the V42 lesson, `NON_MERGE_PARENTS`). The project is an `EXISTS` on its primary key.
 */
internal val MR_DETAILS_BACKLOG: Op<Boolean> = MergeRequests.deletedAt.isNull() and
    (MergeRequests.detailsSyncedAt.isNull() or (MergeRequests.detailsSyncedAt less MergeRequests.changedAt)) and liveProjectOfMr()

/** The merge request's project is LIVE (not moved out, not deleted): an `EXISTS` on the project's primary key, [extra] ANDed inside it. */
internal fun liveProjectOfMr(extra: Op<Boolean> = Op.TRUE): Op<Boolean> = exists(
    Projects.selectAll().where {
        (Projects.connectionId eq MergeRequests.connectionId) and (Projects.projectId eq MergeRequests.projectId) and
            Projects.movedOutAt.isNull() and Projects.deletedAt.isNull() and extra
    },
)

/**
 * A SETTLED merge request with no diff stats: live, stamped at or after its last change, no `details_error`, `additions IS NULL`. A
 * changed row is pending instead, and a pending row's stats are not yet asked again. The status figure `mrDiffStatsMissing` and
 * RECONCILE's stats re-ask ([GitLabMrReads.gappedMergeRequests]) are this one predicate.
 */
private val DIFF_STATS_GAP: Op<Boolean> = MergeRequests.deletedAt.isNull() and MergeRequests.detailsSyncedAt.isNotNull() and
    (MergeRequests.detailsSyncedAt greaterEq MergeRequests.changedAt) and MergeRequests.detailsError.isNull() and
    MergeRequests.additions.isNull()

/** The merge-request reads (V43): the details backlog, the status figures, the group lookups and the rows tests inspect. */
internal class GitLabMrReads(private val database: R2dbcDatabase) {

    private fun pending(connectionId: UInt): Op<Boolean> = (MergeRequests.connectionId eq connectionId) and MR_DETAILS_BACKLOG

    suspend fun staleMergeRequests(connectionId: UInt, limit: Int): List<StaleMergeRequest> = suspendTransaction(database) {
        MergeRequests.select(MergeRequests.mrId, MergeRequests.projectId, MergeRequests.iid).where { pending(connectionId) }
            .orderBy(MergeRequests.updatedAt to SortOrder.DESC, MergeRequests.mrId to SortOrder.ASC)
            .limit(limit)
            .toList()
            .map { StaleMergeRequest(it[MergeRequests.mrId], it[MergeRequests.projectId], it[MergeRequests.iid]) }
    }

    suspend fun countStaleMergeRequests(connectionId: UInt): Long = suspendTransaction(database) {
        MergeRequests.selectAll().where { pending(connectionId) }.count()
    }

    /**
     * The seven figures as database-side `count(*)`s: no row is loaded. `mrDiffStatsMissing` counts the gaps of settled rows only
     * ([DIFF_STATS_GAP]); `mergeRequestsDeleted` the tombstoned rows and `mrDetailsUnread` the LIVE rows carrying any `details_error`.
     */
    suspend fun mrCounts(connectionId: UInt): MrCounts = suspendTransaction(database) {
        suspend fun mrs(extra: Op<Boolean>) =
            MergeRequests.selectAll().where { (MergeRequests.connectionId eq connectionId) and extra }.count()
        val live = MergeRequests.deletedAt.isNull()
        MrCounts(
            mergeRequests = mrs(live),
            mergeRequestsMerged = mrs(live and (MergeRequests.state eq "merged")),
            mrEvents = MrEvents.selectAll().where { MrEvents.connectionId eq connectionId }.count(),
            mrDetailsPending = mrs(MR_DETAILS_BACKLOG),
            mrDiffStatsMissing = mrs(DIFF_STATS_GAP),
            mergeRequestsDeleted = mrs(MergeRequests.deletedAt.isNotNull()),
            mrDetailsUnread = mrs(live and MergeRequests.detailsError.isNotNull()),
        )
    }

    /**
     * RECONCILE's merge-request candidates, newest `updated_at` first (ties: `mr_id`): live rows of a LIVE project in one of [groupPaths]
     * (the groups this pass listed to the end) with `updated_at >= floorMillis` that no page of the pass and no probe has seen since
     * [passStartedAt]. "Unlisted" is only a candidate: the caller confirms each against GitLab before it tombstones one. An empty group
     * set asks nothing.
     */
    suspend fun mrReconcileCandidates(
        connectionId: UInt,
        floorMillis: Long,
        passStartedAt: Long,
        groupPaths: Collection<String>,
        limit: Int,
    ): List<StaleMergeRequest> {
        if (groupPaths.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            MergeRequests.select(MergeRequests.mrId, MergeRequests.projectId, MergeRequests.iid)
                .where {
                    (MergeRequests.connectionId eq connectionId) and MergeRequests.deletedAt.isNull() and
                        (MergeRequests.updatedAt greaterEq floorMillis) and (MergeRequests.lastSeenAt less passStartedAt) and
                        liveProjectOfMr(Projects.groupPath inList groupPaths.distinct())
                }
                .orderBy(MergeRequests.updatedAt to SortOrder.DESC, MergeRequests.mrId to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { StaleMergeRequest(it[MergeRequests.mrId], it[MergeRequests.projectId], it[MergeRequests.iid]) }
        }
    }

    /** The settled merge requests with no diff stats ([DIFF_STATS_GAP]) of LIVE projects, newest first: RECONCILE's stats re-ask. */
    suspend fun gappedMergeRequests(connectionId: UInt, limit: Int): List<StaleMergeRequest> = suspendTransaction(database) {
        MergeRequests.select(MergeRequests.mrId, MergeRequests.projectId, MergeRequests.iid)
            .where { (MergeRequests.connectionId eq connectionId) and DIFF_STATS_GAP and liveProjectOfMr() }
            .orderBy(MergeRequests.updatedAt to SortOrder.DESC, MergeRequests.mrId to SortOrder.ASC)
            .limit(limit)
            .toList()
            .map { StaleMergeRequest(it[MergeRequests.mrId], it[MergeRequests.projectId], it[MergeRequests.iid]) }
    }

    /**
     * `group_path` to `group_id` of the LIVE stored projects (not moved out, not deleted): PROJECTS stamps the group a project was listed
     * under, so a path whose group was deleted and re-created carries two ids across the rows - the one the most recently seen row
     * carries wins (ties: the greater id, the newer group). A path with no live project is absent (nothing to list for it).
     */
    suspend fun configuredGroupIds(connectionId: UInt): Map<String, Long> = suspendTransaction(database) {
        Projects.select(Projects.groupPath, Projects.groupId, Projects.lastSeenAt)
            .where { (Projects.connectionId eq connectionId) and Projects.movedOutAt.isNull() and Projects.deletedAt.isNull() }
            .toList()
            .groupBy { it[Projects.groupPath] }
            .mapValues { (_, rows) -> rows.maxWith(compareBy({ it[Projects.lastSeenAt] }, { it[Projects.groupId] }))[Projects.groupId] }
    }

    /** The ids of the LIVE stored projects (not moved out, not deleted) per `group_path`: a group's current set. */
    suspend fun liveProjectIdsByGroup(connectionId: UInt): Map<String, List<Long>> = suspendTransaction(database) {
        Projects.select(Projects.groupPath, Projects.projectId)
            .where { (Projects.connectionId eq connectionId) and Projects.movedOutAt.isNull() and Projects.deletedAt.isNull() }
            .toList()
            .groupBy({ it[Projects.groupPath] }, { it[Projects.projectId] })
            .mapValues { (_, ids) -> ids.sorted() }
    }

    suspend fun storedProjectIds(connectionId: UInt): Set<Long> = suspendTransaction(database) {
        Projects.select(Projects.projectId).where { Projects.connectionId eq connectionId }.toList()
            .mapTo(HashSet()) { it[Projects.projectId] }
    }

    suspend fun projectPaths(connectionId: UInt, projectIds: Collection<Long>): Map<Long, String> {
        if (projectIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            projectIds.distinct().sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                Projects.select(Projects.projectId, Projects.pathWithNamespace)
                    .where { (Projects.connectionId eq connectionId) and (Projects.projectId inList chunk) }
                    .toList()
            }.associate { it[Projects.projectId] to it[Projects.pathWithNamespace] }
        }
    }

    suspend fun mergeRequestRows(connectionId: UInt, projectId: Long?): List<ResultRow> = suspendTransaction(database) {
        MergeRequests.selectAll()
            .where { (MergeRequests.connectionId eq connectionId) and (projectId?.let { MergeRequests.projectId eq it } ?: Op.TRUE) }
            .orderBy(MergeRequests.projectId to SortOrder.ASC, MergeRequests.iid to SortOrder.ASC)
            .toList()
    }

    suspend fun mrEventRows(connectionId: UInt, mrId: Long?): List<ResultRow> = suspendTransaction(database) {
        MrEvents.selectAll()
            .where { (MrEvents.connectionId eq connectionId) and (mrId?.let { MrEvents.mrId eq it } ?: Op.TRUE) }
            .orderBy(MrEvents.mrId to SortOrder.ASC, MrEvents.eventSource to SortOrder.ASC, MrEvents.sourceId to SortOrder.ASC)
            .toList()
    }
}
