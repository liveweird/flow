package ch.nokillswit.metrics

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.MAX_DISTINCT_FIELD_VALUES
import ch.nokillswit.norm.WorkItemStore
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val MetricsConfigOptionsKey = AttributeKey<MetricsConfigOptions>("MetricsConfigOptions")

/**
 * The metrics-config editor's reference-data read (checkup D3 — split out of the former all-in-one
 * `MetricsConfigService`): everything the `GET .../metrics-config/options` page picks from, computed
 * from `norm` and the stored data profile. Read-only, no table of its own.
 */
class MetricsConfigOptions(
    private val database: R2dbcDatabase,
    private val workItemStore: WorkItemStore,
    private val dataSources: DataSourceService,
) {

    /**
     * `GET /api/v1/data-sources/{id}/metrics-config/options` (v0.3.0 M1 commit 4) — the reference
     * data the metrics-config editor picks from. [workCategoryField] (the `?workCategoryField=`
     * query param) is the ONLY optional input: when present, [DataSourceMetricsConfigOptions.workCategoryValues]
     * is populated from that field's own distinct observed values (capped at
     * [MAX_DISTINCT_FIELD_VALUES], alphabetically by id — [DataSourceMetricsConfigOptions.workCategoryValuesTruncated]
     * `true` when more exist); absent, it stays empty (no field chosen yet to enumerate values for).
     * The cap is a DISPLAY concern only — `MetricsConfigService.referenceData`'s own id validation reads the full,
     * uncapped set, so a legitimate value beyond the first 200 is still accepted on a `PUT`.
     */
    suspend fun options(connectionId: UInt, workCategoryField: String?): DataSourceMetricsConfigOptions = suspendTransaction(database) {
        val profile = dataSources.readProfileSections(connectionId)
        val workflowStatusIds = profile?.workflowStatusIds.orEmpty().toSet()
        val schemeFieldIds = profile?.schemeFieldIds?.toSet()
        val seenStatusIds = workItemStore.distinctIntervalStatusIds(connectionId)
        val workCategoryValues = workCategoryField?.let { field -> workItemStore.distinctCustomFieldValues(connectionId, field) }.orEmpty()
        DataSourceMetricsConfigOptions(
            statuses = workItemStore.allStatusRefs(connectionId).sortedBy { it.statusId }
                .map {
                    MetricsStatusOption(
                        statusId = it.statusId,
                        name = it.name,
                        category = it.category,
                        inWorkflow = it.statusId in workflowStatusIds,
                        seenInHistory = it.statusId in seenStatusIds,
                    )
                },
            fields = profile?.customFields.orEmpty().map {
                val inScheme = schemeFieldIds?.let { ids -> it.id in ids }
                MetricsFieldOption(it.id, it.name, it.type, it.role, inScheme, it.nonNullCount)
            },
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
}
