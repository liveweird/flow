package ch.nokillswit

import ch.nokillswit.infra.db.MAX_PAYLOAD_CHARS
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.lazyStatements
import ch.nokillswit.infra.db.maxRowsPerStatement
import ch.nokillswit.infra.db.rowsInStatementProbe
import ch.nokillswit.infra.db.payloadChunkSizes
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.RawWorklogInput
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ORACLE for the planner when no row is heavy (it used to be main code): as many full chunks of [maxRows] as fit, then the remainder
 * as its binary decomposition (13 -> 8, 4, 1) - at most `log2(maxRows) + 1` distinct sizes, so a table ever produces only a handful of
 * SQL texts. Written independently of `payloadChunkSizes` on purpose.
 */
internal fun chunkSizes(total: Int, maxRows: Int): List<Int> {
    val sizes = ArrayList<Int>()
    var left = total
    while (left >= maxRows) {
        sizes += maxRows
        left -= maxRows
    }
    var bit = Integer.highestOneBit(left)
    while (left > 0) {
        if (left >= bit) {
            sizes += bit
            left -= bit
        }
        bit = bit shr 1
    }
    return sizes
}

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

    /** Runs [lazyStatements] over [payloads]: the statement sizes and the most rows built but not yet handed out at any hand-out. */
    private fun lazyRun(payloads: List<Int>, maxRows: Int): Pair<List<Int>, Int> {
        var built = 0
        var emitted = 0
        var mostInFlight = 0
        val sizes = ArrayList<Int>()
        for (statement in lazyStatements(payloads.size, maxRows, budget, { weight: Int -> weight }) { payloads[built++] }) {
            mostInFlight = maxOf(mostInFlight, built - emitted)
            emitted += statement.size
            sizes += statement.size
            assertEquals(payloads.subList(emitted - statement.size, emitted), statement, "rows keep their order")
        }
        assertEquals(payloads.size, built, "every row is built exactly once")
        return sizes to mostInFlight
    }

    @Test
    fun `the lazy plan equals the full-list plan and builds ahead by at most one statement's row cap`() {
        for (total in 0..450) {
            val (sizes, inFlight) = lazyRun(List(total) { 1 }, max)
            assertEquals(chunkSizes(total, max), sizes, "light total $total")
            assertTrue(inFlight <= max, "light total $total built $inFlight ahead")
            // no payload overflow: not even a power-of-two remainder is built before its statement is decided
            val largest = sizes.maxOrNull() ?: 0
            assertTrue(inFlight <= largest, "light total $total built $inFlight for a largest statement of $largest")
        }
        val random = Random(396)
        repeat(400) { round ->
            val total = random.nextInt(0, 451)
            val heavyShare = listOf(0.0, 0.02, 0.1, 0.5, 1.0)[round % 5]
            val payloads = List(total) {
                when {
                    random.nextDouble() < heavyShare -> random.nextInt(300, 1_600)
                    random.nextBoolean() -> random.nextInt(0, 12)
                    else -> random.nextInt(0, 60)
                }
            }
            val (sizes, inFlight) = lazyRun(payloads, max)
            assertEquals(payloadChunkSizes(payloads, max, budget), sizes, "round $round, payloads $payloads")
            assertTrue(inFlight <= max, "round $round built $inFlight rows ahead of a statement, over the row cap $max")
        }
    }

    @Test
    fun `insertRows plans as the full-list planner does while building lazily - mixed light and heavy text rows`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "payload-lazy", enabled = false)
        val table = WorkItemStore.FieldChanges
        val random = Random(1_396)
        try {
            repeat(3) { round ->
                // `field` is TEXT: a heavy row is 100-700 K characters against the 1 MiB budget, so a statement holds few heavy rows.
                val payloads = List(120) { if (random.nextInt(8) == 0) random.nextInt(100_000, 700_000) else random.nextInt(1, 40) }
                val sizes = ArrayList<Int>()
                val builtAtStatement = ArrayList<Int>()
                var built = 0
                rowsInStatementProbe = { size ->
                    sizes += size
                    builtAtStatement += built
                }
                suspendTransaction(sharedDatabaseForTests()) {
                    table.insertRows(payloads.indices.toList()) { index ->
                        built++
                        this[table.connectionId] = connId
                        this[table.issueId] = round * 1_000L + index
                        this[table.seq] = 1
                        this[table.field] = "f".repeat(payloads[index])
                        this[table.changedAt] = 1L
                    }
                }
                rowsInStatementProbe = null
                assertEquals(payloadChunkSizes(payloads, maxRowsPerStatement(5), MAX_PAYLOAD_CHARS.toLong()), sizes, "round $round")
                assertEquals(payloads.size + 1, built, "round $round: every row built once, the first twice (it names the columns)")
                assertTrue(builtAtStatement.first() < payloads.size, "round $round: the first statement ran before the last row was built")
                assertEquals(
                    payloads.size * (round + 1L),
                    suspendTransaction(sharedDatabaseForTests()) { table.selectAll().where { table.connectionId eq connId }.count() },
                )
            }
        } finally {
            rowsInStatementProbe = null
            suspendTransaction(sharedDatabaseForTests()) { table.deleteWhere { table.connectionId eq connId } }
            SyncedStubFixture.dataSources().delete(connId)
        }
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

    private suspend fun storedWorklogs(connId: UInt): Map<Long, Pair<String, String>> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList()
            .associate { it[JiraRawStore.Worklogs.worklogId] to (it[JiraRawStore.Worklogs.payload] to it[JiraRawStore.Worklogs.sha256]) }
    }

    @Test
    fun `a dense-worklog issue is written in several bounded statements, stored as the per-row writer stores it`() = runBlocking {
        val perRow = SyncedStubFixture.createConnection(namePrefix = "payload-bound", enabled = false)
        val batch = SyncedStubFixture.createConnection(namePrefix = "payload-bound", enabled = false)
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
            for (connId in listOf(perRow, batch)) SyncedStubFixture.dataSources().delete(connId)
        }
    }
}
