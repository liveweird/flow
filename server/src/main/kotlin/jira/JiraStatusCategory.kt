package ch.nokillswit.jira

import ch.nokillswit.norm.StatusCategory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Jira status category in either of its two shapes ([JiraNormalizer.statusRefs]): the plain string enum
 * `GET /statuses/search` returns (`"TODO"`/`"IN_PROGRESS"`/`"DONE"`), or the object an issue's `fields.status` carries
 * (`{"key":"new"|"indeterminate"|"done"}`). Anything else, including `UNDEFINED`, absent or a wrong JSON type, is
 * [StatusCategory.UNKNOWN], never a throw.
 */
internal fun jiraStatusCategory(element: JsonElement?): StatusCategory = when (element) {
    is JsonPrimitive -> if (element.isString) statusCategoryForEnum(element.content) else StatusCategory.UNKNOWN
    is JsonObject -> statusCategoryForKey((element["key"] as? JsonPrimitive)?.contentOrNull)
    else -> StatusCategory.UNKNOWN
}

private fun statusCategoryForEnum(name: String): StatusCategory = when (name) {
    "TODO" -> StatusCategory.TODO
    "IN_PROGRESS" -> StatusCategory.IN_PROGRESS
    "DONE" -> StatusCategory.DONE
    else -> StatusCategory.UNKNOWN
}

private fun statusCategoryForKey(key: String?): StatusCategory = when (key) {
    "new" -> StatusCategory.TODO
    "indeterminate" -> StatusCategory.IN_PROGRESS
    "done" -> StatusCategory.DONE
    else -> StatusCategory.UNKNOWN
}
