package ch.nokillswit.gitlab

import ch.nokillswit.fetch.OutboundPermitsKey
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.TlsTrust
import ch.nokillswit.infra.outbound.guardedKtorClient
import ch.nokillswit.infra.outbound.guardedOkHttpClient
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.GitLabBaseUrlPolicy
import ch.nokillswit.ingest.SyncCursorsServiceKey
import ch.nokillswit.ingest.connectorRegistry
import ch.nokillswit.ingest.jobHandlerRegistry
import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.CodeActivityStoreKey
import io.ktor.client.HttpClient
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.net.InetAddress
import okhttp3.EventListener

/** The validated `gitlab.*` settings — always published (a disabled GitLab is `settings.enabled == false`). */
val GitLabSettingsKey = AttributeKey<GitLabSettings>("GitLabSettings")

/** The parsed `gitlab.allowedHosts`/`allowedCidrs` — always published; [SelfManagedHosts.enabled] is false when empty. */
val GitLabHostsKey = AttributeKey<SelfManagedHosts>("GitLabHosts")

/** The ONE guarded GitLab client — published ONLY when GitLab is enabled, closed on [ApplicationStopped]. */
val GitLabHttpClientKey = AttributeKey<HttpClient>("GitLabHttpClient")

/** The GitLab connector - published ONLY when GitLab is enabled (it is then also in the connector registry). */
val GitLabConnectorKey = AttributeKey<GitLabConnector>("GitLabConnector")

/**
 * Wires the GitLab connector (`.claude/docs/gitlab-integration.md`): validates `gitlab.*` (a malformed host, CIDR or CA bundle, a stub in
 * production, or a response ceiling above Jira's refuses startup), registers the base-URL policy the data-source writes validate
 * against, and - only when GitLab is enabled - builds ONE guarded `HttpClient(OkHttp)` for the self-managed host, the raw store and the
 * `GITLAB` connector (registered in the connector registry). It runs in EVERY role: Test connection is a web-role call, a sync a
 * worker-role one. Registered right after `configureJira` in `application.yaml` (it draws on Jira's `OutboundPermits`).
 *
 * With no host listed and no development stub, GitLab is DISABLED: the settings and hosts are published, the policy answers
 * `gitlab-disabled` to every GitLab write, and no client, store or connector exists.
 */
fun Application.configureGitLab() {
    val settings = readGitLabSettings(environment.config, developmentMode)
    attributes.put(GitLabSettingsKey, settings)
    attributes.put(GitLabHostsKey, settings.hosts)
    // Always: a disabled GitLab must answer the data-source writes `gitlab-disabled`, not look unregistered.
    jobHandlerRegistry().registerGitLabBaseUrlPolicy(baseUrlPolicy(settings))
    // A database handle only: published whether or not GitLab is on, so PR 11's identity routes can read the code layer either way.
    val codeStore = CodeActivityStore(attributes[R2dbcDatabaseKey])
    attributes.put(CodeActivityStoreKey, codeStore)
    if (!settings.enabled) return
    val client = buildGuardedGitLabHttpClient(
        hosts = settings.hosts,
        devStub = settings.devStub,
        requestTimeoutSeconds = settings.requestTimeoutSeconds,
        tls = settings.tls,
    )
    // Like the Jira client: ONE long-lived client, closed on stop so its dispatcher and pool threads do not leak.
    attributes.put(GitLabHttpClientKey, client)
    monitor.subscribe(ApplicationStopped) { client.close() }
    val http = createGitLabHttp(client, settings, attributes[OutboundPermitsKey])
    val rawStore = GitLabRawStore(attributes[R2dbcDatabaseKey])
    attributes.put(GitLabRawStoreKey, rawStore)
    val connector = GitLabConnector(
        newClient = { baseUrl, token -> HttpGitLabClient(http, baseUrl, token) },
        sync = GitLabSyncDependencies(
            dataSources = attributes[DataSourceServiceKey],
            rawStore = rawStore,
            processStore = GitLabRawProcessStore(attributes[R2dbcDatabaseKey]),
            codeStore = codeStore,
            cursors = attributes[SyncCursorsServiceKey],
            database = attributes[R2dbcDatabaseKey],
            usersRefreshMillis = settings.usersRefreshMillis,
            commitFilesPerRun = settings.commitFilesPerRun,
            mrDetailsPerRun = settings.mrDetailsPerRun,
        ),
    )
    attributes.put(GitLabConnectorKey, connector)
    connectorRegistry().register(connector)
}

/** The data-source writes' base-URL rule: [validateGitLabBaseUrl] against the operator's list, its refusal code in a 400. */
private fun baseUrlPolicy(settings: GitLabSettings) = GitLabBaseUrlPolicy { raw ->
    if (!settings.enabled) {
        throw BadRequestException("GitLab data sources are disabled on this server (${GitLabBaseUrlProblem.DISABLED.code})")
    }
    try {
        validateGitLabBaseUrl(raw, settings.hosts, settings.devStub).toString()
    } catch (refused: GitLabBaseUrlException) {
        throw BadRequestException("baseUrl is not an allowed GitLab origin (${refused.code})", refused)
    }
}

/**
 * Builds EXACTLY the guarded Ktor `HttpClient(OkHttp)` [configureGitLab] wires into production, so a test drives the same
 * client rather than an approximation (the `buildGuardedJiraHttpClient` shape).
 *
 *  - allowed host: a host on [hosts], or the development [devStub] host (the caller passes a stub in development mode only);
 *  - address rule: `SelfManagedHosts.addressAllowed` — public addresses for every host, a listed host's private addresses only
 *    inside the configured CIDRs, never loopback/link-local/any-local/multicast; the dev stub host skips it (a local fixture),
 *    like the Jira stub;
 *  - request rule: https to a listed `host[:port]` only (the stub's own origin aside), so an IP-literal URL, which OkHttp never
 *    resolves, and another port of a listed host are refused too;
 *  - [tls]: the JVM default CAs plus the operator's bundle, for THIS client only;
 *  - no redirects, no proxy, no connection-failure retries, no cookies (everything `guardedOkHttpClient` does).
 */
fun buildGuardedGitLabHttpClient(
    hosts: SelfManagedHosts,
    devStub: GitLabDevStub?,
    requestTimeoutSeconds: Long,
    tls: TlsTrust? = null,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    eventListener: EventListener = EventListener.NONE,
): HttpClient {
    val okHttpClient = guardedOkHttpClient(
        allowedHost = { host -> hosts.hostListed(host) || devStub?.matchesHost(host) == true },
        resolver = resolver,
        timeoutSeconds = requestTimeoutSeconds,
        addressAllowed = { host, address -> devStub?.matchesHost(host) == true || hosts.addressAllowed(host, address) },
        schemeFor = { host -> if (devStub?.matchesHost(host) == true) devStub.scheme else "https" },
        eventListener = eventListener,
        sslSocketFactory = tls?.sslSocketFactory,
        trustManager = tls?.trustManager,
        originAllowed = { scheme, host, port ->
            hosts.originListed(scheme, host, port) || devStub?.matchesOrigin(scheme, host, port) == true
        },
    )
    return guardedKtorClient(okHttpClient, requestTimeoutSeconds)
}
