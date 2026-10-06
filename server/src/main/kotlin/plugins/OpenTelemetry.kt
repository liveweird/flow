package ch.nokillswit.plugins

import ch.nokillswit.getOpenTelemetry
import io.ktor.http.*
import io.ktor.server.application.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Upper bound on how long the SDK-closing hook waits for Ktor's own stop. The budget it sits in: Ktor's
 * shutdown grace + timeout + the ingest worker's join (`SHUTDOWN_JOIN_TIMEOUT_MS`) come to ~11 s with the
 * defaults, under this 20 s; and 20 s + the SDK's own <=10 s `close()` stays within the Kubernetes default
 * `terminationGracePeriodSeconds` (30 s), after which the pod is SIGKILLed mid-flush. Raising Ktor's shutdown
 * timeout or lowering the pod's grace period must revisit this number (`observability.md` "Shutdown ordering").
 */
private const val STOP_WAIT_SECONDS = 20L

/**
 * Closes (= flushes, then shuts down) the OpenTelemetry SDK as the LAST thing a JVM exit does.
 *
 * JVM shutdown hooks run concurrently and unordered. The SDK's own hook (now disabled in `core`'s
 * `getOpenTelemetry`) used to race Ktor's stop hook: once the logs pipeline was closed, every audit
 * line emitted while Ktor was still stopping — `sync_job.released` from the worker's
 * `ApplicationStopping` handler — was dropped. This hook instead waits for [ApplicationStopped]
 * (the last handler of Ktor's stop, see [subscribeTelemetryShutdown]) before closing. If the application never finished starting
 * (a boot failure) there is no Ktor stop to wait for, so it flushes immediately — the failure's own
 * log lines must still be exported.
 */
internal class TelemetryShutdown(private val sdk: OpenTelemetrySdk) {
    private val started = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)

    fun markStarted() = started.set(true)

    fun markStopped() = stopped.countDown()

    /** Blocks (bounded) until Ktor has stopped when it had started, then closes the SDK. */
    fun closeAfterStop() {
        if (started.get()) stopped.await(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        sdk.close()
    }
}

/**
 * Ktor raises an event's handlers in subscription order, and every module after this one subscribes its own
 * [ApplicationStopped] work (the pool dispose, the Jira client close — both log). The latch handler is therefore
 * subscribed from INSIDE the [ApplicationStarted] handler, which fires after every module has subscribed, so it
 * is the last [ApplicationStopped] handler to run and the SDK outlives them all.
 */
internal fun Application.subscribeTelemetryShutdown(shutdown: TelemetryShutdown) {
    monitor.subscribe(ApplicationStarted) { app ->
        shutdown.markStarted()
        app.monitor.subscribe(ApplicationStopped) { shutdown.markStopped() }
    }
}

fun Application.configureOpenTelemetry() {
    val openTelemetry = getOpenTelemetry(serviceName = "flow")
    val shutdown = TelemetryShutdown(openTelemetry)
    subscribeTelemetryShutdown(shutdown)
    Runtime.getRuntime().addShutdownHook(Thread(shutdown::closeAfterStop, "otel-flush-after-stop"))
    // Wire the Logback OTel appender to this SDK; flushes the pre-install buffer of boot logs.
    OpenTelemetryAppender.install(openTelemetry)

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
        capturedRequestHeaders(HttpHeaders.UserAgent)
        // No spanKindExtractor: these are HTTP SERVER spans and the plugin's default says so.
        // (The Ktor scaffold's template maps POST→PRODUCER/else→CLIENT, which mislabels
        // every span for trace consumers — deliberately removed.)
        attributesExtractor {
            onStart {
                attributes.put("start-time", System.currentTimeMillis())
            }
            onEnd {
                attributes.put("end-time", System.currentTimeMillis())
            }
        }
    }
}
