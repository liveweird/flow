package ch.nokillswit.telemetry

import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * How a heavy collector reads: every statement in a SHORT transaction of its own, under [timeoutSeconds] as its `queryTimeout` (the
 * only way a statement timeout is set), so it holds no lock beyond that one statement and nothing it reads is part of the cluster
 * transaction.
 */
class HeavyReader internal constructor(private val store: TelemetryStore, val timeoutSeconds: Int) {
    suspend fun <T> read(block: suspend R2dbcTransaction.() -> T): T = store.transaction(timeoutSeconds, block)
}

/**
 * A collector whose reads are too heavy for the cluster transaction (whole-table counts, `.claude/docs/observability.md` "Telemetry"):
 * the storage sizes and the per-connection estimate. The cluster section only decides that one is due and CLAIMS the run by writing its
 * [marker] (value 0), under the lock and committed with the cheap collectors' readings, so at most one attempt is made per
 * [intervalMillis] whatever its outcome; the sampler then runs [collect] after that transaction, each statement through the
 * [HeavyReader], under the heavy phase's own budget, inserts the readings in a transaction of their own and sets the marker to the run's
 * duration in milliseconds (at least 1: a marker still at 0 is a claim that did not complete).
 */
class HeavyCollector(
    val name: String,
    val marker: MetricDef,
    val intervalMillis: Long,
    val collect: suspend HeavyReader.() -> List<Reading>,
)

/** The heavy collectors this process claims when it holds the cluster lock: storage sizes and the per-connection estimate. */
internal fun heavyCollectors(config: TelemetryConfig): List<HeavyCollector> = listOf(
    HeavyCollector("storage", TelemetryMetrics.STORAGE_TICK, config.storageIntervalSeconds * MILLIS_PER_SECOND) { storageReadings() },
    HeavyCollector(
        "connectionEstimate", TelemetryMetrics.ESTIMATE_TICK, config.connectionEstimateIntervalSeconds * MILLIS_PER_SECOND,
    ) { connectionEstimateReadings() },
)

private const val MILLIS_PER_SECOND = 1000L
