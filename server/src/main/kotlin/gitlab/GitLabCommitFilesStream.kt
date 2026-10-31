package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.SkipBudget
import ch.nokillswit.fetch.isOversized
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabCommitFilesStream::class.java)

private const val NOT_FOUND = "NOT_FOUND"

/** The pages of a commit's diff that fit under [MAX_COMMIT_FILES] (100 files a page). */
private const val COMMIT_FILES_PAGES = MAX_COMMIT_FILES / GITLAB_PAGE_SIZE

/**
 * The COMMIT FILES stream (`gitlab-integration.md` "Streams"): the per-file line counts of the stored commits, newest first. It drains
 * the backlog of live NON-MERGE commits whose diff was never read (a merge's diff is against its first parent, a double count: L4),
 * one `/commits/:sha/diff` chain at a time - sequential on purpose, a GitLab job holds ONE permit - spending at most `callsPerRun`
 * diff CALLS per run (`gitlab.commitFilesPerRun`; a commit's pages are calls). Reaching the budget is normal: the run ends, reports
 * `commitFilesPending` and the next run continues; the checks run between commits, so one commit may overshoot by up to nine calls.
 *
 * A commit's diff is read once (a SHA is immutable) and held in memory (at most [MAX_COMMIT_FILES] small rows; an 11th page makes the
 * commit `files_capped`), then written with its stamp when a flush fills ([COMMIT_FILES_FLUSH_COMMITS] commits or
 * [COMMIT_FILES_FLUSH_ROWS] rows), at the end of each claimed batch and on a failure: a run cut anywhere stores every complete
 * commit it fetched and never half of one. Terminal outcomes are stamped too, so the backlog drains: a diff page over
 * `gitlab.maxResponseBytes` or the node ceiling is `files_error = LIMIT_EXCEEDED` (`commitFilesOversized`), a commit GitLab no longer
 * has is `NOT_FOUND` (`commitFilesMissing`). More than `max(10, 10 %)` oversized commits in one run fail the run `LIMIT_EXCEEDED`
 * ([SkipBudget]: the cap is set too small, not isolated outliers) - and then NONE of this run's oversized commits are stamped, so a
 * mis-set cap does not strand the commits it happened to meet first. Any other failure ends the run after the complete commits are
 * flushed. Progress: `commitFiles`, `commitFileRows`, `commitFilesCapped`, `commitFilesOversized`, `commitFilesMissing`,
 * `commitFilesPending`.
 */
internal class GitLabCommitFilesStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val callsPerRun: Int,
) : Stream {
    override val name: String = "commit_files"

    /** The outcomes fetched and not yet written; oversized ones wait for the end of the run (see the class doc). */
    private class Fetched {
        val outcomes = mutableListOf<CommitFilesOutcome>()
        val oversized = mutableListOf<CommitFilesOutcome>()
        var rows = 0

        fun full() = outcomes.size >= COMMIT_FILES_FLUSH_COMMITS || rows >= COMMIT_FILES_FLUSH_ROWS
    }

    private var calls = 0

    override suspend fun run(context: StreamContext) {
        calls = 0
        val skips = SkipBudget(GITLAB_SOURCE, "COMMIT_FILES", "commits")
        val fetched = Fetched()
        try {
            drain(context, fetched, skips)
        } catch (cause: FetchException) {
            keepFetched(context, fetched, cause)
        } catch (cause: CeilingTripException) {
            keepFetched(context, fetched, cause) // the wall clock stopped the run: what was already fetched is stored first
        }
        flush(context, fetched, includeOversized = true)
        val pending = rawStore.countPendingCommitFiles(context.connectionId)
        if (pending > 0) context.incrementProgress("commitFilesPending", pending)
        context.heartbeat(changed = false)
    }

    private suspend fun drain(context: StreamContext, fetched: Fetched, skips: SkipBudget) {
        while (true) {
            // The oversized commits of this run wait unstamped (see the class doc), so they still read as pending: skip them here.
            val waiting = fetched.oversized.mapTo(HashSet()) { it.projectId to it.sha }
            val batch = rawStore.pendingCommitFiles(context.connectionId, COMMIT_FILES_BATCH + waiting.size)
                .filter { (it.projectId to it.sha) !in waiting }.take(COMMIT_FILES_BATCH)
            if (batch.isEmpty()) return
            for (commit in batch) {
                if (calls >= callsPerRun) return
                settle(context, commit, fetched, skips)
                if (fetched.full()) flush(context, fetched, includeOversized = false)
            }
            // Written before the next claim: a fetched commit still reads as pending until its stamp lands.
            flush(context, fetched, includeOversized = false)
        }
    }

    /** One commit's outcome: its files, or a terminal error; any other failure propagates to [run]. */
    private suspend fun settle(context: StreamContext, commit: PendingCommit, fetched: Fetched, skips: SkipBudget) {
        val result = try {
            fetchFiles(commit)
        } catch (failure: FetchException) {
            when {
                failure.isOversized() -> {
                    log.warn("GitLab commit diff over the response cap or a node ceiling, data source {}", context.connectionId)
                    fetched.oversized += CommitFilesOutcome(commit.projectId, commit.sha, null, FILES_ERROR_LIMIT_EXCEEDED)
                    context.incrementProgress("commitFilesOversized")
                    try {
                        skips.skip()
                    } catch (trip: FetchException) {
                        fetched.oversized.clear() // a mis-set cap: leave every oversized commit pending for after the fix
                        throw trip
                    }
                }
                failure.code == NOT_FOUND -> {
                    fetched.outcomes += CommitFilesOutcome(commit.projectId, commit.sha, null, FILES_ERROR_NOT_FOUND)
                    context.incrementProgress("commitFilesMissing")
                    skips.settled()
                }
                else -> throw failure
            }
            context.heartbeat(changed = false)
            return
        }
        fetched.outcomes += CommitFilesOutcome(commit.projectId, commit.sha, result, null)
        fetched.rows += result.files.size
        skips.settled()
        context.heartbeat(changed = false) // fetched, not yet flushed: the lease and the clock, not progress
    }

    /** One commit's files, page by page: [MAX_COMMIT_FILES] at most, `capped` when a further page exists. */
    private suspend fun fetchFiles(commit: PendingCommit): CommitFilesResult {
        val files = ArrayList<RawGitLabCommitFile>()
        var page = 1
        while (true) {
            calls++
            val answer = client.commitDiff(commit.projectId, commit.sha, page)
            files += answer.files
            val next = answer.nextPage
            if (next == null || page >= COMMIT_FILES_PAGES) {
                val capped = next != null || files.size > MAX_COMMIT_FILES
                return CommitFilesResult(files.take(MAX_COMMIT_FILES).mergedByPath(), capped)
            }
            page = next
        }
    }

    /** Flushes what was fetched before [cause] ended the run, then rethrows it; a failing flush must not hide [cause]. */
    private suspend fun keepFetched(context: StreamContext, fetched: Fetched, cause: Exception): Nothing {
        val flushFailure = catchingFailures<Exception?>(
            block = { flush(context, fetched, includeOversized = true); null },
            onFailure = { it },
        )
        // The clock's trip was typed before the flush; rebuilt after it, it knows the flushed commits were stored (progress).
        if (cause is CeilingTripException && flushFailure == null) throw context.ceilingTrip(cause.code, cause.underlying)
        flushFailure?.let(cause::addSuppressed)
        throw cause
    }

    /** Writes the fetched outcomes (the oversized ones too when [includeOversized]) in ONE transaction and empties them. */
    private suspend fun flush(context: StreamContext, fetched: Fetched, includeOversized: Boolean) {
        val outcomes = if (includeOversized) fetched.outcomes + fetched.oversized else fetched.outcomes.toList()
        if (outcomes.isEmpty()) return
        context.transaction { rawStore.flushCommitFiles(context.connectionId, outcomes, context.clock()) }
        val complete = outcomes.mapNotNull { it.result }
        context.incrementProgress("commitFiles", complete.size.toLong())
        context.incrementProgress("commitFileRows", complete.sumOf { it.files.size }.toLong())
        complete.count { it.capped }.takeIf { it > 0 }?.let { context.incrementProgress("commitFilesCapped", it.toLong()) }
        fetched.outcomes.clear()
        fetched.rows = 0
        if (includeOversized) fetched.oversized.clear()
        context.heartbeat(changed = true)
    }
}
