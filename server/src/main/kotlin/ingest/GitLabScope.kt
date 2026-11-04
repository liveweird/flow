package ch.nokillswit.ingest

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.active
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.slf4j.LoggerFactory
import java.net.URI

// The GitLab scope rules (`DataSourceScope.kt` has the lock, the Jira rules and the exception): every active GitLab source reads ONE
// instance, and no group path is read by two sources, so a project is never counted twice. Jira rows are never decoded here.

private val gitLabScopeLog = LoggerFactory.getLogger("ch.nokillswit.ingest.GitLabScope")

internal suspend fun enforceGitLabScope(gitlab: GitLabConnectionRequest, excludeId: UInt?, policy: GitLabBaseUrlPolicy) {
    val connections = DataSourceService.Connections
    val others = connections.selectAll().where { connections.active() and connections.isGitLab() }.toList()
        .filter { it[connections.id].value != excludeId }
        .mapNotNull { row ->
            // Tolerant like the Jira scope check: a row whose settings do not decode protects no paths (and cannot sync either).
            val id = row[connections.id].value
            val settings = catchingFailures({ row.connectionSettings() as GitLabConnectionSettings }) { failure ->
                gitLabScopeLog.warn("Data source {} has undecodable settings, skipped by the scope check: {}", id,
                    failure.javaClass.simpleName)
                null
            }
            settings?.let { GitLabScopeCandidate(id, row[connections.name], it.baseUrl, it.groupPaths) }
        }
    findGitLabScopeConflict(others, policy.canonicalOrigin(gitlab.baseUrl), gitlab.groupPaths, checkInstance = excludeId == null)
        ?.let { throw it }
}

/** Whether [group] is [other], under it or above it (`a/b` overlaps `a` and `a/b/c`, not `a/bc`). */
internal fun groupPathsOverlap(group: String, other: String): Boolean =
    group == other || group.startsWith("$other/") || other.startsWith("$group/")

/**
 * The lowest-id active GitLab data source (other than the row being updated, which the caller left out of [others]) that [baseUrl]/
 * [groupPaths] would conflict with, as the exception to throw, or null. On a create ([checkInstance]) a row on another instance
 * conflicts first; an update never changes the instance (the identity 409), so it checks the group overlap alone.
 */
internal fun findGitLabScopeConflict(
    others: List<GitLabScopeCandidate>,
    baseUrl: String,
    groupPaths: List<String>,
    checkInstance: Boolean,
): DataSourceScopeConflictException? {
    val host = hostOf(baseUrl)
    for (other in others.sortedBy { it.id }) {
        if (checkInstance && other.baseUrl != baseUrl) {
            return DataSourceScopeConflictException(
                ScopeConflictReason.OTHER_INSTANCE, other.id, emptyList(), host,
                "Data source \"${other.name}\" (#${other.id}) already uses the GitLab instance ${hostOf(other.baseUrl)}; " +
                    "Flow reads a single GitLab instance, so every GitLab data source must use it",
            )
        }
        val shared = groupPaths.filter { group -> other.groupPaths.any { groupPathsOverlap(group, it) } }
        if (shared.isNotEmpty()) {
            return DataSourceScopeConflictException(
                ScopeConflictReason.GROUP_OVERLAP, other.id, shared, host,
                "Group ${if (shared.size == 1) "path" else "paths"} ${shared.joinToString(", ")} " +
                    "${if (shared.size == 1) "overlaps" else "overlap"} data source \"${other.name}\" (#${other.id}); " +
                    "a project can belong to only one data source",
            )
        }
    }
    return null
}

private fun hostOf(baseUrl: String): String = URI(baseUrl).host.orEmpty()

/** One active GitLab data source as the scope check sees it. */
internal data class GitLabScopeCandidate(val id: UInt, val name: String, val baseUrl: String, val groupPaths: List<String>)
