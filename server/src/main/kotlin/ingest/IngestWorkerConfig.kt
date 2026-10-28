package ch.nokillswit.ingest

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import io.ktor.server.config.ApplicationConfig
import java.net.InetAddress
import java.util.UUID

data class IngestConfig(
    val schedulerTickSeconds: Long,
    val workerSlots: Int,
    val leaseSeconds: Long,
    val jobRetentionDays: Long,
    val purgeGraceDays: Long,
    val workerId: String,
    /**
     * The wall-clock ceiling of one SYNC/RECONCILE run, in minutes (`ingest.maxJobMinutes`, [DEFAULT_MAX_JOB_MINUTES]): the run stops
     * between pages with `TIME_EXCEEDED` and the next one resumes from the cursors (`SyncPause.kt`).
     */
    val maxJobMinutes: Long = DEFAULT_MAX_JOB_MINUTES,
    /** No-progress SYNC ceiling trips in a row that pause the connection (`ingest.pauseAfterLimitTrips`, default 5). */
    val pauseAfterLimitTrips: Int = DEFAULT_PAUSE_AFTER_LIMIT_TRIPS,
)

/**
 * 60 minutes: about 3x the slowest full backfill measured on the real tenant (22 min before the fetch-ahead work, ~5 min after it for
 * the largest project), so a healthy run never meets it, while a run that grinds on for an hour is a bug or an outage worth stopping
 * (its cursors keep every page, and the next run continues from them). The stub's SYNC takes seconds.
 */
internal const val DEFAULT_MAX_JOB_MINUTES = 60L

/** 5 no-progress trips in a row: each is the same futile run repeating (the back-off spaces them), so five is evidence, not noise. */
internal const val DEFAULT_PAUSE_AFTER_LIMIT_TRIPS = 5

internal fun readIngestConfig(config: ApplicationConfig): IngestConfig {
    val tick = requireConfigLong(config, "ingest.schedulerTickSeconds", min = 5, max = 300)
    val slots = requireConfigInt(config, "ingest.workerSlots", min = 1, max = 16)
    val lease = requireConfigLong(config, "ingest.leaseSeconds", min = 30, max = 3600)
    val retention = requireConfigLong(config, "ingest.jobRetentionDays", min = 1, max = 3650)
    val grace = requireConfigLong(config, "ingest.purgeGraceDays", min = 0, max = 90)
    val maxJobMinutes = requireConfigLong(config, "ingest.maxJobMinutes", min = 5, max = 1440)
    val pauseAfterTrips = requireConfigInt(config, "ingest.pauseAfterLimitTrips", min = 1, max = 100)
    val configuredWorkerId = config.propertyOrNull("ingest.workerId")?.getString()?.trim().orEmpty()
    return IngestConfig(
        tick, slots, lease, retention, grace, configuredWorkerId.ifBlank { defaultWorkerId() }, maxJobMinutes, pauseAfterTrips,
    )
}

/**
 * A running DERIVE holds TWO pooled connections at once — its own big transaction plus the short
 * `MetricsStore.ensureDimDate` transaction opened from inside it — so `workerSlots` concurrent derives
 * can pin `2 × workerSlots` connections. At exactly that many the derives still fit, but nothing is left
 * for requests, job heartbeats or the claim scan, so the pool must be strictly larger. Fail-closed at
 * boot, only for a role that runs the worker (`.claude/docs/persistence.md` "Connection pool").
 *
 * Abandoned lease renewals are deliberately NOT added to this bound: an attempt the ticker gave up on (at its slack) is
 * bounded server-side by about a slack too, and attempts are spaced by `retry = max(1s, interval/2) > slack`, so at most
 * one abandoned renewal per slot is outstanding at a time. If a stall ever eats the headroom, the next renewal's
 * pool-acquire fails or times out, which the lease budget counts as a failed renewal — the job stops before its lease
 * runs out, the fail-closed outcome. Raise `postgres.pool.maxSize` rather than the formula.
 */
internal fun requirePoolFitsWorkerSlots(config: ApplicationConfig, workerSlots: Int) {
    val poolMaxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    check(poolMaxSize > 2 * workerSlots) {
        "postgres.pool.maxSize ($poolMaxSize) must be greater than 2 x ingest.workerSlots ($workerSlots): a running DERIVE " +
            "holds two pooled connections at once (its transaction plus the dim_date ensure), leaving no headroom for " +
            "requests and heartbeats"
    }
}

private fun defaultWorkerId(): String {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")
    return "$host-${UUID.randomUUID().toString().take(8)}"
}
