package ch.nokillswit.gitlab

/** The added and removed lines of one file's diff. */
data class LineCounts(val additions: Int, val deletions: Int)

/**
 * Counts the lines of one file's `diff` text (GitLab's `/commits/:sha/diff` entry): after the first line starting `@@`, a line starting
 * `+` is an addition and one starting `-` a deletion; the next hunk header (`@@`), a context line (` `), the `\ No newline at end of file`
 * marker, an empty line and everything else count nothing. Only the first character of a line matters (a trailing `\r` is harmless).
 *
 * GitLab sends the hunks only - no `--- a/x` / `+++ b/x` file header - so a line starting `---` or `+++` INSIDE a hunk is a removed or
 * added line whose own content begins `--`/`++` and counts. Anything before the first `@@` is skipped, so a file header, if a future
 * GitLab sent one, would not be counted either. A binary file's `Binary files ... differ` text has no hunk: 0/0, as has an empty diff.
 *
 * Runs over the string once without splitting it (no per-line allocation); the caller drops the text afterwards, so a diff is held for the
 * duration of one transform only.
 */
fun countHunkLines(diff: String): LineCounts {
    var additions = 0
    var deletions = 0
    var inHunk = false
    var start = 0
    while (start < diff.length) {
        val newline = diff.indexOf('\n', start)
        val end = if (newline < 0) diff.length else newline
        if (end > start) {
            val first = diff[start]
            if (!inHunk) {
                inHunk = first == '@' && diff.startsWith("@@", start)
            } else if (first == '+') {
                additions++
            } else if (first == '-') {
                deletions++
            }
        }
        start = end + 1
    }
    return LineCounts(additions, deletions)
}
