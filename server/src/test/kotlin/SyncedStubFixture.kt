package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.norm.WorkItemStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.assertEquals

/**
 * A suite-wide, lazily-synced Jira connection over the shared [JiraStubServer] fixture
 * (`.claude/docs/testing.md` "Shared synced fixture"): the full REFERENCE → ISSUES → CHANGELOGS →
 * WORKLOGS → PROCESS → PROFILE backfill `NormalizationPipelineTest`/`JiraSyncPipelineTest`/
 * `DataProfileTest` used to each run from scratch runs exactly ONCE per JVM fork here, and every
 * READ-ONLY test in those three classes reads THIS connection instead of syncing its own.
 *
 * A test that MUTATES state (REPROCESS, RECONCILE, a `processing_version` simulation, PURGE) must
 * never touch this connection — [cloneRawData] copies its raw rows into a fresh connection id, and
 * the test runs only the one stream under test against the clone. [assertUnchanged] (driven by
 * `SyncedStubFixtureTest`) is the tripwire: it re-snapshots this connection and compares it against
 * the baseline captured the moment the backfill first completed.
 */
object SyncedStubFixture {
    val IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")
    const val BACKFILL_FROM = "2025-09-01"

    private val migrated = AtomicBoolean(false)

    /** Idempotent — every test file driving `JiraConnector`/a stream directly needs this before touching the DB. */
    fun ensureMigrated() {
        if (migrated.compareAndSet(false, true)) {
            org.flywaydb.core.Flyway.configure()
                .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    fun rawStore() = JiraRawStore(sharedDatabaseForTests())
    fun cursors() = SyncCursorsService(sharedDatabaseForTests())
    fun workItems() = WorkItemStore(sharedDatabaseForTests())

    suspend fun createConnection(
        dataSources: DataSourceService = dataSources(),
        projectKeys: List<String> = IN_SCOPE_PROJECT_KEYS,
        backfillFrom: String = BACKFILL_FROM,
        namePrefix: String = "jira-clone",
    ): UInt = dataSources.create(
        DataSourceRequest(
            name = unique(namePrefix),
            enabled = true,
            syncIntervalMinutes = 60,
            backfillFrom = backfillFrom,
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                email = "svc-${unique("acct")}@example.com",
                apiToken = "token-${UUID.randomUUID()}",
                projectKeys = projectKeys,
                authScheme = JiraAuthScheme.BASIC,
            ),
        ),
    )

    fun buildJiraHttp(maxRetries: Int = 4): JiraHttp {
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okhttp3.OkHttpClient() }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
        return JiraHttp(httpClient, maxRetries, maxResponseBytes = 33_554_432L, maxConcurrentRequests = 4)
    }

    /** A ready [JiraClient] (its ONE unauthenticated `resolveCloudId()` call already made) against [JiraStubServer]. */
    suspend fun buildClient(maxRetries: Int = 4): JiraClient {
        val stubBaseUrl = JiraStubServer.start()
        val client = HttpJiraClient(buildJiraHttp(maxRetries), stubBaseUrl, stubBaseUrl, "svc@example.com", "token", JiraAuthScheme.BASIC)
        client.resolveCloudId()
        return client
    }

    fun buildConnector(maxRetries: Int = 4): JiraConnector = JiraConnector(
        newClient = { _, email, apiToken, authScheme ->
            val stubBaseUrl = JiraStubServer.start()
            HttpJiraClient(buildJiraHttp(maxRetries), stubBaseUrl, stubBaseUrl, email, apiToken, authScheme)
        },
        sync = JiraSyncDependencies(
            dataSources = dataSources(),
            rawStore = rawStore(),
            cursors = cursors(),
            database = sharedDatabaseForTests(),
            workItems = workItems(),
            incrementalOverlapMinutes = 10,
            issuesPageSize = 100,
        ),
    )

    private fun claimFor(connId: UInt, kind: SyncJobKind = SyncJobKind.SYNC) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = kind,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    suspend fun runConnectorOnce(connector: JiraConnector, connId: UInt, kind: SyncJobKind = SyncJobKind.SYNC) {
        connector.run(SyncJobRunContext(claimFor(connId, kind)) { _, _ -> true })
    }

    /** A fresh [StreamContext] for driving one stream directly against [connId] (no lease loss simulated). */
    fun freshContext(connId: UInt, jobId: UInt = 1u): StreamContext =
        StreamContext(connId, jobId, sharedDatabaseForTests(), cursors(), jobHeartbeat = { _, _ -> true })

    data class Snapshot(
        val rawIssues: Long,
        val rawEntities: Long,
        val rawChangelogs: Long,
        val rawWorklogs: Long,
        val workItemCount: Long,
        val statusIntervalDigest: String,
    )

    /** MD5 over every persisted status interval, ordered deterministically (the `NormalizationPipelineTest` digest pattern). */
    suspend fun statusIntervalDigest(items: WorkItemStore, connId: UInt): String {
        val digest = MessageDigest.getInstance("MD5")
        items.statusIntervalsByIssue(connId).toSortedMap().forEach { (issueId, intervals) ->
            intervals.forEach { interval ->
                val line = "S|$issueId|${interval.seq}|${interval.statusId}|${interval.fromAtMs}|${interval.toAtMs}|${interval.source}\n"
                digest.update(line.toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun snapshot(connId: UInt): Snapshot {
        val store = rawStore()
        val items = workItems()
        return Snapshot(
            rawIssues = store.countIssues(connId),
            rawEntities = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Entities.selectAll().where { JiraRawStore.Entities.connectionId eq connId }.count()
            },
            rawChangelogs = store.countChangelogs(connId),
            rawWorklogs = store.countWorklogs(connId),
            workItemCount = items.countWorkItems(connId),
            statusIntervalDigest = statusIntervalDigest(items, connId),
        )
    }

    private val initLock = Mutex()

    @Volatile
    private var syncedConnectionId: UInt? = null

    private lateinit var baseline: Snapshot

    /**
     * Runs the full backfill exactly once per JVM fork and returns its connection id — idempotent
     * under concurrent callers (the suite runs its tests sequentially today, but a `by lazy`-style
     * double-checked lock keeps this correct even if that ever changes).
     */
    suspend fun connectionId(): UInt {
        syncedConnectionId?.let { return it }
        return initLock.withLock {
            syncedConnectionId?.let { return@withLock it }
            ensureMigrated()
            val connId = createConnection(namePrefix = "jira-shared-fixture")
            runConnectorOnce(buildConnector(), connId)
            baseline = snapshot(connId)
            syncedConnectionId = connId
            connId
        }
    }

    /** Guard against accidental mutation of the shared connection — driven by `SyncedStubFixtureTest`. */
    suspend fun assertUnchanged() {
        val connId = connectionId()
        val current = snapshot(connId)
        assertEquals(baseline, current, "the shared synced fixture's connection $connId must never be mutated by a read-only test")
    }

    /**
     * Copies one connection's `raw.jira_issues`/`raw.jira_entities`/`raw.jira_changelogs`/
     * `raw.jira_worklogs` rows verbatim into another connection id — the cheap substrate a
     * MUTATING test runs its one stream under test against, instead of a second real HTTP sync.
     * Every column, including `needs_processing`/`processing_version`/the tombstone columns, is
     * copied AS-IS: the clone reproduces exactly the "freshly synced" raw state the shared
     * connection was in right after its own backfill, so a test that wants PROCESS to run again
     * (REPROCESS, or after RECONCILE tombstones a row) still has to ask for that explicitly, the
     * same way a real REPROCESS job would (`JiraRawStore.markAllNeedsProcessing`).
     */
    suspend fun cloneRawData(fromConnectionId: UInt, toConnectionId: UInt) {
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            val issues = JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq fromConnectionId }.toList()
            if (issues.isNotEmpty()) {
                JiraRawStore.Issues.batchInsert(issues) { row ->
                    this[JiraRawStore.Issues.connectionId] = toConnectionId
                    this[JiraRawStore.Issues.issueId] = row[JiraRawStore.Issues.issueId]
                    this[JiraRawStore.Issues.issueKey] = row[JiraRawStore.Issues.issueKey]
                    this[JiraRawStore.Issues.projectId] = row[JiraRawStore.Issues.projectId]
                    this[JiraRawStore.Issues.projectKey] = row[JiraRawStore.Issues.projectKey]
                    this[JiraRawStore.Issues.issueUpdatedAt] = row[JiraRawStore.Issues.issueUpdatedAt]
                    this[JiraRawStore.Issues.payload] = row[JiraRawStore.Issues.payload]
                    this[JiraRawStore.Issues.sha256] = row[JiraRawStore.Issues.sha256]
                    this[JiraRawStore.Issues.firstSeenAt] = row[JiraRawStore.Issues.firstSeenAt]
                    this[JiraRawStore.Issues.fetchedAt] = row[JiraRawStore.Issues.fetchedAt]
                    this[JiraRawStore.Issues.changedAt] = row[JiraRawStore.Issues.changedAt]
                    this[JiraRawStore.Issues.changelogSyncedAt] = row[JiraRawStore.Issues.changelogSyncedAt]
                    this[JiraRawStore.Issues.worklogsSyncedAt] = row[JiraRawStore.Issues.worklogsSyncedAt]
                    this[JiraRawStore.Issues.needsProcessing] = row[JiraRawStore.Issues.needsProcessing]
                    this[JiraRawStore.Issues.processedAt] = row[JiraRawStore.Issues.processedAt]
                    this[JiraRawStore.Issues.processedHash] = row[JiraRawStore.Issues.processedHash]
                    this[JiraRawStore.Issues.processingVersion] = row[JiraRawStore.Issues.processingVersion]
                    this[JiraRawStore.Issues.deletedAt] = row[JiraRawStore.Issues.deletedAt]
                    this[JiraRawStore.Issues.movedOutAt] = row[JiraRawStore.Issues.movedOutAt]
                }
            }

            val entities = JiraRawStore.Entities.selectAll().where { JiraRawStore.Entities.connectionId eq fromConnectionId }.toList()
            if (entities.isNotEmpty()) {
                JiraRawStore.Entities.batchInsert(entities) { row ->
                    this[JiraRawStore.Entities.connectionId] = toConnectionId
                    this[JiraRawStore.Entities.kind] = row[JiraRawStore.Entities.kind]
                    this[JiraRawStore.Entities.entityId] = row[JiraRawStore.Entities.entityId]
                    this[JiraRawStore.Entities.payload] = row[JiraRawStore.Entities.payload]
                    this[JiraRawStore.Entities.sha256] = row[JiraRawStore.Entities.sha256]
                    this[JiraRawStore.Entities.firstSeenAt] = row[JiraRawStore.Entities.firstSeenAt]
                    this[JiraRawStore.Entities.lastSeenAt] = row[JiraRawStore.Entities.lastSeenAt]
                    this[JiraRawStore.Entities.changedAt] = row[JiraRawStore.Entities.changedAt]
                    this[JiraRawStore.Entities.deletedAt] = row[JiraRawStore.Entities.deletedAt]
                }
            }

            val changelogs = JiraRawStore.Changelogs.selectAll().where { JiraRawStore.Changelogs.connectionId eq fromConnectionId }.toList()
            if (changelogs.isNotEmpty()) {
                JiraRawStore.Changelogs.batchInsert(changelogs) { row ->
                    this[JiraRawStore.Changelogs.connectionId] = toConnectionId
                    this[JiraRawStore.Changelogs.historyId] = row[JiraRawStore.Changelogs.historyId]
                    this[JiraRawStore.Changelogs.issueId] = row[JiraRawStore.Changelogs.issueId]
                    this[JiraRawStore.Changelogs.createdAt] = row[JiraRawStore.Changelogs.createdAt]
                    this[JiraRawStore.Changelogs.authorAccountId] = row[JiraRawStore.Changelogs.authorAccountId]
                    this[JiraRawStore.Changelogs.payload] = row[JiraRawStore.Changelogs.payload]
                    this[JiraRawStore.Changelogs.fetchedAt] = row[JiraRawStore.Changelogs.fetchedAt]
                }
            }

            val worklogs = JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq fromConnectionId }.toList()
            if (worklogs.isNotEmpty()) {
                JiraRawStore.Worklogs.batchInsert(worklogs) { row ->
                    this[JiraRawStore.Worklogs.connectionId] = toConnectionId
                    this[JiraRawStore.Worklogs.worklogId] = row[JiraRawStore.Worklogs.worklogId]
                    this[JiraRawStore.Worklogs.issueId] = row[JiraRawStore.Worklogs.issueId]
                    this[JiraRawStore.Worklogs.worklogUpdatedAt] = row[JiraRawStore.Worklogs.worklogUpdatedAt]
                    this[JiraRawStore.Worklogs.payload] = row[JiraRawStore.Worklogs.payload]
                    this[JiraRawStore.Worklogs.sha256] = row[JiraRawStore.Worklogs.sha256]
                    this[JiraRawStore.Worklogs.firstSeenAt] = row[JiraRawStore.Worklogs.firstSeenAt]
                    this[JiraRawStore.Worklogs.fetchedAt] = row[JiraRawStore.Worklogs.fetchedAt]
                    this[JiraRawStore.Worklogs.changedAt] = row[JiraRawStore.Worklogs.changedAt]
                    this[JiraRawStore.Worklogs.deletedAt] = row[JiraRawStore.Worklogs.deletedAt]
                }
            }
        }
    }
}
