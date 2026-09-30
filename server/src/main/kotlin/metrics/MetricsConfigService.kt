package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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

/**
 * `source_connections.profile`, decoded — null when the connection has never completed a PROCESS pass.
 * Shared by [MetricsConfigService] and [MetricsConfigOptions].
 */
internal suspend fun DataSourceService.readProfileSections(connectionId: UInt): DataProfileSections? =
    readProfile(connectionId)?.profileJson?.let { METRICS_PROFILE_JSON.decodeFromString(it) }

/**
 * The per-connection metrics configuration (checkup D3 — what remains of the former all-in-one
 * `MetricsConfigService` once the global settings singleton ([MetricsSettingsService]), the editor's
 * reference data ([MetricsConfigOptions]) and owner-team resolution ([DomainOwnerResolver]) moved
 * out): the connection's eight config tables, the effective-config read (stored or computed
 * defaults), the full-replace PUT, the PURGE drain and the detected Sprint field id. A config PUT
 * bumps the ONE shared revision through [settings].
 */
class MetricsConfigService(
    private val database: R2dbcDatabase,
    private val workItemStore: WorkItemStore,
    private val dataSources: DataSourceService,
    private val settings: MetricsSettingsService,
    private val owners: DomainOwnerResolver,
) {

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

    /**
     * `GET /api/v1/data-sources/{id}/metrics-config` (v0.3.0 M1 commit 4): the connection's STORED
     * configuration, or — when nothing is stored — the computed DEFAULTS ([defaultConfig]),
     * `configured = false`. Assumes [connectionId] is a real, active connection (the route's own
     * `requireAdmin` → existence-404 guard runs first); an unknown id here would simply fall
     * through to [defaultConfig] over empty `norm` reads.
     */
    suspend fun effectiveConfig(connectionId: UInt): DataSourceMetricsConfig = suspendTransaction(database) {
        val config = readStoredConfig(connectionId) ?: defaultConfig(connectionId)
        owners.withResolvedOwners(connectionId, config)
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

            settings.bumpRevision()
            val updated = readStoredConfig(connectionId) ?: defaultConfig(connectionId)
            MetricsConfigUpdateOutcome(updated, changed = true)
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
        val profile = dataSources.readProfileSections(connectionId)
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
        val profile = dataSources.readProfileSections(connectionId)
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
        dataSources.readProfileSections(connectionId)?.customFields.orEmpty().firstOrNull { it.role == SPRINT_ROLE }?.id
    }
}
