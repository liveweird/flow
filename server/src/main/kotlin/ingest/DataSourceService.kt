package ch.nokillswit.ingest

import ch.nokillswit.infra.crypto.EncryptedAtRest
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.crypto.reencryptRows
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val DataSourceServiceKey = AttributeKey<DataSourceService>("DataSourceService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to DataSourceService.Connections.id,
    "name" to DataSourceService.Connections.name,
    "createdAt" to DataSourceService.Connections.createdAt,
    "updatedAt" to DataSourceService.Connections.updatedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val DATA_SOURCE_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The outcome of a PUT, so the route can audit a token rotation as its own event. [resumed] is the pause this save lifted
 * (empty when the connection was not paused; at most one SYNC and one RECONCILE snapshot), for the `data_source.resumed` events.
 */
data class DataSourceUpdateResult(
    val tokenRotated: Boolean,
    /** The host only (Jira's site host, the GitLab origin's host), for the `data_source.updated` audit event. */
    val host: String,
    val kind: DataSourceKind,
    val resumed: List<PauseSnapshot> = emptyList(),
)

/**
 * Data sources (v0.2.0 plan §4 V8): a generic connector registry — `kind`, typed common columns
 * and a `settings` jsonb (one shape per kind: Jira's or GitLab's) — one table, queue and
 * worker for every kind (the per-kind write helpers are `DataSourceKindWrites.kt`). The FIRST `EncryptedAtRest` consumer: `secret` holds
 * the scoped read-only
 * API token, `FieldCipher`-encrypted, never returned in any response (`.claude/docs/security.md`
 * "Encryption at rest"). Shaped after Covenant's `toadie/ToadieService.kt`.
 */
class DataSourceService(
    private val database: R2dbcDatabase,
    private val cipher: FieldCipher,
    private val scopeRules: ScopeRules = ScopeRules.ENFORCED,
    /** Where the backfill floor comes from (A48); a registry nobody filled (a direct test construction) imposes none. */
    private val registry: JobHandlerRegistry = JobHandlerRegistry(),
) : EncryptedAtRest {

    object Connections : UIntIdTable("source_connections"), SoftDeletable {
        val kind = varchar("kind", 20)
        val name = varchar("name", MAX_DATA_SOURCE_NAME_LENGTH)
        val enabled = bool("enabled")
        val secret = text("secret")
        val settings = jsonb("settings")
        val syncIntervalMinutes = integer("sync_interval_minutes")
        val backfillFrom = varchar("backfill_from", 10)
        val reconcileHourUtc = integer("reconcile_hour_utc")
        val configRevision = long("config_revision")
        val nextSyncAt = long("next_sync_at").nullable()
        val lastSyncStartedAt = long("last_sync_started_at").nullable()
        val lastSyncSucceededAt = long("last_sync_succeeded_at").nullable()
        val lastSyncErrorCode = varchar("last_sync_error_code", 100).nullable()
        val consecutiveFailures = integer("consecutive_failures")
        val lastReconcileAt = long("last_reconcile_at").nullable()
        val profile = jsonb("profile").nullable()
        val profileAt = long("profile_at").nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        // V9 (plan §0 A2): stamped once a PURGE job succeeds for this soft-deleted connection, so
        // the worker's due-PURGE scan (ingest/DataSourceService.kt's dueForPurge) never re-enqueues
        // it — independent of sync_jobs row retention (a SUCCEEDED PURGE row is itself eventually
        // pruned by ingest.jobRetentionDays).
        val purgedAt = long("purged_at").nullable()
        // V21: a failing RECONCILE's back-off (the consecutive_failures / next_sync_at pair, for RECONCILE).
        val reconcileFailures = integer("reconcile_failures").default(0)
        val nextReconcileAt = long("next_reconcile_at").nullable()
        // V37: the ceiling-trip pause (`SyncPause.kt`): consecutive no-progress SYNC ceiling trips, and the pause they cause.
        val limitTrips = integer("limit_trips").default(0)
        val pausedAt = long("paused_at").nullable()
        val pauseReason = varchar("pause_reason", 100).nullable()
        // V38: the same for RECONCILE (`ReconcilePause.kt`): consecutive ceiling trips without a durable change and the pause they cause.
        val reconcileLimitTrips = integer("reconcile_limit_trips").default(0)
        val reconcilePausedAt = long("reconcile_paused_at").nullable()
        val reconcilePauseReason = varchar("reconcile_pause_reason", 100).nullable()
    }

    override val encryptedRowLabel = "data source token"

    override suspend fun encryptLegacyRows(reencryptAll: Boolean): Int = suspendTransaction(database) {
        cipher.reencryptRows(Connections, listOf(Connections.secret), reencryptAll)
    }

    /** The GitLab base-URL allow-list the write validation applies (null = none registered: every GitLab write is a 400). */
    val gitLabBaseUrlPolicy: GitLabBaseUrlPolicy? get() = registry.gitLabBaseUrlPolicy

    suspend fun list(filter: DataSourceListFilter, paging: PageRequest): DataSourceListResult = suspendTransaction(database) {
        val predicate = dataSourcePredicate(filter) and Connections.active()
        val total = Connections.selectAll().where { predicate }.count()
        val rows = Connections.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        DataSourceListResult(rows.map { it.toDataSourceResponse() }, total)
    }

    /** An active data source of any kind, null when there is none. */
    suspend fun read(id: UInt): DataSourceResponse? = suspendTransaction(database) {
        activeConnection(id)?.toDataSourceResponse()
    }

    /** The kind of an active (not soft-deleted) data source, or null when there is none: what the routes branch on for a typed 409. */
    suspend fun kindOf(id: UInt): DataSourceKind? = suspendTransaction(database) { activeConnection(id)?.dataSourceKind() }

    /**
     * What a `backfillFrom` write may name right now (A48): the default for a blank one and the floor for a created or moved date.
     * Read from the registered [BackfillFloorSource] (the history horizon) -- none registered means today's rules (a 24-month default,
     * the 10-year floor). Outside any transaction: the source opens its own.
     */
    suspend fun backfillPolicy(): BackfillPolicy = backfillPolicyFor(registry.backfillFloorSource?.floor())

    /**
     * Creates the connection; `settings` starts from the request, `cloudId` fills in on the first successful probe/sync.
     * Under [ScopeRules.ENFORCED] the scope lock is taken first and the request must name the same Jira site as, and no
     * project key of, any other active Jira data source - or, for GitLab, the same instance and no overlapping group path
     * (a [DataSourceScopeConflictException], 409). The route passes the [policy] it already
     * read for the blank default, so one request uses one policy; null (a direct caller) reads it here.
     */
    suspend fun create(request: DataSourceRequest, policy: BackfillPolicy? = null): UInt {
        val floorPolicy = policy ?: backfillPolicy()
        return suspendTransaction(database) {
            // Re-checked service-side so direct callers stay guarded.
            validateDataSource(request, apiTokenRequired = true, gitLabBaseUrlPolicy)
            requireBackfillNotBefore(request, floorPolicy)
            takeDataSourceScopeLock(scopeRules)
            enforceDataSourceScope(scopeRules, request, excludeId = null, gitLabBaseUrlPolicy)
            val stamp = nowMillis()
            val settings = request.newSettings(gitLabBaseUrlPolicy)
            Connections.insert {
                it[kind] = request.kindOrBadRequest().name
                it[name] = request.name
                it[enabled] = request.enabled
                it[secret] = cipher.encrypt(checkNotNull(request.secret()) { "the token is required on create" })
                it[Connections.settings] = encodeSettings(settings)
                it[syncIntervalMinutes] = request.syncIntervalMinutes
                it[backfillFrom] = checkNotNull(request.backfillFrom)
                it[reconcileHourUtc] = request.reconcileHourUtc
                it[configRevision] = 1
                it[consecutiveFailures] = 0
                it[createdAt] = stamp
                it[updatedAt] = stamp
                it[markedAsDeleted] = false
            }[Connections.id].value
        }
    }

    /**
     * A full-replace PUT. Jira's `siteUrl` and GitLab's `baseUrl` are the connection's identity (like Toadie's `baseUrl`) — a
     * changed value is a 409, not silently accepted, and so is a block of the other kind; create a new connection instead. Omitting
     * the token keeps the current secret; a present value rotates it. Returns null when the row
     * is missing/deleted (→ 404 at the route). [policy] as on [create].
     */
    suspend fun update(id: UInt, request: DataSourceRequest, policy: BackfillPolicy? = null): DataSourceUpdateResult? {
        val floorPolicy = policy ?: backfillPolicy()
        return suspendTransaction(database) {
            validateDataSource(request, apiTokenRequired = false, gitLabBaseUrlPolicy)
            takeDataSourceScopeLock(scopeRules) // before the row lock: every scope-checked writer queues here first, one order, no deadlock
            // NO KEY UPDATE, not UPDATE: DERIVE's inserts hold FOR KEY SHARE on this row (persistence.md "Row locks vs. FK locks").
            val current = Connections.selectAll().where { (Connections.id eq id) and Connections.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull() ?: return@suspendTransaction null
            val newSettings = mergeSettings(current.connectionSettings(), request, gitLabBaseUrlPolicy) // the identity and kind 409s
            // The history-horizon floor binds only a date that MOVES: an older stored one stays legal, or every edit would 400 (A48).
            if (request.backfillFrom != current[Connections.backfillFrom]) requireBackfillNotBefore(request, floorPolicy)
            enforceDataSourceScope(scopeRules, request, excludeId = id, gitLabBaseUrlPolicy)
            val tokenRotated = request.secret() != null
            val secretValue = request.secret()?.let(cipher::encrypt) ?: current[Connections.secret]
            Connections.update({ (Connections.id eq id) and Connections.active() }) {
                it[name] = request.name
                it[enabled] = request.enabled
                it[secret] = secretValue
                it[Connections.settings] = encodeSettings(newSettings)
                it[syncIntervalMinutes] = request.syncIntervalMinutes
                it[backfillFrom] = checkNotNull(request.backfillFrom)
                it[reconcileHourUtc] = request.reconcileHourUtc
                it[configRevision] = current[Connections.configRevision] + 1
                it[updatedAt] = nowMillis()
                // A save is the operator's fix: lift any failure back-off so the connection is due on the next tick.
                it.clearBackoff(current)
                // ... and the ceiling-trip pause with its counter (`SyncPause.kt`).
                it.clearPause(current)
                it.clearReconcilePause(current)
            }
            if (request.enabled) restampDelayedDerive(id, current[Connections.configRevision] + 1)
            DataSourceUpdateResult(
                tokenRotated, request.host(gitLabBaseUrlPolicy), request.kindOrBadRequest(),
                resumed = listOfNotNull(current.pauseSnapshot(), current.reconcilePauseSnapshot()),
            )
        }
    }

    /**
     * The decrypted material of a stored Jira connection (a SYNC job's read, and the Jira case of the stored Test-connection variant) —
     * never returned in any HTTP response, only handed to the connector in-process.
     */
    data class StoredJiraConnection(
        val siteUrl: String,
        val email: String,
        val apiToken: String,
        val projectKeys: List<String>,
        val authScheme: JiraAuthScheme,
        /** ISO date (`YYYY-MM-DD`) — the ISSUES stream's first-run watermark floor (`jira/JiraIssuesStream.kt`). */
        val backfillFrom: String,
    ) {
        override fun toString(): String =
            "StoredJiraConnection(siteUrl=$siteUrl, email=$email, apiToken=${maskedSecret(apiToken)}, " +
                "projectKeys=$projectKeys, authScheme=$authScheme, backfillFrom=$backfillFrom)"
    }

    /**
     * The stored connection of ANY kind as a Test-connection target (`POST /api/v1/data-sources/{id}/test`), its token decrypted
     * for the connector alone; null when there is no such active data source.
     */
    suspend fun readForTest(id: UInt): ConnectionTarget? = suspendTransaction(database) {
        activeConnection(id)?.toConnectionTarget(cipher)
    }

    /** The SYNC job's own read of a stored Jira connection (`jira/JiraConnector.kt`'s `run`); null for any other kind. */
    suspend fun readForSync(id: UInt): StoredJiraConnection? = suspendTransaction(database) {
        activeJiraConnection(id)?.toStoredJiraConnection(cipher)
    }

    /** The SYNC job's own read of a stored GitLab connection (`gitlab/GitLabConnector.kt`'s `run`); null for any other kind. */
    suspend fun readGitLabForSync(id: UInt): StoredGitLabConnection? = suspendTransaction(database) {
        activeConnection(id)?.takeIf { it.dataSourceKind() == DataSourceKind.GITLAB }?.toStoredGitLabConnection(cipher)
    }

    /**
     * Persists the `cloudId` a successful `tenant_info` probe resolved — first-write-wins is fine; it never changes for a tenant.
     * It writes the WHOLE `settings` back, so it reads the row under the same NO KEY UPDATE lock `update` takes: a concurrent PUT
     * (which removed a project key, say) is waited out and its settings are what get the `cloudId`, never a stale copy that would
     * undo the edit. No scope lock: lock order stays advisory-then-row (`update`), and this writes no project keys of its own.
     */
    suspend fun persistCloudId(id: UInt, cloudId: String) {
        suspendTransaction(database) {
            val row = Connections.selectAll().where { (Connections.id eq id) and Connections.active() and Connections.isJira() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull() ?: return@suspendTransaction
            val settings = row.jiraSettings().copy(cloudId = cloudId)
            Connections.update({ (Connections.id eq id) and Connections.active() and Connections.isJira() }) {
                it[Connections.settings] = encodeSettings(settings)
            }
        }
    }

    /** Soft delete: disables the connection (its jobs will be cancelled once the queue lands) and bumps `configRevision`. */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Connections.update({ (Connections.id eq id) and Connections.active() }) {
            it[markedAsDeleted] = true
            it[enabled] = false
            it[configRevision] = Connections.configRevision + 1
            it[updatedAt] = nowMillis()
        }
    }

    /** A due connection's id and its CURRENT `config_revision` (stamped onto the enqueued job). */
    data class DueConnection(val id: UInt, val configRevision: Long, val kind: DataSourceKind)

    /**
     * Every enabled, active connection's id and CURRENT `config_revision` of a kind that derives metrics
     * ([DataSourceKind.derivesMetrics]; v0.3.0 M3 commit 7) - `MetricsSettingsService.bumpRevision`'s own read: a global config mutation
     * (settings, membership, team capacity) enqueues `DERIVE` for every one of them; a per-connection one narrows it to its own connection.
     * A GitLab connection is never in it: it has no DERIVE to run.
     */
    suspend fun enabledActiveConnections(): List<DueConnection> = enabledActiveConnectionRows(database)

    /** Enabled, active connections whose schedule has arrived (`next_sync_at` null or past) - IngestWorker's scheduler tick. */
    suspend fun dueForSync(now: Long): List<DueConnection> = dueForSyncRows(database, now)

    /** Enabled, active connections that are [reconcileDue] (daily boundary passed, no failure back-off pending). */
    suspend fun dueForReconcile(now: Long): List<DueConnection> = dueForReconcileRows(database, now)

    /** Soft-deleted connections past the purge grace period and never successfully purged. */
    suspend fun dueForPurge(now: Long, graceMillis: Long): List<DueConnection> = dueForPurgeRows(database, now, graceMillis)

    /**
     * Applies a SYNC job's outcome to the connection's sync-status columns
     * (`.claude/docs/ingestion.md` "Scheduling"): success resets the failure streak and
     * schedules `now + syncIntervalMinutes`. The failure branch is TEST-ONLY: the worker's failure path is
     * `SyncJobsService.fail`, which records the back-off (`now + interval × 2^n`, n = the PRE-increment
     * `consecutiveFailures`, capped at [MAX_BACKOFF_MILLIS]) in the transaction that closes the job.
     */
    suspend fun recordSyncOutcome(id: UInt, succeeded: Boolean, errorCode: String?, now: Long = nowMillis()) =
        recordSyncOutcomeRow(database, id, succeeded, errorCode, now)

    /** A RECONCILE job succeeded — stamps `last_reconcile_at` and clears the failure back-off. */
    suspend fun recordReconcileSucceeded(id: UInt, now: Long = nowMillis()) = recordReconcileSucceededRow(database, id, now)

    /** A SYNC job's `started_at` claim — surfaces on the connection so a stuck/never-finished sync is still visible. */
    suspend fun recordSyncStarted(id: UInt, now: Long = nowMillis()) = recordSyncStartedRow(database, id, now)

    /**
     * "Sync now": lifts the ceiling-trip pause and its counter (`SyncPause.kt`) before the manual SYNC is enqueued. Returns what was
     * lifted, or null when the connection was not paused (a counter below the threshold is still cleared).
     */
    suspend fun resumeFromPause(id: UInt): PauseSnapshot? = resumeFromPauseRow(database, id)

    /** "Reconcile now": the same for the RECONCILE pause and its counter (`ReconcilePause.kt`); "Sync now" leaves it alone. */
    suspend fun resumeFromReconcilePause(id: UInt): PauseSnapshot? = resumeFromReconcilePauseRow(database, id)

    /** A PURGE job succeeded (plan §0 A2) — stops the due-PURGE scan from re-enqueueing this connection. */
    suspend fun recordPurgeSucceeded(id: UInt, now: Long = nowMillis()) = recordPurgeSucceededRow(database, id, now)

    /**
     * The PROFILE step's own write (v0.2.0 plan §8/§12 item 9) — [profileJson] is
     * `DataProfileSections`, canonicalized and stored verbatim.
     */
    suspend fun updateProfile(id: UInt, profileJson: String, now: Long = nowMillis()) = updateProfileRow(database, id, profileJson, now)

    /** `GET /api/v1/data-sources/{id}/profile`'s own read (v0.2.0 plan §9/§12 item 9) — null [profileJson] means "never computed". */
    data class StoredProfile(val profileJson: String?, val profileAt: Long?)

    suspend fun readProfile(id: UInt): StoredProfile? = suspendTransaction(database) {
        activeConnection(id)?.let { StoredProfile(it[Connections.profile], it[Connections.profileAt]) }
    }

    /** Id, name and sync freshness of every connection not yet purged, for the telemetry panel (no settings, no secret). */
    suspend fun connectionHealth(): List<ConnectionHealth> = connectionHealthRows(database)

    private suspend fun activeConnection(id: UInt): ResultRow? =
        Connections.selectAll().where { (Connections.id eq id) and Connections.active() }.toList().singleOrNull()

    private suspend fun activeJiraConnection(id: UInt): ResultRow? =
        Connections.selectAll().where { (Connections.id eq id) and Connections.active() and Connections.isJira() }.toList().singleOrNull()
}
