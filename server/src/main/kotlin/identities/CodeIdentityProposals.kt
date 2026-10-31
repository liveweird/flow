package ch.nokillswit.identities

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.validation.foldForSearch
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.NormCodeIdentity

/*
 * The PURE kernel of the Identities page (`.claude/docs/gitlab-integration.md` "Identity"): what an identity is proposed as, where it
 * stands, and which rows one page shows. No database, no clock, no I/O - the service feeds it the three reads (the identity rows, the
 * host's stored decisions, the address matches) and the paging request.
 */

/** What an identity would be linked as if an ADMIN confirmed it: [BOT] names nobody, [LINKED] names [jiraAccountId]. Never stored. */
data class IdentityProposal(val decision: CodeIdentityDecision, val jiraAccountId: String?)

/**
 * The proposal for [identity], given the Jira account ids whose address is one of its addresses ([matchedAccountIds], the distinct ids
 * `CodeActivityStore.proposalMatches` returned for its key):
 *
 * - a `bot` USER is proposed as [CodeIdentityDecision.BOT] whatever its addresses match (the flag wins);
 * - otherwise exactly ONE distinct matched account is proposed as [CodeIdentityDecision.LINKED] to it;
 * - otherwise (no match, or several: ambiguous, never "the first") there is no proposal.
 *
 * Ghosts (no username, so no addresses) and deleted users follow the same rule: a deleted user with a matching address is still
 * proposed, its history is attributable.
 */
fun proposalFor(identity: NormCodeIdentity, matchedAccountIds: Set<String>): IdentityProposal? = when {
    identity.kind == IdentityKind.USER && identity.bot -> IdentityProposal(CodeIdentityDecision.BOT, null)
    matchedAccountIds.size == 1 -> IdentityProposal(CodeIdentityDecision.LINKED, matchedAccountIds.single())
    else -> null
}

/**
 * Where an identity stands, a read-time join and never a column: a stored LINKED decision is [CodeIdentityStatus.LINKED], a stored
 * IGNORED or BOT one is [CodeIdentityStatus.IGNORED_OR_BOT] (the decision beats any proposal), no decision with a proposal is
 * [CodeIdentityStatus.PROPOSED], and anything else is [CodeIdentityStatus.UNMAPPED].
 */
fun statusOf(decision: CodeIdentityDecision?, proposal: IdentityProposal?): CodeIdentityStatus = when (decision) {
    CodeIdentityDecision.LINKED -> CodeIdentityStatus.LINKED
    CodeIdentityDecision.IGNORED, CodeIdentityDecision.BOT -> CodeIdentityStatus.IGNORED_OR_BOT
    null -> if (proposal != null) CodeIdentityStatus.PROPOSED else CodeIdentityStatus.UNMAPPED
}

/** What [selectPage] filters and sorts a row by: its identity [key], names, canonical [emails] and [status]. */
data class SearchableIdentity(
    val key: String,
    val displayName: String?,
    val username: String?,
    val emails: List<String>,
    val status: CodeIdentityStatus,
)

/**
 * One page of [rows] (the list endpoint filters, sorts and pages IN MEMORY: `status` is a read-time join of three stores and one
 * connection's identities are bounded, `list-endpoints.md`). Returns the page and the filtered total.
 *
 * - [status]: equality on [SearchableIdentity.status].
 * - [q] (blank = absent): `foldForSearch(q)` contained in the fold of the display name, the username or ANY address - case- and
 *   accent-insensitive like `containsNormalized`. The key is not searched.
 * - Sort: the request's fields in order, `displayName` and `username` by String natural order with absent values LAST in both
 *   directions (ghosts sink), `id` = the identity key. `parsePaging` appends the `id` ascending tiebreaker, so equal names keep key order.
 * - Page: the 1-based `page` of `pageSize`; a page past the end is an empty list with the same total.
 */
fun <R> selectPage(
    rows: List<R>,
    status: CodeIdentityStatus?,
    q: String?,
    paging: PageRequest,
    projection: (R) -> SearchableIdentity,
): Pair<List<R>, Long> {
    val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.let(::foldForSearch)
    val matching = rows.filter { row ->
        val identity = projection(row)
        (status == null || identity.status == status) && (needle == null || identity.matches(needle))
    }
    val ordered = matching.sortedWith(comparatorFor(paging.sort, projection))
    val offset = (paging.page - 1).toLong() * paging.pageSize
    val page = if (offset >= ordered.size) emptyList() else ordered.drop(offset.toInt()).take(paging.pageSize)
    return page to matching.size.toLong()
}

private fun SearchableIdentity.matches(foldedNeedle: String): Boolean =
    listOfNotNull(displayName, username).plus(emails).any { foldForSearch(it).contains(foldedNeedle) }

private fun <R> comparatorFor(sort: List<SortField>, projection: (R) -> SearchableIdentity): Comparator<R> {
    val parts = sort.map { field ->
        val value: (SearchableIdentity) -> String? = when (field.name) {
            "displayName" -> SearchableIdentity::displayName
            "username" -> SearchableIdentity::username
            "id" -> SearchableIdentity::key
            else -> error("Sort field '${field.name}' has no identity accessor (paging helper invariant violated)")
        }
        Comparator<R> { a, b -> compareNullsLast(value(projection(a)), value(projection(b)), field.descending) }
    }
    return parts.reduce { first, next -> first.thenComparing(next) }
}

/** Natural String order, reversed when [descending]; an absent value sorts after every present one in BOTH directions. */
private fun compareNullsLast(a: String?, b: String?, descending: Boolean): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    descending -> b.compareTo(a)
    else -> a.compareTo(b)
}
