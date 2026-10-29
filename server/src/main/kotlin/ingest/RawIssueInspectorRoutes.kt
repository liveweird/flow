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
import ch.nokillswit.plugins.respondProblem
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral

@Serializable
@Resource("raw-issues/{issueKey}")
class RawIssueResource(val parent: DataSourcesRoute.Id, val issueKey: String)

/** Constant text of the 413: the stored issue is over `jira.processPageBytes` (no sizes: nothing of the document is echoed). */
private const val TOO_LARGE_DETAIL = "The stored issue is too large to inspect"

/** How many raw issues one process inspects at once: each is up to `jira.processPageBytes` of text, so the `web` heap bounds this. */
private const val MAX_CONCURRENT_INSPECTIONS = 2

/**
 * Stored JSON text embedded in the response as it is, with no tree built (the `web` role's heap is small): the text is Postgres's
 * jsonb output, valid JSON by construction, and an unquoted literal is written to the body verbatim. `null` is the one text the
 * literal refuses.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun storedJson(text: String): JsonElement = if (text == "null") JsonNull else JsonUnquotedLiteral(text)

/**
 * `GET /api/v1/data-sources/{id}/raw-issues/{issueKey}` (v0.2.0 plan §9/§12 item 8b): ADMIN only,
 * read-only — one raw issue's stored payload, changelog/worklog history and (if processed at least
 * once) its `norm.*` shape. `issueKey` an all-digits string is looked up by the stable Jira issue
 * id; anything else must match [ISSUE_KEY_PATTERN] (e.g. `ENG-123`) or is `400`. An issue whose stored text (payload + histories +
 * live worklogs, summed in the database) is over `jira.processPageBytes` is `413` - the very figure PROCESS refuses an issue at - and
 * is answered BEFORE any of that text is read. Split out of `DataSourceRoutes.kt`/`SyncStatusRoutes.kt` — one file per sub-resource.
 */
fun Application.configureRawIssueInspectorRoutes() {
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]
    val rawStore = attributes[JiraRawStoreKey]
    val workItems = attributes[WorkItemStoreKey]
    val gate = Semaphore(MAX_CONCURRENT_INSPECTIONS)

    routing {
        authenticate {
            get<RawIssueResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                dataSources.read(connectionId).orNotFound("Data source")

                val issueId = resolveRawIssueId(rawStore, connectionId, route.issueKey).orNotFound("Raw issue")
                // At most MAX_CONCURRENT_INSPECTIONS issues (each up to jira.processPageBytes of text, read, built and serialized)
                // at once in a `web` process; the rest wait for a permit.
                gate.withPermit {
                    if (rawStore.storedBytesForIssue(connectionId, issueId) > rawStore.ceilings.processPageBytes) {
                        call.respondProblem(HttpStatusCode.PayloadTooLarge, TOO_LARGE_DETAIL)
                    } else {
                        val raw = rawStore.issueById(connectionId, issueId).orNotFound("Raw issue")
                        call.respond(HttpStatusCode.OK, raw.toInspection(rawStore, workItems, connectionId))
                    }
                }
            }
        }
    }
}

/** [ISSUE_ID_PATTERN] wins first — an all-digits key is ALWAYS an id lookup, never a (impossible) numeric issue key. */
private suspend fun resolveRawIssueId(rawStore: JiraRawStore, connectionId: UInt, issueKeyOrId: String): Long? {
    if (ISSUE_ID_PATTERN.matches(issueKeyOrId)) {
        return issueKeyOrId.toLongOrNull() ?: throw BadRequestException("issueKey must be a valid issue id")
    }
    if (!ISSUE_KEY_PATTERN.matches(issueKeyOrId)) {
        throw BadRequestException("issueKey must be all digits (an issue id) or match ${ISSUE_KEY_PATTERN.pattern}")
    }
    return rawStore.issueIdByKey(connectionId, issueKeyOrId)
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
        payload = storedJson(payloadJson),
        changelogs = rawStore.changelogPayloadsForIssue(connectionId, issueId).map(::storedJson),
        worklogs = rawStore.worklogPayloadsForIssue(connectionId, issueId).map(::storedJson),
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
