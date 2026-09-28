package ch.nokillswit.jira

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * The ONE shared timestamp parser for every Jira-sourced instant string in this codebase (v0.3.0
 * M1 — the real-tenant blocker from phase 2). `java.time.Instant.parse` only accepts strict
 * ISO-8601 (a `Z` or colon-delimited `+HH:MM` offset) — it REJECTS the form Jira Cloud's REST API
 * v3 actually returns for `created`/`updated`/`resolutiondate` on an issue, a changelog history's
 * own `created`, and a worklog's `started`/`created`/`updated`:
 * `yyyy-MM-dd'T'HH:mm:ss.SSSZ`, i.e. an offset with NO colon (`+0000`) — verified against a real
 * tenant: `Instant.parse("2024-01-15T10:20:30.123+0000")` throws
 * `DateTimeParseException: ... could not be parsed at index 23`. The Agile API (a sprint's
 * `startDate`/`endDate`/`completeDate`) returns the ordinary colon-offset/`Z` ISO-8601 shape
 * instead — [JIRA_INSTANT_FORMATTER] accepts BOTH, so every stream/normalizer call site can read
 * either API's timestamps through the same function. See `.claude/docs/jira-integration.md`
 * "Timestamps" — never call `Instant.parse` directly on Jira-sourced text again.
 *
 * `DateTimeFormatter.ISO_LOCAL_DATE_TIME` parses the `yyyy-MM-dd'T'HH:mm:ss[.SSS...]` local part
 * (fractional seconds are already optional/variable-width there); the bracketed `[XXX][XX][X]`
 * offset patterns are tried in order — `XXX` (`+01:00`/`Z`), then `XX` (`+0100`/`Z`), then `X`
 * (`+01`/`Z`) — so a colon offset, a colonless offset and a bare `Z` all resolve to the same
 * [OffsetDateTime].
 */
private val JIRA_INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    .appendPattern("[XXX][XX][X]")
    .toFormatter()

/**
 * Parses a Jira-sourced timestamp string, accepting both the REST API v3 form (`+0000`, no colon)
 * and the Agile API/ordinary ISO-8601 form (`Z`, `+02:00`, `-0500`). A malformed value throws
 * `java.time.format.DateTimeParseException` — the SAME unchecked exception `Instant.parse` itself
 * threw before this parser existed, so every existing call site's failure handling is unchanged:
 * left uncaught, it fails the stream/job exactly as before; `jira/JiraProcessStream.kt`'s per-issue
 * `catch (failure: Exception)` still isolates it to that one issue.
 */
fun parseJiraInstant(text: String): Instant = OffsetDateTime.parse(text, JIRA_INSTANT_FORMATTER).toInstant()

/** Convenience for the overwhelmingly common `parseJiraInstant(text).toEpochMilli()` call shape. */
fun parseJiraInstantEpochMillis(text: String): Long = parseJiraInstant(text).toEpochMilli()
