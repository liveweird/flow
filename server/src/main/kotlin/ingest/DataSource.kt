package ch.nokillswit.ingest

import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.outbound.ATLASSIAN_RESERVED_SITE_LABELS
import ch.nokillswit.infra.outbound.JIRA_SITE_URL_PATTERN
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.canonicalEmail
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Data sources (v0.2.0 plan §3/§4/§9): an ADMIN-managed connection to an external system — Jira Cloud
 * (`kind = JIRA_CLOUD`) or a self-managed GitLab (`kind = GITLAB`, `GitLabDataSource.kt`), one table, queue and worker.
 * The kind-specific fields travel under `jira` or `gitlab` in the wire shape (exactly one, which sets the kind) and the
 * `settings` jsonb in storage (`ingest/DataSourceService.kt`) — one request/response shape serves create AND update, like
 * Covenant's `ToadieConnectionRequest` (`toadie/ToadieRoutes.kt`), since a data source's identity (Jira's `siteUrl`, GitLab's
 * `baseUrl`) is fixed at create and every other field is a full-replace PUT.
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
 * The connector kinds a data source can be. [derivesMetrics] is the ONE flag every Jira/metrics-only path gates on (DERIVE chaining and
 * fan-out, the report and Home connection scopes, the metrics-config routes): a kind whose data never feeds the `metrics` star is
 * `false` (`.claude/docs/ingestion.md` "Connector kinds").
 */
enum class DataSourceKind(val derivesMetrics: Boolean) {
    JIRA_CLOUD(derivesMetrics = true),
    GITLAB(derivesMetrics = false),
}

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
        "JiraConnectionRequest(siteUrl=$siteUrl, email=$email, apiToken=${maskedSecret(apiToken)}, " +
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
    /** Exactly one of [jira] and [gitlab] is present; the block sets the kind, which never changes after create. */
    val jira: JiraConnectionRequest? = null,
    val gitlab: GitLabConnectionRequest? = null,
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
    /** Epoch millis the ceiling-trip pause began (`SyncPause.kt`), null when scheduled syncing is not paused. */
    val pausedAt: Long? = null,
    /** Why it paused: a trip code (`LIMIT_EXCEEDED`, `TIME_EXCEEDED`); set exactly when [pausedAt] is. */
    val pauseReason: String? = null,
    /** Consecutive SYNC ceiling trips that made no progress; resets on a succeeded SYNC, "Sync now" or a PUT. */
    val limitTrips: Int = 0,
    /** Epoch millis the RECONCILE ceiling-trip pause began (`ReconcilePause.kt`), null when scheduled RECONCILE is not paused. */
    val reconcilePausedAt: Long? = null,
    /** Why it paused: a trip code (`LIMIT_EXCEEDED`, `TIME_EXCEEDED`); set exactly when [reconcilePausedAt] is. */
    val reconcilePauseReason: String? = null,
    /** Consecutive RECONCILE ceiling trips that stored no durable change; resets on a succeeded RECONCILE, "Reconcile now" or a PUT. */
    val reconcileLimitTrips: Int = 0,
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
    /** Present exactly for a [DataSourceKind.JIRA_CLOUD] source. */
    val jira: JiraConnectionResponse? = null,
    /** Present exactly for a [DataSourceKind.GITLAB] source. */
    val gitlab: GitLabConnectionResponse? = null,
    val status: DataSourceStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias DataSourcePageResponse = PageResponse<DataSourceResponse>

/** The ad-hoc Test-connection request body (`POST /api/v1/data-sources/test`) — exactly one connection block, incl. the token. */
@Serializable
data class DataSourceTestRequest(val jira: JiraConnectionRequest? = null, val gitlab: GitLabConnectionRequest? = null)

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

/**
 * What a `backfillFrom` write may name today (domain-model A48): [defaultFrom] is what a blank one becomes, [floor] the earliest day a
 * created or MOVED date may take. Both already include the legacy structural floor, so [defaultFrom] always passes [validateDataSource].
 */
data class BackfillPolicy(val defaultFrom: LocalDate, val floor: LocalDate)

/**
 * The policy for the history horizon's first day [horizonFrom] (null = the horizon is off): the floor is the later of the horizon and
 * the legacy 10-year floor, and the default is today minus [DEFAULT_BACKFILL_MONTHS] raised to that floor when the floor is later --
 * the horizon only ever RAISES the default, so a long horizon never silently widens a blank date into a longer backfill.
 */
fun backfillPolicyFor(horizonFrom: LocalDate?): BackfillPolicy {
    val floor = maxOf(backfillFloor(), horizonFrom ?: backfillFloor())
    val defaultFrom = LocalDate.now(ZoneOffset.UTC).minusMonths(DEFAULT_BACKFILL_MONTHS)
    return BackfillPolicy(defaultFrom = maxOf(defaultFrom, floor), floor = floor)
}

/**
 * The horizon floor on a sanitized, already-[validateDataSource]d request (which has refused anything before the legacy 10-year
 * floor, so a date failing here is one the history horizon refuses); `DataSourceService` calls it on create and on a moving update.
 */
fun requireBackfillNotBefore(request: DataSourceRequest, policy: BackfillPolicy) {
    if (LocalDate.parse(checkNotNull(request.backfillFrom)).isBefore(policy.floor)) {
        throw BadRequestException("backfillFrom must not be before ${policy.floor} (the history horizon)")
    }
}

/**
 * Trims/canonicalizes the scalars — the sanitizer convention (control characters are a 400); a blank `backfillFrom` becomes
 * [defaultFrom].
 */
fun sanitizedDataSourceRequest(request: DataSourceRequest, defaultFrom: LocalDate): DataSourceRequest = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    backfillFrom = sanitizeSingleLine(request.backfillFrom ?: defaultFrom.toString(), "Backfill date"),
    jira = request.jira?.let(::sanitizedJiraConnectionRequest),
    gitlab = request.gitlab?.let(::sanitizedGitLabConnectionRequest),
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

/**
 * The data-source rules — enforced by the route AND re-checked by the service (the `validateTeam` idiom);
 * both take an already-sanitized request. `backfillFrom` gets only the structural bounds here; the history-horizon floor
 * ([BackfillPolicy.floor]) reads another feature's setting and, on update, the stored date, so `DataSourceService` enforces it.
 */
fun validateDataSource(request: DataSourceRequest, apiTokenRequired: Boolean, gitLabPolicy: GitLabBaseUrlPolicy? = null) {
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
    when (request.kindOrBadRequest()) {
        DataSourceKind.JIRA_CLOUD -> validateJira(checkNotNull(request.jira), apiTokenRequired)
        DataSourceKind.GITLAB -> validateGitLabConnection(checkNotNull(request.gitlab), apiTokenRequired, gitLabPolicy)
    }
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
