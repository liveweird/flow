package ch.nokillswit.jira

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The embedded-user normalization of the raw store (`.claude/docs/persistence.md` "The Jira raw store (V10)", "The Jira
 * changelog/worklog raw store (V11)"): a changelog history's `author`, a worklog's `author`/`updateAuthor` and an issue's
 * `assignee`/`reporter`/`creator` are stored as a REFERENCE to the person, not as the copy Jira embeds in every payload. This file
 * is the primitive; the places that apply it are the trim functions of `JiraPayloadTrim.kt`, i.e. every write of an issue, a
 * history or a worklog and the TRIM stream that rewrites older rows.
 *
 * Jira inlines the whole `USER` object there (`self`, four `avatarUrls`, `emailAddress`, `timeZone`, `active`, ...), about 75 % of
 * a history's bytes, repeated on every one of hundreds of thousands of rows. That is a denormalized copy of the `USER` entity
 * (`raw.jira_entities`, which keeps the full object once), not information about the change; Flow reads only `accountId` (and the
 * assignee's `displayName`). It also drops `emailAddress` (personal data): data minimization (`.claude/docs/security.md`).
 *
 * Kept keys: [KEPT_USER_KEYS] (each only when present; `accountType` stays so a later rule can tell humans from `app` bots). A
 * non-object user (absent, `null`, a string) is left untouched. The slimming runs BEFORE canonicalization and hashing, so the
 * stored text and a payload's sha256 agree and a re-sync of an unchanged document hashes identically (`UNCHANGED`, never a
 * spurious `CHANGED`).
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
