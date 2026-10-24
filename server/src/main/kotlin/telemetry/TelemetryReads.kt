package ch.nokillswit.telemetry

import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** The statement budget of every telemetry READ, whole seconds (`queryTimeout`: the only way a statement timeout is set). */
internal const val READ_TIMEOUT_SECONDS = 5

/** One `ops.telemetry_processes` row. */
internal class ProcessRow(
    val id: Int,
    val role: String,
    val instance: String,
    val startedAt: Long,
    val lastSeen: Long,
    val version: String?,
    val commit: String?,
    val heapMaxBytes: Long,
    val containerLimitBytes: Long?,
)

/** A process's newest raw tick: its bucket [ts] and every `(metric, label)` figure the tick wrote. */
internal class ProcessSample(val ts: Long, val values: Map<Pair<String, String>, Double>) {
    fun value(metric: MetricDef, label: String = ""): Double? = values[metric.name to label]
}

/** The newest cluster-wide measurement of one metric: its bucket [ts] and the figure per label. */
internal class ClusterSample(val ts: Long, val values: Map<String, Double>)

/**
 * One group of the history query: [n] samples folded, their [sum] (`avg * n`: the n-weighted mean's numerator, a DELTA's total) and
 * the largest [max], for one process [role], metric, label and bucket start [t].
 */
internal class HistoryRow(
    val role: String,
    val metric: String,
    val label: String,
    val t: Long,
    val n: Long,
    val sum: Double,
    val max: Double,
)

/** How the history query reads the samples: the bucket width, the first bucket's start and where the unrolled raw tail begins. */
internal class HistoryWindow(val range: HistoryRange, val alignedFrom: Long, val bucketMillis: Long)

/**
 * The `ops` schema's reads for the telemetry API (`TelemetryRoutes.kt`): nothing but SELECTs, each in a transaction of its own under
 * [READ_TIMEOUT_SECONDS]. [intervalSeconds] is the configured sample cadence (how far back a process's newest tick can lie).
 */
internal class TelemetryReads(private val store: TelemetryStore, private val intervalSeconds: Int) {
    private val intervalMillis = intervalSeconds * 1000L

    /** Every process seen at or after [since], by id. */
    suspend fun processesSeenSince(since: Long): List<ProcessRow> = store.transaction(READ_TIMEOUT_SECONDS) {
        val p = TelemetryStore.Processes
        p.selectAll().where { p.lastSeen greaterEq since }.orderBy(p.id).toList().map {
            ProcessRow(
                it[p.id].value, it[p.role], it[p.instance], it[p.startedAt], it[p.lastSeen], it[p.buildVersion], it[p.buildCommit],
                it[p.heapMaxBytes], it[p.containerLimitBytes],
            )
        }
    }

    /**
     * Each of [processes]' newest raw tick restricted to [metrics], by process id: the rows within two intervals of the process's
     * `last_seen`, of which only those at its newest bucket are kept (a figure the newest tick did not write is absent, not carried).
     */
    suspend fun latestPerProcess(processes: List<ProcessRow>, metrics: List<MetricDef>): Map<Int, ProcessSample> {
        if (processes.isEmpty()) return emptyMap()
        return store.transaction(READ_TIMEOUT_SECONDS) {
            val s = TelemetryStore.Samples
            val recent = processes.map { (s.processId eq it.id) and (s.ts greaterEq it.lastSeen - 2 * intervalMillis) }
                .reduce { a, b -> a or b }
            val rows = s.select(s.processId, s.ts, s.metric, s.label, s.avg)
                .where { (s.resolution inList RAW_RESOLUTIONS) and (s.metric inList metrics.map { it.name }) and recent }
                .toList()
            rows.groupBy { it[s.processId] }.mapValues { (_, own) ->
                val newest = own.maxOf { it[s.ts] }
                ProcessSample(newest, own.filter { it[s.ts] == newest }.associate { (it[s.metric] to it[s.label]) to it[s.avg] })
            }
        }
    }

    /** The newest raw measurement of each of [metrics] (cluster-wide: whichever process held the lock wrote them), by metric name. */
    suspend fun latestCluster(metrics: List<MetricDef>): Map<String, ClusterSample> = store.transaction(READ_TIMEOUT_SECONDS) {
        val s = TelemetryStore.Samples
        val raw = (s.resolution inList RAW_RESOLUTIONS) and (s.metric inList metrics.map { it.name })
        val newest = s.select(s.metric, s.ts.max()).where { raw }.groupBy(s.metric).toList()
            .associate { it[s.metric] to checkNotNull(it[s.ts.max()]) }
        if (newest.isEmpty()) return@transaction emptyMap()
        val atNewest = newest.map { (metric, ts) -> (s.metric eq metric) and (s.ts eq ts) }.reduce { a, b -> a or b }
        val rows = s.select(s.metric, s.label, s.avg).where { (s.resolution inList RAW_RESOLUTIONS) and atNewest }.toList()
        newest.mapValues { (metric, ts) ->
            ClusterSample(ts, rows.filter { it[s.metric] == metric }.associate { it[s.label] to it[s.avg] })
        }
    }

    /** The first moment any sampler booted (and so the oldest instant any retained sample can describe), or null with no process. */
    suspend fun oldestProcessStart(): Long? = store.transaction(READ_TIMEOUT_SECONDS) {
        val p = TelemetryStore.Processes
        p.select(p.startedAt.min()).toList().singleOrNull()?.get(p.startedAt.min())
    }

    /**
     * The history rows of [metrics] over [window], grouped per process role, metric, label and bucket in SQL. 24h reads the raw
     * tier; 7d and 90d read the hourly tier plus the raw rows of the hours the rollup has not folded yet (everything after the
     * newest hourly row), so the newest hour is never missing. [topRoutes] (the requests group) keeps `*` and the ten busiest routes of
     * the range, the routes with the count and percentile metrics only (the series cap).
     */
    suspend fun historyRows(metrics: List<MetricDef>, window: HistoryWindow, topRoutes: Boolean): List<HistoryRow> =
        store.transaction(READ_TIMEOUT_SECONDS) {
            val s = TelemetryStore.Samples
            val rawTier = RAW_RESOLUTIONS.joinToString(",")
            val tier = if (window.range == HistoryRange.H24) {
                "s.resolution IN ($rawTier) AND s.ts >= ${window.alignedFrom}"
            } else {
                val newestHourly = s.select(s.ts.max()).where { s.resolution eq HOURLY_RESOLUTION }.toList().singleOrNull()?.get(s.ts.max())
                val rawFrom = maxOf(window.alignedFrom, newestHourly?.plus(MILLIS_PER_HOUR) ?: window.alignedFrom)
                "((s.resolution = $HOURLY_RESOLUTION AND s.ts >= ${window.alignedFrom}) OR " +
                    "(s.resolution IN ($rawTier) AND s.ts >= $rawFrom))"
            }
            val names = metrics.joinToString(",") { "'${it.name}'" }
            val perRoute = with(TelemetryMetrics) { listOf(HTTP_COUNT, HTTP_P50, HTTP_P95) }.joinToString(",") { "'${it.name}'" }
            val top = if (topRoutes) {
                """
                WITH top AS (
                    SELECT s.label FROM ops.telemetry_samples s
                    WHERE s.metric = '${TelemetryMetrics.HTTP_COUNT.name}' AND s.label <> '$TOTAL_LABEL' AND $tier
                    GROUP BY s.label ORDER BY SUM(s.avg * s.n) DESC, s.label LIMIT $TOP_ROUTES_IN_HISTORY
                )
                """.trimIndent()
            } else {
                ""
            }
            val routeFilter = if (topRoutes) {
                "AND (s.label = '$TOTAL_LABEL' OR (s.label IN (SELECT label FROM top) AND s.metric IN ($perRoute)))"
            } else {
                ""
            }
            query(
                """
                $top
                SELECT p.role, s.metric, s.label, (s.ts - s.ts % ${window.bucketMillis})::bigint AS t, SUM(s.n)::bigint,
                       SUM(s.avg * s.n)::float8, MAX(s.max)::float8
                FROM ops.telemetry_samples s JOIN ops.telemetry_processes p ON p.id = s.process_id
                WHERE s.metric IN ($names) $routeFilter AND $tier
                GROUP BY p.role, s.metric, s.label, t
                """.trimIndent(),
            ) { row ->
                HistoryRow(
                    role = row.get(0, String::class.java).orEmpty(),
                    metric = row.get(1, String::class.java).orEmpty(),
                    label = row.get(2, String::class.java).orEmpty(),
                    t = row.get(3, Long::class.javaObjectType)!!,
                    n = row.get(4, Long::class.javaObjectType)!!,
                    sum = row.get(5, Double::class.javaObjectType)!!,
                    max = row.get(6, Double::class.javaObjectType)!!,
                )
            }
        }
}

/** The history's requests group keeps this many routes (plus `*`) so that 10 x 3 + 5 series stay inside the 40-series cap. */
internal const val TOP_ROUTES_IN_HISTORY = 10
