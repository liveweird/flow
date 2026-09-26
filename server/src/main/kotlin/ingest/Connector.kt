package ch.nokillswit.ingest

/**
 * A connector kind's behavior (v0.2.0 plan §6/§12 item 4; Covenant's `toadie/` connector shape,
 * ported): `kind` identifies which [DataSourceKind] it serves, and [testConnection] is the ONE
 * outbound call the web role makes directly (`.claude/docs/ingestion.md` "Roles") — bounded to
 * ~30s total (`jira/JiraConnector.kt`), never throwing on a probe failure (every outcome is a
 * [ConnectionTestRow]). `streams(job)` — the sync-job stream runner — arrives with the sync-job
 * queue and worker (plan commit 5); no `Job`/stream model exists yet, so it is intentionally
 * omitted here rather than stubbed against a type that doesn't exist.
 */
interface Connector {
    val kind: DataSourceKind

    suspend fun testConnection(
        siteUrl: String,
        email: String,
        apiToken: String,
        projectKeys: List<String>,
        authScheme: JiraAuthScheme,
    ): ConnectionTestResult
}
