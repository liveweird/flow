package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.flowusers.FlowUserService
import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.Mailer
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException

/**
 * The self-service reset emails (2D3; Toadie's), rendered in the RECIPIENT'S stored language (V1, infra/mail/LocalizedText.kt):
 * the link email and the post-reset notice. No email carries a password — the owner chooses it on the link page.
 */
internal val PASSWORD_RESET_EMAIL_SUBJECT = LocalizedText(
    en = "Reset your Flow password",
    pl = "Zresetuj hasło Flow",
)
private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")
private val INTRO = LocalizedText(
    en = "A password reset was requested for your Flow account. Your password is unchanged. " +
        "If you did not request this, ignore this email. Use the single-use link below to choose a new password.",
    pl = "Ktoś poprosił o zresetowanie hasła do Twojego konta Flow. Twoje hasło pozostaje bez zmian. " +
        "Jeśli to nie Ty prosiłeś/aś o reset, zignoruj tę wiadomość. Użyj jednorazowego linku poniżej, aby wybrać nowe hasło.",
)
private val EXPIRY = LocalizedText(en = "Link lifetime (minutes)", pl = "Ważność linku (minuty)")
internal val PASSWORD_RESET_COMPLETED_SUBJECT = LocalizedText(
    en = "Your Flow password was reset",
    pl = "Twoje hasło Flow zostało zresetowane",
)
internal val PASSWORD_RESET_COMPLETED_BODY = LocalizedText(
    en = "Your Flow password was reset and your existing sessions were signed out. " +
        "If you did not do this, contact your administrator immediately.",
    pl = "Twoje hasło Flow zostało zresetowane, a istniejące sesje zostały wylogowane. " +
        "Jeśli to nie Ty zresetowałeś/aś hasło, natychmiast skontaktuj się z administratorem.",
)

internal fun passwordResetEmailBody(name: String, token: String, appUrl: String, ttlMillis: Long, language: String): String =
    buildString {
        appendLine("${GREETING.of(language)} $name,")
        appendLine()
        appendLine(INTRO.of(language))
        appendLine("${EXPIRY.of(language)}: ${(ttlMillis / 1000 + 59) / 60}")
        appendLine()
        // The fragment never reaches the HTTP server or its access logs; opening the page never consumes the grant.
        appendLine("$appUrl/reset-password/confirm#token=$token")
    }

/**
 * The link WORKER - the business half of `POST /api/v1/password-reset`, launched fire-and-forget after the uniform 202
 * (no timing oracle: lookup, grant insert and the SMTP round trip all happen off the request). Nothing about the
 * account changes here; a failed delivery revokes the grant it just minted. Soft-deleted accounts are unknown by
 * construction (`findWithIdByEmail` filters active rows).
 */
internal suspend fun processPasswordReset(
    app: Application,
    users: FlowUserService,
    resets: PasswordResetService,
    mailer: Mailer,
    appUrl: String,
    email: String,
) {
    var token: String? = null
    try {
        val (userId, user) = users.findWithIdByEmail(email) ?: run {
            audit("password_reset.unknown_email", "email" to email)
            return
        }
        token = resets.issue(userId, user.credentialRevision, user.email) ?: return
        mailer.send(
            to = user.email,
            subject = PASSWORD_RESET_EMAIL_SUBJECT.of(user.language),
            body = passwordResetEmailBody(user.name, token, appUrl, resets.ttlMillis, user.language),
        )
        audit("password_reset.link_sent", "userId" to userId.toLong(), "email" to user.email)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        token?.let { resets.revoke(it) }
        // errorType only - the class name, never e.message or the cause (a mail provider's exception may embed the message body).
        audit("password_reset.send_failed", "email" to email, "errorType" to e.javaClass.simpleName)
        app.log.error("Password reset delivery failed ({})", e.javaClass.simpleName)
    }
}

/** A notification failure cannot undo a confirmed password change or turn its 204 into a 500. */
internal suspend fun notifyPasswordReset(app: Application, users: FlowUserService, mailer: Mailer, userId: UInt) {
    try {
        val user = users.read(userId) ?: return
        mailer.send(user.email, PASSWORD_RESET_COMPLETED_SUBJECT.of(user.language), PASSWORD_RESET_COMPLETED_BODY.of(user.language))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        audit("password_reset.notification_failed", "userId" to userId.toLong(), "errorType" to e.javaClass.simpleName)
        app.log.error("Password reset notification failed ({})", e.javaClass.simpleName)
    }
}
