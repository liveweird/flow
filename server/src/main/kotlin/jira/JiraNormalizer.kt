package ch.nokillswit.jira

import ch.nokillswit.norm.BoardColumnRef
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.CurrentFieldValue
import ch.nokillswit.norm.FieldChangeEvent
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.IssueNormalizationInput
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusChangeEvent
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorklogFact
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val NORMALIZER_JSON = Json { ignoreUnknownKeys = true }

private const val STATUS_FIELD_ID = "status"
private const val ASSIGNEE_FIELD_ID = "assignee"

/** Absent (Kotlin `null`) OR a literal JSON `null` both mean "no value" for a Jira object-shaped field — never a cast failure. */
private fun JsonElement?.orNullObject(): JsonObject? = this?.takeIf { it != JsonNull }?.jsonObject

/**
 * The Jira-specific field ids the PROCESS step needs, discovered ONCE per run from the REFERENCE
 * stream's `FIELD` entities (v0.2.0 plan §8) — never hardcoded, since a real tenant assigns its own
 * `customfield_NNNNN` ids. Sprint/Rank/Team are matched by their UNIQUE `schema.custom` plugin key;
 * Story points and Flagged have no unique `schema.custom` of their own on a real tenant (Story
 * points is plain `schema.custom = ...:float`, shared with any other numeric custom field), so
 * those two are matched by NAME instead (case-insensitive).
 */
data class JiraFieldIds(
    val sprintFieldId: String?,
    val rankFieldId: String?,
    val teamFieldId: String?,
    val storyPointsFieldId: String?,
    val flaggedFieldId: String?,
)

/**
 * Jira-specific parsing for the PROCESS step (v0.2.0 plan §8) — turns raw Jira JSON
 * (`raw.jira_issues.payload`, `raw.jira_changelogs.payload`, `raw.jira_worklogs.payload`,
 * `raw.jira_entities.payload`) into the connector-agnostic shapes `norm/Normalization.kt`/
 * `norm/Tiling.kt` consume. Nothing here touches the database — `jira/JiraProcessStream.kt` is the
 * one caller, feeding it rows already read from `jira/JiraRawStore.kt`.
 */
object JiraNormalizer {

    fun discoverFieldIds(fieldPayloads: List<String>): JiraFieldIds {
        val fields = fieldPayloads.map { NORMALIZER_JSON.parseToJsonElement(it).jsonObject }
        fun bySchemaCustom(marker: String) = fields.firstOrNull { field ->
            field["schema"]?.jsonObject?.get("custom")?.jsonPrimitive?.contentOrNull?.contains(marker) == true
        }?.get("id")?.jsonPrimitive?.contentOrNull
        fun byName(marker: String) = fields.firstOrNull { field ->
            field["name"]?.jsonPrimitive?.contentOrNull?.contains(marker, ignoreCase = true) == true
        }?.get("id")?.jsonPrimitive?.contentOrNull

        return JiraFieldIds(
            sprintFieldId = bySchemaCustom("gh-sprint"),
            rankFieldId = bySchemaCustom("gh-lexo-rank"),
            teamFieldId = bySchemaCustom("atlassian-team"),
            storyPointsFieldId = byName("story point"),
            flaggedFieldId = byName("flagged"),
        )
    }

    /** `norm.statuses`' rebuilt reference rows (v0.2.0 plan §8) — one per `STATUS` entity. */
    fun statusRefs(statusPayloads: List<String>): List<StatusRef> = statusPayloads.map { payload ->
        val status = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
        val categoryKey = status["statusCategory"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
        StatusRef(
            statusId = status.getValue("id").jsonPrimitive.content,
            name = status.getValue("name").jsonPrimitive.content,
            category = statusCategoryForKey(categoryKey),
        )
    }

    private fun statusCategoryForKey(key: String?): StatusCategory = when (key) {
        "new" -> StatusCategory.TODO
        "indeterminate" -> StatusCategory.IN_PROGRESS
        "done" -> StatusCategory.DONE
        else -> StatusCategory.UNKNOWN
    }

    /** `norm.people`'s rebuilt reference rows — one per `USER` entity. */
    fun peopleRefs(userPayloads: List<String>): List<PersonRef> = userPayloads.map { payload ->
        val user = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
        PersonRef(
            accountId = user.getValue("accountId").jsonPrimitive.content,
            displayName = user["displayName"]?.jsonPrimitive?.contentOrNull ?: user.getValue("accountId").jsonPrimitive.content,
            email = user["emailAddress"]?.jsonPrimitive?.contentOrNull,
            active = user["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
        )
    }

    /** `norm.boards`/`norm.board_columns`'s rebuilt rows — one `BOARD` entity joined with its (optional) `BOARD_CONFIGURATION` entity. */
    fun boardRefs(boardPayloads: List<String>, boardConfigurationPayloads: List<String>): List<BoardRef> {
        val configByBoardId = boardConfigurationPayloads.associateBy {
            NORMALIZER_JSON.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content
        }
        return boardPayloads.map { payload ->
            val board = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
            val boardId = board.getValue("id").jsonPrimitive.content
            val columns = configByBoardId[boardId]?.let { configPayload ->
                val config = NORMALIZER_JSON.parseToJsonElement(configPayload).jsonObject
                config["columnConfig"]?.jsonObject?.get("columns")?.jsonArray?.map { columnElement ->
                    val column = columnElement.jsonObject
                    BoardColumnRef(
                        name = column.getValue("name").jsonPrimitive.content,
                        statusIds = column["statuses"]?.jsonArray
                            ?.map { it.jsonObject.getValue("id").jsonPrimitive.content } ?: emptyList(),
                    )
                }
            } ?: emptyList()
            BoardRef(
                boardId = boardId.toLong(),
                name = board.getValue("name").jsonPrimitive.content,
                boardType = board.getValue("type").jsonPrimitive.content,
                projectKey = board["location"]?.jsonObject?.get("projectKey")?.jsonPrimitive?.contentOrNull,
                columns = columns,
            )
        }
    }

    /** `norm.sprints`' rebuilt reference rows — one per `SPRINT` entity. */
    fun sprintRefs(sprintPayloads: List<String>): List<SprintRef> = sprintPayloads.map { payload ->
        val sprint = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
        SprintRef(
            sprintId = sprint.getValue("id").jsonPrimitive.content.toLong(),
            boardId = sprint["originBoardId"]?.jsonPrimitive?.longOrNull,
            name = sprint.getValue("name").jsonPrimitive.content,
            state = sprint.getValue("state").jsonPrimitive.content,
            startAtMs = sprint["startDate"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() },
            endAtMs = sprint["endDate"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() },
            goal = sprint["goal"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * The full per-issue tiling input (v0.2.0 plan §8) — [issuePayload] is `raw.jira_issues.payload`
     * (`{id, key, fields}`), [changelogPayloads] is that issue's histories oldest-first (already
     * ordered by the caller, `jira/JiraRawStore.kt`'s `changelogPayloadsForIssue`), [worklogPayloads]
     * its non-tombstoned worklogs.
     */
    fun normalizeIssue(
        issuePayload: String,
        changelogPayloads: List<String>,
        worklogPayloads: List<String>,
        fieldIds: JiraFieldIds,
        tombstone: TombstoneKind,
    ): IssueNormalizationInput {
        val issue = NORMALIZER_JSON.parseToJsonElement(issuePayload).jsonObject
        val fields = issue.getValue("fields").jsonObject
        val issueId = issue.getValue("id").jsonPrimitive.content.toLong()

        val issueType = fields.getValue("issuetype").jsonObject
        val project = fields.getValue("project").jsonObject
        // `assignee`/`resolution` are present-but-JSON-null when unset (never simply absent), unlike
        // `parent`/the custom fields below — `.jsonObject` would throw on a literal JSON null, so
        // these go through the null-filtering helper instead of a plain `?.jsonObject` cast.
        val assignee = fields["assignee"].orNullObject()
        val reporter = fields["reporter"].orNullObject()
        val priority = fields["priority"].orNullObject()
        val resolution = fields["resolution"].orNullObject()
        val team = fieldIds.teamFieldId?.let { fields[it] }.orNullObject()

        val currentSprints = fieldIds.sprintFieldId?.let { fields[it]?.jsonArray } ?: JsonArray(emptyList())
        val currentSprintIds = currentSprints.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() }
        val currentSprintText = currentSprints.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.jsonObject.getValue("name").jsonPrimitive.content }
        val currentFlagged = fieldIds.flaggedFieldId?.let { fields[it]?.jsonArray?.isNotEmpty() } ?: false

        val worklogs = worklogPayloads.map { payload ->
            val worklog = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
            WorklogFact(
                worklogId = worklog.getValue("id").jsonPrimitive.content.toLong(),
                authorAccountId = worklog["author"]?.jsonObject?.get("accountId")?.jsonPrimitive?.contentOrNull,
                startedAtMs = Instant.parse(worklog.getValue("started").jsonPrimitive.content).toEpochMilli(),
                timeSpentSeconds = worklog.getValue("timeSpentSeconds").jsonPrimitive.content.toLong(),
            )
        }

        val facts = WorkItemFacts(
            issueKey = issue.getValue("key").jsonPrimitive.content,
            projectKey = project.getValue("key").jsonPrimitive.content,
            issueType = issueType.getValue("name").jsonPrimitive.content,
            isSubtask = issueType["subtask"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            parentIssueId = fields["parent"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            summary = fields["summary"]?.jsonPrimitive?.contentOrNull,
            currentStatusId = fields.getValue("status").jsonObject.getValue("id").jsonPrimitive.content,
            resolution = resolution?.get("name")?.jsonPrimitive?.contentOrNull,
            priority = priority?.get("name")?.jsonPrimitive?.contentOrNull,
            assigneeAccountId = assignee?.get("accountId")?.jsonPrimitive?.contentOrNull,
            reporterAccountId = reporter?.get("accountId")?.jsonPrimitive?.contentOrNull,
            createdAtMs = Instant.parse(fields.getValue("created").jsonPrimitive.content).toEpochMilli(),
            updatedAtMs = Instant.parse(fields.getValue("updated").jsonPrimitive.content).toEpochMilli(),
            resolvedAtMs = fields["resolutiondate"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() },
            storyPoints = fieldIds.storyPointsFieldId?.let { fields[it]?.jsonPrimitive?.doubleOrNull },
            originalEstimateSeconds = fields["timetracking"]?.jsonObject?.get("originalEstimateSeconds")?.jsonPrimitive?.longOrNull,
            // Computed from the issue's OWN worklogs, never `timetracking.timeSpentSeconds` (which a
            // real tenant may leave stale relative to the worklog feed this stream already trusts).
            timeSpentSeconds = worklogs.sumOf { it.timeSpentSeconds },
            labels = fields["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            components = fields["components"]?.jsonArray?.map { it.jsonObject.getValue("name").jsonPrimitive.content } ?: emptyList(),
            fixVersions = fields["fixVersions"]?.jsonArray?.map { it.jsonObject.getValue("name").jsonPrimitive.content } ?: emptyList(),
            teamValueJson = team?.toString(),
            rank = fieldIds.rankFieldId?.let { fields[it]?.jsonPrimitive?.contentOrNull },
            tombstone = tombstone,
        )

        return IssueNormalizationInput(
            issueId = issueId,
            facts = facts,
            statusEvents = changelogPayloads.flatMap { statusEventsFromHistory(it) },
            assigneeEvents = changelogPayloads.flatMap { fieldEventsFromHistory(it, ASSIGNEE_FIELD_ID) },
            sprintEvents = fieldIds.sprintFieldId
                ?.let { id -> changelogPayloads.flatMap { sprintEventsFromHistory(it, id) } } ?: emptyList(),
            flaggedEvents = fieldIds.flaggedFieldId
                ?.let { id -> changelogPayloads.flatMap { flaggedEventsFromHistory(it, id) } } ?: emptyList(),
            currentAssignee = CurrentFieldValue(
                assignee?.get("accountId")?.jsonPrimitive?.contentOrNull,
                assignee?.get("displayName")?.jsonPrimitive?.contentOrNull,
            ),
            currentSprintIds = currentSprintIds,
            currentSprintText = currentSprintText,
            currentFlagged = currentFlagged,
            fieldChanges = changelogPayloads.flatMap { fieldChangesFromHistory(it, fieldIds) },
            worklogs = worklogs,
        )
    }

    private fun historyItems(historyPayload: String): Pair<Long, List<JsonObject>> {
        val history = NORMALIZER_JSON.parseToJsonElement(historyPayload).jsonObject
        val atMs = Instant.parse(history.getValue("created").jsonPrimitive.content).toEpochMilli()
        return atMs to history.getValue("items").jsonArray.map { it.jsonObject }
    }

    private fun statusEventsFromHistory(historyPayload: String): List<StatusChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == STATUS_FIELD_ID }
            .map { StatusChangeEvent(atMs, it.getValue("from").jsonPrimitive.content, it.getValue("to").jsonPrimitive.content) }
    }

    private fun fieldEventsFromHistory(historyPayload: String, fieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == fieldId }
            .map {
                FieldChangeEvent(
                    atMs,
                    it["from"]?.jsonPrimitive?.contentOrNull,
                    it["fromString"]?.jsonPrimitive?.contentOrNull,
                    it["to"]?.jsonPrimitive?.contentOrNull,
                    it["toString"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    /**
     * SPRINT's `from`/`to` are comma-joined id lists (plan §8's "multi-valued sprint field" rule) —
     * `value_id` is the LAST id, `value_text` is Jira's own comma-joined `toString`/`fromString`.
     */
    private fun sprintEventsFromHistory(historyPayload: String, sprintFieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == sprintFieldId }
            .map { item ->
                val fromIds = item["from"]?.jsonPrimitive?.contentOrNull?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val toIds = item["to"]?.jsonPrimitive?.contentOrNull?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                FieldChangeEvent(
                    atMs,
                    fromIds.lastOrNull(),
                    item["fromString"]?.jsonPrimitive?.contentOrNull,
                    toIds.lastOrNull(),
                    item["toString"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    private fun flaggedEventsFromHistory(historyPayload: String, flaggedFieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == flaggedFieldId }
            .map { item ->
                val isFlagged = item["to"]?.jsonPrimitive?.contentOrNull != null
                val wasFlagged = item["from"]?.jsonPrimitive?.contentOrNull != null
                FieldChangeEvent(atMs, wasFlagged.toString(), null, isFlagged.toString(), null)
            }
    }

    /**
     * Every TRACKED changelog item — status, assignee, Sprint, Flagged, Rank, priority,
     * resolution, issuetype, project, Key and story points (plan §4) — kept verbatim, in order.
     */
    private fun fieldChangesFromHistory(historyPayload: String, fieldIds: JiraFieldIds): List<FieldChangeFact> {
        val (atMs, items) = historyItems(historyPayload)
        val trackedFieldIds = setOfNotNull(
            STATUS_FIELD_ID, ASSIGNEE_FIELD_ID, "priority", "resolution", "issuetype", "project", "issuekey",
            fieldIds.sprintFieldId, fieldIds.flaggedFieldId, fieldIds.rankFieldId, fieldIds.storyPointsFieldId,
        )
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull in trackedFieldIds }
            .map { item ->
                FieldChangeFact(
                    field = item.getValue("field").jsonPrimitive.content,
                    atMs = atMs,
                    fromValue = item["from"]?.jsonPrimitive?.contentOrNull,
                    fromText = item["fromString"]?.jsonPrimitive?.contentOrNull,
                    toValue = item["to"]?.jsonPrimitive?.contentOrNull,
                    toText = item["toString"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }
}
