package ch.nokillswit.gitlab

import ch.nokillswit.fetch.RunCeiling
import ch.nokillswit.infra.time.MILLIS_PER_DAY

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
    /** Per configured GROUP per run: the merge requests one listing walks (100 per page); the cursor resumes a longer first backfill. */
    val mergeRequests: RunCeiling = RunCeiling(maxPages = 1_000, maxItems = 100_000),
)

/** The most confirmed secondary emails kept per user (`/users/:id/emails`, first page). */
internal const val MAX_EMAILS_PER_USER = 100

/** A commit's `/diff` is read at most this many files deep (10 pages of 100); an 11th page makes the commit `files_capped`. */
internal const val MAX_COMMIT_FILES = 1_000

/** The COMMIT FILES flush thresholds: the fetched commits (and their file rows) are written in one transaction at either bound. */
internal const val COMMIT_FILES_FLUSH_COMMITS = 25
internal const val COMMIT_FILES_FLUSH_ROWS = 5_000

/**
 * The most re-list sweep candidates one PROJECT confirms per run (one `merge_base` call each, `gitlab-integration.md` "Streams"; the cap is
 * per project, so a RECONCILE run's real bound is projects x about 103 `merge_base` calls - the cap, the probe and its repeat). More
 * than this and the rest waits for the next run: the project keeps its re-list request AND its epoch (`relist_started_at`). A candidate
 * is "not seen since the epoch", and every candidate a run handles - kept or unconfirmed (touched, so seen) or tombstoned - leaves that
 * set for good, so each further run sees strictly fewer candidates and repeated runs converge.
 */
internal const val MAX_SWEEP_CONFIRMATIONS = 100

/**
 * How far below the backfill floor RECONCILE's `since`-less commits walk goes before it stops: the listing ends after a page whose
 * commits are ALL older than the floor minus this. A commit whose date is skewed to before the floor sits, in git's date-ordered walk,
 * directly before its in-window parents, so the slack only has to cover a child dated before the floor - minutes to hours in practice;
 * two weeks is the margin. The residual (a child skewed by more than that and sitting last on a page) is on the PR 12 smoke list.
 */
internal const val RECONCILE_COMMITS_SLACK_MILLIS = 14 * MILLIS_PER_DAY

/**
 * The most moved-out projects one RECONCILE asks `GET /projects/:id` about. The set is the projects PROJECTS marked moved out and no
 * reconcile has deleted yet - a handful in practice (a project renamed out of the groups, one deleted); 500 is a bound a runaway pass
 * cannot pass, and anything beyond it waits for the next day.
 */
internal const val MAX_PROJECT_PROBES = 500

/**
 * The most merge requests one RECONCILE confirms with `GET /projects/:id/merge_requests/:iid` (one call each, plus two project probes
 * per project). Candidates are rare - a deleted merge request, a page skipped mid-pass, a project that moved minutes ago - so 200 is
 * generous; more are deferred (`mrReconcileDeferred`) and are candidates again the next day if they are still unlisted.
 */
internal const val MAX_MR_CONFIRMATIONS = 200

/**
 * The most GraphQL calls RECONCILE's stats re-ask makes (50 merge requests each, so 5,000 gaps): after a day-long GraphQL outage the
 * whole backlog of gaps is one pass away, and a permanent gap costs one call per project per day.
 */
internal const val RECONCILE_STATS_CALLS = 100

/**
 * The most `details_error` stamps RECONCILE un-stamps per run. The MR DETAILS skip budget fails a SYNC once more than `max(10, 10 %)` of a
 * run's terminal reads fail, and it counts the retried merge requests together with the fresh failures of the same run: five is half the
 * floor, so five that fail again plus up to five new ones can never trip it. An unbounded un-stamp of permanently failing merge requests
 * would.
 */
internal const val RECONCILE_DETAILS_RETRIES = 5

/**
 * How many project-scoped failures in a row ([PROJECT_SCOPED_FAILURE_CODES]) one RECONCILE stream skips before it rethrows the last one:
 * one broken project is skipped, three in a row read as an outage and fail the run (it backs off like any outage).
 */
internal const val RECONCILE_CONSECUTIVE_FAILURES = 3

/** The pending-commit batch the COMMIT FILES stream claims from the backlog at a time. */
internal const val COMMIT_FILES_BATCH = 50

/** A merge request's notes are read at most this many deep (10 pages of 100); an 11th page makes the merge request `notes_capped`. */
internal const val MAX_MR_NOTES = 1_000

/** The pages of a merge request's notes that fit under [MAX_MR_NOTES]. */
internal const val MR_NOTES_PAGES = MAX_MR_NOTES / GITLAB_PAGE_SIZE

/**
 * How far before a group's watermark (GitLab's own newest `updated_at` of the last completed pass) the next listing starts: `updated_after`
 * is second-granular ISO text against an inclusive bound, and a merge request updated in the same second as the page that ended the pass
 * must not slip between two passes.
 */
internal const val MR_LIST_OVERLAP_MILLIS = 15 * 60_000L
