package ch.nokillswit.jira

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraChangelogStream::class.java)

/** `sync_cursors.cursor` shape for the CHANGELOGS stream (v0.2.0 plan §7). */
@Serializable
internal data class ChangelogsCursor(
    /**
     * Set for [BULK_UNAVAILABLE_WINDOW_MILLIS] after a `changelog/bulkfetch` 404/405/410/501 —
     * checked ONCE at the START of a run: a run that BEGINS inside this window skips bulk
     * entirely, going straight to the per-issue fallback for EVERY batch. A run that begins
     * OUTSIDE this window still tries bulk fresh on every batch even after one batch fails mid-run
     * (`sample-data/README.md`: the omitted bulkfetch chunk's issues use the fallback "and only
     * those; every other chunk has a real mapping" — one chunk-specific failure must not blind the
     * REST of the same run's batches to a bulk endpoint that works fine for them). `null` (the
     * default) means bulk is assumed available.
     */
    val bulkUnavailableUntil: Long? = null,
)

/**
 * A `changelog/bulkfetch` response in one of these statuses means the endpoint itself is
 * unavailable (GA/scope gap, not a transient failure) — the per-issue fallback, not a retry, is the
 * right response (`.claude/docs/jira-integration.md`).
 */
private val BULK_UNAVAILABLE_STATUSES = setOf(404, 405, 410, 501)
private const val BULK_UNAVAILABLE_WINDOW_MILLIS = MILLIS_PER_DAY

/** Jira's own `changelog/bulkfetch` `maxResults` default; `jira.changelogPageSize`'s default. */
internal const val DEFAULT_CHANGELOG_PAGE_SIZE = 1_000

/**
 * The ceiling on ONE batch's whole `changelog/bulkfetch` answer, or one issue's whole per-issue answer, held until its one
 * transaction (`jira.maxResponseBytes` bounds each PAGE only): the history text kept, the histories (rows: each costs ~700 B of
 * row objects on the way into Postgres, whatever its text) and the pages followed (an endless `nextPageToken` or `total`). A real
 * batch is far under all three (50 issues x ~37 histories x ~1.35 KB is ~2.5 MB, ~1,850 rows, 2 pages). Sized against the worker
 * heap (512 MiB, two slots): 16 Mi chars is up to 32 MiB as UTF-16, held twice while it is written, plus 50k rows x ~700 B, so
 * ~100 MiB per slot at worst. Over any = `LIMIT_EXCEEDED`, the oversized path (halve, per issue, skip the issue).
 */
internal const val MAX_ANSWER_CHARS = 16L * 1024 * 1024
internal const val MAX_ANSWER_HISTORIES = 50_000
internal const val MAX_ANSWER_PAGES = 200

private const val BULK_ENDPOINT = "/rest/api/3/changelog/bulkfetch"
private const val PER_ISSUE_ENDPOINT = "/rest/api/3/issue/{id}/changelog"

/** What one `changelog/bulkfetch` attempt for a batch came to. */
private enum class BulkOutcome { DONE, UNAVAILABLE, TOO_LARGE }

/**
 * The CHANGELOGS stream (v0.2.0 plan §7): batches of `jira.changelogBulkSize` (default 50 — MUST
 * match `sample-data/jira-stub`'s own fixed 50-id chunking, `sample-data/README.md`) STALE issues —
 * `changelog_synced_at IS NULL` (never synced) or older than `changed_at` (the issue changed since
 * its last sync) — read straight off `JiraRawStore.staleChangelogIssueIds`, ascending issue id.
 * Resumability needs no page-position cursor of its own: an interrupted batch's issues are simply
 * still stale on the next run (nothing was marked synced, and `insertChangelogs`'s `ON CONFLICT DO
 * NOTHING` makes a re-fetched history a no-op) — the ONE thing genuinely worth remembering across
 * runs is [ChangelogsCursor.bulkUnavailableUntil], so a 24h-unavailable bulk endpoint isn't
 * re-probed every single batch.
 *
 * Each bulkfetch page asks `jira.changelogPageSize` histories (default 1,000 = Jira's own default, max 10,000), not one
 * per issue in the batch: at the real tenant's ~37 histories per issue a 50-issue batch is ~2 calls instead of ~37, and
 * the changelog fetch was ~75 % of a backfill (`.claude/docs/jira-integration.md` "Changelog page size").
 *
 * A batch normally goes through `changelog/bulkfetch` (paged by `nextPageToken`, per Jira's own
 * bulkfetch contract) in ONE transaction with marking every issue in the batch `changelog_synced_at`
 * — a 404/405/410/501 falls back to per-issue `GET /issue/{id}/changelog` (`startAt`-paged) for
 * EXACTLY that batch, one issue per transaction+heartbeat (finer-grained resumability, since each
 * issue is its own sequence of HTTP calls).
 *
 * Oversized batch (`.claude/docs/jira-integration.md` "Oversized changelog/worklog batch"): a bulk response over
 * `jira.maxResponseBytes` (`LIMIT_EXCEEDED`) is re-asked as a batch of half the size (floor 1) for the rest of this
 * invocation (not persisted); a single issue still over the cap goes to the per-issue endpoint, and an issue that
 * is over the cap there too is skipped for this run (`changelogsSkipped`) rather than stalling the stream — unless the
 * run skips more than `max(10, 10% of the issues it attempted)`, which fails the stream `LIMIT_EXCEEDED` ([SkipBudget]).
 * A whole answer past [MAX_ANSWER_CHARS] of history text, [MAX_ANSWER_HISTORIES] histories or [MAX_ANSWER_PAGES] pages is oversized
 * the same way ([BoundedAnswer]).
 */
class JiraChangelogStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val bulkSize: Int,
    private val pageSize: Int = DEFAULT_CHANGELOG_PAGE_SIZE,
    private val maxAnswerChars: Long = MAX_ANSWER_CHARS,
    private val maxAnswerHistories: Int = MAX_ANSWER_HISTORIES,
    private val maxAnswerPages: Int = MAX_ANSWER_PAGES,
) : Stream {
    override val name: String = "changelogs"

    private val ceiling = AnswerCeiling(maxAnswerChars, maxAnswerHistories, maxAnswerPages)

    /** Why the last [BulkOutcome.TOO_LARGE] was oversized (the byte cap or the answer ceiling), for its WARN. */
    private var lastOversized: String? = null

    override suspend fun run(context: StreamContext) {
        // Checked ONCE, not re-evaluated per batch (see ChangelogsCursor's kdoc): a fresh failure
        // discovered PARTWAY through this run still lets every LATER batch try bulk normally.
        val bulkUnavailableAtStart = loadCursor(context)?.bulkUnavailableUntil
        val skipBulkThisRun = bulkUnavailableAtStart != null && context.clock() < bulkUnavailableAtStart
        var cursorWritten = skipBulkThisRun
        // Run-local, never persisted: the batch size only shrinks (oversized bulk responses), and the issues whose own
        // response still exceeds the cap are left stale for the next run but not claimed again by this one.
        var size = bulkSize
        val gaveUp = mutableSetOf<Long>()
        val skips = SkipBudget("CHANGELOGS", "issues")

        while (true) {
            val batch = rawStore.staleChangelogIssueIds(context.connectionId, size, gaveUp)
            if (batch.isEmpty()) break

            when (if (skipBulkThisRun) BulkOutcome.UNAVAILABLE else tryBulk(context, batch)) {
                BulkOutcome.DONE -> skips.settled(batch.size)
                BulkOutcome.TOO_LARGE ->
                    if (batch.size > 1) {
                        val halved = halvedBatchSize(size, batch.size)
                        log.warn(
                            "CHANGELOGS batch oversized, data source {}: size {} -> {} ({})",
                            context.connectionId, batch.size, halved, lastOversized,
                        )
                        size = halved
                    } else {
                        // One issue's bulk answer is over the cap: the per-issue endpoint pages it in smaller pieces.
                        perIssueFallback(context, batch, gaveUp, skips)
                    }
                BulkOutcome.UNAVAILABLE -> {
                    if (!cursorWritten) {
                        val bulkUnavailableUntil = context.clock() + BULK_UNAVAILABLE_WINDOW_MILLIS
                        context.transaction { context.putCursor(name, encodeCursor(ChangelogsCursor(bulkUnavailableUntil))) }
                        cursorWritten = true
                    }
                    perIssueFallback(context, batch, gaveUp, skips)
                }
            }
        }
    }

    /**
     * Attempts [batch] via `changelog/bulkfetch` in ONE transaction. [BulkOutcome.UNAVAILABLE] (having swallowed nothing
     * but a bulk-unavailable status) means the CALLER must fall back to per-issue fetches for this batch instead;
     * [BulkOutcome.TOO_LARGE] (a `LIMIT_EXCEEDED` on any page, the whole batch's partial answer discarded) means ask a
     * smaller batch; any other failure propagates.
     */
    private suspend fun tryBulk(context: StreamContext, batch: List<Long>): BulkOutcome {
        val histories = try {
            fetchBulk(batch)
        } catch (cause: JiraFetchException) {
            return when {
                cause.status in BULK_UNAVAILABLE_STATUSES -> BulkOutcome.UNAVAILABLE
                cause.isOversized() -> BulkOutcome.TOO_LARGE.also { lastOversized = cause.message }
                else -> throw cause
            }
        }
        context.transaction {
            rawStore.insertChangelogs(context.connectionId, histories, context.clock())
            rawStore.markChangelogSynced(context.connectionId, batch, context.clock())
            context.incrementProgress("changelogs", histories.size.toLong())
        }
        context.heartbeat()
        return BulkOutcome.DONE
    }

    /**
     * Pages a `changelog/bulkfetch` call by `nextPageToken` until the response stops carrying one, keeping each page only as the
     * rows it will store (the whole answer goes to one [JiraRawStore.insertChangelogs], duplicates dropped), within [BoundedAnswer].
     */
    private suspend fun fetchBulk(issueIds: List<Long>): List<RawChangelogInput> {
        val answer = BoundedAnswer<RawChangelogInput>(BULK_ENDPOINT, ceiling, "histories")
        var token: String? = null
        while (true) {
            answer.nextPage()
            val response = client.changelogBulk(issueIds.map { it.toString() }, nextPageToken = token, maxResults = pageSize)
            response.getValue("issueChangeLogs").jsonArray.forEach { element ->
                val entry = element.jsonObject
                val issueId = entry.getValue("issueId").jsonPrimitive.content.toLong()
                entry["changeHistories"]?.jsonArray?.forEach { addHistory(answer, historyInput(issueId, it.jsonObject)) }
            }
            token = response["nextPageToken"]?.jsonPrimitive?.contentOrNull
            if (token == null) break
        }
        return answer.items
    }

    /**
     * The per-issue fallback (v0.2.0 plan §7): one issue per transaction+heartbeat, `startAt`-paged
     * (the stub's per-issue changelog pages are deliberately small, `sample-data/README.md`). An issue whose own
     * response is over the byte cap (`LIMIT_EXCEEDED`, the client has no page size to shrink) is isolated: it stays
     * stale for the next run, joins [gaveUp] so this run does not claim it again, ticks `changelogsSkipped` and logs
     * one WARN; the rest of the batch and of the stream carry on, until [skips] says the run has skipped too many to be
     * outliers (more than max(10, 10% of the issues attempted) — the stream then fails `LIMIT_EXCEEDED`).
     */
    private suspend fun perIssueFallback(context: StreamContext, issueIds: List<Long>, gaveUp: MutableSet<Long>, skips: SkipBudget) {
        for (issueId in issueIds) {
            val histories = try {
                fetchIssueHistories(issueId)
            } catch (cause: JiraFetchException) {
                if (!cause.isOversized()) throw cause
                log.warn(
                    "CHANGELOGS issue {} oversized, data source {}: skipped this run, its history stays unfetched ({})",
                    issueId, context.connectionId, cause.message,
                )
                gaveUp += issueId
                context.incrementProgress("changelogsSkipped")
                skips.skip()
                continue
            }
            context.transaction {
                rawStore.insertChangelogs(context.connectionId, histories, context.clock())
                rawStore.markChangelogSynced(context.connectionId, listOf(issueId), context.clock())
                context.incrementProgress("changelogs", histories.size.toLong())
            }
            context.heartbeat()
            skips.settled()
        }
    }

    private suspend fun fetchIssueHistories(issueId: Long): List<RawChangelogInput> {
        val answer = BoundedAnswer<RawChangelogInput>(PER_ISSUE_ENDPOINT, ceiling, "histories")
        var startAt = 0
        while (true) {
            answer.nextPage()
            val page = client.issueChangelogPage(issueId.toString(), startAt)
            page.histories.forEach { addHistory(answer, historyInput(issueId, it.jsonObject)) }
            val consumed = page.histories.size
            val nextStartAt = startAt + consumed
            val isLast = page.isLast == true || nextStartAt >= page.total || consumed == 0
            if (isLast) break
            startAt = nextStartAt
        }
        return answer.items
    }

    private fun addHistory(answer: BoundedAnswer<RawChangelogInput>, history: RawChangelogInput) =
        answer.add(history, history.payloadJson.length)

    private fun historyInput(issueId: Long, history: JsonObject): RawChangelogInput = RawChangelogInput(
        historyId = history.getValue("id").jsonPrimitive.content.toLong(),
        issueId = issueId,
        createdAt = parseJiraInstantEpochMillis(history.getValue("created").jsonPrimitive.content),
        authorAccountId = history["author"].orNullObject()?.get("accountId")?.jsonPrimitive?.contentOrNull,
        payloadJson = history.toString(),
    )

    private suspend fun loadCursor(context: StreamContext): ChangelogsCursor? =
        context.cursor(name)?.cursor?.let { decodeCursor<ChangelogsCursor>(it) }
}
