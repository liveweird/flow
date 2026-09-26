package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.MfaChallengeResponse
import ch.nokillswit.auth.MfaVerifyRequest
import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserFeaturesUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The email-MFA login flow end to end: challenge instead of tokens for an MFA-enabled
 * account, the emailed 6-digit code (captured off the dev-default `log` transport, the
 * PasswordResetTest idiom), the POST /api/v1/login/mfa exchange, and the failure matrix —
 * uniform 401s and the fail-closed 503 on a mail-less deployment. The pure challenge-store
 * unit lives in MfaChallengesTest; the inverted-default flag semantics in
 * FeatureFlagsTest/AuditTest.
 */
class MfaLoginTest {

    /** One-message SMTP sink used to prove recovery after a real transport failure. */
    private class SmtpSink(port: Int) : AutoCloseable {
        private val server = ServerSocket(port)
        private val executor = Executors.newSingleThreadExecutor()
        private val delivered = CountDownLatch(1)

        init {
            executor.submit {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    fun reply(line: String) {
                        writer.write("$line\r\n")
                        writer.flush()
                    }
                    reply("220 localhost")
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("DATA") -> {
                                reply("354 end with dot")
                                while (true) {
                                    val dataLine = reader.readLine() ?: break
                                    if (dataLine == ".") break
                                }
                                reply("250 queued")
                                delivered.countDown()
                            }
                            line.startsWith("QUIT") -> {
                                reply("221 bye")
                                break
                            }
                            else -> reply("250 ok")
                        }
                    }
                }
            }
        }

        fun awaitDelivery(): Boolean = delivered.await(5, TimeUnit.SECONDS)

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }

    /** Enable MFA for [userId] — an empty disabled set removes the default MFA row. */
    private suspend fun HttpClient.enableMfa(userId: UInt) =
        putJson("/api/v1/users/$userId/features", UserFeaturesUpdateRequest(emptyList()))

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        postJson("/api/v1/login", LoginRequest(email, password))

    private suspend fun HttpClient.verify(challengeId: String, code: String): HttpResponse =
        postJson("/api/v1/login/mfa", MfaVerifyRequest(challengeId, code))

    private suspend fun ApplicationTestBuilder.seedMfaUser(
        email: String,
        password: String,
        language: String = "en",
    ): UInt {
        val adminEmail = uniqueEmail("mfa-admin")
        TestUsers.seed(email = adminEmail, password = "pw-123456789")
        val admin = authedClient(adminEmail, "pw-123456789")
        val userId = TestUsers.seed(email = email, password = password, role = UserRole.USER, language = language)
        assertEquals(HttpStatusCode.NoContent, admin.enableMfa(userId).status)
        return userId
    }

    /** The 6-digit code from the captured sign-in email for [email] (log transport). */
    private suspend fun LogCapture.codeFor(email: String): String {
        val message = awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
        assertNotNull(message, "the sign-in code email should have been delivered (log transport)")
        assertTrue("Your sign-in code" in message, "the EN body (the account's stored language)")
        val code = Regex("""(?m)^\d{6}$""").find(message)?.value
        assertNotNull(code, "email should contain the 6-digit code on its own line")
        return code
    }

    @Test
    fun `a Polish-language account gets the Polish sign-in code email`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("mfa-pl")
        seedMfaUser(email, "pw-123456789", language = "pl")
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val message = mail.awaitEvent { "To: $email" in it.formattedMessage }?.formattedMessage
            assertNotNull(message, "the sign-in code email should have been delivered (log transport)")
            // The recipient's stored language (V18) drives the whole email.
            assertTrue("Twój kod logowania" in message, "the PL code label")
            assertTrue("Flow: twój kod logowania" in message, "the PL subject")
            val code = Regex("""(?m)^\d{6}$""").find(message)?.value
            assertNotNull(code, "email should contain the 6-digit code on its own line")
            assertEquals(HttpStatusCode.OK, client.verify(challenge.challengeId, code).status)
        } finally {
            mail.detach()
        }
    }

    @Test
    fun `an MFA-enabled login answers a challenge, the emailed code mints a working pair, and the challenge is single-use`() =
        testApplication {
            usePostgresTestcontainer()
            val email = uniqueEmail("mfa-happy")
            val userId = seedMfaUser(email, "pw-123456789")
            val mail = LogCapture("ch.nokillswit.mail")
            val auditEvents = LogCapture("ch.nokillswit.audit")
            try {
                val client = jsonClient()
                val challengeRes = client.login(email, "pw-123456789")
                assertEquals(HttpStatusCode.OK, challengeRes.status)
                val challenge = challengeRes.body<MfaChallengeResponse>()
                assertTrue(challenge.mfaRequired)
                assertTrue(challenge.challengeId.isNotBlank())
                assertNotNull(
                    auditEvents.awaitEvent {
                        it.message == "login.mfa_challenge" && it.hasKeyValue("email", email)
                    },
                    "the challenge should be audited",
                )
                // No login.success yet — full authentication happens only after the code.
                assertEquals(
                    0,
                    auditEvents.events.count { it.message == "login.success" && it.hasKeyValue("email", email) },
                )

                val code = mail.codeFor(email)
                val verified = client.verify(challenge.challengeId, code)
                assertEquals(HttpStatusCode.OK, verified.status)
                val tokens = verified.body<LoginResponse>()
                assertEquals(userId, tokens.userId)
                // MFA enabled = the default MFA-disabled row was removed.
                assertEquals(emptyList(), tokens.disabledFeatures)
                assertNotNull(
                    auditEvents.awaitEvent {
                        it.message == "login.mfa_success" && it.hasKeyValue("email", email)
                    },
                )

                // The pair is a real session: the bearer works on an authenticated route.
                val me = client.get("/api/v1/users/$userId") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.token}")
                }
                assertEquals(HttpStatusCode.OK, me.status)

                // The challenge was consumed — replaying id + code is a uniform 401.
                assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
            } finally {
                mail.detach()
                auditEvents.detach()
            }
        }

    @Test
    fun `wrong codes are uniform 401s and exhausting the attempt cap kills the challenge`() = testApplication {
        configureApp("security.mfa.maxAttempts" to "3")
        startApplication()
        val email = uniqueEmail("mfa-cap")
        seedMfaUser(email, "pw-123456789")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codeFor(email)
            // A deliberately wrong 6-digit code that can never collide with the real one.
            val wrong = if (code == "000000") "000001" else "000000"

            repeat(3) {
                assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, wrong).status)
            }
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "too_many_attempts")
                },
            )
            // The correct code no longer works — the challenge is gone (still a uniform 401).
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "unknown_challenge")
                },
            )
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a full MFA store rejects issuance without email and keeps the existing challenge valid`() = testApplication {
        configureApp("security.mfa.maxTracked" to "1")
        startApplication()
        val firstEmail = uniqueEmail("mfa-capacity-first")
        val secondEmail = uniqueEmail("mfa-capacity-second")
        seedMfaUser(firstEmail, "pw-123456789")
        seedMfaUser(secondEmail, "pw-123456789")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val first = client.login(firstEmail, "pw-123456789").body<MfaChallengeResponse>()
            val firstCode = mail.codeFor(firstEmail)

            assertEquals(HttpStatusCode.TooManyRequests, client.login(secondEmail, "pw-123456789").status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_capacity_rejected" && it.hasKeyValue("email", secondEmail)
                },
            )
            assertEquals(
                null,
                mail.events.firstOrNull { "To: $secondEmail" in it.formattedMessage },
                "a rejected challenge must not schedule email delivery",
            )
            assertEquals(HttpStatusCode.OK, client.verify(first.challengeId, firstCode).status)
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `failed MFA delivery releases capacity so a recovered mail transport can retry`() = testApplication {
        val port = ServerSocket(0).use { it.localPort }
        configureApp(
            "security.mfa.maxTracked" to "1",
            "mail.transport" to "smtp",
            "mail.smtp.host" to "127.0.0.1",
            "mail.smtp.port" to port.toString(),
            "mail.smtp.startTls" to "false",
        )
        startApplication()
        val email = uniqueEmail("mfa-delivery-recovery")
        seedMfaUser(email, "pw-123456789")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            assertEquals(HttpStatusCode.OK, client.login(email, "pw-123456789").status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_send_failed" && it.hasKeyValue("email", email)
                },
                "the closed SMTP port should fail the first delivery",
            )

            SmtpSink(port).use { smtp ->
                assertEquals(
                    HttpStatusCode.OK,
                    client.login(email, "pw-123456789").status,
                    "the failed challenge must release the only capacity slot",
                )
                assertTrue(smtp.awaitDelivery(), "the retry should reach the recovered SMTP transport")
            }
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `a password change invalidates an already issued MFA challenge`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("mfa-credential-change")
        val userId = seedMfaUser(email, "pw-123456789")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codeFor(email)

            // The challenge captured revision 0. The shared password chokepoint advances it
            // atomically, so the old challenge cannot mint tokens for revision 1.
            TestUsers.service.updatePassword(
                userId,
                ch.nokillswit.auth.hashPassword("changed-password", cost = 4),
            )

            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "credential_changed")
                },
            )
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a malformed body is 400 and the exhausted per-IP bucket is 429`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/login/mfa", "{ not json").status)
        // The mfa bucket is hardcoded 10/min in every mode; the 400 above consumed one slot,
        // nine uniform 401s exhaust the rest, and the eleventh request trips the limit.
        repeat(9) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/api/v1/login/mfa", MfaVerifyRequest("no-such-challenge", "000000")).status,
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.postJson("/api/v1/login/mfa", MfaVerifyRequest("no-such-challenge", "000000")).status,
        )
    }

    @Test
    fun `an expired challenge answers the same uniform 401`() = testApplication {
        // TTL 0: the challenge is born expired — no sleeping in tests.
        configureApp("security.mfa.codeTtlSeconds" to "0")
        startApplication()
        val email = uniqueEmail("mfa-expired")
        seedMfaUser(email, "pw-123456789")
        val mail = LogCapture("ch.nokillswit.mail")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codeFor(email)
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "expired")
                },
            )
        } finally {
            mail.detach()
            auditEvents.detach()
        }
    }

    @Test
    fun `a mail-less deployment fails an MFA-enabled login closed with 503`() = testApplication {
        configureApp("mail.transport" to "disabled")
        startApplication()
        val email = uniqueEmail("mfa-nomail")
        seedMfaUser(email, "pw-123456789")
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            assertEquals(HttpStatusCode.ServiceUnavailable, client.login(email, "pw-123456789").status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.mfa_unavailable" && it.hasKeyValue("email", email)
                },
            )
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `an MFA-disabled account keeps the classic single-step login`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("mfa-off")
        TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        val res = jsonClient().login(email, "pw-123456789")
        assertEquals(HttpStatusCode.OK, res.status)
        val tokens = res.body<LoginResponse>()
        assertTrue(tokens.token.isNotBlank())
        assertEquals(listOf(Feature.MFA), tokens.disabledFeatures)
    }
}
