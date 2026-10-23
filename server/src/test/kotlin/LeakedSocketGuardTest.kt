package ch.nokillswit

import ch.nokillswit.infra.db.LeakedSockets
import ch.nokillswit.infra.db.connectPooledDatabase
import ch.nokillswit.infra.db.withIdleInTransactionTimeout
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Wrapped
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The defences against a backend left holding its transaction by a client that stopped talking to it (`build-times.md`
 * WHY 24, `.claude/docs/persistence.md` "Connection pool"): `LeakedSocketGuard.kt` (r2dbc-postgresql 1.1.3 leaves the socket
 * open after a "Response queue is full") and the pool's `idle_in_transaction_session_timeout`. Each test builds its own
 * small pool under a unique `application_name` and watches its backends from a plain JDBC session; the row it locks is a
 * team row it seeded itself.
 *
 * The driver failure is injected the way the overflow branch of `onNext` raises it: `BackendMessageSubscriber.onError` with the
 * driver's own `ResponseQueueException`, on the connection's event loop — but with the connection idle between statements,
 * not mid-stream (the guard only depends on the state `onError` leaves behind).
 */
class LeakedSocketGuardTest {
    private val baseOptions: ConnectionFactoryOptions
        get() = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()

    private fun options(appName: String): ConnectionFactoryOptions =
        baseOptions.mutate().option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, appName).build()

    private fun <T> withPool(
        appName: String,
        idleInTransactionTimeout: Duration = Duration.ofMinutes(5),
        block: suspend (R2dbcDatabase) -> T,
    ): T = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val (db, pool) = connectPooledDatabase(
            options(appName), 2, 1, Duration.ofSeconds(10), Duration.ofMinutes(1), idleInTransactionTimeout,
        )
        try {
            block(db)
        } finally {
            pool.dispose()
        }
    }

    /** `state` of every backend of [appName] (empty = none left). */
    private fun backendStates(appName: String): List<String> = PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement("SELECT state FROM pg_stat_activity WHERE application_name = ?").use { st ->
            st.setString(1, appName)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) ?: "null") } }
        }
    }

    /** Whether a foreign session can lock the team row within [waitMillis] (a lock wait past it is a `lock_timeout` error). */
    private fun foreignSessionCanLock(teamId: UInt, waitMillis: Long): Boolean = PostgresTestSupport.connect().use { conn ->
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("SET LOCAL lock_timeout = $waitMillis") }
            conn.createStatement().use { it.executeQuery("SELECT id FROM teams WHERE id = ${teamId.toInt()} FOR UPDATE").close() }
            true
        } catch (e: java.sql.SQLException) {
            if (e.sqlState != LOCK_NOT_AVAILABLE) throw e
            false
        } finally {
            conn.rollback()
        }
    }

    private suspend fun R2dbcTransaction.lockTeam(teamId: UInt) {
        exec("SELECT id FROM teams WHERE id = ${teamId.toInt()} FOR UPDATE") { it.get(0) }?.toList()
    }

    /** The raw driver connection under the pooled, cached and guarded wrappers of the CURRENT transaction. */
    private suspend fun R2dbcTransaction.driverConnection(): Any {
        // Exposed keeps the transaction's acquired connection in a private field (its `connection` publisher would acquire another).
        val exposed = connection()
        var c: Any = exposed.javaClass.getDeclaredField("localConnection").apply { isAccessible = true }.get(exposed)!!
        while (c is Wrapped<*> && c.javaClass.name != "io.r2dbc.postgresql.PostgresqlConnection") c = c.unwrap() ?: break
        assertEquals("io.r2dbc.postgresql.PostgresqlConnection", c.javaClass.name)
        return c
    }

    /** What the driver's "Response queue is full" branch runs: `messageSubscriber.onError(...)`, on the event loop. */
    private fun injectResponseQueueOverflow(driverConnection: Any) {
        val client = driverConnection.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(driverConnection)
        val subscriber = client.javaClass.getDeclaredField("messageSubscriber").apply { isAccessible = true }.get(client)
        val netty = client.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(client) as reactor.netty.Connection
        val failure = Class.forName("io.r2dbc.postgresql.client.ReactorNettyClient\$ResponseQueueException")
            .getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("Response queue is full")
        val onError = subscriber.javaClass.getDeclaredMethod("onError", Throwable::class.java).apply { isAccessible = true }
        netty.channel().eventLoop().submit { onError.invoke(subscriber, failure) }.get()
    }

    private suspend fun awaitNoBackend(appName: String, withinMillis: Long): List<String> {
        val deadline = System.nanoTime() + withinMillis * 1_000_000
        var states = backendStates(appName)
        while (states.isNotEmpty() && System.nanoTime() < deadline) {
            delay(POLL_MS)
            states = backendStates(appName)
        }
        return states
    }

    @Test
    fun `the guard finds the driver internals it needs`() {
        assertTrue(LeakedSockets.supported, "r2dbc-postgresql renamed PostgresqlConnection.client or ReactorNettyClient.connection")
    }

    @Test
    fun `a connection the driver gave up on is closed, so its backend ends and its row lock is released`() {
        val appName = "flow-guard-${UUID.randomUUID()}"
        val team = runBlocking { TestTeams.seed("guard-${UUID.randomUUID()}") }
        withPool(appName) { db ->
            val failure = runCatching {
                suspendTransaction(db) {
                    lockTeam(team)
                    injectResponseQueueOverflow(driverConnection())
                    exec("SELECT 1") { it.get(0) }?.toList()
                }
            }.exceptionOrNull()
            assertNotNull(failure, "the statement after the overflow must fail: the driver closed the connection")
            val left = awaitNoBackend(appName, withinMillis = 10_000)
            assertTrue(left.isEmpty(), "the broken connection's backend must end once the pool destroys it; still there: $left")
            assertTrue(foreignSessionCanLock(team, waitMillis = 5_000), "the row lock must be released with the backend")
            // The pool replaces the destroyed connection: the next transaction works.
            assertEquals(listOf(1), suspendTransaction(db) { exec("SELECT 1") { it.getInt(1) }?.toList() })
        }
    }

    /**
     * The driver bug the guard works around, pinned on an UNGUARDED connection straight from the driver's factory: when
     * this fails after a driver upgrade, the upstream fix landed and `LeakedSocketGuard.kt` can go.
     */
    @Test
    fun `without the guard the driver leaves the backend holding its transaction`() = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val appName = "flow-unguarded-${UUID.randomUUID()}"
        val team = TestTeams.seed("unguarded-${UUID.randomUUID()}")
        val connection = ConnectionFactories.get(options(appName)).create().awaitSingle()
        try {
            connection.beginTransaction().awaitFirstOrNull()
            connection.createStatement("SELECT id FROM teams WHERE id = ${team.toInt()} FOR UPDATE").execute().awaitSingle()
                .map { row, _ -> row.get(0) }.awaitSingle()
            injectResponseQueueOverflow(connection)
            assertFalse(connection.validate(io.r2dbc.spi.ValidationDepth.LOCAL).awaitSingle(), "the driver considers it closed")
            runCatching { connection.close().awaitFirstOrNull() }
            delay(UNGUARDED_SETTLE_MS)
            assertEquals(listOf("idle in transaction"), backendStates(appName), "r2dbc-postgresql no longer leaks the socket?")
            assertFalse(foreignSessionCanLock(team, waitMillis = 500), "the leaked backend still holds the row lock")
        } finally {
            // A healthy connection (an assertion failed before the injection, or a fixed driver) closes and rolls back here; a
            // leaked one is a no-op close, so the guard's own cleanup ends its backend. Either way no FOR UPDATE outlives the test.
            runCatching { connection.close().awaitFirstOrNull() }
            LeakedSockets.closeIfLeaked(connection)
            awaitNoBackend(appName, withinMillis = 10_000)
        }
    }

    @Test
    fun `every pooled connection carries the idle-in-transaction timeout`() {
        val appName = "flow-iitt-${UUID.randomUUID()}"
        withPool(appName, idleInTransactionTimeout = Duration.ofSeconds(7)) { db ->
            val shown = suspendTransaction(db) {
                exec("SHOW idle_in_transaction_session_timeout") { it.get(0, String::class.java) }?.toList()?.single()
            }
            assertEquals("7s", shown)
        }
    }

    @Test
    fun `the idle-in-transaction startup option merges with an operator's options and never overrides theirs`() {
        val opt = PostgresqlConnectionFactoryProvider.OPTIONS
        val key = "idle_in_transaction_session_timeout"
        val five = Duration.ofMinutes(5)
        assertEquals(mapOf(key to "300000"), withIdleInTransactionTimeout(baseOptions, five).getValue(opt))
        val withMap = baseOptions.mutate().option(opt, mapOf("search_path" to "public")).build()
        assertEquals(mapOf("search_path" to "public", key to "300000"), withIdleInTransactionTimeout(withMap, five).getValue(opt))
        val operatorSet = baseOptions.mutate().option(opt, mapOf(key to "1000")).build()
        assertEquals(mapOf(key to "1000"), withIdleInTransactionTimeout(operatorSet, five).getValue(opt))
        @Suppress("UNCHECKED_CAST") // the URL form of `options` is its string, which the declared Map type does not cover
        val asString = baseOptions.mutate().option(opt as io.r2dbc.spi.Option<Any>, "search_path=public").build()
        assertEquals("search_path=public;$key=300000", withIdleInTransactionTimeout(asString, five).getValue(opt).toString())
    }

    @Test
    fun `the server ends a session left idle in a transaction and releases its row lock`() {
        val appName = "flow-iitt-${UUID.randomUUID()}"
        val team = runBlocking { TestTeams.seed("iitt-${UUID.randomUUID()}") }
        withPool(appName, idleInTransactionTimeout = Duration.ofSeconds(IDLE_TIMEOUT_S)) { db ->
            val failure = runCatching {
                suspendTransaction(db) {
                    lockTeam(team)
                    assertFalse(foreignSessionCanLock(team, waitMillis = 200), "the transaction holds the row lock")
                    // Idle inside the transaction past the timeout: the server terminates the session.
                    delay(IDLE_TIMEOUT_S * 1000 + IDLE_MARGIN_MS)
                    assertTrue(foreignSessionCanLock(team, waitMillis = 5_000), "the server must have released the lock by now")
                    exec("SELECT 1") { it.get(0) }?.toList()
                }
            }.exceptionOrNull()
            assertNotNull(failure, "the terminated session's next statement must fail")
            assertTrue(awaitNoBackend(appName, withinMillis = 10_000).isEmpty())
            assertEquals(listOf(1), suspendTransaction(db) { exec("SELECT 1") { it.getInt(1) }?.toList() })
        }
    }

    private fun io.r2dbc.spi.Readable.getInt(index: Int): Int = (get(index - 1) as Number).toInt()

    private companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
        const val POLL_MS = 100L
        const val UNGUARDED_SETTLE_MS = 1_000L
        const val IDLE_TIMEOUT_S = 2L
        const val IDLE_MARGIN_MS = 1_500L
    }
}
