package ch.nokillswit.plugins

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.users.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.health")

/** What `/api/v1/ready` asks; the default round-trips the database. */
fun interface ReadinessProbe {
    suspend fun ready(): Boolean
}

/** Test seam: a pre-put probe replaces the database round trip. */
val ReadinessProbeKey = AttributeKey<ReadinessProbe>("ReadinessProbe")

@Serializable
data class HealthResponse(val status: String)

/** The readiness round trip's budget — a probe that hangs must answer 503, not hold the prober. */
internal const val READINESS_TIMEOUT_MS = 3_000L

/**
 * The two public probes. `/api/v1/health` proves the process answers (liveness: the image's
 * HEALTHCHECK, the k8s livenessProbe); `/api/v1/ready` adds one bounded database round trip and
 * answers a 503 problem while it fails (the k8s readinessProbe, the e2e global setup). Registered
 * AFTER `configureDatabase` (it reads [R2dbcDatabaseKey]) and outside `authenticate {}` — both
 * disclose nothing but a status word, and probers carry no token.
 */
fun Application.configureHealth() {
    val database = attributes[R2dbcDatabaseKey]
    val default = ReadinessProbe { databaseAnswers(database) }
    routing {
        get("/api/v1/health") { call.respond(HealthResponse("ok")) }
        get("/api/v1/ready") {
            // Resolved per request: a test's `application {}` block runs after the yaml modules (the ChecksService idiom).
            val probe = call.application.attributes.getOrNull(ReadinessProbeKey) ?: default
            if (probe.ready()) {
                call.respond(HealthResponse("ok"))
            } else {
                call.respondProblem(HttpStatusCode.ServiceUnavailable, "The database did not answer the readiness probe")
            }
        }
    }
}

/** True when one bounded round trip completes — a timeout, a refused connection or a pool failure all read "not ready". */
private suspend fun databaseAnswers(database: R2dbcDatabase): Boolean = catchingFailures({
    withTimeoutOrNull(READINESS_TIMEOUT_MS) {
        suspendTransaction(database) { UserService.Users.select(UserService.Users.id).limit(1).toList() }
        true
    } ?: false
}) { e ->
    // Probes fire every few seconds: the failure is the answer (503), the cause a debug line, not a stack trace per poll.
    log.debug("Readiness probe failed: {}", e.toString())
    false
}
