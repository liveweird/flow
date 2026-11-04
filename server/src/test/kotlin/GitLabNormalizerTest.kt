package ch.nokillswit

import ch.nokillswit.gitlab.IdentityMap
import ch.nokillswit.gitlab.RawMergeRequestForProcessing
import ch.nokillswit.gitlab.RawUserForNorm
import ch.nokillswit.gitlab.codeMergeRequest
import ch.nokillswit.norm.IssueKeyCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The pure GitLab-specific reading of the user table (`gitlab/GitLabNormalizer.kt`'s [IdentityMap]): no database. */
class GitLabNormalizerTest {
    private fun user(id: Long, username: String, emails: List<String> = emptyList(), deletedAt: Long? = null) =
        RawUserForNorm(id, username, "Name $id", "active", false, emails, deletedAt)

    @Test
    fun `a reused username or address resolves to the live account, never to the deleted one`() {
        val map = IdentityMap.from(
            listOf(
                user(12, "jdoe", listOf("j.doe@mail.example"), deletedAt = 5L),
                user(340, "jdoe", listOf("j.doe@mail.example")),
            ),
        )
        assertEquals("u340", map.usernameKey("jdoe"))
        assertEquals("u340", map.usernameKey("JDoe"), "usernames fold case")
        assertEquals("u340", map.resolveAuthor("  J.Doe@Mail.Example "))
        // Both live (or both deleted): the lowest id, deterministic.
        val twoLive = IdentityMap.from(listOf(user(9, "x", listOf("x@mail.example")), user(3, "x", listOf("x@mail.example"))))
        assertEquals("u3", twoLive.usernameKey("x"))
        assertEquals("u3", twoLive.resolveAuthor("x@mail.example"))
    }

    @Test
    fun `an address with no live or deleted owner is an e key, and the fingerprints follow the maps`() {
        val map = IdentityMap.from(listOf(user(1, "a", listOf("a@mail.example"))))
        assertNull(map.usernameKey("nobody"))
        assertEquals(33, map.resolveAuthor("stranger@mail.example").length)
        val renamed = IdentityMap.from(listOf(user(1, "b", listOf("a@mail.example"))))
        assertEquals(map.fingerprint, renamed.fingerprint, "the address map does not know usernames")
        assertNotEquals(map.usernameFingerprint, renamed.usernameFingerprint)
        val moreAddresses = IdentityMap.from(listOf(user(1, "a", listOf("a@mail.example", "a2@mail.example"))))
        assertNotEquals(map.fingerprint, moreAddresses.fingerprint)
        assertEquals(map.usernameFingerprint, moreAddresses.usernameFingerprint)
    }

    private fun rawMr(title: String, source: String, target: String = "main", detailsError: String? = null, deletedAt: Long? = null) =
        RawMergeRequestForProcessing(
            mrId = 1, projectId = 2, iid = 3, state = "merged", draft = false, authorId = null, mergeUserId = null, createdAt = 1,
            updatedAt = 2, mergedAt = 3, closedAt = null, sourceBranch = source, targetBranch = target, title = title, headSha = null,
            mergeCommitSha = null, squashCommitSha = null, reviewerIds = emptyList(), assigneeIds = emptyList(), additions = null,
            deletions = null, fileCount = null, notesCapped = false, changedAt = 1, detailsSyncedAt = 2, detailsError = detailsError,
            deletedAt = deletedAt,
        )

    @Test
    fun `a normalized merge request names the keys of its title and source branch, never of its target branch`() {
        val map = IdentityMap.from(emptyList())
        val mr = codeMergeRequest(rawMr("FLO-12: fix (OPS-3)", "feature/flo-12-fix", target = "release/PLT-9"), emptyList(), map)
        assertEquals(
            listOf(
                IssueKeyCandidate("FLO-12", inTitle = true, inBranch = true),
                IssueKeyCandidate("OPS-3", inTitle = true, inBranch = false),
            ),
            mr.issueKeys,
        )
        assertEquals(emptyList(), codeMergeRequest(rawMr("no key", "main"), emptyList(), map).issueKeys)
    }

    @Test
    fun `the keys do not depend on the details state or a tombstone`() {
        val map = IdentityMap.from(emptyList())
        val keys = listOf(IssueKeyCandidate("ABC-7", inTitle = true, inBranch = false))
        assertEquals(keys, codeMergeRequest(rawMr("ABC-7", "x", detailsError = "NOT_FOUND"), emptyList(), map).issueKeys)
        assertEquals(keys, codeMergeRequest(rawMr("ABC-7", "x", deletedAt = 9), emptyList(), map).issueKeys)
    }
}
