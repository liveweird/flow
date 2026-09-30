package ch.nokillswit

import ch.nokillswit.users.UserRole
import io.ktor.server.netty.EngineMain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decompiling ktor-server-forwarded-header 3.6.0 (XForwardedHeadersKt's onCall handler) confirms
 * it resolves every configured header — including X-Forwarded-For — via `Headers.get(name)`,
 * which returns the FIRST header LINE only (`StringValues.get` = `getAll(name)?.firstOrNull()`).
 * A proxy that APPENDS a fresh X-Forwarded-For line instead of merging into one the client
 * already sent (HAProxy's `option forwardedfor`) therefore leaves TWO physically distinct header
 * lines on the wire, and Ktor's own reader would pick the client-supplied FIRST one — the exact
 * gap `resolveForwardedForOrigin` (plugins/Http.kt) closes by folding every line via `getAll`
 * before XForwardedHeaders reads anything (ForwardedHeadersTest's "two SEPARATE ... header lines"
 * test exercises the same fold, but through ktor-client, whose own request writer folds repeated
 * `header()` calls into ONE wire line before send — so it cannot reproduce the genuinely-distinct
 * wire shape). This test writes the raw HTTP/1.1 request by hand against the REAL Netty engine
 * (ported technique: Toadie's ProductionHttpTest) so the two X-Forwarded-For lines stay distinct
 * exactly like a real HAProxy chain would deliver them.
 */
class RawForwardedForLinesTest {

    @Test
    fun `two DISTINCT X-Forwarded-For wire lines fold into one, in hop order`() {
        val server = EngineMain.createServer(
            arrayOf(
                "-port=0",
                "-P:ktor.development=true",
                "-P:postgres.jdbcUrl=${PostgresTestSupport.jdbcUrl}",
                "-P:postgres.r2dbcUrl=${PostgresTestSupport.r2dbcUrl}",
                "-P:postgres.user=${PostgresTestSupport.user}",
                "-P:postgres.password=${PostgresTestSupport.password}",
                "-P:security.csrf.enabled=false",
                "-P:http.behindProxy=true",
                "-P:security.rateLimit.loginPerMinute=10",
            ),
        )
        val seeded = mutableListOf<UInt>()
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }

            // The FIRST wire line rotates (a spoofing client); the SECOND is what the proxy
            // appended as its OWN, physically separate header line — never comma-joined into
            // the first, unlike anything sent through ktor-client's header() API.
            fun rawLoginStatus(clientLine: String, proxyLine: String): Int {
                // A SEEDED account with a wrong password, never an unknown email: unknown emails pay the
                // login route's constant-time cost-12 bcrypt verify (~225 ms locally, ~2x on CI) x 12 attempts.
                // Seeded BEFORE the socket opens, and soft-deleted in the outer finally.
                val email = "xff-raw-${UUID.randomUUID()}@test"
                seeded += runBlocking { TestUsers.seed(email, "the-right-password", role = UserRole.USER) }
                Socket("127.0.0.1", port).use { socket ->
                    val body = """{"email":"$email","password":"wrong"}"""
                    val bytes = body.toByteArray()
                    val request = buildString {
                        append("POST /api/v1/login HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:$port\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("X-Forwarded-For: $clientLine\r\n")
                        append("X-Forwarded-For: $proxyLine\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                        append(body)
                    }
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                        ?: error("no response from the server")
                    return statusLine.split(" ")[1].toInt()
                }
            }

            repeat(10) { i ->
                assertEquals(401, rawLoginStatus("10.9.$i.1", "203.0.113.7"))
            }
            assertEquals(429, rawLoginStatus("10.9.99.1", "203.0.113.7"))
            // A different proxy-appended address is a different client.
            assertEquals(401, rawLoginStatus("10.9.99.1", "203.0.113.8"))
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            runBlocking { seeded.forEach { TestUsers.softDelete(it) } }
        }
    }
}
