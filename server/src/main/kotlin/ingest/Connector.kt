package ch.nokillswit.ingest

import io.ktor.util.AttributeKey

/**
 * A connector kind's behavior (v0.2.0 plan §6/§12 item 4; Covenant's `toadie/` connector shape,
 * ported): `kind` identifies which [DataSourceKind] it serves, and [testConnection] is the ONE
 * outbound call the web role makes directly (`.claude/docs/ingestion.md` "Roles") — bounded to
 * ~30s total (`jira/JiraConnector.kt`), never throwing on a probe failure (every outcome is a
 * [ConnectionTestRow]).
 *
 * [run] is the sync-job stream runner (`ingest/IngestWorker.kt`'s claim loop calls it once per
 * claimed job): a no-op success for every [SyncJobKind] today — the actual streams (REFERENCE,
 * ISSUES, CHANGELOGS, WORKLOGS, RECONCILE, PROCESS, PROFILE) land in plan commits 6-9. It must
 * call [SyncJobRunContext.heartbeat] at least once per unit of work once real streams exist (the
 * lease/heartbeat contract, `.claude/docs/ingestion.md` "Worker scheduler") and honour
 * [SyncJobRunContext] cancellation cooperatively — for a no-op run there is nothing to honour yet.
 * [purgeSteps] is the extension point A2's PURGE job drains beyond the generic connection
 * housekeeping IngestWorker itself performs — empty until V10's raw tables (and V12's normalized
 * tables) exist to delete from.
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

    suspend fun run(context: SyncJobRunContext) {
        // No streams exist yet (plan commits 6-9) — every job kind succeeds trivially.
    }

    val purgeSteps: List<PurgeStep>
        get() = emptyList()
}

/**
 * One connector-owned cleanup step a PURGE job runs for a soft-deleted connection (plan §0 A2) —
 * e.g. a future "delete this connector's raw rows in batches" step, once V10 lands.
 */
fun interface PurgeStep {
    suspend fun purge(connectionId: UInt)
}

/**
 * What a claimed job hands its connector: the claim itself plus a [heartbeat] callback
 * (`ingest/SyncJobs.kt`'s `heartbeat`, pre-bound to this job/worker) a real stream calls after
 * every page write. `false` means the lease is already lost — the connector must stop without
 * touching cursors (`ingest/IngestWorker.kt`'s ticker independently enforces the same contract).
 */
class SyncJobRunContext(val claim: SyncJobClaim, val heartbeat: suspend () -> Boolean)

/** Every registered connector, keyed by the [DataSourceKind] it serves — populated by `jira/Jira.kt`'s `configureJira`. */
val ConnectorRegistryKey = AttributeKey<Map<DataSourceKind, Connector>>("ConnectorRegistry")
