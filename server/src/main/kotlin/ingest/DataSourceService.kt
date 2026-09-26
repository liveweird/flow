package ch.nokillswit.ingest

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.crypto.EncryptedAtRest
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.crypto.reencryptRows
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val DataSourceServiceKey = AttributeKey<DataSourceService>("DataSourceService")

/** A sync is considered STALE once this many intervals have passed with no successful run. */
private const val STALE_INTERVAL_MULTIPLIER = 3L

// ignoreUnknownKeys: rolling-deploy tolerance — an older replica must not 500 decoding a
// `settings` payload a newer replica already wrote with an extra field.
// ignoreUnknownKeys: rolling-deploy tolerance — an older replica must not 500 decoding a
// `settings` payload a newer replica already wrote with an extra field.
private val settingsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** The `settings` jsonb payload — Jira's fields today; a GitLab kind adds its own shape later. */
@Serializable
internal data class JiraConnectionSettings(
    val siteUrl: String,
    val email: String,
    val projectKeys: List<String>,
    val authScheme: JiraAuthScheme,
    val cloudId: String? = null,
)

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to DataSourceService.Connections.id,
    "name" to DataSourceService.Connections.name,
    "createdAt" to DataSourceService.Connections.createdAt,
    "updatedAt" to DataSourceService.Connections.updatedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val DATA_SOURCE_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The outcome of a PUT, so the route can audit a token rotation as its own event. */
data class DataSourceUpdateResult(val tokenRotated: Boolean, val siteHost: String)

/**
 * Data sources (v0.2.0 plan §4 V8): a generic connector registry — `kind`, typed common columns
 * and a `settings` jsonb (Jira's fields today) — so GitLab later reuses this table, its queue and
 * its worker outright. The FIRST `EncryptedAtRest` consumer: `secret` holds the scoped read-only
 * API token, `FieldCipher`-encrypted, never returned in any response (`.claude/docs/security.md`
 * "Encryption at rest"). Shaped after Covenant's `toadie/ToadieService.kt`.
 */
class DataSourceService(private val database: R2dbcDatabase, private val cipher: FieldCipher) : EncryptedAtRest {

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
    }

    override val encryptedRowLabel = "Jira API token"

    override suspend fun encryptLegacyRows(reencryptAll: Boolean): Int = suspendTransaction(database) {
        cipher.reencryptRows(Connections, listOf(Connections.secret), reencryptAll)
    }

    suspend fun list(filter: DataSourceListFilter, paging: PageRequest): DataSourceListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Connections.active()
        val total = Connections.selectAll().where { predicate }.count()
        val rows = Connections.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        DataSourceListResult(rows.map { it.toResponse() }, total)
    }

    suspend fun read(id: UInt): DataSourceResponse? = suspendTransaction(database) {
        activeConnection(id)?.toResponse()
    }

    /** Creates the connection; `settings` starts from the request, `cloudId` fills in on the first successful probe/sync. */
    suspend fun create(request: DataSourceRequest): UInt = suspendTransaction(database) {
        val stamp = nowMillis()
        val settings = JiraConnectionSettings(
            siteUrl = request.jira.siteUrl,
            email = request.jira.email,
            projectKeys = request.jira.projectKeys,
            authScheme = request.jira.authScheme,
        )
        Connections.insert {
            it[kind] = DataSourceKind.JIRA_CLOUD.name
            it[name] = request.name
            it[enabled] = request.enabled
            it[secret] = cipher.encrypt(checkNotNull(request.jira.apiToken) { "apiToken is required on create" })
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

    /**
     * A full-replace PUT. `siteUrl` is the connection's identity (like Toadie's `baseUrl`) — a
     * changed value is a 409, not silently accepted; create a new connection instead. Omitting
     * `apiToken` keeps the current secret; a present value rotates it. Returns null when the row
     * is missing/deleted (→ 404 at the route).
     */
    suspend fun update(id: UInt, request: DataSourceRequest): DataSourceUpdateResult? = suspendTransaction(database) {
        val current = Connections.selectAll().where { (Connections.id eq id) and Connections.active() }
            .forUpdate().toList().singleOrNull() ?: return@suspendTransaction null
        val currentSettings = decodeSettings(current[Connections.settings])
        if (request.jira.siteUrl != currentSettings.siteUrl) {
            throw ConflictException("siteUrl is the data source identity; create a new connection to change it")
        }
        val tokenRotated = request.jira.apiToken != null
        val secretValue = request.jira.apiToken?.let(cipher::encrypt) ?: current[Connections.secret]
        val newSettings = currentSettings.copy(
            email = request.jira.email,
            projectKeys = request.jira.projectKeys,
            authScheme = request.jira.authScheme,
        )
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
        }
        DataSourceUpdateResult(tokenRotated, siteHost(request.jira.siteUrl))
    }

    /**
     * The decrypted material the stored-connection Test-connection variant needs
     * (`POST /api/v1/data-sources/{id}/test`) — never returned in any HTTP response, only handed
     * to [ch.nokillswit.ingest.Connector.testConnection] in-process.
     */
    data class StoredJiraConnection(
        val siteUrl: String,
        val email: String,
        val apiToken: String,
        val projectKeys: List<String>,
        val authScheme: JiraAuthScheme,
    )

    suspend fun readForTest(id: UInt): StoredJiraConnection? = suspendTransaction(database) {
        val row = activeConnection(id) ?: return@suspendTransaction null
        val settings = decodeSettings(row[Connections.settings])
        StoredJiraConnection(
            siteUrl = settings.siteUrl,
            email = settings.email,
            apiToken = cipher.decrypt(row[Connections.secret]),
            projectKeys = settings.projectKeys,
            authScheme = settings.authScheme,
        )
    }

    /** Persists the `cloudId` a successful `tenant_info` probe resolved — first-write-wins is fine; it never changes for a tenant. */
    suspend fun persistCloudId(id: UInt, cloudId: String) {
        suspendTransaction(database) {
            val row = activeConnection(id) ?: return@suspendTransaction
            val settings = decodeSettings(row[Connections.settings]).copy(cloudId = cloudId)
            Connections.update({ (Connections.id eq id) and Connections.active() }) {
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

    private suspend fun activeConnection(id: UInt): ResultRow? =
        Connections.selectAll().where { (Connections.id eq id) and Connections.active() }.toList().singleOrNull()

    private fun buildPredicate(filter: DataSourceListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Connections.name.containsNormalized(it) }
        return op
    }

    private fun ResultRow.toResponse(): DataSourceResponse {
        val settings = decodeSettings(this[Connections.settings])
        return DataSourceResponse(
            id = this[Connections.id].value,
            kind = DataSourceKind.valueOf(this[Connections.kind]),
            name = this[Connections.name],
            enabled = this[Connections.enabled],
            syncIntervalMinutes = this[Connections.syncIntervalMinutes],
            backfillFrom = this[Connections.backfillFrom],
            reconcileHourUtc = this[Connections.reconcileHourUtc],
            configRevision = this[Connections.configRevision],
            jira = JiraConnectionResponse(
                siteUrl = settings.siteUrl,
                email = settings.email,
                hasApiToken = this[Connections.secret].isNotEmpty(),
                projectKeys = settings.projectKeys,
                authScheme = settings.authScheme,
                cloudId = settings.cloudId,
            ),
            status = toStatus(),
            createdAt = this[Connections.createdAt],
            updatedAt = this[Connections.updatedAt],
        )
    }

    private fun ResultRow.toStatus(now: Long = nowMillis()): DataSourceStatus {
        val lastSyncErrorCode = this[Connections.lastSyncErrorCode]
        val lastSyncSucceededAt = this[Connections.lastSyncSucceededAt]
        val state = when {
            !this[Connections.enabled] -> DataSourceState.DISABLED
            lastSyncErrorCode != null -> DataSourceState.FAILED
            lastSyncSucceededAt == null -> DataSourceState.NEVER_SYNCED
            now - lastSyncSucceededAt > this[Connections.syncIntervalMinutes].toLong() * 60_000L * STALE_INTERVAL_MULTIPLIER ->
                DataSourceState.STALE
            else -> DataSourceState.CURRENT
        }
        return DataSourceStatus(
            state = state,
            lastSyncStartedAt = this[Connections.lastSyncStartedAt],
            lastSyncSucceededAt = lastSyncSucceededAt,
            lastSyncErrorCode = lastSyncErrorCode,
            consecutiveFailures = this[Connections.consecutiveFailures],
            runningJobId = null,
        )
    }
}

/** The host only — never the full URL — for audit lines (`.claude/docs/security.md`). */
internal fun siteHost(siteUrl: String): String = java.net.URI(siteUrl).host

private fun encodeSettings(settings: JiraConnectionSettings): String =
    canonicalJson(settingsJson.encodeToString(settings))

private fun decodeSettings(raw: String): JiraConnectionSettings = settingsJson.decodeFromString(raw)
