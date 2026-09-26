package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.mail.respondMailUnavailable
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.users.Feature
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.canonicalEmail
import ch.nokillswit.users.validateEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import ch.nokillswit.plugins.RateLimits
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The upper bound for the lockout/reset durations configured in SECONDS below, before their
 * `* 1000` millis conversion: `requireConfigLong`'s own default max is `Long.MAX_VALUE`, and a
 * value near it overflows into a negative millis figure on that multiplication — which would
 * silently DISABLE the lockout/throttle (every comparison against a negative "locked until"
 * timestamp fails) instead of refusing to start. 30 days is far beyond any legitimate value for
 * either setting.
 */
internal const val MAX_DURATION_SECONDS = 30L * 24 * 3600

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class PasswordResetRequest(val email: String)

/**
 * The MFA branch of POST /api/v1/login: credentials verified, second factor pending — no
 * tokens yet. The client sends the emailed 6-digit code with [challengeId] to
 * POST /api/v1/login/mfa to obtain the ordinary [LoginResponse].
 */
@Serializable
data class MfaChallengeResponse(
    // Literal discriminator against LoginResponse in the login 200 oneOf; @EncodeDefault
    // keeps the defaulted value on the wire.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val mfaRequired: Boolean = true,
    val challengeId: String,
    /** Epoch millis when the challenge (and its code) expires. */
    val expiresAt: Long,
)

@Serializable
data class MfaVerifyRequest(val challengeId: String, val code: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String? = null)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val userId: UInt,
    /** Additional roles of the authenticated user — empty for a regular user. */
    val roles: List<UserRole>,
    /** Per-user feature flags (V5) — the admin-disabled set; empty = full access. */
    val disabledFeatures: List<Feature>,
    /** The user's stored language (V1) — the SPA applies it to the UI on login/refresh. */
    val language: String,
)

// The refresh rejection detail per audited reason — data beside the handler, not control flow
// in it. Every reason has its explicit entry; the handler's fallback is generic, so a typo in
// a reason string can never masquerade as a specific message.
private val REFRESH_REJECT_MESSAGES = mapOf(
    "invalid_or_expired" to "Invalid or expired refresh token",
    "wrong_token_type" to "Not a refresh token",
    "revoked" to "Refresh token revoked",
    "malformed" to "Malformed refresh token",
    "user_gone" to "User no longer exists",
    "credential_changed" to "Refresh token predates a password change",
)

/** Numeric security claims are accepted only as integral JSON numbers. Auth0's generic claim
 * coercion accepts some malformed representations, so inspect the signed payload directly. */
private fun DecodedJWT.strictLongClaim(name: String): Long? = try {
    val payloadJson = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
    val primitive = Json.parseToJsonElement(payloadJson).jsonObject[name] as? JsonPrimitive
    if (primitive == null || primitive.isString) null else primitive.content.toLongOrNull()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun JwtConfig.authResponse(userId: UInt, user: User): LoginResponse {
    val roles = user.additionalRoles
    val access = issueAccessToken(userId, user.email, roles, user.disabledFeatures)
    val refresh = issueRefreshToken(
        userId,
        user.email,
        roles,
        user.disabledFeatures,
        user.credentialRevision,
    )
    return LoginResponse(
        token = access.token,
        expiresAt = access.expiresAt,
        refreshToken = refresh.token,
        refreshExpiresAt = refresh.expiresAt,
        userId = userId,
        roles = roles.sortedBy { it.name },
        disabledFeatures = user.disabledFeatures.sortedBy { it.name },
        // Not a JWT claim: the SPA reads it from this response, and emails read it fresh
        // at send time — nothing needs it inside the token.
        language = user.language,
    )
}

/** What every auth handler reaches for — built once by [configureAuthRoutes], handed to the route functions below. */
private class AuthDeps(
    val jwtConfig: JwtConfig,
    val userService: ch.nokillswit.users.UserService,
    val blocklist: TokenBlocklistService,
    val loginThrottle: LoginThrottle,
    val resetThrottle: PasswordResetThrottle,
    val mailer: ch.nokillswit.infra.mail.Mailer?,
    val mailAppUrl: String?,
    val mfaChallenges: MfaChallenges,
    val mfaTtlMinutes: Long,
    /** Verifies signature/issuer/audience/expiry of a presented refresh token — the same secret as the access verifier. */
    val refreshVerifier: com.auth0.jwt.JWTVerifier,
)

fun Application.configureAuthRoutes() {
    val jwtConfig = attributes[JwtConfigKey]
    // Boot-validated through the shared requireConfigInt/requireConfigLong (Lettuce's ranges,
    // ported) below: a malformed or out-of-range value is a config error, not a runtime concern —
    // e.g. LOGIN_LOCKOUT_THRESHOLD=0 would lock out every account on its first attempt.
    // codeTtlSeconds itself stays an ad-hoc read (not Lettuce's requireConfigLong-enforced
    // min = 1): Flow's in-memory MfaChallenges (unlike Lettuce's DB-backed table) has no
    // back-dating hook for `MfaLoginTest`'s "an expired challenge answers the same uniform 401"
    // case, which mints a deliberately born-expired challenge via codeTtlSeconds=0.
    val mfaTtlSeconds = environment.config.property("security.mfa.codeTtlSeconds").getString().toLong()
    val deps = AuthDeps(
        jwtConfig = jwtConfig,
        userService = attributes[UserServiceKey],
        blocklist = attributes[TokenBlocklistServiceKey],
        // Per-account lockout, complementing the per-IP bucket (which rotating hosts sidestep):
        // N consecutive failures for one email → locked for the configured window.
        loginThrottle = LoginThrottle(
            threshold = requireConfigInt(environment.config, "security.lockout.threshold", min = 1),
            // Bounded above (30 days) as well as below: an unbounded value survives the *1000
            // millis conversion right up until it doesn't — a durationSeconds near Long.MAX_VALUE
            // overflows into a negative millis figure, which would silently DISABLE the lockout
            // instead of refusing to start.
            lockoutMillis = requireConfigLong(
                environment.config, "security.lockout.durationSeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
            maxTracked = environment.config.property("security.lockout.maxTracked").getString().toInt(),
        ),
        // Self-service password reset: one request per submitted email per interval, uniformly
        // whether or not the account exists (the 429 carries no enumeration signal).
        resetThrottle = PasswordResetThrottle(
            minIntervalMillis = requireConfigLong(
                environment.config, "security.passwordReset.minIntervalSeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
            maxTracked = environment.config.property("security.passwordReset.maxTracked").getString().toInt(),
        ),
        mailer = mailer(),
        mailAppUrl = mailAppUrl(),
        // Email MFA: pending challenges for MFA-enabled accounts mid-login. In-memory, per-instance,
        // like the throttles above. The issuance worker lives beside the email content (auth/MfaEmail.kt).
        mfaChallenges = MfaChallenges(
            ttlMillis = mfaTtlSeconds * 1000,
            maxAttempts = requireConfigInt(environment.config, "security.mfa.maxAttempts", min = 1, max = 100),
            maxTracked = environment.config.property("security.mfa.maxTracked").getString().toInt(),
        ),
        mfaTtlMinutes = (mfaTtlSeconds + 59) / 60,
        refreshVerifier = JWT.require(Algorithm.HMAC256(jwtConfig.secret))
            .withAudience(jwtConfig.audience)
            .withIssuer(jwtConfig.issuer)
            .build(),
    )
    // The buckets are installed by plugins/RateLimits.kt; each public step rides its own.
    routing {
        rateLimit(RateLimitName(RateLimits.LOGIN)) { login(deps) }
        rateLimit(RateLimitName(RateLimits.MFA)) { mfa(deps) }
        rateLimit(RateLimitName(RateLimits.REFRESH)) { refresh(deps) }
        rateLimit(RateLimitName(RateLimits.PASSWORD_RESET)) { passwordReset(deps) }
        authenticate { logout(deps) }
    }
}

private fun loginAuditIdentity(email: String): Pair<String, Any?> =
    if (email.length <= 254) "email" to email else "emailDigest" to loginIdentityKey(email)

private fun Route.login(deps: AuthDeps) {
    val jwtConfig = deps.jwtConfig
    val userService = deps.userService
    val loginThrottle = deps.loginThrottle
    val mailer = deps.mailer
    val mfaChallenges = deps.mfaChallenges
    val mfaTtlMinutes = deps.mfaTtlMinutes
    post("/api/v1/login") {
        val req = call.receive<LoginRequest>()
        // Canonical identity: accounts are stored under the folded email, so the login
        // lookup folds the same way — a padded or case-variant submission matches its
        // account (and keeps sharing one lockout bucket).
        val email = canonicalEmail(req.email)
        val auditIdentity = loginAuditIdentity(email)
        when (loginThrottle.preflight(email)) {
            LoginThrottle.Preflight.ALLOWED -> Unit
            LoginThrottle.Preflight.LOCKED -> {
                audit("login.rejected_locked", auditIdentity)
                // Thrown (not respondProblem) so StatusPages marks the call handled and its
                // generic 429 status handler cannot replace this specific detail.
                throw TooManyRequestsException(
                    "Too many failed login attempts for this account — try again later",
                )
            }
            LoginThrottle.Preflight.CAPACITY_EXCEEDED -> {
                audit("login.capacity_rejected", auditIdentity)
                throw TooManyRequestsException("Too many sign-in identities are being tracked — try again later")
            }
        }
        val record = userService.findWithIdByEmail(email)
        // The unknown-email branch pays a full (discarded) bcrypt verify so its latency
        // matches the wrong-password branch — without it the fast 401 is a timing oracle
        // for account enumeration (the reset path equalizes the same way, via async work).
        val credentialsValid =
            if (record == null) {
                verifyPassword(req.password, TIMING_EQUALIZER_HASH)
                false
            } else {
                verifyPassword(req.password, record.second.passwordHash)
            }
        if (record == null || !credentialsValid) {
            val failureResult = loginThrottle.recordFailure(email)
            when (failureResult) {
                LoginThrottle.FailureResult.CAPACITY_EXCEEDED -> {
                    audit("login.capacity_rejected", auditIdentity)
                    throw TooManyRequestsException("Too many sign-in identities are being tracked — try again later")
                }
                LoginThrottle.FailureResult.ALREADY_LOCKED -> {
                    audit("login.rejected_locked", auditIdentity)
                    throw TooManyRequestsException(
                        "Too many failed login attempts for this account — try again later",
                    )
                }
                LoginThrottle.FailureResult.RECORDED,
                LoginThrottle.FailureResult.LOCKED_NOW,
                -> Unit
            }
            audit(
                "login.failure",
                auditIdentity,
                "reason" to if (record == null) "unknown_email" else "wrong_password",
            )
            if (failureResult == LoginThrottle.FailureResult.LOCKED_NOW) {
                audit("login.lockout", auditIdentity)
            }
            throw UnauthorizedException("Unknown email or wrong password")
        }
        val (userId, user) = record
        if (loginThrottle.recordSuccess(email) == LoginThrottle.SuccessResult.LOCKED) {
            audit("login.rejected_locked", auditIdentity)
            throw TooManyRequestsException(
                "Too many failed login attempts for this account — try again later",
            )
        }
        // Email MFA (opt-in via the MFA feature flag, read straight off the DB record —
        // no JWT exists yet): correct credentials answer with a challenge, not tokens.
        if (Feature.MFA !in user.disabledFeatures) {
            issueMfaChallenge(call, mfaChallenges, mailer, mfaTtlMinutes, userId, user)
            return@post
        }
        audit("login.success", "email" to user.email, "userId" to userId.toLong())
        call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user))
    }
}

private fun Route.mfa(deps: AuthDeps) {
    val jwtConfig = deps.jwtConfig
    val userService = deps.userService
    val mfaChallenges = deps.mfaChallenges
    // Second login step for MFA-enabled accounts: exchange the challenge id + the
    // emailed code for the ordinary token pair. Not behind `authenticate` — there is
    // no token yet.
    post("/api/v1/login/mfa") {
        val req = call.receive<MfaVerifyRequest>()
        when (val outcome = mfaChallenges.verify(req.challengeId, req.code)) {
            is MfaChallenges.Outcome.Failure -> {
                audit("login.mfa_failure", "reason" to outcome.reason)
                // Uniform for every failure mode — a guesser learns nothing about
                // whether the challenge exists, expired, or the code was wrong.
                throw UnauthorizedException("Invalid or expired sign-in code")
            }
            is MfaChallenges.Outcome.Success -> {
                val userId = outcome.userId
                // One read (the /refresh precedent): the user must still exist and be
                // active, and the pair is minted from their current roles/flags.
                val user = userService.read(userId)
                if (user == null) {
                    audit("login.mfa_failure", "reason" to "user_gone", "userId" to userId.toLong())
                    throw UnauthorizedException("Invalid or expired sign-in code")
                }
                if (outcome.credentialRevision != user.credentialRevision) {
                    audit(
                        "login.mfa_failure",
                        "reason" to "credential_changed",
                        "userId" to userId.toLong(),
                    )
                    throw UnauthorizedException("Invalid or expired sign-in code")
                }
                audit("login.mfa_success", "email" to user.email, "userId" to userId.toLong())
                call.respond(
                    HttpStatusCode.OK,
                    jwtConfig.authResponse(userId, user),
                )
            }
        }
    }
}

private fun Route.refresh(deps: AuthDeps) {
    val jwtConfig = deps.jwtConfig
    val userService = deps.userService
    val blocklist = deps.blocklist
    val refreshVerifier = deps.refreshVerifier
    // Not behind `authenticate`: the access token may already be expired here. Pure-sliding —
    // a fresh pair is minted and the old tokens are left to expire on their own (not revoked).
    post("/api/v1/refresh") {
        val req = call.receive<RefreshRequest>()
        fun reject(reason: String, userId: Long? = null): Nothing {
            audit("refresh.rejected", "reason" to reason, "userId" to userId)
            throw UnauthorizedException(REFRESH_REJECT_MESSAGES[reason] ?: "Refresh token rejected")
        }

        val decoded = try {
            refreshVerifier.verify(req.refreshToken)
        } catch (_: JWTVerificationException) {
            reject("invalid_or_expired")
        }
        if (decoded.getClaim("typ").asString() != TOKEN_TYPE_REFRESH) {
            reject("wrong_token_type")
        }
        val rawUserId = decoded.strictLongClaim("userId")
        // A jti-less token could never be blocklisted, so it is malformed by definition
        // (every server-minted token carries one).
        val jti = decoded.id ?: reject("malformed", rawUserId)
        if (blocklist.isRevoked(jti)) {
            reject("revoked", rawUserId)
        }
        val userId = rawUserId
            ?.takeIf { it in 1..UInt.MAX_VALUE.toLong() }
            ?.toUInt()
            ?: reject("malformed")
        // One read: confirm the user still exists and isn't soft-deleted, and pick up their
        // current role/email so changes take effect on the next refresh.
        val user = userService.read(userId)
            ?: reject("user_gone", rawUserId)
        // Exact credential generation: legacy tokens without the claim and malformed numeric
        // representations are rejected rather than falling back to timestamp ordering.
        val credentialRevision = decoded.strictLongClaim("credentialRevision")
            ?.takeIf { it >= 0 }
            ?: reject("malformed", rawUserId)
        if (credentialRevision != user.credentialRevision) {
            reject("credential_changed", rawUserId)
        }
        call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user))
    }
}

private fun Route.passwordReset(deps: AuthDeps) {
    val userService = deps.userService
    val resetThrottle = deps.resetThrottle
    val mailer = deps.mailer
    val mailAppUrl = deps.mailAppUrl
    // Self-service reset: generate a new password and email it. Always 202 for a
    // well-formed request — existence of the account must not be observable, so the
    // actual work happens asynchronously after the response (uniform latency, no
    // timing oracle: the lookup, bcrypt hash, and SMTP round-trip all take place
    // off the request).
    post("/api/v1/password-reset") {
        // Canonical identity: fold like login, so a case-variant reset request
        // reaches its account (and keeps its one throttle bucket).
        val email = canonicalEmail(call.receive<PasswordResetRequest>().email)
        validateEmail(email)
        if (mailer == null) {
            // mail.transport=disabled — the deployment cannot send email at all.
            call.respondMailUnavailable("password reset")
            return@post
        }
        when (resetThrottle.tryAcquire(email)) {
            PasswordResetThrottle.AcquireResult.ACQUIRED -> Unit
            PasswordResetThrottle.AcquireResult.COOLDOWN -> {
                audit("password_reset.throttled", "email" to email)
                throw TooManyRequestsException(
                    "Only one password reset per minute per address — try again shortly",
                )
            }
            PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED -> {
                audit("password_reset.capacity_rejected", "email" to email)
                throw TooManyRequestsException("Too many password reset identities are being tracked — try again later")
            }
        }
        audit("password_reset.requested", "email" to email)
        // The worker (auth/PasswordResetEmail.kt) runs after the uniform 202.
        val app = call.application
        app.launch { processPasswordReset(app, userService, mailer, mailAppUrl, email) }
        call.respond(HttpStatusCode.Accepted)
    }
}

private fun Route.logout(deps: AuthDeps) {
    val blocklist = deps.blocklist
    val refreshVerifier = deps.refreshVerifier
    post("/api/v1/logout") {
        val principal = call.principal<JWTPrincipal>()!!
        val jti = principal.payload.id
        val exp = principal.payload.expiresAt?.time ?: System.currentTimeMillis()
        if (jti != null) {
            blocklist.revoke(jti, exp)
        }
        // Also revoke the refresh token, if the client sent it, so an explicit logout kills it
        // too (rotation leaves superseded tokens alive, but logout is a deliberate revoke).
        // Best-effort by design (logout always answers 204), but the failures are NARROW
        // and logged — a blanket catch would silently skip revocation on unrelated errors
        // and swallow coroutine cancellation.
        val body = try {
            call.receiveNullable<LogoutRequest>()
        } catch (cause: BadRequestException) {
            // ContentNegotiation's malformed-JSON wrap. The cause chain can embed a body
            // excerpt (kotlinx's decode error message) — log the exception CLASS NAME
            // only, the login.mfa_send_failed/password_reset.send_failed `errorType` rule
            // (.claude/docs/observability.md), never the throwable itself.
            val errorType = cause.cause?.javaClass?.simpleName ?: cause.javaClass.simpleName
            call.application.log.debug(
                "Logout body unparsable — skipping refresh-token revocation ({})",
                errorType,
            )
            null
        } catch (cause: CannotTransformContentToTypeException) {
            // A body-less/Content-Type-less POST never enters ContentNegotiation.
            call.application.log.debug(
                "Logout sent no body — skipping refresh-token revocation ({})",
                cause.javaClass.simpleName,
            )
            null
        }
        body?.refreshToken?.let { rt ->
            val decoded = try {
                refreshVerifier.verify(rt)
            } catch (cause: JWTVerificationException) {
                // auth0's JWTDecodeException message can embed the decoded token segment —
                // class name only, same errorType rule as above.
                call.application.log.debug(
                    "Logout refresh token invalid — nothing to revoke ({})",
                    cause.javaClass.simpleName,
                )
                null
            }
            decoded?.id?.let { rjti ->
                blocklist.revoke(rjti, decoded.expiresAt?.time ?: System.currentTimeMillis())
            }
        }
        audit(
            "logout",
            "userId" to principal.payload.getClaim("userId").asLong(),
            "email" to principal.payload.getClaim("email").asString(),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
