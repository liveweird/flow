package ch.nokillswit.gitlab

import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.CodeActivityStore
import kotlinx.serialization.json.Json

private val PROFILE_STREAM_JSON = Json { encodeDefaults = true }

/**
 * The GitLab PROFILE stream (the `jira/JiraProfileStream.kt` twin): runs AFTER `code_process` in a SYNC or REPROCESS job (never in a
 * RECONCILE), one recompute over the current `norm.code_*` rows, stored as the `gitlab` section of `source_connections.profile`. No cursor,
 * no paging: a PROFILE that crashes mid-run leaves the previous profile in place and the next successful run recomputes it wholesale.
 * Never calls GitLab ([GITLAB_LOCAL_STREAMS]).
 */
internal class GitLabProfileStream(
    private val rawStore: GitLabRawStore,
    private val processStore: GitLabRawProcessStore,
    private val codeStore: CodeActivityStore,
    private val dataSources: DataSourceService,
) : Stream {
    override val name: String = CODE_PROFILE_STREAM_NAME

    override suspend fun run(context: StreamContext) {
        val section = GitLabProfile.compute(context.connectionId, rawStore, processStore, codeStore)
        val stored = PROFILE_STREAM_JSON.encodeToString(DataProfileSections(gitlab = section))
        dataSources.updateProfile(context.connectionId, stored, context.clock())
        context.incrementProgress("profileComputed")
        context.heartbeat(changed = true)
    }
}
