package ch.nokillswit.auth

import java.util.TreeMap

/**
 * Per-email throttle for the self-service password reset: at most one request per submitted
 * email per [minIntervalMillis] — uniformly, whether or not the account exists (so the 429
 * carries no enumeration signal). Sibling of [LoginThrottle]: in-memory and per-instance by
 * design (single-replica deployment; a restart only resets the throttle).
 */
class PasswordResetThrottle(
    private val minIntervalMillis: Long,
    private val maxTracked: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
    }

    enum class AcquireResult {
        ACQUIRED,
        COOLDOWN,
        CAPACITY_EXCEEDED,
    }

    private data class Entry(val expiresAt: Long)

    private val entries = mutableMapOf<String, Entry>()
    private val expiryIndex = TreeMap<Long, MutableSet<String>>()
    private val lock = Any()

    private fun key(email: String) = email.trim().lowercase()

    /** Atomically claims a slot without evicting another identity's fresh cooldown. */
    fun tryAcquire(email: String): AcquireResult = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        val key = key(email)
        val last = entries[key]
        when {
            last != null -> AcquireResult.COOLDOWN
            entries.size >= maxTracked -> AcquireResult.CAPACITY_EXCEEDED
            else -> {
                val expiresAt = now + minIntervalMillis
                entries[key] = Entry(expiresAt)
                expiryIndex.getOrPut(expiresAt) { mutableSetOf() }.add(key)
                AcquireResult.ACQUIRED
            }
        }
    }

    private fun pruneExpired(now: Long) {
        while (expiryIndex.firstEntry()?.key?.let { it <= now } == true) {
            val (expiresAt, keys) = expiryIndex.pollFirstEntry()
            keys.forEach { key ->
                if (entries[key]?.expiresAt == expiresAt) entries.remove(key)
            }
        }
    }

}
