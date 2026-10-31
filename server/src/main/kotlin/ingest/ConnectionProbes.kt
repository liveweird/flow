package ch.nokillswit.ingest

import ch.nokillswit.fetch.FetchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The probe harness of a connector's `testConnection` (v0.2.0 plan §6): runs each endpoint check in SEQUENCE, at most [probeTimeoutMs]
 * each and [totalBudgetMs] together, never throwing - every outcome becomes a [ConnectionTestRow] in [rows]. Created when the test
 * starts (its total budget runs from [now] at construction); the Jira and GitLab connectors share it so a row means the same thing
 * for both.
 */
class ProbeRun(
    /** Test seam: an injected clock lets a test simulate the TOTAL budget eroding across probes without a real sleep. */
    private val now: () -> Long,
    totalBudgetMs: Long = TOTAL_BUDGET_MS,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
) {
    private val deadline = now() + totalBudgetMs
    private val collected = mutableListOf<ConnectionTestRow>()

    val rows: List<ConnectionTestRow> get() = collected

    suspend fun probe(name: String, path: String, required: Boolean, scopeHint: String? = null, block: suspend () -> Unit) {
        // [LOW-1] The remaining TOTAL budget, not just [probeTimeoutMs] — a budget checked
        // only ONCE per probe (before it starts) never shrinks the probe's OWN timeout, so a
        // run of several near-the-cap probes could together run well past the documented 30s
        // (each individually within its own 10s allowance). `withTimeout` below is always the
        // SMALLER of the two.
        val remaining = deadline - now()
        if (remaining <= 0) {
            collected += ConnectionTestRow(name, path, required, ok = false, code = "TIMEOUT", scopeHint = scopeHint)
            return
        }
        try {
            withTimeout(minOf(probeTimeoutMs, remaining)) { block() }
            collected += ConnectionTestRow(name, path, required, ok = true, scopeHint = scopeHint)
        } catch (_: TimeoutCancellationException) {
            collected += ConnectionTestRow(name, path, required, ok = false, code = "TIMEOUT", scopeHint = scopeHint)
        } catch (cause: CancellationException) {
            // [MED-4] A GENUINE coroutine cancellation (the caller's scope was cancelled) is
            // not a probe outcome — it must propagate, never get folded into a row. Distinct
            // from TimeoutCancellationException above, which IS this function's own bounded
            // wait expiring and is deliberately reported as a TIMEOUT row.
            throw cause
        } catch (cause: FetchException) {
            collected += ConnectionTestRow(name, path, required, ok = false, status = cause.status, code = cause.code,
                scopeHint = scopeHint)
        } catch (_: IllegalStateException) {
            // A prior REQUIRED probe (tenant_info) never resolved the gateway (e.g. it
            // itself timed out or failed) — every probe downstream of it is unreachable,
            // not a distinct failure of ITS OWN endpoint.
            collected += ConnectionTestRow(name, path, required, ok = false, code = "NOT_FOUND", scopeHint = scopeHint)
        } catch (_: RuntimeException) {
            // [MED-4] A raw `.jsonObject`/`.jsonArray`/`.jsonPrimitive` cast (here, or one a
            // future client implementation forgets to route through its own decode helper)
            // throws `IllegalArgumentException` on a shape mismatch — without this catch-all
            // that crashed the WHOLE `testConnection` call (and 500'd the route), instead of
            // reporting the one malformed probe as a row like every other failure mode.
            collected += ConnectionTestRow(name, path, required, ok = false, code = "INVALID_RESPONSE", scopeHint = scopeHint)
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 10_000L
        const val TOTAL_BUDGET_MS = 30_000L
    }
}
