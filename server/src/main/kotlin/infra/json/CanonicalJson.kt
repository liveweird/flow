package ch.nokillswit.infra.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Canonical JSON for stored `jsonb` payloads (`infra/db/Jsonb.kt`): object keys sorted
 * RECURSIVELY (arrays keep their own order — position is meaning there), rendered in kotlinx.serialization's compact form: the
 * structure is written here, straight to text (no sorted copy of the tree), and only each LEAF goes through kotlinx's own
 * `JsonPrimitive.toString()` (number content and string escaping). Two structurally-equal payloads that
 * arrived with differently-ordered keys canonicalize to byte-identical text, so their
 * [sha256Hex] digests agree — the first consumer is the Jira raw store's per-payload hash
 * (v0.2.0 plan §4, V10, `jira_raw_issues.sha256`). "Exactly as received" means the same VALUES
 * survive the round trip, not the same bytes (`.claude/docs/persistence.md` "jsonb") — jsonb
 * storage itself reformats regardless of this step.
 *
 * **The one value exception: U+0000 never survives.** PostgreSQL `jsonb` (and `text`) cannot store a NUL
 * (`unsupported Unicode escape sequence`), and a single one in a Jira description or comment used to fail the
 * ISSUES page forever. So [canonicalJson] replaces every U+0000 in a string VALUE and in an object KEY with
 * U+FFFD ([replaceNul]) — before the text is hashed, so the stored text and its [sha256Hex] agree. U+FFFD marks that
 * something was there and, unlike dropping the character, can never merge the two tokens around it. Every other
 * character (other control characters included) is kept. A payload without a NUL canonicalizes to the same bytes as
 * before, and a payload with one could never be stored, so no stored hash moves. Two keys of one object that collide only
 * after the replacement (`"a\u0000"` and `"a�"`) merge into one: the later one in document order wins, deterministically.
 */

/** The character PostgreSQL's `jsonb`/`text` cannot store. */
private const val NUL = '\u0000'

/** What [replaceNul] puts in its place: the Unicode replacement character. */
private const val NUL_REPLACEMENT = '\uFFFD'

/**
 * [this] with every U+0000 replaced by U+FFFD (see the file's rule); the very same instance, no copy, when it holds none.
 * Use it on any free text bound for a PostgreSQL `text`/`varchar` column that did not come through [canonicalJson].
 */
fun String.replaceNul(): String = if (indexOf(NUL) < 0) this else replace(NUL, NUL_REPLACEMENT)

/** Parses [raw] and re-renders it with every object's keys sorted recursively and every U+0000 replaced ([replaceNul]). */
fun canonicalJson(raw: String): String = canonicalJson(Json.parseToJsonElement(raw))

/**
 * The same for an already-parsed [element] — for a caller that holds the tree (the Jira raw writers canonicalize the very tree the
 * stream parsed, never its text re-parsed). It is written straight to text: only each object's key list is sorted, values are
 * appended in that order, so NO sorted copy of the tree is built (a dense Jira response keeps one tree alive, not two;
 * `.claude/docs/build-times.md` WHY 48). Byte-identical to rendering a recursively sorted copy through `JsonElement.toString()`
 * (`CanonicalJsonWriterEqualityTest` pins that against the old implementation): every leaf is rendered by kotlinx.serialization's
 * own `JsonPrimitive.toString()`, so number content and string escaping are the library's, not a re-implementation.
 */
fun canonicalJson(element: JsonElement): String = StringBuilder().apply { appendCanonical(element) }.toString()

private fun StringBuilder.appendCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> appendCanonicalObject(element)
        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, value ->
                if (index > 0) append(',')
                appendCanonical(value)
            }
            append(']')
        }
        is JsonPrimitive -> {
            val hasNul = element.isString && element.content.indexOf(NUL) >= 0
            append(if (hasNul) JsonPrimitive(element.content.replaceNul()).toString() else element.toString())
        }
    }
}

/**
 * Keys sorted by their [replaceNul]ed text (stable, so document order breaks ties). Two keys equal only after the replacement are
 * adjacent: the run is written once, at its first position, with the LAST value — what building a map from the sorted entries gives.
 */
private fun StringBuilder.appendCanonicalObject(obj: JsonObject) {
    append('{')
    val entries = obj.entries.sortedBy { it.key.replaceNul() }
    var written = 0
    for (index in entries.indices) {
        val key = entries[index].key.replaceNul()
        if (index + 1 < entries.size && entries[index + 1].key.replaceNul() == key) continue
        if (written++ > 0) append(',')
        append(JsonPrimitive(key).toString()).append(':')
        appendCanonical(entries[index].value)
    }
    append('}')
}

/** The lowercase hex SHA-256 digest of [text] (UTF-8) — canonicalize first for a value-stable hash. */
fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
