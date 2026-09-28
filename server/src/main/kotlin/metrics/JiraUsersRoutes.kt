package ch.nokillswit.metrics

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/jira-users")
class JiraUsersRoute

/** The only sortable field — `displayName`; `accountId` ascending is the deterministic tiebreaker. */
private val JIRA_USER_SORT_FIELDS = setOf("displayName")

/**
 * `GET /api/v1/jira-users` (v0.3.0 M1 commit 3, D12/the main-session scope amendment). Two scopes:
 *
 * - **`scope=unit` (default), any authenticated** — only UNIT-RELEVANT accounts: those that appear
 *   as an assignee or a worklog author on some ACTIVE connection's live work items
 *   ([WorkItemStore.distinctAssigneeAccountIds]/[distinctWorklogAuthorAccountIds]), or hold ANY
 *   `metrics.team_membership` row, ever ([TeamMembershipService.everMemberedAccountIds]) — the
 *   people every report/data-quality view can already name, not the whole Jira instance's
 *   directory (D12 exposes only what other any-authenticated surfaces already do).
 * - **`scope=site`, ADMIN only** (`requireAdmin` runs before ANY read — a non-admin gets a uniform
 *   403 whether or not the query would otherwise succeed) — the WHOLE site directory, for picking
 *   a brand-new team member nobody has touched a work item as yet.
 *
 * `q` substrings the display name (`containsNormalized`); `teamId` further narrows to that team's
 * CURRENT membership ([TeamMembershipService.currentAccountIds]) — combined with the scope
 * restriction by SET INTERSECTION, not replacing it. Paging/sorting/the `count(DISTINCT)` total all
 * happen in SQL, one transaction ([WorkItemStore.listPeople], list-endpoints.md).
 */
fun Application.configureJiraUsersRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val workItems = attributes[WorkItemStoreKey]
    val memberships = attributes[TeamMembershipServiceKey]

    routing {
        authenticate {
            get<JiraUsersRoute> {
                val caller = call.caller()
                val params = call.request.queryParameters
                val scope = params.optionalEnum<JiraUserScope>("scope") ?: JiraUserScope.UNIT
                // Guard before ANY read — a non-admin's scope=site request never touches the DB.
                if (scope == JiraUserScope.SITE) requireAdmin(caller)

                val paging = call.parsePaging(sortable = JIRA_USER_SORT_FIELDS)
                val q = params.optionalString("q")
                val teamId = params.optionalUInt("teamId")

                val scopeIds = if (scope == JiraUserScope.SITE) null else unitRelevantAccountIds(workItems, memberships)
                val teamIds = teamId?.let { memberships.currentAccountIds(it, nowMillis()) }
                val effectiveIds = intersectNullable(scopeIds, teamIds)

                val result = workItems.listPeople(paging, q, effectiveIds)
                val page = result.items.map { JiraUserResponse(it.accountId, it.displayName) }
                call.respond(HttpStatusCode.OK, paging.toPage(page, result.total))
            }
        }
    }
}

/** Assignees ∪ worklog authors (both ACTIVE-connection-scoped) ∪ everyone ever held a team membership row. */
private suspend fun unitRelevantAccountIds(workItems: WorkItemStore, memberships: TeamMembershipService): Set<String> =
    workItems.distinctAssigneeAccountIds() + workItems.distinctWorklogAuthorAccountIds() + memberships.everMemberedAccountIds()

/** `null` means "no restriction" — intersecting two restrictions narrows; either side absent leaves the other's restriction alone. */
private fun intersectNullable(a: Set<String>?, b: Set<String>?): Set<String>? = when {
    a == null -> b
    b == null -> a
    else -> a intersect b
}
