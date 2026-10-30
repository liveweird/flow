package ch.nokillswit

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.CompareDrillView
import ch.nokillswit.reports.CostDrillGroup
import ch.nokillswit.reports.CostsDrillSelection
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.DrillRow
import ch.nokillswit.reports.ReportDrillBar
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportMeta
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.accountDisplayNames
import ch.nokillswit.reports.costKeysSlice
import ch.nokillswit.reports.drillItems
import ch.nokillswit.reports.fetchCompareWorklogs
import ch.nokillswit.reports.fetchCostWorklogs
import ch.nokillswit.reports.groupCostWorklogs
import ch.nokillswit.reports.costsDrillSelection
import ch.nokillswit.reports.md
import ch.nokillswit.reports.parseComparePeople
import ch.nokillswit.reports.parseReportFilter
import ch.nokillswit.reports.rankedAuthors
import ch.nokillswit.reports.readCostSprints
import ch.nokillswit.reports.resolveReportScope
import ch.nokillswit.reports.worklogSlice
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and

/*
 * The cost and compare-people worklog drills as they were BEFORE the Postgres pushdown (master 14ad809): every matching worklog is read
 * into the JVM, grouped, rolled up, sorted by `drillItems` and cut to 500 there. Kept as the golden reference the set-based drills
 * (`fetchCostDrillPage`, `fetchCompareWorklogPage`) are graded against (`ReportDrillPushdownGoldenTest` and the fixture tests): the two
 * must answer the same `ReportDrillItems` -- meta, bar, items, total, truncated -- bit for bit.
 */

/** One listed item before it is labelled: the task (or epic, or above-epic issue) every group of it rolls up to. */
private class LegacyItem(val connectionId: UInt, val issueId: Long, val isEpic: Boolean, val groups: List<CostDrillGroup>)

private fun legacyRollUp(groups: List<CostDrillGroup>): List<LegacyItem> =
    groups.groupBy { it.connectionId to (it.parentTaskId ?: it.issueId) }.map { (owner, rows) ->
        LegacyItem(owner.first, owner.second, isEpic = rows.any { it.epicId == it.issueId }, groups = rows)
    }

private suspend fun legacyCostsResult(meta: ReportMeta, groups: List<CostDrillGroup>): ReportDrillItems {
    val items = legacyRollUp(groups)
    val names = accountDisplayNames(groups.mapNotNull { it.authorAccountId }.distinct())
    val bar = ReportDrillBar(
        count = items.size,
        sumMd = groups.fold(BigDecimal.ZERO) { sum, group -> sum + group.md }.md(),
        from = null,
        to = null,
    )
    return drillItems(
        meta,
        bar,
        items.map { item ->
            val authors = rankedAuthors(item.groups.map { it.authorAccountId to it.md }, names)
            DrillRow(
                connectionId = item.connectionId,
                issueId = item.issueId,
                itemKind = if (item.isEpic) DrillItemKind.EPIC else DrillItemKind.TASK,
                accountId = authors.singleOrNull()?.first,
                teamId = null,
                anchorAt = item.groups.mapNotNull { it.lastStartedAt }.maxOrNull(),
                figure = item.groups.fold(BigDecimal.ZERO) { sum, group -> sum + group.md }.md(),
                assignee = authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { (account, _) -> names[account] ?: account },
            )
        },
    )
}

/** The pre-pushdown `costsItems`: the slice's (or the sprint's) worklogs all read into the JVM, then grouped and cut there. */
internal suspend fun ReportService.legacyCostsItems(filter: ReportFilter, selection: CostsDrillSelection, nowMs: Long): ReportDrillItems =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val window = scope.window
        val groups = when {
            window == null || scope.connectionIds.isEmpty() -> emptyList()
            selection is CostsDrillSelection.Slice ->
                groupCostWorklogs(
                    fetchCostWorklogs(worklogSlice(filter, scope.connectionIds, window) and costKeysSlice(selection.by, selection.keys)),
                )
            selection is CostsDrillSelection.SprintBar ->
                readCostSprints(filter, scope.connectionIds, window, scope.sprintRows, nowMs).perSprint.flatten()
                    .filter { selection.epicSegment == null || selection.epicSegment.matches(it.currentEpicId) }
            else -> emptyList()
        }
        legacyCostsResult(scope.meta, groups)
    }

/** The pre-pushdown compare-people `LOGGED` / `FOREIGN` / `LATE` drill: every worklog of the picks read, then filtered to the person. */
internal suspend fun ReportService.legacyCompareWorklogItems(
    filter: ReportFilter,
    view: CompareDrillView,
    segment: String,
    nowMs: Long,
): ReportDrillItems = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val window = requireNotNull(scope.window)
    val own = fetchCompareWorklogs(filter, scope.connectionIds, window).filter { it.accountId == segment }.filter {
        when (view) {
            CompareDrillView.FOREIGN -> it.foreignWork
            CompareDrillView.LATE -> it.lateMs != null && it.lateMs > MILLIS_PER_DAY
            else -> true
        }
    }
    val rows = own.map { DrillRow(it.connectionId, it.issueId, DrillItemKind.WORKLOG, segment, null, it.startedAt, it.md.toDouble()) }
    drillItems(scope.meta, ReportDrillBar(rows.size, own.map { it.md }.fold(BigDecimal.ZERO, BigDecimal::add).md(), null, null), rows)
}

// ---- the golden comparison: the live endpoint against the legacy path, on the same query ---------------------------------------------

private fun ReportDrillItems.wire(): String = Json.encodeToString(ReportDrillItems.serializer(), this)

private fun legacyService(): ReportService {
    val settings = DerivedStubFixture.metricsSettings()
    return ReportService(sharedDatabaseForTests(), settings, TeamMembershipService(sharedDatabaseForTests(), settings))
}

private suspend fun HttpClient.liveDrill(path: String, query: String): ReportDrillItems {
    val response = get("/api/v1/reports/$path?$query")
    assertEquals(HttpStatusCode.OK, response.status, "GET $path?$query")
    return response.body()
}

/**
 * `GET costs/items?[query]` answered by the server and by [legacyCostsItems] on the same request: equal whole (meta, bar, items in order,
 * total, truncated) and as JSON. Returns the answer for further assertions.
 */
internal suspend fun HttpClient.assertCostsDrillGolden(query: String): ReportDrillItems {
    val now = System.currentTimeMillis()
    val params = parseQueryString(query)
    val calendar = DerivedStubFixture.metricsSettings().readCalendar()
    val filter = params.parseReportFilter(calendar, now, DomainView.EPIC, null)
    val old = legacyService().legacyCostsItems(filter, params.costsDrillSelection(), now)
    val new = liveDrill("costs/items", query)
    assertEquals(old, new, "costs/items?$query")
    assertEquals(old.wire(), new.wire(), "costs/items?$query (JSON)")
    return new
}

/**
 * `GET compare-people/items?[query]&view=[view]&segmentAccountId=[person]` answered by the server and by
 * [legacyCompareWorklogItems]: equal whole and as JSON. [query] carries the connection, the period and the two `accountId`s.
 */
internal suspend fun HttpClient.assertCompareWorklogDrillGolden(query: String, view: CompareDrillView, person: String): ReportDrillItems {
    val now = System.currentTimeMillis()
    val full = "$query&view=$view&segmentAccountId=$person"
    val filter = parseQueryString(full).parseComparePeople(DerivedStubFixture.metricsSettings().readCalendar(), now, null)
    val old = legacyService().legacyCompareWorklogItems(filter, view, person, now)
    val new = liveDrill("compare-people/items", full)
    assertEquals(old, new, "compare-people/items?$full")
    assertEquals(old.wire(), new.wire(), "compare-people/items?$full (JSON)")
    return new
}
