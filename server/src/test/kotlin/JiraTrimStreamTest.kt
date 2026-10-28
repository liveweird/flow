package ch.nokillswit

import ch.nokillswit.DirtyJira.injectIntoHistory
import ch.nokillswit.DirtyJira.injectIntoIssue
import ch.nokillswit.DirtyJira.injectIntoWorklog
import ch.nokillswit.DirtyJira.obj
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.LeaseLostException
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.TIME_EXCEEDED_TRIP
import ch.nokillswit.jira.CURSOR_JSON
import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraTrimStream
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.TrimCursor
import ch.nokillswit.jira.TrimPhase
import ch.nokillswit.jira.TrimTable
import ch.nokillswit.norm.PROCESSING_VERSION
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two columns the TRIM step may move. */
private val MOVABLE = setOf("payload", "sha256")

/** Every other column of a row, as a map (a byte-for-byte witness for the rest). */
private typealias Untouched = Map<Long, Map<String, Any?>>

/** The rows' payload and sha256 (parsed, so `jsonb` formatting does not matter), by key. */
private typealias Contents = Map<Long, Pair<JsonElement, String?>>

/**
 * The TRIM stream (`jira/JiraTrimStream.kt`), over clones of the shared synced fixture: rows put back into the shape Jira sends
 * (full users, the dropped keys) by a raw `UPDATE`, as an install that synced before the payload trim existed holds them. TRIM
 * must bring every table back to exactly the trimmed original, move nothing but `payload`/`sha256`, hash like the write path,
 * finish with a `DONE` cursor, write nothing the second time, resume after a lost lease, and leave PROCESS's output unchanged.
 */
class JiraTrimStreamTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }

    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val db get() = sharedDatabaseForTests()

    /** A clone, and the dirty text each injected row now holds (what Jira would have sent), by key. */
    private class Dirty(
        val connId: UInt,
        val issues: Map<Long, String>,
        val histories: Map<Long, String>,
        val worklogs: Map<Long, String>,
        val tombstonedIssueId: Long,
        val emptiedHistoryId: Long,
    )

    private suspend fun clone(prefix: String): UInt {
        val source = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false)
        SyncedStubFixture.cloneRawData(source, connId)
        return connId
    }

    private suspend fun issueKeys(connId: UInt): List<Long> = suspendTransaction(db) {
        JiraRawStore.Issues.select(JiraRawStore.Issues.issueId).where { JiraRawStore.Issues.connectionId eq connId }
            .toList().map { it[JiraRawStore.Issues.issueId] }.sorted()
    }

    private suspend fun historyKeys(connId: UInt): List<Long> = suspendTransaction(db) {
        JiraRawStore.Changelogs.select(JiraRawStore.Changelogs.historyId).where { JiraRawStore.Changelogs.connectionId eq connId }
            .toList().map { it[JiraRawStore.Changelogs.historyId] }.sorted()
    }

    private suspend fun worklogKeys(connId: UInt): List<Long> = suspendTransaction(db) {
        JiraRawStore.Worklogs.select(JiraRawStore.Worklogs.worklogId).where { JiraRawStore.Worklogs.connectionId eq connId }
            .toList().map { it[JiraRawStore.Worklogs.worklogId] }.sorted()
    }

    /** Indexes that straddle every batch boundary of [size] rows, plus both ends and a few more. */
    private fun sampleIndexes(size: Int, batchSize: Int): List<Int> =
        (listOf(0, 1, 5, size - 1, size - 2, size / 2) + (1..size / batchSize).flatMap {
            listOf(it * batchSize - 1, it * batchSize, it * batchSize + 1)
        }).filter { it in 0 until size }.distinct().sorted()

    /** Puts a clone's sampled rows back into the shape Jira sends, with the hash the pre-trim write path stored. */
    private suspend fun makeDirty(connId: UInt, tombstone: Boolean = true, emptyOne: Boolean = true): Dirty {
        val issueIds = issueKeys(connId)
        val historyIds = historyKeys(connId)
        val worklogIds = worklogKeys(connId)
        // The row at index 2 of every table is left clean: the "already trimmed" row TRIM must not touch.
        val issueSample = sampleIndexes(issueIds.size, TrimTable.ISSUES.batchSize).filter { it != 2 }.map { issueIds[it] }
        val historySample = sampleIndexes(historyIds.size, TrimTable.CHANGELOGS.batchSize).filter { it != 2 }.map { historyIds[it] }
        val worklogSample = sampleIndexes(worklogIds.size, TrimTable.WORKLOGS.batchSize).filter { it != 2 }.map { worklogIds[it] }

        val dirtyIssues = mutableMapOf<Long, String>()
        val dirtyHistories = mutableMapOf<Long, String>()
        val dirtyWorklogs = mutableMapOf<Long, String>()
        suspendTransaction(db) {
            for (id in issueSample) {
                val stored = JiraRawStore.Issues.selectAll()
                    .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq id) }
                    .toList().single()[JiraRawStore.Issues.payload]
                val dirty = injectIntoIssue(stored)
                dirtyIssues[id] = dirty
                JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq id) }) {
                    it[payload] = canonicalJson(dirty)
                    it[sha256] = sha256Hex(canonicalJson(dirty))
                }
            }
            historySample.forEachIndexed { index, id ->
                val stored = JiraRawStore.Changelogs.selectAll()
                    .where { (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq id) }
                    .toList().single()[JiraRawStore.Changelogs.payload]
                val dirty = injectIntoHistory(stored, index).toString()
                dirtyHistories[id] = dirty
                JiraRawStore.Changelogs.update({
                    (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq id)
                }) {
                    it[payload] = canonicalJson(dirty)
                }
            }
            for (id in worklogSample) {
                val stored = JiraRawStore.Worklogs.selectAll()
                    .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq id) }
                    .toList().single()[JiraRawStore.Worklogs.payload]
                val dirty = injectIntoWorklog(stored).toString()
                dirtyWorklogs[id] = dirty
                JiraRawStore.Worklogs.update({
                    (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq id)
                }) {
                    it[payload] = canonicalJson(dirty)
                    it[sha256] = sha256Hex(canonicalJson(dirty))
                }
            }
        }
        // A tombstoned dirty issue, and a history that holds NOTHING but dropped items.
        val tombstoned = issueSample.last()
        if (tombstone) suspendTransaction(db) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq tombstoned) }) {
                it[deletedAt] = 1_700_000_000_000L
                it[needsProcessing] = false
                it[changedAt] = 1_650_000_000_000L
                it[changelogSyncedAt] = 1_660_000_000_000L
            }
        }
        // Its original items are replaced, so this ONE history differs from the reference clone by design: the all-dropped history.
        val emptied = historySample.first()
        if (emptyOne) {
            val onlyDropped = obj(dirtyHistories.getValue(emptied)).let { history ->
                val items = JsonArray(
                    listOf(obj(DirtyJira.DESCRIPTION_EDIT), obj(DirtyJira.REMOTE_LINK), obj(DirtyJira.REMOTE_WORK_ITEM_LINK)),
                )
                JsonObject(history + ("items" to items)).toString()
            }
            dirtyHistories[emptied] = onlyDropped
            suspendTransaction(db) {
                JiraRawStore.Changelogs.update({
                    (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq emptied)
                }) {
                    it[payload] = canonicalJson(onlyDropped)
                }
            }
        }
        return Dirty(connId, dirtyIssues, dirtyHistories, dirtyWorklogs, tombstoned, emptied)
    }

    /** Every column of every row except the ones TRIM may move, by table and key. */
    private suspend fun untouched(connId: UInt): Map<String, Untouched> = suspendTransaction(db) {
        fun rowsOf(rows: List<ResultRow>, columns: List<Column<*>>, key: Column<Long>): Untouched =
            rows.associate { row -> row[key] to columns.filter { it.name !in MOVABLE }.associate { it.name to row[it] } }
        mapOf(
            "issues" to rowsOf(
                JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList(),
                JiraRawStore.Issues.columns, JiraRawStore.Issues.issueId,
            ),
            "changelogs" to rowsOf(
                JiraRawStore.Changelogs.selectAll().where { JiraRawStore.Changelogs.connectionId eq connId }.toList(),
                JiraRawStore.Changelogs.columns, JiraRawStore.Changelogs.historyId,
            ),
            "worklogs" to rowsOf(
                JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList(),
                JiraRawStore.Worklogs.columns, JiraRawStore.Worklogs.worklogId,
            ),
        )
    }

    private suspend fun contents(connId: UInt): Map<String, Contents> = suspendTransaction(db) {
        mapOf(
            "issues" to JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList().associate {
                val payload = Json.parseToJsonElement(it[JiraRawStore.Issues.payload])
                it[JiraRawStore.Issues.issueId] to (payload to it[JiraRawStore.Issues.sha256])
            },
            "changelogs" to JiraRawStore.Changelogs.selectAll().where { JiraRawStore.Changelogs.connectionId eq connId }
                .toList().associate {
                it[JiraRawStore.Changelogs.historyId] to (Json.parseToJsonElement(it[JiraRawStore.Changelogs.payload]) to null)
            },
            "worklogs" to JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList().associate {
                val payload = Json.parseToJsonElement(it[JiraRawStore.Worklogs.payload])
                it[JiraRawStore.Worklogs.worklogId] to (payload to it[JiraRawStore.Worklogs.sha256])
            },
        )
    }

    /** The rows (and which of payload/sha256) in which [actual] differs from [expected]: empty means identical. */
    private fun diff(expected: Map<String, Contents>, actual: Map<String, Contents>, except: Dirty? = null): List<String> =
        expected.flatMap { (table, rows) ->
            val other = actual.getValue(table)
            (rows.keys + other.keys).sorted().mapNotNull { key ->
                val (ePayload, eHash) = rows[key] ?: return@mapNotNull "$table $key: missing in expected"
                val (aPayload, aHash) = other[key] ?: return@mapNotNull "$table $key: missing in actual"
                when {
                    ePayload != aPayload -> "$table $key: payload differs"
                    eHash != aHash -> "$table $key: sha256 differs"
                    else -> null
                }
            }
        }.filterNot { except != null && it.startsWith("changelogs ${except.emptiedHistoryId}:") }

    private fun context(connId: UInt, heartbeat: suspend (String?, String?) -> Boolean = { _, _ -> true }) =
        StreamContext(connId, 1u, db, cursors, jobHeartbeat = heartbeat)

    private suspend fun trimCursor(connId: UInt): TrimCursor =
        CURSOR_JSON.decodeFromString(assertNotNull(cursors.get(connId, "trim")).cursor)

    @Test
    fun `TRIM brings every dirty row back to the trimmed original, moves nothing but payload and sha256, and hashes like the write path`() =
        runBlocking {
            val reference = clone("jira-trim-reference")
            val dirty = makeDirty(clone("jira-trim-dirty"))
            val referenceContents = contents(reference)
            val untouchedBefore = untouched(dirty.connId)
            assertTrue(dirty.issues.size > 10 && dirty.histories.size > 10 && dirty.worklogs.size > 5, "a real sample per table")
            assertTrue(contents(dirty.connId) != referenceContents, "the injection made the clone differ from the original")

            val context = context(dirty.connId)
            JiraTrimStream(store).run(context)

            // Exactly the original, payload AND hash: trim(inject(x)) == x for every row of every table.
            assertEquals(
                emptyList(), diff(referenceContents, contents(dirty.connId), except = dirty),
                "the trimmed clone equals the untouched clone, row for row (but for the all-dropped history, checked below)",
            )
            assertEquals(
                untouchedBefore, untouched(dirty.connId),
                "no column but payload and sha256 moved (changed_at, tombstones, stamps, flags)",
            )
            val progress = context.progressSnapshot()
            assertEquals(dirty.issues.size.toLong(), progress["issuesTrimmed"])
            assertEquals(dirty.histories.size.toLong(), progress["changelogsTrimmed"])
            assertEquals(dirty.worklogs.size.toLong(), progress["worklogsTrimmed"])
            assertNull(progress["trimHashDrift"], "the stored hashes were of the canonical text, so none drifted")

            // The tombstoned row was trimmed and stays tombstoned; the all-dropped history is KEPT with items: [].
            val tombstoneRow = untouchedBefore.getValue("issues").getValue(dirty.tombstonedIssueId)
            assertEquals(1_700_000_000_000L, tombstoneRow["deleted_at"])
            val emptied = contents(dirty.connId).getValue("changelogs").getValue(dirty.emptiedHistoryId).first.jsonObject
            assertEquals(JsonArray(emptyList()), emptied.getValue("items"))
            assertEquals(dirty.emptiedHistoryId.toString(), emptied.getValue("id").toString().trim('"'))

            // The finished walk: a DONE cursor with its completion time, which is never cleared.
            val cursor = trimCursor(dirty.connId)
            assertEquals(TrimPhase.DONE, cursor.phase)
            assertNotNull(cursor.completedAt)
            assertEquals(cursor.completedAt, assertNotNull(cursors.get(dirty.connId, "trim")).lastCompletedAt)

            // The follow-up write of the SAME untrimmed Jira text answers UNCHANGED: TRIM stored the hash the write path computes.
            val now = System.currentTimeMillis()
            for ((id, text) in dirty.issues.entries.take(12)) {
                val row = suspendTransaction(db) {
                    JiraRawStore.Issues.selectAll()
                        .where { (JiraRawStore.Issues.connectionId eq dirty.connId) and (JiraRawStore.Issues.issueId eq id) }
                        .toList().single()
                }
                val input = RawIssueInput(
                    id, row[JiraRawStore.Issues.issueKey], row[JiraRawStore.Issues.projectId], row[JiraRawStore.Issues.projectKey],
                    row[JiraRawStore.Issues.issueUpdatedAt], Json.parseToJsonElement(text),
                )
                val outcome = if (id == dirty.tombstonedIssueId) RawUpsertOutcome.RESURRECTED else RawUpsertOutcome.UNCHANGED
                assertEquals(outcome, store.upsertIssue(dirty.connId, input, now), "issue $id")
                assertEquals(
                    RawUpsertOutcome.UNCHANGED, store.upsertIssues(reference, listOf(input), now).single(),
                    "the reference clone, batch form, issue $id",
                )
            }
            for ((id, text) in dirty.worklogs.entries.take(12)) {
                val issueId = obj(text).getValue("issueId").toString().trim('"').toLong()
                val updatedAt = suspendTransaction(db) {
                    JiraRawStore.Worklogs.selectAll()
                        .where { (JiraRawStore.Worklogs.connectionId eq dirty.connId) and (JiraRawStore.Worklogs.worklogId eq id) }
                        .toList().single()[JiraRawStore.Worklogs.worklogUpdatedAt]
                }
                assertEquals(
                    RawUpsertOutcome.UNCHANGED, store.upsertWorklog(dirty.connId, issueId, id, updatedAt, text, now),
                    "worklog $id",
                )
            }
        }

    @Test
    fun `a second run writes nothing, and a row that is already trimmed is not touched by the first`() = runBlocking {
        val dirty = makeDirty(clone("jira-trim-twice"))
        JiraTrimStream(store).run(context(dirty.connId))
        val contentsAfterFirst = contents(dirty.connId)
        val untouchedAfterFirst = untouched(dirty.connId)
        val cursorAfterFirst = assertNotNull(cursors.get(dirty.connId, "trim"))

        val second = context(dirty.connId)
        JiraTrimStream(store).run(second)

        assertEquals(contentsAfterFirst, contents(dirty.connId))
        assertEquals(untouchedAfterFirst, untouched(dirty.connId))
        assertEquals(cursorAfterFirst, assertNotNull(cursors.get(dirty.connId, "trim")), "the DONE cursor row is not even rewritten")
        assertEquals(emptyMap(), second.progressSnapshot().filterKeys { it.endsWith("Trimmed") || it == "trimHashDrift" })

        // Resetting the cursor re-walks every table and still rewrites nothing: the trim is idempotent.
        cursors.clear(dirty.connId, "trim")
        val third = context(dirty.connId)
        JiraTrimStream(store).run(third)
        assertEquals(contentsAfterFirst, contents(dirty.connId))
        assertEquals(emptyMap(), third.progressSnapshot().filterKeys { it.endsWith("Trimmed") || it == "trimHashDrift" })
        assertEquals(TrimPhase.DONE, trimCursor(dirty.connId).phase)
    }

    /** A context whose every heartbeat costs 3 minutes of a 5-minute budget: the second heartbeat is a wall-clock stop. */
    private fun ticking(connId: UInt): StreamContext {
        val clock = AtomicLong(1_900_000_000_000)
        return StreamContext(
            connId, 1u, db, cursors, jobHeartbeat = { _, _ -> clock.addAndGet(180_000); true }, clock = clock::get, maxRunMillis = 300_000,
        )
    }

    @Test
    fun `a wall-clock stop over a walk that rewrote nothing is not progress, over one that rewrote rows it is`() = runBlocking {
        val clean = clone("jira-trim-stop-clean")
        val idle = assertFailsWith<CeilingTripException> { JiraTrimStream(store).run(ticking(clean)) }
        assertEquals(TIME_EXCEEDED_TRIP, idle.code)
        assertFalse(idle.madeProgress, "every row was already trimmed: batches advanced the cursor but changed nothing")

        val dirty = makeDirty(clone("jira-trim-stop-dirty"))
        val busy = assertFailsWith<CeilingTripException> { JiraTrimStream(store).run(ticking(dirty.connId)) }
        assertTrue(busy.madeProgress, "the first batch rewrote dirty rows")
    }

    @Test
    fun `a lease lost mid-walk leaves the cursor on the last candidate read, and the next run resumes`() = runBlocking {
        val reference = clone("jira-trim-resume-reference")
        val dirty = makeDirty(clone("jira-trim-resume"))
        // Every third issue also gets a description: ~400 SPARSE candidates among 1,200 clean-looking rows, more than 3 batches of 50.
        PostgresTestSupport.connect().use {
            it.createStatement().executeUpdate(
                "UPDATE raw.jira_issues SET payload = jsonb_set(payload, '{fields,description}', '\"x\"') " +
                    "WHERE connection_id = ${dirty.connId} AND issue_id % 3 = 0",
            )
        }
        val issueIds = issueKeys(dirty.connId)
        val candidates = issueIds.filter { it % 3 == 0L || it in dirty.issues.keys }
        assertTrue(candidates.size > 3 * TrimTable.ISSUES.batchSize && candidates.size < issueIds.size / 2, "sparse, over several batches")

        var heartbeats = 0
        val lost = context(dirty.connId) { _, _ -> ++heartbeats <= 2 }
        assertFailsWith<LeaseLostException> { JiraTrimStream(store).run(lost) }

        // Three batches committed (the third batch's own heartbeat is the one that reported the loss): the cursor stands on the
        // 150th CANDIDATE, not on the 150th row.
        val midway = trimCursor(dirty.connId)
        assertEquals(TrimPhase.ISSUES, midway.phase)
        assertEquals(candidates[3 * TrimTable.ISSUES.batchSize - 1], midway.afterKey)
        assertNull(midway.completedAt)
        val midContents = contents(dirty.connId).getValue("issues")
        val cleanIssues = contents(reference).getValue("issues")
        assertEquals(
            candidates.filter { it <= midway.afterKey }.filter { midContents.getValue(it) != cleanIssues.getValue(it) }, emptyList(),
            "every candidate up to the cursor is already trimmed",
        )
        val stillDirty = candidates.filter { it > midway.afterKey }
        assertTrue(stillDirty.isNotEmpty() && stillDirty.all { midContents.getValue(it) != cleanIssues.getValue(it) }, "the rest is not")
        assertTrue(
            contents(dirty.connId).getValue("changelogs") != contents(reference).getValue("changelogs"),
            "later tables are untouched",
        )

        JiraTrimStream(store).run(context(dirty.connId))

        assertEquals(
            emptyList(), diff(contents(reference), contents(dirty.connId), except = dirty),
            "the resumed walk ends at the very rows an uninterrupted one would",
        )
        assertEquals(TrimPhase.DONE, trimCursor(dirty.connId).phase)
    }

    @Test
    fun `payloads of an unexpected shape are neither candidates nor failures`() = runBlocking {
        val connId = clone("jira-trim-odd")
        val issueIds = issueKeys(connId)
        val historyIds = historyKeys(connId)
        val worklogIds = worklogKeys(connId)
        val oddIssues = listOf("\"text\"", "[1,2]", """{"id":"1","fields":"oops"}""", """{"id":"1","fields":null}""",
            """{"id":"1","fields":{"assignee":"x","reporter":null,"creator":[1],"description":null}}""")
        val oddHistories = listOf("\"text\"", "[1]", """{"id":"1","author":"x","updateAuthor":null,"items":"oops"}""",
            """{"id":"1","items":[1,null,"x",{"field":5,"fieldId":7},{"field":"description","fieldId":"summary"}]}""")
        val oddWorklogs = listOf("\"text\"", "[]", """{"id":"1","author":[1],"updateAuthor":"x"}""")
        suspendTransaction(db) {
            oddIssues.forEachIndexed { i, text ->
                JiraRawStore.Issues.update({
                    (JiraRawStore.Issues.connectionId eq connId) and
                        (JiraRawStore.Issues.issueId eq issueIds[i])
                }) {
                    it[payload] = text
                }
            }
            oddHistories.forEachIndexed { i, text ->
                JiraRawStore.Changelogs.update({
                    (JiraRawStore.Changelogs.connectionId eq connId) and
                        (JiraRawStore.Changelogs.historyId eq historyIds[i])
                }) {
                    it[payload] = text
                }
            }
            oddWorklogs.forEachIndexed { i, text ->
                JiraRawStore.Worklogs.update({
                    (JiraRawStore.Worklogs.connectionId eq connId) and
                        (JiraRawStore.Worklogs.worklogId eq worklogIds[i])
                }) {
                    it[payload] = text
                }
            }
        }
        val before = contents(connId)

        val context = context(connId)
        JiraTrimStream(store).run(context)

        // Only the one odd issue that really holds a dropped key (a JSON-null description) is rewritten; the rest are left alone.
        assertEquals(1L, context.progressSnapshot()["issuesTrimmed"])
        assertEquals(null, context.progressSnapshot()["changelogsTrimmed"])
        assertEquals(null, context.progressSnapshot()["worklogsTrimmed"])
        val after = contents(connId)
        assertEquals(emptyList(), diff(before, after).filterNot { it.startsWith("issues ${issueIds[4]}:") })
        assertEquals(
            Json.parseToJsonElement("""{"id":"1","fields":{"assignee":"x","reporter":null,"creator":[1]}}"""),
            after.getValue("issues").getValue(issueIds[4]).first,
        )
        assertEquals(TrimPhase.DONE, trimCursor(connId).phase)
    }

    @Test
    fun `each dropped shape alone is enough to make a row a candidate`() = runBlocking {
        val connId = clone("jira-trim-each")
        val issueIds = issueKeys(connId)
        val historyIds = historyKeys(connId)
        val worklogIds = worklogKeys(connId)
        val fat = """{"accountId":"a","emailAddress":"e@x"}"""
        val issues = listOf("assignee", "reporter", "creator").map { """{"id":"1","fields":{"$it":$fat}}""" } +
            listOf("comment", "worklog", "description", "attachment").map { """{"id":"1","fields":{"$it":[]}}""" }
        val histories = listOf(
            """{"id":"1","author":$fat,"items":[]}""", """{"id":"1","updateAuthor":$fat,"items":[]}""",
            """{"id":"1","items":[{"fieldId":"description"}]}""", """{"id":"1","items":[{"field":"description"}]}""",
            """{"id":"1","items":[{"field":"RemoteIssueLink"}]}""",
            """{"id":"1","items":[{"field":"RemoteWorkItemLink","fieldId":null}]}""",
        )
        val worklogs = listOf("self", "comment").map { """{"id":"1","$it":"x"}""" } +
            listOf("author", "updateAuthor").map { """{"id":"1","$it":$fat}""" }
        suspendTransaction(db) {
            issues.forEachIndexed { i, text ->
                JiraRawStore.Issues.update({
                    (JiraRawStore.Issues.connectionId eq connId) and
                        (JiraRawStore.Issues.issueId eq issueIds[i])
                }) {
                    it[payload] = text
                }
            }
            histories.forEachIndexed { i, text ->
                JiraRawStore.Changelogs.update({
                    (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq historyIds[i])
                }) { it[payload] = text }
            }
            worklogs.forEachIndexed { i, text ->
                JiraRawStore.Worklogs.update({
                    (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq worklogIds[i])
                }) { it[payload] = text }
            }
        }

        val context = context(connId)
        JiraTrimStream(store).run(context)

        assertEquals(issues.size.toLong(), context.progressSnapshot()["issuesTrimmed"])
        assertEquals(histories.size.toLong(), context.progressSnapshot()["changelogsTrimmed"])
        assertEquals(worklogs.size.toLong(), context.progressSnapshot()["worklogsTrimmed"])
        val after = contents(connId)
        val slim = Json.parseToJsonElement("""{"accountId":"a"}""")
        fun userOf(index: Int, key: String) =
            after.getValue("issues").getValue(issueIds[index]).first.jsonObject.getValue("fields").jsonObject.getValue(key)
        assertEquals(slim, userOf(0, "assignee"))
        assertEquals(slim, userOf(2, "creator"))
        assertEquals(Json.parseToJsonElement("""{"id":"1","fields":{}}"""), after.getValue("issues").getValue(issueIds[6]).first)
        assertEquals(Json.parseToJsonElement("""{"id":"1","items":[]}"""), after.getValue("changelogs").getValue(historyIds[3]).first)
        assertEquals(slim, after.getValue("worklogs").getValue(worklogIds[3]).first.jsonObject.getValue("updateAuthor"))
    }

    @Test
    fun `PROCESS on a trimmed clone reproduces the source's status-interval digest and work items`() = runBlocking {
        val source = SyncedStubFixture.connectionId()
        val items = SyncedStubFixture.workItems()
        val dirty = makeDirty(clone("jira-trim-process"), tombstone = false, emptyOne = false)

        JiraTrimStream(store).run(context(dirty.connId))
        // A real REPROCESS flags every issue and runs PROCESS over the trimmed rows.
        assertTrue(store.markAllNeedsProcessing(dirty.connId) > 0)
        JiraProcessStream(store, items).run(SyncedStubFixture.freshContext(dirty.connId))

        assertEquals(
            emptyList(),
            store.issuesToProcess(dirty.connId, currentProcessingVersion = PROCESSING_VERSION, limit = 1),
            "PROCESS handled every issue",
        )
        assertEquals(
            SyncedStubFixture.statusIntervalDigest(items, source),
            SyncedStubFixture.statusIntervalDigest(items, dirty.connId),
            "the trimmed clone normalizes to the source's status intervals",
        )
        assertEquals(items.countWorkItems(source), items.countWorkItems(dirty.connId))
    }

    @Test
    fun `TRIM on the clean stub rewrites nothing and costs a fraction of a second`() = runBlocking {
        val connId = clone("jira-trim-clean")
        val before = contents(connId)
        val context = context(connId)
        val startedAt = System.nanoTime()

        JiraTrimStream(store).run(context)

        val millis = (System.nanoTime() - startedAt) / 1_000_000L
        println("TRIM of the clean stub clone: $millis ms")
        assertEquals(before, contents(connId))
        assertEquals(emptyMap(), context.progressSnapshot())
        assertEquals(TrimPhase.DONE, trimCursor(connId).phase)
    }
}
