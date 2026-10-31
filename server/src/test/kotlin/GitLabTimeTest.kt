package ch.nokillswit

import ch.nokillswit.gitlab.isoInstant
import ch.nokillswit.gitlab.parseGitLabInstantMillis
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `gitlab/GitLabTime.kt`: GitLab's ISO-8601 instants through the shared offset-tolerant parser (never `Instant.parse`). */
class GitLabTimeTest {
    private val expected = Instant.parse("2026-08-31T11:27:54Z").toEpochMilli()

    @Test
    fun `a colon offset, Z, a colonless offset and a bare-hour offset all resolve to the same instant`() {
        assertEquals(expected, parseGitLabInstantMillis("2026-08-31T13:27:54+02:00"))
        assertEquals(expected, parseGitLabInstantMillis("2026-08-31T11:27:54Z"))
        assertEquals(expected, parseGitLabInstantMillis("2026-08-31T13:27:54+0200"))
        assertEquals(expected, parseGitLabInstantMillis("2026-08-31T13:27:54+02"))
    }

    @Test
    fun `fractional seconds are optional`() {
        assertEquals(expected, parseGitLabInstantMillis("2026-08-31T13:27:54.000+02:00"))
        assertEquals(expected + 123, parseGitLabInstantMillis("2026-08-31T13:27:54.123+02:00"))
        assertEquals(expected + 500, parseGitLabInstantMillis("2026-08-31T11:27:54.5Z"))
    }

    @Test
    fun `a malformed value throws`() {
        for (bad in listOf("", "yesterday", "2026-08-31", "2026-08-31 13:27:54Z", "2026-13-31T00:00:00Z")) {
            assertFailsWith<DateTimeParseException>(bad) { parseGitLabInstantMillis(bad) }
        }
    }

    @Test
    fun `isoInstant is the UTC text GitLab's since takes and round-trips`() {
        assertEquals("2026-08-31T11:27:54Z", isoInstant(expected))
        assertEquals(expected, parseGitLabInstantMillis(isoInstant(expected)))
    }
}
