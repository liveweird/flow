package ch.nokillswit.telemetry

import com.codahale.metrics.MetricFilter
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.UniformSnapshot

/** Ktor's `DropwizardMetrics` base name (`ktor.calls`, the plugin's default) plus the dot every series name continues with. */
internal const val KTOR_CALLS_PREFIX = "ktor.calls."

private const val TIMER_SUFFIX = "timer"
private const val API_PREFIX = "/api/"
private const val TELEMETRY_API = "/api/v1/telemetry"
private val PROBE_ROUTES = setOf("/api/v1/health", "/api/v1/ready")
private const val REPORTS_API = "/api/v1/reports"
private const val NANOS_PER_MILLI = 1_000_000.0
private const val STATUS_DIGITS = 3
private const val SERVER_ERROR_FLOOR = 500
private const val RATE_LIMITED = 429

/** The `*` label: every `/api/` route but the telemetry's own and the health and ready probes. */
internal const val TOTAL_LABEL = "*"

private val SELECTOR = Regex("""\([^)]*\)""")
private val SLASHES = Regex("/{2,}")

/**
 * The route name Ktor puts in a series name, normalised to the plain path: Ktor 3.6 names a route after its whole selector chain
 * (`/(authenticate "default")/(RateLimit reports)/api/v1/reports/throughput/(method:GET)`), so every parenthesised selector is dropped and
 * the doubled and trailing slashes collapse (`/api/v1/reports/throughput`). Path parameters (`{id}`) stay as written. Pinned by
 * `LatencyCollectorTest`, so a Ktor upgrade that renames the series fails a test instead of silently emptying the panel.
 */
internal fun normaliseRoute(raw: String): String {
    val path = raw.replace(SELECTOR, "").replace(SLASHES, "/")
    return if (path.length > 1) path.trimEnd('/') else path
}

/** The two per-route series of Ktor's call metrics this reads. */
internal enum class SeriesKind { TIMER, STATUS }

/**
 * One Ktor series name split into its route and its kind: `ktor.calls.<route>.timer` is the route's duration timer and
 * `.<3-digit status>` the meter of that status' responses (Ktor also keeps `.meter`, the all-status meter marked when the routed call
 * STARTS, and the global `ktor.calls.status.<code>` / `.duration` / `.active` / `.exceptions`, none of which this reads). [rawRoute] is
 * the route as written in the name (the key a route's timer and status meters share); [route] is [normaliseRoute] of it.
 */
internal class KtorSeries(val rawRoute: String, val kind: SeriesKind, val status: Int?) {
    val route: String by lazy { normaliseRoute(rawRoute) }
}

/**
 * Splits a registry name, or null when it is not a per-route timer / status series of Ktor's call metrics. A name whose route holds a
 * `?` is never a routed series: it is the fallback name (`request.uri`, query included) Ktor uses for a response it did not route -
 * StatusPages' second answer to a bodiless 429 - and would count a request twice under one label.
 */
internal fun parseKtorSeries(name: String): KtorSeries? {
    if (!name.startsWith(KTOR_CALLS_PREFIX)) return null
    val dot = name.lastIndexOf('.')
    if (dot <= KTOR_CALLS_PREFIX.length) return null
    val suffix = name.substring(dot + 1)
    val rawRoute = name.substring(KTOR_CALLS_PREFIX.length, dot)
    if ('?' in rawRoute) return null
    return when {
        suffix == TIMER_SUFFIX -> KtorSeries(rawRoute, SeriesKind.TIMER, null)
        suffix.length == STATUS_DIGITS && suffix.all(Char::isDigit) -> KtorSeries(rawRoute, SeriesKind.STATUS, suffix.toInt())
        else -> null
    }
}

private fun isCounted(route: String) =
    route.startsWith(API_PREFIX) && route != TELEMETRY_API && !route.startsWith("$TELEMETRY_API/") && route !in PROBE_ROUTES

/** The label of a route's own series: report routes only (the total covers the rest). */
private fun isReport(route: String) = route == REPORTS_API || route.startsWith("$REPORTS_API/")

/** What one label (a route or the total) saw since the previous collection. */
private class Window {
    var count = 0L
    var serverErrors = 0L
    var rateLimited = 0L
    val nanos = mutableListOf<LongArray>()
}

/** The windows a collection fills, one per label: every counted route feeds the total, a report route its own as well. */
private class Windows {
    private val byLabel = HashMap<String, Window>()

    fun each(route: String, apply: (Window) -> Unit) {
        if (!isCounted(route)) return
        apply(byLabel.getOrPut(TOTAL_LABEL) { Window() })
        if (isReport(route)) apply(byLabel.getOrPut(route) { Window() })
    }

    fun readings(): List<Reading> = byLabel.flatMap { (label, window) -> window.readings(label) }
}

private fun Window.readings(label: String): List<Reading> = buildList {
    if (count > 0) {
        add(Reading(TelemetryMetrics.HTTP_COUNT, count.toDouble(), label))
        if (nanos.isNotEmpty()) {
            val snapshot = UniformSnapshot(nanos.concat())
            add(Reading(TelemetryMetrics.HTTP_P50, snapshot.median / NANOS_PER_MILLI, label))
            add(Reading(TelemetryMetrics.HTTP_P95, snapshot.get95thPercentile() / NANOS_PER_MILLI, label))
        }
    }
    if (serverErrors > 0) add(Reading(TelemetryMetrics.HTTP_5XX, serverErrors.toDouble(), label))
    if (rateLimited > 0) add(Reading(TelemetryMetrics.HTTP_429, rateLimited.toDouble(), label))
}

private fun List<LongArray>.concat(): LongArray {
    val out = LongArray(sumOf { it.size })
    var at = 0
    for (part in this) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

/**
 * The request figures of a web process (`.claude/docs/observability.md` "Telemetry"): reads Ktor's Dropwizard TIMERS and status
 * METERS out of [registry] (`MetricRegistryKey`; never its gauges, whose thread-states set dumps the threads) every tick, and turns
 * them into rows per normalised report route plus the [TOTAL_LABEL] total.
 *
 * `http.count` and the status counts are DELTAS of the cumulative Dropwizard counts against the previous tick's value, kept here in
 * memory (the first tick counts everything since the process started; names absent from a tick are forgotten, so the map stays as
 * small as the registry); `http.p50`/`http.p95` come from the timers' windowed snapshots (`TelemetryRegistry`: a true sliding window
 * of the sample interval) of the timers that saw a request, merged into one set of observations per label so the total's percentile is
 * a real one, not a mean of percentiles. A label without a request, 5xx or 429 in the interval has no row.
 */
class LatencyCollector(private val registry: MetricRegistry) {
    private var previous = HashMap<String, Long>()

    @Synchronized
    fun collect(): List<Reading> {
        val seen = HashMap<String, Long>()
        val windows = Windows()
        val timerDeltas = HashMap<String, Long>()
        registry.getTimers(MetricFilter { name, _ -> name.startsWith(KTOR_CALLS_PREFIX) && name.endsWith(".$TIMER_SUFFIX") })
            .forEach { (name, timer) ->
                val series = parseKtorSeries(name)?.takeIf { it.kind == SeriesKind.TIMER } ?: return@forEach
                val delta = deltaOf(seen, name, timer.count)
                timerDeltas[series.rawRoute] = delta
                // Evaluated once: a snapshot of a sliding window copies its values.
                if (delta > 0L) {
                    val nanos = timer.snapshot.values
                    windows.each(series.route) { it.count += delta; it.nanos += nanos }
                }
            }
        val routes = HashMap<String, RouteStatuses>()
        registry.getMeters(MetricFilter { name, _ -> name.startsWith(KTOR_CALLS_PREFIX) })
            .forEach { (name, meter) ->
                val series = parseKtorSeries(name)?.takeIf { it.kind == SeriesKind.STATUS } ?: return@forEach
                val timerDelta = timerDeltas[series.rawRoute] ?: return@forEach // only a ROUTED call has a timer
                routes.getOrPut(series.rawRoute) { RouteStatuses(series.route, timerDelta) }.add(series, deltaOf(seen, name, meter.count))
            }
        routes.values.forEach { statuses ->
            windows.each(statuses.route) {
                it.rateLimited += statuses.rateLimited()
                it.serverErrors += statuses.serverErrors
            }
        }
        previous = seen
        return windows.readings()
    }

    /** The growth of a cumulative count since the previous collection (never negative; a series seen first counts from zero). */
    private fun deltaOf(seen: MutableMap<String, Long>, name: String, current: Long): Long {
        seen[name] = current
        return (current - (previous[name] ?: 0L)).coerceAtLeast(0L)
    }
}

/**
 * One routed call's meter deltas of an interval. Ktor marks the `.meter` when a routed call STARTS but its status meter and its timer
 * when the response is SENT, and counts a send each time the response pipeline runs: `plugins/ErrorHandling.kt`'s `status(429)`
 * handler answers the RateLimit plugin's bodiless 429 a second time (with the problem body). That second send is marked under the
 * routed name on a route with no wrapping selectors, where it lands TWICE in the `429` meter but once in the timer
 * ([timerDelta]), and otherwise under the fallback URI-named series that [parseKtorSeries] ignores. The surplus of the status
 * meters over the timer is therefore taken off the 429 count ([rateLimited]); a 5xx is counted once by Ktor (an exception handled by
 * StatusPages included) and needs no correction.
 */
private class RouteStatuses(val route: String, private val timerDelta: Long) {
    private var statusSum = 0L
    private var tooManyRequests = 0L
    var serverErrors = 0L
        private set

    fun add(series: KtorSeries, delta: Long) {
        val status = series.status ?: return
        statusSum += delta
        if (status == RATE_LIMITED) tooManyRequests += delta
        if (status >= SERVER_ERROR_FLOOR) serverErrors += delta
    }

    fun rateLimited(): Long = (tooManyRequests - (statusSum - timerDelta).coerceAtLeast(0L)).coerceIn(0L, tooManyRequests)
}
