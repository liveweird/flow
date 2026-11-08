package ch.nokillswit.plugins

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The client host every per-IP bucket keys on: Ktor's resolved origin, so behind a trusted proxy (`http.behindProxy`,
 * `http.proxyHops`) it is the `X-Forwarded-For` value the proxy wrote and never a client-chosen one. ONE definition for the
 * `RateLimit` buckets below and for the service-token provider's pre-lookup budget (`integration/`), so the two can never
 * disagree about who the client is.
 */
internal fun ApplicationCall.rateLimitHost(): String = request.origin.remoteHost

/** The name of the bucket a call last rode, stamped by its `requestKey`; read by the 429 audit in ErrorHandling.kt. */
internal val RateLimitBucketKey = AttributeKey<String>("RateLimitBucket")

/**
 * The authenticated user id a PER-USER bucket (reports, telemetry) keyed the call by, stamped by its `requestKey`;
 * absent on the per-IP buckets. The 429 audit carries it as `byUserId` and throttles per user: it is the server-minted JWT claim
 * of a verified token, never client-chosen text.
 */
internal val RateLimitUserKey = AttributeKey<UInt>("RateLimitUser")

/**
 * The service-token id the PER-TOKEN bucket ([RateLimits.INTEGRATION_TOKEN]) keys the call by. Stamped by the service-token
 * authentication provider (`integration/ServiceTokenAuth.kt`) ONLY for a token the registry just verified as live, so `plugins`
 * never imports `integration` and the key space is bounded by the registry. The 429 audit carries it as `byTokenId`.
 */
internal val RateLimitTokenKey = AttributeKey<UInt>("RateLimitToken")

/**
 * Coalesces the `rate_limit.exceeded` audit event (checkup 2C15): an unauthenticated flood must not write one audit line
 * per rejected request. At most ONE event per key per [intervalMs]; [admit] returns the number of rejections folded
 * into the previous window (the event's `suppressed` field) when the caller should emit now, or null when this rejection
 * is folded into the current window. The key is the bucket name for a per-IP bucket (a handful of fixed names, never the
 * client), `bucket:userId` for a per-user bucket (only a verified, signed-in account can reach it, so the key space is the
 * number of accounts) and `bucket:token:tokenId` for the per-token bucket (the registry's size), so memory is bounded no matter who
 * floods; thread-safe via [ConcurrentHashMap.compute]'s per-key atomicity.
 */
internal class RateLimitAuditThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var lastEmitMs: Long, var suppressed: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    fun admit(bucket: String): Long? {
        var emit: Long? = null
        windows.compute(bucket) { _, window ->
            val now = clock()
            when {
                window == null -> Window(now, 0).also { emit = 0 }
                now - window.lastEmitMs >= intervalMs -> window.also {
                    emit = it.suppressed
                    it.lastEmitMs = now
                    it.suppressed = 0
                }
                else -> window.also { it.suppressed++ }
            }
        }
        return emit
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}

/**
 * Every token bucket the routes ride (per client IP, except [REPORTS] and [TELEMETRY]: per signed-in user, and [INTEGRATION_TOKEN]:
 * per verified service token), named in one place so a
 * feature module never has to know another's constant (the auth module used to import the try-it bucket's name — a layering
 * inversion the checkup removed). Route files reference these names; only this plugin installs them.
 */
object RateLimits {
    const val LOGIN = "login"
    const val REFRESH = "refresh"
    const val PASSWORD_RESET = "password-reset"
    const val MFA = "mfa"
    /** `POST /api/v1/password-reset/confirm` - the link's token exchange, 10/min per IP in every mode (the [MFA] idiom). */
    const val PASSWORD_RESET_CONFIRM = "password-reset-confirm"
    /** `POST /api/v1/data-sources/test` and `.../{id}/test` (v0.2.0 plan §9) — the web role's one outbound call. */
    const val DATA_SOURCE_TEST = "data-source-test"
    /** `GET /api/v1/ready` — the one anonymous route that takes a pooled DB connection per call (checkup D7-2, A16). */
    const val READY = "ready"
    /**
     * Every `GET /api/v1/reports/…` operation (the reports, Home and every `…/items` drill) shares ONE bucket per SIGNED-IN USER
     * (the JWT's `userId`, never the client address: a NAT'd office is not one bucket and changing IP buys nothing). The statement
     * timeout bounds one query, not a stream of them; this bounds the stream.
     */
    const val REPORTS = "reports"

    /**
     * Both `GET /api/v1/telemetry/…` reads share ONE bucket per SIGNED-IN USER (the [REPORTS] idiom): 30/min in every mode. The panel
     * polls `current` once a minute and fetches a history per expanded section, a handful of calls a minute; the bound is for a
     * runaway client, since each call is a few `ops` aggregations plus job-table reads.
     */
    const val TELEMETRY = "telemetry"

    /**
     * The whole `/api/v1/integration` namespace, per client IP ([rateLimitHost]): its calls cost a pooled digest lookup before anything is
     * verified, so one host gets a bounded number of them. 60/min in every mode, pinnable via `security.rateLimit.integrationPerMinute`.
     * Only the NAME and the limit live here: Ktor's `RateLimit` runs after authentication and never sees a refused call, so this
     * bucket is NOT registered with the plugin - the service-token provider spends it BEFORE the lookup
     * (`integration/HostWindowLimiter.kt`) and answers through the same 429 path, stamping this name for the audit.
     */
    const val INTEGRATION = "integration"

    /**
     * The audit bucket name of a 429 from the [INTEGRATION] limiter's FULL tracking map (a NEW host refused, not a host over its
     * budget), so an operator can tell one noisy host from a saturated map (the `LoginThrottle` "audited 429 saturation" idiom).
     */
    const val INTEGRATION_SATURATED = "integration-saturated"

    /**
     * The same namespace, per verified SERVICE TOKEN (the [REPORTS] idiom, keyed by [RateLimitTokenKey]): a fixed 120/min in every
     * mode. A scheduled consumer sends one or two calls per tick, so the bound is for a runaway or leaked-token client.
     */
    const val INTEGRATION_TOKEN = "integration-token"

    const val DEFAULT_REFRESH_PER_MINUTE = 30
    /**
     * 60/min per IP: the kubelet probes `/ready` every 5 s per container (12/min, `k8s/web-deployment.yaml` and
     * `worker-deployment.yaml`; the e2e global setup polls every 2 s = 30/min), so real probers stay at a fifth to a half
     * of it, while one host can no longer hold the 20-connection pool busy with a flood of anonymous round trips.
     */
    const val DEFAULT_READY_PER_MINUTE = 60
    /**
     * Production 120/min per user: a person clicking through the reports sends a handful of calls a minute (a page is 1 to 4),
     * so a real session sits at a tenth of it. Development lifts it (the login-bucket idiom) because the e2e suite shares ONE
     * seeded admin across 4 parallel workers whose derive pollers alone send 30/min each (`e2e/tests/helpers.ts`).
     */
    private const val REPORTS_PER_MINUTE_PRODUCTION = 120
    private const val REPORTS_PER_MINUTE_DEVELOPMENT = 1000
    private const val MAX_LIMIT_PER_MINUTE = 1_000_000
    private const val TELEMETRY_PER_MINUTE = 30
    private const val DEFAULT_INTEGRATION_PER_MINUTE = 60
    private const val INTEGRATION_TOKEN_PER_MINUTE = 120
    private const val MFA_PER_MINUTE = 10
    private const val PASSWORD_RESET_CONFIRM_PER_MINUTE = 10
    private const val DATA_SOURCE_TEST_PER_MINUTE = 10
    private const val LOGIN_PER_MINUTE_PRODUCTION = 10
    private const val LOGIN_PER_MINUTE_DEVELOPMENT = 1000
    private const val RESET_PER_MINUTE_PRODUCTION = 5
    private const val RESET_PER_MINUTE_DEVELOPMENT = 100

    /** Blank follows the mode; a number pins the bucket in either mode (the `http.exposeOpenApi` idiom). */
    internal fun Application.configuredLimit(key: String, default: Int): Int =
        if (environment.config.propertyOrNull(key)?.getString().isNullOrBlank()) {
            default
        } else {
            // Boot-validated: a non-numeric or non-positive limit (0 blocks every request) refuses startup.
            requireConfigInt(environment.config, key, min = 1, max = MAX_LIMIT_PER_MINUTE)
        }

    internal fun Application.loginLimit() = configuredLimit(
        "security.rateLimit.loginPerMinute",
        if (developmentMode) LOGIN_PER_MINUTE_DEVELOPMENT else LOGIN_PER_MINUTE_PRODUCTION,
    )
    internal fun Application.refreshLimit() = configuredLimit("security.rateLimit.refreshPerMinute", DEFAULT_REFRESH_PER_MINUTE)
    internal fun Application.readyLimit() = configuredLimit("security.rateLimit.readyPerMinute", DEFAULT_READY_PER_MINUTE)
    internal fun Application.integrationLimit() = configuredLimit("security.rateLimit.integrationPerMinute", DEFAULT_INTEGRATION_PER_MINUTE)
    internal fun Application.passwordResetLimit() = configuredLimit(
        "security.rateLimit.passwordResetPerMinute",
        if (developmentMode) RESET_PER_MINUTE_DEVELOPMENT else RESET_PER_MINUTE_PRODUCTION,
    )
    internal fun Application.reportsLimit() = configuredLimit(
        "security.rateLimit.reportsPerMinute",
        if (developmentMode) REPORTS_PER_MINUTE_DEVELOPMENT else REPORTS_PER_MINUTE_PRODUCTION,
    )
    internal fun telemetryLimit() = TELEMETRY_PER_MINUTE
    internal fun integrationTokenLimit() = INTEGRATION_TOKEN_PER_MINUTE
    internal fun mfaLimit() = MFA_PER_MINUTE
    internal fun passwordResetConfirmLimit() = PASSWORD_RESET_CONFIRM_PER_MINUTE
    internal fun dataSourceTestLimit() = DATA_SOURCE_TEST_PER_MINUTE
}

/**
 * The buckets (see application.yaml `security.rateLimit` and security.md): per IP, except the per-USER reports and telemetry buckets and
 * the per-TOKEN `integration-token` bucket (the per-host `integration` budget is spent by the service-token provider, not installed
 * here). Login,
 * password-reset and reports follow the mode — production keeps them tight, development lifts them so one host
 * driving the e2e suite is not throttled (the per-account lockout and the per-email throttle are the
 * abuse defences in both modes); refresh, ready, MFA and the password-reset confirm are the same in every mode. Installed here, before
 * the feature modules, so any route may ride any bucket regardless of module order.
 */
fun Application.configureRateLimits() {
    val buckets = with(RateLimits) {
        mapOf(
            LOGIN to loginLimit(),
            REFRESH to refreshLimit(),
            PASSWORD_RESET to passwordResetLimit(),
            MFA to mfaLimit(),
            PASSWORD_RESET_CONFIRM to passwordResetConfirmLimit(),
            DATA_SOURCE_TEST to dataSourceTestLimit(),
            READY to readyLimit(),
        )
    }
    val perUserBuckets = with(RateLimits) { mapOf(REPORTS to reportsLimit(), TELEMETRY to telemetryLimit()) }
    install(RateLimit) {
        buckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call ->
                    // The plugin gives its 429 no handle on the bucket, but it computes this key right before
                    // rejecting, so the name stamped here is the rejecting bucket's (the `rate_limit.exceeded` audit).
                    call.attributes.put(RateLimitBucketKey, name)
                    call.rateLimitHost()
                }
            }
        }
        // The per-USER buckets (reports, telemetry). The routes mount them INSIDE `authenticate {}` (reports/ReportRoutes.kt,
        // telemetry/TelemetryRoutes.kt), so this key runs only for a verified token: an anonymous call is a 401 that never reaches it
        // and never creates a limiter. The plugin keeps one limiter per key and drops it after its refill period, so memory is
        // bounded by the accounts active within a minute.
        perUserBuckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call ->
                    call.attributes.put(RateLimitBucketKey, name)
                    val userId = call.caller().userId
                    call.attributes.put(RateLimitUserKey, userId)
                    userId
                }
            }
        }
        // The per-TOKEN bucket (integration-token). The route mounts it INSIDE `authenticate(SERVICE_TOKEN)`, so this key runs only for a
        // token the registry verified live; the provider stamped its id on the call (RateLimitTokenKey), so the limiter count is bounded
        // by the registry and `plugins` names no `integration` type.
        register(RateLimitName(RateLimits.INTEGRATION_TOKEN)) {
            rateLimiter(limit = RateLimits.integrationTokenLimit(), refillPeriod = 60.seconds)
            requestKey { call ->
                call.attributes.put(RateLimitBucketKey, RateLimits.INTEGRATION_TOKEN)
                call.attributes[RateLimitTokenKey]
            }
        }
    }
}
