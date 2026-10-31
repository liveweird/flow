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
 * upsertMergeRequests]). Newest first is what makes offset paging safe against a merge request updated mid-pass: it moves to the FRONT
 * and the next page re-shows an item already seen (`UNCHANGED`), whereas oldest first would skip one.
 *
 * Incremental per group by `updated_after`: a group's first pass asks from the backfill floor, a later one from its WATERMARK minus
 * [MR_LIST_OVERLAP_MILLIS]. The watermark is GitLab's OWN clock - the newest `updated_at` the group's pages showed - and moves only when
 * the group's LAST page commits, in the same transaction as the cursor, so a run cut anywhere resumes at the page it stopped on with the
 * `updated_after` and the running maximum it started with, and no watermark ever passes a merge request that was not listed. The cursor
 * row persists between passes (it holds the watermarks); a pass saved for another list of groups is dropped and restarts, the watermarks
 * of groups no longer configured are dropped, and nothing here ever tombstones: a merge request a listing omits is no evidence it is
 * gone (`deleted_at` is RECONCILE's, PR 9).
 *
 * A group with no live stored project lists nothing, and a group GitLab answers `NOT_FOUND` for (at any page) is skipped for this pass
 * - counted, its watermark left as it was - without failing the other groups or the SYNC. A merge request of a project that is not a
 * stored row (an archived one, an older server ignoring `non_archived`) is skipped and counted.
 *
 * Progress: `pages`, `mergeRequests` (inserted), `mergeRequestsChanged` (changed or resurrected), `mrSkipped`, `mrGroupsSkipped`.
 */
internal class GitLabMergeRequestsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    groupPaths: List<String>,
    private val backfillFromMillis: Long,
    private val ceilings: GitLabCeilings = GitLabCeilings(),
) : Stream {
    override val name: String = "merge_requests"

    private val configured: List<String> = groupPaths.distinct()

    /** What one run knows besides the pass: the watermarks as they stand, the ids it can list, and the skipped merge requests. */
    private class Run(
        val context: StreamContext,
        val watermarks: MutableMap<String, Long>,
        val previousCompletedAt: Long?,
        val groupIds: Map<String, Long>,
        val known: Set<Long>,
    ) {
        var skipped = 0L
        val skippedIds = mutableListOf<Long>()
    }

    override suspend fun run(context: StreamContext) {
        if (configured.isEmpty()) return
        val row = context.cursor(name)
        val stored = row?.cursor?.let { decodeCursor<MergeRequestsCursor>(it) } ?: MergeRequestsCursor()
        val run = Run(
            context, stored.watermarks.filterKeys { it in configured }.toMutableMap(), row?.lastCompletedAt,
            rawStore.configuredGroupIds(context.connectionId), rawStore.storedProjectIds(context.connectionId),
        )
        // A pass saved for another list of groups (an edit while it was interrupted) names other groups by index: restart at group 0.
        val first = stored.pass?.takeIf { it.groups == configured && it.groupIndex in configured.indices } ?: passFor(run, 0)
        for (index in first.groupIndex until configured.size) {
            listGroup(run, index, if (index == first.groupIndex) first else passFor(run, index))
        }
        if (run.skipped > 0) {
            log.warn("GitLab merge requests skipped (their project is not a stored project): {} (ids {})", run.skipped,
                run.skippedIds.take(MAX_LOGGED_IDS))
        }
    }

    /** The first position of group [index]: page 1, asking from its watermark minus the overlap, or from the backfill floor. */
    private fun passFor(run: Run, index: Int): MergeRequestsPass {
        val from = run.watermarks[configured[index]]?.let { it - MR_LIST_OVERLAP_MILLIS } ?: backfillFromMillis
        return MergeRequestsPass(configured, index, 1, isoInstant(from))
    }

    /** The cursor row for [pass] (null = the pass is over): the watermarks as they stand, written inside the caller's transaction. */
    private suspend fun saveCursor(run: Run, pass: MergeRequestsPass?) {
        run.context.putCursor(
            name, encodeCursor(MergeRequestsCursor(run.watermarks.toMap(), pass)),
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
            skipGroup(run, index)
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
                skipGroup(run, index) // the group vanished (or the token lost it): this group waits, the others go on
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
                    // The group's listing is complete: its watermark moves to GitLab's newest updated_at it showed, never backwards.
                    seenMax?.let { run.watermarks.merge(path, it, ::maxOf) }
                    saveCursor(run, after(run, index))
                }
                outcomes.any { it.isChange }
            }
            noteSkipped(run, answer.mergeRequests.filter { it.projectId !in run.known }.map { it.mrId })
            context.heartbeat(changed)
            if (next == null) return
        }
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
    private suspend fun skipGroup(run: Run, index: Int) {
        run.context.incrementProgress("mrGroupsSkipped")
        run.context.transaction { saveCursor(run, after(run, index)) }
        run.context.heartbeat(changed = false)
    }
}
