package ch.nokillswit.plugins

import io.ktor.server.application.*
import com.codahale.metrics.*
import io.ktor.server.metrics.dropwizard.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import io.ktor.http.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*

fun Application.configureMonitoring() {
    var reporter: Slf4jReporter? = null
    install(DropwizardMetrics) {
        reporter = Slf4jReporter.forRegistry(registry)
            .outputTo(LoggerFactory.getLogger("metrics"))
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .also { it.start(10, TimeUnit.SECONDS) }
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
