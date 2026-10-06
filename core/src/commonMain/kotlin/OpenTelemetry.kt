package ch.nokillswit

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.semconv.ServiceAttributes

/**
 * The SDK is returned concrete so its OWNER closes it: the autoconfigure builder's own JVM shutdown
 * hook is disabled, because it runs concurrently with Ktor's stop hook and closed the logs pipeline
 * while `ApplicationStopping` handlers were still auditing — those lines were silently dropped
 * (`server`'s `plugins/OpenTelemetry.kt` closes it AFTER the application has stopped).
 */
fun getOpenTelemetry(serviceName: String): OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        // Defaults via addPropertiesSupplier (lowest precedence) so OTEL_* env vars can override —
        // e.g. flip OTEL_LOGS_EXPORTER=otlp later to ship logs to a collector with no code change.
        .addPropertiesSupplier {
            mapOf(
                "otel.metrics.exporter" to "none",
                "otel.traces.exporter" to "none",
                "otel.logs.exporter" to "console", // interim: System.out; overridable by OTEL_LOGS_EXPORTER
            )
        }
        .addResourceCustomizer { oldResource, _ ->
            oldResource.toBuilder()
                .putAll(oldResource.attributes)
                .put(ServiceAttributes.SERVICE_NAME, serviceName)
                .build()
        }
        .build()
        .openTelemetrySdk
