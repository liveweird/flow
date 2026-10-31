package ch.nokillswit.gitlab

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.norm.CodeActivityStore
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * What [GitLabConnector.run] needs beyond [GitLabClientFactory] - bundled so a `testConnection`-only connector (which never calls
 * `run`) constructs without them. `gitlab/GitLab.kt`'s `configureGitLab` is the one production wiring site.
 */
class GitLabSyncDependencies(
    val dataSources: DataSourceService,
    val rawStore: GitLabRawStore,
    /** The PROCESS-side reads and marks over the raw tables (V44's reader). */
    val processStore: GitLabRawProcessStore,
    /** The `norm.code_*` store PROCESS writes. */
    val codeStore: CodeActivityStore,
    val cursors: SyncCursorsService,
    val database: R2dbcDatabase,
    /** `gitlab.usersRefreshHours` in ms (default 24 h): the USERS stream refetches only once the stored users are older; 0 = every SYNC. */
    val usersRefreshMillis: Long,
    /** `gitlab.commitFilesPerRun`: the diff calls one SYNC run may spend on the COMMIT FILES backlog; the rest waits for the next run. */
    val commitFilesPerRun: Int,
    /** `gitlab.mrDetailsPerRun`: the REST + GraphQL calls one SYNC run may spend on the MR DETAILS backlog; the rest waits. */
    val mrDetailsPerRun: Int,
)
