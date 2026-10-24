package ch.nokillswit.telemetry

import kotlinx.serialization.Serializable

/**
 * The telemetry read API's wire shapes (`GET /api/v1/telemetry/current` and `/history`, ADMIN only; `documentation.yaml`, the
 * `Telemetry*` schemas; `.claude/docs/observability.md` "Telemetry"). Instants are epoch millis, byte figures whole bytes, and a
 * figure the process could not measure (no container limit on macOS, no `memory.peak` on an old kernel) is null, never zero.
 */
@Serializable
data class TelemetryCurrent(
    val generatedAt: Long,
    /** The cadence of the raw samples (`telemetry.sampleIntervalSeconds`) - also the `requests` window. */
    val sampleIntervalSeconds: Int,
    val processes: List<TelemetryProcess>,
    /** The newest storage measurement; null until the cluster section has taken one. */
    val storage: TelemetryStorage?,
    val connections: List<TelemetryConnection>,
    val requests: TelemetryRequests,
)

/** One sampling process (a JVM run) with its latest raw sample. */
@Serializable
data class TelemetryProcess(
    val id: Int,
    val role: String,
    val instance: String,
    val startedAt: Long,
    val lastSeenAt: Long,
    /** The bucket of the sample the figures below come from; null when none is retained. */
    val sampledAt: Long?,
    /** No sample for more than three sample intervals: the process is gone or wedged. */
    val stale: Boolean,
    val version: String?,
    val commit: String?,
    val memory: TelemetryMemory,
    val jvm: TelemetryJvm,
)

@Serializable
data class TelemetryMemory(
    val heapUsedBytes: Long?,
    val heapCommittedBytes: Long?,
    val heapMaxBytes: Long,
    /** The old generation's peak over the latest sample interval. */
    val oldGenPeakBytes: Long?,
    val nonHeapBytes: Long?,
    val directBytes: Long?,
    val rssBytes: Long?,
    val cgroupCurrentBytes: Long?,
    val cgroupAnonBytes: Long?,
    /** `memory.current - inactive_file`: what the kubelet evicts on. */
    val workingSetBytes: Long?,
    /** The cgroup's LIFETIME peak. */
    val cgroupPeakBytes: Long?,
    val containerLimitBytes: Long?,
    /** `workingSetBytes / containerLimitBytes` in percent (0..100+, one decimal); null without a limit or a working set. */
    val percentOfLimit: Double?,
)

@Serializable
data class TelemetryJvm(
    /** Process CPU time over wall time as a fraction of the available processors (0..1). */
    val cpuRatio: Double?,
    val threads: Int?,
    /** Collections over the latest sample interval, all collectors summed. */
    val gcCount: Long?,
    val gcTimeMs: Long?,
    val poolAcquired: Int?,
    val poolIdle: Int?,
    val poolPending: Int?,
    val poolAllocated: Int?,
)

@Serializable
data class TelemetrySchemaSize(val name: String, val bytes: Long)

@Serializable
data class TelemetryStorage(val measuredAt: Long, val databaseBytes: Long, val schemas: List<TelemetrySchemaSize>)

/** One data source: its id and name (never its settings or token), its size estimate, sync freshness, queue, last runs and DERIVE. */
@Serializable
data class TelemetryConnection(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    /** Soft-deleted and awaiting its PURGE: the storage is still held. */
    val deleted: Boolean,
    /** Live issues in `raw.jira_issues` at the last estimate; null before the first estimate. */
    val issues: Long?,
    /** The estimated bytes the connection's rows take (row share x table size; `observability.md` "Telemetry"). */
    val estimatedBytes: Long?,
    val estimatedAt: Long?,
    val freshness: TelemetryFreshness,
    val queue: TelemetryQueue,
    val lastSync: TelemetrySyncRun?,
    val lastBackfill: TelemetryBackfill?,
    /** Jira 429 responses over the last 24 hours (finished SYNC jobs). */
    val rateLimited24h: Long,
    /** Milliseconds slept in Retry-After / back-off over the last 24 hours (finished SYNC jobs). */
    val backoffMs24h: Long,
    val derive: TelemetryDerive?,
)

@Serializable
data class TelemetryFreshness(
    val lastSyncSucceededAt: Long?,
    val consecutiveFailures: Int,
    val lastSyncErrorCode: String?,
    val nextSyncAt: Long?,
)

@Serializable
data class TelemetryQueue(val pending: Long, val running: Long)

@Serializable
data class TelemetrySyncRun(val startedAt: Long?, val finishedAt: Long?, val status: String)

@Serializable
data class TelemetryBackfill(val startedAt: Long?, val finishedAt: Long?, val durationMs: Long?, val status: String)

@Serializable
data class TelemetryDerive(
    val lastFinishedAt: Long,
    val lastDurationMs: Long,
    /** SUCCEEDED or FAILED. */
    val lastStatus: String,
    /** The longest DERIVE run that ended in the last 7 days. */
    val peakDurationMs: Long?,
    /** Rows written by the last run (the sum of its `row_counts`); null for a run that recorded none. */
    val rowsWritten: Long?,
)

@Serializable
data class TelemetryRequests(
    /** The counts' window: the sample interval. */
    val windowSeconds: Int,
    /** The window p50 / p95 cover: the sample interval capped at 300 s (the timers' reservoir); can be shorter than [windowSeconds]. */
    val percentileWindowSeconds: Int,
    /** The sample bucket the figures describe; null when no web process has a recent sample. */
    val measuredAt: Long?,
    /** Every `/api/` route but the telemetry's own and the probes; zeros when nothing was served. */
    val total: TelemetryRouteStats,
    /** The busiest report routes of the interval, by count, at most 15. */
    val routes: List<TelemetryRouteStats>,
)

@Serializable
data class TelemetryRouteStats(
    val route: String,
    val count: Long,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val serverErrors: Long,
    val rateLimited: Long,
)

@Serializable
data class TelemetryHistory(
    val group: String,
    val range: String,
    val bucketSeconds: Int,
    val from: Long,
    val to: Long,
    /** The oldest instant the range can actually hold data for (a fresh install, or job retention shorter than the range). */
    val historyFrom: Long,
    /** More series matched than were returned (40; sync, derive and storage: 25 connections), or connections were left out whole. */
    val truncated: Boolean,
    /** `sync` / `derive` / `storage` only: the connections left out whole (least active, or smallest) past 25; empty otherwise. */
    val omittedConnectionIds: List<Long>,
    val series: List<TelemetrySeries>,
)

/** One line of a chart: [points] are the buckets that have data, ascending. */
@Serializable
data class TelemetrySeries(
    val key: String,
    val metric: String,
    /** The sample label (a route, a GC collector, a schema, a connection id); empty when the metric has none. */
    val label: String,
    /** The process role a per-process series merges (web|worker|all); null for cluster-wide and per-connection series. */
    val role: String?,
    val connectionId: Long?,
    /** BYTES | MILLISECONDS | COUNT | RATIO. */
    val unit: String,
    /** GAUGE | DELTA | PERCENTILE. */
    val kind: String,
    val points: List<TelemetryPoint>,
)

/**
 * One bucket starting at [t]. A GAUGE or PERCENTILE point carries the bucket's mean ([avg], n-weighted) and its largest sample
 * ([max]); a DELTA point's [avg] is the bucket's TOTAL (the sum of the interval deltas) and [max] the largest single interval. The
 * read-time job series (`sync.*`, `derive.*`) have no finer grain, so a count's [max] EQUALS its [avg], the bucket total.
 */
@Serializable
data class TelemetryPoint(val t: Long, val avg: Double, val max: Double)
