package ch.nokillswit.jira

import ch.nokillswit.infra.json.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val DELETED_STATUS_PREFIX = "deleted:"
private const val DELETED_STATUS_NO_NAME = "?"
private const val DELETED_STATUS_HASH_SEPARATOR = "~"
private const val DELETED_STATUS_HASH_LENGTH = 8

/** Every `status_id` column is `VARCHAR(50)` (V13/V15/V16). */
private const val STATUS_ID_MAX_LENGTH = 50

/**
 * The synthetic status id for a changelog status item side Jira gave no id for — a status that was
 * deleted in Jira after the transition keeps its `fromString`/`toString` but loses `from`/`to`.
 *
 * The id is derived from the display name ([name], exactly as Jira gave it — never trimmed, only
 * checked for blankness), so it is stable: two sides lacking ids with the same name get the same id
 * and the status chain stays continuous. `"deleted:" + name` when that fits in 50 characters, else
 * `"deleted:" + name.take(n) + "~" + <first 8 hex of SHA-256(full name)>` totalling at most 50, so
 * two long names sharing a prefix still differ. No name (null or blank) gives `"deleted:?"`.
 *
 * The id matches no `norm.statuses` row, so PROCESS's status lookup resolves it to `(id, UNKNOWN)`:
 * the display name IS the id and the interval lands in the UNMAPPED stage — flagged, never guessed.
 */
internal fun deletedStatusId(name: String?): String {
    if (name.isNullOrBlank()) return DELETED_STATUS_PREFIX + DELETED_STATUS_NO_NAME
    if (DELETED_STATUS_PREFIX.length + name.length <= STATUS_ID_MAX_LENGTH) return DELETED_STATUS_PREFIX + name
    val keep = STATUS_ID_MAX_LENGTH - DELETED_STATUS_PREFIX.length - DELETED_STATUS_HASH_SEPARATOR.length - DELETED_STATUS_HASH_LENGTH
    // Never cut a surrogate pair in half (the result may then be one char shorter, still <= 50).
    val head = name.take(keep).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return DELETED_STATUS_PREFIX + head + DELETED_STATUS_HASH_SEPARATOR + sha256Hex(name).take(DELETED_STATUS_HASH_LENGTH)
}

/**
 * One side of a changelog status item: the real id under [idKey] (`from`/`to`), or — when it is
 * absent, JSON null or blank — [deletedStatusId] of the display name under [nameKey].
 */
internal fun JsonObject.statusSideId(idKey: String, nameKey: String): String =
    this[idKey]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: deletedStatusId(this[nameKey]?.jsonPrimitive?.contentOrNull)
