package ch.nokillswit.jira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val PROJECT_FIELDS_ENDPOINT = "/rest/api/3/projects/fields"

/** How many issue-type ids one `projects/fields` request carries (the repeated `workTypeId` param) — keeps the URL short. */
private const val WORK_TYPES_PER_REQUEST = 25

/**
 * The ONE failure code that stays fatal for the OPTIONAL `PROJECT_FIELDS` REFERENCE step (`.claude/docs/jira-integration.md`
 * "Project field schemes"): an SSRF-guard rejection is a security signal, never something to swallow. Every other
 * [JiraFetchException] (a missing scope, a withdrawn endpoint, a bad shape, a 5xx after retries, a timeout, a rate limit, ...)
 * only skips the project — the endpoint is EXPERIMENTAL and must never stop ingestion.
 */
private const val FATAL_PROJECT_FIELDS_CODE = "BLOCKED_HOST"

/** Whether [failure] skips the optional `PROJECT_FIELDS` step for a project (true) or must propagate (`BLOCKED_HOST`). */
internal fun skipsProjectFields(failure: JiraFetchException): Boolean = failure.code != FATAL_PROJECT_FIELDS_CODE

/**
 * `PROJECT_FIELDS` entity helpers (`GET /projects/fields`, one stored entity per project key): the inputs the step reads
 * off the already-stored `PROJECT`/`PROJECT_STATUSES` entities, the paged fetch, and the stored payload's shape
 * `{"projectKey":…,"projectId":…,"fieldIds":[sorted distinct]}` — the (field, work type) rows are collapsed to a field-id set.
 */
internal object JiraProjectFields {

    /**
     * Project key → numeric project id, from `PROJECT` entity payloads (`project/search` values). Total: an entry of an
     * unexpected shape is skipped, never thrown on — the Test-connection probe that seeds from it is REQUIRED and must not fail here.
     */
    fun projectIdsByKey(projects: List<JsonElement>): Map<String, Long> = projects.mapNotNull { project ->
        val obj = project as? JsonObject ?: return@mapNotNull null
        val key = (obj["key"] as? JsonPrimitive)?.contentOrNull
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        if (key == null || id == null) null else key to id
    }.toMap()

    /** The issue-type ids of a project's statuses document (one entry per type); total like [projectIdsByKey]. */
    fun issueTypeIds(statuses: JsonArray): List<Long> = statuses.mapNotNull {
        ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }.distinct().sorted()

    /**
     * Every field id in [projectId]'s field scheme for [workTypeIds]: pages through `projects/fields` (startAt-paged,
     * trusting a non-null `isLast` over `total`, which may be absent/0, and the page size Jira actually returned) in
     * request-sized groups of work types. Throws [JiraFetchException] — `INVALID_RESPONSE` for a row without a string
     * `fieldId` — so the caller decides what skips.
     */
    suspend fun fetchFieldIds(client: JiraClient, projectId: Long, workTypeIds: List<Long>): List<String> {
        val fieldIds = sortedSetOf<String>()
        for (group in workTypeIds.chunked(WORK_TYPES_PER_REQUEST)) {
            var startAt = 0
            while (true) {
                val page = client.projectFields(projectId, group, startAt)
                page.values.forEach { fieldIds += fieldIdOf(it) }
                val nextStartAt = startAt + page.values.size
                val last = page.isLast ?: (nextStartAt >= page.total)
                if (last || page.values.isEmpty()) break
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

    /** A row's `fieldId` — a non-blank JSON string, else `INVALID_RESPONSE` (`JsonNull.content` is the text "null", so never `content`). */
    private fun fieldIdOf(row: JsonElement): String {
        val field = (row as? JsonObject)?.get("fieldId") as? JsonPrimitive
        return field?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw JiraFetchException("INVALID_RESPONSE", null, PROJECT_FIELDS_ENDPOINT)
    }
}
