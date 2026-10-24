package ch.nokillswit.jira

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The three raw tables the TRIM step rewrites, in the order it visits them, with the rows one batch holds. An issue payload is the
 * big one (tens of KB, hundreds for a heavy ticket), so its batch is the size PROCESS reads (50); a history or worklog is ~1 KB.
 * All stay well under the ~250 rows a result holds before r2dbc-postgresql's response queue overflows (`build-times.md` WHY 24).
 */
internal enum class TrimTable(val batchSize: Int) { ISSUES(50), CHANGELOGS(200), WORKLOGS(200) }

/**
 * What one [JiraRawTrim.trimBatch] did: [scanned] candidate rows read (a batch shorter than the table's batch size is its last),
 * [lastKey] the highest candidate key read (null for an empty batch), [rewritten] rows whose payload changed, and [hashDrift] the
 * rewritten rows whose STORED sha256 was not the hash of their read-back canonical text (a `jsonb` round trip that is not lossless;
 * always 0 for changelogs, which keep no hash).
 */
internal data class TrimBatch(val scanned: Int, val lastKey: Long?, val rewritten: Int, val hashDrift: Int)

/**
 * The TRIM step's raw-store half (`jira/JiraTrimStream.kt`): reads one keyset batch of a raw table `FOR UPDATE` and rewrites the rows
 * the trim functions ([trimIssue]/[trimHistory]/[trimWorklog]) change, in the CALLER's transaction (the stream puts its cursor in the
 * same one). This is the ONE sanctioned rewrite of the otherwise append-only raw tables, and it moves ONLY `payload` and `sha256`:
 * never `changed_at`, `fetched_at`, `needs_processing`, `processed_*`, `*_synced_at` or a tombstone - the stale-claim scans compare
 * `changed_at` against the synced stamps (`JiraRawStreamScans`), so a touched `changed_at` would re-fetch every changelog, and the
 * normalized rows do not change at all (the equivalence property, `JiraPayloadTrimTest`), so nothing is flagged for PROCESS.
 * Only rows the SQL candidate filter ([JiraTrimCandidates]) lets through are read at all, so an already-trimmed row never leaves
 * PostgreSQL; the batch is the first `batchSize` candidates after `afterKey`, and the keys between them are rows that need nothing,
 * so the cursor may advance to the last candidate. A row the trim does not change is not written. The new `sha256` is the hash of
 * the new canonical text, the same text and hash `upsertIssue`/`upsertWorklog` compute for the same Jira document, so the next fetch
 * of an unchanged row is `UNCHANGED`.
 */
internal class JiraRawTrim(private val database: R2dbcDatabase) {

    suspend fun trimBatch(connectionId: UInt, table: TrimTable, afterKey: Long): TrimBatch = suspendTransaction(database) {
        when (table) {
            TrimTable.ISSUES -> trimIssues(connectionId, afterKey)
            TrimTable.CHANGELOGS -> trimChangelogs(connectionId, afterKey)
            TrimTable.WORKLOGS -> trimWorklogs(connectionId, afterKey)
        }
    }

    private suspend fun trimIssues(connectionId: UInt, afterKey: Long): TrimBatch {
        val rows = Issues.select(Issues.issueId, Issues.payload, Issues.sha256)
            .where {
                (Issues.connectionId eq connectionId) and (Issues.issueId greater afterKey) and
                    JiraTrimCandidates.forTable(TrimTable.ISSUES, Issues.payload)
            }
            .orderBy(Issues.issueId).limit(TrimTable.ISSUES.batchSize).forUpdate().toList()
        return rewrite(rows.size, rows.lastOrNull()?.get(Issues.issueId)) { count ->
            for (row in rows) {
                val change = trimmedText(row[Issues.payload], ::trimIssue) ?: continue
                Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq row[Issues.issueId]) }) {
                    it[payload] = change.text
                    it[sha256] = change.hash
                }
                count.rewritten++
                if (change.readBackHash != row[Issues.sha256]) count.hashDrift++
            }
        }
    }

    private suspend fun trimChangelogs(connectionId: UInt, afterKey: Long): TrimBatch {
        val rows = Changelogs.select(Changelogs.historyId, Changelogs.payload)
            .where {
                (Changelogs.connectionId eq connectionId) and (Changelogs.historyId greater afterKey) and
                    JiraTrimCandidates.forTable(TrimTable.CHANGELOGS, Changelogs.payload)
            }
            .orderBy(Changelogs.historyId).limit(TrimTable.CHANGELOGS.batchSize).forUpdate().toList()
        return rewrite(rows.size, rows.lastOrNull()?.get(Changelogs.historyId)) { count ->
            for (row in rows) {
                val change = trimmedText(row[Changelogs.payload], ::trimHistory) ?: continue
                Changelogs.update({ (Changelogs.connectionId eq connectionId) and (Changelogs.historyId eq row[Changelogs.historyId]) }) {
                    it[payload] = change.text
                }
                count.rewritten++
            }
        }
    }

    private suspend fun trimWorklogs(connectionId: UInt, afterKey: Long): TrimBatch {
        val rows = Worklogs.select(Worklogs.worklogId, Worklogs.payload, Worklogs.sha256)
            .where {
                (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId greater afterKey) and
                    JiraTrimCandidates.forTable(TrimTable.WORKLOGS, Worklogs.payload)
            }
            .orderBy(Worklogs.worklogId).limit(TrimTable.WORKLOGS.batchSize).forUpdate().toList()
        return rewrite(rows.size, rows.lastOrNull()?.get(Worklogs.worklogId)) { count ->
            for (row in rows) {
                val change = trimmedText(row[Worklogs.payload], ::trimWorklog) ?: continue
                Worklogs.update({ (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId eq row[Worklogs.worklogId]) }) {
                    it[payload] = change.text
                    it[sha256] = change.hash
                }
                count.rewritten++
                if (change.readBackHash != row[Worklogs.sha256]) count.hashDrift++
            }
        }
    }

    /** The mutable tally of one batch. */
    private class Count(var rewritten: Int = 0, var hashDrift: Int = 0)

    private inline fun rewrite(scanned: Int, lastKey: Long?, body: (Count) -> Unit): TrimBatch {
        val count = Count()
        body(count)
        return TrimBatch(scanned, lastKey, count.rewritten, count.hashDrift)
    }

    /** A rewritten row: its new canonical [text] and [hash], and the hash of the text as it was read back ([readBackHash]). */
    private class Change(val text: String, val hash: String, val readBackHash: String)

    /** [stored] trimmed by [trim], or null when the trim changes nothing (the row is then left alone). */
    private fun trimmedText(stored: String, trim: (JsonElement) -> JsonElement): Change? {
        val parsed = Json.parseToJsonElement(stored)
        val trimmed = trim(parsed)
        if (trimmed == parsed) return null
        val text = canonicalJson(trimmed)
        return Change(text, sha256Hex(text), sha256Hex(canonicalJson(parsed)))
    }
}
