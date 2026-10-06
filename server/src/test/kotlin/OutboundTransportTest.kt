package ch.nokillswit

import ch.nokillswit.infra.outbound.DirectSocketFactory
import ch.nokillswit.infra.outbound.guardedOkHttpClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.buildGuardedJiraHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport-level guarantees of `infra/outbound/OutboundGuard.kt` that `OutboundGuardTest`
 * (allow-list, address ranges, error mapping) does not pin, each against a LOCAL socket server only
 * (`.claude/docs/security.md` "Outbound HTTP calls", "What pins what"):
 *  - the response/connection release of `jira/JiraHttp.kt` (LOW-3), with the leak window forced open by a
 *    server that sends headers plus a first chunk of the body and then HOLDS the rest — plus the
 *    negative control that the old `client.request()` pattern is stuck in exactly that window;
 *  - [DirectSocketFactory] (LOW-4): no re-resolution, no proxy;
 *  - `fastFallback(false)`.
 */
class OutboundTransportTest {

    /**
     * A one-purpose HTTP/1.1 server on a loopback ephemeral port. [heldBodyBytes] > 0: answers [status] with a
     * `Content-Length` of 10 MB, writes [heldBodyBytes] bytes of body, then HOLDS the rest until the client closes the
     * socket ([clientClosed] counts down the moment it does — the deterministic "the connection was released, not
     * leaked" signal). 0: answers 200 `{}` and closes. Every request's header block lands in [requests].
     */
    private class LocalHttpServer(private val status: Int = 200, private val heldBodyBytes: Int = 0) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val probeUrl = "http://${server.inetAddress.hostAddress}:$port/probe"
        val bodyHeld = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val clientClosed = CountDownLatch(1)
        private val sockets = CopyOnWriteArrayList<Socket>()

        init {
            thread(isDaemon = true, name = "outbound-transport-test-accept") {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        return@thread
                    }
                    sockets += socket
                    thread(isDaemon = true, name = "outbound-transport-test-conn") { serve(socket) }
                }
            }
        }

        private fun readHeaders(input: InputStream): String {
            val block = StringBuilder()
            while (!block.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next == -1) break
                block.append(next.toChar())
            }
            return block.toString()
        }

        private fun serve(socket: Socket) {
            try {
                val input = socket.getInputStream()
                requests += readHeaders(input)
                val output = socket.getOutputStream()
                if (heldBodyBytes > 0) {
                    output.write("HTTP/1.1 $status X\r\nContent-Length: 10000000\r\n\r\n".toByteArray())
                    output.write(ByteArray(heldBodyBytes) { 'x'.code.toByte() })
                    output.flush()
                    bodyHeld.countDown()
                    // Hold: blocks until the client closes its end (-1) or the connection breaks.
                    while (input.read() != -1) { /* discard */ }
                    clientClosed.countDown()
                } else {
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    output.flush()
                }
            } catch (_: java.io.IOException) {
                if (heldBodyBytes > 0) clientClosed.countDown()
            } finally {
                socket.close()
            }
        }

        fun awaitClientClosed(): Boolean = clientClosed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
        }
    }

    private fun loopbackClient(timeoutSeconds: Long = 5): HttpClient = buildGuardedJiraHttpClient(
        allowedHost = { true },
        skipAddressCheck = { true },
        requestTimeoutSeconds = timeoutSeconds,
    )

    private companion object {
        /** Free when passing; only a genuinely leaked connection waits it out. */
        const val CLOSE_WAIT_SECONDS = 5L
    }

    // --- [LOW-3] connection release, leak window forced open ---

    @Test
    fun `a failing status releases its connection while the response body is still unread`() {
        runBlocking {
            LocalHttpServer(status = 503, heldBodyBytes = 4096).use { server ->
                // maxRetries = 0: the 503 is terminal. The server never finishes the body, so any code that waits for
                // the body (the old `client.request()` buffers it whole) ends in TIMEOUT after 5s instead of 503 now.
                val error = loopbackClient().use { client ->
                    val jiraHttp = JiraHttp(client, maxRetries = 0, maxResponseBytes = 1_000_000, maxConcurrentRequests = 1)
                    assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, server.probeUrl) }
                }
                assertEquals("UPSTREAM_UNAVAILABLE", error.code)
                assertEquals(503, error.status, "the status must surface without waiting for the held body")
                assertTrue(server.awaitClientClosed(), "the connection must be closed by the client, not leaked")
            }
        }
    }

    @Test
    fun `an oversized body aborts at the limit and releases its connection, the rest never read`() {
        runBlocking {
            LocalHttpServer(status = 200, heldBodyBytes = 64 * 1024).use { server ->
                val error = loopbackClient().use { client ->
                    val jiraHttp = JiraHttp(client, maxRetries = 0, maxResponseBytes = 1_000, maxConcurrentRequests = 1)
                    assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, server.probeUrl) }
                }
                assertEquals("LIMIT_EXCEEDED", error.code, "the cap must trip on the bytes that arrived, not wait for the 10 MB promised")
                assertTrue(server.awaitClientClosed(), "the connection must be closed by the client, not leaked")
            }
        }
    }

    @Test
    fun `negative control - the old client request() pattern is stuck in that same leak window`() {
        runBlocking {
            LocalHttpServer(status = 503, heldBodyBytes = 4096).use { server ->
                val okHttp = guardedOkHttpClient(allowedHost = { true }, skipAddressCheck = { true }, timeoutSeconds = 5)
                HttpClient(OkHttp) {
                    engine { preconfigured = okHttp }
                    expectSuccess = false
                    install(HttpTimeout) { requestTimeoutMillis = 700 }
                }.use { client ->
                    // `client.request()` hands back a fully-buffered response: it waits for the whole (never-ending) body.
                    // This is the behaviour `JiraHttp`'s prepareRequest/execute/bodyAsChannel replaced; if this test ever
                    // stops timing out, the two tests above no longer discriminate the old pattern and must be revisited.
                    assertFailsWith<HttpRequestTimeoutException> { client.request(server.probeUrl) }
                // The stall must be the held body, not a slow runner: the server wrote the headers plus the first
                // chunk of THIS one request and is now holding the rest.
                assertTrue(server.bodyHeld.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS), "the server never reached the held body")
                assertEquals(1, server.requests.size, "exactly one request reached the server")
                }
            }
        }
    }

    // --- [LOW-4] DirectSocketFactory ---

    /** Records what OkHttp was actually told to connect to. */
    private class ConnectRecorder : EventListener() {
        val addresses = CopyOnWriteArrayList<InetSocketAddress>()
        val proxies = CopyOnWriteArrayList<Proxy>()
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            addresses += inetSocketAddress
            proxies += proxy
        }
    }

    @Test
    fun `DirectSocketFactory refuses every self-resolving createSocket overload`() {
        val loopback = InetAddress.getLoopbackAddress()
        assertFailsWith<SocketException> { DirectSocketFactory.createSocket("localhost", 1) }
        assertFailsWith<SocketException> { DirectSocketFactory.createSocket("localhost", 1, loopback, 0) }
        assertFailsWith<SocketException> { DirectSocketFactory.createSocket(loopback, 1) }
        assertFailsWith<SocketException> { DirectSocketFactory.createSocket(loopback, 1, loopback, 0) }
        DirectSocketFactory.createSocket().use { assertFalse(it.isConnected, "the no-arg socket is handed back unconnected") }
    }

    @Test
    fun `DirectSocketFactory sockets never consult the JVM ProxySelector that a plain Socket does`() {
        LocalHttpServer().use { server ->
            val consulted = CopyOnWriteArrayList<URI>()
            val original = ProxySelector.getDefault()
            // A recording selector that answers DIRECT: a plain `Socket()` asks it (and would honour a SOCKS answer);
            // a `Socket(Proxy.NO_PROXY)` never does. (The JDK's own default selector skips loopback targets, so a
            // real unreachable SOCKS proxy cannot discriminate here — the selector call is the observable fact.)
            ProxySelector.setDefault(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        // Only this test's own server: a bystander thread in the same fork must not count.
                        if (uri.port == server.port) consulted += uri
                        return listOf(Proxy.NO_PROXY)
                    }

                    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
                },
            )
            try {
                val target = InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)
                Socket().use { it.connect(target, 1_000) }
                assertEquals(1, consulted.size, "negative control: a plain Socket() consults the ProxySelector")
                consulted.clear()
                DirectSocketFactory.createSocket().use {
                    it.connect(target, 1_000)
                    assertEquals(target, it.remoteSocketAddress, "a direct socket connects to exactly the address it was given")
                }
                assertTrue(consulted.isEmpty(), "a DirectSocketFactory socket must never ask for a proxy: $consulted")
            } finally {
                ProxySelector.setDefault(original)
            }
        }
    }

    @Test
    fun `the guarded client connects to exactly the address GuardedDns resolved, never re-resolving the hostname`() {
        LocalHttpServer().use { server ->
            val pinned = InetAddress.getLoopbackAddress()
            // `.invalid` never resolves through the system resolver (RFC 6761): connecting to this hostname at all
            // proves the connection used GuardedDns's answer and not a second, hostname-based resolution.
            val host = "pinned.flow-test.invalid"
            var resolveCalls = 0
            val recorder = ConnectRecorder()
            val client = guardedOkHttpClient(
                allowedHost = { it == host },
                resolver = { resolveCalls++; listOf(pinned) },
                timeoutSeconds = 5,
                skipAddressCheck = { true },
                eventListener = recorder,
            )
            assertTrue(client.socketFactory === DirectSocketFactory, "the guarded client must use the direct socket factory")
            client.newCall(Request.Builder().url("http://$host:${server.port}/probe").build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(1, resolveCalls, "one new connection, one resolution")
            assertEquals(listOf(InetSocketAddress(pinned, server.port)), recorder.addresses.toList())
            assertEquals(listOf(Proxy.NO_PROXY), recorder.proxies.toList(), "never through a proxy")
            assertTrue(
                server.requests.single().contains("Host: $host:${server.port}"),
                "the original hostname still travels in the Host header: ${server.requests}",
            )
        }
    }

    // --- fastFallback(false) ---

    @Test
    fun `the guarded client has fast fallback off`() {
        // A configuration assertion by choice: fast fallback only changes behaviour when a host resolves to several
        // addresses and an earlier one is slow, which cannot be staged on loopback without timing races (127.0.0.2 is
        // not configured on macOS; a blackholed address needs the network). The flag IS the guarantee: OkHttp then
        // connects the already-checked address list strictly one at a time (no parallel Happy-Eyeballs attempts).
        val client = guardedOkHttpClient(allowedHost = { true }, timeoutSeconds = 5)
        assertFalse(client.fastFallback, "fast fallback races connection attempts across the resolved addresses")
        // Its neighbours in the same hardening block, cheap to pin here too.
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }
}
