package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.mutableOriginConnectionPoint
import ch.nokillswit.infra.config.requireConfigInt

/**
 * Global request-body ceiling: a memory-DoS backstop, not a business rule — every payload
 * field already carries its own maxLength, and the largest legitimate body (a bulk
 * catalog-file import) sits orders of magnitude below this. Exceeding it answers 413
 * (problem body via ErrorHandling.kt).
 */
const val MAX_REQUEST_BODY_BYTES: Long = 10L * 1024 * 1024

/**
 * Whether Swagger UI + the spec are served: `http.exposeOpenApi` when set, else development mode. ONE
 * definition for both the route install (`Routing.kt`, which also requires `servesApi()`) and the CSP exemption
 * (`SecurityHeaders.kt`, same two conditions) — the exemption must never apply where the UI is not actually mounted.
 */
internal fun Application.exposesOpenApi(): Boolean =
    environment.config.propertyOrNull("http.exposeOpenApi")?.getString()
        ?.takeIf { it.isNotBlank() }?.toBoolean()
        ?: developmentMode

fun Application.configureHttp() {
    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BODY_BYTES }
    }
    install(CachingHeaders) {
        options { call, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                // The SPA's static assets are content-hashed by Vite, so a day-long cache is
                // safe for both stylesheets and scripts (both JS media types — the served one
                // depends on the container's mime mapping).
                ContentType.Text.CSS,
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
                // index.html (and every SPA deep link answered with it) is the un-hashed entry point that must
                // pick up new asset names on deploy: `no-cache` forbids reusing a stored copy without asking the
                // server first, so a deploy is never masked by a stale shell. (ConditionalHeaders is not installed, so
                // the revalidation is a plain re-fetch of this one small file, not a 304.) Without this entry the
                // header was simply absent and browsers fell back to heuristic caching.
                ContentType.Text.Html -> CachingOptions(CacheControl.NoCache(null))
                // API answers (JSON and RFC 7807 problem+json) carry per-user, bearer-authorized data:
                // `no-store` keeps them out of browser/proxy caches (a shared machine's back button, an
                // intermediary replaying one user's list to another). Scoped to /api/ so a static JSON
                // asset the SPA ships (if one ever appears) is not swept up.
                ContentType.Application.Json, ContentType.Application.ProblemJson ->
                    if (call.request.path().startsWith("/api/")) CachingOptions(CacheControl.NoStore(null)) else null
                else -> null
            }
        }
    }
    // CORS is installed only when a cross-origin caller actually exists (an explicit allow-list
    // via CORS_ALLOWED_HOSTS). Production is single-origin (Ktor serves the SPA) and local dev
    // goes through the Vite proxy, so the default is: no CORS plugin, browsers enforce
    // same-origin, and no Access-Control-* headers are emitted.
    val corsHosts = environment.config.propertyOrNull("http.corsHosts")?.getString()
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        .orEmpty()
    if (corsHosts.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }
    // Behind a TLS-terminating reverse proxy / ingress, trust X-Forwarded-* so the client IP
    // (rate-limit buckets) and scheme (HTTPS redirect) are the real client's, not the proxy's.
    // Off by default: honoring these headers from direct clients would let them spoof both.
    if (environment.config.propertyOrNull("http.behindProxy")?.getString()?.toBoolean() == true) {
        val proxyHops = requireConfigInt(environment.config, "http.proxyHops", min = 1)
        // HAProxy's `option forwardedfor` APPENDS a fresh X-Forwarded-For header LINE instead of
        // merging into a line the client already sent, so the request can carry the client's own
        // (spoofed) line first and the proxy's real one second. XForwardedHeaders resolves the
        // for-header via Headers.get(name) — the FIRST header LINE only — so it would key the
        // rate limiter on the spoofable client line. Fold every X-Forwarded-For line ourselves
        // (RFC 2616: multiple header fields sharing a name may be combined by joining their
        // values with a comma, in the order received) and resolve the trusted hop directly,
        // BEFORE XForwardedHeaders reads anything: the Setup phase always runs before its
        // Plugins-phase onCall (RawForwardedForLinesTest pins the ordering against a real
        // Netty engine — ktor-client's own request writer folds repeated header() calls into
        // one wire line before send, so a plain testApplication client cannot reproduce it).
        intercept(ApplicationCallPipeline.Setup) {
            resolveForwardedForOrigin(call, proxyHops)
        }
        install(XForwardedHeaders) {
            // Only the headers the proxy contract sets — and therefore overwrites. Ktor's defaults
            // also honour X-Forwarded-Server / X-Forwarded-Protocol / X-Forwarded-SSL /
            // Front-End-Https, which a proxy that sets just the canonical ones passes through
            // from the client untouched (Lettuce's v3.6.2 finding; ForwardedHeadersTest).
            hostHeaders.clear()
            hostHeaders.add(HttpHeaders.XForwardedHost)
            protoHeaders.clear()
            protoHeaders.add(HttpHeaders.XForwardedProto)
            httpsFlagHeaders.clear()
            // Untrusted too: a client-supplied non-numeric value throws a NumberFormatException
            // inside Ktor's own handler (a 500 raised from CallSetup, before any route runs), and
            // nothing reads it — the scheme-derived default port set via protoHeaders above is
            // all the HTTPS redirect needs.
            portHeaders.clear()
            // X-Forwarded-For is resolved by resolveForwardedForOrigin above (multi-line fold);
            // leave this empty so XForwardedHeaders never overwrites that result with its own
            // first-line-only, unfolded read.
            forHeaders.clear()
        }
    }
    install(Compression)
    // DefaultHeaders' own `Server: Ktor/<version>` would disclose the framework and its exact version to
    // every client (checkup 2C18); a fixed generic value replaces it. `Date` stays.
    install(DefaultHeaders) {
        header(HttpHeaders.Server, "flow")
    }
    if (!developmentMode) {
        install(HSTS) {
            includeSubDomains = true
        }
        install(HttpsRedirect) {
            // The port to redirect to. By default 443, the default HTTPS port.
            sslPort = 443
            // 301 Moved Permanently, or 302 Found redirect.
            permanentRedirect = true
        }
    }
    // The Swagger UI + spec mount lives in Routing.kt (configureRouting): it must obey `servesApi()`, and the
    // role is only published later (configureRole), after this module.
}

/**
 * The proxy-trust counterpart to XForwardedHeaders' built-in for-header handling — the identical
 * trust-from-the-end selection (`http.proxyHops`; ForwardedHeadersTest), but folding EVERY
 * X-Forwarded-For header LINE first (`getAll`, not `get`), so a line a trusted proxy APPENDS
 * rather than merges is not shadowed by a client-supplied line of the same name. A hop count in
 * excess of what the request actually carries falls back to the last available value — same
 * fallback Ktor's own useLastProxy()/skipLastProxies() apply — never an exception. Writes
 * directly into the call's MutableOriginConnectionPoint (the same attribute XForwardedHeaders
 * itself populates), so RateLimits' `call.request.origin.remoteHost` sees the resolved value
 * whether or not X-Forwarded-For is present at all.
 */
private fun resolveForwardedForOrigin(call: ApplicationCall, proxyHops: Int) {
    val hops = call.request.headers.getAll(HttpHeaders.XForwardedFor)
        ?.flatMap { it.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: return
    val chosen = hops.getOrNull(hops.size - proxyHops) ?: hops.last()
    val origin = call.mutableOriginConnectionPoint
    origin.remoteHost = chosen
    // Ktor's own resolution (io.ktor.server.plugins.forwardedheaders.isNotHostAddress, internal)
    // also sets remoteAddress only for a value that looks like an IP rather than a hostname — no
    // letters, or an IPv6 literal (which always contains ':'); reimplemented here since the
    // original is not visible outside its module.
    if (chosen.contains(':') || chosen.none { it.isLetter() }) origin.remoteAddress = chosen
}
