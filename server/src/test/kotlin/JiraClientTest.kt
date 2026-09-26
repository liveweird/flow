package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * `JiraHttp`/`HttpJiraClient` against a scripted Ktor `MockEngine` (v0.2.0 plan §11) — no network,
 * no WireMock needed for this suite (that's `DataSourceTestConnectionTest`, over the real stub).
 */
class JiraClientTest {

    private fun jiraHttp(
        engine: MockEngine,
        maxRetries: Int = 2,
        maxResponseBytes: Long = 1_000_000,
        requestDeadlineMillis: Long = 180_000,
        clock: () -> Long = { 0L },
    ): JiraHttp {
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            // Ktor's client-side HttpRedirect plugin (separate from the OkHttp engine's own
            // followRedirects, which JiraHttp/OutboundGuard never enable in production either)
            // must be off too, or a 3xx never reaches JiraHttp's own REDIRECT mapping.
            followRedirects = false
        }
        return JiraHttp(
            httpClient,
            maxRetries,
            maxResponseBytes,
            maxConcurrentRequests = 4,
            requestDeadlineMillis = requestDeadlineMillis,
            random = { 0.5 },
            sleeper = {},
            clock = clock,
        )
    }

    private fun jiraClient(
        engine: MockEngine,
        authScheme: JiraAuthScheme,
        apiToken: String,
        email: String = "svc@example.com",
    ): HttpJiraClient {
        val http = jiraHttp(engine, maxRetries = 0)
        return HttpJiraClient(http, "https://acme.atlassian.net", null, email, apiToken, authScheme)
    }

    @Test
    fun `429 is retried and then RATE_LIMITED once retries are exhausted`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("{}", HttpStatusCode.TooManyRequests, JSON_HEADERS)
        }
        val http = jiraHttp(engine, maxRetries = 2)
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("RATE_LIMITED", error.code)
        assertEquals(3, calls, "the initial attempt plus 2 retries")
    }

    @Test
    fun `a 5xx is retried and then UPSTREAM_UNAVAILABLE`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("{}", HttpStatusCode.ServiceUnavailable, JSON_HEADERS)
        }
        val http = jiraHttp(engine, maxRetries = 1)
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("UPSTREAM_UNAVAILABLE", error.code)
        assertEquals(2, calls)
    }

    @Test
    fun `a successful retry after 429 returns the parsed body`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("{}", HttpStatusCode.TooManyRequests, JSON_HEADERS)
            } else {
                respond("""{"ok":true}""", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val http = jiraHttp(engine, maxRetries = 2)
        val result = http.request(HttpMethod.Get, "https://example.atlassian.net/probe")
        assertEquals(2, calls)
        assertTrue(result.toString().contains("\"ok\":true"))
    }

    @Test
    fun `status maps to the documented failure codes`() {
        listOf(401 to "AUTHENTICATION_FAILED", 403 to "FORBIDDEN_SCOPE", 404 to "NOT_FOUND").forEach { (status, code) ->
            runBlocking {
                val engine = MockEngine { respond("{}", HttpStatusCode.fromValue(status), JSON_HEADERS) }
                val http = jiraHttp(engine, maxRetries = 0)
                val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
                assertEquals(code, error.code, "status $status")
            }
        }
    }

    @Test
    fun `a redirect is refused, never followed`() = runBlocking {
        val engine = MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/"))
        }
        val http = jiraHttp(engine, maxRetries = 0)
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("REDIRECT", error.code)
    }

    @Test
    fun `malformed JSON is INVALID_RESPONSE`() = runBlocking {
        val engine = MockEngine { respond("{ not json", HttpStatusCode.OK, JSON_HEADERS) }
        val http = jiraHttp(engine, maxRetries = 0)
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("INVALID_RESPONSE", error.code)
    }

    @Test
    fun `a response over the byte cap is LIMIT_EXCEEDED`() = runBlocking {
        val engine = MockEngine { respond("x".repeat(100), HttpStatusCode.OK, JSON_HEADERS) }
        val http = jiraHttp(engine, maxRetries = 0, maxResponseBytes = 10)
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("LIMIT_EXCEEDED", error.code)
    }

    @Test
    fun `Basic auth is base64(email colon token), Bearer is the raw token`() = runBlocking {
        var authHeaderSeen: String? = null
        fun engineFor(): MockEngine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/_edge/tenant_info")) {
                respond("""{"cloudId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}""", HttpStatusCode.OK, JSON_HEADERS)
            } else {
                authHeaderSeen = request.headers[HttpHeaders.Authorization]
                respond("""{"accountId":"u1"}""", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val basicClient = jiraClient(engineFor(), JiraAuthScheme.BASIC, apiToken = "tok-1", email = "svc@example.com")
        basicClient.resolveCloudId()
        basicClient.myself()
        val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("svc@example.com:tok-1".toByteArray())
        assertEquals(expectedBasic, authHeaderSeen)

        val bearerClient = jiraClient(engineFor(), JiraAuthScheme.BEARER, apiToken = "tok-2")
        bearerClient.resolveCloudId()
        bearerClient.myself()
        assertEquals("Bearer tok-2", authHeaderSeen)
    }

    @Test
    fun `searchJql honors the nextPageToken the previous page returned`() = runBlocking {
        val seenTokens = mutableListOf<String?>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/_edge/tenant_info")) {
                respond("""{"cloudId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}""", HttpStatusCode.OK, JSON_HEADERS)
            } else {
                seenTokens += request.url.parameters["nextPageToken"]
                val body = if (seenTokens.size == 1) """{"issues":[],"nextPageToken":"page-2"}""" else """{"issues":[]}"""
                respond(body, HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val client = jiraClient(engine, JiraAuthScheme.BASIC, apiToken = "tok")
        client.resolveCloudId()
        val first = client.searchJql("project in (\"ENG\")")
        assertEquals("page-2", first.nextPageToken)
        client.searchJql("project in (\"ENG\")", nextPageToken = first.nextPageToken)
        assertEquals(listOf(null, "page-2"), seenTokens)
    }

    @Test
    fun `resolveCloudId is unauthenticated - no Authorization header is sent`() = runBlocking {
        var sawAuthHeader = false
        val engine = MockEngine { request ->
            if (request.headers[HttpHeaders.Authorization] != null) sawAuthHeader = true
            respond("""{"cloudId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}""", HttpStatusCode.OK, JSON_HEADERS)
        }
        val client = jiraClient(engine, JiraAuthScheme.BASIC, apiToken = "tok")
        client.resolveCloudId()
        assertEquals(false, sawAuthHeader)
    }

    @Test
    fun `a non-UUID cloudId is INVALID_RESPONSE and never becomes the gateway path`() = runBlocking {
        val engine = MockEngine { respond("""{"cloudId":"not-a-uuid"}""", HttpStatusCode.OK, JSON_HEADERS) }
        val client = jiraClient(engine, JiraAuthScheme.BASIC, apiToken = "tok")
        val error = assertFailsWith<JiraFetchException> { client.resolveCloudId() }
        assertEquals("INVALID_RESPONSE", error.code)
    }

    @Test
    fun `a mixed-case cloudId is accepted and stored lowercase`() = runBlocking {
        val engine = MockEngine { respond("""{"cloudId":"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"}""", HttpStatusCode.OK, JSON_HEADERS) }
        val client = jiraClient(engine, JiraAuthScheme.BASIC, apiToken = "tok")
        val cloudId = client.resolveCloudId()
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", cloudId)
    }

    @Test
    fun `myself with a non-object body is INVALID_RESPONSE, not an uncaught raw-cast exception`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/_edge/tenant_info")) {
                respond("""{"cloudId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}""", HttpStatusCode.OK, JSON_HEADERS)
            } else {
                respond("[]", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val client = jiraClient(engine, JiraAuthScheme.BASIC, apiToken = "tok")
        client.resolveCloudId()
        val error = assertFailsWith<JiraFetchException> { client.myself() }
        assertEquals("INVALID_RESPONSE", error.code)
    }

    @Test
    fun `a Retry-After beyond the remaining request deadline fails RATE_LIMITED immediately, without waiting it out`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "{}",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter to listOf("9999"), HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val http = jiraHttp(engine, maxRetries = 5, requestDeadlineMillis = 5_000, clock = { 0L })
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("RATE_LIMITED", error.code)
        assertEquals(1, calls, "must fail on the first attempt once Retry-After alone exceeds the whole budget")
    }

    @Test
    fun `a 5xx retry that would exceed the remaining request deadline fails immediately instead of retrying past it`() = runBlocking {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) }
        val http = jiraHttp(engine, maxRetries = 5, requestDeadlineMillis = 1_000, clock = { 0L })
        val error = assertFailsWith<JiraFetchException> { http.request(HttpMethod.Get, "https://example.atlassian.net/probe") }
        assertEquals("UPSTREAM_UNAVAILABLE", error.code)
        assertEquals(1, calls, "the backoff itself already exceeds the 1s deadline, so no retry happens")
    }
}
