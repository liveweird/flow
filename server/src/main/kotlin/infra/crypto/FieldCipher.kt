package ch.nokillswit.infra.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-level field encryption (AES-256-GCM) for sensitive DB columns, so a database-level
 * attacker (SQL access, pg_dump, stolen volume/backup) sees only ciphertext. The key lives with
 * the app (env var / k8s Secret), never in the database.
 *
 * Envelope format: `enc:v1:<base64(nonce || ciphertext+tag)>` — a fresh random 12-byte nonce per
 * value, 128-bit tag. Values without the prefix are treated as legacy plaintext and returned
 * unchanged by [decrypt]; the startup backfill (see infra/db/Bootstrap.kt) converts them.
 *
 * [previousKeyHex] is a decrypt-only fallback for key rotation: [decrypt] tries the current key
 * first (GCM's auth tag makes a wrong-key attempt fail cleanly), then the previous one. While it
 * is set, the startup backfill re-encrypts every row under the current key.
 */
class FieldCipher(currentKeyHex: String, previousKeyHex: String? = null) {
    private val currentKey = parseKey(currentKeyHex)
    private val previousKey = previousKeyHex?.takeIf { it.isNotBlank() }?.let { parseKey(it) }
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, currentKey, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + ciphertext)
    }

    /** Decrypts an enveloped value; a non-enveloped (legacy plaintext) value is returned unchanged. */
    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size > NONCE_LENGTH) { "Truncated ciphertext envelope" }
        val nonce = bytes.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = bytes.copyOfRange(NONCE_LENGTH, bytes.size)
        return try {
            decryptWith(currentKey, nonce, ciphertext)
        } catch (e: GeneralSecurityException) {
            val fallback = previousKey ?: throw e
            decryptWith(fallback, nonce, ciphertext)
        }
    }

    private fun decryptWith(key: SecretKeySpec, nonce: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    companion object {
        const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_HEX_LENGTH = 64

        private fun parseKey(hex: String): SecretKeySpec {
            require(hex.length == KEY_HEX_LENGTH && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                "Data encryption key must be $KEY_HEX_LENGTH hex characters (32 bytes) — generate one with: openssl rand -hex 32"
            }
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return SecretKeySpec(bytes, "AES")
        }
    }
}
