package ch.nokillswit.reports

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

// The three picker option lists of report 17, the Deep dive (`.claude/docs/reports.md` "Report 17"): the sprints of a domain, the epics,
// and ONE epic's level-0 tasks. Ordinary list endpoints (`.claude/docs/list-endpoints.md`): the shared paging/sort machinery, `q` through
// `containsNormalized`, count and rows on ONE predicate in ONE transaction. Active connections only (`resolveConnectionScope`).

/** One sprint of the sprint picker: [taskCount] = the domain's level-0 tasks that were `in_scope_at_close` in it (A17, A29). */
@Serializable
data class DeepDiveSprintOption(
    val id: Long,
    val connectionId: UInt,
    val name: String,
    val state: String,
    val startAt: Long?,
    val endAt: Long?,
    val completeAt: Long?,
    val taskCount: Int,
)

/** One epic of the epic picker; [id] is the epic's Jira issue id (unique within [connectionId]), [domain] its own domain key. */
@Serializable
data class DeepDiveEpicOption(
    val id: Long,
    val connectionId: UInt,
    val key: String,
    val summary: String?,
    val domain: String?,
)

/** One level-0 task of an epic's handpick list; [id] is the Jira issue id (unique within [connectionId]). */
@Serializable
data class DeepDiveTaskOption(
    val id: Long,
    val connectionId: UInt,
    val key: String,
    val summary: String?,
)

typealias DeepDiveSprintPageResponse = PageResponse<DeepDiveSprintOption>
typealias DeepDiveEpicPageResponse = PageResponse<DeepDiveEpicOption>
typealias DeepDiveTaskPageResponse = PageResponse<DeepDiveTaskOption>

/** The sprint list's sort whitelist (default `-id`: the most recent sprints first) — `id` is the Jira sprint id. */
val DEEP_DIVE_SPRINT_SORT_FIELDS = setOf("id", "name", "startAt", "completeAt")

/** The epic list's sort whitelist (default `key`) — `id` is the epic's issue id. */
val DEEP_DIVE_EPIC_SORT_FIELDS = setOf("id", "key", "summary", "domain")

/** The task list's sort whitelist (default `key`) — `id` is the task's issue id. */
val DEEP_DIVE_TASK_SORT_FIELDS = setOf("id", "key", "summary")

private val SPRINT_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to MetricsTables.FactSprintScope.sprintId,
    "name" to MetricsTables.DimSprint.name,
    "startAt" to MetricsTables.DimSprint.startAt,
    "completeAt" to MetricsTables.DimSprint.completeAt,
)

private val EPIC_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to MetricsTables.DimEpic.issueId,
    "key" to MetricsTables.DimEpic.issueKey,
    "summary" to MetricsTables.DimEpic.summary,
    "domain" to MetricsTables.DimEpic.domainKey,
)

private val TASK_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to MetricsTables.FactTaskDelivery.issueId,
    "key" to MetricsTables.FactTaskDelivery.issueKey,
    "summary" to WorkItemStore.WorkItems.summary,
)

/**
 * `GET /api/v1/reports/deep-dive/sprints`: the sprints in which at least one level-0 task of [domain] (the task's OWN domain, as the
 * report's mode (a) selects) was `in_scope_at_close`, each with the count of those tasks. An unknown [domain] (no `dim_domain` row in
 * scope) is a `400`, never an empty page.
 */
suspend fun ReportService.deepDiveSprints(
    domain: String,
    connectionId: UInt?,
    q: String?,
    paging: PageRequest,
): DeepDiveSprintPageResponse = suspendTransaction(database) {
    val connectionIds = resolveConnectionScope(connectionId)
    val known = MetricsTables.DimDomain.select(MetricsTables.DimDomain.domainKey)
        .where { (MetricsTables.DimDomain.connectionId inList connectionIds) and (MetricsTables.DimDomain.domainKey eq domain) }
        .toList().isNotEmpty()
    if (!known) throw BadRequestException("Unknown domain: $domain")

    val scope = MetricsTables.FactSprintScope
    val task = MetricsTables.FactTaskDelivery
    val sprint = MetricsTables.DimSprint
    var predicate: Op<Boolean> = (scope.connectionId inList connectionIds) and (scope.inScopeAtClose eq true) and
        (task.isSubtask eq false) and (task.domainKey eq domain)
    q?.let { predicate = predicate and sprint.name.containsNormalized(it) }
    val taskCount = scope.issueId.countDistinct()
    val columns = listOf(
        scope.connectionId, scope.sprintId, sprint.name, sprint.state, sprint.startAt, sprint.endAt, sprint.completeAt,
    )
    val source = scope
        .join(task, JoinType.INNER, onColumn = scope.issueId, otherColumn = task.issueId) { scope.connectionId eq task.connectionId }
        .join(sprint, JoinType.INNER, onColumn = scope.sprintId, otherColumn = sprint.sprintId) {
            scope.connectionId eq sprint.connectionId
        }
    fun grouped() = source.select(columns + taskCount).where { predicate }.groupBy(*columns.toTypedArray())

    val total = grouped().count()
    val rows = grouped().applyPaging(paging, SPRINT_COLUMNS).orderBy(scope.connectionId).toList()
    paging.toPage(
        rows.map {
            DeepDiveSprintOption(
                id = it[scope.sprintId],
                connectionId = it[scope.connectionId].value,
                name = it[sprint.name],
                state = it[sprint.state],
                startAt = it[sprint.startAt],
                endAt = it[sprint.endAt],
                completeAt = it[sprint.completeAt],
                taskCount = it[taskCount].toInt(),
            )
        },
        total,
    )
}

/**
 * `GET /api/v1/reports/deep-dive/epics`: every epic of an active connection, optionally narrowed to a [domain] (an unknown one answers
 * empty).
 */
suspend fun ReportService.deepDiveEpics(
    domain: String?,
    connectionId: UInt?,
    q: String?,
    paging: PageRequest,
): DeepDiveEpicPageResponse = suspendTransaction(database) {
    val connectionIds = resolveConnectionScope(connectionId)
    val epic = MetricsTables.DimEpic
    var predicate: Op<Boolean> = epic.connectionId inList connectionIds
    domain?.let { predicate = predicate and (epic.domainKey eq it) }
    q?.let { predicate = predicate and (epic.issueKey.containsNormalized(it) or epic.summary.containsNormalized(it)) }
    val total = epic.select(epic.issueId).where { predicate }.count()
    val rows = epic.select(epic.connectionId, epic.issueId, epic.issueKey, epic.summary, epic.domainKey)
        .where { predicate }
        .applyPaging(paging, EPIC_COLUMNS)
        .orderBy(epic.connectionId)
        .toList()
    paging.toPage(
        rows.map {
            DeepDiveEpicOption(
                id = it[epic.issueId],
                connectionId = it[epic.connectionId].value,
                key = it[epic.issueKey],
                summary = it[epic.summary],
                domain = it[epic.domainKey],
            )
        },
        total,
    )
}

/**
 * `GET /api/v1/reports/deep-dive/epics/{epicKey}/tasks`: the level-0 tasks under ONE epic (`fact_task_delivery.epic_id`, the report's own
 * epic attribution). An unknown epic key, or a key present in several connections in scope without [connectionId], is a `400`.
 */
suspend fun ReportService.deepDiveEpicTasks(
    epicKey: String,
    connectionId: UInt?,
    q: String?,
    paging: PageRequest,
): DeepDiveTaskPageResponse = suspendTransaction(database) {
    val key = sanitizeSingleLine(epicKey, "epicKey")
    val connectionIds = resolveConnectionScope(connectionId)
    val epic = MetricsTables.DimEpic
    val matches = epic.select(epic.connectionId, epic.issueId)
        .where { (epic.connectionId inList connectionIds) and (epic.issueKey eq key) }
        .toList()
    if (matches.isEmpty()) throw BadRequestException("Unknown epic: $key")
    if (matches.size > 1) throw BadRequestException("Epic $key exists in several connections; narrow with connectionId")
    val epicConnectionId = matches.single()[epic.connectionId].value
    val epicIssueId = matches.single()[epic.issueId]

    val task = MetricsTables.FactTaskDelivery
    val item = WorkItemStore.WorkItems
    var predicate: Op<Boolean> = (task.connectionId eq epicConnectionId) and (task.epicId eq epicIssueId) and (task.isSubtask eq false)
    q?.let { predicate = predicate and (task.issueKey.containsNormalized(it) or item.summary.containsNormalized(it)) }
    val source = task.join(item, JoinType.LEFT, onColumn = task.issueId, otherColumn = item.issueId) {
        task.connectionId eq item.connectionId
    }
    val total = source.select(task.issueId).where { predicate }.count()
    val rows = source.select(task.issueId, task.issueKey, item.summary)
        .where { predicate }
        .applyPaging(paging, TASK_COLUMNS)
        .toList()
    paging.toPage(
        rows.map {
            DeepDiveTaskOption(
                id = it[task.issueId],
                connectionId = epicConnectionId,
                key = it[task.issueKey],
                summary = it[item.summary],
            )
        },
        total,
    )
}
