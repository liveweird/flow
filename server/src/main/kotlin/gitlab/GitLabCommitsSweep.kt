package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.StreamContext

private const val NOT_FOUND = "NOT_FOUND"

/** The tip of [branch] (page 1 without `since`), or null when the branch or project answers `NOT_FOUND`; any other failure propagates. */
internal suspend fun readBranchTip(client: GitLabClient, projectId: Long, branch: String): String? = try {
    client.commits(projectId, branch, null, 1).commits.firstOrNull()?.sha
} catch (gone: FetchException) {
    if (gone.code == NOT_FOUND) null else throw gone
}

/**
 * The end of a RELIST (`gitlab-integration.md` "Streams"). GitLab's `since` is git's `--since`, which does not filter: it STOPS walking
 * once everything queued is older than the date, and the parents of an older-dated commit are then not listed at all. A commit with a
 * skewed date (committed before its parent) can therefore be missing from the listing while still on the branch, so "not listed" is
 * only a CANDIDATE: the project's live commits at or after `since` that nothing has seen since the re-list EPOCH (`relist_started_at`,
 * set when the re-list began and kept until it ends, so a candidate kept in one run never re-qualifies in the next).
 *
 * Each candidate is confirmed against the SNAPSHOT TIP, `merge_base(commit, tip)`: the tip is the listing's first commit, or, when the
 * listing was empty, the branch's own tip read once (page 1 without `since`; it is recorded as the head too, so the next run is a plain
 * CHECK). The commit itself means it is still an ancestor (kept); another commit, or `REF_NOT_FOUND`, means it left the branch
 * (tombstoned - a rewritten-away commit usually still exists, so its merge base is the fork point). Nothing is believed unless the tip
 * is still on the branch: ONE probe, `merge_base(tip, branch) == tip`, before the batch, and - if any verdict was a `REF_NOT_FOUND` -
 * once more after it (a project lost mid-sweep answers `REF_NOT_FOUND` for the rest); a failed probe makes every such candidate
 * UNCONFIRMED. A probe that fails for another reason (rate limit, authentication) fails the run, and the saved `listingDone` cursor
 * resumes at the sweep. A tip that cannot be read at all, any other candidate failure and a failed probe make a candidate UNCONFIRMED:
 * kept and TOUCHED and counted, because keeping a gone commit live is the safe error (RECONCILE, PR 9, re-checks) and a persistently
 * failing candidate must not pin the project in RELIST.
 *
 * At most [MAX_SWEEP_CONFIRMATIONS] are confirmed per run; only when more candidates remain does the request (and the epoch) stay set
 * for the next run. The calls run outside a transaction; the verdicts, the head (kept when there is no tip) and the cursor land in one.
 */
internal class CommitsSweep(private val client: GitLabClient, private val rawStore: GitLabRawStore, private val streamName: String) {

    private enum class Verdict { ON_BRANCH, OFF_BRANCH, NOT_FOUND, UNCONFIRMED }

    suspend fun run(context: StreamContext, passStartedAt: Long, cursor: CommitsWalkCursor) {
        val connectionId = context.connectionId
        val projectId = cursor.projectId
        val since = checkNotNull(cursor.sinceMillis) { "a RELIST walk always carries its since" }
        val epoch = checkNotNull(cursor.epochMillis) { "a RELIST walk always carries its epoch" }
        val candidates = rawStore.staleCommitShas(connectionId, projectId, since, epoch, MAX_SWEEP_CONFIRMATIONS + 1)
        val batch = candidates.take(MAX_SWEEP_CONFIRMATIONS)
        val tip = cursor.tipSha ?: readBranchTip(client, projectId, cursor.branch)
        val trusted = batch.isEmpty() || (tip != null && tipOnBranch(projectId, tip, cursor.branch))
        val kept = mutableListOf<String>()
        val gone = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val unconfirmed = mutableListOf<String>()
        for (sha in batch) {
            when (if (trusted && tip != null) verdict(projectId, sha, tip) else Verdict.UNCONFIRMED) {
                Verdict.ON_BRANCH -> kept += sha
                Verdict.OFF_BRANCH -> gone += sha
                Verdict.NOT_FOUND -> missing += sha
                Verdict.UNCONFIRMED -> unconfirmed += sha
            }
            context.heartbeat(changed = false)
        }
        // The project may have been lost part-way through the batch: believe the `REF_NOT_FOUND`s only if the tip still resolves now.
        if (missing.isNotEmpty() && tip != null && tipOnBranch(projectId, tip, cursor.branch)) gone += missing else unconfirmed += missing
        val moreToDo = candidates.size > MAX_SWEEP_CONFIRMATIONS
        val tombstoned = context.transaction {
            val now = context.clock()
            rawStore.touchCommits(connectionId, projectId, kept + unconfirmed, now)
            val count = rawStore.tombstoneCommits(connectionId, projectId, gone, now)
            rawStore.finishRelist(connectionId, projectId, tip, moreToDo, epoch.takeIf { cursor.reconcile })
            context.incrementProgress("commitsRelisted")
            if (count > 0) context.incrementProgress("commitsTombstoned", count.toLong())
            if (kept.isNotEmpty()) context.incrementProgress("commitsSweepKept", kept.size.toLong())
            if (unconfirmed.isNotEmpty()) context.incrementProgress("commitsSweepUnconfirmed", unconfirmed.size.toLong())
            if (moreToDo) context.incrementProgress("commitsSweepDeferred")
            context.putCursor(streamName, encodeCursor(CommitsCursor(passStartedAt, projectId, null)))
            count
        }
        context.heartbeat(changed = tombstoned > 0 || kept.isNotEmpty())
    }

    /**
     * Whether [tip] is still an ancestor of [branch] (so the project and branch exist and nothing was force-pushed since). A
     * `REF_NOT_FOUND` or a different merge base is "no"; any other failure propagates.
     */
    private suspend fun tipOnBranch(projectId: Long, tip: String, branch: String): Boolean = try {
        client.mergeBase(projectId, tip, branch) == tip
    } catch (failure: FetchException) {
        if (failure.code == REF_NOT_FOUND) false else throw failure
    }

    private suspend fun verdict(projectId: Long, sha: String, tip: String): Verdict = try {
        if (client.mergeBase(projectId, sha, tip) == sha) Verdict.ON_BRANCH else Verdict.OFF_BRANCH
    } catch (failure: FetchException) {
        if (failure.code == REF_NOT_FOUND) Verdict.NOT_FOUND else Verdict.UNCONFIRMED
    }
}
