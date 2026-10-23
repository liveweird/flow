package ch.nokillswit.auth

import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64

// The refresh rejection detail per audited reason — data beside the handler, not control flow
// in it. Every reason has its explicit entry; the handler's fallback is generic, so a typo in
// a reason string can never masquerade as a specific message.
internal val REFRESH_REJECT_MESSAGES = mapOf(
    "invalid_or_expired" to "Invalid or expired refresh token",
    "wrong_token_type" to "Not a refresh token",
    "revoked" to "Refresh token revoked",
    "replayed" to "Refresh token already used",
    "malformed" to "Malformed refresh token",
    "user_gone" to "User no longer exists",
    "credential_changed" to "Refresh token predates a password change",
)

/** Numeric security claims are accepted only as integral JSON numbers. Auth0's generic claim
 * coercion accepts some malformed representations, so inspect the signed payload directly. */
internal fun DecodedJWT.strictLongClaim(name: String): Long? = try {
    val payloadJson = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
    val primitive = Json.parseToJsonElement(payloadJson).jsonObject[name] as? JsonPrimitive
    if (primitive == null || primitive.isString) null else primitive.content.toLongOrNull()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
