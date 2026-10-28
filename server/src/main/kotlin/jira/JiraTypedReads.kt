package ch.nokillswit.jira

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.format.DateTimeParseException

/**
 * The typed reads of host-supplied ids, keys and timestamps (`.claude/docs/jira-integration.md` "Paging ceilings"): a field the host
 * sent that cannot be used is `INVALID_RESPONSE` with CONSTANT text (what and which field, never the value), so no host-supplied text
 * reaches `sync_jobs.error_detail` or a log the way a bare `getValue(..)`/`toLong()` failure's message does (`NumberFormatException`
 * quotes its input). Ported from the WORKLOGS reads of #369 and shared by every stream that reads an id or a date off a page.
 */
internal fun invalidField(endpoint: String, what: String, field: String) =
    JiraFetchException("INVALID_RESPONSE", null, endpoint, "$what without a usable $field")

/** [key] as a number (a JSON number or numeric string), else `INVALID_RESPONSE`. [what] names the object ("a worklog"). */
internal fun JsonObject.requireLong(key: String, endpoint: String, what: String): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: throw invalidField(endpoint, what, key)

/** [key] as a non-blank JSON string, else `INVALID_RESPONSE`. */
internal fun JsonObject.requireString(key: String, endpoint: String, what: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw invalidField(endpoint, what, key)

/** An entity's `id` as text (a JSON string or number), else `INVALID_RESPONSE`; [endpoint] names the step. */
internal fun JsonObject.requireIdText(endpoint: String): String =
    (this["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw invalidField(endpoint, "an entity", "id")

/** [key] as a JSON object, else `INVALID_RESPONSE`. */
internal fun JsonObject.requireObject(key: String, endpoint: String, what: String): JsonObject =
    this[key] as? JsonObject ?: throw invalidField(endpoint, what, key)

/** [key] (default `updated`) as a Jira timestamp in epoch millis, else `INVALID_RESPONSE`. */
internal fun JsonObject.requireMillis(endpoint: String, what: String, key: String = "updated"): Long {
    val text = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw invalidField(endpoint, what, key)
    return try {
        parseJiraInstantEpochMillis(text)
    } catch (_: DateTimeParseException) {
        throw invalidField(endpoint, what, key)
    }
}

/** The element as an object, else `INVALID_RESPONSE` (an array element that is not an object). */
internal fun JsonElement.requireObjectElement(endpoint: String, what: String): JsonObject =
    this as? JsonObject ?: throw invalidField(endpoint, what, "object shape")

/**
 * The raw issue a `search/jql` page or `GET /issue/{id}` answers, as the ISSUES stream and RECONCILE's index-gap fetch store it: id,
 * key, project and `updated` read through the typed reads, the payload as sent. ONE read, so both write the same row.
 */
internal fun rawIssueInputOf(issue: JsonElement, endpoint: String): RawIssueInput {
    val obj = issue.requireObjectElement(endpoint, "an issue")
    val fields = obj.requireObject("fields", endpoint, "an issue")
    val project = fields.requireObject("project", endpoint, "an issue")
    return RawIssueInput(
        issueId = obj.requireLong("id", endpoint, "an issue"),
        issueKey = obj.requireString("key", endpoint, "an issue"),
        projectId = project.requireLong("id", endpoint, "an issue's project"),
        projectKey = project.requireString("key", endpoint, "an issue's project"),
        issueUpdatedAt = fields.requireMillis(endpoint, "an issue"),
        payload = issue,
    )
}
