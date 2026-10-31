package ch.nokillswit

import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.gitlab.GitLabClient
import ch.nokillswit.gitlab.GitLabCommitPage
import ch.nokillswit.gitlab.GitLabCurrentUser
import ch.nokillswit.gitlab.GitLabDiffPage
import ch.nokillswit.gitlab.GitLabDiffStatsAnswer
import ch.nokillswit.gitlab.GitLabGroup
import ch.nokillswit.gitlab.GitLabMergeRequestPage
import ch.nokillswit.gitlab.GitLabNotePage
import ch.nokillswit.gitlab.GitLabPage
import ch.nokillswit.gitlab.GitLabReviewerSummary
import ch.nokillswit.gitlab.GitLabTokenInfo
import ch.nokillswit.gitlab.GitLabUserPage

/** The source a scripted failure of [FakeClient] names (messages start "GitLab fetch failed"). */
val fakeSource = FetchSource("GitLab", "gitlab.maxResponseBytes")

/**
 * A scripted [GitLabClient] for the commit and merge-request streams: [listingOf], [mergeBaseOf], [diffOf] and the merge-request lambdas
 * answer (or throw) per call, and the calls are recorded. The calls the streams under test never make are `TODO()`.
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

    /** The group MR listing / notes / approvals / reviewers / GraphQL answers; each throws "not scripted" until a test sets it. */
    var groupMergeRequestsOf: (groupId: Long, updatedAfter: String?, page: Int) -> GitLabMergeRequestPage =
        { _, _, _ -> error("no merge-request listing scripted") }
    var notesOf: (projectId: Long, iid: Long, page: Int) -> GitLabNotePage = { _, _, _ -> error("no notes scripted") }
    var approvalsOf: (projectId: Long, iid: Long) -> List<Long> = { _, _ -> error("no approvals scripted") }
    var reviewersOf: (projectId: Long, iid: Long) -> List<GitLabReviewerSummary> = { _, _ -> error("no reviewers scripted") }
    var diffStatsOf: (fullPath: String, iids: List<Long>) -> GitLabDiffStatsAnswer = { _, _ -> error("no diff stats scripted") }

    /** Every merge-request listing call as (groupId, updatedAfter, page). */
    val listedMrs = mutableListOf<Triple<Long, String?, Int>>()

    /** Every GraphQL call as (fullPath, iids). */
    val graphqlCalls = mutableListOf<Pair<String, List<Long>>>()

    /** Every notes, approvals and reviewers call (the REST calls of the MR details). */
    var restCalls = 0

    override suspend fun groupMergeRequests(groupId: Long, updatedAfter: String?, page: Int): GitLabMergeRequestPage {
        listedMrs += Triple(groupId, updatedAfter, page)
        return groupMergeRequestsOf(groupId, updatedAfter, page)
    }

    override suspend fun mergeRequestNotes(projectId: Long, iid: Long, page: Int): GitLabNotePage {
        restCalls++
        return notesOf(projectId, iid, page)
    }

    override suspend fun mergeRequestApprovals(projectId: Long, iid: Long): List<Long> {
        restCalls++
        return approvalsOf(projectId, iid)
    }

    override suspend fun mergeRequestReviewers(projectId: Long, iid: Long): List<GitLabReviewerSummary> {
        restCalls++
        return reviewersOf(projectId, iid)
    }

    override suspend fun mergeRequestDiffStats(projectFullPath: String, iids: List<Long>): GitLabDiffStatsAnswer {
        graphqlCalls += projectFullPath to iids
        return diffStatsOf(projectFullPath, iids)
    }
}
