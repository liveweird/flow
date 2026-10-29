package ch.nokillswit.infra.db

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.util.context.Context

private val log = LoggerFactory.getLogger("ch.nokillswit.infra.db.DetachingConnectionFactory")

/**
 * Cuts the reference by which a pooled connection pins the caller that happened to CREATE it (`.claude/docs/persistence.md`
 * "Connection pool", `build-times.md` WHY 51). reactor-netty keeps the subscriber chain of the `create()` that opened a channel on
 * that channel's attributes (`ChannelOperations.listener`, a `NewConnectionProvider$NewConnectionObserver` whose `sink` leads to
 * its `actual` subscriber) for the channel's whole life - until the pool closes the connection after `maxIdleTimeSeconds` of
 * idleness, and never while it is reused. That chain ended at r2dbc-pool's allocator and, through the pool's borrower and Exposed's
 * `awaitOne`, at the continuation of the coroutine that triggered the creation: a finished job's frame (here a whole 25,000-row
 * worklog batch, +10 MiB) stayed reachable from the netty selector for minutes. Up to `maxSize` connections could each pin one.
 *
 * This decorator subscribes to the real `create()` with a [DetachingBridge] that forwards the one connection, error or completion
 * to the pool and then drops the pool's subscriber, and offers the driver an EMPTY Reactor context, so what the channel retains ends
 * at the bridge. It sits OUTERMOST on the raw factory so that a connection the bridge closes as an orphan is the fully decorated one
 * (its close reaches the socket guard). `PooledConnectionPinTest` pins it with weak references.
 */
internal class DetachingConnectionFactory(
    private val delegate: ConnectionFactory,
) : ConnectionFactory by delegate {
    override fun create(): Publisher<out Connection> = object : Mono<Connection>() {
        override fun subscribe(actual: CoreSubscriber<in Connection>) {
            val bridge = DetachingBridge(actual)
            actual.onSubscribe(bridge)
            if (bridge.isCancelled()) return // cancelled from onSubscribe: do not open a connection nobody wants
            val source = runCatching { Mono.from(delegate.create()) }.getOrElse { bridge.onError(it); return }
            source.subscribe(bridge)
        }
    }
}

/**
 * Subscriber to the real connection publisher and [Subscription] to the pool's subscriber, in one object. It holds the pool's
 * subscriber only until it has delivered the terminal signal or been cancelled, and honours downstream demand (a connection that
 * arrives before the pool requests waits for it). A connection that no longer has an owner - the pool cancelled while it was
 * on its way, or it arrived after the cancel - is CLOSED, never left to its idle socket.
 *
 * State changes happen under one lock (a connection is created at most a few times a second); the downstream, upstream and
 * connection callbacks run outside it.
 */
internal class DetachingBridge(downstream: Subscriber<in Connection>) : CoreSubscriber<Connection>, Subscription {
    private val lock = Any()
    private var downstream: Subscriber<in Connection>? = downstream
    private var upstream: Subscription? = null
    private var arrived: Connection? = null
    private var demanded = false
    private var finished = false // the upstream has terminated (or been cancelled by us)
    private var cancelled = false

    // EMPTY, never the pool's: reactor-netty copies the subscriber context into the channel's observer at connect time and keeps
    // that copy for the channel's life, so whatever a caller put in it (a ReactorContext element, a tracing value) would be pinned
    // per connection again. The one entry r2dbc-pool would add, the onNextDropped close of a late connection, is this bridge's job.
    override fun currentContext(): Context = Context.empty()

    override fun onSubscribe(s: Subscription) {
        val cancelNow = synchronized(lock) {
            if (cancelled || finished) true else { upstream = s; false }
        }
        if (cancelNow) s.cancel() else s.request(Long.MAX_VALUE)
    }

    override fun onNext(t: Connection) {
        val target: Subscriber<in Connection>?
        val orphan: Connection?
        synchronized(lock) {
            when {
                cancelled || finished || arrived != null -> { target = null; orphan = t }
                demanded -> { target = takeDownstream(); orphan = null; finished = true }
                else -> { target = null; orphan = null; arrived = t }
            }
        }
        if (orphan != null) closeQuietly(orphan)
        if (target != null) { target.onNext(t); target.onComplete() }
    }

    override fun onError(t: Throwable) {
        val target: Subscriber<in Connection>?
        val orphan: Connection?
        synchronized(lock) {
            target = if (finished || cancelled) null else takeDownstream()
            finished = true
            orphan = arrived
            arrived = null
        }
        if (orphan != null) closeQuietly(orphan)
        if (target != null) target.onError(t) else log.debug("connection error after the pool stopped waiting ({})", t.javaClass.simpleName)
    }

    override fun onComplete() {
        val target = synchronized(lock) {
            finished = true
            if (arrived == null) takeDownstream() else null // an undelivered connection completes with its delivery
        }
        target?.onComplete()
    }

    override fun request(n: Long) {
        if (n <= 0) return
        val target: Subscriber<in Connection>?
        val connection: Connection?
        synchronized(lock) {
            demanded = true
            connection = arrived
            arrived = null
            target = if (connection != null) takeDownstream() else null // a cancel has already taken `arrived`
            if (connection != null) finished = true
        }
        if (connection != null && target != null) { target.onNext(connection); target.onComplete() }
    }

    fun isCancelled(): Boolean = synchronized(lock) { cancelled }

    override fun cancel() {
        val toCancel: Subscription?
        val orphan: Connection?
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            downstream = null
            toCancel = upstream.takeIf { !finished }
            upstream = null
            orphan = arrived
            arrived = null
        }
        toCancel?.cancel()
        if (orphan != null) closeQuietly(orphan)
    }

    /** Hands the pool's subscriber out for its terminal signal and forgets it. Call under [lock]. */
    private fun takeDownstream(): Subscriber<in Connection>? {
        val d = downstream
        downstream = null
        upstream = null
        return d
    }

    private fun closeQuietly(connection: Connection) {
        Mono.from(connection.close()).subscribe({}, { log.debug("closing an unclaimed connection failed ({})", it.javaClass.simpleName) })
    }
}
