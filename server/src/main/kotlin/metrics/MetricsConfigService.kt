package ch.nokillswit.metrics

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val MetricsConfigServiceKey = AttributeKey<MetricsConfigService>("MetricsConfigService")

/** The singleton row's fixed id (`V15__create_metrics_config.sql`'s `CHECK (id = 1)`). */
private const val SETTINGS_ID = 1

private fun intArrayJson(values: List<Int>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun parseIntArray(json: String): List<Int> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.int }
private fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun parseStringArray(json: String): List<String> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }

/**
 * The ONE global configuration revision (v0.3.0 M1 commit 3, `.claude/docs/domain-model.md`
 * "Configuration"): `metrics.settings` is a singleton row holding the calendar/thresholds every
 * DERIVE run reads, plus the `config_revision` counter every later config mutation (global
 * settings, per-connection maps, team membership alike) bumps inside its OWN transaction — the
 * `DataSourceService.update` revision idiom (`ingest/DataSourceService.kt:169`), just against one
 * shared row instead of one row per connection. `TeamMembershipService` (same package) calls
 * [bumpRevision] directly — not a cross-feature read, since both live in `metrics`.
 */
class MetricsConfigService(private val database: R2dbcDatabase) {

    object Settings : Table("metrics.settings") {
        val id = integer("id")
        val configRevision = long("config_revision")
        val hoursPerDay = decimal("hours_per_day", precision = 4, scale = 2)
        val timeZone = varchar("time_zone", 64)
        val weekendDays = jsonb("weekend_days")
        val holidays = jsonb("holidays")
        val commitmentGraceMinutes = integer("commitment_grace_minutes")
        val minSampleSize = integer("min_sample_size")
        val agingWindowItems = integer("aging_window_items")
        val agingPercentiles = jsonb("aging_percentiles")
        val backlogWindowSprints = integer("backlog_window_sprints")
        val epicDriftDays = integer("epic_drift_days")
        val updatedAt = long("updated_at")
        val updatedByUserId = long("updated_by_user_id").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    suspend fun read(): MetricsSettingsResponse = suspendTransaction(database) {
        Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single().toResponse()
    }

    /** The current revision alone — cheap read for a caller (e.g. a future DERIVE enqueue) that only needs the number. */
    suspend fun currentRevision(): Long = suspendTransaction(database) {
        Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single()[Settings.configRevision]
    }

    /**
     * A full-replace PUT: every column moves to the request's values, and the revision bumps in
     * the SAME update — UNLESS the request is byte-for-byte what is already stored, in which case
     * nothing is written and [MetricsSettingsUpdateOutcome.changed] is false (the features-PUT
     * precedent: an idempotent re-PUT is a no-op, not a fresh revision/audit line).
     */
    suspend fun replace(request: MetricsSettingsRequest, byUserId: UInt): MetricsSettingsUpdateOutcome = suspendTransaction(database) {
        validateMetricsSettings(request) // re-checked service-side so direct callers stay guarded
        val current = Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single().toResponse()
        if (current.asRequest() == request) {
            return@suspendTransaction MetricsSettingsUpdateOutcome(current, changed = false)
        }
        Settings.update({ Settings.id eq SETTINGS_ID }) {
            it[hoursPerDay] = request.hoursPerDay.toBigDecimal()
            it[timeZone] = request.timeZone
            it[weekendDays] = intArrayJson(request.weekendDays)
            it[holidays] = stringArrayJson(request.holidays)
            it[commitmentGraceMinutes] = request.commitmentGraceMinutes
            it[minSampleSize] = request.minSampleSize
            it[agingWindowItems] = request.agingWindowItems
            it[agingPercentiles] = intArrayJson(request.agingPercentiles)
            it[backlogWindowSprints] = request.backlogWindowSprints
            it[epicDriftDays] = request.epicDriftDays
            it[configRevision] = Settings.configRevision + 1
            it[updatedAt] = nowMillis()
            it[updatedByUserId] = byUserId.toLong()
        }
        val updated = Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single().toResponse()
        MetricsSettingsUpdateOutcome(updated, changed = true)
    }

    /**
     * Bumps the shared revision alone (a team-membership mutation, or — from commit 4 on — a
     * per-connection config PUT): no other column changes, so `updatedAt`/`updatedByUserId` stay
     * whatever the last SETTINGS edit left them — those two describe the settings form itself, not
     * "the last thing that touched the revision".
     */
    suspend fun bumpRevision(): Long = suspendTransaction(database) {
        Settings.update({ Settings.id eq SETTINGS_ID }) { it[configRevision] = Settings.configRevision + 1 }
        Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single()[Settings.configRevision]
    }

    private fun ResultRow.toResponse(): MetricsSettingsResponse = MetricsSettingsResponse(
        configRevision = this[Settings.configRevision],
        hoursPerDay = this[Settings.hoursPerDay].toDouble(),
        timeZone = this[Settings.timeZone],
        weekendDays = parseIntArray(this[Settings.weekendDays]),
        holidays = parseStringArray(this[Settings.holidays]),
        commitmentGraceMinutes = this[Settings.commitmentGraceMinutes],
        minSampleSize = this[Settings.minSampleSize],
        agingWindowItems = this[Settings.agingWindowItems],
        agingPercentiles = parseIntArray(this[Settings.agingPercentiles]),
        backlogWindowSprints = this[Settings.backlogWindowSprints],
        epicDriftDays = this[Settings.epicDriftDays],
        updatedAt = this[Settings.updatedAt],
        updatedByUserId = this[Settings.updatedByUserId]?.toUInt(),
    )
}
