package ch.nokillswit.reports

import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
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
 * - `links:<host>:<count>:<newest decided_at>` per distinct host: a link PUT, DELETE or confirm-proposals moves the count or the newest
 *   instant, and nothing bumps a revision for it.
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
    val l = CodeIdentityLinkStore.Links
    val linkCount = l.identityKey.count()
    val newestDecision = l.decidedAt.max()
    val links = l.select(l.sourceHost, linkCount, newestDecision).where { l.sourceHost inList hosts }.groupBy(l.sourceHost).toList()
        .associateBy { it[l.sourceHost] }
    buildList {
        sources.forEach {
            val id = it[c.id].value
            add("code:$id:${it[c.name]}:${sourceHostOf(urls.getValue(id))}:${it[c.lastSyncSucceededAt]}:${it[c.backfillFrom]}")
        }
        jobs.forEach { add("code-ingest:${it[j.connectionId].value}:${it[newestId]}:${it[lastHeartbeat]}:${it[lastFinish]}") }
        hosts.forEach { host -> add("links:$host:${links[host]?.get(linkCount) ?: 0}:${links[host]?.get(newestDecision)}") }
    }
}
