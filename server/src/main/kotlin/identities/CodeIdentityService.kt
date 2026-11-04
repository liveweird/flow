package ch.nokillswit.identities

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.ProposalMatch
import ch.nokillswit.norm.WorkItemStore
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val CodeIdentityServiceKey = AttributeKey<CodeIdentityService>("CodeIdentityService")

/** The list's optional filters: [status] equality and [q], the case- and accent-insensitive substring over name, username and addresses. */
data class CodeIdentityFilter(val status: CodeIdentityStatus? = null, val q: String? = null)

/** One page of the Identities list and the filtered [total]. */
data class CodeIdentityListResult(val items: List<CodeIdentityResponse>, val total: Long)

/**
 * What a PUT did. [item] is the row as the list would now show it; [previous] is the decision actually overwritten (null = there was
 * none), the audit's `decisionFrom`; [changed] false = the identical decision was already stored, so nothing was written (and the
 * caller audits nothing).
 */
data class DecisionOutcome(val item: CodeIdentityResponse, val previous: IdentityLink?, val changed: Boolean)

/**
 * The Identities page's service (`.claude/docs/gitlab-integration.md` "Identity"): the read-time join of one connection's identity rows,
 * the host's stored decisions and the address matches, and the three writes (decide, remove, confirm proposals) on `code_identity_links`.
 *
 * [host] is always [sourceHostOf] of the data source the request names (never from a body), and [connectionId] that data source: a
 * decision is about an identity of THIS connection, but is stored by host so it outlives the connection. Nothing here audits: the routes
 * emit the events from the outcomes below. No address, name or username appears in any exception message.
 */
class CodeIdentityService(
    private val database: R2dbcDatabase,
    private val links: CodeIdentityLinkStore,
    private val codeStore: CodeActivityStore,
    private val workItems: WorkItemStore,
    private val dataSources: DataSourceService,
) {

    /**
     * The connection's identities with the host's decisions and the proposals, filtered, sorted and paged IN MEMORY (`list-endpoints.md`:
     * `status` is a read-time join over one bounded set). ORPHANED decisions are listed too, with `seen = false`, on their decided
     * status. An orphan is per INSTANCE, not per connection: a decision whose key has no identity row in this connection is an orphan
     * here only when no OTHER active GitLab data source on the [host] has one either; a key another active source still has belongs to
     * that source's page and is not listed on this one.
     */
    suspend fun list(connectionId: UInt, host: String, filter: CodeIdentityFilter, paging: PageRequest): CodeIdentityListResult =
        suspendTransaction(database) {
            val otherKeys = codeStore.identityKeysOf(peerConnections(connectionId, host))
            val rows = assemble(
                codeStore.identityRows(connectionId), links.forHost(host), codeStore.proposalMatches(connectionId), otherKeys,
            )
            val (page, total) = selectPage(rows, filter.status, filter.q, paging) { it.searchable() }
            val names = workItems.displayNames(page.mapNotNull { it.link?.jiraAccountId })
            CodeIdentityListResult(page.map { it.toResponse(names) }, total)
        }

    /**
     * Stores [request] as the decision for ([host], [key]) in ONE transaction: the decision row is read `FOR UPDATE`; when absent it is
     * inserted, and when that loses a race (another insert won) it is read again under lock and overwritten. [DecisionOutcome.previous]
     * is therefore the value actually overwritten. An identical decision is idempotent (nothing written, `changed = false`).
     *
     * A key with no identity row in this connection is a `404` unless a decision is already stored for it (an orphaned decision may be
     * changed). A LINKED account must be a person of an ACTIVE connection (`400`), except when the stored decision already names that
     * very account (so an identical re-PUT is accepted after the person left Jira).
     */
    suspend fun decide(
        connectionId: UInt,
        host: String,
        key: String,
        request: CodeIdentityDecisionRequest,
        byUserId: Long,
        now: Long,
    ): DecisionOutcome = suspendTransaction(database) {
        // FOR UPDATE: nothing references this table, so no long transaction (DERIVE) holds key-share locks on it (persistence.md
        // "Row locks vs. FK locks"). A missing row locks nothing; insertIfAbsent settles that race.
        val found = links.find(host, key, forUpdate = true)
        val identity = codeStore.identityRow(connectionId, key)
        if (identity == null && found == null) throw NotFoundException("Identity not found")
        val account = request.jiraAccountId
        if (account != null && found?.jiraAccountId != account && workItems.displayNames(listOf(account)).isEmpty()) {
            throw BadRequestException("Unknown Jira account id")
        }
        val wanted = IdentityLink(host, key, request.decision, account, byUserId, now)
        val written = write(wanted, found)
        val matches = codeStore.proposalMatches(connectionId).filter { it.identityKey == key }
        val row = assemble(listOfNotNull(identity), listOf(written.stored), matches).single()
        val names = workItems.displayNames(listOfNotNull(written.stored.jiraAccountId))
        DecisionOutcome(row.toResponse(names), written.previous, written.changed)
    }

    /** The insert-or-overwrite of [decide], inside its transaction; [found] is the row already read under lock (null = none). */
    private suspend fun write(wanted: IdentityLink, found: IdentityLink?): Written {
        var current = found
        if (current == null) {
            if (links.insertIfAbsent(wanted)) return Written(wanted, null, true)
            // Another insert won the race: read the winner under lock, so `previous` is what we actually overwrite.
            current = links.find(wanted.host, wanted.key, forUpdate = true)
                ?: throw ConflictException("The decision was changed concurrently, retry")
        }
        if (current.decision == wanted.decision && current.jiraAccountId == wanted.jiraAccountId) return Written(current, current, false)
        check(links.update(wanted)) { "A locked decision row vanished during its own update" }
        return Written(wanted, current, true)
    }

    /** Removes the decision for ([host], [key]) and answers the row removed (the audit's values), or null = there was none (`404`). */
    suspend fun remove(host: String, key: String): IdentityLink? = links.delete(host, key)

    /**
     * Confirms every current proposal of the connection that has no stored decision: a LINKED link per address match, a BOT link per
     * bot. ONE transaction; a decision stored meanwhile wins (`ON CONFLICT DO NOTHING`). Answers ONLY the links actually written, so the
     * caller's counts and audit lines name real changes; a second call writes nothing.
     */
    suspend fun confirmProposals(connectionId: UInt, host: String, byUserId: Long, now: Long): List<IdentityLink> =
        suspendTransaction(database) {
            val rows = assemble(codeStore.identityRows(connectionId), links.forHost(host), codeStore.proposalMatches(connectionId))
            // Rows with an identity and no decision only: an orphan never reaches the insert.
            val candidates = rows.filter { it.identity != null && it.link == null }.mapNotNull { row ->
                row.proposal?.let { IdentityLink(host, row.key, it.decision, it.jiraAccountId, byUserId, now) }
            }
            // The insert order follows `identityRows`' ORDER BY identity_key, so two concurrent confirms take their row locks in the same
            // order and cannot deadlock (a `40P01` would be a 500).
            val inserted = links.insertIgnoringConflicts(candidates)
            candidates.filter { it.key in inserted }
        }

    /** The ids of the OTHER active (not deleted, enabled or not) GitLab data sources on [host]: the instance the orphan rule spans. */
    private suspend fun peerConnections(connectionId: UInt, host: String): List<UInt> =
        dataSources.activeGitLabBaseUrls().filter { (id, baseUrl) -> id != connectionId && sourceHostOf(baseUrl) == host }.keys.toList()
}


/** The link now stored, the one it replaced (if any) and whether the PUT wrote anything. */
private data class Written(val stored: IdentityLink, val previous: IdentityLink?, val changed: Boolean)

/** One list row before it is a response: [identity] is null for an ORPHANED decision (only its [link] exists). */
private class Row(
    val key: String,
    val identity: NormCodeIdentity?,
    val link: IdentityLink?,
    val proposal: IdentityProposal?,
    val proposalName: String?,
) {
    val status: CodeIdentityStatus get() = statusOf(link?.decision, proposal)

    fun searchable() = SearchableIdentity(key, identity?.displayName, identity?.username, identity?.emails.orEmpty(), status)

    fun toResponse(linkedNames: Map<String, String>): CodeIdentityResponse = codeIdentityResponse(
        key, identity, status, proposal, proposalName, link, link?.jiraAccountId?.let(linkedNames::get),
    )
}

/**
 * The three reads joined: a row per identity (its decision and proposal, ambiguity and bot rules in [proposalFor]) plus one per ORPHANED
 * decision (a stored key that no identity row of the connection carries and that is not in [otherInstanceKeys], the identity keys of the
 * host's other active GitLab sources: those decisions belong to their page).
 */
private fun assemble(
    identities: List<NormCodeIdentity>,
    stored: List<IdentityLink>,
    matches: List<ProposalMatch>,
    otherInstanceKeys: Set<String> = emptySet(),
): List<Row> {
    val byKey = stored.associateBy { it.key }
    val matchesByKey = matches.groupBy { it.identityKey }
    val rows = identities.map { identity ->
        val matched = matchesByKey[identity.key].orEmpty()
        val proposal = proposalFor(identity, matched.mapTo(HashSet()) { it.accountId })
        val name = proposal?.jiraAccountId?.let { account -> matched.firstOrNull { it.accountId == account }?.displayName }
        Row(identity.key, identity, byKey[identity.key], proposal, name)
    }
    val seen = identities.mapTo(HashSet()) { it.key }
    return rows + stored.filter { it.key !in seen && it.key !in otherInstanceKeys }.map { Row(it.key, null, it, null, null) }
}
