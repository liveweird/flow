package ch.nokillswit.ingest

import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TilingAnomaly
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** All-digits — looked up by the stable Jira id; anything else must match this before a key lookup (v0.2.0 plan §9/§12 item 8b). */
val ISSUE_ID_PATTERN: Regex = Regex("^[0-9]+$")

/** The Jira Cloud issue-key shape a raw issue inspector lookup accepts (v0.2.0 plan §9/§12 item 8b). */
val ISSUE_KEY_PATTERN: Regex = Regex("^[A-Z][A-Z0-9_]{1,9}-[0-9]{1,10}$")

/** `norm.work_items`' current snapshot for one issue, if it has ever been processed (v0.2.0 plan §9/§12 item 8b). */
@Serializable
data class RawIssueWorkItem(
    val issueKey: String,
    val projectKey: String,
    val issueType: String,
    val statusId: String,
    val statusName: String,
    val statusCategory: StatusCategory,
    val assigneeAccountId: String? = null,
    /** An epic is level 1, from the REFERENCE stream's `ISSUE_TYPE` entities (v0.3.0 M1 commit 2). */
    val hierarchyLevel: Int? = null,
    /** The system `duedate` field's current value, epoch millis at start of day UTC (v0.3.0 M1 commit 2). */
    val dueAt: Long? = null,
    /** Every non-null `customfield_*` current value, keyed by field id (v0.3.0 M1 commit 2) — never filtered. */
    val customFields: JsonObject = JsonObject(emptyMap()),
    val processedAt: Long,
    val processingVersion: Int,
    val deletedAt: Long? = null,
    val movedOutAt: Long? = null,
)

/**
 * `GET /api/v1/data-sources/{id}/raw-issues/{issueKey}` (v0.2.0 plan §9/§12 item 8b, ADMIN only):
 * one Jira issue's raw store row, its changelog/worklog history and — if it has been through
 * PROCESS at least once — its normalized `norm.*` shape. [payload]/[changelogs]/[worklogs] are the
 * exact JSON documents Jira returned (canonicalized), never reparsed into a Jira-specific shape; on the SERVER they are the stored
 * text embedded as an unquoted literal (`storedJson`), never parsed, so the `web` role builds no tree of a document (the wire shape is
 * the objects they always were).
 */
@Serializable
data class RawIssueInspection(
    val issueId: Long,
    val issueKey: String,
    val fetchedAt: Long,
    val changedAt: Long,
    val sha256: String,
    val deletedAt: Long? = null,
    val movedOutAt: Long? = null,
    val needsProcessing: Boolean,
    val payload: JsonElement,
    val changelogs: List<JsonElement> = emptyList(),
    val worklogs: List<JsonElement> = emptyList(),
    val workItem: RawIssueWorkItem? = null,
    val statusIntervals: List<NormalizedStatusInterval> = emptyList(),
    val fieldIntervals: List<NormalizedFieldInterval> = emptyList(),
    val anomalies: List<TilingAnomaly> = emptyList(),
)
