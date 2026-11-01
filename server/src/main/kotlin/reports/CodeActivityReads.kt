package ch.nokillswit.reports

import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.norm.CodeActivityStore.CodeCommits
import ch.nokillswit.norm.CodeActivityStore.CodeIdentities
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrEvents
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.MrEventKind
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * Report 21's SQL reads (`.claude/docs/reports.md` "Report 21", A50): the Exposed tables of `norm/CodeActivityStore.kt`, read-only, inside
 * the caller's report transaction (the statement budget of `reportTransaction`). Every read is bounded by a window and by the keys of
 * the people in play (`CodeKeyScope`; null = every key of the sources, the UNASSIGNED bucket's); no row is loaded for a figure that is
 * a count. Tombstones (`deleted_at`) are never read. Attribution and the figures are CodeActivityKernels.kt's.
 */

/** The identity keys a read is limited to, per connection; null = every key of the sources. A connection missing from the map has none. */
internal typealias CodeKeyScope = Map<UInt, Collection<String>>?

/** The kinds that count as "a review by another human" (`measures.md` Report 21): time to first review, coverage, reviews given. */
internal val REVIEW_KINDS: List<MrEventKind> = listOf(
    MrEventKind.COMMENT, MrEventKind.DIFF_COMMENT, MrEventKind.APPROVAL, MrEventKind.REVIEWED, MrEventKind.CHANGES_REQUESTED,
)

/** The merge request states the reads and kernels name (GitLab's own). */
internal const val MR_OPENED = "opened"
internal const val MR_MERGED = "merged"
internal const val MR_CLOSED = "closed"

/** `IN` lists are cut into statements of at most this many values (well under the driver's bind limit). */
private const val IN_CHUNK = 5_000

/** A merge request of one connection. */
internal data class CodeMrRef(val connectionId: UInt, val mrId: Long)

/** One non-merge, live commit. The `*Counted` figures follow the path exclusions; null = unknown, never zero. */
internal data class CodeCommitRow(
    val connectionId: UInt,
    val projectId: Long,
    val sha: String,
    val identityKey: String,
    val committedAt: Long,
    val additionsCounted: Int?,
    val deletionsCounted: Int?,
    val filesCounted: Int?,
) {
    /** The counted lines are known: all three figures are present (one null leaves the whole commit out of the sums). */
    val countsKnown: Boolean get() = additionsCounted != null && deletionsCounted != null && filesCounted != null

    /** `additions + deletions` counted, null while the commit's counts are unknown (the drill's figure). */
    val linesCounted: Int? get() = if (countsKnown) additionsCounted!! + deletionsCounted!! else null
}

/**
 * One live merge request. [detailsRead] = `details_state` READ (its events are complete). The size is the exclusion-blind GraphQL
 * totals.
 */
internal data class CodeMrRow(
    val connectionId: UInt,
    val mrId: Long,
    val projectId: Long,
    val iid: Long,
    val state: String,
    val draft: Boolean,
    val authorKey: String?,
    val createdAt: Long,
    val mergedAt: Long?,
    val closedAt: Long?,
    val additions: Int?,
    val deletions: Int?,
    val fileCount: Int?,
    val detailsRead: Boolean,
) {
    val ref: CodeMrRef get() = CodeMrRef(connectionId, mrId)

    /** `additions + deletions`; null when either total is unknown. */
    val sizeLines: Int? get() = if (additions != null && deletions != null) additions + deletions else null
}

/** One review event of a live merge request, with that merge request's author key (for "other than the author"). */
internal data class CodeEventRow(
    val mr: CodeMrRef,
    val seq: Int,
    val kind: MrEventKind,
    val actorKey: String?,
    val eventAt: Long?,
    val targetKeys: List<String>,
    val mrAuthorKey: String?,
)

/**
 * One actor's footprint on one merge request: the earliest dated event of the five review kinds ([firstReviewAt], null when all of
 * them are undated) and whether the actor left an APPROVAL event (undated or not). The raw material of time to first review, merged
 * without review / approval and the review concentration.
 */
internal data class CodeReviewSummary(val mr: CodeMrRef, val actorKey: String, val firstReviewAt: Long?, val approved: Boolean)

/** How much activity one identity key has in the window, on one connection (the `quality` block's input). */
internal data class CodeKeyActivity(val connectionId: UInt, val key: String, val commits: Long, val mergeRequests: Long, val events: Long)

/** The window's connection-wide counts of rows that cannot enter a figure (`quality`). */
internal data class CodeQualityCounts(
    val commitsLinesUnknown: Long,
    val mrsSizeUnknown: Long,
    val mrsDetailsNotRead: Long,
    val eventsUndated: Long,
)

private fun inWindow(column: Column<Long>, window: Pair<Long, Long>): Op<Boolean> =
    (column greaterEq window.first) and (column less window.second)

private fun inWindowOrNull(column: Column<Long?>, window: Pair<Long, Long>): Op<Boolean> =
    (column greaterEq window.first) and (column less window.second)

/**
 * `connection IN (...)` and, per connection, `key IN (its keys)` for [scope], or null when nothing can match (no source, or a scope
 * with no key): the caller then makes no statement.
 */
private fun scoped(
    scope: CodeKeyScope,
    connections: Collection<UInt>,
    connection: Column<EntityID<UInt>>,
    inKeys: (List<String>) -> Op<Boolean>,
): Op<Boolean>? {
    if (connections.isEmpty()) return null
    if (scope == null) return connection inList connections
    val parts = scope.filterKeys { it in connections }.mapValues { (_, keys) -> keys.distinct() }.filterValues { it.isNotEmpty() }
        .map { (id, keys) -> (connection eq id) and inKeys(keys) }
    return parts.reduceOrNull<Op<Boolean>, Op<Boolean>> { acc, next -> acc or next }
}

/** The live non-merge commits of the scope's keys committed in [window]. */
internal suspend fun fetchCodeCommits(
    connections: Collection<UInt>,
    scope: CodeKeyScope,
    window: Pair<Long, Long>,
    budget: CodeRowBudget,
): List<CodeCommitRow> {
    val c = CodeCommits
    val keys = scoped(scope, connections, c.connectionId) { c.identityKey inList it } ?: return emptyList()
    val columns = listOf(
        c.connectionId, c.projectId, c.sha, c.identityKey, c.committedAt, c.additionsCounted, c.deletionsCounted, c.filesCounted,
    )
    return c.select(columns)
        .where { keys and (c.isMerge eq false) and c.deletedAt.isNull() and inWindow(c.committedAt, window) }
        .orderBy(c.committedAt to SortOrder.ASC, c.sha to SortOrder.ASC)
        .toBudgetedList(budget) {
            CodeCommitRow(
                it[c.connectionId].value, it[c.projectId], it[c.sha], it[c.identityKey], it[c.committedAt], it[c.additionsCounted],
                it[c.deletionsCounted], it[c.filesCounted],
            )
        }
}

/**
 * The live merge requests of the scope's authors that the report places somewhere: merged, created or closed in [window], and every
 * one still open (an open merge request is current whatever its age). An author-less merge request is read only with no key scope.
 */
internal suspend fun fetchCodeMergeRequests(
    connections: Collection<UInt>,
    scope: CodeKeyScope,
    window: Pair<Long, Long>,
    budget: CodeRowBudget,
): List<CodeMrRow> {
    val m = CodeMergeRequests
    val keys = scoped(scope, connections, m.connectionId) { m.authorKey inList it } ?: return emptyList()
    val placed = inWindowOrNull(m.mergedAt, window) or inWindow(m.createdAt, window) or inWindowOrNull(m.closedAt, window) or
        (m.state eq MR_OPENED)
    return m.select(
        m.connectionId, m.mrId, m.projectId, m.iid, m.state, m.draft, m.authorKey, m.createdAt, m.mergedAt, m.closedAt, m.additions,
        m.deletions, m.fileCount, m.detailsState,
    ).where { keys and m.deletedAt.isNull() and placed }
        .orderBy(m.createdAt to SortOrder.ASC, m.mrId to SortOrder.ASC)
        .toBudgetedList(budget) {
            CodeMrRow(
                it[m.connectionId].value, it[m.mrId], it[m.projectId], it[m.iid], it[m.state], it[m.draft], it[m.authorKey],
                it[m.createdAt], it[m.mergedAt], it[m.closedAt], it[m.additions], it[m.deletions], it[m.fileCount],
                it[m.detailsState] == DetailsState.READ.name,
            )
        }
}

private suspend fun fetchCodeEvents(
    connections: Collection<UInt>,
    scope: CodeKeyScope,
    window: Pair<Long, Long>,
    ofKind: (Column<String>) -> Op<Boolean>,
    inKeys: (List<String>) -> Op<Boolean>,
    budget: CodeRowBudget,
): List<CodeEventRow> {
    val e = CodeMrEvents
    val m = CodeMergeRequests
    val keys = scoped(scope, connections, e.connectionId, inKeys) ?: return emptyList()
    val source = e.join(m, JoinType.INNER, e.connectionId, m.connectionId, additionalConstraint = { e.mrId eq m.mrId })
    return source.select(e.connectionId, e.mrId, e.seq, e.kind, e.actorKey, e.eventAt, e.targetKeys, m.authorKey)
        .where { keys and m.deletedAt.isNull() and ofKind(e.kind) and inWindowOrNull(e.eventAt, window) }
        .orderBy(e.connectionId to SortOrder.ASC, e.mrId to SortOrder.ASC, e.seq to SortOrder.ASC)
        .toBudgetedList(budget) {
            CodeEventRow(
                mr = CodeMrRef(it[e.connectionId].value, it[e.mrId]),
                seq = it[e.seq],
                kind = MrEventKind.valueOf(it[e.kind]),
                actorKey = it[e.actorKey],
                eventAt = it[e.eventAt],
                targetKeys = it[e.targetKeys]?.let(::parseStringArray).orEmpty(),
                mrAuthorKey = it[m.authorKey],
            )
        }
}

/**
 * The dated acts of the scope's ACTORS in [window] (every kind but REVIEW_REQUESTED, whose actor is the requester): the per-member
 * reviews, approvals, changes requested and comments, attributed by `event_at`.
 */
internal suspend fun fetchCodeActorEvents(
    connections: Collection<UInt>,
    scope: CodeKeyScope,
    window: Pair<Long, Long>,
    budget: CodeRowBudget,
): List<CodeEventRow> =
    fetchCodeEvents(connections, scope, window, { it neq MrEventKind.REVIEW_REQUESTED.name }, { CodeMrEvents.actorKey inList it }, budget)

/**
 * The dated REVIEW_REQUESTED events of [window] that name one of the scope's keys in `target_keys` (the people named received the
 * request, whoever asked; the requester's key plays no part). With no key scope (the UNASSIGNED bucket's, every key of the sources) every
 * dated request of the window is read. The SQL test is a PRE-FILTER only: it keeps the events the kernel could attribute (`target_keys`
 * of each is still read whole), so which target counts at `event_at` stays [requestReceivers]'s -- a request naming nobody in scope is
 * simply never loaded (about a quarter of the window's requests for a 15-member team, `build-times.md` WHY 61).
 */
internal suspend fun fetchCodeReviewRequests(
    connections: Collection<UInt>,
    scope: CodeKeyScope,
    window: Pair<Long, Long>,
    budget: CodeRowBudget,
): List<CodeEventRow> = fetchCodeEvents(
    connections, scope, window, { it eq MrEventKind.REVIEW_REQUESTED.name }, { namesAnyOf(CodeMrEvents.targetKeys, it) }, budget,
)

/**
 * `jsonb_exists_any(column, ARRAY[keys]::text[])`: the JSONB array [column] holds at least one of [keys] as a string element (the
 * function form of `?|`, whose literal `?` clashes with placeholders). Every key is a bind parameter; a NULL column is not a match.
 */
private fun namesAnyOf(column: Expression<*>, keys: List<String>): Op<Boolean> = object : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("jsonb_exists_any(")
            append(column)
            append(", ARRAY[")
            keys.forEachIndexed { i, key ->
                if (i > 0) append(", ")
                append(stringParam(key))
            }
            append("]::text[])")
        }
    }
}

/**
 * Per (merged merge request, actor): the earliest dated review-kind event and whether the actor approved. ONE grouped statement per
 * chunk of merge requests, so the events themselves are never loaded. A review counts only up to the MERGE: an event dated after
 * `merged_at` is neither a first review nor a reviewer nor an approval (a comment on a merged merge request is not what held it up);
 * an UNDATED APPROVAL still counts as an approval, as it always did. Per-member acts are NOT bounded (see [fetchCodeActorEvents]).
 * Which actors are another human, and which are BOT or IGNORED, is decided afterwards (the kernel, with
 * `IdentityResolver.decidedKeys`): an actor-less event is no one and is left out here.
 */
internal suspend fun fetchCodeReviewSummaries(
    mrs: Collection<CodeMrRef>,
    budget: CodeRowBudget,
): List<CodeReviewSummary> {
    val e = CodeMrEvents
    val reviewKinds = REVIEW_KINDS.map { it.name }
    val m = CodeMergeRequests
    val beforeMerge = e.eventAt.isNotNull() and (e.eventAt lessEq m.mergedAt)
    val firstReview = valueWhen((e.kind inList reviewKinds) and beforeMerge, e.eventAt).min()
    val approvals = Count(
        valueWhen((e.kind eq MrEventKind.APPROVAL.name) and (e.eventAt.isNull() or (e.eventAt lessEq m.mergedAt)), intLiteral(1)),
    )
    val source = e.join(m, JoinType.INNER, e.connectionId, m.connectionId, additionalConstraint = { e.mrId eq m.mrId })
    return mrs.groupBy({ it.connectionId }) { it.mrId }.flatMap { (connectionId, ids) ->
        ids.distinct().chunked(IN_CHUNK).flatMap { chunk ->
            source.select(e.mrId, e.actorKey, firstReview, approvals)
                .where {
                    (e.connectionId eq connectionId) and (e.mrId inList chunk) and e.actorKey.isNotNull() and (e.kind inList reviewKinds)
                }
                .groupBy(e.mrId, e.actorKey).toBudgetedList(budget) {
                    CodeReviewSummary(CodeMrRef(connectionId, it[e.mrId]), it[e.actorKey]!!, it[firstReview], it[approvals] > 0)
                }
        }
    }
}

/**
 * The label of each (connection, key) among [keys] on [connections]: the display name, else the username; a name that contains an `@`
 * is never a label (a git `user.name` set to an address must not reach the wire), and a ghost has none. Keys are per connection: the
 * same key on another host is another identity.
 */
internal suspend fun fetchCodeIdentityLabels(
    connections: Collection<UInt>,
    keys: Collection<String>,
): Map<Pair<UInt, String>, String> {
    if (connections.isEmpty() || keys.isEmpty()) return emptyMap()
    val i = CodeIdentities
    val labels = LinkedHashMap<Pair<UInt, String>, String>()
    keys.distinct().chunked(IN_CHUNK).forEach { chunk ->
        i.select(i.connectionId, i.identityKey, i.displayName, i.username)
            .where { (i.connectionId inList connections) and (i.identityKey inList chunk) }
            .toList().forEach { row ->
                listOf(row[i.displayName], row[i.username]).firstOrNull { it != null && '@' !in it }
                    ?.let { labels[row[i.connectionId].value to row[i.identityKey]] = it }
            }
    }
    return labels
}

/**
 * How much each identity key did in [window], per connection, whoever it is (grouped counts, no row loaded): commits (non-merge),
 * merge requests (created or merged in the window) and dated events (of live merge requests).
 */
internal suspend fun fetchCodeKeyActivity(connections: Collection<UInt>, window: Pair<Long, Long>): List<CodeKeyActivity> {
    if (connections.isEmpty()) return emptyList()
    val c = CodeCommits
    val m = CodeMergeRequests
    val e = CodeMrEvents
    val commitCount = c.sha.count()
    val commits = c.select(c.connectionId, c.identityKey, commitCount)
        .where { (c.connectionId inList connections) and (c.isMerge eq false) and c.deletedAt.isNull() and inWindow(c.committedAt, window) }
        .groupBy(c.connectionId, c.identityKey).toList().associate { (it[c.connectionId].value to it[c.identityKey]) to it[commitCount] }
    val mrCount = m.mrId.count()
    val mergeRequests = m.select(m.connectionId, m.authorKey, mrCount)
        .where { (m.connectionId inList connections) and m.deletedAt.isNull() and m.authorKey.isNotNull() and periodOfMr(m, window) }
        .groupBy(m.connectionId, m.authorKey).toList().associate { (it[m.connectionId].value to it[m.authorKey]!!) to it[mrCount] }
    val eventCount = e.seq.count()
    val events = e.join(m, JoinType.INNER, e.connectionId, m.connectionId, additionalConstraint = { e.mrId eq m.mrId })
        .select(e.connectionId, e.actorKey, eventCount)
        .where {
            (e.connectionId inList connections) and m.deletedAt.isNull() and e.actorKey.isNotNull() and inWindowOrNull(e.eventAt, window)
        }
        .groupBy(e.connectionId, e.actorKey).toList().associate { (it[e.connectionId].value to it[e.actorKey]!!) to it[eventCount] }
    return (commits.keys + mergeRequests.keys + events.keys).sortedWith(compareBy({ it.first }, { it.second })).map { id ->
        CodeKeyActivity(id.first, id.second, commits[id] ?: 0, mergeRequests[id] ?: 0, events[id] ?: 0)
    }
}

/** A merge request is "in the period" when it was created or merged inside it. */
private fun periodOfMr(m: CodeMergeRequests, window: Pair<Long, Long>): Op<Boolean> =
    inWindow(m.createdAt, window) or inWindowOrNull(m.mergedAt, window)

/**
 * The window's connection-wide counts of rows that cannot enter a figure (`quality`): non-merge commits with an unknown counted figure,
 * merged merge requests (`merged_at` in the window) with an unknown total (lines OR file count) or with details not read, and undated
 * events of the merge requests created or merged in the window. Three statements: one scan per table.
 */
internal suspend fun fetchCodeQualityCounts(connections: Collection<UInt>, window: Pair<Long, Long>): CodeQualityCounts {
    if (connections.isEmpty()) return CodeQualityCounts(0, 0, 0, 0)
    val c = CodeCommits
    val m = CodeMergeRequests
    val e = CodeMrEvents
    val commitsUnknown = c.selectAll()
        .where {
            (c.connectionId inList connections) and (c.isMerge eq false) and c.deletedAt.isNull() and inWindow(c.committedAt, window) and
                (c.additionsCounted.isNull() or c.deletionsCounted.isNull() or c.filesCounted.isNull())
        }.count()
    val merged = (m.connectionId inList connections) and m.deletedAt.isNull() and (m.state eq MR_MERGED) and
        inWindowOrNull(m.mergedAt, window)
    // One scan of the merged merge requests for both counts. A size is unknown when ANY of its three totals is (the lines feed the
    // line figures, the file count the file figure): never silently left out of one of them.
    val sizeUnknown = Count(valueWhen(m.additions.isNull() or m.deletions.isNull() or m.fileCount.isNull(), intLiteral(1)))
    val detailsNotRead = Count(valueWhen(m.detailsState neq DetailsState.READ.name, intLiteral(1)))
    val mergedCounts = m.select(sizeUnknown, detailsNotRead).where { merged }.toList().single()
    val undated = e.join(m, JoinType.INNER, e.connectionId, m.connectionId, additionalConstraint = { e.mrId eq m.mrId }).selectAll()
        .where { (e.connectionId inList connections) and m.deletedAt.isNull() and e.eventAt.isNull() and periodOfMr(m, window) }.count()
    return CodeQualityCounts(commitsUnknown, mergedCounts[sizeUnknown], mergedCounts[detailsNotRead], undated)
}
