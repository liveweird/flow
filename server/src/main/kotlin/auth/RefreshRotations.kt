package ch.nokillswit.auth

/** Hard limit on remembered rotations; when full (after evicting what is no longer needed) a new one is not recorded. */
internal const val MAX_ROTATION_RECORDS = 10_000

/** How many times one rotated token may get another pair inside the grace (a lost response or a racing tab needs one). */
internal const val MAX_GRACE_REUSES = 2

/**
 * The in-memory half of refresh-token rotation (`TokenBlocklistService.claimRefresh`): which refresh `jti`s THIS
 * instance rotated, for whom and when. It drives the replay grace (a replay of a token rotated less than
 * [graceMillis] ago, at most [MAX_GRACE_REUSES] times, is answered with another pair) and tells a replay
 * (`replayed`) from a logout revocation (`revoked`). Per instance and in memory like the lockout/MFA state
 * (single web replica); a restart forgets everything and the behaviour falls back to strict.
 *
 * A record lives until the token's own expiry (past that the token fails verification anyway). The map is
 * capacity-bounded: when full, records that are expired or past their grace are evicted first, and if it is
 * still full the NEW record is dropped (strict for that token), so it can never grow without bound.
 */
internal class RefreshRotations(
    private val clock: () -> Long,
    private val graceMillis: Long,
    private val maxRecords: Int = MAX_ROTATION_RECORDS,
) {
    private class Rotation(val userId: Long, val rotatedAt: Long, val expiresAt: Long) {
        var graceReuses = 0
    }

    private val records = HashMap<String, Rotation>()

    /**
     * Remembers that [jti] was rotated for [userId] now. Called inside the claim's transaction, BEFORE it
     * commits, so a racing loser (whose `ON CONFLICT` only resumes after that commit) always finds it. If the
     * commit then fails the record is stale, but it only matters once a row exists, and the next successful
     * claim of the same jti overwrites it.
     */
    fun record(jti: String, userId: Long, expiresAt: Long) = synchronized(records) {
        val now = clock()
        if (records.size >= maxRecords) {
            records.values.removeIf { it.expiresAt <= now || now - it.rotatedAt >= graceMillis }
        }
        if (records.size < maxRecords) records[jti] = Rotation(userId, now, expiresAt)
    }

    /** True while [jti] is inside its grace with a re-mint left. Does not consume one. */
    fun inGrace(jti: String): Boolean = synchronized(records) {
        records[jti]?.let { withinGrace(it, clock()) } == true
    }

    /** True when this instance remembers rotating [jti], inside or outside the grace. */
    fun remembers(jti: String): Boolean = synchronized(records) { records.containsKey(jti) }

    /**
     * The verdict for a replay of an already-blocklisted [jti]; a [RefreshClaim.GRACE_REPLAY] consumes one of
     * the token's re-mints. [RefreshClaim.REVOKED] when the rotation is not remembered.
     */
    fun replay(jti: String): RefreshClaim = synchronized(records) {
        val rotation = records[jti] ?: return RefreshClaim.REVOKED
        if (!withinGrace(rotation, clock())) return RefreshClaim.REPLAYED
        rotation.graceReuses += 1
        RefreshClaim.GRACE_REPLAY
    }

    /** Forgets every rotation of [userId] (a logout ends the grace: a later replay is plain `revoked`). */
    fun forget(userId: Long) = synchronized(records) {
        records.values.removeIf { it.userId == userId }
    }

    private fun withinGrace(rotation: Rotation, now: Long) =
        now - rotation.rotatedAt < graceMillis && rotation.graceReuses < MAX_GRACE_REUSES
}
