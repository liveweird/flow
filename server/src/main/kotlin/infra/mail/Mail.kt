package ch.nokillswit.infra.mail

import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.util.AttributeKey

/**
 * The configured [Mailer], or null when `mail.transport` is `disabled` — consumers (the
 * password-reset endpoint and email MFA, when they are ported from Lettuce) treat null as
 * "email features unavailable" and answer 503.
 */
val MailerKey = AttributeKey<MailerHolder>("Mailer")

/** AttributeKey cannot hold a nullable type, so the optional mailer travels in a holder. */
class MailerHolder(val mailer: Mailer?)

/** The configured mailer, or null when `mail.transport` is `disabled`. */
fun Application.mailer(): Mailer? = attributes[MailerKey].mailer

/** The deployment's public URL for sign-in links in emails, or null when unconfigured. */
fun Application.mailAppUrl(): String? =
    environment.config.propertyOrNull("mail.appUrl")?.getString()?.takeIf { it.isNotBlank() }

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
 *    Message bodies can contain freshly generated passwords, so production mode REFUSES this
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
                "mail.transport=log writes outbound email (including generated passwords) to the " +
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
                port = environment.config.property("mail.smtp.port").getString().toInt(),
                user = environment.config.property("mail.smtp.user").getString(),
                password = environment.config.property("mail.smtp.password").getString(),
                from = environment.config.property("mail.from").getString(),
                startTls = environment.config.property("mail.smtp.startTls").getString().toBoolean(),
            )
        }
        else -> error("Unknown mail.transport '$transport' — expected log, smtp, or disabled.")
    }
    attributes.put(MailerKey, MailerHolder(mailer))
}
