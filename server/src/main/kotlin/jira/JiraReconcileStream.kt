package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val RECONCILE_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** `sync_cursors.cursor` shape for the RECONCILE stream (v0.2.0 plan §7/§12 item 7). */
@Serializable
internal data class ReconcileCursor(val passStartedAt: Long, val nextPageToken: String? = null)

/** `search/jql fields=id` page size (v0.2.0 plan §7 "RECONCILE") — pages of up to 5000 ids. */
private const val RECONCILE_PAGE_SIZE = 5000

/** `GET /issue/{id}` requests only what a reconcile decision needs — never the full document. */
private const val RECONCILE_ISSUE_FIELDS = "project,key"

private const val NOT_FOUND_CODE = "NOT_FOUND"

/**
 * The RECONCILE stream (v0.2.0 plan §7/§12 item 7): a daily id-only sweep (`search/jql fields=id`,
 * `JiraJql.reconcile`) into the `raw.jira_reconcile_seen` scratch table (V12), then an anti-join
 * against `raw.jira_issues` decides what changed while Flow wasn't looking:
 *
 * - A non-tombstoned raw issue ABSENT from the seen set is checked individually
 *   (`GET /issue/{id}?fields=project,key`): a 404 means Jira deleted it (`deletedAt`); a 200 whose
 *   project is now OUTSIDE [projectKeys] means it moved out (`movedOutAt`, plus a key/project
 *   refresh). Either way `needs_processing` is flagged — every tombstone flags the issue for
 *   processing (plan §7).
 * - An id the sweep saw but `raw.jira_issues` never stored (an index gap — a missed ISSUES page,
 *   say) is fetched in full and upserted, the same write the ISSUES stream itself uses.
 *
 * The scratch table is scoped per (connection, job) so a resumed pass (same job id, after a lease
 * loss/reclaim) re-inserts idempotently rather than duplicating (`JiraRawStore.insertReconcileSeen`'s
 * `ON CONFLICT DO NOTHING`), and is fully drained (`JiraRawStore.clearReconcileSeen`) once the
 * anti-join step completes — a crash before that point simply leaves it for the NEXT run's own
 * anti-join to work from once the sweep re-completes (the ids are re-collected, never lost).
 * `DataSourceService.recordReconcileSucceeded` (stamping `last_reconcile_at`) runs at the JOB level
 * once this stream returns successfully (`ingest/IngestWorker.kt`'s `onSucceeded`), not here.
 */
class JiraReconcileStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
) : Stream {
    override val name: String = "reconcile"

    override suspend fun run(context: StreamContext) {
        val jql = JiraJql.reconcile(projectKeys)
        var state = loadOrStart(context)
        while (true) {
            val page = client.searchJql(jql, fields = "id", nextPageToken = state.nextPageToken, maxResults = RECONCILE_PAGE_SIZE)
            val isLast = page.nextPageToken == null
            context.transaction {
                page.issues.forEach { issueElement ->
                    val issueId = issueElement.jsonObject.getValue("id").jsonPrimitive.content.toLong()
                    rawStore.insertReconcileSeen(context.connectionId, context.jobId, issueId)
                }
                context.putCursor(name, encode(ReconcileCursor(state.passStartedAt, page.nextPageToken)))
                context.incrementProgress("pages")
            }
            context.heartbeat()
            if (isLast) break
            state = state.copy(nextPageToken = page.nextPageToken)
        }

        rawStore.issuesMissingFromSeen(context.connectionId, context.jobId).forEach { issueId -> reconcileMissing(context, issueId) }
        rawStore.seenButUnknownIds(context.connectionId, context.jobId).forEach { issueId -> reconcileIndexGap(context, issueId) }

        context.transaction {
            rawStore.clearReconcileSeen(context.connectionId)
            context.clearCursor(name)
        }
    }

    /** A raw issue the sweep never saw: Jira either deleted it (404) or moved it out of scope (a different project). */
    private suspend fun reconcileMissing(context: StreamContext, issueId: Long) {
        val issue = try {
            client.issue(issueId.toString(), fields = RECONCILE_ISSUE_FIELDS)
        } catch (notFound: JiraFetchException) {
            if (notFound.code != NOT_FOUND_CODE) throw notFound
            context.transaction {
                rawStore.markIssueDeleted(context.connectionId, issueId, context.clock())
                context.incrementProgress("tombstoned")
            }
            context.heartbeat()
            return
        }
        val project = issue.getValue("fields").jsonObject.getValue("project").jsonObject
        val newProjectKey = project.getValue("key").jsonPrimitive.content
        if (newProjectKey !in projectKeys) {
            context.transaction {
                rawStore.markIssueMovedOut(
                    context.connectionId,
                    issueId,
                    newKey = issue.getValue("key").jsonPrimitive.content,
                    newProjectId = project.getValue("id").jsonPrimitive.content.toLong(),
                    newProjectKey = newProjectKey,
                    now = context.clock(),
                )
                context.incrementProgress("tombstoned")
            }
            context.heartbeat()
        }
        // Still in scope: the id sweep should have listed it already — nothing to reconcile here
        // (a transient sweep/anti-join mismatch, not expected in practice).
    }

    /** An id the sweep saw but `raw.jira_issues` never stored — fetched and upserted like a normal ISSUES-stream write. */
    private suspend fun reconcileIndexGap(context: StreamContext, issueId: Long) {
        val issue = client.issue(issueId.toString())
        val fields = issue.getValue("fields").jsonObject
        val project = fields.getValue("project").jsonObject
        context.transaction {
            rawStore.upsertIssue(
                context.connectionId,
                RawIssueInput(
                    issueId = issueId,
                    issueKey = issue.getValue("key").jsonPrimitive.content,
                    projectId = project.getValue("id").jsonPrimitive.content.toLong(),
                    projectKey = project.getValue("key").jsonPrimitive.content,
                    issueUpdatedAt = Instant.parse(fields.getValue("updated").jsonPrimitive.content).toEpochMilli(),
                    payloadJson = issue.toString(),
                ),
                context.clock(),
            )
            context.incrementProgress("issuesUpserted")
        }
        context.heartbeat()
    }

    private suspend fun loadOrStart(context: StreamContext): ReconcileCursor =
        context.cursor(name)?.cursor?.let { RECONCILE_CURSOR_JSON.decodeFromString<ReconcileCursor>(it) }
            ?: ReconcileCursor(passStartedAt = context.clock())

    private fun encode(cursor: ReconcileCursor): String = RECONCILE_CURSOR_JSON.encodeToString(cursor)
}
