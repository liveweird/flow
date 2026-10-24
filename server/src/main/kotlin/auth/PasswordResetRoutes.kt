package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.flowusers.FlowUserServiceKey
import ch.nokillswit.flowusers.canonicalEmail
import ch.nokillswit.flowusers.validateEmail
import ch.nokillswit.flowusers.validatePassword
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.mail.respondMailUnavailable
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.respondProblem
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch

/**
 * The self-service password reset by confirm-link (2D3; Toadie's design, `.claude/docs/security.md` "Self-service password
 * reset"). The request changes NOTHING - it emails a single-use, short-lived link; only the owner who opens it and chooses a
 * new password changes the credential, which (credential revision bump) signs every other session out. Both routes are public.
 */
fun Application.configurePasswordResetRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) - see Role.kt.
    if (!servesApi()) return

    val users = attributes[FlowUserServiceKey]
    val resets = attributes[PasswordResetServiceKey]
    val mailer = mailer()
    val appUrl = mailAppUrl() // the boot-validated origin (infra/mail/Mail.kt), never the request Host
    // One request per submitted email per interval, uniformly whether or not the account exists (the 429 carries no
    // enumeration signal). Boot-validated like the lockout: the *1000 conversion must not overflow.
    val throttle = PasswordResetThrottle(
        minIntervalMillis = requireConfigLong(
            environment.config, "security.passwordReset.minIntervalSeconds", min = 1, max = MAX_DURATION_SECONDS,
        ) * 1000,
        maxTracked = requireConfigInt(environment.config, "security.passwordReset.maxTracked", min = 1, max = MAX_TRACKED_IDENTITIES),
    )
    routing {
        rateLimit(RateLimitName(RateLimits.PASSWORD_RESET)) {
            // Always 202 for a well-formed request - the existence of the account must not be observable, so the real work
            // runs asynchronously after the response (uniform latency, no timing oracle).
            post("/api/v1/password-reset") {
                // Canonical identity: fold like login, so a case-variant request reaches its account (and its one throttle bucket).
                val email = canonicalEmail(call.receive<PasswordResetRequest>().email)
                validateEmail(email)
                if (mailer == null) {
                    // mail.transport=disabled - the deployment cannot send email at all.
                    call.respondMailUnavailable("password reset")
                    return@post
                }
                if (appUrl == null) {
                    call.respondProblem(
                        HttpStatusCode.ServiceUnavailable,
                        "Password reset is not configured — set MAIL_APP_URL to this deployment's public URL",
                    )
                    return@post
                }
                when (throttle.tryAcquire(email)) {
                    PasswordResetThrottle.AcquireResult.ACQUIRED -> Unit
                    PasswordResetThrottle.AcquireResult.COOLDOWN -> {
                        audit("password_reset.throttled", "email" to email)
                        throw TooManyRequestsException("Only one password reset per minute per address — try again shortly")
                    }
                    PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED -> {
                        audit("password_reset.capacity_rejected", "email" to email)
                        throw TooManyRequestsException("Too many password reset identities are being tracked — try again later")
                    }
                }
                audit("password_reset.requested", "email" to email)
                val app = call.application
                app.launch { processPasswordReset(app, users, resets, mailer, appUrl, email) }
                call.respond(HttpStatusCode.Accepted)
            }
        }
        rateLimit(RateLimitName(RateLimits.PASSWORD_RESET_CONFIRM)) {
            post("/api/v1/password-reset/confirm") {
                val req = call.receive<PasswordResetConfirmRequest>()
                // One uniform 401 for an unknown, malformed, expired, used or stale grant - the caller learns nothing more.
                fun reject(): Nothing {
                    audit("password_reset.rejected")
                    throw UnauthorizedException("Reset link is invalid or expired — request a new link")
                }
                // The cheap precheck BEFORE bcrypt: a junk token never costs a hash, and 401 wins over a 400 on the password.
                if (!resets.isUsable(req.token)) reject()
                // A password the rules refuse is a 400 that leaves the grant unconsumed, so the owner can retry on the same link.
                validatePassword(req.password)
                val userId = resets.complete(req.token, hashPassword(req.password)) ?: reject()
                audit("password_reset.completed", "userId" to userId.toLong())
                if (mailer != null) {
                    val app = call.application
                    app.launch { notifyPasswordReset(app, users, mailer, userId) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
