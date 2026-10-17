package ch.nokillswit.norm

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.WorkItemStore.People
import ch.nokillswit.norm.WorkItemStore.AtlassianTeamRow
import ch.nokillswit.norm.WorkItemStore.PersonListResult
import ch.nokillswit.norm.WorkItemStore.PersonRow
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.TeamAssigneeRow
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
 * A text member of the `team_value` JSONB (`{id, name, ...}`, the Atlassian Team custom field as `JiraNormalizer` stores it) —
 * `jsonb_extract_path_text` is NULL for an absent key or a non-object value. The key is inlined as a literal, never a bind
 * parameter: a parameterized expression repeated in SELECT and GROUP BY is two different `$n`s to PostgreSQL ("must appear in
 * the GROUP BY clause").
 */
private fun teamValueText(key: String): CustomFunction<String?> =
    CustomFunction("jsonb_extract_path_text", TextColumnType(), WorkItems.teamValue, stringLiteral(key))

/** The work items the team-seeding reads count: LIVE (not tombstoned, not moved out of scope) rows of an ACTIVE connection. */
private fun liveItemOfActiveConnection(): Op<Boolean> =
    DataSourceService.Connections.active() and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull()

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

    /**
     * Every distinct Atlassian Team (`team_value->>'id'`, site-global, so it matches across connections) on a LIVE TASK-level work
     * item of an ACTIVE connection, with the number of such items (the team-membership seeding's team picker). Task level is the
     * ONE epic/task rule ([hierarchyBucket], A31): epics and issues above them are not counted. A team renamed in Jira shows
     * under the name of its most recently UPDATED issue. A bounded set (the site's Atlassian teams), never paged; ordered by
     * name then id.
     */
    suspend fun atlassianTeams(): List<AtlassianTeamRow> = suspendTransaction(database) {
        val idExpr = teamValueText("id")
        val nameExpr = teamValueText("name")
        val count = WorkItems.issueId.count()
        val latest = WorkItems.updatedAt.max()
        WorkItems.innerJoin(DataSourceService.Connections)
            .select(idExpr, nameExpr, WorkItems.hierarchyLevel, count, latest)
            .where { liveItemOfActiveConnection() and idExpr.isNotNull() }
            .groupBy(idExpr, nameExpr, WorkItems.hierarchyLevel)
            .toList()
            .filter { hierarchyBucket(it[WorkItems.hierarchyLevel]) == HierarchyBucket.TASK }
            .groupBy { it[idExpr] ?: "" }
            .map { (id, variants) ->
                val newest = variants.maxBy { it[latest] ?: Long.MIN_VALUE }
                AtlassianTeamRow(id, newest[nameExpr]?.takeIf { it.isNotBlank() } ?: id, variants.sumOf { it[count] })
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    /**
     * The ASSIGNEES of the LIVE TASK-level work items of ACTIVE connections whose Atlassian Team is [atlassianTeamId] — per
     * account: the number of such items and the creation instants (epoch millis) of the earliest and the latest. Task level is
     * the ONE epic/task rule ([hierarchyBucket], A31), so an epic's assignee (often a lead) is no candidate. An account is named
     * by [displayNames] INCLUDING inactive people — a departed developer is a legitimate historic member; an assignee with no
     * people row at all is left out, since `TeamMembershipService` could not store it (an unknown account id is a 400 there).
     * Ordered by name then account id.
     */
    suspend fun teamAssignees(atlassianTeamId: String): List<TeamAssigneeRow> = suspendTransaction(database) {
        val count = WorkItems.issueId.count()
        val first = WorkItems.createdAt.min()
        val last = WorkItems.createdAt.max()
        val stats = WorkItems.innerJoin(DataSourceService.Connections)
            .select(WorkItems.assigneeAccountId, WorkItems.hierarchyLevel, count, first, last)
            .where {
                liveItemOfActiveConnection() and (teamValueText("id") eq atlassianTeamId) and WorkItems.assigneeAccountId.isNotNull()
            }
            .groupBy(WorkItems.assigneeAccountId, WorkItems.hierarchyLevel)
            .toList()
            .filter { hierarchyBucket(it[WorkItems.hierarchyLevel]) == HierarchyBucket.TASK }
            .groupBy { it[WorkItems.assigneeAccountId] ?: "" }
        if (stats.isEmpty()) return@suspendTransaction emptyList()
        val names = displayNames(stats.keys)
        stats.mapNotNull { (accountId, levels) ->
            val displayName = names[accountId] ?: return@mapNotNull null
            TeamAssigneeRow(
                accountId, displayName, levels.sumOf { it[count] },
                levels.minOf { it[first] ?: Long.MAX_VALUE }, levels.maxOf { it[last] ?: Long.MIN_VALUE },
            )
        }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.accountId }))
    }

    /**
     * The display name of each given account, from `norm.people` of ACTIVE connections — the alphabetically-first name across
     * connections (`MIN`, `listPeople`'s rule) and IGNORING the person's own `active` flag, so a departed user keeps a name
     * (the roster and the seeding preview name them; `listPeople`, the picker, rightly does not offer them). An account with no
     * people row is absent from the map. One grouped read; an empty input short-circuits.
     */
    suspend fun displayNames(accountIds: Collection<String>): Map<String, String> = suspendTransaction(database) {
        if (accountIds.isEmpty()) return@suspendTransaction emptyMap()
        val nameAgg = People.displayName.min()
        People.innerJoin(DataSourceService.Connections)
            .select(People.accountId, nameAgg)
            .where { DataSourceService.Connections.active() and (People.accountId inList accountIds) }
            .groupBy(People.accountId)
            .toList()
            .mapNotNull { row -> row[nameAgg]?.let { row[People.accountId] to it } }
            .toMap()
    }

    /** Distinct project keys among a connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config defaults' 1:1 domain map. */
    suspend fun distinctProjectKeys(connectionId: UInt): Set<String> = suspendTransaction(database) {
        WorkItems.select(WorkItems.projectKey).withDistinct()
            .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
            .map { it[WorkItems.projectKey] }.toList().toSet()
    }

    /** Distinct status ids any of a connection's status intervals ever carried — the metrics-config options' `seenInHistory` flag. */
    suspend fun distinctIntervalStatusIds(connectionId: UInt): Set<String> = suspendTransaction(database) {
        // LIVE work items only: a deleted or moved-out issue's intervals are kept, but its old statuses are no reason to list them.
        StatusIntervals.innerJoin(
            WorkItems,
            { StatusIntervals.connectionId },
            { WorkItems.connectionId },
            { StatusIntervals.issueId eq WorkItems.issueId },
        ).select(StatusIntervals.statusId).withDistinct()
            .where { (StatusIntervals.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
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
