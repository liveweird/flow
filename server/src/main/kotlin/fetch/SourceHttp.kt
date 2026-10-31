package ch.nokillswit.fetch

import ch.nokillswit.infra.outbound.BlockedHostException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SourceHttp::class.java)

internal const val DEFAULT_REQUEST_DEADLINE_MILLIS = 180_000L

/**
 * The shared, low-level HTTP transport of a connector (v0.2.0 plan §6; Jira's today, `jira/JiraHttp.kt`): retry with [Backoff] on 429/5xx/
 * IOException up to [maxRetries], a [Semaphore] capping concurrent in-flight calls, a bounded
 * response read at [maxResponseBytes] (Covenant's `ToadieGraphqlClient` shape), parsed only under [maxResponseNodes] tokens and
 * [MAX_RESPONSE_DEPTH] levels (a big one behind one lock, `NodeCeiling.kt`), and the upstream's own
 * [RateLimitSignal] pause (Jira: `X-RateLimit-NearLimit`). [client] is expected to already carry `HttpTimeout` and the
 * guarded OkHttp engine (`jira/Jira.kt`) - tests substitute a Ktor `MockEngine` client instead.
 * [random]/[sleeper]/[clock] are the test seams `JiraBackoffTest`/`JiraClientTest` inject.
 *
 * [LOW-2] [requestDeadlineMillis] (config `jira.requestDeadlineSeconds`, default 180s) bounds the
 * TOTAL time one logical call may spend across every attempt plus backoff — [requestTimeoutSeconds]
 * (`HttpTimeout`, wired in `jira/Jira.kt`) only bounds a SINGLE attempt, so unbounded retries could
 * otherwise keep one call going for as long as [maxRetries] deep exponential backoff
 * sums to (~10 minutes at the ceiling). A `Retry-After` whose wait alone would cross the remaining
 * deadline fails `RATE_LIMITED` immediately rather than sleeping past the budget; any other retry
 * that would cross it fails with its own terminal code immediately the same way. The deadline is checked
 * again after the backoff and any tenant pause, before the retry asks for a permit again (time spent queueing for a permit is
 * not charged to the retry, as for a first attempt).
 *
 * **Permits are held only while a request runs.** The semaphore is process-wide (one [SourceHttp] serves every connection), so a call
 * hands its permit back while it merely waits - during its own backoff and while its tenant is paused - and re-acquires it after
 * ([Permit], [acquireClear]); a rate-limited tenant therefore cannot starve the others of permits. The one wait that still holds a
 * permit is the [RateLimitSignal] pause (Jira: the `X-RateLimit-NearLimit` 1s pause).
 *
 * **Shared pause, per tenant.** A 429 (or a 5xx that carries `Retry-After`; an HTTP-date `Retry-After` parses as no header, like the
 * backoff treats it) says the TENANT is rate limiting, not just this call, so it records a "not before" instant for the call's
 * `pauseKey` ([pauses]; `max(current, now + the wait the call is about to take)`, set even when the call itself gives up). Every other
 * call of the SAME tenant waits for it before it STARTS a request ([awaitPause]: [sleeper], so cancellable, never holding a permit; a
 * pause that appears while a call queued for a permit is caught after acquiring, and the permit goes back). Atlassian limits per tenant,
 * so the key is the cloud id (`HttpJiraClient`), NOT the host (`api.atlassian.com` is shared by every tenant); a call without a key
 * (the unauthenticated `tenant_info` lookup, direct callers) neither sets nor obeys a pause. Requests already on the wire are not
 * interrupted. A network error (IOException) or a plain 5xx pauses nobody. A tenant's entry stays for the process's life (one tiny entry
 * per cloud id).
 *
 * **Tally.** A call that runs inside a [CallTally] (a coroutine-context element, read once per call) counts its 429s, the back-off it
 * sleeps, the tenant pause it waits out and its rate-limit-signal pauses into the sync job's progress; outside one it counts nothing.
 */
class SourceHttp(
    private val client: HttpClient,
    private val maxRetries: Int,
    private val maxResponseBytes: Long,
    /** The one cap on in-flight calls: [semaphore]'s permits, and what a stream sizes its fetch-ahead by. */
    val maxConcurrentRequests: Int,
    /** Which upstream this transport talks to: failure texts, log lines and the pause rule ([FetchSource]). */
    private val source: FetchSource,
    private val requestDeadlineMillis: Long = DEFAULT_REQUEST_DEADLINE_MILLIS,
    private val random: () -> Double = Math::random,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The most structural tokens one response may carry (`NodeCeiling.kt`); by default a fixed density of [maxResponseBytes]. */
    private val maxResponseNodes: Long = defaultMaxResponseNodes(maxResponseBytes),
) {
    private val semaphore = Semaphore(maxConcurrentRequests)

    /** Held while a body over [BIG_BODY_BYTES] is parsed: at most one big tree is under construction at a time. */
    private val bigParses = Mutex()

    /** Per tenant key: the epoch-millis [clock] instant before which no call of that tenant starts a request. */
    private val pauses = ConcurrentHashMap<String, AtomicLong>()

    suspend fun request(
        method: HttpMethod,
        url: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: JsonElement? = null,
        authHeader: String? = null,
        statusCodeOverrides: Map<Int, String> = emptyMap(),
        // Keys that repeat (`workTypeId=1&workTypeId=2`) — a Map cannot carry them. Still values only, appended through
        // Ktor's parameter builder (encoded), never concatenated into [url], so the no-query-in-errors rule is unchanged.
        repeatedQuery: List<Pair<String, String>> = emptyList(),
        // The tenant whose rate-limit pause this call sets and obeys (`HttpJiraClient`: the gateway base, i.e. the cloud id).
        pauseKey: String? = null,
        // Told the byte size of the body that came back (not of a failed attempt), for a stream that charges a run ceiling for it.
        onWireBytes: ((Int) -> Unit)? = null,
    ): JsonElement = requestAs(
        method, url, query, jsonBody, authHeader, statusCodeOverrides, repeatedQuery, pauseKey, onWireBytes, transform = { it },
    )

    /**
     * [request] that hands the parsed tree to [transform] and returns only ITS result. For a body over [BIG_BODY_BYTES] the
     * transform runs while the big-parse permit is still held, so the tree is garbage before the next big body is decoded:
     * across every stream and slot, at most ONE big tree exists at a time, even when the caller keeps the small result (trimmed
     * item texts, paging fields) for a long time. A failure in [transform] propagates as it is (it is not retried).
     */
    @Suppress("UNCHECKED_CAST") // `Done.value` holds exactly what `transform` returned for this call; the cast restores its type.
    suspend fun <T> requestAs(
        method: HttpMethod,
        url: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: JsonElement? = null,
        authHeader: String? = null,
        statusCodeOverrides: Map<Int, String> = emptyMap(),
        repeatedQuery: List<Pair<String, String>> = emptyList(),
        pauseKey: String? = null,
        onWireBytes: ((Int) -> Unit)? = null,
        transform: (JsonElement) -> T,
    ): T {
        val tally = currentCoroutineContext()[CallTally]
        val call = PendingCall(
            method, url, query.toList() + repeatedQuery, jsonBody, authHeader, statusCodeOverrides, pauseKey, tally, onWireBytes, transform,
        )
        val permit = Permit(semaphore)
        try {
            acquireClear(permit, pauseKey, tally)
            val deadline = clock() + requestDeadlineMillis
            var attempt = 0
            while (true) {
                when (val outcome = attemptOnce(call)) {
                    is Done -> return outcome.value as T
                    is Retry -> backOff(call, outcome, attempt++, deadline, permit)
                }
            }
        } finally {
            permit.release()
        }
    }

    /**
     * Takes a permit at a moment [pauseKey]'s tenant is not paused: waits out a pause WITHOUT a permit, and hands the permit back
     * if a pause appeared while the call queued for it. [afterWait] runs after each pause wait and BEFORE the permit is asked for
     * (a retry's deadline check: the time spent queueing for a permit is not charged to the retry, as it is not for a first attempt).
     */
    private suspend fun acquireClear(permit: Permit, pauseKey: String?, tally: CallTally?, afterWait: () -> Unit = {}) {
        var seen = 0L
        while (true) {
            seen = awaitPause(pauseKey, seen, tally)
            afterWait()
            permit.acquire()
            if (pendingPause(pauseKey, seen) == null) return
            permit.release()
        }
    }

    /**
     * The instant [pauseKey]'s tenant is paused until, if that is still ahead and is not [seen] (the one a wait already served: a
     * test's no-op [sleeper] and frozen clock must not loop forever).
     */
    private fun pendingPause(pauseKey: String?, seen: Long): Long? {
        val target = pauseKey?.let { pauses[it]?.get() } ?: return null
        return target.takeIf { it > clock() && it != seen }
    }

    /** Suspends until [pauseKey]'s pause has passed (re-checking if it was extended); returns the last pause instant waited for. */
    private suspend fun awaitPause(pauseKey: String?, seen: Long, tally: CallTally?): Long {
        var waitedFor = seen
        while (true) {
            val target = pendingPause(pauseKey, waitedFor) ?: return waitedFor
            waitedFor = target
            val wait = target - clock()
            tally?.addTenantPause(wait.coerceAtLeast(0))
            sleeper(wait)
        }
    }

    /**
     * The decision after a failed attempt: record the tenant's pause, give up (retries exhausted, or the wait alone would cross the
     * deadline), or hand the permit back, wait out the backoff and any pause, and take a permit again for the next attempt.
     */
    private suspend fun backOff(call: PendingCall, retry: Retry, attempt: Int, deadline: Long, permit: Permit) {
        val delayMs = Backoff.delayMillis(attempt, retry.retryAfterSeconds, random)
        // The tenant asked for the wait whether or not THIS call will take it (retries exhausted, deadline): the others still honour it.
        if (retry.pausesOthers && call.pauseKey != null) {
            pauses.computeIfAbsent(call.pauseKey) { AtomicLong(0) }.accumulateAndGet(clock() + delayMs) { a, b -> maxOf(a, b) }
        }
        if (attempt >= maxRetries) throw retry.terminal()
        // A Retry-After that crosses the total per-request budget is reported as RATE_LIMITED specifically (the upstream told us how
        // long to wait; we're refusing that wait, not failing on our own account); any other backoff reports its own terminal code.
        val refusal = { if (retry.retryAfterSeconds != null) FetchException(source, "RATE_LIMITED", null, call.url) else retry.terminal() }
        if (clock() + delayMs > deadline) throw refusal()
        permit.release()
        call.tally?.addBackoff(delayMs)
        sleeper(delayMs)
        // A sibling's 429 may have extended the pause past this call's own backoff; the deadline is checked after the backoff and
        // the pause but before queueing for a permit (when a pause ends every waiter wakes at once, and the queue is not the call's fault).
        acquireClear(permit, call.pauseKey, call.tally) { if (clock() > deadline) throw refusal() }
    }

    /** One request/response round trip, classified; a retry is decided by [backOff] AFTER the response is released. */
    private suspend fun attemptOnce(call: PendingCall): Outcome {
        val statement = client.prepareRequest(call.url) {
            this.method = call.method
            call.query.forEach { (key, value) -> parameter(key, value) }
            call.authHeader?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, "application/json")
            // [LOW-4] Ported from Toadie's `UrlFetch.kt`: request the identity encoding so
            // maxResponseBytes counts the SAME bytes actually read off the wire — OkHttp otherwise
            // adds `Accept-Encoding: gzip` itself and transparently decompresses, which would let
            // [readBounded]'s cap be reached only after inflating a compressed body (still safely
            // bounded — decoded bytes are the STRICTER count — but "identity" keeps the two the
            // same rather than relying on that argument).
            header(HttpHeaders.AcceptEncoding, "identity")
            call.jsonBody?.let {
                contentType(ContentType.Application.Json)
                setBody(it.toString())
            }
        }
        return try {
            // [LOW-3] `prepareRequest {}.execute {}` guarantees the response (and its underlying
            // OkHttp connection) is released when the block returns OR throws — the recommended
            // Ktor idiom over the plain `client.request(url) {}` this replaced, which hands the raw
            // `HttpResponse` back to the caller with no guarantee its body/connection is ever
            // released if the caller (as `handleResponse`'s non-2xx/oversized/pre-retry branches
            // do) never reads it.
            statement.execute { response -> handleResponse(response, call) }
        } catch (cause: BlockedHostException) {
            throw FetchException(source, "BLOCKED_HOST", null, call.url).also { it.initCause(cause) }
        } catch (_: HttpRequestTimeoutException) {
            // [LOW-5] Deliberately no `initCause`: HttpRequestTimeoutException's own message
            // embeds the full request URL INCLUDING its query string (which may carry a token) —
            // `endpoint` (host+path only, no query — query params are never string-concatenated
            // into `url`) is the safe substitute (`.claude/docs/security.md` "never log secrets").
            throw FetchException(source, "TIMEOUT", null, call.url)
        } catch (cause: IOException) {
            Retry(null, pausesOthers = false) {
                FetchException(source, "UPSTREAM_UNAVAILABLE", null, call.url).also { it.initCause(cause) }
            }
        }
    }

    private suspend fun handleResponse(response: HttpResponse, call: PendingCall): Outcome {
        val status = response.status.value
        val url = call.url
        if (status == RATE_LIMITED_STATUS || status >= SERVER_ERROR_FLOOR) {
            if (status == RATE_LIMITED_STATUS) call.tally?.countRateLimited()
            val retryAfter = retryAfterSeconds(response)
            return Retry(retryAfter, pausesOthers = status == RATE_LIMITED_STATUS || retryAfter != null) {
                FetchException(source, if (status == RATE_LIMITED_STATUS) "RATE_LIMITED" else "UPSTREAM_UNAVAILABLE", status, url)
            }
        }
        // [CURSOR_EXPIRED] `statusCodeOverrides` lets a specific endpoint reinterpret a status this
        // generic transport would otherwise map to a less useful code — today only
        // `HttpJiraClient.searchJql` uses it, mapping 400/410 to CURSOR_EXPIRED when the request
        // carried a `nextPageToken` (`.claude/docs/jira-integration.md`).
        if (status !in SUCCESS_RANGE) throw FetchException(source, call.statusCodeOverrides[status] ?: codeForStatus(status), status, url)
        source.rateLimitSignal.pauseMillisAfter(response.headers)?.let { pause ->
            call.tally?.countSignalPause()
            sleeper(pause)
        }
        val bytes = readBounded(response)
        // Before the body is parsed: the byte cap bounds neither the tree a dense document parses to nor the stack a deep one
        // overflows (NodeCeiling.kt). The same failure an over-cap body is, so every stream answers it the way it answers that one.
        jsonCeilingBreach(bytes, maxResponseNodes)?.let { breach ->
            log.warn(
                "A {} response was refused ({}); ceilings: {} tokens, {} levels",
                source.label, breach, maxResponseNodes, MAX_RESPONSE_DEPTH,
            )
            throw FetchException(source, LIMIT_EXCEEDED_CODE, status, null, breach)
        }
        // One big tree is built at a time (the lock is held for the parse only, never the network read or the permit's wait):
        // a hostile host cannot make the worker build several dense trees at once. A small body takes no lock.
        // A caller's transform runs inside the same lock, so the tree it was built from is unreachable once the lock is released.
        val value = if (bytes.size > BIG_BODY_BYTES) {
            bigParses.withLock { call.transform(parse(bytes, status, url)) }
        } else {
            call.transform(parse(bytes, status, url))
        }
        call.onWireBytes?.invoke(bytes.size)
        return Done(value)
    }

    // Parsed straight from the bytes: no decoded String (8-16 MiB of UTF-16 for a big body) is ever held beside the tree. A malformed or
    // truncated body, or text after the document, throws a SerializationException (an IOException would be caught the same way).
    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(bytes: ByteArray, status: Int, url: String): JsonElement = try {
        Json.decodeFromStream<JsonElement>(ByteArrayInputStream(bytes))
    } catch (cause: Exception) {
        throw FetchException(source, "INVALID_RESPONSE", status, url).also { it.initCause(cause) }
    }

    private suspend fun readBounded(response: HttpResponse): ByteArray {
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            total += count
            if (total > maxResponseBytes) throw FetchException(source, "LIMIT_EXCEEDED", response.status.value, null)
            output.write(buffer, 0, count)
        }
        // A one-shot answer (GET /field) cannot be halved: one past half the cap is the operator's early warning to raise it.
        if (total > maxResponseBytes / 2) {
            log.warn(
                "A {} response passed half of {}: {} of {} bytes", source.label, source.maxResponseBytesSetting, total, maxResponseBytes,
            )
        }
        return output.toByteArray()
    }

    private fun retryAfterSeconds(response: HttpResponse): Long? = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()

    companion object {
        private const val RATE_LIMITED_STATUS = 429
        private const val SERVER_ERROR_FLOOR = 500
        private val SUCCESS_RANGE = 200..299
        private const val READ_BUFFER_SIZE = 8_192
        private const val BIG_BODY_BYTES = 1024 * 1024

        private fun codeForStatus(status: Int): String = when (status) {
            401 -> "AUTHENTICATION_FAILED"
            403 -> "FORBIDDEN_SCOPE"
            404 -> "NOT_FOUND"
            in 300..399 -> "REDIRECT"
            else -> "INVALID_RESPONSE"
        }
    }
}
