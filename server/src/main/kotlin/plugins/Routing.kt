package ch.nokillswit.plugins

import ch.nokillswit.authz.NotFoundException
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — no SPA/static
    // assets, no catch-all.
    if (!servesApi()) return

    val staticDir = environment.config.propertyOrNull("web.staticDir")?.getString()?.takeIf { it.isNotBlank() }

    routing {
        if (staticDir != null) {
            // Serve the built React SPA: hashed assets plus a fallback to index.html
            // so React Router owns the non-/api URL space. When unset (local dev / tests),
            // the SPA is served by Vite and this module installs no routes.
            route("/") {
                // The `/api` namespace belongs to the typed routes alone: the fallback must never answer
                // under it (nor for `/api` itself). Declared API routes are siblings of this node and
                // out-rank its tail-card, so only calls that would have fallen into the SPA get here; the
                // throw reaches StatusPages — the standard 404 ProblemDetail, like any other not-found.
                intercept(ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    if (path == "/api" || path.startsWith("/api/")) throw NotFoundException("Resource not found")
                }
                singlePageApplication {
                    filesPath = staticDir
                    defaultPage = "index.html"
                }
            }
        }
    }
}
