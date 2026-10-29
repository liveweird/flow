package ch.nokillswit.jira

import ch.nokillswit.norm.BoardColumnRef
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.StatusRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val REFERENCE_JSON = Json { ignoreUnknownKeys = true }

/**
 * The reference-row half of the Jira normalizer (v0.2.0 plan §8): one stored `raw.jira_entities` payload (FIELD, ISSUE_TYPE, STATUS,
 * USER, BOARD, BOARD_CONFIGURATION, SPRINT) to the small value PROCESS, the data profile and PROJECT_FIELDS keep. Every function is
 * PER PAYLOAD (the list forms only map over them): the callers read a kind in chunks (`JiraRawStore.mapEntities`), parse one payload,
 * keep the value and drop the text and the tree before the next, so no kind is ever held whole. Nothing here touches the database.
 */
object JiraReferenceRows {

    fun discoverFieldIds(fieldPayloads: List<String>): JiraFieldIds =
        FieldIdsCollector().also { collector -> fieldPayloads.forEach(collector::accept) }.build()

    /**
     * [discoverFieldIds] fed one stored `FIELD` payload at a time (`JiraRawStore.forEachEntity`): ONE field tree at a time and the FIRST
     * match per marker (what `firstOrNull` over the whole parsed list gave), the kind's text never held at once.
     */
    class FieldIdsCollector {
        private val found = HashMap<String, String?>()

        fun accept(payload: String) {
            val field = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
            val custom = field["schema"]?.jsonObject?.get("custom")?.jsonPrimitive?.contentOrNull
            val name = field["name"]?.jsonPrimitive?.contentOrNull
            (listOf("gh-sprint", "gh-lexo-rank", "atlassian-team", "gh-epic-link").filter { it !in found && custom?.contains(it) == true } +
                listOf("story point", "flagged").filter { it !in found && name?.contains(it, ignoreCase = true) == true })
                .forEach { found[it] = field["id"]?.jsonPrimitive?.contentOrNull }
        }

        fun build() = JiraFieldIds(
            found["gh-sprint"], found["gh-lexo-rank"], found["atlassian-team"],
            found["story point"], found["flagged"], found["gh-epic-link"],
        )
    }

    /**
     * `hierarchyLevel` per Jira issue-type id (v0.3.0 M1 commit 2) — resolved ONCE per PROCESS run
     * from the REFERENCE stream's `ISSUE_TYPE` entities, the FALLBACK source [normalizeIssue] uses
     * when an issue's OWN `fields.issuetype.hierarchyLevel` is absent: the sample stub never
     * includes it on the issue document itself (`sample-data/jira-stub/__files/issuetype.json` is
     * the only fixture carrying it), but a real tenant's issue-search response sometimes does — the
     * issue's own value, when present, is authoritative. An epic is level 1, never "type name =
     * Epic".
     */
    fun issueTypeHierarchy(issueTypePayloads: List<String>): Map<String, Int> = issueTypePayloads.mapNotNull(::issueTypeLevel).toMap()

    /** One stored `ISSUE_TYPE` payload's `(id, hierarchyLevel)`, `null` when either is absent (the step of [issueTypeHierarchy]). */
    fun issueTypeLevel(payload: String): Pair<String, Int>? {
        val type = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
        val id = type["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val level = type["hierarchyLevel"]?.jsonPrimitive?.intOrNull ?: return null
        return id to level
    }

    /**
     * `norm.statuses`' rebuilt reference rows (v0.2.0 plan §8) — one per `STATUS` entity. Jira has two shapes for the
     * category: `GET /statuses/search` (what the REFERENCE stream stores) returns a plain string enum
     * (`"TODO"`/`"IN_PROGRESS"`/`"DONE"`), while an issue's `fields.status` and `/status` return the object form
     * (`{"key":"new"|"indeterminate"|"done"}`). Both are accepted; anything else is `UNKNOWN`, never a throw.
     */
    fun statusRefs(statusPayloads: List<String>): List<StatusRef> = statusPayloads.map(::statusRef)

    /** One stored `STATUS` payload as its reference row (the per-entity step of [statusRefs]). */
    fun statusRef(payload: String): StatusRef {
        val status = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
        return StatusRef(
            statusId = status.getValue("id").jsonPrimitive.content,
            name = status.getValue("name").jsonPrimitive.content,
            category = jiraStatusCategory(status["statusCategory"]),
        )
    }

    /** `norm.people`'s rebuilt reference rows — one per `USER` entity. */
    fun peopleRefs(userPayloads: List<String>): List<PersonRef> = userPayloads.map(::personRef)

    /** One stored `USER` payload as its reference row (the per-entity step of [peopleRefs]). */
    fun personRef(payload: String): PersonRef {
        val user = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
        return PersonRef(
            accountId = user.getValue("accountId").jsonPrimitive.content,
            displayName = user["displayName"]?.jsonPrimitive?.contentOrNull ?: user.getValue("accountId").jsonPrimitive.content,
            email = user["emailAddress"]?.jsonPrimitive?.contentOrNull,
            active = user["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
        )
    }

    /** `norm.boards`/`norm.board_columns`'s rebuilt rows - one `BOARD` entity joined with its (optional) `BOARD_CONFIGURATION` entity. */
    fun boardRefs(boardPayloads: List<String>, boardConfigurationPayloads: List<String>): List<BoardRef> {
        val columnsByBoardId = boardConfigurationPayloads.associate(::boardColumns)
        return boardPayloads.map { boardRef(it, columnsByBoardId) }
    }

    /**
     * One stored `BOARD_CONFIGURATION` payload reduced to `(board id, its columns)`: PROCESS maps the whole kind through this FIRST
     * and joins the small result by board id ([boardRef]), so a board tree and a configuration tree never coexist.
     */
    fun boardColumns(configPayload: String): Pair<String, List<BoardColumnRef>> {
        val config = REFERENCE_JSON.parseToJsonElement(configPayload).jsonObject
        val columns = config["columnConfig"]?.jsonObject?.get("columns")?.jsonArray?.map { columnElement ->
            val column = columnElement.jsonObject
            BoardColumnRef(
                name = column.getValue("name").jsonPrimitive.content,
                statusIds = column["statuses"]?.jsonArray?.map { it.jsonObject.getValue("id").jsonPrimitive.content } ?: emptyList(),
            )
        }
        return config.getValue("id").jsonPrimitive.content to columns.orEmpty()
    }

    /** One stored `BOARD` payload as its reference row, its columns looked up in [columnsByBoardId] ([boardColumns]). */
    fun boardRef(payload: String, columnsByBoardId: Map<String, List<BoardColumnRef>>): BoardRef {
        val board = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
        val boardId = board.getValue("id").jsonPrimitive.content
        return BoardRef(
            boardId = boardId.toLong(),
            name = board.getValue("name").jsonPrimitive.content,
            boardType = board.getValue("type").jsonPrimitive.content,
            projectKey = board["location"]?.jsonObject?.get("projectKey")?.jsonPrimitive?.contentOrNull,
            columns = columnsByBoardId[boardId].orEmpty(),
        )
    }

    /** `norm.sprints`' rebuilt reference rows — one per `SPRINT` entity. */
    fun sprintRefs(sprintPayloads: List<String>): List<SprintRef> = sprintPayloads.map(::sprintRef)

    /** One stored `SPRINT` payload as its reference row (the per-entity step of [sprintRefs]). */
    fun sprintRef(payload: String): SprintRef {
        val sprint = REFERENCE_JSON.parseToJsonElement(payload).jsonObject
        return SprintRef(
            sprintId = sprint.getValue("id").jsonPrimitive.content.toLong(),
            boardId = sprint["originBoardId"]?.jsonPrimitive?.longOrNull,
            name = sprint.getValue("name").jsonPrimitive.content,
            state = sprint.getValue("state").jsonPrimitive.content,
            startAtMs = sprint["startDate"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) },
            endAtMs = sprint["endDate"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) },
            goal = sprint["goal"]?.jsonPrimitive?.contentOrNull,
            // completeDate (v0.3.0 M1 commit 2) — the metrics layer keys sprint periods on completion, not endDate.
            completeAtMs = sprint["completeDate"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) },
        )
    }
}
