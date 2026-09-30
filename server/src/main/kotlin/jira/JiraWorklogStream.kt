package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val WORKLOGS_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** `sync_cursors.cursor` shape for the WORKLOGS stream (v0.2.0 plan §0 A1/§7). */
@Serializable
internal data class WorklogsCursor(
    /** `/worklog/updated`'s `since` — initialized to the FIRST SYNC's run start, NEVER `backfillFrom` (A1). */
    val updatedSince: Long,
    /** `/worklog/deleted`'s `since` — same first-run initialization. */
    val deletedSince: Long,
)

/** One round's worth of stale-issue ids the per-issue backfill claims at a time (heartbeat/resumability granularity). */
private const val BACKFILL_BATCH_SIZE = 100

/**
 * The WORKLOGS stream (v0.2.0 plan §0 A1/§7): the per-issue backfill first, then the incremental
 * `updated`/`deleted` cursor — deliberately NEVER reads the instance-wide `/worklog/updated` feed
 * back to `backfillFrom`, which would sweep every OTHER unit's time tracking into Flow (A1's
 * rationale, `.claude/docs/ingestion.md`).
 *
 * **Backfill.** Issues with `worklogs_synced_at IS NULL` (not deleted) — first ingested OR resurrected
 * (`JiraRawStore.upsertIssue` clears the stamp when it un-tombstones a row: the incremental feed below
 * skips tombstoned issues and moves on, so a returning issue's worklogs must be re-read) — go through
 * `GET /issue/{id}/worklog` (`startAt`-paged), one issue per
 * transaction+heartbeat, same shape as [JiraChangelogStream]'s per-issue fallback.
 *
 * **Incremental.** `updatedSince`/`deletedSince` (persisted in [WorklogsCursor]) start at THIS run's
 * OWN start time on a connection's very first WORKLOGS run — not `backfillFrom` — so the feed only
 * ever covers the connection's own lifetime. `/worklog/updated` returns bare `{worklogId,
 * updatedTime}` pairs with no `issueId`, so every id is resolved through `POST /worklog/list`
 * (≤1000 ids) BEFORE the scope filter can run; anything whose `issueId` isn't a known,
 * non-tombstoned raw issue of this connection is dropped before any write and counted (A1's
 * `worklogsOutOfScope` progress counter, `StreamContext.incrementProgress`). Each page's `since` advances to its own `until` once that page
 * commits — the client never follows Jira's absolute `nextPage` URL, only re-issues the SAME call
 * with the advanced `since` (a security-review requirement: an absolute URL from an upstream
 * response is untrusted input the outbound guard never gets to re-check). `/worklog/deleted` runs
 * the same way after `/worklog/updated` fully drains, tombstoning whatever it names (a no-op for an
 * out-of-scope worklog Flow never stored).
 */
class JiraWorklogStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
) : Stream {
    override val name: String = "worklogs"

    override suspend fun run(context: StreamContext) {
        backfillPerIssue(context)
        val runStartedAt = context.clock()
        val updatedSinceFinal = runIncrementalUpdated(context, runStartedAt)
        runIncrementalDeleted(context, runStartedAt, updatedSinceFinal)
    }

    private suspend fun backfillPerIssue(context: StreamContext) {
        while (true) {
            val batch = rawStore.staleWorklogIssueIds(context.connectionId, BACKFILL_BATCH_SIZE)
            if (batch.isEmpty()) break
            batch.forEach { issueId -> backfillOneIssue(context, issueId) }
        }
    }

    private suspend fun backfillOneIssue(context: StreamContext, issueId: Long) {
        val worklogs = fetchIssueWorklogPages(issueId)
        context.transaction {
            worklogs.forEach { storeWorklog(context, issueId, it) }
            rawStore.markWorklogsSynced(context.connectionId, issueId, context.clock())
            context.incrementProgress("worklogs", worklogs.size.toLong())
        }
        context.heartbeat()
    }

    private suspend fun fetchIssueWorklogPages(issueId: Long): List<JsonObject> {
        val worklogs = mutableListOf<JsonObject>()
        var startAt = 0
        while (true) {
            val page = client.issueWorklogPage(issueId.toString(), startAt)
            worklogs += page.worklogs.map { it.jsonObject }
            val consumed = page.worklogs.size
            val nextStartAt = startAt + consumed
            val isLast = nextStartAt >= page.total || consumed == 0
            if (isLast) break
            startAt = nextStartAt
        }
        return worklogs
    }

    /** Returns the final `updatedSince` once every page of `/worklog/updated` has committed (`page.lastPage == true`). */
    private suspend fun runIncrementalUpdated(context: StreamContext, runStartedAt: Long): Long {
        val existing = loadCursor(context)
        var since = existing?.updatedSince ?: runStartedAt
        val deletedSince = existing?.deletedSince ?: runStartedAt
        while (true) {
            val page = client.worklogUpdated(since)
            var outOfScopeThisPage = 0
            var storedThisPage = 0
            if (page.values.isNotEmpty()) {
                val details = client.worklogList(page.values.map { it.worklogId }).map { it.jsonObject }
                val candidateIssueIds = details.map { it.issueIdLong() }.toSet()
                val knownIds = rawStore.knownInScopeIssueIds(context.connectionId, candidateIssueIds.toList())
                context.transaction {
                    details.forEach { obj ->
                        val issueId = obj.issueIdLong()
                        if (issueId in knownIds) {
                            storeWorklog(context, issueId, obj)
                            storedThisPage++
                        } else {
                            outOfScopeThisPage++
                        }
                    }
                    context.putCursor(name, encode(WorklogsCursor(page.until, deletedSince)))
                    context.incrementProgress("worklogs", storedThisPage.toLong())
                    context.incrementProgress("worklogsOutOfScope", outOfScopeThisPage.toLong())
                }
            } else {
                context.transaction { context.putCursor(name, encode(WorklogsCursor(page.until, deletedSince))) }
            }
            context.heartbeat()
            if (page.lastPage) return page.until
            since = page.until
        }
    }

    private suspend fun runIncrementalDeleted(context: StreamContext, runStartedAt: Long, updatedSinceFinal: Long) {
        val existing = loadCursor(context)
        var since = existing?.deletedSince ?: runStartedAt
        while (true) {
            val page = client.worklogDeleted(since)
            context.transaction {
                page.values.forEach { entry -> rawStore.tombstoneWorklog(context.connectionId, entry.worklogId, context.clock()) }
                context.putCursor(name, encode(WorklogsCursor(updatedSinceFinal, page.until)))
                context.incrementProgress("tombstoned", page.values.size.toLong())
            }
            context.heartbeat()
            if (page.lastPage) break
            since = page.until
        }
    }

    private suspend fun storeWorklog(context: StreamContext, issueId: Long, worklog: JsonObject) {
        val worklogId = worklog.getValue("id").jsonPrimitive.content.toLong()
        val updatedAt = parseJiraInstantEpochMillis(worklog.getValue("updated").jsonPrimitive.content)
        rawStore.upsertWorklog(context.connectionId, issueId, worklogId, updatedAt, worklog.toString(), context.clock())
    }

    private fun JsonObject.issueIdLong(): Long = getValue("issueId").jsonPrimitive.content.toLong()

    private suspend fun loadCursor(context: StreamContext): WorklogsCursor? =
        context.cursor(name)?.cursor?.let { WORKLOGS_CURSOR_JSON.decodeFromString<WorklogsCursor>(it) }

    private fun encode(cursor: WorklogsCursor): String = WORKLOGS_CURSOR_JSON.encodeToString(cursor)
}
