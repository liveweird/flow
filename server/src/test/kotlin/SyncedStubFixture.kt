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
import org.jetbrains.exposed.v1.r2dbc.update
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
        // A metrics test drives DERIVE itself under a pinned clock: its connection must be DISABLED,
        // or any other test's config change (bumpRevision enqueues a DERIVE for every ENABLED
        // connection) lets a running worker re-derive it with the real clock mid-test.
        enabled: Boolean = true,
    ): UInt = dataSources.create(
        DataSourceRequest(
            name = unique(namePrefix),
            enabled = enabled,
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

    /**
     * Extends [cloneRawData] with a CHEAP PROCESS/PROFILE-free clone: every `norm.*` row (work
     * items, status/field intervals, field changes, worklogs, and the rebuilt-wholesale reference
     * tables — statuses/people/boards/board_columns/sprints) plus `source_connections.profile`/
     * `profile_at`, all copied verbatim and rewritten to [toConnectionId] — never re-running
     * `jira/JiraProcessStream.kt`/`jira/JiraProfileStream.kt` against the clone, since the SHARED
     * connection [fromConnectionId] already ran them once (`.claude/docs/testing.md` "Shared synced
     * fixture"). Surrogate `SERIAL id` columns (`StatusIntervals`/`FieldIntervals`/`FieldChanges`)
     * are never copied — every other column is copied as-is. [DerivedStubFixture] is this
     * function's first consumer; `SyncedStubFixtureTest` pins that the clone's own status-interval
     * digest matches the source connection's.
     */
    suspend fun cloneProcessedData(fromConnectionId: UInt, toConnectionId: UInt) {
        cloneRawData(fromConnectionId, toConnectionId)
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            val workItemRows = WorkItemStore.WorkItems.selectAll()
                .where { WorkItemStore.WorkItems.connectionId eq fromConnectionId }.toList()
            if (workItemRows.isNotEmpty()) {
                WorkItemStore.WorkItems.batchInsert(workItemRows) { row ->
                    this[WorkItemStore.WorkItems.connectionId] = toConnectionId
                    this[WorkItemStore.WorkItems.issueId] = row[WorkItemStore.WorkItems.issueId]
                    this[WorkItemStore.WorkItems.issueKey] = row[WorkItemStore.WorkItems.issueKey]
                    this[WorkItemStore.WorkItems.projectKey] = row[WorkItemStore.WorkItems.projectKey]
                    this[WorkItemStore.WorkItems.issueType] = row[WorkItemStore.WorkItems.issueType]
                    this[WorkItemStore.WorkItems.isSubtask] = row[WorkItemStore.WorkItems.isSubtask]
                    this[WorkItemStore.WorkItems.parentIssueId] = row[WorkItemStore.WorkItems.parentIssueId]
                    this[WorkItemStore.WorkItems.summary] = row[WorkItemStore.WorkItems.summary]
                    this[WorkItemStore.WorkItems.statusId] = row[WorkItemStore.WorkItems.statusId]
                    this[WorkItemStore.WorkItems.statusName] = row[WorkItemStore.WorkItems.statusName]
                    this[WorkItemStore.WorkItems.statusCategory] = row[WorkItemStore.WorkItems.statusCategory]
                    this[WorkItemStore.WorkItems.resolution] = row[WorkItemStore.WorkItems.resolution]
                    this[WorkItemStore.WorkItems.priority] = row[WorkItemStore.WorkItems.priority]
                    this[WorkItemStore.WorkItems.assigneeAccountId] = row[WorkItemStore.WorkItems.assigneeAccountId]
                    this[WorkItemStore.WorkItems.reporterAccountId] = row[WorkItemStore.WorkItems.reporterAccountId]
                    this[WorkItemStore.WorkItems.createdAt] = row[WorkItemStore.WorkItems.createdAt]
                    this[WorkItemStore.WorkItems.updatedAt] = row[WorkItemStore.WorkItems.updatedAt]
                    this[WorkItemStore.WorkItems.resolvedAt] = row[WorkItemStore.WorkItems.resolvedAt]
                    this[WorkItemStore.WorkItems.storyPoints] = row[WorkItemStore.WorkItems.storyPoints]
                    this[WorkItemStore.WorkItems.originalEstimateSeconds] = row[WorkItemStore.WorkItems.originalEstimateSeconds]
                    this[WorkItemStore.WorkItems.timeSpentSeconds] = row[WorkItemStore.WorkItems.timeSpentSeconds]
                    this[WorkItemStore.WorkItems.labels] = row[WorkItemStore.WorkItems.labels]
                    this[WorkItemStore.WorkItems.components] = row[WorkItemStore.WorkItems.components]
                    this[WorkItemStore.WorkItems.fixVersions] = row[WorkItemStore.WorkItems.fixVersions]
                    this[WorkItemStore.WorkItems.currentSprintIds] = row[WorkItemStore.WorkItems.currentSprintIds]
                    this[WorkItemStore.WorkItems.teamValue] = row[WorkItemStore.WorkItems.teamValue]
                    this[WorkItemStore.WorkItems.flagged] = row[WorkItemStore.WorkItems.flagged]
                    this[WorkItemStore.WorkItems.rank] = row[WorkItemStore.WorkItems.rank]
                    this[WorkItemStore.WorkItems.hierarchyLevel] = row[WorkItemStore.WorkItems.hierarchyLevel]
                    this[WorkItemStore.WorkItems.dueAt] = row[WorkItemStore.WorkItems.dueAt]
                    this[WorkItemStore.WorkItems.customFields] = row[WorkItemStore.WorkItems.customFields]
                    this[WorkItemStore.WorkItems.anomalies] = row[WorkItemStore.WorkItems.anomalies]
                    this[WorkItemStore.WorkItems.deletedAt] = row[WorkItemStore.WorkItems.deletedAt]
                    this[WorkItemStore.WorkItems.movedOutAt] = row[WorkItemStore.WorkItems.movedOutAt]
                    this[WorkItemStore.WorkItems.processedAt] = row[WorkItemStore.WorkItems.processedAt]
                    this[WorkItemStore.WorkItems.processingVersion] = row[WorkItemStore.WorkItems.processingVersion]
                }
            }

            val statusIntervalRows = WorkItemStore.StatusIntervals.selectAll()
                .where { WorkItemStore.StatusIntervals.connectionId eq fromConnectionId }.toList()
            if (statusIntervalRows.isNotEmpty()) {
                WorkItemStore.StatusIntervals.batchInsert(statusIntervalRows) { row ->
                    this[WorkItemStore.StatusIntervals.connectionId] = toConnectionId
                    this[WorkItemStore.StatusIntervals.issueId] = row[WorkItemStore.StatusIntervals.issueId]
                    this[WorkItemStore.StatusIntervals.seq] = row[WorkItemStore.StatusIntervals.seq]
                    this[WorkItemStore.StatusIntervals.statusId] = row[WorkItemStore.StatusIntervals.statusId]
                    this[WorkItemStore.StatusIntervals.statusName] = row[WorkItemStore.StatusIntervals.statusName]
                    this[WorkItemStore.StatusIntervals.statusCategory] = row[WorkItemStore.StatusIntervals.statusCategory]
                    this[WorkItemStore.StatusIntervals.fromAt] = row[WorkItemStore.StatusIntervals.fromAt]
                    this[WorkItemStore.StatusIntervals.toAt] = row[WorkItemStore.StatusIntervals.toAt]
                    this[WorkItemStore.StatusIntervals.intervalSource] = row[WorkItemStore.StatusIntervals.intervalSource]
                }
            }

            val fieldIntervalRows = WorkItemStore.FieldIntervals.selectAll()
                .where { WorkItemStore.FieldIntervals.connectionId eq fromConnectionId }.toList()
            if (fieldIntervalRows.isNotEmpty()) {
                WorkItemStore.FieldIntervals.batchInsert(fieldIntervalRows) { row ->
                    this[WorkItemStore.FieldIntervals.connectionId] = toConnectionId
                    this[WorkItemStore.FieldIntervals.issueId] = row[WorkItemStore.FieldIntervals.issueId]
                    this[WorkItemStore.FieldIntervals.field] = row[WorkItemStore.FieldIntervals.field]
                    this[WorkItemStore.FieldIntervals.seq] = row[WorkItemStore.FieldIntervals.seq]
                    this[WorkItemStore.FieldIntervals.valueId] = row[WorkItemStore.FieldIntervals.valueId]
                    this[WorkItemStore.FieldIntervals.valueText] = row[WorkItemStore.FieldIntervals.valueText]
                    this[WorkItemStore.FieldIntervals.fromAt] = row[WorkItemStore.FieldIntervals.fromAt]
                    this[WorkItemStore.FieldIntervals.toAt] = row[WorkItemStore.FieldIntervals.toAt]
                }
            }

            val fieldChangeRows = WorkItemStore.FieldChanges.selectAll()
                .where { WorkItemStore.FieldChanges.connectionId eq fromConnectionId }.toList()
            if (fieldChangeRows.isNotEmpty()) {
                WorkItemStore.FieldChanges.batchInsert(fieldChangeRows) { row ->
                    this[WorkItemStore.FieldChanges.connectionId] = toConnectionId
                    this[WorkItemStore.FieldChanges.issueId] = row[WorkItemStore.FieldChanges.issueId]
                    this[WorkItemStore.FieldChanges.seq] = row[WorkItemStore.FieldChanges.seq]
                    this[WorkItemStore.FieldChanges.field] = row[WorkItemStore.FieldChanges.field]
                    this[WorkItemStore.FieldChanges.changedAt] = row[WorkItemStore.FieldChanges.changedAt]
                    this[WorkItemStore.FieldChanges.fromValue] = row[WorkItemStore.FieldChanges.fromValue]
                    this[WorkItemStore.FieldChanges.fromText] = row[WorkItemStore.FieldChanges.fromText]
                    this[WorkItemStore.FieldChanges.toValue] = row[WorkItemStore.FieldChanges.toValue]
                    this[WorkItemStore.FieldChanges.toText] = row[WorkItemStore.FieldChanges.toText]
                    this[WorkItemStore.FieldChanges.fieldId] = row[WorkItemStore.FieldChanges.fieldId]
                }
            }

            val worklogRows = WorkItemStore.Worklogs.selectAll()
                .where { WorkItemStore.Worklogs.connectionId eq fromConnectionId }.toList()
            if (worklogRows.isNotEmpty()) {
                WorkItemStore.Worklogs.batchInsert(worklogRows) { row ->
                    this[WorkItemStore.Worklogs.connectionId] = toConnectionId
                    this[WorkItemStore.Worklogs.worklogId] = row[WorkItemStore.Worklogs.worklogId]
                    this[WorkItemStore.Worklogs.issueId] = row[WorkItemStore.Worklogs.issueId]
                    this[WorkItemStore.Worklogs.authorAccountId] = row[WorkItemStore.Worklogs.authorAccountId]
                    this[WorkItemStore.Worklogs.startedAt] = row[WorkItemStore.Worklogs.startedAt]
                    this[WorkItemStore.Worklogs.timeSpentSeconds] = row[WorkItemStore.Worklogs.timeSpentSeconds]
                    this[WorkItemStore.Worklogs.createdAt] = row[WorkItemStore.Worklogs.createdAt]
                    this[WorkItemStore.Worklogs.updatedAt] = row[WorkItemStore.Worklogs.updatedAt]
                }
            }

            val statusRows = WorkItemStore.Statuses.selectAll()
                .where { WorkItemStore.Statuses.connectionId eq fromConnectionId }.toList()
            if (statusRows.isNotEmpty()) {
                WorkItemStore.Statuses.batchInsert(statusRows) { row ->
                    this[WorkItemStore.Statuses.connectionId] = toConnectionId
                    this[WorkItemStore.Statuses.statusId] = row[WorkItemStore.Statuses.statusId]
                    this[WorkItemStore.Statuses.name] = row[WorkItemStore.Statuses.name]
                    this[WorkItemStore.Statuses.category] = row[WorkItemStore.Statuses.category]
                }
            }

            val peopleRows = WorkItemStore.People.selectAll()
                .where { WorkItemStore.People.connectionId eq fromConnectionId }.toList()
            if (peopleRows.isNotEmpty()) {
                WorkItemStore.People.batchInsert(peopleRows) { row ->
                    this[WorkItemStore.People.connectionId] = toConnectionId
                    this[WorkItemStore.People.accountId] = row[WorkItemStore.People.accountId]
                    this[WorkItemStore.People.displayName] = row[WorkItemStore.People.displayName]
                    this[WorkItemStore.People.email] = row[WorkItemStore.People.email]
                    this[WorkItemStore.People.active] = row[WorkItemStore.People.active]
                }
            }

            val boardRows = WorkItemStore.Boards.selectAll()
                .where { WorkItemStore.Boards.connectionId eq fromConnectionId }.toList()
            if (boardRows.isNotEmpty()) {
                WorkItemStore.Boards.batchInsert(boardRows) { row ->
                    this[WorkItemStore.Boards.connectionId] = toConnectionId
                    this[WorkItemStore.Boards.boardId] = row[WorkItemStore.Boards.boardId]
                    this[WorkItemStore.Boards.name] = row[WorkItemStore.Boards.name]
                    this[WorkItemStore.Boards.boardType] = row[WorkItemStore.Boards.boardType]
                    this[WorkItemStore.Boards.projectKey] = row[WorkItemStore.Boards.projectKey]
                }
            }

            val boardColumnRows = WorkItemStore.BoardColumns.selectAll()
                .where { WorkItemStore.BoardColumns.connectionId eq fromConnectionId }.toList()
            if (boardColumnRows.isNotEmpty()) {
                WorkItemStore.BoardColumns.batchInsert(boardColumnRows) { row ->
                    this[WorkItemStore.BoardColumns.connectionId] = toConnectionId
                    this[WorkItemStore.BoardColumns.boardId] = row[WorkItemStore.BoardColumns.boardId]
                    this[WorkItemStore.BoardColumns.seq] = row[WorkItemStore.BoardColumns.seq]
                    this[WorkItemStore.BoardColumns.name] = row[WorkItemStore.BoardColumns.name]
                    this[WorkItemStore.BoardColumns.statusIds] = row[WorkItemStore.BoardColumns.statusIds]
                }
            }

            val sprintRows = WorkItemStore.Sprints.selectAll()
                .where { WorkItemStore.Sprints.connectionId eq fromConnectionId }.toList()
            if (sprintRows.isNotEmpty()) {
                WorkItemStore.Sprints.batchInsert(sprintRows) { row ->
                    this[WorkItemStore.Sprints.connectionId] = toConnectionId
                    this[WorkItemStore.Sprints.sprintId] = row[WorkItemStore.Sprints.sprintId]
                    this[WorkItemStore.Sprints.boardId] = row[WorkItemStore.Sprints.boardId]
                    this[WorkItemStore.Sprints.name] = row[WorkItemStore.Sprints.name]
                    this[WorkItemStore.Sprints.state] = row[WorkItemStore.Sprints.state]
                    this[WorkItemStore.Sprints.startAt] = row[WorkItemStore.Sprints.startAt]
                    this[WorkItemStore.Sprints.endAt] = row[WorkItemStore.Sprints.endAt]
                    this[WorkItemStore.Sprints.goal] = row[WorkItemStore.Sprints.goal]
                    this[WorkItemStore.Sprints.completeAt] = row[WorkItemStore.Sprints.completeAt]
                }
            }

            val sourceProfileRow = DataSourceService.Connections.selectAll()
                .where { DataSourceService.Connections.id eq fromConnectionId }.toList().single()
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq toConnectionId }) {
                it[profile] = sourceProfileRow[DataSourceService.Connections.profile]
                it[profileAt] = sourceProfileRow[DataSourceService.Connections.profileAt]
            }
        }
    }
}
