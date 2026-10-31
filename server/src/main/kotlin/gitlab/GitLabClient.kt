package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.SourceHttp
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder

/**
 * The GitLab REST surface the PROJECTS and USERS streams and the Test-connection probes need (`gitlab-integration.md` "Streams").
 * Every call is `<baseUrl>/api/v4<path>` with the personal access token in `PRIVATE-TOKEN`; the next page is ALWAYS computed here from
 * `X-Next-Page` and re-applied to the configured origin - the `Link` header is never read, so a hostile server cannot steer a call to
 * another host.
 */
interface GitLabClient {
    /** The most calls this client lets run at once (the shared permit pool's size); a stream sizes any look-ahead by it. */
    val maxConcurrentRequests: Int

    /** `GET /version`: the instance's version string. */
    suspend fun version(): String

    /** `GET /user`: the token's owner. */
    suspend fun currentUser(): GitLabCurrentUser

    /** `GET /personal_access_tokens/self`: the token's scopes and state. */
    suspend fun tokenSelf(): GitLabTokenInfo

    /** `GET /groups/:path`: the group at a configured full [path] (percent-encoded `/`); a missing or invisible group is `NOT_FOUND`. */
    suspend fun group(path: String): GitLabGroup

    /** `GET /groups/:id/projects` (subgroups included, archived and shared excluded), page [page] (1-based). */
    suspend fun groupProjects(groupId: Long, page: Int): GitLabPage

    /**
     * `GET /users`, page [page] (1-based). Each user is slimmed to small values INSIDE the response transform ([GitLabUserSummary]),
     * so the page's parsed tree is garbage before the caller makes its next call (one tree per slot, `JiraHeapFit`).
     */
    suspend fun users(page: Int): GitLabUserPage

    /**
     * `GET /users/:id/emails`: the user's confirmed secondary email addresses, at most [MAX_EMAILS_PER_USER] (admin tokens only; a 403
     * is `FORBIDDEN_SCOPE`). The strings are extracted inside the transform, so no tree outlives the call.
     */
    suspend fun userEmails(userId: Long): List<String>

    /**
     * `GET /projects/:id/repository/commits` with `with_stats=true`, page [page] (1-based): the commits reachable from [refName] -
     * a branch, a SHA or a range `a..b` (sent as a query VALUE, so the dots and the branch name are encoded, never spliced into the
     * path) - newest first, limited to those committed after [since] (an ISO instant, [isoInstant]) when given. Each commit is reduced to
     * a [GitLabCommitSummary] inside the transform: no message body, committer or diff is kept.
     */
    suspend fun commits(projectId: Long, refName: String, since: String?, page: Int): GitLabCommitPage

    /**
     * `GET /projects/:id/repository/merge_base`: the best common ancestor of [refA] and [refB], as its SHA. A ref GitLab does not know
     * (it answers 400 or 404) is [REF_NOT_FOUND].
     */
    suspend fun mergeBase(projectId: Long, refA: String, refB: String): String

    /**
     * `GET /projects/:id/repository/commits/:sha/diff`, page [page] (1-based): the files of one commit, each reduced inside the transform
     * to its path, flags and counted lines ([summarizeDiffFile]); the diff text is counted once and dropped. [sha] must be a full
     * commit id ([SHA_PATTERN]) or the call is refused before any request.
     */
    suspend fun commitDiff(projectId: Long, sha: String, page: Int): GitLabDiffPage
}

/** One user of `/users` reduced to small values: the identity columns, the user's own emails and the slim payload (no `emails` yet). */
class GitLabUserSummary(
    val id: Long,
    val username: String,
    val name: String,
    val state: String,
    val bot: Boolean,
    val ownEmails: List<String>,
    val slim: JsonObject,
)

/** One page of `/users` as [GitLabUserSummary] values, with the same paging fields as [GitLabPage]. */
class GitLabUserPage(val users: List<GitLabUserSummary>, val nextPage: Int?, val total: Long?)

/** Builds a client for one connection's base URL and token (the `JiraClientFactory` shape). */
typealias GitLabClientFactory = (baseUrl: String, token: String) -> GitLabClient

/** `per_page` of every list call: the maximum GitLab allows (and the only size the stub maps). */
const val GITLAB_PAGE_SIZE = 100

/** A numeric GitLab id as every `id` field is; anything else must never be spliced into a path. */
private val NUMERIC_ID = Regex("^[0-9]{1,18}$")

/** The shape of a full group path: segments of letters, digits, `_`, `.` and `-` joined by `/`. */
private val GROUP_PATH = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*$")

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404

private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

class HttpGitLabClient(
    private val http: SourceHttp,
    baseUrl: String,
    token: String,
) : GitLabClient {
    private val base = "${baseUrl.trimEnd('/')}/api/v4"
    private val auth = gitLabAuthHeader(token)

    /** Rate limits are per instance (per token owner, in practice): the canonical base URL is the tenant key. */
    private val pauseKey = baseUrl

    override val maxConcurrentRequests: Int get() = http.maxConcurrentRequests

    override suspend fun version(): String = getTree("/version") { decode<GitLabVersion>(it, "/version").version }

    override suspend fun currentUser(): GitLabCurrentUser = getTree("/user") { decode(it, "/user") }

    override suspend fun tokenSelf(): GitLabTokenInfo = getTree("/personal_access_tokens/self") { decode(it,
        "/personal_access_tokens/self") }

    override suspend fun group(path: String): GitLabGroup {
        if (!GROUP_PATH.matches(path)) throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, "/groups/{path}")
        // `/` inside the one path segment must travel as %2F; the encoder leaves letters, digits, `.`, `-` and `_` alone.
        val encoded = URLEncoder.encode(path, Charsets.UTF_8)
        return getTree("/groups/$encoded") { decode(it, "/groups/{path}") }
    }

    override suspend fun groupProjects(groupId: Long, page: Int): GitLabPage {
        val endpoint = "/groups/${requireNumericId(groupId, "/groups/{id}/projects")}/projects"
        val paged = getPaged(endpoint, page, mapOf("include_subgroups" to "true", "archived" to "false", "with_shared" to "false")) { it }
        return GitLabPage(paged.value, paged.nextPage, paged.total)
    }

    override suspend fun users(page: Int): GitLabUserPage {
        val paged = getPaged("/users", page, emptyMap()) { items -> items.map { summarizeUser(it) } }
        return GitLabUserPage(paged.value, paged.nextPage, paged.total)
    }

    override suspend fun userEmails(userId: Long): List<String> {
        val endpoint = "/users/${requireNumericId(userId, "/users/{id}/emails")}/emails"
        return getTree(endpoint, mapOf("per_page" to GITLAB_PAGE_SIZE.toString())) {
            secondaryEmails(decode<JsonArray>(it, "/users/{id}/emails")).take(MAX_EMAILS_PER_USER)
        }
    }

    override suspend fun commits(projectId: Long, refName: String, since: String?, page: Int): GitLabCommitPage {
        val endpoint = "/projects/${requireNumericId(projectId, "/projects/{id}/repository/commits")}/repository/commits"
        val extra = buildMap {
            put("ref_name", refName)
            put("with_stats", "true")
            since?.let { put("since", it) }
        }
        val paged = getPaged(endpoint, page, extra) { items -> items.map(::summarizeCommit) }
        return GitLabCommitPage(paged.value, paged.nextPage, paged.total)
    }

    override suspend fun mergeBase(projectId: Long, refA: String, refB: String): String {
        val endpoint = "/projects/${requireNumericId(projectId, "/projects/{id}/repository/merge_base")}/repository/merge_base"
        return getTree(
            endpoint,
            repeatedQuery = listOf("refs[]" to refA, "refs[]" to refB),
            statusCodeOverrides = mapOf(HTTP_BAD_REQUEST to REF_NOT_FOUND, HTTP_NOT_FOUND to REF_NOT_FOUND),
        ) { tree ->
            (tree as? JsonObject)?.text("id")?.takeIf { SHA_PATTERN.matches(it) }
                ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, "/projects/{id}/repository/merge_base")
        }
    }

    override suspend fun commitDiff(projectId: Long, sha: String, page: Int): GitLabDiffPage {
        val template = "/projects/{id}/repository/commits/{sha}/diff"
        if (!SHA_PATTERN.matches(sha)) throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, template)
        val endpoint = "/projects/${requireNumericId(projectId, template)}/repository/commits/$sha/diff"
        val paged = getPaged(endpoint, page, emptyMap()) { items -> items.map(::summarizeDiffFile) }
        return GitLabDiffPage(paged.value, paged.nextPage)
    }

    private fun summarizeUser(item: JsonElement): GitLabUserSummary {
        val user = item as? JsonObject ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, "/users")
        val id = (user["id"] as? JsonPrimitive)?.longOrNull ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, "/users")
        return GitLabUserSummary(
            id = id,
            username = user.text("username").orEmpty(),
            name = user.text("name").orEmpty(),
            state = user.text("state").orEmpty(),
            bot = (user["bot"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ownEmails = ownEmailsOf(user),
            slim = slimUser(user, emptyList()),
        )
    }

    private suspend fun <T> getTree(
        path: String,
        query: Map<String, String> = emptyMap(),
        repeatedQuery: List<Pair<String, String>> = emptyList(),
        statusCodeOverrides: Map<Int, String> = emptyMap(),
        transform: (JsonElement) -> T,
    ): T = http.requestAs(
        HttpMethod.Get, "$base$path", query, null, auth, statusCodeOverrides, repeatedQuery, pauseKey = pauseKey, transform = transform,
    )

    private class Paged<T>(val value: T, val nextPage: Int?, val total: Long?)

    /**
     * One list page: [convert] runs on the parsed array INSIDE the response transform (so a caller can keep small values only),
     * and `X-Next-Page` / `X-Total` are copied there too (the `Headers` object must not escape). GitLab ALWAYS sends `X-Next-Page`, empty
     * on the last page: an ABSENT header is `INVALID_RESPONSE`, never "last page" (a proxy stripping it must not end a pass after one page
     * and have the sweep mark every unseen row moved out or deleted).
     */
    private suspend fun <T> getPaged(path: String, page: Int, extra: Map<String, String>, convert: (JsonArray) -> T): Paged<T> {
        val query = extra + mapOf("per_page" to GITLAB_PAGE_SIZE.toString(), "page" to page.toString())
        return http.requestAsWithHeaders(HttpMethod.Get, "$base$path", query, null, auth, pauseKey = pauseKey) { json, headers ->
            val rawNext = headers["X-Next-Page"]?.trim() ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, path,
                "no X-Next-Page header")
            val next = rawNext.takeIf { it.isNotEmpty() }?.let { raw ->
                raw.toIntOrNull()?.takeIf { it > page }
                    ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, path, "a page that does not advance")
            }
            Paged(convert(decode<JsonArray>(json, path)), next, headers["X-Total"]?.trim()?.toLongOrNull())
        }
    }

    /** An id spliced into a path (a response-supplied number, never text): a malicious body must not inject a path segment. */
    private fun requireNumericId(id: Long, endpoint: String): String {
        val text = id.toString()
        if (!NUMERIC_ID.matches(text)) throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, endpoint)
        return text
    }
}

@kotlinx.serialization.Serializable
private data class GitLabVersion(val version: String)

/** The ONE decode path for every GitLab response shape: a mismatch becomes `INVALID_RESPONSE` instead of an uncaught exception. */
private inline fun <reified T> decode(json: JsonElement, endpoint: String): T = try {
    LENIENT_JSON.decodeFromJsonElement(json)
} catch (cause: Exception) {
    throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, endpoint).also { it.initCause(cause) }
}
