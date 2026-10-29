package ch.nokillswit

import ch.nokillswit.DirtyJira.SUBTASKS
import ch.nokillswit.DirtyJira.adf
import ch.nokillswit.DirtyJira.fatLink
import ch.nokillswit.DirtyJira.obj
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.CURSOR_JSON
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraTrimCandidates
import ch.nokillswit.jira.JiraTrimStream
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.TRIM_RULES_VERSION
import ch.nokillswit.jira.TrimCursor
import ch.nokillswit.jira.TrimPhase
import ch.nokillswit.jira.TrimTable
import ch.nokillswit.jira.slimIssueLinks
import ch.nokillswit.jira.trimIssue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRIM rules v2 (`jira/JiraIssueLinks.kt`, `fields.subtasks` and `fields.environment` dropped) on an install whose `trim` cursor is
 * already `DONE` at v1: the cursor's version, the upgrade walk, the SQL candidate filter against the Kotlin trim. Over clones of the
 * shared synced fixture, with the keys Jira sends put back into issue rows by a raw `UPDATE` (the stub itself has no links).
 */
class JiraTrimV2Test {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }

    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val db get() = sharedDatabaseForTests()

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

    private suspend fun payloads(connId: UInt): Map<Long, Pair<JsonElement, String>> = suspendTransaction(db) {
        JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList().associate {
            it[JiraRawStore.Issues.issueId] to (Json.parseToJsonElement(it[JiraRawStore.Issues.payload]) to it[JiraRawStore.Issues.sha256])
        }
    }

    /** Every column but payload and sha256, by issue id. */
    private suspend fun otherColumns(connId: UInt): Map<Long, Map<String, Any?>> = suspendTransaction(db) {
        JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList().associate { row ->
            row[JiraRawStore.Issues.issueId] to
                JiraRawStore.Issues.columns.filter { it.name != "payload" && it.name != "sha256" }.associate { it.name to row[it] }
        }
    }

    /** [ids] put back into the shape Jira sends: fat links (both directions), subtasks and an environment. Returns the fat text by id. */
    private suspend fun makeFat(connId: UInt, ids: List<Long>): Map<Long, String> {
        val before = payloads(connId)
        val fat = ids.associateWith { id ->
            val issue = before.getValue(id).first.jsonObject
            val fields = issue.getValue("fields").jsonObject
            val extra = mapOf(
                "issuelinks" to Json.parseToJsonElement(
                    "[${fatLink("$id", "outwardIssue", "9$id", "ZZ-$id")},${fatLink("1$id", "inwardIssue", "8$id", "YY-$id")}]",
                ),
                "subtasks" to Json.parseToJsonElement(SUBTASKS),
                "environment" to obj(adf("prod")),
            )
            JsonObject(issue + ("fields" to JsonObject(fields + extra))).toString()
        }
        suspendTransaction(db) {
            for ((id, text) in fat) {
                JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq id) }) {
                    it[payload] = canonicalJson(text)
                    it[sha256] = sha256Hex(canonicalJson(text))
                }
            }
        }
        return fat
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, db, cursors, jobHeartbeat = { _, _ -> true })

    private suspend fun trimCursor(connId: UInt): TrimCursor =
        CURSOR_JSON.decodeFromString(assertNotNull(cursors.get(connId, "trim")).cursor)

    @Test
    fun `a trim cursor DONE at v1 walks again, rewrites links, subtasks and environment, ends DONE at v2, then touches nothing`() =
        runBlocking {
            val reference = clone("jira-trim-v2-reference")
            val upgraded = clone("jira-trim-v2-upgrade")
            val ids = issueKeys(upgraded).take(2 * TrimTable.ISSUES.batchSize + 20)
            val fat = makeFat(upgraded, ids)
            // The cursor exactly as 1.17.1 wrote it: no `version` key.
            val v1Done = """{"phase":"DONE","afterKey":-1,"completedAt":1700000000000}"""
            cursors.put(upgraded, "trim", v1Done, lastCompletedAt = 1_700_000_000_000L)
            assertEquals(1, trimCursor(upgraded).version, "a cursor without a version is v1")
            val columnsBefore = otherColumns(upgraded)

            val first = context(upgraded)
            JiraTrimStream(store).run(first)

            assertEquals(ids.size.toLong(), first.progressSnapshot()["issuesTrimmed"], "exactly the fat rows")
            assertNull(first.progressSnapshot()["changelogsTrimmed"])
            assertNull(first.progressSnapshot()["worklogsTrimmed"])
            assertNull(first.progressSnapshot()["trimHashDrift"], "the stored hashes were of the canonical text")
            val cursor = trimCursor(upgraded)
            assertEquals(TrimPhase.DONE, cursor.phase)
            assertEquals(TRIM_RULES_VERSION, cursor.version)
            assertTrue(assertNotNull(cursor.completedAt) > 1_700_000_000_000L, "completed again, now")
            assertEquals(columnsBefore, otherColumns(upgraded), "nothing but payload and sha256 moved (no changed_at, no PROCESS flag)")

            val rewritten = payloads(upgraded)
            val clean = payloads(reference)
            for ((id, row) in rewritten) {
                val (payload, hash) = row
                assertEquals(sha256Hex(canonicalJson(payload.toString())), hash, "issue $id: the hash is of the new canonical text")
                if (id !in fat) {
                    assertEquals(clean.getValue(id), row, "issue $id: a row the rules do not change is not touched")
                    continue
                }
                val fields = payload.jsonObject.getValue("fields").jsonObject
                assertTrue("subtasks" !in fields && "environment" !in fields, "issue $id")
                val links = fields.getValue("issuelinks")
                assertEquals(slimIssueLinks(obj(fat.getValue(id)).getValue("fields").jsonObject.getValue("issuelinks")), links)
                assertTrue(!links.toString().contains("self") && !links.toString().contains("fields"), "issue $id: $links")
                val cleanFields = clean.getValue(id).first.jsonObject.getValue("fields").jsonObject
                assertEquals(cleanFields, JsonObject(fields - "issuelinks"), "issue $id: everything else is as the clean clone")
            }

            // The next fetch of the same Jira document (fat) is UNCHANGED: TRIM stored the hash the write path computes.
            val now = System.currentTimeMillis()
            for (id in ids.take(8)) {
                val row = suspendTransaction(db) {
                    JiraRawStore.Issues.selectAll()
                        .where { (JiraRawStore.Issues.connectionId eq upgraded) and (JiraRawStore.Issues.issueId eq id) }.toList().single()
                }
                val input = RawIssueInput(
                    id, row[JiraRawStore.Issues.issueKey], row[JiraRawStore.Issues.projectId], row[JiraRawStore.Issues.projectKey],
                    row[JiraRawStore.Issues.issueUpdatedAt], Json.parseToJsonElement(fat.getValue(id)),
                )
                assertEquals(RawUpsertOutcome.UNCHANGED, store.upsertIssue(upgraded, input, now), "issue $id")
            }

            // A second run reads one cursor row and writes nothing, not even the cursor.
            val cursorRow = assertNotNull(cursors.get(upgraded, "trim"))
            val second = context(upgraded)
            JiraTrimStream(store).run(second)
            assertEquals(emptyMap(), second.progressSnapshot())
            assertEquals(cursorRow, cursors.get(upgraded, "trim"))
            assertEquals(rewritten, payloads(upgraded))
        }

    @Test
    fun `a trim cursor left half-walked by v1 restarts at the first issue, and a fresh walk writes its version`() = runBlocking {
        val halfWalked = clone("jira-trim-v2-half")
        val ids = issueKeys(halfWalked).take(30)
        makeFat(halfWalked, ids)
        // v1 had finished ISSUES (these fat rows are below its cursor) and was in the WORKLOGS phase when it was interrupted.
        cursors.put(halfWalked, "trim", """{"phase":"WORKLOGS","afterKey":5,"completedAt":null}""")

        val context = context(halfWalked)
        JiraTrimStream(store).run(context)

        assertEquals(ids.size.toLong(), context.progressSnapshot()["issuesTrimmed"])
        assertEquals(TrimCursor(TrimPhase.DONE, -1, trimCursor(halfWalked).completedAt, TRIM_RULES_VERSION), trimCursor(halfWalked))

        val fresh = clone("jira-trim-v2-fresh")
        JiraTrimStream(store).run(context(fresh))
        assertEquals(TRIM_RULES_VERSION, trimCursor(fresh).version, "the stored text carries the version, it is not a decode default")
        assertTrue(Regex(""""version"\s*:\s*$TRIM_RULES_VERSION""").containsMatchIn(assertNotNull(cursors.get(fresh, "trim")).cursor))
    }

    @Test
    fun `the SQL candidate filter selects exactly the issues the Kotlin trim changes, over a table of payloads`() = runBlocking {
        val connId = clone("jira-trim-v2-sql")
        val slimType = """{"name":"Blocks","inward":"in","outward":"out"}"""
        val slimLink = """{"id":"1","type":$slimType,"outwardIssue":{"id":"2","key":"A-2"}}"""
        val table = listOf(
            "no fields" to """{"id":"1"}""",
            "fields not an object" to """{"id":"1","fields":"oops"}""",
            "clean slim link" to """{"id":"1","fields":{"issuelinks":[$slimLink]}}""",
            "no links" to """{"id":"1","fields":{"issuelinks":[]}}""",
            "both ends slim" to
                """{"id":"1","fields":{"issuelinks":[{"type":$slimType,"inwardIssue":{"key":"A-1"},"outwardIssue":{"id":"3"}}]}}""",
            "link with only id" to """{"id":"1","fields":{"issuelinks":[{"id":"1"}]}}""",
            "links not an array" to """{"id":"1","fields":{"issuelinks":{"self":"u"}}}""",
            "links a string" to """{"id":"1","fields":{"issuelinks":"oops"}}""",
            "links null" to """{"id":"1","fields":{"issuelinks":null}}""",
            "link elements not objects" to """{"id":"1","fields":{"issuelinks":[1,null,"x",[{"self":"u"}]]}}""",
            "type a string, issues null or arrays" to
                """{"id":"1","fields":{"issuelinks":[{"id":"1","type":"Blocks","inwardIssue":null,"outwardIssue":[{"self":"u"}]}]}}""",
            "link with self" to """{"id":"1","fields":{"issuelinks":[{"id":"1","self":"u"}]}}""",
            "link with an unknown key" to """{"id":"1","fields":{"issuelinks":[{"id":"1","extra":1}]}}""",
            "type with id" to """{"id":"1","fields":{"issuelinks":[{"id":"1","type":{"id":"7","name":"Blocks"}}]}}""",
            "type with self" to """{"id":"1","fields":{"issuelinks":[{"type":{"self":"u"}}]}}""",
            "inwardIssue with fields" to """{"id":"1","fields":{"issuelinks":[{"inwardIssue":{"id":"1","key":"A-1","fields":{}}}]}}""",
            "outwardIssue with self" to """{"id":"1","fields":{"issuelinks":[{"outwardIssue":{"key":"A-1","self":"u"}}]}}""",
            "fat link after slim ones" to """{"id":"1","fields":{"issuelinks":[$slimLink,$slimLink,{"id":"9","self":"u"}]}}""",
            "fat link in a canonical test payload" to """{"id":"1","fields":{"issuelinks":[${fatLink("1", "inwardIssue", "2", "A-2")}]}}""",
            "empty subtasks" to """{"id":"1","fields":{"subtasks":[]}}""",
            "null subtasks" to """{"id":"1","fields":{"subtasks":null}}""",
            "environment null" to """{"id":"1","fields":{"environment":null}}""",
            "environment text" to """{"id":"1","fields":{"environment":"prod"}}""",
            "a custom field named like a link" to """{"id":"1","fields":{"customfield_1":{"issuelinks":[{"self":"u"}]},"summary":"x"}}""",
            "a dropped key only at the top level" to
                """{"id":"1","subtasks":[],"environment":"x","issuelinks":[{"self":"u"}],"fields":{}}""",
            "a fat user" to """{"id":"1","fields":{"assignee":{"accountId":"a","emailAddress":"e"}}}""",
            "a slim user and slim links" to """{"id":"1","fields":{"assignee":{"accountId":"a"},"issuelinks":[$slimLink]}}""",
        )
        val ids = issueKeys(connId).take(table.size)
        suspendTransaction(db) {
            table.forEachIndexed { i, (_, text) ->
                JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq ids[i]) }) {
                    it[payload] = text
                }
            }
        }

        val candidates = suspendTransaction(db) {
            JiraRawStore.Issues.select(JiraRawStore.Issues.issueId)
                .where {
                    (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId inList ids) and
                        JiraTrimCandidates.forTable(TrimTable.ISSUES, JiraRawStore.Issues.payload)
                }.toList().map { it[JiraRawStore.Issues.issueId] }.toSet()
        }
        val changed = table.indices.filter { i ->
            val parsed = Json.parseToJsonElement(table[i].second)
            trimIssue(parsed) != parsed
        }.map { ids[it] }.toSet()

        assertTrue(changed.size in 10..table.size - 6, "the table mixes changed and unchanged payloads: ${changed.size}")
        assertEquals(
            table.indices.filter { (ids[it] in candidates) != (ids[it] in changed) }.map { table[it].first },
            emptyList(),
            "candidate filter and Kotlin trim disagree on these payloads",
        )
    }
}
