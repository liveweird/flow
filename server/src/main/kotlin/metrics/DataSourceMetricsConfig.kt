package ch.nokillswit.metrics

import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.norm.StatusCategory
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/** Matches `V15__create_metrics_config.sql`'s `status_stage_map.stage` CHECK. */
@Serializable
enum class MetricsStage { NOT_STARTED, IN_PROGRESS, DONE }

/** One `metrics.status_stage_map` row for the `domain_key = ''` (every-domain) mapping. */
@Serializable
data class MetricsStatusStage(val statusId: String, val stage: MetricsStage)

/**
 * One `metrics.status_stage_map` row for a NON-empty `domain_key` — a per-domain override of
 * [MetricsStatusStage]'s every-domain mapping: an item whose domain is [domainKey] reads [stage]
 * for [statusId] instead of the every-domain stage (`MetricsDeriver`/`DeriveKernels.stageIntervals`;
 * `.claude/docs/metrics.md` "Per-domain stage overrides"). A status with no row here for the item's
 * domain simply uses the every-domain mapping.
 */
@Serializable
data class MetricsDomainStatusStage(val domainKey: String, val statusId: String, val stage: MetricsStage)

/** `metrics.field_config`'s five roles, one field id each (`null` = unset — only `workCategory` ships unset by default). */
@Serializable
data class MetricsFieldConfig(
    val estimateTask: String? = null,
    val estimateEpic: String? = null,
    val epicStart: String? = null,
    val epicDue: String? = null,
    val workCategory: String? = null,
)

/**
 * One `metrics.domain_map` row — project → domain. [ownerTeamId] (A19/A22, v0.3.0 M3 commit 9e) is
 * an OPTIONAL explicitly-configured owner team for this project row; every row sharing the same
 * [domainKey] must carry the same [ownerTeamId] (null or equal — [validateDataSourceMetricsConfig]
 * 400s a disagreement). Left unconfigured (`null`) on a stored row, `MetricsConfigService`'s GET
 * fills it with the SAME computed default `MetricsDeriver`'s own DERIVE run would resolve — see
 * `DomainOwnerResolver.resolveOwnerTeamByDomain`.
 */
@Serializable
data class MetricsDomainMapping(val projectKey: String, val domainKey: String, val domainName: String, val ownerTeamId: UInt? = null)

/** One `metrics.board_team_map` row (D10: one board per team — the OTHER direction, `teamId` unique, is a `23505` -> `409`). */
@Serializable
data class MetricsBoardTeamMapping(val boardId: Long, val teamId: UInt)

/** One `metrics.activity_type_map` row (D6: activity types are standard issue types). */
@Serializable
data class MetricsActivityTypeMapping(val issueType: String, val activityType: String)

/** One `metrics.work_category_map` row (D8: the task's own value, else its epic's). */
@Serializable
data class MetricsWorkCategoryMapping(val valueId: String, val valueName: String? = null, val category: String)

/** One `metrics.team_sprint_capacity` row — an admin override; DERIVE falls back to a computed default (A3) when absent. */
@Serializable
data class MetricsSprintCapacity(val sprintId: Long, val capacityMd: Double)

/**
 * The per-connection metrics configuration (v0.3.0 M1 commit 4, `.claude/docs/domain-model.md`
 * "Configuration"): ONE composite resource — `GET/PUT /api/v1/data-sources/{id}/metrics-config` —
 * instead of eight paired endpoints (API-RES-004, the features-PUT idiom, `MetricsSettings`'
 * precedent). `configured = false` means nothing is stored yet and every field below is a COMPUTED
 * default (`MetricsConfigService.effectiveConfig`); a later commit's DERIVE reads exactly this same
 * effective shape, so reports work before an admin touches anything (team attribution excepted).
 */
@Serializable
data class DataSourceMetricsConfig(
    val configured: Boolean,
    val statusStages: List<MetricsStatusStage> = emptyList(),
    val domainStatusStages: List<MetricsDomainStatusStage> = emptyList(),
    val fields: MetricsFieldConfig = MetricsFieldConfig(),
    val domains: List<MetricsDomainMapping> = emptyList(),
    val boards: List<MetricsBoardTeamMapping> = emptyList(),
    val activityTypes: List<MetricsActivityTypeMapping> = emptyList(),
    val workCategories: List<MetricsWorkCategoryMapping> = emptyList(),
    val blockedStatuses: List<String> = emptyList(),
    val sprintCapacities: List<MetricsSprintCapacity> = emptyList(),
)

/** The PUT body — a full replace; `configured` is excluded (server-computed, never client-supplied). */
@Serializable
data class DataSourceMetricsConfigRequest(
    val statusStages: List<MetricsStatusStage> = emptyList(),
    val domainStatusStages: List<MetricsDomainStatusStage> = emptyList(),
    val fields: MetricsFieldConfig = MetricsFieldConfig(),
    val domains: List<MetricsDomainMapping> = emptyList(),
    val boards: List<MetricsBoardTeamMapping> = emptyList(),
    val activityTypes: List<MetricsActivityTypeMapping> = emptyList(),
    val workCategories: List<MetricsWorkCategoryMapping> = emptyList(),
    val blockedStatuses: List<String> = emptyList(),
    val sprintCapacities: List<MetricsSprintCapacity> = emptyList(),
)

/** The stored response reshaped back into request form — [MetricsConfigService]'s own no-op comparison (the features-PUT precedent). */
internal fun DataSourceMetricsConfig.asRequest(): DataSourceMetricsConfigRequest = DataSourceMetricsConfigRequest(
    statusStages = statusStages,
    domainStatusStages = domainStatusStages,
    fields = fields,
    domains = domains,
    boards = boards,
    activityTypes = activityTypes,
    workCategories = workCategories,
    blockedStatuses = blockedStatuses,
    sprintCapacities = sprintCapacities,
)

/**
 * A canonically-ORDERED copy — every list sorted by its own natural key — so
 * `MetricsConfigService.replaceConfig`'s no-op comparison is order-INSENSITIVE: a plain `SELECT`
 * with no `ORDER BY` (`readStoredConfig`) gives no row-order guarantee, and a re-PUT of the SAME
 * set in a different array order must still be a no-op (no bump, no audit), never a spurious
 * "changed" write. Only ORDER changes here — no row is added, removed or renamed.
 */
internal fun DataSourceMetricsConfigRequest.canonicalized(): DataSourceMetricsConfigRequest = copy(
    statusStages = statusStages.sortedBy { it.statusId },
    domainStatusStages = domainStatusStages.sortedWith(compareBy({ it.domainKey }, { it.statusId })),
    domains = domains.sortedBy { it.projectKey },
    boards = boards.sortedBy { it.boardId },
    activityTypes = activityTypes.sortedBy { it.issueType },
    workCategories = workCategories.sortedBy { it.valueId },
    blockedStatuses = blockedStatuses.sorted(),
    sprintCapacities = sprintCapacities.sortedBy { it.sprintId },
)

/**
 * Trims free-text names (the sanitizer convention — control characters are a 400): the domain and work-category
 * display names and the user-entered activity type. A mapping's `issueType`/`valueId` are NOT touched — they are
 * Jira's own values, matched verbatim against the connection's reference data.
 */
fun sanitizedDataSourceMetricsConfig(request: DataSourceMetricsConfigRequest): DataSourceMetricsConfigRequest = request.copy(
    activityTypes = request.activityTypes.map { it.copy(activityType = sanitizeSingleLine(it.activityType, "activityType")) },
    domains = request.domains.map { it.copy(domainName = sanitizeSingleLine(it.domainName, "domainName")) },
    workCategories = request.workCategories.map {
        it.copy(valueName = it.valueName?.let { name -> sanitizeSingleLine(name, "valueName") })
    },
)

/**
 * The connection's known reference ids/values (v0.3.0 M1 commit 4) — every id
 * [DataSourceMetricsConfigRequest] carries is checked against exactly these, computed by
 * `MetricsConfigService.replaceConfig` from `norm`, the connection's stored data profile and the
 * ACTIVE teams registry (never re-queried inside this pure function, so validation itself stays
 * unit-testable without a database).
 */
data class MetricsConfigReferenceData(
    val statusIds: Set<String>,
    val fieldIds: Set<String>,
    val projectKeys: Set<String>,
    val boardIds: Set<Long>,
    val issueTypes: Set<String>,
    val sprintIds: Set<Long>,
    val activeTeamIds: Set<UInt>,
    /** Null when no `WORK_CATEGORY` field is configured yet — any [MetricsWorkCategoryMapping] is then a 400. */
    val workCategoryValueIds: Set<String>?,
)

/**
 * Enforced by the route AND re-checked by the service (the `validateTeam` idiom): every
 * client-supplied id must resolve to something the connection's own `norm` data (or its ACTIVE
 * teams / stored data profile) actually knows about — the client-supplied-FK idiom, `400` never
 * `404` (there is no path id here). `stage`'s enum shape is already enforced by kotlinx
 * deserialization. D10's "board maps to a team that is active" is checked here; "one board per
 * team" (the other direction) is a `23505` -> `409` at the database, via the
 * `uq_metrics_board_team_map_team_id` constraint (already wired in `plugins/ErrorHandling.kt`).
 */
fun validateDataSourceMetricsConfig(request: DataSourceMetricsConfigRequest, ref: MetricsConfigReferenceData) {
    requireNoDuplicateKeys(request.statusStages.map { it.statusId }, "statusId in statusStages")
    requireNoDuplicateKeys(request.domainStatusStages.map { it.domainKey to it.statusId }, "(domainKey, statusId) in domainStatusStages")
    requireNoDuplicateKeys(request.domains.map { it.projectKey }, "projectKey in domains")
    requireNoDuplicateKeys(request.boards.map { it.boardId }, "boardId in boards")
    requireNoDuplicateKeys(request.activityTypes.map { it.issueType }, "issueType in activityTypes")
    requireNoDuplicateKeys(request.workCategories.map { it.valueId }, "valueId in workCategories")
    requireNoDuplicateKeys(request.blockedStatuses, "statusId in blockedStatuses")
    requireNoDuplicateKeys(request.sprintCapacities.map { it.sprintId }, "sprintId in sprintCapacities")

    request.statusStages.forEach { entry ->
        if (entry.statusId !in ref.statusIds) throw BadRequestException("Unknown status id: ${entry.statusId}")
    }
    validateDomainStatusStages(request, ref)
    listOfNotNull(
        request.fields.estimateTask,
        request.fields.estimateEpic,
        request.fields.epicStart,
        request.fields.epicDue,
        request.fields.workCategory,
    ).forEach { fieldId -> if (fieldId !in ref.fieldIds) throw BadRequestException("Unknown field id: $fieldId") }
    validateDomains(request.domains, ref)
    request.boards.forEach { board ->
        if (board.boardId !in ref.boardIds) throw BadRequestException("Unknown board id: ${board.boardId}")
        if (board.teamId !in ref.activeTeamIds) throw BadRequestException("Unknown or inactive team id: ${board.teamId}")
    }
    request.activityTypes.forEach { activity ->
        if (activity.issueType !in ref.issueTypes) throw BadRequestException("Unknown issue type: ${activity.issueType}")
    }
    if (request.workCategories.isNotEmpty() && request.fields.workCategory == null) {
        throw BadRequestException("workCategories requires fields.workCategory to be set")
    }
    request.workCategories.forEach { mapping ->
        if (ref.workCategoryValueIds?.let { mapping.valueId !in it } == true) {
            throw BadRequestException("Unknown work category value id: ${mapping.valueId}")
        }
    }
    request.blockedStatuses.forEach { statusId ->
        if (statusId !in ref.statusIds) throw BadRequestException("Unknown status id: $statusId")
    }
    request.sprintCapacities.forEach { capacity ->
        if (capacity.sprintId !in ref.sprintIds) throw BadRequestException("Unknown sprint id: ${capacity.sprintId}")
        if (capacity.capacityMd < 0) throw BadRequestException("capacityMd must be >= 0")
    }
}

/**
 * `domainStatusStages[]`'s checks (a per-domain override of the every-domain status → stage map): the
 * status must be one of the connection's own `norm.statuses`, and the domain key must be a domain the
 * SAME request defines — a `domains[].domainKey`, or the project key of an observed project the
 * request's `domains` leaves unmapped (DERIVE falls back to the project key itself as the domain,
 * `MetricsDeriver`'s `domainByProject[projectKey] ?: projectKey`). The empty key is the every-domain
 * row (`statusStages`) and is never a valid override domain. The stage enum is enforced by
 * deserialization.
 */
private fun validateDomainStatusStages(request: DataSourceMetricsConfigRequest, ref: MetricsConfigReferenceData) {
    if (request.domainStatusStages.isEmpty()) return
    val mappedProjects = request.domains.map { it.projectKey }.toSet()
    val knownDomainKeys = request.domains.map { it.domainKey }.toSet() + (ref.projectKeys - mappedProjects)
    request.domainStatusStages.forEach { entry ->
        if (entry.domainKey.isEmpty() || entry.domainKey !in knownDomainKeys) {
            throw BadRequestException("Unknown domain key in domainStatusStages: ${entry.domainKey}")
        }
        if (entry.statusId !in ref.statusIds) throw BadRequestException("Unknown status id: ${entry.statusId}")
    }
}

/**
 * `domains[]`'s own three checks (split out of [validateDataSourceMetricsConfig] to keep its
 * cyclomatic complexity under the repo's detekt threshold): an unknown project key, an unknown or
 * inactive `ownerTeamId` (A19/A22, v0.3.0 M3 commit 9e — the `boards[].teamId` idiom), and every
 * project row of the SAME `domainKey` agreeing on one owner (null or equal) — a genuine
 * disagreement is a malformed request, never silently resolved to "no owner" here (the DERIVE-time
 * resolution does that instead, see `DomainOwnerResolver.resolveOwnerTeamByDomain`).
 */
private fun validateDomains(domains: List<MetricsDomainMapping>, ref: MetricsConfigReferenceData) {
    domains.forEach { domain ->
        if (domain.projectKey !in ref.projectKeys) throw BadRequestException("Unknown project key: ${domain.projectKey}")
        if (domain.ownerTeamId != null && domain.ownerTeamId !in ref.activeTeamIds) {
            throw BadRequestException("Unknown or inactive owner team id: ${domain.ownerTeamId}")
        }
    }
    domains.groupBy { it.domainKey }.forEach { (domainKey, rows) ->
        if (rows.map { it.ownerTeamId }.distinct().size > 1) {
            throw BadRequestException("Disagreeing ownerTeamId for domain: $domainKey")
        }
    }
}

/**
 * A repeated key WITHIN one array of the SAME PUT body is a malformed request (`400`), not a
 * conflict with something ELSE already stored — deliberately NOT applied to `boards[].teamId`
 * (two boards claiming the same team, even within one request, is D10's "one board per team" and
 * stays a `409` at the database via `uq_metrics_board_team_map_team_id`, never pre-empted here).
 */
private fun <T> requireNoDuplicateKeys(keys: List<T>, label: String) {
    val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) throw BadRequestException("Duplicate $label: ${duplicates.joinToString()}")
}

/**
 * `StatusCategory` → [MetricsStage] — the seeded default before an admin overrides it; `UNKNOWN`
 * stays unmapped (flagged, never guessed).
 */
internal fun StatusCategory.toDefaultStage(): MetricsStage? = when (this) {
    StatusCategory.TODO -> MetricsStage.NOT_STARTED
    StatusCategory.IN_PROGRESS -> MetricsStage.IN_PROGRESS
    StatusCategory.DONE -> MetricsStage.DONE
    StatusCategory.UNKNOWN -> null
}

/** One status, for the options endpoint — every status Jira reports, whether or not it is mapped. */
@Serializable
data class MetricsStatusOption(val statusId: String, val name: String, val category: StatusCategory)

/** One custom field, for the options endpoint — the SAME shape `jira/JiraProfile.kt` already detects a role for. */
@Serializable
data class MetricsFieldOption(val fieldId: String, val name: String, val type: String, val detectedRole: String)

@Serializable
data class MetricsBoardOption(val boardId: Long, val name: String, val projectKey: String?)

@Serializable
data class MetricsSprintOption(val sprintId: Long, val boardId: Long?, val name: String, val state: String)

/** One distinct value observed in the configured `WORK_CATEGORY` field, across this connection's live work items. */
@Serializable
data class MetricsFieldValueOption(val valueId: String, val valueName: String?)

/**
 * `GET /api/v1/data-sources/{id}/metrics-config/options` (v0.3.0 M1 commit 4) — the reference data
 * the metrics-config editor picks from; [workCategoryValues] is empty unless `?workCategoryField=`
 * names a field id (`MetricsConfigOptions.options`). [workCategoryValuesTruncated] is `true` when
 * the field carries more than `WorkItemStore`'s distinct-value cap (200) — the response still
 * shows the first 200 (alphabetically by id) rather than growing unbounded for a poorly-chosen
 * high-cardinality field.
 */
@Serializable
data class DataSourceMetricsConfigOptions(
    val statuses: List<MetricsStatusOption> = emptyList(),
    val fields: List<MetricsFieldOption> = emptyList(),
    val projects: List<String> = emptyList(),
    val boards: List<MetricsBoardOption> = emptyList(),
    val issueTypes: List<String> = emptyList(),
    val workCategoryValues: List<MetricsFieldValueOption> = emptyList(),
    val workCategoryValuesTruncated: Boolean = false,
    val sprints: List<MetricsSprintOption> = emptyList(),
)
