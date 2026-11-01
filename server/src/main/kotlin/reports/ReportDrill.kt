package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/** The most items one drill answers with (the Aging WIP idiom); [ReportDrillItems.total] and [ReportDrillBar] cover them all. */
internal const val DRILL_MAX_ITEMS = 500

/**
 * What a drilled row is: a task, an epic, (the data-quality late-logging drill) one worklog, or (Report 21's code drill) a commit or a
 * merge request of the GitLab code layer, whose `issueKey` is `<project path>@<sha12>` / `<project path>!<iid>`.
 */
@Serializable
enum class DrillItemKind { TASK, EPIC, WORKLOG, COMMIT, MERGE_REQUEST }

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
    /**
     * The Jira issue keys a merge request names within the known Jira projects, sorted (A51; empty = it names none): set ONLY on the
     * `MERGE_REQUEST` items of Report 21's drill and Home's `CODE_*` lists; null for every other kind and report.
     */
    val linkedKeys: List<String>? = null,
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
    /** A ready-made assignee label, for a row whose people are not one account (the cost drill's authors); `null` = name [accountId]. */
    val assignee: String? = null,
)

/** The drill order (figure descending, nulls last; newest anchor; connection; issue id): the ONE order of [drillItems] and a SQL cut. */
internal val DRILL_ORDER: Comparator<DrillRow> =
    compareByDescending<DrillRow, Double?>(nullsFirst()) { it.figure }
        .thenByDescending(nullsFirst()) { it.anchorAt }
        .thenBy { it.connectionId }
        .thenBy { it.issueId }

/**
 * Sorts [rows] ([DRILL_ORDER]), cuts them to [DRILL_MAX_ITEMS], labels ONLY the listed ones (issue key and summary through
 * [workItemLabels], assignee names through [accountDisplayNames]), settles ties by issue key and assembles the response. Runs inside
 * the caller's transaction.
 */
internal suspend fun drillItems(meta: ReportMeta, bar: ReportDrillBar, rows: List<DrillRow>): ReportDrillItems =
    listedDrillItems(meta, bar, rows.sortedWith(DRILL_ORDER).take(DRILL_MAX_ITEMS), total = rows.size)

/**
 * [drillItems] for a read that already ordered ([DRILL_ORDER], `ORDER BY ... LIMIT` [DRILL_MAX_ITEMS]) and counted in SQL: [listed] is
 * the first page of the set and [total] the size of the WHOLE set (the figures of [bar] are the caller's, over that same set). The
 * rows are re-sorted by the issue key tie-break only, like [drillItems].
 */
internal suspend fun listedDrillItems(meta: ReportMeta, bar: ReportDrillBar, listed: List<DrillRow>, total: Int): ReportDrillItems {
    val labels = workItemLabels(listed.map { it.connectionId to it.issueId })
    val names = accountDisplayNames(listed.filter { it.assignee == null }.mapNotNull { it.accountId }.distinct())
    val items = listed.map { row ->
        val label = labels[row.connectionId to row.issueId]
        ReportDrillItem(
            issueKey = label?.first ?: row.issueId.toString(),
            summary = label?.second,
            itemKind = row.itemKind,
            accountId = row.accountId,
            assignee = row.assignee ?: row.accountId?.let { names[it] ?: it },
            teamId = row.teamId,
            anchorAt = row.anchorAt,
            figure = row.figure,
        )
    }.sortedWith(
        compareByDescending<ReportDrillItem, Double?>(nullsFirst()) { it.figure }
            .thenByDescending(nullsFirst()) { it.anchorAt }
            .thenBy { it.issueKey },
    )
    return ReportDrillItems(meta, bar, items, total = total, truncated = total > items.size)
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

/** A required enum query parameter: `400` when absent or blank, or (through [optionalEnum]) when not a constant of [E]. */
internal inline fun <reified E : Enum<E>> Parameters.requireEnum(name: String): E =
    optionalEnum<E>(name) ?: throw BadRequestException("$name is required (allowed: ${enumValues<E>().joinToString { it.name }})")

/** The required `bucketIndex` of a histogram drill: a non-negative integer (`400` otherwise); the histogram checks its range. */
internal fun Parameters.requireBucketIndex(): Int = optionalBucketIndex() ?: throw BadRequestException("bucketIndex is required")

/** An optional `bucketIndex`: absent is `null`, anything but a non-negative integer is a `400`. */
internal fun Parameters.optionalBucketIndex(): Int? =
    optionalString("bucketIndex")?.let { raw ->
        raw.toIntOrNull()?.takeIf { it >= 0 } ?: throw BadRequestException("Invalid bucketIndex: $raw")
    }
