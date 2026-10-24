package ch.nokillswit.plugins

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import io.ktor.server.config.ApplicationConfig
import com.codahale.metrics.*
import io.ktor.server.metrics.dropwizard.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import io.ktor.http.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*

/** The shortest non-zero `monitoring.reporterIntervalSeconds`: a tighter dump is the idle-allocation and log-volume cost avoided. */
internal const val MIN_REPORTER_INTERVAL_SECONDS = 30

/** The longest `monitoring.reporterIntervalSeconds` (one hour). */
internal const val MAX_REPORTER_INTERVAL_SECONDS = 3600

/**
 * `monitoring.reporterIntervalSeconds` (env `METRICS_REPORT_INTERVAL_SECONDS`, default 300): how often the Dropwizard registry is
 * dumped to the `metrics` logger. `null` = `0` = the reporter is off (the metrics are still collected). Anything else outside
 * [MIN_REPORTER_INTERVAL_SECONDS]..[MAX_REPORTER_INTERVAL_SECONDS], or non-numeric, refuses startup in every mode.
 */
internal fun reporterIntervalSeconds(config: ApplicationConfig): Int? {
    val key = "monitoring.reporterIntervalSeconds"
    val seconds = requireConfigInt(config, key, min = 0, max = MAX_REPORTER_INTERVAL_SECONDS)
    check(seconds == 0 || seconds >= MIN_REPORTER_INTERVAL_SECONDS) {
        "Config \"$key\" must be 0 (off) or between $MIN_REPORTER_INTERVAL_SECONDS and $MAX_REPORTER_INTERVAL_SECONDS (was \"$seconds\")"
    }
    return seconds.takeIf { it != 0 }
}

/** `telemetry.sampleIntervalSeconds` bounds (the sampler's cadence, `telemetry/TelemetryConfig.kt`; read here for the timers' window). */
internal const val MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS = 60
internal const val MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS = 1800

/** One hour: the sample interval must divide it, so an hour's raw samples tile it exactly (the stored `resolution` is the interval). */
private const val SECONDS_PER_HOUR = 3600

/** The request timers never keep more than this much history, whatever the sample interval (see [TelemetryRegistry]). */
internal const val MAX_TIMER_WINDOW_SECONDS = 300L

/**
 * `telemetry.sampleIntervalSeconds` (env `TELEMETRY_SAMPLE_INTERVAL_SECONDS`, default 300), boot-validated: an integer in
 * [MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS]..[MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS] that divides 3600. Read by this plugin because
 * the registry's request timers are windowed by it ([TelemetryRegistry]) and the telemetry package sits ABOVE `plugins/` (nothing
 * may import it from here), so the one bound lives here and `telemetry/TelemetryConfig.kt` calls it.
 */
internal fun telemetrySampleIntervalSeconds(config: ApplicationConfig): Int {
    val key = "telemetry.sampleIntervalSeconds"
    val seconds = requireConfigInt(
        config, key, min = MIN_TELEMETRY_SAMPLE_INTERVAL_SECONDS, max = MAX_TELEMETRY_SAMPLE_INTERVAL_SECONDS,
    )
    check(SECONDS_PER_HOUR % seconds == 0) { "Config \"$key\" must divide 3600, so the samples tile an hour (was \"$seconds\")" }
    return seconds
}

/** The timers' window: the sample interval, capped at [MAX_TIMER_WINDOW_SECONDS]. */
internal fun timerWindowSeconds(sampleIntervalSeconds: Int): Long = minOf(sampleIntervalSeconds.toLong(), MAX_TIMER_WINDOW_SECONDS)

/**
 * The Dropwizard registry whose timers keep a TRUE sliding window ([SlidingTimeWindowArrayReservoir], [timerWindowSeconds]: the
 * last sample interval, at most 5 minutes) instead of Dropwizard's default exponentially-decaying reservoir, whose percentiles lag
 * a burst for minutes: the telemetry sampler reads p50/p95 of the window since its previous tick. The price is memory: the
 * reservoir keeps every measurement of the window (two longs, ~16 bytes, per update of each timer it feeds) until it ages out, so
 * the cap bounds it at a few MB even for thousands of requests a window. Ktor's plugin creates every timer through
 * [MetricRegistry.timer], which is the one method overridden. [clock] is injectable for tests.
 */
class TelemetryRegistry(private val windowSeconds: Long, private val clock: Clock = Clock.defaultClock()) : MetricRegistry() {
    override fun timer(name: String): Timer =
        timer(name) { Timer(SlidingTimeWindowArrayReservoir(windowSeconds, TimeUnit.SECONDS, clock), clock) }
}

/**
 * Published by [configureMonitoring]: the Dropwizard registry Ktor's request meters write into. The telemetry sampler reads the
 * timers from it (`telemetry/`); the registry is the seam, so nothing below `telemetry/` imports that package.
 */
val MetricRegistryKey = AttributeKey<MetricRegistry>("MetricRegistry")

fun Application.configureMonitoring() {
    val intervalSeconds = reporterIntervalSeconds(environment.config)
    val telemetryRegistry = TelemetryRegistry(timerWindowSeconds(telemetrySampleIntervalSeconds(environment.config)))
    attributes.put(MetricRegistryKey, telemetryRegistry)
    var reporter: Slf4jReporter? = null
    install(DropwizardMetrics) {
        registry = telemetryRegistry
        // The default also registers Dropwizard's JVM gauge sets, whose thread-states gauge dumps every thread on each
        // reporter run; the telemetry sampler reads the MXBeans itself (threadCount, no dump).
        registerJvmMetricSets = false
        if (intervalSeconds != null) {
            reporter = Slf4jReporter.forRegistry(registry)
                .outputTo(LoggerFactory.getLogger("metrics"))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build()
                .also { it.start(intervalSeconds.toLong(), TimeUnit.SECONDS) }
        }
    }
    // The reporter owns a scheduler thread — stop it with the application, or every
    // testApplication block (and every redeploy) leaks one.
    monitor.subscribe(ApplicationStopped) { reporter?.stop() }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        // Client-supplied ids reach logs and OTel attributes, so cap them: ≤64 URL-safe
        // chars (UUIDs and trace ids fit); anything else is ignored, not an error.
        verify { callId: String ->
            callId.length in 1..64 && callId.all { it.isLetterOrDigit() || it in "-_." }
        }
    }
}
