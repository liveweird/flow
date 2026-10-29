package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.JiraStoredCeilings
import ch.nokillswit.jira.StoredIssueSize
import ch.nokillswit.jira.claimWithin
import ch.nokillswit.jira.purgeAll
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.purgeAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIB = 1024L * 1024
private const val LOGGER = "ch.nokillswit.jira.JiraProcessStream"

/**
 * PROCESS claims a page by STORED BYTES (`jira.processPageBytes`, `.claude/docs/ingestion.md` "Normalized layer"): up to 50 stale
 * issues are claimed in order while the sum of their payload + histories + live worklogs stays within the budget; an issue that alone
 * exceeds it is skipped (`issuesOversized`, one WARN, left `needs_processing`, no `norm` row); and every reference kind is read in chunks
 * of at most that many stored bytes (`JiraEntityReads`), so no kind size refuses, skips or stalls anything. Each test works on
 * a clone of the shared synced fixture's RAW rows (`SyncedStubFixture.cloneRawData`) and pads rows by SQL, as a hostile host could have
 * stored them; the shared connection is never touched.
 */
class JiraProcessBudgetTest {
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()

    private fun storeWith(processPageBytes: Long) =
        JiraRawStore(sharedDatabaseForTests(), JiraStoredCeilings(processPageBytes = processPageBytes))

    private suspend fun clone(): UInt {
        val clone = SyncedStubFixture.createConnection(namePrefix = "jira-process-budget", enabled = false).also { connections += it }
        SyncedStubFixture.cloneRawData(SyncedStubFixture.connectionId(), clone)
        return clone
    }

    @AfterTest
    fun dropConnections() = runBlocking {
        connections.forEach { connId ->
            SyncedStubFixture.workItems().purgeAll(connId)
            SyncedStubFixture.rawStore().purgeAll(connId)
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private fun execute(sql: String) {
        PostgresTestSupport.connect().use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    /** Every stale issue's stored size, by the very SQL the inspector uses (an independent read of what the claim sums). */
    private suspend fun sizes(store: JiraRawStore, connId: UInt): List<StoredIssueSize> =
        store.issuesToProcess(connId, PROCESSING_VERSION, limit = 100_000)
            .map { StoredIssueSize(it, store.storedBytesForIssue(connId, it)) }

    private suspend fun claimAll(store: JiraRawStore, connId: UInt, limit: Int = 50): List<List<Long>> {
        val pages = mutableListOf<List<Long>>()
        var after: Long? = null
        while (true) {
            val claim = store.issuesToProcessWithin(connId, PROCESSING_VERSION, limit, after)
            after = claim.lastExaminedId ?: break
            if (claim.ids.isNotEmpty()) pages += claim.ids
        }
        return pages
    }

    @Test
    fun `the claim cuts pages by stored bytes - each within the budget, the same issues in the same order, the 50 cap still applies`() =
        runBlocking {
            val connId = clone()
            val unlimited = storeWith(Long.MAX_VALUE / 4)
            unlimited.markAllNeedsProcessing(connId)
            val sizes = sizes(unlimited, connId)
            assertTrue(sizes.size > 60, "the stub has more than a page of issues: ${sizes.size}")
            val biggest = sizes.maxOf { it.totalBytes }
            val budget = biggest * 3

            val pages = claimAll(storeWith(budget), connId)

            assertEquals(sizes.map { it.issueId }, pages.flatten(), "every stale issue is claimed once, in id order")
            val byId = sizes.associate { it.issueId to it.totalBytes }
            pages.forEach { page ->
                assertTrue(page.size <= 50)
                assertTrue(page.sumOf { byId.getValue(it) } <= budget, "a page stays within the budget")
            }
            // Greedy and maximal: the first issue of the NEXT page would not have fitted the page before it.
            pages.zipWithNext().forEach { (page, next) ->
                val withNext = page.sumOf { byId.getValue(it) } + byId.getValue(next.first())
                if (page.size < 50) assertTrue(withNext > budget, "page cut early: $page")
            }
            assertTrue(pages.size > sizes.size / 50, "the budget made more pages than the count cap alone: ${pages.size}")
            // The same claim, computed in memory from the sizes (the pure rule), agrees page by page.
            var rest = sizes
            pages.forEach { page ->
                val expected = claimWithin(rest.take(50), budget)
                assertEquals(expected.ids, page)
                rest = rest.drop(expected.ids.size)
            }
        }

    @Test
    fun `a page always holds at least one issue that fits, and an unlimited budget is the old 50-issue page`() = runBlocking {
        val connId = clone()
        val unlimited = storeWith(Long.MAX_VALUE / 4)
        unlimited.markAllNeedsProcessing(connId)
        val sizes = sizes(unlimited, connId)
        val biggest = sizes.maxOf { it.totalBytes }

        // A budget of exactly the biggest issue: no issue is oversized, so a page is never empty and one issue per page at worst.
        val pages = claimAll(storeWith(biggest), connId)
        assertEquals(sizes.map { it.issueId }, pages.flatten())
        assertTrue(pages.all { it.isNotEmpty() })
        assertTrue(storeWith(biggest).issuesToProcessWithin(connId, PROCESSING_VERSION, 50).oversized.isEmpty())

        val old = claimAll(unlimited, connId)
        assertEquals(unlimited.issuesToProcess(connId, PROCESSING_VERSION, 50), old.first(), "no budget pressure: the old page")
        assertEquals(50, old.first().size)
    }

    @Test
    fun `an issue over the budget alone is skipped loudly - counted, warned, left flagged, no norm row - and the rest process`() =
        runBlocking {
            val connId = clone()
            val unlimited = storeWith(Long.MAX_VALUE / 4)
            unlimited.markAllNeedsProcessing(connId)
            val flagged = unlimited.issuesToProcess(connId, PROCESSING_VERSION, 6)
            // Only the first six issues are stale: the rest are marked processed, as the shared fixture's rows were.
            execute(
                "UPDATE raw.jira_issues SET needs_processing = false " +
                    "WHERE connection_id = $connId AND issue_id NOT IN (${flagged.joinToString(",")})",
            )
            val padded = flagged[2]
            execute(
                "UPDATE raw.jira_issues SET payload = payload || jsonb_build_object('padding', repeat('x', ${3 * MIB})) " +
                    "WHERE connection_id = $connId AND issue_id = $padded",
            )
            val store = storeWith(1 * MIB)
            val ctx = context(connId)
            val logs = LogCapture(LOGGER)
            try {
                JiraProcessStream(store, SyncedStubFixture.workItems()).run(ctx)

                assertEquals(1L, ctx.progressSnapshot()["issuesOversized"])
                assertEquals(5L, ctx.progressSnapshot()["issuesProcessed"])
                assertNull(ctx.progressSnapshot()["issuesFailed"])
                val warn = logs.events.single { it.formattedMessage.startsWith("PROCESS skipped issue") }
                assertContains(warn.formattedMessage, "PROCESS skipped issue $padded of data source $connId")
                assertContains(warn.formattedMessage, "jira.processPageBytes")
                assertFalse(logs.events.any { it.formattedMessage.contains("xxxxxxxx") }, "the payload is never logged")
                val stale = unlimited.issuesToProcess(connId, PROCESSING_VERSION, 100).toSet()
                assertEquals(setOf(padded), stale, "only the oversized issue stays stale")
                assertNull(workItemOf(connId, padded), "no norm row was written for it")
                flagged.filter { it != padded }.forEach { assertNotNull(workItemOf(connId, it), "issue $it was processed") }
                assertTrue(ctx.ceilingTrip("LIMIT_EXCEEDED", null).madeProgress)

                // Every run examines it again - loudly again - and still processes nothing for it.
                val again = context(connId)
                JiraProcessStream(store, SyncedStubFixture.workItems()).run(again)
                assertEquals(1L, again.progressSnapshot()["issuesOversized"])
                assertFalse(again.ceilingTrip("LIMIT_EXCEEDED", null).madeProgress, "nothing changed: no progress")
            } finally {
                logs.detach()
            }
        }

    private suspend fun workItemOf(connId: UInt, issueId: Long) = suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.WorkItems.selectAll()
            .where { (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.issueId eq issueId) }
            .toList().singleOrNull()
    }

    /** `n` synthetic live entities of [kind] on the clone: enough to exceed one planning window (2,000) and many chunks. */
    private fun insertEntities(connId: UInt, kind: String, n: Int, payloadSql: String) = execute(
        "INSERT INTO raw.jira_entities (connection_id, kind, entity_id, payload, sha256, first_seen_at, last_seen_at, changed_at) " +
            "SELECT $connId, '$kind', 'synthetic-' || lpad(g::text, 6, '0'), $payloadSql, repeat('0', 64), 1, 1, 1 " +
            "FROM generate_series(1, $n) g",
    )

    /** The four rebuilt reference tables of a connection as order-independent text (the very rows the store wrote). */
    private fun referenceRows(connId: UInt): Map<String, String> = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            mapOf(
                "statuses" to "SELECT status_id, name, category FROM norm.statuses WHERE connection_id = $connId ORDER BY status_id",
                "people" to "SELECT account_id, display_name, email, active FROM norm.people " +
                    "WHERE connection_id = $connId ORDER BY account_id",
                "boards" to "SELECT board_id, name, board_type, project_key FROM norm.boards WHERE connection_id = $connId ORDER BY 1",
                "columns" to "SELECT * FROM norm.board_columns WHERE connection_id = $connId ORDER BY 1, 2, 3, 4",
                "sprints" to "SELECT sprint_id, board_id, name, state, start_at, end_at, goal " +
                    "FROM norm.sprints WHERE connection_id = $connId ORDER BY sprint_id",
            ).mapValues { (_, sql) ->
                st.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    val rows = generateSequence { if (rs.next()) (1..columns).joinToString("|") { rs.getString(it).orEmpty() } else null }
                    rows.joinToString("\n")
                }
            }
        }
    }

    @Test
    fun `PROCESS over reference kinds spanning many chunks writes the very rows an unchunked read does, however big the kinds are`() =
        runBlocking {
            val connId = clone()
            // 2,100 USER entities (past one 2,000-entity planning window) and 300 more of each other reference kind, beside the stub's own.
            insertEntities(
                connId, "USER", 2_100,
                "jsonb_build_object('accountId', 'u-' || g, 'displayName', 'User ' || g, 'active', g % 2 = 0)",
            )
            insertEntities(connId, "STATUS", 300, "jsonb_build_object('id', 'x' || g, 'name', 'Status ' || g, 'statusCategory', 'TODO')")
            insertEntities(connId, "BOARD", 300, "jsonb_build_object('id', 9000 + g, 'name', 'Board ' || g, 'type', 'scrum')")
            insertEntities(
                connId, "BOARD_CONFIGURATION", 300,
                "jsonb_build_object('id', (9000 + g)::text, 'columnConfig', jsonb_build_object('columns', jsonb_build_array(" +
                    "jsonb_build_object('name', 'To Do', 'statuses', jsonb_build_array(jsonb_build_object('id', 'x' || g))))))",
            )
            insertEntities(
                connId, "SPRINT", 300,
                "jsonb_build_object('id', 9000 + g, 'name', 'Sprint ' || g, 'state', 'closed', 'originBoardId', 9000 + g)",
            )
            // A soft-deleted entity must be invisible to every read (the `deleted_at IS NULL` filter of both statements).
            execute(
                "INSERT INTO raw.jira_entities " +
                    "(connection_id, kind, entity_id, payload, sha256, first_seen_at, last_seen_at, changed_at, deleted_at) " +
                    "VALUES ($connId, 'USER', 'deleted-user', '{\"accountId\":\"deleted-user\",\"displayName\":\"Gone\"}', " +
                    "repeat('0', 64), 1, 1, 1, 5)",
            )
            // A configuration whose board is not live is never parsed (this one would throw if it were: no `id`).
            execute(
                "INSERT INTO raw.jira_entities " +
                    "(connection_id, kind, entity_id, payload, sha256, first_seen_at, last_seen_at, changed_at) " +
                    "VALUES ($connId, 'BOARD_CONFIGURATION', 'orphan-config', '{}', repeat('0', 64), 1, 1, 1)",
            )
            val unlimited = storeWith(Long.MAX_VALUE / 4)
            unlimited.markAllNeedsProcessing(connId)

            // One chunk per kind (the budget is far above any kind's text)...
            JiraProcessStream(unlimited, SyncedStubFixture.workItems()).run(context(connId))
            val whole = referenceRows(connId)
            val peopleRows = whole.getValue("people").lines().size
            assertTrue(peopleRows > 2_100, "the big USER kind was rebuilt: $peopleRows")
            assertTrue(whole.getValue("columns").isNotBlank() && whole.getValue("sprints").isNotBlank())

            // ...against a chunk of a few hundred bytes (many hundreds of chunks per kind): byte-identical rows, and nothing tripped.
            val chunked = context(connId)
            JiraProcessStream(storeWith(2 * 1024), SyncedStubFixture.workItems()).run(chunked)
            assertEquals(whole, referenceRows(connId))
            assertNull(chunked.progressSnapshot()["referenceRowsSkipped"], "no kind size is a skip, a refusal or a stall")

            // The chunk plan itself: every entity once, ascending, whatever the chunk size.
            val tiny = storeWith(1)
            val ids = tiny.mapEntities(connId, JiraEntityKind.USER.name) { id, _ -> id }
            assertEquals(ids.sorted(), ids)
            assertEquals(ids.size, ids.toSet().size)
            assertFalse("deleted-user" in ids, "a soft-deleted entity is never read")
            val live = PostgresTestSupport.connect().use { conn ->
                conn.createStatement().use { st ->
                    val sql = "SELECT count(*) FROM raw.jira_entities " +
                        "WHERE connection_id = $connId AND kind = 'USER' AND deleted_at IS NULL"
                    st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
                }
            }
            assertEquals(live, ids.size, "every live entity once, as counted independently")
            assertEquals(false, referenceRows(connId).getValue("people").contains("deleted-user"))
            assertEquals(unlimited.mapEntities(connId, JiraEntityKind.USER.name) { id, _ -> id }, ids)
        }

    @Test
    fun `PROJECT_FIELDS stores every project however big the stored kinds are`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-process-budget-ref", enabled = false).also { connections += it }
        val ctx = context(connId)
        val keys = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS

        // A 1 KiB chunk budget (the old "kind over twice the budget is refused whole"): the pass completes and skips nothing.
        JiraReferenceStream(SyncedStubFixture.buildClient(maxRetries = 0), storeWith(1_024), keys).run(ctx)

        assertNull(ctx.progressSnapshot()["projectFieldsSkipped"], "chunked reads: no project skipped")
        assertNull(cursors.get(connId, "reference"), "the pass completed: its cursor is cleared")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
