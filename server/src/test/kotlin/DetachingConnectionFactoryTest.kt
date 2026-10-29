package ch.nokillswit

import ch.nokillswit.infra.db.DetachingConnectionFactory
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.util.context.Context
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contract of `DetachingConnectionFactory` (`infra/db/DetachingConnectionFactory.kt`) without a database: one delivery, errors,
 * demand, cancellation (a connection nobody owns is closed) and the point of it all - the pool's subscriber is forgotten once served.
 * `PooledConnectionPinTest` pins the effect on a real pool.
 */
class DetachingConnectionFactoryTest {
    private class FakeConnection {
        val closed = AtomicInteger()
        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "close" -> Mono.fromRunnable<Void> { closed.incrementAndGet() }
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeConnection"
                else -> error("unexpected ${method.name}")
            }
        } as Connection
    }

    /** A source that emits only when the test says so and IGNORES cancellation, like a driver whose connect is already in flight. */
    private class ManualSource : ConnectionFactory {
        var subscriber: Subscriber<in Connection>? = null
        var upstreamCancels = 0
        override fun create(): Publisher<out Connection> = Publisher { s ->
            subscriber = s
            s.onSubscribe(object : Subscription {
                override fun request(n: Long) = Unit
                override fun cancel() { upstreamCancels++ }
            })
        }
        override fun getMetadata(): ConnectionFactoryMetadata = ConnectionFactoryMetadata { "manual" }
    }

    private class Recorder(private val initialRequest: Long) : Subscriber<Connection> {
        val values = mutableListOf<Connection>()
        val errors = mutableListOf<Throwable>()
        var completions = 0
        var subscription: Subscription? = null
        override fun onSubscribe(s: Subscription) { subscription = s; if (initialRequest > 0) s.request(initialRequest) }
        override fun onNext(t: Connection) { values += t }
        override fun onError(t: Throwable) { errors += t }
        override fun onComplete() { completions++ }
    }

    private fun factoryOver(source: ConnectionFactory) = DetachingConnectionFactory(source)

    @Test
    fun `delivers the connection once and completes`() {
        val fake = FakeConnection()
        val source = object : ConnectionFactory by ManualSource() {
            override fun create(): Publisher<out Connection> = Mono.just(fake.connection)
        }
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        assertEquals(listOf(fake.connection), recorder.values)
        assertEquals(1, recorder.completions)
        assertEquals(0, fake.closed.get())
        assertSame(fake.connection, Mono.from(factoryOver(source).create()).block())
    }

    @Test
    fun `an error reaches the subscriber and nothing else`() {
        val boom = IllegalStateException("boom")
        val source = object : ConnectionFactory by ManualSource() {
            override fun create(): Publisher<out Connection> = Mono.error(boom)
        }
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        assertEquals(listOf<Throwable>(boom), recorder.errors)
        assertTrue(recorder.values.isEmpty())
        assertEquals(0, recorder.completions)
    }

    @Test
    fun `an empty source completes empty`() {
        val source = object : ConnectionFactory by ManualSource() {
            override fun create(): Publisher<out Connection> = Mono.empty<Connection>()
        }
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        assertTrue(recorder.values.isEmpty())
        assertEquals(1, recorder.completions)
    }

    @Test
    fun `a connection that arrives before the demand waits for it`() {
        val fake = FakeConnection()
        val source = ManualSource()
        val recorder = Recorder(0)
        factoryOver(source).create().subscribe(recorder)
        source.subscriber!!.onNext(fake.connection)
        source.subscriber!!.onComplete()
        assertTrue(recorder.values.isEmpty(), "no demand yet")
        recorder.subscription!!.request(1)
        assertEquals(listOf(fake.connection), recorder.values)
        assertEquals(1, recorder.completions)
        assertEquals(0, fake.closed.get())
    }

    @Test
    fun `cancelling before the connection arrives cancels the source and closes the late connection`() {
        val fake = FakeConnection()
        val source = ManualSource()
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        recorder.subscription!!.cancel()
        assertEquals(1, source.upstreamCancels, "the real connect is cancelled")
        source.subscriber!!.onNext(fake.connection) // a driver that was already past the point of cancelling
        source.subscriber!!.onComplete()
        assertTrue(recorder.values.isEmpty())
        assertEquals(0, recorder.completions)
        assertEquals(1, fake.closed.get(), "the late connection is closed, not leaked")
    }

    @Test
    fun `cancelling after the connection arrived but before the demand closes it`() {
        val fake = FakeConnection()
        val source = ManualSource()
        val recorder = Recorder(0)
        factoryOver(source).create().subscribe(recorder)
        source.subscriber!!.onNext(fake.connection)
        recorder.subscription!!.cancel()
        assertEquals(1, fake.closed.get())
        recorder.subscription!!.request(1)
        assertTrue(recorder.values.isEmpty())
        assertEquals(1, fake.closed.get(), "closed exactly once")
    }

    @Test
    fun `signals after the delivery do not reach the served subscriber`() {
        val first = FakeConnection()
        val source = ManualSource()
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        source.subscriber!!.onNext(first.connection)
        source.subscriber!!.onError(IllegalStateException("late"))
        assertEquals(1, recorder.values.size)
        assertEquals(1, recorder.completions)
        assertTrue(recorder.errors.isEmpty())
        assertEquals(0, first.closed.get())
    }

    @Test
    fun `the subscriber is forgotten once it has been served, so the source cannot pin it`() {
        // `source.subscriber` stands for the bridge a netty channel keeps alive for the connection's life.
        val source = ManualSource()
        var recorder: Recorder? = Recorder(1)
        val weak = WeakReference(recorder)
        factoryOver(source).create().subscribe(recorder!!)
        source.subscriber!!.onNext(FakeConnection().connection)
        source.subscriber!!.onComplete()
        assertEquals(1, recorder.values.size)
        recorder = null
        assertTrue(awaitCollected(weak), "the pool's subscriber is still reachable from the bridge after delivery")
        assertNotNull(source.subscriber)
    }

    @Test
    fun `the subscriber is forgotten once it cancelled`() {
        val source = ManualSource()
        var recorder: Recorder? = Recorder(1)
        val weak = WeakReference(recorder)
        factoryOver(source).create().subscribe(recorder!!)
        recorder.subscription!!.cancel()
        recorder = null
        assertTrue(awaitCollected(weak), "the pool's subscriber is still reachable from the bridge after the cancel")
    }

    @Test
    fun `a cancel after the delivery never reaches the source`() {
        // The source stands for the driver's connect, whose channel now carries a live pooled connection.
        val source = ManualSource()
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        source.subscriber!!.onNext(FakeConnection().connection)
        // `Mono.from` over this plain Publisher takes the first value and cancels the source itself (the driver's Mono does not).
        val cancelsBefore = source.upstreamCancels
        recorder.subscription!!.cancel()
        assertEquals(1, recorder.values.size)
        assertEquals(cancelsBefore, source.upstreamCancels, "the pool's cancel reached the source")
    }

    @Test
    fun `a cancel from onSubscribe never opens a connection`() {
        val source = ManualSource()
        val recorder = object : Subscriber<Connection> {
            override fun onSubscribe(s: Subscription) = s.cancel()
            override fun onNext(t: Connection) = error("no value expected")
            override fun onError(t: Throwable) = error("no error expected")
            override fun onComplete() = error("no completion expected")
        }
        factoryOver(source).create().subscribe(recorder)
        assertNull(source.subscriber, "the real create() was never subscribed")
    }

    @Test
    fun `a create that throws reaches the subscriber as an error`() {
        val boom = IllegalStateException("boom")
        val source = object : ConnectionFactory by ManualSource() {
            override fun create(): Publisher<out Connection> = throw boom
        }
        val recorder = Recorder(1)
        factoryOver(source).create().subscribe(recorder)
        assertEquals(listOf<Throwable>(boom), recorder.errors)
    }

    @Test
    fun `the source sees an empty context, never the pool's`() {
        val source = ManualSource()
        Mono.from(factoryOver(source).create()).contextWrite(Context.of("caller", "state")).subscribe()
        val context = (source.subscriber as CoreSubscriber<*>).currentContext()
        assertTrue(context.isEmpty, "the driver copies this context into the channel for its life: $context")
    }

    @Test
    fun `the metadata is the delegate's`() {
        val source = ManualSource()
        assertEquals("manual", factoryOver(source).metadata.name)
    }
}
