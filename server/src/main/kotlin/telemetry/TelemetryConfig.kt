package ch.nokillswit.telemetry

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.plugins.telemetrySampleIntervalSeconds
import io.ktor.server.config.ApplicationConfig

/** `telemetry.storageIntervalSeconds` bounds (15 minutes .. one day). */
internal const val MIN_STORAGE_INTERVAL_SECONDS = 900
internal const val MAX_STORAGE_INTERVAL_SECONDS = 86_400

/** `telemetry.connectionEstimateIntervalSeconds` bounds (one hour .. seven days). */
internal const val MIN_CONNECTION_ESTIMATE_INTERVAL_SECONDS = 3_600
internal const val MAX_CONNECTION_ESTIMATE_INTERVAL_SECONDS = 604_800

/**
 * The `telemetry.*` block (`application.yaml`, `.claude/docs/observability.md` "Telemetry"). Read and validated on EVERY boot in
 * every mode and role - a malformed value refuses startup naming its key even when `enabled` is false, the `ingest.*` idiom.
 */
data class TelemetryConfig(
    val enabled: Boolean,
    /**
     * The per-process sample cadence (60..1800, a divisor of 3600): the stored raw `resolution`, and the request timers' window
     * (capped at 5 minutes, `plugins/Monitoring.kt`).
     */
    val sampleIntervalSeconds: Int,
    /** How often the cluster section measures storage sizes (read by the storage collector, a later commit). */
    val storageIntervalSeconds: Int,
    /** How often the cluster section estimates per-connection storage (read by the estimate collector, a later commit). */
    val connectionEstimateIntervalSeconds: Int,
) {
    val sampleIntervalMillis: Long get() = sampleIntervalSeconds * 1000L

    /**
     * The per-statement budget of every telemetry transaction (the transaction's `queryTimeout`, whole seconds): a quarter of the
     * interval, so a statement is always cancelled well inside the tick's own `interval / 2` budget.
     */
    val statementTimeoutSeconds: Int
        get() = (sampleIntervalSeconds / 4).coerceIn(MIN_STATEMENT_TIMEOUT_SECONDS, MAX_STATEMENT_TIMEOUT_SECONDS)
}

private const val MIN_STATEMENT_TIMEOUT_SECONDS = 15
private const val MAX_STATEMENT_TIMEOUT_SECONDS = 60

fun readTelemetryConfig(config: ApplicationConfig): TelemetryConfig {
    val enabledKey = "telemetry.enabled"
    val rawEnabled = config.property(enabledKey).getString()
    val enabled = rawEnabled.toBooleanStrictOrNull() ?: error("Config \"$enabledKey\" must be true or false (was \"$rawEnabled\")")
    return TelemetryConfig(
        enabled = enabled,
        sampleIntervalSeconds = telemetrySampleIntervalSeconds(config),
        storageIntervalSeconds = requireConfigInt(
            config, "telemetry.storageIntervalSeconds", min = MIN_STORAGE_INTERVAL_SECONDS, max = MAX_STORAGE_INTERVAL_SECONDS,
        ),
        connectionEstimateIntervalSeconds = requireConfigInt(
            config, "telemetry.connectionEstimateIntervalSeconds",
            min = MIN_CONNECTION_ESTIMATE_INTERVAL_SECONDS, max = MAX_CONNECTION_ESTIMATE_INTERVAL_SECONDS,
        ),
    )
}
