package ch.nokillswit.plugins

import ch.nokillswit.getOpenTelemetry
import io.ktor.http.*
import io.ktor.server.application.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender

fun Application.configureOpenTelemetry() {
    val openTelemetry = getOpenTelemetry(serviceName = "flow")
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
