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
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
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
 * the baseline captured the moment the backfill first completed — and so does every later
 * [connectionId] call (the tripwire test alone would prove nothing when it runs first; [FixtureTripwire]).
 */
object SyncedStubFixture {
    val IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")
    const val BACKFILL_FROM = "2025-09-01"

    /** Idempotent — delegates to [PostgresTestSupport], whose container init migrates (class-order robust under forks). */
    fun ensureMigrated() = PostgresTestSupport.ensureMigrated()

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

    fun buildJiraHttp(maxRetries: Int = 4, maxResponseBytes: Long = 33_554_432L): JiraHttp {
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okhttp3.OkHttpClient() }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
        return JiraHttp(httpClient, maxRetries, maxResponseBytes, maxConcurrentRequests = 4)
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

    // A snapshot is ~25 ms, so EVERY connectionId() call re-verifies the connection (FixtureTripwire).
    private val tripwire = FixtureTripwire<Snapshot>("synced fixture", perCallerClass = false)

    /**
     * Runs the full backfill exactly once per JVM fork and returns its connection id — idempotent
     * under concurrent callers (the suite runs its tests sequentially today, but a `by lazy`-style
     * double-checked lock keeps this correct even if that ever changes). Every call after the
     * first also re-verifies the connection against the baseline captured when the backfill
     * completed ([FixtureTripwire]): a consumer that mutated it fails the next test that asks.
     */
    suspend fun connectionId(): UInt {
        val connId = ensureSynced()
        tripwire.verify(connId) { snapshot(connId) }
        return connId
    }

    private suspend fun ensureSynced(): UInt {
        syncedConnectionId?.let { return it }
        return initLock.withLock {
            syncedConnectionId?.let { return@withLock it }
            ensureMigrated()
            val connId = createConnection(namePrefix = "jira-shared-fixture")
            runConnectorOnce(buildConnector(), connId)
            tripwire.arm(snapshot(connId))
            syncedConnectionId = connId
            connId
        }
    }

    /** Guard against accidental mutation of the shared connection — driven by `SyncedStubFixtureTest`. */
    suspend fun assertUnchanged() {
        val connId = ensureSynced()
        tripwire.verify(connId, force = true) { snapshot(connId) }
    }

    internal val RAW_CLONE_TABLES: List<Table> = listOf(
        JiraRawStore.Issues, JiraRawStore.Entities, JiraRawStore.Changelogs, JiraRawStore.Worklogs,
    )

    internal val PROCESSED_CLONE_TABLES: List<Table> = listOf(
        WorkItemStore.WorkItems, WorkItemStore.StatusIntervals, WorkItemStore.FieldIntervals, WorkItemStore.FieldChanges,
        WorkItemStore.Worklogs, WorkItemStore.Statuses, WorkItemStore.People, WorkItemStore.Boards, WorkItemStore.BoardColumns,
        WorkItemStore.Sprints,
    )

    private const val CONNECTION_ID_COLUMN = "connection_id"

    /** Tables whose Exposed column set has already been proven equal to the database's (once per JVM fork). */
    private val verifiedColumnSets = ConcurrentHashMap.newKeySet<String>()

    private fun quoted(column: Column<*>) = "\"${column.name}\""

    /**
     * ONE `INSERT ... SELECT` for [table]: every column the Exposed object declares is copied
     * verbatim from [fromConnectionId]'s rows, except `connection_id` (rewritten to [toConnectionId])
     * and the surrogate SERIAL `id` (auto-increment columns get fresh values in the clone). The
     * column list comes from the table object, and [assertColumnSetMatchesDatabase] proves it equals
     * the database's own, so a column added by a migration but forgotten in the Exposed object (or
     * the reverse) fails the clone instead of being silently dropped.
     */
    private fun cloneTableSql(table: Table, fromConnectionId: UInt, toConnectionId: UInt): String {
        val copied = table.columns.filter { it.columnType !is AutoIncColumnType<*> && it.name != CONNECTION_ID_COLUMN }
        val targetList = (listOf("\"$CONNECTION_ID_COLUMN\"") + copied.map(::quoted)).joinToString(", ")
        val selectList = (listOf("$toConnectionId") + copied.map(::quoted)).joinToString(", ")
        return "INSERT INTO ${table.tableName} ($targetList) SELECT $selectList " +
            "FROM ${table.tableName} WHERE $CONNECTION_ID_COLUMN = $fromConnectionId"
    }

    private fun assertColumnSetMatchesDatabase(conn: java.sql.Connection, table: Table) {
        if (table.tableName in verifiedColumnSets) return
        val (schema, name) = table.tableName.split('.')
        val sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?"
        val database = conn.prepareStatement(sql).use { st ->
            st.setString(1, schema)
            st.setString(2, name)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        assertEquals(
            database,
            table.columns.map { it.name }.toSet(),
            "${table.tableName}: the Exposed columns must equal the database's, or the SQL clone would drop one",
        )
        verifiedColumnSets += table.tableName
    }

    /** `table name -> row count` for [connId] over every table [cloneRawData]/[cloneProcessedData] copy (raw and norm). */
    internal fun cloneTableRowCounts(connId: UInt): Map<String, Long> =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.createStatement().use { st ->
                (RAW_CLONE_TABLES + PROCESSED_CLONE_TABLES).associate { table ->
                    table.tableName to st.executeQuery("SELECT COUNT(*) FROM ${table.tableName} WHERE $CONNECTION_ID_COLUMN = $connId")
                        .use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }

    /** Runs one `INSERT ... SELECT` per table (plus [extra]) in ONE database transaction on a plain JDBC connection. */
    private fun cloneInTransaction(
        tables: List<Table>,
        fromConnectionId: UInt,
        toConnectionId: UInt,
        extra: (java.sql.Statement) -> Unit = {},
    ) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    for (table in tables) {
                        assertColumnSetMatchesDatabase(conn, table)
                        st.executeUpdate(cloneTableSql(table, fromConnectionId, toConnectionId))
                    }
                    extra(st)
                }
                conn.commit()
            } catch (failure: Throwable) {
                // A failing rollback must never mask the failure that caused it.
                runCatching { conn.rollback() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }

    /**
     * Copies one connection's `raw.jira_issues`/`raw.jira_entities`/`raw.jira_changelogs`/
     * `raw.jira_worklogs` rows verbatim into another connection id — the cheap substrate a
     * MUTATING test runs its one stream under test against, instead of a second real HTTP sync.
     * One SQL `INSERT ... SELECT` per table ([cloneTableSql]): every column, including
     * `needs_processing`/`processing_version`/the tombstone columns, is copied AS-IS, so the clone
     * reproduces exactly the "freshly synced" raw state the shared connection was in right after its
     * own backfill; a test that wants PROCESS to run again (REPROCESS, or after RECONCILE tombstones
     * a row) still has to ask for that explicitly, the same way a real REPROCESS job would
     * (`JiraRawStore.markAllNeedsProcessing`).
     */
    suspend fun cloneRawData(fromConnectionId: UInt, toConnectionId: UInt) {
        cloneInTransaction(RAW_CLONE_TABLES, fromConnectionId, toConnectionId)
    }

    /**
     * Extends [cloneRawData] with a CHEAP PROCESS/PROFILE-free clone: every `norm.*` row (work
     * items, status/field intervals, field changes, worklogs, and the rebuilt-wholesale reference
     * tables — statuses/people/boards/board_columns/sprints) plus `source_connections.profile`/
     * `profile_at`, all copied verbatim and rewritten to [toConnectionId] — never re-running
     * `jira/JiraProcessStream.kt`/`jira/JiraProfileStream.kt` against the clone, since the SHARED
     * connection [fromConnectionId] already ran them once (`.claude/docs/testing.md` "Shared synced
     * fixture"). Same mechanism as [cloneRawData] (one SQL `INSERT ... SELECT` per table, columns from
     * the Exposed table objects, surrogate `SERIAL id` columns never copied); the raw and processed
     * copies run in ONE transaction. [DerivedStubFixture] is this function's first consumer;
     * `SyncedStubFixtureTest` pins that the clone's own status-interval digest matches the source
     * connection's.
     */
    suspend fun cloneProcessedData(fromConnectionId: UInt, toConnectionId: UInt) {
        cloneInTransaction(RAW_CLONE_TABLES + PROCESSED_CLONE_TABLES, fromConnectionId, toConnectionId) { st ->
            val connections = DataSourceService.Connections.tableName
            val updated = st.executeUpdate(
                "UPDATE $connections SET profile = src.profile, profile_at = src.profile_at " +
                    "FROM $connections src WHERE src.id = $fromConnectionId AND $connections.id = $toConnectionId",
            )
            assertEquals(1, updated, "the clone's source_connections row must receive the source's profile")
        }
    }
}
