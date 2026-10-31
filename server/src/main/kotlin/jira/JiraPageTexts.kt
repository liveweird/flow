package ch.nokillswit.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// What the accumulating streams keep of a response page: the trimmed item TEXTS and the paging fields, never the parsed page.
// They are built inside `JiraClient.*As`'s convert lambda, which `HttpJiraClient` runs while the big-parse lock is held
// (`SourceHttp.requestAs`), so the page tree is unreachable by the time the stream sees the result.

/** One `changelog/bulkfetch` page: its histories as the rows they will store, and the token of the next page (null on the last). */
internal class BulkChangelogPageTexts(val histories: List<RawChangelogInput>, val nextPageToken: String?)

/** One `GET /issue/{id}/changelog` page: its histories as rows, and the paging fields the stream decides on. */
internal class ChangelogPageTexts(val histories: List<RawChangelogInput>, val total: Int, val isLast: Boolean?)

/** One `GET /issue/{id}/worklog` page: its worklogs as rows (payload trimmed), and the issue's `total`. */
internal class WorklogPageTexts(val worklogs: List<RawWorklogInput>, val total: Int)

/** One `worklog/list` detail held until its feed page's transaction: its issue and its trimmed text (parsed only if in scope). */
internal class FeedWorklog(val issueId: Long, val payloadJson: String)

/** A `worklog/list` answer as its [FeedWorklog]s (the convert step of `worklogListAs`: its array is gone once this returns). */
internal fun feedWorklogsOf(elements: JsonArray): List<FeedWorklog> = elements.map { element ->
    val worklog = element.jsonObject
    FeedWorklog(worklog.requireLong("issueId", WORKLOG_LIST_ENDPOINT, "a worklog"), trimWorklog(worklog).toString())
}

// The `...As` calls hand a page to `convert` and return only its result. On the real client ([HttpJiraClient]) `convert` runs while
// the big-parse lock is still held (`SourceHttp.requestAs`), so a dense page's tree is garbage before the next big body is parsed and the
// streams that accumulate answers across pages (CHANGELOGS, WORKLOGS) never keep a page tree beside their text. Any other client (a
// test fake, or a wrapper that overrides the plain calls) is called through the plain method and `convert` runs after it, so its
// overrides are honoured; the result is the same.

internal suspend fun <T> JiraClient.changelogBulkAs(
    issueIds: List<String>,
    nextPageToken: String?,
    maxResults: Int,
    convert: (JsonObject) -> T,
): T = if (this is HttpJiraClient) {
    changelogBulkAs(issueIds, nextPageToken, maxResults, convert)
} else {
    convert(changelogBulk(issueIds, nextPageToken, maxResults))
}

internal suspend fun <T> JiraClient.issueChangelogPageAs(issueId: String, startAt: Int, convert: (JiraChangelogPage) -> T): T =
    if (this is HttpJiraClient) issueChangelogPageAs(issueId, startAt, convert) else convert(issueChangelogPage(issueId, startAt))

internal suspend fun <T> JiraClient.issueWorklogPageAs(issueId: String, startAt: Int, convert: (JiraWorklogStartAtPage) -> T): T =
    if (this is HttpJiraClient) issueWorklogPageAs(issueId, startAt, convert) else convert(issueWorklogPage(issueId, startAt))

internal suspend fun <T> JiraClient.worklogListAs(ids: List<Long>, convert: (JsonArray) -> T): T =
    if (this is HttpJiraClient) worklogListAs(ids, convert) else convert(worklogList(ids))
