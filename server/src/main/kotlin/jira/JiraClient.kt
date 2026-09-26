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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    suspend fun issue(idOrKey: String): JsonObject
    suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String? = null, maxResults: Int = 50): JsonObject
    suspend fun issueChangelogPage(issueId: String, startAt: Int = 0): JiraStartAtPage
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

    override suspend fun resolveCloudId(): String {
        val json = http.request(HttpMethod.Get, "$tenantInfoBaseUrl/_edge/tenant_info", authHeader = null)
        val cloudId = json.jsonObject["cloudId"]?.jsonPrimitive?.contentOrNull
            ?: throw JiraFetchException("INVALID_RESPONSE", null, "/_edge/tenant_info")
        val gatewayHost = stubBaseUrl ?: "https://$ATLASSIAN_GATEWAY_HOST"
        gatewayBase = "$gatewayHost/ex/jira/$cloudId"
        return cloudId
    }

    override suspend fun myself(): JsonObject = get("/rest/api/3/myself").jsonObject

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        val query = buildMap {
            put("jql", jql)
            put("maxResults", maxResults.toString())
            fields?.let { put("fields", it) }
            nextPageToken?.let { put("nextPageToken", it) }
        }
        return decode(get("/rest/api/3/search/jql", query))
    }

    override suspend fun approximateCount(jql: String): Long {
        val body = buildJsonObject { put("jql", jql) }
        val json = post("/rest/api/3/search/approximate-count", body)
        return json.jsonObject["count"]?.jsonPrimitive?.longOrNull
            ?: throw JiraFetchException("INVALID_RESPONSE", null, "/rest/api/3/search/approximate-count")
    }

    override suspend fun issue(idOrKey: String): JsonObject = get("/rest/api/3/issue/$idOrKey").jsonObject

    override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
        val body = buildJsonObject {
            putJsonArray("issueIdsOrKeys") { issueIds.forEach { add(JsonPrimitive(it)) } }
            put("maxResults", maxResults)
            nextPageToken?.let { put("nextPageToken", it) }
        }
        return post("/rest/api/3/changelog/bulkfetch", body).jsonObject
    }

    override suspend fun issueChangelogPage(issueId: String, startAt: Int): JiraStartAtPage =
        decode(get("/rest/api/3/issue/$issueId/changelog", mapOf("startAt" to startAt.toString())))

    override suspend fun issueWorklogPage(issueId: String, startAt: Int): JiraWorklogStartAtPage =
        decode(get("/rest/api/3/issue/$issueId/worklog", mapOf("startAt" to startAt.toString())))

    override suspend fun worklogUpdated(sinceEpochMillis: Long): JiraWorklogIdsPage =
        decode(get("/rest/api/3/worklog/updated", mapOf("since" to sinceEpochMillis.toString())))

    override suspend fun worklogDeleted(sinceEpochMillis: Long): JiraWorklogIdsPage =
        decode(get("/rest/api/3/worklog/deleted", mapOf("since" to sinceEpochMillis.toString())))

    override suspend fun worklogList(ids: List<Long>): JsonArray {
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } }
        return post("/rest/api/3/worklog/list", body).jsonArray
    }

    override suspend fun fields(): JsonArray = get("/rest/api/3/field").jsonArray
    override suspend fun statusesSearch(startAt: Int): JiraStartAtPage =
        decode(get("/rest/api/3/statuses/search", mapOf("startAt" to startAt.toString())))
    override suspend fun statusCategories(): JsonArray = get("/rest/api/3/statuscategory").jsonArray
    override suspend fun projectsSearch(startAt: Int): JiraStartAtPage =
        decode(get("/rest/api/3/project/search", mapOf("startAt" to startAt.toString())))
    override suspend fun projectStatuses(projectKey: String): JsonArray = get("/rest/api/3/project/$projectKey/statuses").jsonArray
    override suspend fun issueTypes(): JsonArray = get("/rest/api/3/issuetype").jsonArray
    override suspend fun priorities(startAt: Int): JiraStartAtPage =
        decode(get("/rest/api/3/priority/search", mapOf("startAt" to startAt.toString())))
    override suspend fun resolutions(startAt: Int): JiraStartAtPage =
        decode(get("/rest/api/3/resolution/search", mapOf("startAt" to startAt.toString())))
    override suspend fun issueLinkTypes(): JsonObject = get("/rest/api/3/issueLinkType").jsonObject
    override suspend fun usersSearch(startAt: Int): JsonArray =
        get("/rest/api/3/users/search", mapOf("startAt" to startAt.toString())).jsonArray
    override suspend fun boards(startAt: Int): JiraStartAtPage =
        decode(get("/rest/agile/1.0/board", mapOf("startAt" to startAt.toString())))
    override suspend fun boardConfiguration(boardId: Long): JsonObject = get("/rest/agile/1.0/board/$boardId/configuration").jsonObject
    override suspend fun boardSprints(boardId: Long, startAt: Int): JiraStartAtPage =
        decode(get("/rest/agile/1.0/board/$boardId/sprint", mapOf("startAt" to startAt.toString())))

    private fun requireGateway(): String =
        gatewayBase.ifBlank { throw IllegalStateException("resolveCloudId() must run before any gateway call") }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        http.request(HttpMethod.Get, "${requireGateway()}$path", query, null, auth)

    private suspend fun post(path: String, body: JsonElement, query: Map<String, String> = emptyMap()): JsonElement =
        http.request(HttpMethod.Post, "${requireGateway()}$path", query, body, auth)

    private inline fun <reified T> decode(json: JsonElement): T = try {
        LENIENT_JSON.decodeFromJsonElement(json)
    } catch (cause: Exception) {
        throw JiraFetchException("INVALID_RESPONSE").also { it.initCause(cause) }
    }

    private companion object {
        // The real Jira response carries many fields our envelopes don't declare (e.g.
        // `warningMessages`, `expand`) — ignore them rather than 500 on a field this client
        // never asked for.
        val LENIENT_JSON = Json { ignoreUnknownKeys = true }
    }
}
