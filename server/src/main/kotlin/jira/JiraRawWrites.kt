package ch.nokillswit.jira

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.classifyUpsert
import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraRawWrites::class.java)

/**
 * The raw store's diff-rule writes (v0.2.0 plan §7): the sha256 upsert per issue/entity/worklog, the append-only
 * changelog insert, and the worklog/entity tombstones. Each method opens its OWN `suspendTransaction` (joining an
 * enclosing one — see [JiraRawStore]). The whole-page forms of the four writers are [JiraRawBatchWrites], which shares the one
 * diff rule ([classifyUpsert]) and the per-row change updates at the bottom of this file.
 */
internal class JiraRawWrites(private val database: R2dbcDatabase, private val ceilings: JiraStoredCeilings = JiraStoredCeilings()) {

    /**
     * Diffs and writes one issue (v0.2.0 plan §7 "ISSUES", A1): unchanged sha256 (and not
     * tombstoned) bumps only `fetched_at`; a genuine change refreshes the payload/key/project
     * columns, `changed_at` and flags `needs_processing`; a tombstoned issue seen again clears
     * `deleted_at`/`moved_out_at` (resurrection).
     */
    suspend fun upsertIssue(connectionId: UInt, issue: RawIssueInput, now: Long): RawUpsertOutcome =
        suspendTransaction(database) {
            val canonical = canonicalJson(trimIssue(issue.payload))
            val size = canonical.renderedBytes()
            if (size > ceilings.maxPayloadBytes) {
                log.warnRefused("issue", issue.issueKey, connectionId, size, ceilings.maxPayloadBytes)
                Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }) { it[fetchedAt] = now }
                return@suspendTransaction RawUpsertOutcome.REFUSED
            }
            val hash = sha256Hex(canonical)
            val existing = Issues.selectAll()
                .where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }
                .forUpdate().toList().singleOrNull()
            val tombstoned = existing != null && (existing[Issues.deletedAt] != null || existing[Issues.movedOutAt] != null)
            val outcome = classifyUpsert(existing?.get(Issues.sha256), hash, tombstoned)

            when (outcome) {
                RawUpsertOutcome.INSERTED -> Issues.insert {
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
                RawUpsertOutcome.UNCHANGED ->
                    Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issue.issueId) }) { it[fetchedAt] = now }
                RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED ->
                    changeIssueRow(connectionId, issue, canonical, hash, now, tombstoned)
                RawUpsertOutcome.REFUSED -> error(NEVER_REFUSES)
            }
            outcome
        }

    /** Same diff rule as [upsertIssue], for a reference-data entity (v0.2.0 plan §7 "REFERENCE"). */
    suspend fun upsertEntity(connectionId: UInt, kind: String, entityId: String, document: JsonElement, now: Long): RawUpsertOutcome =
        suspendTransaction(database) {
            val canonical = canonicalJson(document)
            val size = canonical.renderedBytes()
            if (size > ceilings.maxPayloadBytes) {
                log.warnRefused("$kind entity", entityId, connectionId, size, ceilings.maxPayloadBytes)
                touchEntity(connectionId, kind, entityId, now)
                return@suspendTransaction RawUpsertOutcome.REFUSED
            }
            val hash = sha256Hex(canonical)
            val existing = Entities.selectAll()
                .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId) }
                .forUpdate().toList().singleOrNull()
            val deleted = existing != null && existing[Entities.deletedAt] != null
            val outcome = classifyUpsert(existing?.get(Entities.sha256), hash, deleted)

            when (outcome) {
                RawUpsertOutcome.INSERTED -> Entities.insert {
                    it[Entities.connectionId] = connectionId
                    it[Entities.kind] = kind
                    it[Entities.entityId] = entityId
                    it[payload] = canonical
                    it[sha256] = hash
                    it[firstSeenAt] = now
                    it[lastSeenAt] = now
                    it[changedAt] = now
                }
                RawUpsertOutcome.UNCHANGED -> Entities.update({
                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId)
                }) { it[lastSeenAt] = now }
                RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED ->
                    changeEntityRow(connectionId, kind, entityId, canonical, hash, now)
                RawUpsertOutcome.REFUSED -> error(NEVER_REFUSES)
            }
            outcome
        }

    /**
     * Marks a live entity row as seen WITHOUT changing its payload — the REFERENCE pass's way of keeping the previous row of an
     * optional step it skipped (`PROJECT_FIELDS`) out of the end-of-pass tombstone sweep. A no-op for a missing or tombstoned row.
     */
    suspend fun touchEntity(connectionId: UInt, kind: String, entityId: String, now: Long): Int = suspendTransaction(database) {
        Entities.update({
            (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId) and
                Entities.deletedAt.isNull()
        }) { it[lastSeenAt] = now }
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
    ): RawUpsertOutcome = suspendTransaction(database) {
        val size = payloadJson.renderedBytes()
        if (size > ceilings.maxHistoryBytes) {
            log.warnRefused("history", historyId.toString(), connectionId, size, ceilings.maxHistoryBytes)
            return@suspendTransaction RawUpsertOutcome.REFUSED
        }
        val canonical = trimmedHistoryCanonicalJson(payloadJson)
        Changelogs.insertIgnore {
            it[Changelogs.connectionId] = connectionId
            it[Changelogs.historyId] = historyId
            it[Changelogs.issueId] = issueId
            it[Changelogs.createdAt] = createdAt
            it[Changelogs.authorAccountId] = authorAccountId
            it[payload] = canonical
            it[fetchedAt] = now
        }
        RawUpsertOutcome.INSERTED
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
            val size = payloadJson.renderedBytes()
            if (size > ceilings.maxWorklogBytes) {
                log.warnRefused("worklog", worklogId.toString(), connectionId, size, ceilings.maxWorklogBytes)
                Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }) { it[fetchedAt] = now }
                return@suspendTransaction RawUpsertOutcome.REFUSED
            }
            val canonical = trimmedWorklogCanonicalJson(payloadJson)
            val hash = sha256Hex(canonical)
            val existing = Worklogs.selectAll()
                .where { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }
                .forUpdate().toList().singleOrNull()
            val tombstoned = existing != null && existing[Worklogs.deletedAt] != null
            val outcome = classifyUpsert(existing?.get(Worklogs.sha256), hash, tombstoned)

            when (outcome) {
                RawUpsertOutcome.INSERTED -> Worklogs.insert {
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
                RawUpsertOutcome.UNCHANGED ->
                    Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }) {
                        it[fetchedAt] = now
                    }
                RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED ->
                    changeWorklogRow(connectionId, issueId, worklogId, worklogUpdatedAt, canonical, hash, now)
                RawUpsertOutcome.REFUSED -> error(NEVER_REFUSES)
            }
            outcome
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
        now: Long = nowMillis(),
    ): Int =
        suspendTransaction(database) {
            Entities.update({
                (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and
                    (Entities.lastSeenAt less passStartedAt) and Entities.deletedAt.isNull()
            }) { it[deletedAt] = now }
        }
}

/** [classifyUpsert] only classifies a stored row against a new hash; a refusal happens before it, so it never returns REFUSED. */
internal const val NEVER_REFUSES = "classifyUpsert never refuses"

/** A genuine change of an issue row (or its resurrection): refresh the payload/key/project columns, flag it, clear the tombstones. */
internal suspend fun changeIssueRow(
    connectionId: UInt,
    issue: RawIssueInput,
    canonical: String,
    hash: String,
    now: Long,
    tombstoned: Boolean,
) {
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
        // A resurrected issue was skipped by the incremental worklog feed while tombstoned (and that feed's
        // cursor moved on), so the per-issue worklog backfill must run for it again. The changelog needs no
        // reset: `changed_at = now` already makes it stale against `changelog_synced_at`.
        if (tombstoned) it[worklogsSyncedAt] = null
    }
}

/** A genuine change of an entity row (or its resurrection). */
internal suspend fun changeEntityRow(connectionId: UInt, kind: String, entityId: String, canonical: String, hash: String, now: Long) {
    Entities.update({
        (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId eq entityId)
    }) {
        it[payload] = canonical
        it[sha256] = hash
        it[lastSeenAt] = now
        it[changedAt] = now
        it[deletedAt] = null
    }
}

/** A genuine change of a worklog row (or its resurrection). */
internal suspend fun changeWorklogRow(
    connectionId: UInt,
    issueId: Long,
    worklogId: Long,
    worklogUpdatedAt: Long,
    canonical: String,
    hash: String,
    now: Long,
) {
    Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq worklogId) }) {
        it[Worklogs.issueId] = issueId
        it[Worklogs.worklogUpdatedAt] = worklogUpdatedAt
        it[payload] = canonical
        it[sha256] = hash
        it[fetchedAt] = now
        it[changedAt] = now
        it[deletedAt] = null
    }
}
