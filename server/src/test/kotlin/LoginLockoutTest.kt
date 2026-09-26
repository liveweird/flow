package ch.nokillswit

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The per-account lockout (auth/LoginThrottle.kt wired in AuthRoutes): after `threshold`
 * consecutive failures for one email, further attempts — even with the correct password —
 * answer 429 for the lockout window. Independent of the per-IP rate limit.
 */
class LoginLockoutTest {

    @Test
    fun `a full lockout store rejects unseen identities while retaining existing state`() = testApplication {
        configureApp(
            "security.lockout.threshold" to "2",
            "security.lockout.maxTracked" to "1",
        )
        startApplication()
        val client = jsonClient()
        val tracked = uniqueEmail("capacity-tracked")
        val rejected = " ${"x".repeat(100_000)}@invalid "
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.login(tracked, "wrong").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.login(rejected, "wrong").status)
            val capacityEvent = assertNotNull(
                auditEvents.awaitEvent { it.message == "login.capacity_rejected" },
                "capacity rejection should be audited",
            )
            val auditFields = capacityEvent.keyValuePairs.orEmpty()
            val digest = auditFields.single { it.key == "emailDigest" }.value as String
            assertEquals(64, digest.length)
            assertFalse(auditFields.any { it.key == "email" })
            // The fresh tracked counter survived: its next failure still trips its lock.
            assertEquals(HttpStatusCode.Unauthorized, client.login(tracked, "wrong").status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.lockout" && it.hasKeyValue("email", tracked)
                },
                "ordinary login identities should keep the email audit field",
            )
            assertEquals(HttpStatusCode.TooManyRequests, client.login(tracked, "wrong").status)
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `threshold consecutive failures lock the account - even the right password answers 429`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("locked")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(3) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }

        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
        // The lockout's account-specific detail must survive StatusPages' generic 429 handler.
        assertContains(lockedOut.bodyAsText(), "failed login attempts")
    }

    @Test
    fun `a successful login resets the failure counter`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("reset")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(2) {
            client.login(email, "wrong-pw")
        }
        val success = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.OK, success.status)

        // The counter restarted: two more failures stay under the threshold of 3.
        repeat(2) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }
    }

    @Test
    fun `the lockout bucket is shared across email case variants`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("variant")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        for (variant in listOf(email, email.uppercase(), " $email ")) {
            client.login(variant, "wrong-pw")
        }
        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
    }
}
