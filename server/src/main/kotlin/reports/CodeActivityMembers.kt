package ch.nokillswit.reports

import ch.nokillswit.norm.MrEventKind

/*
 * Report 21's per-member volumes (`measures.md` "Report 21", A50): NEUTRAL context, no cue, no rank. Each figure is attributed by its
 * own anchor (a commit by `committed_at`, an opened merge request by `created_at`, an act by `event_at`, an open one by now) to the
 * person its key is LINKED to and to the team that person was in then, so a mover's volumes split by anchor. An unlinked identity is
 * its own member (the UNASSIGNED bucket's rows). Counts are never hidden; the two p50 cells are under the minimum sample.
 */

/**
 * One member's volumes over the window. `filesChanged`, `linesAdded` and `linesDeleted` are the exclusion-aware counted figures, with the
 * commits whose counts are unknown left out (and counted in `commitsLinesUnknown`).
 */
internal data class CodeMemberFigures(
    val member: CodeMember,
    val commits: Int,
    val filesChanged: Long,
    val linesAdded: Long,
    val linesDeleted: Long,
    val commitsLinesUnknown: Int,
    val mrsMerged: Int,
    val mrsOpened: Int,
    val openMrs: Int,
    val mrCycleWorkingDaysP50: Double?,
    val mrSizeLinesP50: Double?,
    val reviewsGiven: Int,
    val approvalsGiven: Int,
    val changesRequestedGiven: Int,
    val comments: Int,
    val reviewRequestsReceived: Int,
)

private class MemberAcc {
    var commits = 0
    var files = 0L
    var added = 0L
    var deleted = 0L
    var unknown = 0
    var opened = 0
    var openNow = 0
    var comments = 0
    val cycle = mutableListOf<Double>()
    val size = mutableListOf<Double>()
    val reviewed = mutableSetOf<CodeMrRef>()
    val approved = mutableSetOf<CodeMrRef>()
    val changes = mutableSetOf<CodeMrRef>()
    val requested = mutableSetOf<CodeMrRef>()
}

/** The p50 of [values], absent under [minSample] values. */
private fun p50OrNull(values: List<Double>, minSample: Int): Double? =
    values.takeIf { it.isNotEmpty() && it.size >= minSample }?.sorted()?.let { percentileContinuous(it, CODE_MEDIAN) }

/**
 * The volumes of every member in scope, ordered by account then key. [roster] accounts are listed even with no row at all (the
 * team's people stay visible with zeros); [flow] supplies the merged merge requests, so the member cells and the team figures are
 * the same rows. [events] are the scope's actors' dated acts, [requests] every dated review request of the window.
 */
internal fun CodeKernelContext.memberFigures(
    flow: CodeFlowFigures,
    commits: List<CodeCommitRow>,
    mrs: List<CodeMrRow>,
    events: List<CodeEventRow>,
    requests: List<CodeEventRow>,
    roster: Collection<String>,
): List<CodeMemberFigures> {
    val acc = linkedMapOf<CodeMember, MemberAcc>()
    fun of(member: CodeMember) = acc.getOrPut(member) { MemberAcc() }
    roster.filter { selection.accounts == null || it in selection.accounts }.forEach { of(CodeMember(it, null)) }
    commits.forEach { commit -> addCommit(commit, ::of) }
    flow.mrs.forEach { figure ->
        val a = of(figure.scoped.member)
        a.cycle += figure.cycleWorkingDays
        figure.sizeLines?.let { a.size += it }
    }
    mrs.forEach { row ->
        if (inWindow(row.createdAt)) memberAt(row.connectionId, row.authorKey, row.createdAt)?.let { of(it).opened++ }
        if (row.state == MR_OPENED) memberAt(row.connectionId, row.authorKey, nowMs)?.let { of(it).openNow++ }
    }
    events.forEach { event -> addAct(event, ::of) }
    requests.forEach { event -> addRequests(event, ::of) }
    return acc.map { (member, a) -> a.toFigures(member, minSample) }
        .sortedWith(compareBy({ it.member.accountId ?: "" }, { it.member.identityKey ?: "" }))
}

private fun CodeKernelContext.addCommit(commit: CodeCommitRow, of: (CodeMember) -> MemberAcc) {
    val member = memberAt(commit.connectionId, commit.identityKey, commit.committedAt) ?: return
    val a = of(member)
    a.commits++
    if (commit.countsKnown) {
        a.files += commit.filesCounted!!
        a.added += commit.additionsCounted!!
        a.deleted += commit.deletionsCounted!!
    } else {
        a.unknown++
    }
}

/** An act by the actor, at its `event_at`: the review-kind sets are DISTINCT merge requests and leave out the author's own. */
private fun CodeKernelContext.addAct(event: CodeEventRow, of: (CodeMember) -> MemberAcc) {
    val at = event.eventAt ?: return
    val actor = event.actorKey ?: return
    val a = of(memberAt(event.mr.connectionId, actor, at) ?: return)
    if (event.kind == MrEventKind.COMMENT || event.kind == MrEventKind.DIFF_COMMENT) a.comments++
    if (event.kind !in REVIEW_KINDS || attribution.identities.samePerson(event.mr.connectionId, actor, event.mrAuthorKey)) return
    a.reviewed += event.mr
    if (event.kind == MrEventKind.APPROVAL) a.approved += event.mr
    if (event.kind == MrEventKind.CHANGES_REQUESTED) a.changes += event.mr
}

/** A review request names its targets: each one in scope at `event_at` received it (distinct merge requests). */
private fun CodeKernelContext.addRequests(event: CodeEventRow, of: (CodeMember) -> MemberAcc) {
    val at = event.eventAt?.takeIf { inWindow(it) } ?: return
    event.targetKeys.forEach { target -> memberAt(event.mr.connectionId, target, at)?.let { of(it).requested += event.mr } }
}

private fun MemberAcc.toFigures(member: CodeMember, minSample: Int) = CodeMemberFigures(
    member = member,
    commits = commits,
    filesChanged = files,
    linesAdded = added,
    linesDeleted = deleted,
    commitsLinesUnknown = unknown,
    mrsMerged = cycle.size,
    mrsOpened = opened,
    openMrs = openNow,
    mrCycleWorkingDaysP50 = p50OrNull(cycle, minSample),
    mrSizeLinesP50 = p50OrNull(size, minSample),
    reviewsGiven = reviewed.size,
    approvalsGiven = approved.size,
    changesRequestedGiven = changes.size,
    comments = comments,
    reviewRequestsReceived = requested.size,
)
