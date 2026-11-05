package ch.nokillswit

import ch.nokillswit.norm.ISSUE_KEY_MAX_LENGTH
import ch.nokillswit.norm.IssueKeyCandidate
import ch.nokillswit.norm.extractIssueKeyCandidates
import ch.nokillswit.norm.extractIssueKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure issue-key extractor (`norm/IssueKeys.kt`; V46 merge request rows, V47 commit rows): its token rule, boundaries and folding. */
class IssueKeysTest {
    private fun keys(title: String?, branch: String? = null) = extractIssueKeyCandidates(title, branch).map { it.issueKey }

    private fun titleOnly(vararg keys: String) = keys.map { IssueKeyCandidate(it, inTitle = true, inBranch = false) }

    @Test
    fun `a key is found between any non-alphanumeric boundaries`() {
        val titles = listOf("[FLO-12] fix", "FLO-12: fix", "(FLO-12)", "fix FLO-12", "FLO-12", "fix, FLO-12.", "fix/FLO-12/x", "a-FLO-12-b")
        for (title in titles) {
            assertEquals(titleOnly("FLO-12"), extractIssueKeyCandidates(title, null), title)
        }
        assertEquals(
            listOf(IssueKeyCandidate("FLO-12", inTitle = false, inBranch = true)),
            extractIssueKeyCandidates("no key here", "feature/flo-12-fix"),
        )
    }

    @Test
    fun `the underscore is a boundary - a key beside it is found, a bare underscore number is not`() {
        assertEquals(emptyList(), keys("flo_12"), "no hyphen: no key")
        assertEquals(listOf("FLO-12"), keys("FLO-12_fix"), "a trailing underscore ends the key")
        assertEquals(listOf("FLO-12"), keys("_FLO-12"), "a leading underscore starts it")
        // An inner underscore belongs to the project part (Jira allows it), and each suffix after one is a candidate of its own.
        assertEquals(listOf("FIX_FLO-12", "FLO-12"), keys("fix_FLO-12"))
        assertEquals(listOf("MY_PROJ-1", "PROJ-1"), keys("MY_PROJ-1"))
        assertEquals(listOf("A_B-1"), keys("A_B-1"), "the suffix B alone is too short to be a project part")
        assertEquals(listOf("A_B2_CD-7", "B2_CD-7", "CD-7"), keys("a_b2_cd-7"))
        assertEquals(listOf("X_9A-3"), keys("x_9a-3"), "a suffix must start with a letter: 9A-3 is no candidate")
    }

    @Test
    fun `a key is folded to uppercase and written once per merge request`() {
        assertEquals(titleOnly("FLO-12"), extractIssueKeyCandidates("flo-12 and Flo-12 and FLO-12", null))
        assertEquals(
            listOf(IssueKeyCandidate("FLO-12", inTitle = true, inBranch = true)),
            extractIssueKeyCandidates("FLO-12: fix", "feature/flo-12-fix"),
            "a key in both is one candidate with both flags",
        )
    }

    @Test
    fun `two keys come back sorted, each with its own flags`() {
        val found = extractIssueKeyCandidates("PLT-7 and FLO-9, FLO-12", "gtm-3/plt-7")
        assertEquals(
            listOf(
                IssueKeyCandidate("FLO-12", inTitle = true, inBranch = false),
                IssueKeyCandidate("FLO-9", inTitle = true, inBranch = false),
                IssueKeyCandidate("GTM-3", inTitle = false, inBranch = true),
                IssueKeyCandidate("PLT-7", inTitle = true, inBranch = true),
            ),
            found,
        )
        assertEquals(found, extractIssueKeyCandidates("PLT-7 and FLO-9, FLO-12", "gtm-3/plt-7"), "deterministic")
    }

    @Test
    fun `a number has no leading zero and no more than seven digits`() {
        assertEquals(emptyList(), keys("FLO-012"))
        assertEquals(emptyList(), keys("FLO-0"))
        assertEquals(listOf("FLO-1"), keys("FLO-1"))
        assertEquals(listOf("FLO-9999999"), keys("FLO-9999999"))
        assertEquals(emptyList(), keys("FLO-12345678"), "eight digits")
    }

    @Test
    fun `an alphanumeric neighbour on either side disqualifies the token`() {
        assertEquals(emptyList(), keys("FLO-12a"))
        assertEquals(emptyList(), keys("FLO-12b3"))
        assertEquals(listOf("XFLO-12"), keys("xFLO-12"), "a letter before extends the project part")
        assertEquals(emptyList(), keys("2FLO-12"))
        assertEquals(emptyList(), keys("FLO-12345678"))
        assertEquals(emptyList(), keys("1-12"), "the project part starts with a letter")
        assertEquals(emptyList(), keys("F-12"), "and has at least two characters")
        assertEquals(emptyList(), keys("ABCDEFGHIJK-12"), "and at most ten")
        assertEquals(listOf("ABCDEFGHIJ-12"), keys("ABCDEFGHIJ-12"))
        assertEquals(listOf("FLO-12", "OPS-3"), keys("FLO-12-OPS-3"), "a hyphen separates two keys")
    }

    @Test
    fun `no key is longer than the column`() {
        assertEquals(18, keys("ABCDEFGHIJ-9999999").single().length)
        assertTrue(keys("A_B_C_D_E_F-9999999 fix_ABCDEFGHIJ-9999999").all { it.length <= ISSUE_KEY_MAX_LENGTH })
    }

    @Test
    fun `junk tokens are candidates by design - the read-time scope filters them`() {
        assertEquals(listOf("SHA-256", "UTF-8"), keys("Encode as utf-8 and SHA-256"))
        assertEquals(listOf("V2-3"), keys("bump", "feature/v2-3-cleanup"))
        assertEquals(listOf("RELEASE-2024"), keys("release-2024-1"))
    }

    @Test
    fun `null and empty inputs give no candidates`() {
        assertEquals(emptyList(), extractIssueKeyCandidates(null, null))
        assertEquals(emptyList(), extractIssueKeyCandidates("", ""))
        assertEquals(emptyList(), extractIssueKeyCandidates("no keys, just words", "main"))
        assertEquals(titleOnly("FLO-1"), extractIssueKeyCandidates("FLO-1", null))
        assertEquals(listOf("FLO-1"), keys(null, "flo-1"))
    }

    @Test
    fun `a non-ascii letter beside a key is a boundary`() {
        assertEquals(listOf("FLO-12"), keys("naprawa-FLO-12, żółć FLO-12ż"), "ASCII letters and digits are the only neighbours that bind")
    }

    @Test
    fun `a commit title is read through the same rule - sorted, folded and distinct`() {
        assertEquals(listOf("FLO-31"), extractIssueKeys("flo-31: tighten the retry"))
        assertEquals(listOf("PLT-45"), extractIssueKeys("[PLT-45] bump the client"))
        assertEquals(listOf("FLO-120", "PLT-60"), extractIssueKeys("plt-60 and FLO-120 together"), "sorted, not in text order")
        assertEquals(listOf("FLO-1"), extractIssueKeys("FLO-1 then flo-1 again"), "one row per key")
        assertEquals(listOf("UTF-8"), extractIssueKeys("switch to UTF-8"), "junk is a candidate by design")
        assertEquals(listOf("FIX_FLO-8", "FLO-8"), extractIssueKeys("fix_FLO-8: the suffix is a candidate too"))
        assertEquals(listOf("FLO-9"), extractIssueKeys("Merge branch 'feature/flo-9-x' into 'main'"), "a merge title quotes the branch")
        assertEquals(emptyList(), extractIssueKeys("FLO-012 and FLO-12a and 2FLO-12"), "leading zero and alphanumeric neighbours")
    }

    @Test
    fun `a commit title extraction equals the title side of the merge request rule`() {
        for (title in listOf("[FLO-12] fix", "a-FLO-12-b", "fix_FLO-12, MY_PROJ-1", "no key", "OPS-1 OPS-2 ops-1", "")) {
            assertEquals(extractIssueKeyCandidates(title, null).map { it.issueKey }, extractIssueKeys(title), title)
        }
    }

    @Test
    fun `a null or empty commit title gives no keys, and no key is longer than the column`() {
        assertEquals(emptyList(), extractIssueKeys(null))
        assertEquals(emptyList(), extractIssueKeys(""))
        assertTrue(extractIssueKeys("ABCDEFGHIJ-1234567 FLO-1").all { it.length <= ISSUE_KEY_MAX_LENGTH })
    }
}
