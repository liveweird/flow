package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraTrimStream::class.java)

/** Where the TRIM walk is: the table it is in, the last key it finished there, and when the whole walk ended. */
@Serializable
internal enum class TrimPhase(val table: TrimTable?) {
    ISSUES(TrimTable.ISSUES), CHANGELOGS(TrimTable.CHANGELOGS), WORKLOGS(TrimTable.WORKLOGS), DONE(null);

    /** The rows one batch of this phase holds (`TrimTable.batchSize`); a DONE phase has none. */
    fun batchSize(): Int = table?.batchSize ?: 0

    /** The phase after this one (DONE is its own successor). */
    fun next(): TrimPhase = entries.getOrElse(ordinal + 1) { DONE }
}

/** `sync_cursors.cursor` shape of the TRIM stream. [afterKey] is the last issue/history/worklog id finished in [phase]. */
@Serializable
internal data class TrimCursor(
    val phase: TrimPhase = TrimPhase.ISSUES,
    val afterKey: Long = BEFORE_FIRST_KEY,
    val completedAt: Long? = null,
)

/** Raw keys are positive Jira ids; the walk starts before all of them. */
internal const val BEFORE_FIRST_KEY = -1L

/**
 * The TRIM stream (`.claude/docs/ingestion.md` "TRIM"): rewrites the raw rows stored before the payload trim existed
 * (`jira/JiraPayloadTrim.kt`) once, so an install keeps nothing of the dropped data and a later fetch of an unchanged document hashes
 * the same as the row ([RawUpsertOutcome.UNCHANGED]). It is the FIRST step of a SYNC and of a REPROCESS - never of a RECONCILE - and
 * makes no Jira call.
 *
 * It walks `raw.jira_issues`, `raw.jira_changelogs`, `raw.jira_worklogs` (tombstoned rows included) by primary key, reading only the
 * rows a SQL candidate filter says the trim would change ([JiraTrimCandidates]), in batches of `TrimTable.batchSize` candidates (50
 * issues, 200 histories or worklogs). Each batch is ONE transaction that rewrites what the trim changes ([JiraRawTrim], only `payload`
 * and `sha256`) and advances the cursor to the last candidate it read (the keys in between need nothing), so a crash or a lost lease
 * resumes at the last committed batch and finds the same rows (the trim is idempotent). A batch shorter than its size ends the
 * table. When the walk is done the cursor stays as `DONE` with `lastCompletedAt` - unlike a REFERENCE or RECONCILE cursor it is
 * never cleared, because "no row" would mean "not started" and walk three tables again for nothing
 * (deleting the `trim` cursor row on purpose re-runs TRIM, e.g. after a rollback to a build that wrote untrimmed rows). The
 * normalized layer does not change (the equivalence property), so nothing is flagged for PROCESS.
 *
 * Counters (only when non-zero): `issuesTrimmed`, `changelogsTrimmed`, `worklogsTrimmed` (rows rewritten) and `trimHashDrift`
 * (rewritten rows whose stored sha256 was not the hash of their read-back canonical text - a `jsonb` round trip that is not
 * lossless; the row then gets one spurious `CHANGED` the next time Jira serves it, never a wrong normalized row). One INFO line per table.
 */
internal class JiraTrimStream(private val rawStore: JiraRawStore) : Stream {
    override val name: String = "trim"

    override suspend fun run(context: StreamContext) {
        var cursor = context.cursor(name)?.cursor?.let { decodeCursor<TrimCursor>(it) } ?: TrimCursor()
        var tally = PhaseTally()
        while (cursor.phase != TrimPhase.DONE) {
            val table = checkNotNull(cursor.phase.table)
            val phase = cursor.phase
            val batch = context.transaction {
                val batch = rawStore.trimBatch(context.connectionId, table, cursor.afterKey)
                cursor = nextCursor(cursor, batch, context.clock())
                context.putCursor(name, encodeCursor(cursor), lastCompletedAt = cursor.completedAt)
                batch
            }
            tally += batch
            countProgress(context, table, batch)
            if (cursor.phase != phase) {
                log.info(
                    "TRIM {} for data source {}: {} candidate rows read, {} rewritten ({} hash drift)",
                    table, context.connectionId, tally.scanned, tally.rewritten, tally.hashDrift,
                )
                tally = PhaseTally()
            }
            context.heartbeat(changed = batch.rewritten > 0)
        }
    }

    private fun nextCursor(cursor: TrimCursor, batch: TrimBatch, now: Long): TrimCursor {
        val last = batch.lastKey
        if (last != null && batch.scanned >= cursor.phase.batchSize()) return cursor.copy(afterKey = last)
        val next = cursor.phase.next()
        return TrimCursor(next, BEFORE_FIRST_KEY, completedAt = if (next == TrimPhase.DONE) now else null)
    }

    private fun countProgress(context: StreamContext, table: TrimTable, batch: TrimBatch) {
        if (batch.rewritten > 0) {
            val key = when (table) {
                TrimTable.ISSUES -> "issuesTrimmed"
                TrimTable.CHANGELOGS -> "changelogsTrimmed"
                TrimTable.WORKLOGS -> "worklogsTrimmed"
            }
            context.incrementProgress(key, batch.rewritten.toLong())
        }
        if (batch.hashDrift > 0) context.incrementProgress("trimHashDrift", batch.hashDrift.toLong())
    }

    /** The running totals of one table, for its INFO line. */
    private data class PhaseTally(val scanned: Int = 0, val rewritten: Int = 0, val hashDrift: Int = 0) {
        operator fun plus(batch: TrimBatch) = PhaseTally(scanned + batch.scanned, rewritten + batch.rewritten, hashDrift + batch.hashDrift)
    }
}
