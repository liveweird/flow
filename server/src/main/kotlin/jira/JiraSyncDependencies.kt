package ch.nokillswit.jira

import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.norm.WorkItemStore
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * What [JiraConnector.run] needs beyond [JiraClientFactory] — bundled so `JiraConnectorTest`'s
 * `testConnection`-only fixtures (which never call `run`) keep constructing a bare `JiraConnector`
 * without threading these through. `jira/Jira.kt`'s `configureJira` is the one production wiring
 * site.
 */
class JiraSyncDependencies(
    val dataSources: DataSourceService,
    val rawStore: JiraRawStore,
    val cursors: SyncCursorsService,
    val database: R2dbcDatabase,
    /** The normalized layer's write target (v0.2.0 plan §0 A3/§8, `norm/WorkItemStore.kt`) — PROCESS's and PURGE's one consumer. */
    val workItems: WorkItemStore,
    /** `jira.incrementalOverlapMinutes` (default 10, plan §5/§7) — the ISSUES stream's re-fetch window past the last watermark. */
    val incrementalOverlapMinutes: Long,
    val issuesPageSize: Int,
    /**
     * `jira.changelogBulkSize` (default 50, plan §5/§7) — the CHANGELOGS stream's `changelog/bulkfetch`
     * chunk size; MUST match the stub's chunking (`sample-data/README.md`).
     */
    val changelogBulkSize: Int = DEFAULT_CHANGELOG_BULK_SIZE,
    /** `jira.changelogPageSize` (default 1,000, Jira's own default) — the histories asked per `changelog/bulkfetch` page. */
    val changelogPageSize: Int = DEFAULT_CHANGELOG_PAGE_SIZE,
    /**
     * `jira.usersRefreshHours` in ms (default 24 h): REFERENCE refetches the users only once they are older
     * ([JiraReferenceStream]); 0 = every SYNC.
     */
    val usersRefreshMillis: Long = DEFAULT_USERS_REFRESH_HOURS * MILLIS_PER_HOUR,
)

private const val DEFAULT_CHANGELOG_BULK_SIZE = 50
private const val DEFAULT_USERS_REFRESH_HOURS = 24L
