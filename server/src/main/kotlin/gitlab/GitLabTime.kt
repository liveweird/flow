package ch.nokillswit.gitlab

import ch.nokillswit.infra.time.parseIsoOffsetInstant
import java.time.Instant

// GitLab writes ISO-8601 instants with a colon offset (`2026-08-31T13:27:54.000+02:00`, or `Z`); `Instant.parse` must never be called on
// upstream text, so every GitLab timestamp goes through the shared offset-tolerant parser (`infra/time/IsoOffsetInstant.kt`).

/** A GitLab timestamp text as epoch millis; a malformed value throws `java.time.format.DateTimeParseException`. */
fun parseGitLabInstantMillis(text: String): Long = parseIsoOffsetInstant(text).toEpochMilli()

/** [millis] as the UTC instant text GitLab's `since` parameter takes (`2026-08-31T11:27:54Z`). */
fun isoInstant(millis: Long): String = Instant.ofEpochMilli(millis).toString()
