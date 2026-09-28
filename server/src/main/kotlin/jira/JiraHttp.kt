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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A failed Jira call, classified into one of the plan's §6 codes. [status]/[endpoint] are for
 * logging/diagnostics only — never render [endpoint] with a query string (it may embed a token).
 */
class JiraFetchException(val code: String, val status: Int? = null, val endpoint: String? = null) :
    RuntimeException(
        buildString {
            append("Jira fetch failed: ").append(code)
            status?.let { append(" (").append(it).append(')') }
            endpoint?.let { append(" @ ").append(it) }
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
 * otherwise hold the [Semaphore] permit above for as long as [maxRetries] deep exponential backoff
 * sums to (~10 minutes at the ceiling). A `Retry-After` whose wait alone would cross the remaining
 * deadline fails `RATE_LIMITED` immediately rather than sleeping past the budget; any other retry
 * that would cross it fails with its own terminal code immediately the same way.
 */
class JiraHttp(
    private val client: HttpClient,
    private val maxRetries: Int,
    private val maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    private val requestDeadlineMillis: Long = DEFAULT_REQUEST_DEADLINE_MILLIS,
    private val random: () -> Double = Math::random,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val semaphore = Semaphore(maxConcurrentRequests)

    suspend fun request(
        method: HttpMethod,
        url: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: JsonElement? = null,
        authHeader: String? = null,
        statusCodeOverrides: Map<Int, String> = emptyMap(),
    ): JsonElement = semaphore.withPermit {
        requestWithRetries(
            method, url, query, jsonBody, authHeader,
            attempt = 0, deadline = clock() + requestDeadlineMillis, statusCodeOverrides = statusCodeOverrides,
        )
    }

    private suspend fun requestWithRetries(
        method: HttpMethod,
        url: String,
        query: Map<String, String>,
        jsonBody: JsonElement?,
        authHeader: String?,
        attempt: Int,
        deadline: Long,
        statusCodeOverrides: Map<Int, String> = emptyMap(),
    ): JsonElement {
        val statement = client.prepareRequest(url) {
            this.method = method
            query.forEach { (key, value) -> parameter(key, value) }
            authHeader?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, "application/json")
            // [LOW-4] Ported from Toadie's `UrlFetch.kt`: request the identity encoding so
            // maxResponseBytes counts the SAME bytes actually read off the wire — OkHttp otherwise
            // adds `Accept-Encoding: gzip` itself and transparently decompresses, which would let
            // [readBounded]'s cap be reached only after inflating a compressed body (still safely
            // bounded — decoded bytes are the STRICTER count — but "identity" keeps the two the
            // same rather than relying on that argument).
            header(HttpHeaders.AcceptEncoding, "identity")
            jsonBody?.let {
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
            statement.execute { response ->
                handleResponse(response, method, url, query, jsonBody, authHeader, attempt, deadline, statusCodeOverrides)
            }
        } catch (cause: BlockedHostException) {
            throw JiraFetchException("BLOCKED_HOST", null, url).also { it.initCause(cause) }
        } catch (_: HttpRequestTimeoutException) {
            // [LOW-5] Deliberately no `initCause`: HttpRequestTimeoutException's own message
            // embeds the full request URL INCLUDING its query string (which may carry a token) —
            // `endpoint` (host+path only, no query — query params are never string-concatenated
            // into `url`) is the safe substitute (`.claude/docs/security.md` "never log secrets").
            throw JiraFetchException("TIMEOUT", null, url)
        } catch (cause: IOException) {
            retryOrThrow(attempt, null, method, url, query, jsonBody, authHeader, deadline, statusCodeOverrides) {
                JiraFetchException("UPSTREAM_UNAVAILABLE", null, url).also { it.initCause(cause) }
            }
        }
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        method: HttpMethod,
        url: String,
        query: Map<String, String>,
        jsonBody: JsonElement?,
        authHeader: String?,
        attempt: Int,
        deadline: Long,
        statusCodeOverrides: Map<Int, String>,
    ): JsonElement {
        val status = response.status.value
        if (status == RATE_LIMITED_STATUS || status >= SERVER_ERROR_FLOOR) {
            return retryOrThrow(
                attempt, retryAfterSeconds(response), method, url, query, jsonBody, authHeader, deadline, statusCodeOverrides,
            ) {
                JiraFetchException(if (status == RATE_LIMITED_STATUS) "RATE_LIMITED" else "UPSTREAM_UNAVAILABLE", status, url)
            }
        }
        // [CURSOR_EXPIRED] `statusCodeOverrides` lets a specific endpoint reinterpret a status this
        // generic transport would otherwise map to a less useful code — today only
        // `HttpJiraClient.searchJql` uses it, mapping 400/410 to CURSOR_EXPIRED when the request
        // carried a `nextPageToken` (`.claude/docs/jira-integration.md`).
        if (status !in SUCCESS_RANGE) throw JiraFetchException(statusCodeOverrides[status] ?: codeForStatus(status), status, url)
        if (response.headers[NEAR_LIMIT_HEADER] == "true") sleeper(NEAR_LIMIT_PAUSE_MS)
        val bytes = readBounded(response)
        return try {
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (cause: Exception) {
            throw JiraFetchException("INVALID_RESPONSE", status, url).also { it.initCause(cause) }
        }
    }

    private suspend fun retryOrThrow(
        attempt: Int,
        retryAfterSeconds: Long?,
        method: HttpMethod,
        url: String,
        query: Map<String, String>,
        jsonBody: JsonElement?,
        authHeader: String?,
        deadline: Long,
        statusCodeOverrides: Map<Int, String>,
        terminal: () -> JiraFetchException,
    ): JsonElement {
        if (attempt >= maxRetries) throw terminal()
        val delayMs = JiraBackoff.delayMillis(attempt, retryAfterSeconds, random)
        if (clock() + delayMs > deadline) {
            // The wait alone would cross the total per-request budget — a Retry-After that does
            // this is reported as RATE_LIMITED specifically (the upstream told us how long to
            // wait; we're refusing that wait, not failing on our own account); any other backoff
            // in the same situation reports its own terminal code the same way.
            throw if (retryAfterSeconds != null) JiraFetchException("RATE_LIMITED", null, url) else terminal()
        }
        sleeper(delayMs)
        return requestWithRetries(method, url, query, jsonBody, authHeader, attempt + 1, deadline, statusCodeOverrides)
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
