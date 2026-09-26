package ch.nokillswit.jira

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.DataSourceService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val JiraRawStoreKey = AttributeKey<JiraRawStore>("JiraRawStore")

/** Batch size for the PURGE step's connector-cleanup (plan §0 A2) — bounds one DELETE's row count. */
internal const val JIRA_PURGE_BATCH_SIZE = 500

/**
 * The kinds `raw.jira_entities` partitions by (v0.2.0 plan §4 V10) — this exact order is also the
 * REFERENCE stream's fixed step sequence (`jira/JiraReferenceStream.kt`), so a kind name doubles as
 * a cursor `step` value.
 */
enum class JiraEntityKind {
    FIELD, STATUS, STATUS_CATEGORY, PROJECT, PROJECT_STATUSES, ISSUE_TYPE, PRIORITY, RESOLUTION,
    ISSUE_LINK_TYPE, USER, BOARD, BOARD_CONFIGURATION, SPRINT,
}

/** What the ISSUES stream hands [JiraRawStore.upsertIssue] for one page's issue document. */
data class RawIssueInput(
    val issueId: Long,
    val issueKey: String,
    val projectId: Long,
    val projectKey: String,
    val issueUpdatedAt: Long,
    /** The issue document exactly as `search/jql` returned it (not yet canonicalized). */
    val payloadJson: String,
)

enum class RawUpsertOutcome { INSERTED, UNCHANGED, CHANGED, RESURRECTED }

/**
 * The Jira raw store (v0.2.0 plan §4/§7, V10): `raw.jira_issues`/`raw.jira_entities`, both in the
 * `raw` PostgreSQL schema (plan §0 A3) via schema-qualified Exposed `Table`s
 * (`RawSchemaRoundtripTest` proves this works over Exposed R2DBC). Payloads are canonicalized
 * (`infra/json/CanonicalJson.kt`) and sha256-hashed before storage; the hash — not a byte
 * comparison — is what decides "changed" (jsonb storage itself reformats regardless).
 *
 * Every write method opens its OWN `suspendTransaction` — like `ingest/SyncCursors.kt`'s service —
 * relying on Exposed reusing an ENCLOSING transaction for nested calls against the same
 * [database], so a stream's `StreamContext.transaction { }` block (`ingest/Stream.kt`) can call
 * several of these plus a cursor write and have them all land in one transaction.
 */
class JiraRawStore(private val database: R2dbcDatabase) {

    object Issues : Table("raw.jira_issues") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val projectId = long("project_id")
        val projectKey = varchar("project_key", 20)
        val issueUpdatedAt = long("issue_updated_at")
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val fetchedAt = long("fetched_at")
        val changedAt = long("changed_at")
        val changelogSyncedAt = long("changelog_synced_at").nullable()
        val worklogsSyncedAt = long("worklogs_synced_at").nullable()
        val needsProcessing = bool("needs_processing").default(true)
        val processedAt = long("processed_at").nullable()
        val processedHash = char("processed_hash", 64).nullable()
        val processingVersion = integer("processing_version").nullable()
        val deletedAt = long("deleted_at").nullable()
        val movedOutAt = long("moved_out_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object Entities : Table("raw.jira_entities") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val kind = varchar("kind", 30)
        val entityId = varchar("entity_id", 50)
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, kind, entityId)
    }

    /**
     * Diffs and writes one issue (v0.2.0 plan §7 "ISSUES", A1): unchanged sha256 (and not
     * tombstoned) bumps only `fetched_at`; a genuine change refreshes the payload/key/project
     * columns, `changed_at` and flags `needs_processing`; a tombstoned issue seen again clears
     * `deleted_at`/`moved_out_at` (resurrection).
     */
    suspend fun upsertIssue(connectionId: UInt, issue: RawIssueInput, now: Long): RawUpsertOutcome =
        suspendTransaction(database) {
            val canonical = canonicalJson(issue.payloadJson)
            val hash = sha256Hex(canonical)
            val existing = Issues.selectAll()
                .where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }
                .forUpdate().toList().singleOrNull()

            if (existing == null) {
                Issues.insert {
                    it[Issues.connectionId] = connectionId
                    it[issueId] = issue.issueId
                    it[issueKey] = issue.issueKey
                    it[projectId] = issue.projectId
                    it[projectKey] = issue.projectKey
                    it[issueUpdatedAt] = issue.issueUpdatedAt
                    it[payload] = canonical
                    it[sha256] = hash
                    it[firstSeenAt] = now
                    it[fetchedAt] = now
                    it[changedAt] = now
                    it[needsProcessing] = true
                }
                return@suspendTransaction RawUpsertOutcome.INSERTED
            }

            val tombstoned = existing[Issues.deletedAt] != null || existing[Issues.movedOutAt] != null
            if (existing[Issues.sha256] == hash && !tombstoned) {
                Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }) {
                    it[fetchedAt] = now
                }
                return@suspendTransaction RawUpsertOutcome.UNCHANGED
            }

            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }) {
                it[issueKey] = issue.issueKey
                it[projectId] = issue.projectId
                it[projectKey] = issue.projectKey
                it[issueUpdatedAt] = issue.issueUpdatedAt
                it[payload] = canonical
                it[sha256] = hash
                it[fetchedAt] = now
                it[changedAt] = now
                it[needsProcessing] = true
                it[deletedAt] = null
                it[movedOutAt] = null
            }
            if (tombstoned) RawUpsertOutcome.RESURRECTED else RawUpsertOutcome.CHANGED
        }

    /** Same diff rule as [upsertIssue], for a reference-data entity (v0.2.0 plan §7 "REFERENCE"). */
    suspend fun upsertEntity(connectionId: UInt, kind: String, entityId: String, payloadJson: String, now: Long): RawUpsertOutcome =
        suspendTransaction(database) {
            val canonical = canonicalJson(payloadJson)
            val hash = sha256Hex(canonical)
            val existing = Entities.selectAll()
                .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId) }
                .forUpdate().toList().singleOrNull()

            if (existing == null) {
                Entities.insert {
                    it[Entities.connectionId] = connectionId
                    it[Entities.kind] = kind
                    it[Entities.entityId] = entityId
                    it[payload] = canonical
                    it[sha256] = hash
                    it[firstSeenAt] = now
                    it[lastSeenAt] = now
                    it[changedAt] = now
                }
                return@suspendTransaction RawUpsertOutcome.INSERTED
            }

            val deleted = existing[Entities.deletedAt] != null
            if (existing[Entities.sha256] == hash && !deleted) {
                Entities.update({
                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId)
                }) { it[lastSeenAt] = now }
                return@suspendTransaction RawUpsertOutcome.UNCHANGED
            }

            Entities.update({
                (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId)
            }) {
                it[payload] = canonical
                it[sha256] = hash
                it[lastSeenAt] = now
                it[changedAt] = now
                it[deletedAt] = null
            }
            if (deleted) RawUpsertOutcome.RESURRECTED else RawUpsertOutcome.CHANGED
        }

    /** REFERENCE pass end (plan §7): tombstones [kind] entities not seen since [passStartedAt] — returns the count flipped. */
    suspend fun markEntitiesDeletedNotSeenSince(
        connectionId: UInt,
        kind: String,
        passStartedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Int =
        suspendTransaction(database) {
            Entities.update({
                (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and
                    (Entities.lastSeenAt less passStartedAt) and Entities.deletedAt.isNull()
            }) { it[deletedAt] = now }
        }

    /** One batch of a connection's `raw.jira_issues` rows, oldest-inserted-order-free (the PURGE step, plan §0 A2). */
    suspend fun purgeIssuesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Issues.select(Issues.issueId).where { Issues.connectionId eq connectionId }.limit(batchSize)
            .map { it[Issues.issueId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Issues.deleteWhere { (Issues.connectionId eq connectionId) and (Issues.issueId inList ids) }
    }

    /** One batch of a connection's `raw.jira_entities` rows (the PURGE step, plan §0 A2). */
    suspend fun purgeEntitiesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val rows = Entities.select(Entities.kind, Entities.entityId).where { Entities.connectionId eq connectionId }
            .limit(batchSize).toList()
        if (rows.isEmpty()) return@suspendTransaction 0
        rows.groupBy({ it[Entities.kind] }, { it[Entities.entityId] })
            .entries.sumOf { (kind, entityIds) ->
                Entities.deleteWhere {
                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId inList entityIds)
                }
            }
    }

    /** Test-only inspection: the raw row count for a connection (`JiraRawStoreTest`/`JiraSyncPipelineTest`). */
    internal suspend fun countIssues(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { Issues.connectionId eq connectionId }.count()
    }
}

/** Drains a connection's Jira raw rows in batches — the PURGE step (`ingest/Connector.kt`'s `PurgeStep`, plan §0 A2). */
suspend fun JiraRawStore.purgeAll(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE) {
    while (purgeIssuesBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeEntitiesBatch(connectionId, batchSize) > 0) { /* drain */ }
}
