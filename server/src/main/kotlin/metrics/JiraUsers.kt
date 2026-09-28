package ch.nokillswit.metrics

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/jira-users`' `scope` query param: `UNIT` (default, any authenticated) restricts to
 * accounts already relevant to this unit's own data (an assignee, a worklog author, or ever held a
 * team membership row); `SITE` (ADMIN only) is the whole site directory, for picking a brand-new
 * team member.
 */
enum class JiraUserScope { UNIT, SITE }

/** One Jira account, DISTINCT across connections (D12: everyone sees these names — any authenticated read, UNIT scope). */
@Serializable
data class JiraUserResponse(val accountId: String, val displayName: String)

typealias JiraUserPageResponse = PageResponse<JiraUserResponse>
