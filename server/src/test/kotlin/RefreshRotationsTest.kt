package ch.nokillswit

import ch.nokillswit.auth.MAX_GRACE_REUSES
import ch.nokillswit.auth.RefreshClaim
import ch.nokillswit.auth.RefreshRotations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The in-memory rotation records (auth/RefreshRotations.kt) alone: bounded, evicting what is no longer needed. */
class RefreshRotationsTest {

    @Test
    fun `a full map evicts records past their grace before dropping the new one`() {
        var now = 0L
        val rotations = RefreshRotations({ now }, graceMillis = 1_000, maxRecords = 2)
        rotations.record("a", 1, expiresAt = 1_000_000)
        rotations.record("b", 1, expiresAt = 1_000_000)

        // Full and both still inside their grace: the NEW record is dropped (strict for it), nothing else is lost.
        rotations.record("c", 1, expiresAt = 1_000_000)
        assertFalse(rotations.remembers("c"))
        assertTrue(rotations.remembers("a") && rotations.remembers("b"))

        // Once the grace has passed they may be evicted to make room.
        now = 1_000
        rotations.record("d", 1, expiresAt = 1_000_000)
        assertTrue(rotations.remembers("d"))
        assertEquals(RefreshClaim.REVOKED, rotations.replay("a"))
    }

    @Test
    fun `a record is dropped at its own expiry when room is needed and re-mints are capped`() {
        var now = 0L
        val rotations = RefreshRotations({ now }, graceMillis = 60_000, maxRecords = 1)
        rotations.record("a", 1, expiresAt = 500)
        repeat(MAX_GRACE_REUSES) { assertEquals(RefreshClaim.GRACE_REPLAY, rotations.replay("a")) }
        assertEquals(RefreshClaim.REPLAYED, rotations.replay("a"))

        now = 500
        rotations.record("b", 1, expiresAt = 100_000)
        assertTrue(rotations.remembers("b"))
        assertFalse(rotations.remembers("a"))
    }
}
