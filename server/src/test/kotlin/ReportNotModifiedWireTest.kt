package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.server.netty.EngineMain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the REAL Netty engine puts on the wire for a report `304` (RFC 9110 §8.6 allows a `304` `Content-Length` only with the value the
 * `200` would have; Ktor sends `0`, a known deviation pinned below). The engine test host used elsewhere does not go through Netty, so
 * this boots the server like
 * `RawForwardedForLinesTest` and reads the raw response by hand.
 */
class ReportNotModifiedWireTest {

    @Test
    fun `a report 304 over Netty has no body and and no framing but the engine's zero length, and keeps the security headers`() {
        val server = EngineMain.createServer(
            arrayOf(
                "-port=0",
                "-P:ktor.development=true",
                "-P:postgres.jdbcUrl=${PostgresTestSupport.jdbcUrl}",
                "-P:postgres.r2dbcUrl=${PostgresTestSupport.r2dbcUrl}",
                "-P:postgres.user=${PostgresTestSupport.user}",
                "-P:postgres.password=${PostgresTestSupport.password}",
                "-P:security.csrf.enabled=false",
                // The production default role is `all`: without these a live ingest worker and sampler would run against the SHARED
                // container (TestEnvironment.configureApp sets the same two), claiming other tests' jobs and moving the data stamp.
                "-P:app.role=web",
                "-P:telemetry.enabled=false",
            ),
        )
        var userId: UInt? = null
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val email = uniqueEmail("wire-304")
            userId = runBlocking { TestUsers.seed(email, "pw", role = FlowUserRole.USER) }

            val http = HttpClient.newHttpClient()
            val login = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"pw"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, login.statusCode(), login.body())
            val token = assertNotNull(Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(login.body())?.groupValues?.get(1))
            val filters = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/reports/filters"))
                    .header("Authorization", "Bearer $token").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, filters.statusCode())
            val etag = assertNotNull(filters.headers().firstValue("ETag").orElse(null))

            fun raw(method: String, extra: String = ""): String = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10_000
                val request = "$method /api/v1/reports/filters HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n" +
                    "$extra" + "Connection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readText()
            }
            val raw = raw("GET", "If-None-Match: $etag\r\n")
            // A HEAD (the spec declares no HEAD operation, so the conformance-checked client cannot send one) carries the same posture.
            val headLines = raw("HEAD").substringBefore("\r\n\r\n").lines()
            assertTrue(headLines.first().contains(" 200"), headLines.first())
            // The validator ran on the HEAD too: its ETag, ONE Cache-Control (never the blanket AND the validator), no Vary.
            fun headerValues(lines: List<String>, name: String) =
                lines.drop(1).filter { it.substringBefore(':').equals(name, ignoreCase = true) }.map { it.substringAfter(':').trim() }
            assertEquals(listOf("no-store"), headerValues(headLines, "Cache-Control"), "HEAD: $headLines")
            // One weak tag proves the validator ran; not equal to `etag`: the shared suite's data stamp may move between the two calls.
            assertTrue(headerValues(headLines, "ETag").single().startsWith("W/\""), "HEAD: $headLines")
            assertTrue(headerValues(headLines, "Vary").isEmpty(), "HEAD carries no Vary; the head was: $headLines")
            val head = raw.substringBefore("\r\n\r\n").lines()
            assertTrue(head.first().contains(" 304"), head.first())
            assertEquals(listOf("no-store"), headerValues(head, "Cache-Control"), "304: $head")
            val names = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            assertEquals("", raw.substringAfter("\r\n\r\n"), "a 304 has no body")
            // KNOWN DEVIATION (reports.md "Cache validators"): Ktor's `BaseApplicationResponse.commitHeaders` appends
            // `Content-Length: 0` to every NoContent answer, a 304 included, and no route-level hook can remove it (it runs after every
            // plugin hook; a content-level header cannot name Transfer-Encoding). A 304 is bodiless by definition (and, with no-store,
            // nothing is refreshed from it), so it is harmless; the pin is that nothing OTHER than that exact value
            // ever appears (no body length, no chunking).
            assertTrue(names["content-length"] in setOf(null, "0"), "a 304 may carry only Content-Length: 0 here; the head was: $head")
            assertFalse("transfer-encoding" in names, "no chunked framing on a 304; the head was: $head")
            assertEquals(etag, names["etag"])
            assertEquals("nosniff", names["x-content-type-options"])
            assertNotNull(names["content-security-policy"], "the security headers ride a 304 too")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            userId?.let { runBlocking { TestUsers.softDelete(it) } }
        }
    }
}
