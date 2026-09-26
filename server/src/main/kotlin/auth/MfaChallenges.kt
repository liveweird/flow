package ch.nokillswit.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TreeMap

/**
 * In-memory store of pending email-MFA challenges (Lettuce's, ported): a login with correct
 * credentials by an MFA-enabled user mints a challenge — an opaque id handed to the client
 * plus a 6-digit code emailed to the account — and the pair must come back to
 * POST /api/v1/login/mfa within [ttlMillis] and [maxAttempts] guesses. A challenge is
 * single-use: consumed on success, dropped on expiry or when the attempt cap is exceeded.
 *
 * In-memory and per-instance by design (the LoginThrottle posture): the deployment runs a
 * single replica, and a restart only invalidates pending challenges — the user simply signs
 * in again. Multiple live challenges per account (repeated logins) are accepted: the short
 * TTL, the attempt cap, and the login rate bucket bound the guessing surface
 * (≤ maxAttempts·10⁻⁶ per challenge).
 */
class MfaChallenges(
    private val ttlMillis: Long,
    private val maxAttempts: Int,
    private val maxTracked: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
    }

    class CapacityExceededException : RuntimeException()

    data class IssuedChallenge(val challengeId: String, val code: String, val expiresAt: Long)

    sealed interface Outcome {
        data class Success(val userId: UInt, val credentialRevision: Long) : Outcome

        /** [reason] feeds the audit trail only — the HTTP answer stays a uniform 401. */
        data class Failure(val reason: String) : Outcome
    }

    private data class Challenge(
        val userId: UInt,
        val credentialRevision: Long,
        val code: String,
        val expiresAt: Long,
        val attempts: Int,
    )

    private val challenges = mutableMapOf<String, Challenge>()
    private val expiryIndex = TreeMap<Long, MutableSet<String>>()
    private val lock = Any()

    fun issue(userId: UInt, credentialRevision: Long): IssuedChallenge = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        if (challenges.size >= maxTracked) throw CapacityExceededException()
        val id = generateChallengeId()
        val code = generateMfaCode()
        val expiresAt = now + ttlMillis
        challenges[id] = Challenge(userId, credentialRevision, code, expiresAt, attempts = 0)
        expiryIndex.getOrPut(expiresAt) { mutableSetOf() }.add(id)
        IssuedChallenge(id, code, expiresAt)
    }

    fun verify(challengeId: String, code: String): Outcome = synchronized(lock) {
        val challenge = challenges[challengeId]
            ?: return@synchronized Outcome.Failure("unknown_challenge")
        if (challenge.expiresAt <= clock()) {
            removeChallenge(challengeId)
            return@synchronized Outcome.Failure("expired")
        }
        if (MessageDigest.isEqual(challenge.code.toByteArray(), code.toByteArray())) {
            removeChallenge(challengeId)
            return@synchronized Outcome.Success(challenge.userId, challenge.credentialRevision)
        }
        val attempts = challenge.attempts + 1
        if (attempts >= maxAttempts) {
            removeChallenge(challengeId)
            Outcome.Failure("too_many_attempts")
        } else {
            challenges[challengeId] = challenge.copy(attempts = attempts)
            Outcome.Failure("wrong_code")
        }
    }

    /** Drops an issued challenge when its delivery fails, immediately releasing its slot. */
    fun discard(challengeId: String) = synchronized(lock) {
        removeChallenge(challengeId)
    }

    private fun pruneExpired(now: Long) {
        while (expiryIndex.firstEntry()?.key?.let { it <= now } == true) {
            val (expiresAt, ids) = expiryIndex.pollFirstEntry()
            ids.forEach { id ->
                if (challenges[id]?.expiresAt == expiresAt) challenges.remove(id)
            }
        }
    }

    private fun removeChallenge(id: String): Boolean {
        val removed = challenges.remove(id) ?: return false
        expiryIndex[removed.expiresAt]?.let { ids ->
            ids.remove(id)
            if (ids.isEmpty()) expiryIndex.remove(removed.expiresAt)
        }
        return true
    }

    private companion object {
        val secureRandom = SecureRandom()

        /** 128 bits of opaque, unguessable challenge identity. */
        fun generateChallengeId(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
