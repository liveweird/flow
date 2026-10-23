package ch.nokillswit.auth

import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TokenBlocklistServiceKey = AttributeKey<TokenBlocklistService>("TokenBlocklistService")

/** How long one [TokenBlocklistService.isRevoked] verdict may be served from memory. */
internal const val REVOCATION_CACHE_TTL_MS = 30_000L

// Prune the cache opportunistically once it grows past this many entries.
private const val CACHE_PRUNE_THRESHOLD = 10_000

/**
 * The default for how long after a refresh token was rotated a replay of it is still answered with a
 * fresh pair (the lost-response grace, `security.refresh.replayGraceSeconds`). It must outlast the SPA's
 * 30 s request deadline: a `/refresh` response that never arrived leaves the client holding the old
 * token, and its retry has to work. 0 turns the grace off (strict one-time use).
 */
internal const val REFRESH_REPLAY_GRACE_MS = 60_000L

/** What `/refresh` may do with the presented token once it has tried to claim it. */
enum class RefreshClaim {
    /** This call blocklisted the token: mint a pair. */
    CLAIMED,

    /** Rotated less than the grace window ago by this instance, at most [MAX_GRACE_REUSES] times: mint a pair again. */
    GRACE_REPLAY,

    /** Rotated longer ago, or the grace's re-mints are used up (and still remembered): a replay. */
    REPLAYED,

    /** Blocklisted by something else, or the rotation is not remembered (logout, restart, full record): revoked. */
    REVOKED,
}

class TokenBlocklistService(
    private val database: R2dbcDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val replayGraceMillis: Long = REFRESH_REPLAY_GRACE_MS,
) {
    object RevokedTokens : Table("revoked_tokens") {
        val jti = varchar("jti", 36)
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(jti)
    }

    private data class CachedVerdict(val revoked: Boolean, val staleAt: Long)

    // isRevoked runs inside JWT validation on EVERY authenticated request; this cache keeps
    // that from being a DB round-trip each time. Verdicts live at most REVOCATION_CACHE_TTL_MS,
    // and revoke() seeds the revoked verdict directly — so on this (single-replica) deployment
    // a logout is visible immediately, and the TTL only bounds staleness across a restart or a
    // hypothetical second replica. Caching a stale "revoked" is always safe (revocation is
    // permanent); the TTL is what bounds a stale "not revoked".
    private val cache = ConcurrentHashMap<String, CachedVerdict>()

    // Which refresh jtis this instance rotated (auth/RefreshRotations.kt): drives the replay grace and
    // tells a replay from a logout revocation. In memory, per instance, bounded; a restart loses it.
    private val rotations = RefreshRotations(clock, replayGraceMillis)

    /** True while [jti] was rotated by this instance less than the grace window ago with a re-mint left. */
    fun inReplayGrace(jti: String): Boolean = rotations.inGrace(jti)

    /** True when this instance remembers rotating [jti] (inside or outside the grace). */
    fun wasRotated(jti: String): Boolean = rotations.remembers(jti)

    /** Logout ends the grace of every refresh token this instance rotated for [userId]: a later replay is `revoked`. */
    fun forgetRotations(userId: Long) = rotations.forget(userId)

    /**
     * The rotation step of `/refresh`: the atomic claim of [jti] (see [insertRevocation]), then the verdict.
     * Only the call that inserted the row is [RefreshClaim.CLAIMED]; the rotation is recorded inside the
     * claim's transaction, before its commit, so a racing loser is deterministically answered by the grace.
     * A replay is [RefreshClaim.GRACE_REPLAY] inside the grace and [RefreshClaim.REPLAYED]/[RefreshClaim.REVOKED] beyond it.
     */
    suspend fun claimRefresh(jti: String, expiresAtEpochMillis: Long, userId: Long): RefreshClaim =
        if (insertRevocation(jti, expiresAtEpochMillis) { rotations.record(jti, userId, expiresAtEpochMillis) }) {
            RefreshClaim.CLAIMED
        } else {
            rotations.replay(jti)
        }

    suspend fun revoke(jti: String, expiresAtEpochMillis: Long) {
        revokeIfAbsent(jti, expiresAtEpochMillis)
    }

    /**
     * Blocklists [jti] and reports whether THIS call inserted the row. The insert is one
     * `INSERT .. ON CONFLICT DO NOTHING` on the jti primary key, so of any number of concurrent
     * callers with the same jti exactly one gets `true` — the atomic "check not revoked + revoke"
     * that refresh-token rotation needs (`/refresh` treats `false` as a replay). A `false` also
     * seeds the revoked verdict: the row exists, whoever wrote it.
     */
    suspend fun revokeIfAbsent(jti: String, expiresAtEpochMillis: Long): Boolean =
        insertRevocation(jti, expiresAtEpochMillis) {}

    // [onInserted] runs inside the transaction, right after this call's insert took the row and before the commit.
    private suspend fun insertRevocation(jti: String, expiresAtEpochMillis: Long, onInserted: () -> Unit): Boolean {
        val inserted = suspendTransaction(database) {
            RevokedTokens.deleteWhere { expiresAt less System.currentTimeMillis() }
            val inserted = RevokedTokens.insertIgnore {
                it[RevokedTokens.jti] = jti
                it[RevokedTokens.expiresAt] = expiresAtEpochMillis
            }.insertedCount > 0
            if (inserted) onInserted()
            inserted
        }
        cache[jti] = CachedVerdict(revoked = true, staleAt = clock() + REVOCATION_CACHE_TTL_MS)
        return inserted
    }

    suspend fun isRevoked(jti: String): Boolean {
        val now = clock()
        cache[jti]?.takeIf { it.staleAt > now }?.let { return it.revoked }
        val revoked = suspendTransaction(database) {
            RevokedTokens.selectAll()
                .where { RevokedTokens.jti eq jti }
                .count() > 0
        }
        cache[jti] = CachedVerdict(revoked, staleAt = now + REVOCATION_CACHE_TTL_MS)
        if (cache.size > CACHE_PRUNE_THRESHOLD) {
            cache.entries.removeIf { it.value.staleAt <= now }
        }
        return revoked
    }
}
