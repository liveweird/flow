package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabMrReconcileSweep::class.java)

private const val NOT_FOUND = "NOT_FOUND"
private const val MAX_LOGGED_IDS = 20

/**
 * The end of RECONCILE's merge-request re-list: the tombstones, each CONFIRMED by GitLab first - the [CommitsSweep] discipline
 * (`gitlab-integration.md` "Streams"). A listing that does not show a merge request is only a CANDIDATE, never evidence: the live merge
 * requests of LIVE projects in a group the pass listed to its last page (`completedGroups`), updated at or after the floor (older ones
 * are kept but not re-checked), that nothing has seen since the pass began (`last_seen_at < passStartedAt`), the stalest-checked first: a
 * stable set of kept-but-unlisted merge requests larger than the cap rotates (each run's probes touch them, so the next run reaches the
 * rest) instead of starving the tail.
 *
 * At most [MAX_MR_CONFIRMATIONS] are confirmed per run, grouped by project. Per project, in order:
 *  1. `GET /projects/:id` must answer 200 FIRST. A 404 means every candidate of the project is UNCONFIRMED; any other failure of this
 *     probe propagates (the saved `listingDone` cursor resumes at the sweep);
 *  2. per candidate `GET .../merge_requests/:iid`: **200** = KEPT (the body is stored through `upsertMergeRequests`: unchanged rows are
 *     touched, a changed one is refreshed and its details go stale; a body that is not the candidate - another id or project - proves
 *     nothing and counts as UNCONFIRMED); **404** = GONE (tentative); any other failure = UNCONFIRMED;
 *  3. if any verdict was GONE, the project is probed AGAIN: 200 tombstones them (`deleted_at = now`, PROCESS flagged), anything else
 *     makes them UNCONFIRMED (a project lost mid-batch 404s the rest of it).
 *
 * Kept and unconfirmed rows are touched (`last_seen_at = now`), so they leave this pass's set; keeping a gone merge request live is the
 * safe error. A tombstone deletes nothing: the events stay until PURGE, and a listing that shows the row again resurrects it. What
 * prevents a wrong tombstone: a transient listing failure propagates before any sweep; a skipped page makes a candidate that the probe
 * answers 200 for; a group that was `NOT_FOUND` or skipped is not in `completedGroups`; an invisible project (403 reads as 404) fails the
 * pre-probe, one lost mid-batch fails the post-probe; a project that moved out of the group minutes ago answers 200 by id (bounded by
 * the cap; the next SYNC marks it moved out and the next RECONCILE excludes it); a project with merge requests switched off answers 200
 * while its merge requests 404 - they ARE tombstoned (the API has none any more; rows and events stay).
 *
 * Progress: `mrReconcileCandidates` (the batch), `mrReconcileKept`, `mrReconcileUnconfirmed`, `mergeRequestsDeleted`,
 * `mrReconcileDeferred` (more candidates than the cap). Heartbeats: false per probe, and true after a project's write only when it
 * tombstoned, kept or changed something. One WARN per run names the tombstoned ids (at most [MAX_LOGGED_IDS]) and one counts the
 * unconfirmed.
 */
internal class GitLabMrReconcileSweep(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val backfillFromMillis: Long,
) {
    /** What one `GET .../merge_requests/:iid` said: the body (200), a 404, or a failure that is no verdict. */
    private sealed interface Probe {
        class Found(val body: RawGitLabMergeRequest) : Probe

        data object Missing : Probe

        data object Failed : Probe
    }

    private class Outcome {
        val kept = mutableListOf<RawGitLabMergeRequest>()
        val keptIds = mutableListOf<Long>()
        val gone = mutableListOf<Long>()
        val unconfirmed = mutableListOf<Long>()
    }

    suspend fun run(context: StreamContext, state: MrReconcileState) {
        val candidates = rawStore.mrReconcileCandidates(
            context.connectionId, backfillFromMillis, state.passStartedAt, state.completedGroups, MAX_MR_CONFIRMATIONS + 1,
        )
        val batch = candidates.take(MAX_MR_CONFIRMATIONS)
        if (batch.isEmpty()) return
        context.incrementProgress("mrReconcileCandidates", batch.size.toLong())
        if (candidates.size > MAX_MR_CONFIRMATIONS) context.incrementProgress("mrReconcileDeferred")
        val tombstoned = mutableListOf<Long>()
        var unconfirmed = 0
        for ((projectId, group) in batch.groupBy { it.projectId }) {
            val outcome = confirm(context, projectId, group)
            tombstoned += outcome.gone
            unconfirmed += outcome.unconfirmed.size
            store(context, outcome)
        }
        if (tombstoned.isNotEmpty()) {
            log.warn("GitLab merge requests marked deleted by a reconcile: {} (ids {})", tombstoned.size, tombstoned.take(MAX_LOGGED_IDS))
        }
        if (unconfirmed > 0) log.warn("GitLab merge requests a reconcile could not confirm either way: {}", unconfirmed)
    }

    /** The verdicts for one project's candidates; the calls run outside any transaction. */
    private suspend fun confirm(context: StreamContext, projectId: Long, group: List<StaleMergeRequest>): Outcome {
        val outcome = Outcome()
        val trusted = projectAnswers(projectId)
        context.heartbeat(changed = false)
        for (candidate in group) {
            if (!trusted) {
                outcome.unconfirmed += candidate.mrId
                continue
            }
            when (val probe = probe(projectId, candidate)) {
                Probe.Missing -> outcome.gone += candidate.mrId
                Probe.Failed -> outcome.unconfirmed += candidate.mrId
                is Probe.Found -> if (probe.body.mrId == candidate.mrId && probe.body.projectId == projectId) {
                    outcome.kept += probe.body
                    outcome.keptIds += candidate.mrId
                } else {
                    outcome.unconfirmed += candidate.mrId
                }
            }
            context.heartbeat(changed = false)
        }
        // The project may have been lost part-way through the batch: believe the 404s only if it still answers now.
        if (outcome.gone.isNotEmpty() && !projectAnswers(projectId)) {
            outcome.unconfirmed += outcome.gone
            outcome.gone.clear()
        }
        return outcome
    }

    /** Whether `GET /projects/:id` answers 200 (404 = false); any other failure propagates. */
    private suspend fun projectAnswers(projectId: Long): Boolean = try {
        client.project(projectId)
        true
    } catch (failure: FetchException) {
        if (failure.code == NOT_FOUND) false else throw failure
    }

    /** Any failure other than a 404 is [Probe.Failed]: the candidate stays live and is touched (the safe error). */
    private suspend fun probe(projectId: Long, candidate: StaleMergeRequest): Probe = try {
        Probe.Found(client.mergeRequest(projectId, candidate.iid))
    } catch (failure: FetchException) {
        if (failure.code == NOT_FOUND) Probe.Missing else Probe.Failed
    }

    /** One project's verdicts in ONE transaction: the kept bodies, the touched, the tombstoned. */
    private suspend fun store(context: StreamContext, outcome: Outcome) {
        var changed = false
        context.transaction {
            val now = context.clock()
            val outcomes: List<RawUpsertOutcome> = rawStore.upsertMergeRequests(context.connectionId, outcome.kept, now)
            rawStore.touchMergeRequests(context.connectionId, outcome.unconfirmed, now)
            val count = rawStore.tombstoneMergeRequests(context.connectionId, outcome.gone, now)
            if (outcome.keptIds.isNotEmpty()) context.incrementProgress("mrReconcileKept", outcome.keptIds.size.toLong())
            if (outcome.unconfirmed.isNotEmpty()) context.incrementProgress("mrReconcileUnconfirmed", outcome.unconfirmed.size.toLong())
            if (count > 0) context.incrementProgress("mergeRequestsDeleted", count.toLong())
            changed = count > 0 || outcome.keptIds.isNotEmpty() || outcomes.any { it.isChange }
        }
        context.heartbeat(changed)
    }
}
