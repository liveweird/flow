package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val ISSUES_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** `sync_cursors.cursor` shape for the ISSUES stream (v0.2.0 plan §7). */
@Serializable
internal data class IssuesCursor(
    val watermarkAt: Long? = null,
    /**
     * The exact JQL string driving THIS run — computed once at the run's start, then reused for
     * every page (`sample-data/README.md`: the stub ignores JQL text, but a real tenant's paging
     * must reuse the same query).
     */
    val jql: String,
    val nextPageToken: String? = null,
    val runStartedAt: Long,
)

/**
 * A restart bound for [JiraFetchException]'s `CURSOR_EXPIRED` — a real Jira token never expires
 * again immediately after a fresh query; this only guards against a pathological/misbehaving
 * upstream.
 */
private const val MAX_CURSOR_RESTARTS = 5

/**
 * The ISSUES stream (v0.2.0 plan §7): pages `search/jql` with an opaque `nextPageToken`, JQL scoped
 * by [projectKeys] with a relative `updated` bound (`JiraJql.incremental`) — TZ-free by design
 * (`jira/JiraJql.kt`). Fields are requested with `fields = null` (Jira's own default — EVERY
 * field), matching the stub's "no `fields` param" full-document response
 * (`.claude/docs/jira-integration.md` "ISSUES stream fields"); this also serves the normalization
 * layer landing in a later commit, since Jira does not let a caller ask for "every SYSTEM field
 * plus every discovered custom field" any more cheaply than asking for all of them.
 *
 * `N` (the relative `-Nm` window) is minutes since `backfillFromEpochMillis` on the very first run,
 * or minutes since the last completed run's watermark plus [incrementalOverlapMinutes] afterward.
 * The cursor advances in the SAME transaction as each page's raw-issue upserts
 * (`ingest/Stream.kt`'s `StreamContext.transaction`); the watermark itself only moves to this run's
 * `runStartedAt` once the LAST page of that run is written — a lease loss or thrown fault mid-run
 * leaves it exactly where the last completed page left it.
 */
class JiraIssuesStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
    private val backfillFromEpochMillis: Long,
    private val incrementalOverlapMinutes: Long,
    private val pageSize: Int,
) : Stream {
    override val name: String = "issues"

    override suspend fun run(context: StreamContext) {
        var state = loadOrStart(context)
        var restarts = 0
        while (true) {
            val page = try {
                client.searchJql(state.jql, fields = null, nextPageToken = state.nextPageToken, maxResults = pageSize)
            } catch (expired: JiraFetchException) {
                if (expired.code != "CURSOR_EXPIRED" || restarts >= MAX_CURSOR_RESTARTS) throw expired
                restarts++
                state = freshRun(context, state.watermarkAt)
                continue
            }
            val isLast = page.nextPageToken == null
            val newWatermark = if (isLast) state.runStartedAt else state.watermarkAt
            context.transaction {
                page.issues.forEach { issueElement ->
                    val issue = issueElement.jsonObject
                    val fields = issue.getValue("fields").jsonObject
                    val project = fields.getValue("project").jsonObject
                    rawStore.upsertIssue(
                        context.connectionId,
                        RawIssueInput(
                            issueId = issue.getValue("id").jsonPrimitive.content.toLong(),
                            issueKey = issue.getValue("key").jsonPrimitive.content,
                            projectId = project.getValue("id").jsonPrimitive.content.toLong(),
                            projectKey = project.getValue("key").jsonPrimitive.content,
                            issueUpdatedAt = Instant.parse(fields.getValue("updated").jsonPrimitive.content).toEpochMilli(),
                            payloadJson = issue.toString(),
                        ),
                        context.clock(),
                    )
                }
                context.putCursor(
                    name,
                    ISSUES_CURSOR_JSON.encodeToString(IssuesCursor(newWatermark, state.jql, page.nextPageToken, state.runStartedAt)),
                    watermarkAt = newWatermark,
                    lastCompletedAt = if (isLast) context.clock() else null,
                )
                context.incrementProgress("pages")
                context.incrementProgress("issuesUpserted", page.issues.size.toLong())
            }
            context.heartbeat()
            if (isLast) break
            state = state.copy(nextPageToken = page.nextPageToken, watermarkAt = newWatermark)
        }
    }

    /**
     * A persisted `nextPageToken` means a prior run was interrupted mid-page — resume it AS-IS
     * (same JQL/runStartedAt/token); otherwise start a fresh run from the watermark.
     */
    private suspend fun loadOrStart(context: StreamContext): IssuesCursor {
        val existing = context.cursor(name)?.cursor?.let { ISSUES_CURSOR_JSON.decodeFromString<IssuesCursor>(it) }
        if (existing?.nextPageToken != null) return existing
        return freshRun(context, existing?.watermarkAt)
    }

    private fun freshRun(context: StreamContext, watermarkAt: Long?): IssuesCursor {
        val now = context.clock()
        val sinceMinutes = if (watermarkAt != null) {
            minutesBetween(watermarkAt, now) + incrementalOverlapMinutes
        } else {
            minutesBetween(backfillFromEpochMillis, now)
        }
        return IssuesCursor(
            watermarkAt = watermarkAt,
            jql = JiraJql.incremental(projectKeys, sinceMinutes),
            nextPageToken = null,
            runStartedAt = now,
        )
    }
}

/** Minutes between two epoch-millis instants, rounded UP (never under-covers a partial minute) and floored at zero. */
private fun minutesBetween(fromMillis: Long, toMillis: Long): Long {
    val millis = toMillis - fromMillis
    if (millis <= 0) return 0
    return (millis + 59_999L) / 60_000L
}
