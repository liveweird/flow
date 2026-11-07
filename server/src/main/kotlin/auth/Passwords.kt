package ch.nokillswit.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * bcrypt hashes at most 72 bytes including a null terminator, so a password may be at most
 * 71 UTF-8 bytes — longer input makes at.favre's strict strategy throw (a 500, and on login
 * an account-enumeration oracle: unknown emails short-circuit to 401 before hashing).
 * Enforced as 400 at the API boundary (see `validatePassword` in flowusers/FlowUserRoutes.kt) and
 * treated as never-matching in [verifyPassword].
 */
const val MAX_PASSWORD_BYTES = 71

internal fun exceedsBcryptLimit(plain: String): Boolean =
    plain.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES

// Both bcrypt entry points hop to Dispatchers.Default: at cost 12 a hash/verify is hundreds
// of milliseconds of pure CPU, which must not occupy a request-dispatcher thread.
internal suspend fun hashPassword(plain: String, cost: Int = 12): String =
    withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(cost, plain.toCharArray()) }

internal suspend fun verifyPassword(plain: String, hash: String): Boolean =
    !exceedsBcryptLimit(plain) &&
        withContext(Dispatchers.Default) { BCrypt.verifyer().verify(plain.toCharArray(), hash).verified }

/**
 * A real cost-12 hash of a throwaway string, verified against when a login's email matches no
 * account: the unknown-email branch must pay the same bcrypt price as the wrong-password one,
 * or the fast 401 becomes a timing oracle distinguishing unknown from existing accounts.
 * The verify result is always discarded — this hash can never authenticate anyone.
 */
internal const val TIMING_EQUALIZER_HASH = "\$2a\$12\$x7ALsPyiaDt.5FQcrfQSlOmsT7xycnGCanTeSTKCFqcBWW5K43GO6"

private val secureRandom = SecureRandom()

/** The 6-digit email-MFA code (leading zeros kept) — guess-resistance comes from the challenge
 *  attempt cap, not the code length (see auth/MfaChallenges.kt). */
internal fun generateMfaCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

private const val OPAQUE_TOKEN_BYTES = 32

/**
 * An opaque bearer secret: 32 `SecureRandom` bytes (256 bits) as unpadded base64url, always 43 characters of
 * `[A-Za-z0-9_-]`. The password-reset link token and the service-token body (`integration/`, prefixed `flow_int_`) are both
 * minted here; only the SHA-256 digest ([ch.nokillswit.infra.json.sha256Hex]) of either is ever stored.
 */
internal fun generateOpaqueToken(): String {
    val bytes = ByteArray(OPAQUE_TOKEN_BYTES).also(secureRandom::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
