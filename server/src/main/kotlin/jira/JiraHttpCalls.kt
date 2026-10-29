package ch.nokillswit.jira

import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonElement

// The value types of one `JiraHttp` call (split out of `JiraHttp.kt` to keep it under the source-file size cap).

/** What one request needs on every attempt; [pauseKey] names the tenant whose shared pause it obeys (null = none). */
internal class PendingCall(
    val method: HttpMethod,
    val url: String,
    val query: List<Pair<String, String>>,
    val jsonBody: JsonElement?,
    val authHeader: String?,
    val statusCodeOverrides: Map<Int, String>,
    val pauseKey: String?,
    /** The sync job's [JiraCallTally] when the call runs inside one, else null. */
    val tally: JiraCallTally?,
    /** Told the size of the response body on the wire, once it is read (the size a character-per-run ceiling charges). */
    val onWireBytes: ((Int) -> Unit)?,
    /** Applied to the parsed tree while the big-parse lock is still held (`JiraHttp.requestAs`); identity for a plain request. */
    val transform: (JsonElement) -> Any?,
)

/** One attempt's verdict: the parsed body, or "try again" with what the backoff decision needs. */
internal sealed interface Outcome

internal class Done(val value: Any?) : Outcome

internal class Retry(
    val retryAfterSeconds: Long?,
    /** True for a 429, or a 5xx carrying `Retry-After`: the TENANT is rate limiting, so its other calls pause too. */
    val pausesOthers: Boolean,
    val terminal: () -> JiraFetchException,
) : Outcome

/** One call's hold on a semaphore permit, so it can be handed back while the call only waits (backoff, a tenant pause). */
internal class Permit(private val semaphore: Semaphore) {
    private var held = false

    suspend fun acquire() {
        semaphore.acquire()
        held = true
    }

    fun release() {
        if (held) {
            held = false
            semaphore.release()
        }
    }
}
