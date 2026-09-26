package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.auth.RefreshRequest
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /api/v1/password-reset. The test app uses the dev-default `log` mail transport, so
 * delivered email is captured with a ListAppender on the `ch.nokillswit.mail` logger (the
 * AuditTest pattern); the endpoint's work is asynchronous, so assertions await the audit
 * events that the worker emits as completion barriers.
 */
class PasswordResetTest {

    @Test
    fun `a Polish-language account gets the Polish reset email`() = testApplication {
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
                auditEvents.awaitEvent {
                    it.message == "password_reset.completed" && it.hasKeyValue("email", email)
                },
                "the reset should complete",
            )
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the reset email should have been delivered (log transport)")
            // The recipient's stored language (V18) drives the whole email.
            assertTrue("Cześć Reset Polka," in message, "the PL greeting")
            assertTrue("Nowe hasło" in message, "the PL password label")
            assertTrue("Twoje nowe hasło Flow" in message, "the PL subject")
            assertFalse("New password" in message, "no EN leak into the PL body")
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `existing account gets a working new password by email and the old one stops working`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset")
        TestUsers.seed(email = email, password = "old-password-123", name = "Reset Tester")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val oldSession = client.postJson(
                "/api/v1/login",
                LoginRequest(email, "old-password-123"),
            ).body<LoginResponse>()
            val response = client.post("/api/v1/password-reset") {
                contentType(ContentType.Application.Json)
                setBody(PasswordResetRequest(email))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)

            // The email is logged BEFORE the new hash is stored — wait for the completion
            // audit event so the login below can't race the DB write.
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.completed" && it.hasKeyValue("email", email)
                },
                "the reset should complete",
            )
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the reset email should have been delivered (log transport)")
            assertTrue("Hi Reset Tester," in message)
            assertTrue("New password" in message, "the EN body (the account's stored language)")
            assertTrue("Your new Flow password" in message, "the EN subject")
            val newPassword = Regex("""(?m)^[A-Za-z0-9_-]{16}$""").find(message)?.value
            assertNotNull(newPassword, "email should contain the generated password on its own line")

            val newLogin = client.postJson("/api/v1/login", LoginRequest(email, newPassword))
            assertEquals(HttpStatusCode.OK, newLogin.status, "the emailed password must work")
            val newSession = newLogin.body<LoginResponse>()

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/api/v1/refresh", RefreshRequest(oldSession.refreshToken)).status,
                "reset must invalidate the pre-reset refresh token",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.postJson("/api/v1/refresh", RefreshRequest(newSession.refreshToken)).status,
                "the post-reset generation must refresh immediately",
            )

            val oldLogin = client.postJson("/api/v1/login", LoginRequest(email, "old-password-123"))
            assertEquals(HttpStatusCode.Unauthorized, oldLogin.status, "the old password must be dead")
        } finally {
            mail.detach()
            auditEvents.detach()
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
        TestUsers.seed(email = email, password = "old-password-123")
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
            // Send-before-store: the hash was never replaced, so the old password still works.
            val oldLogin = jsonClient().postJson("/api/v1/login", LoginRequest(email, "old-password-123"))
            assertEquals(HttpStatusCode.OK, oldLogin.status, "old password must survive a failed delivery")
        } finally {
            auditEvents.detach()
        }
    }
}
