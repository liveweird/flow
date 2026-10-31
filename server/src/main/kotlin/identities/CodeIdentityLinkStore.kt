package ch.nokillswit.identities

import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.infra.db.upsertRows
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** One stored decision (`public.code_identity_links`, V45): [jiraAccountId] is non-null exactly when [decision] is LINKED. */
data class IdentityLink(
    val host: String,
    val key: String,
    val decision: CodeIdentityDecision,
    val jiraAccountId: String?,
    val decidedByUserId: Long,
    val decidedAt: Long,
)

/**
 * The admin decisions about GitLab identities (V45, `persistence.md` "Code identity links (V45)"): keyed by `(source_host, identity_key)`
 * so they outlive a PROCESS rebuild of `norm.code_identities`, a PURGE and a re-created data source. The routes' service and the
 * [IdentityResolver] are the only readers and writers; the table is hard-deleted. Every method opens its own `suspendTransaction`, which
 * joins an enclosing one, and an empty input makes no statement.
 */
class CodeIdentityLinkStore(private val database: R2dbcDatabase) {

    /**
     * Query-only (the DB owns the DDL). `decided_by_user_id` is a plain column on purpose: it references `users`, but `identities/` never
     * imports `flowusers/`, and Exposed definitions are not the schema (`persistence.md`).
     */
    object Links : Table("code_identity_links") {
        val sourceHost = varchar("source_host", MAX_HOST)
        val identityKey = varchar("identity_key", MAX_KEY)
        val decision = varchar("decision", MAX_DECISION)
        val jiraAccountId = varchar("jira_account_id", MAX_ACCOUNT_ID).nullable()
        val decidedByUserId = long("decided_by_user_id")
        val decidedAt = long("decided_at")
        override val primaryKey = PrimaryKey(sourceHost, identityKey)
    }

    /** Every decision of the instance [host]. */
    suspend fun forHost(host: String): List<IdentityLink> = suspendTransaction(database) {
        Links.selectAll().where { Links.sourceHost eq host }.map(::toLink).toList()
    }

    /**
     * The decision for ([host], [key]), or null. [forUpdate] locks the row for the rest of the transaction. Plain `FOR UPDATE` is right
     * here: nothing references this table, so no long transaction (DERIVE) holds key-share locks on it (`persistence.md` "Row locks vs.
     * FK locks").
     */
    suspend fun find(host: String, key: String, forUpdate: Boolean = false): IdentityLink? = suspendTransaction(database) {
        val query = Links.selectAll().where { (Links.sourceHost eq host) and (Links.identityKey eq key) }
        (if (forUpdate) query.forUpdate() else query).map(::toLink).toList().singleOrNull()
    }

    /** The decisions of [host] for the given identity [keys]. */
    suspend fun forKeys(host: String, keys: Collection<String>): List<IdentityLink> {
        if (keys.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            Links.selectAll().where { (Links.sourceHost eq host) and (Links.identityKey inList keys.distinct()) }.map(::toLink).toList()
        }
    }

    /** The LINKED decisions of [host] naming one of [accountIds] (the resolver's `keysByAccount`). */
    suspend fun forAccounts(host: String, accountIds: Collection<String>): List<IdentityLink> {
        if (accountIds.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            Links.selectAll()
                .where {
                    (Links.sourceHost eq host) and (Links.decision eq CodeIdentityDecision.LINKED.name) and
                        (Links.jiraAccountId inList accountIds.distinct())
                }
                .map(::toLink).toList()
        }
    }

    /** Writes [link], replacing a decision already stored for the same ([IdentityLink.host], [IdentityLink.key]). */
    suspend fun upsert(link: IdentityLink) {
        suspendTransaction(database) {
            Links.upsertRows(listOf(link), keys = listOf(Links.sourceHost, Links.identityKey)) {
                this[Links.sourceHost] = it.host
                this[Links.identityKey] = it.key
                this[Links.decision] = it.decision.name
                this[Links.jiraAccountId] = it.jiraAccountId
                this[Links.decidedByUserId] = it.decidedByUserId
                this[Links.decidedAt] = it.decidedAt
            }
        }
    }

    /** Writes [links] that have no decision yet; a decision already stored for a key wins (`ON CONFLICT DO NOTHING`). */
    suspend fun insertIgnoringConflicts(links: List<IdentityLink>) {
        if (links.isEmpty()) return
        suspendTransaction(database) {
            Links.insertRowsIgnoringConflicts(links) {
                this[Links.sourceHost] = it.host
                this[Links.identityKey] = it.key
                this[Links.decision] = it.decision.name
                this[Links.jiraAccountId] = it.jiraAccountId
                this[Links.decidedByUserId] = it.decidedByUserId
                this[Links.decidedAt] = it.decidedAt
            }
        }
    }

    /** Removes one decision; the rows deleted (0 = there was none). */
    suspend fun delete(host: String, key: String): Int = suspendTransaction(database) {
        Links.deleteWhere { (sourceHost eq host) and (identityKey eq key) }
    }

    /** Removes every decision of [host] (the tests' cleanup; no route does this); the rows deleted. */
    suspend fun deleteHost(host: String): Int = suspendTransaction(database) {
        Links.deleteWhere { sourceHost eq host }
    }

    private fun toLink(row: ResultRow) = IdentityLink(
        host = row[Links.sourceHost],
        key = row[Links.identityKey],
        decision = CodeIdentityDecision.valueOf(row[Links.decision]),
        jiraAccountId = row[Links.jiraAccountId],
        decidedByUserId = row[Links.decidedByUserId],
        decidedAt = row[Links.decidedAt],
    )

    private companion object {
        /** The V45 column widths. */
        const val MAX_HOST = 255
        const val MAX_KEY = 40
        const val MAX_DECISION = 10
        const val MAX_ACCOUNT_ID = 100
    }
}
