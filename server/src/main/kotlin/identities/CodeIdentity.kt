package ch.nokillswit.identities

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.NormCodeIdentity
import io.ktor.server.plugins.BadRequestException
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * An ADMIN's stored decision about one GitLab identity (`public.code_identity_links.decision`, V45, CHECK-constrained to exactly these
 * three). [LINKED] names a Jira person; [IGNORED] and [BOT] name nobody (the table's CHECK keeps `jira_account_id` null for them). The
 * same enum is the proposal's decision on the wire, where only [LINKED] and [BOT] occur.
 */
enum class CodeIdentityDecision { LINKED, IGNORED, BOT }

/**
 * Where an identity stands on the Identities page: a READ-TIME join of the stored decision and the computed proposal, never a column.
 * [LINKED] = decided and linked; [IGNORED_OR_BOT] = decided ignored or bot; [PROPOSED] = no decision, but an address matches one Jira
 * person (or the identity is a bot); [UNMAPPED] = no decision and no proposal.
 */
enum class CodeIdentityStatus { PROPOSED, UNMAPPED, LINKED, IGNORED_OR_BOT }

/** The widest Jira account id `norm.people.account_id` and `code_identity_links.jira_account_id` hold (V45 mirrors the people table). */
const val MAX_JIRA_ACCOUNT_ID = 100

/** The two identity-key shapes (`norm/CodeNormalization.kt`): `u<user id>` and `e<hash of the email>`; V45's CHECK is the same. */
val IDENTITY_KEY_PATTERN = Regex("^u[0-9]{1,19}$|^e[0-9a-f]{32}$")

/** The path's identity key: well-formed or a `400` (never an address, never echoed). */
fun validateIdentityKey(raw: String): String {
    if (!IDENTITY_KEY_PATTERN.matches(raw)) throw BadRequestException("identityKey is malformed")
    return raw
}

/**
 * An ADMIN's decision about one identity. [jiraAccountId] is required for [CodeIdentityDecision.LINKED] and forbidden for IGNORED and BOT
 * (V45's CHECK keeps the stored row the same shape).
 */
@Serializable
data class CodeIdentityDecisionRequest(val decision: CodeIdentityDecision, val jiraAccountId: String? = null)

/** The request with its account id trimmed, or a `400` naming the broken rule (the message never echoes the id). */
fun validateDecisionRequest(request: CodeIdentityDecisionRequest): CodeIdentityDecisionRequest {
    val accountId = request.jiraAccountId?.let { sanitizeSingleLine(it, "jiraAccountId") }
    if (request.decision == CodeIdentityDecision.LINKED) {
        if (accountId == null || accountId.isEmpty() || accountId.length > MAX_JIRA_ACCOUNT_ID) {
            throw BadRequestException("jiraAccountId must be 1-$MAX_JIRA_ACCOUNT_ID characters for a LINKED decision")
        }
    } else if (accountId != null) {
        throw BadRequestException("jiraAccountId is only allowed for a LINKED decision")
    }
    return request.copy(jiraAccountId = accountId)
}

/** What confirming a proposal would write (never stored as such): [jiraAccountId] is null for a BOT proposal. */
@Serializable
data class CodeIdentityProposal(val decision: CodeIdentityDecision, val jiraAccountId: String? = null, val jiraDisplayName: String? = null)

/**
 * A stored decision as the page shows it. [decidedByUserId] is an id only (`identities/` never imports `flowusers/`), [decidedAt] an
 * ISO-8601 UTC instant; [jiraDisplayName] is null for IGNORED/BOT and for a person no active connection lists any more.
 */
@Serializable
data class CodeIdentityDecisionView(
    val decision: CodeIdentityDecision,
    val jiraAccountId: String? = null,
    val jiraDisplayName: String? = null,
    val decidedByUserId: Long,
    val decidedAt: String,
)

/**
 * One row of the Identities page. [seen] is false for an ORPHANED decision: one stored for this data source's host whose key has no
 * identity row in THIS connection (the user left every fact, or the source was re-created and not yet processed). It carries the key, the
 * kind the key's shape implies and the decision only: no name, username or address.
 */
@Serializable
data class CodeIdentityResponse(
    val identityKey: String,
    val kind: IdentityKind,
    val userId: Long? = null,
    val username: String? = null,
    val displayName: String? = null,
    val state: String? = null,
    val bot: Boolean,
    val deleted: Boolean,
    val emails: List<String>,
    val status: CodeIdentityStatus,
    val seen: Boolean,
    val proposal: CodeIdentityProposal? = null,
    val decision: CodeIdentityDecisionView? = null,
)

/** The bulk confirmation's counts: LINKED and BOT decisions actually written (a decision made meanwhile is not counted). */
@Serializable
data class CodeIdentityConfirmResult(val linked: Int, val bots: Int)

typealias CodeIdentityPageResponse = PageResponse<CodeIdentityResponse>

/** The ISO-8601 UTC form of a stored `decided_at`. */
fun isoUtc(millis: Long): String = Instant.ofEpochMilli(millis).toString()

/**
 * The page row for one identity ([identity] null = an orphaned decision, which has only its [link]). [proposalName] is the matched Jira
 * person's name (the proposal's own match row), [linkedName] the LINKED decision's account name (`WorkItemStore.displayNames`).
 */
fun codeIdentityResponse(
    key: String,
    identity: NormCodeIdentity?,
    status: CodeIdentityStatus,
    proposal: IdentityProposal?,
    proposalName: String?,
    link: IdentityLink?,
    linkedName: String?,
): CodeIdentityResponse = CodeIdentityResponse(
    identityKey = key,
    kind = identity?.kind ?: if (key.startsWith("u")) IdentityKind.USER else IdentityKind.EMAIL,
    userId = identity?.userId,
    username = identity?.username,
    displayName = identity?.displayName,
    state = identity?.state,
    bot = identity?.bot ?: false,
    deleted = identity?.deletedAt != null,
    emails = identity?.emails.orEmpty(),
    status = status,
    seen = identity != null,
    proposal = proposal?.let { CodeIdentityProposal(it.decision, it.jiraAccountId, proposalName) },
    decision = link?.let {
        CodeIdentityDecisionView(it.decision, it.jiraAccountId, linkedName, it.decidedByUserId, isoUtc(it.decidedAt))
    },
)
