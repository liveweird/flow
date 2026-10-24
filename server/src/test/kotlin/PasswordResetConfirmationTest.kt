package ch.nokillswit

import ch.nokillswit.auth.MfaChallengeResponse
import ch.nokillswit.auth.MfaVerifyRequest
import ch.nokillswit.auth.PasswordResetConfirmRequest
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.flowusers.Feature
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `POST /api/v1/password-reset/confirm` and the `mail.appUrl` boot contract (2D3); the happy path rides [PasswordResetTest]. */
class PasswordResetConfirmationTest {
    @Test
    fun `a blank reset origin fails closed without issuing a grant and warns at boot`() = testApplication {
        val boot = LogCapture("ROOT")
        try {
            configureApp("mail.appUrl" to "")
            startApplication()
            assertEquals(
                1,
                boot.events.count { "mail.appUrl is blank" in it.formattedMessage },
                "a blank origin with a mail transport on warns ONCE at boot",
            )
        } finally {
            boot.detach()
        }
        val email = uniqueEmail("reset-no-origin")
        val id = TestUsers.seed(email = email, password = "old-password")
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            jsonClient().postJson("/api/v1/password-reset", PasswordResetRequest(email)).status,
        )
        assertTrue(resetTokenHashes(id).isEmpty())
    }

    @Test
    fun `a blank reset origin warns once with an smtp transport and not at all when mail is disabled`() {
        for ((transport, expectedWarnings) in listOf("smtp" to 1, "disabled" to 0)) {
            testApplication {
                val boot = LogCapture("ROOT")
                try {
                    configureApp(
                        "mail.appUrl" to "",
                        "mail.transport" to transport,
                        "mail.smtp.host" to "localhost",
                        "mail.smtp.port" to "1",
                        "mail.smtp.startTls" to "false",
                    )
                    startApplication()
                    assertEquals(
                        expectedWarnings,
                        boot.events.count { "mail.appUrl is blank" in it.formattedMessage },
                        "transport $transport",
                    )
                } finally {
                    boot.detach()
                }
            }
        }
    }

    @Test
    fun `a malformed reset origin refuses startup in any mode`() {
        val malformed = listOf(
            "https://h/path", "https://u@h", "https://h?x", "https://h#f", "ftp://h", "not a url", "https:///", "https://h:", "https://h:0",
        )
        for (bad in malformed) {
            testApplication {
                configureApp("mail.appUrl" to bad)
                assertStartupFails("mail.appUrl") { startApplication() }
            }
        }
        // Production mode: http is refused too (the earlier fail-closed checks satisfied first).
        testApplication {
            configureApp(
                "jwt.secret" to strongJwtSecret(),
                "mail.transport" to "disabled",
                "security.encryption.key" to strongEncryptionKey(),
                "mail.appUrl" to "http://h",
            )
            serverConfig { developmentMode = false }
            assertStartupFails("mail.appUrl") { startApplication() }
        }
    }

    @Test
    fun `confirmation rejects malformed and unknown grants uniformly and has its own rate limit`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val details = mutableSetOf<String?>()
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            repeat(10) { index ->
                val token = if (index % 2 == 0) "malformed" else "a".repeat(43)
                val response = client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "valid-password"))
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                details += response.body<ProblemDetail>().detail
            }
            assertEquals(1, details.size, "a malformed and an unknown token read identically: $details")
            assertNotNull(details.single())
            val rejected = auditEvents.events.filter { it.message == "password_reset.rejected" }
            assertEquals(10, rejected.size, "every rejection is audited")
            assertTrue(rejected.all { it.keyValuePairs.isNullOrEmpty() }, "the rejection event carries no fields, so no token")
        } finally {
            auditEvents.detach()
        }
        val limited = client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest("a".repeat(43), "valid-password"))
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("no-store", limited.headers["Cache-Control"])
    }

    @Test
    fun `the bcrypt byte ceiling does not consume a valid grant and completion works with mail disabled`() = testApplication {
        configureApp("mail.transport" to "disabled")
        val email = uniqueEmail("reset-confirm-disabled")
        val id = TestUsers.seed(email = email, password = "old-password")
        val resets = newPasswordResetService()
        val token = assertNotNull(resets.issueToken(id, 0, email))
        val client = jsonClient()
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "é".repeat(36))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "short")).status,
        )
        assertTrue(resets.isUsable(token), "a refused password leaves the grant unconsumed")
        assertEquals(
            HttpStatusCode.NoContent,
            client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password")).status,
        )
        assertEquals(HttpStatusCode.OK, client.login(email, "chosen-password").status)
    }

    @Test
    fun `a notification failure does not undo the confirmation and the audit carries no token or password`() = testApplication {
        configureApp(
            "mail.transport" to "smtp",
            "mail.smtp.host" to "localhost",
            "mail.smtp.port" to "1",
            "mail.smtp.startTls" to "false",
        )
        val email = uniqueEmail("reset-notify-fail")
        val id = TestUsers.seed(email = email, password = "old-password")
        val token = assertNotNull(newPasswordResetService().issueToken(id, 0, email))
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password")).status,
            )
            val event = assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "password_reset.notification_failed" && it.hasKeyValue("userId", id.toLong())
                },
            )
            fun ch.qos.logback.classic.spi.ILoggingEvent.mentions(secret: String) =
                secret in formattedMessage || keyValuePairs.orEmpty().any { secret in it.value.toString() }
            assertFalse(event.mentions(token))
            assertFalse(event.mentions("chosen-password"))
            assertTrue(auditEvents.events.none { it.mentions(token) || it.mentions("chosen-password") }, "no audit message or field")
            assertEquals(HttpStatusCode.OK, client.login(email, "chosen-password").status)
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `a reset invalidates a pending MFA challenge but MFA stays enabled at the next login`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("reset-mfa")
        val id = TestUsers.seed(email = email, password = "old-password")
        TestUsers.service.setDisabledFeatures(id, emptySet()) // MFA is on unless it is in the disabled set
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val pending = client.login(email, "old-password").body<MfaChallengeResponse>()
            val message = assertNotNull(mail.awaitEvent { "To: $email" in it.formattedMessage }).formattedMessage
            val code = assertNotNull(Regex("(?m)^\\d{6}$").find(message)?.value)
            val token = assertNotNull(newPasswordResetService().issueToken(id, 0, email))
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password")).status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/api/v1/login/mfa", MfaVerifyRequest(pending.challengeId, code)).status,
                "the revision bump kills the challenge minted before the reset",
            )
            assertFalse(Feature.MFA in assertNotNull(TestUsers.service.read(id)).disabledFeatures)
            assertTrue(client.login(email, "chosen-password").body<MfaChallengeResponse>().mfaRequired, "no auto-login, no MFA bypass")
            val notification = assertNotNull(
                mail.awaitEvent { "To: $email" in it.formattedMessage && "Your Flow password was reset" in it.formattedMessage },
            ).formattedMessage
            assertFalse(token in notification)
            assertFalse("chosen-password" in notification)
        } finally {
            mail.detach()
        }
    }
}
