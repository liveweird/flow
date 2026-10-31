package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.SkipBudget
import ch.nokillswit.fetch.isOversized
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabMrDetailsStream::class.java)

private const val NOT_FOUND = "NOT_FOUND"

/**
 * The transport failures of the ONE GraphQL call that are a GAP, not a failure: the endpoint is down, slow, missing, refused to this token
 * or answered something unreadable or too big. Everything else - a rate limit, a rejected token, a blocked host - ends the run as it would
 * for any REST call (the diff stats are a nicety; the token and the rate budget are not).
 */
private val GRAPHQL_GAP_CODES = setOf(
    "UPSTREAM_UNAVAILABLE", "TIMEOUT", "INVALID_RESPONSE", NOT_FOUND, "LIMIT_EXCEEDED", "REDIRECT", "FORBIDDEN_SCOPE",
)

/**
 * The MR DETAILS stream (`gitlab-integration.md` "Streams"): the backlog of live merge requests (of live projects) whose details were
 * never read or are older than the merge request's last change, NEWEST `updated_at` first, one merge request at a time (sequential on
 * purpose: a GitLab job holds ONE permit and one parsed tree). A merge request's details are its notes (as action codes, at most
 * [MAX_MR_NOTES]; an 11th page makes it `notes_capped`), `/approvals`, `/reviewers` and its diff stats from ONE batched GraphQL call per
 * project and claimed batch ([MR_DETAILS_BATCH] merge requests, memoised for the batch). The events and the stamp land in ONE
 * transaction per merge request ([GitLabRawStore.settleMrDetails]), so a run cut anywhere (the lease, the clock, a failure) has
 * committed every merge request it finished and nothing of the one it was on; the notes are held as small summaries only while that one
 * merge request is read.
 *
 * Budget: at most `callsPerRun` REST + GraphQL CALLS per run (`gitlab.mrDetailsPerRun`); the check runs between merge requests, so one
 * may overshoot by up to twelve. Reaching it is a NORMAL stop: the run ends, reports `mrDetailsPending`, the next run continues.
 *
 * Outcomes that are not failures (each stamps the merge request so the backlog drains - nothing repeats every run):
 *  - the notes (any page) answer `NOT_FOUND`: `details_error = NOT_FOUND`, no events, `mrDetailsMissing`; NO tombstone (RECONCILE decides,
 *    PR 9); a re-listed, changed merge request clears the stamp;
 *  - `/approvals` or `/reviewers` alone answer `NOT_FOUND`: "none" for that merge request, `mrApprovalsUnavailable` /
 *    `mrReviewersUnavailable` (an instance lacking the endpoint must not mark every merge request gone);
 *  - a response over `gitlab.maxResponseBytes` or the node ceiling, on any of the three REST calls: `details_error = LIMIT_EXCEEDED`,
 *    `mrDetailsOversized`, stamped at the END of the run through [SkipBudget] (more than `max(10, 10 %)` of them fail the run
 *    `LIMIT_EXCEEDED` and NONE is stamped: the cap is too small, not isolated outliers). Until then they wait in memory and are excluded
 *    from this run's re-claims, which would otherwise return them forever;
 *  - the GraphQL call fails ([GRAPHQL_GAP_CODES]), answers errors, lacks the project or lacks a merge request: a NULL GAP for those merge
 *    requests (`mrDiffStatsGaps`, one WARN per run with the count and the first code), stamped complete and NOT asked again until the
 *    merge request changes.
 * Any other failure propagates after the oversized stamps are written.
 *
 * Progress: `mrDetails`, `mrEvents`, `mrNotesCapped`, `mrDetailsMissing`, `mrDetailsOversized`, `mrDiffStatsGaps`,
 * `mrApprovalsUnavailable`, `mrReviewersUnavailable`, `mrDetailsPending`.
 */
internal class GitLabMrDetailsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val callsPerRun: Int,
) : Stream {
    override val name: String = "mr_details"

    /** Everything one run keeps: the calls spent, the oversized merge requests waiting for their stamp, and the GraphQL trouble seen. */
    private class Session(val context: StreamContext) {
        val skips = SkipBudget(GITLAB_SOURCE, "MR_DETAILS", "merge requests")
        val oversized = mutableListOf<StaleMergeRequest>()
        var calls = 0
        var gaps = 0L
        var graphqlProblems = 0
        var firstGraphqlCode: String? = null
    }

    /** A claimed batch: its project paths (the GraphQL names a project by it), its iids per project and the answers asked so far. */
    private class Batch(val mrs: List<StaleMergeRequest>, val paths: Map<Long, String>) {
        val iidsByProject: Map<Long, List<Long>> = mrs.groupBy({ it.projectId }, { it.iid })
        val stats = HashMap<Long, Map<Long, GitLabDiffStats>>()
    }

    /** One merge request's notes: [capped] when a page beyond [MAX_MR_NOTES] exists. */
    private class Notes(val items: List<GitLabNoteSummary>, val capped: Boolean)

    override suspend fun run(context: StreamContext) {
        val session = Session(context)
        try {
            try {
                drain(session)
            } catch (cause: FetchException) {
                keepOversized(session, cause)
            } catch (cause: CeilingTripException) {
                keepOversized(session, cause) // the wall clock stopped the run: what was read is stored, the oversized ones stamped
            }
            stampOversized(session)
            val pending = rawStore.countStaleMergeRequests(context.connectionId)
            if (pending > 0) context.incrementProgress("mrDetailsPending", pending)
            context.heartbeat(changed = false)
        } finally {
            logGraphqlTrouble(session)
        }
    }

    private suspend fun drain(session: Session) {
        val connectionId = session.context.connectionId
        while (true) {
            // The oversized merge requests of this run wait unstamped, so they still read as stale: skip them here.
            val waiting = session.oversized.mapTo(HashSet()) { it.mrId }
            val claimed = rawStore.staleMergeRequests(connectionId, MR_DETAILS_BATCH + waiting.size)
                .filter { it.mrId !in waiting }.take(MR_DETAILS_BATCH)
            if (claimed.isEmpty()) return
            val batch = Batch(claimed, rawStore.projectPaths(connectionId, claimed.map { it.projectId }))
            for (mr in claimed) {
                if (session.calls >= callsPerRun) return
                settle(session, batch, mr)
            }
        }
    }

    /** One merge request's outcome: its details stored with their stamp, or a terminal stamp; any other failure propagates to [run]. */
    private suspend fun settle(session: Session, batch: Batch, mr: StaleMergeRequest) {
        val context = session.context
        val read = try {
            readEvents(session, mr)
        } catch (failure: FetchException) {
            val stored = when {
                failure.isOversized() -> deferOversized(session, mr)
                failure.code == NOT_FOUND -> markMissing(session, mr)
                else -> throw failure
            }
            context.heartbeat(changed = stored)
            return
        }
        // The calls are done before the transaction opens: nothing below waits on the network while a row is locked.
        val stats = diffStatsOf(session, batch, mr)
        val settled = rawStore.settleMrDetails(
            context.connectionId, mr.mrId, read.events, context.clock(), MrDetailsOutcome(notesCapped = read.capped, stats = stats),
        )
        session.skips.settled()
        if (settled) {
            context.incrementProgress("mrDetails")
            context.incrementProgress("mrEvents", read.events.size.toLong())
            if (read.capped) context.incrementProgress("mrNotesCapped")
        }
        context.heartbeat(changed = true)
    }

    private class Read(val events: List<RawGitLabMrEvent>, val capped: Boolean)

    private suspend fun readEvents(session: Session, mr: StaleMergeRequest): Read {
        val notes = fetchNotes(session, mr)
        val approvers = orNoneWhenMissing(session, "mrApprovalsUnavailable") { client.mergeRequestApprovals(mr.projectId, mr.iid) }
        val reviewers = orNoneWhenMissing(session, "mrReviewersUnavailable") { client.mergeRequestReviewers(mr.projectId, mr.iid) }
        return Read(buildMrEvents(notes.items, approvers.orEmpty(), reviewers.orEmpty()), notes.capped)
    }

    /** One merge request's notes, page by page: [MAX_MR_NOTES] at most, `capped` when a further page exists. */
    private suspend fun fetchNotes(session: Session, mr: StaleMergeRequest): Notes {
        val items = ArrayList<GitLabNoteSummary>()
        var page = 1
        while (true) {
            session.calls++
            val answer = client.mergeRequestNotes(mr.projectId, mr.iid, page)
            items += answer.notes
            val next = answer.nextPage
            if (next == null || page >= MR_NOTES_PAGES) return Notes(items.take(MAX_MR_NOTES), next != null || items.size > MAX_MR_NOTES)
            page = next
        }
    }

    /** [call]'s answer, or null when it answers `NOT_FOUND` (counted under [counter]): the endpoint, not the merge request, is missing. */
    private suspend fun <T> orNoneWhenMissing(session: Session, counter: String, call: suspend () -> T): T? {
        session.calls++
        return try {
            call()
        } catch (failure: FetchException) {
            if (failure.code != NOT_FOUND) throw failure
            session.context.incrementProgress(counter)
            null
        }
    }

    /** [mr]'s stats from the batch's answer for its project (asked on first need), or null - a gap, counted. */
    private suspend fun diffStatsOf(session: Session, batch: Batch, mr: StaleMergeRequest): GitLabDiffStats? {
        val answer = batch.stats[mr.projectId] ?: askDiffStats(session, batch, mr.projectId).also { batch.stats[mr.projectId] = it }
        val stats = answer[mr.iid]
        if (stats == null) {
            session.gaps++
            session.context.incrementProgress("mrDiffStatsGaps")
        }
        return stats
    }

    private suspend fun askDiffStats(session: Session, batch: Batch, projectId: Long): Map<Long, GitLabDiffStats> {
        val path = batch.paths[projectId] ?: return emptyMap()
        val iids = batch.iidsByProject.getValue(projectId)
        session.calls++
        return try {
            val answer = client.mergeRequestDiffStats(path, iids)
            if (answer.errors > 0 || answer.projectMissing) noteGraphqlProblem(session, answer.firstErrorCode)
            answer.stats
        } catch (failure: FetchException) {
            if (failure.code !in GRAPHQL_GAP_CODES) throw failure
            noteGraphqlProblem(session, failure.code)
            emptyMap()
        }
    }

    private fun noteGraphqlProblem(session: Session, code: String?) {
        session.graphqlProblems++
        if (session.firstGraphqlCode == null) session.firstGraphqlCode = code
    }

    /** [mr]'s read was over a ceiling: it waits for the END of the run's stamp; a mis-set cap (too many) fails the run and stamps none. */
    private fun deferOversized(session: Session, mr: StaleMergeRequest): Boolean {
        log.warn("GitLab merge-request details over the response cap or a node ceiling, data source {}", session.context.connectionId)
        session.oversized += mr
        session.context.incrementProgress("mrDetailsOversized")
        try {
            session.skips.skip()
        } catch (trip: FetchException) {
            session.oversized.clear() // a mis-set cap: leave every oversized merge request stale for after the fix
            throw trip
        }
        return false // nothing is stored until the end of the run
    }

    /** The notes answered `NOT_FOUND`: stamp the merge request (no events, no tombstone); true when the row was there to stamp. */
    private suspend fun markMissing(session: Session, mr: StaleMergeRequest): Boolean {
        val context = session.context
        val stamped = rawStore.settleMrDetails(
            context.connectionId, mr.mrId, emptyList(), context.clock(), MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND),
        )
        if (stamped) context.incrementProgress("mrDetailsMissing")
        session.skips.settled()
        return stamped
    }

    /** Stamps what was read before [cause] ended the run, then rethrows it; a failing stamp must not hide [cause]. */
    private suspend fun keepOversized(session: Session, cause: Exception): Nothing {
        val stampFailure = catchingFailures<Exception?>(block = { stampOversized(session); null }, onFailure = { it })
        // The clock's trip was typed before the stamps; rebuilt after them, it knows they were stored (progress).
        if (cause is CeilingTripException && stampFailure == null) throw session.context.ceilingTrip(cause.code, cause.underlying)
        stampFailure?.let(cause::addSuppressed)
        throw cause
    }

    /** Stamps every oversized merge request `LIMIT_EXCEEDED` in ONE transaction (their events and stats stay as they were), then clears. */
    private suspend fun stampOversized(session: Session) {
        if (session.oversized.isEmpty()) return
        val context = session.context
        context.transaction {
            val now = context.clock()
            session.oversized.forEach {
                rawStore.settleMrDetails(
                    context.connectionId, it.mrId, emptyList(), now, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED),
                )
            }
        }
        session.oversized.clear()
        context.heartbeat(changed = true)
    }

    private fun logGraphqlTrouble(session: Session) {
        if (session.gaps == 0L && session.graphqlProblems == 0) return
        log.warn(
            "GitLab diff stats unavailable for {} merge requests in data source {} ({} GraphQL call(s) with problems, first code {})",
            session.gaps, session.context.connectionId, session.graphqlProblems, session.firstGraphqlCode ?: "none",
        )
    }
}
