package ch.nokillswit.telemetry

import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage

// G1 "G1 Old Gen", Serial "Tenured Gen", Parallel "PS Old Gen"; ZGC/Shenandoah have no old-generation heap pool and record none.
private val OLD_GEN_NAMES = listOf("old", "tenured")

/** One garbage collector's lifetime totals. */
data class GcTotals(val name: String, val count: Long, val timeMillis: Long)

/** What [JvmProbe] reads from the JVM; a test passes a fake, production the platform MXBeans ([PlatformJvmSources]). */
interface JvmSources {
    fun heap(): MemoryUsage
    fun nonHeap(): MemoryUsage

    /** The old-generation pool's peak usage since its previous call (the pool's peak is reset after the read); null when no such pool. */
    fun oldGenPeakBytesAndReset(): Long?
    fun directBufferBytes(): Long?

    /** Total CPU time the process has used, nanoseconds; null when the JVM cannot tell. */
    fun processCpuNanos(): Long?
    fun nanoTime(): Long
    fun availableProcessors(): Int
    fun threadCount(): Int
    fun gcTotals(): List<GcTotals>
}

/** The real thing: the platform MXBeans. Reads only counters and sizes - never a thread dump, never JVM arguments or properties. */
object PlatformJvmSources : JvmSources {
    private val memory = ManagementFactory.getMemoryMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val oldGen = ManagementFactory.getMemoryPoolMXBeans().firstOrNull {
        it.type == MemoryType.HEAP && OLD_GEN_NAMES.any { part -> it.name.contains(part, ignoreCase = true) }
    }
    private val direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).firstOrNull { it.name == "direct" }

    override fun heap(): MemoryUsage = memory.heapMemoryUsage
    override fun nonHeap(): MemoryUsage = memory.nonHeapMemoryUsage

    override fun oldGenPeakBytesAndReset(): Long? = oldGen?.let { pool ->
        pool.peakUsage.used.also { pool.resetPeakUsage() }
    }

    override fun directBufferBytes(): Long? = direct?.memoryUsed?.takeIf { it >= 0 }
    override fun processCpuNanos(): Long? = os?.processCpuTime?.takeIf { it >= 0 }
    override fun nanoTime(): Long = System.nanoTime()
    override fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
    override fun threadCount(): Int = threads.threadCount
    override fun gcTotals(): List<GcTotals> = collectors.map { GcTotals(it.name, it.collectionCount, it.collectionTime) }
}

/**
 * The per-process JVM collector: memory pools, CPU, threads and GC. The deltas (CPU over wall time, GC count and time per
 * collector) are taken against the previous [read], the first against the probe's construction (CPU) or the JVM's start (GC), so
 * it is one object per process. Not thread-safe - the sampler ticks from one coroutine.
 */
class JvmProbe(private val sources: JvmSources = PlatformJvmSources) {
    private var lastCpuNanos: Long? = sources.processCpuNanos()
    private var lastWallNanos: Long = sources.nanoTime()
    private val lastGc = HashMap<String, GcTotals>()

    fun read(): List<Reading> {
        val out = ArrayList<Reading>(READINGS_HINT)
        val heap = sources.heap()
        out += Reading(TelemetryMetrics.HEAP_USED, heap.used.toDouble())
        out += Reading(TelemetryMetrics.HEAP_COMMITTED, heap.committed.toDouble())
        sources.oldGenPeakBytesAndReset()?.let { out += Reading(TelemetryMetrics.OLD_GEN_PEAK, it.toDouble()) }
        out += Reading(TelemetryMetrics.NON_HEAP_USED, sources.nonHeap().used.toDouble())
        sources.directBufferBytes()?.let { out += Reading(TelemetryMetrics.DIRECT_BUFFERS, it.toDouble()) }
        cpuRatio()?.let { out += Reading(TelemetryMetrics.CPU_RATIO, it) }
        out += Reading(TelemetryMetrics.THREADS, sources.threadCount().toDouble())
        for (gc in sources.gcTotals()) {
            val previous = lastGc.put(gc.name, gc)
            // A collector reporting -1 (unsupported) has no figures to difference.
            if (gc.count < 0 || gc.timeMillis < 0) continue
            out += Reading(TelemetryMetrics.GC_COUNT, (gc.count - (previous?.count ?: 0L)).toDouble(), gc.name)
            out += Reading(TelemetryMetrics.GC_TIME, (gc.timeMillis - (previous?.timeMillis ?: 0L)).toDouble(), gc.name)
        }
        return out
    }

    /** CPU time over wall time since the previous read, as a fraction of the available processors (0..1); null when it cannot be told. */
    private fun cpuRatio(): Double? {
        val cpu = sources.processCpuNanos()
        val wall = sources.nanoTime()
        val previousCpu = lastCpuNanos
        val previousWall = lastWallNanos
        lastCpuNanos = cpu
        lastWallNanos = wall
        if (cpu == null || previousCpu == null || wall <= previousWall) return null
        val processors = sources.availableProcessors().coerceAtLeast(1)
        return ((cpu - previousCpu).toDouble() / ((wall - previousWall).toDouble() * processors)).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val READINGS_HINT = 12
    }
}
