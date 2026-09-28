package ch.nokillswit.plugins

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.withCharset
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.r2dbc.spi.R2dbcException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException

/**
 * RFC 7807 problem detail. Serialized as `application/problem+json`.
 *
 * `type` defaults to `about:blank`, in which case `title` is the standard reason phrase for
 * `status` (per the RFC). `detail` is the occurrence-specific message.
 */
@Serializable
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)

/** RFC 7807 media type for error bodies. */
val ProblemJson: ContentType = ContentType("application", "problem+json")

private val problemSerializer = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Respond with an RFC 7807 `application/problem+json` body. Goes through [TextContent] explicitly
 * so ContentNegotiation does not relabel it as `application/json`. Usable from anywhere — including
 * the JWT `challenge`, which runs outside [StatusPages].
 */
suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String? = null,
    title: String = status.description,
    instance: String? = null,
) {
    // The occurrence path is useful without reflecting query parameters that may contain secrets.
    val problem = ProblemDetail(
        title = title,
        status = status.value,
        detail = detail ?: status.description,
        instance = instance ?: request.path(),
    )
    respond(
        TextContent(
            problemSerializer.encodeToString(ProblemDetail.serializer(), problem),
            ProblemJson.withCharset(Charsets.UTF_8),
            status,
        )
    )
}

private const val PG_UNIQUE_VIOLATION = "23505"

// exclusion_violation: raised by an EXCLUDE USING gist constraint (v0.3.0 M1 commit 3,
// metrics.team_membership's overlap guard, `.claude/docs/persistence.md` "The metrics schema —
// configuration (V15)") — the SAME "this would collide with an existing row" shape as a unique
// violation, just a different SQLSTATE, so it joins 23505 on the 409 path below.
private const val PG_EXCLUSION_VIOLATION = "23P01"

// character_not_in_repertoire: PostgreSQL rejects NUL (0x00) inside text values — a client
// error (nothing legitimate contains NUL), not a server fault.
private const val PG_CHARACTER_NOT_IN_REPERTOIRE = "22021"

/** One cause-chain walk for every predicate below (self included). */
private inline fun Throwable.anyInChain(predicate: (Throwable) -> Boolean): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if (predicate(cur)) return true
        cur = cur.cause
    }
    return false
}

private fun Throwable.hasSqlState(state: String): Boolean =
    anyInChain { it is R2dbcException && it.sqlState == state }

// Internal (not private): the user-import loop classifies per-row duplicates with it.
internal fun Throwable.isUniqueViolation(): Boolean = hasSqlState(PG_UNIQUE_VIOLATION)

internal fun Throwable.isExclusionViolation(): Boolean = hasSqlState(PG_EXCLUSION_VIOLATION)

internal fun Throwable.isCharacterNotInRepertoire(): Boolean = hasSqlState(PG_CHARACTER_NOT_IN_REPERTOIRE)

// Per-constraint 409 wording: Postgres names the violated unique index in its error message
// ('duplicate key value violates unique constraint "uq_…"'), so the detail can say WHAT
// already exists instead of the generic fallback. Keyed by the partial unique indexes the
// migrations define; an unmapped constraint (a future migration) falls back to the generic.
private val UNIQUE_CONSTRAINT_DETAILS = mapOf(
    "uq_users_email_active" to "A user with this email already exists",
    "uq_teams_name_active" to "A team with this name already exists",
    "uq_source_connections_name_active" to "A data source with this name already exists",
    "uq_metrics_board_team_map_team_id" to "This team is already mapped to another board",
)

private fun Throwable.uniqueViolationDetail(): String {
    var found: String? = null
    anyInChain { t ->
        val message = t.message ?: return@anyInChain false
        found = UNIQUE_CONSTRAINT_DETAILS.entries.firstOrNull { (name, _) -> name in message }?.value
        found != null
    }
    return found ?: "Resource already exists"
}

private suspend fun ApplicationCall.respondConflict(cause: Throwable) =
    respondProblem(HttpStatusCode.Conflict, cause.uniqueViolationDetail())

private suspend fun ApplicationCall.respondDbFailure(cause: Throwable) = when {
    cause.isUniqueViolation() -> respondConflict(cause)
    cause.isExclusionViolation() ->
        respondProblem(HttpStatusCode.Conflict, "Overlapping team membership for this account")
    cause.isCharacterNotInRepertoire() ->
        respondProblem(HttpStatusCode.BadRequest, "Text must not contain the NUL character")
    else -> respondInternalError(cause)
}

// Internal (not private): the JWT `validate`/`challenge` pair in plugins/Security.kt answers a
// blocklist-lookup failure with this same catch-all 500 instead of a 401 (the 500-not-401 rule —
// see "JWT model" in .claude/docs/security.md).
internal suspend fun ApplicationCall.respondInternalError(cause: Throwable) {
    application.log.error("Unhandled exception while processing ${request.local.method.value} ${request.local.uri}", cause)
    respondProblem(HttpStatusCode.InternalServerError, "An unexpected error occurred")
}

// ContentNegotiation wraps every converter failure in BadRequestException("Failed to convert
// request body to class ch.nokillswit...", JsonConvertException) — the FQCN is internal
// structure in a client-facing body (MT-007; API-ERR-003) and the text is Ktor-version
// -dependent, so it is replaced with fixed vocabulary. The wrap always carries a
// ContentConvertException in its cause chain; our own validators throw BadRequestException
// with no such cause, so their intentional messages pass through untouched.
private fun Throwable.hasContentConvertCause(): Boolean =
    // Starts at the CAUSE on purpose: a validator's own BadRequestException has no wrap.
    cause?.anyInChain { it is ContentConvertException } == true

// The Resources plugin's path/query decode failures arrive as BadRequestException with this
// exact message (an overflowing id, a non-numeric segment) — equally internal vocabulary.
private const val RESOURCE_DECODE_MESSAGE = "Can't transform call to resource"

private fun clientSafeBadRequestDetail(cause: BadRequestException): String = when {
    cause.hasContentConvertCause() -> "Request body is invalid or does not match the expected schema"
    cause.message == RESOURCE_DECODE_MESSAGE -> "Path or query parameter is malformed"
    else -> cause.message ?: "Bad request"
}

// Path ids are unsigned (UInt) end-to-end, but kotlinx's UInt decoding parses via
// toInt().toUInt() — a negative segment like /users/-1 silently WRAPPED to 4294967295 and
// flowed into the normal lookup instead of failing (MT-005), contradicting the spec's
// `minimum: 0`. No legitimate /api/ path has a negative-integer segment.
private val NEGATIVE_ID_SEGMENT = Regex("""^-\d+$""")

private fun ApplicationCall.hasNegativeIdSegment(): Boolean {
    val path = request.path()
    return path.startsWith("/api/") &&
        path.split('/').any { NEGATIVE_ID_SEGMENT.matches(it.decodeURLPart()) }
}

fun Application.configureErrorHandling() {
    install(StatusPages) {
        // The per-IP RateLimit plugin rejects with a bodiless 429; give it the same RFC 7807
        // body every other error carries. NOTE: this status handler intercepts EVERY 429 that
        // StatusPages itself didn't produce — so caller-specific 429s (the login lockout) must
        // go through TooManyRequestsException below (handled calls are marked and skipped),
        // never a direct respondProblem, or their specific detail would be replaced.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondProblem(status, "Rate limit exceeded — retry later")
        }
        // Routing's wrong-method-on-an-existing-path rejection is a bodiless 405; give it the
        // RFC 7807 body every other error carries (MT-004; API-ERR-001). No `Allow` header —
        // Ktor does not surface the allowed-method set at this point (noted in the guidelines).
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(status, "Method not allowed for this resource")
        }
        exception<TooManyRequestsException> { call, cause ->
            call.respondProblem(HttpStatusCode.TooManyRequests, cause.message ?: "Too many requests")
        }
        // The global RequestBodyLimit plugin (plugins/Http.kt) rejects oversized bodies with
        // this exception; give the 413 the RFC 7807 body every other error carries.
        exception<PayloadTooLargeException> { call, _ ->
            call.respondProblem(HttpStatusCode.PayloadTooLarge, "Request body exceeds the size limit")
        }
        exception<BadRequestException> { call, cause ->
            call.respondProblem(HttpStatusCode.BadRequest, clientSafeBadRequestDetail(cause))
        }
        // A POST/PUT with NO Content-Type (a truly body-less request) never enters
        // ContentNegotiation (no converter matches ContentType.Any), so `call.receive` throws
        // this instead of the BadRequestException the converter path wraps malformed JSON in —
        // and it used to escape to the 500 catch-all (v2.34.0; the 22021 central-mapping
        // precedent). Deliberately NOT the abstract ContentTransformationException parent:
        // that would mislabel PayloadTooLarge (413, mapped above) / a future
        // UnsupportedMediaType (415).
        exception<CannotTransformContentToTypeException> { call, _ ->
            if (call.request.headers[HttpHeaders.ContentType] == null) {
                call.respondProblem(HttpStatusCode.BadRequest, "Request body is missing or not JSON")
            } else {
                call.respondProblem(HttpStatusCode.UnsupportedMediaType, "Request body must use application/json")
            }
        }
        exception<UnauthorizedException> { call, cause ->
            call.respondProblem(HttpStatusCode.Unauthorized, cause.message ?: "Unauthorized")
        }
        exception<ForbiddenException> { call, cause ->
            // Denials are part of the security audit trail: who tried what they may not do.
            audit(
                "authz.denied",
                "method" to call.request.local.method.value,
                "path" to call.request.local.uri,
                "byUserId" to call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong(),
                "detail" to cause.message,
            )
            call.respondProblem(HttpStatusCode.Forbidden, cause.message ?: "Forbidden")
        }
        exception<NotFoundException> { call, cause ->
            call.respondProblem(HttpStatusCode.NotFound, cause.message ?: "Resource not found")
        }
        exception<ConflictException> { call, cause ->
            call.respondProblem(HttpStatusCode.Conflict, cause.message ?: "Conflict")
        }
        exception<BadGatewayException> { call, cause ->
            call.respondProblem(HttpStatusCode.BadGateway, cause.message ?: "Upstream request failed")
        }
        // Non-unique DB failures fall through to a 500 problem (logged) instead of rethrowing,
        // which would escape StatusPages and yield a bodiless default 500.
        exception<ExposedSQLException> { call, cause ->
            call.respondDbFailure(cause)
        }
        exception<R2dbcException> { call, cause ->
            call.respondDbFailure(cause)
        }
        exception<Throwable> { call, cause ->
            call.respondInternalError(cause)
        }
    }
    // The negative-id rejection (MT-005) runs before routing, so no handler ever sees the
    // silently wrapped value; StatusPages above turns the throw into the problem body.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.hasNegativeIdSegment()) {
            throw BadRequestException("Path id must be a non-negative integer")
        }
    }
}
