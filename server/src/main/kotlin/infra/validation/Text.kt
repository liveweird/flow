package ch.nokillswit.infra.validation

import io.ktor.server.plugins.BadRequestException
import java.text.Normalizer

/**
 * Canonical single-line identity fields (names and the like): trimmed, and control characters
 * are a clean 400 instead of stored garbage. Ported from Lettuce.
 */
fun sanitizeSingleLine(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.any { it.isISOControl() }) {
        throw BadRequestException("$field must not contain control characters")
    }
    return trimmed
}

/** The optional description every registry carries: trimmed, control characters a 400, blank = absent. */
fun sanitizedDescription(raw: String?): String? = raw?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() }

/** The name/description length rule every registry enforces — one wording, so the SPA's messages never drift per area. */
fun requireNameAndDescription(name: String, description: String?, maxName: Int, maxDescription: Int) {
    if (name.isBlank() || name.length > maxName) throw BadRequestException("Name must be 1-$maxName characters")
    if (description != null && description.length > maxDescription) {
        throw BadRequestException("Description must be at most $maxDescription characters")
    }
}

/**
 * The ONE canonical form of an email address (trimmed, lower-cased): Flow-user logins, the Jira connection's account, and GitLab identities
 * (`norm/CodeNormalization.kt` hashes it into an identity key; PR 11's identity proposals compare it). Applied at EVERY entry point so one
 * mailbox is one account (`ADMIN@x` cannot create a second account beside `admin@x`, and a padded/case-variant login matches). A pure fold
 * that never throws: login must stay a uniform 401 on garbage.
 */
fun canonicalEmail(raw: String): String = raw.trim().lowercase()

private val COMBINING_MARKS = Regex("\\p{M}+")

/** Letters NFD does not decompose, folded like the SPA's `foldDiacritics` (and `unaccent`): `ł` is `l`, `ß` is `ss`. */
private val NON_DECOMPOSING = mapOf('ł' to "l", 'đ' to "d", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss")

/**
 * The search fold: lower-cased, decomposed (NFD), combining marks stripped, and the few non-decomposing letters mapped - so `Zoë` and
 * `zoe`, `Łukasz` and `lukasz` fold alike. The in-memory twin of `containsNormalized` (`infra/db/Sql.kt`, `unaccent` then `LOWER`) and
 * of the SPA's `foldDiacritics`, for the one list that filters in memory instead of in SQL (the code identities, `list-endpoints.md`).
 */
fun foldForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    return buildString(decomposed.length) { decomposed.forEach { append(NON_DECOMPOSING[it] ?: it) } }
}
