package ch.nokillswit.ingest

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE

/** Today's `reconcileHourUtc` boundary (UTC), as epoch millis. */
internal fun todayReconcileBoundary(reconcileHourUtc: Int, now: Long): Long {
    val date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return date.atTime(reconcileHourUtc, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
}

/**
 * True once today's `reconcileHourUtc` boundary has passed AND the last reconcile predates it —
 * so a worker outage spanning the boundary still catches up the same day, and a job already run
 * today is not re-enqueued (`ingest/DataSourceService.kt`'s `dueForReconcile`, polled every
 * `ingest.schedulerTickSeconds`). A failed run (which never stamps [lastReconcileAt]) would
 * otherwise stay due on every tick, so a pending failure back-off ([nextRetryAt], the
 * `next_reconcile_at` column) also holds the connection back until it has elapsed; the catch-up
 * property is unchanged, the retry is merely delayed.
 */
internal fun reconcileDue(lastReconcileAt: Long?, reconcileHourUtc: Int, now: Long, nextRetryAt: Long? = null): Boolean {
    val boundary = todayReconcileBoundary(reconcileHourUtc, now)
    return now >= boundary && (lastReconcileAt == null || lastReconcileAt < boundary) && (nextRetryAt == null || nextRetryAt <= now)
}

/** The ceiling of every failure back-off: SYNC's (`next_sync_at`) and RECONCILE's (`next_reconcile_at`). */
internal const val MAX_BACKOFF_MILLIS = 6L * 60 * 60 * 1000

/** The first RECONCILE retry delay; [backoffMillis] doubles it per further failure (15 m, 30 m, 1 h, 2 h, 4 h, then the cap). */
internal const val RECONCILE_RETRY_BASE_MILLIS = 15 * MILLIS_PER_MINUTE

/** `interval × 2^failures`, capped at [MAX_BACKOFF_MILLIS] — the shift is bounded so it can never overflow. */
internal fun backoffMillis(intervalMillis: Long, failures: Int): Long =
    minOf(intervalMillis * (1L shl minOf(failures, 32)), MAX_BACKOFF_MILLIS)
