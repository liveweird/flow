package ch.nokillswit

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `insertRows` must not let a long transaction's memory grow with the rows it has written (`.claude/docs/build-times.md`
 * WHY 14). DERIVE is ONE transaction, and Exposed R2DBC 1.5.0's `R2dbcTransaction` keeps every statement it executed in
 * `executedStatements` until the transaction ends (`closeStatementsAndConnection`, at commit or rollback, only clears the list — it closes
 * nothing; `executeIn` itself clears it before a statement when `!supportsMultipleResultSets`). Each
 * multi-row statement (up to 32,000 bind parameters) drags its driver `PostgresqlStatement` with it: ~100,000 parsed SQL
 * tokens plus every encoded parameter, ~8 MB, so a 24,000-issue DERIVE kept 700+ MB reachable and died at `-Xmx512m`.
 * `writeRows` therefore calls `clearExecutedStatements()` after each chunk; the pin is the cause itself — the list stays
 * empty. (A retained-heap measurement pinned the effect too, but cost ~5 s per fork for no extra signal; WHY 14.)
 */
class MultiRowInsertRetentionTest {
    private val table = MetricsTables.ItemStage

    // Exposed keeps the list internal; a rename on an upgrade fails here, loudly, and the pin must be re-derived then.
    private fun executedStatements(tx: R2dbcTransaction): Int {
        val field = R2dbcTransaction::class.java.getDeclaredField("executedStatements").also { it.isAccessible = true }
        return (field.get(tx) as List<*>).size
    }

    /** One chunk of [rows] rows (5,333 rows x 6 columns = 31,998 bind parameters is a maximum-size one). */
    private suspend fun R2dbcTransaction.writeChunk(connId: UInt, round: Int, rows: Int) {
        table.insertRows((1..rows).map { it + round * 10_000 }) {
            this[table.connectionId] = connId
            this[table.issueId] = it.toLong()
            this[table.stage] = "IN_PROGRESS"
            this[table.statusId] = "10001"
            this[table.validFrom] = it * 1_000L
            this[table.validTo] = null
        }
    }

    private suspend fun withConnection(body: suspend (UInt) -> Unit) {
        val connId = SyncedStubFixture.createConnection(namePrefix = "retention", enabled = false)
        try {
            body(connId)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { table.deleteWhere { table.connectionId eq connId } }
        }
    }

    @Test
    fun `a transaction does not keep the statements insertRows executed`() = runBlocking {
        withConnection { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                repeat(3) { writeChunk(connId, it, rows = 50) }
                assertEquals(0, executedStatements(this), "insertRows left its executed statements reachable until commit")
            }
        }
    }
}
