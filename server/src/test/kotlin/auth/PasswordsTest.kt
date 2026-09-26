package ch.nokillswit.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for the bcrypt helpers (no server, no database). */
class PasswordsTest {

    @Test
    fun `hash and verify round-trip`() = runBlocking<Unit> {
        val hash = hashPassword("s3cret-enough", cost = 4)
        assertTrue(verifyPassword("s3cret-enough", hash))
        assertFalse(verifyPassword("something-else", hash))
    }

    @Test
    fun `verification treats over-long input as never matching instead of throwing`() = runBlocking<Unit> {
        val hash = hashPassword("short", cost = 4)
        // 200 ASCII chars = 200 bytes, over bcrypt's 71-byte ceiling: at.favre's strict
        // strategy would throw — verifyPassword must swallow that into a plain mismatch.
        assertFalse(verifyPassword("x".repeat(200), hash))
    }

    @Test
    fun `byte limit counts UTF-8 bytes not characters`() {
        // 24 four-byte emoji = 96 bytes from 24 characters.
        assertTrue(exceedsBcryptLimit("🐸".repeat(24)))
        assertFalse(exceedsBcryptLimit("x".repeat(71)))
        assertTrue(exceedsBcryptLimit("x".repeat(72)))
    }
}
