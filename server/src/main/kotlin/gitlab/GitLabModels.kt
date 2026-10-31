package ch.nokillswit.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

// The slices of GitLab's REST answers the connector reads; everything else in a body is ignored (the client decodes leniently).

/** One page of a list endpoint: the items, the page after this one (`X-Next-Page`, null at the end) and the list's `X-Total` if sent. */
class GitLabPage(val items: JsonArray, val nextPage: Int?, val total: Long?)

/**
 * `GET /personal_access_tokens/self`: the scopes the token carries and whether it is still usable. No defaults on purpose: a 200 body
 * missing either field (`{}`) must decode to `INVALID_RESPONSE`, never pass the token check as "no scopes, active".
 */
@Serializable
data class GitLabTokenInfo(val scopes: List<String>, val active: Boolean)

/** `GET /user`: the token's owner; `is_admin` decides whether user emails are visible. */
@Serializable
data class GitLabCurrentUser(val id: Long, @SerialName("is_admin") val isAdmin: Boolean = false)

/** `GET /groups/:id`: the numeric id the group's other endpoints take, and its full path (verified against the configured one). */
@Serializable
data class GitLabGroup(val id: Long, @SerialName("full_path") val fullPath: String)

/**
 * Whether these token scopes go beyond reading: an ALLOW-list, every scope must start with `read_` (`read_api`, `read_user`,
 * `read_repository`, ...) or be `admin_mode`. Anything else - `api`, `sudo`, `write_*`, `create_runner`, `manage_runner`,
 * `k8s_proxy`, `self_rotate`, `ai_features`, or a scope GitLab adds later - is refused at Test connection and again at every
 * SYNC (`TOKEN_SCOPE_TOO_BROAD`): Flow needs `read_api` only.
 *
 * `admin_mode` is allowed because on an instance with Admin Mode on, an admin token needs it to act as an admin through the API
 * at all (without it `/user` hides `is_admin` and `/users/:id/emails` answers 403, so the identity proposals lose the emails).
 * It only lifts what the other scopes allow, so beside `read_*` scopes the token stays read-only.
 */
fun List<String>.tooBroad(): Boolean = any { !it.startsWith("read_") && it != ADMIN_MODE_SCOPE }

private const val ADMIN_MODE_SCOPE = "admin_mode"

/**
 * `GET /projects/:id` reduced to what RECONCILE's project probe decides on. It is never stored: the body of an archived or moved
 * project differs from its listing, so feeding it to the raw store would resurrect the row (`changeProject` clears both markers).
 */
class GitLabProjectDetail(
    val id: Long,
    val pathWithNamespace: String,
    val namespaceFullPath: String?,
    val archived: Boolean,
    val defaultBranch: String?,
)

/** One project as the PROJECTS stream hands it over: the group it was LISTED under and GitLab's document (slimmed on write). */
class RawGitLabProject(
    val projectId: Long,
    val pathWithNamespace: String,
    /** The configured group path (lowercase) the project was listed under, and that group's numeric id. */
    val groupPath: String,
    val groupId: Long,
    val defaultBranch: String?,
    val archived: Boolean,
    val payload: JsonElement,
)

/** One user as the USERS stream hands it over; [emails] is the sorted, distinct, lowercase union (`gitLabUserEmails`). */
class RawGitLabUser(
    val userId: Long,
    val username: String,
    val name: String,
    val state: String,
    val bot: Boolean,
    val emails: List<String>,
    val payload: JsonElement,
)
