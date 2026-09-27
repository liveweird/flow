package ch.nokillswit.users

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.MAX_PASSWORD_BYTES
import ch.nokillswit.auth.exceedsBcryptLimit
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** Minimum accepted password length for create and change. */
const val MIN_PASSWORD_LENGTH = 10

/** Shared password rule for create and change: the minimum plus bcrypt's byte ceiling. */
internal fun validatePassword(password: String) {
    if (password.length < MIN_PASSWORD_LENGTH) {
        throw BadRequestException("Password must be at least $MIN_PASSWORD_LENGTH characters")
    }
    // Longer input would make bcrypt throw (a 500) — see MAX_PASSWORD_BYTES in auth/Passwords.kt.
    if (exceedsBcryptLimit(password)) {
        throw BadRequestException("Password must be at most $MAX_PASSWORD_BYTES bytes in UTF-8")
    }
}

/** Audit format for a roles/features set: comma-joined sorted names, "" = empty set. */
private fun <T : Enum<T>> Set<T>.joinedNames(): String = map { it.name }.sorted().joinToString(",")

/**
 * Name and email are identity/security-relevant (email is the login identifier); the
 * `user.updated` event carries deltas only for the fields that actually changed — and is
 * skipped entirely when neither did.
 */
private fun auditUserUpdated(byUserId: UInt, targetUserId: UInt, existing: User, name: String, email: String) {
    if (name == existing.name && email == existing.email) return
    val fields = mutableListOf<Pair<String, Any?>>(
        "byUserId" to byUserId.toLong(),
        "targetUserId" to targetUserId.toLong(),
    )
    if (name != existing.name) {
        fields += "nameFrom" to existing.name
        fields += "nameTo" to name
    }
    if (email != existing.email) {
        fields += "emailFrom" to existing.email
        fields += "emailTo" to email
    }
    audit("user.updated", *fields.toTypedArray())
}

@Serializable
@Resource("/api/v1/users")
class Users {
    @Serializable
    @Resource("{id}")
    class Id(val parent: Users = Users(), val id: UInt) {
        @Serializable
        @Resource("password")
        class Password(val parent: Id)

        @Serializable
        @Resource("features")
        class Features(val parent: Id)

        @Serializable
        @Resource("language")
        class Language(val parent: Id)
    }
}

fun Application.configureUserRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val userService = attributes[UserServiceKey]

    routing {
        authenticate {
            userList(userService)
            userCreate(userService)
            userReadUpdateDelete(userService)
            userPassword(userService)
            userFeaturesAndLanguage(userService)
        }
    }
}

private fun Route.userList(userService: UserService) {
    // Management surface: ADMIN-only end to end (list included) — regular users only
    // keep the self password change below.
    get<Users> {
        requireAdmin(call.caller())
        val paging = call.parsePaging(sortable = USER_SORT_FIELDS)
        val params = call.request.queryParameters
        val feature = params.optionalEnum<Feature>("feature")
        val featureEnabled = params.optionalBoolean("featureEnabled")
        // The pair rule: the flag-state filter is meaningless as a lone half.
        if ((feature == null) != (featureEnabled == null)) {
            throw BadRequestException("feature and featureEnabled must be supplied together")
        }
        val filter = UserListFilter(
            name = params.optionalString("name"),
            email = params.optionalString("email"),
            role = params.optionalEnum<UserRole>("role"),
            feature = feature,
            featureEnabled = featureEnabled,
        )
        val result = userService.list(filter, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

private fun Route.userCreate(userService: UserService) {
    post<Users> {
        val caller = call.caller()
        // Guarded BEFORE the body decodes — the guard needs nothing from the payload,
        // so a non-admin's malformed body stays 403, not the converter's 400.
        requireAdmin(caller)
        val req = call.receive<UserCreateRequest>()
        val name = sanitizeSingleLine(req.name, "Name")
        val email = canonicalEmail(req.email)
        validateNameAndEmail(name, email)
        validatePassword(req.password)
        validateLanguage(req.language)
        val role = rolesToStored(req.roles)
        // The password arrives client-generated and leaves this handler only as a
        // bcrypt hash — no response ever carries plaintext. A duplicate active email
        // rides the V1 partial index into the central 23505 → 409.
        val user = User(
            name = name,
            email = email,
            passwordHash = hashPassword(req.password),
            role = role,
            language = req.language ?: DEFAULT_LANGUAGE,
        )
        val id = userService.create(user)
        audit(
            "user.created",
            *buildList<Pair<String, Any?>> {
                add("byUserId" to caller.userId.toLong())
                add("newUserId" to id.toLong())
                add("email" to email)
                // As STORED (the request set folded through rolesToStored), not as requested.
                add("roles" to role.asAdditionalRoles().joinedNames())
                if (user.language != DEFAULT_LANGUAGE) add("language" to user.language)
            }.toTypedArray(),
        )
        call.response.header(HttpHeaders.Location, call.application.href(Users.Id(id = id)))
        // Re-read for the response: create() seeds the inverted-default MFA-disabled
        // row (V5 semantics), which the in-memory `user` object doesn't carry.
        val stored = userService.read(id).orVanished("user", id)
        call.respond(HttpStatusCode.Created, stored.toResponse(id))
    }
}

private fun Route.userReadUpdateDelete(userService: UserService) {
    get<Users.Id> { route ->
        // Guard BEFORE read (the documented users idiom): an unauthorized caller gets a
        // uniform 403 whether or not the id exists.
        requireSelfOrAdmin(call.caller(), route.id)
        val user = userService.read(route.id).orNotFound("User")
        call.respond(HttpStatusCode.OK, user.toResponse(route.id))
    }
    put<Users.Id> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        val req = call.receive<UserUpdateRequest>()
        // Payload validation FIRST — 400 wins over 404/409, like the sibling handlers.
        val name = sanitizeSingleLine(req.name, "Name")
        val email = canonicalEmail(req.email)
        validateNameAndEmail(name, email)
        val requestedRole = rolesToStored(req.roles)
        val existing = userService.read(route.id).orNotFound("User")
        // Last-admin protection: demoting the final active administrator would lock
        // everyone out of the management surface. The race-proof check runs inside
        // updateGuarded's transaction; this pre-check only exists for the audit read.
        if (existing.role == UserRole.ADMIN && requestedRole != UserRole.ADMIN &&
            userService.countActiveAdmins() <= 1
        ) {
            throw ConflictException("The last administrator cannot be demoted")
        }
        when (userService.updateGuarded(route.id, name, email, requestedRole)) {
            UserService.GuardedMutation.NOT_FOUND -> throw NotFoundException("User not found")
            UserService.GuardedMutation.LAST_ADMIN ->
                throw ConflictException("The last administrator cannot be demoted")
            UserService.GuardedMutation.DONE -> Unit
        }
        auditUserUpdated(caller.userId, route.id, existing, name = name, email = email)
        if (requestedRole != existing.role) {
            audit(
                "user.roles_changed",
                "byUserId" to caller.userId.toLong(),
                "targetUserId" to route.id.toLong(),
                // As STORED on both sides, and named like user.updated's nameFrom/nameTo.
                "rolesFrom" to existing.additionalRoles.joinedNames(),
                "rolesTo" to requestedRole.asAdditionalRoles().joinedNames(),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    delete<Users.Id> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        // Before the read, so it 403s uniformly: deleting your own account mid-session
        // is a lockout footgun, not a management action.
        if (caller.userId == route.id) {
            throw ForbiddenException("You cannot delete your own account")
        }
        // Existence (404) and the last-admin state (409) are decided inside ONE
        // transaction — same outcome order as before, without the check/mutate race.
        when (userService.deleteGuarded(route.id)) {
            UserService.GuardedMutation.NOT_FOUND -> throw NotFoundException("User not found")
            UserService.GuardedMutation.LAST_ADMIN ->
                throw ConflictException("The last administrator cannot be deleted")
            UserService.GuardedMutation.DONE -> Unit
        }
        audit(
            "user.deleted",
            "byUserId" to caller.userId.toLong(),
            "targetUserId" to route.id.toLong(),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.userPassword(userService: UserService) {
    put<Users.Id.Password> { route ->
        val caller = call.caller()
        requireSelfOrAdmin(caller, route.parent.id)
        val req = call.receive<PasswordUpdateRequest>()
        // Changing one's OWN password always requires the current one (even for an admin);
        // an admin resetting somebody else's does not. Read before update so a wrong
        // current password never mutates anything. Checked BEFORE the length validation
        // so 403 wins over 400 (the convention everywhere else).
        if (caller.userId == route.parent.id) {
            val existing = userService.read(route.parent.id).orNotFound("User")
            if (req.currentPassword == null || !verifyPassword(req.currentPassword, existing.passwordHash)) {
                audit(
                    "password.change_denied",
                    "targetUserId" to route.parent.id.toLong(),
                    "byUserId" to caller.userId.toLong(),
                    "reason" to "wrong_current_password",
                )
                throw ForbiddenException("Current password is missing or incorrect")
            }
        }
        validatePassword(req.password)
        userService.updatePassword(route.parent.id, hashPassword(req.password)).orNotFound("User")
        audit(
            "password.changed",
            "targetUserId" to route.parent.id.toLong(),
            "byUserId" to caller.userId.toLong(),
            "selfChange" to (caller.userId == route.parent.id),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.userFeaturesAndLanguage(userService: UserService) {
    put<Users.Id.Features> { route ->
        val caller = call.caller()
        // Guard before receive: a non-admin's malformed body is 403, not 400. Self-change
        // is deliberately allowed — the users routes are never feature-gated, so an admin
        // can always adjust their own flags back. An unknown feature name fails enum
        // decoding -> BadRequestException -> 400.
        requireAdmin(caller)
        val req = call.receive<UserFeaturesUpdateRequest>()
        val existing = userService.read(route.parent.id).orNotFound("User")
        val requested = req.disabledFeatures.toSet()
        if (userService.setDisabledFeatures(route.parent.id, requested) == 0) {
            throw NotFoundException("User not found")
        }
        if (requested != existing.disabledFeatures) {
            audit(
                "user.features_changed",
                "byUserId" to caller.userId.toLong(),
                "targetUserId" to route.parent.id.toLong(),
                // Named like user.updated's nameFrom/nameTo (the audit convention).
                "featuresFrom" to existing.disabledFeatures.joinedNames(),
                "featuresTo" to requested.joinedNames(),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    // Per-user language (V1, Lettuce's model verbatim): self or ADMIN — the header
    // language switcher is the self-service writer (switching while signed in also
    // saves here); an admin may fix a mis-set language. Idempotent; guard before
    // receive/validate so 403 wins over 400.
    put<Users.Id.Language> { route ->
        val caller = call.caller()
        requireSelfOrAdmin(caller, route.parent.id)
        val req = call.receive<UserLanguageUpdateRequest>()
        validateLanguage(req.language)
        val existing = userService.read(route.parent.id).orNotFound("User")
        if (userService.setLanguage(route.parent.id, req.language) == 0) {
            throw NotFoundException("User not found")
        }
        if (req.language != existing.language) {
            audit(
                "user.language_changed",
                "byUserId" to caller.userId.toLong(),
                "targetUserId" to route.parent.id.toLong(),
                "from" to existing.language,
                "to" to req.language,
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
