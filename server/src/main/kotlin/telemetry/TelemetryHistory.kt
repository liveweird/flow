package ch.nokillswit.telemetry

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsStore

/** The `range` query parameter: how far back a history reaches. */
internal enum class HistoryRange(val param: String, val millis: Long) {
    H24("24h", MILLIS_PER_DAY),
    D7("7d", 7 * MILLIS_PER_DAY),
    D90("90d", 90 * MILLIS_PER_DAY),
    ;

    companion object {
        fun parse(value: String): HistoryRange? = entries.firstOrNull { it.param == value }
    }
}

/** The `group` query parameter: one section of the panel. */
internal enum class HistoryGroup(val param: String) {
    MEMORY("memory"),
    STORAGE("storage"),
    SYNC("sync"),
    DERIVE("derive"),
    REQUESTS("requests"),
    JVM("jvm"),
    ;

    companion object {
        fun parse(value: String): HistoryGroup? = entries.firstOrNull { it.param == value }
    }
}

/** A series carries at most this many points, a response at most [MAX_SERIES] series. */
internal const val MAX_POINTS = 400
internal const val MAX_SERIES = 40

private const val HOUR_SECONDS = 3_600
private const val SIX_HOUR_SECONDS = 21_600
private const val DAY_SECONDS = 86_400

/**
 * The width of a history bucket in seconds: 24h reads the raw tier at the sample interval (widened to a whole multiple of it only
 * when the span would exceed [MAX_POINTS] points, i.e. an interval under 216 s), 7d the hourly tier (168 points) and 90d the hourly
 * tier folded to six hours (360 points).
 */
internal fun historyBucketSeconds(range: HistoryRange, intervalSeconds: Int): Int = when (range) {
    HistoryRange.H24 -> intervalSeconds * Math.ceilDiv(DAY_SECONDS, MAX_POINTS * intervalSeconds)
    HistoryRange.D7 -> HOUR_SECONDS
    HistoryRange.D90 -> SIX_HOUR_SECONDS
}

private val REGISTRY: Map<String, MetricDef> = TelemetryMetrics.all.associateBy { it.name }

/** Per-process metrics merge per ROLE; every other group's metrics are cluster-wide (whichever process held the lock wrote them). */
private val PER_PROCESS_GROUPS = setOf(MetricGroup.MEMORY, MetricGroup.JVM, MetricGroup.REQUESTS)
private val PER_CONNECTION_METRICS = setOf(TelemetryMetrics.CONN_ISSUES.name, TelemetryMetrics.CONN_BYTES_EST.name)

/** One finished-jobs bucket of a connection, as either job table reports it: [extra] is the 429 count (sync) or the rows written. */
private class JobBucket(val t: Long, val runs: Long, val failures: Long, val avgMs: Double?, val maxMs: Long?, val extra: Long)

/** A series derived at read time from the job tables (no sampler row behind it): its name, unit and kind. */
private class ReadTimeMetric(val name: String, val unit: MetricUnit, val kind: MetricKind, val dropWhenAllZero: Boolean = false)

private val SYNC_RUNS = ReadTimeMetric("sync.runs", MetricUnit.COUNT, MetricKind.DELTA)
private val SYNC_FAILURES = ReadTimeMetric("sync.failures", MetricUnit.COUNT, MetricKind.DELTA, dropWhenAllZero = true)
private val SYNC_DURATION = ReadTimeMetric("sync.duration_ms", MetricUnit.MILLISECONDS, MetricKind.GAUGE)
private val SYNC_RATE_LIMITED = ReadTimeMetric("sync.rate_limited", MetricUnit.COUNT, MetricKind.DELTA, dropWhenAllZero = true)
private val DERIVE_RUNS = ReadTimeMetric("derive.runs", MetricUnit.COUNT, MetricKind.DELTA)
private val DERIVE_FAILURES = ReadTimeMetric("derive.failures", MetricUnit.COUNT, MetricKind.DELTA, dropWhenAllZero = true)
private val DERIVE_DURATION = ReadTimeMetric("derive.duration_ms", MetricUnit.MILLISECONDS, MetricKind.GAUGE)
private val DERIVE_ROWS = ReadTimeMetric("derive.rows_written", MetricUnit.COUNT, MetricKind.DELTA)

/**
 * `GET /api/v1/telemetry/history`: the series of one panel section over a range. The sampled groups (memory, jvm, requests, storage,
 * the queue of sync) are SQL aggregations of `ops.telemetry_samples` per process role, metric, label and bucket (`TelemetryReads`),
 * merged here: several processes of one role share a series (a gauge's mean is n-weighted and its max the largest, a DELTA adds up),
 * so a trend survives a restart. The job groups (sync, derive) are read-time aggregations of `sync_jobs` / `metrics.derive_runs` per
 * connection and bucket, reached through [SyncJobsService] and [MetricsStore].
 */
internal class TelemetryHistoryReport(
    private val store: TelemetryStore,
    private val reads: TelemetryReads,
    private val syncJobs: SyncJobsService,
    private val metricsStore: MetricsStore,
    private val intervalSeconds: Int,
    private val jobRetentionMillis: Long,
) {
    suspend fun build(group: HistoryGroup, range: HistoryRange, now: Long): TelemetryHistory {
        val bucketSeconds = historyBucketSeconds(range, intervalSeconds)
        val bucketMillis = bucketSeconds * 1000L
        val from = now - range.millis
        val window = HistoryWindow(range, from - Math.floorMod(from, bucketMillis), bucketMillis)
        val sampled = when (group) {
            HistoryGroup.MEMORY -> sampled(MetricGroup.MEMORY, window)
            HistoryGroup.JVM -> sampled(MetricGroup.JVM, window)
            HistoryGroup.STORAGE -> sampled(MetricGroup.STORAGE, window)
            HistoryGroup.REQUESTS -> sampled(MetricGroup.REQUESTS, window, topRoutes = true)
            HistoryGroup.SYNC -> sampled(MetricGroup.SYNC, window) + syncSeries(window, now)
            HistoryGroup.DERIVE -> deriveSeries(window, now)
        }
        val historyFrom = when (group) {
            HistoryGroup.SYNC, HistoryGroup.DERIVE -> maxOf(from, now - jobRetentionMillis)
            else -> reads.oldestProcessStart()?.let { maxOf(from, minOf(it, now)) } ?: now
        }
        return TelemetryHistory(
            group = group.param, range = range.param, bucketSeconds = bucketSeconds, from = from, to = now, historyFrom = historyFrom,
            truncated = sampled.size > MAX_SERIES, series = sampled.take(MAX_SERIES),
        )
    }

    private suspend fun sampled(group: MetricGroup, window: HistoryWindow, topRoutes: Boolean = false): List<TelemetrySeries> {
        val metrics = TelemetryMetrics.all.filter { it.group == group }
        return mergeRows(reads.historyRows(metrics, window, topRoutes))
    }

    private suspend fun syncSeries(window: HistoryWindow, now: Long): List<TelemetrySeries> {
        val stats = store.transaction(READ_TIMEOUT_SECONDS) { syncJobs.bucketStats(window.alignedFrom, window.bucketMillis, to = now + 1) }
        val buckets = stats.groupBy({ it.connectionId }) {
            JobBucket(it.bucketStart, it.runs, it.failures, it.avgDurationMs, it.maxDurationMs, it.rateLimited)
        }
        return jobSeries(buckets, SYNC_RUNS, SYNC_FAILURES, SYNC_DURATION, SYNC_RATE_LIMITED)
    }

    private suspend fun deriveSeries(window: HistoryWindow, now: Long): List<TelemetrySeries> {
        val stats = store.transaction(READ_TIMEOUT_SECONDS) {
            metricsStore.runBuckets(window.alignedFrom, window.bucketMillis, to = now + 1)
        }
        val buckets = stats.groupBy({ it.connectionId }) {
            JobBucket(it.bucketStart, it.runs, it.failures, it.avgDurationMs, it.maxDurationMs, it.rowsWritten)
        }
        return jobSeries(buckets, DERIVE_RUNS, DERIVE_FAILURES, DERIVE_DURATION, DERIVE_ROWS)
    }

    /** Four series per connection, connections by id: runs, failures, duration (mean and longest run) and a fourth count. */
    private fun jobSeries(
        byConnection: Map<UInt, List<JobBucket>>,
        runs: ReadTimeMetric,
        failures: ReadTimeMetric,
        duration: ReadTimeMetric,
        extra: ReadTimeMetric,
    ): List<TelemetrySeries> = byConnection.toSortedMap().flatMap { (id, buckets) ->
        fun counts(value: (JobBucket) -> Long) = buckets.map { TelemetryPoint(it.t, value(it).toDouble(), value(it).toDouble()) }
        listOfNotNull(
            readTime(id, runs, counts { it.runs }),
            readTime(id, failures, counts { it.failures }),
            readTime(id, duration, durations(buckets)),
            readTime(id, extra, counts { it.extra }),
        )
    }

    /** The duration points: the mean and the longest run of each bucket that has a timed run. */
    private fun durations(buckets: List<JobBucket>) =
        buckets.mapNotNull { b -> b.avgMs?.let { TelemetryPoint(b.t, it, (b.maxMs ?: it.toLong()).toDouble()) } }

    /** A per-connection series; null for an empty one, and for a [ReadTimeMetric.dropWhenAllZero] one that never left zero (absent = 0). */
    private fun readTime(connectionId: UInt, metric: ReadTimeMetric, points: List<TelemetryPoint>): TelemetrySeries? {
        if (points.isEmpty() || (metric.dropWhenAllZero && points.all { it.max == 0.0 })) return null
        return TelemetrySeries(
            key = "${metric.name}:$connectionId", metric = metric.name, label = connectionId.toString(), role = null,
            connectionId = connectionId.toLong(), unit = metric.unit.name, kind = metric.kind.name, points = points.sortedBy { it.t },
        )
    }

    /** Folds the SQL groups into series: one per (role | cluster, metric, label), the groups of one bucket merged. */
    private fun mergeRows(rows: List<HistoryRow>): List<TelemetrySeries> {
        val known = rows.filter { it.metric in REGISTRY }
        val series = known.groupBy { Triple(roleOf(it), it.metric, it.label) }.map { (identity, own) ->
            val (role, metric, label) = identity
            val def = REGISTRY.getValue(metric)
            val points = own.groupBy { it.t }.toSortedMap().map { (t, parts) ->
                val n = parts.sumOf { it.n }
                val sum = parts.sumOf { it.sum }
                TelemetryPoint(t, if (def.kind == MetricKind.DELTA) sum else sum / n, parts.maxOf { it.max })
            }
            TelemetrySeries(
                key = listOfNotNull(role, metric, label.ifEmpty { null }).joinToString(":"), metric = metric, label = label, role = role,
                connectionId = if (metric in PER_CONNECTION_METRICS) label.toLongOrNull() else null,
                unit = def.unit.name, kind = def.kind.name, points = points,
            )
        }
        val order = TelemetryMetrics.all.withIndex().associate { (index, def) -> def.name to index }
        return series.sortedWith(compareBy({ order.getValue(it.metric) }, { it.label }, { it.role.orEmpty() }))
    }

    private fun roleOf(row: HistoryRow): String? = if (REGISTRY.getValue(row.metric).group in PER_PROCESS_GROUPS) row.role else null
}
