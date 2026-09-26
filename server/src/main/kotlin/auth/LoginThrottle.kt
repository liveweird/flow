package ch.nokillswit.auth

import java.security.MessageDigest
import java.util.TreeMap

/**
 * Per-account login throttle: after [threshold] consecutive failures for the same submitted
 * email, further attempts are rejected for [lockoutMillis] — regardless of whether the account
 * exists (so it leaks nothing), and independent of the per-IP rate limit (which an attacker can
 * sidestep by rotating hosts).
 *
 * In-memory and per-instance by design: the deployment runs a single replica, and losing the
 * counters on restart only resets the throttle, never grants access. A successful login clears
 * the account's counter.
 */
class LoginThrottle(
    private val threshold: Int,
    private val lockoutMillis: Long,
    private val maxTracked: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
    }

    enum class Preflight {
        ALLOWED,
        LOCKED,
        CAPACITY_EXCEEDED,
    }

    enum class FailureResult {
        RECORDED,
        LOCKED_NOW,
        ALREADY_LOCKED,
        CAPACITY_EXCEEDED,
    }

    enum class SuccessResult {
        CLEARED,
        LOCKED,
    }

    private data class State(val failures: Int, val lockedUntil: Long, val expiresAt: Long)

    private val states = mutableMapOf<String, State>()
    private val expiryIndex = TreeMap<Long, MutableSet<String>>()
    private val lock = Any()

    private fun key(email: String) = loginIdentityKey(email)

    /**
     * Checks an identity before the account lookup and bcrypt work. Existing identities may
     * continue at capacity; a new identity is rejected until stale state can be reclaimed.
     */
    fun preflight(email: String): Preflight {
        val k = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val state = states[k]
            when {
                state?.lockedUntil?.let { it > now } == true -> Preflight.LOCKED
                state != null || states.size < maxTracked -> Preflight.ALLOWED
                else -> Preflight.CAPACITY_EXCEEDED
            }
        }
    }

    /** Records a failure, atomically rechecking capacity after the password work. */
    fun recordFailure(email: String): FailureResult {
        val k = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val current = states[k]
            if (current == null && states.size >= maxTracked) {
                return@synchronized FailureResult.CAPACITY_EXCEEDED
            }
            // A request that passed preflight just before another request tripped the lock must
            // never replace that active lock with a fresh counter.
            if (current != null && current.lockedUntil > now) {
                return@synchronized FailureResult.ALREADY_LOCKED
            }
            val failures = (current?.failures ?: 0) + 1
            if (failures >= threshold) {
                putState(k, State(0, now + lockoutMillis, now + lockoutMillis))
                FailureResult.LOCKED_NOW
            } else {
                putState(k, State(failures, 0, now + lockoutMillis))
                FailureResult.RECORDED
            }
        }
    }

    fun recordSuccess(email: String): SuccessResult {
        val key = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val current = states[key]
            if (current != null && current.lockedUntil > now) {
                SuccessResult.LOCKED
            } else {
                removeState(key)
                SuccessResult.CLEARED
            }
        }
    }

    private fun putState(key: String, state: State) {
        removeState(key)
        states[key] = state
        expiryIndex.getOrPut(state.expiresAt) { mutableSetOf() }.add(key)
    }

    private fun removeState(key: String) {
        val removed = states.remove(key) ?: return
        expiryIndex[removed.expiresAt]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) expiryIndex.remove(removed.expiresAt)
        }
    }

    private fun pruneExpired(now: Long) {
        while (expiryIndex.firstEntry()?.key?.let { it <= now } == true) {
            val (expiresAt, keys) = expiryIndex.pollFirstEntry()
            keys.forEach { key ->
                if (states[key]?.expiresAt == expiresAt) states.remove(key)
            }
        }
    }

}

/** Fixed-size, non-reversible key so an arbitrarily long submitted identity is never retained. */
internal fun loginIdentityKey(email: String): String {
    val canonical = email.trim().lowercase().toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
}
