package ch.nokillswit.integration

import ch.nokillswit.auth.generateOpaqueToken
import ch.nokillswit.flowusers.FlowUserService.FlowUsers
import ch.nokillswit.infra.json.sha256Hex
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ServiceTokenServiceKey = AttributeKey<ServiceTokenService>("ServiceTokenService")

/** The registry list is capped: a registry past this many tokens is not a scenario (`{items}` is for bounded, admin-curated sets). */
const val SERVICE_TOKEN_LIST_LIMIT = 100

enum class ServiceTokenRevokeOutcome { REVOKED, NOT_FOUND, ALREADY_REVOKED }

/** Why [ServiceTokenService.authenticate] refused: the audit reason; on the wire every one of them is the same 401. */
enum class ServiceTokenRejection { UNKNOWN_OR_REVOKED, EXPIRED }

/** What [ServiceTokenService.authenticate] decided about one presented bearer value. */
sealed interface ServiceTokenVerdict {
    /** A live token. [lastUsedAtBefore] is its `last_used_at` BEFORE this call's stamp (null = never used). */
    data class Live(
        val tokenId: UInt,
        val name: String,
        val createdAt: Long,
        val expiresAt: Long?,
        val lastUsedAtBefore: Long?,
    ) : ServiceTokenVerdict

    /** Refused. [tokenId] is known only when a row matched the digest (an expired token, or one revoked/expired in a race), else null. */
    data class Rejected(val reason: ServiceTokenRejection, val tokenId: UInt? = null) : ServiceTokenVerdict
}

/**
 * The service-token registry (V48): ADMIN-issued, read-only machine credentials. A token is immutable: create (shown once), revoke
 * (terminal; `revoked_at` IS the removal), nothing else. Only the SHA-256 hex digest of the bearer value is stored, and the lookup is
 * SQL equality on that digest, so no constant-time compare is needed (the V35 reset-grant argument). Lettuce's `IntegrationClientService`,
 * renamed, plus the optional expiry. [clock] is injectable for the tests.
 */
class ServiceTokenService(
    private val database: R2dbcDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    object ServiceTokens : UIntIdTable("service_tokens") {
        // Live-name uniqueness rides the partial unique index uq_service_tokens_name_live (V48): Exposed defs are query-only.
        val name = varchar("name", length = MAX_SERVICE_TOKEN_NAME_LENGTH)
        val tokenHash = varchar("token_hash", length = 64)
        val createdBy = reference("created_by", FlowUsers)
        val createdAt = long("created_at")
        val expiresAt = long("expires_at").nullable()
        val lastUsedAt = long("last_used_at").nullable()
        val revokedAt = long("revoked_at").nullable()
        val revokedBy = reference("revoked_by", FlowUsers).nullable()
    }

    private val creators = FlowUsers.alias("creators")
    private val revokers = FlowUsers.alias("revokers")

    /** Newest first, at most [SERVICE_TOKEN_LIST_LIMIT] rows. A soft-deleted creator or revoker keeps its name. */
    suspend fun list(): List<ServiceToken> = suspendTransaction(database) {
        joined().orderBy(ServiceTokens.createdAt to SortOrder.DESC, ServiceTokens.id to SortOrder.DESC)
            .limit(SERVICE_TOKEN_LIST_LIMIT).map { it.toToken() }.toList()
    }

    suspend fun read(id: UInt): ServiceToken? = suspendTransaction(database) { readInTransaction(id) }

    /**
     * Inserts the token and returns it with the plaintext bearer value. The plaintext never persists, only its digest. A live-name clash
     * surfaces as the unique violation (`uq_service_tokens_name_live`), which the central error handler maps to a 409.
     */
    suspend fun create(name: String, expiresAt: Long?, byUserId: UInt): Pair<ServiceToken, String> = suspendTransaction(database) {
        val now = clock()
        val sanitized = validateServiceTokenCreate(name, expiresAt, now)
        val secret = SERVICE_TOKEN_PREFIX + generateOpaqueToken()
        val id = ServiceTokens.insert {
            it[ServiceTokens.name] = sanitized
            it[tokenHash] = sha256Hex(secret)
            it[createdBy] = byUserId
            it[createdAt] = now
            it[ServiceTokens.expiresAt] = expiresAt
        }[ServiceTokens.id].value
        checkNotNull(readInTransaction(id)) { "the service token just inserted is missing" } to secret
    }

    /** Terminal. The update is conditional on `revoked_at IS NULL`, so a concurrent revoke loses cleanly (zero rows: ALREADY_REVOKED). */
    suspend fun revoke(id: UInt, byUserId: UInt): ServiceTokenRevokeOutcome = suspendTransaction(database) {
        // Keep the whole row: reading only `revoked_at` would fold "exists, not revoked" into "missing".
        val row = ServiceTokens.selectAll().where { ServiceTokens.id eq id }.singleOrNull()
            ?: return@suspendTransaction ServiceTokenRevokeOutcome.NOT_FOUND
        if (row[ServiceTokens.revokedAt] != null) return@suspendTransaction ServiceTokenRevokeOutcome.ALREADY_REVOKED
        val updated = ServiceTokens.update({ (ServiceTokens.id eq id) and ServiceTokens.revokedAt.isNull() }) {
            it[revokedAt] = clock()
            it[revokedBy] = byUserId
        }
        if (updated == 0) ServiceTokenRevokeOutcome.ALREADY_REVOKED else ServiceTokenRevokeOutcome.REVOKED
    }

    /**
     * The per-request lookup: the digest of [raw] must match a row that is not revoked and not expired at [nowMs] (both in the WHERE, so
     * revocation and expiry take effect on the very next request, no cache). A live match stamps `last_used_at` (conditional on the same
     * predicate, so a revoke between the select and the update is never recorded as a use). Callers pre-check [SERVICE_TOKEN_PATTERN].
     * A refusal is classified for the audit trail only; the wire answer is one text for every reason.
     */
    suspend fun authenticate(raw: String, nowMs: Long): ServiceTokenVerdict = suspendTransaction(database) {
        val hash = sha256Hex(raw)
        val live = (ServiceTokens.revokedAt.isNull()) and (ServiceTokens.expiresAt.isNull() or (ServiceTokens.expiresAt greater nowMs))
        val row = ServiceTokens.selectAll().where { (ServiceTokens.tokenHash eq hash) and live }.singleOrNull()
        if (row == null) {
            val known = ServiceTokens.selectAll().where { ServiceTokens.tokenHash eq hash }.singleOrNull()
            val expired = known != null && known[ServiceTokens.revokedAt] == null
            return@suspendTransaction ServiceTokenVerdict.Rejected(
                reason = if (expired) ServiceTokenRejection.EXPIRED else ServiceTokenRejection.UNKNOWN_OR_REVOKED,
                tokenId = known?.get(ServiceTokens.id)?.value,
            )
        }
        val tokenId = row[ServiceTokens.id].value
        val stamped = ServiceTokens.update({ (ServiceTokens.id eq tokenId) and live }) { it[lastUsedAt] = nowMs }
        if (stamped == 0) {
            return@suspendTransaction ServiceTokenVerdict.Rejected(ServiceTokenRejection.UNKNOWN_OR_REVOKED, tokenId)
        }
        ServiceTokenVerdict.Live(
            tokenId = tokenId,
            name = row[ServiceTokens.name],
            createdAt = row[ServiceTokens.createdAt],
            expiresAt = row[ServiceTokens.expiresAt],
            lastUsedAtBefore = row[ServiceTokens.lastUsedAt],
        )
    }

    private suspend fun readInTransaction(id: UInt): ServiceToken? =
        joined().where { ServiceTokens.id eq id }.map { it.toToken() }.singleOrNull()

    private fun joined() = ServiceTokens
        .innerJoin(creators, { ServiceTokens.createdBy }, { creators[FlowUsers.id] })
        .leftJoin(revokers, { ServiceTokens.revokedBy }, { revokers[FlowUsers.id] })
        .selectAll()

    private fun ResultRow.toToken() = ServiceToken(
        id = this[ServiceTokens.id].value,
        name = this[ServiceTokens.name],
        createdAt = this[ServiceTokens.createdAt],
        createdByName = this[creators[FlowUsers.name]],
        expiresAt = this[ServiceTokens.expiresAt],
        lastUsedAt = this[ServiceTokens.lastUsedAt],
        revoked = this[ServiceTokens.revokedAt] != null,
        revokedAt = this[ServiceTokens.revokedAt],
        revokedByName = getOrNull(revokers[FlowUsers.name]),
    )
}
