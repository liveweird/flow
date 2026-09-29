package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.norm.MAX_DISTINCT_FIELD_VALUES
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
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
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** Decodes `source_connections.profile` — rolling-deploy tolerance, the `ingest/DataProfileRoutes.kt` idiom. */
private val METRICS_PROFILE_JSON = Json { ignoreUnknownKeys = true }

/** `duedate` is Jira's plain system field for an epic's due date — a valid `EPIC_DUE`/`EPIC_START` choice, never a custom field id. */
private const val DUE_DATE_FIELD_ID = "duedate"

/** The profile-detected role `jira/JiraProfile.kt` stamps on a Story-points-shaped custom field. */
private const val STORY_POINTS_ROLE = "STORY_POINTS"

/** The profile-detected role `jira/JiraProfile.kt` stamps on the Sprint custom field (`gh-sprint`). */
private const val SPRINT_ROLE = "SPRINT"

/** The name fragment a "Start date"-shaped custom field carries (case-insensitive) — the epic-start default's first choice. */
private const val START_DATE_NAME_FRAGMENT = "start date"

/**
 * Jira Plans' own start-date field name (case-insensitive) — the epic-start default's FALLBACK,
 * for a tenant using company-managed Plans dates instead of a team-managed "Start date" custom
 * field (`.claude/docs/domain-model.md` "Configuration": "'Start date' + `duedate`, or Jira Plans'
 * 'Target start'/'Target end'"). `EPIC_DUE` itself keeps its `duedate` default unconditionally —
 * `duedate` is a plain system field, always present as a CHOICE regardless of which date scheme a
 * given epic's project actually uses.
 */
private const val TARGET_START_NAME_FRAGMENT = "target start"

val MetricsConfigServiceKey = AttributeKey<MetricsConfigService>("MetricsConfigService")

/** A PUT that changed nothing bumps no revision and prompts no audit (the features-PUT precedent). */
data class MetricsConfigUpdateOutcome(val response: DataSourceMetricsConfig, val changed: Boolean)

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
class MetricsConfigService(
    private val database: R2dbcDatabase,
    private val workItemStore: WorkItemStore,
    private val dataSources: DataSourceService,
    private val syncJobs: SyncJobsService,
) {

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

    // The eight per-connection config tables `DataSourceMetricsConfig` composes (v0.3.0 M1 commit
    // 4, `V15__create_metrics_config.sql`) — no `SERIAL`/`INTEGER` ids, natural keys throughout
    // (the V8-V13 dialect this schema already follows).

    object StatusStageMap : Table("metrics.status_stage_map") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val statusId = varchar("status_id", 50)
        val domainKey = varchar("domain_key", 50).default("")
        val stage = varchar("stage", 20)
        override val primaryKey = PrimaryKey(connectionId, statusId, domainKey)
    }

    object FieldConfig : Table("metrics.field_config") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val role = varchar("role", 20)
        val fieldId = varchar("field_id", 100)
        override val primaryKey = PrimaryKey(connectionId, role)
    }

    object DomainMap : Table("metrics.domain_map") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectKey = varchar("project_key", 20)
        val domainKey = varchar("domain_key", 50)
        val domainName = varchar("domain_name", 100)
        /** A19 (V17, commit 9d) — an explicitly configured owner team for this project; not yet
         * writable through the request/response DTO (the config API/UI for it is the NEXT commit),
         * so [replaceConfig] preserves whatever value is already stored across its own full-replace. */
        val ownerTeamId = reference("owner_team_id", TeamService.Teams).nullable()
        override val primaryKey = PrimaryKey(connectionId, projectKey)
    }

    object BoardTeamMap : Table("metrics.board_team_map") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val boardId = long("board_id")
        val teamId = reference("team_id", TeamService.Teams)
        override val primaryKey = PrimaryKey(connectionId, boardId)
    }

    object TeamSprintCapacity : Table("metrics.team_sprint_capacity") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val capacityMd = decimal("capacity_md", precision = 8, scale = 2)
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    object ActivityTypeMap : Table("metrics.activity_type_map") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueType = varchar("issue_type", 50)
        val activityType = varchar("activity_type", 50)
        override val primaryKey = PrimaryKey(connectionId, issueType)
    }

    object WorkCategoryMap : Table("metrics.work_category_map") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val valueId = varchar("value_id", 100)
        val valueName = varchar("value_name", 200).nullable()
        val category = varchar("category", 100)
        override val primaryKey = PrimaryKey(connectionId, valueId)
    }

    object BlockedStatuses : Table("metrics.blocked_statuses") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val statusId = varchar("status_id", 50)
        override val primaryKey = PrimaryKey(connectionId, statusId)
    }

    suspend fun read(): MetricsSettingsResponse = suspendTransaction(database) {
        Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single().toResponse()
    }

    /** The current revision alone — cheap read for a caller (e.g. a future DERIVE enqueue) that only needs the number. */
    suspend fun currentRevision(): Long = suspendTransaction(database) {
        Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single()[Settings.configRevision]
    }

    /**
     * A full-replace PUT: every column moves to the request's values, and the revision bumps (and
     * DERIVE is enqueued, [bumpRevision]) in the SAME transaction — UNLESS the request is
     * byte-for-byte what is already stored, in which case nothing is written, nothing is enqueued
     * and [MetricsSettingsUpdateOutcome.changed] is false (the features-PUT precedent: an
     * idempotent re-PUT is a no-op, not a fresh revision/audit line).
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
            it[updatedAt] = nowMillis()
            it[updatedByUserId] = byUserId.toLong()
        }
        // The shared revision moves through bumpRevision (nested into this transaction), which also
        // enqueues DERIVE for every enabled connection — a settings change reaches derived numbers
        // exactly like a membership or per-connection config change does.
        bumpRevision()
        val updated = Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single().toResponse()
        MetricsSettingsUpdateOutcome(updated, changed = true)
    }

    /**
     * Bumps the shared revision alone (a team-membership mutation, or — from commit 4 on — a
     * per-connection config PUT): no other column changes, so `updatedAt`/`updatedByUserId` stay
     * whatever the last SETTINGS edit left them — those two describe the settings form itself, not
     * "the last thing that touched the revision". Since v0.3.0 M3 commit 7, ALSO enqueues `DERIVE`
     * (scheduler priority — `SyncJobsService.enqueueScheduled`) for EVERY enabled, active
     * connection, stamped with that connection's OWN `source_connections.config_revision` (the
     * value `SyncJobsService.claim`'s `CONFIG_CHANGED` check compares against — a DIFFERENT counter
     * from the metrics settings revision this method itself bumps): any configuration change —
     * global settings, a team membership edit, a per-connection metrics-config PUT — must reach
     * every connection's derived numbers, not just the one that happened to be edited (plan §2
     * decision 1). Must run INSIDE the caller's transaction (nested `suspendTransaction` against the
     * SAME database).
     */
    suspend fun bumpRevision(): Long = suspendTransaction(database) {
        Settings.update({ Settings.id eq SETTINGS_ID }) { it[configRevision] = Settings.configRevision + 1 }
        val newRevision = Settings.selectAll().where { Settings.id eq SETTINGS_ID }.toList().single()[Settings.configRevision]
        dataSources.enabledActiveConnections().forEach { connection ->
            syncJobs.enqueueScheduled(connection.id, SyncJobKind.DERIVE, connection.configRevision)
        }
        newRevision
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

    /**
     * `GET /api/v1/data-sources/{id}/metrics-config` (v0.3.0 M1 commit 4): the connection's STORED
     * configuration, or — when nothing is stored — the computed DEFAULTS ([defaultConfig]),
     * `configured = false`. Assumes [connectionId] is a real, active connection (the route's own
     * `requireAdmin` → existence-404 guard runs first); an unknown id here would simply fall
     * through to [defaultConfig] over empty `norm` reads.
     */
    suspend fun effectiveConfig(connectionId: UInt): DataSourceMetricsConfig = suspendTransaction(database) {
        val config = readStoredConfig(connectionId) ?: defaultConfig(connectionId)
        withResolvedOwners(connectionId, config)
    }

    /**
     * A full-replace PUT over all eight per-connection tables, in ONE transaction: validate every
     * id against [MetricsConfigReferenceData] computed from `norm`/the stored data profile/the
     * ACTIVE teams registry, then delete-and-reinsert each table, then bump the shared
     * `config_revision` — UNLESS the request is byte-for-byte what is already stored (the
     * features-PUT precedent: an idempotent re-PUT is a no-op, no write/bump/audit). A board →
     * team clash (this connection's own request, or a team another connection already claims)
     * raises `23505` on `uq_metrics_board_team_map_team_id`, propagated uncaught to
     * `plugins/ErrorHandling.kt`'s central 409 mapping.
     */
    suspend fun replaceConfig(connectionId: UInt, request: DataSourceMetricsConfigRequest): MetricsConfigUpdateOutcome =
        suspendTransaction(database) {
            validateDataSourceMetricsConfig(request, referenceData(connectionId, request))
            val stored = readStoredConfig(connectionId)
            // Order-INSENSITIVE: `readStoredConfig` carries no `ORDER BY` guarantee (deterministic
            // ordering is added below anyway, but two semantically-identical requests may still
            // list their rows in a different array order) — canonicalize both sides before
            // comparing, so a reordered-but-equal re-PUT stays a no-op (v0.3.0 M1 commit 4 review fix).
            if (stored != null && stored.asRequest().canonicalized() == request.canonicalized()) {
                return@suspendTransaction MetricsConfigUpdateOutcome(stored, changed = false)
            }

            StatusStageMap.deleteWhere { StatusStageMap.connectionId eq connectionId }
            if (request.statusStages.isNotEmpty()) {
                StatusStageMap.batchInsert(request.statusStages) { entry ->
                    this[StatusStageMap.connectionId] = connectionId
                    this[StatusStageMap.statusId] = entry.statusId
                    this[StatusStageMap.domainKey] = ""
                    this[StatusStageMap.stage] = entry.stage.name
                }
            }

            FieldConfig.deleteWhere { FieldConfig.connectionId eq connectionId }
            val fieldRows = listOfNotNull(
                request.fields.estimateTask?.let { "ESTIMATE_TASK" to it },
                request.fields.estimateEpic?.let { "ESTIMATE_EPIC" to it },
                request.fields.epicStart?.let { "EPIC_START" to it },
                request.fields.epicDue?.let { "EPIC_DUE" to it },
                request.fields.workCategory?.let { "WORK_CATEGORY" to it },
            )
            if (fieldRows.isNotEmpty()) {
                FieldConfig.batchInsert(fieldRows) { (role, fieldId) ->
                    this[FieldConfig.connectionId] = connectionId
                    this[FieldConfig.role] = role
                    this[FieldConfig.fieldId] = fieldId
                }
            }

            // A19 (V17), writable as of v0.3.0 M3 commit 9e: `ownerTeamId` moves with the rest of
            // the row on every full-replace PUT — `validateDataSourceMetricsConfig` already checked
            // it is either unset or an ACTIVE team, and that every row sharing a `domainKey` agrees.
            DomainMap.deleteWhere { DomainMap.connectionId eq connectionId }
            if (request.domains.isNotEmpty()) {
                DomainMap.batchInsert(request.domains) { mapping ->
                    this[DomainMap.connectionId] = connectionId
                    this[DomainMap.projectKey] = mapping.projectKey
                    this[DomainMap.domainKey] = mapping.domainKey
                    this[DomainMap.domainName] = mapping.domainName
                    this[DomainMap.ownerTeamId] = mapping.ownerTeamId
                }
            }

            BoardTeamMap.deleteWhere { BoardTeamMap.connectionId eq connectionId }
            if (request.boards.isNotEmpty()) {
                BoardTeamMap.batchInsert(request.boards) { mapping ->
                    this[BoardTeamMap.connectionId] = connectionId
                    this[BoardTeamMap.boardId] = mapping.boardId
                    this[BoardTeamMap.teamId] = mapping.teamId
                }
            }

            ActivityTypeMap.deleteWhere { ActivityTypeMap.connectionId eq connectionId }
            if (request.activityTypes.isNotEmpty()) {
                ActivityTypeMap.batchInsert(request.activityTypes) { mapping ->
                    this[ActivityTypeMap.connectionId] = connectionId
                    this[ActivityTypeMap.issueType] = mapping.issueType
                    this[ActivityTypeMap.activityType] = mapping.activityType
                }
            }

            WorkCategoryMap.deleteWhere { WorkCategoryMap.connectionId eq connectionId }
            if (request.workCategories.isNotEmpty()) {
                WorkCategoryMap.batchInsert(request.workCategories) { mapping ->
                    this[WorkCategoryMap.connectionId] = connectionId
                    this[WorkCategoryMap.valueId] = mapping.valueId
                    this[WorkCategoryMap.valueName] = mapping.valueName
                    this[WorkCategoryMap.category] = mapping.category
                }
            }

            BlockedStatuses.deleteWhere { BlockedStatuses.connectionId eq connectionId }
            if (request.blockedStatuses.isNotEmpty()) {
                BlockedStatuses.batchInsert(request.blockedStatuses) { statusId ->
                    this[BlockedStatuses.connectionId] = connectionId
                    this[BlockedStatuses.statusId] = statusId
                }
            }

            TeamSprintCapacity.deleteWhere { TeamSprintCapacity.connectionId eq connectionId }
            if (request.sprintCapacities.isNotEmpty()) {
                TeamSprintCapacity.batchInsert(request.sprintCapacities) { capacity ->
                    this[TeamSprintCapacity.connectionId] = connectionId
                    this[TeamSprintCapacity.sprintId] = capacity.sprintId
                    this[TeamSprintCapacity.capacityMd] = capacity.capacityMd.toBigDecimal()
                }
            }

            bumpRevision()
            val updated = readStoredConfig(connectionId) ?: defaultConfig(connectionId)
            MetricsConfigUpdateOutcome(updated, changed = true)
        }

    /**
     * `GET /api/v1/data-sources/{id}/metrics-config/options` (v0.3.0 M1 commit 4) — the reference
     * data the metrics-config editor picks from. [workCategoryField] (the `?workCategoryField=`
     * query param) is the ONLY optional input: when present, [DataSourceMetricsConfigOptions.workCategoryValues]
     * is populated from that field's own distinct observed values (capped at
     * [MAX_DISTINCT_FIELD_VALUES], alphabetically by id — [DataSourceMetricsConfigOptions.workCategoryValuesTruncated]
     * `true` when more exist); absent, it stays empty (no field chosen yet to enumerate values for).
     * The cap is a DISPLAY concern only — [referenceData]'s own id validation reads the full,
     * uncapped set, so a legitimate value beyond the first 200 is still accepted on a `PUT`.
     */
    suspend fun options(connectionId: UInt, workCategoryField: String?): DataSourceMetricsConfigOptions = suspendTransaction(database) {
        val profile = readProfileSections(connectionId)
        val workCategoryValues = workCategoryField?.let { field -> workItemStore.distinctCustomFieldValues(connectionId, field) }.orEmpty()
        DataSourceMetricsConfigOptions(
            statuses = workItemStore.allStatusRefs(connectionId).sortedBy { it.statusId }
                .map { MetricsStatusOption(it.statusId, it.name, it.category) },
            fields = profile?.customFields.orEmpty().map { MetricsFieldOption(it.id, it.name, it.type, it.role) },
            projects = workItemStore.distinctProjectKeys(connectionId).sorted(),
            boards = workItemStore.allBoardRefs(connectionId).sortedBy { it.boardId }
                .map { MetricsBoardOption(it.boardId, it.name, it.projectKey) },
            issueTypes = workItemStore.distinctIssueTypes(connectionId).sorted(),
            workCategoryValues = workCategoryValues.take(MAX_DISTINCT_FIELD_VALUES)
                .map { (valueId, valueName) -> MetricsFieldValueOption(valueId, valueName) },
            workCategoryValuesTruncated = workCategoryValues.size > MAX_DISTINCT_FIELD_VALUES,
            sprints = workItemStore.allSprintRefs(connectionId).sortedBy { it.sprintId }
                .map { MetricsSprintOption(it.sprintId, it.boardId, it.name, it.state) },
        )
    }

    /**
     * The PURGE job's generic, connector-agnostic cleanup step (v0.3.0 M1 commit 4,
     * `ingest/IngestWorker.kt`'s PURGE path, run AFTER the connector's own `purgeSteps`): drains
     * every one of this connection's eight per-connection config rows. Small tables, rebuilt
     * wholesale on every config PUT already — cleared outright, no batching needed (the
     * `WorkItemStore.purgeReferenceRows` idiom).
     */
    suspend fun purgeConnectionConfig(connectionId: UInt) = suspendTransaction(database) {
        StatusStageMap.deleteWhere { StatusStageMap.connectionId eq connectionId }
        FieldConfig.deleteWhere { FieldConfig.connectionId eq connectionId }
        DomainMap.deleteWhere { DomainMap.connectionId eq connectionId }
        BoardTeamMap.deleteWhere { BoardTeamMap.connectionId eq connectionId }
        TeamSprintCapacity.deleteWhere { TeamSprintCapacity.connectionId eq connectionId }
        ActivityTypeMap.deleteWhere { ActivityTypeMap.connectionId eq connectionId }
        WorkCategoryMap.deleteWhere { WorkCategoryMap.connectionId eq connectionId }
        BlockedStatuses.deleteWhere { BlockedStatuses.connectionId eq connectionId }
    }

    /** Every id/value [validateDataSourceMetricsConfig] checks the request against — see [MetricsConfigReferenceData]'s own doc. */
    private suspend fun referenceData(connectionId: UInt, request: DataSourceMetricsConfigRequest): MetricsConfigReferenceData {
        val profile = readProfileSections(connectionId)
        val activeTeamIds = TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
            .toList().map { it[TeamService.Teams.id].value }.toSet()
        return MetricsConfigReferenceData(
            statusIds = workItemStore.allStatusRefs(connectionId).map { it.statusId }.toSet(),
            fieldIds = profile?.customFields.orEmpty().map { it.id }.toSet() + DUE_DATE_FIELD_ID,
            projectKeys = workItemStore.distinctProjectKeys(connectionId),
            boardIds = workItemStore.allBoardRefs(connectionId).map { it.boardId }.toSet(),
            issueTypes = workItemStore.distinctIssueTypes(connectionId),
            sprintIds = workItemStore.allSprintRefs(connectionId).map { it.sprintId }.toSet(),
            activeTeamIds = activeTeamIds,
            workCategoryValueIds = request.fields.workCategory
                ?.let { field -> workItemStore.distinctCustomFieldValues(connectionId, field).map { it.first }.toSet() },
        )
    }

    /** The computed defaults (v0.3.0 M1 commit 4, `.claude/docs/domain-model.md` "Configuration") — `configured = false`. */
    private suspend fun defaultConfig(connectionId: UInt): DataSourceMetricsConfig {
        val statusStages = workItemStore.allStatusRefs(connectionId).mapNotNull { status ->
            status.category.toDefaultStage()?.let { MetricsStatusStage(status.statusId, it) }
        }
        val profile = readProfileSections(connectionId)
        val storyPointsFieldId = profile?.customFields.orEmpty().firstOrNull { it.role == STORY_POINTS_ROLE }?.id
        val epicStartFieldId = profile?.customFields.orEmpty()
            .firstOrNull { it.name.contains(START_DATE_NAME_FRAGMENT, ignoreCase = true) }?.id
            ?: profile?.customFields.orEmpty().firstOrNull { it.name.contains(TARGET_START_NAME_FRAGMENT, ignoreCase = true) }?.id
        val domains = workItemStore.distinctProjectKeys(connectionId).sorted()
            .map { MetricsDomainMapping(projectKey = it, domainKey = it, domainName = it) }
        val activityTypes = workItemStore.distinctIssueTypes(connectionId).sorted()
            .map { MetricsActivityTypeMapping(issueType = it, activityType = it) }
        return DataSourceMetricsConfig(
            configured = false,
            statusStages = statusStages,
            fields = MetricsFieldConfig(
                estimateTask = storyPointsFieldId,
                estimateEpic = storyPointsFieldId,
                epicStart = epicStartFieldId,
                epicDue = DUE_DATE_FIELD_ID,
                workCategory = null,
            ),
            domains = domains,
            boards = emptyList(),
            activityTypes = activityTypes,
            workCategories = emptyList(),
            blockedStatuses = emptyList(),
            sprintCapacities = emptyList(),
        )
    }

    /**
     * Null when NOTHING is stored across all eight tables — [effectiveConfig] then falls back to
     * [defaultConfig]. Every read carries an explicit `ORDER BY` on its own natural key — a plain
     * `SELECT` gives no row-order guarantee otherwise, and a deterministic order here makes the
     * response stable across repeated `GET`s even though [replaceConfig]'s own no-op check compares
     * canonically (order-insensitively) regardless.
     */
    private suspend fun readStoredConfig(connectionId: UInt): DataSourceMetricsConfig? {
        val statusStages = StatusStageMap.selectAll()
            .where { (StatusStageMap.connectionId eq connectionId) and (StatusStageMap.domainKey eq "") }
            .orderBy(StatusStageMap.statusId)
            .toList().map { MetricsStatusStage(it[StatusStageMap.statusId], MetricsStage.valueOf(it[StatusStageMap.stage])) }
        val fieldRows = FieldConfig.selectAll().where { FieldConfig.connectionId eq connectionId }
            .orderBy(FieldConfig.role)
            .toList().associate { it[FieldConfig.role] to it[FieldConfig.fieldId] }
        val domains = DomainMap.selectAll().where { DomainMap.connectionId eq connectionId }
            .orderBy(DomainMap.projectKey)
            .toList().map {
                MetricsDomainMapping(
                    it[DomainMap.projectKey],
                    it[DomainMap.domainKey],
                    it[DomainMap.domainName],
                    it[DomainMap.ownerTeamId]?.value,
                )
            }
        val boards = BoardTeamMap.selectAll().where { BoardTeamMap.connectionId eq connectionId }
            .orderBy(BoardTeamMap.boardId)
            .toList().map { MetricsBoardTeamMapping(it[BoardTeamMap.boardId], it[BoardTeamMap.teamId].value) }
        val activityTypes = ActivityTypeMap.selectAll().where { ActivityTypeMap.connectionId eq connectionId }
            .orderBy(ActivityTypeMap.issueType)
            .toList().map { MetricsActivityTypeMapping(it[ActivityTypeMap.issueType], it[ActivityTypeMap.activityType]) }
        val workCategories = WorkCategoryMap.selectAll().where { WorkCategoryMap.connectionId eq connectionId }
            .orderBy(WorkCategoryMap.valueId)
            .toList().map {
                MetricsWorkCategoryMapping(it[WorkCategoryMap.valueId], it[WorkCategoryMap.valueName], it[WorkCategoryMap.category])
            }
        val blockedStatuses = BlockedStatuses.selectAll().where { BlockedStatuses.connectionId eq connectionId }
            .orderBy(BlockedStatuses.statusId)
            .toList().map { it[BlockedStatuses.statusId] }
        val sprintCapacities = TeamSprintCapacity.selectAll().where { TeamSprintCapacity.connectionId eq connectionId }
            .orderBy(TeamSprintCapacity.sprintId)
            .toList().map { MetricsSprintCapacity(it[TeamSprintCapacity.sprintId], it[TeamSprintCapacity.capacityMd].toDouble()) }

        val anyStored = statusStages.isNotEmpty() || fieldRows.isNotEmpty() || domains.isNotEmpty() || boards.isNotEmpty() ||
            activityTypes.isNotEmpty() || workCategories.isNotEmpty() || blockedStatuses.isNotEmpty() || sprintCapacities.isNotEmpty()
        if (!anyStored) return null

        return DataSourceMetricsConfig(
            configured = true,
            statusStages = statusStages,
            fields = MetricsFieldConfig(
                estimateTask = fieldRows["ESTIMATE_TASK"],
                estimateEpic = fieldRows["ESTIMATE_EPIC"],
                epicStart = fieldRows["EPIC_START"],
                epicDue = fieldRows["EPIC_DUE"],
                workCategory = fieldRows["WORK_CATEGORY"],
            ),
            domains = domains,
            boards = boards,
            activityTypes = activityTypes,
            workCategories = workCategories,
            blockedStatuses = blockedStatuses,
            sprintCapacities = sprintCapacities,
        )
    }

    /** `source_connections.profile`, decoded — null when the connection has never completed a PROCESS pass. */
    private suspend fun readProfileSections(connectionId: UInt): DataProfileSections? =
        dataSources.readProfile(connectionId)?.profileJson?.let { METRICS_PROFILE_JSON.decodeFromString(it) }

    /**
     * The connection's own Sprint custom field id, auto-detected from the stored data profile's
     * `customFields[].role == "SPRINT"` (`jira/JiraProfile.kt`'s schema-based discovery,
     * `JiraNormalizer.discoverFieldIds`'s `gh-sprint` match) — never admin-configurable, unlike the
     * five [MetricsFieldConfig] roles: a real tenant has at most one Sprint-shaped field, so there is
     * nothing for an admin to choose. `null` when the connection has never completed a PROCESS pass,
     * or its profile detected no Sprint-shaped field at all. `MetricsDeriver`'s sprint step reads
     * changelog rows by this ID (`WorkItemStore.fieldChangesByFieldIds`) rather than by the display
     * text `"Sprint"` — a tenant that renamed or localized the field would otherwise silently return
     * no rows, and the sprint step would then fabricate "only ever in its current sprint since
     * creation" for every task, corrupting every historical sprint total with no signal. Reusing the
     * SAME profile-role lookup [defaultConfig] already runs for `STORY_POINTS`.
     */
    suspend fun detectedSprintFieldId(connectionId: UInt): String? = suspendTransaction(database) {
        readProfileSections(connectionId)?.customFields.orEmpty().firstOrNull { it.role == SPRINT_ROLE }?.id
    }

    /**
     * A19/A22 (V17, commit 9d/9e, `.claude/docs/domain-model.md` "Amendments"): every project key
     * this connection has an EXPLICITLY configured owner team for — `metrics/MetricsDeriver.kt`'s
     * `ownerTeamByDomain` resolves per DOMAIN key (several project rows may share one), agreeing
     * configured owners winning outright, a disagreement resolving to none, and only a project with
     * NO configured owner at all falling back to the one mapped `board_team_map` board on it. Only
     * rows with a non-null `owner_team_id` are returned here (an unconfigured project is simply
     * absent, never present with a `null` value) — `ownerTeamByDomain` itself additionally drops any
     * value pointing at a currently soft-deleted team (A22).
     */
    suspend fun domainOwnerTeamIds(connectionId: UInt): Map<String, UInt> = suspendTransaction(database) {
        DomainMap.select(DomainMap.projectKey, DomainMap.ownerTeamId)
            .where { (DomainMap.connectionId eq connectionId) and DomainMap.ownerTeamId.isNotNull() }
            .toList().associate { it[DomainMap.projectKey] to it[DomainMap.ownerTeamId]!!.value }
    }

    /**
     * A19/A22 (v0.3.0 M1 commit 4 / M3 commit 9e, `.claude/docs/domain-model.md` "Amendments",
     * `.claude/docs/metrics.md`) — resolves each configured DOMAIN key's (not project's — several
     * project rows may share one) owner team. Pure and DB-free: the ONE implementation
     * `MetricsDeriver.ownerTeamByDomain` (the DERIVE run) and [withResolvedOwners] (the GET
     * default/display, below) both call, rather than duplicating the agreement/fallback algorithm.
     *
     * 1. Every project row belonging to the domain that carries a CONFIGURED owner in
     *    [configuredOwners] must AGREE on the same team — rows with no configured owner are
     *    ignored when checking agreement, so a single configured row among several unconfigured
     *    ones still "agrees" trivially. A genuine DISAGREEMENT between two or more distinct
     *    configured owners resolves to NO owner outright — it does NOT fall through to the board
     *    fallback below. A configured owner that is not currently ACTIVE (soft-deleted) also
     *    resolves to no owner rather than falling through (A22 — the admin's explicit choice is
     *    never silently replaced).
     * 2. Absent any configured owner at all, the team of the SINGLE `board_team_map` board (also
     *    active-team-filtered) mapped across ALL of the domain's project keys
     *    ([boardsByProject]) — no mapped board, or more than one distinct team among several
     *    boards across the domain's projects, resolves to no owner.
     * 3. Otherwise absent from the map entirely — the caller's `UNOWNED`/`null` bucket.
     */
    fun resolveOwnerTeamByDomain(
        projectKeysByDomain: Map<String, List<String>>,
        configuredOwners: Map<String, UInt>,
        boardsByProject: Map<String, List<Long>>,
        boardTeamByBoardId: Map<Long, UInt>,
        activeTeamIds: Set<UInt>,
    ): Map<String, UInt> {
        val activeBoardTeamByBoardId = boardTeamByBoardId.filterValues { it in activeTeamIds }
        return projectKeysByDomain.mapNotNull { (domainKey, projectKeys) ->
            val distinctConfigured = projectKeys.mapNotNull { configuredOwners[it] }.distinct()
            val owner = when {
                distinctConfigured.size > 1 -> null
                distinctConfigured.size == 1 -> distinctConfigured.single().takeIf { it in activeTeamIds }
                else -> projectKeys.flatMap { boardsByProject[it].orEmpty() }
                    .mapNotNull { activeBoardTeamByBoardId[it] }.distinct().singleOrNull()
            }
            owner?.let { domainKey to it }
        }.toMap()
    }

    /**
     * v0.3.0 M3 commit 9e: fills every [DataSourceMetricsConfig.domains] row whose
     * [MetricsDomainMapping.ownerTeamId] is unconfigured (`null`) with the SAME computed default
     * [resolveOwnerTeamByDomain] would give a DERIVE run — so a `GET` always shows the owner a
     * report/DERIVE run would actually use, whether an admin configured it explicitly or this
     * connection is entirely unconfigured. An EXPLICITLY stored (non-null) value is never
     * overwritten — [resolveOwnerTeamByDomain]'s own [configuredOwners] input is built from those
     * same explicit values, so a domain that already agrees on one owner resolves to it here too.
     */
    private suspend fun withResolvedOwners(connectionId: UInt, config: DataSourceMetricsConfig): DataSourceMetricsConfig {
        if (config.domains.none { it.ownerTeamId == null }) return config
        val activeTeamIds = TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
            .toList().map { it[TeamService.Teams.id].value }.toSet()
        val configuredOwners = config.domains.mapNotNull { domain -> domain.ownerTeamId?.let { domain.projectKey to it } }.toMap()
        val boardsByProject = workItemStore.allBoardRefs(connectionId).filter { it.projectKey != null }
            .groupBy({ it.projectKey!! }, { it.boardId })
        val boardTeamByBoardId = config.boards.associate { it.boardId to it.teamId }
        val projectKeysByDomain = config.domains.groupBy({ it.domainKey }, { it.projectKey })
        val resolved = resolveOwnerTeamByDomain(projectKeysByDomain, configuredOwners, boardsByProject, boardTeamByBoardId, activeTeamIds)
        val domains = config.domains.map { domain ->
            if (domain.ownerTeamId != null) domain else domain.copy(ownerTeamId = resolved[domain.domainKey])
        }
        return config.copy(domains = domains)
    }
}
