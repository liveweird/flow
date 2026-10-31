package ch.nokillswit

import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.gitlab.GitLabConnector
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabSettings
import ch.nokillswit.gitlab.GitLabSyncDependencies
import ch.nokillswit.gitlab.HttpGitLabClient
import ch.nokillswit.gitlab.createGitLabHttp
import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.GitLabConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.ScopeRules
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.defaultBackfillFrom
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.UUID

/**
 * Test-side GitLab data-source plumbing (`.claude/docs/test-fixtures.md`): a direct [DataSourceService] with the scope rules OFF and a
 * base-URL policy that accepts the in-JVM [GitLabStubServer] (the production policy is `gitlab/GitLab.kt`'s, over the operator's list),
 * connections pointing at it, and a [GitLabConnector] that talks to the stub over a plain OkHttp client (the loopback stub is not behind
 * the production SSRF guard, like `SyncedStubFixture.buildJiraHttp`).
 */
object GitLabFixtures {
    const val GROUPS_IN_SCOPE_FIRST = "platform"
    const val GROUPS_IN_SCOPE_SECOND = "apps"

    fun dataSources(): DataSourceService {
        val registry = JobHandlerRegistry().apply { registerGitLabBaseUrlPolicy { it } }
        return DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY), ScopeRules.OFF, registry)
    }

    fun rawStore() = GitLabRawStore(sharedDatabaseForTests())

    /** A GitLab connection on the stub with a token the stub accepts (any non-empty one). DISABLED by default: no worker re-syncs it. */
    suspend fun createConnection(
        dataSources: DataSourceService = dataSources(),
        groupPaths: List<String> = listOf(GROUPS_IN_SCOPE_FIRST, GROUPS_IN_SCOPE_SECOND),
        baseUrl: String = GitLabStubServer.start(),
        enabled: Boolean = false,
        namePrefix: String = "gitlab-test",
    ): UInt = dataSources.create(
        DataSourceRequest(
            name = "$namePrefix-${UUID.randomUUID().toString().take(8)}",
            enabled = enabled,
            syncIntervalMinutes = 60,
            backfillFrom = defaultBackfillFrom(),
            reconcileHourUtc = 3,
            gitlab = GitLabConnectionRequest(baseUrl = baseUrl, token = "glpat-test-token", groupPaths = groupPaths),
        ),
    )

    fun settings(maxRetries: Int = 0): GitLabSettings =
        GitLabSettings(SelfManagedHosts.NONE, null, null, 30, 8L * 1024 * 1024, maxRetries, 180, 24, 5_000)

    /** A plain OkHttp-backed client to the stub; one per call, closed with the JVM. */
    fun stubClient(maxRetries: Int = 0): (String, String) -> HttpGitLabClient {
        val http = createGitLabHttp(
            HttpClient(OkHttp) {
                engine { preconfigured = okhttp3.OkHttpClient() }
                expectSuccess = false
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 10_000
                }
            },
            settings(maxRetries),
            OutboundPermits(4),
        )
        return { baseUrl, token -> HttpGitLabClient(http, baseUrl, token) }
    }

    fun connector(
        dataSources: DataSourceService = dataSources(),
        usersRefreshHours: Long = 24,
        now: () -> Long = System::currentTimeMillis,
        commitFilesPerRun: Int = 100_000,
    ) =
        GitLabConnector(
            newClient = stubClient(),
            now = now,
            sync = GitLabSyncDependencies(
                dataSources = dataSources,
                rawStore = rawStore(),
                cursors = SyncCursorsService(sharedDatabaseForTests()),
                database = sharedDatabaseForTests(),
                usersRefreshMillis = usersRefreshHours * 3_600_000L,
                commitFilesPerRun = commitFilesPerRun,
            ),
        )

    /** A fresh [StreamContext] for driving one stream directly against [connId] (no lease loss: the `SyncedStubFixture` shape). */
    fun freshContext(connId: UInt, jobId: UInt = 1u): StreamContext =
        StreamContext(
            connId, jobId, sharedDatabaseForTests(), SyncCursorsService(sharedDatabaseForTests()), jobHeartbeat = { _, _ -> true },
        )

    /** Runs [stream] once through a fresh context and returns its progress counters. */
    suspend fun runStream(stream: Stream, connId: UInt, context: StreamContext = freshContext(connId)): Map<String, Long> {
        context.currentStreamName = stream.name
        stream.run(context)
        return context.progressSnapshot()
    }

    fun claimFor(connId: UInt, kind: SyncJobKind = SyncJobKind.SYNC, onDemand: Boolean = false) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.GITLAB,
        kind = kind,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
        requestedOnDemand = onDemand,
    )

    /** Runs one job of [kind] through [connector] directly; every progress JSON the run flushes is appended to [progress]. */
    suspend fun run(
        connector: GitLabConnector,
        connId: UInt,
        kind: SyncJobKind = SyncJobKind.SYNC,
        onDemand: Boolean = false,
        progress: MutableList<String> = mutableListOf(),
    ) = connector.run(SyncJobRunContext(claimFor(connId, kind, onDemand)) { json, _ -> true.also { json?.let(progress::add) } })
}
