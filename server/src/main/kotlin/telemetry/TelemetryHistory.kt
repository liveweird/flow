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

/**
 * The per-connection groups (sync, derive, storage) have their own budget: up to this many connections (four series each for sync and
 * derive, plus sync's two queue series; two each for storage, plus its cluster series), a connection never cut partially. Past it the
 * connections with the fewest ended runs in the range (storage: the smallest estimated bytes) are left out whole and listed in
 * `omittedConnectionIds`.
 */
internal const val MAX_JOB_CONNECTIONS = 25

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

/**
 * Per-process metrics merge per ROLE (memory, jvm); every other group's metrics are cluster-wide (whichever process held the lock wrote
 * them). Requests are per process too but merge across ALL serving roles (web and `all` are one fleet for a route), so their series
 * carry no role.
 */
private val PER_ROLE_GROUPS = setOf(MetricGroup.MEMORY, MetricGroup.JVM)
private val PER_CONNECTION_METRICS = setOf(TelemetryMetrics.CONN_ISSUES.name, TelemetryMetrics.CONN_BYTES_EST.name)

/** One finished-jobs bucket of a connection, as either job table reports it: [extra] is the 429 count (sync) or the rows written. */
private class JobBucket(val t: Long, val runs: Long, val failures: Long, val avgMs: Double?, val maxMs: Long?, val extra: Long)

/** A series derived at read time from the job tables (no sampler row behind it): its name, unit and kind. */
private const val JOB_SERIES_PER_CONNECTION = 4
private const val STORAGE_SERIES_PER_CONNECTION = 2

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
) {
    /** The series of one response, the cap they were cut to and the connections left out whole. */
    private class Assembled(val series: List<TelemetrySeries>, val cap: Int = MAX_SERIES, val omitted: List<Long> = emptyList())

    suspend fun build(group: HistoryGroup, range: HistoryRange, now: Long): TelemetryHistory {
        val bucketSeconds = historyBucketSeconds(range, intervalSeconds)
        val bucketMillis = bucketSeconds * 1000L
        val from = now - range.millis
        val window = HistoryWindow(range, from - Math.floorMod(from, bucketMillis), bucketMillis)
        val assembled = when (group) {
            HistoryGroup.MEMORY -> Assembled(sampled(MetricGroup.MEMORY, window))
            HistoryGroup.JVM -> Assembled(sampled(MetricGroup.JVM, window))
            HistoryGroup.STORAGE -> storageSeries(window)
            HistoryGroup.REQUESTS -> Assembled(sampled(MetricGroup.REQUESTS, window, topRoutes = true))
            HistoryGroup.SYNC -> syncSeries(window, now)
            HistoryGroup.DERIVE -> deriveSeries(window, now)
        }
        val historyFrom = historyFrom(group, from, now)
        return TelemetryHistory(
            group = group.param, range = range.param, bucketSeconds = bucketSeconds, from = from, to = now, historyFrom = historyFrom,
            truncated = assembled.series.size > assembled.cap || assembled.omitted.isNotEmpty(),
            omittedConnectionIds = assembled.omitted, series = assembled.series.take(assembled.cap),
        )
    }

    /**
     * The oldest instant the range holds data for, clipped to the range: the first sampler boot for the sampled groups, the oldest
     * ENDED job / DERIVE run still retained (the owners' `oldestFinishedAt`; retention may be shorter than the range) for sync and derive.
     */
    private suspend fun historyFrom(group: HistoryGroup, from: Long, now: Long): Long {
        val oldest = when (group) {
            HistoryGroup.SYNC -> store.transaction(READ_TIMEOUT_SECONDS) { syncJobs.oldestFinishedAt(now + 1) }
            HistoryGroup.DERIVE -> store.transaction(READ_TIMEOUT_SECONDS) { metricsStore.oldestFinishedAt(now + 1) }
            else -> reads.oldestProcessStart()
        }
        return oldest?.let { maxOf(from, minOf(it, now)) } ?: now
    }

    private suspend fun sampled(group: MetricGroup, window: HistoryWindow, topRoutes: Boolean = false): List<TelemetrySeries> {
        val metrics = TelemetryMetrics.all.filter { it.group == group }
        return mergeRows(reads.historyRows(metrics, window, topRoutes))
    }

    /**
     * The database and per-schema sizes are always kept; the per-connection series (`conn.issues`, `conn.bytes.est`) share the
     * [MAX_JOB_CONNECTIONS] budget, a connection never cut partially: past it, the connections with the smallest latest estimated
     * bytes (ties: the higher id) are left out whole and listed in `omittedConnectionIds`.
     */
    private suspend fun storageSeries(window: HistoryWindow): Assembled {
        val all = sampled(MetricGroup.STORAGE, window)
        val cluster = all.count { it.connectionId == null }
        val byConnection = all.filter { it.connectionId != null }.groupBy { checkNotNull(it.connectionId) }
        fun latestBytes(series: List<TelemetrySeries>) =
            series.firstOrNull { it.metric == TelemetryMetrics.CONN_BYTES_EST.name }?.points?.lastOrNull()?.avg ?: 0.0
        val ranked = byConnection.entries.sortedWith(compareBy({ -latestBytes(it.value) }, { it.key }))
        val kept = ranked.take(MAX_JOB_CONNECTIONS).map { it.key }.toSet()
        return Assembled(
            series = all.filter { it.connectionId == null || it.connectionId in kept },
            cap = cluster + STORAGE_SERIES_PER_CONNECTION * MAX_JOB_CONNECTIONS,
            omitted = ranked.drop(MAX_JOB_CONNECTIONS).map { it.key }.sorted(),
        )
    }

    private suspend fun syncSeries(window: HistoryWindow, now: Long): Assembled {
        val stats = store.transaction(READ_TIMEOUT_SECONDS) { syncJobs.bucketStats(window.alignedFrom, window.bucketMillis, to = now + 1) }
        val buckets = stats.groupBy({ it.connectionId }) {
            JobBucket(it.bucketStart, it.runs, it.failures, it.avgDurationMs, it.maxDurationMs, it.rateLimited)
        }
        val queue = sampled(MetricGroup.SYNC, window)
        val (series, omitted) = jobSeries(buckets, SYNC_RUNS, SYNC_FAILURES, SYNC_DURATION, SYNC_RATE_LIMITED)
        return Assembled(queue + series, cap = queue.size + JOB_SERIES_PER_CONNECTION * MAX_JOB_CONNECTIONS, omitted = omitted)
    }

    private suspend fun deriveSeries(window: HistoryWindow, now: Long): Assembled {
        val stats = store.transaction(READ_TIMEOUT_SECONDS) {
            metricsStore.runBuckets(window.alignedFrom, window.bucketMillis, to = now + 1)
        }
        val buckets = stats.groupBy({ it.connectionId }) {
            JobBucket(it.bucketStart, it.runs, it.failures, it.avgDurationMs, it.maxDurationMs, it.rowsWritten)
        }
        val (series, omitted) = jobSeries(buckets, DERIVE_RUNS, DERIVE_FAILURES, DERIVE_DURATION, DERIVE_ROWS)
        return Assembled(series, cap = JOB_SERIES_PER_CONNECTION * MAX_JOB_CONNECTIONS, omitted = omitted)
    }

    /**
     * Four series per connection, connections by id: runs, failures, duration (mean and longest run) and a fourth count. At most
     * [MAX_JOB_CONNECTIONS] connections are kept - the busiest by ended runs, then the lowest id - and the rest are returned as the
     * second element (ascending ids), never cut partially.
     */
    private fun jobSeries(
        byConnection: Map<UInt, List<JobBucket>>,
        runs: ReadTimeMetric,
        failures: ReadTimeMetric,
        duration: ReadTimeMetric,
        extra: ReadTimeMetric,
    ): Pair<List<TelemetrySeries>, List<Long>> {
        val ranked = byConnection.entries.sortedWith(compareBy({ -it.value.sumOf { b -> b.runs } }, { it.key }))
        val kept = ranked.take(MAX_JOB_CONNECTIONS).sortedBy { it.key }
        val series = kept.flatMap { (id, buckets) ->
            fun counts(value: (JobBucket) -> Long) = buckets.map { TelemetryPoint(it.t, value(it).toDouble(), value(it).toDouble()) }
            listOfNotNull(
                readTime(id, runs, counts { it.runs }),
                readTime(id, failures, counts { it.failures }),
                readTime(id, duration, durations(buckets)),
                readTime(id, extra, counts { it.extra }),
            )
        }
        return series to ranked.drop(MAX_JOB_CONNECTIONS).map { it.key.toLong() }.sorted()
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

    private fun roleOf(row: HistoryRow): String? = if (REGISTRY.getValue(row.metric).group in PER_ROLE_GROUPS) row.role else null
}
