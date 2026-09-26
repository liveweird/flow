package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.infra.mail.passwordEmail
import ch.nokillswit.users.UserService
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException

/**
 * Content of the password-reset email, rendered in the recipient's language. Thin wrapper
 * over the shared scaffold (infra/mail/PasswordEmail.kt) — only the intro copy, the password
 * label, and the subject live here. The route passes the recipient's stored language (V1).
 */
internal val PASSWORD_RESET_EMAIL_SUBJECT: LocalizedText = LocalizedText(
    en = "Your new Flow password",
    pl = "Twoje nowe hasło Flow",
)

private val INTRO = LocalizedText(
    en = "a password reset was requested for your Flow account, so your previous " +
        "password no longer works. Sign in with the new password below and change it " +
        "afterwards. If you didn't request this, someone submitted your address on the " +
        "reset form — sign in and change the password now.",
    pl = "ktoś poprosił o zresetowanie hasła do Twojego konta Flow, więc Twoje " +
        "poprzednie hasło już nie działa. Zaloguj się nowym hasłem poniżej, a potem je " +
        "zmień. Jeśli to nie Ty prosiłeś/aś o reset, ktoś podał Twój adres w formularzu — " +
        "zaloguj się i zmień hasło od razu.",
)

private val PASSWORD_LABEL = LocalizedText(en = "New password", pl = "Nowe hasło")

internal fun passwordResetEmailBody(name: String, password: String, appUrl: String?, language: String): String =
    passwordEmail(
        name = name,
        intro = INTRO,
        passwordLabel = PASSWORD_LABEL,
        password = password,
        appUrl = appUrl,
        language = language,
    )

/**
 * The password-reset WORKER — the business half of `POST /api/v1/password-reset`, extracted
 * from the route registrar (route files stay declarative; the detekt exemption covers
 * registration, not workers). Runs AFTER the uniform 202 (no timing oracle): lookup,
 * generate, send-before-store, and the completion/failure audits. Launched fire-and-forget
 * by the handler. Soft-deleted accounts are unknown here by construction —
 * findWithIdByEmail filters active rows. The email renders in the recipient's stored
 * language (V1; see infra/mail/LocalizedText.kt).
 */
internal suspend fun processPasswordReset(
    app: Application,
    userService: UserService,
    resetMailer: Mailer,
    mailAppUrl: String?,
    email: String,
) {
    var delivered = false
    try {
        val record = userService.findWithIdByEmail(email)
        if (record == null) {
            audit("password_reset.unknown_email", "email" to email)
            return
        }
        val (userId, user) = record
        val newPassword = generatePassword()
        // Send FIRST, then store: a delivery failure leaves the old password
        // working; a storage failure after delivery is recoverable by retrying.
        // The two halves audit under distinct events — a store_failed means the
        // recipient now holds an emailed password that does NOT work yet.
        resetMailer.send(
            to = user.email,
            subject = PASSWORD_RESET_EMAIL_SUBJECT.of(user.language),
            body = passwordResetEmailBody(user.name, newPassword, mailAppUrl, user.language),
        )
        delivered = true
        userService.updatePassword(userId, hashPassword(newPassword))
        audit("password_reset.completed", "email" to user.email, "userId" to userId.toLong())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        audit(
            if (delivered) "password_reset.store_failed" else "password_reset.send_failed",
            "email" to email,
            "error" to e.message,
        )
        app.log.error("Password reset failed for $email (delivered=$delivered)", e)
    }
}
