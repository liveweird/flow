package ch.nokillswit.infra.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The string-array JSON codec every `jsonb`/text array column shares (`metrics.dim_domain.project_keys`,
 * `metrics.settings.holidays`, `norm.work_items.labels`, `norm.board_columns.status_ids`, ...): a compact
 * JSON array of strings, in list order.
 */
fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

/** The inverse of [stringArrayJson]. */
fun parseStringArray(json: String): List<String> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
