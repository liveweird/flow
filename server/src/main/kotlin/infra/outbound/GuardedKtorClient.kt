package ch.nokillswit.infra.outbound

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The Ktor `HttpClient(OkHttp)` over a [guardedOkHttpClient], shared by the Jira and the GitLab wiring so the two cannot drift.
 * Ktor's client-side `HttpRedirect` plugin is separate from the OkHttp engine's own `followRedirects` (already false in
 * [guardedOkHttpClient]) — both must refuse a 3xx, or checking-then-following would defeat the resolve-check-connect guard.
 */
fun guardedKtorClient(okHttpClient: OkHttpClient, requestTimeoutSeconds: Long): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = okHttpClient }
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutSeconds * MILLIS_PER_SECOND
        connectTimeoutMillis = requestTimeoutSeconds * MILLIS_PER_SECOND
    }
}
