package ch.nokillswit

import ch.nokillswit.fetch.CallTally
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.SourceHttp
import ch.nokillswit.jira.createJiraHttp
import ch.nokillswit.jira.fetchAheadInOrder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private val NEAR_LIMIT_HEADERS =
    headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-RateLimit-NearLimit" to listOf("true"))

/**
 * `CallTally` (`.claude/docs/jira-integration.md` "Rate limits and backoff"): the coroutine-context element `SourceHttp` counts a
 * sync job's 429s, back-off, tenant-pause time and near-limit pauses into. A Ktor `MockEngine`, a virtual clock and a sleeper that
 * only moves it, so nothing waits in real time (the `JiraClientTest` / `JiraHttpSharedPauseTest` pattern).
 */
class JiraHttpTallyTest {
    private val clock = AtomicLong(1_000)

    private fun http(engine: MockEngine, maxRetries: Int = 2) = createJiraHttp(
        HttpClient(engine) { expectSuccess = false; followRedirects = false },
        maxRetries = maxRetries, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4,
        random = { 0.5 }, sleeper = { clock.addAndGet(it) }, clock = { clock.get() },
    )

    private suspend fun SourceHttp.get(path: String, tenant: String? = null) =
        request(HttpMethod.Get, "https://tenant.test$path", pauseKey = tenant)

    @Test
    fun `a 429 with Retry-After is counted with the back-off it slept`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            if (calls++ == 0) {
                respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
            } else {
                respond("{}", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val tally = CallTally()
        withContext(tally) { http(engine).get("/a") }
        assertEquals(mapOf("jiraRateLimited" to 1L, "jiraBackoffMs" to 3_000L), tally.snapshot(), "only non-zero counters are present")
    }

    @Test
    fun `a call that gives up still counts every 429 it saw`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1")) }
        val tally = CallTally()
        val failure = assertFailsWith<FetchException> { withContext(tally) { http(engine, maxRetries = 2).get("/a") } }
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals(3L, tally.snapshot()["jiraRateLimited"], "the first attempt and both retries")
        assertEquals(2_000L, tally.snapshot()["jiraBackoffMs"], "two back-offs of 1 s; the last 429 gave up without sleeping")
    }

    @Test
    fun `a near-limit response is counted as a pause`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, NEAR_LIMIT_HEADERS) }
        val tally = CallTally()
        withContext(tally) { http(engine).get("/a"); http(engine).get("/b") }
        assertEquals(mapOf("jiraNearLimitPauses" to 2L), tally.snapshot())
    }

    @Test
    fun `a tenant pause set by another call is counted as time waited, not as a 429 of the waiter`() = runBlocking {
        val first = AtomicInteger()
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/limited" && first.getAndIncrement() == 0) {
                respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5"))
            } else {
                respond("{}", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val http = http(engine)
        // The first job's call is rate limited and records the tenant's pause (its own back-off follows); the second job - its own
        // tally - starts afterwards and waits the pause out.
        val limitedJob = CallTally()
        withContext(limitedJob) { http.get("/limited", tenant = "t") }
        clock.set(1_000) // the pause (5 s) is ahead again, as it was when the second job started
        val waitingJob = CallTally()
        withContext(waitingJob) { http.get("/other", tenant = "t") }
        assertEquals(mapOf("jiraRateLimited" to 1L, "jiraBackoffMs" to 5_000L), limitedJob.snapshot())
        assertEquals(mapOf("jiraTenantPausedMs" to 5_000L), waitingJob.snapshot())
    }

    @Test
    fun `fetch-ahead children share the job's tally, calls outside any tally count nothing`() = runBlocking {
        val http = http(MockEngine { respond("{}", HttpStatusCode.OK, NEAR_LIMIT_HEADERS) })
        val tally = CallTally()
        withContext(tally) {
            fetchAheadInOrder((1..5).toList(), window = 4, fetch = { http.get("/child$it") }) { _, outcome -> outcome.getOrThrow() }
        }
        assertEquals(5L, tally.snapshot()["jiraNearLimitPauses"], "five children of the look-ahead, one shared tally")

        http.get("/outside")
        assertEquals(5L, tally.snapshot()["jiraNearLimitPauses"], "a call in no tally touches none")
        assertTrue(CallTally().snapshot().isEmpty(), "a fresh tally has no keys")
    }
}
