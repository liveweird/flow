package ch.nokillswit.jira

import ch.nokillswit.ingest.BoardColumnProfile
import ch.nokillswit.ingest.BoardProfile
import ch.nokillswit.ingest.CustomFieldProfile
import ch.nokillswit.ingest.DataProfileRange
import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.EstimatesProfile
import ch.nokillswit.ingest.PeopleProfile
import ch.nokillswit.ingest.ProjectProfile
import ch.nokillswit.ingest.ReopensProfile
import ch.nokillswit.ingest.SprintsProfile
import ch.nokillswit.ingest.WorkflowProfile
import ch.nokillswit.ingest.WorkflowStatusProfile
import ch.nokillswit.ingest.WorklogsProfile
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.Normalization
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val PROFILE_JSON = Json { ignoreUnknownKeys = true }

/**
 * The Jira data profile (v0.2.0 plan §8 "Data profile sections"/§12 item 9): SQL aggregates over
 * `raw.*`/`norm.*`, one pass over each connection's LIVE (non-tombstoned) `norm.work_items` plus
 * the reference rows PROCESS already rebuilt (`norm.statuses`/`boards`/`board_columns`/`sprints`)
 * and the raw `PROJECT_STATUSES`/`FIELD` entities REFERENCE stores. Pure aggregation — every input
 * is already-normalized Jira data, nothing here makes an outbound call.
 */
object JiraProfile {

    suspend fun compute(connectionId: UInt, rawStore: JiraRawStore, workItems: WorkItemStore): DataProfileSections {
        val items = workItems.profileWorkItems(connectionId)
        val liveIssueIds = items.map { it.issueId }.toSet()
        val intervalsByIssue = workItems.statusIntervalsByIssue(connectionId).filterKeys { it in liveIssueIds }
        val statusRefs = workItems.allStatusRefs(connectionId).associateBy { it.statusId }
        val boardRefs = workItems.allBoardRefs(connectionId)
        val sprintRefs = workItems.allSprintRefs(connectionId)
        val worklogRows = workItems.worklogRows(connectionId).filter { it.issueId in liveIssueIds }
        val sprintFieldValues = workItems.fieldIntervalValues(connectionId, TrackedField.SPRINT)
            .filter { (issueId, valueId) -> issueId in liveIssueIds && valueId != null }
        val referenceStatusesByProject = rawStore.entityRowsByKind(connectionId, JiraEntityKind.PROJECT_STATUSES.name)
            .associate { (projectKey, payload) -> projectKey to parseProjectStatuses(payload) }
        val fieldPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.FIELD.name)
        val issueFieldsPayloads = rawStore.issuePayloads(connectionId)

        return DataProfileSections(
            range = computeRange(items),
            projects = computeProjects(items),
            workflows = computeWorkflows(items, intervalsByIssue, referenceStatusesByProject),
            boards = computeBoards(boardRefs, items, intervalsByIssue, statusRefs),
            customFields = computeCustomFields(fieldPayloads, issueFieldsPayloads),
            estimates = computeEstimates(items),
            worklogs = computeWorklogs(worklogRows, items.size.toLong()),
            reopens = computeReopens(items, intervalsByIssue),
            sprints = computeSprints(sprintRefs, sprintFieldValues, items.size.toLong()),
            people = computePeople(items),
            anomalyCounts = items.flatMap { it.anomalies }.groupingBy { it.name }.eachCount().mapValues { it.value.toLong() },
        )
    }

    private fun pct(count: Long, total: Long): Double = if (total <= 0) 0.0 else count.toDouble() * 100.0 / total

    private fun computeRange(items: List<WorkItemStore.ProfileWorkItemRow>): DataProfileRange? =
        if (items.isEmpty()) null else DataProfileRange(items.minOf { it.createdAt }, items.maxOf { it.updatedAt })

    private fun computeProjects(items: List<WorkItemStore.ProfileWorkItemRow>): List<ProjectProfile> =
        items.groupBy { it.projectKey }
            .map { (projectKey, rows) ->
                val issueCounts = rows.groupingBy { it.issueType }.eachCount().mapValues { it.value.toLong() }
                ProjectProfile(projectKey, issueCounts)
            }
            .sortedBy { it.projectKey }

    private fun computeWorkflows(
        items: List<WorkItemStore.ProfileWorkItemRow>,
        intervalsByIssue: Map<Long, List<NormalizedStatusInterval>>,
        referenceStatusesByProject: Map<String, Map<String, List<String>>>,
    ): List<WorkflowProfile> = items.groupBy { it.projectKey to it.issueType }.map { (key, rows) ->
        val (projectKey, issueType) = key
        val issueIds = rows.map { it.issueId }.toSet()
        val statusNames = mutableMapOf<String, String>()
        val statusCategories = mutableMapOf<String, StatusCategory>()
        val transitionCounts = mutableMapOf<String, Long>()
        issueIds.forEach { issueId ->
            intervalsByIssue[issueId].orEmpty().forEach { interval ->
                statusNames[interval.statusId] = interval.statusName
                statusCategories[interval.statusId] = interval.category
                if (interval.source == IntervalSource.CHANGE) {
                    transitionCounts[interval.statusId] = (transitionCounts[interval.statusId] ?: 0L) + 1
                }
            }
        }
        val observed = statusNames.keys.sorted().map { statusId ->
            WorkflowStatusProfile(
                statusId = statusId,
                name = statusNames.getValue(statusId),
                category = statusCategories.getValue(statusId),
                transitionCount = transitionCounts[statusId] ?: 0L,
            )
        }
        WorkflowProfile(projectKey, issueType, observed, referenceStatusesByProject[projectKey]?.get(issueType).orEmpty())
    }.sortedWith(compareBy({ it.projectKey }, { it.issueType }))

    private fun computeBoards(
        boardRefs: List<BoardRef>,
        items: List<WorkItemStore.ProfileWorkItemRow>,
        intervalsByIssue: Map<Long, List<NormalizedStatusInterval>>,
        statusRefsById: Map<String, ch.nokillswit.norm.StatusRef>,
    ): List<BoardProfile> {
        val issueIdsByProject = items.groupBy({ it.projectKey }) { it.issueId }
        return boardRefs.map { board ->
            val mappedStatusIds = board.columns.flatMap { it.statusIds }.toSet()
            val projectIssueIds = issueIdsByProject[board.projectKey].orEmpty()
            val observed = projectIssueIds.flatMap { intervalsByIssue[it].orEmpty() }
                .associate { it.statusId to it.statusName }
            val unmapped = observed.filterKeys { it !in mappedStatusIds }.values.distinct().sorted()
            BoardProfile(
                boardId = board.boardId,
                name = board.name,
                boardType = board.boardType,
                projectKey = board.projectKey,
                columns = board.columns.map { column ->
                    BoardColumnProfile(column.name, column.statusIds.map { statusRefsById[it]?.name ?: it })
                },
                unmappedStatusNames = unmapped,
            )
        }.sortedBy { it.boardId }
    }

    /**
     * Whether Jira's `fields[fieldId]` counts as "set" for the custom-field fill-rate — an empty
     * array (Sprint/Flagged) means "no value".
     */
    private fun hasCustomFieldValue(fields: kotlinx.serialization.json.JsonObject, fieldId: String): Boolean {
        val element = fields[fieldId] ?: return false
        if (element is kotlinx.serialization.json.JsonNull) return false
        return (element as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() ?: true
    }

    private fun computeCustomFields(fieldPayloads: List<String>, issuePayloads: List<String>): List<CustomFieldProfile> {
        val fields = fieldPayloads.map { PROFILE_JSON.parseToJsonElement(it).jsonObject }
            .filter { it["custom"]?.jsonPrimitive?.booleanOrNull == true }
        val fieldIds = JiraNormalizer.discoverFieldIds(fieldPayloads)
        val issuesFields = issuePayloads.map { PROFILE_JSON.parseToJsonElement(it).jsonObject.getValue("fields").jsonObject }
        val total = issuesFields.size.toLong()
        return fields.map { field ->
            val id = field.getValue("id").jsonPrimitive.content
            val name = field["name"]?.jsonPrimitive?.contentOrNull ?: id
            val type = field["schema"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown"
            val nonNullCount = issuesFields.count { hasCustomFieldValue(it, id) }.toLong()
            val role = when (id) {
                fieldIds.sprintFieldId -> "SPRINT"
                fieldIds.rankFieldId -> "RANK"
                fieldIds.teamFieldId -> "TEAM"
                fieldIds.storyPointsFieldId -> "STORY_POINTS"
                fieldIds.flaggedFieldId -> "FLAGGED"
                else -> "OTHER"
            }
            CustomFieldProfile(id, name, type, nonNullCount, pct(nonNullCount, total), role)
        }.sortedBy { it.id }
    }

    private fun computeEstimates(items: List<WorkItemStore.ProfileWorkItemRow>): EstimatesProfile {
        val total = items.size.toLong()
        val storyPoints = items.count { it.storyPoints != null }.toLong()
        val originalEstimate = items.count { it.originalEstimateSeconds != null }.toLong()
        return EstimatesProfile(total, storyPoints, pct(storyPoints, total), originalEstimate, pct(originalEstimate, total))
    }

    private const val SECONDS_PER_HOUR = 3600.0

    private fun computeWorklogs(worklogRows: List<WorkItemStore.WorklogRow>, totalIssues: Long): WorklogsProfile {
        val count = worklogRows.size.toLong()
        val totalHours = worklogRows.sumOf { it.timeSpentSeconds } / SECONDS_PER_HOUR
        val itemsWithWorklog = worklogRows.map { it.issueId }.toSet().size.toLong()
        val authorCount = worklogRows.mapNotNull { it.authorAccountId }.toSet().size.toLong()
        return WorklogsProfile(count, totalHours, itemsWithWorklog, pct(itemsWithWorklog, totalIssues), authorCount)
    }

    /** Reuses [Normalization.reopenCount] — the SAME pure DONE→non-DONE check `JiraSyncPipelineTest` asserts with. */
    private fun computeReopens(
        items: List<WorkItemStore.ProfileWorkItemRow>,
        intervalsByIssue: Map<Long, List<NormalizedStatusInterval>>,
    ): ReopensProfile {
        val total = items.size.toLong()
        val count = items.count { Normalization.reopenCount(intervalsByIssue[it.issueId].orEmpty()) > 0 }.toLong()
        return ReopensProfile(count, total, pct(count, total))
    }

    private fun computeSprints(
        sprintRefs: List<ch.nokillswit.norm.SprintRef>,
        sprintFieldValues: List<Pair<Long, String?>>,
        totalIssues: Long,
    ): SprintsProfile {
        val stateCounts = sprintRefs.groupingBy { it.state }.eachCount().mapValues { it.value.toLong() }
        val sprintIdsByIssue = sprintFieldValues.groupBy({ it.first }) { it.second!! }.mapValues { it.value.toSet() }
        val itemsWithSprint = sprintIdsByIssue.size.toLong()
        val carryOverCount = sprintIdsByIssue.values.count { it.size >= 2 }.toLong()
        return SprintsProfile(
            count = sprintRefs.size.toLong(),
            stateCounts = stateCounts,
            itemsWithSprint = itemsWithSprint,
            itemsWithSprintPercent = pct(itemsWithSprint, totalIssues),
            carryOverCount = carryOverCount,
            carryOverPercent = pct(carryOverCount, itemsWithSprint),
        )
    }

    private fun computePeople(items: List<WorkItemStore.ProfileWorkItemRow>): PeopleProfile {
        val total = items.size.toLong()
        val activeAssignees = items.mapNotNull { it.assigneeAccountId }.toSet().size.toLong()
        val unassigned = items.count { it.assigneeAccountId == null }.toLong()
        return PeopleProfile(activeAssignees, pct(unassigned, total))
    }

    /** `GET /project/{key}/statuses`' response (v0.2.0 plan §7 "REFERENCE"): one entry per issue type, its own status list. */
    private fun parseProjectStatuses(payload: String): Map<String, List<String>> =
        PROFILE_JSON.parseToJsonElement(payload).jsonArray.associate { entry ->
            val obj = entry.jsonObject
            val typeName = obj.getValue("name").jsonPrimitive.content
            val statusNames = obj["statuses"]?.jsonArray?.map { it.jsonObject.getValue("name").jsonPrimitive.content }.orEmpty()
            typeName to statusNames
        }
}
