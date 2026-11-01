package ch.nokillswit.reports

import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 21's own cache-validator lines (`.claude/docs/reports.md` "Cache validators"). The report reads `norm.code_*` and the identity
 * links LIVE, which no DERIVE stamps, so its tag also carries the GitLab inputs. They are added to the code-activity operation ONLY: a
 * Jira report's tag must not move on a link write or a GitLab PROCESS page (`ReportValidatorInputsTest`).
 */

/**
 * The extra stamp lines, in one cheap read-only [reportTransaction], empty while no GitLab source is active:
 *
 * - `code:<id>:<name>:<host>:<lastSyncSucceededAt>:<backfillFrom>` per ACTIVE GitLab source (what `sources[]` prints, and a source's
 *   appearance, rename, re-pointing or new backfill start);
 * - `code-ingest:<id>:<newest job id>:<last heartbeat>:<last finish>` over their SYNC / RECONCILE / REPROCESS / PURGE jobs: a PROCESS
 *   commits page by page inside a SYNC, and the heartbeat moves after each committed page (the Jira `ingest:` line's reasoning);
 * - `links:<host>:<digest>` per distinct host, the md5 of the host's decisions in key order (key, decision, account, `decided_at`): a link
 *   PUT, DELETE or confirm-proposals changes it, and nothing bumps a revision for it. Not a count and a newest instant: `decided_at` is
 *   taken before the commit, so with several pods two different states can share both.
 */
internal suspend fun ReportService.codeStamp(): List<String> = reportTransaction {
    val deps = codeDeps ?: return@reportTransaction emptyList()
    val urls = deps.dataSources.activeGitLabBaseUrls()
    if (urls.isEmpty()) return@reportTransaction emptyList()
    val c = DataSourceService.Connections
    val sources = c.select(c.id, c.name, c.lastSyncSucceededAt, c.backfillFrom).where { c.id inList urls.keys }.orderBy(c.id).toList()
    val j = SyncJobsService.Jobs
    val ingestKinds = listOf(SyncJobKind.SYNC, SyncJobKind.RECONCILE, SyncJobKind.REPROCESS, SyncJobKind.PURGE).map { it.name }
    val newestId = j.id.max()
    val lastHeartbeat = j.heartbeatAt.max()
    val lastFinish = j.finishedAt.max()
    val jobs = j.select(j.connectionId, newestId, lastHeartbeat, lastFinish)
        .where { (j.kind inList ingestKinds) and (j.connectionId inList urls.keys) }
        .groupBy(j.connectionId).orderBy(j.connectionId).toList()
    val hosts = urls.values.map { sourceHostOf(it) }.distinct().sorted()
    val links = linkDigests(hosts)
    buildList {
        sources.forEach {
            val id = it[c.id].value
            add("code:$id:${it[c.name]}:${sourceHostOf(urls.getValue(id))}:${it[c.lastSyncSucceededAt]}:${it[c.backfillFrom]}")
        }
        jobs.forEach { add("code-ingest:${it[j.connectionId].value}:${it[newestId]}:${it[lastHeartbeat]}:${it[lastFinish]}") }
        hosts.forEach { host -> add("links:$host:${links[host] ?: "none"}") }
    }
}

/**
 * The md5 of each of [hosts]' stored decisions (`public.code_identity_links`), in key order; a host with none is absent. One grouped
 * statement. The hosts are canonical authorities (`sourceHostOf`) and are quoted all the same.
 */
private suspend fun R2dbcTransaction.linkDigests(hosts: List<String>): Map<String, String> {
    val quoted = hosts.joinToString(",") { "'" + it.replace("'", "''") + "'" }
    val rows: Flow<Pair<String, String>?>? = exec(
        """
        SELECT source_host,
               md5(string_agg(identity_key || ':' || decision || ':' || coalesce(jira_account_id, '') || ':' || decided_at, ','
                              ORDER BY identity_key))
        FROM code_identity_links WHERE source_host IN ($quoted) GROUP BY source_host
        """.trimIndent(),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, String::class.java)!! to row.get(1, String::class.java)!! }
    return rows?.toList()?.filterNotNull().orEmpty().toMap()
}
