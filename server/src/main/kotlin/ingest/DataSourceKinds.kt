package ch.nokillswit.ingest

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.gitlab.GitLabSettings
import ch.nokillswit.ingest.DataSourceService.Connections
import org.jetbrains.exposed.v1.core.Op
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.inList

/*
 * The kind-leak guards (`.claude/docs/ingestion.md` "Connector kinds"): a connection of a kind that is not Jira, or that derives no
 * metrics, must never reach a path written for Jira rows. Two shapes: a SQL predicate for the paths that LIST connections, and a typed
 * 409 for the routes that address ONE connection by id.
 */

/**
 * The connections of a kind that feeds the `metrics` star ([DataSourceKind.derivesMetrics]): the predicate every report scope, the
 * DERIVE fan-out and the other metrics-only lists add beside `active()`.
 */
fun Connections.derivesMetrics(): Op<Boolean> = kind inList DataSourceKind.entries.filter { it.derivesMetrics }.map { it.name }

/** The Jira connections alone: the scope rules and every other Jira-only list. */
fun Connections.isJira(): Op<Boolean> = kind inList listOf(DataSourceKind.JIRA_CLOUD.name)

/** The GitLab connections alone: the GitLab scope rules and every other GitLab-only list. */
fun Connections.isGitLab(): Op<Boolean> = kind inList listOf(DataSourceKind.GITLAB.name)

/** The RFC 7807 `type` of [DataSourceKindUnsupportedException]. */
const val DATA_SOURCE_KIND_UNSUPPORTED = "urn:flow:problem:data-source-kind-unsupported"

/** An existing data source of a kind the route does not serve (metrics-config on GitLab, say): 409 `data-source-kind-unsupported`. */
class DataSourceKindUnsupportedException(val kind: DataSourceKind, what: String) :
    ConflictException("This data source is a ${kind.name} connection, which has no $what", DATA_SOURCE_KIND_UNSUPPORTED)

/** The connection's kind, or 404 when it does not exist / is deleted; throws the 409 when [allowed] rejects the kind. */
private suspend fun DataSourceService.requireKind(id: UInt, what: String, allowed: (DataSourceKind) -> Boolean) {
    val kind = kindOf(id) ?: throw NotFoundException("Data source not found")
    if (!allowed(kind)) throw DataSourceKindUnsupportedException(kind, what)
}

/** 404 when [id] is not an active data source; 409 `data-source-kind-unsupported` unless its kind derives metrics. */
suspend fun DataSourceService.requireMetricsConnection(id: UInt) = requireKind(id, "metrics configuration") { it.derivesMetrics }

/** 404 when [id] is not an active data source; 409 `data-source-kind-unsupported` unless it is a Jira connection. */
suspend fun DataSourceService.requireJiraConnection(id: UInt, what: String) =
    requireKind(id, what) { it == DataSourceKind.JIRA_CLOUD }

/**
 * One connector kind as the admin UI's kind picker needs it (`GET /api/v1/data-sources/kinds`): whether a data source of the kind can
 * be created on this server, and the origins an operator allows. A bounded, admin-curated set, so the plain `{items}` wrapper.
 */
@Serializable
data class DataSourceKindInfo(
    val kind: DataSourceKind,
    val enabled: Boolean,
    val allowedOrigins: List<String>,
    /** The path-exclusion globs a source of the kind follows until it sets its own (GitLab's built-ins; empty for Jira). */
    val defaultPathExclusions: List<String>,
)

@Serializable
data class DataSourceKindsResponse(val items: List<DataSourceKindInfo>)

/**
 * Every [DataSourceKind], in enum order: Jira Cloud is always available (its allow-list is the Atlassian shape, not an operator list);
 * GitLab is enabled when the operator listed a host or a development stub is set, and its origins are exactly those the base-URL
 * validator would accept (`https://host[:port]` per listed host, plus the stub's own origin). Nothing here is a secret.
 */
fun dataSourceKinds(gitlab: GitLabSettings): DataSourceKindsResponse = DataSourceKindsResponse(
    DataSourceKind.entries.map { kind ->
        when (kind) {
            DataSourceKind.JIRA_CLOUD -> DataSourceKindInfo(
                kind,
                enabled = true,
                allowedOrigins = emptyList(),
                defaultPathExclusions = emptyList(),
            )
            DataSourceKind.GITLAB -> DataSourceKindInfo(
                kind,
                enabled = gitlab.enabled,
                allowedOrigins = if (gitlab.enabled) gitlab.hosts.origins() + listOfNotNull(gitlab.devStub?.origin()) else emptyList(),
                defaultPathExclusions = DEFAULT_PATH_EXCLUSIONS,
            )
        }
    },
)
