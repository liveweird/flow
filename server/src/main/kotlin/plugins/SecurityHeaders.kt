package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

// Strict Content-Security-Policy for the SPA + JSON API (production single-origin serving).
// - script-src 'self': the built SPA bundle is external/hashed; index.html has no inline app scripts.
// - style-src 'unsafe-inline': REQUIRED by Mantine (its style props/CSS variables render inline `style`
//   attributes and runtime <style> tags; Mantine 9 has no emotion) + the app's inline style={{}} usage.
//   Styles only — script-src stays strict, so this does not reopen script injection.
// - img-src 'self' data:: local assets + data URIs; remote images in user markdown won't load (acceptable).
// - connect-src 'self': the API is same-origin and the SPA opens no WebSocket.
// Pinned verbatim by ServerTest — any edit must be a deliberate test edit too.
private const val APP_CSP =
    "default-src 'self'; " +
    "script-src 'self'; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; " +
    "font-src 'self'; " +
    "connect-src 'self'; " +
    "object-src 'none'; " +
    "base-uri 'self'; " +
    "form-action 'self'; " +
    "frame-ancestors 'none'"

// Defense-in-depth security response headers. The code is already XSS-safe (React escaping +
// react-markdown with no raw HTML); these headers contain the blast radius of any future
// regression and harden against clickjacking / MIME sniffing / referrer leakage.
fun Application.configureSecurityHeaders() {
    // Read once at boot, like the route install in Http.kt (the same exposesOpenApi()).
    val swaggerMounted = exposesOpenApi()
    intercept(ApplicationCallPipeline.Plugins) {
        val headers = call.response.headers
        if (headers["X-Content-Type-Options"] == null) { // set once per call
            headers.append("X-Content-Type-Options", "nosniff")
            headers.append("X-Frame-Options", "DENY")
            headers.append("Referrer-Policy", "no-referrer")
            headers.append("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()")
            // Swagger UI bootstraps with inline script/style, so it is exempt from the strict CSP — but ONLY
            // where it is actually mounted (exposeOpenApi on) and only for /openapi itself or its subtree.
            // Anywhere else `/openapi…` is an ordinary path (in production the SPA catch-all answers it with
            // index.html), and it must carry the full CSP.
            val path = call.request.path()
            val swaggerPath = path == "/openapi" || path.startsWith("/openapi/")
            if (!(swaggerMounted && swaggerPath)) {
                headers.append("Content-Security-Policy", APP_CSP)
            }
        }
    }
}
