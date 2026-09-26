package ch.nokillswit

import ch.nokillswit.infra.crypto.FieldCipher
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure unit tests for the AES-256-GCM field cipher (no container, no Ktor). */
class FieldCipherTest {

    private val keyA = "0000000000000000000000000000000000000000000000000000000000000001"
    private val keyB = "0000000000000000000000000000000000000000000000000000000000000002"
    private val cipher = FieldCipher(keyA)

    @Test
    fun `roundtrip preserves content including unicode and empty string`() {
        for (plain in listOf("secret-password", "zażółć gęślą jaźń 🥬", "")) {
            val enveloped = cipher.encrypt(plain)
            assertTrue(enveloped.startsWith(FieldCipher.PREFIX))
            assertEquals(plain, cipher.decrypt(enveloped))
        }
    }

    @Test
    fun `every encryption uses a fresh nonce`() {
        // Identical plaintexts must not produce identical ciphertexts, or the DB would leak equality.
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `legacy plaintext passes through decrypt unchanged`() {
        assertEquals("pre-encryption row", cipher.decrypt("pre-encryption row"))
    }

    @Test
    fun `tampered ciphertext fails to decrypt`() {
        val enveloped = cipher.encrypt("integrity matters")
        // Flip the last base64 char (part of the GCM tag) — authentication must fail.
        val tampered = enveloped.dropLast(2) + if (enveloped[enveloped.length - 2] == 'A') "B=" else "A="
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(tampered) }
    }

    @Test
    fun `a wrong key fails cleanly`() {
        val enveloped = cipher.encrypt("for key A only")
        assertFailsWith<GeneralSecurityException> { FieldCipher(keyB).decrypt(enveloped) }
    }

    @Test
    fun `rotation - the previous key is a decrypt-only fallback`() {
        val oldValue = FieldCipher(keyA).encrypt("written under the old key")
        val rotated = FieldCipher(keyB, previousKeyHex = keyA)
        // Reads both generations…
        assertEquals("written under the old key", rotated.decrypt(oldValue))
        assertEquals("fresh", rotated.decrypt(rotated.encrypt("fresh")))
        // …but writes only under the current key: key A alone cannot read new ciphertext.
        assertFailsWith<GeneralSecurityException> { FieldCipher(keyA).decrypt(rotated.encrypt("new")) }
    }

    @Test
    fun `malformed keys are rejected`() {
        for (bad in listOf("", "abc", "z".repeat(64), keyA + "00")) {
            assertFailsWith<IllegalArgumentException> { FieldCipher(bad) }
        }
    }

    @Test
    fun `a truncated envelope is rejected`() {
        assertFailsWith<IllegalArgumentException> { cipher.decrypt("${FieldCipher.PREFIX}AAAA") }
    }
}
