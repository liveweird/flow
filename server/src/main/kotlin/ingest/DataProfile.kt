package ch.nokillswit.ingest

import ch.nokillswit.norm.StatusCategory
import kotlinx.serialization.Serializable

/** `norm.work_items`' created/updated range for a connection's live rows (v0.2.0 plan §8 "range"). */
@Serializable
data class DataProfileRange(val earliestCreatedAt: Long, val latestUpdatedAt: Long)

/** Issue counts by type, one entry per project (v0.2.0 plan §8 "projects"). */
@Serializable
data class ProjectProfile(val projectKey: String, val issueCounts: Map<String, Long>)

/** One status observed in a project×type's own status intervals (v0.2.0 plan §8 "workflows"). */
@Serializable
data class WorkflowStatusProfile(val statusId: String, val name: String, val category: StatusCategory, val transitionCount: Long)

/** Per project×type (v0.2.0 plan §8 "workflows"): statuses actually seen, plus the project's reference workflow (`PROJECT_STATUSES`). */
@Serializable
data class WorkflowProfile(
    val projectKey: String,
    val issueType: String,
    val observedStatuses: List<WorkflowStatusProfile>,
    val referenceStatusNames: List<String>,
)

@Serializable
data class BoardColumnProfile(val name: String, val statusNames: List<String>)

/** One board's columns → statuses, plus statuses observed on the board's project that map to no column (v0.2.0 plan §8 "boards"). */
@Serializable
data class BoardProfile(
    val boardId: Long,
    val name: String,
    val boardType: String,
    val projectKey: String? = null,
    val columns: List<BoardColumnProfile>,
    val unmappedStatusNames: List<String>,
)

/** One custom field's fill rate and detected role (v0.2.0 plan §8 "customFields"). */
@Serializable
data class CustomFieldProfile(
    val id: String,
    val name: String,
    val type: String,
    val nonNullCount: Long,
    val fillPercent: Double,
    val role: String,
)

/** Story-point/original-estimate coverage (v0.2.0 plan §8 "estimates"). */
@Serializable
data class EstimatesProfile(
    val totalIssues: Long = 0,
    val storyPointsCount: Long = 0,
    val storyPointsPercent: Double = 0.0,
    val originalEstimateCount: Long = 0,
    val originalEstimatePercent: Double = 0.0,
)

/** Worklog volume and coverage (v0.2.0 plan §8 "worklogs"). */
@Serializable
data class WorklogsProfile(
    val count: Long = 0,
    val totalHours: Double = 0.0,
    val itemsWithWorklog: Long = 0,
    val itemsWithWorklogPercent: Double = 0.0,
    val authorCount: Long = 0,
)

/** A DONE→non-DONE transition, per issue (v0.2.0 plan §8 "reopens") — `norm/Normalization.kt`'s `reopenCount`. */
@Serializable
data class ReopensProfile(val count: Long = 0, val totalIssues: Long = 0, val percent: Double = 0.0)

/** Sprint coverage and carry-over (v0.2.0 plan §8 "sprints") — carry-over means >=2 distinct sprint ids over an issue's lifetime. */
@Serializable
data class SprintsProfile(
    val count: Long = 0,
    val stateCounts: Map<String, Long> = emptyMap(),
    val itemsWithSprint: Long = 0,
    val itemsWithSprintPercent: Double = 0.0,
    val carryOverCount: Long = 0,
    val carryOverPercent: Double = 0.0,
)

/** Assignee coverage (v0.2.0 plan §8 "people"). */
@Serializable
data class PeopleProfile(val activeAssignees: Long = 0, val unassignedPercent: Double = 0.0)

/**
 * `GET /api/v1/data-sources/{id}/profile` (v0.2.0 plan §8/§9/§12 item 9): [computedAt] is null
 * before the connection's first PROCESS/PROFILE run — every section then carries its own empty
 * default rather than the endpoint 404ing or omitting fields. `jira/JiraProfile.kt` computes every
 * section below; the PROFILE stream (`jira/JiraProfileStream.kt`) stores the result verbatim in
 * `source_connections.profile`/`profile_at`. [workflowStatusIds] is the sorted union of status ids
 * across every in-scope project's reference workflow (`PROJECT_STATUSES`) — the metrics-config
 * Statuses tab's "relevant statuses" filter; empty on a profile stored before the field existed. [schemeFieldIds] is the
 * sorted union of field ids in the in-scope projects' field schemes (`PROJECT_FIELDS`, the optional experimental
 * `projects/fields` step) — the Fields tab's "fields of the project" filter; `null` = unknown (no project has the entity).
 */
@Serializable
data class DataProfile(
    val computedAt: Long? = null,
    val range: DataProfileRange? = null,
    val projects: List<ProjectProfile> = emptyList(),
    val workflows: List<WorkflowProfile> = emptyList(),
    val boards: List<BoardProfile> = emptyList(),
    val customFields: List<CustomFieldProfile> = emptyList(),
    val estimates: EstimatesProfile = EstimatesProfile(),
    val worklogs: WorklogsProfile = WorklogsProfile(),
    val reopens: ReopensProfile = ReopensProfile(),
    val sprints: SprintsProfile = SprintsProfile(),
    val people: PeopleProfile = PeopleProfile(),
    val anomalyCounts: Map<String, Long> = emptyMap(),
    val workflowStatusIds: List<String> = emptyList(),
    val schemeFieldIds: List<String>? = null,
)

/**
 * Everything [JiraProfile][ch.nokillswit.jira.JiraProfile] computes, MINUS [DataProfile.computedAt]
 * — the `profile` jsonb column's own shape.
 */
@Serializable
data class DataProfileSections(
    val range: DataProfileRange? = null,
    val projects: List<ProjectProfile> = emptyList(),
    val workflows: List<WorkflowProfile> = emptyList(),
    val boards: List<BoardProfile> = emptyList(),
    val customFields: List<CustomFieldProfile> = emptyList(),
    val estimates: EstimatesProfile = EstimatesProfile(),
    val worklogs: WorklogsProfile = WorklogsProfile(),
    val reopens: ReopensProfile = ReopensProfile(),
    val sprints: SprintsProfile = SprintsProfile(),
    val people: PeopleProfile = PeopleProfile(),
    val anomalyCounts: Map<String, Long> = emptyMap(),
    val workflowStatusIds: List<String> = emptyList(),
    val schemeFieldIds: List<String>? = null,
)

/** Wraps [this] with [computedAt] into the wire response — `ingest/DataProfileRoutes.kt`'s one call site. */
fun DataProfileSections.withComputedAt(computedAt: Long?): DataProfile = DataProfile(
    computedAt = computedAt,
    range = range,
    projects = projects,
    workflows = workflows,
    boards = boards,
    customFields = customFields,
    estimates = estimates,
    worklogs = worklogs,
    reopens = reopens,
    sprints = sprints,
    people = people,
    anomalyCounts = anomalyCounts,
    workflowStatusIds = workflowStatusIds,
    schemeFieldIds = schemeFieldIds,
)
