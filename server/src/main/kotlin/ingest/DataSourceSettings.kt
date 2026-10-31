package ch.nokillswit.ingest

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.ingest.DataSourceService.Connections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow

// ignoreUnknownKeys: rolling-deploy tolerance — an older replica must not 500 decoding a
// `settings` payload a newer replica already wrote with an extra field.
private val settingsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * The `settings` jsonb payload, one shape per [DataSourceKind]: the decoder picks the shape from the row's `kind` ([decodeSettings]), never
 * by guessing from the JSON and never by assuming Jira - no discriminator is stored, so Jira's stored JSON is byte-for-byte what it was
 * before a second kind existed.
 */
internal sealed interface ConnectionSettings

/** Jira's fields (`kind = JIRA_CLOUD`); the stored shape is unchanged since V8. */
@Serializable
internal data class JiraConnectionSettings(
    val siteUrl: String,
    val email: String,
    val projectKeys: List<String>,
    val authScheme: JiraAuthScheme,
    val cloudId: String? = null,
) : ConnectionSettings

/**
 * GitLab's fields (`kind = GITLAB`): the instance origin and the group paths to read, plus the optional path-exclusion globs
 * (`gitlab-integration.md` "Path exclusions"; null = the built-in defaults). The GitLab data-source release fills the rest in.
 */
@Serializable
internal data class GitLabConnectionSettings(
    val baseUrl: String,
    val groupPaths: List<String>,
    val pathExclusions: List<String>? = null,
) : ConnectionSettings

/** The host only — never the full URL — for audit lines (`.claude/docs/security.md`). */
internal fun siteHost(siteUrl: String): String = java.net.URI(siteUrl).host

internal fun encodeSettings(settings: ConnectionSettings): String = canonicalJson(
    when (settings) {
        is JiraConnectionSettings -> settingsJson.encodeToString(settings)
        is GitLabConnectionSettings -> settingsJson.encodeToString(settings)
    },
)

/** The settings of a row of [kind], decoded as THAT kind's shape. */
internal fun decodeSettings(kind: DataSourceKind, raw: String): ConnectionSettings = when (kind) {
    DataSourceKind.JIRA_CLOUD -> settingsJson.decodeFromString<JiraConnectionSettings>(raw)
    DataSourceKind.GITLAB -> settingsJson.decodeFromString<GitLabConnectionSettings>(raw)
}

/** The row's `kind` column as the enum. */
internal fun ResultRow.dataSourceKind(): DataSourceKind = DataSourceKind.valueOf(this[Connections.kind])

/** The row's settings in the shape its own kind declares. */
internal fun ResultRow.connectionSettings(): ConnectionSettings = decodeSettings(dataSourceKind(), this[Connections.settings])

/**
 * The row's settings as Jira's, for a path that only ever serves Jira rows. A row of any other kind is a bug in the caller's kind gate
 * (it must filter or reject before it gets here), so this fails loudly rather than reading another kind's JSON as Jira.
 */
internal fun ResultRow.jiraSettings(): JiraConnectionSettings =
    connectionSettings() as? JiraConnectionSettings
        ?: error("Data source ${this[Connections.id].value} is ${dataSourceKind()}, not ${DataSourceKind.JIRA_CLOUD}")
