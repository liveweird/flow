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
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
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

    object Changelogs : Table("raw.jira_changelogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val historyId = long("history_id")
        val issueId = long("issue_id")
        val createdAt = long("created_at")
        val authorAccountId = varchar("author_account_id", 100).nullable()
        val payload = jsonb("payload")
        val fetchedAt = long("fetched_at")
        override val primaryKey = PrimaryKey(connectionId, historyId)
    }

    object Worklogs : Table("raw.jira_worklogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        val issueId = long("issue_id")
        val worklogUpdatedAt = long("worklog_updated_at")
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val fetchedAt = long("fetched_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, worklogId)
    }

    /**
     * Append-only insert for one changelog history (v0.2.0 plan §7 "CHANGELOGS"): a history is
     * immutable once Jira creates it, so `ON CONFLICT DO NOTHING` on the PK is the whole dedup rule
     * — the same history reaching here twice (a resumed batch, an overlapping bulk/per-issue
     * fallback pair) is a silent no-op, never a duplicate row or an error.
     */
    suspend fun insertChangelog(
        connectionId: UInt,
        historyId: Long,
        issueId: Long,
        createdAt: Long,
        authorAccountId: String?,
        payloadJson: String,
        now: Long,
    ) {
        suspendTransaction(database) {
            val canonical = canonicalJson(payloadJson)
            Changelogs.insertIgnore {
                it[Changelogs.connectionId] = connectionId
                it[Changelogs.historyId] = historyId
                it[Changelogs.issueId] = issueId
                it[Changelogs.createdAt] = createdAt
                it[Changelogs.authorAccountId] = authorAccountId
                it[payload] = canonical
                it[fetchedAt] = now
            }
        }
    }

    /**
     * The CHANGELOGS stream's claim scan (v0.2.0 plan §7): in-scope (never tombstoned) issues whose
     * history has never been synced, OR changed since it last was — ascending issue id (matching
     * `sample-data`'s global chronological id assignment, so a batch of [limit] ascending ids lines
     * up with the stub's own fixed 50-id bulkfetch chunks), batches of [limit]
     * (`jira.changelogBulkSize`).
     */
    suspend fun staleChangelogIssueIds(connectionId: UInt, limit: Int): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where {
                (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and
                    (Issues.changelogSyncedAt.isNull() or (Issues.changelogSyncedAt less Issues.changedAt))
            }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** Marks every id in [issueIds] as changelog-synced — called in the SAME transaction as their history inserts. */
    suspend fun markChangelogSynced(connectionId: UInt, issueIds: List<Long>, now: Long) {
        if (issueIds.isEmpty()) return
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) }) {
                it[changelogSyncedAt] = now
                it[needsProcessing] = true
            }
        }
    }

    /** The WORKLOGS stream's per-issue backfill scan (v0.2.0 plan §7 "WORKLOGS", A1): in-scope issues never worklog-synced. */
    suspend fun staleWorklogIssueIds(connectionId: UInt, limit: Int): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.worklogsSyncedAt.isNull() }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** Marks one issue as worklog-synced — called in the SAME transaction as its worklog-page upserts. */
    suspend fun markWorklogsSynced(connectionId: UInt, issueId: Long, now: Long) {
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }) {
                it[worklogsSyncedAt] = now
                it[needsProcessing] = true
            }
        }
    }

    /**
     * A1's scope filter (v0.2.0 plan §0/§7 "WORKLOGS"): which of [issueIds] are a KNOWN,
     * non-tombstoned `raw.jira_issues` row for [connectionId] — the WORKLOGS stream's incremental
     * step calls this AFTER fetching `worklog/list` details (only that response carries `issueId`)
     * and BEFORE writing anything, so an out-of-scope worklog is dropped without ever reaching
     * `raw.jira_worklogs`.
     */
    suspend fun knownInScopeIssueIds(connectionId: UInt, issueIds: List<Long>): Set<Long> {
        if (issueIds.isEmpty()) return emptySet()
        return suspendTransaction(database) {
            Issues.select(Issues.issueId)
                .where {
                    (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) and
                        Issues.deletedAt.isNull() and Issues.movedOutAt.isNull()
                }
                .map { it[Issues.issueId] }.toList().toSet()
        }
    }

    /**
     * Diffs and writes one in-scope worklog (v0.2.0 plan §7 "WORKLOGS", A1) — the same sha256
     * diff/tombstone-resurrection rule [upsertIssue]/[upsertEntity] use: a worklog can be EDITED
     * after creation (a time-spent correction), unlike a changelog history, so it needs the same
     * "changed_at only moves on genuine content change" rule the issue/entity tables use.
     */
    suspend fun upsertWorklog(
        connectionId: UInt,
        issueId: Long,
        worklogId: Long,
        worklogUpdatedAt: Long,
        payloadJson: String,
        now: Long,
    ): RawUpsertOutcome =
        suspendTransaction(database) {
            val canonical = canonicalJson(payloadJson)
            val hash = sha256Hex(canonical)
            val existing = Worklogs.selectAll()
                .where { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }
                .forUpdate().toList().singleOrNull()

            if (existing == null) {
                Worklogs.insert {
                    it[Worklogs.connectionId] = connectionId
                    it[Worklogs.worklogId] = worklogId
                    it[Worklogs.issueId] = issueId
                    it[Worklogs.worklogUpdatedAt] = worklogUpdatedAt
                    it[payload] = canonical
                    it[sha256] = hash
                    it[firstSeenAt] = now
                    it[fetchedAt] = now
                    it[changedAt] = now
                }
                return@suspendTransaction RawUpsertOutcome.INSERTED
            }

            val tombstoned = existing[Worklogs.deletedAt] != null
            if (existing[Worklogs.sha256] == hash && !tombstoned) {
                Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }) {
                    it[fetchedAt] = now
                }
                return@suspendTransaction RawUpsertOutcome.UNCHANGED
            }

            Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }) {
                it[Worklogs.issueId] = issueId
                it[Worklogs.worklogUpdatedAt] = worklogUpdatedAt
                it[payload] = canonical
                it[sha256] = hash
                it[fetchedAt] = now
                it[changedAt] = now
                it[deletedAt] = null
            }
            if (tombstoned) RawUpsertOutcome.RESURRECTED else RawUpsertOutcome.CHANGED
        }

    /** The WORKLOGS stream's `/worklog/deleted` step (v0.2.0 plan §7, A1): a no-op if [worklogId] was never stored (out of scope). */
    suspend fun tombstoneWorklog(connectionId: UInt, worklogId: Long, now: Long): Int = suspendTransaction(database) {
        Worklogs.update({
            (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) and Worklogs.deletedAt.isNull()
        }) { it[deletedAt] = now }
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

    object ReconcileSeen : Table("raw.jira_reconcile_seen") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val jobId = integer("job_id")
        val issueId = long("issue_id")
        override val primaryKey = PrimaryKey(connectionId, jobId, issueId)
    }

    /**
     * The RECONCILE stream's id-sweep write (v0.2.0 plan §7/§12 item 7): `ON CONFLICT DO NOTHING`
     * on the `(connectionId, jobId, issueId)` PK makes a resumed page (same job, after a lease
     * loss/reclaim) idempotent rather than duplicating.
     */
    suspend fun insertReconcileSeen(connectionId: UInt, jobId: UInt, issueId: Long) {
        suspendTransaction(database) {
            ReconcileSeen.insertIgnore {
                it[ReconcileSeen.connectionId] = connectionId
                it[ReconcileSeen.jobId] = jobId.toInt()
                it[ReconcileSeen.issueId] = issueId
            }
        }
    }

    /**
     * The RECONCILE stream's anti-join (v0.2.0 plan §7): non-tombstoned raw issues for
     * [connectionId] absent from [jobId]'s seen set — Jira either deleted or moved them out of
     * scope while Flow wasn't looking.
     */
    suspend fun issuesMissingFromSeen(connectionId: UInt, jobId: UInt): List<Long> = suspendTransaction(database) {
        val seenIds = ReconcileSeen.select(ReconcileSeen.issueId)
            .where { (ReconcileSeen.connectionId eq connectionId) and (ReconcileSeen.jobId eq jobId.toInt()) }
            .map { it[ReconcileSeen.issueId] }.toList().toSet()
        Issues.select(Issues.issueId)
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.movedOutAt.isNull() }
            .map { it[Issues.issueId] }.toList()
            .filterNot { it in seenIds }
    }

    /** The RECONCILE stream's index-gap case (v0.2.0 plan §7): ids [jobId]'s sweep saw that `raw.jira_issues` never stored. */
    suspend fun seenButUnknownIds(connectionId: UInt, jobId: UInt): List<Long> = suspendTransaction(database) {
        val seenIds = ReconcileSeen.select(ReconcileSeen.issueId)
            .where { (ReconcileSeen.connectionId eq connectionId) and (ReconcileSeen.jobId eq jobId.toInt()) }
            .map { it[ReconcileSeen.issueId] }.toList().toSet()
        val knownIds = Issues.select(Issues.issueId).where { Issues.connectionId eq connectionId }
            .map { it[Issues.issueId] }.toList().toSet()
        (seenIds - knownIds).toList()
    }

    /** Drains every scratch row for [connectionId] (v0.2.0 plan §7): called once a pass's anti-join step completes. */
    suspend fun clearReconcileSeen(connectionId: UInt): Int = suspendTransaction(database) {
        ReconcileSeen.deleteWhere { ReconcileSeen.connectionId eq connectionId }
    }

    /** Test-only inspection: the scratch table's row count for a connection (`JiraSyncPipelineTest`'s "empty after" assertion). */
    internal suspend fun countReconcileSeen(connectionId: UInt): Long = suspendTransaction(database) {
        ReconcileSeen.selectAll().where { ReconcileSeen.connectionId eq connectionId }.count()
    }

    /**
     * The RECONCILE stream's "deleted" tombstone (v0.2.0 plan §7): a 404 on `GET /issue/{id}`.
     * Guarded on `deletedAt.isNull()` so a repeat RECONCILE pass (idempotence) is a no-op once set.
     */
    suspend fun markIssueDeleted(connectionId: UInt, issueId: Long, now: Long): Int = suspendTransaction(database) {
        Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) and Issues.deletedAt.isNull() }) {
            it[deletedAt] = now
            it[needsProcessing] = true
        }
    }

    /**
     * The RECONCILE stream's "moved out of scope" tombstone (v0.2.0 plan §7): a 200 on
     * `GET /issue/{id}` whose project is no longer in the connection's scope — the key/project
     * columns are refreshed to their new value alongside the tombstone. Guarded on
     * `movedOutAt.isNull()` so a repeat RECONCILE pass (idempotence) is a no-op once set.
     */
    suspend fun markIssueMovedOut(
        connectionId: UInt,
        issueId: Long,
        newKey: String,
        newProjectId: Long,
        newProjectKey: String,
        now: Long,
    ): Int = suspendTransaction(database) {
        Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) and Issues.movedOutAt.isNull() }) {
            it[issueKey] = newKey
            it[projectId] = newProjectId
            it[projectKey] = newProjectKey
            it[movedOutAt] = now
            it[needsProcessing] = true
        }
    }

    /**
     * The PROCESS step's claim scan (v0.2.0 plan §7/§8, plan commit 8a): every raw issue still
     * needing a rebuild — flagged `needs_processing`, OR whose `processing_version` disagrees with
     * [currentProcessingVersion] (a version bump reprocesses everything automatically, plan §8),
     * ascending issue id, batches of [limit] (`jira/JiraProcessStream.kt`'s batch size of 50). With
     * [afterIssueId] the scan resumes strictly AFTER that id (keyset paging): the stream passes the
     * last id of the previous page, so an issue that failed and stays flagged is retried by the NEXT
     * PROCESS pass, never re-claimed forever inside this one.
     */
    suspend fun issuesToProcess(
        connectionId: UInt,
        currentProcessingVersion: Int,
        limit: Int,
        afterIssueId: Long? = null,
    ): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where {
                (Issues.connectionId eq connectionId) and
                    (
                        (Issues.needsProcessing eq true) or
                            Issues.processingVersion.isNull() or
                            (Issues.processingVersion neq currentProcessingVersion)
                        ) and
                    (if (afterIssueId == null) Op.TRUE else (Issues.issueId greater afterIssueId))
            }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** One raw issue's PROCESS input (v0.2.0 plan §8): the canonicalized payload plus its current tombstone/process bookkeeping. */
    data class RawIssueForProcessing(
        val issueId: Long,
        val payloadJson: String,
        val sha256: String,
        val deletedAt: Long?,
        val movedOutAt: Long?,
    )

    /** One issue for PROCESS's per-issue fallback — `FOR UPDATE` like [issuesForProcessing] (same lock-until-commit reasoning). */
    suspend fun issueForProcessing(connectionId: UInt, issueId: Long): RawIssueForProcessing? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }
            .forUpdate()
            .toList().singleOrNull()?.let {
                RawIssueForProcessing(issueId, it[Issues.payload], it[Issues.sha256], it[Issues.deletedAt], it[Issues.movedOutAt])
            }
    }

    /**
     * [issueForProcessing] for a whole PROCESS page in ONE query — a row per issue still present
     * (a concurrent PURGE may have removed some; those are simply absent). It takes `FOR UPDATE`,
     * ordered by `issue_id` (a fixed lock order): called inside `JiraProcessStream`'s page
     * transaction, it holds each issue's raw row until the page commits, so a concurrent write that
     * re-flags an issue (`upsertIssue` on a changed payload) WAITS and lands AFTER the commit —
     * its `needs_processing = true` is never overwritten by this page's [markProcessedBatch], and
     * the payload read here is exactly the one the mark stamps. Held for ~50 rows over ~30 ms.
     */
    suspend fun issuesForProcessing(connectionId: UInt, issueIds: List<Long>): List<RawIssueForProcessing> {
        if (issueIds.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) }
                .orderBy(Issues.issueId)
                .forUpdate()
                .map {
                    RawIssueForProcessing(
                        it[Issues.issueId], it[Issues.payload], it[Issues.sha256], it[Issues.deletedAt], it[Issues.movedOutAt],
                    )
                }
                .toList()
        }
    }

    /**
     * [changelogPayloadsForIssue] for a whole PROCESS page in ONE query: issue id → its histories,
     * oldest first — the SAME `(created_at, history_id)` order per issue. An issue without
     * changelogs is absent from the map.
     */
    suspend fun changelogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> {
        if (issueIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            Changelogs.selectAll().where { (Changelogs.connectionId eq connectionId) and (Changelogs.issueId inList issueIds) }
                .toList()
                .groupBy { it[Changelogs.issueId] }
                .mapValues { (_, rows) ->
                    rows.sortedWith(compareBy({ it[Changelogs.createdAt] }, { it[Changelogs.historyId] })).map { it[Changelogs.payload] }
                }
        }
    }

    /** [worklogPayloadsForIssue] for a whole PROCESS page in ONE query: issue id → its non-tombstoned worklogs (absent when none). */
    suspend fun worklogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> {
        if (issueIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            Worklogs.selectAll().where {
                (Worklogs.connectionId eq connectionId) and (Worklogs.issueId inList issueIds) and Worklogs.deletedAt.isNull()
            }.toList().groupBy({ it[Worklogs.issueId] }, { it[Worklogs.payload] })
        }
    }

    /**
     * [markProcessed] for a whole PROCESS page in ONE statement: each row's `processed_hash` is the
     * `sha256` that was READ for it ([shaByIssueId], from [issuesForProcessing]) — a per-row `CASE`,
     * never the column's value at update time, so the mark can only ever claim the payload that was
     * actually normalized.
     */
    suspend fun markProcessedBatch(connectionId: UInt, shaByIssueId: Map<Long, String>, now: Long, processingVersion: Int) {
        if (shaByIssueId.isEmpty()) return
        suspendTransaction(database) {
            val hash = shaByIssueId.entries.fold<Map.Entry<Long, String>, CaseWhen<String>?>(null) { acc, (issueId, sha) ->
                val condition = Issues.issueId eq issueId
                acc?.When(condition, stringParam(sha)) ?: Case().When(condition, stringParam(sha))
            }!!.Else(Issues.sha256)
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId inList shaByIssueId.keys.toList()) }) {
                it[Issues.processedAt] = now
                it[Issues.processedHash] = hash
                it[Issues.processingVersion] = processingVersion
                it[Issues.needsProcessing] = false
            }
        }
    }

    /** Marks one issue processed (v0.2.0 plan §8 step 5) — called in the SAME transaction as its `norm.*` rows' write. */
    suspend fun markProcessed(connectionId: UInt, issueId: Long, processedHash: String, now: Long, processingVersion: Int) {
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }) {
                it[Issues.processedAt] = now
                it[Issues.processedHash] = processedHash
                it[Issues.processingVersion] = processingVersion
                it[Issues.needsProcessing] = false
            }
        }
    }

    /** REPROCESS (v0.2.0 plan §7/§12 item 8): flags EVERY raw issue for [connectionId] for the next PROCESS pass. */
    suspend fun markAllNeedsProcessing(connectionId: UInt): Int = suspendTransaction(database) {
        Issues.update({ Issues.connectionId eq connectionId }) { it[needsProcessing] = true }
    }

    /** Every non-deleted `raw.jira_entities` payload of [kind] for a connection (plan §8) — PROCESS's own reference-row source. */
    suspend fun entityPayloadsByKind(connectionId: UInt, kind: String): List<String> = suspendTransaction(database) {
        Entities.select(Entities.payload)
            .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() }
            .map { it[Entities.payload] }.toList()
    }

    /** One raw issue's full detail (v0.2.0 plan §9/§12 item 8b) — the raw issue inspector's own read shape. */
    data class RawIssueDetail(
        val issueId: Long,
        val issueKey: String,
        val payloadJson: String,
        val sha256: String,
        val fetchedAt: Long,
        val changedAt: Long,
        val deletedAt: Long?,
        val movedOutAt: Long?,
        val needsProcessing: Boolean,
    )

    private fun ResultRow.toRawIssueDetail() = RawIssueDetail(
        issueId = this[Issues.issueId],
        issueKey = this[Issues.issueKey],
        payloadJson = this[Issues.payload],
        sha256 = this[Issues.sha256],
        fetchedAt = this[Issues.fetchedAt],
        changedAt = this[Issues.changedAt],
        deletedAt = this[Issues.deletedAt],
        movedOutAt = this[Issues.movedOutAt],
        needsProcessing = this[Issues.needsProcessing],
    )

    /** The raw issue inspector's "all digits" branch (v0.2.0 plan §9/§12 item 8b) — looked up by the stable Jira id. */
    suspend fun issueById(connectionId: UInt, issueId: Long): RawIssueDetail? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }
            .toList().singleOrNull()?.toRawIssueDetail()
    }

    /** The raw issue inspector's key branch (v0.2.0 plan §9/§12 item 8b) — looked up by the CURRENT `issue_key` (a move rewrites it). */
    suspend fun issueByKey(connectionId: UInt, issueKey: String): RawIssueDetail? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueKey eq issueKey) }
            .toList().singleOrNull()?.toRawIssueDetail()
    }

    /**
     * Every non-deleted `raw.jira_entities` row of [kind] for a connection, WITH its `entity_id`
     * (v0.2.0 plan §8/§12 item 9) — unlike [entityPayloadsByKind], which drops the id since PROCESS's
     * reference-row rebuild never needs it (the payload alone carries its own `id` field). The data
     * profile's workflow section needs the id (here, a project KEY for `PROJECT_STATUSES`) to know
     * WHICH project a payload belongs to.
     */
    suspend fun entityRowsByKind(connectionId: UInt, kind: String): List<Pair<String, String>> = suspendTransaction(database) {
        Entities.select(Entities.entityId, Entities.payload)
            .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() }
            .map { it[Entities.entityId] to it[Entities.payload] }.toList()
    }

    /**
     * Every LIVE (non-tombstoned) issue's raw payload for a connection (v0.2.0 plan §8/§12 item 9)
     * — the data profile's custom-field fill-rate scan.
     */
    suspend fun issuePayloads(connectionId: UInt): List<String> = suspendTransaction(database) {
        Issues.select(Issues.payload)
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.movedOutAt.isNull() }
            .map { it[Issues.payload] }.toList()
    }

    /** One issue's changelog histories, oldest first — `(created_at, history_id)` (v0.2.0 plan §8's tiling input order). */
    suspend fun changelogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> = suspendTransaction(database) {
        Changelogs.selectAll().where { (Changelogs.connectionId eq connectionId) and (Changelogs.issueId eq issueId) }
            .toList()
            .sortedWith(compareBy({ it[Changelogs.createdAt] }, { it[Changelogs.historyId] }))
            .map { it[Changelogs.payload] }
    }

    /** One issue's non-tombstoned worklogs (v0.2.0 plan §8) — `norm.work_item_worklogs`' source. */
    suspend fun worklogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> = suspendTransaction(database) {
        Worklogs.selectAll().where {
            (Worklogs.connectionId eq connectionId) and (Worklogs.issueId eq issueId) and Worklogs.deletedAt.isNull()
        }.map { it[Worklogs.payload] }.toList()
    }

    /** `GET …/{id}/status` counts (v0.2.0 plan §9): every `raw.jira_entities` row for a connection, grouped by kind. */
    suspend fun entityCountsByKind(connectionId: UInt): Map<String, Long> = suspendTransaction(database) {
        Entities.select(Entities.kind).where { Entities.connectionId eq connectionId }
            .toList().groupingBy { it[Entities.kind] }.eachCount().mapValues { it.value.toLong() }
    }

    /**
     * `GET …/{id}/status` counts (v0.2.0 plan §9): raw issues tombstoned as deleted or moved out,
     * and issues still flagged for processing.
     */
    suspend fun countIssuesDeleted(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNotNull() }.count()
    }

    suspend fun countIssuesMovedOut(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and Issues.movedOutAt.isNotNull() }.count()
    }

    suspend fun countNeedsProcessing(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.needsProcessing eq true) }.count()
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

    /** One batch of a connection's `raw.jira_changelogs` rows (the PURGE step, plan §0 A2, V11). */
    suspend fun purgeChangelogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Changelogs.select(Changelogs.historyId).where { Changelogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Changelogs.historyId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Changelogs.deleteWhere { (Changelogs.connectionId eq connectionId) and (Changelogs.historyId inList ids) }
    }

    /** One batch of a connection's `raw.jira_worklogs` rows (the PURGE step, plan §0 A2, V11). */
    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Worklogs.select(Worklogs.worklogId).where { Worklogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Worklogs.worklogId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList ids) }
    }

    /** Test-only inspection: the raw row count for a connection (`JiraRawStoreTest`/`JiraSyncPipelineTest`). */
    internal suspend fun countIssues(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { Issues.connectionId eq connectionId }.count()
    }

    /** Test-only inspection: the changelog history row count for a connection. */
    internal suspend fun countChangelogs(connectionId: UInt): Long = suspendTransaction(database) {
        Changelogs.selectAll().where { Changelogs.connectionId eq connectionId }.count()
    }

    /** Test-only inspection: the worklog row count for a connection, optionally excluding tombstoned rows. */
    internal suspend fun countWorklogs(connectionId: UInt, excludeDeleted: Boolean = false): Long = suspendTransaction(database) {
        val predicate = if (excludeDeleted) {
            (Worklogs.connectionId eq connectionId) and Worklogs.deletedAt.isNull()
        } else {
            Worklogs.connectionId eq connectionId
        }
        Worklogs.selectAll().where { predicate }.count()
    }
}

/** Drains a connection's Jira raw rows in batches — the PURGE step (`ingest/Connector.kt`'s `PurgeStep`, plan §0 A2). */
suspend fun JiraRawStore.purgeAll(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE) {
    while (purgeChangelogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorklogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeIssuesBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeEntitiesBatch(connectionId, batchSize) > 0) { /* drain */ }
}
