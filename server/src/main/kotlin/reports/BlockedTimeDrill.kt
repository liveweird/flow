package ch.nokillswit.reports

/** Which of the blocked-time report's two histograms a drill reads (`view`). */
enum class BlockedDrillView { BLOCKED_WORKING_DAYS, SHARE_OF_CYCLE }

/**
 * `GET /api/v1/reports/blocked-time/items`: the DONE items behind ONE bar of the blocked-working-days or the share-of-cycle
 * histogram. The report's own fetch ([fetchBlockedItems], with the same `itemKind`: tasks, epics or both — each row says
 * which it is) and its own per-item value (`blockedWd` for every item, zeros included; `share` only for an item with a cycle
 * above zero) go through the one bucket rule ([distributionDrillItems]). The report's histograms carry no per-person split, so
 * there is no segment.
 */
internal suspend fun ReportService.blockedTimeItems(
    filter: ReportFilter,
    itemKind: BlockedItemKind,
    view: BlockedDrillView,
    bucketIndex: Int,
    nowMs: Long,
): ReportDrillItems = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val items = scope.window?.let { fetchBlockedItems(filter, itemKind, scope.connectionIds, it) }.orEmpty()
    val valueOf: (DoneItem) -> Double? =
        if (view == BlockedDrillView.BLOCKED_WORKING_DAYS) { item -> item.blockedWd } else { item -> item.share }
    distributionDrillItems(scope.meta, items, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, null, valueOf) { item, value ->
        DrillRow(item.connectionId, item.issueId, DrillItemKind.valueOf(item.kind), item.account, item.teamId, item.doneAt, value)
    }
}
