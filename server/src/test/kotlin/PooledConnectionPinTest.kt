package ch.nokillswit

import ch.nokillswit.infra.db.connectPooledDatabase
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pooled connection must not keep the coroutine frame of the caller that CREATED it reachable (`.claude/docs/persistence.md`
 * "Connection pool", `build-times.md` WHY 51). Before `DetachingConnectionFactory`, reactor-netty kept the subscriber chain of
 * the acquire that created a connection (`NewConnectionProvider$NewConnectionObserver` on the channel's attributes -> the pool's
 * borrower -> the caller's `awaitOne` continuation) for the connection's whole life, so a 25,000-row batch held in the frame of a
 * job that had long returned stayed on the heap while the connection sat idle in the pool.
 *
 * Each test builds its OWN fresh pool with `initialSize = 0`, so the test's own first query is the one that CREATES the connection.
 */
class PooledConnectionPinTest {
    private fun options(): ConnectionFactoryOptions =
        ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl)
            .mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()

    /**
     * Holds a 16 MiB array in a local that is live across the query (read afterwards, so the compiler spills it into the
     * continuation), and returns only a weak reference to it: once the function has returned, nothing of the caller may keep it.
     */
    private suspend fun queryHoldingBatch(db: R2dbcDatabase, rows: Int): WeakReference<ByteArray> {
        val batch = ByteArray(16 * 1024 * 1024)
        val ref = WeakReference(batch)
        val answered = suspendTransaction(db) {
            exec("SELECT 'x'") { row -> row.get(0, String::class.java) }?.toList().orEmpty()
        }
        assertEquals(rows, answered.size)
        batch[0] = answered.size.toByte() // reads the array after the suspension: keeps it in the continuation
        return ref
    }

    @Test
    fun `the frame that created a pooled connection is collectable while the connection sits idle`() = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val (db, pool) = connectPooledDatabase(options(), 4, 0, Duration.ofSeconds(10), Duration.ofMinutes(5))
        try {
            val ref = withContext(Dispatchers.IO) { queryHoldingBatch(db, 1) }
            assertEquals(1, pool.metrics.get().allocatedSize(), "the query created exactly one pooled connection")
            assertEquals(1, pool.metrics.get().idleSize(), "and it sits idle in the pool")
            assertTrue(awaitCollected(ref), "the creating caller's batch is still reachable from the idle pooled connection")
        } finally {
            pool.dispose()
        }
    }

    /** Like [queryHoldingBatch], but keeps its transaction (and so its pooled connection) open until [gate] opens. */
    private suspend fun queryHoldingBatchUntil(db: R2dbcDatabase, gate: CompletableDeferred<Unit>): WeakReference<ByteArray> {
        val batch = ByteArray(16 * 1024 * 1024)
        val ref = WeakReference(batch)
        suspendTransaction(db) {
            exec("SELECT 'x'") { row -> row.get(0, String::class.java) }?.toList()
            gate.await()
        }
        batch[0] = 1 // read after the suspensions: keeps the array in the continuation
        return ref
    }

    @Test
    fun `a pool grown by concurrent acquires pins none of the callers' frames`() = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val (db, pool) = connectPooledDatabase(options(), 4, 0, Duration.ofSeconds(10), Duration.ofMinutes(5))
        try {
            val gate = CompletableDeferred<Unit>()
            val callers = (1..3).map { async(Dispatchers.IO) { queryHoldingBatchUntil(db, gate) } }
            // All three hold a connection at once: the pool had to CREATE three, one per caller.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (pool.metrics.get().acquiredSize() < 3 && System.nanoTime() < deadline) delay(20)
            assertEquals(3, pool.metrics.get().acquiredSize())
            gate.complete(Unit)
            val refs = callers.awaitAll()
            assertEquals(3, pool.metrics.get().idleSize(), "all three connections sit idle in the pool")
            refs.forEach { assertTrue(awaitCollected(it), "a creating caller's batch is still reachable from the pool") }
        } finally {
            pool.dispose()
        }
    }
}
