package ch.nokillswit

import ch.nokillswit.infra.db.MAX_PAYLOAD_CHARS
import ch.nokillswit.infra.db.chunkSizes
import ch.nokillswit.infra.db.payloadChunkSizes
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.RawWorklogInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `infra/db/MultiRowInsert.kt` bounds a statement's payload as well as its bind parameters (`MAX_PAYLOAD_CHARS`; `build-times.md`
 * WHY 48, "Postgres memory under dense raw inserts"): the pure planner `payloadChunkSizes`, then a dense-worklog write against
 * Postgres that must land in several statements and store exactly what the per-row writer stores.
 */
class MultiRowInsertPayloadTest {
    private val max = 100
    private val budget = 1_000L

    private fun plan(vararg payloads: Int) = payloadChunkSizes(payloads.toList(), max, budget)

    @Test
    fun `an empty batch plans no statement`() {
        assertEquals(emptyList(), plan())
    }

    @Test
    fun `one row over the budget goes alone, and so does its neighbourhood's remainder`() {
        assertEquals(listOf(1), plan(50_000))
        // [heavy, light, light]: the heavy row alone, then the two light ones together
        assertEquals(listOf(1, 2), plan(50_000, 10, 10))
        // [light, heavy]: the heavy row cannot join the light one
        assertEquals(listOf(1, 1), plan(10, 50_000))
    }

    @Test
    fun `many small rows plan exactly what the bind cap alone plans`() {
        for (total in 0..450) {
            assertEquals(chunkSizes(total, max), payloadChunkSizes(List(total) { 1 }, max, budget), "total $total")
            assertEquals(chunkSizes(total, max), payloadChunkSizes(List(total) { 0 }, max, budget), "weightless total $total")
        }
    }

    @Test
    fun `the budget is inclusive - exactly at it fits, one over splits`() {
        assertEquals(listOf(2), plan(500, 500))
        assertEquals(listOf(1, 1), plan(500, 501))
        // 4 rows of 250 = 1,000 fit as one statement; a fifth would not
        assertEquals(listOf(4), plan(250, 250, 250, 250))
        assertEquals(listOf(4, 1), plan(250, 250, 250, 250, 250))
    }

    @Test
    fun `a mixed batch splits where the payload fills up and keeps only power-of-two sizes`() {
        // the first 600 cannot pair with the second (1,200); the second plus four 100s is exactly 1,000 (5 rows, rounded down to 4),
        // then the remaining three 100s (3 rows -> 2) and the last one alone
        assertEquals(listOf(1, 4, 2, 1), plan(600, 600, 100, 100, 100, 100, 100, 100))
        val heavy = List(37) { if (it % 5 == 0) 900 else 30 }
        val sizes = payloadChunkSizes(heavy, max, budget)
        assertEquals(heavy.size, sizes.sum(), "every row is written exactly once")
        assertTrue(sizes.all { it == max || Integer.bitCount(it) == 1 }, "only full or power-of-two chunks: $sizes")
        var offset = 0
        for (size in sizes) {
            val payload = heavy.subList(offset, offset + size).sum()
            assertTrue(size == 1 || payload <= budget, "a statement of $size rows carries $payload > $budget")
            offset += size
        }
    }

    @Test
    fun `the row cap and the payload budget both apply - the tighter one wins`() {
        // 250 weightless rows: row cap only (100, 100, 32, 16, 2)
        assertEquals(chunkSizes(250, max), payloadChunkSizes(List(250) { 0 }, max, budget))
        // 250 rows of 100: the payload allows 10 per statement, so 8 (power of two), the rest in the same manner
        val sizes = payloadChunkSizes(List(250) { 100 }, max, budget)
        assertTrue(sizes.all { it <= 8 }, "$sizes")
        assertEquals(250, sizes.sum())
    }

    // --- Postgres ------------------------------------------------------------------------------------------------------------------

    /** Counts the multi-row INSERTs into [table] and remembers the most `String` characters one of them bound. */
    private class InsertCounter(private val table: String) : SuspendStatementInterceptor {
        var statements = 0
        var maxChars = 0

        override suspend fun beforeExecution(transaction: R2dbcTransaction, context: StatementContext) {
            val sql = context.sql(transaction)
            if (!sql.startsWith("INSERT INTO") || table !in sql.substringBefore(" VALUES")) return
            statements++
            maxChars = maxOf(maxChars, context.args.sumOf { (it.second as? String)?.length ?: 0 })
        }
    }

    private fun unique() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun createConnection(): UInt = SyncedStubFixture.dataSources().create(
        DataSourceRequest(
            name = "payload-bound-${unique()}",
            enabled = false,
            syncIntervalMinutes = 60,
            backfillFrom = defaultBackfillFrom(),
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                siteUrl = TEST_JIRA_SITE_URL,
                email = "svc-${unique()}@example.com",
                apiToken = "token-${UUID.randomUUID()}",
                projectKeys = listOf("ENG"),
                authScheme = JiraAuthScheme.BASIC,
            ),
        ),
    )

    private suspend fun storedWorklogs(connId: UInt): Map<Long, Pair<String, String>> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList()
            .associate { it[JiraRawStore.Worklogs.worklogId] to (it[JiraRawStore.Worklogs.payload] to it[JiraRawStore.Worklogs.sha256]) }
    }

    @Test
    fun `a dense-worklog issue is written in several bounded statements, stored as the per-row writer stores it`() = runBlocking {
        val perRow = createConnection()
        val batch = createConnection()
        val store = JiraRawStore(sharedDatabaseForTests())
        val count = 2_000
        // ~4 KB each: 8 MB of jsonb for one issue, the shape that OOM-killed a Postgres backend in one statement.
        val worklogs = (1..count).map {
            val id = 900_000L + it
            RawWorklogInput(77L, id, it * 10L, """{"id":"$id","issueId":"77","timeSpentSeconds":$it,"pad":"${"x".repeat(4_000)}$it"}""")
        }
        try {
            val counter = InsertCounter("jira_worklogs")
            val outcomes = suspendTransaction(sharedDatabaseForTests()) {
                registerInterceptor(counter)
                store.upsertWorklogs(batch, worklogs, now = 5_000)
            }
            worklogs.forEach { store.upsertWorklog(perRow, it.issueId, it.worklogId, it.worklogUpdatedAt, it.payloadJson, now = 5_000) }

            assertEquals(List(count) { RawUpsertOutcome.INSERTED }, outcomes)
            assertTrue(counter.statements >= 8, "8 MB of payload must not go in fewer than 8 statements, was ${counter.statements}")
            assertTrue(counter.statements <= 64, "...and not in a statement per row, was ${counter.statements}")
            assertTrue(counter.maxChars <= MAX_PAYLOAD_CHARS, "a statement bound ${counter.maxChars} chars, over $MAX_PAYLOAD_CHARS")
            assertEquals(count.toLong(), store.countWorklogs(batch))
            assertEquals(storedWorklogs(perRow), storedWorklogs(batch), "payload and sha256 are byte-identical to the per-row writer's")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (connId in listOf(perRow, batch)) JiraRawStore.Worklogs.deleteWhere { JiraRawStore.Worklogs.connectionId eq connId }
            }
        }
    }
}
