package ch.nokillswit

import ch.nokillswit.auth.RefreshRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins for declared behaviors the feature suites did not exercise (found by the 2026-08
 * quality checkup via the conformance coverage report): the negative-id 400 across resources,
 * the refresh endpoint's 400/429, and the AutoHeadResponse plugin.
 */
class CoverageGapsTest {

    @Test
    fun `negative path ids answer 400 on every id-carrying resource`() = testApplication {
        usePostgresTestcontainer()
        // The pre-routing intercept fires before authentication, so no token is needed —
        // exactly the spec's promise (minimum: 0) for every /api/ path.
        val client = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/users/-5").status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/users/-1").status)
        // Nested id-carrying paths ride the same pre-routing intercept.
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/users/-1/features").status)
    }

    @Test
    fun `a malformed refresh body is 400`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/refresh") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `the refresh bucket throttles a chatty host with 429`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        // The bucket is 30/min per host (deliberately not configurable); a garbage token is
        // rejected fast (401) until the bucket empties — then the 429 with a problem body.
        val statuses = (1..35).map {
            client.postJson("/api/v1/refresh", RefreshRequest(refreshToken = "garbage")).status
        }
        assertTrue(statuses.take(30).all { it == HttpStatusCode.Unauthorized }, "expected 401s first: $statuses")
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    @Test
    fun `AutoHeadResponse answers HEAD for GET routes`() = testApplication {
        usePostgresTestcontainer()
        // The default client on purpose: /openapi is outside the spec'd /api surface (the
        // conformance plugin would rightly flag an undeclared operation).
        val response = client.head("/openapi/documentation.yaml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }
}
