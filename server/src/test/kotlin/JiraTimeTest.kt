package ch.nokillswit

import ch.nokillswit.jira.parseJiraInstant
import ch.nokillswit.jira.parseJiraInstantEpochMillis
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `parseJiraInstant`/`parseJiraInstantEpochMillis` (v0.3.0 M1, the real-tenant phase-2 blocker):
 * `java.time.Instant.parse` rejects the REST API v3 form (`+0000`, no colon) Jira Cloud actually
 * returns — see `.claude/docs/jira-integration.md` "Timestamps".
 */
class JiraTimeTest {

    @Test
    fun `accepts the REST API v3 form with millis and a colonless offset`() {
        assertEquals(Instant.parse("2024-01-15T10:20:30.123Z"), parseJiraInstant("2024-01-15T10:20:30.123+0000"))
    }

    @Test
    fun `accepts the ordinary Z form`() {
        assertEquals(Instant.parse("2024-01-15T10:20:30.123Z"), parseJiraInstant("2024-01-15T10:20:30.123Z"))
    }

    @Test
    fun `accepts a colon offset ahead of UTC`() {
        assertEquals(Instant.parse("2024-01-15T08:20:30.123Z"), parseJiraInstant("2024-01-15T10:20:30.123+02:00"))
    }

    @Test
    fun `accepts a colonless offset behind UTC`() {
        assertEquals(Instant.parse("2024-01-15T15:20:30.123Z"), parseJiraInstant("2024-01-15T10:20:30.123-0500"))
    }

    @Test
    fun `accepts a form with no fractional seconds`() {
        assertEquals(Instant.parse("2024-01-15T10:20:30Z"), parseJiraInstant("2024-01-15T10:20:30+0000"))
    }

    @Test
    fun `the epoch millis convenience matches the instant form`() {
        assertEquals(
            Instant.parse("2024-01-15T10:20:30.123Z").toEpochMilli(),
            parseJiraInstantEpochMillis("2024-01-15T10:20:30.123+0000"),
        )
    }

    @Test
    fun `accepts the epoch millis changelog bulkfetch returns for a history's created`() {
        assertEquals(Instant.ofEpochMilli(1_790_330_188_061), parseJiraInstant("1790330188061"))
        assertEquals(1_790_330_188_061, parseJiraInstantEpochMillis("1790330188061"))
    }

    @Test
    fun `a malformed value throws the same unchecked exception Instant-parse itself would have`() {
        assertFailsWith<DateTimeParseException> { parseJiraInstant("not-a-timestamp") }
    }
}
