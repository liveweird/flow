package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.infra.validation.PathGlob
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectorRun
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.JobRefusedException
import ch.nokillswit.ingest.ProbeRun
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.TOKEN_INACTIVE
import ch.nokillswit.ingest.TOKEN_SCOPE_TOO_BROAD
import ch.nokillswit.ingest.backfillFromEpochMillis
import ch.nokillswit.norm.purgeAll

private const val NOT_ADMIN = "NOT_ADMIN"
private const val READ_API = "read_api"

/**
 * The GitLab connector (`gitlab-integration.md`): Test connection, and the SYNC job's six streams, in this order: PROJECTS, USERS,
 * COMMITS, MERGE REQUESTS (the listings), then COMMIT FILES and MR DETAILS (the budgeted backlogs), then `code_process` (the local
 * PROCESS into `norm.code_*`, V44) and `code_profile` (the data profile's `gitlab` section). No GitLab-calling stream may follow them:
 * [GITLAB_LOCAL_STREAMS] are deadline-exempt and disarm the hard stop.
 *
 * RECONCILE runs three streams, cheap and high-value first so a wall-clock stop lands in the long one: `reconcile_projects` (a
 * `GET /projects/:id` per moved-out project: 404 marks it deleted, `archived` marks it archived), `reconcile_merge_requests` (the
 * group re-list from the floor, the confirmed tombstones of the merge requests it did not show, the stats re-ask and the failed
 * details retries) and `reconcile_commits` (the `since`-less re-list of every synced project and its confirmed tombstones). It
 * reads nothing SYNC's cursors hold and writes only its own, and ends with `code_process` (never the profile), so a tombstone or a
 * refilled stat is mirrored into the norm layer in the SAME job. REPROCESS re-flags every raw commit and merge request and runs
 * `code_process` then `code_profile`, under a plain context with no tally and no token probe (a local rebuild works with a revoked
 * token). PURGE drains the raw tables, then the code layer; DERIVE never reaches a connector (`IngestWorker` chains it for kinds that
 * derive metrics only).
 */
class GitLabConnector(
    private val newClient: GitLabClientFactory,
    /** Test seam: an injected clock simulates the 30 s TOTAL probe budget eroding, and is the streams' clock. */
    private val now: () -> Long = System::currentTimeMillis,
    private val sync: GitLabSyncDependencies? = null,
) : Connector {
    override val kind = DataSourceKind.GITLAB

    /**
     * PURGE's connector-owned steps, in this order (Jira's): the raw tables, then the code layer (`norm.code_*`). Both drain in bounded
     * batches. `source_connections.profile` is left alone, as for Jira.
     */
    override val purgeSteps: List<PurgeStep>
        get() = sync?.let { deps ->
            listOf(
                PurgeStep { connectionId -> deps.rawStore.purgeAll(connectionId) },
                PurgeStep { connectionId -> deps.codeStore.purgeAll(connectionId) },
            )
        } ?: emptyList()

    override suspend fun run(context: SyncJobRunContext) {
        when (context.claim.kind) {
            SyncJobKind.SYNC -> runSync(context)
            SyncJobKind.PURGE -> purgeSteps.forEach { it.purge(context.claim.connectionId) }
            SyncJobKind.RECONCILE -> runReconcile(context)
            SyncJobKind.REPROCESS -> runReprocess(context)
            // DERIVE is connector-agnostic and dispatched before a connector's run(); a GitLab connection never has one.
            SyncJobKind.DERIVE -> Unit
        }
    }

    /**
     * SYNC: refuse a token with a scope outside the read-only allow-list, or an inactive one (re-probed from
     * `/personal_access_tokens/self` on EVERY run, so a token widened or revoked after Test connection is caught before anything is
     * stored: `TOKEN_SCOPE_TOO_BROAD` / `TOKEN_INACTIVE`), then the six streams inside the shared call tally
     * (`ingest/ConnectorRun.kt`). "Sync now" refreshes the users whatever their age.
     */
    private suspend fun runSync(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "GitLabConnector.run(SYNC) requires GitLabSyncDependencies (gitlab/GitLab.kt's configureGitLab)" }
        val claim = context.claim
        val stored = deps.dataSources.readGitLabForSync(claim.connectionId) ?: return
        val client = newClient(stored.baseUrl, stored.token)
        val exclusions = stored.pathExclusions.map(PathGlob::compile)
        ConnectorRun.withTally(context, deps.database, deps.cursors, now, deadlineExemptStreams = GITLAB_LOCAL_STREAMS) { streamContext ->
            refuseUnlessReadOnly(client)
            val isAdmin = client.currentUser().isAdmin
            val backfill = backfillFromEpochMillis(stored.backfillFrom)
            val streams: List<Stream> = listOf(
                GitLabProjectsStream(client, deps.rawStore, stored.groupPaths),
                GitLabUsersStream(client, deps.rawStore, isAdmin, if (claim.requestedOnDemand) 0 else deps.usersRefreshMillis),
                GitLabCommitsStream(client, deps.rawStore, backfill),
                // The listings come first and the backlogs after: a listing is a handful of calls a run and must not wait behind a
                // backlog that may spend its whole budget (the two backlogs keep the order they were added in).
                GitLabMergeRequestsStream(client, deps.rawStore, stored.groupPaths, backfill),
                GitLabCommitFilesStream(client, deps.rawStore, deps.commitFilesPerRun),
                GitLabMrDetailsStream(client, deps.rawStore, deps.mrDetailsPerRun),
                // Local from here on: nothing that calls GitLab may follow.
                GitLabProcessStream(deps.processStore, deps.codeStore, exclusions),
                GitLabProfileStream(deps.rawStore, deps.processStore, deps.codeStore, deps.dataSources),
            )
            streams.forEach { stream ->
                streamContext.currentStreamName = stream.name
                stream.run(streamContext)
            }
        }
    }

    /**
     * RECONCILE: the same token re-probe as SYNC, then `reconcile_projects` -> `reconcile_merge_requests` -> `reconcile_commits` inside
     * the shared call tally. The order is deliberate (see the class kdoc). A scheduled run leaves `LIMIT_EXCEEDED` details stamps alone;
     * "Reconcile now" (`requestedOnDemand`) un-stamps them too, once the operator has raised the response ceiling.
     */
    private suspend fun runReconcile(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "GitLabConnector.run(RECONCILE) requires GitLabSyncDependencies (configureGitLab)" }
        val claim = context.claim
        val stored = deps.dataSources.readGitLabForSync(claim.connectionId) ?: return
        val client = newClient(stored.baseUrl, stored.token)
        val exclusions = stored.pathExclusions.map(PathGlob::compile)
        ConnectorRun.withTally(context, deps.database, deps.cursors, now, deadlineExemptStreams = GITLAB_LOCAL_STREAMS) { streamContext ->
            refuseUnlessReadOnly(client)
            val backfill = backfillFromEpochMillis(stored.backfillFrom)
            val streams: List<Stream> = listOf(
                GitLabReconcileProjectsStream(client, deps.rawStore),
                GitLabMergeRequestsStream(
                    client, deps.rawStore, stored.groupPaths, backfill, reconcile = true,
                    sweep = GitLabMrReconcileSweep(client, deps.rawStore, backfill),
                    refresh = GitLabMrReconcileRefresh(client, deps.rawStore, claim.requestedOnDemand),
                ),
                GitLabCommitsStream(client, deps.rawStore, backfill, reconcile = true),
                GitLabProcessStream(deps.processStore, deps.codeStore, exclusions),
            )
            streams.forEach { stream ->
                streamContext.currentStreamName = stream.name
                stream.run(streamContext)
            }
        }
    }

    /**
     * REPROCESS: flags EVERY raw commit and merge request of the connection, then runs `code_process` and `code_profile` - a
     * version bump, a changed exclusion list to apply now, or a manually requested rebuild; it never touches GitLab (a plain
     * [StreamContext]: no tally, no token probe, so it works with a revoked token). A deleted source is nothing to do.
     */
    private suspend fun runReprocess(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "GitLabConnector.run(REPROCESS) requires GitLabSyncDependencies (configureGitLab)" }
        val claim = context.claim
        val stored = deps.dataSources.readGitLabForSync(claim.connectionId) ?: return
        deps.processStore.markAllNeedsProcessing(claim.connectionId, commitsOnly = false)
        val streamContext = StreamContext(claim.connectionId, claim.id, deps.database, deps.cursors, context.heartbeat, now)
        val stream = GitLabProcessStream(deps.processStore, deps.codeStore, stored.pathExclusions.map(PathGlob::compile))
        streamContext.currentStreamName = stream.name
        stream.run(streamContext)
        val profile = GitLabProfileStream(deps.rawStore, deps.processStore, deps.codeStore, deps.dataSources)
        streamContext.currentStreamName = profile.name
        profile.run(streamContext)
    }

    /** Re-probes the token on EVERY run (SYNC and RECONCILE): a scope beyond reading, or an inactive token, refuses the job. */
    private suspend fun refuseUnlessReadOnly(client: GitLabClient) {
        val token = client.tokenSelf()
        if (token.scopes.tooBroad()) {
            throw JobRefusedException(
                TOKEN_SCOPE_TOO_BROAD,
                "the token carries a scope beyond reading (${token.scopes.joinToString(", ")}); Flow needs $READ_API only",
            )
        }
        if (!token.active) throw JobRefusedException(TOKEN_INACTIVE, "the token is inactive (revoked or expired)")
    }

    /**
     * Probes in SEQUENCE, at most 10 s each and 30 s total, never throwing (`ingest/ConnectionProbes.kt`): `version`, `user`,
     * `user_admin` (optional: an admin token sees user emails), `token_scopes` (a token with a scope beyond `read_*`
     * (`TOKEN_SCOPE_TOO_BROAD`), or an inactive one (`TOKEN_INACTIVE`), fails it),
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
