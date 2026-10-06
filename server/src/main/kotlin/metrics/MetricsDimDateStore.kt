package ch.nokillswit.metrics

import ch.nokillswit.metrics.MetricsTables.DimDate
import ch.nokillswit.metrics.MetricsTables.DimSprint
import ch.nokillswit.metrics.MetricsTables.FactEpicPlan
import ch.nokillswit.metrics.MetricsTables.FactTaskDelivery
import ch.nokillswit.metrics.MetricsTables.FactWorklog
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The PostgreSQL advisory-lock key of [MetricsStore.ensureDimDate] — the ASCII bytes of
 * "FlowDate" as a positive `bigint` (the single-key form `pg_advisory_xact_lock(key)`). The repo's only other advisory
 * lock is `SyncJobsService.claim`'s per-connection try-lock, in the two-`int` key space, which cannot collide with this
 * one; no other code may take THIS key (`.claude/docs/persistence.md` lists both).
 */
internal const val DIM_DATE_LOCK_KEY = 0x466C6F7744617465L

/** Rows per `batchUpsert` in [MetricsStore.ensureDimDate] (a cold table is tens of thousands of rows). */
private const val DIM_DATE_WRITE_BATCH = 1000

/**
 * The global `metrics.dim_date` reconciliation ([MetricsStore.ensureDimDate]) and the two fact reads
 * that size the span it must cover — the `dim_date` / calendar concern of [MetricsStore].
 */
internal class MetricsDimDateStore(private val database: R2dbcDatabase) {

    /**
     * Brings the global `metrics.dim_date` in line with [calendar] over the whole span the table and
     * [range] together cover, in its OWN short transaction — never the caller's. Returns the number of
     * rows written (0 in steady state).
     *
     * Why a separate transaction: `dim_date` is shared by every connection's DERIVE, and a write made
     * inside a DERIVE's one big transaction holds its row locks until that (minutes-long) commit —
     * two DERIVEs with different ranges could deadlock (SQLSTATE 40P01) or, at best, serialized on
     * the locks. [inTopLevelSuspendTransaction] takes a fresh pooled connection and COMMITS before
     * returning even when called from inside a `suspendTransaction`, so the caller's later statements
     * (PostgreSQL `READ COMMITTED` — nothing in this repo sets another level — take a fresh snapshot
     * per statement) see the committed rows while the DERIVE holds no `dim_date` lock at all. The one
     * serialization point left is [DIM_DATE_LOCK_KEY]: a transaction-scoped advisory lock taken FIRST,
     * so concurrent ensures queue (milliseconds) instead of deadlocking on row locks taken in
     * different orders. No table references `dim_date(day)` (V16/V17 declare no foreign key to it).
     *
     * What is written: the target span is the union of the stored days' `[min, max]` and [range]
     * (gaps between disjoint ranges are filled); every day in it is recomputed with [calendar] and
     * upserted ONLY when missing or when its `day_start_ms`/`day_end_ms`/`is_working_day` differ — so
     * a time-zone/weekend/holiday change rewrites EVERY stored row, not just the run's own range,
     * while an unchanged calendar writes nothing. `config_revision` is the revision the row's current
     * content was written under (stamped on written rows only); the `day` key is never updated.
     *
     * **A stale caller only inserts.** Under the advisory lock the CURRENT global revision
     * (`metrics.settings.config_revision`) is read: a caller whose [configRevision] is lower started under an
     * older calendar, and rewriting differing rows to it would roll the table back under a newer run (also
     * across an A→B→A change, where row stamps alone cannot tell) — so it inserts MISSING rows only and never
     * updates one. That stale run is itself re-derived (`bumpRevision` enqueued it). Only a caller at the
     * current revision rewrites differing rows.
     */
    suspend fun ensureDimDate(calendar: WorkingCalendar, range: DimDateRange, configRevision: Long): Int =
        inTopLevelSuspendTransaction(database) {
            exec("SELECT pg_advisory_xact_lock($DIM_DATE_LOCK_KEY)")
            val currentRevision = MetricsSettingsService.Settings.select(MetricsSettingsService.Settings.configRevision).toList()
                .single()[MetricsSettingsService.Settings.configRevision]
            val mayRewrite = configRevision >= currentRevision
            val stored = DimDate.selectAll().toList().associate {
                it[DimDate.day] to WorkingCalendar.DimDateRow(
                    it[DimDate.day], it[DimDate.dayStartMs], it[DimDate.dayEndMs], it[DimDate.isWorkingDay],
                )
            }
            val rangeFrom = calendar.dayOf(range.fromMs)
            val rangeTo = calendar.dayOf(range.toMs)
            val fromDay = stored.keys.minOrNull()?.let { minOf(LocalDate.parse(it), rangeFrom) } ?: rangeFrom
            val toDay = stored.keys.maxOrNull()?.let { maxOf(LocalDate.parse(it), rangeTo) } ?: rangeTo
            val changed = calendar.dimDateRows(fromDay, toDay).filter { wanted ->
                val existing = stored[wanted.day]
                existing == null || (mayRewrite && existing != wanted)
            }
            changed.chunked(DIM_DATE_WRITE_BATCH).forEach { chunk ->
                DimDate.batchUpsert(chunk, DimDate.day) { row ->
                    this[DimDate.day] = row.day
                    this[DimDate.dayStartMs] = row.dayStartMs
                    this[DimDate.dayEndMs] = row.dayEndMs
                    this[DimDate.isWorkingDay] = row.isWorkingDay
                    this[DimDate.configRevision] = configRevision
                }
            }
            changed.size
        }

    /**
     * `(start_at, due_at)` of every CURRENT epic baseline (`superseded_at IS NULL`, start/due/budget
     * all set) — the windows [runFlowStep]'s PV spreading reads `dim_date` over. Not horizon-filtered
     * here: [dimDateRange] applies [inPvHorizon] itself.
     */
    suspend fun currentEpicPlanWindows(connectionId: UInt): List<Pair<Long, Long>> = suspendTransaction(database) {
        FactEpicPlan.select(FactEpicPlan.startAt, FactEpicPlan.dueAt).where {
            (FactEpicPlan.connectionId eq connectionId) and FactEpicPlan.supersededAt.isNull() and
                FactEpicPlan.startAt.isNotNull() and FactEpicPlan.dueAt.isNotNull() and FactEpicPlan.budgetMd.isNotNull()
        }.toList().map { it[FactEpicPlan.startAt]!! to it[FactEpicPlan.dueAt]!! }
    }

    /**
     * The earliest timestamp any flow-aggregate join places on a `dim_date` day for this connection:
     * `fact_worklog.started_at`, `dim_sprint.start_at`, `fact_task_delivery.done_at`. Null when there
     * are none.
     */
    suspend fun earliestFactEventMs(connectionId: UInt): Long? = suspendTransaction(database) {
        val worklog = FactWorklog.startedAt.min()
        val sprint = DimSprint.startAt.min()
        val done = FactTaskDelivery.doneAt.min()
        listOfNotNull(
            FactWorklog.select(worklog).where { FactWorklog.connectionId eq connectionId }.toList().single()[worklog],
            DimSprint.select(sprint).where { DimSprint.connectionId eq connectionId }.toList().single()[sprint],
            FactTaskDelivery.select(done).where { FactTaskDelivery.connectionId eq connectionId }.toList().single()[done],
        ).minOrNull()
    }
}
