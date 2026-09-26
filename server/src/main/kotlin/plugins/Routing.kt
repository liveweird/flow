package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val staticDir = environment.config.propertyOrNull("web.staticDir")?.getString()?.takeIf { it.isNotBlank() }

    routing {
        if (staticDir != null) {
            // Serve the built React SPA: hashed assets plus a fallback to index.html
            // so React Router owns the non-/api URL space. When unset (local dev / tests),
            // the SPA is served by Vite and this module installs no routes.
            singlePageApplication {
                filesPath = staticDir
                defaultPage = "index.html"
            }
        }
    }
}
