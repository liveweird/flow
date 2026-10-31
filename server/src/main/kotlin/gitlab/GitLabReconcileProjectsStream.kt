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
 * authentication), and a lease loss costs nothing: every probe commits its own write and repeats are guarded, so there is no cursor.
 * One WARN per run names the deleted ids (at most [MAX_LOGGED_IDS]).
 */
internal class GitLabReconcileProjectsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
) : Stream {
    override val name: String = "reconcile_projects"

    override suspend fun run(context: StreamContext) {
        val deleted = mutableListOf<Long>()
        for (projectId in rawStore.movedOutProjects(context.connectionId, MAX_PROJECT_PROBES)) {
            val changed = when (val verdict = probe(projectId)) {
                Verdict.Gone -> markDeleted(context, projectId).also { if (it) deleted += projectId }
                is Verdict.Exists -> if (verdict.archived) markArchived(context, projectId) else probed(context)
            }
            context.heartbeat(changed)
        }
        if (deleted.isNotEmpty()) {
            log.warn("GitLab projects marked deleted by a reconcile (404): {} (ids {})", deleted.size, deleted.take(MAX_LOGGED_IDS))
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
    }

    private suspend fun probe(projectId: Long): Verdict = try {
        Verdict.Exists(client.project(projectId).archived)
    } catch (failure: FetchException) {
        if (failure.code == "NOT_FOUND") Verdict.Gone else throw failure
    }
}
