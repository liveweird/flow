package ch.nokillswit

import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.authz.caller
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.integration.HostWindowLimiter
import ch.nokillswit.integration.IntegrationHostLimiterKey
import ch.nokillswit.integration.IntegrationMe
import ch.nokillswit.integration.SERVICE_TOKEN
import ch.nokillswit.integration.ServiceTokenAuthFailedAuditKey
import ch.nokillswit.integration.ServiceTokenCreateRequest
import ch.nokillswit.integration.ServiceTokenCreateResponse
import ch.nokillswit.integration.ServiceTokenService
import ch.nokillswit.integration.ServiceTokenServiceKey
import ch.nokillswit.integration.ServiceTokenUsedAuditKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.RateLimitAuditThrottle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service-token authentication provider (`integration/ServiceTokenAuth.kt`) and `GET /api/v1/integration/me`: a strict header
 * shape check, ONE uncached registry lookup, a uniform 401 for every refusal, an outage that is a 500 and never a 401, the two buckets
 * and the coalesced audit trail. Tokens are SHARED suite state - every test mints UNIQUE names.
 */
class ServiceTokenAuthTest {

    private val me = "/api/v1/integration/me"
    private val jwtText = "Missing or invalid bearer token"
    private val serviceText = "Missing or invalid service token"

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.issue(prefix: String): ServiceTokenCreateResponse {
        val response = postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(name(prefix)))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.revoke(id: UInt) {
        assertEquals(HttpStatusCode.NoContent, post("/api/v1/service-tokens/$id/revoke").status)
    }

    private fun HttpRequestBuilder.bearer(secret: String) = header(HttpHeaders.Authorization, "Bearer $secret")

    /** A well-formed `flow_int_` value that no registry row matches. */
    private fun unknownSecret() = "flow_int_" + UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x').substring(0, 43)

    private suspend fun HttpResponse.assertUniform401(detail: String = serviceText) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("application/problem+json", contentType()?.withoutParameters().toString())
        assertEquals(detail, body<ProblemDetail>().detail)
    }

    private suspend fun ApplicationTestBuilder.adminClient() = seededClient("st-auth", FlowUserRole.ADMIN)

    @Test
    fun `a live token reads its own facts and every call moves last_used_at`() = testApplication {
        usePostgresTestcontainer()
        val admin = adminClient()
        val issued = admin.issue("me")
        val client = jsonClient()
        val before = System.currentTimeMillis()

        val first = client.get(me) { bearer(issued.secret) }
        assertEquals(HttpStatusCode.OK, first.status)
        val facts = first.body<IntegrationMe>()
        assertEquals(issued.token.id, facts.tokenId)
        assertEquals(issued.token.name, facts.name)
        assertEquals(issued.token.createdAt, facts.createdAt)
        assertNull(facts.expiresAt)
        assertNull(facts.lastUsedAt, "the values BEFORE this call's stamp: never used yet")

        val stamped = admin.get("/api/v1/service-tokens/${issued.token.id}").body<ch.nokillswit.integration.ServiceToken>().lastUsedAt
        assertNotNull(stamped)
        assertTrue(stamped >= before, "last_used_at moved to this call's time")

        val second = client.get(me) { bearer(issued.secret) }.body<IntegrationMe>()
        assertEquals(stamped, second.lastUsedAt, "the second call sees the first call's stamp")
        // HEAD rides AutoHeadResponse, and `bearer` is matched case-insensitively.
        assertEquals(
            HttpStatusCode.OK,
            client.get(me) { header(HttpHeaders.Authorization, "bEaReR ${issued.secret}") }.status,
        )
    }

    @Test
    fun `an expiry is reported by me and an expired token is the uniform 401`() = testApplication {
        usePostgresTestcontainer()
        val admin = adminClient()
        val creator = TestUsers.seed(uniqueEmail("st-expiry"), "pw")
        val now = System.currentTimeMillis()
        val live = admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(name("expiring"), now + 3_600_000))
            .body<ServiceTokenCreateResponse>()
        assertEquals(now + 3_600_000, jsonClient().get(me) { bearer(live.secret) }.body<IntegrationMe>().expiresAt)

        // Born in the past through a service whose clock lags: its expiry is "in the future" for IT, long gone for the app.
        val lagging = ServiceTokenService(sharedDatabaseForTests()) { now - 7_200_000 }
        val (_, expired) = lagging.create(name("expired"), now - 3_600_000, creator)
        jsonClient().get(me) { bearer(expired) }.assertUniform401()
    }

    @Test
    fun `every refusal is one uniform 401 problem and unknown and revoked are indistinguishable`() = testApplication {
        usePostgresTestcontainer()
        val admin = adminClient()
        val live = admin.issue("uniform-live")
        val revoked = admin.issue("uniform-revoked")
        admin.revoke(revoked.token.id)
        val client = jsonClient()

        val unknown = client.get(me) { bearer(unknownSecret()) }
        val afterRevoke = client.get(me) { bearer(revoked.secret) }
        unknown.assertUniform401()
        afterRevoke.assertUniform401()
        val unknownBody = unknown.bodyAsText()
        assertEquals(unknownBody, afterRevoke.bodyAsText(), "same body byte for byte: nothing tells them apart on the wire")

        // Shape refusals: none of them reaches the registry.
        client.get(me).assertUniform401()
        client.get(me) { bearer("flow_xxx_" + "A".repeat(43)) }.assertUniform401()
        client.get(me) { bearer(live.secret.dropLast(1)) }.assertUniform401()
        client.get(me) { bearer(live.secret + "A") }.assertUniform401()
        client.get(me) { header(HttpHeaders.Authorization, "Basic ${live.secret}") }.assertUniform401()
        client.get(me) { header(HttpHeaders.Authorization, live.secret) }.assertUniform401()
        client.get(me) { header(HttpHeaders.Authorization, "Bearer  ${live.secret}") }.assertUniform401()
        client.get(me) {
            header(HttpHeaders.Authorization, "Bearer ${live.secret}")
            header(HttpHeaders.Authorization, "Bearer ${live.secret}")
        }.assertUniform401()

        // ... and the live token was never harmed by any of that.
        assertEquals(HttpStatusCode.OK, client.get(me) { bearer(live.secret) }.status)
    }

    @Test
    fun `a user's JWT on the integration namespace is 401 with the service text`() = testApplication {
        usePostgresTestcontainer()
        adminClient().get(me).assertUniform401()
    }

    @Test
    fun `a service token is refused everywhere else with the JWT text and leaves no service_token audit line`() = testApplication {
        usePostgresTestcontainer()
        val issued = adminClient().issue("elsewhere")
        val client = jsonClient()
        withAuditCapture { capture ->
            client.get("/api/v1/teams") { bearer(issued.secret) }.assertUniform401(jwtText)
            client.get("/api/v1/flow-users") { bearer(issued.secret) }.assertUniform401(jwtText)
            client.get("/api/v1/service-tokens") { bearer(issued.secret) }.assertUniform401(jwtText)
            client.get("/api/v1/telemetry/current") { bearer(issued.secret) }.assertUniform401(jwtText)
            client.post("/api/v1/teams") {
                bearer(issued.secret)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"nope"}""")
            }.assertUniform401(jwtText)
            assertEquals(emptyList(), capture.events.filter { it.message.startsWith("service_token.") }.map { it.message })
        }
    }

    @Test
    fun `the JWT routes still authenticate after the service-token provider joined them`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.OK, seededClient("st-jwt").get("/api/v1/teams").status)
    }

    @Test
    fun `caller() on a service-token call is unauthorized`() = testApplication {
        configureApp()
        application {
            routing {
                authenticate(SERVICE_TOKEN) {
                    get("/test-only/service-caller") {
                        val outcome = runCatching { call.caller() }.exceptionOrNull()
                        call.respondText(if (outcome is UnauthorizedException) "unauthorized" else "caller=$outcome")
                    }
                }
            }
        }
        startApplication()
        val issued = adminClient().issue("caller")
        // The default client on purpose: the path is test-only and not in the spec.
        assertEquals("unauthorized", client.get("/test-only/service-caller") { bearer(issued.secret) }.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/test-only/service-caller").status)
    }

    @Test
    fun `a lookup that throws is a 500 never a 401, while a malformed header stays 401 during the outage`() = testApplication {
        configureApp()
        application {
            val unreachable = R2dbcDatabase.connect(
                url = "r2dbc:postgresql://localhost:1/nonexistent",
                user = "nobody",
                password = "nobody",
            )
            attributes.put(ServiceTokenServiceKey, ServiceTokenService(unreachable))
        }
        startApplication()
        val client = jsonClient()
        val response = client.get(me) { bearer(unknownSecret()) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)
        client.get(me) { bearer("not-a-service-token") }.assertUniform401()
    }

    @Test
    fun `the per-IP bucket answers the 429 problem after its limit and audits it once`() = testApplication {
        configureApp("security.rateLimit.integrationPerMinute" to "3")
        startApplication()
        val issued = adminClient().issue("ip-bucket")
        val client = jsonClient()
        withAuditCapture { capture ->
            repeat(3) { assertEquals(HttpStatusCode.OK, client.get(me) { bearer(issued.secret) }.status) }
            val throttled = client.get("$me?probe=secret-value") { bearer(issued.secret) }
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("application/problem+json", throttled.contentType()?.withoutParameters().toString())
            assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
            repeat(2) { assertEquals(HttpStatusCode.TooManyRequests, client.get(me) { bearer(issued.secret) }.status) }

            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size, "three rejections fold into ONE event")
            val event = events.single()
            assertTrue(event.hasKeyValue("bucket", "integration"))
            assertTrue(event.hasKeyValue("path", me), "no query string in the event")
            assertTrue(event.hasKeyValue("suppressed", 0L))
            assertTrue(event.keyValuePairs.none { "secret-value" in it.value.toString() || issued.secret in it.value.toString() })
            assertTrue(event.keyValuePairs.none { it.key == "remoteHost" || it.key == "ip" })
            assertTrue(event.keyValuePairs.none { it.key == "byTokenId" || it.key == "byUserId" }, "no token was verified for this refusal")
        }
    }

    @Test
    fun `the per-token bucket is per token, so a second token from the same host is untouched, and the audit names the token`() =
        testApplication {
            // The per-IP bucket is lifted so only the fixed 120/min per-token bucket can answer a 429 here.
            configureApp("security.rateLimit.integrationPerMinute" to "100000")
            startApplication()
            val admin = adminClient()
            val first = admin.issue("tok-a")
            val second = admin.issue("tok-b")
            val client = jsonClient()
            withAuditCapture { capture ->
                repeat(120) { assertEquals(HttpStatusCode.OK, client.get(me) { bearer(first.secret) }.status) }
                val throttled = client.get(me) { bearer(first.secret) }
                assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
                assertEquals(HttpStatusCode.TooManyRequests, client.get(me) { bearer(first.secret) }.status)
                assertEquals(HttpStatusCode.OK, client.get(me) { bearer(second.secret) }.status, "the other token has its own bucket")

                val events = capture.events.filter { it.message == "rate_limit.exceeded" }
                assertEquals(1, events.size, "A's two rejections fold into ONE event")
                val event = events.single()
                assertTrue(event.hasKeyValue("bucket", "integration-token"))
                assertTrue(event.hasKeyValue("byTokenId", first.token.id.toLong()))
                assertTrue(event.keyValuePairs.none { it.key == "byUserId" }, "a service token is not a user")
                assertTrue(event.hasKeyValue("suppressed", 0L))
            }
        }

    @Test
    fun `refused and valid attempts spend the same per-host budget, and the 429 needs no lookup`() = testApplication {
        configureApp("security.rateLimit.integrationPerMinute" to "3")
        startApplication()
        val issued = adminClient().issue("budget")
        val client = jsonClient()
        withAuditCapture { capture ->
            client.get(me) { bearer(unknownSecret()) }.assertUniform401()
            assertEquals(HttpStatusCode.OK, client.get(me) { bearer(issued.secret) }.status)
            client.get(me).assertUniform401()
            // The budget is spent: an unknown token, a malformed one and the valid one are all refused now.
            val probes = listOf<HttpRequestBuilder.() -> Unit>({ bearer(unknownSecret()) }, { bearer("junk") }, { bearer(issued.secret) })
            for (probe in probes) {
                val refused = client.get(me, probe)
                assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                assertEquals("application/problem+json", refused.contentType()?.withoutParameters().toString())
                assertEquals("Rate limit exceeded — retry later", refused.body<ProblemDetail>().detail)
                assertNotNull(refused.headers[HttpHeaders.RetryAfter])
            }
            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size)
            assertTrue(events.single().hasKeyValue("bucket", "integration"))
            assertTrue(events.single().hasKeyValue("suppressed", 0L))
            assertTrue(events.single().keyValuePairs.none { it.key == "byTokenId" })
        }
    }

    @Test
    fun `once a host is over its budget the registry is never consulted`() = testApplication {
        configureApp("security.rateLimit.integrationPerMinute" to "3")
        application {
            val unreachable = R2dbcDatabase.connect(
                url = "r2dbc:postgresql://localhost:1/nonexistent",
                user = "nobody",
                password = "nobody",
            )
            attributes.put(ServiceTokenServiceKey, ServiceTokenService(unreachable))
        }
        startApplication()
        val client = jsonClient()
        // Every attempt that reaches the (unreachable) registry is a 500 ...
        repeat(3) { assertEquals(HttpStatusCode.InternalServerError, client.get(me) { bearer(unknownSecret()) }.status) }
        // ... so the 429 proves the 4th never got that far, a well-formed token or not.
        repeat(2) { assertEquals(HttpStatusCode.TooManyRequests, client.get(me) { bearer(unknownSecret()) }.status) }
    }

    @Test
    fun `hosts do not share a budget, a saturated map refuses a new host, and the window refills`() = testApplication {
        var now = 10_000_000L
        configureApp("http.behindProxy" to "true")
        application {
            attributes.put(IntegrationHostLimiterKey, HostWindowLimiter(limit = 2, maxTracked = 2, clock = { now }))
        }
        startApplication()
        val client = jsonClient()
        suspend fun attempt(host: String) = client.get(me) {
            header(HttpHeaders.XForwardedFor, "10.0.0.99, $host")
            bearer(unknownSecret())
        }.status
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.1")) }
        assertEquals(HttpStatusCode.TooManyRequests, attempt("203.0.113.1"))
        // A second host has its own budget (the key is the proxy-resolved client, the LAST X-Forwarded-For value) ...
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.2")) }
        assertEquals(HttpStatusCode.TooManyRequests, attempt("203.0.113.2"))
        // ... but the map holds two hosts: a third is refused rather than tracked.
        assertEquals(HttpStatusCode.TooManyRequests, attempt("203.0.113.3"))
        // The windows end, the map is reclaimed and everyone starts over.
        now += 60_001
        assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.3"))
        assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.1"))
    }

    @Test
    fun `auth_failed is audited per reason and token, coalesced with a suppressed count, and never carries a secret`() = testApplication {
        var now = 1_000_000L
        configureApp()
        application {
            attributes.put(ServiceTokenAuthFailedAuditKey, RateLimitAuditThrottle(clock = { now }))
        }
        startApplication()
        val admin = adminClient()
        val revoked = admin.issue("audit-revoked")
        admin.revoke(revoked.token.id)
        val creator = TestUsers.seed(uniqueEmail("st-audit-expired"), "pw")
        val lagging = ServiceTokenService(sharedDatabaseForTests()) { System.currentTimeMillis() - 7_200_000 }
        val (expiredToken, expired) = lagging.create(name("audit-expired"), System.currentTimeMillis() - 3_600_000, creator)
        val unknown = unknownSecret()
        val client = jsonClient()
        withAuditCapture { capture ->
            repeat(3) { client.get(me).assertUniform401() }
            repeat(3) { client.get(me) { bearer(unknown) }.assertUniform401() }
            client.get(me) { bearer(revoked.secret) }.assertUniform401()
            client.get(me) { bearer(expired) }.assertUniform401()

            val failed = capture.events.filter { it.message == "service_token.auth_failed" }
            assertEquals(4, failed.size, "one event per reason (and token) in the window")
            val malformed = failed.single { it.hasKeyValue("reason", "missing_or_malformed") }
            assertTrue(malformed.hasKeyValue("suppressed", 0L))
            assertTrue(malformed.keyValuePairs.none { it.key == "tokenId" })
            val unmatched = failed.filter { it.hasKeyValue("reason", "unknown_or_revoked") }
            val unknownEvent = unmatched.single { it.keyValuePairs.none { kv -> kv.key == "tokenId" } }
            assertTrue(unknownEvent.hasKeyValue("suppressed", 0L))
            assertTrue(unmatched.single { it.hasKeyValue("tokenId", revoked.token.id.toLong()) }.hasKeyValue("suppressed", 0L))
            assertTrue(failed.single { it.hasKeyValue("reason", "expired") }.hasKeyValue("tokenId", expiredToken.id.toLong()))

            now += 61_000
            client.get(me).assertUniform401()
            val again = capture.events.filter { it.message == "service_token.auth_failed" }.last()
            assertTrue(again.hasKeyValue("reason", "missing_or_malformed"))
            assertTrue(again.hasKeyValue("suppressed", 2L), "the two folded rejections are counted")

            val everything = capture.events.flatMap { e -> listOf(e.formattedMessage) + e.keyValuePairs.map { it.value.toString() } }
            for (secret in listOf(revoked.secret, expired, unknown)) {
                assertTrue(everything.none { secret in it || sha256Hex(secret) in it }, "neither a token nor its digest is ever audited")
            }
        }
    }

    @Test
    fun `used is audited per token per minute with the method and path, coalesced, and never carries the secret`() = testApplication {
        var now = 5_000_000L
        configureApp()
        application { attributes.put(ServiceTokenUsedAuditKey, RateLimitAuditThrottle(clock = { now })) }
        startApplication()
        val issued = adminClient().issue("used")
        val other = adminClient().issue("used-other")
        val client = jsonClient()
        withAuditCapture { capture ->
            repeat(3) { client.get("$me?probe=q") { bearer(issued.secret) } }
            client.get(me) { bearer(other.secret) }
            val used = capture.events.filter { it.message == "service_token.used" }
            assertEquals(2, used.size, "one per token in the window")
            val event = used.single { it.hasKeyValue("tokenId", issued.token.id.toLong()) }
            assertTrue(event.hasKeyValue("name", issued.token.name))
            assertTrue(event.hasKeyValue("method", "GET"))
            assertTrue(event.hasKeyValue("path", me), "the path only, never the query string")
            assertTrue(event.hasKeyValue("suppressed", 0L))
            now += 61_000
            client.get(me) { bearer(issued.secret) }
            val later = capture.events.filter { it.message == "service_token.used" && it.hasKeyValue("tokenId", issued.token.id.toLong()) }
            assertTrue(later.last().hasKeyValue("suppressed", 2L))
            val everything = capture.events.flatMap { e -> listOf(e.formattedMessage) + e.keyValuePairs.map { it.value.toString() } }
            assertFalse(everything.any { issued.secret in it || sha256Hex(issued.secret) in it || "probe" in it })
        }
    }
}
