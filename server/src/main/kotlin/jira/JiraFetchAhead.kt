package ch.nokillswit.jira

import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Fetches [items] ahead of the code that stores them: up to [window] fetches are started, in list order, while [consume]
 * is handed every outcome strictly in list order (one at a time, never concurrently with itself). A failed fetch reaches
 * [consume] as a failed [Result], at ITS position, instead of cancelling the fetches before it - so the items ahead of the
 * first failure are all consumed exactly as a one-by-one loop would, and whatever [consume] throws ends the run there.
 *
 * Structured concurrency: the fetches are children of this call's own scope. When [consume] throws, or this coroutine is
 * cancelled, every fetch still in flight is cancelled and awaited before the exception leaves (nothing outlives the call).
 * [catchingFailures] turns an ordinary failure into the [Result] but rethrows cancellation, which therefore never becomes
 * an "outcome". A [window] of 1 is the sequential loop: start one, wait for it, consume it.
 *
 * Memory: no fetch is started while the results that are finished but not yet consumed already weigh more than [budget]
 * ([weight] of each; a failure weighs 0). The oldest item is always awaited, so the loop cannot stall; what is held is at most
 * [budget] plus the results of the fetches that were already running when the budget was crossed (at most [window] of them).
 *
 * This is only the look-ahead. It is NOT a request limit: how many calls actually run at once is decided by the caller's
 * client (`JiraHttp`'s semaphore), and [window] is that same number, so the semaphore is never the second limit.
 */
internal suspend fun <T, R> fetchAheadInOrder(
    items: List<T>,
    window: Int,
    fetch: suspend (T) -> R,
    weight: (R) -> Int = { 0 },
    budget: Int = Int.MAX_VALUE,
    consume: suspend (T, Result<R>) -> Unit,
) {
    require(window >= 1) { "window must be at least 1, was $window" }
    coroutineScope {
        val ahead = ArrayDeque<Triple<T, Held, Deferred<Result<R>>>>()
        var next = 0
        while (next < items.size || ahead.isNotEmpty()) {
            while (next < items.size && ahead.size < window && (ahead.isEmpty() || ahead.sumOf { it.second.weight } <= budget)) {
                val item = items[next++]
                val held = Held()
                val outcome = async {
                    catchingFailures(
                        block = {
                            val value = fetch(item)
                            held.weight = weight(value)
                            Result.success(value)
                        },
                        onFailure = { Result.failure(it) },
                    )
                }
                ahead += Triple(item, held, outcome)
            }
            val (item, _, outcome) = ahead.removeFirst()
            consume(item, outcome.await())
        }
    }
}

/** The weight of one fetched-but-unconsumed result; 0 until its fetch has finished (and for a failure). */
private class Held {
    @Volatile
    var weight: Int = 0
}
