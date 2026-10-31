package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JIRA_SOURCE
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.backfillFromEpochMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val FROM = "2025-09-01"
private const val EARLIER = "2025-06-01"
private const val LATER = "2026-02-01"
private val NOW = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()

/** Answers each `search/jql` call through [script] (the call's number from 0, the page token) and fails every other call. */
private class ScriptedIssuesClient(private val script: (call: Int, token: String?) -> JiraSearchPage) : UnsupportedJiraClient() {
    private var calls = 0

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage =
        script(calls++, nextPageToken)
}

private fun emptyPage(next: String? = null) = JiraSearchPage(JsonArray(emptyList()), next)

private fun issuePage(id: Long, next: String?) = JiraSearchPage(
    JsonArray(
        listOf(
            buildJsonObject {
                put("id", id.toString())
                put("key", "FLO-$id")
                putJsonObject("fields") {
                    put("updated", "2026-05-20T10:00:00.000+0000")
                    putJsonObject("project") { put("id", "10000"); put("key", "FLO") }
                }
            },
        ),
    ),
    next,
)

/**
 * The `backfill` progress marker of a SYNC (`.claude/docs/ingestion.md` "Progress counters"): the ISSUES run sets it to 1 when it is a
 * first download or widens the covered scope (a project added, `backfillFrom` moved earlier) - the telemetry panel's "last backfill"
 * is the latest job carrying it - and leaves it out of an incremental run, a narrowing, and a resumed incremental run. Every test owns a
 * DISABLED connection and drives the stream alone over a scripted client (the `JiraScopeCatchUpTest` pattern).
 */
class JiraBackfillMarkerTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()

    private suspend fun newConnection(prefix: String): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(
            namePrefix = prefix, enabled = false, projectKeys = listOf("FLO", "OPS"), backfillFrom = FROM,
        )
    }

    /** Runs the ISSUES stream once and returns its job's `backfill` counter (null = absent). */
    private suspend fun run(
        connId: UInt,
        keys: List<String> = listOf("FLO"),
        backfillFrom: String = FROM,
        script: (Int, String?) -> JiraSearchPage = { _, _ -> emptyPage() },
    ): Long? {
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = { NOW })
        val client = ScriptedIssuesClient(script)
        JiraIssuesStream(client, store, keys, backfillFromEpochMillis(backfillFrom), backfillFrom, 10L, 100).run(context)
        return context.progressSnapshot()["backfill"]
    }

    @Test
    fun `the first run is a backfill, an incremental run after it is not`() = runBlocking {
        val connId = newConnection("backfill-first")
        assertEquals(1L, run(connId))
        assertNull(run(connId), "the scope is covered now: an incremental run")
        assertNull(run(connId))
    }

    @Test
    fun `an added project widens the scope and is a backfill, narrowing is not`() = runBlocking {
        val connId = newConnection("backfill-widen")
        run(connId, listOf("FLO", "OPS"))
        assertNull(run(connId, listOf("FLO", "OPS")))
        assertNull(run(connId, listOf("FLO")), "dropping a project needs no search")
        assertEquals(1L, run(connId, listOf("FLO", "OPS")), "the dropped project comes back: its history is downloaded again")
        assertNull(run(connId, listOf("FLO", "OPS")))
    }

    @Test
    fun `an earlier backfillFrom is a backfill, a later one is not`() = runBlocking {
        val connId = newConnection("backfill-date")
        run(connId, backfillFrom = FROM)
        assertNull(run(connId, backfillFrom = LATER), "a later date narrows the window")
        assertEquals(1L, run(connId, backfillFrom = EARLIER), "reaching back past what is covered downloads more history")
        assertNull(run(connId, backfillFrom = EARLIER))
    }

    @Test
    fun `an interrupted first run is still a backfill when it resumes, an interrupted incremental run is not`() = runBlocking {
        val connId = newConnection("backfill-resume")
        val interrupted = { call: Int, _: String? ->
            if (call == 0) issuePage(1, next = "t1") else throw FetchException(JIRA_SOURCE, "UPSTREAM_UNAVAILABLE", 503)
        }
        assertFailsWith<FetchException> { run(connId, script = interrupted) }
        assertEquals(1L, run(connId, script = { _, token -> issuePage(2, next = null).also { assertEquals("t1", token) } }), "resumed")
        assertNull(run(connId))

        // Now an incremental run that stops after its first page: its resumption is not a backfill.
        assertFailsWith<FetchException> { run(connId, script = interrupted) }
        assertNull(run(connId, script = { _, _ -> issuePage(3, next = null) }))
    }
}
