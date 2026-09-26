package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.seconds

/**
 * Every per-IP token bucket the routes ride, named in one place so a feature module never has to
 * know another's constant (the auth module used to import the try-it bucket's name — a layering
 * inversion the checkup removed). Route files reference these names; only this plugin installs them.
 */
object RateLimits {
    const val LOGIN = "login"
    const val REFRESH = "refresh"
    const val PASSWORD_RESET = "password-reset"
    const val MFA = "mfa"

    const val DEFAULT_REFRESH_PER_MINUTE = 30
    private const val MFA_PER_MINUTE = 10
    private const val LOGIN_PER_MINUTE_PRODUCTION = 10
    private const val LOGIN_PER_MINUTE_DEVELOPMENT = 1000
    private const val RESET_PER_MINUTE_PRODUCTION = 5
    private const val RESET_PER_MINUTE_DEVELOPMENT = 100

    /** Blank follows the mode; a number pins the bucket in either mode (the `http.exposeOpenApi` idiom). */
    internal fun Application.configuredLimit(key: String, default: Int): Int =
        environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }?.toInt() ?: default

    internal fun Application.loginLimit() = configuredLimit(
        "security.rateLimit.loginPerMinute",
        if (developmentMode) LOGIN_PER_MINUTE_DEVELOPMENT else LOGIN_PER_MINUTE_PRODUCTION,
    )
    internal fun Application.refreshLimit() = configuredLimit("security.rateLimit.refreshPerMinute", DEFAULT_REFRESH_PER_MINUTE)
    internal fun Application.passwordResetLimit() = configuredLimit(
        "security.rateLimit.passwordResetPerMinute",
        if (developmentMode) RESET_PER_MINUTE_DEVELOPMENT else RESET_PER_MINUTE_PRODUCTION,
    )
    internal fun mfaLimit() = MFA_PER_MINUTE
}

/**
 * The per-IP buckets (see application.yaml `security.rateLimit` and security.md): login and
 * password-reset follow the mode — production keeps them tight, development lifts them so one host
 * driving the e2e suite is not throttled (the per-account lockout and the per-email throttle are the
 * abuse defences in both modes); refresh and MFA are the same in every mode. Installed here, before
 * the feature modules, so any route may ride any bucket regardless of module order.
 */
fun Application.configureRateLimits() {
    val buckets = with(RateLimits) {
        mapOf(
            LOGIN to loginLimit(),
            REFRESH to refreshLimit(),
            PASSWORD_RESET to passwordResetLimit(),
            MFA to mfaLimit(),
        )
    }
    install(RateLimit) {
        buckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call -> call.request.origin.remoteHost }
            }
        }
    }
}
