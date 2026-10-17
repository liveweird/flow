package ch.nokillswit.jira

import ch.nokillswit.infra.json.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The ONE documented exception to the raw store's "exactly as Jira returned it" rule (`.claude/docs/persistence.md` "The Jira
 * changelog/worklog raw store (V11)", `.claude/docs/ingestion.md` "CHANGELOGS stream"): a changelog history's `author` and a
 * worklog's `author`/`updateAuthor` are stored as a REFERENCE to the person, not as the copy Jira embeds in every payload.
 *
 * Jira inlines the whole `USER` object there (`self`, four `avatarUrls`, `emailAddress`, `timeZone`, `active`, ...), about 75 % of
 * a history's bytes, repeated on every one of hundreds of thousands of rows. That is a denormalized copy of the `USER` entity
 * (`raw.jira_entities`, which keeps the full object once), not information about the change; Flow reads only `accountId`. It also
 * drops `emailAddress` (personal data) from those rows: data minimization (`.claude/docs/security.md`).
 *
 * Kept keys: [KEPT_USER_KEYS] (each only when present; `accountType` stays so a later rule can tell humans from `app` bots). A
 * non-object `author` (absent, `null`, a string) is left untouched. Applied by [slimmedCanonicalJson] — i.e. on every write of
 * a changelog history or a worklog — BEFORE canonicalization and hashing, so the stored text and a worklog's sha256 agree and a
 * re-sync of an unchanged worklog hashes identically (`UNCHANGED`, never a spurious `CHANGED`).
 *
 * Existing rows are NOT rewritten (no backfill, by decision): an install keeps its old full rows until it is set up afresh.
 */
internal val KEPT_USER_KEYS: List<String> = listOf("accountId", "displayName", "accountType")

/** The top-level payload keys holding an embedded user: a history's `author`; a worklog's `author` and `updateAuthor`. */
internal val USER_REFERENCE_FIELDS: List<String> = listOf("author", "updateAuthor")

/** [user] reduced to [KEPT_USER_KEYS] when it is an object (a missing key stays missing); any other JSON value is returned as is. */
internal fun slimUserReference(user: JsonElement): JsonElement =
    if (user is JsonObject) JsonObject(user.filterKeys { it in KEPT_USER_KEYS }) else user

/** [payload] with each [USER_REFERENCE_FIELDS] entry that is present slimmed ([slimUserReference]); every other key untouched. */
internal fun slimUserReferences(payload: JsonObject): JsonObject =
    JsonObject(payload.mapValues { (key, value) -> if (key in USER_REFERENCE_FIELDS) slimUserReference(value) else value })

/** Parses [payloadJson], slims its user references, and renders it canonically ([canonicalJson]) — the text to store and to hash. */
internal fun slimmedCanonicalJson(payloadJson: String): String {
    val parsed = Json.parseToJsonElement(payloadJson)
    return canonicalJson(if (parsed is JsonObject) slimUserReferences(parsed) else parsed)
}
