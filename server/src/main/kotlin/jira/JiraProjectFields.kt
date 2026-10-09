package ch.nokillswit.jira

import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.hierarchyBucket
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
 * `{"projectKey":…,"projectId":…,"fieldIds":[sorted distinct],"epicFieldIds":[…],"taskFieldIds":[…]}` — the (field, work type)
 * rows are collapsed to field-id sets: the union plus the epic/task split (absent when unknown).
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
     * The field ids of [projectId]'s field scheme per work type (the row's `workTypeId`), for [workTypeIds]: pages through
     * `projects/fields` (startAt-paged, trusting a non-null `isLast` over `total`, which may be absent/0, and the page size
     * Jira actually returned) in request-sized groups of work types, folding each page into the per-type sets as it arrives.
     * A row without a numeric `workTypeId` still counts in [FetchedScheme.fieldIds] but makes the per-type split unknown
     * ([FetchedScheme.byWorkType] `null`). Throws
     * [JiraFetchException] — `INVALID_RESPONSE` for a row without a string `fieldId` — so the caller decides what skips.
     */
    suspend fun fetchScheme(client: JiraClient, projectId: Long, workTypeIds: List<Long>): FetchedScheme {
        val collector = SchemeCollector()
        for (group in workTypeIds.chunked(WORK_TYPES_PER_REQUEST)) {
            var startAt = 0
            while (true) {
                val page = client.projectFields(projectId, group, startAt)
                page.values.forEach(collector::add)
                val nextStartAt = startAt + page.values.size
                val last = page.isLast ?: (nextStartAt >= page.total)
                if (last || page.values.isEmpty()) break
                startAt = nextStartAt
            }
        }
        return collector.build()
    }

    /** Folds the rows of each page into the distinct field ids and the per-work-type sets, so no page is kept once folded. */
    private class SchemeCollector {
        private val fieldIds = sortedSetOf<String>()
        private val byWorkType = sortedMapOf<Long, MutableSet<String>>()
        private var splitKnown = true

        fun add(row: JsonElement) {
            val fieldId = fieldIdOf(row)
            fieldIds += fieldId
            val workTypeId = workTypeIdOf(row)
            if (workTypeId == null) splitKnown = false else byWorkType.getOrPut(workTypeId) { sortedSetOf() } += fieldId
        }

        fun build() = FetchedScheme(fieldIds.toList(), if (splitKnown) byWorkType else null)
    }

    /** What [fetchScheme] collected: the distinct field ids and — when every row named its work type — the ids per work type. */
    class FetchedScheme(val fieldIds: List<String>, val byWorkType: Map<Long, Set<String>>?)

    /**
     * The `PROJECT_FIELDS` payload. [hierarchy] (issue-type id → `hierarchyLevel`, `JiraNormalizer.issueTypeHierarchy`) splits the
     * scheme: a field is an epic field when any work type at level 1 carries it, a task field when any at level 0 or -1 (a
     * sub-task) does; a work type missing from [hierarchy] counts as a task type, and one above level 1 counts for neither.
     * Without the split (an empty [hierarchy], or [FetchedScheme.byWorkType] unknown) only the union `fieldIds` is stored — readers
     * then fall back to it for both ([parse]).
     */
    fun payload(projectKey: String, projectId: Long, scheme: FetchedScheme, hierarchy: Map<String, Int>): String {
        val split = scheme.byWorkType?.takeIf { hierarchy.isNotEmpty() }?.let { splitByLevel(it, hierarchy) }
        return buildJsonObject {
            put("projectKey", projectKey)
            put("projectId", projectId)
            put("fieldIds", JsonArray(scheme.fieldIds.map { JsonPrimitive(it) }))
            if (split != null) {
                put("epicFieldIds", JsonArray(split.first.map { JsonPrimitive(it) }))
                put("taskFieldIds", JsonArray(split.second.map { JsonPrimitive(it) }))
            }
        }.toString()
    }

    private fun splitByLevel(byWorkType: Map<Long, Set<String>>, hierarchy: Map<String, Int>): Pair<List<String>, List<String>> {
        val epic = sortedSetOf<String>()
        val task = sortedSetOf<String>()
        byWorkType.forEach { (workTypeId, fields) ->
            when (hierarchyBucket(hierarchy[workTypeId.toString()])) {
                HierarchyBucket.EPIC -> epic += fields
                HierarchyBucket.TASK -> task += fields
                null -> Unit
            }
        }
        return epic.toList() to task.toList()
    }

    /** A stored payload's field ids: the union, and the epic/task split (both = the union for a payload stored without it). */
    class Parsed(val fieldIds: List<String>, val epicFieldIds: List<String>, val taskFieldIds: List<String>)

    /** The `fieldIds`/`epicFieldIds`/`taskFieldIds` of a stored `PROJECT_FIELDS` payload (the data profile's reader). */
    fun parse(payload: String): Parsed {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val union = ids(obj.getValue("fieldIds"))
        val epic = obj["epicFieldIds"] as? JsonArray
        val task = obj["taskFieldIds"] as? JsonArray
        return if (epic != null && task != null) Parsed(union, ids(epic), ids(task)) else Parsed(union, union, union)
    }

    private fun ids(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

    /** A row's numeric `workTypeId` (a JSON number or numeric string), else `null` — the split is then unknown. */
    private fun workTypeIdOf(row: JsonElement): Long? =
        ((row as? JsonObject)?.get("workTypeId") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** A row's `fieldId` — a non-blank JSON string, else `INVALID_RESPONSE` (`JsonNull.content` is the text "null"). */
    private fun fieldIdOf(row: JsonElement): String {
        val field = (row as? JsonObject)?.get("fieldId") as? JsonPrimitive
        return field?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw JiraFetchException("INVALID_RESPONSE", null, PROJECT_FIELDS_ENDPOINT)
    }
}
