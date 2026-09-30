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

    /** One projects-plus-window term of a multi-clause incremental query: [projectKeys] updated within the last [sinceMinutes]. */
    data class Clause(val projectKeys: List<String>, val sinceMinutes: Long)

    private fun clauseText(projectKeys: List<String>, sinceMinutes: Long): String {
        require(sinceMinutes >= 0) { "sinceMinutes must not be negative" }
        return "${scope(projectKeys)} AND updated >= \"-${sinceMinutes}m\""
    }

    /** The incremental ISSUES stream query: scope AND a relative `updated` bound, oldest first. */
    fun incremental(projectKeys: List<String>, sinceMinutes: Long): String =
        "${clauseText(projectKeys, sinceMinutes)} ORDER BY updated ASC"

    /**
     * The incremental ISSUES query when different projects need different windows (a scope catch-up:
     * the added projects reach back to `backfillFrom`, the retained ones only to the watermark):
     * `(<scope A> AND updated >= "-Na") OR (<scope B> AND updated >= "-Nb") ORDER BY updated ASC`. A
     * single clause is rendered EXACTLY as the two-argument [incremental] (no parentheses), so an
     * unchanged scope sends today's query text.
     */
    fun incremental(clauses: List<Clause>): String {
        require(clauses.isNotEmpty()) { "clauses must not be empty" }
        if (clauses.size == 1) return incremental(clauses.single().projectKeys, clauses.single().sinceMinutes)
        return clauses.joinToString(" OR ", postfix = " ORDER BY updated ASC") { "(${clauseText(it.projectKeys, it.sinceMinutes)})" }
    }

    /**
     * The RECONCILE id-sweep query: scope AND a relative `updated` bound reaching back to the
     * connection's `backfillFrom` (the same window the ISSUES stream's first run covered), ordered
     * by id so paging is stable and resumable. Without the bound the sweep would list issues older
     * than anything Flow ever fetched, and every one of them would be fetched one by one as an
     * "index gap".
     */
    fun reconcile(projectKeys: List<String>, sinceMinutes: Long): String {
        require(sinceMinutes >= 0) { "sinceMinutes must not be negative" }
        return "${scope(projectKeys)} AND updated >= \"-${sinceMinutes}m\" ORDER BY id ASC"
    }
}
