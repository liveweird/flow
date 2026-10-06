package ch.nokillswit.ingest

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/data-sources/{id}/status` (v0.2.0 plan §9/§12 item 7): a connection summary, every
 * persisted stream cursor, raw-store row counts, the most recent job of each kind, and the
 * connection's currently RUNNING job, if any. ADMIN only, no mutation — a read-only diagnostic
 * view over state every other endpoint already owns (data sources, sync jobs, cursors, the raw
 * store), assembled here rather than duplicated.
 */
@Serializable
data class SyncStatusResponse(
    val connection: DataSourceResponse,
    val cursors: List<SyncCursorSummary>,
    val counts: SyncCounts,
    /** The most recent job of each [SyncJobKind] ever requested for this connection, keyed by its name. */
    val lastJobs: Map<String, SyncJobResponse>,
    /** The connection's open job in full: RUNNING if any, else the oldest PENDING (`status.runningJobId` stays RUNNING-only). */
    val currentJob: SyncJobResponse? = null,
)

/**
 * One `sync_cursors` row, summarized: [position] is the stream's own raw persisted cursor JSON — an
 * opaque, per-stream-owned diagnostic string (`ingest/SyncCursors.kt`'s `cursor` column), not
 * reparsed here since each stream (`jira/JiraIssuesStream.kt`, `jira/JiraReferenceStream.kt`, …)
 * owns its own cursor shape.
 */
@Serializable
data class SyncCursorSummary(
    val stream: String,
    val watermarkAt: Long? = null,
    val position: String,
    val lastCompletedAt: Long? = null,
)

/** Raw/normalized-layer row counts for one connection (v0.2.0 plan §9). */
@Serializable
data class SyncCounts(
    val rawIssues: Long,
    val tombstonedDeleted: Long,
    val tombstonedMovedOut: Long,
    val changelogs: Long,
    val worklogs: Long,
    val entitiesByKind: Map<String, Long>,
    val needsProcessing: Long,
)
