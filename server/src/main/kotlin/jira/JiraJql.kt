package ch.nokillswit.jira

import ch.nokillswit.ingest.PROJECT_KEY_PATTERN

/**
 * JQL construction (v0.2.0 plan §6/§7). Project keys are validated against the SAME
 * [PROJECT_KEY_PATTERN] `ingest/DataSource.kt` enforces at create/update time, so by the time a
 * key reaches here it is already `^[A-Z][A-Z0-9_]{1,9}$` — no quoting/escaping is ever needed
 * because the shape cannot contain a quote, backslash or whitespace. Absolute JQL dates are
 * TZ-sensitive (spike fact sheet); every date-bounded query here uses Jira's TZ-free RELATIVE
 * syntax (`"-Nm"`) computed from the watermark + overlap instead.
 */
object JiraJql {
    private fun requireValidKeys(projectKeys: List<String>) {
        require(projectKeys.isNotEmpty()) { "projectKeys must not be empty" }
        projectKeys.forEach {
            require(PROJECT_KEY_PATTERN.matches(it)) { "invalid project key for JQL: $it" }
        }
    }

    /** `project in ("A","B")` — the scope every other JQL builder here starts from. */
    fun scope(projectKeys: List<String>): String {
        requireValidKeys(projectKeys)
        return "project in (" + projectKeys.joinToString(",") { "\"$it\"" } + ")"
    }

    /** The incremental ISSUES stream query: scope AND a relative `updated` bound, oldest first. */
    fun incremental(projectKeys: List<String>, sinceMinutes: Long): String {
        require(sinceMinutes >= 0) { "sinceMinutes must not be negative" }
        return "${scope(projectKeys)} AND updated >= \"-${sinceMinutes}m\" ORDER BY updated ASC"
    }

    /** The RECONCILE id-sweep query: scope only, ordered by id so paging is stable and resumable. */
    fun reconcile(projectKeys: List<String>): String = "${scope(projectKeys)} ORDER BY id ASC"
}
