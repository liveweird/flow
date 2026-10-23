package ch.nokillswit.reports

import ch.nokillswit.metrics.MD_SCALE
import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The cost drill's set-based read (`.claude/docs/reports.md` "Drill to items" and "Report 16"): the drill lists at most
 * `DRILL_MAX_ITEMS` items, so the items are formed, ordered and cut in Postgres and only that page crosses to the JVM. ONE statement:
 * a worklog is rolled up to the item it belongs to (a sub-task to its parent task, D2), the items are grouped, ordered like `drillItems`
 * (rounded MD, newest anchor, connection, issue id) and limited, and the whole set's item count and exact MD ride along as window
 * functions evaluated BEFORE the limit -- so `bar` and `total` still describe every item and a concurrent DERIVE cannot split them from the
 * page. Each listed item also carries its worklogs' (author, MD) pairs as one JSON array, which the authors line is ranked from.
 */

private const val SUM_PRECISION = 18
private const val STORED_SCALE = 4

/** One listed item of the cost drill: exact MD, latest `started_at`, whether it is an epic logged on directly, its (author, MD) shares. */
internal class CostDrillListed(
    val connectionId: UInt,
    val issueId: Long,
    val isEpic: Boolean,
    val md: BigDecimal,
    val lastStartedAt: Long,
    val shares: List<Pair<String?, BigDecimal>>,
)

/** The first page of the drilled set: the [items] listed (at most [DRILL_MAX_ITEMS]), the [total] items of the set and its [exactMd]. */
internal class CostDrillPage(val items: List<CostDrillListed>, val total: Int, val exactMd: BigDecimal) {
    companion object {
        val EMPTY = CostDrillPage(emptyList(), 0, BigDecimal.ZERO)
    }
}

/**
 * The issue a worklog rolls up to: its sub-task's parent task, else the issue itself (a worklog on an epic or an above-epic issue lists
 * that issue, A31). [costParentTaskId] spelled in SQL, with no bind parameter: the SELECT, GROUP BY and ORDER BY spell this one expression
 * and Postgres only matches them when their text is identical.
 */
private fun costRolledUpIssue(): Expression<Long?> =
    Coalesce(Case().When(MetricsTables.DimTask.isSubtask, MetricsTables.DimTask.parentTaskId), MetricsTables.FactWorklog.issueId)

/**
 * The epic a worklog counts toward NOW, [costCurrentEpicId] spelled in SQL: its `dim_task` row's (the join found a task or sub-task
 * row), else its own epic when it is logged on it (A21), else none.
 */
private fun costCurrentEpic(): ExpressionWithColumnType<Long?> {
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    return Case().When(task.issueId.isNotNull(), task.epicId).Else(Case().When(w.epicId eq w.issueId, w.epicId))
}

/**
 * The worklogs this epic segment keeps ([EpicSegmentSelector.matches] spelled in SQL, over [costCurrentEpic]): the sprint bar's drill is
 * narrowed with it, so a segment's list is the chart's segment.
 */
internal fun EpicSegmentSelector.costPredicate(): Op<Boolean> {
    val current = costCurrentEpic()
    return when (key) {
        NO_EPIC_KEY -> current.isNull()
        OTHER_EPICS_KEY -> current.isNotNull() and (current notInList topEpicIds)
        else -> current eq key.toLong()
    }
}

/**
 * The first [DRILL_MAX_ITEMS] items of the worklogs [predicate] selects, ordered like [drillItems], with the whole set's size and exact MD.
 * The worklogs are the `fact_worklog` rows left-joined to `dim_task` (a task or sub-task row names the parent and the current epic).
 */
internal suspend fun fetchCostDrillPage(predicate: Op<Boolean>): CostDrillPage {
    val w = MetricsTables.FactWorklog
    val item = costRolledUpIssue()
    val md = w.md.sum()
    val rounded = CustomFunction("ROUND", DecimalColumnType(SUM_PRECISION, STORED_SCALE), md, intLiteral(MD_SCALE))
    val last = w.startedAt.max()
    // BOOL_OR of nothing but NULLs (no worklog of the item is on an epic) is NULL: false.
    val onEpic = CustomFunction(
        "COALESCE", BooleanColumnType(), CustomFunction("BOOL_OR", BooleanColumnType(), w.epicId eq w.issueId), booleanLiteral(false),
    )
    val shares = CustomFunction(
        "JSONB_AGG", TextColumnType(), CustomFunction("JSONB_BUILD_ARRAY", TextColumnType(), w.authorAccountId, w.md),
    ).castTo(TextColumnType())
    val itemCount = Count(intLiteral(1)).over()
    val totalMd = Sum(md, DecimalColumnType(SUM_PRECISION, STORED_SCALE)).over()
    val rows = costWorklogSource().select(w.connectionId, item, md, last, onEpic, shares, itemCount, totalMd)
        .where { predicate }
        .groupBy(w.connectionId, item)
        .orderBy(rounded to SortOrder.DESC, last to SortOrder.DESC_NULLS_LAST, w.connectionId to SortOrder.ASC, item to SortOrder.ASC)
        .limit(DRILL_MAX_ITEMS)
        .toList()
    val first = rows.firstOrNull() ?: return CostDrillPage.EMPTY
    return CostDrillPage(
        items = rows.map {
            CostDrillListed(
                connectionId = it[w.connectionId].value,
                issueId = requireNotNull(it[item]),
                isEpic = it[onEpic],
                md = it[md] ?: BigDecimal.ZERO,
                lastStartedAt = requireNotNull(it[last]) { "an item has at least one worklog" },
                shares = sharesOf(it[shares]),
            )
        },
        total = requireNotNull(first[itemCount]).toInt(),
        exactMd = first[totalMd] ?: BigDecimal.ZERO,
    )
}

/** The `[[author|null, md], …]` array of an item's worklogs, parsed with the exact decimal digits. */
private fun sharesOf(json: String): List<Pair<String?, BigDecimal>> =
    Json.parseToJsonElement(json).jsonArray.map { pair ->
        val (author, md) = pair.jsonArray
        (author as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content to BigDecimal((md as JsonPrimitive).content)
    }
