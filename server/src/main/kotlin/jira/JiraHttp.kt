package ch.nokillswit.jira

import ch.nokillswit.fetch.DEFAULT_REQUEST_DEADLINE_MILLIS
import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.fetch.RateLimitSignal
import ch.nokillswit.fetch.SourceHttp
import ch.nokillswit.fetch.defaultMaxResponseNodes
import ch.nokillswit.ingest.JiraAuthScheme
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import java.util.Base64
import kotlinx.coroutines.delay

// Jira's flavour of the shared transport (`fetch/SourceHttp.kt`): its name, its response-cap setting, its near-limit pause and the
// `Authorization` header. The retry/backoff/permit/deadline/ceiling machinery is the shared one (`.claude/docs/jira-integration.md`).

/** `X-RateLimit-NearLimit: true` on a success: Atlassian says the tenant is about to be limited, so the call pauses a second. */
internal object JiraNearLimitSignal : RateLimitSignal {
    private const val NEAR_LIMIT_HEADER = "X-RateLimit-NearLimit"
    private const val NEAR_LIMIT_PAUSE_MS = 1_000L

    override fun pauseMillisAfter(headers: Headers): Long? = NEAR_LIMIT_PAUSE_MS.takeIf { headers[NEAR_LIMIT_HEADER] == "true" }
}

/** The Jira upstream as the fetch machinery names it: messages start "Jira fetch failed", the cap is `jira.maxResponseBytes`. */
internal val JIRA_SOURCE = FetchSource("Jira", "jira.maxResponseBytes", JiraNearLimitSignal)

/** The shared transport for Jira. Every parameter is [SourceHttp]'s; `configureJira` and the tests build it here. */
internal fun createJiraHttp(
    client: HttpClient,
    maxRetries: Int,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    requestDeadlineMillis: Long = DEFAULT_REQUEST_DEADLINE_MILLIS,
    random: () -> Double = Math::random,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    clock: () -> Long = System::currentTimeMillis,
    maxResponseNodes: Long = defaultMaxResponseNodes(maxResponseBytes),
    permits: OutboundPermits? = null,
): SourceHttp = SourceHttp(
    client, maxRetries, maxResponseBytes, maxConcurrentRequests, JIRA_SOURCE, requestDeadlineMillis, random, sleeper, clock,
    maxResponseNodes, permits,
)

/** `Authorization` header value for [scheme] - never logged (see `.claude/docs/security.md`). */
fun jiraAuthHeader(email: String, apiToken: String, scheme: JiraAuthScheme): String = when (scheme) {
    JiraAuthScheme.BASIC -> "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray(Charsets.UTF_8))
    JiraAuthScheme.BEARER -> "Bearer $apiToken"
}
