package ch.nokillswit.gitlab

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.validation.canonicalEmail
import ch.nokillswit.norm.CountedFile
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.LineStats
import ch.nokillswit.norm.MrEventInput
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.NormCodeMrEvent
import ch.nokillswit.norm.countLines
import ch.nokillswit.norm.identityKeyForEmail
import ch.nokillswit.norm.identityKeyForUser
import ch.nokillswit.norm.noreplyUserId
import ch.nokillswit.norm.orderEvents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Raw GitLab rows -> the `norm.code_*` row shapes (`gitlab-integration.md` "PROCESS / PROFILE"; V44): pure functions, no I/O, so a
 * REPROCESS over the same raw rows writes the same norm rows. The rules are `norm/CodeNormalization.kt`'s (identity keys, counted
 * lines, event order); what lives here is the GitLab-specific reading of the raw rows (the action codes, the reviewer states, the user
 * table). Every rule is versioned by `CODE_PROCESSING_VERSION`: change one and bump it.
 */

/**
 * How a person is named in the facts, built ONCE per PROCESS run from EVERY `raw.gitlab_users` row (live and deleted). A commit's
 * `author_email` resolves, in order: (1) the canonical address is one of a user's `emails` -> `u<id>` (a duplicate across users: the
 * lowest id wins, deterministic); (2) the noreply pattern `<id>-<username>@users.noreply.<host>` -> `u<id>` (the id is in the local part);
 * (3) otherwise `e<hash>`. Merge request and event actors are ids, so they are `u<id>` directly; a review-request note's `@username`
 * resolves through [usernameKey].
 */
internal class IdentityMap private constructor(
    private val emailToUserId: Map<String, Long>,
    private val usernameToKey: Map<String, String>,
) {
    /** The user a canonical [email] is one of the addresses of, or null. */
    fun userIdOf(email: String): Long? = emailToUserId[email]

    /** The identity key a raw commit author address resolves to. */
    fun resolveAuthor(rawEmail: String): String {
        val email = canonicalEmail(rawEmail)
        val userId = emailToUserId[email] ?: noreplyUserId(email)
        return if (userId != null) identityKeyForUser(userId) else identityKeyForEmail(email)
    }

    /** The key of a user by `@username` (GitLab usernames are case-insensitive here), or null for an unknown one. */
    fun usernameKey(username: String): String? = usernameToKey[username.lowercase()]

    /**
     * A sha256 of the sorted address -> key map the resolution uses (step 1). A change of it (an address added to or removed from a user)
     * moves commits between `e` and `u` keys, so PROCESS re-flags every commit when it differs from the stored one. The noreply pattern
     * (step 2) is code, versioned by `CODE_PROCESSING_VERSION`.
     */
    val fingerprint: String by lazy {
        sha256Hex(canonicalJson(JsonObject(emailToUserId.mapValues { JsonPrimitive(identityKeyForUser(it.value)) })))
    }

    companion object {
        fun from(users: List<RawUserForNorm>): IdentityMap {
            val emails = HashMap<String, Long>()
            val usernames = HashMap<String, String>()
            users.sortedBy { it.userId }.forEach { user ->
                user.emails.map(::canonicalEmail).filter { it.isNotEmpty() }.forEach { emails.putIfAbsent(it, user.userId) }
                usernames.putIfAbsent(user.username.lowercase(), identityKeyForUser(user.userId))
            }
            return IdentityMap(emails, usernames)
        }
    }
}

/** The fingerprint of the EFFECTIVE exclusion list, in its stored order (the list is admin-ordered and de-duplicated at the write). */
internal fun exclusionsFingerprint(patterns: List<String>): String =
    sha256Hex(canonicalJson(kotlinx.serialization.json.JsonArray(patterns.map(::JsonPrimitive))))

/**
 * The identity rows of one PROCESS run (V44 `norm.code_identities`): one USER row per raw user; a ghost USER row (no username, name or
 * state) for every user id a fact names that has no users row ([referencedIds] and the noreply ids of the live commits' addresses); one
 * EMAIL row per distinct canonical author address of the LIVE commits that resolves to no user, named by the newest commit of that address
 * (ties: the smallest sha) and holding the canonical address so an admin can see what to decide on. Every key a LIVE fact names has a row.
 */
internal fun codeIdentities(
    users: List<RawUserForNorm>,
    authors: List<CommitAuthor>,
    referencedIds: Set<Long>,
    map: IdentityMap,
): List<NormCodeIdentity> {
    val userRows = users.sortedBy { it.userId }.map {
        NormCodeIdentity(
            key = identityKeyForUser(it.userId), kind = IdentityKind.USER, userId = it.userId, username = it.username,
            displayName = it.name, state = it.state, bot = it.bot, emails = it.emails, deletedAt = it.deletedAt,
        )
    }
    val byEmail = authors.groupBy { canonicalEmail(it.email) }
    val noreplyIds = byEmail.keys.filter { map.userIdOf(it) == null }.mapNotNull(::noreplyUserId)
    val ghostIds = (referencedIds + noreplyIds) - users.map { it.userId }.toSet()
    val ghosts = ghostIds.sorted().map {
        NormCodeIdentity(identityKeyForUser(it), IdentityKind.USER, it, null, null, null, false, emptyList(), null)
    }
    val emailRows = byEmail.filterKeys { map.userIdOf(it) == null && noreplyUserId(it) == null }.map { (email, spellings) ->
        val newest = spellings.minWith(compareByDescending<CommitAuthor> { it.committedAt }.thenBy { it.sha })
        NormCodeIdentity(
            key = identityKeyForEmail(email), kind = IdentityKind.EMAIL, userId = null, username = null, displayName = newest.name,
            state = null, bot = false, emails = listOf(email).filter { it.isNotEmpty() }, deletedAt = null,
        )
    }.sortedBy { it.key }
    return userRows + ghosts + emailRows
}

/**
 * One commit (V44 `norm.code_commits`). A merge commit: no counted figures and no `files` (no diff is read). A non-merge commit whose files
 * are pending (`files_fetched_at` null) or terminally unavailable (`files_error`): `files` and the counted figures are null (UNKNOWN, never
 * zero), GitLab's own `additions`/`deletions` stay. Otherwise `files` is the raw `files_changed` and the counted figures are
 * [countLines] over the STORED files (a capped commit's first 1,000, `files_capped` says so).
 */
internal fun codeCommit(
    raw: RawCommitForProcessing,
    files: List<RawCommitFileForProcessing>,
    excluded: (String) -> Boolean,
    map: IdentityMap,
): NormCodeCommit {
    val isMerge = raw.parentCount > 1
    val known = !isMerge && raw.filesFetchedAt != null && raw.filesError == null
    val counted = if (known) {
        countLines(files.map { CountedFile(it.path, it.additions, it.deletions) }, LineStats(raw.additions, raw.deletions), excluded)
    } else {
        null
    }
    return NormCodeCommit(
        projectId = raw.projectId, sha = raw.sha, identityKey = map.resolveAuthor(raw.authorEmail), title = raw.title,
        authoredAt = raw.authoredAt, committedAt = raw.committedAt, isMerge = isMerge, additions = raw.additions,
        deletions = raw.deletions, files = if (known) raw.filesChanged else null,
        additionsCounted = counted?.additions, deletionsCounted = counted?.deletions, filesCounted = counted?.files,
        filesCapped = raw.filesCapped, deletedAt = raw.deletedAt,
    )
}

/**
 * One merge request (V44 `norm.code_merge_requests`). `details_state` is `UNREADABLE` when `details_error` is set, `PENDING` when the
 * details were never read or are older than the last change, else `READ`; ONLY a `READ` merge request carries events - a stale or
 * unreadable one has an empty set (a report reads the state, never "no events"), so stale raw events never masquerade as current.
 */
internal fun codeMergeRequest(
    raw: RawMergeRequestForProcessing,
    events: List<RawMrEventForProcessing>,
    map: IdentityMap,
): NormCodeMergeRequest {
    val state = when {
        raw.detailsError != null -> DetailsState.UNREADABLE
        raw.detailsSyncedAt == null || raw.detailsSyncedAt < raw.changedAt -> DetailsState.PENDING
        else -> DetailsState.READ
    }
    fun keys(ids: List<Long>) = ids.map(::identityKeyForUser).distinct().sorted()
    return NormCodeMergeRequest(
        mrId = raw.mrId, projectId = raw.projectId, iid = raw.iid, state = raw.state, draft = raw.draft,
        authorKey = raw.authorId?.let(::identityKeyForUser), mergeUserKey = raw.mergeUserId?.let(::identityKeyForUser),
        createdAt = raw.createdAt, updatedAt = raw.updatedAt, mergedAt = raw.mergedAt, closedAt = raw.closedAt,
        sourceBranch = raw.sourceBranch, targetBranch = raw.targetBranch, title = raw.title, headSha = raw.headSha,
        mergeCommitSha = raw.mergeCommitSha, squashCommitSha = raw.squashCommitSha, reviewerKeys = keys(raw.reviewerIds),
        assigneeKeys = keys(raw.assigneeIds), additions = raw.additions, deletions = raw.deletions, fileCount = raw.fileCount,
        notesCapped = raw.notesCapped, detailsState = state, deletedAt = raw.deletedAt,
        events = if (state == DetailsState.READ) mapEvents(events, map) else emptyList(),
    )
}

private val NOTE_EVENT_KINDS: Map<String, MrEventKind> = mapOf(
    NOTE_ACTION_COMMENT to MrEventKind.COMMENT,
    NOTE_ACTION_DIFF_COMMENT to MrEventKind.DIFF_COMMENT,
    "APPROVAL" to MrEventKind.APPROVAL,
    "CHANGES_REQUESTED" to MrEventKind.CHANGES_REQUESTED,
    MR_ACTION_REVIEW_REQUESTED to MrEventKind.REVIEW_REQUESTED,
)

private const val REVIEWER_REVIEWED = "REVIEWED"
private const val REVIEWER_REQUESTED_CHANGES = "REQUESTED_CHANGES"

/**
 * A merge request's raw events -> its ordered norm events. A NOTE with one of the five review actions maps to the same-named kind
 * (actor `u<id>`, the note time; a review request carries its `@username` targets resolved to `u<id>`, unresolved ones dropped); every
 * other note action (UNAPPROVAL, REVIEW_REQUEST_REMOVED, COMMITS_ADDED, MERGED, CLOSED, REOPENED, DRAFT, READY, OTHER_SYSTEM) is not an
 * event (the merge request row carries the merged/closed times). An APPROVAL row (the legacy endpoint, no time) is an `APPROVAL` event
 * with no time ONLY when no NOTE approval by the same actor exists. A REVIEWER row: `REVIEWED` -> `REVIEWED`, timed by the entry's
 * `created_at` (when the reviewer was added: the API has no "reviewed at", so approximate); `REQUESTED_CHANGES` -> `CHANGES_REQUESTED`
 * unless a note by the same actor already says so; `APPROVED` is dropped (the approval is the note's / the endpoint's event);
 * `UNREVIEWED` and `OTHER_STATE` are nothing.
 */
internal fun mapEvents(events: List<RawMrEventForProcessing>, map: IdentityMap): List<NormCodeMrEvent> {
    fun noteActors(action: String) = events.filter { it.source == "NOTE" && it.action == action }.mapNotNull { it.actorId }.toSet()
    val noteApprovers = noteActors("APPROVAL")
    val noteChangeRequesters = noteActors("CHANGES_REQUESTED")
    val inputs = events.mapNotNull { event ->
        val actor = event.actorId?.let(::identityKeyForUser)
        fun input(kind: MrEventKind, at: Long?, targets: List<String> = emptyList()) =
            MrEventInput(kind, actor, at, targets, event.source, event.sourceId)
        when (event.source) {
            "NOTE" -> NOTE_EVENT_KINDS[event.action]?.let { kind ->
                val targets = if (kind == MrEventKind.REVIEW_REQUESTED) {
                    event.targets.mapNotNull(map::usernameKey).distinct().sorted()
                } else {
                    emptyList()
                }
                input(kind, event.eventAt, targets)
            }
            "APPROVAL" -> if (event.actorId in noteApprovers) null else input(MrEventKind.APPROVAL, null)
            "REVIEWER" -> when (event.action) {
                REVIEWER_REVIEWED -> input(MrEventKind.REVIEWED, event.eventAt)
                REVIEWER_REQUESTED_CHANGES ->
                    if (event.actorId in noteChangeRequesters) null else input(MrEventKind.CHANGES_REQUESTED, event.eventAt)
                else -> null
            }
            else -> null
        }
    }
    return orderEvents(inputs)
}
