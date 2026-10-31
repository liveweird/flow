package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectorRun
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.JobRefusedException
import ch.nokillswit.ingest.ProbeRun
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.TOKEN_SCOPE_TOO_BROAD

private const val TOKEN_INACTIVE = "TOKEN_INACTIVE"
private const val NOT_ADMIN = "NOT_ADMIN"
private const val READ_API = "read_api"

/**
 * The GitLab connector (`gitlab-integration.md`): Test connection, and the SYNC job's two streams, PROJECTS then USERS. The other job
 * kinds are accepted and do nothing yet - RECONCILE and REPROCESS arrive with the streams that need them (the plan's PRs 9 and 10), PURGE
 * drains the raw tables, and DERIVE never reaches a connector (`IngestWorker` chains it for kinds that derive metrics only).
 */
class GitLabConnector(
    private val newClient: GitLabClientFactory,
    /** Test seam: an injected clock simulates the 30 s TOTAL probe budget eroding, and is the streams' clock. */
    private val now: () -> Long = System::currentTimeMillis,
    private val sync: GitLabSyncDependencies? = null,
) : Connector {
    override val kind = DataSourceKind.GITLAB

    /** PURGE's connector-owned step: drains this connection's `raw.gitlab_*` rows in batches. */
    override val purgeSteps: List<PurgeStep>
        get() = sync?.let { deps -> listOf(PurgeStep { connectionId -> deps.rawStore.purgeAll(connectionId) }) } ?: emptyList()

    override suspend fun run(context: SyncJobRunContext) {
        when (context.claim.kind) {
            SyncJobKind.SYNC -> runSync(context)
            SyncJobKind.PURGE -> purgeSteps.forEach { it.purge(context.claim.connectionId) }
            // No-ops until the streams they need exist (RECONCILE: the SHA re-list and tombstones; REPROCESS: the norm layer).
            SyncJobKind.RECONCILE, SyncJobKind.REPROCESS -> Unit
            // DERIVE is connector-agnostic and dispatched before a connector's run(); a GitLab connection never has one.
            SyncJobKind.DERIVE -> Unit
        }
    }

    /**
     * SYNC: refuse a token that could write (re-probed from `/personal_access_tokens/self` on EVERY run, so a token widened after Test
     * connection is caught before anything is stored: `TOKEN_SCOPE_TOO_BROAD`), then PROJECTS and USERS inside the shared call tally
     * (`ingest/ConnectorRun.kt`). "Sync now" refreshes the users whatever their age.
     */
    private suspend fun runSync(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "GitLabConnector.run(SYNC) requires GitLabSyncDependencies (gitlab/GitLab.kt's configureGitLab)" }
        val claim = context.claim
        val stored = deps.dataSources.readGitLabForSync(claim.connectionId) ?: return
        val client = newClient(stored.baseUrl, stored.token)
        ConnectorRun.withTally(context, deps.database, deps.cursors, now, deadlineExemptStreams = emptySet()) { streamContext ->
            val token = client.tokenSelf()
            if (token.scopes.tooBroad()) {
                throw JobRefusedException(
                    TOKEN_SCOPE_TOO_BROAD,
                    "the token carries a write-capable scope (${token.scopes.joinToString(", ")}); Flow needs $READ_API only",
                )
            }
            if (!token.active) throw JobRefusedException(TOKEN_SCOPE_TOO_BROAD, "the token is inactive")
            val isAdmin = client.currentUser().isAdmin
            val streams: List<Stream> = listOf(
                GitLabProjectsStream(client, deps.rawStore, stored.groupPaths),
                GitLabUsersStream(client, deps.rawStore, isAdmin, if (claim.requestedOnDemand) 0 else deps.usersRefreshMillis),
            )
            streams.forEach { stream ->
                streamContext.currentStreamName = stream.name
                stream.run(streamContext)
            }
        }
    }

    /**
     * Probes in SEQUENCE, at most 10 s each and 30 s total, never throwing (`ingest/ConnectionProbes.kt`): `version`, `user`,
     * `user_admin` (optional: an admin token sees user emails), `token_scopes` (a token that could write, or is inactive, fails it),
     * a `group:` and a `group_projects:` row per configured path, `users`, and `user_emails` (optional: can the token read the
     * emails it was given an admin for). The token is never echoed.
     */
    override suspend fun testConnection(target: ConnectionTarget): ConnectionTestResult {
        check(target is ConnectionTarget.GitLab) { "GitLabConnector cannot probe a ${target::class.simpleName} target" }
        val client = newClient(target.baseUrl, target.token)
        val run = ProbeRun(now)
        var owner: GitLabCurrentUser? = null
        val groupIds = mutableMapOf<String, Long>()

        run.probe("version", "/api/v4/version", required = true) { client.version() }
        run.probe("user", "/api/v4/user", required = true, scopeHint = READ_API) { owner = client.currentUser() }
        owner?.let { user ->
            run.probe("user_admin", "/api/v4/user", required = false) {
                if (!user.isAdmin) throw FetchException(GITLAB_SOURCE, NOT_ADMIN)
            }
        }
        run.probe("token_scopes", "/api/v4/personal_access_tokens/self", required = true, scopeHint = READ_API) {
            val token = client.tokenSelf()
            if (token.scopes.tooBroad()) throw FetchException(GITLAB_SOURCE, TOKEN_SCOPE_TOO_BROAD)
            if (!token.active) throw FetchException(GITLAB_SOURCE, TOKEN_INACTIVE)
        }
        target.groupPaths.forEach { path ->
            run.probe("group:$path", "/api/v4/groups/$path", required = true, scopeHint = READ_API) {
                val group = client.group(path)
                if (!group.fullPath.equals(path, ignoreCase = true)) throw FetchException(GITLAB_SOURCE, "INVALID_RESPONSE")
                groupIds[path] = group.id
            }
            run.probe("group_projects:$path", "/api/v4/groups/$path/projects", required = true, scopeHint = READ_API) {
                // The group row above did not resolve it: this one is unreachable, not a failure of its own (ProbeRun maps it to
                // NOT_FOUND).
                client.groupProjects(checkNotNull(groupIds[path]) { "group $path not resolved" }, 1)
            }
        }
        run.probe("users", "/api/v4/users", required = true, scopeHint = READ_API) { client.users(1) }
        owner?.let { user ->
            run.probe("user_emails", "/api/v4/users/${user.id}/emails", required = false,
                scopeHint = READ_API) { client.userEmails(user.id) }
        }
        return ConnectionTestResult(run.rows)
    }
}
