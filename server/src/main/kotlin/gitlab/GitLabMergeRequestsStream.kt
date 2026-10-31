package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunBudget
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabMergeRequestsStream::class.java)

private const val MRS_ENDPOINT = "/api/v4/groups/{id}/merge_requests"
private const val NOT_FOUND = "NOT_FOUND"
private const val MAX_LOGGED_IDS = 20

/**
 * The MERGE REQUESTS stream (`gitlab-integration.md` "Streams"): per configured group in order, the group's merge requests (subgroups
 * included, every state) into `raw.gitlab_merge_requests`, NEWEST `updated_at` first, hash-diffed like projects ([GitLabRawStore.
 * upsertMergeRequests]). Newest first makes offset paging safe against a merge request UPDATED mid-pass: it moves to the FRONT and the
 * next page re-shows an item already seen (`UNCHANGED`), whereas oldest first would skip one. It does NOT make paging safe against an
 * item that DROPS OUT mid-pass (a merge request deleted, or leaving the group): everything after it shifts one place earlier, so the
 * first item of the next page is skipped, and its `updated_at` sits below the watermark. That residual gap is closed by RECONCILE
 * (the RECONCILE mode below), which re-lists every group's merge requests from the floor with no watermark.
 *
 * Incremental per group by `updated_after`: a group's first pass asks from the backfill floor, a later one from its WATERMARK minus
 * [MR_LIST_OVERLAP_MILLIS]. The watermark is GitLab's OWN clock - the newest `updated_at` the group's pages showed - and moves only when
 * the group's LAST page commits, in the same transaction as the cursor, so a run cut anywhere resumes at the page it stopped on with the
 * `updated_after` and the running maximum it started with. The cursor
 * row persists between passes (it holds the watermarks); a pass saved for another list of groups is dropped and restarts, the watermarks
 * of groups no longer configured are dropped, and nothing here ever tombstones: a merge request a listing omits is no evidence it is
 * gone (`deleted_at` is RECONCILE's, PR 9). A project that NEWLY ENTERS a group (a move or a rename into the configured groups: its id is
 * not in the group's live set recorded at its last completed pass) has older merge requests below the watermark, so that group's next
 * pass lists from the backfill floor.
 *
 * A group with no live stored project lists nothing (`mrGroupsSkipped`), and a group GitLab answers `NOT_FOUND` for (at any page) is
 * skipped for this pass - counted (`mrGroupsNotFound`, one WARN naming the configured paths), its watermark left as it was - without
 * failing the other groups or the SYNC. A merge request of a project that is not a stored row (an archived one, an older server ignoring
 * `non_archived`) is skipped and counted. The heartbeat counts as progress whenever the resumable page cursor advanced.
 *
 * Progress: `pages`, `mergeRequests` (inserted), `mergeRequestsChanged` (changed or resurrected), `mrSkipped`, `mrGroupsSkipped`,
 * `mrGroupsNotFound`.
 *
 * **RECONCILE mode** (`reconcile = true`, the stream `reconcile_merge_requests`): the same listing machinery - the page transaction, the
 * ceiling, `mrSkipped`/`mrGroupsSkipped`/`mrGroupsNotFound` - in its OWN cursor row, so SYNC's watermarks and project sets are
 * never read or written. Every group is listed from the backfill floor (no watermark), and a group's last page adds its path to the state's
 * `completedGroups` instead of moving a watermark. When the last group is done the state is saved `listingDone`, then [sweep] confirms
 * and tombstones the merge requests the listing did not show, [refresh] re-asks the stats gaps and the failed details reads, and the cursor
 * is cleared. The pass resumes across attempts and jobs from the state; a pass saved for another group list is dropped with its state; a
 * saved `listingDone` goes straight to the sweep. A listing page counts as progress only when it changed a row: the pass cursor is
 * cleared when it ends, so a pass that re-lists identical pages every day is not progress.
 */
internal class GitLabMergeRequestsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    groupPaths: List<String>,
    private val backfillFromMillis: Long,
    private val ceilings: GitLabCeilings = GitLabCeilings(),
    private val reconcile: Boolean = false,
    private val sweep: GitLabMrReconcileSweep? = null,
    private val refresh: GitLabMrReconcileRefresh? = null,
) : Stream {
    init {
        require(!reconcile || (sweep != null && refresh != null)) { "RECONCILE mode needs its sweep and its refresh" }
    }

    override val name: String = if (reconcile) "reconcile_merge_requests" else "merge_requests"

    private val configured: List<String> = groupPaths.distinct()

    /** What one run knows besides the pass: the watermarks as they stand, the ids it can list, and the skipped merge requests. */
    private class Run(
        val context: StreamContext,
        val watermarks: MutableMap<String, Long>,
        val previousCompletedAt: Long?,
        val groupIds: Map<String, Long>,
        val known: Set<Long>,
        /** The live stored project ids per group path now, and as recorded at each group's last completed pass. */
        val liveProjects: Map<String, List<Long>>,
        val projectSets: MutableMap<String, List<Long>>,
        /** RECONCILE mode only: the pass state, replaced as groups complete. */
        var reconcileState: MrReconcileState? = null,
    ) {
        var skipped = 0L
        val skippedIds = mutableListOf<Long>()
        val notFoundGroups = mutableListOf<String>()
    }

    override suspend fun run(context: StreamContext) {
        if (configured.isEmpty()) return
        val row = context.cursor(name)
        val stored = row?.cursor?.let { decodeCursor<MergeRequestsCursor>(it) } ?: MergeRequestsCursor()
        val run = Run(
            context, stored.watermarks.filterKeys { it in configured }.toMutableMap(), row?.lastCompletedAt,
            rawStore.configuredGroupIds(context.connectionId), rawStore.storedProjectIds(context.connectionId),
            rawStore.liveProjectIdsByGroup(context.connectionId), stored.projectSets.filterKeys { it in configured }.toMutableMap(),
        )
        // A pass saved for another list of groups (an edit while it was interrupted) names other groups by index: restart at group 0.
        val savedPass = stored.pass?.takeIf { it.groups == configured && it.groupIndex in configured.indices }
        if (reconcile) run.reconcileState = resumedState(context, stored, savedPass)
        if (run.reconcileState?.listingDone != true) {
            val first = savedPass ?: passFor(run, 0)
            for (index in first.groupIndex until configured.size) {
                listGroup(run, index, if (index == first.groupIndex) first else passFor(run, index))
            }
            warnAboutSkips(run)
        }
        if (reconcile) finishReconcile(run)
    }

    /**
     * RECONCILE's pass state: the saved one when it can be resumed (its pass is for THIS group list, or its listing is done), else a fresh
     * one starting now. Completed groups no longer configured are dropped.
     */
    private fun resumedState(context: StreamContext, stored: MergeRequestsCursor, savedPass: MergeRequestsPass?): MrReconcileState {
        val saved = stored.reconcile
        return if (saved != null && (saved.listingDone || savedPass != null)) {
            saved.copy(completedGroups = saved.completedGroups.filter { it in configured })
        } else {
            MrReconcileState(context.clock())
        }
    }

    /** The listing is done: confirm and tombstone, re-ask the gaps and the failed reads, then end the pass. */
    private suspend fun finishReconcile(run: Run) {
        val state = checkNotNull(run.reconcileState)
        checkNotNull(sweep).run(run.context, state)
        checkNotNull(refresh).run(run.context)
        run.context.transaction { run.context.clearCursor(name) }
    }

    private fun warnAboutSkips(run: Run) {
        if (run.skipped > 0) {
            log.warn("GitLab merge requests skipped (their project is not a stored project): {} (ids {})", run.skipped,
                run.skippedIds.take(MAX_LOGGED_IDS))
        }
        if (run.notFoundGroups.isNotEmpty()) {
            log.warn("GitLab merge requests: groups the token cannot see were skipped this run: {}", run.notFoundGroups)
        }
    }

    /**
     * The first position of group [index]: page 1, asking from its watermark minus the overlap, or from the backfill floor - also when a
     * live project is in the group now that its last completed pass did not know (it newly entered; its older merge requests are below
     * the watermark). A group with no recorded set is unknown and forces nothing.
     */
    private fun passFor(run: Run, index: Int): MergeRequestsPass {
        val path = configured[index]
        val current = run.liveProjects[path].orEmpty()
        val recorded = run.projectSets[path]?.toSet()
        val entered = recorded != null && current.any { it !in recorded }
        if (reconcile) return MergeRequestsPass(configured, index, 1, isoInstant(backfillFromMillis))
        val from = if (entered) backfillFromMillis else run.watermarks[path]?.let { it - MR_LIST_OVERLAP_MILLIS } ?: backfillFromMillis
        return MergeRequestsPass(configured, index, 1, isoInstant(from), projectIds = current)
    }

    /** The cursor row for [pass] (null = the pass is over): the watermarks as they stand, written inside the caller's transaction. */
    private suspend fun saveCursor(run: Run, pass: MergeRequestsPass?) {
        if (reconcile) {
            // No watermark, no project sets: the pass and its state only. The listing is done exactly when the pass is over.
            val state = checkNotNull(run.reconcileState).copy(listingDone = pass == null).also { run.reconcileState = it }
            run.context.putCursor(name, encodeCursor(MergeRequestsCursor(pass = pass, reconcile = state)))
            return
        }
        run.context.putCursor(
            name, encodeCursor(MergeRequestsCursor(run.watermarks.toMap(), pass, run.projectSets.toMap())),
            watermarkAt = run.watermarks.values.minOrNull(),
            // The previous pass's completion is carried through the pass in progress; only the end of a pass stamps a new one.
            lastCompletedAt = if (pass == null) run.context.clock() else run.previousCompletedAt,
        )
    }

    /** The cursor after group [index] is done (or skipped): the next group's first position, or the end of the pass. */
    private fun after(run: Run, index: Int): MergeRequestsPass? =
        if (index + 1 < configured.size) passFor(run, index + 1) else null

    private suspend fun listGroup(run: Run, index: Int, start: MergeRequestsPass) {
        val context = run.context
        val path = configured[index]
        val groupId = run.groupIds[path]
        if (groupId == null) { // no live stored project: PROJECTS found nothing in this group, so there is nothing to keep
            skipGroup(run, index, "mrGroupsSkipped")
            return
        }
        val budget = RunBudget(GITLAB_SOURCE, MRS_ENDPOINT, ceilings.mergeRequests, "merge requests")
        var pass = start
        while (true) {
            budget.nextPage()
            val answer = try {
                client.groupMergeRequests(groupId, pass.updatedAfter, pass.page)
            } catch (gone: FetchException) {
                if (gone.code != NOT_FOUND) throw gone
                run.notFoundGroups += path
                skipGroup(run, index, "mrGroupsNotFound") // the group vanished (or the token lost it): this group waits, the others go on
                return
            }
            budget.add(answer.mergeRequests.size)
            val kept = answer.mergeRequests.filter { it.projectId in run.known }
            val seenMax = listOfNotNull(pass.seenMax, answer.mergeRequests.maxOfOrNull { it.updatedAt }).maxOrNull()
            val next = answer.nextPage
            val changed = context.transaction {
                val outcomes = rawStore.upsertMergeRequests(context.connectionId, kept, context.clock())
                count(context, outcomes, answer.mergeRequests.size - kept.size)
                if (next != null) {
                    pass = pass.copy(page = next, seenMax = seenMax)
                    saveCursor(run, pass)
                } else {
                    completeGroup(run, path, pass, seenMax)
                    saveCursor(run, after(run, index))
                }
                outcomes.any { it.isChange }
            }
            noteSkipped(run, answer.mergeRequests.filter { it.projectId !in run.known }.map { it.mrId })
            // An advanced page cursor is progress even when every row came back unchanged - except in RECONCILE mode, whose cursor is
            // cleared when the pass ends: a daily re-list of identical pages must not read as progress.
            context.heartbeat(changed || (!reconcile && next != null))
            if (next == null) return
        }
    }

    /** The group's listing is complete: a watermark moves (SYNC) or the group joins the pass's completed set (RECONCILE). */
    private fun completeGroup(run: Run, path: String, pass: MergeRequestsPass, seenMax: Long?) {
        if (reconcile) {
            val state = checkNotNull(run.reconcileState)
            run.reconcileState = state.copy(completedGroups = state.completedGroups + path)
            return
        }
        // The group's watermark moves to GitLab's newest updated_at it showed, never backwards.
        seenMax?.let { run.watermarks.merge(path, it, ::maxOf) }
        // An empty set is a pass saved before the field existed (a group with no live project is skipped, never completed):
        // unknown, so nothing is recorded and the group's next completed pass records one.
        if (pass.projectIds.isNotEmpty()) run.projectSets[path] = pass.projectIds
    }

    private fun count(context: StreamContext, outcomes: List<RawUpsertOutcome>, skipped: Int) {
        context.incrementProgress("pages")
        val inserted = outcomes.count { it == RawUpsertOutcome.INSERTED }
        if (inserted > 0) context.incrementProgress("mergeRequests", inserted.toLong())
        val changed = outcomes.count { it == RawUpsertOutcome.CHANGED || it == RawUpsertOutcome.RESURRECTED }
        if (changed > 0) context.incrementProgress("mergeRequestsChanged", changed.toLong())
        if (skipped > 0) context.incrementProgress("mrSkipped", skipped.toLong())
    }

    private fun noteSkipped(run: Run, ids: List<Long>) {
        run.skipped += ids.size
        if (run.skippedIds.size < MAX_LOGGED_IDS) run.skippedIds += ids.take(MAX_LOGGED_IDS - run.skippedIds.size)
    }

    /** Group [index] lists nothing this pass: counted, its watermark untouched, the cursor moved on in its own transaction. */
    private suspend fun skipGroup(run: Run, index: Int, counter: String) {
        run.context.incrementProgress(counter)
        run.context.transaction { saveCursor(run, after(run, index)) }
        run.context.heartbeat(changed = false)
    }
}
