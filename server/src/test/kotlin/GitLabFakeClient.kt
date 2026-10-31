package ch.nokillswit

import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.gitlab.GitLabClient
import ch.nokillswit.gitlab.GitLabCommitPage
import ch.nokillswit.gitlab.GitLabCurrentUser
import ch.nokillswit.gitlab.GitLabDiffPage
import ch.nokillswit.gitlab.GitLabGroup
import ch.nokillswit.gitlab.GitLabPage
import ch.nokillswit.gitlab.GitLabTokenInfo
import ch.nokillswit.gitlab.GitLabUserPage

/** The source a scripted failure of [FakeClient] names (messages start "GitLab fetch failed"). */
val fakeSource = FetchSource("GitLab", "gitlab.maxResponseBytes")

/**
 * A scripted [GitLabClient] for the commit streams: [listingOf], [mergeBaseOf] and [diffOf] answer (or throw) per call, and the calls are
 * recorded. The calls the streams under test never make are `TODO()`.
 */
class FakeClient : GitLabClient {
    var listingOf: (projectId: Long, ref: String, since: String?, page: Int) -> GitLabCommitPage =
        { _, _, _, _ -> error("no listing scripted") }
    var mergeBaseOf: (projectId: Long, a: String, b: String) -> String = { _, _, _ -> error("no merge_base scripted") }
    var diffOf: (projectId: Long, sha: String, page: Int) -> GitLabDiffPage = { _, _, _ -> error("no diff scripted") }

    /** Every listing call as (ref, since, page). */
    val listed = mutableListOf<Triple<String, String?, Int>>()
    val listCalls: Int get() = listed.size
    var mergeBaseCalls = 0

    /** Every merge_base call as (refA, refB). */
    val mergeBases = mutableListOf<Pair<String, String>>()
    var diffCalls = 0

    override val maxConcurrentRequests: Int = 1

    override suspend fun commits(projectId: Long, refName: String, since: String?, page: Int): GitLabCommitPage {
        listed += Triple(refName, since, page)
        return listingOf(projectId, refName, since, page)
    }

    override suspend fun mergeBase(projectId: Long, refA: String, refB: String): String {
        mergeBaseCalls++
        mergeBases += refA to refB
        return mergeBaseOf(projectId, refA, refB)
    }

    override suspend fun commitDiff(projectId: Long, sha: String, page: Int): GitLabDiffPage {
        diffCalls++
        return diffOf(projectId, sha, page)
    }

    override suspend fun version(): String = TODO()

    override suspend fun currentUser(): GitLabCurrentUser = TODO()

    override suspend fun tokenSelf(): GitLabTokenInfo = TODO()

    override suspend fun group(path: String): GitLabGroup = TODO()

    override suspend fun groupProjects(groupId: Long, page: Int): GitLabPage = TODO()

    override suspend fun users(page: Int): GitLabUserPage = TODO()

    override suspend fun userEmails(userId: Long): List<String> = TODO()
}
