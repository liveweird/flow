package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintScopeItem
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * The USER-level frozen reader every sprint report shares (Velocity, Throughput's `bySprint`, Sprint consistency): a
 * sprint's per-user FROZEN figures are computed from the `fact_sprint_snapshot.scope` JSONB (the writer's
 * `MetricsSprintWrites.kt`'s `sprintScopeItemsJson` shape, D13) with the same predicates, attribution and rounding as the live ones —
 * `.claude/docs/metrics.md`: "per-user velocity from the snapshot needs no child table". ONE strict parser
 * ([parseFrozenScope]) and ONE fetch ([fetchFrozenScopes]); each report only picks the item shape it needs.
 * Every function runs inside the CALLER's `suspendTransaction`.
 */

/** One `fact_sprint_scope` row's contribution to Velocity's committed/final buckets (the removed-row rule applied by the caller). */
internal data class ScopeContribution(
    val connectionId: UInt,
    val sprintId: Long,
    val accountId: String?,
    val committed: Boolean,
    val inScopeAtClose: Boolean,
    val commitMd: Double?,
    val closeMd: Double?,
)

/**
 * One stored scope item under the strict reader: every accessor fails loudly (naming the connection, sprint and item)
 * on a missing key or a wrong JSON type — an invariant violation of the one writer, never read as "no estimate". A JSON
 * `null` stays `null` where the accessor is nullable (= no estimate / no timestamp / unassigned, exactly as the live columns).
 */
internal class FrozenScopeItem(
    private val item: JsonObject,
    private val index: Int,
    private val malformed: (String) -> Nothing,
) {
    private fun scalar(key: String): JsonPrimitive {
        val value = item[key] ?: malformed("item $index is missing key $key")
        return value as? JsonPrimitive ?: malformed("item $index key $key is not a scalar")
    }

    fun flag(key: String): Boolean =
        scalar(key).takeIf { !it.isString }?.booleanOrNull ?: malformed("item $index key $key is not a boolean")

    fun md(key: String): Double? {
        val value = scalar(key)
        if (value is JsonNull) return null
        return value.takeIf { !it.isString }?.doubleOrNull ?: malformed("item $index key $key is not a number")
    }

    fun nullableLong(key: String): Long? {
        val value = scalar(key)
        if (value is JsonNull) return null
        return value.takeIf { !it.isString }?.longOrNull ?: malformed("item $index key $key is not an integer")
    }

    fun long(key: String): Long = nullableLong(key) ?: malformed("item $index key $key is not an integer")

    fun nullableText(key: String): String? {
        val value = scalar(key)
        return when {
            value is JsonNull -> null
            value.isString -> value.content
            else -> malformed("item $index key $key is not a string")
        }
    }
}

/**
 * Parses one snapshot's stored `scope` JSON (written ONLY by `MetricsSprintWrites.kt`'s `sprintScopeItemsJson`) item by item through
 * [build]. A malformed document fails with `fact_sprint_snapshot.scope malformed for connection C sprint S: …`.
 */
internal fun <T> parseFrozenScope(scopeJson: String, connectionId: UInt, sprintId: Long, build: (FrozenScopeItem) -> T): List<T> {
    fun malformed(what: String): Nothing =
        error("fact_sprint_snapshot.scope malformed for connection $connectionId sprint $sprintId: $what")
    val array = Json.parseToJsonElement(scopeJson) as? JsonArray ?: malformed("not a JSON array")
    return array.mapIndexed { index, element ->
        val item = element as? JsonObject ?: malformed("item $index is not an object")
        build(FrozenScopeItem(item, index, ::malformed))
    }
}

/** The stored scope as Velocity's [ScopeContribution] rows (only the five keys its buckets read). */
internal fun frozenContributionsOf(scopeJson: String, connectionId: UInt, sprintId: Long): List<ScopeContribution> =
    parseFrozenScope(scopeJson, connectionId, sprintId) { item ->
        ScopeContribution(
            connectionId = connectionId,
            sprintId = sprintId,
            accountId = item.nullableText("assigneeAtCommitment"),
            committed = item.flag("committed"),
            inScopeAtClose = item.flag("inScopeAtClose"),
            commitMd = item.md("estimateAtCommitmentMd"),
            closeMd = item.md("estimateAtCloseMd"),
        )
    }

/**
 * The stored scope as the kernel's own [SprintScopeItem] (every key the writer emits), so Throughput and Sprint
 * consistency run `DeriveKernels.sprintTotals` — the very function that produced the frozen team totals — over it.
 */
internal fun frozenScopeItemsOf(scopeJson: String, connectionId: UInt, sprintId: Long): List<SprintScopeItem> =
    parseFrozenScope(scopeJson, connectionId, sprintId) { item ->
        SprintScopeItem(
            issueId = item.long("issueId"),
            addedAtMs = item.nullableLong("addedAtMs"),
            removedAtMs = item.nullableLong("removedAtMs"),
            committed = item.flag("committed"),
            inScopeAtClose = item.flag("inScopeAtClose"),
            estimateAtCommitmentMd = item.md("estimateAtCommitmentMd"),
            estimateAtCloseMd = item.md("estimateAtCloseMd"),
            estimateAtDoneMd = item.md("estimateAtDoneMd"),
            assigneeAtCommitment = item.nullableText("assigneeAtCommitment"),
            doneInSprint = item.flag("doneInSprint"),
            carriedOver = item.flag("carriedOver"),
            dropped = item.flag("dropped"),
        )
    }

/**
 * Each in-scope sprint's FROZEN scope rows, parsed by [parse] and narrowed to [accountId]'s own items ([assigneeOf]). A
 * sprint WITH a snapshot is always a key (an account with no items there maps to an empty list, so its frozen figures are
 * zeros, like the live ones); a sprint with none is absent -> the report's `snapshot = null`. Only the exact
 * (connection, sprint) pairs of [sprintRows] are kept: `connection IN (...) AND sprint IN (...)` is a cross product, and two
 * connections to one Jira site share sprint ids.
 */
internal suspend fun <T> fetchFrozenScopes(
    sprintRows: List<SprintRow>,
    accountId: String,
    parse: (scopeJson: String, connectionId: UInt, sprintId: Long) -> List<T>,
    assigneeOf: (T) -> String?,
): Map<Pair<UInt, Long>, List<T>> {
    if (sprintRows.isEmpty()) return emptyMap()
    val snapshot = MetricsTables.FactSprintSnapshot
    val inScope = sprintRows.map { it.connectionId to it.sprintId }.toSet()
    return snapshot.selectAll()
        .where {
            (snapshot.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
                (snapshot.sprintId inList sprintRows.map { it.sprintId }.distinct())
        }
        .toList()
        .filter { (it[snapshot.connectionId].value to it[snapshot.sprintId]) in inScope }
        .associate { row ->
            val connectionId = row[snapshot.connectionId].value
            val sprintId = row[snapshot.sprintId]
            (connectionId to sprintId) to parse(row[snapshot.scope], connectionId, sprintId).filter { assigneeOf(it) == accountId }
        }
}
