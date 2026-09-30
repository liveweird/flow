package ch.nokillswit.norm

import kotlinx.serialization.Serializable

/**
 * The connector-agnostic PROCESS glue (v0.2.0 plan §8): combines [Tiling]'s pure interval math with
 * a per-issue set of already-Jira-parsed facts (`jira/JiraNormalizer.kt` is the one producer today)
 * into the shape `norm/WorkItemStore.kt` writes. Nothing here is Jira-specific — a future GitLab
 * connector would produce its own [IssueNormalizationInput] and reuse everything below unchanged.
 */

/**
 * Bump on ANY change to the tiling/write-shape rules below — `raw.jira_issues.processing_version
 * IS DISTINCT FROM` this reprocesses every issue automatically (plan §8).
 *
 * `2` (v0.3.0 M1 commit 2, V14, `.claude/docs/domain-model.md` "Gaps in `norm`"): PARENT
 * tiling, every `customfield_*` current value captured into `custom_fields`, `hierarchyLevel`/
 * `dueAt` current values, `field_id` on every `work_item_field_changes` row, worklog
 * `created`/`updated` timestamps, sprint `completeDate` — every existing issue reprocesses
 * automatically on the next PROCESS pass.
 */
const val PROCESSING_VERSION = 2

/** Mirrors `raw.jira_issues`' own tombstone columns onto `norm.work_items` (plan §8: "keep the row, flagged", never deleted). */
enum class TombstoneKind { NONE, DELETED, MOVED_OUT }

/** The CURRENT-snapshot columns `norm.work_items` stores — everything else is derived from the interval/change lists below. */
data class WorkItemFacts(
    val issueKey: String,
    val projectKey: String,
    val issueType: String,
    val isSubtask: Boolean,
    val parentIssueId: Long?,
    val summary: String?,
    val currentStatusId: String,
    val resolution: String?,
    val priority: String?,
    val assigneeAccountId: String?,
    val reporterAccountId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val resolvedAtMs: Long?,
    val storyPoints: Double?,
    val originalEstimateSeconds: Long?,
    val timeSpentSeconds: Long,
    val labels: List<String>,
    val components: List<String>,
    val fixVersions: List<String>,
    /** The Atlassian Team field, stored as-is (plan §4) — raw JSON text, or null if unset. */
    val teamValueJson: String?,
    val rank: String?,
    /** An epic is level 1 (from the REFERENCE stream's `ISSUE_TYPE` entities — never "type name = Epic", v0.3.0 M1 commit 2). */
    val hierarchyLevel: Int? = null,
    /** The system `duedate` field's current value, epoch millis at start of day UTC (v0.3.0 M1 commit 2). */
    val dueAtMs: Long? = null,
    /** Every FILLED `customfield_*` current value, canonicalized, keyed by field id (v0.3.0 M1 commit 2) — never filtered by which. */
    val customFieldsJson: String = "{}",
    val tombstone: TombstoneKind,
    /**
     * `raw.jira_issues.deleted_at`/`moved_out_at` VERBATIM — the moment RECONCILE first detected
     * this tombstone (v0.3.0 M1 commit 2 review fix), never the CURRENT PROCESS run's own clock: a
     * `PROCESSING_VERSION` bump reprocesses every already-tombstoned issue, and `now` would reset
     * every one of them to the reprocess/deploy time. Meaningless when [tombstone] is `NONE`.
     */
    val tombstoneAtMs: Long? = null,
)

/** One `norm.work_item_field_changes` row — every tracked changelog item, kept verbatim (plan §4). */
data class FieldChangeFact(
    val field: String,
    val atMs: Long,
    val fromValue: String?,
    val fromText: String?,
    val toValue: String?,
    val toText: String?,
    // The changelog item's own `fieldId` (v0.3.0 M1 commit 2) — the metrics layer's per-field
    // replay key; `field` is a display name only.
    val fieldId: String? = null,
)

data class WorklogFact(
    val worklogId: Long,
    val authorAccountId: String?,
    val startedAtMs: Long,
    val timeSpentSeconds: Long,
    // When the worklog was actually entered/last edited (v0.3.0 M1 commit 2, report 14's
    // late-logging measure) — `null` when Jira omits them, NEVER falling back to
    // [startedAtMs]/[createdAtMs]: a missing timestamp is missing data, not "no skew".
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null,
)

/** The (id, displayText) pair a field's CURRENT value resolves to — null/null means "no value" (unassigned, unflagged). */
data class CurrentFieldValue(val id: String?, val text: String?)

/**
 * Everything one issue's PROCESS pass needs (plan §8) — assembled by `jira/JiraNormalizer.kt` from
 * `raw.jira_issues`/`raw.jira_changelogs`/`raw.jira_worklogs`.
 */
data class IssueNormalizationInput(
    val issueId: Long,
    val facts: WorkItemFacts,
    val statusEvents: List<StatusChangeEvent>,
    val assigneeEvents: List<FieldChangeEvent>,
    val sprintEvents: List<FieldChangeEvent>,
    val flaggedEvents: List<FieldChangeEvent>,
    val currentAssignee: CurrentFieldValue,
    val currentSprintIds: List<Long>,
    val currentSprintText: String?,
    val currentFlagged: Boolean,
    val fieldChanges: List<FieldChangeFact>,
    val worklogs: List<WorklogFact>,
    /** PARENT tiling input (v0.3.0 M1 commit 2) — parent-changing changelog items, oldest first. */
    val parentEvents: List<FieldChangeEvent> = emptyList(),
    val currentParent: CurrentFieldValue = CurrentFieldValue(null, null),
)

/** Serializable: the raw issue inspector's response shape (v0.2.0 plan §9/§12 item 8b) returns these verbatim. */
@Serializable
data class NormalizedStatusInterval(
    val seq: Int,
    val statusId: String,
    val statusName: String,
    val category: StatusCategory,
    val fromAtMs: Long,
    val toAtMs: Long?,
    val source: IntervalSource,
)

@Serializable
data class NormalizedFieldInterval(
    val field: TrackedField,
    val seq: Int,
    val valueId: String?,
    val valueText: String?,
    val fromAtMs: Long,
    val toAtMs: Long?,
)

/** One issue's full write-shape (plan §8 step 5: "delete the child rows, insert the new ones, upsert work_items"). */
data class NormalizedIssue(
    val issueId: Long,
    val facts: WorkItemFacts,
    val currentStatusName: String,
    val currentStatusCategory: StatusCategory,
    val statusIntervals: List<NormalizedStatusInterval>,
    val fieldIntervals: List<NormalizedFieldInterval>,
    val fieldChanges: List<FieldChangeFact>,
    val worklogs: List<WorklogFact>,
    val currentSprintIds: List<Long>,
    val flagged: Boolean,
    val anomalies: List<TilingAnomaly>,
)

object Normalization {
    /**
     * Tiles [input] into a full [NormalizedIssue] (plan §8 steps 2-4). [statusLookup] resolves a
     * status id to its (name, category) — `norm.statuses`' rebuilt reference rows, read once per
     * PROCESS run, never per issue.
     */
    fun normalize(input: IssueNormalizationInput, statusLookup: (statusId: String) -> Pair<String, StatusCategory>): NormalizedIssue {
        val createdAtMs = input.facts.createdAtMs
        val statusResult = Tiling.statusIntervals(createdAtMs, input.facts.currentStatusId, input.statusEvents)
        val statusIntervals = statusResult.intervals.map { interval ->
            val (name, category) = statusLookup(interval.statusId)
            NormalizedStatusInterval(interval.seq, interval.statusId, name, category, interval.fromAtMs, interval.toAtMs, interval.source)
        }
        val (currentStatusName, currentStatusCategory) = statusLookup(input.facts.currentStatusId)

        val assigneeIntervals = Tiling.fieldIntervals(
            createdAtMs, input.currentAssignee.id, input.currentAssignee.text, input.assigneeEvents,
        ).map { NormalizedFieldInterval(TrackedField.ASSIGNEE, it.seq, it.valueId, it.valueText, it.fromAtMs, it.toAtMs) }
        val sprintIntervals = Tiling.fieldIntervals(
            createdAtMs, input.currentSprintIds.lastOrNull()?.toString(), input.currentSprintText, input.sprintEvents,
        ).map { NormalizedFieldInterval(TrackedField.SPRINT, it.seq, it.valueId, it.valueText, it.fromAtMs, it.toAtMs) }
        val flaggedIntervals = Tiling.fieldIntervals(createdAtMs, input.currentFlagged.toString(), null, input.flaggedEvents)
            .map { NormalizedFieldInterval(TrackedField.FLAGGED, it.seq, it.valueId, it.valueText, it.fromAtMs, it.toAtMs) }
        // PARENT tiles the same way ASSIGNEE does (v0.3.0 M1 commit 2): the first interval seeds from
        // the first parent-change event's `from` value, else the current parent — Tiling's own
        // construction rule needs no special-casing here.
        val parentIntervals = Tiling.fieldIntervals(
            createdAtMs, input.currentParent.id, input.currentParent.text, input.parentEvents,
        ).map { NormalizedFieldInterval(TrackedField.PARENT, it.seq, it.valueId, it.valueText, it.fromAtMs, it.toAtMs) }

        return NormalizedIssue(
            issueId = input.issueId,
            facts = input.facts,
            currentStatusName = currentStatusName,
            currentStatusCategory = currentStatusCategory,
            statusIntervals = statusIntervals,
            fieldIntervals = assigneeIntervals + sprintIntervals + flaggedIntervals + parentIntervals,
            fieldChanges = input.fieldChanges,
            worklogs = input.worklogs,
            currentSprintIds = input.currentSprintIds,
            flagged = input.currentFlagged,
            anomalies = statusResult.anomalies,
        )
    }

    /**
     * A reopen (plan §8 data-profile "reopens: DONE→non-DONE"): a status interval whose category is
     * DONE immediately followed by one that isn't. Pure and reusable by both the pipeline test's own
     * assertion and a future data-profile computation (plan commit 9).
     */
    fun reopenCount(statusIntervals: List<NormalizedStatusInterval>): Int =
        statusIntervals.zipWithNext().count { (a, b) -> a.category == StatusCategory.DONE && b.category != StatusCategory.DONE }
}
