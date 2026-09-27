package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataProfile
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val dataProfileMigrated = AtomicBoolean(false)

// Same rationale as `JiraSyncPipelineTest`/`IngestWorkerTest`: this suite drives
// `ch.nokillswit.jira.JiraConnector` directly (deterministic, no worker/scheduler timing) against
// `sharedDatabaseForTests()`, so nothing else in this JVM fork is guaranteed to have run Flyway
// first.
private fun ensureDataProfileMigrated() {
    if (dataProfileMigrated.compareAndSet(false, true)) {
        org.flywaydb.core.Flyway.configure()
            .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

private val DATA_PROFILE_IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")

@Serializable
private data class ProfileExpectedReopens(val count: Long, val inScopeCount: Long)

@Serializable
private data class ProfileExpectedWorklogs(val inScopeCount: Long, val inScopeIssueCount: Long)

@Serializable
private data class ProfileExpectedSprints(val carryOverCount: Long)

@Serializable
private data class ProfileExpectedIssues(val perProject: Map<String, Long>)

@Serializable
private data class ProfileExpectedBoard(val id: Long, val projectKey: String, val unmappedStatuses: List<String>)

@Serializable
private data class ProfileExpectedFixture(
    val issues: ProfileExpectedIssues,
    val reopens: ProfileExpectedReopens,
    val worklogs: ProfileExpectedWorklogs,
    val sprints: ProfileExpectedSprints,
    val boards: List<ProfileExpectedBoard>,
)

private val DATA_PROFILE_FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private val dataProfileExpectedFixture: ProfileExpectedFixture by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    DATA_PROFILE_FIXTURE_JSON.decodeFromString(file.readText())
}

/**
 * `GET /api/v1/data-sources/{id}/profile` (v0.2.0 plan §8/§9/§12 item 9, `jira/JiraProfile.kt` +
 * `ingest/DataProfileRoutes.kt`): ADMIN only, read-only. `computedAt` is null before the
 * connection's first PROCESS pass; a full SYNC against the shared `JiraStubServer` fixture
 * (`sample-data/jira-stub`) then makes the profile match `sample-data/jira/expected.json`'s own
 * in-scope-reachable figures — reopens, worklog coverage, sprint carry-over and the deliberately
 * unmapped board status (`.claude/docs/ingestion.md` "Jira stub").
 */
class DataProfileTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = ch.nokillswit.ingest.DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())
    private fun cursors() = SyncCursorsService(sharedDatabaseForTests())
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

    private fun jira() = JiraConnectionRequest(
        siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
        email = "svc-${unique("acct")}@example.com",
        apiToken = "token-${UUID.randomUUID()}",
        projectKeys = DATA_PROFILE_IN_SCOPE_PROJECT_KEYS,
        authScheme = JiraAuthScheme.BASIC,
    )

    private fun dataSourceRequest() = DataSourceRequest(
        name = unique("profile-conn"),
        enabled = true,
        syncIntervalMinutes = 60,
        backfillFrom = "2025-09-01",
        reconcileHourUtc = 3,
        jira = jira(),
    )

    private fun buildJiraHttp(): JiraHttp {
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okhttp3.OkHttpClient() }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
        return JiraHttp(httpClient, maxRetries = 4, maxResponseBytes = 33_554_432L, maxConcurrentRequests = 4)
    }

    private fun buildConnector(): JiraConnector = JiraConnector(
        newClient = { _, email, apiToken, authScheme ->
            val stubBaseUrl = JiraStubServer.start()
            HttpJiraClient(buildJiraHttp(), stubBaseUrl, stubBaseUrl, email, apiToken, authScheme)
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

    private fun claimFor(connId: UInt) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = SyncJobKind.SYNC,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    private suspend fun runConnectorOnce(connector: JiraConnector, connId: UInt) {
        connector.run(SyncJobRunContext(claimFor(connId)) { _, _ -> true })
    }

    @Test
    fun `non-admin gets 403`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profileadmin403", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val user = seededClient("profileuser403")
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/${created.id}/profile").status)
    }

    @Test
    fun `an unknown connection is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profilemissing404", UserRole.ADMIN)

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/profile").status)
    }

    @Test
    fun `the profile is null before any PROCESS pass`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profilenull", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val profile = admin.get("/api/v1/data-sources/${created.id}/profile").body<DataProfile>()
        assertNull(profile.computedAt)
        assertEquals(0, profile.projects.size)
        assertEquals(0L, profile.reopens.count)
    }

    @Test
    fun `a full SYNC's profile matches expected-json's in-scope reopens, worklog coverage, carry-over and the unmapped board status`() {
        // The full SYNC (REFERENCE → … → PROCESS → PROFILE over ~1,200 stub issues) runs OUTSIDE
        // testApplication: its body runs under kotlinx-coroutines-test's runTest, whose timeout a
        // slow CI runner exceeds (UncompletedCoroutinesError) — the same split the pipeline tests use.
        var connId = 0u
        testApplication {
            ensureDataProfileMigrated()
            configureApp("app.role" to "web")
            startApplication()
            val admin = seededClient("profilefull", UserRole.ADMIN)
            connId = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>().id
        }

        runBlocking { runConnectorOnce(buildConnector(), connId) }

        testApplication {
            configureApp("app.role" to "web")
            startApplication()
            val admin = seededClient("profilefull-read", UserRole.ADMIN)
            val profile = admin.get("/api/v1/data-sources/$connId/profile").body<DataProfile>()
            assertTrue(profile.computedAt != null, "PROFILE must run after PROCESS in a SYNC job")

            assertEquals(
                dataProfileExpectedFixture.reopens.inScopeCount, profile.reopens.count,
                "sample-data/jira/expected.json reopens.inScopeCount — the profile only ever sees in-scope work items",
            )
            assertEquals(
                dataProfileExpectedFixture.worklogs.inScopeIssueCount, profile.worklogs.itemsWithWorklog,
                "sample-data/jira/expected.json worklogs.inScopeIssueCount",
            )
            assertEquals(
                dataProfileExpectedFixture.worklogs.inScopeCount, profile.worklogs.count,
                "sample-data/jira/expected.json worklogs.inScopeCount",
            )
            assertEquals(
                dataProfileExpectedFixture.sprints.carryOverCount, profile.sprints.carryOverCount,
                "sample-data/jira/expected.json sprints.carryOverCount — carry-over only happens on scrum projects, all in-scope",
            )

            val perProject = profile.projects.associate { it.projectKey to it.issueCounts.values.sum() }
            DATA_PROFILE_IN_SCOPE_PROJECT_KEYS.forEach { projectKey ->
                assertEquals(
                    dataProfileExpectedFixture.issues.perProject.getValue(projectKey), perProject[projectKey],
                    "sample-data/jira/expected.json issues.perProject.$projectKey",
                )
            }
            assertTrue("SEC" !in perProject, "the out-of-scope project must never appear in the profile")

            val expectedGtmBoard = dataProfileExpectedFixture.boards.single { it.projectKey == "GTM" }
            val gtmBoard = profile.boards.single { it.boardId == expectedGtmBoard.id }
            assertEquals(
                expectedGtmBoard.unmappedStatuses.toSet(), gtmBoard.unmappedStatusNames.toSet(),
                "sample-data/jira/expected.json boards[projectKey=GTM].unmappedStatuses — the deliberately unmapped 'Waiting' status",
            )
        }
    }
}
