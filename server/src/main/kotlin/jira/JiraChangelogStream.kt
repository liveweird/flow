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
 */
class JiraChangelogStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val bulkSize: Int,
) : Stream {
    override val name: String = "changelogs"

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
                            "CHANGELOGS batch over the response cap, data source {}: size {} -> {}",
                            context.connectionId, batch.size, halved,
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
        val logs = try {
            fetchBulk(batch)
        } catch (cause: JiraFetchException) {
            return when {
                cause.status in BULK_UNAVAILABLE_STATUSES -> BulkOutcome.UNAVAILABLE
                cause.isOversized() -> BulkOutcome.TOO_LARGE
                else -> throw cause
            }
        }
        context.transaction {
            storeBulkResult(context, logs)
            rawStore.markChangelogSynced(context.connectionId, batch, context.clock())
            context.incrementProgress("changelogs", logs.sumOf { it["changeHistories"]?.jsonArray?.size ?: 0 }.toLong())
        }
        context.heartbeat()
        return BulkOutcome.DONE
    }

    /** Pages a `changelog/bulkfetch` call by `nextPageToken` until the response stops carrying one. */
    private suspend fun fetchBulk(issueIds: List<Long>): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var token: String? = null
        while (true) {
            val response = client.changelogBulk(issueIds.map { it.toString() }, nextPageToken = token, maxResults = bulkSize)
            results += response.getValue("issueChangeLogs").jsonArray.map { it.jsonObject }
            token = response["nextPageToken"]?.jsonPrimitive?.contentOrNull
            if (token == null) break
        }
        return results
    }

    /** The whole bulk answer's histories in one [JiraRawStore.insertChangelogs] (one multi-row insert per chunk, duplicates dropped). */
    private suspend fun storeBulkResult(context: StreamContext, logs: List<JsonObject>) {
        val histories = logs.flatMap { entry ->
            val issueId = entry.getValue("issueId").jsonPrimitive.content.toLong()
            (entry["changeHistories"]?.jsonArray ?: emptyList()).map { historyInput(issueId, it.jsonObject) }
        }
        rawStore.insertChangelogs(context.connectionId, histories, context.clock())
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
                    "CHANGELOGS issue {} over the response cap, data source {}: skipped this run, its history stays unfetched",
                    issueId, context.connectionId,
                )
                gaveUp += issueId
                context.incrementProgress("changelogsSkipped")
                skips.skip()
                continue
            }
            context.transaction {
                rawStore.insertChangelogs(context.connectionId, histories.map { historyInput(issueId, it) }, context.clock())
                rawStore.markChangelogSynced(context.connectionId, listOf(issueId), context.clock())
                context.incrementProgress("changelogs", histories.size.toLong())
            }
            context.heartbeat()
            skips.settled()
        }
    }

    private suspend fun fetchIssueHistories(issueId: Long): List<JsonObject> {
        val histories = mutableListOf<JsonObject>()
        var startAt = 0
        while (true) {
            val page = client.issueChangelogPage(issueId.toString(), startAt)
            histories += page.histories.map { it.jsonObject }
            val consumed = page.histories.size
            val nextStartAt = startAt + consumed
            val isLast = page.isLast == true || nextStartAt >= page.total || consumed == 0
            if (isLast) break
            startAt = nextStartAt
        }
        return histories
    }

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
