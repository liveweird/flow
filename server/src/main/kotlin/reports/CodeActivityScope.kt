package ch.nokillswit.reports

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.teamAt
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 21's scope (`.claude/docs/reports.md` "Report 21", domain-model A50): which GitLab sources a request reads, the identity keys of
 * the people in play and who a key is, and the dated memberships a row's anchor is attributed through. Nothing here reads `norm.code_*`
 * (CodeActivityReads.kt) and nothing computes a figure (CodeActivityKernels.kt). The functions that read run inside the caller's report
 * transaction; the links are read ONLY through `IdentityResolver`, never from the table.
 */

/** One GitLab source in scope: its connection, the stored canonical base URL and [host] = `sourceHostOf(baseUrl)`, the links' key. */
internal data class CodeSource(val connectionId: UInt, val baseUrl: String, val host: String)

/**
 * The sources of [urls] (the ACTIVE GitLab connections' base URLs: not soft-deleted, a disabled one only has syncing paused and still
 * owns its rows) a request reads, in connection order: all of them, or the one [connectionId]. A Jira connection, an unknown one and an
 * inactive one are all absent from [urls], hence the same `400` as `resolveConnectionScope` answers for the other kind.
 */
internal fun codeSourcesOf(urls: Map<UInt, String>, connectionId: UInt?): List<CodeSource> {
    if (connectionId != null && connectionId !in urls) throw BadRequestException("Unknown or inactive connectionId: $connectionId")
    return urls.filterKeys { connectionId == null || it == connectionId }.toSortedMap()
        .map { (id, baseUrl) -> CodeSource(id, baseUrl, sourceHostOf(baseUrl)) }
}

/** [codeSourcesOf] over [DataSourceService.activeGitLabBaseUrls] (joins the enclosing transaction). */
internal suspend fun resolveCodeSources(dataSources: DataSourceService, connectionId: UInt?): List<CodeSource> =
    codeSourcesOf(dataSources.activeGitLabBaseUrls(), connectionId)

/**
 * Who the identity keys of the sources are: a LINKED key's Jira account, and the BOT / IGNORED keys (not a human reviewer). A decision
 * belongs to the HOST, so two sources on one instance agree on a key. A key with no decision is undecided: it attributes nothing but is
 * still a human actor.
 */
internal class CodeIdentities(
    private val hosts: Map<UInt, String>,
    private val accountByHostKey: Map<String, Map<String, String>>,
    private val decisionByHostKey: Map<String, Map<String, CodeIdentityDecision>>,
) {
    /** The host of [connectionId]'s source (a decision belongs to the host, so a key is one identity across its sources). */
    fun hostOf(connectionId: UInt): String? = hosts[connectionId]

    /** The Jira account [key] (of a row of [connectionId]) is LINKED to; null for an undecided, IGNORED or BOT key and for no key. */
    fun accountOf(connectionId: UInt, key: String?): String? = key?.let { accountByHostKey[hosts[connectionId]]?.get(it) }

    /** True for a key decided BOT or IGNORED: its acts are not a review by another human. */
    fun isNonHuman(connectionId: UInt, key: String): Boolean =
        decisionByHostKey[hosts[connectionId]]?.get(key).let { it == CodeIdentityDecision.BOT || it == CodeIdentityDecision.IGNORED }

    /** The same person: one key, or two keys LINKED to one account. Unknown ([b] null) is nobody. */
    fun samePerson(connectionId: UInt, a: String, b: String?): Boolean {
        if (b == null) return false
        if (a == b) return true
        val account = accountOf(connectionId, a)
        return account != null && account == accountOf(connectionId, b)
    }

    /** True when an admin decided [key] (LINKED, IGNORED or BOT); an undecided key is the one that still needs an admin. */
    fun isDecided(connectionId: UInt, key: String): Boolean = decisionByHostKey[hosts[connectionId]]?.containsKey(key) == true

    /** True when [key] has a LINKED decision. */
    fun isLinked(connectionId: UInt, key: String): Boolean = accountOf(connectionId, key) != null

    companion object {
        /** One host, the decisions given directly (a pure test's world). */
        fun of(connections: Collection<UInt>, host: String, decisions: Map<String, CodeIdentityDecision>, accounts: Map<String, String>) =
            CodeIdentities(connections.associateWith { host }, mapOf(host to accounts), mapOf(host to decisions))
    }
}

/**
 * The identity keys of each source's host that are LINKED to one of [accounts], per connection (a source whose host holds none is
 * absent). Reads only through [IdentityResolver.keysByAccount], one statement per host; empty [accounts] makes none.
 */
internal suspend fun keysOfAccounts(
    resolver: IdentityResolver,
    sources: List<CodeSource>,
    accounts: Collection<String>,
): Map<UInt, Set<String>> {
    if (accounts.isEmpty()) return emptyMap()
    val byHost = sources.map { it.host }.distinct()
        .associateWith { host -> resolver.keysByAccount(host, accounts).values.flatten().toSet() }
    return sources.mapNotNull { source ->
        byHost.getValue(source.host).takeIf { it.isNotEmpty() }?.let { source.connectionId to it }
    }.toMap()
}

/**
 * [CodeIdentities] for the [keys] seen on each connection (a key is looked up on its connection's host): the LINKED accounts
 * ([IdentityResolver.accountsByKey]) and every stored decision ([IdentityResolver.decidedKeys]), two statements per host.
 */
internal suspend fun loadCodeIdentities(
    resolver: IdentityResolver,
    sources: List<CodeSource>,
    keys: Map<UInt, Collection<String>>,
): CodeIdentities {
    val hostOf = sources.associate { it.connectionId to it.host }
    val keysByHost = sources.groupBy { it.host }.mapValues { (_, group) -> group.flatMap { keys[it.connectionId].orEmpty() }.toSet() }
    val accounts = keysByHost.mapValues { (host, hostKeys) -> resolver.accountsByKey(host, hostKeys) }
    val decisions = keysByHost.mapValues { (host, hostKeys) -> resolver.decidedKeys(host, hostKeys) }
    return CodeIdentities(hostOf, accounts, decisions)
}

/** The memberships of [accounts], every team and date, by account (`teamAt`'s input); an empty input makes no statement. */
internal suspend fun readCodeMemberships(accounts: Collection<String>): Map<String, List<TeamMembershipService.MembershipInterval>> {
    if (accounts.isEmpty()) return emptyMap()
    val m = TeamMembershipService.TeamMembership
    return m.select(m.accountId, m.teamId, m.validFrom, m.validTo).where { m.accountId inList accounts.distinct() }.toList()
        .groupBy({ it[m.accountId] }) { TeamMembershipService.MembershipInterval(it[m.teamId].value, it[m.validFrom], it[m.validTo]) }
}

/**
 * The accounts with a membership of [teamId] overlapping `[fromMs, toMs)` (A3's predicate, as `memberAccountIds`): the people whose
 * code a team page can attribute to the team at some anchor of the window. A mover is attributed by anchor afterwards (`teamAt`).
 */
internal suspend fun readTeamRoster(teamId: UInt, fromMs: Long, toMs: Long): Set<String> {
    val m = TeamMembershipService.TeamMembership
    return m.select(m.accountId)
        .where { (m.teamId eq teamId) and (m.validFrom less toMs) and (m.validTo.isNull() or (m.validTo greater fromMs)) }
        .toList().map { it[m.accountId] }.toSet()
}

/** The dated memberships behind the attribution and the identities of the keys: the one place a key becomes an account and a team. */
internal class CodeAttribution(
    val identities: CodeIdentities,
    private val memberships: Map<String, List<TeamMembershipService.MembershipInterval>>,
) {
    /** The account a row's [key] is LINKED to (null: unlinked). */
    fun accountOf(connectionId: UInt, key: String?): String? = identities.accountOf(connectionId, key)

    /** The team the key's person was in at [atMs] (`teamAt`, half-open `[from, to)`); null: unlinked, or linked with no membership then. */
    fun teamOf(connectionId: UInt, key: String?, atMs: Long): UInt? = teamAt(accountOf(connectionId, key), atMs, memberships)
}

/**
 * What a request narrows the attributed rows to: [teamId] (`0` = the UNASSIGNED bucket, rows with no team at their anchor) and, at USER
 * level, the picked [accounts] (null: the whole team).
 */
internal class CodeSelection(val teamId: UInt, val accounts: Set<String>?) {
    /** A row whose person is [account] and whose team at the row's anchor is [team] is in scope. */
    fun accepts(team: UInt?, account: String?): Boolean =
        (if (teamId == UNASSIGNED_TEAM_ID) team == null else team == teamId) && (accounts == null || account in accounts)
}

/** Who a row is: the LINKED account, else (an unlinked identity) its key on its host; all null for a row naming no identity at all. */
internal data class CodeMember(val accountId: String?, val identityKey: String?, val host: String? = null)

/**
 * The [CodeMember] of a row whose key is [key] and LINKED account [account]. An unlinked identity is qualified by its [host]: a key is
 * only unique within one GitLab instance, so `u42` of two hosts are two people.
 */
internal fun codeMemberOf(account: String?, key: String?, host: String?): CodeMember =
    if (account != null) CodeMember(account, null) else CodeMember(null, key, host.takeIf { key != null })
