package ch.nokillswit.infra.time

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * The ISO-8601 local date-time with ANY offset spelling an upstream sends: a colon offset (`+01:00`), a colonless one (`+0100`,
 * what Jira Cloud's REST API v3 returns), a bare hour offset (`+01`) or `Z`. `Instant.parse` accepts only `Z` and the colon form,
 * so it must never be called on upstream text.
 *
 * `DateTimeFormatter.ISO_LOCAL_DATE_TIME` parses the `yyyy-MM-dd'T'HH:mm:ss[.SSS...]` local part (fractional seconds are already
 * optional and variable-width there); the bracketed `[XXX][XX][X]` offset patterns are tried in order - `XXX` (`+01:00`/`Z`),
 * then `XX` (`+0100`/`Z`), then `X` (`+01`/`Z`) - so all of them resolve to the same [OffsetDateTime].
 */
val ISO_OFFSET_INSTANT: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    .appendPattern("[XXX][XX][X]")
    .toFormatter()

/**
 * Parses [text] through [ISO_OFFSET_INSTANT]. A malformed value throws `java.time.format.DateTimeParseException`, the same
 * unchecked exception `Instant.parse` throws, so a caller's failure handling does not depend on which parser it uses.
 */
fun parseIsoOffsetInstant(text: String): Instant = OffsetDateTime.parse(text, ISO_OFFSET_INSTANT).toInstant()
