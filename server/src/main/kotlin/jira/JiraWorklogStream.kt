package ch.nokillswit.jira

import ch.nokillswit.fetch.BoundedAnswer
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.PageCeiling
import ch.nokillswit.fetch.halvedBatchSize
import ch.nokillswit.fetch.isOversized
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraWorklogStream::class.java)

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

/** The issues fetched and not yet written by one backfill batch, with their worklogs. */
private class FetchedBatch {
    val issueIds = mutableListOf<Long>()
    val worklogs = mutableListOf<RawWorklogInput>()

    /** The characters of stored text held, the memory bound beside the worklog count (a few huge worklogs are few). */
    var chars = 0L
        private set

    fun add(issueId: Long, issueWorklogs: List<RawWorklogInput>) {
        issueIds += issueId
        worklogs += issueWorklogs
        chars += issueWorklogs.sumOf { it.payloadJson.length }
    }

    fun full(): Boolean = worklogs.size >= BACKFILL_FLUSH_WORKLOGS || chars >= BACKFILL_FLUSH_CHARS

    fun clear() {
        issueIds.clear()
        worklogs.clear()
        chars = 0
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
 * `GET /issue/{id}/worklog` (`startAt`-paged; the issues of a batch fetched concurrently within the client's own request limit),
 * one heartbeat per issue and ONE transaction per claimed batch of
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
 *
 * Whole-answer ceilings (`jira-integration.md` "Worklog answer ceilings", [WorklogCeilings]): one issue's backfill chain is a
 * [BoundedAnswer] (text, worklogs, pages; past any the issue is skipped like an oversized page), and the incremental feed is
 * checked per page before anything is stored ([requirePlausible]: entries per page, a far-future or non-advancing `until`), its
 * `worklog/list` details are a [BoundedAnswer], and a run follows at most [WorklogCeilings.maxFeedPages] pages of each feed. A
 * feed failure is a typed `LIMIT_EXCEEDED`/`INVALID_RESPONSE` that fails the job BEFORE the page's transaction, so the cursor
 * stays at the last page that committed: never a truncated page, never a cursor past what was stored.
 */
class JiraWorklogStream internal constructor(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val ceilings: WorklogCeilings,
) : Stream {
    constructor(client: JiraClient, rawStore: JiraRawStore) : this(client, rawStore, WorklogCeilings())

    override val name: String = "worklogs"

    override suspend fun run(context: StreamContext) {
        backfillPerIssue(context)
        val runStartedAt = context.clock()
        val updatedSinceFinal = runIncrementalUpdated(context, runStartedAt)
        runIncrementalDeleted(context, runStartedAt, updatedSinceFinal)
    }

    /**
     * Claims [BACKFILL_BATCH_SIZE] stale issues at a time and fetches their worklogs concurrently ([fetchAndFlush]; one heartbeat
     * per issue, in claim order, an oversized issue skipped for the run). What was fetched is written in ONE transaction per batch
     * ([flushBackfill]): one
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
            context.heartbeat(changed = fetchAndFlush(context, batch, gaveUp, skips))
        }
    }

    /**
     * Fetches [batch] into a [FetchedBatch] and flushes it - early at the memory bound, and on a fetch failure.
     *
     * **Concurrency.** The issues' worklogs are fetched concurrently ([fetchAheadInOrder]) but SETTLED strictly in claim order:
     * the heartbeat, the skip budget, `gaveUp`, the progress counters, the [FetchedBatch] and the flushes all run in the single
     * consuming coroutine, one issue after another, exactly as the sequential loop did - so the stored rows, the stamps, the
     * early-flush points and the failure semantics ([backfillPerIssue]) are unchanged. A failure at issue N reaches the consumer
     * at position N, after issues 1..N-1 were stored; issues after N may already have been fetched, and are discarded (stamped
     * nowhere, left for the next run).
     *
     * The limit it rides is the client's own: every call still goes through `SourceHttp`'s `jira.maxConcurrentRequests` semaphore
     * (shared by the whole process), so at most that many GETs are ever in flight, with the same backoff, `Retry-After` and
     * guarded-client path as before. The look-ahead window IS that number (so a limit of 1 is the sequential loop) and adds no
     * limit of its own; on top of it no fetch starts while the finished-but-unstored issues already hold more than
     * [BACKFILL_FLUSH_WORKLOGS] worklogs, so memory is bounded by worklogs and not only by issues (the worst case:
     * `jira-integration.md` "Batched raw writes"). A page chain of ONE issue stays sequential ([fetchIssueWorklogPages]).
     * Pinned by `JiraWorklogConcurrencyTest`; the figures: `build-times.md` WHY 33.
     */
    private suspend fun fetchAndFlush(context: StreamContext, batch: List<Long>, gaveUp: MutableSet<Long>, skips: SkipBudget): Boolean {
        val fetched = FetchedBatch()
        var wrote = false
        try {
            fetchAheadInOrder(
                batch,
                window = client.maxConcurrentRequests.coerceAtLeast(1),
                fetch = ::fetchIssueWorklogPages,
                weight = List<RawWorklogInput>::size,
                budget = BACKFILL_FLUSH_WORKLOGS,
            ) { issueId, outcome ->
                val worklogs = settleFetch(context, issueId, outcome, gaveUp, skips) ?: return@fetchAheadInOrder
                fetched.add(issueId, worklogs)
                if (fetched.full()) wrote = flushBackfill(context, fetched) || wrote
            }
        } catch (cause: FetchException) {
            keepFetched(context, fetched, cause)
        } catch (cause: CeilingTripException) {
            keepFetched(context, fetched, cause) // the wall clock stopped the run: what was already fetched is stored first
        }
        return flushBackfill(context, fetched) || wrote
    }

    /** Flushes what was fetched before [cause] ended the run, then rethrows it; a failing flush must not hide [cause]. */
    private suspend fun keepFetched(context: StreamContext, fetched: FetchedBatch, cause: Exception): Nothing {
        val flushFailure = catchingFailures<Exception?>(block = { flushBackfill(context, fetched); null }, onFailure = { it })
        // The clock's trip was typed before the flush; rebuilt after it, it knows the flushed issues were stored (progress).
        if (cause is CeilingTripException && flushFailure == null) throw context.ceilingTrip(cause.code, cause.underlying)
        flushFailure?.let(cause::addSuppressed)
        throw cause
    }

    /** Writes and stamps the issues [fetched] so far in ONE transaction, then empties it (a no-op when none); true when it wrote. */
    private suspend fun flushBackfill(context: StreamContext, fetched: FetchedBatch): Boolean {
        if (fetched.issueIds.isEmpty()) return false
        context.transaction {
            val outcomes = rawStore.upsertWorklogs(context.connectionId, fetched.worklogs, context.clock())
            rawStore.markWorklogsSynced(context.connectionId, fetched.issueIds, context.clock())
            context.incrementProgress("worklogs", (fetched.worklogs.size - context.countRefused("worklogsSkipped", outcomes)).toLong())
        }
        fetched.clear()
        context.markChanged()
        return true
    }

    /** One issue's fetched [outcome], settled in claim order: its worklogs, or null when its page or answer is over a cap (skipped). */
    private suspend fun settleFetch(
        context: StreamContext,
        issueId: Long,
        outcome: Result<List<RawWorklogInput>>,
        gaveUp: MutableSet<Long>,
        skips: SkipBudget,
    ): List<RawWorklogInput>? {
        val worklogs = outcome.getOrElse { cause ->
            if (cause !is FetchException || !cause.isOversized()) throw cause
            log.warn(
                "WORKLOGS issue {} over the response cap or an answer ceiling, data source {}: skipped this run ({})",
                issueId, context.connectionId, cause.message,
            )
            gaveUp += issueId
            context.incrementProgress("worklogsIssuesSkipped")
            skips.skip()
            return null
        }
        context.heartbeat(changed = false) // fetched, not yet flushed (`flushBackfill`): the lease and the clock, not progress
        skips.settled()
        return worklogs
    }

    /**
     * One issue's whole worklog chain, `startAt`-paged, kept as the rows it will store within [WorklogCeilings.backfill]: a host
     * that never stops (a `total` it never reaches) or packs a giant answer is `LIMIT_EXCEEDED`, the issue skipped for the run.
     */
    private suspend fun fetchIssueWorklogPages(issueId: Long): List<RawWorklogInput> {
        val answer = BoundedAnswer<RawWorklogInput>(JIRA_SOURCE, ISSUE_WORKLOG_ENDPOINT, ceilings.backfill, "worklogs")
        var startAt = 0
        while (true) {
            answer.nextPage()
            val page = client.issueWorklogPageAs(issueId.toString(), startAt) { raw ->
                WorklogPageTexts(raw.worklogs.map { worklogInput(issueId, it.jsonObject, ISSUE_WORKLOG_ENDPOINT) }, raw.total)
            }
            page.worklogs.forEach { addWorklog(answer, it) }
            val consumed = page.worklogs.size
            val nextStartAt = startAt + consumed
            val isLast = nextStartAt >= page.total || consumed == 0
            if (isLast) break
            startAt = nextStartAt
        }
        return answer.items
    }

    private fun addWorklog(answer: BoundedAnswer<RawWorklogInput>, worklog: RawWorklogInput) =
        answer.add(worklog, worklog.payloadJson.length)

    /**
     * Returns the final `updatedSince` once every page of `/worklog/updated` has committed (`page.lastPage == true`). Each page is
     * checked ([requirePlausible]) and its details held within [WorklogCeilings.feedAnswer] BEFORE its transaction, and the run
     * follows at most [WorklogCeilings.maxFeedPages] pages: a failure there leaves the cursor at the last page that committed, so
     * nothing is skipped and the next run resumes.
     */
    private suspend fun runIncrementalUpdated(context: StreamContext, runStartedAt: Long): Long {
        val existing = loadCursor(context)
        var since = existing?.updatedSince ?: runStartedAt
        val deletedSince = existing?.deletedSince ?: runStartedAt
        val listAsk = ListAsk()
        val pages = PageCeiling(JIRA_SOURCE, WORKLOG_UPDATED_ENDPOINT, ceilings.maxFeedPages, "feed pages in one run")
        while (true) {
            pages.next()
            val page = client.worklogUpdated(since)
            page.requirePlausible(WORKLOG_UPDATED_ENDPOINT, since, context.clock(), ceilings.feedAnswer.maxItems)
            val next = nextSince(context, WORKLOG_UPDATED_ENDPOINT, page, since)
            var outOfScopeThisPage = 0
            var storedThisPage = 0
            if (page.values.isNotEmpty()) {
                val details = listWorklogs(context, page.values.map { it.worklogId }, listAsk)
                val knownIds = rawStore.knownInScopeIssueIds(context.connectionId, details.map { it.issueId }.distinct())
                context.transaction {
                    details.forEach { detail ->
                        if (detail.issueId in knownIds) {
                            val outcome = storeWorklog(context, detail)
                            if (outcome == RawUpsertOutcome.REFUSED) context.incrementProgress("worklogsSkipped") else storedThisPage++
                        } else {
                            outOfScopeThisPage++
                        }
                    }
                    context.putCursor(name, encodeCursor(WorklogsCursor(next, deletedSince)))
                    context.incrementProgress("worklogs", storedThisPage.toLong())
                    context.incrementProgress("worklogsOutOfScope", outOfScopeThisPage.toLong())
                }
            } else {
                context.transaction { context.putCursor(name, encodeCursor(WorklogsCursor(next, deletedSince))) }
            }
            context.heartbeat(changed = true) // a monotonic time cursor advances
            if (page.lastPage) return next
            since = next
        }
    }

    /**
     * Resolves [ids] through `POST /worklog/list` in batches of at most [ask]`.size`. A `LIMIT_EXCEEDED` answer halves the
     * size and re-asks the same ids (the smaller size then holds for the rest of the run); a single id still over the
     * cap is skipped, counted and logged, so the page — and the cursor behind it — can advance. The details are held as the rows
     * they will store within [WorklogCeilings.feedAnswer]; past it the page fails `LIMIT_EXCEEDED` (not halved: the answer, not a
     * response, is too big, and a truncated page must not advance the cursor).
     */
    private suspend fun listWorklogs(context: StreamContext, ids: List<Long>, ask: ListAsk): List<FeedWorklog> {
        val answer = BoundedAnswer<FeedWorklog>(JIRA_SOURCE, WORKLOG_LIST_ENDPOINT, ceilings.feedAnswer, "worklogs")
        var remaining = ids
        while (remaining.isNotEmpty()) {
            val chunk = remaining.take(ask.size)
            val listed = try {
                client.worklogListAs(chunk, ::feedWorklogsOf)
            } catch (cause: FetchException) {
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
                continue
            }
            listed.forEach { detail -> answer.add(detail, detail.payloadJson.length) }
            remaining = remaining.drop(chunk.size)
            ask.skips.settled(chunk.size)
        }
        return answer.items
    }

    /** [JiraWorklogIdsPage.advancedSince], with one WARN (constant text and numbers) when `until` would have rewound the cursor. */
    private fun nextSince(context: StreamContext, endpoint: String, page: JiraWorklogIdsPage, since: Long): Long {
        val next = page.advancedSince(since)
        if (next != page.until) {
            log.warn(
                "WORKLOGS feed {} answered until {} before since {}, data source {}: cursor kept",
                endpoint, page.until, since, context.connectionId,
            )
        }
        return next
    }

    private suspend fun runIncrementalDeleted(context: StreamContext, runStartedAt: Long, updatedSinceFinal: Long) {
        val existing = loadCursor(context)
        var since = existing?.deletedSince ?: runStartedAt
        val pages = PageCeiling(JIRA_SOURCE, WORKLOG_DELETED_ENDPOINT, ceilings.maxFeedPages, "feed pages in one run")
        while (true) {
            pages.next()
            val page = client.worklogDeleted(since)
            page.requirePlausible(WORKLOG_DELETED_ENDPOINT, since, context.clock(), ceilings.feedAnswer.maxItems)
            val next = nextSince(context, WORKLOG_DELETED_ENDPOINT, page, since)
            context.transaction {
                page.values.forEach { entry -> rawStore.tombstoneWorklog(context.connectionId, entry.worklogId, context.clock()) }
                context.putCursor(name, encodeCursor(WorklogsCursor(updatedSinceFinal, next)))
                context.incrementProgress("tombstoned", page.values.size.toLong())
            }
            context.heartbeat(changed = true) // a monotonic time cursor advances
            if (page.lastPage) break
            since = next
        }
    }

    /** The incremental feed's single-row write (a feed page is small: only worklogs touched since the connection began). */
    private suspend fun storeWorklog(context: StreamContext, detail: FeedWorklog): RawUpsertOutcome {
        // `id` and `updated` are read only now, and only for a worklog in scope: a foreign (out-of-scope) worklog of the
        // instance-wide feed is dropped without them being read (its `issueId` is the only field the scope filter needs).
        val input = worklogInput(detail.issueId, Json.parseToJsonElement(detail.payloadJson).jsonObject, WORKLOG_LIST_ENDPOINT)
        val now = context.clock()
        return rawStore.upsertWorklog(context.connectionId, input.issueId, input.worklogId, input.worklogUpdatedAt, input.payloadJson, now)
    }

    /**
     * A worklog as it will be stored. The payload is TRIMMED here ([trimWorklog], idempotent: the writers trim again to the same
     * canonical text), so what the stream holds until its transaction is what the ceilings count, not Jira's ~4x larger document.
     */
    private fun worklogInput(issueId: Long, worklog: JsonObject, endpoint: String) = RawWorklogInput(
        issueId = issueId,
        worklogId = worklog.requireLong("id", endpoint, "a worklog"),
        worklogUpdatedAt = worklog.requireMillis(endpoint, "a worklog"),
        payloadJson = trimWorklog(worklog).toString(),
    )

    private suspend fun loadCursor(context: StreamContext): WorklogsCursor? =
        context.cursor(name)?.cursor?.let { decodeCursor<WorklogsCursor>(it) }
}
