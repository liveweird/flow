package ch.nokillswit.plugins

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.*
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

fun Application.configureMonitoring() {
    val intervalSeconds = reporterIntervalSeconds(environment.config)
    var reporter: Slf4jReporter? = null
    install(DropwizardMetrics) {
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
