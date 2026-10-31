package ch.nokillswit.ingest

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.maskedSecret
import io.ktor.server.plugins.BadRequestException
import java.net.URI

// The per-kind half of a data-source write (`DataSourceService.create/update`, the Test-connection routes): which block a request
// carries, what settings it stores, how a PUT merges into the stored ones. Kept out of `DataSourceService.kt` (the 400-line cap), so
// a third kind adds a branch here, not a second copy of the service's transaction.

/** Which block a body carries; exactly one must be present. */
private fun kindOf(jira: Any?, gitlab: Any?): DataSourceKind = when {
    jira != null && gitlab != null -> throw BadRequestException("Exactly one of jira or gitlab must be present")
    jira != null -> DataSourceKind.JIRA_CLOUD
    gitlab != null -> DataSourceKind.GITLAB
    else -> throw BadRequestException("Exactly one of jira or gitlab must be present")
}

/** The kind the request's block names, or a 400 unless exactly one block is present. */
internal fun DataSourceRequest.kindOrBadRequest(): DataSourceKind = kindOf(jira, gitlab)

/** The kind an ad-hoc Test-connection body names, or a 400 unless exactly one block is present. */
internal fun DataSourceTestRequest.kindOrBadRequest(): DataSourceKind = kindOf(jira, gitlab)

/** The host only, never the full URL, for audit lines: Jira's site host, the GitLab origin's host. [policy] canonicalises GitLab's. */
internal fun DataSourceRequest.host(policy: GitLabBaseUrlPolicy?): String = when (kindOrBadRequest()) {
    DataSourceKind.JIRA_CLOUD -> siteHost(checkNotNull(jira).siteUrl)
    DataSourceKind.GITLAB -> gitLabHost(checkNotNull(gitlab).baseUrl, policy)
}

private fun gitLabHost(rawBaseUrl: String, policy: GitLabBaseUrlPolicy?): String =
    URI(policy?.canonicalOrigin(rawBaseUrl) ?: rawBaseUrl).host.orEmpty()

/** The write-only secret the request carries: Jira's `apiToken`, GitLab's `token` (null = keep the stored one on a PUT). */
internal fun DataSourceRequest.secret(): String? = jira?.apiToken ?: gitlab?.token

/** The settings a CREATE stores. GitLab's base URL is stored canonical (the policy's origin), so the identity compares exactly. */
internal fun DataSourceRequest.newSettings(policy: GitLabBaseUrlPolicy?): ConnectionSettings = when (kindOrBadRequest()) {
    DataSourceKind.JIRA_CLOUD -> checkNotNull(jira).let {
        JiraConnectionSettings(siteUrl = it.siteUrl, email = it.email, projectKeys = it.projectKeys, authScheme = it.authScheme)
    }
    DataSourceKind.GITLAB -> checkNotNull(gitlab).let {
        GitLabConnectionSettings(
            baseUrl = checkNotNull(policy) { "no GitLab base-URL policy" }.canonicalOrigin(it.baseUrl),
            groupPaths = it.groupPaths,
            pathExclusions = it.pathExclusions,
        )
    }
}

/**
 * The settings a PUT stores: [current] with the request's editable fields. The identity (Jira's `siteUrl`, GitLab's canonical
 * `baseUrl`) and the kind are fixed at create: a different one is a 409, not silently accepted.
 */
internal fun mergeSettings(current: ConnectionSettings, request: DataSourceRequest, policy: GitLabBaseUrlPolicy?): ConnectionSettings =
    when (current) {
        is JiraConnectionSettings -> {
            val jira = request.jira ?: throw kindFixed()
            if (jira.siteUrl != current.siteUrl) {
                throw ConflictException("siteUrl is the data source identity; create a new connection to change it")
            }
            current.copy(email = jira.email, projectKeys = jira.projectKeys, authScheme = jira.authScheme)
        }
        is GitLabConnectionSettings -> {
            val gitlab = request.gitlab ?: throw kindFixed()
            if (checkNotNull(policy) { "no GitLab base-URL policy" }.canonicalOrigin(gitlab.baseUrl) != current.baseUrl) {
                throw ConflictException("baseUrl is the data source identity; create a new connection to change it")
            }
            current.copy(groupPaths = gitlab.groupPaths, pathExclusions = gitlab.pathExclusions ?: current.pathExclusions)
        }
    }

private fun kindFixed() = ConflictException("kind is fixed at create; create a new connection to change it")

/** The stored GitLab connection as the SYNC job reads it (`gitlab/GitLabConnector.kt`): the token decrypted, never logged. */
data class StoredGitLabConnection(
    val baseUrl: String,
    val token: String,
    val groupPaths: List<String>,
    /** ISO date (`YYYY-MM-DD`) - the COMMITS stream's first-run floor (a later release); stored now so the shape is stable. */
    val backfillFrom: String,
) {
    override fun toString(): String =
        "StoredGitLabConnection(baseUrl=$baseUrl, token=${maskedSecret(token)}, groupPaths=$groupPaths, backfillFrom=$backfillFrom)"
}

/**
 * The sanitized, validated in-process target of an ad-hoc Test-connection body (`POST /api/v1/data-sources/test`): exactly one block,
 * its token required. [policy] is the GitLab base-URL allow-list.
 */
internal fun DataSourceTestRequest.toTarget(policy: GitLabBaseUrlPolicy?): ConnectionTarget = when (kindOrBadRequest()) {
    DataSourceKind.JIRA_CLOUD -> {
        val jira = sanitizedJiraConnectionRequest(checkNotNull(jira))
        validateJira(jira, apiTokenRequired = true)
        ConnectionTarget.Jira(
            siteUrl = jira.siteUrl,
            email = jira.email,
            apiToken = checkNotNull(jira.apiToken) { "apiToken is required on the ad-hoc test" },
            projectKeys = jira.projectKeys,
            authScheme = jira.authScheme,
        )
    }
    DataSourceKind.GITLAB -> {
        val gitlab = sanitizedGitLabConnectionRequest(checkNotNull(gitlab))
        validateGitLabConnection(gitlab, tokenRequired = true, policy)
        ConnectionTarget.GitLab(
            baseUrl = checkNotNull(policy).canonicalOrigin(gitlab.baseUrl),
            token = checkNotNull(gitlab.token) { "token is required on the ad-hoc test" },
            groupPaths = gitlab.groupPaths,
        )
    }
}

/** The host only, for the `data_source.tested` audit line. */
internal fun ConnectionTarget.host(): String = when (this) {
    is ConnectionTarget.Jira -> siteHost(siteUrl)
    is ConnectionTarget.GitLab -> URI(baseUrl).host.orEmpty()
}

internal val ConnectionTarget.kind: DataSourceKind
    get() = when (this) {
        is ConnectionTarget.Jira -> DataSourceKind.JIRA_CLOUD
        is ConnectionTarget.GitLab -> DataSourceKind.GITLAB
    }
