package ch.nokillswit.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The issue-link normalization of the raw store (`.claude/docs/persistence.md` "The Jira raw store (V10)"): `fields.issuelinks[]` is
 * stored as a REFERENCE to the linked issue, not as the copy Jira embeds. Jira inlines the linked issue's `fields` (summary, status,
 * priority, issue type with icon URLs) and `self` links in every link of every issue - about 15 % of the stored issue text on a real
 * install, for a key no reader uses today. Kept: the link's `id`, its `type` reduced to `name`/`inward`/`outward` ([KEPT_LINK_TYPE_KEYS])
 * and the linked issue (`inwardIssue` / `outwardIssue`) reduced to `id`/`key` ([KEPT_LINKED_ISSUE_KEYS]) - enough to rebuild the link
 * graph; everything else about the linked issue lives on its own issue row.
 *
 * Total like [slimUserReference]: a value of an unexpected shape (not an array, an element or a `type` that is not an object) is left
 * exactly as it is; a missing key stays missing; an unknown extra key inside a link is dropped. Idempotent. Like the user slimming it
 * runs BEFORE canonicalization and hashing (via `trimIssue`), so the stored text and its sha256 agree.
 */
internal const val ISSUE_LINKS_FIELD = "issuelinks"

/** The keys of one `issuelinks[]` element that are stored. */
internal val KEPT_LINK_KEYS: List<String> = listOf("id", "type", "inwardIssue", "outwardIssue")

/** The keys of a link's `type` that are stored (`id` and `self` are not). */
internal val KEPT_LINK_TYPE_KEYS: List<String> = listOf("name", "inward", "outward")

/** The keys of a link's `inwardIssue` / `outwardIssue` that are stored (`self` and the embedded `fields` are not). */
internal val KEPT_LINKED_ISSUE_KEYS: List<String> = listOf("id", "key")

/** The link keys that hold an embedded linked issue. */
internal val LINKED_ISSUE_KEYS: List<String> = listOf("inwardIssue", "outwardIssue")

private fun JsonElement.keepKeys(keys: List<String>): JsonElement = if (this is JsonObject) JsonObject(filterKeys { it in keys }) else this

/** [link] reduced to [KEPT_LINK_KEYS], its `type` and linked issues reduced to their kept keys; a non-object as is. */
internal fun slimIssueLink(link: JsonElement): JsonElement {
    if (link !is JsonObject) return link
    return JsonObject(
        link.filterKeys { it in KEPT_LINK_KEYS }.mapValues { (key, value) ->
            when (key) {
                "type" -> value.keepKeys(KEPT_LINK_TYPE_KEYS)
                in LINKED_ISSUE_KEYS -> value.keepKeys(KEPT_LINKED_ISSUE_KEYS)
                else -> value
            }
        },
    )
}

/** Every element of [links] slimmed ([slimIssueLink]); any value but an array as is. */
internal fun slimIssueLinks(links: JsonElement): JsonElement = if (links is JsonArray) JsonArray(links.map(::slimIssueLink)) else links
