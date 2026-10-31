package ch.nokillswit.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

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
 * `read_repository`, ...). Anything else - `api`, `sudo`, `write_*`, `create_runner`, `manage_runner`, `k8s_proxy`,
 * `self_rotate`, `ai_features`,
 * or a scope GitLab adds later - is refused at Test connection and again at every SYNC (`TOKEN_SCOPE_TOO_BROAD`): Flow needs
 * `read_api` only.
 */
fun List<String>.tooBroad(): Boolean = any { !it.startsWith("read_") }
