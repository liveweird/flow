package ch.nokillswit.integration

import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Service tokens (V48): ADMIN-issued, read-only machine credentials for the `/api/v1/integration` reads (Astrolabe's
 * scheduled sync; `.claude/docs/persistence.md` "Service tokens (V48)"). A token is `flow_int_` plus 43 base64url characters
 * (256 bits of server randomness), shown once at creation; only its SHA-256 hex digest is stored.
 */
const val MAX_SERVICE_TOKEN_NAME_LENGTH = 100

/** The first nine characters of every service token: one secret-scanner rule covers the sibling family (`toadie_int_`, `lettuce_int_`). */
const val SERVICE_TOKEN_PREFIX = "flow_int_"

/** The whole bearer value: the prefix plus 43 unpadded base64url characters. A mismatch is rejected before any hashing or database work. */
val SERVICE_TOKEN_PATTERN = Regex("^flow_int_[A-Za-z0-9_-]{43}$")

/** The longest an expiry may lie ahead of "now": ten fixed 24-hour years. */
const val MAX_SERVICE_TOKEN_LIFETIME_MILLIS = 10 * 365 * MILLIS_PER_DAY

/** One registry row. `revoked` is the status: an expired but unrevoked token reads `revoked = false` (the page derives "expired"). */
@Serializable
data class ServiceToken(
    val id: UInt,
    val name: String,
    val createdAt: Long,
    val createdByName: String,
    val expiresAt: Long? = null,
    val lastUsedAt: Long? = null,
    val revoked: Boolean,
    val revokedAt: Long? = null,
    val revokedByName: String? = null,
)

/** The registry list: newest first, at most [SERVICE_TOKEN_LIST_LIMIT] rows (the `{items}` wrapper of a bounded, admin-curated set). */
@Serializable
data class ServiceTokenListResponse(val items: List<ServiceToken>)

/** `expiresAt` (epoch ms) is optional: omitted or null = the token never expires. */
@Serializable
data class ServiceTokenCreateRequest(
    val name: String,
    val expiresAt: Long? = null,
)

/** The one-time create response: the plaintext [secret] exists ONLY here; `toString()` masks it so a stray `"$response"` never logs it. */
@Serializable
data class ServiceTokenCreateResponse(
    val token: ServiceToken,
    val secret: String,
) {
    override fun toString(): String = "ServiceTokenCreateResponse(token=$token, secret=${maskedSecret(secret)})"
}

/**
 * The create-request rules, in the 400 wording of the other registries: the name trimmed (control characters a 400) and
 * 1..[MAX_SERVICE_TOKEN_NAME_LENGTH] characters; `expiresAt` null, or after [nowMs] and at most [MAX_SERVICE_TOKEN_LIFETIME_MILLIS] ahead
 * of it. Returns the sanitized name. The route runs it (403-before-400 ordering) and the service re-runs it, so direct callers are guarded.
 */
fun validateServiceTokenCreate(name: String, expiresAt: Long?, nowMs: Long): String {
    val sanitized = sanitizeSingleLine(name, "Name")
    if (sanitized.isEmpty() || sanitized.length > MAX_SERVICE_TOKEN_NAME_LENGTH) {
        throw BadRequestException("Name must be 1-$MAX_SERVICE_TOKEN_NAME_LENGTH characters")
    }
    if (expiresAt != null) {
        if (expiresAt <= nowMs) throw BadRequestException("expiresAt must be in the future")
        if (expiresAt > nowMs + MAX_SERVICE_TOKEN_LIFETIME_MILLIS) throw BadRequestException("expiresAt must be at most 10 years ahead")
    }
    return sanitized
}
