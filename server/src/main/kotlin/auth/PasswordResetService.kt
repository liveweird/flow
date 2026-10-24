package ch.nokillswit.auth

import ch.nokillswit.flowusers.FlowUserService.FlowUsers
import ch.nokillswit.flowusers.updatePasswordInTransaction
import ch.nokillswit.infra.db.active
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val PasswordResetServiceKey = AttributeKey<PasswordResetService>("PasswordResetService")

private const val RESET_TOKEN_BYTES = 32
private const val DEFAULT_RESET_TTL_MILLIS = 900_000L
private const val MAX_RESET_TTL_MILLIS = 3_600_000L
private val resetRandom = SecureRandom()

/** The shape of an emailed token: 32 random bytes as unpadded base64url. Junk is rejected before any database hit. */
internal val RESET_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")

/**
 * The single-use password-reset grants (V35, 2D3; Toadie's design). Only the SHA-256 digest of the emailed token is
 * stored, so a database read cannot replay a link, and lookup is SQL equality on the digest of a 256-bit random value
 * (no constant-time compare needed). A grant authorizes one thing: replacing ONE account's password. It dies on use
 * (every grant of the user), a password change (its captured `credential_revision` no longer matches), an email change
 * (its captured `email` no longer matches), an account soft delete and expiry. A newer request does NOT revoke an
 * earlier usable link, or anyone typing an address into the form could invalidate the owner's link.
 */
class PasswordResetService(
    private val database: R2dbcDatabase,
    val ttlMillis: Long = DEFAULT_RESET_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(ttlMillis in 1..MAX_RESET_TTL_MILLIS) { "Password reset TTL must be positive and at most one hour" } }

    object Grants : Table("password_reset_tokens") {
        val tokenHash = varchar("token_hash", 64)
        val userId = reference("user_id", FlowUsers)
        val email = varchar("email", 254)
        val credentialRevision = long("credential_revision")
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(tokenHash)
    }

    /** Mints a grant for the account as it is NOW (revision and email); null when the account moved or is gone. */
    suspend fun issue(userId: UInt, credentialRevision: Long, email: String): String? {
        // A separate cleanup transaction preserves the user-before-grant lock ordering.
        suspendTransaction(database) { Grants.deleteWhere { expiresAt lessEq clock() } }
        val bytes = ByteArray(RESET_TOKEN_BYTES).also(resetRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return suspendTransaction(database) {
            if (!lockUser(userId, credentialRevision, email)) return@suspendTransaction null
            Grants.insert {
                it[tokenHash] = digest(token)
                it[Grants.userId] = userId
                it[Grants.email] = email
                it[Grants.credentialRevision] = credentialRevision
                it[expiresAt] = clock() + ttlMillis
            }
            token
        }
    }

    /** Cheap precheck before bcrypt; [complete] rechecks everything inside its transaction. */
    suspend fun isUsable(token: String): Boolean {
        if (!RESET_TOKEN_PATTERN.matches(token)) return false
        return suspendTransaction(database) {
            (Grants innerJoin FlowUsers).select(Grants.tokenHash).where {
                (Grants.tokenHash eq digest(token)) and (Grants.expiresAt greater clock()) and FlowUsers.active() and
                    (Grants.credentialRevision eq FlowUsers.credentialRevision) and (Grants.email eq FlowUsers.email)
            }.count() == 1L
        }
    }

    /** Consumption, password replacement and sibling revocation are ONE transaction, across instances. */
    suspend fun complete(token: String, passwordHash: String): UInt? {
        if (!RESET_TOKEN_PATTERN.matches(token)) return null
        val hash = digest(token)
        return suspendTransaction(database) {
            val grant = Grants.selectAll().where { Grants.tokenHash eq hash }.toList().singleOrNull()
                ?: return@suspendTransaction null
            val userId = grant[Grants.userId].value
            val revision = grant[Grants.credentialRevision]
            if (!lockUser(userId, revision, grant[Grants.email])) return@suspendTransaction null
            // A competing completion or a delivery-failure cleanup may have removed the row.
            if (Grants.deleteWhere { (tokenHash eq hash) and (expiresAt greater clock()) } != 1) {
                return@suspendTransaction null
            }
            check(updatePasswordInTransaction(userId, passwordHash, revision, clock()) == 1)
            Grants.deleteWhere { Grants.userId eq userId }
            userId
        }
    }

    suspend fun revoke(token: String) {
        suspendTransaction(database) { Grants.deleteWhere { tokenHash eq digest(token) } }
    }

    // Plain FOR UPDATE (persistence.md "Row locks vs. FK locks"): `users` is referenced only by columns no long transaction
    // inserts (`updated_by_*`, `teams.manager_user_id`, `user_disabled_features.user_id`, `sync_jobs.requested_by_user_id`;
    // this table's own inserts are short), so DERIVE cannot stall behind it - FlowUserService.lockedActiveAdminCount is the
    // precedent. The lock serializes issue/complete against a concurrent password or email write on the same account.
    private suspend fun lockUser(userId: UInt, credentialRevision: Long, email: String): Boolean =
        FlowUsers.select(FlowUsers.id).where {
            (FlowUsers.id eq userId) and FlowUsers.active() and
                (FlowUsers.credentialRevision eq credentialRevision) and (FlowUsers.email eq email)
        }.forUpdate().toList().isNotEmpty()

    private fun digest(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
}
