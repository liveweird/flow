package ch.nokillswit.metrics

import ch.nokillswit.ingest.ConfigRevisionSource
import ch.nokillswit.ingest.JobHandler
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.SyncJobKind

/**
 * Plugs the metrics layer into `ingest/JobHandlers.kt`'s registry (checkup D5 — `ingest/` never
 * imports `metrics/`): the DERIVE handler (its return value is the `config_revision` the run read,
 * compared by `IngestWorker.onSucceeded` against [MetricsSettingsService.currentRevision]), then the
 * three connector-agnostic PURGE steps in the order the worker runs them — the per-connection config
 * drain, the sprint-calendar drain (A32), then the derived-star drain (`.claude/docs/ingestion.md` "PURGE").
 */
fun JobHandlerRegistry.registerMetricsHandlers(
    deriver: MetricsDeriver,
    metricsConfig: MetricsConfigService,
    sprintCalendars: SprintCalendarService,
    metricsSettings: MetricsSettingsService,
    metricsStore: MetricsStore,
) {
    register(SyncJobKind.DERIVE, JobHandler { context -> deriver.derive(context) })
    registerPurgeStep(PurgeStep { connectionId -> metricsConfig.purgeConnectionConfig(connectionId) })
    registerPurgeStep(PurgeStep { connectionId -> sprintCalendars.purgeConnection(connectionId) })
    registerPurgeStep(PurgeStep { connectionId -> metricsStore.purgeAll(connectionId) })
    registerConfigRevisionSource(ConfigRevisionSource { metricsSettings.currentRevision() })
}
