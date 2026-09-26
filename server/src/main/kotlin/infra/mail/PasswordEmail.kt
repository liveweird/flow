package ch.nokillswit.infra.mail

private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")
private val SIGN_IN = LocalizedText(en = "Sign in", pl = "Zaloguj się")

/**
 * Shared scaffold for the transactional emails that deliver a generated password, rendered
 * in the RECIPIENT'S language (Lettuce's, ported with the password-reset feature).
 * Side-effect-free and directly unit-tested (LocalizedEmailTest); the feature-specific
 * wrappers (auth/PasswordResetEmail.kt today, users/WelcomeEmail.kt when that arrives)
 * contribute only their intro copy. The sign-in link renders only when the deployment's
 * `mail.appUrl` is configured.
 */
internal fun passwordEmail(
    name: String,
    intro: LocalizedText,
    passwordLabel: LocalizedText,
    password: String,
    appUrl: String?,
    language: String,
): String = buildString {
    appendLine("${GREETING.of(language)} $name,")
    appendLine()
    appendLine(intro.of(language))
    appendLine()
    appendLine("${passwordLabel.of(language)}:")
    appendLine()
    appendLine(password)
    if (!appUrl.isNullOrBlank()) {
        appendLine()
        appendLine("${SIGN_IN.of(language)}: $appUrl")
    }
}
