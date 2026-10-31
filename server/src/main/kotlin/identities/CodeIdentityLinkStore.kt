package ch.nokillswit.identities

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

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
     * The decision for ([host], [key]), or null. [forUpdate] locks an EXISTING row, and only for the rest of the CALLER's enclosing
     * transaction (called outside one, the lock ends with this method's own transaction and protects nothing); a missing row locks
     * nothing, so a concurrent first insert is not excluded - that is what [insertIfAbsent]'s boolean answers. Plain `FOR UPDATE` is
     * right here: nothing references this table, so no long transaction (DERIVE) holds key-share locks on it (`persistence.md` "Row
     * locks vs. FK locks").
     *
     * **The PUT pattern, in ONE transaction** (so the value the service audits as `decisionFrom` is always the one actually overwritten):
     * `find(forUpdate = true)`; when null, [insertIfAbsent]; when that returns false (a concurrent insert won), `find(forUpdate = true)`
     * again and [update].
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

    /** Writes [link] when no decision is stored for its ([IdentityLink.host], [IdentityLink.key]); true when a row was inserted. */
    suspend fun insertIfAbsent(link: IdentityLink): Boolean = suspendTransaction(database) {
        Links.insertIgnore { bind(it, link) }.insertedCount > 0
    }

    /** Overwrites the decision stored for [link]'s primary key; true when a row was updated (false = there was none). */
    suspend fun update(link: IdentityLink): Boolean = suspendTransaction(database) {
        Links.update({ (Links.sourceHost eq link.host) and (Links.identityKey eq link.key) }) {
            it[decision] = link.decision.name
            it[jiraAccountId] = link.jiraAccountId
            it[decidedByUserId] = link.decidedByUserId
            it[decidedAt] = link.decidedAt
        } > 0
    }

    /**
     * Writes the [links] that have no decision yet and answers the identity keys it ACTUALLY inserted; a decision already stored for a
     * key wins and the key is absent from the answer (`ON CONFLICT DO NOTHING`, one statement per link in this transaction: the batch
     * is bounded by the connection's identity count, and a multi-row insert cannot say which rows it wrote).
     */
    suspend fun insertIgnoringConflicts(links: List<IdentityLink>): Set<String> {
        if (links.isEmpty()) return emptySet()
        return suspendTransaction(database) {
            links.filter { link -> Links.insertIgnore { bind(it, link) }.insertedCount > 0 }.mapTo(LinkedHashSet()) { it.key }
        }
    }

    /** Removes one decision and answers the row removed (`DELETE … RETURNING`), or null when there was none. */
    suspend fun delete(host: String, key: String): IdentityLink? = suspendTransaction(database) {
        Links.deleteReturning(Links.columns) { (Links.sourceHost eq host) and (Links.identityKey eq key) }
            .map(::toLink).toList().singleOrNull()
    }

    /** Removes every decision of [host] (the tests' cleanup; no route does this); the rows deleted. */
    suspend fun deleteHost(host: String): Int = suspendTransaction(database) {
        Links.deleteWhere { sourceHost eq host }
    }

    private fun bind(statement: UpdateBuilder<*>, link: IdentityLink) {
        statement[Links.sourceHost] = link.host
        statement[Links.identityKey] = link.key
        statement[Links.decision] = link.decision.name
        statement[Links.jiraAccountId] = link.jiraAccountId
        statement[Links.decidedByUserId] = link.decidedByUserId
        statement[Links.decidedAt] = link.decidedAt
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
        const val MAX_HOST = 300
        const val MAX_KEY = 40
        const val MAX_DECISION = 10
        const val MAX_ACCOUNT_ID = 100
    }
}
