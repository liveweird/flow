package ch.nokillswit.ingest

import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The GitLab half of the data-source wire (`DataSource.kt` has the common part and the Jira half): the instance origin, the
 * read-only personal access token and the group paths to read. The kind's connector lives in `gitlab/`; this file knows nothing
 * of it but the [GitLabBaseUrlPolicy] seam, which `configureGitLab` fills (the allow-list is the operator's, `gitlab.*`).
 */
const val MAX_GITLAB_BASE_URL_LENGTH = 300
const val MAX_GITLAB_TOKEN_LENGTH = 1000
const val MIN_GROUP_PATHS = 1
const val MAX_GROUP_PATHS = 10
const val MAX_GROUP_PATH_LENGTH = 255

/** One segment of a GitLab group path: lowercase letters, digits, `_`, `.` and `-`, not starting with a dot or a hyphen. */
val GROUP_SEGMENT_PATTERN: Regex = Regex("^[a-z0-9_][a-z0-9_.-]*$")

@Serializable
data class GitLabConnectionRequest(
    val baseUrl: String,
    /** Required on create; omitted or blank on update keeps the current token, present rotates it. */
    val token: String? = null,
    val groupPaths: List<String>,
) {
    /** A default data-class `toString()` would print the token in plain text into any log line. */
    override fun toString(): String = "GitLabConnectionRequest(baseUrl=$baseUrl, token=${maskedSecret(token)}, groupPaths=$groupPaths)"
}

@Serializable
data class GitLabConnectionResponse(
    val baseUrl: String,
    /** The token is write-only - never returned in any response or audit line. */
    val hasToken: Boolean,
    val groupPaths: List<String>,
)

/**
 * Canonicalises and allow-lists a GitLab base URL against the operator's `gitlab.allowedHosts` (and the development stub): returns the
 * canonical origin (lowercase host, default port dropped, no trailing slash) or throws the 400 whose text carries the stable refusal
 * code (`gitlab-disabled`, `gitlab-host-not-allowed`, ...). Registered by `configureGitLab` on `JobHandlerRegistry` (the
 * `BackfillFloorSource` seam shape), so `ingest/` never imports `gitlab/`'s validator.
 */
fun interface GitLabBaseUrlPolicy {
    fun canonicalOrigin(raw: String): String
}

/** The `jira`/`gitlab` block of a request, sanitized the same way [sanitizedDataSourceRequest] does it. */
fun sanitizedGitLabConnectionRequest(gitlab: GitLabConnectionRequest): GitLabConnectionRequest = gitlab.copy(
    baseUrl = sanitizeSingleLine(gitlab.baseUrl, "Base URL").removeSuffix("/"),
    // Blank is the SPA's "unchanged" signal for a masked password-style field (same as an omitted/null token).
    token = gitlab.token?.let { sanitizeSingleLine(it, "Token") }?.takeIf { it.isNotBlank() },
    groupPaths = normalizeGroupPaths(gitlab.groupPaths.map { sanitizeSingleLine(it, "Group path") }),
)

/** Trim, strip leading/trailing `/`, lowercase, fold duplicates (GitLab paths are case-insensitive). */
fun normalizeGroupPaths(paths: List<String>): List<String> = paths.map { it.trim().trim('/').lowercase() }.distinct()

/**
 * The GitLab rules, enforced by the route AND re-checked by the service. [policy] null = no GitLab on this server (a registry nobody
 * filled): every GitLab write is then a 400 `gitlab-disabled`.
 */
fun validateGitLabConnection(gitlab: GitLabConnectionRequest, tokenRequired: Boolean, policy: GitLabBaseUrlPolicy?) {
    if (gitlab.baseUrl.isEmpty() || gitlab.baseUrl.length > MAX_GITLAB_BASE_URL_LENGTH) {
        throw BadRequestException("baseUrl must be 1-$MAX_GITLAB_BASE_URL_LENGTH characters")
    }
    if (tokenRequired && gitlab.token.isNullOrBlank()) throw BadRequestException("token is required")
    gitlab.token?.let {
        if (it.isBlank() ||
            it.length > MAX_GITLAB_TOKEN_LENGTH) throw BadRequestException("token must be 1-$MAX_GITLAB_TOKEN_LENGTH characters")
    }
    if (gitlab.groupPaths.size !in MIN_GROUP_PATHS..MAX_GROUP_PATHS) {
        throw BadRequestException("groupPaths must contain $MIN_GROUP_PATHS-$MAX_GROUP_PATHS group paths")
    }
    gitlab.groupPaths.forEach { path ->
        val shaped = path.isNotEmpty() && path.length <= MAX_GROUP_PATH_LENGTH && path.split('/').all { GROUP_SEGMENT_PATTERN.matches(it) }
        if (!shaped) throw BadRequestException("Invalid group path: ${path.take(MAX_GROUP_PATH_LENGTH)}")
    }
    gitlab.groupPaths.forEach { path ->
        if (gitlab.groupPaths.any { other -> other != path && path.startsWith("$other/") }) {
            throw BadRequestException("groupPaths must not repeat or nest: $path")
        }
    }
    if (policy == null) throw BadRequestException("GitLab data sources are disabled on this server (gitlab-disabled)")
    policy.canonicalOrigin(gitlab.baseUrl)
}
