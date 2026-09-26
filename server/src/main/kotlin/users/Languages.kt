package ch.nokillswit.users

/**
 * The build-time supported-language set — mirrored by the SPA's `SUPPORTED_LANGUAGES` in
 * `web/src/i18n.ts` (a documented shared constant, like the enum-name whitelists). Adding a
 * language is a code change on both sides by design — server-side it also means a new
 * parameter on infra/mail/LocalizedText, which turns every email text in the codebase into
 * a compile error until translated (the server-side sibling of the SPA's locale-parity
 * gate). [DEFAULT_LANGUAGE] is what an omitted create-language resolves to and the
 * fallback for anything unknown.
 */
const val DEFAULT_LANGUAGE = "en"

val SUPPORTED_LANGUAGES: List<String> = listOf(DEFAULT_LANGUAGE, "pl")
