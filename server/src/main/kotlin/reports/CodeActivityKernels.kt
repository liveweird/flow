package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import java.time.ZoneId

/*
 * Report 21's flow figures (`.claude/docs/measures.md` "Report 21", domain-model A50), pure: no database, no clock. Every row is
 * attributed by the ANCHOR of the figure it feeds (a merged merge request by `merged_at`, an open one by now, ...) to the team its
 * author's dated membership covers then (`teamAt`, half-open); the per-member volumes are CodeActivityMembers.kt's. The verdicts
 * (lower is better) are the page's: these functions return numbers only.
 */

/** The selection, attribution, period and knobs every kernel of one request reads. */
internal class CodeKernelContext(
    val selection: CodeSelection,
    val attribution: CodeAttribution,
    /** The report's period, `[from, to)`. */
    val window: Pair<Long, Long>,
    val nowMs: Long,
    val calendar: WorkingCalendar,
    val zone: ZoneId,
    /** `metrics.settings.min_sample_size`: the distributions and percentiles under it are hidden; counts never are. */
    val minSample: Int,
) {
    fun inWindow(atMs: Long?): Boolean = atMs != null && atMs >= window.first && atMs < window.second

    /**
     * Who the row is when it is in scope at its anchor [atMs] (its key's person, or the undecided key itself), else null: the team that
     * person's membership covers at [atMs] must be the selected one (`0`: none), and at USER level the person must be a pick. A key an
     * admin decided BOT or IGNORED is in no figure anywhere (it needs no admin, and attributes nothing).
     */
    fun memberAt(connectionId: UInt, key: String?, atMs: Long): CodeMember? {
        if (key != null && attribution.identities.isNonHuman(connectionId, key)) return null
        val account = attribution.accountOf(connectionId, key)
        if (!selection.accepts(attribution.teamOf(connectionId, key, atMs), account)) return null
        return codeMemberOf(account, key, attribution.identities.hostOf(connectionId))
    }
}

/** A merge request attributed to [member] at its anchor. */
internal data class ScopedMr(val row: CodeMrRow, val member: CodeMember) {
    val accountId: String? get() = member.accountId
}

/** Merged merge requests of the window (`merged_at`), authored by someone in scope at `merged_at`. */
internal fun CodeKernelContext.mergedInScope(mrs: List<CodeMrRow>): List<ScopedMr> = mrs.mapNotNull { row ->
    if (row.state != MR_MERGED || !inWindow(row.mergedAt)) return@mapNotNull null
    memberAt(row.connectionId, row.authorKey, row.mergedAt!!)?.let { ScopedMr(row, it) }
}

/** How one merged merge request was reviewed by OTHER humans (the `decidedKeys` exclusion applied). */
internal class MrReview(val firstReviewAt: Long?, val approved: Boolean, val reviewers: Set<String>)

/**
 * The review of [row] from its actors' [summaries] (already bounded at the merge, `fetchCodeReviewSummaries`): an actor counts when
 * it is not the author (the same key, or another key LINKED to the author's account), and is not decided BOT or IGNORED; an
 * unlinked HUMAN reviewer counts. [MrReview.reviewers] are the distinct people
 * with a dated review-kind event (the LINKED account, else the key).
 */
internal fun CodeKernelContext.reviewOf(row: CodeMrRow, summaries: List<CodeReviewSummary>): MrReview {
    val identities = attribution.identities
    val others = summaries.filter {
        !identities.isNonHuman(row.connectionId, it.actorKey) && !identities.samePerson(row.connectionId, it.actorKey, row.authorKey)
    }
    // An unlinked reviewer is told apart by host AND key: a key is only unique within one GitLab instance.
    val host = identities.hostOf(row.connectionId)
    val reviewers = others.filter { it.firstReviewAt != null }
        .map { attribution.accountOf(row.connectionId, it.actorKey) ?: "key:$host:${it.actorKey}" }.toSet()
    return MrReview(others.mapNotNull { it.firstReviewAt }.minOrNull(), others.any { it.approved }, reviewers)
}

/**
 * One merged merge request's measurable values. The review fields are null when its details were not read (its events are not
 * known: counted in `quality.mrsDetailsNotRead`, never "unreviewed"); [firstReviewWorkingDays] is also null when nobody reviewed it.
 */
internal data class MergedMrFigure(
    val scoped: ScopedMr,
    val cycleWorkingDays: Double,
    val cycleElapsedDays: Double,
    val review: MrReview?,
    val firstReviewWorkingDays: Double?,
    val sizeLines: Double?,
    val sizeFiles: Double?,
)

/** A count and its share of the population it is read against (null: the population is empty). */
internal data class CodeShare(val count: Int, val share: Double?)

/** One Monday-started week of the zone and the merge requests merged in it. */
internal data class CodeWeek(val bucketStart: LocalDate, val merged: Int)

/** The team's flow over the window: [mrs] are every merged merge request in scope, the distributions are views over them. */
internal class CodeFlowFigures(
    val mrs: List<MergedMrFigure>,
    val closedUnmerged: Int,
    val mergedWithoutReview: CodeShare,
    val mergedWithoutApproval: CodeShare,
    val weekly: List<CodeWeek>,
) {
    val merged: Int get() = mrs.size
}

/**
 * The flow figures. [summaries] hold the actors of the READ merged merge requests (`fetchCodeReviewSummaries`). Merged without review /
 * approval are counted among the merge requests whose details were read, and their share is read against those.
 */
internal fun CodeKernelContext.flowFigures(mrs: List<CodeMrRow>, summaries: List<CodeReviewSummary>): CodeFlowFigures {
    val byMr = summaries.groupBy { it.mr }
    val figures = mergedInScope(mrs).map { scoped ->
        val row = scoped.row
        val mergedAt = row.mergedAt!!
        val review = if (row.detailsRead) reviewOf(row, byMr[row.ref].orEmpty()) else null
        MergedMrFigure(
            scoped = scoped,
            cycleWorkingDays = calendar.workingDaysBetween(row.createdAt, mergedAt),
            cycleElapsedDays = (mergedAt - row.createdAt) / MS_PER_DAY,
            review = review,
            firstReviewWorkingDays = review?.firstReviewAt?.let { calendar.workingDaysBetween(row.createdAt, it) },
            sizeLines = row.sizeLines?.toDouble(),
            sizeFiles = row.fileCount?.toDouble(),
        )
    }
    val read = figures.mapNotNull { it.review }
    fun share(count: Int) = CodeShare(count, if (read.isEmpty()) null else count.toDouble() / read.size)
    val closed = mrs.count { row ->
        row.state == MR_CLOSED && inWindow(row.closedAt) && memberAt(row.connectionId, row.authorKey, row.closedAt!!) != null
    }
    return CodeFlowFigures(
        mrs = figures,
        closedUnmerged = closed,
        mergedWithoutReview = share(read.count { it.firstReviewAt == null }),
        mergedWithoutApproval = share(read.count { !it.approved }),
        weekly = weeklyMerged(figures.map { it.scoped.row.mergedAt!! }),
    )
}

/** Merged-per-week bars over every Monday-started week of the window, zero-filled (the sum is the number merged). */
internal fun CodeKernelContext.weeklyMerged(mergedAts: List<Long>): List<CodeWeek> {
    val days = bucketStarts(window.first, window.second, zone, ThroughputBucket.WEEK)
    val starts = days.map { calendar.dayBoundsMs(it).first }
    val counts = IntArray(days.size)
    for (at in mergedAts) {
        val found = starts.binarySearch(at)
        val index = if (found >= 0) found else -found - 2
        if (index in counts.indices) counts[index]++
    }
    return days.mapIndexed { i, day -> CodeWeek(day, counts[i]) }
}

/**
 * The distribution of [valueOf] over the merged merge requests that have one. At USER level ([picked] non-empty) the shares of every
 * picked person ride along (`buildDistributionByUser`); a hidden one carries none.
 */
internal fun CodeFlowFigures.distribution(
    minSample: Int,
    tailPercent: Int,
    picked: PickedAccounts,
    valueOf: (MergedMrFigure) -> Double?,
): Distribution {
    if (picked.ids.isEmpty()) return buildDistribution(mrs.mapNotNull(valueOf), minSample, tailPercent)
    return buildDistributionByUser(
        picked.ids.map { id -> UserValues(id, picked.label(id), mrs.filter { it.scoped.accountId == id }.mapNotNull(valueOf)) },
        minSample,
        tailPercent,
    )
}

/**
 * The review concentration (optional, D-C 8): reviewers per merged merge request (p50, zeros included) and the share of the distinct
 * (merge request, reviewer) pairs the busiest reviewer holds, over the merge requests whose details were read; both absent under
 * `minSample` of them ([n] says how many). A bottleneck signal about the review process: it names no one.
 */
internal data class CodeReviewConcentration(val n: Int, val reviewersPerMergedMrP50: Double?, val topReviewerShare: Double?)

internal fun reviewConcentration(flow: CodeFlowFigures, minSample: Int): CodeReviewConcentration {
    val reviewed = flow.mrs.mapNotNull { it.review }
    val n = reviewed.size
    if (n == 0 || n < minSample) return CodeReviewConcentration(n, null, null)
    val perMr = reviewed.map { it.reviewers.size.toDouble() }.sorted()
    val pairs = reviewed.sumOf { it.reviewers.size }
    val busiest = reviewed.flatMap { it.reviewers }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return CodeReviewConcentration(n, percentileContinuous(perMr, CODE_MEDIAN), if (pairs == 0) null else busiest.toDouble() / pairs)
}

/** The open merge requests now: [count] excludes the [drafts] (a draft is not waiting for review), the age is over [count]'s. */
internal data class CodeOpenFigures(
    val count: Int,
    val drafts: Int,
    val ageHidden: Boolean,
    val ageP50: Double?,
    val ageP85: Double?,
)

/** Open merge requests (state `opened`, however old) whose author is in scope at NOW; the age is created-to-now in working days. */
internal fun CodeKernelContext.openFigures(mrs: List<CodeMrRow>): CodeOpenFigures {
    val open = mrs.filter { it.state == MR_OPENED && memberAt(it.connectionId, it.authorKey, nowMs) != null }
    val waiting = open.filterNot { it.draft }
    val ages = waiting.map { calendar.workingDaysBetween(it.createdAt, nowMs) }.sorted()
    val hidden = ages.size < minSample
    return CodeOpenFigures(
        count = waiting.size,
        drafts = open.size - waiting.size,
        ageHidden = hidden,
        ageP50 = ages.takeIf { !hidden && it.isNotEmpty() }?.let { percentileContinuous(it, CODE_MEDIAN) },
        ageP85 = ages.takeIf { !hidden && it.isNotEmpty() }?.let { percentileContinuous(it, CODE_P85) },
    )
}

/** What the sources hold that no figure can use, connection-wide for the window (`quality`, `measures.md` "Unattributed work"). */
internal data class CodeQuality(
    val unlinkedIdentities: Int,
    val unlinkedCommits: Long,
    val unlinkedMergeRequests: Long,
    val unlinkedEvents: Long,
    val commitsLinesUnknown: Long,
    val mrsSizeUnknown: Long,
    val mrsDetailsNotRead: Long,
    val eventsUndated: Long,
)

/**
 * The `quality` block: the activity of the UNDECIDED keys (the ones that need an admin; a BOT or IGNORED key is a decision and counts
 * nowhere) and the unknown counts.
 */
internal fun codeQuality(activity: List<CodeKeyActivity>, counts: CodeQualityCounts, identities: CodeIdentities): CodeQuality {
    val unlinked = activity.filterNot { identities.isDecided(it.connectionId, it.key) }
    return CodeQuality(
        unlinkedIdentities = unlinked.map { identities.hostOf(it.connectionId) to it.key }.distinct().size,
        unlinkedCommits = unlinked.sumOf { it.commits },
        unlinkedMergeRequests = unlinked.sumOf { it.mergeRequests },
        unlinkedEvents = unlinked.sumOf { it.events },
        commitsLinesUnknown = counts.commitsLinesUnknown,
        mrsSizeUnknown = counts.mrsSizeUnknown,
        mrsDetailsNotRead = counts.mrsDetailsNotRead,
        eventsUndated = counts.eventsUndated,
    )
}

internal const val CODE_MEDIAN = 0.5
internal const val CODE_P85 = 0.85
