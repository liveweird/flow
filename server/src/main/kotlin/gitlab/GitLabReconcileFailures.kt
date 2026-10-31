package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException

/**
 * The failures one PROJECT can cause on its own: a token that cannot see it (`FORBIDDEN_SCOPE`), a server that errors or times out on it
 * (`UPSTREAM_UNAVAILABLE`, `TIMEOUT`), an unreadable answer (`INVALID_RESPONSE`) or a redirect. RECONCILE skips such a project and goes
 * on, so one broken project cannot stop the daily pass for the rest. Everything else - `RATE_LIMITED`, `AUTHENTICATION_FAILED`, `TOKEN_*`,
 * `BLOCKED_HOST` and any code not listed - concerns the whole connection and propagates at once.
 */
internal val PROJECT_SCOPED_FAILURE_CODES = setOf("FORBIDDEN_SCOPE", "UPSTREAM_UNAVAILABLE", "TIMEOUT", "INVALID_RESPONSE", "REDIRECT")

internal fun FetchException.isProjectScoped(): Boolean = code in PROJECT_SCOPED_FAILURE_CODES

/**
 * Guards the skip rule against an instance-wide outage that looks like many project-scoped failures: after
 * [RECONCILE_CONSECUTIVE_FAILURES] in a row inside one stream (any success resets the count) the last failure is rethrown, so the run
 * fails like the outage it is and backs off instead of spending hundreds of slow skips.
 */
internal class ProjectFailureStreak {
    private var consecutive = 0

    fun succeeded() {
        consecutive = 0
    }

    /** Counts one project-scoped [failure]; throws it when it is the last of an unbroken run. */
    fun failed(failure: FetchException) {
        if (++consecutive >= RECONCILE_CONSECUTIVE_FAILURES) throw failure
    }
}
