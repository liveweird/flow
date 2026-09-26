package ch.nokillswit.authz

class UnauthorizedException(message: String = "Authentication required") : RuntimeException(message)

class ForbiddenException(message: String = "Forbidden") : RuntimeException(message)

/**
 * Addressed resource does not exist (or is soft-deleted / lives under a different parent path)
 * → 404 with the given detail. Routes throw it from read preambles and zero-row mutation
 * results instead of hand-rolling a respondProblem + return.
 */
class NotFoundException(message: String = "Resource not found") : RuntimeException(message)

/** The route 404 convention as a helper: a null read → `"<resource> not found"`. */
fun <T : Any> T?.orNotFound(resource: String): T = this ?: throw NotFoundException("$resource not found")

/** The zero-row-mutation half of the same convention. */
fun Int.orNotFound(resource: String): Int =
    if (this == 0) throw NotFoundException("$resource not found") else this

/**
 * Requested action conflicts with the resource's current state (e.g. an invalid status
 * transition, or the last-admin protections) → 409 with the given detail.
 */
class ConflictException(message: String = "Conflict") : RuntimeException(message)

/**
 * Caller-specific throttling (e.g. the per-account login lockout) → 429 with the given detail.
 * Distinct from the per-IP RateLimit plugin's bodiless 429, which the StatusPages
 * `status(TooManyRequests)` handler completes with a generic problem body.
 */
class TooManyRequestsException(message: String = "Too many requests") : RuntimeException(message)

/**
 * An UPSTREAM failure while acting on the caller's behalf (the catalog URL fetch: the remote
 * answered non-200, redirected, timed out, or overflowed the size cap) → 502 with the given
 * detail. Deliberately distinct from 400 — the request was well-formed; the other side failed.
 */
class BadGatewayException(message: String = "Upstream request failed") : RuntimeException(message)
