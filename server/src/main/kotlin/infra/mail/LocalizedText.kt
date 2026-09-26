package ch.nokillswit.infra.mail

/**
 * A server-composed text in every supported language (Lettuce's, ported with the
 * password-reset feature). The constructor arity IS the add-a-language gate: adding a
 * supported language means adding a parameter here, which turns every email text in the
 * codebase into a compile error until translated — the server-side sibling of the SPA's
 * locale-parity gate. [of] resolves the recipient's language with the English fallback for
 * anything unknown. Call sites pass the recipient's stored language (V1 users.language).
 */
internal data class LocalizedText(val en: String, val pl: String) {
    /** The recipient's text, English fallback for anything unknown. */
    fun of(language: String): String = when (language) {
        "pl" -> pl
        else -> en
    }
}
