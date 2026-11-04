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

/** A merge request's REST read failing like this after the transport's retries is stamped [DETAILS_ERROR_FETCH_FAILED]. */
private val MR_READ_FAILURE_CODES = setOf("UPSTREAM_UNAVAILABLE", "INVALID_RESPONSE", "TIMEOUT")

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
 *    `mrDetailsOversized`; one of the three failing `UPSTREAM_UNAVAILABLE`, `TIMEOUT` or `INVALID_RESPONSE` after the transport's
 *    retries: `details_error = FETCH_FAILED`, `mrDetailsFailed` (a merge request that fails every time must not block the backlog behind
 *    it). Both are stamped at the END of the run through ONE [SkipBudget]: more than `max(10, 10 %)` of them fail the run and NONE is
 *    stamped (the cap is too small, or the instance is down, not isolated outliers). The run fails with the LAST failed read's OWN code
 *    when failed reads are at least as many as oversized ones (an outage backs off like an outage and is never a ceiling trip), else
 *    `LIMIT_EXCEEDED`. Until then they wait in memory and are excluded from this run's re-claims, which would otherwise return them
 *    forever; a change of the merge request re-reads it. On an ABNORMAL end (a failure, the wall clock) only the oversized ones are
 *    stamped: the failed reads stay stale and are read again next run;
 *  - the GraphQL call answers errors, lacks the project or a merge request, or fails with a per-answer code ([GRAPHQL_GAP_CODES]): a NULL
 *    GAP for those merge requests (`mrDiffStatsGaps`, counted when the merge request is stamped; one WARN per run with the count and the
 *    first code), stamped complete and NOT asked again until the merge request changes;
 *  - the GraphQL call fails with an OUTAGE code ([GRAPHQL_OUTAGE_CODES]): the circuit breaker (`mrGraphqlBreaker` 1, one WARN). No
 *    further GraphQL call this run; every merge request settled from then on, the failing batch included, is stamped with a stats GAP
 *    (as above) and its REST reads go on. (Only transient codes trip it: a disabled, redirected or refused endpoint is a gap per batch.)
 *    RECONCILE (PR 9) re-asks gapped stats, breaker gaps included.
 * Any other failure propagates after the deferred stamps are written.
 *
 * Progress: `mrDetails`, `mrEvents`, `mrNotesCapped`, `mrDetailsMissing`, `mrDetailsOversized`, `mrDetailsFailed`, `mrDiffStatsGaps`,
 * `mrGraphqlBreaker`, `mrApprovalsUnavailable`, `mrReviewersUnavailable`, `mrDetailsPending`.
 */
internal class GitLabMrDetailsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val callsPerRun: Int,
) : Stream {
    override val name: String = "mr_details"

    /** A merge request whose read ended in a terminal [error] and waits for the end of the run to be stamped. */
    private class Deferred(val mr: StaleMergeRequest, val error: String, val cause: FetchException)

    /** Everything one run keeps: the calls spent, the merge requests waiting for their terminal stamp, and the GraphQL trouble seen. */
    private class Session(val context: StreamContext) {
        val skips = SkipBudget(GITLAB_SOURCE, "MR_DETAILS", "merge requests")
        val deferred = mutableListOf<Deferred>()
        var calls = 0
        var gaps = 0L
        var graphqlProblems = 0
        var firstGraphqlCode: String? = null

        /** Set by the first GraphQL outage: no further GraphQL call this run. */
        var breaker = false
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
                keepDeferred(session, cause)
            } catch (cause: CeilingTripException) {
                keepDeferred(session, cause) // the wall clock stopped the run: oversized ones stamped, failed reads stay stale
            }
            stampDeferred(session)
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
            // The deferred merge requests of this run wait unstamped, so they still read as stale: skip them here.
            val waiting = session.deferred.mapTo(HashSet()) { it.mr.mrId }
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

    /** One merge request's outcome: its details stored with their stamp, or a terminal one; any other failure propagates to [run]. */
    private suspend fun settle(session: Session, batch: Batch, mr: StaleMergeRequest) {
        val context = session.context
        // The GraphQL answer comes first (memoised per project and batch); after an outage it is a gap without a call.
        val stats = diffStatsOf(session, batch, mr)
        val read = try {
            readEvents(session, mr)
        } catch (failure: FetchException) {
            val stored = when {
                failure.isOversized() -> defer(session, mr, DETAILS_ERROR_LIMIT_EXCEEDED, "mrDetailsOversized", failure)
                failure.code == NOT_FOUND -> markMissing(session, mr)
                failure.code in MR_READ_FAILURE_CODES -> defer(session, mr, DETAILS_ERROR_FETCH_FAILED, "mrDetailsFailed", failure)
                else -> throw failure
            }
            context.heartbeat(changed = stored)
            return
        }
        // The calls are done before the transaction opens: nothing below waits on the network while a row is locked.
        val settled = rawStore.settleMrDetails(
            context.connectionId, mr.mrId, read.events, context.clock(), MrDetailsOutcome(notesCapped = read.capped, stats = stats),
        )
        session.skips.settled()
        if (settled) {
            context.incrementProgress("mrDetails")
            context.incrementProgress("mrEvents", read.events.size.toLong())
            if (read.capped) context.incrementProgress("mrNotesCapped")
            if (stats == null) {
                session.gaps++
                context.incrementProgress("mrDiffStatsGaps")
            }
        }
        context.heartbeat(changed = settled)
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

    /** [mr]'s stats from the batch's answer for its project (asked on first need), or null - a gap (counted once it is stamped). */
    private suspend fun diffStatsOf(session: Session, batch: Batch, mr: StaleMergeRequest): GitLabDiffStats? {
        val answer = batch.stats[mr.projectId] ?: askDiffStats(session, batch, mr.projectId).also { batch.stats[mr.projectId] = it }
        return answer[mr.iid]
    }

    private suspend fun askDiffStats(session: Session, batch: Batch, projectId: Long): Map<Long, GitLabDiffStats> {
        val path = batch.paths[projectId] ?: return emptyMap()
        if (session.breaker) return emptyMap() // the endpoint was out earlier in this run: its stats are gaps, no further call
        val iids = batch.iidsByProject.getValue(projectId)
        session.calls++
        return try {
            val answer = client.mergeRequestDiffStats(path, iids)
            if (answer.errors > 0 || answer.projectMissing) noteGraphqlProblem(session, answer.firstErrorCode)
            answer.stats
        } catch (failure: FetchException) {
            when (failure.code) {
                in GRAPHQL_GAP_CODES -> {
                    noteGraphqlProblem(session, failure.code)
                    emptyMap()
                }
                in GRAPHQL_OUTAGE_CODES -> {
                    noteGraphqlProblem(session, failure.code)
                    session.context.incrementProgress("mrGraphqlBreaker")
                    session.breaker = true
                    log.warn("GitLab GraphQL endpoint out ({}), no further GraphQL call this run: stats are gaps", failure.code)
                    emptyMap()
                }
                else -> throw failure
            }
        }
    }

    private fun noteGraphqlProblem(session: Session, code: String?) {
        session.graphqlProblems++
        if (session.firstGraphqlCode == null) session.firstGraphqlCode = code
    }

    /**
     * [mr]'s read ended in a terminal [error] ([counter] counts it) because of [cause]: it waits for the END of the run's stamp; too many
     * (a mis-set cap, an instance that is down) fail the run and stamp none. The code is decided from the whole deferred list: the LAST
     * read failure's own code when failed reads are at least as many as oversized ones (an outage backs off like an outage), else
     * `LIMIT_EXCEEDED`.
     */
    private fun defer(session: Session, mr: StaleMergeRequest, error: String, counter: String, cause: FetchException): Boolean {
        log.warn("GitLab merge-request details not readable ({}), data source {}", error, session.context.connectionId)
        session.deferred += Deferred(mr, error, cause)
        session.context.incrementProgress(counter)
        try {
            session.skips.skip()
        } catch (trip: FetchException) {
            val reads = session.deferred.filter { it.error == DETAILS_ERROR_FETCH_FAILED }
            val oversized = session.deferred.size - reads.size
            session.deferred.clear() // a mis-set cap or an outage: leave every deferred merge request stale for after the fix
            if (reads.isEmpty() || reads.size < oversized) throw trip
            throw reads.last().cause.also { it.addSuppressed(trip) }
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

    /**
     * Stamps what was deferred before [cause] ended the run, then rethrows it; a failing stamp must not hide [cause]. Only the OVERSIZED
     * ones are stamped on this abnormal end: a failed read (`FETCH_FAILED`) stays stale and is read again next run - a hanging GitLab
     * late in a long run would otherwise let the wall clock trip first and stamp the ones it met for good.
     */
    private suspend fun keepDeferred(session: Session, cause: Exception): Nothing {
        val stampFailure = catchingFailures<Exception?>(
            block = { stampDeferred(session, includeFailedReads = false); null },
            onFailure = { it },
        )
        // The clock's trip was typed before the stamps; rebuilt after them, it knows they were stored (progress).
        if (cause is CeilingTripException && stampFailure == null) throw session.context.ceilingTrip(cause.code, cause.underlying)
        stampFailure?.let(cause::addSuppressed)
        throw cause
    }

    /**
     * Stamps the deferred merge requests with their terminal error in ONE transaction (events and stats stay as they were), then clears;
     * the failed reads only when [includeFailedReads] (a normal end of the run).
     */
    private suspend fun stampDeferred(session: Session, includeFailedReads: Boolean = true) {
        val stamps = session.deferred.filter { includeFailedReads || it.error != DETAILS_ERROR_FETCH_FAILED }
        session.deferred.clear()
        if (stamps.isEmpty()) return
        val context = session.context
        context.transaction {
            val now = context.clock()
            stamps.forEach {
                rawStore.settleMrDetails(context.connectionId, it.mr.mrId, emptyList(), now, MrDetailsOutcome(error = it.error))
            }
        }
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
