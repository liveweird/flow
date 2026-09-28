package ch.nokillswit.jira

import ch.nokillswit.infra.outbound.ATLASSIAN_GATEWAY_HOST
import ch.nokillswit.ingest.JiraAuthScheme
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Jira Cloud REST surface the ingestion pipeline needs (v0.2.0 plan §6). Every call goes
 * through the gateway `https://api.atlassian.com/ex/jira/{cloudId}` (or, in development, the
 * single stub host substituted for both the tenant_info host and the gateway — `jira/Jira.kt`).
 * [resolveCloudId] must run before any other method; implementations may throw
 * `IllegalStateException` otherwise ([HttpJiraClient] does).
 */
interface JiraClient {
    /** `GET https://<site>/_edge/tenant_info` — the ONE unauthenticated endpoint. */
    suspend fun resolveCloudId(): String
    suspend fun myself(): JsonObject
    suspend fun searchJql(jql: String, fields: String? = null, nextPageToken: String? = null, maxResults: Int = 100): JiraSearchPage
    suspend fun approximateCount(jql: String): Long
    suspend fun issue(idOrKey: String, fields: String? = null): JsonObject
    suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String? = null, maxResults: Int = 50): JsonObject
    suspend fun issueChangelogPage(issueId: String, startAt: Int = 0): JiraChangelogPage
    /** A1: per-issue worklog page — backfill and newly-in-scope issues. */
    suspend fun issueWorklogPage(issueId: String, startAt: Int = 0): JiraWorklogStartAtPage
    suspend fun worklogUpdated(sinceEpochMillis: Long): JiraWorklogIdsPage
    suspend fun worklogDeleted(sinceEpochMillis: Long): JiraWorklogIdsPage
    suspend fun worklogList(ids: List<Long>): JsonArray
    suspend fun fields(): JsonArray
    suspend fun statusesSearch(startAt: Int = 0): JiraStartAtPage
    suspend fun statusCategories(): JsonArray
    suspend fun projectsSearch(startAt: Int = 0): JiraStartAtPage
    suspend fun projectStatuses(projectKey: String): JsonArray
    suspend fun issueTypes(): JsonArray
    suspend fun priorities(startAt: Int = 0): JiraStartAtPage
    suspend fun resolutions(startAt: Int = 0): JiraStartAtPage
    suspend fun issueLinkTypes(): JsonObject
    suspend fun usersSearch(startAt: Int = 0): JsonArray
    suspend fun boards(startAt: Int = 0): JiraStartAtPage
    suspend fun boardConfiguration(boardId: Long): JsonObject
    suspend fun boardSprints(boardId: Long, startAt: Int = 0): JiraStartAtPage
}

/** [MED-2] A genuine Atlassian `cloudId` — a UUID, matched case-insensitively then stored lowercase. */
private val CLOUD_ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/** [MED-2] A numeric Jira issue id, as `search`'s `id` field (never the display `key`) always is. */
private val ISSUE_ID_PATTERN = Regex("^[0-9]{1,20}$")

/** [MED-2] The Jira Cloud issue-KEY shape (`PROJ-123`) — [JiraClient.issue] accepts either an id or a key. */
private val ISSUE_KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,9}-[0-9]{1,10}$")

/**
 * [tenantInfoBaseUrl] is the connection's own site (or, in development, the stub host).
 * [stubBaseUrl] non-null means development mode: the gateway HOST becomes the same stub instance
 * — but the gateway PATH SHAPE (`/ex/jira/{cloudId}/...`) is unchanged either way
 * (`sample-data/README.md` "Gateway shape": "there's no separate fake `*.atlassian.net` origin to
 * resolve", only the host is substituted). In production [stubBaseUrl] is null and the gateway
 * host is `https://api.atlassian.com`. Either way the `cloudId` path segment comes from
 * [resolveCloudId], which must run before any other call.
 */
class HttpJiraClient(
    private val http: JiraHttp,
    private val tenantInfoBaseUrl: String,
    private val stubBaseUrl: String?,
    email: String,
    apiToken: String,
    authScheme: JiraAuthScheme,
) : JiraClient {
    private val auth = jiraAuthHeader(email, apiToken, authScheme)
    private var gatewayBase: String = ""

    /**
     * [MED-2] `cloudId` comes straight from an UNAUTHENTICATED response and is then (a) persisted
     * (`DataSourceService.persistCloudId`), (b) echoed back in every `DataSourceResponse`, and (c)
     * spliced into the trusted gateway URL every subsequent call uses — an unvalidated value could
     * inject an extra path segment (or, with a `..`-style value, redirect the gateway path
     * entirely) into that trusted URL. Validated against the UUID shape Atlassian's `tenant_info`
     * always returns before it is used for ANY of the three; never persisted if invalid.
     */
    override suspend fun resolveCloudId(): String {
        val endpoint = "/_edge/tenant_info"
        val json = http.request(HttpMethod.Get, "$tenantInfoBaseUrl$endpoint", authHeader = null)
        val rawCloudId = decode<JsonObject>(json, endpoint).primitiveOrNull("cloudId", endpoint)?.contentOrNull
            ?: throw JiraFetchException("INVALID_RESPONSE", null, endpoint)
        if (!CLOUD_ID_PATTERN.matches(rawCloudId)) throw JiraFetchException("INVALID_RESPONSE", null, endpoint)
        val cloudId = rawCloudId.lowercase()
        val gatewayHost = stubBaseUrl ?: "https://$ATLASSIAN_GATEWAY_HOST"
        gatewayBase = "$gatewayHost/ex/jira/$cloudId"
        return cloudId
    }

    override suspend fun myself(): JsonObject {
        val endpoint = "/rest/api/3/myself"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        val query = buildMap {
            put("jql", jql)
            put("maxResults", maxResults.toString())
            fields?.let { put("fields", it) }
            nextPageToken?.let { put("nextPageToken", it) }
        }
        val endpoint = "/rest/api/3/search/jql"
        // CURSOR_EXPIRED (`.claude/docs/jira-integration.md`): only meaningful once a page carries
        // a `nextPageToken` — the FIRST page's own 400/410 (a malformed/rejected JQL, say) is a
        // genuine INVALID_RESPONSE, not an expired token that never existed.
        val overrides = if (nextPageToken != null) mapOf(400 to "CURSOR_EXPIRED", 410 to "CURSOR_EXPIRED") else emptyMap()
        return decode(http.request(HttpMethod.Get, "${requireGateway()}$endpoint", query, null, auth, overrides), endpoint)
    }

    override suspend fun approximateCount(jql: String): Long {
        val endpoint = "/rest/api/3/search/approximate-count"
        val body = buildJsonObject { put("jql", jql) }
        val json = post(endpoint, body)
        return decode<JsonObject>(json, endpoint).primitiveOrNull("count", endpoint)?.longOrNull
            ?: throw JiraFetchException("INVALID_RESPONSE", null, endpoint)
    }

    override suspend fun issue(idOrKey: String, fields: String?): JsonObject {
        val endpoint = "/rest/api/3/issue/{id}"
        val id = requireIssueIdOrKey(idOrKey, endpoint)
        val query = fields?.let { mapOf("fields" to it) } ?: emptyMap()
        return decode(get("/rest/api/3/issue/$id", query), endpoint)
    }

    override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
        val endpoint = "/rest/api/3/changelog/bulkfetch"
        val body = buildJsonObject {
            putJsonArray("issueIdsOrKeys") { issueIds.forEach { add(JsonPrimitive(requireIssueIdOrKey(it, endpoint))) } }
            put("maxResults", maxResults)
            nextPageToken?.let { put("nextPageToken", it) }
        }
        return decode(post(endpoint, body), endpoint)
    }

    override suspend fun issueChangelogPage(issueId: String, startAt: Int): JiraChangelogPage {
        val endpoint = "/rest/api/3/issue/{id}/changelog"
        val id = requireIssueIdOrKey(issueId, endpoint)
        return decode(get("/rest/api/3/issue/$id/changelog", mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun issueWorklogPage(issueId: String, startAt: Int): JiraWorklogStartAtPage {
        val endpoint = "/rest/api/3/issue/{id}/worklog"
        val id = requireIssueIdOrKey(issueId, endpoint)
        return decode(get("/rest/api/3/issue/$id/worklog", mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun worklogUpdated(sinceEpochMillis: Long): JiraWorklogIdsPage {
        val endpoint = "/rest/api/3/worklog/updated"
        return decode(get(endpoint, mapOf("since" to sinceEpochMillis.toString())), endpoint)
    }

    override suspend fun worklogDeleted(sinceEpochMillis: Long): JiraWorklogIdsPage {
        val endpoint = "/rest/api/3/worklog/deleted"
        return decode(get(endpoint, mapOf("since" to sinceEpochMillis.toString())), endpoint)
    }

    override suspend fun worklogList(ids: List<Long>): JsonArray {
        val endpoint = "/rest/api/3/worklog/list"
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } }
        return decode(post(endpoint, body), endpoint)
    }

    override suspend fun fields(): JsonArray {
        val endpoint = "/rest/api/3/field"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun statusesSearch(startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/api/3/statuses/search"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun statusCategories(): JsonArray {
        val endpoint = "/rest/api/3/statuscategory"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun projectsSearch(startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/api/3/project/search"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun projectStatuses(projectKey: String): JsonArray {
        val endpoint = "/rest/api/3/project/$projectKey/statuses"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun issueTypes(): JsonArray {
        val endpoint = "/rest/api/3/issuetype"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun priorities(startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/api/3/priority/search"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun resolutions(startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/api/3/resolution/search"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun issueLinkTypes(): JsonObject {
        val endpoint = "/rest/api/3/issueLinkType"
        return decode(get(endpoint), endpoint)
    }

    override suspend fun usersSearch(startAt: Int): JsonArray {
        val endpoint = "/rest/api/3/users/search"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun boards(startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/agile/1.0/board"
        return decode(get(endpoint, mapOf("startAt" to startAt.toString())), endpoint)
    }

    override suspend fun boardConfiguration(boardId: Long): JsonObject {
        val endpoint = "/rest/agile/1.0/board/{id}/configuration"
        return decode(get("/rest/agile/1.0/board/$boardId/configuration"), endpoint)
    }

    override suspend fun boardSprints(boardId: Long, startAt: Int): JiraStartAtPage {
        val endpoint = "/rest/agile/1.0/board/{id}/sprint"
        return decode(get("/rest/agile/1.0/board/$boardId/sprint", mapOf("startAt" to startAt.toString())), endpoint)
    }

    private fun requireGateway(): String =
        gatewayBase.ifBlank { throw IllegalStateException("resolveCloudId() must run before any gateway call") }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        http.request(HttpMethod.Get, "${requireGateway()}$path", query, null, auth)

    private suspend fun post(path: String, body: JsonElement, query: Map<String, String> = emptyMap()): JsonElement =
        http.request(HttpMethod.Post, "${requireGateway()}$path", query, body, auth)

    /**
     * [MED-2] Any Jira-supplied identifier spliced into a gateway PATH — the search probe's raw
     * `id` field is the one live source today; the ISSUES stream landing in a later commit will
     * add more. A malicious/compromised response must never inject an extra path segment into the
     * trusted gateway URL this way.
     */
    private fun requireIssueIdOrKey(idOrKey: String, endpoint: String): String {
        if (!ISSUE_ID_PATTERN.matches(idOrKey) && !ISSUE_KEY_PATTERN.matches(idOrKey)) {
            throw JiraFetchException("INVALID_RESPONSE", null, endpoint)
        }
        return idOrKey
    }
}

// The real Jira response carries many fields our envelopes don't declare (e.g. `warningMessages`,
// `expand`) — ignore them rather than 500 on a field this client never asked for.
private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/**
 * [MED-4] The ONE decode path for every Jira response shape, structured OR raw (`JsonObject`/
 * `JsonArray`). `kotlinx.serialization`'s `JsonObject`/`JsonArray` serializers throw
 * `SerializationException` (a `RuntimeException`) — not the `IllegalArgumentException` a raw
 * `.jsonObject`/`.jsonArray` postfix cast throws — on a shape mismatch (an HTML error page, a bare
 * array where an object was expected, …) either way; routing every cast through here means EVERY
 * such mismatch becomes `INVALID_RESPONSE` instead of an uncaught exception that 500s Test
 * connection (`JiraConnectorTest`, `DataSourceTestConnectionTest`).
 */
private inline fun <reified T> decode(json: JsonElement, endpoint: String? = null): T = try {
    LENIENT_JSON.decodeFromJsonElement(json)
} catch (cause: Exception) {
    throw JiraFetchException("INVALID_RESPONSE", null, endpoint).also { it.initCause(cause) }
}

/** [MED-4] `JsonObject["key"]?.jsonPrimitive` also raw-casts on a non-primitive value — same mapping. */
private fun JsonObject.primitiveOrNull(key: String, endpoint: String): JsonPrimitive? = try {
    this[key]?.jsonPrimitive
} catch (cause: IllegalArgumentException) {
    throw JiraFetchException("INVALID_RESPONSE", null, endpoint).also { it.initCause(cause) }
}
