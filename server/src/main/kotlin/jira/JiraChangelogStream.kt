package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val CHANGELOGS_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

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
private const val BULK_UNAVAILABLE_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

/**
 * The CHANGELOGS stream (v0.2.0 plan §7): batches of `jira.changelogBulkSize` (default 50 — MUST
 * match `sample-data/jira-stub`'s own fixed 50-id chunking, `sample-data/README.md`) STALE issues —
 * `changelog_synced_at IS NULL` (never synced) or older than `changed_at` (the issue changed since
 * its last sync) — read straight off `JiraRawStore.staleChangelogIssueIds`, ascending issue id.
 * Resumability needs no page-position cursor of its own: an interrupted batch's issues are simply
 * still stale on the next run (nothing was marked synced, and `insertChangelog`'s `ON CONFLICT DO
 * NOTHING` makes a re-fetched history a no-op) — the ONE thing genuinely worth remembering across
 * runs is [ChangelogsCursor.bulkUnavailableUntil], so a 24h-unavailable bulk endpoint isn't
 * re-probed every single batch.
 *
 * A batch normally goes through `changelog/bulkfetch` (paged by `nextPageToken`, per Jira's own
 * bulkfetch contract) in ONE transaction with marking every issue in the batch `changelog_synced_at`
 * — a 404/405/410/501 falls back to per-issue `GET /issue/{id}/changelog` (`startAt`-paged) for
 * EXACTLY that batch, one issue per transaction+heartbeat (finer-grained resumability, since each
 * issue is its own sequence of HTTP calls).
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

        while (true) {
            val batch = rawStore.staleChangelogIssueIds(context.connectionId, bulkSize)
            if (batch.isEmpty()) break

            val handledByBulk = !skipBulkThisRun && tryBulk(context, batch)
            if (!handledByBulk) {
                if (!cursorWritten) {
                    val bulkUnavailableUntil = context.clock() + BULK_UNAVAILABLE_WINDOW_MILLIS
                    context.transaction { context.putCursor(name, encode(ChangelogsCursor(bulkUnavailableUntil))) }
                    cursorWritten = true
                }
                perIssueFallback(context, batch)
            }
        }
    }

    /**
     * Attempts [batch] via `changelog/bulkfetch` in ONE transaction — `false` (having swallowed
     * nothing but a bulk-unavailable status) means the CALLER must fall back to per-issue fetches
     * for this batch instead; any other failure propagates.
     */
    private suspend fun tryBulk(context: StreamContext, batch: List<Long>): Boolean {
        val logs = try {
            fetchBulk(batch)
        } catch (cause: JiraFetchException) {
            if (cause.status !in BULK_UNAVAILABLE_STATUSES) throw cause
            return false
        }
        context.transaction {
            storeBulkResult(context, logs)
            rawStore.markChangelogSynced(context.connectionId, batch, context.clock())
            context.incrementProgress("changelogs", logs.sumOf { it["changeHistories"]?.jsonArray?.size ?: 0 }.toLong())
        }
        context.heartbeat()
        return true
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

    private suspend fun storeBulkResult(context: StreamContext, logs: List<JsonObject>) {
        logs.forEach { entry ->
            val issueId = entry.getValue("issueId").jsonPrimitive.content.toLong()
            val histories = entry["changeHistories"]?.jsonArray ?: emptyList()
            histories.forEach { storeHistory(context, issueId, it.jsonObject) }
        }
    }

    /**
     * The per-issue fallback (v0.2.0 plan §7): one issue per transaction+heartbeat, `startAt`-paged
     * (the stub's per-issue changelog pages are deliberately small, `sample-data/README.md`).
     */
    private suspend fun perIssueFallback(context: StreamContext, issueIds: List<Long>) {
        issueIds.forEach { issueId ->
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
            context.transaction {
                histories.forEach { storeHistory(context, issueId, it) }
                rawStore.markChangelogSynced(context.connectionId, listOf(issueId), context.clock())
                context.incrementProgress("changelogs", histories.size.toLong())
            }
            context.heartbeat()
        }
    }

    private suspend fun storeHistory(context: StreamContext, issueId: Long, history: JsonObject) {
        val historyId = history.getValue("id").jsonPrimitive.content.toLong()
        val createdAt = Instant.parse(history.getValue("created").jsonPrimitive.content).toEpochMilli()
        val authorAccountId = history["author"]?.jsonObject?.get("accountId")?.jsonPrimitive?.contentOrNull
        rawStore.insertChangelog(context.connectionId, historyId, issueId, createdAt, authorAccountId, history.toString(), context.clock())
    }

    private suspend fun loadCursor(context: StreamContext): ChangelogsCursor? =
        context.cursor(name)?.cursor?.let { CHANGELOGS_CURSOR_JSON.decodeFromString<ChangelogsCursor>(it) }

    private fun encode(cursor: ChangelogsCursor): String = CHANGELOGS_CURSOR_JSON.encodeToString(cursor)
}
