package ch.nokillswit

import ch.nokillswit.plugins.MetricRegistryKey
import ch.nokillswit.plugins.TelemetryRegistry
import ch.nokillswit.plugins.timerWindowSeconds
import com.codahale.metrics.Clock
import com.codahale.metrics.Histogram
import com.codahale.metrics.SlidingTimeWindowArrayReservoir
import com.codahale.metrics.Timer
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `plugins/Monitoring.kt`'s telemetry seam: the registry Ktor's request meters write into is a [TelemetryRegistry] whose timers keep
 * a TRUE sliding window (Dropwizard's default decaying reservoir would keep a burst's samples, weighted, for minutes), capped at
 * five minutes, published as `MetricRegistryKey`; Ktor's JVM metric sets (the thread-dumping gauges) are not registered.
 */
class TelemetryRegistryTest {
    private class ManualClock : Clock() {
        var nanos = 1_000_000_000L
        override fun getTick() = nanos
    }

    /** The reservoir behind a timer (Dropwizard keeps it in two private fields). */
    private fun reservoirOf(timer: Timer): Any {
        val histogram = Timer::class.java.getDeclaredField("histogram").apply { isAccessible = true }.get(timer)
        return Histogram::class.java.getDeclaredField("reservoir").apply { isAccessible = true }.get(histogram)
    }

    @Test
    fun `a timer forgets what is older than the window`() {
        val clock = ManualClock()
        val timer = TelemetryRegistry(windowSeconds = 60, clock = clock).timer("t")
        timer.update(100, TimeUnit.MILLISECONDS)
        clock.nanos += TimeUnit.SECONDS.toNanos(59)
        assertEquals(1, timer.snapshot.size(), "inside the window")
        clock.nanos += TimeUnit.SECONDS.toNanos(2)
        assertEquals(0, timer.snapshot.size(), "outside the window the sample is gone")
        assertEquals(1L, timer.count, "the lifetime count is untouched")
    }

    @Test
    fun `the window is the sample interval capped at five minutes`() {
        assertEquals(60L, timerWindowSeconds(60))
        assertEquals(300L, timerWindowSeconds(300))
        assertEquals(300L, timerWindowSeconds(600))
        assertEquals(300L, timerWindowSeconds(1800))
    }

    @Test
    fun `the application publishes a registry whose Ktor timers use the sliding reservoir and has no JVM gauge sets`() = testApplication {
        usePostgresTestcontainer()
        val registry = application.attributes[MetricRegistryKey]
        assertIs<TelemetryRegistry>(registry)
        jsonClient().get("/api/v1/health")
        assertTrue(registry.timers.isNotEmpty(), "Ktor's call timers are created through the registry: ${registry.names}")
        for ((name, timer) in registry.timers) {
            assertIs<SlidingTimeWindowArrayReservoir>(reservoirOf(timer), "timer $name")
        }
        val jvm = registry.names.filter { it.startsWith("jvm.") }
        assertTrue(jvm.isEmpty(), "registerJvmMetricSets is off: $jvm")
    }
}
