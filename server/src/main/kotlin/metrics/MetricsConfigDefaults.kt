package ch.nokillswit.metrics

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.WorkItemStore

// The computed DEFAULT metrics configuration of a connection nothing is stored for (`MetricsConfigService.effectiveConfig`
// falls back to it, `configured = false`): the profile-detected field roles and the `norm`-derived mappings.

/** `duedate` is Jira's plain system field for an epic's due date — a valid `EPIC_DUE`/`EPIC_START` choice, never a custom field id. */
internal const val DUE_DATE_FIELD_ID = "duedate"

/** The profile-detected role `jira/JiraProfile.kt` stamps on a Story-points-shaped custom field. */
private const val STORY_POINTS_ROLE = "STORY_POINTS"

/** The name fragment a "Start date"-shaped custom field carries (case-insensitive) — the epic-start default's first choice. */
private const val START_DATE_NAME_FRAGMENT = "start date"

/**
 * Jira Plans' own start-date field name (case-insensitive) — the epic-start default's FALLBACK,
 * for a tenant using company-managed Plans dates instead of a team-managed "Start date" custom
 * field (`.claude/docs/domain-model.md` "Configuration": "'Start date' + `duedate`, or Jira Plans'
 * 'Target start'/'Target end'"). `EPIC_DUE` itself keeps its `duedate` default unconditionally —
 * `duedate` is a plain system field, always present as a CHOICE regardless of which date scheme a
 * given epic's project actually uses.
 */
private const val TARGET_START_NAME_FRAGMENT = "target start"

/** The computed defaults (v0.3.0 M1 commit 4, `.claude/docs/domain-model.md` "Configuration") — `configured = false`. */
internal suspend fun computeDefaultConfig(
    workItemStore: WorkItemStore,
    dataSources: DataSourceService,
    connectionId: UInt,
): DataSourceMetricsConfig {
    val statusStages = workItemStore.allStatusRefs(connectionId).mapNotNull { status ->
        status.category.toDefaultStage()?.let { MetricsStatusStage(status.statusId, it) }
    }
    val profile = dataSources.readProfileSections(connectionId)
    val storyPointsFieldId = profile?.customFields.orEmpty().firstOrNull { it.role == STORY_POINTS_ROLE }?.id
    val epicStartFieldId = profile?.customFields.orEmpty()
        .firstOrNull { it.name.contains(START_DATE_NAME_FRAGMENT, ignoreCase = true) }?.id
        ?: profile?.customFields.orEmpty().firstOrNull { it.name.contains(TARGET_START_NAME_FRAGMENT, ignoreCase = true) }?.id
    val domains = workItemStore.distinctProjectKeys(connectionId).sorted()
        .map { MetricsDomainMapping(projectKey = it, domainKey = it, domainName = it) }
    val activityTypes = workItemStore.distinctIssueTypes(connectionId).sorted()
        .map { MetricsActivityTypeMapping(issueType = it, activityType = it) }
    return DataSourceMetricsConfig(
        configured = false,
        statusStages = statusStages,
        fields = MetricsFieldConfig(
            estimateTask = storyPointsFieldId,
            estimateEpic = storyPointsFieldId,
            epicStart = epicStartFieldId,
            epicDue = DUE_DATE_FIELD_ID,
            workCategory = null,
        ),
        domains = domains,
        boards = emptyList(),
        activityTypes = activityTypes,
        workCategories = emptyList(),
    )
}
