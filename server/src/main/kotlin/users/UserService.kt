package ch.nokillswit.users

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val UserServiceKey = AttributeKey<UserService>("UserService")

data class UserListFilter(
    val name: String? = null,
    val email: String? = null,
    val role: UserRole? = null,
    /**
     * Feature-flag state filter — the pair travels together (the route 400s a lone half):
     * [feature] names the flag, [featureEnabled] the state to match.
     */
    val feature: Feature? = null,
    val featureEnabled: Boolean? = null,
)

data class UserListResult(
    val items: List<UserResponse>,
    val total: Long,
)

// Deliberately no "role": the wire shape is a set, and a set has no order (the Lettuce rule).
private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to UserService.Users.id,
    "name" to UserService.Users.name,
    "email" to UserService.Users.email,
)

/** The ONE sortable whitelist — the route's `parsePaging` argument derives from the column map
 *  above, so the two can never drift apart (a mismatch used to be a runtime 500). */
val USER_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class UserService(private val database: R2dbcDatabase) {
    object Users : UIntIdTable(), SoftDeletable {
        val name = varchar("name", length = 50)
        // Uniqueness is enforced by a partial unique index (active rows only) in migration V1,
        // so a soft-deleted user frees its email. Exposed table defs are query-only (not DDL),
        // so this column carries no `.uniqueIndex()`.
        val email = varchar("email", length = 254)
        val passwordHash = varchar("password_hash", length = 255)
        // Single-column role storage (CHECK in V1); UserRole.USER is the baseline.
        val role = varchar("role", length = 20).default(UserRole.USER.name)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        val passwordChangedAt = long("password_changed_at").default(0)
        val credentialRevision = long("credential_revision").default(0)
        // Per-user language (V1): sign-in UI language + the language of every email sent
        // to the user. No CHECK — SUPPORTED_LANGUAGES is the whitelist.
        val language = varchar("language", length = 10).default("en")
    }

    // Per-user feature flags (V5) — the DISABLED set; no row = enabled.
    object UserDisabledFeatures : Table("user_disabled_features") {
        val userId = reference("user_id", Users)
        val feature = varchar("feature", length = 30)
        override val primaryKey = PrimaryKey(userId, feature)
    }

    suspend fun create(user: User): UInt = suspendTransaction(database) {
        // Re-checked service-side so direct callers stay guarded (the feature-template rule);
        // the plaintext-password rules can't apply here — only the bcrypt hash arrives.
        validateNameAndEmail(user.name, user.email)
        validateLanguage(user.language)
        val newRecord = Users.insert {
            it[name] = user.name
            it[email] = canonicalEmail(user.email)
            it[passwordHash] = user.passwordHash
            it[role] = user.role.name
            it[language] = user.language
        }
        val id = newRecord[Users.id].value
        // MFA is the one inverted-default flag (opt-in): every new user starts with the
        // disabled row present, mirroring the V5 seed for pre-existing users. All creation
        // paths (admin create, test seeds) funnel through here.
        UserDisabledFeatures.insert {
            it[UserDisabledFeatures.userId] = id
            it[UserDisabledFeatures.feature] = Feature.MFA.name
        }
        id
    }

    suspend fun read(id: UInt): User? = suspendTransaction(database) {
        Users.selectAll()
            .where { (Users.id eq id) and Users.active() }
            .toList()
            .singleOrNull()
            ?.let { it.toUser(featuresOf(it[Users.id].value)) }
    }

    suspend fun findWithIdByEmail(email: String): Pair<UInt, User>? = suspendTransaction(database) {
        // Stored emails are canonical; folding the argument too is defense-in-depth so a
        // caller that skipped canonicalEmail still matches.
        Users.selectAll()
            .where { (Users.email eq canonicalEmail(email)) and Users.active() }
            .toList()
            .singleOrNull()
            ?.let { it[Users.id].value to it.toUser(featuresOf(it[Users.id].value)) }
    }

    // The plaintext rules (min length, bcrypt byte ceiling) are route-side by necessity:
    // only the bcrypt hash ever reaches the service.
    suspend fun updatePassword(id: UInt, passwordHash: String): Int = suspendTransaction(database) {
        Users.update({ (Users.id eq id) and Users.active() }) {
            it[this.passwordHash] = passwordHash
            // The revision increment shares the hash update, so no token can observe a new
            // password with the old credential generation (or vice versa).
            it[passwordChangedAt] = System.currentTimeMillis()
            it[credentialRevision] = Users.credentialRevision + 1
        }
    }

    /**
     * Set the per-user language (V1). Returns 1, or 0 when the id is unknown or
     * soft-deleted (the route 404s). Idempotent.
     */
    suspend fun setLanguage(id: UInt, language: String): Int = suspendTransaction(database) {
        validateLanguage(language) // re-checked service-side so direct callers stay guarded
        Users.update({ (Users.id eq id) and Users.active() }) {
            it[Users.language] = language
        }
    }

    suspend fun list(filter: UserListFilter, paging: PageRequest): UserListResult =
        suspendTransaction(database) {
            val predicate: Op<Boolean> = buildPredicate(filter) and Users.active()
            val total = Users.selectAll().where { predicate }.count()
            val rows = Users.selectAll()
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .toList()
            // One batch query for the page's disabled sets — not a per-row lookup.
            val featuresByUser = disabledFeaturesByUserIds(rows.map { it[Users.id].value })
            val items = rows.map { row ->
                val id = row[Users.id].value
                row.toUser(featuresByUser[id].orEmpty().toSet()).toResponse(id)
            }
            UserListResult(items = items, total = total)
        }

    /**
     * Wholesale-replace the user's disabled-feature set (V5). Returns 1, or 0 when the id is
     * unknown or soft-deleted (the route 404s). Idempotent — a same-set re-PUT is a no-op
     * replace, not a transition.
     */
    suspend fun setDisabledFeatures(id: UInt, features: Set<Feature>): Int = suspendTransaction(database) {
        val exists = Users.select(Users.id)
            .where { (Users.id eq id) and Users.active() }
            .toList()
            .isNotEmpty()
        if (!exists) return@suspendTransaction 0
        UserDisabledFeatures.deleteWhere { UserDisabledFeatures.userId eq id }
        features.forEach { f ->
            UserDisabledFeatures.insert {
                it[userId] = id
                it[feature] = f.name
            }
        }
        1
    }

    /** Outcome of a last-admin-guarded mutation (see [updateGuarded]/[deleteGuarded]). */
    enum class GuardedMutation { DONE, NOT_FOUND, LAST_ADMIN }

    /**
     * Updates the identity fields + role inside ONE transaction with the last-admin check —
     * password and passwordChangedAt stay untouched. The check and the mutation share the
     * transaction (admin rows locked via [lockedActiveAdminCount]), so two concurrent demotes
     * cannot both observe "two admins left" and demote both — the read-then-count-then-update
     * split across separate transactions was a TOCTOU.
     */
    suspend fun updateGuarded(id: UInt, name: String, email: String, role: UserRole): GuardedMutation =
        suspendTransaction(database) {
            validateNameAndEmail(name, email) // re-checked service-side so direct callers stay guarded
            val existingRole = activeRole(id) ?: return@suspendTransaction GuardedMutation.NOT_FOUND
            if (existingRole == UserRole.ADMIN && role != UserRole.ADMIN && lockedActiveAdminCount() <= 1) {
                return@suspendTransaction GuardedMutation.LAST_ADMIN
            }
            val rows = Users.update({ (Users.id eq id) and Users.active() }) {
                it[Users.name] = name
                // Canonical identity, folded here too (defense-in-depth like findWithIdByEmail).
                it[Users.email] = canonicalEmail(email)
                it[Users.role] = role.name
            }
            if (rows == 0) GuardedMutation.NOT_FOUND else GuardedMutation.DONE
        }

    /**
     * Soft delete with the last-admin check in the SAME transaction (see [updateGuarded]):
     * blocks login, refresh rejects `user_gone`, the V1 partial index frees the email.
     */
    suspend fun deleteGuarded(id: UInt): GuardedMutation = suspendTransaction(database) {
        val existingRole = activeRole(id) ?: return@suspendTransaction GuardedMutation.NOT_FOUND
        if (existingRole == UserRole.ADMIN && lockedActiveAdminCount() <= 1) {
            return@suspendTransaction GuardedMutation.LAST_ADMIN
        }
        val rows = Users.update({ (Users.id eq id) and Users.active() }) {
            it[markedAsDeleted] = true
        }
        if (rows == 0) GuardedMutation.NOT_FOUND else GuardedMutation.DONE
    }

    /** Backs the routes' fast-path 409 pre-checks (the ordering gate; correctness lives in
     *  the guarded mutations above). */
    suspend fun countActiveAdmins(): Long = suspendTransaction(database) {
        Users.selectAll().where { (Users.role eq UserRole.ADMIN.name) and Users.active() }.count()
    }

    private suspend fun activeRole(id: UInt): UserRole? =
        Users.select(Users.role)
            .where { (Users.id eq id) and Users.active() }
            .toList()
            .singleOrNull()
            ?.let { UserRole.valueOf(it[Users.role]) }

    // FOR UPDATE on the active-admin rows serializes concurrent admin mutations: the second
    // transaction blocks on the first's locks and re-evaluates the predicate after its commit,
    // so a demoted/deleted row no longer counts. Admins are few — counting in memory is fine.
    private suspend fun lockedActiveAdminCount(): Int =
        Users.selectAll()
            .where { (Users.role eq UserRole.ADMIN.name) and Users.active() }
            .forUpdate()
            .toList()
            .size

    private fun buildPredicate(filter: UserListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and (Users.name.containsNormalized(it)) }
        filter.email?.takeIf { it.isNotBlank() }?.let { op = op and (Users.email.containsNormalized(it)) }
        filter.role?.let { op = op and (Users.role eq it.name) }
        filter.feature?.let { f ->
            // The route guarantees featureEnabled is non-null whenever feature is set.
            val disabled = UserDisabledFeatures
                .select(UserDisabledFeatures.userId)
                .where { UserDisabledFeatures.feature eq f.name }
            op = op and if (filter.featureEnabled == false) {
                Users.id inSubQuery disabled
            } else {
                Users.id notInSubQuery disabled
            }
        }
        return op
    }

    /** Bootstrap: rotate a user's password only while they still carry [expectedHash]. */
    suspend fun rotatePasswordIfHashMatches(email: String, expectedHash: String, newHash: String): Int =
        suspendTransaction(database) {
            Users.update({ (Users.email eq email) and (Users.passwordHash eq expectedHash) and Users.active() }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = System.currentTimeMillis()
                it[credentialRevision] = Users.credentialRevision + 1
            }
        }

    /** Bootstrap: how many active accounts still carry [hash] (the well-known seed password). */
    suspend fun countActiveWithPasswordHash(hash: String): Long = suspendTransaction(database) {
        Users.selectAll().where { (Users.passwordHash eq hash) and Users.active() }.count()
    }

    private suspend fun featuresOf(id: UInt): Set<Feature> =
        UserDisabledFeatures.selectAll()
            .where { UserDisabledFeatures.userId eq id }
            .map { Feature.valueOf(it[UserDisabledFeatures.feature]) }
            .toList()
            .toSet()

    private suspend fun disabledFeaturesByUserIds(ids: List<UInt>): Map<UInt, List<Feature>> =
        if (ids.isEmpty()) emptyMap()
        else UserDisabledFeatures.selectAll()
            .where { UserDisabledFeatures.userId inList ids }
            .toList()
            .groupBy({ it[UserDisabledFeatures.userId].value }, { Feature.valueOf(it[UserDisabledFeatures.feature]) })

    private fun ResultRow.toUser(disabledFeatures: Set<Feature> = emptySet()): User = User(
        name = this[Users.name],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        role = UserRole.valueOf(this[Users.role]),
        disabledFeatures = disabledFeatures,
        passwordChangedAt = this[Users.passwordChangedAt],
        credentialRevision = this[Users.credentialRevision],
        language = this[Users.language],
    )
}
