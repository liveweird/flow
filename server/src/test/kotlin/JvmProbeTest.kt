package ch.nokillswit

import ch.nokillswit.telemetry.GcTotals
import ch.nokillswit.telemetry.JvmProbe
import ch.nokillswit.telemetry.JvmSources
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import java.lang.management.MemoryUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `telemetry/JvmProbe.kt`: the deltas (CPU over wall time, GC per collector), the old-gen peak read-and-reset, and the real MXBeans. */
class JvmProbeTest {
    private class FakeSources : JvmSources {
        var heapUsed = 100L
        var cpuNanos: Long? = 0L
        var wallNanos = 1_000L
        var processors = 4
        var oldGenPeak: Long? = 500L
        var oldGenResets = 0
        var gc = listOf(GcTotals("Copy", 10, 100))
        override fun heap() = MemoryUsage(0, heapUsed, 200, 1000)
        override fun nonHeap() = MemoryUsage(0, 70, 80, -1)
        override fun oldGenPeakBytesAndReset(): Long? = oldGenPeak.also { oldGenResets++ }
        override fun directBufferBytes(): Long? = 9
        override fun processCpuNanos() = cpuNanos
        override fun nanoTime() = wallNanos
        override fun availableProcessors() = processors
        override fun threadCount() = 31
        override fun gcTotals() = gc
    }

    private fun List<Reading>.value(metric: String, label: String = "") =
        singleOrNull { it.metric.name == metric && it.label == label }?.value

    @Test
    fun `memory, thread and buffer figures are read as they are`() {
        val readings = JvmProbe(FakeSources()).read()
        assertEquals(100.0, readings.value("mem.heap_used"))
        assertEquals(200.0, readings.value("mem.heap_committed"))
        assertEquals(500.0, readings.value("mem.old_gen_peak"))
        assertEquals(70.0, readings.value("mem.non_heap_used"))
        assertEquals(9.0, readings.value("mem.direct_buffers"))
        assertEquals(31.0, readings.value("jvm.threads"))
    }

    @Test
    fun `the old-generation peak is reset by every read and omitted when there is no such pool`() {
        val sources = FakeSources()
        val probe = JvmProbe(sources)
        probe.read()
        probe.read()
        assertEquals(2, sources.oldGenResets, "each read takes the peak and resets it")
        sources.oldGenPeak = null
        assertNull(probe.read().value("mem.old_gen_peak"))
    }

    @Test
    fun `cpu is process time over wall time as a fraction of the processors`() {
        val sources = FakeSources()
        val probe = JvmProbe(sources)
        sources.cpuNanos = 2_000L // 2000 ns of CPU over 1000 ns of wall on 4 processors
        sources.wallNanos = 2_000L
        assertEquals(0.5, probe.read().value("jvm.cpu_ratio"))
        sources.cpuNanos = 2_000L // idle for the next interval
        sources.wallNanos = 3_000L
        assertEquals(0.0, probe.read().value("jvm.cpu_ratio"))
        sources.cpuNanos = 1_000_000L // more CPU than the processors allow (a clock skew): clamped
        sources.wallNanos = 4_000L
        assertEquals(1.0, probe.read().value("jvm.cpu_ratio"))
    }

    @Test
    fun `cpu is omitted when the JVM cannot tell or the clock did not advance`() {
        val sources = FakeSources()
        val probe = JvmProbe(sources)
        sources.cpuNanos = null
        sources.wallNanos = 2_000L
        assertNull(probe.read().value("jvm.cpu_ratio"))
        sources.cpuNanos = 5L
        sources.wallNanos = 2_000L // same wall reading
        assertNull(probe.read().value("jvm.cpu_ratio"))
    }

    @Test
    fun `gc figures are deltas per collector, the first against the JVM start`() {
        val sources = FakeSources()
        val probe = JvmProbe(sources)
        val first = probe.read()
        assertEquals(10.0, first.value("jvm.gc_count", "Copy"))
        assertEquals(100.0, first.value("jvm.gc_time_ms", "Copy"))
        sources.gc = listOf(GcTotals("Copy", 13, 150), GcTotals("MarkSweepCompact", 1, 40))
        val second = probe.read()
        assertEquals(3.0, second.value("jvm.gc_count", "Copy"))
        assertEquals(50.0, second.value("jvm.gc_time_ms", "Copy"))
        assertEquals(1.0, second.value("jvm.gc_count", "MarkSweepCompact"), "a collector first seen later starts from zero")
        sources.gc = listOf(GcTotals("Copy", 13, 150), GcTotals("MarkSweepCompact", -1, -1))
        val third = probe.read()
        assertEquals(0.0, third.value("jvm.gc_count", "Copy"))
        assertNull(third.value("jvm.gc_count", "MarkSweepCompact"), "an unsupported collector has no figures")
    }

    @Test
    fun `the real MXBeans produce a plausible reading`() {
        val probe = JvmProbe()
        val readings = probe.read()
        assertTrue(assertNotNull(readings.value("mem.heap_used")) > 0)
        assertTrue(assertNotNull(readings.value("jvm.threads")) >= 1)
        assertTrue(readings.any { it.metric == TelemetryMetrics.GC_COUNT }, "every JVM has at least one collector bean")
        assertTrue(readings.all { it.value.isFinite() }, "no NaN or infinity ever reaches the table")
    }
}
