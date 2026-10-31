package ch.nokillswit.identities

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.ingest.DataSourceKindUnsupportedException
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.norm.CodeActivityStoreKey
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/data-sources/{id}/code-identities")
class CodeIdentitiesRoute(val id: UInt) {
    @Serializable
    @Resource("{identityKey}")
    class Key(val parent: CodeIdentitiesRoute, val identityKey: String)

    @Serializable
    @Resource("confirm-proposals")
    class Confirm(val parent: CodeIdentitiesRoute)
}

/** The sortable fields of the list; `id` is the identity key (the tiebreaker `parsePaging` appends). */
private val CODE_IDENTITY_SORT_FIELDS = setOf("displayName", "username", "id")

/** The longest `q` the list accepts (a name or an address fragment). */
private const val MAX_QUERY_LENGTH = 100

/** `via` of a `code_identity.decided` audit line: the single PUT, or the bulk confirmation. */
private const val VIA_PUT = "PUT"
private const val VIA_CONFIRM = "CONFIRM_PROPOSALS"

/**
 * Wires the identity mapping (`.claude/docs/gitlab-integration.md` "Identity"). The resolver and the service are published in EVERY role
 * (DERIVE and the reports read links without serving the API); only a role that serves the API goes on to register the routes.
 * Registered right after the data-profile routes in `application.yaml`: after `configureDatabase` (the database handle), `configureJira`
 * (`WorkItemStoreKey`) and `configureGitLab` (`CodeActivityStoreKey`, published whether or not GitLab is on), before the catch-all.
 *
 * The four routes are ADMIN only, and `requireAdmin` is the FIRST statement of every handler: the data source, the key, the query and the
 * body are all read after it, so a non-admin gets one uniform `403` (`authorization.md`). Then 404 (data source) -> 409 (not a GitLab
 * source) -> 400 (key, query, body) -> 404 (identity / decision) -> 400 (unknown Jira account). The list carries the GitLab addresses, to
 * ADMIN only; every `/api/` answer is `no-store`. The server never places a stored address in a URL; `q` is search text an admin typed,
 * which may contain one (as flow-users' `q` already may). Audits carry ids, keys, the host and decisions, never an address or a name.
 */
fun Application.configureCodeIdentities() {
    val database = attributes[R2dbcDatabaseKey]
    val links = CodeIdentityLinkStore(database)
    attributes.put(IdentityResolverKey, IdentityResolver(links))
    val dataSources = attributes[DataSourceServiceKey]
    val service = CodeIdentityService(database, links, attributes[CodeActivityStoreKey], attributes[WorkItemStoreKey], dataSources)
    attributes.put(CodeIdentityServiceKey, service)
    if (!servesApi()) return

    routing {
        authenticate {
            codeIdentityList(dataSources, service)
            codeIdentityDecide(dataSources, service)
            codeIdentityRemove(dataSources, service)
            codeIdentityConfirm(dataSources, service)
        }
    }
}

private fun Route.codeIdentityList(dataSources: DataSourceService, service: CodeIdentityService) {
    get<CodeIdentitiesRoute> { route ->
        requireAdmin(call.caller())
        val host = dataSources.gitLabHost(route.id)
        val paging = call.parsePaging(CODE_IDENTITY_SORT_FIELDS, defaultSort = listOf(SortField("displayName", descending = false)))
        val params = call.request.queryParameters
        val filter = CodeIdentityFilter(
            status = params.optionalEnum<CodeIdentityStatus>("status"),
            q = params.optionalString("q")?.let { sanitizeSingleLine(it, "q") }?.also {
                if (it.length > MAX_QUERY_LENGTH) throw BadRequestException("q must be at most $MAX_QUERY_LENGTH characters")
            },
        )
        val result = service.list(route.id, host, filter, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

private fun Route.codeIdentityDecide(dataSources: DataSourceService, service: CodeIdentityService) {
    put<CodeIdentitiesRoute.Key> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        val connectionId = route.parent.id
        val host = dataSources.gitLabHost(connectionId)
        val key = validateIdentityKey(route.identityKey)
        val request = validateDecisionRequest(call.receive())
        val outcome = service.decide(connectionId, host, key, request, caller.userId.toLong(), nowMillis())
        // A re-PUT of what is stored writes nothing and audits nothing; a change names the decision it overwrote.
        if (outcome.changed) {
            auditDecided(caller, connectionId, host, key, request.decision, request.jiraAccountId, outcome.previous, VIA_PUT)
        }
        call.respond(HttpStatusCode.OK, outcome.item)
    }
}

private fun Route.codeIdentityRemove(dataSources: DataSourceService, service: CodeIdentityService) {
    delete<CodeIdentitiesRoute.Key> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        val connectionId = route.parent.id
        val host = dataSources.gitLabHost(connectionId)
        val key = validateIdentityKey(route.identityKey)
        // The removed row (DELETE ... RETURNING) is what the audit names; none = nothing to remove.
        val removed = service.remove(host, key) ?: throw NotFoundException("Decision not found")
        audit(
            "code_identity.decision_removed",
            "byUserId" to caller.userId.toLong(),
            "dataSourceId" to connectionId.toLong(),
            "host" to removed.host,
            "identityKey" to removed.key,
            "decision" to removed.decision.name,
            "jiraAccountId" to removed.jiraAccountId,
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.codeIdentityConfirm(dataSources: DataSourceService, service: CodeIdentityService) {
    post<CodeIdentitiesRoute.Confirm> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        val connectionId = route.parent.id
        val host = dataSources.gitLabHost(connectionId)
        val written = service.confirmProposals(connectionId, host, caller.userId.toLong(), nowMillis())
        // One event per link actually written (the attribution trail answers who linked what, when): a decision stored meanwhile is
        // not in `written`, and a second call writes nothing, so it audits nothing.
        written.forEach {
            auditDecided(caller, connectionId, it.host, it.key, it.decision, it.jiraAccountId, previous = null, via = VIA_CONFIRM)
        }
        val result = CodeIdentityConfirmResult(
            linked = written.count { it.decision == CodeIdentityDecision.LINKED },
            bots = written.count { it.decision == CodeIdentityDecision.BOT },
        )
        call.respond(HttpStatusCode.OK, result)
    }
}

private fun auditDecided(
    caller: CallerPrincipal,
    connectionId: UInt,
    host: String,
    key: String,
    decision: CodeIdentityDecision,
    jiraAccountId: String?,
    previous: IdentityLink?,
    via: String,
) {
    audit(
        "code_identity.decided",
        "byUserId" to caller.userId.toLong(),
        "dataSourceId" to connectionId.toLong(),
        "host" to host,
        "identityKey" to key,
        "decision" to decision.name,
        "jiraAccountId" to jiraAccountId,
        "decisionFrom" to previous?.decision?.name,
        "jiraAccountIdFrom" to previous?.jiraAccountId,
        "via" to via,
    )
}

/**
 * The host the data source's decisions are keyed by ([sourceHostOf] of its canonical base URL): `404` when the source does not exist or
 * is deleted, `409` `data-source-kind-unsupported` when it is not a GitLab source (the mirror of the metrics-config `409` for GitLab),
 * ONE read of the data source either way.
 */
private suspend fun DataSourceService.gitLabHost(id: UInt): String {
    val source = read(id).orNotFound("Data source")
    val gitlab = source.gitlab ?: throw DataSourceKindUnsupportedException(source.kind, "identity links")
    return sourceHostOf(gitlab.baseUrl)
}
