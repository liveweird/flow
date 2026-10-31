package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.StreamContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabMrReconcileRefresh::class.java)

/**
 * What RECONCILE re-asks after the merge-request re-list (`gitlab-integration.md` "MR DETAILS", "RECONCILE may re-ask"): the stats
 * SYNC left as gaps, and the details reads that failed. RECONCILE never runs MR DETAILS itself.
 *
 * **Stats** (L6): the settled live merge requests of live projects with no diff stats ([GitLabMrReads.gappedMergeRequests], newest first,
 * at most [RECONCILE_STATS_CALLS] x [MR_DETAILS_BATCH] = 5,000 rows), grouped by project, [MR_DETAILS_BATCH] iids per GraphQL call, so
 * `ceil(gaps / 50)` calls per project and at most [RECONCILE_STATS_CALLS] a run. More than 5,000 permanent gaps would starve the older
 * fillable ones: nothing records when a gap was last asked, so the read cannot rotate. The iids GitLab answers are written
 * (stats and PROCESS flag, the details stamp untouched: `mrStatsRefilled` counts rows filled); one it still answers null for stays a
 * gap, asked again tomorrow - at most one call per project with gaps per day. A transient OUTAGE code ([GRAPHQL_OUTAGE_CODES]) ends the
 * pass after that one call (`mrGraphqlBreaker`, the details stream's breaker); a GAP code ([GRAPHQL_GAP_CODES]) or a null project moves
 * on to the next project; anything else (a rate limit, a rejected token) propagates. A partial answer with `errors[]` still writes
 * what it answered and carries on with the project's next batch, so one permanent gap cannot starve the newer merge requests behind it.
 *
 * **Retries** (L7, U2): ONE SQL un-stamp of at most [RECONCILE_DETAILS_RETRIES] merge requests whose `details_error` is `FETCH_FAILED` or
 * `NOT_FOUND`, the stalest first, plus `LIMIT_EXCEEDED` when the job was requested on demand ("Reconcile now" after the operator raised
 * `gitlab.maxResponseBytes`; an oversized merge request stays oversized, so re-asking it daily would be a wasted call and, unbounded,
 * would trip the details skip budget on every SYNC). The next SYNC's MR DETAILS reads them again (`mrDetailsRetried`).
 */
internal class GitLabMrReconcileRefresh(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val onDemand: Boolean,
) {
    suspend fun run(context: StreamContext) {
        refillStats(context)
        val errors = listOf(DETAILS_ERROR_FETCH_FAILED, DETAILS_ERROR_NOT_FOUND) +
            if (onDemand) listOf(DETAILS_ERROR_LIMIT_EXCEEDED) else emptyList()
        val retried = context.transaction { rawStore.retryMrDetails(context.connectionId, errors, RECONCILE_DETAILS_RETRIES) }
        if (retried > 0) context.incrementProgress("mrDetailsRetried", retried.toLong())
        context.heartbeat(retried > 0)
    }

    private class Stats(var calls: Int = 0, var problems: Int = 0, var firstCode: String? = null, var outage: Boolean = false)

    private suspend fun refillStats(context: StreamContext) {
        val connectionId = context.connectionId
        val gapped = rawStore.gappedMergeRequests(connectionId, RECONCILE_STATS_CALLS * MR_DETAILS_BATCH)
        if (gapped.isEmpty()) return
        val byProject = gapped.groupBy { it.projectId }
        val paths = rawStore.projectPaths(connectionId, byProject.keys)
        val stats = Stats()
        for ((projectId, mrs) in byProject) {
            val path = paths[projectId] ?: continue
            for (chunk in mrs.map { it.iid }.chunked(MR_DETAILS_BATCH)) {
                if (stats.calls >= RECONCILE_STATS_CALLS || stats.outage) break
                if (!askChunk(context, stats, projectId, path, chunk)) break
            }
            if (stats.calls >= RECONCILE_STATS_CALLS || stats.outage) break
        }
        if (stats.problems > 0) {
            log.warn("GitLab GraphQL diff stats re-ask: {} problems, first code {}", stats.problems, stats.firstCode)
        }
    }

    /** One GraphQL call for [iids]; false when the rest of the project's batches should be skipped. */
    private suspend fun askChunk(context: StreamContext, stats: Stats, projectId: Long, path: String, iids: List<Long>): Boolean {
        stats.calls++
        val answer = try {
            client.mergeRequestDiffStats(path, iids)
        } catch (failure: FetchException) {
            when (failure.code) {
                in GRAPHQL_OUTAGE_CODES -> {
                    note(stats, failure.code)
                    stats.outage = true
                    context.incrementProgress("mrGraphqlBreaker")
                    log.warn("GitLab GraphQL endpoint out ({}), no further stats re-ask this run", failure.code)
                }
                in GRAPHQL_GAP_CODES -> note(stats, failure.code)
                else -> throw failure
            }
            context.heartbeat(changed = false)
            return false
        }
        if (answer.errors > 0 || answer.projectMissing) note(stats, answer.firstErrorCode)
        val filled = if (answer.stats.isEmpty()) {
            0
        } else {
            context.transaction { rawStore.writeMrDiffStats(context.connectionId, projectId, answer.stats) }
        }
        if (filled > 0) context.incrementProgress("mrStatsRefilled", filled.toLong())
        context.heartbeat(filled > 0)
        return !answer.projectMissing
    }

    private fun note(stats: Stats, code: String?) {
        stats.problems++
        if (stats.firstCode == null) stats.firstCode = code
    }
}
