package ch.nokillswit.ingest

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.active
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.slf4j.LoggerFactory

private val scopeLog = LoggerFactory.getLogger("ch.nokillswit.ingest.DataSourceScope")

/**
 * Whether [DataSourceService] enforces the data-source scope invariants (`.claude/docs/ingestion.md` "Data
 * sources"): ONE Jira site across every active Jira data source and NO project key in two of them; for GitLab, ONE instance across
 * the active GitLab sources and NO group path equal to, under or above another source's (`GitLabScope.kt`). The kinds never conflict
 * with each other.
 * [ENFORCED] is the only production value; [OFF] exists for the e2e stack (`INGEST_SCOPE_RULES=off`, refused outside
 * development mode by `configureDatabase`) and for the server tests' direct fixture service.
 */
enum class ScopeRules {
    ENFORCED,
    OFF,
    ;

    companion object {
        /** The `ingest.scopeRules` config value; case-insensitive, anything else fails startup. */
        fun parse(raw: String): ScopeRules = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: error("Config \"ingest.scopeRules\" must be \"enforced\" or \"off\", got \"$raw\"")
    }
}

/**
 * The boot-validated `ingest.scopeRules` (`INGEST_SCOPE_RULES`, default `enforced`; blank also means the default). Fail-closed
 * like `jira.stubBaseUrl`: `off` outside development mode refuses to start, so production can never run without the rules
 * (the shape of `jira/Jira.kt`'s stub-URL refusal; pinned by `DataSourceScopeBootTest`).
 */
internal fun Application.configuredScopeRules(): ScopeRules {
    val raw = environment.config.propertyOrNull("ingest.scopeRules")?.getString()?.trim().orEmpty()
    val rules = if (raw.isEmpty()) ScopeRules.ENFORCED else ScopeRules.parse(raw)
    if (rules == ScopeRules.OFF && !developmentMode) {
        error("Config \"ingest.scopeRules\" must be \"enforced\" outside development mode (the scope rules can never be off in production)")
    }
    if (rules == ScopeRules.OFF) scopeLog.warn("INGEST_SCOPE_RULES=off: the scope rules are not enforced (permitted in development only)")
    return rules
}

/**
 * The transaction-scoped advisory lock `create` and `update` take FIRST, so two concurrent writes cannot both pass the
 * scope check against a snapshot that lacks the other (the ASCII bytes of "FlowSite", the single-`bigint` form
 * `pg_advisory_xact_lock(key)`; distinct from `DIM_DATE_LOCK_KEY`, and the `bigint` form never collides with
 * `SyncJobsService.claim`'s two-`int` key space). A row lock cannot do the job: the invariant spans rows that do not
 * exist yet (`.claude/docs/persistence.md` "advisory locks").
 */
internal const val DATA_SOURCE_SCOPE_LOCK_KEY = 0x466C6F7753697465L

/** Which scope rule a write broke; the [problemType] is the RFC 7807 `type` a client branches on. */
enum class ScopeConflictReason(val problemType: String, val kind: DataSourceKind) {
    OTHER_SITE("urn:flow:problem:data-source-other-site", DataSourceKind.JIRA_CLOUD),
    PROJECT_OVERLAP("urn:flow:problem:data-source-project-overlap", DataSourceKind.JIRA_CLOUD),
    OTHER_INSTANCE("urn:flow:problem:data-source-other-instance", DataSourceKind.GITLAB),
    GROUP_OVERLAP("urn:flow:problem:data-source-group-overlap", DataSourceKind.GITLAB),
}

/**
 * A 409 for a write that would break a scope invariant. `instance` is the conflicting data source's own path
 * (`/api/v1/data-sources/{id}`); [keys] are the shared project keys (Jira) or the overlapping group paths (GitLab), empty for the
 * site/instance reasons, and [host] is the REQUESTED site's or instance's host (never the full URL) — both feed the
 * `data_source.scope_rejected` audit event.
 */
class DataSourceScopeConflictException(
    val reason: ScopeConflictReason,
    val conflictingId: UInt,
    val keys: List<String>,
    val host: String,
    detail: String,
) : ConflictException(detail, reason.problemType, "/api/v1/data-sources/$conflictingId")

/** The scope lock (see [DATA_SOURCE_SCOPE_LOCK_KEY]); a no-op under [ScopeRules.OFF]. Must be the transaction's first lock. */
internal suspend fun R2dbcTransaction.takeDataSourceScopeLock(rules: ScopeRules) {
    if (rules == ScopeRules.ENFORCED) exec("SELECT pg_advisory_xact_lock($DATA_SOURCE_SCOPE_LOCK_KEY)")
}

/**
 * Throws the scope 409 for [request] against every active data source of its kind other than [excludeId] (the row being updated;
 * null on a create, which also checks the site or instance); the caller holds the lock from [takeDataSourceScopeLock]. [gitLabPolicy]
 * canonicalises a GitLab base URL for the comparison.
 */
internal suspend fun enforceDataSourceScope(
    rules: ScopeRules,
    request: DataSourceRequest,
    excludeId: UInt?,
    gitLabPolicy: GitLabBaseUrlPolicy?,
) {
    if (rules == ScopeRules.OFF) return
    when (request.kindOrBadRequest()) {
        DataSourceKind.JIRA_CLOUD -> enforceJiraScope(checkNotNull(request.jira), excludeId)
        DataSourceKind.GITLAB -> enforceGitLabScope(checkNotNull(request.gitlab), excludeId, checkNotNull(gitLabPolicy))
    }
}

private suspend fun enforceJiraScope(jira: JiraConnectionRequest, excludeId: UInt?) {
    val connections = DataSourceService.Connections
    // These are the JIRA rules (one Jira site, no project in two sources): they are checked against Jira rows only. Another kind's
    // rows neither protect keys nor name a site, and are never decoded here (GitLab has its own rules: `GitLabScope.kt`).
    val others = connections.selectAll().where { connections.active() and connections.isJira() }.toList()
        .filter { it[connections.id].value != excludeId }
        .mapNotNull { row ->
            // Tolerant like the reports' filter reads: a row whose settings do not decode protects no keys (and cannot sync
            // either) - it must not turn every create and update into a 500.
            val id = row[connections.id].value
            val settings = catchingFailures({ row.jiraSettings() }) { failure ->
                scopeLog.warn("Data source {} has undecodable settings, skipped by the scope check: {}", id, failure.javaClass.simpleName)
                null
            }
            settings?.let { ScopeCandidate(id, row[connections.name], it.siteUrl, it.projectKeys) }
        }
    findScopeConflict(others, jira.siteUrl, jira.projectKeys, checkSite = excludeId == null)?.let { throw it }
}

/**
 * The lowest-id active data source (other than [excludeId], the row being updated) that [siteUrl]/[projectKeys] would
 * conflict with, as the exception to throw, or null. On a create ([checkSite]) a row on another site conflicts first;
 * an update never changes the site (the identity 409), so it checks the project overlap alone.
 */
internal fun findScopeConflict(
    others: List<ScopeCandidate>,
    siteUrl: String,
    projectKeys: List<String>,
    checkSite: Boolean,
): DataSourceScopeConflictException? {
    for (other in others.sortedBy { it.id }) {
        if (checkSite && other.siteUrl != siteUrl) {
            return DataSourceScopeConflictException(
                ScopeConflictReason.OTHER_SITE, other.id, emptyList(), siteHost(siteUrl),
                "Data source \"${other.name}\" (#${other.id}) already uses the Jira site ${siteHost(other.siteUrl)}; " +
                    "Flow reads a single Jira site, so every data source must use it",
            )
        }
        val shared = projectKeys.filter { it in other.projectKeys }
        if (shared.isNotEmpty()) {
            return DataSourceScopeConflictException(
                ScopeConflictReason.PROJECT_OVERLAP, other.id, shared, siteHost(siteUrl),
                "Project ${if (shared.size == 1) "key" else "keys"} ${shared.joinToString(", ")} " +
                    "${if (shared.size == 1) "is" else "are"} already read by data source \"${other.name}\" (#${other.id}); " +
                    "a project can belong to only one data source",
            )
        }
    }
    return null
}

/** One active data source as the scope check sees it. */
internal data class ScopeCandidate(val id: UInt, val name: String, val siteUrl: String, val projectKeys: List<String>)
