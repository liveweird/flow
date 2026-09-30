package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraChangelogPage
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraStartAtPage
import ch.nokillswit.jira.JiraWorklogIdsPage
import ch.nokillswit.jira.JiraWorklogStartAtPage
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.MAX_CURSOR_RESTARTS
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.backfillFromEpochMillis
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BACKFILL_FROM = "2025-09-01"
private val PASS_CLOCK = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli()
private val IN_SCOPE = listOf("FLO")

/** A [JiraClient] whose every call fails — a subclass overrides only what its scenario needs. */
internal abstract class UnsupportedJiraClient : JiraClient {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("not scripted")
    override suspend fun resolveCloudId(): String = unsupported()
    override suspend fun myself(): JsonObject = unsupported()
    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage = unsupported()
    override suspend fun approximateCount(jql: String): Long = unsupported()
    override suspend fun issue(idOrKey: String, fields: String?): JsonObject = unsupported()
    override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject = unsupported()
    override suspend fun issueChangelogPage(issueId: String, startAt: Int): JiraChangelogPage = unsupported()
    override suspend fun issueWorklogPage(issueId: String, startAt: Int): JiraWorklogStartAtPage = unsupported()
    override suspend fun worklogUpdated(sinceEpochMillis: Long): JiraWorklogIdsPage = unsupported()
    override suspend fun worklogDeleted(sinceEpochMillis: Long): JiraWorklogIdsPage = unsupported()
    override suspend fun worklogList(ids: List<Long>): JsonArray = unsupported()
    override suspend fun fields(): JsonArray = unsupported()
    override suspend fun statusesSearch(startAt: Int): JiraStartAtPage = unsupported()
    override suspend fun statusCategories(): JsonArray = unsupported()
    override suspend fun projectsSearch(startAt: Int): JiraStartAtPage = unsupported()
    override suspend fun projectStatuses(projectKey: String): JsonArray = unsupported()
    override suspend fun issueTypes(): JsonArray = unsupported()
    override suspend fun priorities(startAt: Int): JiraStartAtPage = unsupported()
    override suspend fun resolutions(startAt: Int): JiraStartAtPage = unsupported()
    override suspend fun issueLinkTypes(): JsonObject = unsupported()
    override suspend fun usersSearch(startAt: Int): JsonArray = unsupported()
    override suspend fun boards(startAt: Int): JiraStartAtPage = unsupported()
    override suspend fun boardConfiguration(boardId: Long): JsonObject = unsupported()
    override suspend fun boardSprints(boardId: Long, startAt: Int): JiraStartAtPage = unsupported()
}

/**
 * Records every `search/jql` request (JQL text + page token) and every `GET /issue/{id}` probe, and
 * answers the sweep with [sweepIds] (one page). A probe of an id in [deletedIds] is a 404, one in
 * [probeProjects] is a 200 carrying that (project id, key), any other probe fails loudly. A search
 * carrying a page token in [expiredTokens] — or every search with [expireAlways] — throws
 * `CURSOR_EXPIRED`.
 */
private class ScriptedJiraClient(
    private val sweepIds: List<Long>,
    private val deletedIds: Set<Long> = emptySet(),
    private val probeProjects: Map<Long, Pair<Long, String>> = emptyMap(),
    private val expiredTokens: Set<String> = emptySet(),
    private val expireAlways: Boolean = false,
) : UnsupportedJiraClient() {
    val searches = mutableListOf<Pair<String, String?>>()
    val probes = mutableListOf<Long>()

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        searches += jql to nextPageToken
        if (expireAlways || nextPageToken in expiredTokens) throw JiraFetchException("CURSOR_EXPIRED", 400)
        return JiraSearchPage(JsonArray(sweepIds.map { id -> buildJsonObject { put("id", id.toString()) } }), null)
    }

    override suspend fun issue(idOrKey: String, fields: String?): JsonObject {
        val id = idOrKey.toLong()
        probes += id
        if (id in deletedIds) throw JiraFetchException("NOT_FOUND", 404)
        val (projectId, projectKey) = probeProjects[id] ?: throw AssertionError("unexpected probe of issue $id")
        return buildJsonObject {
            put("id", idOrKey)
            put("key", "$projectKey-$id")
            putJsonObject("fields") { putJsonObject("project") { put("id", projectId.toString()); put("key", projectKey) } }
        }
    }
}

/** A client that answers every `search/jql` with an empty page and fails any other call — the ISSUES stream over "nothing changed". */
private class EmptySearchJiraClient : UnsupportedJiraClient() {
    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int) =
        JiraSearchPage(JsonArray(emptyList()), null)
}

private const val SLACK_MS = 300_000L // RECONCILE_CANDIDATE_SLACK_MILLIS: 5 minutes, pinned independently

/**
 * The windowed RECONCILE sweep (`JiraJql.reconcile`, `JiraRawStore.issuesMissingFromSeen`) and the
 * local out-of-scope tombstone (`JiraRawStore.markOutOfScopeProjects`, called at the start of the ISSUES
 * stream) — `.claude/docs/ingestion.md` "RECONCILE stream". Each test owns a DISABLED connection it
 * fills itself (hand-placed rows and `PROJECT` entities, so their dates sit exactly around the window),
 * drives one stream directly under a pinned clock, and never touches the shared synced fixture.
 */
class JiraReconcileWindowTest {
    private val store = SyncedStubFixture.rawStore()

    private suspend fun newConnection(prefix: String, withProjects: Boolean = true): UInt {
        SyncedStubFixture.ensureMigrated()
        val connId = SyncedStubFixture.createConnection(
            namePrefix = prefix, enabled = false, projectKeys = IN_SCOPE, backfillFrom = BACKFILL_FROM,
        )
        if (withProjects) {
            seedProject(connId, 10, "FLO")
            seedProject(connId, 20, "OLD")
        }
        return connId
    }

    /** The REFERENCE stream's `PROJECT` entity for a project — what `JiraRawStore.resolveProjectIds` reads. */
    private suspend fun seedProject(connId: UInt, projectId: Long, key: String) {
        store.upsertEntity(connId, "PROJECT", projectId.toString(), """{"id":"$projectId","key":"$key"}""", PASS_CLOCK)
    }

    private fun context(connId: UInt) = StreamContext(
        connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true }, clock = { PASS_CLOCK },
    )

    private fun reconcile(client: JiraClient, keys: List<String> = IN_SCOPE) =
        JiraReconcileStream(client, store, keys, backfillFromEpochMillis(BACKFILL_FROM))

    private suspend fun seed(
        connId: UInt,
        issueId: Long,
        projectKey: String,
        updatedAt: String,
        projectId: Long = if (projectKey == "FLO") 10L else 20L,
    ) {
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = issueId,
                issueKey = "$projectKey-$issueId",
                projectId = projectId,
                projectKey = projectKey,
                issueUpdatedAt = Instant.parse(updatedAt).toEpochMilli(),
                payloadJson = """{"id":"$issueId","key":"$projectKey-$issueId","fields":{"project":{"key":"$projectKey"}}}""",
            ),
            now = PASS_CLOCK,
        )
    }

    private suspend fun row(connId: UInt, issueId: Long) = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll()
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .toList().single()
    }

    /** The windowed sweep query for an anchor, `N` derived independently of the production helper: whole minutes anchor → pass start. */
    private fun sweepJql(anchor: String, keys: String = "\"FLO\""): String {
        val minutes = Duration.between(Instant.parse(anchor), Instant.ofEpochMilli(PASS_CLOCK)).toMinutes()
        return "project in ($keys) AND updated >= \"-${minutes}m\" ORDER BY id ASC"
    }

    private fun search(jql: String, token: String? = null) = jql to token

    @Test
    fun `the sweep is bounded by backfillFrom and only rows at or past the window start plus five minutes are probed`() = runBlocking {
        val connId = newConnection("jira-reconcile-window")
        val backfill = backfillFromEpochMillis(BACKFILL_FROM)
        fun atMs(millis: Long) = Instant.ofEpochMilli(millis).toString()
        // Stored before backfillFrom: kept, never re-checked (its absence from the sweep proves nothing).
        seed(connId, 1, "FLO", "2025-08-15T10:00:00Z")
        // Just under window start + 5 min: not a candidate. Exactly window start + 5 min: a candidate (deleted in Jira).
        seed(connId, 2, "FLO", atMs(backfill + SLACK_MS - 1))
        seed(connId, 3, "FLO", atMs(backfill + SLACK_MS))
        // In-window rows absent from the sweep: Jira moved one to another project.
        seed(connId, 4, "FLO", "2026-02-10T10:00:00Z")
        // In-window, seen by the sweep: untouched.
        seed(connId, 5, "FLO", "2026-03-01T10:00:00Z")
        // Scope is by project ID: a row keyed "OLD" but stored under the in-scope id 10 IS a candidate,
        // one keyed "FLO" but stored under the out-of-scope id 20 is NOT.
        seed(connId, 6, "OLD", "2026-03-01T10:00:00Z", projectId = 10)
        seed(connId, 7, "FLO", "2026-03-01T10:00:00Z", projectId = 20)
        val client = ScriptedJiraClient(
            sweepIds = listOf(5),
            deletedIds = setOf(3, 6),
            probeProjects = mapOf(4L to (99L to "SEC")),
        )

        reconcile(client).run(context(connId))

        assertEquals(
            listOf(search(sweepJql("${BACKFILL_FROM}T00:00:00Z"))), client.searches,
            "one sweep page, carrying the relative window back to backfillFrom",
        )
        assertEquals(
            listOf(3L, 4L, 6L), client.probes.sorted(), "candidates: in-scope id, at or past window start + 5 min, missing from the sweep",
        )
        assertNotNull(row(connId, 3)[JiraRawStore.Issues.deletedAt], "a 404 tombstones the issue as deleted")
        assertNotNull(row(connId, 4)[JiraRawStore.Issues.movedOutAt], "a move to another project tombstones it as moved out")
        assertEquals("SEC", row(connId, 4)[JiraRawStore.Issues.projectKey])
        assertNotNull(row(connId, 6)[JiraRawStore.Issues.deletedAt])
        for (untouched in listOf(1L, 2L, 5L, 7L)) {
            val untouchedRow = row(connId, untouched)
            assertNull(untouchedRow[JiraRawStore.Issues.deletedAt], "issue $untouched must not be deleted")
            assertNull(untouchedRow[JiraRawStore.Issues.movedOutAt], "issue $untouched must not be moved out")
        }
        assertEquals(0L, store.countReconcileSeen(connId), "the scratch table is drained once the pass completes")
    }

    @Test
    fun `the window rounds minutes down so the sweep never starts before backfillFrom`() = runBlocking {
        val connId = newConnection("jira-reconcile-floor")
        val start = backfillFromEpochMillis(BACKFILL_FROM) + 90_000L // 1.5 minutes past backfillFrom
        val ctx = StreamContext(
            connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true }, clock = { start },
        )
        val client = ScriptedJiraClient(sweepIds = emptyList())

        reconcile(client).run(ctx)

        assertEquals(listOf(search("""project in ("FLO") AND updated >= "-1m" ORDER BY id ASC""")), client.searches)
    }

    @Test
    fun `a resumed pass anti-joins with its stored window and reuses its stored query text`() = runBlocking {
        val connId = newConnection("jira-reconcile-resume")
        val ctx = context(connId)
        val storedWindowStart = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        // Before the stored window: not a candidate under it (a recomputed window, from backfillFrom, WOULD make it one).
        seed(connId, 1, "FLO", "2026-02-01T10:00:00Z")
        // After it, missing from the sweep: a candidate, deleted in Jira.
        seed(connId, 2, "FLO", "2026-03-02T10:00:00Z")
        ctx.putCursor("reconcile", storedCursor("stored jql text", "tok-2", storedWindowStart, ctx.jobId.toLong()))
        val client = ScriptedJiraClient(sweepIds = emptyList(), deletedIds = setOf(2))

        reconcile(client).run(ctx)

        assertEquals(listOf(search("stored jql text", "tok-2")), client.searches)
        assertEquals(listOf(2L), client.probes, "the anti-join must use the stored window, not recompute one")
    }

    private fun storedCursor(jql: String, token: String?, windowStart: Long, jobId: Long) = buildJsonObject {
        put("passStartedAt", PASS_CLOCK)
        if (token != null) put("nextPageToken", token)
        put("jql", jql)
        put("windowStartMillis", windowStart)
        put("jobId", jobId)
    }.toString()

    @Test
    fun `a cursor that cannot be resumed starts a fresh pass - legacy, another job's, or an already finished sweep`() = runBlocking {
        val fresh = search(sweepJql("${BACKFILL_FROM}T00:00:00Z"))
        val windowStart = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        val cursors = mapOf(
            // Written before the sweep was windowed: its token belongs to the old, unbounded query.
            "legacy" to """{"passStartedAt":${PASS_CLOCK - Duration.ofHours(5).toMillis()},"nextPageToken":"tok-9"}""",
            // Another job's pass: the scratch seen-set is keyed by job id, so its pages 1..k-1 are invisible to this job.
            "other-job" to storedCursor("their jql", "tok-3", windowStart, jobId = 99),
            // A finished sweep a crash left behind (no page token): nothing to resume.
            "finished" to storedCursor("their jql", null, windowStart, jobId = 1),
        )
        for ((label, cursor) in cursors) {
            val connId = newConnection("jira-reconcile-$label")
            val ctx = context(connId)
            ctx.putCursor("reconcile", cursor)
            // An id the abandoned sweep had already collected: it must not survive into the fresh pass's anti-join.
            seed(connId, 8, "FLO", "2026-03-01T10:00:00Z")
            store.insertReconcileSeen(connId, ctx.jobId, 8)
            val client = ScriptedJiraClient(sweepIds = emptyList(), deletedIds = setOf(8))

            reconcile(client).run(ctx)

            assertEquals(listOf(fresh), client.searches, "$label: a fresh first page with the new windowed query, no stale token")
            assertEquals(listOf(8L), client.probes, "$label: the abandoned sweep's scratch ids were drained")
        }
        Unit
    }

    @Test
    fun `CURSOR_EXPIRED restarts the pass with a fresh query and window, bounded like the ISSUES stream`() = runBlocking {
        val connId = newConnection("jira-reconcile-expired")
        val ctx = context(connId)
        // Stored window starts 2026-03-01; the row below is older, so it is a candidate only under the NEW window (backfillFrom).
        seed(connId, 1, "FLO", "2026-02-01T10:00:00Z")
        ctx.putCursor(
            "reconcile",
            storedCursor("stale jql", "old-token", Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(), ctx.jobId.toLong()),
        )
        val client = ScriptedJiraClient(sweepIds = emptyList(), deletedIds = setOf(1), expiredTokens = setOf("old-token"))

        reconcile(client).run(ctx)

        assertEquals(
            listOf(search("stale jql", "old-token"), search(sweepJql("2025-09-01T00:00:00Z"))),
            client.searches,
            "the expired token is dropped and the sweep restarts from page one",
        )
        assertEquals(listOf(1L), client.probes, "the anti-join after the restart uses the NEW window")

        val stuckId = newConnection("jira-reconcile-expired-always")
        val stuck = ScriptedJiraClient(sweepIds = emptyList(), expireAlways = true)
        val failure = assertFailsWith<JiraFetchException> { reconcile(stuck).run(context(stuckId)) }
        assertEquals("CURSOR_EXPIRED", failure.code)
        assertEquals(MAX_CURSOR_RESTARTS + 1, stuck.searches.size, "the first request plus exactly MAX_CURSOR_RESTARTS restarts")
    }

    @Test
    fun `a project renamed since the last REFERENCE pass stays in scope by id - not tombstoned as moved out`() = runBlocking {
        // The PROJECT entity (id 10) still says FLO; Jira renamed the project to FLOW since.
        val connId = newConnection("jira-reconcile-rename")
        seed(connId, 1, "FLO", "2026-03-01T10:00:00Z", projectId = 10)
        // The sweep misses row 1, so it is probed: Jira answers 200 with the same project id and the new key.
        val client = ScriptedJiraClient(sweepIds = emptyList(), probeProjects = mapOf(1L to (10L to "FLOW")))

        reconcile(client).run(context(connId))

        assertEquals(listOf(1L), client.probes)
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt], "same project id: a rename is not a move out of scope")
        assertNull(row(connId, 1)[JiraRawStore.Issues.deletedAt])
    }

    @Test
    fun `a rename with the configured key gone stale is still not a move out - the project id is unchanged`() = runBlocking {
        // Steady state after Jira renamed FLO -> FLOW: the live PROJECT entity is keyed FLOW, the connection still says FLO.
        val connId = newConnection("jira-reconcile-rename-stale", withProjects = false)
        seedProject(connId, 10, "FLOW")
        seed(connId, 1, "FLO", "2026-03-01T10:00:00Z", projectId = 10)
        val client = ScriptedJiraClient(sweepIds = emptyList(), probeProjects = mapOf(1L to (10L to "FLOW")))

        reconcile(client).run(context(connId))

        assertEquals(listOf(1L), client.probes)
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt], "an unresolved scope must not turn a rename into a tombstone")
        assertNull(row(connId, 1)[JiraRawStore.Issues.deletedAt])
    }

    @Test
    fun `an unresolvable scope does not restrict the reconcile candidates`() = runBlocking {
        // No PROJECT entities at all: the scope cannot be resolved, so the anti-join falls back to master's behaviour (any project).
        val connId = newConnection("jira-reconcile-unresolved", withProjects = false)
        seed(connId, 1, "OLD", "2026-03-01T10:00:00Z")
        val client = ScriptedJiraClient(sweepIds = emptyList(), deletedIds = setOf(1))

        reconcile(client).run(context(connId))

        assertEquals(listOf(1L), client.probes)
        assertNotNull(row(connId, 1)[JiraRawStore.Issues.deletedAt])
        Unit
    }

    @Test
    fun `markOutOfScopeProjects tombstones a removed project locally, idempotently, and a re-upsert resurrects it`() = runBlocking {
        val connId = newConnection("jira-out-of-scope")
        seed(connId, 1, "FLO", "2026-01-10T10:00:00Z")
        seed(connId, 2, "OLD", "2026-01-10T10:00:00Z")
        seed(connId, 3, "OLD", "2026-01-11T10:00:00Z")
        // Already deleted in Jira: its tombstone stays as it is.
        seed(connId, 4, "OLD", "2026-01-12T10:00:00Z")
        store.markIssueDeleted(connId, 4, now = PASS_CLOCK - 1)
        // Clear the needs_processing flag every insert sets, to see the tombstone re-raise it.
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ JiraRawStore.Issues.connectionId eq connId }) {
                it[needsProcessing] = false
            }
        }

        assertEquals(2, store.markOutOfScopeProjects(connId, IN_SCOPE, now = PASS_CLOCK), "both live OLD rows, nothing else")

        for (id in listOf(2L, 3L)) {
            val tombstoned = row(connId, id)
            assertEquals(PASS_CLOCK, tombstoned[JiraRawStore.Issues.movedOutAt])
            assertTrue(tombstoned[JiraRawStore.Issues.needsProcessing], "the tombstone flags the row for PROCESS")
            assertEquals("OLD", tombstoned[JiraRawStore.Issues.projectKey], "project columns stay as stored")
            assertEquals("OLD-$id", tombstoned[JiraRawStore.Issues.issueKey])
        }
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt], "an in-scope row is untouched")
        assertFalse(row(connId, 1)[JiraRawStore.Issues.needsProcessing])
        assertNull(row(connId, 4)[JiraRawStore.Issues.movedOutAt], "an already-deleted row keeps its own tombstone only")
        assertEquals(PASS_CLOCK - 1, row(connId, 4)[JiraRawStore.Issues.deletedAt])

        assertEquals(0, store.markOutOfScopeProjects(connId, IN_SCOPE, now = PASS_CLOCK + 1), "a second call changes nothing")
        assertEquals(PASS_CLOCK, row(connId, 2)[JiraRawStore.Issues.movedOutAt], "the tombstone time is not re-stamped")

        // A re-added project's issue is upserted again (the scope catch-up re-searches it): the tombstone is cleared.
        val outcome = store.upsertIssue(
            connId,
            RawIssueInput(2, "OLD-2", 20L, "OLD", Instant.parse("2026-01-10T10:00:00Z").toEpochMilli(), """{"id":"2","key":"OLD-2"}"""),
            now = PASS_CLOCK + 2,
        )
        assertEquals(RawUpsertOutcome.RESURRECTED, outcome)
        assertNull(row(connId, 2)[JiraRawStore.Issues.movedOutAt])
    }

    @Test
    fun `markOutOfScopeProjects decides scope by project id - a rename is safe and an unresolvable key skips the step`() = runBlocking {
        val connId = newConnection("jira-out-of-scope-rename", withProjects = false)
        // Project 10 was renamed FLO -> FLOW in Jira; project 20 (OLD) is genuinely not part of the scope.
        seedProject(connId, 10, "FLOW")
        seedProject(connId, 20, "OLD")
        seed(connId, 1, "FLO", "2026-01-10T10:00:00Z", projectId = 10)
        seed(connId, 2, "OLD", "2026-01-10T10:00:00Z", projectId = 20)

        // The connection still says "FLO": it resolves to no live project, so NOTHING is tombstoned - not even project 20.
        assertEquals(0, store.markOutOfScopeProjects(connId, listOf("FLO"), now = PASS_CLOCK), "unresolved scope: fail safe")
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt])
        assertNull(row(connId, 2)[JiraRawStore.Issues.movedOutAt])

        // The admin updated the scope to "FLOW": project 10's rows (still keyed "FLO") stay, project 20's go.
        assertEquals(1, store.markOutOfScopeProjects(connId, listOf("FLOW"), now = PASS_CLOCK))
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt], "the renamed project's rows are still in scope")
        assertNotNull(row(connId, 2)[JiraRawStore.Issues.movedOutAt])
        Unit
    }

    @Test
    fun `every ISSUES run starts by tombstoning a removed project's rows without any probe`() = runBlocking {
        val connId = newConnection("jira-issues-out-of-scope")
        seed(connId, 1, "FLO", "2026-01-10T10:00:00Z")
        seed(connId, 2, "OLD", "2026-01-10T10:00:00Z")
        val ctx = context(connId)

        JiraIssuesStream(EmptySearchJiraClient(), store, IN_SCOPE, backfillFromEpochMillis(BACKFILL_FROM), BACKFILL_FROM, 10, 100).run(ctx)

        assertNotNull(row(connId, 2)[JiraRawStore.Issues.movedOutAt], "the removed project's row is moved out by the ISSUES stream itself")
        assertNull(row(connId, 1)[JiraRawStore.Issues.movedOutAt])
        assertEquals(1L, ctx.progressSnapshot()["movedOutOfScope"])
    }
}
