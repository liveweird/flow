package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsSettingsResponse
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
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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

/**
 * The data stamp: everything a report's answer can change with, apart from the request itself — computed BEFORE the report
 * query, so a DERIVE finishing in between can only make the stamp OLDER than the body (a later request mismatches and gets
 * a fresh 200), never newer. In one cheap read-only transaction:
 *
 * - the live `metrics.settings.config_revision` (every settings, per-connection config and membership edit bumps it, and
 *   those are the live reads the data-quality and WIP reports make);
 * - the newest SUCCEEDED derive run — id, `finished_at`, `config_revision` — of EVERY active connection, whatever the request's
 *   `connectionId`: the person names reports and `/filters` join live (`norm.people`) are read across connections, so a narrowed
 *   report still depends on another connection's data (every SYNC chains a DERIVE, `.claude/docs/ingestion.md`);
 * - the active connections' ids and names and the active teams' ids, names and `updated_at` (labels and existence checks no
 *   DERIVE rewrites: `400 Unknown or inactive teamId/connectionId`, group labels);
 * - the clock token ([clockToken]).
 */
internal suspend fun ReportService.dataStamp(
    settings: MetricsSettingsResponse,
    clock: StampClock,
    nowMs: Long,
): List<String> = suspendTransaction(database) {
    val c = DataSourceService.Connections
    val connections = c.select(c.id, c.name).where { c.active() }.orderBy(c.id).toList()
        .map { it[c.id].value to it[c.name] }
    val t = TeamService.Teams
    val teams = t.select(t.id, t.name, t.updatedAt).where { t.active() }.orderBy(t.id).toList()
    val runs = newestSucceededRuns(connections.map { it.first })
    buildList {
        add("settings:${settings.configRevision}")
        add("clock:${clockToken(clock, WorkingCalendar.of(settings), nowMs)}")
        connections.forEach { add("connection:${it.first}:${it.second}") }
        teams.forEach { add("team:${it[t.id].value}:${it[t.name]}:${it[t.updatedAt]}") }
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

/** Computes the ETag of the current request (path + query) over a fresh [dataStamp]. */
internal suspend fun ReportService.etagFor(
    call: ApplicationCall,
    settings: MetricsSettingsResponse,
    clock: StampClock,
    nowMs: Long,
): String = reportEtag(codeIdentity, call.request.path(), call.request.queryParameters, dataStamp(settings, clock, nowMs))

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
