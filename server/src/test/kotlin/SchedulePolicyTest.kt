import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.MAX_BACKOFF_MILLIS
import ch.nokillswit.ingest.RECONCILE_RETRY_BASE_MILLIS
import ch.nokillswit.ingest.backoffMillis
import ch.nokillswit.ingest.reconcileDue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ingest/SchedulePolicy.kt`: the pure scheduling rules (`.claude/docs/ingestion.md` "Worker scheduler"). */
class SchedulePolicyTest {
    private val boundary = Instant.parse("2024-01-01T03:00:00Z").toEpochMilli()
    private val now = boundary + 2 * 60 * MILLIS_PER_MINUTE // 05:00, two hours past the 03:00 boundary
    private val yesterday = boundary - 60 * MILLIS_PER_MINUTE

    @Test
    fun `reconcileDue is false before the boundary and true after it for a never-reconciled connection`() {
        assertFalse(reconcileDue(null, 3, boundary - 1))
        assertTrue(reconcileDue(null, 3, boundary))
    }

    @Test
    fun `reconcileDue is false once a reconcile succeeded since the boundary`() {
        assertFalse(reconcileDue(boundary + 1, 3, now))
        assertTrue(reconcileDue(yesterday, 3, now), "a worker outage spanning the boundary still catches up the same day")
    }

    @Test
    fun `reconcileDue waits out a pending failure back-off and is due once it has elapsed`() {
        assertTrue(reconcileDue(yesterday, 3, now, nextRetryAt = null))
        assertFalse(reconcileDue(yesterday, 3, now, nextRetryAt = now + 1), "back-off in the future holds the connection back")
        assertTrue(reconcileDue(yesterday, 3, now, nextRetryAt = now), "back-off elapsed exactly now")
        assertTrue(reconcileDue(yesterday, 3, now, nextRetryAt = now - 1), "back-off in the past")
        assertFalse(reconcileDue(null, 3, boundary - 1, nextRetryAt = null), "the back-off never makes a not-yet-due reconcile due")
        assertFalse(reconcileDue(boundary + 1, 3, now, nextRetryAt = now - 1), "nor one already reconciled today")
    }

    @Test
    fun `the reconcile retry back-off runs 15 minutes, 30 minutes, 1 hour, 2 hours, 4 hours, then the six hour cap`() {
        val hour = 60 * MILLIS_PER_MINUTE
        val sequence = (0..7).map { backoffMillis(RECONCILE_RETRY_BASE_MILLIS, it) }
        val expected = listOf(15 * MILLIS_PER_MINUTE, 30 * MILLIS_PER_MINUTE, hour, 2 * hour, 4 * hour)
        assertEquals(expected + List(3) { MAX_BACKOFF_MILLIS }, sequence)
        assertEquals(6 * hour, MAX_BACKOFF_MILLIS)
    }
}
