package ch.nokillswit

import ch.nokillswit.audit.audit
import ch.nokillswit.plugins.configureOpenTelemetry
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * The child JVM of [ShutdownAuditTest]: the production shutdown wiring in miniature — a real Netty
 * `embeddedServer(...).start(wait = true)` (which registers Ktor's own JVM shutdown hook, exactly as
 * `EngineMain` does) with the real `configureOpenTelemetry`, and an `ApplicationStopping` handler that
 * audits after a short delay, the shape of the ingest worker's lease release (a DB round trip, then
 * `audit("sync_job.released", …)`). The parent SIGTERMs it once the engine is up and reads the console
 * log exporter's stdout.
 */
object ShutdownAuditProbe {
    const val EVENT = "probe.released_on_stop"
    private const val RELEASE_ROUND_TRIP_MS = 200L

    @JvmStatic
    fun main(args: Array<String>) {
        embeddedServer(Netty, port = 0) {
            configureOpenTelemetry()
            monitor.subscribe(ApplicationStopping) {
                Thread.sleep(RELEASE_ROUND_TRIP_MS)
                audit(EVENT, "workerId" to "probe")
            }
        }.start(wait = true)
    }
}
