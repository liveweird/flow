package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.BeforeClass

/** Every report endpoint whose response carries a `Distribution` and so takes `tailPercent` (the `ReportTailPercent` spec parameter). */
private val DISTRIBUTION_REPORTS = listOf(
    "cycle-time", "blocked-time", "task-estimation-accuracy", "epic-estimation-accuracy",
    "estimate-adjustments", "reported-time-ratio", "data-quality",
)

/** Every `Distribution` object reachable in [element] (an object holding `n`, `hidden` and `histogram`). */
private fun distributionsIn(element: JsonElement): List<JsonObject> = when (element) {
    is JsonObject -> (if ("histogram" in element && "hidden" in element) listOf(element) else emptyList()) +
        element.values.flatMap(::distributionsIn)
    is JsonArray -> element.flatMap(::distributionsIn)
    else -> emptyList()
}

/**
 * `tailPercent` reaches the `Distribution` of EVERY report that builds one (the parser threads it through the shared
 * `buildDistribution`): grading is on the wire JSON of the derived stub fixture, independent of the report code, per
 * distribution: counts sum to `n`, only the LAST bucket is ever open, and `tailPercent=0` has none open at all.
 */
class ReportTailPercentTest {

    @Test
    fun `every distribution report honours tailPercent - counts sum to n, only the last bucket is open, 0 closes it`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-tail-percent")
        val base = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        var openBuckets = 0
        var distributions = 0

        for (report in DISTRIBUTION_REPORTS) {
            for (tail in listOf("", "&tailPercent=0", "&tailPercent=25")) {
                val response = client.get("/api/v1/reports/$report?$base$tail")
                assertEquals(HttpStatusCode.OK, response.status, "$report$tail")
                val found = distributionsIn(Json.parseToJsonElement(response.bodyAsText()))
                assertTrue(found.isNotEmpty(), "$report$tail carries a distribution")
                for (distribution in found) {
                    val n = distribution["n"]!!.jsonPrimitive.content.toLong()
                    val histogram = distribution["histogram"]!!.jsonArray.map { it.jsonObject }
                    distributions++
                    if (histogram.isEmpty()) continue
                    assertEquals(n, histogram.sumOf { it["count"]!!.jsonPrimitive.content.toLong() }, "$report$tail Σ counts == n")
                    // `to` is always written (explicit null on the open bucket), never omitted.
                    assertTrue(histogram.all { "to" in it }, "$report$tail every bucket carries `to`")
                    val open = histogram.map { it["to"] is JsonNull }
                    assertTrue(open.dropLast(1).none { it }, "$report$tail only the last bucket may be open")
                    if (tail == "&tailPercent=0") assertTrue(open.none { it }, "$report$tail has no open bucket")
                    if (open.last()) openBuckets++
                }
            }
        }
        assertTrue(distributions > 0)
        assertNotNull(openBuckets.takeIf { it > 0 }, "at 25% some distribution of the derived fixture must have a tail")
    }

    @Test
    fun `an out-of-range or malformed tailPercent is 400 on every distribution report`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-tail-percent-400")
        for (report in DISTRIBUTION_REPORTS) {
            for (bad in listOf("26", "-1", "x", "1.5")) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.get("/api/v1/reports/$report?tailPercent=$bad").status,
                    "$report tailPercent=$bad",
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
