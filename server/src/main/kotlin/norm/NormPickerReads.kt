package ch.nokillswit.norm

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.WorkItemStore.People
import ch.nokillswit.norm.WorkItemStore.PersonListResult
import ch.nokillswit.norm.WorkItemStore.PersonRow
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.WorkItemStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** `NormPickerReads.distinctCustomFieldValues`' response-size cap (v0.3.0 M1 commit 4 review fix). */
internal const val MAX_DISTINCT_FIELD_VALUES = 200

/**
 * One custom-field value, as `(valueId, valueName)` pairs — `NormPickerReads.distinctCustomFieldValues`'
 * own reader, handling every shape a Jira field value can take (an object, a bare primitive, or an
 * array of either) without ever casting blindly.
 */
internal fun fieldValueOptions(element: JsonElement?): List<Pair<String, String?>> = when {
    element == null || element == JsonNull -> emptyList()
    element is JsonArray -> element.flatMap { fieldValueOptions(it) }
    element is JsonObject -> {
        val id = element["id"]?.jsonPrimitive?.contentOrNull ?: element["value"]?.jsonPrimitive?.contentOrNull
        val name = element["value"]?.jsonPrimitive?.contentOrNull ?: element["name"]?.jsonPrimitive?.contentOrNull
        id?.let { listOf(it to name) } ?: emptyList()
    }
    else -> element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(it to it) } ?: emptyList()
}

/**
 * The reads behind the pickers and option lists — the `/jira-users` list and its UNIT-scope inputs, and the metrics
 * configuration's project-key / issue-type / custom-field-value options. Every read spans ACTIVE connections or one
 * connection's LIVE (non-tombstoned) work items; nothing here writes.
 */
internal class NormPickerReads(private val database: R2dbcDatabase) {

    /**
     * Every Jira account known to an ACTIVE connection, DISTINCT by account id (v0.3.0 M1 commit
     * 3): two connections to the same site share account ids (plan §12 risk note), so this
     * collapses via `GROUP BY account_id` (picking the alphabetically-first display name via
     * `MIN`) rather than a connection-scoped read. Excludes soft-deleted connections and inactive
     * `norm.people` rows (a departed user is marked `active = false` on the NEXT REFERENCE pass'
     * wholesale rebuild, never removed outright). [q] filters by [containsNormalized] on the
     * display name OR the account id (a picker finds a person by either);
     * [accountIds] (when non-null) restricts to that exact set (e.g. a team's current membership, or the
     * unit-relevant set `JiraUsersRoutes.kt` computes) — an empty set short-circuits to no rows.
     * `count(DISTINCT)` and the grouped page read run in the SAME transaction (list-endpoints.md),
     * ordered by the aggregated display name then account id
     * (a deterministic tiebreaker — never left to whatever order `GROUP BY` happens to return).
     */
    suspend fun listPeople(paging: PageRequest, q: String? = null, accountIds: Set<String>? = null): PersonListResult =
        suspendTransaction(database) {
            if (accountIds != null && accountIds.isEmpty()) return@suspendTransaction PersonListResult(emptyList(), 0)
            var predicate: Op<Boolean> = DataSourceService.Connections.active() and (People.active eq true)
            q?.let { term ->
                predicate = predicate and (People.displayName.containsNormalized(term) or People.accountId.containsNormalized(term))
            }
            accountIds?.let { ids -> predicate = predicate and (People.accountId inList ids) }

            val joined = People.innerJoin(DataSourceService.Connections)
            val countExpr = People.accountId.countDistinct()
            val total = joined.select(countExpr).where { predicate }.single()[countExpr]

            val nameAgg = People.displayName.min()
            val descending = paging.sort.firstOrNull { it.name == "displayName" }?.descending == true
            val rows = joined.select(People.accountId, nameAgg).where { predicate }
                .groupBy(People.accountId)
                .orderBy(nameAgg to (if (descending) SortOrder.DESC else SortOrder.ASC), People.accountId to SortOrder.ASC)
                .limit(paging.pageSize)
                .offset((paging.page - 1).toLong() * paging.pageSize)
                .toList()
                // MIN() over a non-null column is itself typed nullable by Exposed (an empty group
                // yields SQL NULL in general) — never actually null here, since GROUP BY only ever
                // emits a row for an account id that has at least one matching people row.
                .map { PersonRow(it[People.accountId], it[nameAgg] ?: "") }
            PersonListResult(rows, total)
        }

    /** Distinct assignee account ids across ACTIVE connections' LIVE work items — one input to `/jira-users`' UNIT scope. */
    suspend fun distinctAssigneeAccountIds(): Set<String> = suspendTransaction(database) {
        WorkItems.innerJoin(DataSourceService.Connections)
            .select(WorkItems.assigneeAccountId).withDistinct()
            .where { DataSourceService.Connections.active() and WorkItems.assigneeAccountId.isNotNull() and WorkItems.deletedAt.isNull() }
            .toList()
            .mapNotNull { it[WorkItems.assigneeAccountId] }
            .toSet()
    }

    /** Distinct worklog author account ids across ACTIVE connections — the other input to `/jira-users`' UNIT scope. */
    suspend fun distinctWorklogAuthorAccountIds(): Set<String> = suspendTransaction(database) {
        Worklogs.innerJoin(DataSourceService.Connections)
            .select(Worklogs.authorAccountId).withDistinct()
            .where { DataSourceService.Connections.active() and Worklogs.authorAccountId.isNotNull() }
            .toList()
            .mapNotNull { it[Worklogs.authorAccountId] }
            .toSet()
    }

    /** Distinct project keys among a connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config defaults' 1:1 domain map. */
    suspend fun distinctProjectKeys(connectionId: UInt): Set<String> = suspendTransaction(database) {
        WorkItems.select(WorkItems.projectKey).withDistinct()
            .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
            .map { it[WorkItems.projectKey] }.toList().toSet()
    }

    /** Distinct status ids any of a connection's status intervals ever carried — the metrics-config options' `seenInHistory` flag. */
    suspend fun distinctIntervalStatusIds(connectionId: UInt): Set<String> = suspendTransaction(database) {
        StatusIntervals.select(StatusIntervals.statusId).withDistinct()
            .where { StatusIntervals.connectionId eq connectionId }
            .map { it[StatusIntervals.statusId] }.toList().toSet()
    }

    /** Distinct issue types among a connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config defaults' activity-type map. */
    suspend fun distinctIssueTypes(connectionId: UInt): Set<String> = suspendTransaction(database) {
        WorkItems.select(WorkItems.issueType).withDistinct()
            .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
            .map { it[WorkItems.issueType] }.toList().toSet()
    }

    /**
     * Every distinct `(valueId, valueName)` pair a `custom_fields[fieldId]` value carries across a
     * connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config options endpoint's
     * `?workCategoryField=` read and `work_category_map`'s own id validation. Handles every shape
     * Jira uses for a select-field value: an object (`{id, value}`), a bare string/number, or an
     * array of either (a multi-select) — never a cast failure on an unexpected shape, since the
     * field could be ANY custom field the admin picks, not necessarily a `select`. Returns the FULL
     * set, uncapped: `work_category_map` id validation must never reject a legitimate value just
     * because it fell outside the OPTIONS endpoint's own display cap
     * (`MetricsConfigOptions.options`, [MAX_DISTINCT_FIELD_VALUES]) — the config table itself has
     * no such limit.
     */
    suspend fun distinctCustomFieldValues(connectionId: UInt, fieldId: String): List<Pair<String, String?>> =
        suspendTransaction(database) {
            WorkItems.select(WorkItems.customFields)
                .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
                .toList()
                .flatMap { row ->
                    val element = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject[fieldId]
                    fieldValueOptions(element)
                }
                .distinctBy { it.first }
                .sortedBy { it.first }
        }
}
