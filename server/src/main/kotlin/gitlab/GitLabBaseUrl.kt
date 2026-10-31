package ch.nokillswit.gitlab

import ch.nokillswit.infra.outbound.SelfManagedHosts
import java.net.URI
import java.net.URISyntaxException

/** Why a GitLab base URL was refused; [code] is the stable identifier the data-source 400 maps from. */
enum class GitLabBaseUrlProblem(val code: String) {
    /** Not parseable as an absolute URL with a host (also: stray whitespace, a malformed authority or port). */
    INVALID("gitlab-base-url-invalid"),

    /** Not `https` (the development stub's own origin aside). */
    SCHEME("gitlab-base-url-scheme"),

    /** Carries a path, query, fragment or credentials: a base URL is an origin only. */
    NOT_AN_ORIGIN("gitlab-base-url-not-origin"),

    /** The operator list has no host: GitLab is off. */
    DISABLED("gitlab-disabled"),

    /** The host (and port) is not on `gitlab.allowedHosts`. */
    HOST_NOT_ALLOWED("gitlab-host-not-allowed"),
}

class GitLabBaseUrlException(val problem: GitLabBaseUrlProblem) : IllegalArgumentException(problem.code) {
    val code: String get() = problem.code
}

private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_HTTP_PORT = 80

/**
 * Validates a data source's GitLab base URL against the operator's list and returns its canonical origin (lowercase host,
 * default port dropped, no trailing slash). The data-source validator maps a [GitLabBaseUrlException] to a 400 by its code.
 *
 *  - `https` only; `http` is accepted for [devStub]'s own origin alone (development mode);
 *  - an origin only: the path is empty or `/`, and there is no query, fragment or userinfo;
 *  - `host[:port]` must be on `gitlab.allowedHosts`; the default port 443 matches an entry with no port and the reverse.
 *
 * The exception carries only the code, never the URL.
 */
fun validateGitLabBaseUrl(raw: String, hosts: SelfManagedHosts, devStub: GitLabDevStub?): URI {
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        throw GitLabBaseUrlException(GitLabBaseUrlProblem.INVALID)
    }
    val scheme = uri.scheme?.lowercase() ?: throw GitLabBaseUrlException(GitLabBaseUrlProblem.INVALID)
    val host = uri.host?.lowercase() ?: throw GitLabBaseUrlException(GitLabBaseUrlProblem.INVALID)
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty() !in setOf("", "/")) {
        throw GitLabBaseUrlException(GitLabBaseUrlProblem.NOT_AN_ORIGIN)
    }
    // `https://host:/` parses with port -1 but a trailing colon in the authority: reject any authority that is not host[:port].
    val explicitPort = uri.port.takeIf { it != -1 }
    if (uri.rawAuthority?.lowercase() != host + (explicitPort?.let { ":$it" } ?: "")) {
        throw GitLabBaseUrlException(GitLabBaseUrlProblem.INVALID)
    }
    val effectivePort = explicitPort ?: if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
    if (devStub != null && devStub.matchesOrigin(scheme, host, effectivePort)) {
        val defaultPort = if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        return origin(scheme, host, explicitPort?.takeIf { it != defaultPort })
    }
    if (scheme != "https") throw GitLabBaseUrlException(GitLabBaseUrlProblem.SCHEME)
    if (!hosts.enabled) throw GitLabBaseUrlException(GitLabBaseUrlProblem.DISABLED)
    if (!hosts.originListed(scheme, host, effectivePort)) {
        throw GitLabBaseUrlException(GitLabBaseUrlProblem.HOST_NOT_ALLOWED)
    }
    return origin(scheme, host, explicitPort?.takeIf { it != DEFAULT_HTTPS_PORT })
}

private fun origin(scheme: String, host: String, port: Int?): URI = URI(if (port == null) "$scheme://$host" else "$scheme://$host:$port")
