package ch.nokillswit.ingest

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.users.canonicalEmail
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Data sources (v0.2.0 plan §3/§4/§9): an ADMIN-managed connection to an external issue tracker —
 * Jira Cloud today (`kind = JIRA_CLOUD`), GitLab later reusing the same table, queue and worker.
 * The Jira-specific fields (site, service-account email, scoped API token, project scope, auth
 * scheme) travel under `jira` in the wire shape and the `settings` jsonb in storage
 * (`ingest/DataSourceService.kt`) — one request/response shape serves create AND update, like
 * Covenant's `ToadieConnectionRequest` (`toadie/ToadieRoutes.kt`), since a data source's identity
 * (`siteUrl`) is fixed at create and every other field is a full-replace PUT.
 */

const val MAX_DATA_SOURCE_NAME_LENGTH = 100
const val MAX_SITE_URL_LENGTH = 253
const val MAX_JIRA_EMAIL_LENGTH = 254
const val MAX_API_TOKEN_LENGTH = 1000
const val MIN_PROJECT_KEYS = 1
const val MAX_PROJECT_KEYS = 50
const val MIN_SYNC_INTERVAL_MINUTES = 5
const val MAX_SYNC_INTERVAL_MINUTES = 1440
const val MIN_RECONCILE_HOUR_UTC = 0
const val MAX_RECONCILE_HOUR_UTC = 23
const val DEFAULT_RECONCILE_HOUR_UTC = 3
const val DEFAULT_BACKFILL_MONTHS = 24L

/** The Jira Cloud project-key shape: one uppercase letter, then 1-9 uppercase letters/digits/underscores. */
val PROJECT_KEY_PATTERN: Regex = Regex("^[A-Z][A-Z0-9_]{1,9}$")

/**
 * The allow-list boundary (`.claude/docs/security.md` "Outbound HTTP calls"): a Jira Cloud site's
 * ORIGIN only — no path, no query, no port, no trailing slash — so this value can never carry
 * more than a host the outbound guard (arriving in plan commit 4) will pin. The single capturing
 * group is the tenant label, checked against [ATLASSIAN_RESERVED_SITE_LABELS] below.
 */
val JIRA_SITE_URL_PATTERN: Regex = Regex("^https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.atlassian\\.net$")

/**
 * Labels under `*.atlassian.net` that are Atlassian's OWN product/marketing/support surfaces, not
 * a customer tenant — they match [JIRA_SITE_URL_PATTERN]'s shape but must never be accepted as a
 * connection target (an admin fat-fingering `https://api.atlassian.net` would otherwise point the
 * outbound guard at Atlassian's own infrastructure instead of a tenant site).
 */
val ATLASSIAN_RESERVED_SITE_LABELS: Set<String> = setOf(
    "api", "id", "admin", "www", "auth", "start", "home", "status", "developer",
    "support", "community", "marketplace", "my", "team",
)

enum class DataSourceKind { JIRA_CLOUD }

enum class JiraAuthScheme { BASIC, BEARER }

enum class DataSourceState { NEVER_SYNCED, CURRENT, STALE, FAILED, DISABLED }

@Serializable
data class JiraConnectionRequest(
    val siteUrl: String,
    val email: String,
    /** Required on create; omitted or blank on update keeps the current token, present rotates it. */
    val apiToken: String? = null,
    val projectKeys: List<String>,
    val authScheme: JiraAuthScheme = JiraAuthScheme.BASIC,
) {
    /** A default data-class `toString()` would print the token in plain text into any log line. */
    override fun toString(): String =
        "JiraConnectionRequest(siteUrl=$siteUrl, email=$email, apiToken=${apiToken?.let { "***" }}, " +
            "projectKeys=$projectKeys, authScheme=$authScheme)"
}

@Serializable
data class DataSourceRequest(
    val name: String,
    val enabled: Boolean = true,
    val syncIntervalMinutes: Int,
    /** ISO date (YYYY-MM-DD); null computes today minus 24 months. */
    val backfillFrom: String? = null,
    val reconcileHourUtc: Int = DEFAULT_RECONCILE_HOUR_UTC,
    val jira: JiraConnectionRequest,
)

@Serializable
data class JiraConnectionResponse(
    val siteUrl: String,
    val email: String,
    /** The token is write-only — never returned in any response or audit line. */
    val hasApiToken: Boolean,
    val projectKeys: List<String>,
    val authScheme: JiraAuthScheme,
    val cloudId: String? = null,
)

@Serializable
data class DataSourceStatus(
    val state: DataSourceState,
    val lastSyncStartedAt: Long? = null,
    val lastSyncSucceededAt: Long? = null,
    val lastSyncErrorCode: String? = null,
    val consecutiveFailures: Int,
    /** The connection's currently RUNNING sync job, if any (`ingest/SyncJobsService.runningJobId`). */
    val runningJobId: UInt? = null,
)

@Serializable
data class DataSourceResponse(
    val id: UInt,
    val kind: DataSourceKind,
    val name: String,
    val enabled: Boolean,
    val syncIntervalMinutes: Int,
    val backfillFrom: String,
    val reconcileHourUtc: Int,
    val configRevision: Long,
    val jira: JiraConnectionResponse,
    val status: DataSourceStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias DataSourcePageResponse = PageResponse<DataSourceResponse>

/** The ad-hoc Test-connection request body (`POST /api/v1/data-sources/test`) — `jira` alone, incl. the token. */
@Serializable
data class DataSourceTestRequest(val jira: JiraConnectionRequest)

/** One probed endpoint's outcome (v0.2.0 plan §6 `JiraConnector.testConnection`). Never carries the token. */
@Serializable
data class ConnectionTestRow(
    val name: String,
    val path: String,
    val required: Boolean,
    val ok: Boolean,
    val status: Int? = null,
    val code: String? = null,
    val scopeHint: String? = null,
)

/** The full Test-connection outcome; `cloudId` is populated once `tenant_info` succeeds. */
@Serializable
data class ConnectionTestResult(
    val rows: List<ConnectionTestRow>,
    val cloudId: String? = null,
)

data class DataSourceListFilter(val name: String? = null)

data class DataSourceListResult(val items: List<DataSourceResponse>, val total: Long)

/** Today minus [DEFAULT_BACKFILL_MONTHS], UTC — the default `backfillFrom` when none is given. */
fun defaultBackfillFrom(): String = LocalDate.now(ZoneOffset.UTC).minusMonths(DEFAULT_BACKFILL_MONTHS).toString()

/**
 * The wire shape `backfillFrom` must match BEFORE `LocalDate.parse` ever sees it — parse alone
 * accepts signed years (`-0001-01-01`), which then 500s on the `VARCHAR(10)` column.
 */
private val ISO_DATE_PATTERN: Regex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** How many years back of `backfillFrom` are ever allowed, on top of the fixed 2000-01-01 floor. */
private const val MAX_BACKFILL_YEARS_BACK = 10L

/**
 * The floor a `backfillFrom` date must not precede: the LATER (stricter) of a fixed 2000-01-01
 * and [MAX_BACKFILL_YEARS_BACK] years before today. A fixed 2000 floor alone would only get
 * looser every year as "N years back" moves forward past it, so the two are combined and
 * whichever bound is stricter (closer to today) wins — today that is always the rolling one.
 */
private fun backfillFloor(): LocalDate =
    maxOf(LocalDate.of(2000, 1, 1), LocalDate.now(ZoneOffset.UTC).minusYears(MAX_BACKFILL_YEARS_BACK))

/** Trims/canonicalizes the scalars — the sanitizer convention (control characters are a 400). */
fun sanitizedDataSourceRequest(request: DataSourceRequest): DataSourceRequest = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    backfillFrom = sanitizeSingleLine(request.backfillFrom ?: defaultBackfillFrom(), "Backfill date"),
    jira = sanitizedJiraConnectionRequest(request.jira),
)

/**
 * The `jira` block alone, sanitized the same way [sanitizedDataSourceRequest] does — shared with
 * the ad-hoc Test-connection request (`POST /api/v1/data-sources/test`), which carries no other
 * `DataSourceRequest` field.
 */
fun sanitizedJiraConnectionRequest(jira: JiraConnectionRequest): JiraConnectionRequest = jira.copy(
    siteUrl = sanitizeSingleLine(jira.siteUrl, "Site URL"),
    email = canonicalEmail(sanitizeSingleLine(jira.email, "Email")),
    // Blank is the SPA's "unchanged" signal for a masked password-style field (same as an
    // omitted/null token) — never a 1-char token, so fold it to null before validation.
    apiToken = jira.apiToken?.let { sanitizeSingleLine(it, "API token") }?.takeIf { it.isNotBlank() },
    projectKeys = jira.projectKeys.map { sanitizeSingleLine(it, "Project key") }.distinct(),
)

/** The data-source rules — enforced by the route; the service takes an already-validated request and does not re-check. */
fun validateDataSource(request: DataSourceRequest, apiTokenRequired: Boolean) {
    if (request.name.isBlank() || request.name.length > MAX_DATA_SOURCE_NAME_LENGTH) {
        throw BadRequestException("Name must be 1-$MAX_DATA_SOURCE_NAME_LENGTH characters")
    }
    if (request.syncIntervalMinutes < MIN_SYNC_INTERVAL_MINUTES || request.syncIntervalMinutes > MAX_SYNC_INTERVAL_MINUTES) {
        throw BadRequestException("syncIntervalMinutes must be between $MIN_SYNC_INTERVAL_MINUTES and $MAX_SYNC_INTERVAL_MINUTES")
    }
    if (request.reconcileHourUtc < MIN_RECONCILE_HOUR_UTC || request.reconcileHourUtc > MAX_RECONCILE_HOUR_UTC) {
        throw BadRequestException("reconcileHourUtc must be between $MIN_RECONCILE_HOUR_UTC and $MAX_RECONCILE_HOUR_UTC")
    }
    val backfillFrom = requireNotNull(request.backfillFrom) { "backfillFrom must be sanitized before validation" }
    if (!ISO_DATE_PATTERN.matches(backfillFrom)) {
        throw BadRequestException("backfillFrom must be an ISO date (YYYY-MM-DD)")
    }
    val parsedBackfillFrom = runCatching { LocalDate.parse(backfillFrom) }.getOrElse {
        throw BadRequestException("backfillFrom must be an ISO date (YYYY-MM-DD)")
    }
    if (parsedBackfillFrom.isAfter(LocalDate.now(ZoneOffset.UTC))) {
        throw BadRequestException("backfillFrom must not be in the future")
    }
    if (parsedBackfillFrom.isBefore(backfillFloor())) {
        throw BadRequestException("backfillFrom must not be before ${backfillFloor()}")
    }
    validateJira(request.jira, apiTokenRequired)
}

/** `internal`, not `private`: the Test-connection routes (`DataSourceRoutes.kt`) validate an ad-hoc `jira` block directly. */
internal fun validateJira(jira: JiraConnectionRequest, apiTokenRequired: Boolean) {
    val siteLabel = jira.siteUrl.takeIf { it.length <= MAX_SITE_URL_LENGTH }
        ?.let { JIRA_SITE_URL_PATTERN.matchEntire(it) }?.groupValues?.get(1)
    if (siteLabel == null || siteLabel in ATLASSIAN_RESERVED_SITE_LABELS) {
        throw BadRequestException("siteUrl must be exactly https://<site>.atlassian.net (no path, query or trailing slash)")
    }
    if (jira.email.isBlank() || jira.email.length > MAX_JIRA_EMAIL_LENGTH || '@' !in jira.email) {
        throw BadRequestException("email must be a valid address of at most $MAX_JIRA_EMAIL_LENGTH characters")
    }
    if (apiTokenRequired && jira.apiToken.isNullOrBlank()) {
        throw BadRequestException("apiToken is required")
    }
    jira.apiToken?.let {
        if (it.isBlank() || it.length > MAX_API_TOKEN_LENGTH) {
            throw BadRequestException("apiToken must be 1-$MAX_API_TOKEN_LENGTH characters")
        }
    }
    if (jira.projectKeys.size !in MIN_PROJECT_KEYS..MAX_PROJECT_KEYS) {
        throw BadRequestException("projectKeys must contain $MIN_PROJECT_KEYS-$MAX_PROJECT_KEYS keys")
    }
    jira.projectKeys.forEach { key ->
        if (!PROJECT_KEY_PATTERN.matches(key)) {
            throw BadRequestException("Invalid project key: $key (must match ${PROJECT_KEY_PATTERN.pattern})")
        }
    }
}
