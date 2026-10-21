package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.PublicHolidayCreateRequest
import ch.nokillswit.metrics.PublicHolidayItem
import ch.nokillswit.metrics.PublicHolidayList
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The public-holiday dictionary (`metrics.public_holidays`, V25): GET any authenticated, POST/DELETE ADMIN-only, hard-deleting, unique
 * per date. The table is GLOBAL shared state in the shared container, so the tests use far-off years (the V25 seed stops at 2030) and
 * remove what they add. Every mutation bumps the shared revision (the DERIVE fan-out itself: `MetricsDeriveFanOutTest`).
 */
class PublicHolidayTest {

    private suspend fun HttpClient.revision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision

    private suspend fun HttpClient.createHoliday(date: String, name: String) = post("/api/v1/public-holidays") {
        contentType(ContentType.Application.Json)
        setBody(PublicHolidayCreateRequest(date, name))
    }

    @Test
    fun `the dictionary is readable by any authenticated user and ordered by date`() = testApplication {
        usePostgresTestcontainer()
        val first = TestHolidays.service.create(PublicHolidayCreateRequest("2085-01-06", "Epiphany"))
        val second = TestHolidays.service.create(PublicHolidayCreateRequest("2085-05-01", "Labour Day"))
        try {
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/public-holidays").status)
            val list = seededClient("holiday-reader", FlowUserRole.USER).get("/api/v1/public-holidays").body<PublicHolidayList>()
            val ours = list.items.filter { it.id == first.id || it.id == second.id }
            assertEquals(listOf("2085-01-06", "2085-05-01"), ours.map { it.date })
            assertEquals("Epiphany", ours.first().name)
            assertEquals(list.items.sortedBy { it.date }, list.items, "ordered by date over the whole dictionary")
        } finally {
            TestHolidays.service.delete(first.id)
            TestHolidays.service.delete(second.id)
        }
    }

    @Test
    fun `mutations are ADMIN-only and the guard runs before the body and the id lookup`() = testApplication {
        usePostgresTestcontainer()
        val plain = seededClient("holiday-plain", FlowUserRole.USER)
        val existing = TestHolidays.service.create(PublicHolidayCreateRequest("2085-06-16", "Some Day"))
        try {
            assertEquals(HttpStatusCode.Forbidden, plain.createHoliday("2085-06-17", "Blocked").status)
            assertEquals(HttpStatusCode.Forbidden, plain.createHoliday("garbage", " ").status, "403 wins over the 400 shape check")
            assertEquals(HttpStatusCode.Forbidden, plain.delete("/api/v1/public-holidays/${existing.id}").status)
            assertEquals(HttpStatusCode.Forbidden, plain.delete("/api/v1/public-holidays/999999999").status, "and over the 404")
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().delete("/api/v1/public-holidays/${existing.id}").status)
            assertTrue(TestHolidays.service.list().any { it.id == existing.id }, "nothing was deleted")
        } finally {
            TestHolidays.service.delete(existing.id)
        }
    }

    @Test
    fun `create validates the shape, trims the name and rejects a duplicate date with 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("holiday-admin", FlowUserRole.ADMIN)

        for ((date, name) in listOf(
            "2086-1-06" to "Sloppy", "garbage" to "Nope", "2086-02-30" to "Nope", "+12086-01-06" to "Nope", "2086-01-06 " to "Nope",
            "2086-01-06" to "  ", "2086-01-06" to "x".repeat(101), "2086-01-06" to "tab\there",
        )) {
            assertEquals(HttpStatusCode.BadRequest, admin.createHoliday(date, name).status, "expected 400 for '$date' / '$name'")
        }
        assertEquals(HttpStatusCode.BadRequest, admin.delete("/api/v1/public-holidays/-1").status, "a negative id is a 400")

        val response = admin.createHoliday("2086-01-06", "  Epiphany  ")
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<PublicHolidayItem>()
        try {
            assertEquals("/api/v1/public-holidays/${created.id}", response.headers["Location"])
            assertEquals("2086-01-06", created.date)
            assertEquals("Epiphany", created.name, "trimmed")
            assertEquals(HttpStatusCode.Conflict, admin.createHoliday("2086-01-06", "Duplicate").status, "one holiday per date")
            assertEquals(1, TestHolidays.service.list().count { it.date == "2086-01-06" })
        } finally {
            TestHolidays.service.delete(created.id)
        }
    }

    @Test
    fun `delete is a hard delete, 404s on a missing id and frees the date`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("holiday-del", FlowUserRole.ADMIN)
        val id = TestHolidays.service.create(PublicHolidayCreateRequest("2087-03-03", "Disposable")).id
        var again: PublicHolidayItem? = null
        try {
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/public-holidays/$id").status)
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/public-holidays/$id").status)
            assertTrue(admin.get("/api/v1/public-holidays").body<PublicHolidayList>().items.none { it.id == id })
            again = TestHolidays.service.create(PublicHolidayCreateRequest("2087-03-03", "Again"))
        } finally {
            TestHolidays.service.delete(id)
            again?.let { TestHolidays.service.delete(it.id) }
        }
    }

    @Test
    fun `a create and a delete each bump the shared revision, a failed create and a missing delete do not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("holiday-revision", FlowUserRole.ADMIN)
        val start = admin.revision()

        val created = admin.createHoliday("2089-02-02", "Revision day").body<PublicHolidayItem>()
        try {
            assertEquals(start + 1, admin.revision())
            assertEquals(HttpStatusCode.Conflict, admin.createHoliday("2089-02-02", "Again").status)
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/public-holidays/999999999").status)
            assertEquals(start + 1, admin.revision(), "neither the rolled-back duplicate nor the missing delete moves it")
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/public-holidays/${created.id}").status)
            assertEquals(start + 2, admin.revision())
        } finally {
            TestHolidays.service.delete(created.id)
        }
    }

    @Test
    fun `holiday mutations land in the security audit trail`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("holiday-audit-admin")
        val adminId = TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")
        withAuditCapture { capture ->
            val created = admin.createHoliday("2088-11-11", "Independence Day").body<PublicHolidayItem>()
            admin.delete("/api/v1/public-holidays/${created.id}")

            val createdEvent = assertNotNull(
                capture.awaitEvent { it.message == "public_holiday.created" && it.hasKeyValue("holidayId", created.id.toLong()) },
            )
            assertTrue(createdEvent.hasKeyValue("byUserId", adminId.toLong()))
            assertTrue(createdEvent.hasKeyValue("date", "2088-11-11"))
            assertTrue(createdEvent.hasKeyValue("name", "Independence Day"))
            val deletedEvent = assertNotNull(
                capture.awaitEvent { it.message == "public_holiday.deleted" && it.hasKeyValue("holidayId", created.id.toLong()) },
            )
            assertTrue(deletedEvent.hasKeyValue("date", "2088-11-11"))
        }
    }
}
