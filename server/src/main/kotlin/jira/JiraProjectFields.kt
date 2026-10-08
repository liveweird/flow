package ch.nokillswit.jira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val PROJECT_FIELDS_ENDPOINT = "/rest/api/3/projects/fields"

/** How many issue-type ids one `projects/fields` request carries (the repeated `workTypeId` param) — keeps the URL short. */
private const val WORK_TYPES_PER_REQUEST = 25

/**
 * The failure codes that make the OPTIONAL `PROJECT_FIELDS` REFERENCE step skip a project instead of failing the SYNC
 * (`.claude/docs/jira-integration.md` "Project field schemes"): the endpoint is EXPERIMENTAL, so a missing scope (401/403),
 * a withdrawn endpoint (404) or a shape/status the client cannot read (`INVALID_RESPONSE`) must never cost the sync. Everything
 * else (5xx after retries, timeouts, rate limits, a blocked host) behaves like any other reference step.
 */
internal val PROJECT_FIELDS_SKIP_CODES: Set<String> = setOf("AUTHENTICATION_FAILED", "FORBIDDEN_SCOPE", "NOT_FOUND", "INVALID_RESPONSE")

/**
 * `PROJECT_FIELDS` entity helpers (`GET /projects/fields`, one stored entity per project key): the inputs the step reads
 * off the already-stored `PROJECT`/`PROJECT_STATUSES` entities, the paged fetch, and the stored payload's shape
 * `{"projectKey":…,"projectId":…,"fieldIds":[sorted distinct]}` — the (field, work type) rows are collapsed to a field-id set.
 */
internal object JiraProjectFields {

    /** Project key → numeric project id, from `PROJECT` entity payloads (`project/search` values). */
    fun projectIdsByKey(projects: List<JsonElement>): Map<String, Long> = projects.mapNotNull { project ->
        val key = project.jsonObject["key"]?.jsonPrimitive?.content
        val id = project.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
        if (key == null || id == null) null else key to id
    }.toMap()

    /** The issue-type ids of a project's statuses document (`GET /project/{key}/statuses`: one array entry per issue type). */
    fun issueTypeIds(statuses: JsonArray): List<Long> =
        statuses.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull() }.distinct().sorted()

    /**
     * Every field id in [projectId]'s field scheme for [workTypeIds]: pages through `projects/fields` (startAt-paged,
     * honouring `isLast`/`total` and the page size Jira actually returned) in request-sized groups of work types.
     * Throws [JiraFetchException] — `INVALID_RESPONSE` for a row without a `fieldId` — so the caller decides what skips.
     */
    suspend fun fetchFieldIds(client: JiraClient, projectId: Long, workTypeIds: List<Long>): List<String> {
        val fieldIds = sortedSetOf<String>()
        for (group in workTypeIds.chunked(WORK_TYPES_PER_REQUEST)) {
            var startAt = 0
            while (true) {
                val page = client.projectFields(projectId, group, startAt)
                page.values.forEach { fieldIds += fieldIdOf(it) }
                val nextStartAt = startAt + page.values.size
                if (page.isLast == true || nextStartAt >= page.total || page.values.isEmpty()) break
                startAt = nextStartAt
            }
        }
        return fieldIds.toList()
    }

    fun payload(projectKey: String, projectId: Long, fieldIds: List<String>): String = buildJsonObject {
        put("projectKey", projectKey)
        put("projectId", projectId)
        put("fieldIds", JsonArray(fieldIds.map { JsonPrimitive(it) }))
    }.toString()

    /** The `fieldIds` of a stored `PROJECT_FIELDS` payload (the data profile's reader). */
    fun fieldIds(payload: String): List<String> =
        Json.parseToJsonElement(payload).jsonObject.getValue("fieldIds").jsonArray.map { it.jsonPrimitive.content }

    private fun fieldIdOf(row: JsonElement): String = try {
        row.jsonObject.getValue("fieldId").jsonPrimitive.content
    } catch (cause: IllegalArgumentException) {
        throw JiraFetchException("INVALID_RESPONSE", null, PROJECT_FIELDS_ENDPOINT).also { it.initCause(cause) }
    } catch (cause: NoSuchElementException) {
        throw JiraFetchException("INVALID_RESPONSE", null, PROJECT_FIELDS_ENDPOINT).also { it.initCause(cause) }
    }
}
