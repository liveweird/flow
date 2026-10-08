package ch.nokillswit

import ch.nokillswit.ingest.LoggedFailure
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A failed sync job's logged exception keeps its own message and every frame, but never a cause's message (it can carry a URL query). */
class LoggedFailureTest {
    @Test
    fun `the failure keeps its message and frames, each cause only its class name`() {
        val transport = IOException("Connect timeout has expired [url=https://example.atlassian.net/rest/api/3/search/jql?jql=x]")
        val failure = IllegalStateException("Jira fetch failed: TIMEOUT @ https://example.atlassian.net/rest/api/3/search/jql", transport)

        val logged = LoggedFailure(failure, "IllegalStateException: ${failure.message}")

        assertEquals("IllegalStateException: ${failure.message}", logged.message)
        assertEquals(failure.stackTrace.toList(), logged.stackTrace.toList())
        val cause = logged.cause!!
        assertEquals("java.io.IOException", cause.message)
        assertEquals(transport.stackTrace.toList(), cause.stackTrace.toList())
        assertNull(cause.cause)
    }

    @Test
    fun `a failure without a cause logs without one`() {
        val failure = IllegalArgumentException("Element class kotlinx.serialization.json.JsonLiteral is not a JsonObject")

        val logged = LoggedFailure(failure, "bounded")

        assertSame(null, logged.cause)
        assertEquals("bounded", logged.message)
    }

    @Test
    fun `a cause chain is cut after ten levels`() {
        var failure: Throwable = IllegalStateException("root")
        repeat(15) { failure = IllegalStateException("level $it", failure) }

        val depth = generateSequence(LoggedFailure(failure, "top") as Throwable) { it.cause }.count()

        assertEquals(11, depth)
    }
}
