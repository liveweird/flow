package ch.nokillswit.metrics

import ch.nokillswit.metrics.MetricsTables.AggDailyFlow
import ch.nokillswit.metrics.MetricsTables.AggDailyWip
import ch.nokillswit.metrics.MetricsTables.DeriveRuns
import ch.nokillswit.metrics.MetricsTables.DimDomain
import ch.nokillswit.metrics.MetricsTables.DimEpic
import ch.nokillswit.metrics.MetricsTables.DimSprint
import ch.nokillswit.metrics.MetricsTables.DimTask
import ch.nokillswit.metrics.MetricsTables.FactEpicDelivery
import ch.nokillswit.metrics.MetricsTables.FactEpicPlan
import ch.nokillswit.metrics.MetricsTables.FactSprint
import ch.nokillswit.metrics.MetricsTables.FactSprintScope
import ch.nokillswit.metrics.MetricsTables.FactSprintSnapshot
import ch.nokillswit.metrics.MetricsTables.FactTaskDelivery
import ch.nokillswit.metrics.MetricsTables.FactWorklog
import ch.nokillswit.metrics.MetricsTables.ItemBlocked
import ch.nokillswit.metrics.MetricsTables.ItemEstimate
import ch.nokillswit.metrics.MetricsTables.ItemStage
import ch.nokillswit.metrics.MetricsTables.TaskAssignee
import ch.nokillswit.metrics.MetricsTables.TaskDomain
import ch.nokillswit.metrics.MetricsTables.TaskEpic
import ch.nokillswit.metrics.MetricsTables.TaskSprint
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The PURGE job's generic drain of every rebuildable `metrics.*` row ([MetricsStore.purgeAll] delegates here). */
internal class MetricsPurge(private val database: R2dbcDatabase) {

    /**
     * Every rebuildable `metrics.*` row for one connection, in dependency-safe order — the PURGE
     * job's generic step (`ingest/IngestWorker.kt`, `.claude/docs/ingestion.md` "PURGE"). Snapshot
     * rows need `SET LOCAL metrics.allow_snapshot_delete = 'on'` first (the ONE sanctioned bypass of
     * the immutability trigger, `.claude/docs/persistence.md`) — scoped to the CALLER's transaction,
     * so this whole method must run inside `suspendTransaction`. `derive_runs` (review round 2b)
     * joins the drain too — a purged connection's own run HISTORY has no reader left once its raw/
     * norm/star rows are all gone, the `sync_jobs` hard-delete-on-terminal precedent applied to a
     * table that, unlike `sync_jobs`, has no opportunistic retention window of its own until a
     * connection is actually deleted.
     */
    suspend fun purgeAll(connectionId: UInt) = suspendTransaction(database) {
        exec("SET LOCAL metrics.allow_snapshot_delete = 'on'")
        FactSprintSnapshot.deleteWhere { FactSprintSnapshot.connectionId eq connectionId }
        FactSprint.deleteWhere { FactSprint.connectionId eq connectionId }
        FactSprintScope.deleteWhere { FactSprintScope.connectionId eq connectionId }
        FactWorklog.deleteWhere { FactWorklog.connectionId eq connectionId }
        FactEpicPlan.deleteWhere { FactEpicPlan.connectionId eq connectionId }
        FactEpicDelivery.deleteWhere { FactEpicDelivery.connectionId eq connectionId }
        FactTaskDelivery.deleteWhere { FactTaskDelivery.connectionId eq connectionId }
        AggDailyWip.deleteWhere { AggDailyWip.connectionId eq connectionId }
        AggDailyFlow.deleteWhere { AggDailyFlow.connectionId eq connectionId }
        ItemBlocked.deleteWhere { ItemBlocked.connectionId eq connectionId }
        ItemStage.deleteWhere { ItemStage.connectionId eq connectionId }
        ItemEstimate.deleteWhere { ItemEstimate.connectionId eq connectionId }
        TaskSprint.deleteWhere { TaskSprint.connectionId eq connectionId }
        TaskAssignee.deleteWhere { TaskAssignee.connectionId eq connectionId }
        TaskDomain.deleteWhere { TaskDomain.connectionId eq connectionId }
        TaskEpic.deleteWhere { TaskEpic.connectionId eq connectionId }
        DimSprint.deleteWhere { DimSprint.connectionId eq connectionId }
        DimEpic.deleteWhere { DimEpic.connectionId eq connectionId }
        DimTask.deleteWhere { DimTask.connectionId eq connectionId }
        DimDomain.deleteWhere { DimDomain.connectionId eq connectionId }
        DeriveRuns.deleteWhere { DeriveRuns.connectionId eq connectionId.toInt() }
        Unit
    }
}
