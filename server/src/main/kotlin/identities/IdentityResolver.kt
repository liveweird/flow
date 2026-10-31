package ch.nokillswit.identities

import io.ktor.util.AttributeKey
import java.net.URI
import java.net.URISyntaxException

/** Published by `configureCodeIdentities` in EVERY role (a worker's DERIVE and the reports read links without serving the API). */
val IdentityResolverKey = AttributeKey<IdentityResolver>("IdentityResolver")

/**
 * The ONE entry point the later reports use to attribute GitLab activity to Jira people (`.claude/docs/gitlab-integration.md`
 * "Identity"): it reads CONFIRMED decisions only, never a proposal, so unconfirmed address matches attribute nothing. Unmapped activity is
 * kept and becomes attributable once an ADMIN links the identity. It answers account ids, not teams: a team at an instant is the one
 * pure rule in `metrics/` (`teamAt`), which this package must not import (layering, `PackageBoundaryTest`).
 *
 * The [host] is [sourceHostOf] of a data source's base URL. One `IN` statement per call; an empty input makes none.
 */
class IdentityResolver(private val links: CodeIdentityLinkStore) {

    /** The Jira account id of each of [keys] that has a LINKED decision on [host]; IGNORED, BOT and undecided keys are absent. */
    suspend fun accountsByKey(host: String, keys: Collection<String>): Map<String, String> =
        links.forKeys(host, keys).mapNotNull { link -> link.linkedAccount()?.let { link.key to it } }.toMap()

    /** The identity keys (sorted) LINKED to each of [accountIds] on [host]; an account with none is absent. */
    suspend fun keysByAccount(host: String, accountIds: Collection<String>): Map<String, List<String>> =
        links.forAccounts(host, accountIds).mapNotNull { link -> link.linkedAccount()?.let { it to link.key } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keys) -> keys.sorted() }

    private fun IdentityLink.linkedAccount(): String? = jiraAccountId.takeIf { decision == CodeIdentityDecision.LINKED }
}

/**
 * The instance a decision belongs to: the canonical base URL's authority, lower-cased - `gitlab.example.com`, or `gitlab.example.com:8443`
 * when the URL names a non-default port. The scheme carries no identity, and the stored base URL is already canonical
 * (`gitlab/GitLabBaseUrl.kt`), so the result is stable across edits that re-save the same origin. The malformed-URL message never
 * carries the URL.
 */
fun sourceHostOf(baseUrl: String): String {
    val uri = try {
        URI(baseUrl)
    } catch (_: URISyntaxException) {
        throw IllegalArgumentException("Data source base URL is malformed")
    }
    val host = requireNotNull(uri.host) { "Data source base URL has no host" }.lowercase()
    return if (uri.port == -1) host else "$host:${uri.port}"
}
