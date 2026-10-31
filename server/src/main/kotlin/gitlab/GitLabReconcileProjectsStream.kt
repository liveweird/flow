package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabReconcileProjectsStream::class.java)

private const val MAX_LOGGED_IDS = 20

/**
 * RECONCILE's project probe (`gitlab-integration.md` "Streams", RECONCILE). PROJECTS already marks every project a completed pass did not
 * list as MOVED OUT (a project that left the groups, was archived, or was deleted - none is listed under `archived=false`) and brings a
 * re-listed one back; what it cannot tell is WHICH of those is gone. This stream asks, once per moved-out project that no reconcile has
 * deleted yet (at most [MAX_PROJECT_PROBES]), `GET /projects/:id`:
 *  - **404** - the project is deleted (or invisible to the token): `deleted_at = now`, guarded, so a repeated pass writes nothing
 *    (`projectsDeleted`). Nothing cascades: its commits and merge requests keep their rows, as a moved project's do - the live-project
 *    `EXISTS` of every backlog already keeps them out of the work;
 *  - **200 and `archived`** - `archived = true` on the row and nothing else (`projectsArchived`);
 *  - **any other 200** - no write (`projectsProbed`): the project still exists, it merely left the configured groups.
 *
 * The 200 body is NEVER fed to `upsertProjects`: the body of an archived or moved project differs from its listing, the hash would
 * change and `changeProject` would resurrect the row as live (it clears both markers). Any other failure propagates (rate limit,
 * authentication) - except a failure one project can cause on its own ([PROJECT_SCOPED_FAILURE_CODES]: it counts as `projectProbesFailed`,
 * the project is left as it was and the pass goes on; [RECONCILE_CONSECUTIVE_FAILURES] in a row rethrow, like the outage they read as).
 * A lease loss costs nothing: every probe commits its own write and repeats are guarded.
 *
 * Rotation: the probes go by project id from a keyset in the `reconcile_projects` cursor row (`lastProbedId`), at most
 * [MAX_PROJECT_PROBES] a run, wrapping to the start when fewer remain, so more moved-out projects than one run's cap are all probed within
 * a few runs. The row exists only while the set is larger than one run's cap; a smaller set is probed whole every run and keeps no cursor.
 * One WARN per run names the deleted ids (at most [MAX_LOGGED_IDS]).
 */
internal class GitLabReconcileProjectsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
) : Stream {
    override val name: String = "reconcile_projects"

    override suspend fun run(context: StreamContext) {
        val deleted = mutableListOf<Long>()
        val streak = ProjectFailureStreak()
        val batch = nextBatch(context)
        for (projectId in batch) {
            val changed = when (val verdict = probe(projectId)) {
                Verdict.Gone -> markDeleted(context, projectId).also { if (it) deleted += projectId }.also { streak.succeeded() }
                is Verdict.Exists -> {
                    streak.succeeded()
                    if (verdict.archived) markArchived(context, projectId) else probed(context)
                }
                is Verdict.Failed -> {
                    streak.failed(verdict.failure)
                    context.incrementProgress("projectProbesFailed")
                    false
                }
            }
            context.heartbeat(changed)
        }
        saveRotation(context, batch)
        if (deleted.isNotEmpty()) {
            log.warn("GitLab projects marked deleted by a reconcile (404): {} (ids {})", deleted.size, deleted.take(MAX_LOGGED_IDS))
        }
    }

    /** The next [MAX_PROJECT_PROBES] moved-out projects after the saved keyset, wrapping to the start for whatever room is left. */
    private suspend fun nextBatch(context: StreamContext): List<Long> {
        val after = context.cursor(name)?.cursor?.let { decodeCursor<ProjectProbeCursor>(it).lastProbedId } ?: 0L
        val ahead = rawStore.movedOutProjects(context.connectionId, MAX_PROJECT_PROBES, after)
        if (ahead.size >= MAX_PROJECT_PROBES || after == 0L) return ahead
        val wrapped = rawStore.movedOutProjects(context.connectionId, MAX_PROJECT_PROBES - ahead.size, 0L)
        return ahead + wrapped.filter { it !in ahead }
    }

    /** Keeps the keyset only while the set outgrows one run's cap; a smaller set is probed whole and needs none. */
    private suspend fun saveRotation(context: StreamContext, batch: List<Long>) {
        if (batch.size < MAX_PROJECT_PROBES) {
            context.transaction { context.clearCursor(name) }
        } else {
            context.transaction { context.putCursor(name, encodeCursor(ProjectProbeCursor(batch.last()))) }
        }
    }

    private suspend fun markDeleted(context: StreamContext, projectId: Long): Boolean =
        context.transaction { rawStore.markProjectDeleted(context.connectionId, projectId, context.clock()) }
            .also { if (it) context.incrementProgress("projectsDeleted") }

    private suspend fun markArchived(context: StreamContext, projectId: Long): Boolean =
        context.transaction { rawStore.markProjectArchived(context.connectionId, projectId) }
            .also { context.incrementProgress(if (it) "projectsArchived" else "projectsProbed") }

    private fun probed(context: StreamContext): Boolean {
        context.incrementProgress("projectsProbed")
        return false
    }

    private sealed interface Verdict {
        data object Gone : Verdict

        class Exists(val archived: Boolean) : Verdict

        /** A project-scoped failure: the project is left as it was. */
        class Failed(val failure: FetchException) : Verdict
    }

    private suspend fun probe(projectId: Long): Verdict = try {
        Verdict.Exists(client.project(projectId).archived)
    } catch (failure: FetchException) {
        when {
            failure.code == "NOT_FOUND" -> Verdict.Gone
            failure.isProjectScoped() -> Verdict.Failed(failure)
            else -> throw failure
        }
    }
}
