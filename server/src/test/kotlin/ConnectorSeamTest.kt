package ch.nokillswit

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectorRegistry
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.GitLabConnectionSettings
import ch.nokillswit.ingest.JiraConnectionSettings
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.connectionSettings
import ch.nokillswit.ingest.connectorRegistry
import ch.nokillswit.ingest.decodeSettings
import ch.nokillswit.ingest.encodeSettings
import ch.nokillswit.ingest.jiraSettings
import ch.nokillswit.jira.JiraConnector
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The worker's clock (2024-01-01T01:00:00Z): before the default RECONCILE hour, so only a SYNC is due for a fresh connection. */
private val TICK_CLOCK_MILLIS = java.time.Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()

/**
 * The connector seam (`.claude/docs/gitlab-integration.md` "The connector seam", `ingest/ConnectorRegistry.kt`, `ingest/Connector.kt`):
 * the additive registry, `ConnectionTarget` dispatch, the V40 `kind` CHECK, and a job whose kind has no connector failing cleanly
 * (`CONNECTOR_UNAVAILABLE`) without stopping the worker loop.
 */
class ConnectorSeamTest {
    private val insertedGitLabRows = mutableListOf<UInt>()
    private val createdJiraRows = mutableListOf<UInt>()
    private val workerId = "seam-worker-${UUID.randomUUID().toString().take(8)}"

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        insertedGitLabRows.forEach(GitLabRowFixture::remove)
        createdJiraRows.forEach { SyncedStubFixture.dataSources().delete(it) }
    }

    private class RecordingConnector(override val kind: DataSourceKind, private val onRun: suspend () -> Unit = {}) : Connector {
        val targets = mutableListOf<ConnectionTarget>()
        override suspend fun testConnection(target: ConnectionTarget): ConnectionTestResult {
            targets += target
            return ConnectionTestResult(rows = emptyList())
        }

        override suspend fun run(context: SyncJobRunContext) = onRun()
    }

    @Test
    fun `the registry is additive, hands out the map, and refuses a second connector for one kind`() {
        val registry = ConnectorRegistry()
        val jira = RecordingConnector(DataSourceKind.JIRA_CLOUD)
        val gitlab = RecordingConnector(DataSourceKind.GITLAB)
        assertTrue(registry.connectors().isEmpty())
        assertNull(registry.connectorFor(DataSourceKind.GITLAB))

        registry.register(jira)
        registry.register(gitlab) // adding GITLAB must not replace JIRA_CLOUD (the bug the old map attribute had)
        assertSame(jira, registry.connectorFor(DataSourceKind.JIRA_CLOUD))
        assertSame(gitlab, registry.connectorFor(DataSourceKind.GITLAB))
        assertEquals(setOf(DataSourceKind.JIRA_CLOUD, DataSourceKind.GITLAB), registry.connectors().keys)

        val duplicate = assertFailsWith<IllegalStateException> { registry.register(RecordingConnector(DataSourceKind.GITLAB)) }
        assertTrue(duplicate.message!!.contains("GITLAB"))
        assertSame(gitlab, registry.connectorFor(DataSourceKind.GITLAB), "the refused duplicate did not replace the first")

        // The map is a snapshot: a later registration is not retroactively visible in one already handed out.
        val snapshot = registry.connectors()
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `the booted app registers JIRA_CLOUD in the registry and nothing else`() = testApplication {
        configureApp()
        application {
            val registry = connectorRegistry()
            assertTrue(registry.connectorFor(DataSourceKind.JIRA_CLOUD) is JiraConnector)
            assertNull(registry.connectorFor(DataSourceKind.GITLAB), "no GitLab connector exists before its own release")
            assertEquals(1, registry.connectors().size)
        }
        startApplication()
    }

    @Test
    fun `each connector answers only its own ConnectionTarget case, and no target prints its secret`() = runBlocking<Unit> {
        val gitlab = ConnectionTarget.GitLab("https://gitlab.example.com", "glpat-top-secret", listOf("acme"))
        val jira = ConnectionTarget.Jira(
            "https://acme.atlassian.net", "svc@example.com", "jira-top-secret", listOf("ENG"), JiraAuthScheme.BASIC,
        )

        // A GitLab connector receives the GitLab case intact.
        val recording = RecordingConnector(DataSourceKind.GITLAB)
        recording.testConnection(gitlab)
        assertEquals(listOf<ConnectionTarget>(gitlab), recording.targets)

        // The Jira connector refuses a target that is not its own (an internal error, never reachable from a route).
        val jiraConnector = JiraConnector(newClient = { _, _, _, _ -> error("not reached") })
        val refused = assertFailsWith<IllegalStateException> { jiraConnector.testConnection(gitlab) }
        assertTrue(refused.message!!.contains("GitLab"))

        assertFalse(gitlab.toString().contains("top-secret"), gitlab.toString())
        assertFalse(jira.toString().contains("top-secret"), jira.toString())
        assertTrue(gitlab.toString().contains("***") && jira.toString().contains("***"))
    }

    @Test
    fun `V40 admits GITLAB in the kind CHECK, still refuses any other value, and keeps the constraint name`() {
        PostgresTestSupport.connect().use { c ->
            val definition = c.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'source_connections_kind_check' " +
                    "AND conrelid = 'public.source_connections'::regclass",
            ).use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
            assertNotNull(definition, "the constraint V8 declared inline is named source_connections_kind_check")
            assertTrue(definition.contains("GITLAB") && definition.contains("JIRA_CLOUD"), definition)
        }
        val id = runBlocking { GitLabRowFixture.insert("seam-check").also { insertedGitLabRows += it } }
        assertNotNull(id)
        val rejected = assertFailsWith<SQLException> {
            PostgresTestSupport.connect().use { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO source_connections (kind, name, enabled, secret, settings, sync_interval_minutes, backfill_from, " +
                            "reconcile_hour_utc, config_revision, consecutive_failures, created_at, updated_at) VALUES " +
                            "('BOGUS', 'bogus-${UUID.randomUUID()}', FALSE, '', '{}', 60, '2025-01-01', 3, 1, 0, 1, 1)",
                    )
                }
            }
        }
        assertTrue(rejected.message!!.contains("source_connections_kind_check"), rejected.message)
    }

    @Test
    fun `settings are decoded by kind, and Jira's stored JSON shape is unchanged`() = runBlocking<Unit> {
        // Jira's keys, as stored since V8: no discriminator, nothing renamed.
        val jira = JiraConnectionSettings("https://acme.atlassian.net", "svc@example.com", listOf("ENG"), JiraAuthScheme.BASIC)
        val keys = Json.parseToJsonElement(encodeSettings(jira)).jsonObject.keys
        assertEquals(setOf("siteUrl", "email", "projectKeys", "authScheme", "cloudId"), keys)
        assertEquals(jira, decodeSettings(DataSourceKind.JIRA_CLOUD, encodeSettings(jira)))

        val gitlab = decodeSettings(DataSourceKind.GITLAB, GitLabRowFixture.MINIMAL_SETTINGS)
        assertEquals(GitLabConnectionSettings("https://gitlab.example.com", listOf("acme/platform"), pathExclusions = null), gitlab)
        assertEquals(gitlab, decodeSettings(DataSourceKind.GITLAB, encodeSettings(gitlab)))
        // The same JSON is not interchangeable: each kind reads its own shape, never the other's.
        assertFailsWith<SerializationException> { decodeSettings(DataSourceKind.JIRA_CLOUD, GitLabRowFixture.MINIMAL_SETTINGS) }

        val id = GitLabRowFixture.insert("seam-settings").also { insertedGitLabRows += it }
        val row = suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.selectAll().where { DataSourceService.Connections.id eq id }.toList().single()
        }
        assertEquals(gitlab, row.connectionSettings())
        assertFailsWith<IllegalStateException> { row.jiraSettings() } // a GITLAB row is never read as Jira, even by a careless caller
    }

    @Test
    fun `a job whose kind has no connector fails with CONNECTOR_UNAVAILABLE and the worker loop carries on`() = runBlocking<Unit> {
        val dataSources = SyncedStubFixture.dataSources()
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { TICK_CLOCK_MILLIS })
        val jiraRuns = AtomicInteger()
        // Only JIRA_CLOUD is registered: GitLab is "off" in this process.
        val connectors = mapOf<DataSourceKind, Connector>(
            DataSourceKind.JIRA_CLOUD to RecordingConnector(DataSourceKind.JIRA_CLOUD) { jiraRuns.incrementAndGet() },
        )
        val handlers = JobHandlerRegistry().apply { register(SyncJobKind.DERIVE) { null } }
        val config = IngestConfig(
            schedulerTickSeconds = 15, workerSlots = 4, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7, workerId = workerId,
        )
        val worker = IngestWorker(jobs, dataSources, handlers, connectors, config) { TICK_CLOCK_MILLIS }

        val gitlabId = GitLabRowFixture.insert("seam-off", enabled = true).also { insertedGitLabRows += it }
        val jiraId = SyncedStubFixture.createConnection(namePrefix = "seam-jira", enabled = true).also { createdJiraRows += it }

        // One tick enqueues a due SYNC for BOTH connections, claims both and runs both; the GitLab one fails, the Jira one runs.
        withOnlyConnections(setOf(gitlabId, jiraId), TICK_CLOCK_MILLIS) {
            coroutineScope { worker.tick(this) }
        }

        val gitlabJob = jobs.list(gitlabId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.single()
        assertEquals(SyncJobStatus.FAILED, gitlabJob.status)
        assertEquals("CONNECTOR_UNAVAILABLE", gitlabJob.errorCode)
        assertTrue(gitlabJob.errorDetail!!.contains("GITLAB"), gitlabJob.errorDetail)
        assertEquals(1, jiraRuns.get(), "the Jira job in the same tick still ran")
        val jiraJob = jobs.list(jiraId, SyncJobListFilter(kind = SyncJobKind.SYNC), pagingAll()).items.single()
        assertEquals(SyncJobStatus.SUCCEEDED, jiraJob.status)

        // The failure is recorded on the connection like any failed SYNC (back-off), with the same stable code.
        val connection = suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.selectAll().where { DataSourceService.Connections.id eq gitlabId }.toList().single()
        }
        assertEquals("CONNECTOR_UNAVAILABLE", connection[DataSourceService.Connections.lastSyncErrorCode])
        assertEquals(1, connection[DataSourceService.Connections.consecutiveFailures])
        assertNotNull(connection[DataSourceService.Connections.nextSyncAt])
    }

    @Test
    fun `a PURGE for a kind with no connector fails instead of stamping the connection purged`() = runBlocking<Unit> {
        val dataSources = SyncedStubFixture.dataSources()
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { TICK_CLOCK_MILLIS })
        val gitlabId = GitLabRowFixture.insert("seam-purge").also { insertedGitLabRows += it }
        val handlers = JobHandlerRegistry().apply { register(SyncJobKind.DERIVE) { null } }
        val config = IngestConfig(
            schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7, workerId = workerId,
        )
        val worker = IngestWorker(jobs, dataSources, handlers, emptyMap(), config) { TICK_CLOCK_MILLIS }
        val purge = jobs.requestJob(gitlabId, SyncJobKind.PURGE, requestedByUserId = 1u, configRevision = 1L).jobId
        val claim = withOnlyConnections(setOf(gitlabId), TICK_CLOCK_MILLIS) {
            jobs.claim(workerId, leaseSeconds = 300, now = TICK_CLOCK_MILLIS)
        }
        assertNotNull(claim)
        assertEquals(DataSourceKind.GITLAB, claim.connectorKind, "the claim reads the kind from the row")

        worker.runJob(claim)

        val job = assertNotNull(jobs.read(gitlabId, purge))
        assertEquals(SyncJobStatus.FAILED, job.status)
        assertEquals("CONNECTOR_UNAVAILABLE", job.errorCode)
        val purgedAt = suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.selectAll().where { DataSourceService.Connections.id eq gitlabId }.toList().single()
        }[DataSourceService.Connections.purgedAt]
        assertNull(purgedAt)
    }

    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())
}
