package ch.nokillswit.jira

import ch.nokillswit.infra.json.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the raw store keeps of a Jira payload: the TRIMMED document (`.claude/docs/persistence.md` "The Jira raw store (V10)" and
 * "The Jira changelog/worklog raw store (V11)"). Flow reads a small part of what Jira sends - the normalizer, the data profile, the
 * raw issue inspector, the reference streams, RECONCILE and REPROCESS are the whole list of readers - and the rest is free text
 * (comments, descriptions, which also carry personal data) or copies of data held elsewhere (worklogs have their own table, a
 * user their own `USER` entity). It is dropped BEFORE the payload is canonicalized and hashed, so the stored text and its sha256
 * agree and a re-fetch of an unchanged document hashes identically (`UNCHANGED`, no `changed_at` move).
 *
 * The functions are pure and total: a value of an unexpected shape (`fields` that is not an object, `items` that is not an array,
 * an item that is not an object) is left exactly as it is, never an exception - the posture of [slimUserReference]. Each is
 * idempotent. They are also the body of the TRIM stream ([JiraTrimStream]), which rewrites the rows stored before this existed, so
 * a write and a rewrite agree byte for byte.
 *
 * **What is dropped, and why it is safe** (each key was grepped across the readers; `JiraPayloadTrimTest` pins the equivalence
 * property - normalizing a trimmed document gives the same rows as the untrimmed one):
 * - issue `fields.comment`, `fields.worklog`, `fields.description`, `fields.attachment`: no reader (worklogs come from
 *   `raw.jira_worklogs`); `fields.subtasks` (a list of embedded child issues - the parent/child edge is the child's own `parent`, which
 *   the normalizer reads) and `fields.environment` (free text, empty on every real install measured): no reader either (trim rules v2);
 * - issue `fields.issuelinks[]`: slimmed to `{id, type: {name, inward, outward}, inwardIssue/outwardIssue: {id, key}}`
 *   ([slimIssueLinks]; Jira embeds the linked issue's summary, status, priority and type with icon URLs in every link), no reader (v2);
 * - issue `fields.assignee`/`reporter`/`creator`: slimmed to `{accountId, displayName, accountType}`; the normalizer reads the
 *   assignee's `accountId`/`displayName` and the reporter's `accountId`, the creator is unread;
 * - a worklog's `self` and `comment`, and its `author`/`updateAuthor` slimmed;
 * - a changelog history's `author` slimmed, and the `items` of a description edit and of a remote link
 *   ([DROPPED_HISTORY_ITEM_FIELDS]) removed: the normalizer tracks a closed list of items by `fieldId`, parent moves by name, and
 *   none of these is among them. **The history row itself is never dropped, even with `items: []`**: the backlog report counts
 *   every history as a touch of its issue (`reports/BacklogTouches.kt`).
 *
 * Everything else is kept - every other `fields.*`, every `customfield_*` with its history, `timetracking`, the history's
 * `id`/`created`/`historyMetadata`, a worklog's timestamps, `visibility` and `properties`.
 */

/** The issue `fields` keys that are not stored. */
internal val DROPPED_ISSUE_FIELDS: Set<String> = setOf("comment", "worklog", "description", "attachment", "subtasks", "environment")

/** The issue `fields` keys that hold an embedded user and are stored as a reference ([slimUserReference]). */
internal val SLIMMED_ISSUE_USER_FIELDS: Set<String> = setOf("assignee", "reporter", "creator")

/** The worklog keys that are not stored (`comment` is the free text, `self` a URL). */
internal val DROPPED_WORKLOG_KEYS: Set<String> = setOf("self", "comment")

/** A changelog item whose `fieldId` is one of these is not stored (a description edit). */
internal val DROPPED_HISTORY_ITEM_FIELD_IDS: Set<String> = setOf("description")

/**
 * A changelog item with NO `fieldId` (absent, null or not a string) whose display `field` is one of these is not stored: a
 * description edit again, and the two spellings of a remote-link change (Jira sends the latter with no `fieldId`). An item that
 * has a `fieldId` is judged by it alone ([DROPPED_HISTORY_ITEM_FIELD_IDS]): the display name of a custom field is free text, and
 * its history is kept.
 */
internal val DROPPED_HISTORY_ITEM_FIELDS: Set<String> = setOf("description", "RemoteIssueLink", "RemoteWorkItemLink")

/** [issue] without its dropped `fields` keys, users and issue links slimmed; anything but `{fields: {...}}` as is. */
internal fun trimIssue(issue: JsonElement): JsonElement {
    val fields = (issue as? JsonObject)?.get("fields") as? JsonObject ?: return issue
    val trimmed = JsonObject(
        fields.entries.filter { (key, _) -> key !in DROPPED_ISSUE_FIELDS }
            .associate { (key, value) ->
                key to when (key) {
                    in SLIMMED_ISSUE_USER_FIELDS -> slimUserReference(value)
                    ISSUE_LINKS_FIELD -> slimIssueLinks(value)
                    else -> value
                }
            },
    )
    return JsonObject(issue + ("fields" to trimmed))
}

/** [history] with its `author` slimmed and the items of [DROPPED_HISTORY_ITEM_FIELD_IDS]/[DROPPED_HISTORY_ITEM_FIELDS] removed. */
internal fun trimHistory(history: JsonElement): JsonElement {
    if (history !is JsonObject) return history
    val slim = slimUserReferences(history)
    val items = slim["items"] as? JsonArray ?: return slim
    return JsonObject(slim + ("items" to JsonArray(items.filterNot { it is JsonObject && isDroppedHistoryItem(it) })))
}

/** [worklog] without [DROPPED_WORKLOG_KEYS] and with its `author`/`updateAuthor` slimmed. */
internal fun trimWorklog(worklog: JsonElement): JsonElement =
    if (worklog is JsonObject) JsonObject(slimUserReferences(worklog).filterKeys { it !in DROPPED_WORKLOG_KEYS }) else worklog

private fun isDroppedHistoryItem(item: JsonObject): Boolean {
    val fieldId = item["fieldId"].stringOrNull()
    if (fieldId != null) return fieldId in DROPPED_HISTORY_ITEM_FIELD_IDS
    return item["field"].stringOrNull() in DROPPED_HISTORY_ITEM_FIELDS
}

/** The text of a JSON string value; `null` for an absent key, a JSON null or any non-string value (never throws). */
private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Parses [payloadJson] once, trims it ([trimIssue]) and renders it canonically - the text to store and to hash. For a caller that only
 * has text: the raw writers hold the parsed issue and call `canonicalJson(trimIssue(payload))` themselves (build-times WHY 48), the
 * same bytes without the re-parse.
 */
internal fun trimmedIssueCanonicalJson(payloadJson: String): String = canonicalJson(trimIssue(Json.parseToJsonElement(payloadJson)))

/** The same for a changelog history ([trimHistory]). */
internal fun trimmedHistoryCanonicalJson(payloadJson: String): String = canonicalJson(trimHistory(Json.parseToJsonElement(payloadJson)))

/** The same for a worklog ([trimWorklog]). */
internal fun trimmedWorklogCanonicalJson(payloadJson: String): String = canonicalJson(trimWorklog(Json.parseToJsonElement(payloadJson)))
