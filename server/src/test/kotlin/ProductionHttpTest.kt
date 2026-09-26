package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Production-mode proxy-header hardening (plugins/Http.kt), behind `http.behindProxy` (ported
 * from Toadie's ProductionHttpTest): only the canonical X-Forwarded-Host/X-Forwarded-Proto may
 * steer the HTTPS redirect — the alias headers Ktor trusts by default (X-Forwarded-Server,
 * X-Forwarded-SSL) must not, since a proxy that sets just the canonical ones passes them through
 * from the client untouched (security.md's "Reverse proxy" paragraph). Also covers the
 * fewer-hops-than-configured X-Forwarded-For fallback and the X-Forwarded-Port safety fix
 * (ForwardedHeadersTest/RawForwardedForLinesTest cover the rate-limit key itself).
 */
class ProductionHttpTest {

    private suspend fun ApplicationTestBuilder.bootProductionBehindProxy() {
        configureApp(
            "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
            "jwt.secret" to strongJwtSecret(),
            "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
            "http.behindProxy" to "true",
        )
        serverConfig { developmentMode = false }
        startApplication()
    }

    @Test
    fun `X-Forwarded-Server does not steer the redirect target, and X-Forwarded-SSL does not suppress it`() =
        testApplication {
            withSeedRestored {
                bootProductionBehindProxy()
                val client = createClient { followRedirects = false }

                val spoofed = client.get("/api/v1/health") {
                    header("X-Forwarded-Server", "evil.example")
                }
                assertEquals(HttpStatusCode.MovedPermanently, spoofed.status)
                val location = assertNotNull(spoofed.headers[HttpHeaders.Location])
                assertFalse("evil.example" in location, "X-Forwarded-Server must not steer the redirect: $location")

                val sslSpoofed = client.get("/api/v1/health") {
                    header("X-Forwarded-SSL", "on")
                }
                assertEquals(
                    HttpStatusCode.MovedPermanently,
                    sslSpoofed.status,
                    "X-Forwarded-SSL must not mark an otherwise-plain request as already secure",
                )
            }
        }

    @Test
    fun `fewer X-Forwarded-For hops than proxyHops falls back safely, never 500s`() = testApplication {
        withSeedRestored {
            configureApp(
                "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
                "jwt.secret" to strongJwtSecret(),
                "security.encryption.key" to strongEncryptionKey(),
                "mail.transport" to "disabled",
                "http.behindProxy" to "true",
                "http.proxyHops" to "3",
            )
            serverConfig { developmentMode = false }
            startApplication()
            val client = createClient { followRedirects = false }
            // Only ONE hop present though proxyHops=3 is configured — resolveForwardedForOrigin
            // must fall back to the last available value rather than indexing out of bounds.
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XForwardedFor, "203.0.113.5")
                header(HttpHeaders.XForwardedProto, "https")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `a non-numeric X-Forwarded-Port never 500s`() = testApplication {
        withSeedRestored {
            configureApp(
                "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
                "jwt.secret" to strongJwtSecret(),
                "security.encryption.key" to strongEncryptionKey(),
                "mail.transport" to "disabled",
                "http.behindProxy" to "true",
            )
            serverConfig { developmentMode = false }
            startApplication()
            val client = createClient { followRedirects = false }
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XForwardedProto, "https")
                header("X-Forwarded-Port", "not-a-number")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
