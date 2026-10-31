package ch.nokillswit.gitlab

import ch.nokillswit.fetch.RunCeiling

/**
 * The paging ceilings of one GitLab SYNC run (`jira-integration.md` "Paging ceilings" for the idea), sized with wide headroom for the
 * ~70-developer unit and the same for every run: the pages followed and the rows written, per group for the projects and per project
 * for the commits. Past one the
 * stream fails `LIMIT_EXCEEDED` BEFORE the page's transaction, so a trip leaves the cursor at the last page that committed.
 */
internal data class GitLabCeilings(
    /** Per configured group: its projects (100 per page). */
    val projects: RunCeiling = RunCeiling(maxPages = 100, maxItems = 10_000),
    /** The instance's users (100 per page). */
    val users: RunCeiling = RunCeiling(maxPages = 500, maxItems = 50_000),
    /** Per PROJECT per run: the commits one walk lists (100 per page); a first backfill of a huge repository drains over several runs. */
    val commits: RunCeiling = RunCeiling(maxPages = 500, maxItems = 50_000),
)

/** The most confirmed secondary emails kept per user (`/users/:id/emails`, first page). */
internal const val MAX_EMAILS_PER_USER = 100

/** A commit's `/diff` is read at most this many files deep (10 pages of 100); an 11th page makes the commit `files_capped`. */
internal const val MAX_COMMIT_FILES = 1_000

/** The COMMIT FILES flush thresholds: the fetched commits (and their file rows) are written in one transaction at either bound. */
internal const val COMMIT_FILES_FLUSH_COMMITS = 25
internal const val COMMIT_FILES_FLUSH_ROWS = 5_000

/**
 * The most re-list sweep candidates one project confirms per run (one `merge_base` call each, `gitlab-integration.md` "Streams"). More
 * than this and the rest waits for the next run: the project keeps its re-list request, and every confirmed candidate has left the
 * candidate set by then (touched or tombstoned), so repeated runs converge.
 */
internal const val MAX_SWEEP_CONFIRMATIONS = 100

/** The pending-commit batch the COMMIT FILES stream claims from the backlog at a time. */
internal const val COMMIT_FILES_BATCH = 50
