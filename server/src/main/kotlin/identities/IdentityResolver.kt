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

    /**
     * The stored decision of each of [keys] on [host], whatever it is (LINKED, IGNORED or BOT); an undecided key is absent. Report 21
     * reads it to tell a human reviewer from a BOT or IGNORED actor (`reports/CodeActivityScope.kt`): those attribute nothing and are
     * not "a review by another human", while an UNDECIDED actor still is one.
     */
    suspend fun decidedKeys(host: String, keys: Collection<String>): Map<String, CodeIdentityDecision> =
        links.forKeys(host, keys).associate { it.key to it.decision }

    private fun IdentityLink.linkedAccount(): String? = jiraAccountId.takeIf { decision == CodeIdentityDecision.LINKED }
}

/**
 * The instance a decision belongs to. [baseUrl] is the CANONICAL origin (`gitlab/GitLabBaseUrl.kt`: scheme and authority, the scheme's
 * default port already dropped, no path), never raw user input. The result is the authority, lower-cased - `gitlab.example.com`, or
 * `gitlab.example.com:8443` when the URL names a non-default port. The scheme carries no identity, so the result is stable across edits
 * that re-save the same origin. The malformed-URL message never carries the URL.
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
