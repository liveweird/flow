package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.SourceHttp
import ch.nokillswit.jira.JIRA_SOURCE
import ch.nokillswit.jira.createJiraHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `SourceHttp.requestAs` (`.claude/docs/jira-integration.md` "Stored-size ceilings"): the caller's transform runs on the parsed tree
 * while the big-parse lock is still held, so the streams that accumulate answers (CHANGELOGS, WORKLOGS) never keep a page tree alive.
 */
class JiraRequestAsTest {
    private fun http(body: ByteArray, concurrent: Int = 4): SourceHttp {
        val client = HttpClient(
            MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            expectSuccess = false
            followRedirects = false
        }
        return createJiraHttp(client, maxRetries = 0, maxResponseBytes = 8L * 1024 * 1024, maxConcurrentRequests = concurrent, sleeper = {})
    }

    private val big = "[${"0,".repeat(600_000)}0]".toByteArray()
    private val small = "[1,2,3]".toByteArray()

    @Test
    fun `concurrent big fetches never overlap their transforms, and each gets only its transform's result`() = runBlocking<Unit> {
        assertTrue(big.size > 1024 * 1024)
        val http = http(big)
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val results = (1..4).map { n ->
            async(Dispatchers.Default) {
                http.requestAs(HttpMethod.Get, "https://stub.invalid/x") { tree ->
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    Thread.sleep(50) // long enough for another call to reach the lock if it were free
                    inside.decrementAndGet()
                    n * 1000 + (tree as JsonArray).size
                }
            }
        }.awaitAll()
        assertEquals(listOf(1_000, 2_000, 3_000, 4_000).map { it + 600_001 }, results)
        assertEquals(1, maxInside.get(), "at most one big tree is being transformed at a time")
    }

    @Test
    fun `a small body takes no lock and a plain request still returns the tree`() = runBlocking<Unit> {
        val http = http(small)
        assertEquals(3, http.requestAs(HttpMethod.Get, "https://stub.invalid/x") { (it as JsonArray).size })
        assertEquals(3, (http.request(HttpMethod.Get, "https://stub.invalid/x") as JsonArray).size)
    }

    @Test
    fun `a failing transform propagates unchanged and releases the lock`() = runBlocking<Unit> {
        val http = http(big)
        assertFailsWith<IllegalStateException> { http.requestAs<Unit>(HttpMethod.Get, "https://stub.invalid/x") { error("boom") } }
        val failure = assertFailsWith<FetchException> {
            http.requestAs<Unit>(HttpMethod.Get, "https://stub.invalid/x") {
                throw FetchException(JIRA_SOURCE, "INVALID_RESPONSE", null, "x")
            }
        }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(600_001, http.requestAs(HttpMethod.Get, "https://stub.invalid/x") { (it as JsonArray).size })
    }

    @Test
    fun `the changelog and worklog streams fetch their pages through the lock-holding As calls, never the plain ones`() {
        val dir = listOf(File("src/main/kotlin/jira"), File("server/src/main/kotlin/jira")).first { it.isDirectory }
        listOf("JiraChangelogStream.kt", "JiraWorklogStream.kt").forEach { name ->
            val source = File(dir, name).readText()
            listOf("changelogBulk(", "issueChangelogPage(", "issueWorklogPage(", "worklogList(").forEach { plain ->
                assertTrue("client.$plain" !in source, "$name calls client.$plain: a page tree would outlive the big-parse lock")
            }
        }
        assertTrue("client.changelogBulkAs(" in File(dir, "JiraChangelogStream.kt").readText())
        assertTrue("client.issueWorklogPageAs(" in File(dir, "JiraWorklogStream.kt").readText())
    }
}
