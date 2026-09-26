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

class TokenBlocklistService(
    private val database: R2dbcDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
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

    suspend fun revoke(jti: String, expiresAtEpochMillis: Long) {
        suspendTransaction(database) {
            RevokedTokens.deleteWhere { expiresAt less System.currentTimeMillis() }
            RevokedTokens.insertIgnore {
                it[RevokedTokens.jti] = jti
                it[RevokedTokens.expiresAt] = expiresAtEpochMillis
            }
        }
        cache[jti] = CachedVerdict(revoked = true, staleAt = clock() + REVOCATION_CACHE_TTL_MS)
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
