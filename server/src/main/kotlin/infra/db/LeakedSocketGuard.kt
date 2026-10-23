package ch.nokillswit.infra.db

import io.r2dbc.postgresql.client.Client
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.lang.reflect.Field

private val log = LoggerFactory.getLogger("ch.nokillswit.infra.db.LeakedSocketGuard")

/**
 * Closes the socket r2dbc-postgresql leaves OPEN after a protocol-level connection error (`.claude/docs/persistence.md`
 * "Connection pool", `build-times.md` WHY 24). In r2dbc-postgresql 1.1.3, `ReactorNettyClient.BackendMessageSubscriber.onError`
 * (reached by "Response queue is full", ReactorNettyClient.java:884) completes the request sink, whose `doAfterTerminate`
 * runs `handleClose()` synchronously and sets `isClosed`; the `close()` it calls next then sees `isClosed` already set and
 * does nothing, and so does every later `Connection.close()` (the pool's destroy included). The channel stays open with
 * nobody reading it, so the backend keeps its transaction and every lock it took: `idle in transaction` when the rest of
 * the result fit in the socket buffers, `active` in `ClientWrite` otherwise (no server-side timeout ends that one) — for
 * as long as the process lives. Reproduced in `LeakedSocketGuardTest`.
 *
 * The decorator sits on the RAW factory (below the pool), so the pool's destroy of an invalid connection (a failed
 * `LOCAL` validation on release, which a closed client always fails) reaches it: after the driver's own close it disposes
 * the netty channel when the client says it is no longer connected but the channel is still open. A healthy close has
 * already closed the channel by then (the driver awaits `onDispose()`), so it is a no-op there.
 *
 * Reflection, because the driver keeps both handles private (`PostgresqlConnection.client`, `ReactorNettyClient.connection`).
 * If a driver upgrade renames them the guard switches itself off with one WARN at first use; `LeakedSocketGuardTest` pins
 * both the handles and the driver bug itself (when the bug test fails after an upgrade, the upstream fix landed and this
 * file can go).
 */
internal class LeakedSocketClosingConnectionFactory(
    private val delegate: ConnectionFactory,
) : ConnectionFactory by delegate {
    override fun create(): Publisher<out Connection> =
        Mono.from(delegate.create()).map { LeakedSocketClosingConnection(it) }
}

internal class LeakedSocketClosingConnection(
    private val delegate: Connection,
) : Connection by delegate, Wrapped<Connection> {
    override fun unwrap(): Connection = delegate

    override fun close(): Publisher<Void> =
        Mono.from(delegate.close()).doFinally {
            // Never let the guard itself fail a close: a reflection surprise is logged, the close stands.
            runCatching { LeakedSockets.closeIfLeaked(delegate) }
                .onFailure { log.warn("leaked-socket guard failed ({})", it.javaClass.simpleName) }
        }
}

internal object LeakedSockets {
    private class Handles(val client: Field, val nettyConnection: Field)

    private val handles: Handles? by lazy {
        runCatching {
            Handles(
                client = Class.forName("io.r2dbc.postgresql.PostgresqlConnection").getDeclaredField("client")
                    .apply { isAccessible = true },
                nettyConnection = Class.forName("io.r2dbc.postgresql.client.ReactorNettyClient").getDeclaredField("connection")
                    .apply { isAccessible = true },
            )
        }.onFailure {
            log.warn("leaked-socket guard disabled: r2dbc-postgresql internals not found ({})", it.javaClass.simpleName)
        }.getOrNull()
    }

    /** Whether the driver internals the guard needs were found (pinned by `LeakedSocketGuardTest`). */
    val supported: Boolean get() = handles != null

    /** Resolves the driver internals at pool setup, so a "disabled" WARN shows at boot, not at the first broken connection. */
    fun checkSupported(): Boolean = supported

    /**
     * Disposes the netty channel of [connection] (a raw `PostgresqlConnection`) when its client is closed but the channel
     * is still open, i.e. the driver gave up on the connection without closing its socket. Returns whether it did.
     */
    fun closeIfLeaked(connection: Connection): Boolean {
        val h = handles ?: return false
        val client = (if (h.client.declaringClass.isInstance(connection)) h.client.get(connection) else null) as? Client
        val gaveUp = client != null && !client.isConnected && h.nettyConnection.declaringClass.isInstance(client)
        val netty = (if (gaveUp) h.nettyConnection.get(client) else null) as? reactor.netty.Connection
        if (netty == null || !netty.channel().isOpen) return false
        netty.dispose()
        log.warn(
            "closed the socket of a broken database connection the driver had left open (backend pid {}; its transaction rolls back)",
            client?.processId?.orElse(null),
        )
        return true
    }
}
