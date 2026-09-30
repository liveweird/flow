package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.backfillFromEpochMillis
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OVERLAP = 10L
private const val EARLY = "2025-09-01"
private const val LATE = "2026-02-01"
private val T0 = Instant.parse("2026-06-01T12:00:00Z")
private val TEST_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Answers every `search/jql` through [script] (JQL text, page token) and records the requests; every other call fails. */
private class CapturingIssuesClient(
    private val script: (jql: String, token: String?) -> JiraSearchPage = { _, _ -> JiraSearchPage(JsonArray(emptyList()), null) },
) : UnsupportedJiraClient() {
    val searches = mutableListOf<Pair<String, String?>>()

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        searches += jql to nextPageToken
        return script(jql, nextPageToken)
    }
}

private fun issueJson(id: Long, projectKey: String, projectId: Long) = buildJsonObject {
    put("id", id.toString())
    put("key", "$projectKey-$id")
    putJsonObject("fields") {
        put("updated", "2026-05-20T10:00:00.000+0000")
        putJsonObject("project") { put("id", projectId.toString()); put("key", projectKey) }
    }
}

private fun pageOf(nextToken: String?, vararg issues: JsonObject) = JiraSearchPage(JsonArray(issues.toList()), nextToken)

/**
 * The scope catch-up of the ISSUES stream (`.claude/docs/ingestion.md` "Watermark, overlap and relative
 * JQL") and the RECONCILE index-gap guard that waits for it. Every test owns a DISABLED connection, drives
 * the streams directly over a scripted fake client and a mutable pinned clock, and never touches the
 * shared synced fixture.
 */
class JiraScopeCatchUpTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private var clock = T0.toEpochMilli()

    private suspend fun newConnection(prefix: String, keys: List<String> = listOf("FLO", "OPS")): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false, projectKeys = keys, backfillFrom = EARLY)
    }

    private fun context(connId: UInt) = StreamContext(
        connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = { clock },
    )

    private fun issues(client: JiraClient, keys: List<String>, backfillFrom: String = EARLY) =
        JiraIssuesStream(client, store, keys, backfillFromEpochMillis(backfillFrom), backfillFrom, OVERLAP, 100)

    private suspend fun runIssues(
        connId: UInt,
        client: CapturingIssuesClient,
        keys: List<String>,
        backfillFrom: String = EARLY,
        at: Instant = Instant.ofEpochMilli(clock),
    ) {
        clock = at.toEpochMilli()
        issues(client, keys, backfillFrom).run(context(connId))
    }

    private suspend fun issuesCursor(connId: UInt): IssuesCursor =
        TEST_CURSOR_JSON.decodeFromString(assertNotNull(cursors.get(connId, "issues")).cursor)

    /** `-Nm`'s N derived independently of the production helper: minutes from [from] to the run's clock, rounded up. */
    private fun minutes(from: Instant, to: Instant, extra: Long = 0): Long {
        val seconds = Duration.between(from, to).seconds
        return (seconds + 59) / 60 + extra
    }

    private fun sinceBackfill(backfillFrom: String, now: Instant) = minutes(Instant.parse("${backfillFrom}T00:00:00Z"), now)

    private fun clause(keys: String, minutes: Long) = "project in ($keys) AND updated >= \"-${minutes}m\""

    private fun single(keys: String, minutes: Long) = "${clause(keys, minutes)} ORDER BY updated ASC"

    private fun multi(vararg clauses: String) = clauses.joinToString(" OR ", postfix = " ORDER BY updated ASC") { "($it)" }

    private val flo = "\"FLO\""
    private val ops = "\"OPS\""
    private val floOps = "\"FLO\",\"OPS\""

    @Test
    fun `the first run reaches back to backfillFrom, later runs only to the watermark plus overlap`() = runBlocking {
        val connId = newConnection("jira-catchup-basic")
        val client = CapturingIssuesClient()

        runIssues(connId, client, listOf("FLO", "OPS"))
        val second = T0.plusSeconds(3_600)
        runIssues(connId, client, listOf("FLO", "OPS"), at = second)

        assertEquals(
            listOf(single(floOps, sinceBackfill(EARLY, T0)), single(floOps, 60 + OVERLAP)),
            client.searches.map { it.first },
            "first run: -N(backfillFrom); second run: today's watermark + overlap single clause",
        )
        val cursor = issuesCursor(connId)
        assertEquals(second.toEpochMilli(), cursor.watermarkAt)
        assertEquals(listOf("FLO", "OPS"), cursor.coveredProjectKeys)
        assertEquals(EARLY, cursor.coveredBackfillFrom)
        assertNull(cursor.nextPageToken)
    }

    @Test
    fun `an added project is caught up from backfillFrom in one query, then the scope is covered`() = runBlocking {
        val connId = newConnection("jira-catchup-added")
        val client = CapturingIssuesClient()
        runIssues(connId, client, listOf("FLO"))

        val second = T0.plusSeconds(3_600)
        runIssues(connId, client, listOf("FLO", "OPS"), at = second)
        val third = second.plusSeconds(1_800)
        runIssues(connId, client, listOf("FLO", "OPS"), at = third)

        assertEquals(
            listOf(
                single(flo, sinceBackfill(EARLY, T0)),
                multi(clause(flo, 60 + OVERLAP), clause(ops, sinceBackfill(EARLY, second))),
                single(floOps, 30 + OVERLAP),
            ),
            client.searches.map { it.first },
            "retained FLO since the watermark, added OPS since backfillFrom; once complete, one clause for both",
        )
        assertEquals(listOf("FLO", "OPS"), issuesCursor(connId).coveredProjectKeys)
    }

    @Test
    fun `a project added to an all-new scope is one added clause`() = runBlocking {
        val connId = newConnection("jira-catchup-all-new")
        val client = CapturingIssuesClient()
        runIssues(connId, client, listOf("FLO"))

        val second = T0.plusSeconds(600)
        runIssues(connId, client, listOf("OPS"), at = second)

        assertEquals(single(ops, sinceBackfill(EARLY, second)), client.searches.last().first)
        assertEquals(listOf("OPS"), issuesCursor(connId).coveredProjectKeys)
    }

    @Test
    fun `an earlier backfillFrom re-searches the retained projects from the new date`() = runBlocking {
        val connId = newConnection("jira-catchup-earlier")
        val client = CapturingIssuesClient()
        runIssues(connId, client, listOf("FLO", "OPS"), backfillFrom = LATE)

        val second = T0.plusSeconds(3_600)
        runIssues(connId, client, listOf("FLO", "OPS"), backfillFrom = EARLY, at = second)
        val third = second.plusSeconds(900)
        runIssues(connId, client, listOf("FLO", "OPS"), backfillFrom = EARLY, at = third)

        assertEquals(
            listOf(
                single(floOps, sinceBackfill(LATE, T0)),
                single(floOps, sinceBackfill(EARLY, second)),
                single(floOps, 15 + OVERLAP),
            ),
            client.searches.map { it.first },
        )
        assertEquals(EARLY, issuesCursor(connId).coveredBackfillFrom)
    }

    @Test
    fun `narrowing keeps the watermark and shrinks the covered scope on completion`() = runBlocking {
        val connId = newConnection("jira-catchup-narrow")
        val client = CapturingIssuesClient()
        runIssues(connId, client, listOf("FLO", "OPS"))

        val second = T0.plusSeconds(3_600)
        runIssues(connId, client, listOf("FLO"), at = second)
        assertEquals(single(flo, 60 + OVERLAP), client.searches.last().first, "a removed project needs no download")
        val afterRemoval = issuesCursor(connId)
        assertEquals(listOf("FLO"), afterRemoval.coveredProjectKeys)
        assertEquals(second.toEpochMilli(), afterRemoval.watermarkAt)

        val third = second.plusSeconds(1_200)
        runIssues(connId, client, listOf("FLO"), backfillFrom = LATE, at = third)
        assertEquals(single(flo, 20 + OVERLAP), client.searches.last().first, "a later backfillFrom downloads nothing either")
        assertEquals(LATE, issuesCursor(connId).coveredBackfillFrom)
    }

    @Test
    fun `a legacy cursor without a covered scope covers the current one - no re-download on deploy`() = runBlocking {
        val connId = newConnection("jira-catchup-legacy")
        val watermark = T0.minusSeconds(7_200).toEpochMilli()
        val legacy = """{"watermarkAt":$watermark,"jql":"old text","runStartedAt":$watermark}"""
        cursors.put(connId, "issues", legacy, watermarkAt = watermark, lastCompletedAt = watermark)
        val client = CapturingIssuesClient()

        runIssues(connId, client, listOf("FLO", "OPS"))

        assertEquals(listOf(single(floOps, 120 + OVERLAP)), client.searches.map { it.first })
        val cursor = issuesCursor(connId)
        assertEquals(listOf("FLO", "OPS"), cursor.coveredProjectKeys)
        assertEquals(EARLY, cursor.coveredBackfillFrom)
    }

    @Test
    fun `an interrupted catch-up resumes its stored query and token, while a changed scope starts a fresh run`() = runBlocking {
        suspend fun interruptedCatchUp(prefix: String): Triple<UInt, CapturingIssuesClient, String> {
            val connId = newConnection(prefix)
            runIssues(connId, CapturingIssuesClient(), listOf("FLO"), at = T0)
            val failing = CapturingIssuesClient { _, token ->
                if (token == null) pageOf("tok-2", issueJson(1, "OPS", 20)) else error("upstream fault")
            }
            assertFailsWith<IllegalStateException> { runIssues(connId, failing, listOf("FLO", "OPS"), at = T0.plusSeconds(3_600)) }
            val stored = issuesCursor(connId)
            assertEquals("tok-2", stored.nextPageToken)
            assertEquals(listOf("FLO"), stored.coveredProjectKeys, "an interrupted run has not yet covered the added project")
            return Triple(connId, failing, stored.jql)
        }

        val (resumeId, _, storedJql) = interruptedCatchUp("jira-catchup-resume")
        val resumed = CapturingIssuesClient()
        runIssues(resumeId, resumed, listOf("FLO", "OPS"), at = T0.plusSeconds(7_200))
        assertEquals(
            listOf<Pair<String, String?>>(storedJql to "tok-2"), resumed.searches,
            "the SAME jql and token, not a recomputed window",
        )
        assertEquals(listOf("FLO", "OPS"), issuesCursor(resumeId).coveredProjectKeys)

        val (changedId, _, _) = interruptedCatchUp("jira-catchup-changed")
        val fresh = CapturingIssuesClient()
        val now = T0.plusSeconds(7_200)
        runIssues(changedId, fresh, listOf("FLO", "OPS", "PLT"), at = now)
        assertEquals(
            listOf<Pair<String, String?>>(
                multi(clause(flo, 120 + OVERLAP), clause("\"OPS\",\"PLT\"", sinceBackfill(EARLY, now))) to null,
            ),
            fresh.searches,
            "the token is discarded; the watermark (and its covered scope) are what the interrupted run left",
        )
        assertEquals(listOf("FLO", "OPS", "PLT"), issuesCursor(changedId).coveredProjectKeys)
    }

    @Test
    fun `a removed then re-added project is resurrected by the catch-up`() = runBlocking {
        val connId = newConnection("jira-catchup-resurrect")
        store.upsertEntity(connId, "PROJECT", "10", """{"id":"10","key":"FLO"}""", clock)
        store.upsertEntity(connId, "PROJECT", "20", """{"id":"20","key":"OPS"}""", clock)
        val opsIssue = issueJson(5, "OPS", 20)
        runIssues(connId, CapturingIssuesClient { _, _ -> pageOf(null, opsIssue) }, listOf("FLO", "OPS"))
        store.markWorklogsSynced(connId, 5, clock)

        // The admin removes OPS: the next run tombstones its rows locally and the covered scope shrinks.
        runIssues(connId, CapturingIssuesClient(), listOf("FLO"), at = T0.plusSeconds(600))
        assertNotNull(row(connId, 5)[JiraRawStore.Issues.movedOutAt], "moved out by the removal")
        assertNotNull(row(connId, 5)[JiraRawStore.Issues.worklogsSyncedAt], "a tombstone alone keeps the worklog stamp")
        assertEquals(listOf("FLO"), issuesCursor(connId).coveredProjectKeys)

        // OPS comes back: it is an ADDED project, searched from backfillFrom, and its rows are resurrected.
        val client = CapturingIssuesClient { _, _ -> pageOf(null, opsIssue) }
        val back = T0.plusSeconds(1_200)
        runIssues(connId, client, listOf("FLO", "OPS"), at = back)

        assertEquals(
            multi(clause(flo, 10 + OVERLAP), clause(ops, sinceBackfill(EARLY, back))),
            client.searches.single().first,
        )
        assertNull(row(connId, 5)[JiraRawStore.Issues.movedOutAt], "the re-added project's tombstoned row is resurrected")
        assertTrue(row(connId, 5)[JiraRawStore.Issues.needsProcessing])
        assertNull(
            row(connId, 5)[JiraRawStore.Issues.worklogsSyncedAt],
            "a resurrected issue is stale for the per-issue worklog backfill (the incremental feed skipped it while tombstoned)",
        )
        assertEquals(listOf(5L), store.staleWorklogIssueIds(connId, 10))
    }

    @Test
    fun `a removal run that fails before its last page still shrinks the covered scope, so a re-added project is fully caught up`() =
        runBlocking {
            val connId = newConnection("jira-catchup-shrink-early")
            store.upsertEntity(connId, "PROJECT", "10", """{"id":"10","key":"FLO"}""", clock)
            store.upsertEntity(connId, "PROJECT", "20", """{"id":"20","key":"OPS"}""", clock)
            val opsIssues = arrayOf(issueJson(5, "OPS", 20), issueJson(6, "OPS", 20))
            runIssues(connId, CapturingIssuesClient { _, _ -> pageOf(null, *opsIssues) }, listOf("FLO", "OPS"), at = T0)
            val completedAt = assertNotNull(cursors.get(connId, "issues")).lastCompletedAt

            // The admin removes OPS; the SYNC tombstones its rows, then dies on its first search.
            val failing = CapturingIssuesClient { _, _ -> error("upstream fault") }
            assertFailsWith<IllegalStateException> { runIssues(connId, failing, listOf("FLO"), at = T0.plusSeconds(600)) }
            assertNotNull(row(connId, 5)[JiraRawStore.Issues.movedOutAt], "the tombstones committed at the start of the run")
            val afterFailure = issuesCursor(connId)
            assertEquals(listOf("FLO"), afterFailure.coveredProjectKeys, "covered shrank with the tombstones, not on a last page")
            assertEquals(T0.toEpochMilli(), afterFailure.watermarkAt, "the watermark did not move")
            assertEquals(completedAt, assertNotNull(cursors.get(connId, "issues")).lastCompletedAt)

            // OPS comes back: it must be ADDED (searched from backfillFrom), or rows not updated recently stay moved out forever.
            val back = T0.plusSeconds(1_200)
            val client = CapturingIssuesClient { _, _ -> pageOf(null, *opsIssues) }
            runIssues(connId, client, listOf("FLO", "OPS"), at = back)

            assertEquals(
                multi(clause(flo, 20 + OVERLAP), clause(ops, sinceBackfill(EARLY, back))),
                client.searches.single().first,
            )
            for (id in listOf(5L, 6L)) assertNull(row(connId, id)[JiraRawStore.Issues.movedOutAt], "issue $id is resurrected")
        }

    @Test
    fun `a CURSOR_EXPIRED restart during a catch-up keeps the added clause`() = runBlocking {
        val connId = newConnection("jira-catchup-expired")
        runIssues(connId, CapturingIssuesClient(), listOf("FLO"), at = T0)
        var expiredOnce = false
        val client = CapturingIssuesClient { _, token ->
            when {
                token == null && !expiredOnce -> pageOf("tok-2")
                token == "tok-2" -> { expiredOnce = true; throw JiraFetchException("CURSOR_EXPIRED", 400) }
                else -> pageOf(null)
            }
        }
        val second = T0.plusSeconds(3_600)

        runIssues(connId, client, listOf("FLO", "OPS"), at = second)

        val expected = multi(clause(flo, 60 + OVERLAP), clause(ops, sinceBackfill(EARLY, second)))
        assertEquals(
            listOf<Pair<String, String?>>(expected to null, expected to "tok-2", expected to null), client.searches,
            "the restart recomputes the catch-up query from the unchanged watermark and covered scope",
        )
        assertEquals(listOf("FLO", "OPS"), issuesCursor(connId).coveredProjectKeys)
    }

    @Test
    fun `one edit removing a project, adding one and moving backfillFrom earlier searches both remaining from the new date`() =
        runBlocking {
            val connId = newConnection("jira-catchup-combined")
            val client = CapturingIssuesClient()
            runIssues(connId, client, listOf("FLO", "OPS"), backfillFrom = LATE, at = T0)

            val second = T0.plusSeconds(3_600)
            runIssues(connId, client, listOf("OPS", "PLT"), backfillFrom = EARLY, at = second)

            val plt = "\"PLT\""
            assertEquals(
                multi(clause(ops, sinceBackfill(EARLY, second)), clause(plt, sinceBackfill(EARLY, second))),
                client.searches.last().first,
                "retained OPS re-searched from the earlier date, added PLT from backfillFrom, removed FLO absent",
            )
            val cursor = issuesCursor(connId)
            assertEquals(listOf("OPS", "PLT"), cursor.coveredProjectKeys)
            assertEquals(EARLY, cursor.coveredBackfillFrom)
        }

    private suspend fun row(connId: UInt, issueId: Long) = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll()
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .toList().single()
    }

    // --- RECONCILE index-gap guard -------------------------------------------------------------

    /** Sweeps [sweepIds] and answers every full `GET /issue/{id}` with a complete FLO issue, counting them. */
    private class IndexGapClient(private val sweepIds: List<Long>) : UnsupportedJiraClient() {
        val fullFetches = mutableListOf<Long>()

        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int) =
            JiraSearchPage(JsonArray(sweepIds.map { id -> buildJsonObject { put("id", id.toString()) } }), null)

        override suspend fun issue(idOrKey: String, fields: String?): JsonObject {
            fullFetches += idOrKey.toLong()
            return issueJson(idOrKey.toLong(), "FLO", 10)
        }
    }

    private suspend fun reconcileConnection(prefix: String, withProjects: Boolean = true): UInt {
        val connId = newConnection(prefix, keys = listOf("FLO"))
        if (withProjects) store.upsertEntity(connId, "PROJECT", "10", """{"id":"10","key":"FLO"}""", clock)
        return connId
    }

    private fun completedCursor(keys: List<String>?, from: String?, watermark: Long? = T0.toEpochMilli() - 1_000) =
        TEST_CURSOR_JSON.encodeToString(
            IssuesCursor(
                watermarkAt = watermark, jql = "x", runStartedAt = T0.toEpochMilli() - 1_000,
                coveredProjectKeys = keys, coveredBackfillFrom = from,
            ),
        )

    private suspend fun reconcileOnce(connId: UInt, client: IndexGapClient, issuesCursor: String?): StreamContext {
        if (issuesCursor != null) cursors.put(connId, "issues", issuesCursor, watermarkAt = T0.toEpochMilli() - 1_000)
        clock = T0.toEpochMilli()
        val context = context(connId)
        JiraReconcileStream(client, store, listOf("FLO"), backfillFromEpochMillis(EARLY)).run(context)
        return context
    }

    @Test
    fun `the index-gap phase waits until an ISSUES run has covered the scope, then runs`() = runBlocking {
        val waiting = mapOf(
            "no cursor" to null,
            "first run interrupted (no watermark)" to completedCursor(null, null, watermark = null),
            "a current project not covered" to completedCursor(listOf("OPS"), EARLY),
            "the covered history starts later than backfillFrom" to completedCursor(listOf("FLO"), LATE),
        )
        for ((label, cursor) in waiting) {
            val connId = reconcileConnection("jira-gap-wait")
            val client = IndexGapClient(sweepIds = listOf(77))

            val context = reconcileOnce(connId, client, cursor)

            assertTrue(client.fullFetches.isEmpty(), "$label: no full fetch of the unknown id")
            assertEquals(0L, store.countIssues(connId), "$label: nothing stored")
            assertEquals(1L, context.progressSnapshot()["indexGapSkipped"], "$label: the skip is counted")
        }

        val covering = mapOf(
            "covered scope" to completedCursor(listOf("FLO", "OPS"), EARLY),
            "covered history reaches further back" to completedCursor(listOf("FLO"), "2025-01-01"),
            "a legacy cursor covers what is configured" to """{"watermarkAt":${T0.toEpochMilli() - 1_000},"jql":"x","runStartedAt":1}""",
        )
        for ((label, cursor) in covering) {
            val connId = reconcileConnection("jira-gap-run")
            val client = IndexGapClient(sweepIds = listOf(77))

            val context = reconcileOnce(connId, client, cursor)

            assertEquals(listOf(77L), client.fullFetches, "$label: the gap is fetched")
            assertEquals(1L, store.countIssues(connId), "$label: and stored")
            assertFalse(context.progressSnapshot().containsKey("indexGapSkipped"), "$label: nothing skipped")
        }
        Unit
    }

    @Test
    fun `no PROJECT entities yet logs the scope as unresolved at INFO, a real unresolved key still warns`() = runBlocking {
        val capture = LogCapture("ch.nokillswit.jira.JiraReconcileStream")
        try {
            val noProjects = reconcileConnection("jira-gap-noproj", withProjects = false)
            reconcileOnce(noProjects, IndexGapClient(emptyList()), null)
            val noProjectEvents = capture.events.filter { "no PROJECT reference entities" in it.formattedMessage }
            assertEquals(1, noProjectEvents.size)
            assertEquals(ch.qos.logback.classic.Level.INFO, noProjectEvents.single().level)
            assertTrue(capture.events.none { it.level == ch.qos.logback.classic.Level.WARN }, "no misleading rename/mistype WARN")

            // A PROJECT entity exists, but the configured key (FLO) is not among them: the genuine WARN.
            val renamed = reconcileConnection("jira-gap-renamed", withProjects = false)
            store.upsertEntity(renamed, "PROJECT", "10", """{"id":"10","key":"FLOW"}""", clock)
            reconcileOnce(renamed, IndexGapClient(emptyList()), null)
            assertEquals(1, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN })
        } finally {
            capture.detach()
        }
    }
}
