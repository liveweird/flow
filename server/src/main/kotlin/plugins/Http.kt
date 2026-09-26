package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.plugins.bodylimit.*

/**
 * Global request-body ceiling: a memory-DoS backstop, not a business rule — every payload
 * field already carries its own maxLength, and the largest legitimate body (a bulk
 * catalog-file import) sits orders of magnitude below this. Exceeding it answers 413
 * (problem body via ErrorHandling.kt).
 */
const val MAX_REQUEST_BODY_BYTES: Long = 10L * 1024 * 1024

fun Application.configureHttp() {
    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BODY_BYTES }
    }
    install(CachingHeaders) {
        options { call, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                // The SPA's static assets are content-hashed by Vite, so a day-long cache is
                // safe for both stylesheets and scripts (both JS media types — the served one
                // depends on the container's mime mapping). index.html stays uncached: it is
                // the un-hashed entry point that must pick up new asset names on deploy.
                ContentType.Text.CSS,
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
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
        install(XForwardedHeaders)
    }
    install(Compression)
    install(DefaultHeaders)
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
    // Swagger UI + the full spec are an API roadmap for anyone who can reach the host, so they
    // are served only in development mode — or when explicitly re-enabled for a trusted
    // environment via HTTP_EXPOSE_OPENAPI=true. (Bearer-token auth cannot protect a
    // browser-loaded UI: page loads carry no Authorization header.)
    val exposeOpenApi = environment.config.propertyOrNull("http.exposeOpenApi")?.getString()
        ?.takeIf { it.isNotBlank() }?.toBoolean()
        ?: developmentMode
    if (exposeOpenApi) {
        // swaggerUI serves both the UI page and the spec (GET /openapi/documentation.yaml).
        routing {
            swaggerUI(path = "openapi")
        }
    }
}
