package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.gitlab.GITLAB_AUTH_HEADER
import ch.nokillswit.gitlab.GitLabRateLimitSignal
import ch.nokillswit.gitlab.GitLabSettings
import ch.nokillswit.gitlab.HttpGitLabClient
import ch.nokillswit.gitlab.createGitLabHttp
import ch.nokillswit.gitlab.tooBroad
import ch.nokillswit.infra.outbound.SelfManagedHosts
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://gitlab.example.test"
private const val CLOCK_NOW = 1_800_000_000_000L

/** `HttpGitLabClient` over the shared `SourceHttp` against a scripted `MockEngine` - no network, no WireMock. */
class GitLabClientTest {
    private val settings = GitLabSettings(SelfManagedHosts.NONE, null, null, 30, 8L * 1024 * 1024, 1, 180, 24)

    private class Wire(val sleeps: MutableList<Long> = mutableListOf())

    private fun client(engine: MockEngine, wire: Wire = Wire(), base: String = BASE, maxRetries: Int = 0): HttpGitLabClient {
        val http = createGitLabHttp(
            HttpClient(engine) {
                expectSuccess = false
                followRedirects = false
            },
            GitLabSettings(settings.hosts, null, null, 30, settings.maxResponseBytes, maxRetries, 180, 24),
            OutboundPermits(4),
            source = FetchSource("GitLab", "gitlab.maxResponseBytes", GitLabRateLimitSignal { CLOCK_NOW }, GITLAB_AUTH_HEADER),
            random = { 0.5 },
            sleeper = { wire.sleeps += it },
            clock = { CLOCK_NOW },
        )
        return HttpGitLabClient(http, base, "glpat-secret")
    }

    @Test
    fun `PRIVATE-TOKEN carries the token and Authorization is not sent`() = runBlocking {
        var privateToken: String? = null
        var authorization: String? = "unset"
        val engine = MockEngine { request ->
            privateToken = request.headers["PRIVATE-TOKEN"]
            authorization = request.headers[HttpHeaders.Authorization]
            respond("""{"version":"19.3.1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals("19.3.1", client(engine).version())
        assertEquals("glpat-secret", privateToken)
        assertNull(authorization)
    }

    @Test
    fun `X-Next-Page drives paging and a Link header to another host is never requested`() = runBlocking {
        val hosts = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            val page = request.url.parameters["page"]
            pages += page
            val next = if (page == "1") "2" else ""
            respond(
                if (page == "1") """[{"id":1}]""" else """[{"id":2}]""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "X-Next-Page" to listOf(next),
                    "X-Total" to listOf("2"),
                    HttpHeaders.Link to listOf("<https://evil.example.test/api/v4/users?page=2>; rel=\"next\""),
                ),
            )
        }
        val client = client(engine)
        val first = client.users(1)
        assertEquals(2, first.nextPage)
        assertEquals(2L, first.total)
        val second = client.users(first.nextPage!!)
        assertNull(second.nextPage, "an empty X-Next-Page is the end")
        assertEquals(listOf<String?>("1", "2"), pages)
        assertEquals(setOf("gitlab.example.test"), hosts.toSet(), "the Link header was never followed")
    }

    @Test
    fun `an absent X-Next-Page header is INVALID_RESPONSE, never the last page`() = runBlocking {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).users(1) }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).groupProjects(7, 1) }.code)
    }

    @Test
    fun `a token-info answer missing scopes or active is INVALID_RESPONSE`() = runBlocking {
        for (body in listOf("{}", """{"scopes":["read_api"]}""", """{"active":true}""")) {
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).tokenSelf() }.code, body)
        }
    }

    @Test
    fun `users come back slimmed to small values and the emails answer is reduced to its address strings`() = runBlocking {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/emails")) {
                """[{"id":1,"email":"S@x.test","confirmed_at":"2026-01-01"},{"id":2,"email":"t@x.test"}]"""
            } else {
                """[{"id":4,"username":"u4","name":"U Four","state":"active","bot":false,"email":"P@x.test","bio":"private text"}]"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("")))
        }
        val client = client(engine)
        val user = client.users(1).users.single()
        assertEquals(4L, user.id)
        assertEquals(listOf("P@x.test"), user.ownEmails)
        assertNull(user.slim["bio"], "the slim value carries no unlisted member")
        assertEquals(listOf("S@x.test", "t@x.test"), client.userEmails(4))
    }

    @Test
    fun `a page that does not advance, or is not a number, is INVALID_RESPONSE`() = runBlocking {
        for (next in listOf("1", "0", "abc")) {
            val engine = MockEngine {
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"),
                    "X-Next-Page" to listOf(next)))
            }
            val failure = assertFailsWith<FetchException> { client(engine).users(1) }
            assertEquals("INVALID_RESPONSE", failure.code, "X-Next-Page=$next")
        }
    }

    @Test
    fun `a group path travels as one percent-encoded segment and the list calls carry the scope parameters`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                if (request.url.encodedPath.endsWith("/projects")) "[]" else """{"id":8,"full_path":"platform/tooling"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("")),
            )
        }
        val client = client(engine)
        val group = client.group("platform/tooling")
        assertEquals(8L, group.id)
        client.groupProjects(group.id, 3)
        assertEquals("/api/v4/groups/platform%2Ftooling", seen[0].encodedPath, "Ktor must keep %2F as one segment")
        assertEquals("/api/v4/groups/8/projects", seen[1].encodedPath)
        assertEquals(
            mapOf("include_subgroups" to "true", "archived" to "false", "with_shared" to "false", "per_page" to "100", "page" to "3"),
            seen[1].parameters.entries().associate { it.key to it.value.single() },
        )
    }

    @Test
    fun `a malformed group path or id is refused before any request is made`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = client(engine)
        for (path in listOf("../etc", "a b", "a//b", "a/../b", "")) {
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.group(path) }.code, "path <$path>")
        }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.groupProjects(-1, 1) }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.userEmails(-5) }.code)
        assertEquals(0, requests)
    }

    @Test
    fun `a body of the wrong shape is INVALID_RESPONSE, never an uncaught exception`() = runBlocking {
        val engine = MockEngine {
            respond("""["not","an","object"]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = client(engine)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.currentUser() }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.tokenSelf() }.code)
        val objectEngine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(objectEngine).users(1) }.code)
    }

    private suspend fun sleepAfter(headers: Array<Pair<String, String>>): List<Long> {
        val wire = Wire()
        val engine = MockEngine {
            respond(
                """{"version":"1"}""", HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    *headers.map { it.first to listOf(it.second) }.toTypedArray(),
                ),
            )
        }
        client(engine, wire).version()
        return wire.sleeps
    }

    @Test
    fun `RateLimit-Remaining near zero pauses until the reset, clamped to 1-5 seconds, 2 s when the reset is unknown`() = runBlocking {
        val nowSeconds = CLOCK_NOW / 1_000
        assertEquals(listOf(5_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "${nowSeconds + 60}")))
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "${nowSeconds + 2}")))
        assertEquals(listOf(1_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "0", "RateLimit-Reset" to "${nowSeconds - 30}")),
            "a past reset still waits 1 s")
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3")))
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "soon")))
        assertEquals(emptyList(), sleepAfter(arrayOf("RateLimit-Remaining" to "100", "RateLimit-Reset" to "${nowSeconds + 60}")))
        assertEquals(emptyList(), sleepAfter(emptyArray()), "no headers, no pause")
    }

    @Test
    fun `a 429 pauses only the tenant that sent it - the pause key is the base URL`() = runBlocking {
        val wire = Wire()
        val engine = MockEngine { request ->
            if (request.url.host == "a.example.test" && request.url.encodedPath.endsWith("/version") && wire.sleeps.isEmpty()) {
                respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
            } else {
                respond("""{"version":"1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = createGitLabHttp(
            HttpClient(engine) { expectSuccess = false },
            GitLabSettings(settings.hosts, null, null, 30, settings.maxResponseBytes, 0, 180, 24),
            OutboundPermits(4),
            random = { 0.5 },
            sleeper = { wire.sleeps += it },
            clock = { CLOCK_NOW },
        )
        val tenantA = HttpGitLabClient(http, "https://a.example.test", "tok-a")
        val tenantB = HttpGitLabClient(http, "https://b.example.test", "tok-b")

        assertEquals("RATE_LIMITED", assertFailsWith<FetchException> { tenantA.version() }.code)
        assertEquals("1", tenantB.version())
        assertTrue(wire.sleeps.isEmpty(), "the other tenant did not wait: ${wire.sleeps}")
        assertEquals("1", tenantA.version())
        assertEquals(listOf(7_000L), wire.sleeps, "the 429's tenant waited the Retry-After out before its next call")
    }

    @Test
    fun `scopes that could write are too broad - api, sudo and write_ scopes`() {
        assertTrue(listOf("read_api", "api").tooBroad())
        assertTrue(listOf("sudo").tooBroad())
        assertTrue(listOf("read_api", "write_repository").tooBroad())
        for (scope in listOf("create_runner", "manage_runner", "k8s_proxy", "self_rotate", "ai_features", "unknown_future_scope")) {
            assertTrue(listOf("read_api", scope).tooBroad(), "$scope is outside the read_ allow-list")
        }
        assertFalse(listOf("read_api").tooBroad())
        assertFalse(listOf("read_api", "read_user", "read_repository").tooBroad())
        assertFalse(emptyList<String>().tooBroad())
        // An Admin-Mode instance needs admin_mode for an admin token to read emails; beside read_* it stays read-only.
        assertFalse(listOf("read_api", "admin_mode").tooBroad())
        assertTrue(listOf("api", "admin_mode").tooBroad())
        assertTrue(listOf("read_api", "ADMIN_MODE").tooBroad())
    }
}
