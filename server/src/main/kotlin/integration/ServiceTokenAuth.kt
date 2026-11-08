package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.plugins.RateLimitTokenKey
import ch.nokillswit.plugins.RateLimitBucketKey
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.rateLimitHost
import ch.nokillswit.plugins.respondInternalError
import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/** The service-token provider's name: `authenticate(SERVICE_TOKEN) { … }` is the ONLY way into the `/integration` reads. */
const val SERVICE_TOKEN = "service-token"

/**
 * The principal of a call that presented a LIVE service token: a machine identity, deliberately NOT a user (no `userId`, roles, feature
 * flags or MFA). `call.caller()` reads only a JWT principal, so every guard, `isActiveManager` and per-user bucket is unreachable from it.
 */
data class ServiceTokenPrincipal(val tokenId: UInt, val name: String)

/** The facts of the token that authenticated this call, as they were BEFORE this call's `last_used_at` stamp (`GET /integration/me`). */
internal val ServiceTokenFactsKey = AttributeKey<ServiceTokenVerdict.Live>("ServiceTokenFacts")

/** The one 401 text for every failure reason (the reason is in the `service_token.auth_failed` audit line, never on the wire). */
internal const val SERVICE_TOKEN_UNAUTHORIZED_DETAIL = "Missing or invalid service token"

private const val BEARER_PREFIX = "Bearer "

/**
 * The raw bearer value of [call], or null for anything that is not exactly one `Authorization: Bearer flow_int_<43 base64url>` header:
 * a missing, repeated, differently-schemed (`Basic`, a bare token) or malformed value is rejected here, BEFORE any hashing or database
 * work (the JWT provider's "structural rejections never reach the database" rule).
 */
internal fun serviceTokenOf(call: ApplicationCall): String? {
    val values = call.request.headers.getAll(HttpHeaders.Authorization)
    if (values == null || values.size != 1) return null
    val value = values.single()
    if (!value.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    return value.substring(BEARER_PREFIX.length).takeIf { SERVICE_TOKEN_PATTERN.matches(it) }
}

/**
 * The service-token authentication provider (`.claude/docs/security.md` "Service tokens"): shape check, then ONE uncached registry
 * lookup ([ServiceTokenService.authenticate]: digest equality, not revoked, not expired, `last_used_at` stamped), so revocation and
 * expiry take effect on the very next request. A lookup that THROWS is an outage and answers the catch-all's 500, never a 401 (the
 * JWT provider's rule: a transient database fault must not read as a revoked credential). Every other failure is the same 401 problem.
 * The per-host budget ([HostWindowLimiter]) is spent before anything else, so a flood costs no lookup past it. The service and the
 * audit throttles are read from the application attributes at request time (not captured when the provider is built, so a test can swap
 * the service, a throttle or the limiter). Nothing here ever logs the header, the token or its digest.
 */
class ServiceTokenAuthenticationProvider(config: Config) : AuthenticationProvider(config) {

    class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        // The per-host budget comes FIRST, for every attempt (valid or not), strictly before any lookup: over it (or with the tracking map
        // saturated) the call is a 429 and the registry is never touched. Ktor's own rate limiter cannot do this (it runs after
        // this phase).
        val budget = call.application.attributes[IntegrationHostLimiterKey].tryAcquire(call.rateLimitHost())
        if (budget is HostWindowLimiter.Verdict.Refused) return context.refuseRateLimited(budget)
        val raw = serviceTokenOf(call)
        if (raw == null) {
            call.authFailed("missing_or_malformed", tokenId = null)
            return context.reject(AuthenticationFailedCause.NoCredentials, outage = null)
        }
        val service = call.application.attributes[ServiceTokenServiceKey]
        var outage: Exception? = null
        // A cancelled call (client gone) mid-lookup unwinds: catchingFailures never swallows cancellation.
        val verdict = catchingFailures({ service.authenticate(raw, System.currentTimeMillis()) }) { cause ->
            outage = cause
            null
        }
        when (verdict) {
            is ServiceTokenVerdict.Live -> {
                call.attributes.put(ServiceTokenFactsKey, verdict)
                call.attributes.put(RateLimitTokenKey, verdict.tokenId)
                context.principal(name, ServiceTokenPrincipal(verdict.tokenId, verdict.name))
                call.used(verdict)
            }
            is ServiceTokenVerdict.Rejected -> {
                val reason = if (verdict.reason == ServiceTokenRejection.EXPIRED) "expired" else "unknown_or_revoked"
                call.authFailed(reason, verdict.tokenId)
                context.reject(AuthenticationFailedCause.InvalidCredentials, outage = null)
            }
            null -> context.reject(AuthenticationFailedCause.Error("service token lookup failed"), outage)
        }
    }
}

/** The challenge: [outage] (a THROWN lookup) is the catch-all 500, everything else the one uniform 401 `application/problem+json`. */
private fun AuthenticationContext.reject(cause: AuthenticationFailedCause, outage: Exception?) {
    challenge(SERVICE_TOKEN, cause) { challenge, call ->
        if (outage != null) {
            call.respondInternalError(outage)
        } else {
            call.respondProblem(HttpStatusCode.Unauthorized, SERVICE_TOKEN_UNAUTHORIZED_DETAIL)
        }
        challenge.complete()
    }
}

/**
 * The pre-lookup per-host 429. It is answered through the SAME path as every `RateLimit` rejection: a bodiless 429 that
 * `plugins/ErrorHandling.kt`'s status handler turns into the generic problem body and the coalesced `rate_limit.exceeded` audit,
 * which names the bucket this stamps (`integration`, or `integration-saturated` when the limiter's map is full and a NEW host was refused)
 * and, no token being verified yet, no `byTokenId`.
 */
private fun AuthenticationContext.refuseRateLimited(refusal: HostWindowLimiter.Verdict.Refused) {
    challenge(SERVICE_TOKEN, AuthenticationFailedCause.Error("integration rate limit")) { challenge, call ->
        call.attributes.put(RateLimitBucketKey, if (refusal.saturated) RateLimits.INTEGRATION_SATURATED else RateLimits.INTEGRATION)
        val seconds = (refusal.retryAfterMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        call.response.header(HttpHeaders.RetryAfter, seconds.coerceAtLeast(1).toString())
        call.respond(HttpStatusCode.TooManyRequests)
        challenge.complete()
    }
}

private const val MILLIS_PER_SECOND = 1000L

/**
 * `service_token.auth_failed`, coalesced per reason (and token id, when a row matched) per minute with a `suppressed` count, so a replayed
 * leaked token writes a line a minute, not a line per request. The throttle map is not capacity-bounded, so the key space is bounded by
 * construction instead: the id-less reasons (no row matched) plus (reason, token id) pairs, and a token id exists only when a registry row
 * matched the digest (the shape check and the per-host budget run first), so it is at most the registry's size times the reasons.
 */
private fun ApplicationCall.authFailed(reason: String, tokenId: UInt?) {
    val key = if (tokenId == null) reason else "$reason:$tokenId"
    application.attributes[ServiceTokenAuthFailedAuditKey].admit(key)?.let { suppressed ->
        val fields = listOfNotNull("reason" to reason, tokenId?.let { "tokenId" to it.toLong() }, "suppressed" to suppressed)
        audit("service_token.auth_failed", *fields.toTypedArray())
    }
}

/**
 * `service_token.used`, coalesced per token per minute: which token read what, when. The path is the request path only (the
 * `/integration` routes have no path parameters), never the query string, the header or the token.
 */
private fun ApplicationCall.used(token: ServiceTokenVerdict.Live) {
    application.attributes[ServiceTokenUsedAuditKey].admit(token.tokenId.toString())?.let { suppressed ->
        audit(
            "service_token.used",
            "tokenId" to token.tokenId.toLong(),
            "name" to token.name,
            "method" to request.httpMethod.value,
            "path" to request.path(),
            "suppressed" to suppressed,
        )
    }
}
