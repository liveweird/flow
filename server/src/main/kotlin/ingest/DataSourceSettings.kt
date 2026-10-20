package ch.nokillswit.ingest

import ch.nokillswit.infra.json.canonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ignoreUnknownKeys: rolling-deploy tolerance — an older replica must not 500 decoding a
// `settings` payload a newer replica already wrote with an extra field.
private val settingsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** The `settings` jsonb payload — Jira's fields today; a GitLab kind adds its own shape later. */
@Serializable
internal data class JiraConnectionSettings(
    val siteUrl: String,
    val email: String,
    val projectKeys: List<String>,
    val authScheme: JiraAuthScheme,
    val cloudId: String? = null,
)

/** The host only — never the full URL — for audit lines (`.claude/docs/security.md`). */
internal fun siteHost(siteUrl: String): String = java.net.URI(siteUrl).host

internal fun encodeSettings(settings: JiraConnectionSettings): String =
    canonicalJson(settingsJson.encodeToString(settings))

internal fun decodeSettings(raw: String): JiraConnectionSettings = settingsJson.decodeFromString(raw)
