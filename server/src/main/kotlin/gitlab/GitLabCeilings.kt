package ch.nokillswit.gitlab

import ch.nokillswit.fetch.RunCeiling

/**
 * The paging ceilings of one GitLab SYNC run (`jira-integration.md` "Paging ceilings" for the idea), sized with wide headroom for the
 * ~70-developer unit and the same for every run: the pages followed and the rows written, per group for the projects. Past one the
 * stream fails `LIMIT_EXCEEDED` BEFORE the page's transaction, so a trip leaves the cursor at the last page that committed.
 */
internal data class GitLabCeilings(
    /** Per configured group: its projects (100 per page). */
    val projects: RunCeiling = RunCeiling(maxPages = 100, maxItems = 10_000),
    /** The instance's users (100 per page). */
    val users: RunCeiling = RunCeiling(maxPages = 500, maxItems = 50_000),
)

/** The most confirmed secondary emails kept per user (`/users/:id/emails`, first page). */
internal const val MAX_EMAILS_PER_USER = 100
