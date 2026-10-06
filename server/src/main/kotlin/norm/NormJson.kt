package ch.nokillswit.norm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The `norm.work_items` JSON-array columns' writers and their inverse readers — kept as pairs, so a column's
// encoding changes in exactly one place (the PROCESS write path and every read path import from here).

internal fun longArrayJson(values: List<Long>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

internal fun anomaliesJson(values: List<TilingAnomaly>): String =
    buildJsonArray { values.forEach { add(JsonPrimitive(it.name)) } }.toString()

/** The inverse of [anomaliesJson] — the raw issue inspector's/data profile's own read path. */
internal fun parseAnomalies(json: String): List<TilingAnomaly> =
    Json.parseToJsonElement(json).jsonArray.map { TilingAnomaly.valueOf(it.jsonPrimitive.content) }

/** The inverse of [longArrayJson] (`norm.work_items.current_sprint_ids`) — the DERIVE read's own reader. */
internal fun parseLongArray(json: String): List<Long> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.long }
