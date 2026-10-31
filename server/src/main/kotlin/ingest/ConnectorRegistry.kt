package ch.nokillswit.ingest

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Every connector, keyed by the [DataSourceKind] it serves. ADDITIVE: each connector module registers its own kind from its
 * `configureXxx` (`jira/Jira.kt`'s `configureJira`; the GitLab one follows it in `application.yaml`), so a second connector never
 * replaces the first, and registering one kind twice is a boot error. A kind nobody registered (GitLab switched off, or not yet built)
 * is simply absent: [IngestWorker] fails a job of that kind cleanly ([ConnectorUnavailableException]) instead of running it.
 * The `JobHandlerRegistry` pattern (`JobHandlers.kt`): filled during boot, read by the worker once it starts, so the map is concurrent
 * only defensively.
 */
class ConnectorRegistry {
    private val connectors = ConcurrentHashMap<DataSourceKind, Connector>()

    fun register(connector: Connector) {
        check(connectors.putIfAbsent(connector.kind, connector) == null) {
            "A connector for ${connector.kind} is already registered"
        }
    }

    fun connectorFor(kind: DataSourceKind): Connector? = connectors[kind]

    /** A snapshot of what is registered now (what [IngestWorker] is built from at `ApplicationStarted`). */
    fun connectors(): Map<DataSourceKind, Connector> = connectors.toMap()
}

val ConnectorRegistryKey = AttributeKey<ConnectorRegistry>("ConnectorRegistry")

/** The registry, created on first access so no connector module has to create it (the `jobHandlerRegistry()` shape). */
fun Application.connectorRegistry(): ConnectorRegistry = attributes.computeIfAbsent(ConnectorRegistryKey) { ConnectorRegistry() }

/**
 * The stable code a job fails with when its connection's kind has no registered connector (`last_sync_error_code` / `sync_jobs.error_code`;
 * a member of [SYNC_ERROR_CODES]): the kind is off or not built in this process, which no retry of the same job can fix.
 */
const val CONNECTOR_UNAVAILABLE = "CONNECTOR_UNAVAILABLE"

/** A claimed job whose [kind] has no registered connector; [IngestWorker] fails the job with [CONNECTOR_UNAVAILABLE]. */
class ConnectorUnavailableException(val kind: DataSourceKind) : Exception("No connector is registered for data source kind $kind")
