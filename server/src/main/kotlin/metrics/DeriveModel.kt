package ch.nokillswit.metrics

import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The system `duedate` field id — `metrics/MetricsConfigService.kt`'s own default for `fields.epicDue`. */
private const val DUE_DATE_FIELD_ID = "duedate"

/** Per-item derived quantities shared by both the epic and task write paths — computed once per issue. */
internal data class ItemDerived(
    val stages: List<StageInterval>,
    val started: Long?,
    val done: Long?,
    val reopenCount: Int,
    val blocked: List<BlockedInterval>,
    val ownSnapshots: EstimateSnapshots,
    /** The item's OWN configured-estimate-field timeline (review round 2a) — empty when no estimate
     * field is configured for this item's role; kept alongside [ownSnapshots] (rather than discarded
     * once the snapshots are taken) so a SUBTASKS-fallback parent can merge its children's OWN
     * timelines ([mergeEstimateTimelines]) instead of reusing their CURRENT sum at
     * every past instant. */
    val estimateTimeline: List<EstimatePoint>,
    val ownCategory: String?,
)

/** The connection's configured maps [DeriveContext] needs — split out of it purely to stay under the parameter-count gate. */
internal data class ConfigMaps(
    val stageMap: Map<String, ItemStage>,
    /** Per DOMAIN key with at least one override: the every-domain [stageMap] overlaid with that domain's own rows. */
    val stageMapByDomain: Map<String, Map<String, ItemStage>>,
    val domainByProject: Map<String, String>,
    val activityTypeByIssueType: Map<String, String>,
    val workCategoryMap: Map<String, String>,
    val blockedStatusIds: Set<String>,
    val boardTeamByBoardId: Map<Long, UInt>,
    val workCategoryFieldId: String?,
    val epicDriftDays: Int,
    val epicStartFieldId: String?,
    val epicDueFieldId: String?,
)

/**
 * Every lookup [MetricsDeriver]'s per-issue derivation needs, gathered ONCE per DERIVE run — split
 * into [ConfigMaps]/plain properties purely to stay under detekt's parameter-count gate; every
 * property is exposed as a plain accessor so call sites read `context.stageMap` etc. unchanged.
 *
 * **Batch-scoped fields (review round 2b, plan §5's memory bound) are `var`, reassigned by
 * `MetricsDeriver`'s own batch loops (`runPass1`/`runPass2`) directly before each batch of up to
 * [DERIVE_BATCH_SIZE] issues is processed — NEVER loaded for the whole connection at once.** A
 * lookup for an issue OUTSIDE the currently loaded batch returns nothing; every kernel/composer call
 * site below only ever reads ITS OWN item's `issueId` from these maps (never a sibling's, a child's,
 * or a parent's — the ONE exception, worklog seconds for a task's own sub-tasks, is served instead by
 * [worklogSecondsByIssue], a small connection-wide AGGREGATE the constructor loads once), so
 * reassigning these maps between batches is safe.
 */
internal class DeriveContext(
    private val configMaps: ConfigMaps,
    val itemsById: Map<Long, WorkItemStore.DerivationWorkItemRow>,
    val sprintBoardById: Map<Long, Long?>,
    /** Whether a sprint counts as CLOSED for A22's "current sprint excludes closed sprints" rule —
     * `state == "closed"` OR `completeAtMs` at or before the DERIVE run's own clock; a sprint absent
     * here (never fetched) is treated as not-closed by every reader ([Map.get] returning `null`). */
    val sprintClosedById: Map<Long, Boolean>,
    val membershipsByAccount: Map<String, List<TeamMembershipService.MembershipInterval>>,
    /** Every issue's summed worklog seconds, connection-wide (review round 2b) — a small aggregate
     * `Map<Long, Long>`, never the full per-worklog row shape; see [WorkItemStore.worklogSecondsByIssue]. */
    val worklogSecondsByIssue: Map<Long, Long>,
    /** A19/A22: each DOMAIN key's owner team — `domain_map.owner_team_id` if EVERY configured project
     * row of the domain agrees (ignoring unconfigured rows), else the ONE `board_team_map` board
     * mapped across ALL of the domain's project keys; absent (never a `null` value) when neither
     * resolves. Both sources are filtered to currently ACTIVE teams first (A22: a soft-deleted team
     * resolves as if unconfigured/unmapped) — see [MetricsDeriver.ownerTeamByDomain]'s own doc. */
    val ownerTeamByDomain: Map<String, UInt>,
    /** A22: team ids that are currently ACTIVE (not soft-deleted) — every NOW-evaluated team column
     * (`current_team_id`, `ownerTeamByDomain`) is filtered through this; AS-WAS columns
     * (`credit_team_id`, `author_team_id`, sprint team at done/started) keep the historical team
     * regardless, per A22's own split. */
    val activeTeamIds: Set<UInt>,
    val calendar: WorkingCalendar,
    val now: Long,
    val hoursPerDay: Double,
) {
    var statusIntervalsByIssue: Map<Long, List<NormalizedStatusInterval>> = emptyMap()
    var flaggedIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var estimateChangesByIssueAndField: Map<Long, List<FieldChangeRow>> = emptyMap()
    var assigneeIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var sprintIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var parentIntervalsByIssue: Map<Long, List<NormalizedFieldInterval>> = emptyMap()
    var issueKeyChangesByIssue: Map<Long, List<FieldChangeRow>> = emptyMap()

    val stageMap get() = configMaps.stageMap

    /** The status → stage map for items of [domainKey]: its overrides over the every-domain rows, else the every-domain map itself. */
    fun stageMapFor(domainKey: String): Map<String, ItemStage> = configMaps.stageMapByDomain[domainKey] ?: stageMap

    val domainByProject get() = configMaps.domainByProject
    val activityTypeByIssueType get() = configMaps.activityTypeByIssueType
    val workCategoryMap get() = configMaps.workCategoryMap
    val blockedStatusIds get() = configMaps.blockedStatusIds
    val boardTeamByBoardId get() = configMaps.boardTeamByBoardId
    val workCategoryFieldId get() = configMaps.workCategoryFieldId
    val epicDriftDays get() = configMaps.epicDriftDays
    val epicStartFieldId get() = configMaps.epicStartFieldId
    val epicDueFieldId get() = configMaps.epicDueFieldId

    /** The epic start/due DATE fields' current value for [item] — `duedate` reads the already-parsed
     * system column (`WorkItemStore.DerivationWorkItemRow.dueAt`); any other configured field id is a
     * plain ISO `YYYY-MM-DD` string in `custom_fields`, parsed to epoch millis at start of day UTC —
     * the SAME convention `jira/JiraNormalizer.kt` already applies to the system `duedate` field. */
    fun epicDateValue(item: WorkItemStore.DerivationWorkItemRow, fieldId: String?): Long? {
        if (fieldId == null) return null
        if (fieldId == DUE_DATE_FIELD_ID) return item.dueAt
        val raw = item.customFields[fieldId]?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** The configured estimate field id for [item] — epics may override tasks' own field. */
    fun estimateFieldIdFor(item: WorkItemStore.DerivationWorkItemRow, config: DataSourceMetricsConfig): String? =
        if (isEpicItem(item)) {
            config.fields.estimateEpic ?: config.fields.estimateTask
        } else {
            config.fields.estimateTask
        }
}

/** Whether [item] is an epic (hierarchy level 1, `norm/Hierarchy.kt`) — a missing item is none. */
internal fun isEpicItem(item: WorkItemStore.DerivationWorkItemRow?): Boolean =
    item != null && hierarchyBucket(item.hierarchyLevel) == HierarchyBucket.EPIC
