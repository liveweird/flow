package ch.nokillswit.jira

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * Paging/envelope shapes the Jira Cloud REST API returns (v0.2.0 plan §6, spike fact sheet).
 * Deliberately thin: the payload arrays stay `JsonArray` (parsed further by the normalization
 * layer arriving in a later commit) — only the paging metadata needed to drive the client's own
 * pagination is typed here.
 */

/** `GET/POST /rest/api/3/search/jql` — opaque `nextPageToken` paging (not `startAt`). */
@Serializable
data class JiraSearchPage(
    val issues: JsonArray = JsonArray(emptyList()),
    val nextPageToken: String? = null,
)

/** The common `startAt`/`maxResults`/`total` envelope shared by most `startAt`-paged endpoints. */
@Serializable
data class JiraStartAtPage(
    val startAt: Int = 0,
    val maxResults: Int = 0,
    val total: Int = 0,
    val isLast: Boolean? = null,
    val values: JsonArray = JsonArray(emptyList()),
)

/** `GET /rest/api/3/issue/{id}/worklog` — same envelope shape, but the array key is `worklogs`. */
@Serializable
data class JiraWorklogStartAtPage(
    val startAt: Int = 0,
    val maxResults: Int = 0,
    val total: Int = 0,
    val worklogs: JsonArray = JsonArray(emptyList()),
)

/**
 * `GET /rest/api/3/issue/{id}/changelog` — its own envelope shape (the array key is `histories`,
 * NOT `values` — unlike every OTHER `startAt`-paged endpoint this client calls; the CHANGELOGS
 * stream's per-issue fallback, `jira/JiraChangelogStream.kt`, is the one consumer, and
 * `sample-data/jira-stub`'s generator emits exactly this shape).
 */
@Serializable
data class JiraChangelogPage(
    val startAt: Int = 0,
    val maxResults: Int = 0,
    val total: Int = 0,
    val isLast: Boolean? = null,
    val histories: JsonArray = JsonArray(emptyList()),
)

/** One id/timestamp pair from `worklog/updated` or `worklog/deleted`. */
@Serializable
data class JiraWorklogIdEntry(val worklogId: Long, val updatedTime: Long)

/** `GET /rest/api/3/worklog/{updated,deleted}` — its own cursor shape (`since`/`until`/`nextPage`/`lastPage`). */
@Serializable
data class JiraWorklogIdsPage(
    val values: List<JiraWorklogIdEntry> = emptyList(),
    val since: Long = 0,
    val until: Long = 0,
    val nextPage: String? = null,
    val lastPage: Boolean = true,
)
