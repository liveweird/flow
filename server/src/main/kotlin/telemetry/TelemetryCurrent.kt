package ch.nokillswit.telemetry

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.plugins.timerWindowSeconds
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A process with no sample (and no tick) for more than this many sample intervals is `stale`. */
private const val STALE_AFTER_INTERVALS = 3

/** A process unseen for longer than this is not listed at all (a stale one stays for a day if it is the last of its role). */
private const val LISTED_FOR_MILLIS = MILLIS_PER_DAY

/** A stale process last seen within this long stays listed even when its role has a live one: a wedged replica must stay visible. */
private const val WEDGED_VISIBLE_MILLIS = MILLIS_PER_HOUR

private const val TOP_ROUTES = 15
private const val PERCENT = 100.0
private const val ONE_DECIMAL = 10.0

private val PROCESS_METRICS: List<MetricDef> = TelemetryMetrics.all.filter {
    it.group == MetricGroup.MEMORY || it.group == MetricGroup.JVM || it.group == MetricGroup.REQUESTS
}
private val API_ROLES = setOf("web", "all")

/**
 * `GET /api/v1/telemetry/current`: the processes with their latest sample, the newest storage measurement, one block per data source
 * and the busiest routes of the latest interval. Everything outside `ops` comes through the owning package's service (names and
 * freshness through [DataSourceService] - id and name ONLY, never settings or the token -, the queue and the jobs through
 * [SyncJobsService], DERIVE through [MetricsStore]) inside one transaction, so the whole read runs under the telemetry read budget.
 */
internal class TelemetryCurrentReport(
    private val store: TelemetryStore,
    private val reads: TelemetryReads,
    private val dataSources: DataSourceService,
    private val syncJobs: SyncJobsService,
    private val metricsStore: MetricsStore,
    private val intervalSeconds: Int,
) {
    suspend fun build(now: Long): TelemetryCurrent {
        val seen = reads.processesSeenSince(now - LISTED_FOR_MILLIS)
        val samples = reads.latestPerProcess(seen, PROCESS_METRICS)
        val stale = seen.filter { isStale(it, samples[it.id], now) }.map { it.id }.toSet()
        val processes = visibleProcesses(seen, stale, now)
        val cluster = reads.latestCluster(
            with(TelemetryMetrics) { listOf(DB_SIZE, DB_SCHEMA_SIZE, CONN_ISSUES, CONN_BYTES_EST) },
        )
        val api = processes.filter { it.role in API_ROLES && it.id !in stale }.mapNotNull { samples[it.id] }
        return TelemetryCurrent(
            generatedAt = now,
            sampleIntervalSeconds = intervalSeconds,
            processes = processes.map { it.toWire(samples[it.id], it.id in stale) },
            storage = storage(cluster),
            connections = connections(now, cluster),
            requests = requests(api),
        )
    }

    /**
     * Stale = nothing for more than three sample intervals: no tick (`last_seen`), or ticks without a sample - the newest sample's
     * bucket is that old, or none is retained for a process older than that (a sampler whose inserts fail while it still ticks).
     */
    private fun isStale(process: ProcessRow, sample: ProcessSample?, now: Long): Boolean {
        val limit = STALE_AFTER_INTERVALS * intervalSeconds * 1000L
        return now - process.lastSeen > limit || (sample?.let { now - it.ts > limit } ?: (now - process.startedAt > limit))
    }

    /**
     * The processes worth listing: every live one; every stale one seen within the last hour (a wedged replica stays visible, so does
     * the pod a rolling update just replaced, for that hour); and per role the newest older stale one when the role has none alive
     * (a worker that stopped stays visible for a day).
     */
    private fun visibleProcesses(seen: List<ProcessRow>, stale: Set<Int>, now: Long): List<ProcessRow> {
        val (dead, live) = seen.partition { it.id in stale }
        val liveRoles = live.map { it.role }.toSet()
        val recent = dead.filter { now - it.lastSeen <= WEDGED_VISIBLE_MILLIS }
        val lastOfRole = dead.filter { it.role !in liveRoles }.groupBy { it.role }.values.map { byRole -> byRole.maxBy { it.lastSeen } }
        return (live + recent + lastOfRole).distinctBy { it.id }.sortedWith(compareBy({ it.role }, { it.startedAt }, { it.id }))
    }

    private fun ProcessRow.toWire(sample: ProcessSample?, stale: Boolean): TelemetryProcess {
        fun bytes(metric: MetricDef) = sample?.value(metric)?.roundToLong()
        val workingSet = bytes(TelemetryMetrics.CGROUP_WORKING_SET)
        val percent = if (workingSet != null && containerLimitBytes != null && containerLimitBytes > 0) {
            (workingSet.toDouble() / containerLimitBytes * PERCENT * ONE_DECIMAL).roundToLong() / ONE_DECIMAL
        } else {
            null
        }
        fun total(metric: MetricDef): Long? {
            val figures = sample?.values?.filterKeys { it.first == metric.name }?.values.orEmpty()
            return if (figures.isEmpty()) null else figures.sum().roundToLong()
        }
        fun count(metric: MetricDef) = sample?.value(metric)?.roundToInt()
        return TelemetryProcess(
            id = id, role = role, instance = instance, startedAt = startedAt, lastSeenAt = lastSeen, sampledAt = sample?.ts,
            stale = stale, version = version, commit = commit,
            memory = TelemetryMemory(
                heapUsedBytes = bytes(TelemetryMetrics.HEAP_USED), heapCommittedBytes = bytes(TelemetryMetrics.HEAP_COMMITTED),
                heapMaxBytes = heapMaxBytes, oldGenPeakBytes = bytes(TelemetryMetrics.OLD_GEN_PEAK),
                nonHeapBytes = bytes(TelemetryMetrics.NON_HEAP_USED), directBytes = bytes(TelemetryMetrics.DIRECT_BUFFERS),
                rssBytes = bytes(TelemetryMetrics.RSS), cgroupCurrentBytes = bytes(TelemetryMetrics.CGROUP_CURRENT),
                cgroupAnonBytes = bytes(TelemetryMetrics.CGROUP_ANON), workingSetBytes = workingSet,
                cgroupPeakBytes = bytes(TelemetryMetrics.CGROUP_PEAK), containerLimitBytes = containerLimitBytes,
                percentOfLimit = percent,
            ),
            jvm = TelemetryJvm(
                cpuRatio = sample?.value(TelemetryMetrics.CPU_RATIO), threads = count(TelemetryMetrics.THREADS),
                gcCount = total(TelemetryMetrics.GC_COUNT), gcTimeMs = total(TelemetryMetrics.GC_TIME),
                poolAcquired = count(TelemetryMetrics.POOL_ACQUIRED), poolIdle = count(TelemetryMetrics.POOL_IDLE),
                poolPending = count(TelemetryMetrics.POOL_PENDING), poolAllocated = count(TelemetryMetrics.POOL_ALLOCATED),
            ),
        )
    }

    private fun storage(cluster: Map<String, ClusterSample>): TelemetryStorage? {
        val size = cluster[TelemetryMetrics.DB_SIZE.name] ?: return null
        val databaseBytes = size.values[""] ?: return null
        val schemas = cluster[TelemetryMetrics.DB_SCHEMA_SIZE.name]?.values.orEmpty().entries
            .sortedBy { it.key }.map { TelemetrySchemaSize(it.key, it.value.roundToLong()) }
        return TelemetryStorage(measuredAt = size.ts, databaseBytes = databaseBytes.roundToLong(), schemas = schemas)
    }

    private suspend fun connections(now: Long, cluster: Map<String, ClusterSample>): List<TelemetryConnection> =
        store.transaction(READ_TIMEOUT_SECONDS) {
            val roster = dataSources.connectionHealth()
            val queue = syncJobs.queueByConnection()
            val lastSync = syncJobs.latestSyncs(backfillOnly = false)
            val lastBackfill = syncJobs.latestSyncs(backfillOnly = true)
            val day = syncJobs.bucketStats(now - MILLIS_PER_DAY, MILLIS_PER_DAY, to = now + 1).groupBy { it.connectionId }
            val deriveRuns = metricsStore.latestRuns()
            val peakSpan = DERIVE_PEAK_DAYS * MILLIS_PER_DAY
            val derivePeaks = metricsStore.runBuckets(now - peakSpan, peakSpan, to = now + 1)
                .groupBy { it.connectionId }.mapValues { (_, buckets) -> buckets.mapNotNull { it.maxDurationMs }.maxOrNull() }
            val issues = cluster[TelemetryMetrics.CONN_ISSUES.name]?.values.orEmpty()
            val estimate = cluster[TelemetryMetrics.CONN_BYTES_EST.name]
            roster.map { c ->
                val key = c.id.toString()
                val backfill = lastBackfill[c.id]
                val derive = deriveRuns[c.id]
                TelemetryConnection(
                    id = c.id.toLong(), name = c.name, enabled = c.enabled, deleted = c.deleted,
                    issues = issues[key]?.roundToLong(), estimatedBytes = estimate?.values?.get(key)?.roundToLong(),
                    estimatedAt = estimate?.takeIf { key in it.values }?.ts,
                    freshness = TelemetryFreshness(c.lastSyncSucceededAt, c.consecutiveFailures, c.lastSyncErrorCode, c.nextSyncAt),
                    queue = queue[c.id]?.let { TelemetryQueue(it.pending, it.running) } ?: TelemetryQueue(0, 0),
                    lastSync = lastSync[c.id]?.let { TelemetrySyncRun(it.startedAt, it.finishedAt, it.status) },
                    lastBackfill = backfill?.let {
                        val started = it.startedAt
                        val finished = it.finishedAt
                        val duration = if (started != null && finished != null) finished - started else null
                        TelemetryBackfill(started, finished, duration, it.status)
                    },
                    rateLimited24h = day[c.id].orEmpty().sumOf { it.rateLimited },
                    backoffMs24h = day[c.id].orEmpty().sumOf { it.backoffMs },
                    derive = derive?.let {
                        TelemetryDerive(it.finishedAt, it.durationMs, it.status, derivePeaks[c.id], it.rowsWritten)
                    },
                )
            }
        }

    /**
     * The latest interval's request figures over the live web / `all` processes' newest bucket: counts and error counts add up,
     * the p50 is the count-weighted mean of the processes' p50s and the p95 the largest (a mean of quantiles understates a tail).
     */
    private fun requests(api: List<ProcessSample>): TelemetryRequests {
        val newest = api.maxOfOrNull { it.ts }
        val labels = api.filter { it.ts == newest }.flatMap { it.values.keys }
            .filter { it.first == TelemetryMetrics.HTTP_COUNT.name }.map { it.second }.distinct()
        fun stats(label: String): TelemetryRouteStats {
            val rows = api.filter { it.ts == newest }
            val counts = rows.map { it.value(TelemetryMetrics.HTTP_COUNT, label) ?: 0.0 }
            val count = counts.sum()
            // Only the processes that wrote a p50 weigh in, divided by THEIR requests.
            val withP50 = rows.zip(counts).mapNotNull { (row, n) -> row.value(TelemetryMetrics.HTTP_P50, label)?.let { it to n } }
            val p50Count = withP50.sumOf { it.second }
            return TelemetryRouteStats(
                route = label,
                count = count.roundToLong(),
                p50Ms = if (p50Count > 0) withP50.sumOf { it.first * it.second } / p50Count else null,
                p95Ms = rows.mapNotNull { it.value(TelemetryMetrics.HTTP_P95, label) }.maxOrNull(),
                serverErrors = rows.sumOf { it.value(TelemetryMetrics.HTTP_5XX, label) ?: 0.0 }.roundToLong(),
                rateLimited = rows.sumOf { it.value(TelemetryMetrics.HTTP_429, label) ?: 0.0 }.roundToLong(),
            )
        }
        val routes = labels.filter { it != TOTAL_LABEL }.map(::stats).sortedWith(compareBy({ -it.count }, { it.route })).take(TOP_ROUTES)
        return TelemetryRequests(intervalSeconds, timerWindowSeconds(intervalSeconds).toInt(), newest, stats(TOTAL_LABEL), routes)
    }
}

/** The span the DERIVE `peakDurationMs` covers, days. */
private const val DERIVE_PEAK_DAYS = 7L
