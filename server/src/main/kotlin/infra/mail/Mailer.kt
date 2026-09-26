package ch.nokillswit.infra.mail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Outbound email. One transactional message at a time — this app sends no bulk mail. */
interface Mailer {
    suspend fun send(to: String, subject: String, body: String)
}

/**
 * Plain-SMTP transport (Jakarta Mail / Eclipse Angus). All parameters come from the `mail.smtp.*`
 * config block; auth is used only when [user] is non-blank. Blocking JavaMail I/O is confined to
 * [Dispatchers.IO].
 */
class SmtpMailer(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val from: String,
    private val startTls: Boolean,
) : Mailer {
    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.starttls.enable", startTls.toString())
            // Refuse to silently downgrade: when STARTTLS is on, require the server to support it.
            put("mail.smtp.starttls.required", startTls.toString())
            put("mail.smtp.auth", (user.isNotBlank()).toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.writetimeout", "10000")
        }
        if (user.isNotBlank()) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
            })
        } else {
            Session.getInstance(props)
        }
    }

    override suspend fun send(to: String, subject: String, body: String) {
        withContext(Dispatchers.IO) {
            // No `apply {}` here: inside it, `from`/`subject` would resolve to MimeMessage's own
            // (empty) properties instead of the parameters.
            val message = MimeMessage(session)
            message.setFrom(InternetAddress(from))
            message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
            message.setSubject(subject, "UTF-8")
            message.setText(body, "UTF-8")
            Transport.send(message)
        }
    }
}

/**
 * Development transport: writes the whole message to the `ch.nokillswit.mail` logger instead of
 * sending it (the Django console-backend idea). The body may contain a freshly generated
 * password, which is why production mode refuses this transport at startup (see configureMail).
 */
class LogMailer : Mailer {
    private val log = LoggerFactory.getLogger("ch.nokillswit.mail")

    override suspend fun send(to: String, subject: String, body: String) {
        log.info("Outbound email (log transport, not sent)\nTo: {}\nSubject: {}\n\n{}", to, subject, body)
    }
}
