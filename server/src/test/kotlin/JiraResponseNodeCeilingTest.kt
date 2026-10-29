package ch.nokillswit

import ch.nokillswit.jira.DEPTH_CEILING_TEXT
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.MAX_RESPONSE_DEPTH
import ch.nokillswit.jira.NODE_CEILING_TEXT
import ch.nokillswit.jira.RESPONSE_BYTES_PER_NODE
import ch.nokillswit.jira.defaultMaxResponseNodes
import ch.nokillswit.jira.jsonCeilingBreach
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private const val NO_LIMIT = Long.MAX_VALUE

/**
 * The structural ceilings on a Jira response (`JiraNodeCeiling.kt`, `.claude/docs/jira-integration.md` "Node and depth ceiling"):
 * the scan counts tokens and nesting outside strings exactly, a dense or deep body is refused with the same `LIMIT_EXCEEDED` an
 * over-cap body is BEFORE it is parsed (so no `StackOverflowError`), the ceiling is derived from `jira.maxResponseBytes`, and every
 * real-shaped stub page that can approach the cap passes at the shipped density.
 */
class JiraResponseNodeCeilingTest {
    private fun bytes(json: String) = json.toByteArray(Charsets.UTF_8)

    private fun nested(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    // --- The scan -----------------------------------------------------------------------------------------------------

    @Test
    fun `the scan counts containers, entries and separators outside strings, exactly`() {
        // { : , : [ , ] } = 1 brace + 2 colons + 1 comma + 1 bracket + 1 comma: 6 in all.
        val document = bytes("""{"a":1,"b":[2,3]}""")
        assertNull(jsonCeilingBreach(document, 6))
        assertEquals(NODE_CEILING_TEXT, jsonCeilingBreach(document, 5))
        assertNull(jsonCeilingBreach(bytes("[]"), 1))
        assertEquals(NODE_CEILING_TEXT, jsonCeilingBreach(bytes("[]"), 0))
    }

    @Test
    fun `structural characters inside strings, escaped quotes and multi-byte text are neither counted nor nested`() {
        val hostile = """{"a":"{[:,{[:,","b":"say \"hi\", {x}\\","c":"zażółć gęślą jaźń, {:}","d":"[[[[[[[["}"""
        // The real tokens: the opening brace, four colons and three commas: 8.
        assertNull(jsonCeilingBreach(bytes(hostile), 8, depthLimit = 1))
        assertEquals(NODE_CEILING_TEXT, jsonCeilingBreach(bytes(hostile), 7, depthLimit = 1))
    }

    @Test
    fun `nesting past the depth ceiling is refused, at exactly one level over`() {
        assertNull(jsonCeilingBreach(bytes(nested(MAX_RESPONSE_DEPTH)), NO_LIMIT))
        assertEquals(DEPTH_CEILING_TEXT, jsonCeilingBreach(bytes(nested(MAX_RESPONSE_DEPTH + 1)), NO_LIMIT))
        // Siblings do not nest: closing a level frees it.
        assertNull(jsonCeilingBreach(bytes("[" + "[],".repeat(10_000) + "[]]"), NO_LIMIT))
        // A stray closer cannot take the depth below zero and hide the openers after it.
        assertEquals(DEPTH_CEILING_TEXT, jsonCeilingBreach(bytes("]]]]" + "[".repeat(MAX_RESPONSE_DEPTH + 1)), NO_LIMIT))
        assertEquals(DEPTH_CEILING_TEXT, jsonCeilingBreach(bytes("[".repeat(100_000)), NO_LIMIT))
    }

    @Test
    fun `the default ceiling is one token per eight bytes, 1 Mi tokens at the 8 MiB cap`() {
        assertEquals(8L, RESPONSE_BYTES_PER_NODE)
        assertEquals(1024L * 1024, defaultMaxResponseNodes(8L * 1024 * 1024))
        assertEquals(512, MAX_RESPONSE_DEPTH)
    }

    @Test
    fun `the hostile Jira host script duplicates the ceiling constants, so it is pinned to these`() {
        val script = listOf(File("sample-data/jira/hostile.mjs"), File("../sample-data/jira/hostile.mjs")).first { it.isFile }.readText()
        assertEquals(RESPONSE_BYTES_PER_NODE, Regex("""const BYTES_PER_NODE = (\d+);""").find(script)?.groupValues?.get(1)?.toLong())
        assertEquals(MAX_RESPONSE_DEPTH, Regex("""const MAX_DEPTH = (\d+);""").find(script)?.groupValues?.get(1)?.toInt())
    }

    // --- Through JiraHttp -----------------------------------------------------------------------------------------------

    private class Wire(val requests: MutableList<io.ktor.client.request.HttpRequestData> = CopyOnWriteArrayList())

    private fun http(
        maxResponseBytes: Long,
        body: ByteArray,
        wire: Wire = Wire(),
        headers: io.ktor.http.Headers = JSON_HEADERS,
        maxResponseNodes: Long? = null,
    ): JiraHttp {
        val client = HttpClient(MockEngine { request -> wire.requests += request; respond(body, HttpStatusCode.OK, headers) }) {
            expectSuccess = false
            followRedirects = false
        }
        // The production wiring (`configureJira`) passes only the byte cap; a test passes the nodes only to pin the boundary.
        return if (maxResponseNodes == null) {
            JiraHttp(client, maxRetries = 0, maxResponseBytes = maxResponseBytes, maxConcurrentRequests = 1, sleeper = {})
        } else {
            JiraHttp(
                client, maxRetries = 0, maxResponseBytes = maxResponseBytes, maxConcurrentRequests = 1, sleeper = {},
                maxResponseNodes = maxResponseNodes,
            )
        }
    }

    private suspend fun JiraHttp.get() = request(HttpMethod.Get, "https://stub.test/rest/api/3/x")

    @Test
    fun `a dense body inside the byte cap is refused as LIMIT_EXCEEDED before it is parsed, with constant text`() = runBlocking<Unit> {
        // 1 MiB cap -> 131,072 tokens. `{},` x 100,000 = 300 KB (inside the byte cap) and 200,000 tokens. The array is never closed:
        // had it been parsed it would be INVALID_RESPONSE, so LIMIT_EXCEEDED proves the scan came first.
        val dense = bytes("[" + "{},".repeat(100_000))
        assertTrue(dense.size < 1L * 1024 * 1024)
        val failure = assertFailsWith<JiraFetchException> { http(1L * 1024 * 1024, dense).get() }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertEquals(200, failure.status)
        assertEquals("Jira fetch failed: LIMIT_EXCEEDED (200): $NODE_CEILING_TEXT", failure.message)
    }

    @Test
    fun `a body nested a hundred thousand levels deep is a typed refusal, not a StackOverflowError`() = runBlocking<Unit> {
        val deep = bytes("[".repeat(100_000))
        val failure = assertFailsWith<JiraFetchException> { http(8L * 1024 * 1024, deep).get() }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertEquals("Jira fetch failed: LIMIT_EXCEEDED (200): $DEPTH_CEILING_TEXT", failure.message)
        // A well-formed one just past the ceiling is refused too (`CanonicalJsonTest` measured 515 / 1,015 object+array PAIRS, ~1,030 /
        // ~2,030 levels, on a worst-case 1 MiB thread before `toString()` / the parser overflow: 512 levels keeps ~2x margin).
        val closed = assertFailsWith<JiraFetchException> { http(8L * 1024 * 1024, bytes(nested(MAX_RESPONSE_DEPTH + 1))).get() }
        assertEquals("LIMIT_EXCEEDED", closed.code)
        // At the ceiling it parses, and re-serialises.
        val atCeiling = http(8L * 1024 * 1024, bytes(nested(MAX_RESPONSE_DEPTH))).get()
        assertEquals(nested(MAX_RESPONSE_DEPTH), atCeiling.toString())
    }

    @Test
    fun `the same refusal is the one an over-cap body gets, so a stream answers both alike`() = runBlocking<Unit> {
        val overCap = assertFailsWith<JiraFetchException> { http(1_024, ByteArray(2_000) { 'x'.code.toByte() }).get() }
        val dense = assertFailsWith<JiraFetchException> { http(1_024, bytes("[" + "0,".repeat(400))).get() }
        assertEquals(overCap.code, dense.code)
    }

    @Test
    fun `a body at the ceiling still parses, and one token over does not`() = runBlocking<Unit> {
        // [0,0,0,0] = 1 bracket + 3 commas.
        val parsed = http(8_192, bytes("[0,0,0,0]"), maxResponseNodes = 4).get()
        assertEquals(4, (parsed as JsonArray).size)
        assertFailsWith<JiraFetchException> { http(8_192, bytes("[0,0,0,0]"), maxResponseNodes = 3).get() }
    }

    @Test
    fun `a ceiling not passed is derived from the byte cap, as configureJira builds JiraHttp`() = runBlocking<Unit> {
        // 8,192 bytes -> 1,024 tokens: the bracket + 1,023 commas is exactly at it, one comma more is over.
        assertEquals(1_024, (http(8_192, bytes("[" + "0,".repeat(1_023) + "0]")).get() as JsonArray).size)
        assertFailsWith<JiraFetchException> { http(8_192, bytes("[" + "0,".repeat(1_024) + "0]")).get() }
        // And `configureJira` does not pass its own: no second key, so raising the cap raises the ceiling with it.
        val jiraKt = listOf(File("src/main/kotlin/jira/Jira.kt"), File("server/src/main/kotlin/jira/Jira.kt")).first { it.isFile }
        val source = jiraKt.readText()
        val construction = Regex("""JiraHttp\(([^)]*)\)""").find(source)?.groupValues?.get(1).orEmpty()
        assertTrue("maxResponseBytes" in construction && "maxResponseNodes" !in construction, construction)
        val yaml = checkNotNull(JiraResponseNodeCeilingTest::class.java.getResource("/application.yaml")).readText()
        assertTrue("maxResponseNodes" !in yaml)
    }

    @Test
    fun `a body over a mebibyte is parsed under the big-parse lock and arrives intact`() = runBlocking<Unit> {
        val big = bytes("[" + "0,".repeat(600_000) + "0]")
        assertTrue(big.size > 1024 * 1024)
        assertEquals(600_001, (http(8L * 1024 * 1024, big).get() as JsonArray).size)
    }

    @Test
    fun `a multi-byte body parses from its bytes to the same tree the text parses to`() = runBlocking<Unit> {
        // Polish diacritics, CJK, an astral emoji (a surrogate pair), a literal and an escaped pair, and \u escapes of both kinds.
        val text = """{"pl":"zażółć gęślą jaźń","cjk":"日本語のテキスト 漢字","emoji":"😀 𝄞","escaped":"\u0105\u4e2d \ud83d\ude00",""" +
            """"key ż 中":["é",1.5,null,true]}"""
        val expected = Json.parseToJsonElement(text)
        val parsed = http(8L * 1024 * 1024, bytes(text)).get()
        assertEquals(expected, parsed)
        assertEquals(expected.toString(), parsed.toString())
        val obj = parsed as JsonObject
        assertEquals("zażółć gęślą jaźń", (obj.getValue("pl") as JsonPrimitive).content)
        assertEquals("😀 𝄞", (obj.getValue("emoji") as JsonPrimitive).content)
        assertEquals("ą中 😀", (obj.getValue("escaped") as JsonPrimitive).content)
        // Past the 1 MiB big-parse lock, with a multi-byte character straddling the 8 KiB reads of the stream decoder.
        val big = "[" + "\"żółć日😀\",".repeat(150_000) + "\"end\"]"
        assertTrue(bytes(big).size > 1024 * 1024)
        assertEquals(Json.parseToJsonElement(big), http(8L * 1024 * 1024, bytes(big)).get())
    }

    @Test
    fun `a truncated body, text after the document and an empty body are INVALID_RESPONSE, not an unmapped one`() = runBlocking<Unit> {
        listOf(
            """{"a":[1,2""",
            """{"a":"zażółć""",
            """{"a":1}}""",
            """{"a":1} trailing""",
            """[1,2]3""",
            """{"a":1}{"b":2}""",
            "",
            "not json",
        ).forEach { body ->
            val failure = assertFailsWith<JiraFetchException>(body) { http(8L * 1024 * 1024, bytes(body)).get() }
            assertEquals("INVALID_RESPONSE", failure.code, body)
            assertEquals(200, failure.status, body)
        }
    }

    @Test
    fun `the node-ceiling WARN carries the constant ceiling text and numbers only, never the body`() = runBlocking<Unit> {
        val logs = LogCapture("ch.nokillswit.jira.JiraHttp")
        try {
            val marker = "HOSTSECRET"
            assertFailsWith<JiraFetchException> { http(1_024, bytes("""["$marker",""" + "0,".repeat(400) + "0]")).get() }
            val warn = logs.events.single { it.formattedMessage.startsWith("A Jira response was refused") }
            assertEquals(
                "A Jira response was refused ($NODE_CEILING_TEXT); ceilings: 128 tokens, $MAX_RESPONSE_DEPTH levels",
                warn.formattedMessage,
            )
            assertTrue(marker !in warn.formattedMessage)
        } finally {
            logs.detach()
        }
    }

    // --- Compression ----------------------------------------------------------------------------------------------------

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    @Test
    fun `identity encoding is asked for, and a gzip body that comes anyway is never inflated or parsed`() = runBlocking<Unit> {
        // A small compressed body that would inflate to a dense 3 MB document.
        val bomb = gzip(bytes("[" + "{},".repeat(1_000_000) + "{}]"))
        assertTrue(bomb.size < 100_000)
        val wire = Wire()
        val headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip"))
        val failure = assertFailsWith<JiraFetchException> { http(8L * 1024 * 1024, bomb, wire, headers).get() }
        assertEquals("INVALID_RESPONSE", failure.code, "the compressed bytes are what is read and parsed: not JSON")
        assertEquals("identity", wire.requests.single().headers[HttpHeaders.AcceptEncoding])
    }

    // --- The stub's pages ------------------------------------------------------------------------------------------------

    private fun stubFiles(): List<File> {
        val dir = listOf(File("sample-data/jira-stub/__files"), File("../sample-data/jira-stub/__files")).first { it.isDirectory }
        return dir.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
    }

    /**
     * The shapes denser than the ceiling's 0.125 tokens a byte: an ids-only `search/jql` page (0.20) and a project's statuses (0.13-0.14).
     * Both are tiny by construction (75 KB for 5,000 ids, ~76 KB for the busiest project), ~1% of the cap, so at the shipped cap they
     * are far below the ceiling in absolute terms; a tightly capped test would trip them and say nothing about the real wiring.
     */
    private fun denserThanCeiling(name: String) = name.startsWith("search-jql-ids-") || name.startsWith("project-statuses-")

    @Test
    fun `every real-shaped stub page of a kilobyte or more passes at the shipped density, the dense small shapes excepted`() =
        runBlocking<Unit> {
            var tight = 0
            var excepted = 0
            stubFiles().forEach { file ->
                val expected = Json.parseToJsonElement(file.readText())
                val compact = bytes(expected.toString())
                if (compact.size < 1_000) return@forEach
                // At the real default cap every page passes.
                assertEquals(expected, http(8L * 1024 * 1024, compact).get(), file.name)
                if (denserThanCeiling(file.name)) {
                    excepted++
                } else {
                    // The byte cap just above this page, so the DERIVED ceiling is what is under test, at the shipped ratio.
                    assertEquals(expected, http(compact.size.toLong() + 1, compact).get(), file.name)
                    tight++
                }
            }
            assertTrue(tight > 40 && excepted > 3, "the stub's pages were exercised: $tight tight, $excepted excepted")
        }

    @Test
    fun `the densest realistic page, 5,000 ids of an ids-only search, passes at the real cap`() = runBlocking<Unit> {
        val page = bytes("""{"issues":[""" + (30_001L..35_000L).joinToString(",") { """{"id":"$it"}""" } + """],"nextPageToken":"t"}""")
        val parsed = http(8L * 1024 * 1024, page).get() as JsonObject
        assertEquals(5_000, (parsed.getValue("issues") as JsonArray).size)
    }

    @Test
    fun `GET field at ten thousand fields keeps more than twice the headroom under the ceiling`() {
        // The one response that cannot be halved: ~4.1 MB on a real site (~420 B and ~0.11 tokens a byte a field).
        val field = bytes(Json.parseToJsonElement(stubFiles().first { it.name == "field.json" }.readText()).toString())
        val density = jsonTokens(field).toDouble() / field.size
        val realistic = 4_100_000 * density
        assertTrue(defaultMaxResponseNodes(8L * 1024 * 1024) > 2 * realistic, "density $density -> $realistic tokens")
    }

    private fun jsonTokens(bytes: ByteArray): Long {
        // The smallest limit that does not breach is the count: bisect on the scan itself.
        var low = 0L
        var high = bytes.size.toLong() + 1
        while (low < high) {
            val mid = (low + high) / 2
            if (jsonCeilingBreach(bytes, mid) == null) high = mid else low = mid + 1
        }
        return low
    }
}
