package ch.nokillswit.jira

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraRawBatchWrites::class.java)

/** The most keys one `IN (…)` list of this file's SELECT/UPDATE statements carries (one bind parameter each). */
private const val KEY_CHUNK = 1_000

/**
 * One reference-data entity [JiraRawBatchWrites.upsertEntities] stores: its id within the kind and the payload as Jira returned it -
 * the tree the stream parsed (canonicalized in place, never re-parsed from text; build-times WHY 48).
 */
data class RawEntityInput(val entityId: String, val payload: JsonElement)

/**
 * One worklog [JiraRawBatchWrites.upsertWorklogs] stores: the payload as the WORKLOGS stream holds it (already trimmed there,
 * `trimWorklog`), not yet canonicalized; the writer trims again (idempotent) and canonicalizes.
 */
data class RawWorklogInput(val issueId: Long, val worklogId: Long, val worklogUpdatedAt: Long, val payloadJson: String)

/** One changelog history [JiraRawBatchWrites.insertChangelogs] stores (the payload as Jira returned it, not yet trimmed). */
data class RawChangelogInput(
    val historyId: Long,
    val issueId: Long,
    val createdAt: Long,
    val authorAccountId: String?,
    val payloadJson: String,
)

/**
 * The batch form of [JiraRawWrites]' four writers (`.claude/docs/build-times.md` WHY 21): a whole page of issues, a whole REFERENCE
 * page of entities, a whole backfill batch of worklogs or a whole bulk answer of changelog histories in a handful of statements
 * instead of `SELECT … FOR UPDATE` + `INSERT` per row in a transaction of its own (~11,800 statements in ~1,540 transactions per
 * stub SYNC). Every method opens its own `suspendTransaction` (joining an enclosing one), like the single-row writers.
 *
 * The result is the one the single-row methods leave, row for row, and so are the returned outcomes (in input order):
 * - ONE `SELECT key, sha256, tombstones … WHERE key IN (…) ORDER BY key FOR UPDATE` per [KEY_CHUNK] keys (the keys are sorted
 *   before chunking, so rows are locked in key order across chunks too);
 * - the shared pure rule [classifyUpsert] decides each row, exactly as the single-row path does;
 * - INSERTED rows go out as multi-row `INSERT`s ([insertRows]), UNCHANGED rows as ONE `UPDATE … WHERE key IN (…)` of the single
 *   column the single-row path touches, and CHANGED/RESURRECTED rows (a minority outside a backfill) through the very per-row
 *   `update` helpers the single-row path uses (`changeIssueRow` & co: `upsertRows` cannot express them, an omitted NOT NULL
 *   `first_seen_at` fails the INSERT half before the conflict is looked at);
 * - a key repeated within one call is written by the single-row path AFTER the batch has been applied, so the outcome of the
 *   second occurrence is judged against the first one's write, as in a sequential run.
 */
internal class JiraRawBatchWrites(
    private val database: R2dbcDatabase,
    private val single: JiraRawWrites,
    private val ceilings: JiraStoredCeilings = JiraStoredCeilings(),
) {

    /** What the stored row of a key says about the rule: its hash and whether it is tombstoned. */
    private class Stored(val hash: String, val tombstoned: Boolean)

    /** One input with the key, canonical text and hash the rule works on (computed once). */
    private class Prepared<I, K>(val input: I, val key: K, val canonical: String, val hash: String)

    /** The table-specific half of [diffBatch]. */
    private class Ops<I, K>(
        val load: suspend (List<K>) -> Map<K, Stored>,
        val insert: suspend (List<Prepared<I, K>>) -> Unit,
        val touch: suspend (List<K>) -> Unit,
        val change: suspend (Prepared<I, K>, Boolean) -> Unit,
        val again: suspend (I) -> RawUpsertOutcome,
    )

    /** Same outcomes and rows as N [JiraRawWrites.upsertIssue] calls, in the statements described on the class. */
    suspend fun upsertIssues(connectionId: UInt, issues: List<RawIssueInput>, now: Long): List<RawUpsertOutcome> {
        if (issues.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            diffBatch(
                issues,
                keyOf = { it.issueId },
                prepare = {
                    val canonical = canonicalJson(trimIssue(it.payload))
                    Prepared(it, it.issueId, canonical, sha256Hex(canonical)).takeUnless { row ->
                        row.refused("issue", it.issueKey, connectionId, ceilings.maxPayloadBytes)
                    }
                },
                ops = Ops(
                    load = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                            Issues.select(Issues.issueId, Issues.sha256, Issues.deletedAt, Issues.movedOutAt)
                                .where { (Issues.connectionId eq connectionId) and (Issues.issueId inList chunk) }
                                .orderBy(Issues.issueId)
                                .forUpdate().toList()
                        }.associate {
                            it[Issues.issueId] to Stored(it[Issues.sha256], it[Issues.deletedAt] != null || it[Issues.movedOutAt] != null)
                        }
                    },
                    insert = { rows ->
                        Issues.insertRows(rows) {
                            this[Issues.connectionId] = connectionId
                            this[Issues.issueId] = it.input.issueId
                            this[Issues.issueKey] = it.input.issueKey
                            this[Issues.projectId] = it.input.projectId
                            this[Issues.projectKey] = it.input.projectKey
                            this[Issues.issueUpdatedAt] = it.input.issueUpdatedAt
                            this[Issues.payload] = it.canonical
                            this[Issues.sha256] = it.hash
                            this[Issues.firstSeenAt] = now
                            this[Issues.fetchedAt] = now
                            this[Issues.changedAt] = now
                            this[Issues.needsProcessing] = true
                        }
                    },
                    touch = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId inList chunk) }) {
                                it[fetchedAt] = now
                            }
                        }
                    },
                    change = { row, tombstoned -> changeIssueRow(connectionId, row.input, row.canonical, row.hash, now, tombstoned) },
                    again = { single.upsertIssue(connectionId, it, now) },
                ),
            )
        }
    }

    /** Same outcomes and rows as N [JiraRawWrites.upsertEntity] calls of one [kind]. */
    suspend fun upsertEntities(connectionId: UInt, kind: String, entities: List<RawEntityInput>, now: Long): List<RawUpsertOutcome> {
        if (entities.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            diffBatch(
                entities,
                keyOf = { it.entityId },
                prepare = {
                    val canonical = canonicalJson(it.payload)
                    Prepared(it, it.entityId, canonical, sha256Hex(canonical)).takeUnless { row ->
                        row.refused("$kind entity", it.entityId, connectionId, ceilings.maxPayloadBytes)
                    }
                },
                ops = Ops(
                    load = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                            Entities.select(Entities.entityId, Entities.sha256, Entities.deletedAt)
                                .where {
                                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId inList chunk)
                                }
                                .orderBy(Entities.entityId)
                                .forUpdate().toList()
                        }.associate { it[Entities.entityId] to Stored(it[Entities.sha256], it[Entities.deletedAt] != null) }
                    },
                    insert = { rows ->
                        Entities.insertRows(rows) {
                            this[Entities.connectionId] = connectionId
                            this[Entities.kind] = kind
                            this[Entities.entityId] = it.key
                            this[Entities.payload] = it.canonical
                            this[Entities.sha256] = it.hash
                            this[Entities.firstSeenAt] = now
                            this[Entities.lastSeenAt] = now
                            this[Entities.changedAt] = now
                        }
                    },
                    touch = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                            // Like the single path ([JiraRawWrites.touchEntity]): a tombstoned row is never revived by a touch.
                            Entities.update({
                                (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId inList chunk) and
                                    Entities.deletedAt.isNull()
                            }) { it[lastSeenAt] = now }
                        }
                    },
                    change = { row, _ -> changeEntityRow(connectionId, kind, row.key, row.canonical, row.hash, now) },
                    again = { single.upsertEntity(connectionId, kind, it.entityId, it.payload, now) },
                ),
            )
        }
    }

    /** Same outcomes and rows as N [JiraRawWrites.upsertWorklog] calls. */
    suspend fun upsertWorklogs(connectionId: UInt, worklogs: List<RawWorklogInput>, now: Long): List<RawUpsertOutcome> {
        if (worklogs.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            diffBatch(
                worklogs,
                keyOf = { it.worklogId },
                prepare = prepare@{
                    // Checked on the text it arrived as, BEFORE any parse.
                    if (textRefused("worklog", it.worklogId.toString(), it.payloadJson, connectionId, ceilings.maxWorklogBytes)) {
                        return@prepare null
                    }
                    val canonical = trimmedWorklogCanonicalJson(it.payloadJson)
                    Prepared(it, it.worklogId, canonical, sha256Hex(canonical))
                },
                ops = Ops(
                    load = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).flatMap { chunk ->
                            Worklogs.select(Worklogs.worklogId, Worklogs.sha256, Worklogs.deletedAt)
                                .where { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList chunk) }
                                .orderBy(Worklogs.worklogId)
                                .forUpdate().toList()
                        }.associate { it[Worklogs.worklogId] to Stored(it[Worklogs.sha256], it[Worklogs.deletedAt] != null) }
                    },
                    insert = { rows ->
                        Worklogs.insertRows(rows) {
                            this[Worklogs.connectionId] = connectionId
                            this[Worklogs.worklogId] = it.input.worklogId
                            this[Worklogs.issueId] = it.input.issueId
                            this[Worklogs.worklogUpdatedAt] = it.input.worklogUpdatedAt
                            this[Worklogs.payload] = it.canonical
                            this[Worklogs.sha256] = it.hash
                            this[Worklogs.firstSeenAt] = now
                            this[Worklogs.fetchedAt] = now
                            this[Worklogs.changedAt] = now
                        }
                    },
                    touch = { keys ->
                        keys.sorted().chunked(KEY_CHUNK).forEach { chunk ->
                            Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList chunk) }) {
                                it[fetchedAt] = now
                            }
                        }
                    },
                    change = { row, _ ->
                        changeWorklogRow(connectionId, row.input.issueId, row.key, row.input.worklogUpdatedAt, row.canonical, row.hash, now)
                    },
                    again = { single.upsertWorklog(connectionId, it.issueId, it.worklogId, it.worklogUpdatedAt, it.payloadJson, now) },
                ),
            )
        }
    }

    /**
     * Same rows as N [JiraRawWrites.insertChangelog] calls: ONE multi-row `INSERT … ON CONFLICT DO NOTHING` per chunk. A history
     * is immutable, so the rule stays "the first write wins": one already stored, or repeated in [histories], is dropped silently
     * (its outcome is still INSERTED - nothing distinguishes it). A history over [JiraStoredCeilings.maxHistoryBytes] is
     * [RawUpsertOutcome.REFUSED] on the text it arrived as, before any parse, and is not written. Outcomes in input order.
     */
    suspend fun insertChangelogs(connectionId: UInt, histories: List<RawChangelogInput>, now: Long): List<RawUpsertOutcome> {
        if (histories.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val refused = histories.map {
                textRefused("history", it.historyId.toString(), it.payloadJson, connectionId, ceilings.maxHistoryBytes)
            }
            val prepared = histories.filterIndexed { index, _ -> !refused[index] }.map { it to trimmedHistoryCanonicalJson(it.payloadJson) }
            Changelogs.insertRowsIgnoringConflicts(prepared) { (history, canonical) ->
                this[Changelogs.connectionId] = connectionId
                this[Changelogs.historyId] = history.historyId
                this[Changelogs.issueId] = history.issueId
                this[Changelogs.createdAt] = history.createdAt
                this[Changelogs.authorAccountId] = history.authorAccountId
                this[Changelogs.payload] = canonical
                this[Changelogs.fetchedAt] = now
            }
            refused.map { if (it) RawUpsertOutcome.REFUSED else RawUpsertOutcome.INSERTED }
        }
    }

    /** True (after one WARN) when [text], as it arrived and before any parse, is over [ceiling] bytes. */
    private fun textRefused(what: String, id: String, text: String, connectionId: UInt, ceiling: Long): Boolean {
        val size = text.renderedBytes()
        if (size <= ceiling) return false
        log.warnRefused(what, id, connectionId, size, ceiling)
        return true
    }

    /** True (after one WARN) when this row's canonical text is over [ceiling] bytes. */
    private fun Prepared<*, *>.refused(what: String, id: String, connectionId: UInt, ceiling: Long): Boolean =
        textRefused(what, id, canonical, connectionId, ceiling)

    /**
     * The table-independent body over [inputs]: [prepare] each (null = REFUSED, over its ceiling), classify every first occurrence of a
     * key, apply the writes, replay the repeats singly. A refused item writes nothing: a stored row of its key is only touched
     * ([Ops.touch]), so a pass that sweeps unseen rows never tombstones it, and it is no change. Outcomes in input order.
     */
    private suspend fun <I, K> diffBatch(
        inputs: List<I>,
        keyOf: (I) -> K,
        prepare: (I) -> Prepared<I, K>?,
        ops: Ops<I, K>,
    ): List<RawUpsertOutcome> {
        val prepared = ArrayList<Prepared<I, K>>(inputs.size)
        val refused = HashSet<Int>()
        inputs.forEachIndexed { index, input -> prepare(input)?.let(prepared::add) ?: refused.add(index) }
        val within = diffPrepared(prepared, ops).iterator()
        if (refused.isNotEmpty()) ops.touch(refused.map { keyOf(inputs[it]) }.distinct())
        return inputs.indices.map { if (it in refused) RawUpsertOutcome.REFUSED else within.next() }
    }

    private suspend fun <I, K> diffPrepared(prepared: List<Prepared<I, K>>, ops: Ops<I, K>): List<RawUpsertOutcome> {
        val outcomes = arrayOfNulls<RawUpsertOutcome>(prepared.size)
        val seen = HashSet<K>()
        val repeats = ArrayList<Int>()
        val firsts = ArrayList<Int>(prepared.size)
        prepared.forEachIndexed { index, row -> if (seen.add(row.key)) firsts += index else repeats += index }

        val stored = ops.load(firsts.map { prepared[it].key })
        val toInsert = ArrayList<Prepared<I, K>>()
        val toTouch = ArrayList<K>()
        for (index in firsts) {
            val row = prepared[index]
            val current = stored[row.key]
            val tombstoned = current?.tombstoned ?: false
            val outcome = classifyUpsert(current?.hash, row.hash, tombstoned)
            outcomes[index] = outcome
            when (outcome) {
                RawUpsertOutcome.INSERTED -> toInsert += row
                RawUpsertOutcome.UNCHANGED -> toTouch += row.key
                RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED -> ops.change(row, tombstoned)
                RawUpsertOutcome.REFUSED -> error(NEVER_REFUSES)
            }
        }
        if (toInsert.isNotEmpty()) ops.insert(toInsert)
        if (toTouch.isNotEmpty()) ops.touch(toTouch)
        for (index in repeats) outcomes[index] = ops.again(prepared[index].input)
        return outcomes.map { checkNotNull(it) }
    }
}
