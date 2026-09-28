package ch.nokillswit.ingest

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraRawStoreKey
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
@Resource("raw-issues/{issueKey}")
class RawIssueResource(val parent: DataSourcesRoute.Id, val issueKey: String)

private val RAW_ISSUE_JSON = Json { ignoreUnknownKeys = true }

/**
 * `GET /api/v1/data-sources/{id}/raw-issues/{issueKey}` (v0.2.0 plan §9/§12 item 8b): ADMIN only,
 * read-only — one raw issue's stored payload, changelog/worklog history and (if processed at least
 * once) its `norm.*` shape. `issueKey` an all-digits string is looked up by the stable Jira issue
 * id; anything else must match [ISSUE_KEY_PATTERN] (e.g. `ENG-123`) or is `400`. Split out of
 * `DataSourceRoutes.kt`/`SyncStatusRoutes.kt` — one file per sub-resource.
 */
fun Application.configureRawIssueInspectorRoutes() {
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]
    val rawStore = attributes[JiraRawStoreKey]
    val workItems = attributes[WorkItemStoreKey]

    routing {
        authenticate {
            get<RawIssueResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                dataSources.read(connectionId).orNotFound("Data source")

                val raw = lookupRawIssue(rawStore, connectionId, route.issueKey).orNotFound("Raw issue")
                call.respond(HttpStatusCode.OK, raw.toInspection(rawStore, workItems, connectionId))
            }
        }
    }
}

/** [ISSUE_ID_PATTERN] wins first — an all-digits key is ALWAYS an id lookup, never a (impossible) numeric issue key. */
private suspend fun lookupRawIssue(rawStore: JiraRawStore, connectionId: UInt, issueKeyOrId: String): JiraRawStore.RawIssueDetail? {
    if (ISSUE_ID_PATTERN.matches(issueKeyOrId)) {
        val issueId = issueKeyOrId.toLongOrNull() ?: throw BadRequestException("issueKey must be a valid issue id")
        return rawStore.issueById(connectionId, issueId)
    }
    if (!ISSUE_KEY_PATTERN.matches(issueKeyOrId)) {
        throw BadRequestException("issueKey must be all digits (an issue id) or match ${ISSUE_KEY_PATTERN.pattern}")
    }
    return rawStore.issueByKey(connectionId, issueKeyOrId)
}

private suspend fun JiraRawStore.RawIssueDetail.toInspection(
    rawStore: JiraRawStore,
    workItems: WorkItemStore,
    connectionId: UInt,
): RawIssueInspection {
    val workItem = workItems.workItemView(connectionId, issueId)
    return RawIssueInspection(
        issueId = issueId,
        issueKey = issueKey,
        fetchedAt = fetchedAt,
        changedAt = changedAt,
        sha256 = sha256,
        deletedAt = deletedAt,
        movedOutAt = movedOutAt,
        needsProcessing = needsProcessing,
        payload = RAW_ISSUE_JSON.parseToJsonElement(payloadJson).jsonObject,
        changelogs = rawStore.changelogPayloadsForIssue(connectionId, issueId)
            .map { RAW_ISSUE_JSON.parseToJsonElement(it).jsonObject },
        worklogs = rawStore.worklogPayloadsForIssue(connectionId, issueId)
            .map { RAW_ISSUE_JSON.parseToJsonElement(it).jsonObject },
        workItem = workItem?.let {
            RawIssueWorkItem(
                issueKey = it.issueKey,
                projectKey = it.projectKey,
                issueType = it.issueType,
                statusId = it.statusId,
                statusName = it.statusName,
                statusCategory = it.statusCategory,
                assigneeAccountId = it.assigneeAccountId,
                hierarchyLevel = it.hierarchyLevel,
                dueAt = it.dueAt,
                customFields = it.customFields,
                processedAt = it.processedAt,
                processingVersion = it.processingVersion,
                deletedAt = it.deletedAt,
                movedOutAt = it.movedOutAt,
            )
        },
        statusIntervals = workItems.statusIntervalsForIssue(connectionId, issueId),
        fieldIntervals = workItems.fieldIntervalsForIssue(connectionId, issueId),
        anomalies = workItem?.anomalies.orEmpty(),
    )
}
