package ch.nokillswit.gitlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ONE JSON configuration of the GitLab streams' `sync_cursors` text (the `JiraCursor.kt` shape): defaults written, unknown
 * keys ignored.
 */
private val CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal inline fun <reified T> encodeCursor(cursor: T): String = CURSOR_JSON.encodeToString(cursor)

internal inline fun <reified T> decodeCursor(text: String): T = CURSOR_JSON.decodeFromString(text)

/**
 * The `projects` stream's position inside one pass: [passStartedAt] (the sweep's reference instant), the index of the configured
 * group being read and the next page of it. Cleared when the pass ends.
 */
@Serializable
internal data class ProjectsCursor(val passStartedAt: Long, val groupIndex: Int = 0, val page: Int = 1)

/** The `users` stream's position inside one pass: [passStartedAt] and the next page of `/users`. Cleared when the pass ends. */
@Serializable
internal data class UsersCursor(val passStartedAt: Long, val page: Int = 1)
