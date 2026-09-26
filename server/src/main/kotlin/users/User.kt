package ch.nokillswit.users

import kotlinx.serialization.Serializable

/**
 * The stored account role. Unlike a plain capability set, storage is a single column with a
 * CHECK constraint (V1): every account is exactly one of these, [USER] being the baseline.
 * On the wire (JWT `roles` claim, LoginResponse.roles) only ADDITIONAL roles travel — a
 * regular user serializes as the empty array — so adding a future role never changes the
 * shape for existing accounts.
 *
 * [ADMIN] — the management role: may reset any user's password (and future admin surfaces).
 * [USER] — the implicit baseline; never transmitted.
 */
@Serializable
enum class UserRole { ADMIN, USER }

/**
 * Per-user feature flags (V5) — stored as the DISABLED set (`user_disabled_features`,
 * no row = enabled), replaced wholesale by `PUT /users/{id}/features` (ADMIN). The Kotlin
 * enum is the whitelist — a future gateable feature is just a new value here (no migration).
 * [MFA] is the one INVERTED-DEFAULT, login-scoped flag: every user starts with the disabled
 * row present (the V5 seed + the `UserService.create` chokepoint), so email MFA is opt-in;
 * it gates the LOGIN FLOW only — no route names it in `requireFeatureEnabled` (which awaits
 * its first area-gating consumer).
 */
@Serializable
enum class Feature { MFA }

@Serializable
data class User(
    val name: String,
    val email: String,
    val passwordHash: String,
    val role: UserRole = UserRole.USER,
    // Per-user feature flags (V5) — the DISABLED set, empty = full access. Rides the domain
    // object like the role so /login and /refresh mint the claim from the same read. Never
    // client-settable via PUT — replaced only by PUT /users/{id}/features (ADMIN).
    val disabledFeatures: Set<Feature> = emptySet(),
    // Epoch millis of the last password change (0 = never). Retained as account metadata;
    // refresh invalidation uses the exact monotonic revision below.
    val passwordChangedAt: Long = 0,
    // Incremented atomically with every password mutation and copied into refresh tokens.
    // Equality on /refresh avoids timestamp precision and clock-ordering ambiguity.
    val credentialRevision: Long = 0,
    // Per-user language (V1, Lettuce's V61): the UI language at sign-in and the language
    // of every email sent to the user. Set at create; never client-settable via the
    // whole-user PUT — changed only by PUT /users/{id}/language (target user or ADMIN).
    val language: String = "en",
) {
    /** The wire/claim shape: additional roles only — empty for a regular user. */
    val additionalRoles: Set<UserRole>
        get() = role.asAdditionalRoles()
}

/** The wire/audit shape of one stored role: additional roles only — empty for the baseline. */
fun UserRole.asAdditionalRoles(): Set<UserRole> =
    if (this == UserRole.ADMIN) setOf(UserRole.ADMIN) else emptySet()

@Serializable
data class UserCreateRequest(
    val name: String,
    val email: String,
    // Client-generated (the SPA's utils/password.ts) and shown to the admin exactly once —
    // the server stores only the bcrypt hash and never returns plaintext.
    val password: String,
    val roles: List<UserRole>? = null,
    /** Create-only (the whole-user PUT never touches it); omitted = English. */
    val language: String? = null,
)

@Serializable
data class UserUpdateRequest(
    val name: String,
    val email: String,
    val roles: List<UserRole>,
)

@Serializable
data class UserResponse(
    val id: UInt,
    val name: String,
    val email: String,
    /** Additional roles only — empty for a regular user (the standing wire shape). */
    val roles: List<UserRole>,
    /** Per-user feature flags (V5) — the admin-disabled set; empty = full access. */
    val disabledFeatures: List<Feature>,
    /** The stored per-user language (V1) — the ONE synced language (UI + emails). */
    val language: String,
)

fun User.toResponse(id: UInt) = UserResponse(
    id = id,
    name = name,
    email = email,
    roles = additionalRoles.sortedBy { it.name },
    disabledFeatures = disabledFeatures.sortedBy { it.name },
    language = language,
)

/** Body of PUT /users/{id}/language (target user or ADMIN — the switcher's save). */
@Serializable
data class UserLanguageUpdateRequest(val language: String)

/** Wholesale replacement of a user's disabled-feature set (PUT /users/{id}/features). */
@Serializable
data class UserFeaturesUpdateRequest(val disabledFeatures: List<Feature>)

typealias UserPageResponse = ch.nokillswit.infra.paging.PageResponse<UserResponse>

/** The single stored role from a wire roles set: ADMIN present wins, else the baseline. */
fun rolesToStored(roles: Collection<UserRole>?): UserRole =
    if (roles != null && UserRole.ADMIN in roles) UserRole.ADMIN else UserRole.USER

@Serializable
data class PasswordUpdateRequest(
    val password: String,
    // Required when a caller changes their OWN password (even an admin); not required
    // for an admin resetting somebody else's.
    val currentPassword: String? = null,
)
