package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraWorklogStream::class.java)

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

/** `POST /worklog/list`'s documented ceiling on ids per call; the run's id-batch size starts here and only shrinks. */
private const val WORKLOG_LIST_MAX_IDS = 1000

/** The run-local (never persisted) size of a `worklog/list` id batch: starts at [WORKLOG_LIST_MAX_IDS], only shrinks. */
private class ListAsk(var size: Int = WORKLOG_LIST_MAX_IDS)

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
 *
 * Oversized response (`.claude/docs/jira-integration.md` "Oversized changelog/worklog batch"): a `worklog/list` answer
 * over `jira.maxResponseBytes` (`LIMIT_EXCEEDED`) is re-asked for half the ids (floor 1) for the rest of this invocation
 * (not persisted); a single worklog id still over the cap is skipped (`worklogsSkipped`). A backfilled issue whose own
 * `GET /issue/{id}/worklog` page is over the cap is skipped for this run, left unsynced for the next
 * (`worklogsIssuesSkipped`), so neither path can stall the stream.
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
        // Run-local: issues whose own response is over the cap stay unsynced for the next run but are not re-claimed here.
        val gaveUp = mutableSetOf<Long>()
        while (true) {
            val batch = rawStore.staleWorklogIssueIds(context.connectionId, BACKFILL_BATCH_SIZE, gaveUp)
            if (batch.isEmpty()) break
            batch.forEach { issueId -> backfillOneIssue(context, issueId, gaveUp) }
        }
    }

    private suspend fun backfillOneIssue(context: StreamContext, issueId: Long, gaveUp: MutableSet<Long>) {
        val worklogs = try {
            fetchIssueWorklogPages(issueId)
        } catch (cause: JiraFetchException) {
            if (!cause.isOversized()) throw cause
            log.warn("WORKLOGS issue {} over the response cap, data source {}: skipped this run", issueId, context.connectionId)
            gaveUp += issueId
            context.incrementProgress("worklogsIssuesSkipped")
            return
        }
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
        val listAsk = ListAsk()
        while (true) {
            val page = client.worklogUpdated(since)
            var outOfScopeThisPage = 0
            var storedThisPage = 0
            if (page.values.isNotEmpty()) {
                val details = listWorklogs(context, page.values.map { it.worklogId }, listAsk)
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

    /**
     * Resolves [ids] through `POST /worklog/list` in batches of at most [ask]`.size`. A `LIMIT_EXCEEDED` answer halves the
     * size and re-asks the same ids (the smaller size then holds for the rest of the run); a single id still over the
     * cap is skipped, counted and logged, so the page — and the cursor behind it — can advance.
     */
    private suspend fun listWorklogs(context: StreamContext, ids: List<Long>, ask: ListAsk): List<JsonObject> {
        val details = mutableListOf<JsonObject>()
        var remaining = ids
        while (remaining.isNotEmpty()) {
            val chunk = remaining.take(ask.size)
            try {
                details += client.worklogList(chunk).map { it.jsonObject }
                remaining = remaining.drop(chunk.size)
            } catch (cause: JiraFetchException) {
                if (!cause.isOversized()) throw cause
                if (chunk.size > 1) {
                    val halved = halvedBatchSize(ask.size, chunk.size)
                    log.warn("WORKLOGS list over the response cap, data source {}: size {} -> {}", context.connectionId, chunk.size, halved)
                    ask.size = halved
                } else {
                    log.warn("WORKLOGS worklog {} over the response cap, data source {}: skipped", chunk.single(), context.connectionId)
                    context.incrementProgress("worklogsSkipped")
                    remaining = remaining.drop(1)
                }
            }
        }
        return details
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
