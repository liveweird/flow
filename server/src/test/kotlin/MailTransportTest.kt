package ch.nokillswit

import ch.nokillswit.infra.mail.LogMailer
import ch.nokillswit.infra.mail.SmtpMailer
import ch.nokillswit.infra.mail.mailer
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import jakarta.mail.MessagingException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outbound-email transport selection (infra/mail/Mail.kt, ported from Lettuce): the
 * `log`/`smtp`/`disabled` matrix, the production refusal of the log transport (the
 * fail-closed family), and the blank-SMTP-host refusal in any mode. No consumer endpoint
 * exists yet — password reset / MFA arrive with their own ports — so delivery is exercised
 * at the Mailer level.
 */
class MailTransportTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.captureApplication(): () -> Application {
        var app: Application? = null
        application { app = this }
        return { assertNotNull(app, "application must have started") }
    }

    @Test
    fun `production mode refuses to start with the log transport`() = testApplication {
        // Strong JWT secret so the earlier configureSecurity check passes; configureMail runs
        // at the top of the infrastructure group, before Flyway/Bootstrap.
        configureApp("jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey())
        serverConfig { developmentMode = false }
        assertStartupFails("mail.transport") { startApplication() }
    }

    @Test
    fun `smtp transport without a host refuses to start in any mode`() = testApplication {
        configureApp("mail.transport" to "smtp", "mail.smtp.host" to "")
        assertStartupFails("SMTP_HOST") { startApplication() }
    }

    @Test
    fun `an unknown transport refuses to start`() = testApplication {
        configureApp("mail.transport" to "carrier-pigeon")
        assertStartupFails("Unknown mail.transport") { startApplication() }
    }

    @Test
    fun `disabled transport publishes a null mailer`() = testApplication {
        configureApp("mail.transport" to "disabled")
        val app = captureApplication()
        startApplication()
        assertNull(app().mailer(), "disabled must publish no mailer")
    }

    @Test
    fun `the dev-default log transport delivers to the mail logger`() = testApplication {
        configureApp() // mail.transport defaults to `log`; tests run in development mode
        val app = captureApplication()
        startApplication()
        val mailer = assertIs<LogMailer>(app().mailer())
        val capture = LogCapture("ch.nokillswit.mail")
        try {
            mailer.send("someone@flow.test", "Test subject", "Body line one")
            val message = capture.events.firstOrNull {
                "To: someone@flow.test" in it.formattedMessage
            }?.formattedMessage
            assertNotNull(message, "the log transport should have logged the message")
            assertTrue("Subject: Test subject" in message)
            assertTrue("Body line one" in message)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `smtp transport publishes an SmtpMailer`() = testApplication {
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1",
            "mail.smtp.startTls" to "false",
        )
        val app = captureApplication()
        startApplication()
        assertIs<SmtpMailer>(app().mailer())
    }

    @Test
    fun `smtp delivery failure surfaces as a MessagingException`() = testApplication {
        // Port 1 is closed: the send path (message building + transport) runs and fails fast —
        // consumers own their handling (the password-reset port audits and keeps the old hash).
        val mailer = SmtpMailer(
            host = "127.0.0.1",
            port = 1,
            user = "",
            password = "",
            from = "flow@localhost",
            startTls = false,
        )
        assertFailsWith<MessagingException> {
            mailer.send("nobody@flow.test", "Will not arrive", "Body")
        }
    }
}
