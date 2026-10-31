package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.fetch.RateLimitSignal
import ch.nokillswit.fetch.SourceHttp
import ch.nokillswit.fetch.defaultMaxResponseNodes
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import kotlinx.coroutines.delay

// GitLab's flavour of the shared transport (`fetch/SourceHttp.kt`): its name, its response-cap setting, its rate-limit pause and the
// `PRIVATE-TOKEN` header. The retry/backoff/permit/deadline/ceiling machinery is the shared one (`jira-integration.md` "The shared
// transport").

/** The request header a personal access token travels in (never `Authorization`: the raw token is the value). */
internal const val GITLAB_AUTH_HEADER = "PRIVATE-TOKEN"

private const val NEAR_LIMIT_REMAINING = 5L
private const val MIN_PAUSE_MS = 1_000L
private const val MAX_PAUSE_MS = 5_000L
private const val DEFAULT_PAUSE_MS = 2_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * GitLab's rate-limit headers on a SUCCESS: at most [NEAR_LIMIT_REMAINING] calls left in the window (`RateLimit-Remaining`) pauses the
 * call until the window resets (`RateLimit-Reset`, epoch seconds), clamped to 1..5 s, or 2 s when the reset is absent or unreadable;
 * more left means no pause. The transport sleeps this while HOLDING a permit of the pool GitLab shares with Jira
 * (`fetch/OutboundPermits.kt`), so the 5 s clamp is a hard bound on what a slow window can cost the other connector.
 */
class GitLabRateLimitSignal(private val clock: () -> Long = System::currentTimeMillis) : RateLimitSignal {
    override fun pauseMillisAfter(headers: Headers): Long? {
        val remaining = headers["RateLimit-Remaining"]?.trim()?.toLongOrNull() ?: return null
        if (remaining > NEAR_LIMIT_REMAINING) return null
        val resetAtMillis = headers["RateLimit-Reset"]?.trim()?.toLongOrNull()?.times(MILLIS_PER_SECOND)
            ?: return DEFAULT_PAUSE_MS
        return (resetAtMillis - clock()).coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
    }
}

/** The GitLab upstream as the fetch machinery names it: messages start "GitLab fetch failed", the cap is `gitlab.maxResponseBytes`. */
internal val GITLAB_SOURCE = FetchSource("GitLab", "gitlab.maxResponseBytes", GitLabRateLimitSignal(), GITLAB_AUTH_HEADER)

/**
 * The shared transport for GitLab, drawing on the worker's ONE [permits] pool (published by `configureJira`) so the heap fit that
 * counts Jira's in-flight bodies already covers these. Every other parameter is [SourceHttp]'s; `configureGitLab` and the tests build it
 * here.
 */
internal fun createGitLabHttp(
    client: HttpClient,
    settings: GitLabSettings,
    permits: OutboundPermits,
    source: FetchSource = GITLAB_SOURCE,
    random: () -> Double = Math::random,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    clock: () -> Long = System::currentTimeMillis,
): SourceHttp = SourceHttp(
    client, settings.maxRetries, settings.maxResponseBytes, permits.count, source, settings.requestDeadlineMillis, random, sleeper, clock,
    defaultMaxResponseNodes(settings.maxResponseBytes), permits,
)

/** The `PRIVATE-TOKEN` header value: the raw personal access token. Never logged (`security.md`). */
internal fun gitLabAuthHeader(token: String): String = token
