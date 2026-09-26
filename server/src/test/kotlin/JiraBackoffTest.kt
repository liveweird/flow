package ch.nokillswit

import ch.nokillswit.jira.JiraBackoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure backoff math (v0.2.0 plan §6): `min(30s, 2s·2^n) × U(0.7,1.3)`, `Retry-After` precedence + clamp. Injected [random]. */
class JiraBackoffTest {

    @Test
    fun `Retry-After takes precedence over the exponential formula`() {
        assertEquals(5_000L, JiraBackoff.delayMillis(attempt = 0, retryAfterSeconds = 5))
        assertEquals(5_000L, JiraBackoff.delayMillis(attempt = 4, retryAfterSeconds = 5), "Retry-After ignores the attempt count")
    }

    @Test
    fun `Retry-After is clamped to 120s and never negative`() {
        assertEquals(120_000L, JiraBackoff.delayMillis(attempt = 0, retryAfterSeconds = 999))
        assertEquals(0L, JiraBackoff.delayMillis(attempt = 0, retryAfterSeconds = -10))
    }

    @Test
    fun `exponential backoff doubles per attempt and is capped at 30s before jitter`() {
        assertEquals(1_400L, JiraBackoff.delayMillis(attempt = 0, retryAfterSeconds = null, random = { 0.0 }))
        assertEquals(2_800L, JiraBackoff.delayMillis(attempt = 1, retryAfterSeconds = null, random = { 0.0 }))
        assertEquals(5_600L, JiraBackoff.delayMillis(attempt = 2, retryAfterSeconds = null, random = { 0.0 }))
        // 2s * 2^10 = 2048s, far past the 30s cap.
        assertEquals(21_000L, JiraBackoff.delayMillis(attempt = 10, retryAfterSeconds = null, random = { 0.0 }))
    }

    @Test
    fun `jitter stays within U(0,7 dot 1,3) at every attempt`() {
        (0..6).forEach { attempt ->
            val base = minOf(30_000L, 2_000L * (1L shl attempt))
            val low = JiraBackoff.delayMillis(attempt, null, random = { 0.0 })
            val high = JiraBackoff.delayMillis(attempt, null, random = { 0.999999 })
            assertEquals((base * 0.7).toLong(), low, "attempt $attempt floor")
            assertTrue(high <= (base * 1.3).toLong(), "attempt $attempt ceiling: $high")
            assertTrue(low <= high)
        }
    }
}
