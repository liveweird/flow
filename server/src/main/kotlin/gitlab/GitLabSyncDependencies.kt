package ch.nokillswit.gitlab

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncCursorsService
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * What [GitLabConnector.run] needs beyond [GitLabClientFactory] - bundled so a `testConnection`-only connector (which never calls
 * `run`) constructs without them. `gitlab/GitLab.kt`'s `configureGitLab` is the one production wiring site.
 */
class GitLabSyncDependencies(
    val dataSources: DataSourceService,
    val rawStore: GitLabRawStore,
    val cursors: SyncCursorsService,
    val database: R2dbcDatabase,
    /** `gitlab.usersRefreshHours` in ms (default 24 h): the USERS stream refetches only once the stored users are older; 0 = every SYNC. */
    val usersRefreshMillis: Long,
)
