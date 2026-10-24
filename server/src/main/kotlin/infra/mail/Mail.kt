package ch.nokillswit.infra.mail

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.util.AttributeKey

/**
 * The configured [Mailer], or null when `mail.transport` is `disabled` — consumers (the
 * password-reset endpoint and email MFA) treat null as
 * "email features unavailable" and answer 503.
 */
val MailerKey = AttributeKey<MailerHolder>("Mailer")

/** AttributeKey cannot hold a nullable type, so the optional mailer travels in a holder. */
class MailerHolder(val mailer: Mailer?)

/** The configured mailer, or null when `mail.transport` is `disabled`. */
fun Application.mailer(): Mailer? = attributes[MailerKey].mailer

/** The validated, trailing-slash-trimmed `mail.appUrl` origin (null when blank), held like [MailerHolder] (no nullable keys). */
val MailAppUrlKey = AttributeKey<MailAppUrlHolder>("MailAppUrl")

class MailAppUrlHolder(val origin: String?)

/**
 * The deployment's public origin for the links in emails (the self-service password reset), or null when unconfigured.
 * Configured, never derived from the request `Host`; the format was validated at boot by [configureMail].
 */
fun Application.mailAppUrl(): String? = attributes[MailAppUrlKey].origin

private const val MAX_APP_URL_LENGTH = 2048
private const val MAX_URL_PORT = 65535

/**
 * Validates `mail.appUrl` as an origin and returns it without a trailing slash, or null when blank. A malformed value
 * refuses startup in ANY mode: it can never produce a correct link, and a wrong origin would mail reset tokens to
 * another host. `http` is accepted in development mode only. The message never echoes the value.
 */
internal fun validatedAppUrlOrigin(value: String, development: Boolean): String? {
    if (value.isBlank()) return null
    val uri = try {
        java.net.URI(value)
    } catch (_: java.net.URISyntaxException) {
        null
    }
    val valid = uri != null && value.length <= MAX_APP_URL_LENGTH &&
        (uri.scheme == "https" || (development && uri.scheme == "http")) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.orEmpty() in setOf("", "/") && uri.port in -1..MAX_URL_PORT
    check(valid) {
        "mail.appUrl must be the deployment's https origin (http only in development), " +
            "without path, query, fragment or credentials"
    }
    return value.trimEnd('/')
}

/** The uniform 503 for email-dependent features on a `mail.transport=disabled` deployment. */
suspend fun ApplicationCall.respondMailUnavailable(feature: String) =
    respondProblem(
        HttpStatusCode.ServiceUnavailable,
        "This deployment cannot send email — $feature is unavailable",
    )

/**
 * Selects the outbound-email transport from the `mail:` config block and publishes it as
 * [MailerKey]. Registered in application.yaml at the top of the infrastructure group, before
 * the feature modules that will consume it.
 *
 * Transports:
 *  - `log` (dev default) — messages go to the `ch.nokillswit.mail` logger instead of being sent.
 *    Message bodies carry password-reset links and MFA codes, so production mode REFUSES this
 *    transport at startup (same fail-closed family as the JWT-secret and seed-password checks).
 *  - `smtp` — real delivery via `mail.smtp.*`; a blank host refuses startup in ANY mode (it
 *    cannot possibly work, and failing late would drop transactional emails silently).
 *  - `disabled` (the Docker image default) — email features off; a null mailer is published.
 */
fun Application.configureMail() {
    val transport = environment.config.property("mail.transport").getString().trim().lowercase()
    val mailer: Mailer? = when (transport) {
        "disabled" -> null
        "log" -> {
            val message =
                "mail.transport=log writes outbound email (including password-reset links and MFA codes) to the " +
                    "application log. Set MAIL_TRANSPORT=smtp with real SMTP settings, or disabled."
            if (developmentMode) log.warn("$message (permitted in development only)")
            else error(message)
            LogMailer()
        }
        "smtp" -> {
            val host = environment.config.property("mail.smtp.host").getString()
            if (host.isBlank()) {
                error("mail.transport=smtp requires a non-blank SMTP_HOST (mail.smtp.host).")
            }
            SmtpMailer(
                host = host,
                port = requireConfigInt(environment.config, "mail.smtp.port", min = 1, max = 65535),
                user = environment.config.property("mail.smtp.user").getString(),
                password = environment.config.property("mail.smtp.password").getString(),
                from = environment.config.property("mail.from").getString(),
                startTls = environment.config.property("mail.smtp.startTls").getString().toBoolean(),
            )
        }
        else -> error("Unknown mail.transport '$transport' — expected log, smtp, or disabled.")
    }
    attributes.put(MailerKey, MailerHolder(mailer))
    val appUrl = validatedAppUrlOrigin(environment.config.propertyOrNull("mail.appUrl")?.getString().orEmpty().trim(), developmentMode)
    if (appUrl == null && transport != "disabled") {
        log.warn("mail.appUrl is blank — the self-service password reset answers 503 until MAIL_APP_URL is set")
    }
    attributes.put(MailAppUrlKey, MailAppUrlHolder(appUrl))
}
