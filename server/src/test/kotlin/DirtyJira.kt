package ch.nokillswit

import ch.nokillswit.jira.SLIMMED_ISSUE_USER_FIELDS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Jira payloads as Jira sends them: a clean (stored, trimmed) payload with the keys the raw store drops put back - full users, free
 * text, description and remote-link items - for the tests of the payload trim (`JiraPayloadTrimTest`).
 */
internal object DirtyJira {
    fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Free text of the kind the raw store drops. */
    val ADF = adf("free text")

    fun adf(text: String) = """{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""

    /** A changelog item as Jira sends it; a null [fieldId] leaves the key out (remote-link items have none). */
    fun item(field: String, fieldId: String?, from: String?, to: String?, fromString: String?, toString: String?): String {
        val id = if (fieldId == null) "" else """"fieldId":"$fieldId","""
        fun v(key: String, value: String?) = if (value == null) """"$key":null""" else """"$key":"$value""""
        val values = listOf(v("from", from), v("fromString", fromString), v("to", to), v("toString", toString)).joinToString(",")
        return """{"field":"$field",$id"fieldtype":"jira",$values}"""
    }

    val DESCRIPTION_EDIT = item("description", "description", null, null, "old text", "new text")
    val REMOTE_LINK = item("RemoteIssueLink", null, null, "10000", null, "https://example.com/page")
    val REMOTE_WORK_ITEM_LINK = item("RemoteWorkItemLink", null, null, "10001", null, "https://example.com/other")

    /** `fields.subtasks` as Jira sends it: the embedded child issues. */
    const val SUBTASKS: String =
        """[{"id":"10002","key":"ABC-2","self":"https://x.atlassian.net/rest/api/3/issue/10002","fields":{"summary":"child"}}]"""

    /** A linked issue as Jira embeds it in a link: `self` and a whole `fields` object next to the `id` and `key` Flow keeps. */
    fun fatLinkedIssue(id: String, key: String) =
        """{"id":"$id","key":"$key","self":"https://x.atlassian.net/rest/api/3/issue/$id","fields":{"summary":"linked $key",""" +
            """"status":{"name":"Done","iconUrl":"https://x/s.png"},"priority":{"name":"High","iconUrl":"https://x/p.png"},""" +
            """"issuetype":{"id":"1","name":"Bug","iconUrl":"https://x/b.png","subtask":false}}}"""

    /** A link type as Jira sends it (with `id` and `self`). */
    const val FAT_LINK_TYPE: String =
        """{"id":"10000","name":"Blocks","inward":"is blocked by","outward":"blocks",""" +
            """"self":"https://x.atlassian.net/rest/api/3/issueLinkType/10000"}"""

    /** An `issuelinks` element as Jira sends it, in [direction] (`inwardIssue` or `outwardIssue`), with an unknown extra key. */
    fun fatLink(id: String, direction: String, linkedId: String, linkedKey: String) =
        """{"id":"$id","self":"https://x.atlassian.net/rest/api/3/issueLink/$id","type":$FAT_LINK_TYPE,""" +
            """"$direction":${fatLinkedIssue(linkedId, linkedKey)},"extra":true}"""

    /** [user] with the keys Jira adds to an embedded user (e-mail address, time zone, ...); a non-user value as it is. */
    fun fullOf(user: JsonElement): JsonElement {
        val base = user as? JsonObject ?: return user
        val id = (base["accountId"] as? JsonPrimitive)?.content ?: return user
        val extra = mapOf(
            "emailAddress" to JsonPrimitive("$id@example.com"),
            "active" to JsonPrimitive(true),
            "timeZone" to JsonPrimitive("UTC"),
        )
        return JsonObject(base + extra)
    }

    /** [payload] with full assignee/reporter/creator (where present) and a description, comment, worklog and attachment added. */
    fun injectIntoIssue(payload: String): String {
        val issue = obj(payload)
        val fields = issue.getValue("fields").jsonObject
        val dirty = fields.toMutableMap()
        for (key in SLIMMED_ISSUE_USER_FIELDS) fields[key]?.let { dirty[key] = fullOf(it) }
        dirty["description"] = obj(ADF)
        dirty["comment"] = obj("""{"comments":[{"id":"1","body":$ADF}],"total":1}""")
        dirty["worklog"] = obj("""{"worklogs":[{"id":"1","comment":$ADF}],"total":1}""")
        dirty["attachment"] = Json.parseToJsonElement("""[{"id":"3","filename":"secret.pdf"}]""")
        dirty["subtasks"] = Json.parseToJsonElement(SUBTASKS)
        dirty["environment"] = obj(adf("prod"))
        return JsonObject(issue + ("fields" to JsonObject(dirty))).toString()
    }

    /** A dropped item at the front, one in the middle and one at the end, and the author made a full user (the account id kept). */
    fun injectIntoHistory(payload: String, index: Int): JsonObject {
        val history = obj(payload)
        val items = history.getValue("items").jsonArray.toMutableList()
        items.add(0, obj(DESCRIPTION_EDIT))
        items.add(items.size / 2 + 1, obj(if (index % 2 == 0) REMOTE_LINK else REMOTE_WORK_ITEM_LINK))
        items.add(obj(item("description", null, null, null, "from", "to")))
        val author = history["author"]?.let { mapOf("author" to fullOf(it)) }.orEmpty()
        return JsonObject(history + mapOf("items" to JsonArray(items)) + author)
    }

    /** [payload] with `self`, a rich-text `comment` and full authors (where present) added. */
    fun injectIntoWorklog(payload: String): JsonObject {
        val worklog = obj(payload)
        val extra = mapOf("self" to JsonPrimitive("https://x.atlassian.net/w"), "comment" to obj(ADF))
        val users = listOf("author", "updateAuthor").mapNotNull { key -> worklog[key]?.let { key to fullOf(it) } }.toMap()
        return JsonObject(worklog + extra + users)
    }
}
