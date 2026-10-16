package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/** The most items one drill answers with (the Aging WIP idiom); [ReportDrillItems.total] and [ReportDrillBar] cover them all. */
internal const val DRILL_MAX_ITEMS = 500

/** What a drilled row is: a task, an epic, or (the data-quality late-logging drill) one worklog. */
@Serializable
enum class DrillItemKind { TASK, EPIC, WORKLOG }

/**
 * The bar the listed items add up to, recomputed by the server over the SAME rows the report's own bar used: [count] items,
 * [sumMd] their MD (MD bars; unestimated items add 0) and [from]/[to] the value range of a histogram bucket (`to` null = the open
 * tail). Fields a bar kind does not have are `null`.
 */
@Serializable
data class ReportDrillBar(val count: Int, val sumMd: Double?, val from: Double?, val to: Double?)

/**
 * One item behind a bar. [figure] is the bar's own per-item value (MD, days, a ratio, …); `null` = unestimated, which the bar
 * counts as 0 MD. [anchorAt] is the instant the report anchors the item to (`done_at`, a sprint's `complete_at`, a worklog's
 * `started_at`). [assignee] is the display name of [accountId] (the account id itself when no name is known).
 */
@Serializable
data class ReportDrillItem(
    val issueKey: String,
    val summary: String?,
    val itemKind: DrillItemKind,
    val accountId: String?,
    val assignee: String?,
    val teamId: UInt?,
    val anchorAt: Long?,
    val figure: Double?,
)

/**
 * A drill list (`GET /api/v1/reports/<report>/items`): the report's [meta], the [bar] it recomputed, at most [DRILL_MAX_ITEMS]
 * [items] (largest figure first) and the [total] of items behind the bar — [truncated] when [total] is more than were listed.
 * [bar] and [total] always describe the same set the items are cut from, so `total == bar.count`.
 */
@Serializable
data class ReportDrillItems(
    val meta: ReportMeta,
    val bar: ReportDrillBar,
    val items: List<ReportDrillItem>,
    val total: Int,
    val truncated: Boolean,
)

/** What a report's drill adapter hands [drillItems]: one row per item of the bar, identity only — labels are fetched later. */
internal data class DrillRow(
    val connectionId: UInt,
    val issueId: Long,
    val itemKind: DrillItemKind,
    val accountId: String?,
    val teamId: UInt?,
    val anchorAt: Long?,
    val figure: Double?,
)

/**
 * Sorts [rows] (figure descending with nulls last, newest anchor first, then connection and issue id), cuts them to
 * [DRILL_MAX_ITEMS], labels ONLY the listed ones (issue key and summary through [workItemLabels], assignee names through
 * [accountDisplayNames]), settles ties by issue key and assembles the response. Runs inside the caller's transaction.
 */
internal suspend fun drillItems(meta: ReportMeta, bar: ReportDrillBar, rows: List<DrillRow>): ReportDrillItems {
    val listed = rows.sortedWith(
        compareByDescending<DrillRow, Double?>(nullsFirst()) { it.figure }
            .thenByDescending(nullsFirst()) { it.anchorAt }
            .thenBy { it.connectionId }
            .thenBy { it.issueId },
    ).take(DRILL_MAX_ITEMS)
    val labels = workItemLabels(listed.map { it.connectionId to it.issueId })
    val names = accountDisplayNames(listed.mapNotNull { it.accountId }.distinct())
    val items = listed.map { row ->
        val label = labels[row.connectionId to row.issueId]
        ReportDrillItem(
            issueKey = label?.first ?: row.issueId.toString(),
            summary = label?.second,
            itemKind = row.itemKind,
            accountId = row.accountId,
            assignee = row.accountId?.let { names[it] ?: it },
            teamId = row.teamId,
            anchorAt = row.anchorAt,
            figure = row.figure,
        )
    }.sortedWith(
        compareByDescending<ReportDrillItem, Double?>(nullsFirst()) { it.figure }
            .thenByDescending(nullsFirst()) { it.anchorAt }
            .thenBy { it.issueKey },
    )
    return ReportDrillItems(meta, bar, items, total = rows.size, truncated = rows.size > items.size)
}

/**
 * The `segmentAccountId` rule: it narrows the listed items to ONE person of a USER-level request, so it must be one of the picked
 * `accountId`s (`400` below USER level or for a stranger). Returns the account id to narrow to, `null` for the whole bar.
 */
internal fun ReportFilter.requireSegment(segmentAccountId: String?): String? {
    if (segmentAccountId == null) return null
    if (level != ReportLevel.USER || segmentAccountId !in accountIds) {
        throw BadRequestException("segmentAccountId must be one of the picked accountIds (USER level only)")
    }
    return segmentAccountId
}
