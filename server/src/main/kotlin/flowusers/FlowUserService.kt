package ch.nokillswit.flowusers

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val FlowUserServiceKey = AttributeKey<FlowUserService>("FlowUserService")

data class FlowUserListFilter(
    val name: String? = null,
    val email: String? = null,
    val role: FlowUserRole? = null,
    /**
     * Feature-flag state filter — the pair travels together (the route 400s a lone half):
     * [feature] names the flag, [featureEnabled] the state to match.
     */
    val feature: Feature? = null,
    val featureEnabled: Boolean? = null,
)

data class FlowUserListResult(
    val items: List<FlowUserResponse>,
    val total: Long,
)

// Deliberately no "role": the wire shape is a set, and a set has no order (the Lettuce rule).
private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to FlowUserService.FlowUsers.id,
    "name" to FlowUserService.FlowUsers.name,
    "email" to FlowUserService.FlowUsers.email,
)

/** The ONE sortable whitelist — the route's `parsePaging` argument derives from the column map
 *  above, so the two can never drift apart (a mismatch used to be a runtime 500). */
val FLOW_USER_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class FlowUserService(private val database: R2dbcDatabase) {
    object FlowUsers : UIntIdTable("users"), SoftDeletable {
        val name = varchar("name", length = 50)
        // Uniqueness is enforced by a partial unique index (active rows only) in migration V1,
        // so a soft-deleted user frees its email. Exposed table defs are query-only (not DDL),
        // so this column carries no `.uniqueIndex()`.
        val email = varchar("email", length = 254)
        val passwordHash = varchar("password_hash", length = 255)
        // Single-column role storage (CHECK in V1); FlowUserRole.USER is the baseline.
        val role = varchar("role", length = 20).default(FlowUserRole.USER.name)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        val passwordChangedAt = long("password_changed_at").default(0)
        val credentialRevision = long("credential_revision").default(0)
        // Per-user language (V1): sign-in UI language + the language of every email sent
        // to the user. No CHECK — SUPPORTED_LANGUAGES is the whitelist.
        val language = varchar("language", length = 10).default("en")
    }

    // Per-user feature flags (V5) — the DISABLED set; no row = enabled.
    object UserDisabledFeatures : Table("user_disabled_features") {
        val userId = reference("user_id", FlowUsers)
        val feature = varchar("feature", length = 30)
        override val primaryKey = PrimaryKey(userId, feature)
    }

    suspend fun create(user: FlowUser): UInt = suspendTransaction(database) {
        // Re-checked service-side so direct callers stay guarded (the feature-template rule);
        // the plaintext-password rules can't apply here — only the bcrypt hash arrives.
        validateNameAndEmail(user.name, user.email)
        validateLanguage(user.language)
        val newRecord = FlowUsers.insert {
            it[name] = user.name
            it[email] = canonicalEmail(user.email)
            it[passwordHash] = user.passwordHash
            it[role] = user.role.name
            it[language] = user.language
        }
        val id = newRecord[FlowUsers.id].value
        // MFA is the one inverted-default flag (opt-in): every new user starts with the
        // disabled row present, mirroring the V5 seed for pre-existing users. All creation
        // paths (admin create, test seeds) funnel through here.
        UserDisabledFeatures.insert {
            it[UserDisabledFeatures.userId] = id
            it[UserDisabledFeatures.feature] = Feature.MFA.name
        }
        id
    }

    suspend fun read(id: UInt): FlowUser? = suspendTransaction(database) {
        FlowUsers.selectAll()
            .where { (FlowUsers.id eq id) and FlowUsers.active() }
            .toList()
            .singleOrNull()
            ?.let { it.toFlowUser(featuresOf(it[FlowUsers.id].value)) }
    }

    suspend fun findWithIdByEmail(email: String): Pair<UInt, FlowUser>? = suspendTransaction(database) {
        // Stored emails are canonical; folding the argument too is defense-in-depth so a
        // caller that skipped canonicalEmail still matches.
        FlowUsers.selectAll()
            .where { (FlowUsers.email eq canonicalEmail(email)) and FlowUsers.active() }
            .toList()
            .singleOrNull()
            ?.let { it[FlowUsers.id].value to it.toFlowUser(featuresOf(it[FlowUsers.id].value)) }
    }

    // The plaintext rules (min length, bcrypt byte ceiling) are route-side by necessity:
    // only the bcrypt hash ever reaches the service.
    suspend fun updatePassword(id: UInt, passwordHash: String): Int = suspendTransaction(database) {
        updatePasswordInTransaction(id, passwordHash, expectedRevision = null)
    }

    /**
     * Set the per-user language (V1). Returns 1, or 0 when the id is unknown or
     * soft-deleted (the route 404s). Idempotent.
     */
    suspend fun setLanguage(id: UInt, language: String): Int = suspendTransaction(database) {
        validateLanguage(language) // re-checked service-side so direct callers stay guarded
        FlowUsers.update({ (FlowUsers.id eq id) and FlowUsers.active() }) {
            it[FlowUsers.language] = language
        }
    }

    suspend fun list(filter: FlowUserListFilter, paging: PageRequest): FlowUserListResult =
        suspendTransaction(database) {
            val predicate: Op<Boolean> = buildPredicate(filter) and FlowUsers.active()
            val total = FlowUsers.selectAll().where { predicate }.count()
            val rows = FlowUsers.selectAll()
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .toList()
            // One batch query for the page's disabled sets — not a per-row lookup.
            val featuresByUser = disabledFeaturesByUserIds(rows.map { it[FlowUsers.id].value })
            val items = rows.map { row ->
                val id = row[FlowUsers.id].value
                row.toFlowUser(featuresByUser[id].orEmpty().toSet()).toResponse(id)
            }
            FlowUserListResult(items = items, total = total)
        }

    /**
     * Wholesale-replace the user's disabled-feature set (V5). Returns 1, or 0 when the id is
     * unknown or soft-deleted (the route 404s). Idempotent — a same-set re-PUT is a no-op
     * replace, not a transition.
     */
    suspend fun setDisabledFeatures(id: UInt, features: Set<Feature>): Int = suspendTransaction(database) {
        val exists = FlowUsers.select(FlowUsers.id)
            .where { (FlowUsers.id eq id) and FlowUsers.active() }
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
    suspend fun updateGuarded(id: UInt, name: String, email: String, role: FlowUserRole): GuardedMutation =
        suspendTransaction(database) {
            validateNameAndEmail(name, email) // re-checked service-side so direct callers stay guarded
            val existingRole = activeRole(id) ?: return@suspendTransaction GuardedMutation.NOT_FOUND
            if (existingRole == FlowUserRole.ADMIN && role != FlowUserRole.ADMIN && lockedActiveAdminCount() <= 1) {
                return@suspendTransaction GuardedMutation.LAST_ADMIN
            }
            val rows = FlowUsers.update({ (FlowUsers.id eq id) and FlowUsers.active() }) {
                it[FlowUsers.name] = name
                // Canonical identity, folded here too (defense-in-depth like findWithIdByEmail).
                it[FlowUsers.email] = canonicalEmail(email)
                it[FlowUsers.role] = role.name
            }
            if (rows == 0) GuardedMutation.NOT_FOUND else GuardedMutation.DONE
        }

    /**
     * Soft delete with the last-admin check in the SAME transaction (see [updateGuarded]):
     * blocks login, refresh rejects `user_gone`, the V1 partial index frees the email.
     */
    suspend fun deleteGuarded(id: UInt): GuardedMutation = suspendTransaction(database) {
        val existingRole = activeRole(id) ?: return@suspendTransaction GuardedMutation.NOT_FOUND
        if (existingRole == FlowUserRole.ADMIN && lockedActiveAdminCount() <= 1) {
            return@suspendTransaction GuardedMutation.LAST_ADMIN
        }
        val rows = FlowUsers.update({ (FlowUsers.id eq id) and FlowUsers.active() }) {
            it[markedAsDeleted] = true
        }
        if (rows == 0) GuardedMutation.NOT_FOUND else GuardedMutation.DONE
    }

    /** Backs the routes' fast-path 409 pre-checks (the ordering gate; correctness lives in
     *  the guarded mutations above). */
    suspend fun countActiveAdmins(): Long = suspendTransaction(database) {
        FlowUsers.selectAll().where { (FlowUsers.role eq FlowUserRole.ADMIN.name) and FlowUsers.active() }.count()
    }

    private suspend fun activeRole(id: UInt): FlowUserRole? =
        FlowUsers.select(FlowUsers.role)
            .where { (FlowUsers.id eq id) and FlowUsers.active() }
            .toList()
            .singleOrNull()
            ?.let { FlowUserRole.valueOf(it[FlowUsers.role]) }

    // FOR UPDATE on the active-admin rows serializes concurrent admin mutations: the second
    // transaction blocks on the first's locks and re-evaluates the predicate after its commit,
    // so a demoted/deleted row no longer counts. Admins are few — counting in memory is fine.
    private suspend fun lockedActiveAdminCount(): Int =
        FlowUsers.selectAll()
            .where { (FlowUsers.role eq FlowUserRole.ADMIN.name) and FlowUsers.active() }
            .forUpdate()
            .toList()
            .size

    private fun buildPredicate(filter: FlowUserListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and (FlowUsers.name.containsNormalized(it)) }
        filter.email?.takeIf { it.isNotBlank() }?.let { op = op and (FlowUsers.email.containsNormalized(it)) }
        filter.role?.let { op = op and (FlowUsers.role eq it.name) }
        filter.feature?.let { f ->
            // The route guarantees featureEnabled is non-null whenever feature is set.
            val disabled = UserDisabledFeatures
                .select(UserDisabledFeatures.userId)
                .where { UserDisabledFeatures.feature eq f.name }
            op = op and if (filter.featureEnabled == false) {
                FlowUsers.id inSubQuery disabled
            } else {
                FlowUsers.id notInSubQuery disabled
            }
        }
        return op
    }

    /** Bootstrap: rotate a user's password only while they still carry [expectedHash]. */
    suspend fun rotatePasswordIfHashMatches(email: String, expectedHash: String, newHash: String): Int =
        suspendTransaction(database) {
            FlowUsers.update({ (FlowUsers.email eq email) and (FlowUsers.passwordHash eq expectedHash) and FlowUsers.active() }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = nowMillis()
                it[credentialRevision] = FlowUsers.credentialRevision + 1
            }
        }

    /** Bootstrap: how many active accounts still carry [hash] (the well-known seed password). */
    suspend fun countActiveWithPasswordHash(hash: String): Long = suspendTransaction(database) {
        FlowUsers.selectAll().where { (FlowUsers.passwordHash eq hash) and FlowUsers.active() }.count()
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

    private fun ResultRow.toFlowUser(disabledFeatures: Set<Feature> = emptySet()): FlowUser = FlowUser(
        name = this[FlowUsers.name],
        email = this[FlowUsers.email],
        passwordHash = this[FlowUsers.passwordHash],
        role = FlowUserRole.valueOf(this[FlowUsers.role]),
        disabledFeatures = disabledFeatures,
        passwordChangedAt = this[FlowUsers.passwordChangedAt],
        credentialRevision = this[FlowUsers.credentialRevision],
        language = this[FlowUsers.language],
    )
}

/**
 * The ONE password write (the admin PUT and the reset confirmation both land here); the caller owns the
 * transaction, so a reset grant's consumption and the credential change commit together. A non-null
 * [expectedRevision] makes the write conditional on the account's credential generation. The revision
 * increment shares the hash update, so no token can observe a new password with the old credential
 * generation (or vice versa).
 */
internal suspend fun updatePasswordInTransaction(
    id: UInt,
    passwordHash: String,
    expectedRevision: Long?,
    changedAt: Long = nowMillis(),
): Int = with(FlowUserService.FlowUsers) {
    update({
        (FlowUserService.FlowUsers.id eq id) and active() and
            (expectedRevision?.let { credentialRevision eq it } ?: Op.TRUE)
    }) {
        it[FlowUserService.FlowUsers.passwordHash] = passwordHash
        it[passwordChangedAt] = changedAt
        it[credentialRevision] = credentialRevision + 1
    }
}
