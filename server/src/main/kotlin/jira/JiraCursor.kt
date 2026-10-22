package ch.nokillswit.jira

import kotlinx.serialization.json.Json

/**
 * The ONE JSON configuration of every Jira stream's `sync_cursors` text (issues, changelogs, worklogs, reconcile, reference): defaults are
 * written so a cursor stays self-describing, and unknown keys are ignored so a cursor written by a newer build still reads.
 */
internal val CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal inline fun <reified T> encodeCursor(cursor: T): String = CURSOR_JSON.encodeToString(cursor)

internal inline fun <reified T> decodeCursor(text: String): T = CURSOR_JSON.decodeFromString(text)
