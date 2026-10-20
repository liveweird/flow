package ch.nokillswit.jira

import ch.nokillswit.infra.catchingFailures
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

/**
 * The backfill flushes (writes and stamps) a batch early once it holds this many worklogs, so a batch of long-lived tickets
 * (hundreds of worklogs each) stays bounded in memory; an ordinary batch of 100 issues is far below it.
 */
private const val BACKFILL_FLUSH_WORKLOGS = 5_000

/** The issues fetched and not yet written by one backfill batch, with their worklogs. */
private class FetchedBatch {
    val issueIds = mutableListOf<Long>()
    val worklogs = mutableListOf<RawWorklogInput>()

    fun add(issueId: Long, issueWorklogs: List<RawWorklogInput>) {
        issueIds += issueId
        worklogs += issueWorklogs
    }

    fun clear() {
        issueIds.clear()
        worklogs.clear()
    }
}

/** `POST /worklog/list`'s documented ceiling on ids per call; the run's id-batch size starts here and only shrinks. */
private const val WORKLOG_LIST_MAX_IDS = 1000

/** The run-local (never persisted) size of a `worklog/list` id batch (starts at [WORKLOG_LIST_MAX_IDS], only shrinks) and skip budget. */
private class ListAsk(var size: Int = WORKLOG_LIST_MAX_IDS) {
    /** Listed worklogs skipped vs. asked over the whole run (the threshold is per run, across the feed's pages). */
    val skips = SkipBudget("WORKLOGS", "listed worklogs")
}

/**
 * The WORKLOGS stream (v0.2.0 plan §0 A1/§7): the per-issue backfill first, then the incremental
 * `updated`/`deleted` cursor — deliberately NEVER reads the instance-wide `/worklog/updated` feed
 * back to `backfillFrom`, which would sweep every OTHER unit's time tracking into Flow (A1's
 * rationale, `.claude/docs/ingestion.md`).
 *
 * **Backfill.** Issues with `worklogs_synced_at IS NULL` (not deleted) — first ingested OR resurrected
 * (`JiraRawStore.upsertIssue` clears the stamp when it un-tombstones a row: the incremental feed below
 * skips tombstoned issues and moves on, so a returning issue's worklogs must be re-read) — go through
 * `GET /issue/{id}/worklog` (`startAt`-paged), one heartbeat per issue and ONE transaction per claimed batch of
 * [BACKFILL_BATCH_SIZE] issues (`backfillPerIssue`: its failure semantics).
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
 * (`worklogsIssuesSkipped`), so neither path can stall the stream. Each skip counter has its own run-local [SkipBudget]:
 * past `max(10, 10% of the ids attempted)` skips the stream fails `LIMIT_EXCEEDED` (the cap is too small, not an outlier).
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

    /**
     * Claims [BACKFILL_BATCH_SIZE] stale issues at a time and fetches each one's worklogs sequentially (one heartbeat per issue,
     * an oversized issue skipped for the run). What was fetched is written in ONE transaction per batch ([flushBackfill]): one
     * [JiraRawStore.upsertWorklogs] and one [JiraRawStore.markWorklogsSynced] over the fetched issues' ids
     * (`.claude/docs/build-times.md` WHY 21). A batch that has gathered [BACKFILL_FLUSH_WORKLOGS] worklogs is flushed early, so
     * one batch of long-lived tickets never holds an unbounded number of them in memory.
     *
     * **Failure semantics.** Progress is committed per flush, not per issue. The upserts and the synced stamps share the
     * transaction, so an issue is never stamped without its worklogs having been written. A fetch failure that ends the stream
     * (a non-oversized error on issue N, or the skip budget) first flushes the issues fetched BEFORE it - they are stored and
     * stamped exactly as the per-issue commits did - and then rethrows, so a persistently failing issue never throws the
     * progress of its batch away on every run. A failure of the write itself, or a lost lease, leaves the unflushed issues
     * unstamped: the next run claims them again (`worklogs_synced_at` is still NULL) and re-fetches them, at most one batch of
     * GETs, never a gap or a duplicate.
     */
    private suspend fun backfillPerIssue(context: StreamContext) {
        // Run-local: issues whose own response is over the cap stay unsynced for the next run but are not re-claimed here.
        val gaveUp = mutableSetOf<Long>()
        val skips = SkipBudget("WORKLOGS", "issues")
        while (true) {
            val batch = rawStore.staleWorklogIssueIds(context.connectionId, BACKFILL_BATCH_SIZE, gaveUp)
            if (batch.isEmpty()) break
            fetchAndFlush(context, batch, gaveUp, skips)
            context.heartbeat()
        }
    }

    /** Fetches [batch] issue by issue into a [FetchedBatch] and flushes it - early at the memory bound, and on a fetch failure. */
    private suspend fun fetchAndFlush(context: StreamContext, batch: List<Long>, gaveUp: MutableSet<Long>, skips: SkipBudget) {
        val fetched = FetchedBatch()
        try {
            for (issueId in batch) {
                val (_, worklogs) = fetchOneIssue(context, issueId, gaveUp, skips) ?: continue
                fetched.add(issueId, worklogs.map { worklogInput(issueId, it) })
                if (fetched.worklogs.size >= BACKFILL_FLUSH_WORKLOGS) flushBackfill(context, fetched)
            }
        } catch (cause: JiraFetchException) {
            // Keep what was fetched before the failure; a failing flush must not hide the fetch failure that ends the run.
            catchingFailures<Exception?>(block = { flushBackfill(context, fetched); null }, onFailure = { it })
                ?.let(cause::addSuppressed)
            throw cause
        }
        flushBackfill(context, fetched)
    }

    /** Writes and stamps the issues [fetched] so far in ONE transaction, then empties it (a no-op when nothing was fetched). */
    private suspend fun flushBackfill(context: StreamContext, fetched: FetchedBatch) {
        if (fetched.issueIds.isEmpty()) return
        context.transaction {
            rawStore.upsertWorklogs(context.connectionId, fetched.worklogs, context.clock())
            rawStore.markWorklogsSynced(context.connectionId, fetched.issueIds, context.clock())
            context.incrementProgress("worklogs", fetched.worklogs.size.toLong())
        }
        fetched.clear()
    }

    /** One issue's worklogs, or null when its own page is over the cap (skipped for this run, left unsynced). */
    private suspend fun fetchOneIssue(
        context: StreamContext,
        issueId: Long,
        gaveUp: MutableSet<Long>,
        skips: SkipBudget,
    ): Pair<Long, List<JsonObject>>? {
        val worklogs = try {
            fetchIssueWorklogPages(issueId)
        } catch (cause: JiraFetchException) {
            if (!cause.isOversized()) throw cause
            log.warn("WORKLOGS issue {} over the response cap, data source {}: skipped this run", issueId, context.connectionId)
            gaveUp += issueId
            context.incrementProgress("worklogsIssuesSkipped")
            skips.skip()
            return null
        }
        context.heartbeat()
        skips.settled()
        return issueId to worklogs
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
                ask.skips.settled(chunk.size)
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
                    ask.skips.skip()
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

    /** The incremental feed's single-row write (a feed page is small: only worklogs touched since the connection began). */
    private suspend fun storeWorklog(context: StreamContext, issueId: Long, worklog: JsonObject) {
        val input = worklogInput(issueId, worklog)
        rawStore.upsertWorklog(context.connectionId, issueId, input.worklogId, input.worklogUpdatedAt, input.payloadJson, context.clock())
    }

    private fun worklogInput(issueId: Long, worklog: JsonObject) = RawWorklogInput(
        issueId = issueId,
        worklogId = worklog.getValue("id").jsonPrimitive.content.toLong(),
        worklogUpdatedAt = parseJiraInstantEpochMillis(worklog.getValue("updated").jsonPrimitive.content),
        payloadJson = worklog.toString(),
    )

    private fun JsonObject.issueIdLong(): Long = getValue("issueId").jsonPrimitive.content.toLong()

    private suspend fun loadCursor(context: StreamContext): WorklogsCursor? =
        context.cursor(name)?.cursor?.let { WORKLOGS_CURSOR_JSON.decodeFromString<WorklogsCursor>(it) }

    private fun encode(cursor: WorklogsCursor): String = WORKLOGS_CURSOR_JSON.encodeToString(cursor)
}
