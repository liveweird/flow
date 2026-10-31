package ch.nokillswit.ingest

import ch.nokillswit.infra.maskedSecret

/**
 * A connector kind's behavior (v0.2.0 plan §6/§12 item 4; Covenant's `toadie/` connector shape,
 * ported): `kind` identifies which [DataSourceKind] it serves, and [testConnection] is the ONE
 * outbound call the web role makes directly (`.claude/docs/ingestion.md` "Roles") — bounded to
 * ~30s total (`jira/JiraConnector.kt`), never throwing on a probe failure (every outcome is a
 * [ConnectionTestRow]).
 *
 * [run] is the sync-job stream runner (`ingest/IngestWorker.kt`'s claim loop calls it once per
 * claimed job): it runs the job kind's ordered streams (`.claude/docs/ingestion.md` "Streams"; the
 * Jira connector's order lives in `jira/JiraConnector.kt`). It must call
 * [SyncJobRunContext.heartbeat] at least once per unit of work (the lease/heartbeat contract,
 * `.claude/docs/ingestion.md` "Worker scheduler") and honour [SyncJobRunContext] cancellation
 * cooperatively. [purgeSteps] is the extension point A2's PURGE job drains beyond the generic
 * connection housekeeping IngestWorker itself performs — each connector deletes its own raw and
 * normalized rows.
 */
interface Connector {
    val kind: DataSourceKind

    /** Probes [target]. A connector handles ONLY its own [ConnectionTarget] case (a mismatch is an internal error, never a route's). */
    suspend fun testConnection(target: ConnectionTarget): ConnectionTestResult

    suspend fun run(context: SyncJobRunContext) {
        // Default for a connector without streams: every job kind succeeds trivially.
    }

    val purgeSteps: List<PurgeStep>
        get() = emptyList()
}

/**
 * One connector-owned cleanup step a PURGE job runs for a soft-deleted connection (plan §0 A2) —
 * e.g. `jira/JiraConnector.kt`'s batched drains of its `raw.jira_*` and `norm.*` rows.
 */
fun interface PurgeStep {
    suspend fun purge(connectionId: UInt)
}

/**
 * What a claimed job hands its connector: the claim itself plus a [heartbeat] callback
 * (`ingest/SyncJobs.kt`'s `heartbeat`, pre-bound to this job/worker) a real stream calls after
 * every page write, carrying that stream's own progress-counter JSON and current stream name
 * (v0.2.0 plan §12 item 7, `ingest/Stream.kt`'s `StreamContext`) so they flush onto the job row
 * alongside the lease renewal. `false` means the lease is already lost — the connector must stop
 * without touching cursors (`ingest/IngestWorker.kt`'s ticker independently enforces the same
 * contract). [clock] is the SAME injectable clock `IngestWorker`/`StreamContext` themselves read —
 * `metrics/MetricsDeriver.kt`'s `derive()` is the one connector-agnostic consumer today (review
 * round 2a: it used to carry a SEPARATE constructor-level clock of its own, unreachable from a
 * test that only pins THIS context).
 */
class SyncJobRunContext(
    val claim: SyncJobClaim,
    val clock: () -> Long = System::currentTimeMillis,
    /** The run's wall-clock ceiling as a duration (`ingest.maxJobMinutes`; SYNC and RECONCILE), null for none - see [StreamContext]. */
    val maxRunMillis: Long? = null,
    /**
     * How long after the run starts it is cancelled in flight ([maxRunMillis] plus the grace, `ingest.hardStopGraceSeconds`), null for
     * never - see [StreamContext.withHardStop].
     */
    val hardStopMillis: Long? = null,
    val heartbeat: suspend (progress: String?, currentStream: String?) -> Boolean,
)

/**
 * What [Connector.testConnection] probes, one case per connector kind: the in-process material of a connection (an ad-hoc request body,
 * or a stored row with its token decrypted). Never serialized and never logged with its secret - each case masks it in `toString`.
 */
sealed interface ConnectionTarget {
    /** A Jira Cloud site: exactly the fields the Jira probe has always taken. */
    data class Jira(
        val siteUrl: String,
        val email: String,
        val apiToken: String,
        val projectKeys: List<String>,
        val authScheme: JiraAuthScheme,
    ) : ConnectionTarget {
        override fun toString(): String =
            "Jira(siteUrl=$siteUrl, email=$email, apiToken=${maskedSecret(apiToken)}, projectKeys=$projectKeys, authScheme=$authScheme)"
    }

    /** A self-managed GitLab instance: its origin, the read-only token and the group paths to read. */
    data class GitLab(val baseUrl: String, val token: String, val groupPaths: List<String>) : ConnectionTarget {
        override fun toString(): String = "GitLab(baseUrl=$baseUrl, token=${maskedSecret(token)}, groupPaths=$groupPaths)"
    }
}
