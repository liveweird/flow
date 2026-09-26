package ch.nokillswit.infra.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Canonical JSON for stored `jsonb` payloads (`infra/db/Jsonb.kt`): object keys sorted
 * RECURSIVELY (arrays keep their own order — position is meaning there), rendered through
 * kotlinx.serialization's compact `JsonElement.toString()`. Two structurally-equal payloads that
 * arrived with differently-ordered keys canonicalize to byte-identical text, so their
 * [sha256Hex] digests agree — the first consumer is the Jira raw store's per-payload hash
 * (v0.2.0 plan §4, V10, `jira_raw_issues.sha256`). "Exactly as received" means the same VALUES
 * survive the round trip, not the same bytes (`.claude/docs/persistence.md` "jsonb") — jsonb
 * storage itself reformats regardless of this step.
 */

/** Parses [raw] and re-renders it with every object's keys sorted recursively. */
fun canonicalJson(raw: String): String = canonicalize(Json.parseToJsonElement(raw)).toString()

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (key, value) -> key to canonicalize(value) })
    is JsonArray -> JsonArray(element.map(::canonicalize))
    is JsonPrimitive -> element
}

/** The lowercase hex SHA-256 digest of [text] (UTF-8) — canonicalize first for a value-stable hash. */
fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
