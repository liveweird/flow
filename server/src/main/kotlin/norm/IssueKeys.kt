package ch.nokillswit.norm

/**
 * The issue-key candidates a merge request names (`gitlab-integration.md` "Issue keys", the V46 `norm.code_mr_issue_keys` rows).
 * Pure and connector-agnostic: no knowledge of which Jira projects exist - EVERY token the rule admits is a CANDIDATE (`UTF-8`,
 * `SHA-256` and a `v2-3` branch slug included, by design), and the reports scope a candidate to the known project keys at read time.
 *
 * The token rule: a project part `[A-Za-z][A-Za-z0-9_]{1,9}`, a hyphen, a number `[1-9][0-9]{0,6}` (no leading zero, so `FLO-012` is
 * no key), bounded on BOTH sides by a non-alphanumeric character or the text's end. So `[FLO-12]`, `FLO-12:`, `(FLO-12)` and
 * `feature/flo-12-fix` name `FLO-12`, while `FLO-12a` and `2FLO-12` name nothing (a letter before the key extends the project part:
 * `xFLO-12` names `XFLO-12`). Only ASCII letters and digits count as alphanumeric: a non-ASCII letter next to a key is a boundary.
 *
 * **The underscore is a boundary character.** `flo_12` has no hyphen and names nothing; `FLO-12_fix` names `FLO-12`; and a key preceded
 * by `_` (`fix_FLO-12`, `feat_flo-12-x`) is found too. But Jira project keys may contain `_` themselves (`MY_PROJ-1`,
 * `DataSource.PROJECT_KEY_PATTERN`), so the token keeps its inner underscores AND each underscore-delimited suffix is a candidate of its
 * own: `fix_FLO-12` yields `FIX_FLO-12` and `FLO-12`, `MY_PROJ-1` yields `MY_PROJ-1` and `PROJ-1`. A suffix is a candidate only where it
 * is itself a valid project part (a letter first, 2-10 characters), so `A_B-1` yields only `A_B-1`. The read-time scope keeps whichever
 * is a known project.
 *
 * Keys are folded to UPPERCASE (branches are lowercased by tooling), at most [ISSUE_KEY_MAX_LENGTH] characters (the `issue_key`
 * column width), distinct per merge request: a key in the title AND the branch is ONE candidate with both flags. The TARGET branch is
 * never read: it names where code goes, not what it is for.
 */
fun extractIssueKeyCandidates(title: String?, sourceBranch: String?): List<IssueKeyCandidate> {
    val inTitle = keysIn(title)
    val inBranch = keysIn(sourceBranch)
    return (inTitle + inBranch).sorted().map { IssueKeyCandidate(it, it in inTitle, it in inBranch) }
}

/** One key a merge request names: the folded [issueKey] and where it was found ([inTitle], [inBranch]; at least one is true). */
data class IssueKeyCandidate(val issueKey: String, val inTitle: Boolean, val inBranch: Boolean)

/** The width of `norm.code_mr_issue_keys.issue_key` (the Jira side's `norm.work_items.issue_key`, V13). */
const val ISSUE_KEY_MAX_LENGTH = 20

private val ISSUE_KEY_TOKEN = Regex("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9_]{1,9})-([1-9][0-9]{0,6})(?![A-Za-z0-9])")

private val PROJECT_PART = Regex("[A-Z][A-Z0-9_]{1,9}")

private fun keysIn(text: String?): Set<String> {
    if (text.isNullOrEmpty()) return emptySet()
    val keys = HashSet<String>()
    for (match in ISSUE_KEY_TOKEN.findAll(text)) {
        val project = match.groupValues[1].uppercase()
        val number = match.groupValues[2]
        keys += "$project-$number"
        // Each underscore-delimited suffix of the project part is a candidate too (the underscore is a boundary).
        var cut = project.indexOf('_')
        while (cut >= 0) {
            val suffix = project.substring(cut + 1)
            if (PROJECT_PART.matches(suffix)) keys += "$suffix-$number"
            cut = project.indexOf('_', cut + 1)
        }
    }
    return keys.filterTo(HashSet()) { it.length <= ISSUE_KEY_MAX_LENGTH }
}

/** mirrors `ingest/DataSource.kt`'s `PROJECT_KEY_PATTERN` plus the number: a full Jira issue key. */
private val ISSUE_KEY_PROJECT_PATTERN = Regex("^([A-Z][A-Z0-9_]{1,9})-\\d+$")

/**
 * The project-key prefix of a full issue key (e.g. `"OPS-9001"` -> `"OPS"`); `null` for a malformed/missing key. The ONE helper: the
 * DERIVE project-key timeline (`metrics/`) and the reports' known-project scope (`reports/IssueKeyScope.kt`) both read it.
 */
fun projectKeyFromIssueKey(issueKey: String?): String? = issueKey?.let { ISSUE_KEY_PROJECT_PATTERN.find(it)?.groupValues?.get(1) }
