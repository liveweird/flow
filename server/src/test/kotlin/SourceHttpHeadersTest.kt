package ch.nokillswit

import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.fetch.SourceHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared transport's headers-aware transform, the configurable auth header and the shared permit pool (`fetch/`), over a
 * `MockEngine`.
 */
class SourceHttpHeadersTest {

    private fun http(
        engine: MockEngine,
        source: FetchSource = FetchSource("Test", "test.maxResponseBytes"),
        permits: OutboundPermits? = null,
    ): SourceHttp = SourceHttp(
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
        },
        maxRetries = 0,
        maxResponseBytes = 1_000_000,
        maxConcurrentRequests = 4,
        source = source,
        random = { 0.5 },
        sleeper = {},
        permits = permits,
    )

    @Test
    fun `the transform sees the response headers and the parsed tree`() = runBlocking {
        val engine = MockEngine {
            respond("[1,2]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"),
                "X-Next-Page" to listOf("2")))
        }
        val (size, next) = http(engine).requestAsWithHeaders(HttpMethod.Get, "https://example.test/list") { json, headers ->
            json.jsonArray.size to headers["X-Next-Page"]
        }
        assertEquals(2, size)
        assertEquals("2", next)
    }

    @Test
    fun `requestAs and request still return the tree`() = runBlocking {
        val engine = MockEngine { respond("[1]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val transport = http(engine)
        assertEquals(1, transport.requestAs(HttpMethod.Get, "https://example.test/x") { it.jsonArray.size })
        assertTrue(transport.request(HttpMethod.Get, "https://example.test/x") is JsonArray)
    }

    @Test
    fun `the auth header travels under the source's header name and Authorization stays unset`() = runBlocking {
        var custom: String? = null
        var authorization: String? = "unset"
        val engine = MockEngine { request ->
            custom = request.headers["PRIVATE-TOKEN"]
            authorization = request.headers[HttpHeaders.Authorization]
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        http(engine, FetchSource("Test", "test.maxResponseBytes", authHeaderName = "PRIVATE-TOKEN"))
            .request(HttpMethod.Get, "https://example.test/x", authHeader = "tok")
        assertEquals("tok", custom)
        assertNull(authorization)
    }

    @Test
    fun `two transports over one permit pool of one serialise`() = runBlocking {
        val permits = OutboundPermits(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inFlight = 0
        var peak = 0
        val engine = MockEngine {
            inFlight++
            peak = maxOf(peak, inFlight)
            if (!entered.isCompleted) entered.complete(Unit)
            release.await()
            inFlight--
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val first = http(engine, permits = permits)
        val second = http(engine, permits = permits)
        val a = async { first.request(HttpMethod.Get, "https://example.test/a") }
        entered.await()
        val b = async { second.request(HttpMethod.Get, "https://example.test/b") }
        delay(150)
        assertEquals(1, inFlight, "the second call waits while the first holds the only permit")
        release.complete(Unit)
        a.await()
        b.await()
        assertEquals(1, peak)
    }
}
