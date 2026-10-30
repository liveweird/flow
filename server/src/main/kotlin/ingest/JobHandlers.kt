package ch.nokillswit.ingest

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The seam that keeps `ingest/` free of `metrics/` (checkup D5): `IngestWorker` dispatches the
 * connector-agnostic job kinds through this registry instead of importing the package that
 * implements them, and `metrics/` — which already depends on `ingest/` — plugs into it from
 * `configureMetrics` (`metrics/MetricsJobHandlers.kt`). No DI framework: like every service the
 * registry travels on `Application.attributes` ([jobHandlerRegistry]).
 */
fun interface JobHandler {
    /**
     * Runs one claimed job of the kind this handler is registered for. The return value is the
     * shared config revision the run read at its own start (DERIVE's `metrics.settings.config_revision`),
     * or `null` when the kind has no such notion — `IngestWorker.onSucceeded` compares it against
     * [ConfigRevisionSource.currentRevision] so a config change that coalesced into the in-flight job
     * is never silently lost.
     */
    suspend fun run(context: SyncJobRunContext): Long?
}

/** The live value a [JobHandler]'s returned revision is compared against after a successful run. */
fun interface ConfigRevisionSource {
    suspend fun currentRevision(): Long
}

/**
 * The first day a data source's `backfillFrom` may name, as the reporting history horizon (domain-model A48) sets it today, or
 * `null` when the horizon is off. Read at WRITE time by `DataSourceService` (create, and an update that moves the date) -- never
 * by a stream: the stored date is a running backfill's identity, so the floor must not slide with the clock.
 */
fun interface BackfillFloorSource {
    suspend fun floor(): LocalDate?
}

/**
 * Job-kind handlers, extra PURGE steps (the connector-owned [PurgeStep] shape from `Connector.kt`, in
 * registration order — `IngestWorker` runs them after the connector's own `purgeSteps`) and the
 * revision source for the post-run staleness check, plus the [BackfillFloorSource] the data-source writes consult. Filled
 * during boot (module order: `configureMetrics` before `configureIngestWorker`), read by the worker
 * once it starts, so the collections are concurrent only defensively.
 */
class JobHandlerRegistry {
    private val handlers = ConcurrentHashMap<SyncJobKind, JobHandler>()
    private val purgeSteps = CopyOnWriteArrayList<PurgeStep>()

    @Volatile
    var configRevisionSource: ConfigRevisionSource? = null
        private set

    fun register(kind: SyncJobKind, handler: JobHandler) {
        check(handlers.putIfAbsent(kind, handler) == null) { "A handler for job kind $kind is already registered" }
    }

    fun handlerFor(kind: SyncJobKind): JobHandler? = handlers[kind]

    fun registerPurgeStep(step: PurgeStep) {
        purgeSteps.add(step)
    }

    fun purgeSteps(): List<PurgeStep> = purgeSteps.toList()

    fun registerConfigRevisionSource(source: ConfigRevisionSource) {
        check(configRevisionSource == null) { "A config revision source is already registered" }
        configRevisionSource = source
    }

    /** `null` until `metrics/` registers one (a registry nobody filled, as in a direct test construction, imposes no horizon). */
    @Volatile
    var backfillFloorSource: BackfillFloorSource? = null
        private set

    fun registerBackfillFloorSource(source: BackfillFloorSource) {
        check(backfillFloorSource == null) { "A backfill floor source is already registered" }
        backfillFloorSource = source
    }
}

val JobHandlerRegistryKey = AttributeKey<JobHandlerRegistry>("JobHandlerRegistry")

/**
 * The registry, created on first access so neither module has to create it; `configureMetrics` must still register
 * its handlers before the worker starts (`configureIngestWorker` checks at `ApplicationStarted`).
 */
fun Application.jobHandlerRegistry(): JobHandlerRegistry = attributes.computeIfAbsent(JobHandlerRegistryKey) { JobHandlerRegistry() }
