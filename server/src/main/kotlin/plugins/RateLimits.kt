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

/** The name of the bucket a call last rode, stamped by its `requestKey`; read by the 429 audit in ErrorHandling.kt. */
internal val RateLimitBucketKey = AttributeKey<String>("RateLimitBucket")

/**
 * The authenticated user id a PER-USER bucket (today only [RateLimits.REPORTS]) keyed the call by, stamped by its `requestKey`;
 * absent on the per-IP buckets. The 429 audit carries it as `byUserId` and throttles per user: it is the server-minted JWT claim
 * of a verified token, never client-chosen text.
 */
internal val RateLimitUserKey = AttributeKey<UInt>("RateLimitUser")

/**
 * Coalesces the `rate_limit.exceeded` audit event (checkup 2C15): an unauthenticated flood must not write one audit line
 * per rejected request. At most ONE event per key per [intervalMs]; [admit] returns the number of rejections folded
 * into the previous window (the event's `suppressed` field) when the caller should emit now, or null when this rejection
 * is folded into the current window. The key is the bucket name for a per-IP bucket (a handful of fixed names, never the
 * client) and `bucket:userId` for a per-user bucket (only a verified, signed-in account can reach it, so the key space is the
 * number of accounts), so memory is bounded no matter who floods; thread-safe via [ConcurrentHashMap.compute]'s per-key atomicity.
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
 * Every token bucket the routes ride (per client IP, except [REPORTS], which is per signed-in user), named in one place so a
 * feature module never has to know another's constant (the auth module used to import the try-it bucket's name — a layering
 * inversion the checkup removed). Route files reference these names; only this plugin installs them.
 */
object RateLimits {
    const val LOGIN = "login"
    const val REFRESH = "refresh"
    const val PASSWORD_RESET = "password-reset"
    const val MFA = "mfa"
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
    private const val MFA_PER_MINUTE = 10
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
    internal fun Application.passwordResetLimit() = configuredLimit(
        "security.rateLimit.passwordResetPerMinute",
        if (developmentMode) RESET_PER_MINUTE_DEVELOPMENT else RESET_PER_MINUTE_PRODUCTION,
    )
    internal fun Application.reportsLimit() = configuredLimit(
        "security.rateLimit.reportsPerMinute",
        if (developmentMode) REPORTS_PER_MINUTE_DEVELOPMENT else REPORTS_PER_MINUTE_PRODUCTION,
    )
    internal fun mfaLimit() = MFA_PER_MINUTE
    internal fun dataSourceTestLimit() = DATA_SOURCE_TEST_PER_MINUTE
}

/**
 * The buckets (see application.yaml `security.rateLimit` and security.md): per IP, except the per-USER reports bucket. Login,
 * password-reset and reports follow the mode — production keeps them tight, development lifts them so one host
 * driving the e2e suite is not throttled (the per-account lockout and the per-email throttle are the
 * abuse defences in both modes); refresh, ready and MFA are the same in every mode. Installed here, before
 * the feature modules, so any route may ride any bucket regardless of module order.
 */
fun Application.configureRateLimits() {
    val buckets = with(RateLimits) {
        mapOf(
            LOGIN to loginLimit(),
            REFRESH to refreshLimit(),
            PASSWORD_RESET to passwordResetLimit(),
            MFA to mfaLimit(),
            DATA_SOURCE_TEST to dataSourceTestLimit(),
            READY to readyLimit(),
        )
    }
    val reportsPerMinute = with(RateLimits) { reportsLimit() }
    install(RateLimit) {
        buckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call ->
                    // The plugin gives its 429 no handle on the bucket, but it computes this key right before
                    // rejecting, so the name stamped here is the rejecting bucket's (the `rate_limit.exceeded` audit).
                    call.attributes.put(RateLimitBucketKey, name)
                    call.request.origin.remoteHost
                }
            }
        }
        // The one per-USER bucket. The routes mount it INSIDE `authenticate {}` (reports/ReportRoutes.kt), so this key runs only
        // for a verified token: an anonymous call is a 401 that never reaches it and never creates a limiter. The plugin keeps one
        // limiter per key and drops it after its refill period, so memory is bounded by the accounts active within a minute.
        register(RateLimitName(RateLimits.REPORTS)) {
            rateLimiter(limit = reportsPerMinute, refillPeriod = 60.seconds)
            requestKey { call ->
                call.attributes.put(RateLimitBucketKey, RateLimits.REPORTS)
                val userId = call.caller().userId
                call.attributes.put(RateLimitUserKey, userId)
                userId
            }
        }
    }
}
