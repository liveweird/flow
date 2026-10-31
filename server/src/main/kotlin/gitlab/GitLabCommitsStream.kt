package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunBudget
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabCommitsStream::class.java)

private const val COMMITS_ENDPOINT = "/api/v4/projects/{id}/repository/commits"
private const val NOT_FOUND = "NOT_FOUND"
private const val MAX_LOGGED_IDS = 20

/**
 * The COMMITS stream (`gitlab-integration.md` "Streams"): per live project in id order, the commits of the DEFAULT branch into
 * `raw.gitlab_commits`. No hash: a commit is immutable by SHA. What a project needs is decided once per run from at most two cheap
 * reads, with no write:
 *  - no stored head: **FULL**, `ref_name=<branch>&since=<backfillFrom>`;
 *  - a re-list was requested: **RELIST**;
 *  - otherwise **CHECK**: page 1 of the branch; its first commit is the tip. Tip == stored head: nothing to do. Else `merge_base(head,
 *    branch)`: equal to the head means the history only grew, a **RANGE** walk of `<head>..<branch>` (it also catches commits merged
 *    in with old dates, which a `since` cursor would miss); anything else, or a head GitLab no longer knows (`REF_NOT_FOUND`), is a
 *    force-push: the request is recorded (`relist_requested`) and the project is **RELISTED**.
 * A renamed default branch needs no special case: the head is a SHA.
 *
 * A RELIST lists the branch from `since` = the oldest live stored commit's date (not the backfill floor, which moves forward daily and
 * would sweep the commits between the old and the new floor); its sweep ([sweep]) tombstones the commits that left the branch, each
 * CONFIRMED against it first. Every listing page commits its rows and the cursor together; the head moves (and a re-list request is
 * cleared) with the last page of a FULL or RANGE walk and with the sweep of a RELIST, so a run cut anywhere resumes where it stopped
 * and never records a head over commits it has not stored. A project without a default branch, or whose branch or
 * project answers `NOT_FOUND`, is skipped (counted, one WARN naming ids); PROJECTS will rename it or RECONCILE (PR 9) delete it.
 *
 * Progress: `pages` (listing pages), `commits` (inserted), `commitsTombstoned`, `commitsRelisted`, `commitsSweepKept`,
 * `commitsSweepUnconfirmed`, `commitsSweepDeferred` (candidates beyond the per-run cap), `commitsProjectsSkipped`.
 */
internal class GitLabCommitsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val backfillFromMillis: Long,
    private val ceilings: GitLabCeilings = GitLabCeilings(),
) : Stream {
    override val name: String = "commits"

    private class Pass(val startedAt: Long, var lastCompleted: Long?)

    private sealed interface Decision {
        data object Unchanged : Decision

        data object Skip : Decision

        class Walk(val cursor: CommitsWalkCursor) : Decision
    }

    override suspend fun run(context: StreamContext) {
        val resumed = context.cursor(name)?.cursor?.let { decodeCursor<CommitsCursor>(it) }
        val pass = Pass(resumed?.passStartedAt ?: context.clock(), resumed?.lastCompletedProjectId)
        val skippedIds = mutableListOf<Long>()
        val projects = rawStore.liveProjectsForCommits(context.connectionId).filter { project ->
            pass.lastCompleted?.let { project.projectId > it } ?: true
        }
        for (project in projects) {
            // A walk saved for another branch (the default branch was renamed mid-pass) names a listing that no longer exists: drop it.
            val saved = resumed?.walk?.takeIf { it.projectId == project.projectId && it.branch == project.defaultBranch }
            when (val decision = if (saved != null) Decision.Walk(saved) else decide(context, project)) {
                Decision.Unchanged -> Unit
                Decision.Skip -> {
                    skippedIds += project.projectId
                    context.incrementProgress("commitsProjectsSkipped")
                }
                is Decision.Walk -> walk(context, pass, decision.cursor)
            }
            context.heartbeat(changed = false) // a project that wrote nothing still checks the lease and the clock
        }
        if (skippedIds.isNotEmpty()) {
            log.warn(
                "GitLab commits skipped (no default branch, or the branch or project is gone): {} (ids {})",
                skippedIds.size, skippedIds.take(MAX_LOGGED_IDS),
            )
        }
        context.transaction { context.clearCursor(name) }
    }

    /** What [project] needs this run, from at most a page and a merge-base; writes nothing except the re-list request of a force-push. */
    private suspend fun decide(context: StreamContext, project: CommitsProjectRow): Decision {
        val branch = project.defaultBranch ?: return Decision.Skip
        val id = project.projectId
        val head = project.headSha ?: return Decision.Walk(fullWalk(id, branch))
        if (project.relistRequested) return Decision.Walk(relistWalk(context.connectionId, id, branch))
        return check(context, id, branch, head)
    }

    /** CHECK: the branch tip against the stored [head]; a RANGE when the history only grew, else a RELIST. */
    private suspend fun check(context: StreamContext, id: Long, branch: String, head: String): Decision {
        val tip = try {
            client.commits(id, branch, null, 1).commits.firstOrNull()?.sha
        } catch (gone: FetchException) {
            if (gone.code == NOT_FOUND) null else throw gone
        }
        if (tip == null) return Decision.Skip
        if (tip == head) return Decision.Unchanged
        val base = try {
            client.mergeBase(id, head, branch)
        } catch (unknown: FetchException) {
            if (unknown.code == REF_NOT_FOUND) null else throw unknown
        }
        if (base == head) return Decision.Walk(CommitsWalkCursor(id, WalkMode.RANGE, "$head..$branch"))
        context.transaction { rawStore.requestRelist(context.connectionId, id) }
        return Decision.Walk(relistWalk(context.connectionId, id, branch))
    }

    private fun fullWalk(projectId: Long, branch: String) =
        CommitsWalkCursor(projectId, WalkMode.FULL, branch, since = isoInstant(backfillFromMillis), sinceMillis = backfillFromMillis)

    private suspend fun relistWalk(connectionId: UInt, projectId: Long, branch: String): CommitsWalkCursor {
        val sinceMillis = rawStore.oldestLiveCommittedAt(connectionId, projectId) ?: backfillFromMillis
        return CommitsWalkCursor(projectId, WalkMode.RELIST, branch, since = isoInstant(sinceMillis), sinceMillis = sinceMillis)
    }

    /**
     * Walks one project: the listing, page by page, then (a RELIST only) the sweep. A saved walk whose listing is done goes straight to
     * the sweep.
     */
    private suspend fun walk(context: StreamContext, pass: Pass, start: CommitsWalkCursor) {
        val listed = if (start.listingDone) start else list(context, pass, start)
        if (listed.mode == WalkMode.RELIST) sweep(context, pass, listed)
        pass.lastCompleted = listed.projectId
    }

    /**
     * Commits each listing page with the cursor. The last page of a FULL or RANGE walk also moves the head and moves the cursor past
     * the project; a RELIST's last page only marks the listing done (the sweep needs calls, which never run inside a transaction).
     * Returns the cursor as it ended (a RANGE that turned into a RELIST returns the RELIST's).
     */
    private suspend fun list(context: StreamContext, pass: Pass, start: CommitsWalkCursor): CommitsWalkCursor {
        var cursor = start
        val budget = RunBudget(GITLAB_SOURCE, COMMITS_ENDPOINT, ceilings.commits, "commits")
        while (true) {
            budget.nextPage()
            val answer = try {
                client.commits(cursor.projectId, cursor.refName, cursor.since, cursor.page)
            } catch (gone: FetchException) {
                // The stored head is unknown to GitLab: the history was rewritten under it. Re-list this project in the same run.
                if (gone.code != NOT_FOUND || cursor.mode != WalkMode.RANGE) throw gone
                context.transaction { rawStore.requestRelist(context.connectionId, cursor.projectId) }
                cursor = relistWalk(context.connectionId, cursor.projectId, cursor.branch)
                continue
            }
            budget.add(answer.commits.size)
            val tip = cursor.tipSha ?: answer.commits.firstOrNull()?.sha
            val next = answer.nextPage
            val position = if (next != null) cursor.copy(page = next, tipSha = tip) else cursor.copy(tipSha = tip, listingDone = true)
            val changed = context.transaction {
                val rows = answer.commits.map(::rawCommit)
                val outcomes = rawStore.upsertCommits(context.connectionId, cursor.projectId, rows, context.clock())
                context.incrementProgress("pages")
                val inserted = outcomes.count { it == RawUpsertOutcome.INSERTED }
                if (inserted > 0) context.incrementProgress("commits", inserted.toLong())
                when {
                    next != null || cursor.mode == WalkMode.RELIST ->
                        context.putCursor(name, encodeCursor(CommitsCursor(pass.startedAt, pass.lastCompleted, position)))
                    else -> {
                        if (tip != null) rawStore.recordCommitsHead(context.connectionId, cursor.projectId, tip, relistRequested = false)
                        context.putCursor(name, encodeCursor(CommitsCursor(pass.startedAt, cursor.projectId, null)))
                    }
                }
                outcomes.any { it.isChange }
            }
            context.heartbeat(changed)
            if (next == null) return position
            cursor = position
        }
    }

    private enum class Verdict { ON_BRANCH, OFF_BRANCH, UNCONFIRMED }

    /**
     * The end of a RELIST. GitLab's `since` is git's `--since`, which does not filter: it STOPS walking once everything queued is older
     * than the date, and the parents of an older-dated commit are then not listed at all. A commit with a skewed date (committed
     * before its parent) can therefore be missing from the listing while still on the branch, so "not listed" is only a CANDIDATE:
     * the project's live commits at or after `since` that this pass did not see are each confirmed with `merge_base(commit, branch)`.
     * The commit itself means it is still an ancestor of the tip (kept, `last_seen_at` touched); another commit, or `REF_NOT_FOUND`,
     * means it left the branch (tombstoned - a rewritten-away commit usually still exists in the repository, so its merge base is
     * the fork point); any other failure keeps it, counts it and leaves the re-list request set. At most
     * [MAX_SWEEP_CONFIRMATIONS] are confirmed per run; if more candidates remain the request stays set and the next run goes on. The
     * calls run outside a transaction; the verdicts, the new head and the cursor land in one.
     */
    private suspend fun sweep(context: StreamContext, pass: Pass, cursor: CommitsWalkCursor) {
        val since = checkNotNull(cursor.sinceMillis) { "a RELIST walk always carries its since" }
        val candidates =
            rawStore.staleCommitShas(context.connectionId, cursor.projectId, since, pass.startedAt, MAX_SWEEP_CONFIRMATIONS + 1)
        val kept = mutableListOf<String>()
        val gone = mutableListOf<String>()
        var unconfirmed = 0
        for (sha in candidates.take(MAX_SWEEP_CONFIRMATIONS)) {
            when (verdict(cursor, sha)) {
                Verdict.ON_BRANCH -> kept += sha
                Verdict.OFF_BRANCH -> gone += sha
                Verdict.UNCONFIRMED -> unconfirmed++
            }
            context.heartbeat(changed = false)
        }
        val moreToDo = candidates.size > MAX_SWEEP_CONFIRMATIONS || unconfirmed > 0
        val tombstoned = context.transaction {
            val now = context.clock()
            rawStore.touchCommits(context.connectionId, cursor.projectId, kept, now)
            val count = rawStore.tombstoneCommits(context.connectionId, cursor.projectId, gone, now)
            if (cursor.tipSha != null) rawStore.recordCommitsHead(context.connectionId, cursor.projectId, cursor.tipSha, moreToDo)
            context.incrementProgress("commitsRelisted")
            if (count > 0) context.incrementProgress("commitsTombstoned", count.toLong())
            if (kept.isNotEmpty()) context.incrementProgress("commitsSweepKept", kept.size.toLong())
            if (unconfirmed > 0) context.incrementProgress("commitsSweepUnconfirmed", unconfirmed.toLong())
            if (candidates.size > MAX_SWEEP_CONFIRMATIONS) context.incrementProgress("commitsSweepDeferred")
            context.putCursor(name, encodeCursor(CommitsCursor(pass.startedAt, cursor.projectId, null)))
            count
        }
        context.heartbeat(changed = tombstoned > 0 || kept.isNotEmpty())
    }

    private suspend fun verdict(cursor: CommitsWalkCursor, sha: String): Verdict = try {
        if (client.mergeBase(cursor.projectId, sha, cursor.branch) == sha) Verdict.ON_BRANCH else Verdict.OFF_BRANCH
    } catch (failure: FetchException) {
        if (failure.code == REF_NOT_FOUND) Verdict.OFF_BRANCH else Verdict.UNCONFIRMED
    }

    private fun rawCommit(commit: GitLabCommitSummary) = RawGitLabCommit(
        commit.sha, commit.title, commit.authorName, commit.authorEmail, commit.authoredAt, commit.committedAt, commit.parentCount,
        commit.additions, commit.deletions,
    )
}
