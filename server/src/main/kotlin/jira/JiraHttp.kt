package ch.nokillswit.jira

import ch.nokillswit.infra.outbound.BlockedHostException
import ch.nokillswit.ingest.JiraAuthScheme
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraHttp::class.java)

/**
 * A failed Jira call, classified into one of the plan's §6 codes. [status]/[endpoint] are for
 * logging/diagnostics only — never render [endpoint] with a query string (it may embed a token). [detail] is a short,
 * secret-free operator hint appended to the message (the oversized-skip threshold's "skipped N of M" line).
 */
class JiraFetchException(val code: String, val status: Int? = null, val endpoint: String? = null, detail: String? = null) :
    RuntimeException(
        buildString {
            append("Jira fetch failed: ").append(code)
            status?.let { append(" (").append(it).append(')') }
            endpoint?.let { append(" @ ").append(it) }
            detail?.let { append(": ").append(it) }
        },
    )

/**
 * Pure backoff math (v0.2.0 plan §6): `min(30s, 2s·2^n) × U(0.7,1.3)`, honouring a `Retry-After`
 * header (clamped to 120s) when the upstream sends one. [random] is injectable (`JiraBackoffTest`)
 * so jitter bounds can be pinned exactly instead of asserted with float tolerance games.
 */
object JiraBackoff {
    private const val BASE_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L
    private const val MAX_RETRY_AFTER_SECONDS = 120L
    private const val JITTER_FLOOR = 0.7
    private const val JITTER_SPAN = 0.6

    fun delayMillis(attempt: Int, retryAfterSeconds: Long?, random: () -> Double = Math::random): Long {
        if (retryAfterSeconds != null) return retryAfterSeconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1_000L
        val exponential = minOf(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl attempt.coerceIn(0, 20)))
        val jitter = JITTER_FLOOR + random() * JITTER_SPAN
        return (exponential * jitter).toLong()
    }
}

/** `Authorization` header value for [scheme] — never logged (see `.claude/docs/security.md`). */
fun jiraAuthHeader(email: String, apiToken: String, scheme: JiraAuthScheme): String = when (scheme) {
    JiraAuthScheme.BASIC -> "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray(Charsets.UTF_8))
    JiraAuthScheme.BEARER -> "Bearer $apiToken"
}

private const val DEFAULT_REQUEST_DEADLINE_MILLIS = 180_000L

/** What one request needs on every attempt; [pauseKey] names the tenant whose shared pause it obeys (null = none). */
private class PendingCall(
    val method: HttpMethod,
    val url: String,
    val query: List<Pair<String, String>>,
    val jsonBody: JsonElement?,
    val authHeader: String?,
    val statusCodeOverrides: Map<Int, String>,
    val pauseKey: String?,
    /** The sync job's [JiraCallTally] when the call runs inside one, else null. */
    val tally: JiraCallTally?,
)

/** One attempt's verdict: the parsed body, or "try again" with what the backoff decision needs. */
private sealed interface Outcome

private class Done(val json: JsonElement) : Outcome

private class Retry(
    val retryAfterSeconds: Long?,
    /** True for a 429, or a 5xx carrying `Retry-After`: the TENANT is rate limiting, so its other calls pause too. */
    val pausesOthers: Boolean,
    val terminal: () -> JiraFetchException,
) : Outcome

/** One call's hold on a semaphore permit, so it can be handed back while the call only waits (backoff, a tenant pause). */
private class Permit(private val semaphore: Semaphore) {
    private var held = false

    suspend fun acquire() {
        semaphore.acquire()
        held = true
    }

    fun release() {
        if (held) {
            held = false
            semaphore.release()
        }
    }
}

/**
 * The shared, low-level Jira HTTP transport (v0.2.0 plan §6): retry with [JiraBackoff] on 429/5xx/
 * IOException up to [maxRetries], a [Semaphore] capping concurrent in-flight calls, a bounded
 * response read at [maxResponseBytes] (Covenant's `ToadieGraphqlClient` shape), and an
 * `X-RateLimit-NearLimit` pause. [client] is expected to already carry `HttpTimeout` and the
 * guarded OkHttp engine (`jira/Jira.kt`) — tests substitute a Ktor `MockEngine` client instead.
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
 * **Permits are held only while a request runs.** The semaphore is process-wide (one [JiraHttp] serves every connection), so a call
 * hands its permit back while it merely waits - during its own backoff and while its tenant is paused - and re-acquires it after
 * ([Permit], [acquireClear]); a rate-limited tenant therefore cannot starve the others of permits. The one wait that still holds a
 * permit is the `X-RateLimit-NearLimit` 1s pause.
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
 * **Tally.** A call that runs inside a [JiraCallTally] (a coroutine-context element, read once per call) counts its 429s, the back-off it
 * sleeps, the tenant pause it waits out and its near-limit pauses into the sync job's progress; outside one it counts nothing.
 */
class JiraHttp(
    private val client: HttpClient,
    private val maxRetries: Int,
    private val maxResponseBytes: Long,
    /** The one cap on in-flight calls: [semaphore]'s permits, and what a stream sizes its fetch-ahead by. */
    val maxConcurrentRequests: Int,
    private val requestDeadlineMillis: Long = DEFAULT_REQUEST_DEADLINE_MILLIS,
    private val random: () -> Double = Math::random,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val semaphore = Semaphore(maxConcurrentRequests)

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
    ): JsonElement {
        val tally = currentCoroutineContext()[JiraCallTally]
        val call = PendingCall(method, url, query.toList() + repeatedQuery, jsonBody, authHeader, statusCodeOverrides, pauseKey, tally)
        val permit = Permit(semaphore)
        try {
            acquireClear(permit, pauseKey, tally)
            val deadline = clock() + requestDeadlineMillis
            var attempt = 0
            while (true) {
                when (val outcome = attemptOnce(call)) {
                    is Done -> return outcome.json
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
    private suspend fun acquireClear(permit: Permit, pauseKey: String?, tally: JiraCallTally?, afterWait: () -> Unit = {}) {
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
    private suspend fun awaitPause(pauseKey: String?, seen: Long, tally: JiraCallTally?): Long {
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
        val delayMs = JiraBackoff.delayMillis(attempt, retry.retryAfterSeconds, random)
        // The tenant asked for the wait whether or not THIS call will take it (retries exhausted, deadline): the others still honour it.
        if (retry.pausesOthers && call.pauseKey != null) {
            pauses.computeIfAbsent(call.pauseKey) { AtomicLong(0) }.accumulateAndGet(clock() + delayMs) { a, b -> maxOf(a, b) }
        }
        if (attempt >= maxRetries) throw retry.terminal()
        // A Retry-After that crosses the total per-request budget is reported as RATE_LIMITED specifically (the upstream told us how
        // long to wait; we're refusing that wait, not failing on our own account); any other backoff reports its own terminal code.
        val refusal = { if (retry.retryAfterSeconds != null) JiraFetchException("RATE_LIMITED", null, call.url) else retry.terminal() }
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
            throw JiraFetchException("BLOCKED_HOST", null, call.url).also { it.initCause(cause) }
        } catch (_: HttpRequestTimeoutException) {
            // [LOW-5] Deliberately no `initCause`: HttpRequestTimeoutException's own message
            // embeds the full request URL INCLUDING its query string (which may carry a token) —
            // `endpoint` (host+path only, no query — query params are never string-concatenated
            // into `url`) is the safe substitute (`.claude/docs/security.md` "never log secrets").
            throw JiraFetchException("TIMEOUT", null, call.url)
        } catch (cause: IOException) {
            Retry(null, pausesOthers = false) {
                JiraFetchException("UPSTREAM_UNAVAILABLE", null, call.url).also { it.initCause(cause) }
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
                JiraFetchException(if (status == RATE_LIMITED_STATUS) "RATE_LIMITED" else "UPSTREAM_UNAVAILABLE", status, url)
            }
        }
        // [CURSOR_EXPIRED] `statusCodeOverrides` lets a specific endpoint reinterpret a status this
        // generic transport would otherwise map to a less useful code — today only
        // `HttpJiraClient.searchJql` uses it, mapping 400/410 to CURSOR_EXPIRED when the request
        // carried a `nextPageToken` (`.claude/docs/jira-integration.md`).
        if (status !in SUCCESS_RANGE) throw JiraFetchException(call.statusCodeOverrides[status] ?: codeForStatus(status), status, url)
        if (response.headers[NEAR_LIMIT_HEADER] == "true") {
            call.tally?.countNearLimitPause()
            sleeper(NEAR_LIMIT_PAUSE_MS)
        }
        val bytes = readBounded(response)
        return try {
            Done(Json.parseToJsonElement(bytes.decodeToString()))
        } catch (cause: Exception) {
            throw JiraFetchException("INVALID_RESPONSE", status, url).also { it.initCause(cause) }
        }
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
            if (total > maxResponseBytes) throw JiraFetchException("LIMIT_EXCEEDED", response.status.value, null)
            output.write(buffer, 0, count)
        }
        // A one-shot answer (GET /field) cannot be halved: one past half the cap is the operator's early warning to raise it.
        if (total > maxResponseBytes / 2) {
            log.warn("A Jira response passed half of jira.maxResponseBytes: {} of {} bytes", total, maxResponseBytes)
        }
        return output.toByteArray()
    }

    private fun retryAfterSeconds(response: HttpResponse): Long? = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()

    companion object {
        private const val RATE_LIMITED_STATUS = 429
        private const val SERVER_ERROR_FLOOR = 500
        private val SUCCESS_RANGE = 200..299
        private const val NEAR_LIMIT_HEADER = "X-RateLimit-NearLimit"
        private const val NEAR_LIMIT_PAUSE_MS = 1_000L
        private const val READ_BUFFER_SIZE = 8_192

        private fun codeForStatus(status: Int): String = when (status) {
            401 -> "AUTHENTICATION_FAILED"
            403 -> "FORBIDDEN_SCOPE"
            404 -> "NOT_FOUND"
            in 300..399 -> "REDIRECT"
            else -> "INVALID_RESPONSE"
        }
    }
}
