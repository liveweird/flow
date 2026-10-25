package ch.nokillswit

import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.REFERENCE_STEP_ORDER
import ch.nokillswit.jira.ReferenceCursor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REFRESH_MS = 24 * MILLIS_PER_HOUR
private const val T0 = 1_800_000_000_000L

/**
 * The REFERENCE pass's USER step runs only when the stored users are older than `jira.usersRefreshHours`
 * (`jira/JiraReferenceStream.kt`): a fresh pass skips `users/search`, counts `usersSkipped`, and keeps every user live (its
 * sweep leaves USER alone, also when the pass is resumed from a cursor); the refresh off (0) or users past the window fetch
 * them again. The stub client counts its `usersSearch` calls; each pass runs on a pinned clock.
 */
class JiraUsersRefreshTest {

    private class Counting(private val real: JiraClient) : JiraClient by real {
        var userCalls = 0
        override suspend fun usersSearch(startAt: Int): JsonArray {
            userCalls++
            return real.usersSearch(startAt)
        }
    }

    private suspend fun pass(connId: UInt, atMs: Long, refreshMs: Long = REFRESH_MS): Pair<Int, Map<String, Long>> {
        val client = Counting(SyncedStubFixture.buildClient(maxRetries = 0))
        val context = StreamContext(
            connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true }, clock = { atMs },
        )
        JiraReferenceStream(client, SyncedStubFixture.rawStore(), SyncedStubFixture.IN_SCOPE_PROJECT_KEYS, refreshMs).run(context)
        return client.userCalls to context.progressSnapshot()
    }

    private suspend fun users(connId: UInt): List<ResultRow> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Entities.selectAll().where {
            (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.USER.name)
        }.toList()
    }

    private fun List<ResultRow>.live() = filter { it[JiraRawStore.Entities.deletedAt] == null }

    @Test
    fun `users are fetched on the first pass, skipped while fresh and fetched again once older than the window`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-users-refresh", enabled = false)

        val (firstCalls, first) = pass(connId, T0)
        val stored = users(connId).live().size
        assertTrue(firstCalls > 0 && stored > 0, "the first pass fetches and stores the users")
        assertNull(first["usersSkipped"])

        val (freshCalls, fresh) = pass(connId, T0 + REFRESH_MS - 1)
        assertEquals(0, freshCalls, "no users/search call while the users are fresh")
        assertEquals(1L, fresh["usersSkipped"])
        assertEquals(stored, users(connId).live().size, "the sweep of a skipping pass tombstones no user")
        assertTrue(users(connId).all { it[JiraRawStore.Entities.lastSeenAt] == T0 }, "a skipped pass does not touch the users")

        val (staleCalls, stale) = pass(connId, T0 + REFRESH_MS)
        assertEquals(firstCalls, staleCalls, "at the window's end the users are fetched again")
        assertNull(stale["usersSkipped"])
        assertTrue(users(connId).live().all { it[JiraRawStore.Entities.lastSeenAt] == T0 + REFRESH_MS })
    }

    @Test
    fun `with the refresh off every pass fetches the users`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-users-refresh-off", enabled = false)
        val (firstCalls, _) = pass(connId, T0, refreshMs = 0)
        val (secondCalls, second) = pass(connId, T0 + 1, refreshMs = 0)
        assertEquals(firstCalls, secondCalls)
        assertNull(second["usersSkipped"])
    }

    @Test
    fun `a resumed pass keeps the skip it recorded and its sweep leaves the users live`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-users-refresh-resume", enabled = false)
        pass(connId, T0)
        val stored = users(connId).live().size

        // A later pass skipped the users and stopped at BOARD; its passStartedAt is past every user's last_seen_at.
        val later = T0 + MILLIS_PER_HOUR
        val skipped = ReferenceCursor(later, JiraEntityKind.BOARD, stepOrder = REFERENCE_STEP_ORDER, usersSkipped = true)
        SyncedStubFixture.cursors().put(connId, "reference", Json.encodeToString(skipped))
        val (calls, _) = pass(connId, later + 1)
        assertEquals(0, calls)
        assertEquals(stored, users(connId).live().size, "the resumed pass's sweep left USER alone")

        // The same cursor without the flag (a pass that DID fetch users) sweeps them: none was seen since its start.
        SyncedStubFixture.cursors().put(connId, "reference", Json.encodeToString(skipped.copy(usersSkipped = false)))
        pass(connId, later + 2)
        assertEquals(0, users(connId).live().size, "without the flag the sweep covers USER, as before")
    }

    @Test
    fun `an attempt that skipped and stopped at USER, resumed once the users are stale, fetches and sweeps them again`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-users-refresh-reset", enabled = false)
        pass(connId, T0)
        SyncedStubFixture.rawStore().upsertEntity(connId, JiraEntityKind.USER.name, "gone", """{"accountId":"gone"}""", now = T0)

        // The skip committed its USER cursor (startAt 0, flag set); the worker died before the next step. A day later it resumes.
        val stale = T0 + REFRESH_MS
        val skipped = ReferenceCursor(stale - 1, JiraEntityKind.USER, stepOrder = REFERENCE_STEP_ORDER, usersSkipped = true)
        SyncedStubFixture.cursors().put(connId, "reference", Json.encodeToString(skipped))
        val (calls, _) = pass(connId, stale)

        assertTrue(calls > 0, "the stale users are fetched")
        val gone = users(connId).single { it[JiraRawStore.Entities.entityId] == "gone" }
        assertTrue(
            gone[JiraRawStore.Entities.deletedAt] != null,
            "the fetch cleared the flag, so its sweep tombstones a user Jira no longer lists",
        )
    }
}
