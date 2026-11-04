package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunBudget
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.isChange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GitLabProjectsStream::class.java)

private const val PROJECTS_ENDPOINT = "/api/v4/groups/{id}/projects"

/**
 * The PROJECTS stream (`gitlab-integration.md` "Streams"): per configured group (in order), resolve the group by its encoded path, then
 * page its projects (`include_subgroups=true`, archived and shared excluded) into `raw.gitlab_projects`, hash-diffing each one
 * ([GitLabRawStore.upsertProjects]). A group GitLab does not show to the token is a `NOT_FOUND` failure of the stream, like a Jira
 * project the token cannot see. Defensive filters, for an older server that ignores a parameter: an archived project is skipped, and so
 * is one whose namespace is not the configured group or under it (a project shared INTO the group), and so is one whose
 * `default_branch` or path is longer than its column (never truncated); each skip is counted
 * (`projectsSkipped`) with one WARN per run that names ids only.
 *
 * The pass is resumable: the cursor holds the pass start, the group index and the next page; every page commits its rows and the
 * cursor together. When the last group's last page is done, the live projects not seen during the pass are marked moved out of
 * scope (their rows stay; seen again, they are resurrected) and the cursor is cleared. Progress: `pages`, `projects`,
 * `projectsSkipped`.
 */
internal class GitLabProjectsStream(
    private val client: GitLabClient,
    private val rawStore: GitLabRawStore,
    private val groupPaths: List<String>,
    private val ceilings: GitLabCeilings = GitLabCeilings(),
) : Stream {
    override val name: String = "projects"

    override suspend fun run(context: StreamContext) {
        // groupIndex == size: every group was read, only the sweep is left. A cursor written for another list of groups (an edit while a
        // pass was interrupted) is dropped and the pass restarts: its index would name another group, and the sweep would then mark
        // the projects of the groups it never read as moved out.
        val resumed = context.cursor(name)?.cursor?.let { decodeCursor<ProjectsCursor>(it) }
            ?.takeIf { it.groups == groupPaths && it.groupIndex in 0..groupPaths.size }
        val passStartedAt = resumed?.passStartedAt ?: context.clock()
        val skippedIds = mutableListOf<Long>()
        for (index in (resumed?.groupIndex ?: 0) until groupPaths.size) {
            val page = if (index == resumed?.groupIndex) resumed.page else 1
            readGroup(context, passStartedAt, index, page, skippedIds)
        }
        if (skippedIds.isNotEmpty()) {
            log.warn(
                "GitLab projects skipped (archived, outside the configured group, or an over-long branch/path): {} (ids {})",
                skippedIds.size, skippedIds.take(MAX_LOGGED_IDS),
            )
        }
        context.transaction {
            rawStore.markProjectsMovedOutNotSeenSince(context.connectionId, passStartedAt, context.clock())
            context.clearCursor(name)
        }
    }

    private suspend fun readGroup(context: StreamContext, passStartedAt: Long, index: Int, startPage: Int, skippedIds: MutableList<Long>) {
        val path = groupPaths[index]
        val group = client.group(path)
        if (!group.fullPath.equals(path, ignoreCase = true)) throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null,
            "/api/v4/groups/{path}")
        val budget = RunBudget(GITLAB_SOURCE, PROJECTS_ENDPOINT, ceilings.projects, "projects")
        var page = startPage
        while (true) {
            budget.nextPage()
            val answer = client.groupProjects(group.id, page)
            budget.add(answer.items.size)
            val kept = mutableListOf<RawGitLabProject>()
            for (item in answer.items) {
                val project = item as? JsonObject ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, PROJECTS_ENDPOINT)
                val raw = toProject(project, path, group.id)
                if (raw == null) skippedIds += project.id() else kept += raw
            }
            val nextPage = answer.nextPage
            val changed = context.transaction {
                val outcomes = rawStore.upsertProjects(context.connectionId, kept, context.clock())
                context.incrementProgress("pages")
                context.incrementProgress("projects", kept.size.toLong())
                if (answer.items.size > kept.size) context.incrementProgress("projectsSkipped", (answer.items.size - kept.size).toLong())
                val next = if (nextPage == null) {
                    ProjectsCursor(passStartedAt, index + 1, groups = groupPaths)
                } else {
                    ProjectsCursor(passStartedAt, index, nextPage, groupPaths)
                }
                context.putCursor(name, encodeCursor(next))
                outcomes.any { it.isChange }
            }
            context.heartbeat(changed)
            page = nextPage ?: return
        }
    }

    /** The raw row of [project] listed under the configured [groupPath], or null for one this stream leaves out (see the class doc). */
    private fun toProject(project: JsonObject, groupPath: String, groupId: Long): RawGitLabProject? {
        val id = project.id()
        val pathWithNamespace = project.text("path_with_namespace") ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null,
            PROJECTS_ENDPOINT)
        val archived = (project["archived"] as? JsonPrimitive)?.booleanOrNull ?: false
        val namespacePath = ((project["namespace"] as? JsonObject)?.text("full_path") ?: pathWithNamespace).lowercase()
        val inGroup = namespacePath == groupPath || namespacePath.startsWith("$groupPath/")
        // A branch name or path wider than its column is not stored truncated (PR 7 puts the branch in a URL path): the project is skipped.
        val defaultBranch = project.text("default_branch")
        if (archived || !inGroup || (defaultBranch?.length ?: 0) > MAX_BRANCH_LENGTH ||
            pathWithNamespace.length > MAX_PATH_LENGTH) return null
        return RawGitLabProject(id, pathWithNamespace, groupPath, groupId, defaultBranch, archived, project)
    }

    private fun JsonObject.id(): Long =
        ((get("id") as? JsonPrimitive)?.longOrNull) ?: throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, PROJECTS_ENDPOINT)

    private companion object {
        const val MAX_LOGGED_IDS = 20
        const val MAX_BRANCH_LENGTH = 255
        const val MAX_PATH_LENGTH = 1024
    }
}
