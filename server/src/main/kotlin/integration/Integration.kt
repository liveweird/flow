package ch.nokillswit.integration

import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.plugins.RateLimits
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.util.AttributeKey

/**
 * The coalescing windows of `service_token.auth_failed` (per reason, plus the token id when a row matched) and `service_token.used` (per
 * token): one event per key per minute with a `suppressed` count, so a replayed leaked token writes a line a minute, not a line per
 * request. Read at request time, so a test can swap them (the `AccessRejectionAuditKey` idiom).
 */
internal val ServiceTokenAuthFailedAuditKey = AttributeKey<RateLimitAuditThrottle>("ServiceTokenAuthFailedAudit")
internal val ServiceTokenUsedAuditKey = AttributeKey<RateLimitAuditThrottle>("ServiceTokenUsedAudit")

/**
 * The per-host budget the provider spends before every lookup (`HostWindowLimiter`, limit `security.rateLimit.integrationPerMinute`).
 * Read at request time, so a test can swap it for a small or fake-clocked one.
 */
internal val IntegrationHostLimiterKey = AttributeKey<HostWindowLimiter>("IntegrationHostLimiter")

/**
 * Registers the service-token authentication provider next to the JWT one (`.claude/docs/security.md` "Service tokens"). On Ktor 3.6.1
 * `Application.authentication {}` configures the already-installed Authentication plugin, so `plugins/Security.kt` stays untouched and
 * `plugins` never imports `integration`. Runs after `configureSecurity` (the plugin) and `configureDatabase` (the service the provider
 * reads at request time) and before any route module that names `authenticate(SERVICE_TOKEN)`. Registered in every role: the provider
 * costs nothing until a route uses it, and only `configureIntegrationRoutes` (web/all) mounts one.
 */
fun Application.configureIntegration() {
    attributes.put(ServiceTokenAuthFailedAuditKey, RateLimitAuditThrottle())
    attributes.put(ServiceTokenUsedAuditKey, RateLimitAuditThrottle())
    // Boot-validated like every bucket (a non-numeric or non-positive pin refuses startup).
    attributes.put(IntegrationHostLimiterKey, HostWindowLimiter(limit = with(RateLimits) { integrationLimit() }))
    authentication {
        register(ServiceTokenAuthenticationProvider(ServiceTokenAuthenticationProvider.Config(SERVICE_TOKEN)))
    }
}
