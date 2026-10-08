package ch.nokillswit.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Absent (Kotlin `null`) OR a literal JSON `null` both mean "no value" for a Jira object-shaped field — never a cast failure. */
internal fun JsonElement?.orNullObject(): JsonObject? = this?.takeIf { it != JsonNull }?.jsonObject

/**
 * The same for an array-shaped field. A real tenant sends an unset Sprint (or Flagged) custom field as JSON `null`, not
 * `[]`; `.jsonArray` threw on it and failed every issue without a sprint in PROCESS.
 */
internal fun JsonElement?.orNullArray(): JsonArray? = this?.takeIf { it != JsonNull }?.jsonArray
