package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.PasswordResetConfirmRequest
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.processPasswordReset
import ch.nokillswit.infra.mail.Mailer
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * POST /api/v1/password-reset and its confirm step. The test app uses the dev-default `log` mail transport, so
 * delivered email is captured on the `ch.nokillswit.mail` logger (the AuditTest pattern); the request's work is
 * asynchronous, so assertions await the audit events that the worker emits as completion barriers.
 */
class PasswordResetTest {

    @Test
    fun `a Polish-language account gets the Polish reset link email`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-pl")
        TestUsers.seed(email = email, password = "old-password-123", name = "Reset Polka", language = "pl")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertNotNull(
                auditEvents.awaitEvent { it.message == "password_reset.link_sent" && it.hasKeyValue("email", email) },
                "the link should be sent",
            )
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the reset email should have been delivered (log transport)")
            // The recipient's stored language (V18) drives the whole email.
            assertTrue("Cześć Reset Polka," in message, "the PL greeting")
            assertTrue("Twoje hasło pozostaje bez zmian" in message, "the PL body")
            assertTrue("Ważność linku (minuty): 15" in message, "the PL lifetime line")
            assertTrue("Zresetuj hasło Flow" in message, "the PL subject")
            assertFalse("Your password is unchanged" in message, "no EN leak into the PL body")
            assertTrue(Regex("""https://localhost:8084/reset-password/confirm#token=[A-Za-z0-9_-]{43}""").containsMatchIn(message))
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a request preserves credentials and the confirmation consumes the link and revokes existing sessions`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset")
        val id = TestUsers.seed(email = email, password = "old-password-123", name = "Reset Tester")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        val everything = LogCapture("ROOT")
        try {
            val client = jsonClient()
            val oldSession = client.postJson("/api/v1/login", LoginRequest(email, "old-password-123")).body<LoginResponse>()
            val response = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertNotNull(
                auditEvents.awaitEvent { it.message == "password_reset.link_sent" && it.hasKeyValue("email", email) },
                "the link should be sent",
            )
            val message = assertNotNull(mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage)
            assertTrue("Hi Reset Tester," in message)
            assertTrue("Your password is unchanged" in message, "the EN body (the account's stored language)")
            assertTrue("Reset your Flow password" in message, "the EN subject")
            assertTrue("Link lifetime (minutes): 15" in message)
            val token = assertNotNull(
                Regex("""reset-password/confirm#token=([A-Za-z0-9_-]{43})""").find(message)?.groupValues?.get(1),
                "the email carries the link with the token in the fragment",
            )
            assertEquals(1, resetTokenHashes(id).size)
            assertFalse(token in resetTokenHashes(id).single(), "only the digest is stored")

            // Requesting changes nothing: the old password works and the pre-request session is still live.
            assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/login", LoginRequest(email, "old-password-123")).status)
            val oldBearer = createClient {
                flowTestClientDefaults()
                install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer ${oldSession.token}") }
            }
            assertEquals(HttpStatusCode.OK, oldBearer.get("/api/v1/flow-users/$id").status, "the request left the session alone")

            assertEquals(
                HttpStatusCode.BadRequest,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "short")).status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password-1")).status,
            )
            assertNotNull(
                auditEvents.events.firstOrNull { it.message == "password_reset.completed" && it.hasKeyValue("userId", id.toLong()) },
                "the confirmation is audited with the account id",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password-2")).status,
                "a link works once",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                oldBearer.get("/api/v1/flow-users/$id").status,
                "the old access token dies with the credential change",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/api/v1/refresh", RefreshRequest(oldSession.refreshToken)).status,
                "the pre-reset refresh token is dead",
            )
            val newLogin = client.postJson("/api/v1/login", LoginRequest(email, "chosen-password-1"))
            assertEquals(HttpStatusCode.OK, newLogin.status, "the chosen password signs in")
            assertEquals(
                HttpStatusCode.OK,
                client.postJson("/api/v1/refresh", RefreshRequest(newLogin.body<LoginResponse>().refreshToken)).status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/login", LoginRequest(email, "old-password-123")).status)
            assertTrue(resetTokenHashes(id).isEmpty())

            // Whole-log sweep: the token lives in the emailed link only (the dev `log` transport prints that line), and the
            // chosen password nowhere - no logger, no key/value, no audit field.
            fun ch.qos.logback.classic.spi.ILoggingEvent.mentions(secret: String) =
                secret in formattedMessage || keyValuePairs.orEmpty().any { secret in it.value.toString() }
            val tokenEvents = everything.events.filter { it.mentions(token) }
            assertTrue(tokenEvents.isNotEmpty(), "the dev log transport printed the link, so the sweep can see the token")
            assertEquals(
                setOf("ch.nokillswit.mail"),
                tokenEvents.map { it.loggerName }.toSet(),
                "the token appears only in the delivery line",
            )
            assertTrue(everything.events.none { it.mentions("chosen-password-1") || it.mentions("chosen-password-2") })
        } finally {
            mail.detach()
            auditEvents.detach()
            everything.detach()
        }
    }

    @Test
    fun `unknown email answers 202 identically and sends nothing`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-nobody")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            // The async branch signals completion via the audit trail — wait for it, then
            // assert no email went out.
            val audited = auditEvents.awaitEvent {
                it.message == "password_reset.unknown_email" && it.hasKeyValue("email", email)
            }
            assertNotNull(audited, "the unknown-email branch should be audited")
            assertNull(mail.events.firstOrNull { "To: $email" in it.formattedMessage })
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a sixth request while five links are live mints and sends nothing but still answers 202`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-capped")
        val id = TestUsers.seed(email = email, password = "old-password-123")
        val live = (1..5).map { assertNotNull(newPasswordResetService().issueToken(id, 0, email)) }
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status, "the cap is not an oracle")
            val capped = assertNotNull(
                auditEvents.awaitEvent { it.message == "password_reset.capped" && it.hasKeyValue("userId", id.toLong()) },
            )
            assertEquals(listOf("userId"), capped.keyValuePairs.map { it.key }, "the capped event names the account only")
            assertNull(mail.events.firstOrNull { "To: $email" in it.formattedMessage }, "no sixth email")
            assertTrue(auditEvents.events.none { it.message == "password_reset.link_sent" && it.hasKeyValue("email", email) })
            assertEquals(5, resetTokenHashes(id).size)
            val resets = newPasswordResetService()
            assertTrue(live.all { resets.isUsable(it) }, "the earlier links stay valid")
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `an account that moves between the lookup and the grant insert is audited as stale and gets no email`() = testApplication {
        configureApp()
        var app: Application? = null
        application { app = this }
        startApplication()
        val email = uniqueEmail("reset-stale-worker")
        val id = TestUsers.seed(email = email, password = "old-password-123")
        val sent = mutableListOf<String>()
        val mailer = object : Mailer {
            override suspend fun send(to: String, subject: String, body: String) {
                sent += to
            }
        }
        // The worker reads the account first and mints second; the grant store's clock is read between the two (the pruning
        // step opens `issue`), so a password write fired from it lands exactly in the window.
        val moved = AtomicBoolean(false)
        val resets = newPasswordResetService {
            if (moved.compareAndSet(false, true)) {
                runBlocking { TestUsers.service.updatePassword(id, hashPassword("admin-password", cost = 4)) }
            }
            System.currentTimeMillis()
        }
        withAuditCapture { audit ->
            processPasswordReset(assertNotNull(app), TestUsers.service, resets, mailer, "https://localhost:8084", email)
            val stale = assertNotNull(audit.events.firstOrNull { it.message == "password_reset.stale_account" })
            assertTrue(stale.hasKeyValue("userId", id.toLong()))
            assertTrue(audit.events.none { it.message == "password_reset.link_sent" })
        }
        assertTrue(sent.isEmpty())
        assertTrue(resetTokenHashes(id).isEmpty())
    }

    @Test
    fun `a second request within the interval is 429, uniformly for unknown emails too`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val email = uniqueEmail("reset-throttle") // does not exist — the throttle must not care
        suspend fun request() = client.post("/api/v1/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(email))
        }
        assertEquals(HttpStatusCode.Accepted, request().status)
        assertEquals(HttpStatusCode.TooManyRequests, request().status)
    }

    @Test
    fun `a full reset store rejects a new identity without scheduling reset work`() = testApplication {
        configureApp("security.passwordReset.maxTracked" to "1")
        startApplication()
        val client = jsonClient()
        val tracked = uniqueEmail("reset-capacity-tracked")
        val rejected = uniqueEmail("reset-capacity-rejected")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            suspend fun request(email: String) = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }

            assertEquals(HttpStatusCode.Accepted, request(tracked).status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.unknown_email" && it.hasKeyValue("email", tracked)
                },
            )
            assertEquals(HttpStatusCode.TooManyRequests, request(rejected).status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.capacity_rejected" && it.hasKeyValue("email", rejected)
                },
            )
            assertFalse(
                auditEvents.events.any {
                    it.hasKeyValue("email", rejected) &&
                        it.message in setOf("password_reset.requested", "password_reset.unknown_email")
                },
                "a capacity rejection must not schedule the reset worker",
            )
            assertEquals(HttpStatusCode.TooManyRequests, request(tracked).status)
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `malformed emails are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        for (bad in listOf("", "   ", "no-at-sign", "x".repeat(255) + "@test")) {
            val response = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(bad))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "for input '${bad.take(20)}'")
        }
    }

    @Test
    fun `disabled mail transport answers 503`() = testApplication {
        configureApp("mail.transport" to "disabled")
        startApplication()
        val response = jsonClient().post("/api/v1/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(uniqueEmail("reset-disabled")))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `a delivery failure is audited and leaves the old password working`() = testApplication {
        // Real SMTP transport pointed at a closed port: send() throws AFTER the 202.
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1",
            "mail.smtp.startTls" to "false",
        )
        startApplication()
        val email = uniqueEmail("reset-sendfail")
        val id = TestUsers.seed(email = email, password = "old-password-123")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val response = jsonClient().post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status, "delivery failure must stay unobservable")
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.send_failed" && it.hasKeyValue("email", email)
                },
                "the failed delivery should be audited",
            )
            // The link never left: its grant is revoked, and the password was never touched.
            assertTrue(resetTokenHashes(id).isEmpty(), "a failed delivery revokes its grant")
            val oldLogin = jsonClient().postJson("/api/v1/login", LoginRequest(email, "old-password-123"))
            assertEquals(HttpStatusCode.OK, oldLogin.status, "old password must survive a failed delivery")
        } finally {
            auditEvents.detach()
        }
    }
}
