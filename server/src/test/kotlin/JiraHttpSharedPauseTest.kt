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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private const val TIMEOUT_MILLIS = 10_000L
private const val TENANT_A = "tenant-a"
private const val TENANT_B = "tenant-b"
private const val CLOUD_A = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
private const val CLOUD_B = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e"

/**
 * The shared rate-limit pause of `JiraHttp` (`jira-integration.md` "Rate limits and backoff"): one call's 429 (or a 5xx with
 * `Retry-After`) makes every other call OF THE SAME TENANT wait before it starts a request, never another tenant's, and nobody waits
 * while holding a semaphore permit. A Ktor `MockEngine`, a virtual clock and a sleeper the test controls, so no test waits in real time.
 */
class JiraHttpSharedPauseTest {
    private val clock = AtomicLong(1_000)
    private val sleeps = CopyOnWriteArrayList<Long>()

    /** [sleeper] defaults to a virtual sleep: it only moves the clock. */
    private fun http(
        engine: MockEngine,
        limit: Int,
        maxRetries: Int,
        deadlineMillis: Long = 180_000,
        sleeper: suspend (Long) -> Unit = { clock.addAndGet(it) },
    ): JiraHttp = JiraHttp(
        HttpClient(engine) { expectSuccess = false; followRedirects = false },
        maxRetries = maxRetries, maxResponseBytes = 1_000_000, maxConcurrentRequests = limit, requestDeadlineMillis = deadlineMillis,
        random = { 0.5 }, sleeper = { sleeps += it; sleeper(it) }, clock = { clock.get() },
    )

    private suspend fun JiraHttp.get(path: String, tenant: String? = TENANT_A) =
        request(HttpMethod.Get, "https://tenant.test$path", pauseKey = tenant)

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(TIMEOUT_MILLIS) { while (!condition()) delay(5) }

    @Test
    fun `a 429 on one call holds the queued calls of its tenant back until its Retry-After has passed, then they proceed`() =
        runBlocking<Unit> {
            val starts = CopyOnWriteArrayList<Pair<String, Long>>()
            val limited = CompletableDeferred<Unit>()
            val firstOfA = AtomicBoolean(true)
            val bAndCOnTheWire = CompletableDeferred<Unit>()
            val onTheWire = AtomicInteger()
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                starts += path to clock.get()
                when {
                    // The 429 waits until /b and /c are on the wire: answered earlier, /b or /c could see the pause before it sends
                    // (correctly waiting it out too, so the sleeper would run more than the three times this test counts on).
                    path == "/a" && firstOfA.compareAndSet(true, false) -> {
                        bAndCOnTheWire.await()
                        respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5"))
                    }
                    // /b and /c stay in flight (holding a permit each) until /a has recorded the pause, so /d and /e are still queued.
                    path == "/b" || path == "/c" -> {
                        if (onTheWire.incrementAndGet() == 2) bAndCOnTheWire.complete(Unit)
                        limited.await()
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    }
                    else -> respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                }
            }
            val release = CompletableDeferred<Unit>()
            // The first sleep is /a's backoff, which starts AFTER the pause is recorded: that is what lets /b and /c finish.
            val http = http(engine, limit = 3, maxRetries = 2) { limited.complete(Unit); release.await() }
            // UNDISPATCHED: each call runs up to its first suspension here, so /a, /b, /c take the three permits in this order.
            val calls = listOf("/a", "/b", "/c", "/d", "/e").map { path ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { http.get(path) }
            }

            // /a backs off 5 s; /d and /e take permits as they free up, find the pause and wait it out - all three without a permit.
            waitUntil { sleeps.size == 3 }
            assertEquals(listOf(5_000L, 5_000L, 5_000L), sleeps.toList())
            assertEquals(listOf("/a", "/b", "/c"), starts.map { it.first }.sorted(), "/d and /e have not started a request")

            clock.set(6_000)
            release.complete(Unit)
            withTimeout(TIMEOUT_MILLIS) { calls.awaitAll() }
            val late = starts.filter { (path, _) -> path == "/d" || path == "/e" } + starts.last { it.first == "/a" }
            assertEquals(3, late.size)
            assertTrue(late.all { it.second >= 6_000 }, "everything that started after the 429 started after its deadline: $late")
            assertEquals(6, starts.size, "one request per call plus /a's retry")
        }

    @Test
    fun `a 5xx with Retry-After and a 429 without it pause the tenant, a plain 5xx pauses nobody`() = runBlocking<Unit> {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/plain5xx" -> respond("{}", HttpStatusCode.ServiceUnavailable, JSON_HEADERS)
                "/503retryAfter" -> respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "7"))
                "/httpDate" -> respond(
                    "{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "Wed, 21 Oct 2026 07:28:00 GMT"),
                )
                "/429" -> respond("{}", HttpStatusCode.TooManyRequests, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.OK, JSON_HEADERS)
            }
        }
        val http = http(engine, limit = 3, maxRetries = 0)

        assertFailsWith<JiraFetchException> { http.get("/plain5xx") }
        http.get("/ok")
        assertEquals(emptyList(), sleeps.toList(), "a 5xx without Retry-After is that call's problem only")

        assertFailsWith<JiraFetchException> { http.get("/503retryAfter") }
        http.get("/ok")
        assertEquals(listOf(7_000L), sleeps.toList(), "the next call waited out the Retry-After, though the first call was not retried")

        assertFailsWith<JiraFetchException> { http.get("/429") }
        http.get("/ok")
        assertEquals(listOf(7_000L, 2_000L), sleeps.toList(), "a 429 without Retry-After pauses for the computed backoff (2 s x 1.0)")

        assertFailsWith<JiraFetchException> { http.get("/httpDate") }
        http.get("/ok")
        assertEquals(listOf(7_000L, 2_000L, 2_000L), sleeps.toList(), "an HTTP-date Retry-After is no header: the computed backoff")

        http.get("/ok")
        assertEquals(3, sleeps.size, "once the pause has passed nothing waits")
    }

    @Test
    fun `a paused tenant's waiting call holds no permit, so another tenant proceeds at once, and cancelling the waiter sends nothing`() =
        runBlocking<Unit> {
            val requests = CopyOnWriteArrayList<String>()
            val engine = MockEngine { request ->
                requests += request.url.encodedPath
                if (request.url.encodedPath == "/limited") {
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                } else {
                    respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                }
            }
            val waiting = CompletableDeferred<Unit>()
            val pausing = AtomicBoolean(true)
            val http = http(engine, limit = 1, maxRetries = 0) { millis ->
                if (pausing.get()) {
                    waiting.complete(Unit)
                    awaitCancellation()
                }
                clock.addAndGet(millis)
            }
            assertFailsWith<JiraFetchException> { http.get("/limited", TENANT_A) }

            val parked = async(Dispatchers.Default) { http.get("/parked", TENANT_A) }
            withTimeout(TIMEOUT_MILLIS) { waiting.await() }
            // The only permit is free: tenant B (and a keyless call) do not wait for tenant A's pause.
            withTimeout(TIMEOUT_MILLIS) { http.get("/b", TENANT_B) }
            withTimeout(TIMEOUT_MILLIS) { http.get("/keyless", null) }
            assertEquals(1, sleeps.size, "only tenant A's waiter slept")

            parked.cancelAndJoin()
            assertTrue(parked.isCancelled)
            assertEquals(listOf("/limited", "/b", "/keyless"), requests.toList(), "the cancelled call never sent a request")

            pausing.set(false)
            withTimeout(TIMEOUT_MILLIS) { http.get("/after", TENANT_A) }
            assertEquals(listOf("/limited", "/b", "/keyless", "/after"), requests.toList(), "tenant A proceeds once the pause is served")
        }

    @Test
    fun `HttpJiraClient keys the pause by cloud id, so one tenant's 429 never stalls a client of another tenant on the same JiraHttp`() =
        runBlocking<Unit> {
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path == "/_edge/tenant_info" -> {
                        val cloudId = if (request.url.host == "a.test") CLOUD_A else CLOUD_B
                        respond("""{"cloudId":"$cloudId"}""", HttpStatusCode.OK, JSON_HEADERS)
                    }
                    path.startsWith("/ex/jira/$CLOUD_A/") ->
                        respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                    else -> respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                }
            }
            val http = http(engine, limit = 2, maxRetries = 0)
            val a = HttpJiraClient(http, "https://a.test", null, "svc@example.com", "token", JiraAuthScheme.BASIC)
            val b = HttpJiraClient(http, "https://b.test", null, "svc@example.com", "token", JiraAuthScheme.BASIC)
            a.resolveCloudId()
            b.resolveCloudId()

            assertEquals("RATE_LIMITED", assertFailsWith<JiraFetchException> { a.myself() }.code)
            b.myself()
            assertEquals(emptyList(), sleeps.toList(), "tenant B did not wait for tenant A's pause")
            assertEquals("RATE_LIMITED", assertFailsWith<JiraFetchException> { a.myself() }.code)
            assertEquals(listOf(30_000L), sleeps.toList(), "tenant A's next call waited it out")
        }

    @Test
    fun `time spent queueing for a permit is not charged to a retry, which is still sent after the deadline has passed`() =
        runBlocking<Unit> {
            val requests = CopyOnWriteArrayList<String>()
            val firstOfX = AtomicBoolean(true)
            val zHolds = CompletableDeferred<Unit>()
            val zOnTheWire = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                requests += path
                when {
                    path == "/x" && firstOfX.compareAndSet(true, false) ->
                        respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                    path == "/z" -> {
                        zOnTheWire.complete(Unit)
                        zHolds.await()
                        respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                    }
                    else -> respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                }
            }
            val backoffServed = CompletableDeferred<Unit>()
            val sleepCalls = AtomicInteger()
            // Deadline: 10 s from clock 1000, i.e. 11000. The one permit is free during /x's 1 s backoff.
            val http = http(engine, limit = 1, maxRetries = 2, deadlineMillis = 10_000) { millis ->
                if (sleepCalls.incrementAndGet() == 1) backoffServed.await() else clock.addAndGet(millis)
            }
            // On this test's own single-threaded loop (no Dispatchers.Default): /x and /z only run when the test body suspends.
            val x = async(start = CoroutineStart.UNDISPATCHED) { http.get("/x") }
            waitUntil { sleeps.size == 1 } // /x is backing off, its permit handed back

            // A keyless call (no pause to obey) takes the only permit and keeps it.
            val z = async(start = CoroutineStart.UNDISPATCHED) { http.get("/z", null) }
            withTimeout(TIMEOUT_MILLIS) { zOnTheWire.await() }

            clock.set(2_000) // the backoff has elapsed, inside the deadline
            backoffServed.complete(Unit)
            // /x was resumed by the line above and is ahead of this body in the loop's queue: yield() lets it run, synchronously, through
            // its deadline check (passed: 2000 <= 11000) until it suspends in the permit queue /z holds. No timing involved.
            yield()
            clock.set(50_000) // the queue wait alone outlasts /x's whole 10 s budget
            zHolds.complete(Unit)

            withTimeout(TIMEOUT_MILLIS) { z.await() }
            withTimeout(TIMEOUT_MILLIS) { x.await() } // would throw RATE_LIMITED if the queue wait counted
            assertEquals(listOf("/x", "/z", "/x"), requests.toList(), "the retry was sent")
        }

    @Test
    fun `a retry whose backoff and pause outlast the call's deadline is not sent`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<String>()
        val limited = CompletableDeferred<Unit>()
        val yFinished = CompletableDeferred<Unit>()
        val yStarted = CompletableDeferred<Unit>()
        val sleepCalls = AtomicInteger()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += path
            when (path) {
                // /x is told to wait only once /y is on the wire, so /y does not itself meet /x's pause.
                "/x" -> {
                    yStarted.await()
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5"))
                }
                // /y is in flight while /x backs off, and is then told to wait 100 s: the tenant's pause grows past /x's deadline.
                else -> {
                    yStarted.complete(Unit)
                    limited.await()
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "100"))
                }
            }
        }
        val http = http(engine, limit = 2, maxRetries = 3, deadlineMillis = 10_000) { millis ->
            if (sleepCalls.incrementAndGet() == 1) {
                limited.complete(Unit)
                yFinished.await()
                clock.set(6_000) // /x's own 5 s backoff has elapsed
            } else {
                clock.addAndGet(millis)
            }
        }
        val x = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { runCatching { http.get("/x") } }
        val y = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { http.get("/y") }.also { yFinished.complete(Unit) }
        }

        val yFailure = withTimeout(TIMEOUT_MILLIS) { y.await() }.exceptionOrNull()
        assertIs<JiraFetchException>(yFailure)
        assertEquals("RATE_LIMITED", yFailure.code, "/y refused a 100 s wait against its 10 s budget")
        val xFailure = withTimeout(TIMEOUT_MILLIS) { x.await() }.exceptionOrNull()
        assertIs<JiraFetchException>(xFailure)
        assertEquals("RATE_LIMITED", xFailure.code, "/x, after its backoff and the extended pause, was past its deadline")
        assertEquals(listOf("/x", "/y"), requests.sorted(), "/x's retry was never sent")
    }
}
