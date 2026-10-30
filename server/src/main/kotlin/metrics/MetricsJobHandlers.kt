package ch.nokillswit.metrics

import ch.nokillswit.ingest.BackfillFloorSource
import ch.nokillswit.ingest.ConfigRevisionSource
import ch.nokillswit.ingest.JobHandler
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.infra.db.nowMillis

/**
 * Plugs the metrics layer into `ingest/JobHandlers.kt`'s registry (checkup D5 — `ingest/` never
 * imports `metrics/`): the DERIVE handler (its return value is the `config_revision` the run read,
 * compared by `IngestWorker.onSucceeded` against [MetricsSettingsService.currentRevision]), then the
 * three connector-agnostic PURGE steps in the order the worker runs them — the per-connection config
 * drain, the sprint-calendar drain (A32), then the derived-star drain (`.claude/docs/ingestion.md` "PURGE"). It also registers the
 * backfill floor source (A48): the history horizon's first day, which the data-source writes check `backfillFrom` against.
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
    // The horizon's first day in the metrics time zone as of today -- the same `historyHorizon(calendar day)` every report reads.
    registerBackfillFloorSource(
        BackfillFloorSource { metricsSettings.readWithCalendar().let { it.settings.historyHorizon(it.calendar.dayOf(nowMillis()))?.from } },
    )
}
