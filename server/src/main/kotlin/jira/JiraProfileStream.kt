package ch.nokillswit.jira

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.WorkItemStore
import kotlinx.serialization.json.Json

private val PROFILE_STREAM_JSON = Json { encodeDefaults = true }

/**
 * The PROFILE stream (v0.2.0 plan §7/§8/§12 item 9): runs AFTER PROCESS in a SYNC or REPROCESS job
 * (`jira/JiraConnector.kt`) — a single, O(1)-cursor recompute over the connection's now-current
 * `norm.*` rows, stored on `source_connections.profile`/`profile_at`
 * (`ingest/DataSourceService.kt`'s `updateProfile`). No paging, no resume: unlike every OTHER
 * stream, a PROFILE that crashes mid-run simply leaves the connection's PREVIOUS profile in place
 * — the next successful SYNC/REPROCESS recomputes it wholesale, exactly like PROCESS's own
 * reference-row rebuild.
 */
class JiraProfileStream(
    private val rawStore: JiraRawStore,
    private val workItemStore: WorkItemStore,
    private val dataSources: DataSourceService,
) : Stream {
    override val name: String = "profile"

    override suspend fun run(context: StreamContext) {
        val sections = JiraProfile.compute(context.connectionId, rawStore, workItemStore)
        dataSources.updateProfile(context.connectionId, PROFILE_STREAM_JSON.encodeToString(sections), context.clock())
        context.incrementProgress("profileComputed")
        context.heartbeat()
    }
}
