package ch.nokillswit

import ch.nokillswit.plugins.RateLimits
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-USER reports bucket (`RateLimits.REPORTS`, mounted once inside `authenticate {}` in `reports/ReportRoutes.kt`): one
 * token bucket per signed-in user shared by every `GET /api/v1/reports/…` operation. Each test runs its own application
 * instance, so the small configured limit cannot starve another test.
 */
class ReportRateLimitTest {

    private val filters = "/api/v1/reports/filters"

    @Test
    fun `a user past the bucket gets a 429 and one audit event naming them, while a second user is unaffected`() = testApplication {
        configureApp("security.rateLimit.reportsPerMinute" to "3")
        startApplication()
        val emailA = uniqueEmail("report-bucket-a")
        val idA = TestUsers.seed(emailA, "pw")
        val a = authedClient(emailA, "pw")
        val b = seededClient("report-bucket-b")
        withAuditCapture { capture ->
            repeat(3) { assertEquals(HttpStatusCode.OK, a.get(filters).status) }
            val throttled = a.get("$filters?probe=secret-value")
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("application/problem+json", throttled.contentType()?.withoutParameters().toString())
            assertContains(throttled.bodyAsText(), "Rate limit exceeded")
            assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
            repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, a.get(filters).status) }

            // Same client address (the test engine), a different account: its own, untouched bucket.
            repeat(3) { assertEquals(HttpStatusCode.OK, b.get(filters).status, "user B's bucket is independent of user A's") }

            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size, "A's four rejections fold into ONE event; B was never rejected")
            val event = events.single()
            assertTrue(event.hasKeyValue("bucket", "reports"))
            assertTrue(event.hasKeyValue("byUserId", idA.toLong()))
            assertTrue(event.hasKeyValue("path", filters), "the path carries no query string")
            assertTrue(event.hasKeyValue("suppressed", 0L))
            assertTrue(event.keyValuePairs.none { "secret-value" in it.value.toString() }, "no query value in the event")
            assertTrue(event.keyValuePairs.none { it.key == "remoteHost" || it.key == "ip" }, "no client address in the event")
        }
    }

    @Test
    fun `the audit coalesces per user, so two throttled users in one minute leave one event each`() = testApplication {
        configureApp("security.rateLimit.reportsPerMinute" to "1")
        startApplication()
        val emailA = uniqueEmail("report-bucket-co-a")
        val emailB = uniqueEmail("report-bucket-co-b")
        val idA = TestUsers.seed(emailA, "pw")
        val idB = TestUsers.seed(emailB, "pw")
        val a = authedClient(emailA, "pw")
        val b = authedClient(emailB, "pw")
        withAuditCapture { capture ->
            for (client in listOf(a, b)) {
                assertEquals(HttpStatusCode.OK, client.get(filters).status)
                repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, client.get(filters).status) }
            }
            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(2, events.size, "one event per throttled user, not one for the whole bucket")
            val named = events.map { e -> e.keyValuePairs.single { it.key == "byUserId" }.value }.toSet()
            assertEquals(setOf<Any?>(idA.toLong(), idB.toLong()), named)
            assertTrue(events.all { it.hasKeyValue("bucket", "reports") && it.hasKeyValue("suppressed", 0L) })
        }
    }

    @Test
    fun `an anonymous or forged call is a 401 that never consumes or creates a bucket`() = testApplication {
        configureApp("security.rateLimit.reportsPerMinute" to "2")
        startApplication()
        val anon = jsonClient()
        val user = seededClient("report-bucket-anon")
        withAuditCapture { capture ->
            repeat(6) { assertEquals(HttpStatusCode.Unauthorized, anon.get(filters).status) }
            repeat(3) {
                val forged = anon.get(filters) { header(HttpHeaders.Authorization, "Bearer not.a.token") }
                assertEquals(HttpStatusCode.Unauthorized, forged.status)
            }
            repeat(2) { assertEquals(HttpStatusCode.OK, user.get(filters).status, "the 401s left the budget intact") }
            assertEquals(HttpStatusCode.TooManyRequests, user.get(filters).status)
            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size, "only the signed-in user's rejection is audited; the anonymous 401s are not rate-limit events")
        }
    }

    @Test
    fun `every reports operation of the spec rides the one bucket`() = testApplication {
        // Limit 1: after the first call the user's bucket is empty, so ANY other /reports/ operation that still answers
        // something other than 429 is mounted outside the bucket (the 429 fires before parameter parsing, so no query is needed).
        configureApp("security.rateLimit.reportsPerMinute" to "1")
        startApplication()
        val client = seededClient("report-bucket-sweep")
        val operations = OpenApiSpec.parsed.paths
            .filter { (path, item) -> path.startsWith("/api/v1/reports/") && item.get != null }
            .keys.sorted()
        assertTrue(operations.size >= MIN_REPORT_OPERATIONS, "the sweep covers ${operations.size} report operations -- the spec shrank?")
        assertTrue(filters in operations)
        assertEquals(HttpStatusCode.OK, client.get(filters).status, "the one token")
        val escaped = operations.mapNotNull { path ->
            val status = client.get(path).status
            "$path -> $status".takeIf { status != HttpStatusCode.TooManyRequests }
        }
        assertEquals(emptyList(), escaped, "report operations outside the per-user bucket")
    }

    @Test
    fun `the limit follows the mode when blank or absent and a number pins it in either mode`() {
        fun limitFor(development: Boolean, configured: String?): Int {
            var limit = 0
            testApplication {
                environment {
                    val pinned = listOfNotNull(configured?.let { "security.rateLimit.reportsPerMinute" to it })
                    config = MapApplicationConfig(*pinned.toTypedArray())
                }
                serverConfig { developmentMode = development }
                application { limit = with(RateLimits) { reportsLimit() } }
                startApplication()
            }
            return limit
        }
        assertEquals(120, limitFor(development = false, configured = null), "production default")
        assertEquals(120, limitFor(development = false, configured = ""), "blank follows the mode: production")
        assertEquals(1000, limitFor(development = true, configured = null), "development lifts it")
        assertEquals(1000, limitFor(development = true, configured = ""), "blank follows the mode: development")
        assertEquals(7, limitFor(development = false, configured = "7"), "a number pins it in production")
        assertEquals(7, limitFor(development = true, configured = "7"), "a number pins it in development")
    }

    private companion object {
        const val MIN_REPORT_OPERATIONS = 34
    }
}
