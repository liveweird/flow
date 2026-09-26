package ch.nokillswit.audit

import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory

/**
 * Security audit trail. Events are structured SLF4J logs on a dedicated logger with the [AUDIT]
 * marker — they flow through the Logback OpenTelemetryAppender (logback.xml) into the OTel logs
 * pipeline like everything else, so redirecting them to a collector/SIEM is env-only
 * (OTEL_LOGS_EXPORTER=otlp). The marker + event name + key/values survive as OTel attributes.
 *
 * Convention: `audit("<area>.<event>", "key" to value, …)`. Never log secrets (passwords,
 * tokens); emails and ids are fine — this is an internal operational trail.
 */
private val logger = LoggerFactory.getLogger("ch.nokillswit.audit")

val AUDIT: Marker = MarkerFactory.getMarker("AUDIT")

fun audit(event: String, vararg fields: Pair<String, Any?>) {
    var builder = logger.atInfo().addMarker(AUDIT).setMessage(event)
    for ((k, v) in fields) builder = builder.addKeyValue(k, v)
    builder.log()
}
