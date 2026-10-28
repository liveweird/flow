package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamWorkCategoryTargetService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.plugins.offerValidator
import ch.nokillswit.teams.TeamService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * Cache validators for the report endpoints (`.claude/docs/reports.md` "Cache validators"): every report GET answers with a
 * weak `ETag` computed from the request and a data stamp BEFORE the report query runs, and a matching `If-None-Match`
 * short-circuits to `304` without running it. A report's content depends only on the query and the derived data — never on
 * WHO asks (D12) — so the validator carries no caller identity. The headers themselves are written by
 * `plugins/ResponseValidators.kt`, only onto a call that really ends as a `200`/`304`.
 */

private const val FIVE_MINUTES_MS = 5 * 60 * 1000L

/**
 * Identifies the running code in every report ETag, so a deploy that changes a report's wire shape or computation can never be
 * answered `304` against a body the old code produced: the sha256 of the server's own code artifact (the jar under
 * `installDist`), or — where the code source is a directory (`:server:run`, tests) — a random id per boot. Computed once.
 */
internal val REPORT_CODE_IDENTITY: String = codeIdentityOf(ReportService::class.java.protectionDomain?.codeSource?.location?.toURI())

/** [REPORT_CODE_IDENTITY]'s rule for one code-source [location]: a file's sha256 (streamed), anything else a fresh random id. */
internal fun codeIdentityOf(location: URI?): String {
    val file = runCatching { location?.let { File(it) } }.getOrNull()?.takeIf { it.isFile }
    if (file == null) return "boot-${UUID.randomUUID()}"
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return "jar-" + digest.digest().joinToString("") { "%02x".format(it) }
}

private const val DIGEST_BUFFER_BYTES = 64 * 1024

/**
 * How finely `now` enters a report's stamp. [DAY] (the configured zone's calendar day) fits every report whose figures move only
 * with the DERIVE data and the local date (default periods, "as of today"). [FIVE_MINUTES] is for the figures that move
 * continuously with the clock: Aging WIP's fractional ages, Data quality's member-days clipped at `now`, and the filters'
 * "current" roster (a membership can end at any instant).
 */
internal enum class StampClock { DAY, FIVE_MINUTES }

/** The clock part of the stamp: the local calendar day in the configured zone, or the five-minute bucket since the epoch. */
internal fun clockToken(clock: StampClock, calendar: WorkingCalendar, nowMs: Long): String = when (clock) {
    StampClock.DAY -> calendar.dayOf(nowMs).toString()
    StampClock.FIVE_MINUTES -> (nowMs / FIVE_MINUTES_MS).toString()
}

/** One connection's newest SUCCEEDED derive run - the facts the data stamp is built from. */
internal data class NewestRun(val connectionId: UInt, val runId: Int, val finishedAt: Long?, val configRevision: Long)

/**
 * The newest SUCCEEDED `derive_runs` row of each of [connectionIds] (a connection with none is absent): ONE `DISTINCT ON
 * (connection_id)` query ordered `connection_id, id DESC` - the newest by the monotonic run id, never by the worker-supplied
 * `started_at`, and never the run history. Runs in the caller's transaction.
 */
internal suspend fun newestSucceededRuns(connectionIds: List<UInt>): List<NewestRun> {
    if (connectionIds.isEmpty()) return emptyList()
    val runs = MetricsTables.DeriveRuns
    return runs.select(runs.id, runs.connectionId, runs.finishedAt, runs.configRevision)
        .where { (runs.status eq DERIVE_RUN_SUCCEEDED) and (runs.connectionId inList connectionIds.map { it.toInt() }) }
        .withDistinctOn(runs.connectionId)
        .orderBy(runs.connectionId to SortOrder.ASC, runs.id to SortOrder.DESC)
        .toList().map { NewestRun(it[runs.connectionId].toUInt(), it[runs.id], it[runs.finishedAt], it[runs.configRevision]) }
}

/**
 * The data stamp: everything a report's answer can change with, apart from the request itself - computed BEFORE the report
 * query, so a DERIVE finishing in between can only make the stamp OLDER than the body (a later request mismatches and gets
 * a fresh 200), never newer. In one cheap read-only [reportTransaction]:
 *
 * - the live `metrics.settings.config_revision` ([settings], read by the route): every settings, per-connection config (status/stage,
 *   board-team, domain, activity-type, work-category maps), sprint-calendar, team-capacity, public-holiday and team-membership write bumps
 *   it in its own transaction, and those are the inputs the reports read at QUERY time (the working calendar, `readWithCalendar`;
 *   the Costs capacity; the data-quality and WIP config reads);
 * - the newest SUCCEEDED derive run - id, `finished_at`, `config_revision` - of EVERY active connection, whatever the request's
 *   `connectionId`: person names (`norm.people`) are read across connections, so a narrowed report still depends on another
 *   connection's data (every SYNC chains a DERIVE, `.claude/docs/ingestion.md`);
 * - each connection's ingest activity: the newest SYNC/RECONCILE/REPROCESS/PURGE job id and its latest `heartbeat_at`/`finished_at`.
 *   Several reports read `raw.*`/`norm.*` LIVE (Backlog's `touchedWithinDays`, WIP's USER level and status/column grouping, the deep
 *   dive's titles, every person name), those tables are written page by page DURING a SYNC, and a SYNC that fails after committing
 *   pages chains no DERIVE, so the derive run alone would leave a `304` stale; the heartbeat moves after each committed page, which
 *   also covers a run in progress;
 * - the active connections' ids, names and `siteUrl` (what the reports print and `/filters` reads out of `settings`) and last
 *   successful sync (Home's freshness), plus a line per soft-deleted connection with its `purged_at` (names and `/filters` still read a
 *   deleted connection's people until its PURGE removes them);
 * - the active teams' ids, names and `updated_at` (existence - `400 Unknown or inactive teamId` - the name, the manager `/filters`
 *   prints; no DERIVE rewrites any of them);
 * - every team's work-category targets (name, percent, `updated_at`): the Costs report reads them at query time and their PUT bumps
 *   NO revision (they feed no DERIVE), so they enter the stamp themselves;
 * - the clock token ([clockToken]).
 *
 * `source_connections.updated_at` is deliberately NOT in it: it moves on every ADMIN edit (a token rotation too), which a USER could
 * read off a tag; only what a report prints is stamped.
 */
internal suspend fun ReportService.dataStamp(
    settings: MetricsSettingsResponse,
    calendar: WorkingCalendar,
    clock: StampClock,
    nowMs: Long,
): List<String> = reportTransaction {
    val c = DataSourceService.Connections
    val connections = c.select(c.id, c.name, c.settings, c.lastSyncSucceededAt).where { c.active() }.orderBy(c.id).toList()
    val retired = c.select(c.id, c.purgedAt).where { c.markedAsDeleted eq true }.orderBy(c.id).toList()
    val j = SyncJobsService.Jobs
    val ingestKinds = listOf(SyncJobKind.SYNC, SyncJobKind.RECONCILE, SyncJobKind.REPROCESS, SyncJobKind.PURGE).map { it.name }
    val newestId = j.id.max()
    val lastHeartbeat = j.heartbeatAt.max()
    val lastFinish = j.finishedAt.max()
    val ingest = j.select(j.connectionId, newestId, lastHeartbeat, lastFinish).where { j.kind inList ingestKinds }
        .groupBy(j.connectionId).orderBy(j.connectionId).toList()
    val t = TeamService.Teams
    val teams = t.select(t.id, t.name, t.updatedAt).where { t.active() }.orderBy(t.id).toList()
    val targets = TeamWorkCategoryTargetService.TeamWorkCategoryTargets
    val targetRows = targets.selectAll().orderBy(targets.teamId to SortOrder.ASC, targets.category to SortOrder.ASC).toList()
    val runs = newestSucceededRuns(connections.map { it[c.id].value })
    buildList {
        add("settings:${settings.configRevision}")
        add("clock:${clockToken(clock, calendar, nowMs)}")
        connections.forEach {
            add("connection:${it[c.id].value}:${it[c.name]}:${siteUrlOf(it[c.settings])}:${it[c.lastSyncSucceededAt]}")
        }
        retired.forEach { add("retired:${it[c.id].value}:${it[c.purgedAt]}") }
        ingest.forEach { add("ingest:${it[j.connectionId].value}:${it[newestId]}:${it[lastHeartbeat]}:${it[lastFinish]}") }
        teams.forEach { add("team:${it[t.id].value}:${it[t.name]}:${it[t.updatedAt]}") }
        targetRows.forEach {
            add("target:${it[targets.teamId].value}:${it[targets.category]}:${it[targets.targetPercent]}:${it[targets.updatedAt]}")
        }
        runs.forEach { add("run:${it.connectionId}:${it.runId}:${it.finishedAt}:${it.configRevision}") }
    }
}

/**
 * The report ETag: a WEAK validator (`W/"…"`) — the stamp promises "the same figures", not the same bytes (a bucketed `now`
 * can shift a fractional age inside one validator, and the response may be compressed) — over the [codeIdentity], the
 * route's path, the canonical query (parameters sorted by name, a repeated name's values in request order) and the [stamp]
 * lines. sha256, base64url, no padding.
 */
internal fun reportEtag(codeIdentity: String, path: String, query: Parameters, stamp: List<String>): String {
    val canonicalQuery = query.entries().sortedBy { it.key }.flatMap { (name, values) ->
        values.map { "${name.encodeURLParameter()}=${it.encodeURLParameter()}" }
    }.joinToString("&")
    val digest = MessageDigest.getInstance("SHA-256")
    // Length-prefixed fields: free text (a team name) can never blur into its neighbour.
    (listOf(codeIdentity, path, canonicalQuery) + stamp).forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
    }
    return "W/\"${Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())}\""
}

/**
 * Computes the ETag of the current request (path + query) over a fresh [dataStamp]. [callerShape] lists the facts about the CALLER the
 * answer is shaped by (today only Compare people's capacity verdict, [CapacityGrant]); every other report passes none, because its
 * content never depends on who asks (D12), so a validator is shared by every caller and a changed verdict can never answer `304`.
 */
internal suspend fun ReportService.etagFor(
    call: ApplicationCall,
    scope: StampScope,
    callerShape: List<String> = emptyList(),
): String = reportEtag(
    codeIdentity, call.request.path(), call.request.queryParameters,
    dataStamp(scope.settings, scope.calendar, scope.clock, scope.nowMs) + callerShape,
)

/** A report's response, built lazily: it runs only when the validator check did not answer `304` (see [reportGet]). */
internal typealias ReportAnswer = suspend () -> Any

/** Names the [ReportAnswer] a [reportGet] lambda returns, after it has parsed every parameter that needs no data. */
internal fun answer(block: ReportAnswer): ReportAnswer = block

/** Reads the settings with the working calendar (one transaction) and `now` from the service's clock: what a route stamps with. */
internal suspend fun ReportService.stampScope(clock: StampClock): StampScope {
    val (settings, calendar) = metricsSettings.readWithCalendar()
    return StampScope(settings, calendar, clock, this.clock())
}

/** What a route knows when it stamps: the settings and the working calendar read in one go, how finely `now` enters, and `now`. */
internal data class StampScope(val settings: MetricsSettingsResponse, val calendar: WorkingCalendar, val clock: StampClock, val nowMs: Long)

private val ENTITY_TAG = Regex("(?:W/)?\"[^\"]*\"")

/** What a request's `If-None-Match` says about the current ETag. */
internal enum class IfNoneMatch {
    /** No header, or none of the listed entity-tags matches. */
    NO_MATCH,

    /** A listed entity-tag matches WEAKLY (the opaque tags are equal, the `W/` prefix ignored): RFC 9110 §13.1.2. */
    TAG_MATCH,

    /** The `*` wildcard: true only if a current representation exists, i.e. only if the report itself would succeed. */
    ANY,
}

internal fun ifNoneMatch(headerLines: List<String>?, etag: String): IfNoneMatch {
    val ours = etag.removePrefix("W/")
    val lines = headerLines.orEmpty()
    return when {
        lines.any { line -> ENTITY_TAG.findAll(line).any { it.value.removePrefix("W/") == ours } } -> IfNoneMatch.TAG_MATCH
        lines.any { line -> line.split(',').any { it.trim() == "*" } } -> IfNoneMatch.ANY
        else -> IfNoneMatch.NO_MATCH
    }
}

/**
 * Answers a report request against its already-computed [etag]: `304` (no body, [produce] never runs) when `If-None-Match`
 * lists it, else `200` with the body [produce] builds. The `*` wildcard cannot be judged without the report, so it runs
 * [produce] first (a `400` still wins) and then answers `304` with the body dropped. The validator is only OFFERED here
 * (`plugins/ResponseValidators.kt` writes the headers onto a call that ends `200`/`304`, so a `400`/`500` never carries it).
 * Authorization has run before this (`call.caller()`), so an unauthenticated request never reaches it. Reified so a generic
 * body (a `PageResponse<…>`) keeps its type arguments for the serializer.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.respondRevalidated(etag: String, produce: suspend () -> T) {
    offerValidator(etag)
    val precondition = ifNoneMatch(request.headers.getAll(HttpHeaders.IfNoneMatch), etag)
    if (precondition == IfNoneMatch.TAG_MATCH) {
        respond(HttpStatusCode.NotModified)
        return
    }
    val body = produce()
    if (precondition == IfNoneMatch.ANY) respond(HttpStatusCode.NotModified) else respond(HttpStatusCode.OK, body)
}
