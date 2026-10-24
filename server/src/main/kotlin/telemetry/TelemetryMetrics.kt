package ch.nokillswit.telemetry

/**
 * How a metric's stored figures read (`.claude/docs/observability.md` "Telemetry"): a GAUGE is the instantaneous value at the
 * sample (hourly rows: the n-weighted mean and the largest sample), a DELTA is the change over the sampling interval - a count,
 * a time - (an hourly row's `avg * n` is the hour's total) and a PERCENTILE is a quantile of the interval's window (the hourly
 * mean is a mean of quantiles, the hourly max the worst one).
 */
enum class MetricKind { GAUGE, DELTA, PERCENTILE }

enum class MetricUnit { BYTES, MILLISECONDS, COUNT, RATIO }

/** The panel section a metric belongs to (the read API's `group`, a later commit). */
enum class MetricGroup { MEMORY, JVM, REQUESTS, SYNC, STORAGE, TELEMETRY }

/** One metric of the registry. [name] is the stored `metric` column (VARCHAR(48)): a metric is added here, never in a migration. */
data class MetricDef(val name: String, val kind: MetricKind, val unit: MetricUnit, val group: MetricGroup) {
    init {
        require(name.length <= MAX_METRIC_NAME_LENGTH) { "metric name \"$name\" exceeds $MAX_METRIC_NAME_LENGTH characters" }
    }
}

internal const val MAX_METRIC_NAME_LENGTH = 48
internal const val MAX_LABEL_LENGTH = 120

/** One measured figure of a tick: [metric] (+ an optional [label], e.g. a GC collector or a route) at [value]. */
class Reading(val metric: MetricDef, val value: Double, label: String = "") {
    val label: String = label.take(MAX_LABEL_LENGTH)
    override fun toString() = "Reading(${metric.name}${if (label.isEmpty()) "" else "[$label]"}=$value)"
}

/** The metric registry: every name a sampler may write. */
object TelemetryMetrics {
    private fun gauge(name: String, unit: MetricUnit, group: MetricGroup) = MetricDef(name, MetricKind.GAUGE, unit, group)
    private fun delta(name: String, unit: MetricUnit, group: MetricGroup) = MetricDef(name, MetricKind.DELTA, unit, group)

    val HEAP_USED = gauge("mem.heap_used", MetricUnit.BYTES, MetricGroup.MEMORY)
    val HEAP_COMMITTED = gauge("mem.heap_committed", MetricUnit.BYTES, MetricGroup.MEMORY)

    /** The old generation's peak since the previous sample (reset after each read): per interval, not like [CGROUP_PEAK]. */
    val OLD_GEN_PEAK = gauge("mem.old_gen_peak", MetricUnit.BYTES, MetricGroup.MEMORY)
    val NON_HEAP_USED = gauge("mem.non_heap_used", MetricUnit.BYTES, MetricGroup.MEMORY)
    val DIRECT_BUFFERS = gauge("mem.direct_buffers", MetricUnit.BYTES, MetricGroup.MEMORY)

    /** `VmRSS` of `/proc/self/status`. */
    val RSS = gauge("mem.rss", MetricUnit.BYTES, MetricGroup.MEMORY)
    val CGROUP_CURRENT = gauge("mem.cgroup_current", MetricUnit.BYTES, MetricGroup.MEMORY)
    val CGROUP_ANON = gauge("mem.cgroup_anon", MetricUnit.BYTES, MetricGroup.MEMORY)

    /** `memory.current - inactive_file`: what the kubelet evicts on. */
    val CGROUP_WORKING_SET = gauge("mem.cgroup_working_set", MetricUnit.BYTES, MetricGroup.MEMORY)
    /** The cgroup's LIFETIME peak (`memory.peak`, kernel 5.19+), never per interval - unlike [OLD_GEN_PEAK]. */
    val CGROUP_PEAK = gauge("mem.cgroup_peak", MetricUnit.BYTES, MetricGroup.MEMORY)

    /** Process CPU time over wall time, as a fraction of the available processors (0..1). */
    val CPU_RATIO = gauge("jvm.cpu_ratio", MetricUnit.RATIO, MetricGroup.JVM)
    val THREADS = gauge("jvm.threads", MetricUnit.COUNT, MetricGroup.JVM)

    /** Per collector (the label). */
    val GC_COUNT = delta("jvm.gc_count", MetricUnit.COUNT, MetricGroup.JVM)
    val GC_TIME = delta("jvm.gc_time_ms", MetricUnit.MILLISECONDS, MetricGroup.JVM)
    val POOL_ACQUIRED = gauge("pool.acquired", MetricUnit.COUNT, MetricGroup.JVM)
    val POOL_IDLE = gauge("pool.idle", MetricUnit.COUNT, MetricGroup.JVM)
    val POOL_PENDING = gauge("pool.pending", MetricUnit.COUNT, MetricGroup.JVM)
    val POOL_ALLOCATED = gauge("pool.allocated", MetricUnit.COUNT, MetricGroup.JVM)

    /**
     * How long the cluster section of a tick took. Also the cluster section's marker: its presence at a bucket's `ts` is how a
     * second process finds the bucket already served (`TelemetrySampler.kt`).
     */
    val CLUSTER_TICK = gauge("telemetry.cluster_ms", MetricUnit.MILLISECONDS, MetricGroup.TELEMETRY)

    val all: List<MetricDef> by lazy {
        listOf(
            HEAP_USED, HEAP_COMMITTED, OLD_GEN_PEAK, NON_HEAP_USED, DIRECT_BUFFERS, RSS, CGROUP_CURRENT, CGROUP_ANON,
            CGROUP_WORKING_SET, CGROUP_PEAK, CPU_RATIO, THREADS, GC_COUNT, GC_TIME, POOL_ACQUIRED, POOL_IDLE, POOL_PENDING,
            POOL_ALLOCATED, CLUSTER_TICK,
        )
    }
}
