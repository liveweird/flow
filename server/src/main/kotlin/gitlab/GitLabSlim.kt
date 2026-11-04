package ch.nokillswit.gitlab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// The slim payloads the raw store keeps (V41): a projection of GitLab's document, never the whole of it - no description, avatar,
// statistics or permissions. The projection IS what the sha256 hashes, so a field GitLab adds to its document never moves a hash.
// The key lists are `internal` so `GitLabRawPayloadWhitelistTest` can pin every stored payload against them.

internal val PROJECT_KEYS = listOf(
    "id", "path", "path_with_namespace", "name", "default_branch", "archived", "visibility", "created_at", "last_activity_at",
)
internal val NAMESPACE_KEYS = listOf("id", "full_path", "kind", "parent_id")
internal val USER_KEYS = listOf(
    "id", "username", "name", "state", "bot", "is_admin", "locked", "external", "public_email", "email", "commit_email", "created_at",
    "last_activity_on",
)
private val EMAIL_FIELDS = listOf("email", "commit_email", "public_email")

private fun JsonObject.pick(keys: List<String>): Map<String, JsonElement> =
    keys.mapNotNull { key -> get(key)?.takeUnless { it is JsonNull }?.let { key to it } }.toMap()

/** The project document reduced to [PROJECT_KEYS] plus `namespace{id, full_path, kind, parent_id}`. */
internal fun slimProject(project: JsonElement): JsonObject {
    val source = project.jsonObject
    val namespace = (source["namespace"] as? JsonObject)?.pick(NAMESPACE_KEYS)
    return JsonObject(source.pick(PROJECT_KEYS) + listOfNotNull(namespace?.let { "namespace" to JsonObject(it) }))
}

/** The user document reduced to [USER_KEYS] plus the merged, normalised `emails` array. */
internal fun slimUser(user: JsonElement, emails: List<String>): JsonObject =
    JsonObject(user.jsonObject.pick(USER_KEYS) + ("emails" to JsonArray(emails.map(::JsonPrimitive))))

/** The text of a string member, null for a missing, null or non-string one. */
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * The sorted, distinct, lowercased union of the user's own `email`/`commit_email`/`public_email` and the [secondary] emails
 * (`/users/:id/emails`): the identity proposals' input (PR 11). Blank entries are dropped; an email is never logged.
 */
internal fun gitLabUserEmails(user: JsonObject, secondary: List<String>): List<String> = mergeEmails(ownEmailsOf(user), secondary)

/** The user's own `email`/`commit_email`/`public_email` values, as sent (the list shows them to an admin token only). */
internal fun ownEmailsOf(user: JsonObject): List<String> = EMAIL_FIELDS.mapNotNull { user.text(it) }

/** The sorted, distinct, lowercased union of [own] and [secondary] emails, blanks dropped. */
internal fun mergeEmails(own: List<String>, secondary: List<String>): List<String> =
    (own + secondary).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()

/** The `email` members of a `/users/:id/emails` answer (`[{id, email, confirmed_at}]`); anything else is skipped. */
internal fun secondaryEmails(answer: JsonArray): List<String> =
    answer.mapNotNull { (it as? JsonObject)?.text("email") }
