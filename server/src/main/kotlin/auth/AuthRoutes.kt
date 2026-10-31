package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.plugins.JwtConfigKey
import ch.nokillswit.plugins.servesApi
import ch.nokillswit.flowusers.Feature
import ch.nokillswit.flowusers.FlowUser
import ch.nokillswit.flowusers.FlowUserServiceKey
import ch.nokillswit.infra.validation.canonicalEmail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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

/**
 * The upper bound for the lockout/reset/MFA durations configured in SECONDS (before their `* 1000` millis conversion):
 * a value near `Long.MAX_VALUE` would overflow into a negative millis figure and silently DISABLE the lockout/throttle
 * instead of refusing to start. 30 days is far beyond any legitimate value.
 */
internal const val MAX_DURATION_SECONDS = 30L * 24 * 3600

/** Ceiling for the in-memory throttle/challenge stores' `maxTracked` capacities — bounds the memory an operator can commit. */
internal const val MAX_TRACKED_IDENTITIES = 1_000_000

private fun JwtConfig.authResponse(userId: UInt, user: FlowUser): LoginResponse {
    val roles = user.additionalRoles
    val access = issueAccessToken(userId, user.email, roles, user.disabledFeatures, user.credentialRevision)
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
    val userService: ch.nokillswit.flowusers.FlowUserService,
    val blocklist: TokenBlocklistService,
    val loginThrottle: LoginThrottle,
    val mailer: ch.nokillswit.infra.mail.Mailer?,
    val mfaChallenges: MfaChallenges,
    val mfaTtlMinutes: Long,
    /** Verifies signature/issuer/audience/expiry of a presented refresh token — the same secret as the access verifier. */
    val refreshVerifier: com.auth0.jwt.JWTVerifier,
)

fun Application.configureAuthRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val jwtConfig = attributes[JwtConfigKey]
    // Boot-validated through the shared requireConfigInt/requireConfigLong (Lettuce's ranges,
    // ported) below: a malformed or out-of-range value is a config error, not a runtime concern —
    // e.g. LOGIN_LOCKOUT_THRESHOLD=0 would lock out every account on its first attempt.
    // codeTtlSeconds is validated with min = 0 ONLY in development mode (not Lettuce's min = 1): Flow's
    // in-memory MfaChallenges (unlike Lettuce's DB-backed table) has no back-dating hook for
    // `MfaLoginTest`'s "an expired challenge answers the same uniform 401" case, which mints a
    // deliberately born-expired challenge via codeTtlSeconds=0. Outside development a zero TTL would
    // silently make every MFA login fail, so it refuses startup. The upper bound guards the *1000 overflow.
    val mfaTtlSeconds = requireConfigLong(
        environment.config, "security.mfa.codeTtlSeconds", min = if (developmentMode) 0 else 1, max = MAX_DURATION_SECONDS,
    )
    val deps = AuthDeps(
        jwtConfig = jwtConfig,
        userService = attributes[FlowUserServiceKey],
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
            maxTracked = requireConfigInt(environment.config, "security.lockout.maxTracked", min = 1, max = MAX_TRACKED_IDENTITIES),
        ),
        mailer = mailer(),
        // Email MFA: pending challenges for MFA-enabled accounts mid-login. In-memory, per-instance,
        // like the throttles above. The issuance worker lives beside the email content (auth/MfaEmail.kt).
        mfaChallenges = MfaChallenges(
            ttlMillis = mfaTtlSeconds * 1000,
            maxAttempts = requireConfigInt(environment.config, "security.mfa.maxAttempts", min = 1, max = 100),
            maxTracked = requireConfigInt(environment.config, "security.mfa.maxTracked", min = 1, max = MAX_TRACKED_IDENTITIES),
        ),
        mfaTtlMinutes = (mfaTtlSeconds + 59) / 60,
        refreshVerifier = JWT.require(Algorithm.HMAC256(jwtConfig.secret))
            .withAudience(jwtConfig.audience)
            .withIssuer(jwtConfig.issuer)
            // Rotation blocklists the jti until the token's own expiry, so an exp-less token is malformed.
            .withClaimPresence("exp")
            .build(),
    )
    // The buckets are installed by plugins/RateLimits.kt; each public step rides its own.
    routing {
        rateLimit(RateLimitName(RateLimits.LOGIN)) { login(deps) }
        rateLimit(RateLimitName(RateLimits.MFA)) { mfa(deps) }
        rateLimit(RateLimitName(RateLimits.REFRESH)) { refresh(deps) }
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
                audit(EVENT_LOGIN_REJECTED_LOCKED, auditIdentity)
                // Thrown (not respondProblem) so StatusPages marks the call handled and its
                // generic 429 status handler cannot replace this specific detail.
                throw TooManyRequestsException(
                    "Too many failed login attempts for this account — try again later",
                )
            }
            LoginThrottle.Preflight.CAPACITY_EXCEEDED -> {
                audit(EVENT_LOGIN_CAPACITY_REJECTED, auditIdentity)
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
                    audit(EVENT_LOGIN_CAPACITY_REJECTED, auditIdentity)
                    throw TooManyRequestsException("Too many sign-in identities are being tracked — try again later")
                }
                LoginThrottle.FailureResult.ALREADY_LOCKED -> {
                    audit(EVENT_LOGIN_REJECTED_LOCKED, auditIdentity)
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
            audit(EVENT_LOGIN_REJECTED_LOCKED, auditIdentity)
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
                audit(EVENT_LOGIN_MFA_FAILURE, "reason" to outcome.reason)
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
                    audit(EVENT_LOGIN_MFA_FAILURE, "reason" to "user_gone", "userId" to userId.toLong())
                    throw UnauthorizedException("Invalid or expired sign-in code")
                }
                if (outcome.credentialRevision != user.credentialRevision) {
                    audit(
                        EVENT_LOGIN_MFA_FAILURE,
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
    // Not behind `authenticate`: the access token may already be expired here. Pure-sliding with
    // ROTATION: the presented refresh token is blocklisted (until its own expiry) by an atomic claim
    // and a fresh pair is minted, so the token works once. A replay inside REFRESH_REPLAY_GRACE_MS of
    // the rotation still gets a pair (the response was probably lost); beyond it, a `replayed` 401.
    // The access token it was paired with is left to expire on its own (<= 15 min).
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
        // The cheap fast path (one plain select); a recently rotated token is let through to the claim below.
        if (blocklist.isRevoked(jti) && !blocklist.inReplayGrace(jti)) {
            reject(if (blocklist.wasRotated(jti)) "replayed" else "revoked", rawUserId)
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
        // Rotation: claim the presented token LAST, after every other check passed, with one atomic
        // insert. Two concurrent refreshes with the same token both pass the fast path above; only the
        // insert's winner is CLAIMED, the other is answered by the grace (its own pair) or rejected.
        val expiresAt = decoded.expiresAt?.time ?: reject("malformed", rawUserId)
        when (blocklist.claimRefresh(jti, expiresAt, userId.toLong())) {
            RefreshClaim.CLAIMED -> Unit
            RefreshClaim.GRACE_REPLAY -> audit("refresh.replay_grace", "userId" to rawUserId)
            RefreshClaim.REPLAYED -> reject("replayed", rawUserId)
            RefreshClaim.REVOKED -> reject("revoked", rawUserId)
        }
        call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user))
    }
}

private fun Route.logout(deps: AuthDeps) {
    val blocklist = deps.blocklist
    val refreshVerifier = deps.refreshVerifier
    post("/api/v1/logout") {
        val principal = call.principal<JWTPrincipal>()!!
        // A logout ends the replay grace of this user's recently rotated refresh tokens (a copy of one
        // must not mint after sign-out); the access principal is what logout already trusts.
        principal.payload.getClaim("userId").asLong()?.let { blocklist.forgetRotations(it) }
        val jti = principal.payload.id
        val exp = principal.payload.expiresAt?.time ?: nowMillis()
        if (jti != null) {
            blocklist.revoke(jti, exp)
        }
        // Also revoke the refresh token, if the client sent it, so an explicit logout kills it
        // too (a refresh token is also blocklisted when it is rotated, but logout ends the live one).
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
            // Covers a mismatched pair too: the presented refresh token's own user loses its grace as well.
            decoded?.strictLongClaim("userId")?.let { blocklist.forgetRotations(it) }
            decoded?.id?.let { rjti ->
                blocklist.revoke(rjti, decoded.expiresAt?.time ?: nowMillis())
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
