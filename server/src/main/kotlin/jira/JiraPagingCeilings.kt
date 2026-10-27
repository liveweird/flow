package ch.nokillswit.jira

import ch.nokillswit.infra.json.sha256Hex

// The paging ceilings of the loops that follow a host's paging (`.claude/docs/jira-integration.md` "Paging ceilings"): what a
// compromised or buggy host behind the allow-list can make a stream fetch, hold or write, bounded the way WORKLOGS' and CHANGELOGS'
// answers are (PageCeiling, BoundedAnswer, RunBudget). Constants, not configuration: they are sized against what a real tenant
// has with wide headroom, never against `jira.maxResponseBytes` or a page size, and a trip is a typed `LIMIT_EXCEEDED`
// (`answer ceiling: ...`) with the cursor at the last committed page, visible on the data-source status page.

/** The endpoints as named in a failure (never a URL). */
internal const val SEARCH_JQL_ENDPOINT = "/rest/api/3/search/jql"
internal const val ISSUE_ENDPOINT = "/rest/api/3/issue/{id}"
internal const val BOARDS_ENDPOINT = "/rest/agile/1.0/board"
internal const val BOARD_SPRINTS_ENDPOINT = "/rest/agile/1.0/board/{id}/sprint"
internal const val USERS_SEARCH_ENDPOINT = "/rest/api/3/users/search"

/** The `startAt`-paged REFERENCE steps' endpoints (STATUS, PROJECT, RESOLUTION, BOARD), by the step that pages them. */
internal val START_AT_ENDPOINTS: Map<JiraEntityKind, String> = mapOf(
    JiraEntityKind.STATUS to "/rest/api/3/statuses/search",
    JiraEntityKind.PROJECT to "/rest/api/3/project/search",
    JiraEntityKind.RESOLUTION to "/rest/api/3/resolution/search",
    JiraEntityKind.BOARD to BOARDS_ENDPOINT,
)

/**
 * The REFERENCE stream's figures (an injectable constructor parameter, so a test can use small ones). The paged steps write every page
 * as it arrives and hold nothing but the page, so each is a [RunCeiling] on its pages AND its rows: a page's rows are otherwise bounded
 * only by `jira.maxResponseBytes` (8 MiB is ~760k minimal entities). The real dev database holds ~1k projects, ~1.9k statuses, ~16k
 * users (50 a page: ~330 pages; an earlier measure of the real tenant said ~2,300), 14 boards and ~0.9k sprints.
 */
internal data class ReferenceCeilings(
    /** ONE STATUS/PROJECT/RESOLUTION/BOARD step: 500 pages and 100,000 entities (the largest real step is ~22 pages, ~1.9k rows). */
    val startAt: RunCeiling = START_AT_CEILING,
    /** The USER step: 4,000 pages and 250,000 users (the real ~16k are ~330 pages; an all-staff site grows into the thousands). */
    val users: RunCeiling = USERS_CEILING,
    /**
     * ALL boards' sprints of one SPRINT pass, across boards (a per-board ceiling multiplied by 5,000 boards would be 1M requests):
     * 10,000 requests and 50,000 sprints (real: ~14 boards, ~0.9k sprints, about 30 requests).
     */
    val sprints: RunCeiling = SPRINTS_CEILING,
    /** The PROJECT_FIELDS fetch of one project. */
    val projectFields: ProjectFieldsCeilings = ProjectFieldsCeilings(),
    /** The in-scope boards `fetchInScopeBoards` holds (twice per pass): 5,000 boards within 4 Mi characters, over 500 pages. */
    val boards: AnswerCeiling = BOARDS_ANSWER_CEILING,
)

internal val START_AT_CEILING = RunCeiling(maxPages = 500, maxItems = 100_000)
internal val USERS_CEILING = RunCeiling(maxPages = 4_000, maxItems = 250_000)
internal val SPRINTS_CEILING = RunCeiling(maxPages = 10_000, maxItems = 50_000)
internal val BOARDS_ANSWER_CEILING = AnswerCeiling(maxChars = 4L * 1024 * 1024, maxItems = 5_000, maxPages = 500)

/**
 * ONE project's `projects/fields` fetch: at most [maxWorkTypes] work-type ids (taken from the stored `PROJECT_STATUSES` payload, so the
 * 25-per-request groups and their requests are bounded), [maxPagesPerGroup] pages of one group, [maxRows] (field, work type) rows in all
 * (each folded into the per-type sets, so this bounds what the collector holds) and [maxFieldIds] distinct field ids. The real site has
 * ~9.7k fields in all; a project's scheme is ~150 of them over ~20 work types (~3k rows). Past any: `LIMIT_EXCEEDED`, which skips
 * the project.
 */
internal data class ProjectFieldsCeilings(
    val maxPagesPerGroup: Int = 500,
    val maxWorkTypes: Int = 1_000,
    val maxRows: Int = 500_000,
    val maxFieldIds: Int = 50_000,
)

/**
 * ONE ISSUES run (an invocation: a resumed run counts from its resume, each run starts the count again): 10,000 pages and 500,000
 * issues. A full backfill of the production target (~10 projects x ~6.4k issues = ~64k) is ~640 pages at the default page size of
 * 100 (a project alone ~64), so the pages carry ~15x headroom and the issues ~8x; both outlast a page size halved a few times by
 * the response cap. The issue ceiling is also the most `raw.jira_issues` rows a run can add. Past either the job fails
 * `LIMIT_EXCEEDED` BEFORE the page's transaction: the cursor stays on the token that asked the refused page, and the next run resumes
 * there, so a legitimately larger scope still completes, one ceiling's worth a run.
 */
internal val ISSUES_RUN_CEILING = RunCeiling(maxPages = 10_000, maxItems = 500_000)

/**
 * ONE RECONCILE pass: 500 pages of up to 5,000 ids and the same 500,000 ids as ISSUES (so a scope too big for one is too big for
 * both): the production target is ~13 pages, and 500 still holds 500k ids if Jira caps an id-only page at 1,000. The ids are the
 * rows of `raw.jira_reconcile_seen`, which the next pass drains.
 */
internal val RECONCILE_RUN_CEILING = RunCeiling(maxPages = 500, maxItems = 500_000)

/** The longest `nextPageToken` accepted: real ones are a few hundred characters; one goes back in a URL and into the cursor. */
internal const val MAX_PAGE_TOKEN_CHARS = 8_192

/**
 * Refuses a `nextPageToken` that cannot be a progressing cursor, BEFORE the page it came with is stored or the token persisted
 * (`INVALID_RESPONSE`, constant text): longer than [MAX_PAGE_TOKEN_CHARS], or one the host already sent in this run (the page
 * chain cycles, so the same pages would be re-fetched until a ceiling). Remembers a SHA-256 of each token, not the token; [resumed]
 * is the token a resumed run starts from. One guard per run, never shared; a token belongs to ONE query, so [restart] (the query was
 * replaced after a `CURSOR_EXPIRED`) forgets them - the run's page and item ceilings keep counting across it.
 */
internal class PageTokenGuard(private val endpoint: String, resumed: String? = null) {
    private val seen = HashSet<String>()

    init {
        resumed?.let { seen += sha256Hex(it) }
    }

    fun restart() = seen.clear()

    fun accept(token: String?) {
        if (token == null) return
        if (token.length > MAX_PAGE_TOKEN_CHARS) {
            throw JiraFetchException("INVALID_RESPONSE", null, endpoint, "a page token longer than $MAX_PAGE_TOKEN_CHARS characters")
        }
        if (!seen.add(sha256Hex(token))) {
            throw JiraFetchException("INVALID_RESPONSE", null, endpoint, "a page token the host already sent in this run")
        }
    }
}
