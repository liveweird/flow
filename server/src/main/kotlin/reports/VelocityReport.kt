package ch.nokillswit.reports

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.sumMd
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The four figures every velocity row carries, live or frozen (`fact_sprint(_snapshot)`/`fact_sprint_scope` alike). */
@Serializable
data class VelocitySnapshot(val initialMd: Double, val initialItems: Int, val finalMd: Double, val finalItems: Int)

/**
 * One sprint's own velocity (Report 1, `.claude/docs/measures.md` "Report 1 — Velocity"): `initial`
 * = committed, `final` = final scope (A17). [snapshot] is the frozen `fact_sprint_snapshot` figures
 * (`null` until the sprint was closed AND team-mapped at some past DERIVE — D13); [drift] compares
 * the two, `false` whenever no snapshot exists yet. At USER level the snapshot is that one account's frozen
 * share of the sprint's stored scope (see [buildUserSprints]).
 */
@Serializable
data class VelocitySprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val snapshot: VelocitySnapshot?,
    val drift: Boolean,
)

/**
 * One org-drill entry: a team's own sums at UNIT level ([teamId]/[label] set, [accountId] null,
 * summed straight off `fact_sprint`), or one user's `assignee_at_commitment` sums at TEAM level
 * ([accountId]/[label] set, summed off `fact_sprint_scope` — the same removed-row rule the team
 * total itself follows, so Σ users == the team total; a `null` [accountId]/[label] is the
 * unassigned-at-commitment bucket, never a stored sentinel — the `credit_team_id` convention).
 * Always empty at USER level, where [VelocityReport.sprints] is already that one user's own split.
 */
@Serializable
data class VelocityGroup(
    val teamId: UInt? = null,
    val accountId: String? = null,
    val label: String? = null,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

@Serializable
data class VelocityReport(val meta: ReportMeta, val sprints: List<VelocitySprint>, val groups: List<VelocityGroup>)

/** One `fact_sprint_scope` row's contribution to the committed/final buckets (the removed-row rule applied by the caller). */
internal data class ScopeContribution(
    val connectionId: UInt,
    val sprintId: Long,
    val accountId: String?,
    val committed: Boolean,
    val inScopeAtClose: Boolean,
    val commitMd: Double?,
    val closeMd: Double?,
)

/** The committed(∧inScopeAtClose)/final figures of one account's (or any group's) scope rows — the ONE rule live and frozen share. */
private fun figuresOf(rows: List<ScopeContribution>): VelocitySnapshot = VelocitySnapshot(
    initialMd = sumMd(rows.filter { it.committed && it.inScopeAtClose }.map { it.commitMd }),
    initialItems = rows.count { it.committed && it.inScopeAtClose },
    finalMd = sumMd(rows.filter { it.inScopeAtClose }.map { it.closeMd }),
    finalItems = rows.count { it.inScopeAtClose },
)

private fun velocityDrift(live: VelocitySnapshot, snapshot: VelocitySnapshot?): Boolean {
    if (snapshot == null) return false
    return kotlin.math.abs(live.initialMd - snapshot.initialMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        kotlin.math.abs(live.finalMd - snapshot.finalMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        live.initialItems != snapshot.initialItems ||
        live.finalItems != snapshot.finalItems
}

/**
 * `GET /api/v1/reports/velocity` (v0.3.0 M4 commit 10b, Report 1). Levels (from the parsed
 * filter): UNIT groups every team's closed sprints in the period by team (Σ final MD/items,
 * straight off `fact_sprint`); TEAM narrows to one team's own sprints, grouped by
 * `fact_sprint_scope.assignee_at_commitment`; USER narrows `sprints` itself to that one account's
 * own contribution (`groups` stays empty). `teamId = 0` (UNASSIGNED) is always empty.
 */
suspend fun ReportService.velocity(filter: ReportFilter): VelocityReport = suspendTransaction(database) {
    // A sprint-anchored report reads no time window, so `nowMs` only feeds the scope's unused `window`.
    val scope = resolveReportScope(filter, nowMillis())
    val sprintRows = scope.sprintRows
    val meta = scope.meta

    if (filter.level == ReportLevel.USER) {
        val accountId = requireNotNull(filter.accountId) { "USER level always carries an accountId (ReportFilter's own invariant)" }
        val contributions = fetchScopeContributions(sprintRows, accountId)
        val frozen = fetchFrozenContributions(sprintRows, accountId)
        return@suspendTransaction VelocityReport(meta, buildUserSprints(sprintRows, contributions, frozen), emptyList())
    }

    val snapshotByKey = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
    val sprints = sprintRows.map { row ->
        val snapshot = snapshotByKey[row.connectionId to row.sprintId]?.figures
        VelocitySprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = row.live.initialMd, initialItems = row.live.initialItems,
            finalMd = row.live.finalMd, finalItems = row.live.finalItems,
            snapshot = snapshot, drift = velocityDrift(row.live, snapshot),
        )
    }
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamGroups(sprintRows)
        ReportLevel.TEAM -> userGroups(fetchScopeContributions(sprintRows, accountId = null))
        ReportLevel.USER -> emptyList() // handled above
    }
    VelocityReport(meta, sprints, groups)
}

/** [accountId] `null` fetches every user's own contribution (TEAM-level groups); non-null narrows to one (USER level). */
private suspend fun fetchScopeContributions(sprintRows: List<SprintRow>, accountId: String?): List<ScopeContribution> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    var predicate: Op<Boolean> = (MetricsTables.FactSprintScope.connectionId inList connectionIds) and
        (MetricsTables.FactSprintScope.sprintId inList sprintIds)
    accountId?.let { predicate = predicate and (MetricsTables.FactSprintScope.assigneeAtCommitment eq it) }
    // connection IN (...) AND sprint IN (...) is a cross product: keep exactly the (connection, sprint) pairs
    // in scope, since two connections to one Jira site share sprint ids.
    val inScope = sprintRows.map { it.connectionId to it.sprintId }.toSet()
    return MetricsTables.FactSprintScope.selectAll().where { predicate }.toList().filter {
        (it[MetricsTables.FactSprintScope.connectionId].value to it[MetricsTables.FactSprintScope.sprintId]) in inScope
    }.map {
        ScopeContribution(
            connectionId = it[MetricsTables.FactSprintScope.connectionId].value,
            sprintId = it[MetricsTables.FactSprintScope.sprintId],
            accountId = it[MetricsTables.FactSprintScope.assigneeAtCommitment],
            committed = it[MetricsTables.FactSprintScope.committed],
            inScopeAtClose = it[MetricsTables.FactSprintScope.inScopeAtClose],
            commitMd = it[MetricsTables.FactSprintScope.estimateAtCommitmentMd]?.toDouble(),
            closeMd = it[MetricsTables.FactSprintScope.estimateAtCloseMd]?.toDouble(),
        )
    }
}

/** Σ final MD/items per team, straight off `fact_sprint` (UNIT-level groups). */
private suspend fun teamGroups(sprintRows: List<SprintRow>): List<VelocityGroup> =
    orgGroups(ReportLevel.UNIT, sprintRows, { it.teamId }, { null }).map { (key, rows) ->
        VelocityGroup(
            teamId = key.teamId,
            label = key.label,
            initialMd = sumMd(rows.map { it.live.initialMd }),
            initialItems = rows.sumOf { it.live.initialItems },
            finalMd = sumMd(rows.map { it.live.finalMd }),
            finalItems = rows.sumOf { it.live.finalItems },
        )
    }

/**
 * Σ committed(∧inScopeAtClose)/final MD per `assignee_at_commitment` — the removed-row rule, so Σ
 * users == the team total (TEAM-level groups); the unassigned (null) group last, as sprint consistency.
 */
private suspend fun userGroups(contributions: List<ScopeContribution>): List<VelocityGroup> =
    orgGroups(ReportLevel.TEAM, contributions, { null }, { it.accountId }).map { (key, rows) ->
        val figures = figuresOf(rows)
        VelocityGroup(
            accountId = key.accountId,
            label = key.label,
            initialMd = figures.initialMd,
            initialItems = figures.initialItems,
            finalMd = figures.finalMd,
            finalItems = figures.finalItems,
        )
    }

/**
 * The frozen counterpart of [fetchScopeContributions]: parses each in-scope sprint's `fact_sprint_snapshot.scope`
 * JSONB (the writer's `sprintScopeItemsJson` shape, D13) into the same [ScopeContribution] rows, keeping [accountId]'s
 * own items. A sprint WITH a snapshot is always a key (an account with no items there maps to an empty list, so its
 * frozen figures are zeros, like the live ones); a sprint with none is absent -> `snapshot = null`.
 */
private suspend fun fetchFrozenContributions(
    sprintRows: List<SprintRow>,
    accountId: String,
): Map<Pair<UInt, Long>, List<ScopeContribution>> {
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
            val items = frozenContributionsOf(row[snapshot.scope], connectionId, sprintId)
            (connectionId to sprintId) to items.filter { it.accountId == accountId }
        }
}

/**
 * Parses one snapshot's stored `scope` JSON (written ONLY by `MetricsStore.sprintScopeItemsJson`) into [ScopeContribution]
 * rows. Strict on purpose: a missing key, a non-boolean flag or a non-numeric MD is an invariant violation of the one
 * writer, so it fails loudly with the offending connection/sprint/item rather than being read as "no estimate". A JSON
 * `null` MD stays `null` (= no estimate, exactly as the live `fact_sprint_scope` columns).
 */
internal fun frozenContributionsOf(scopeJson: String, connectionId: UInt, sprintId: Long): List<ScopeContribution> {
    fun malformed(what: String): Nothing =
        error("fact_sprint_snapshot.scope malformed for connection $connectionId sprint $sprintId: $what")
    val array = Json.parseToJsonElement(scopeJson) as? JsonArray ?: malformed("not a JSON array")
    return array.mapIndexed { index, element ->
        val item = element as? JsonObject ?: malformed("item $index is not an object")
        fun scalar(key: String): JsonPrimitive {
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
        val assignee = scalar("assigneeAtCommitment")
        ScopeContribution(
            connectionId = connectionId,
            sprintId = sprintId,
            accountId = when {
                assignee is JsonNull -> null
                assignee.isString -> assignee.content
                else -> malformed("item $index key assigneeAtCommitment is not a string")
            },
            committed = flag("committed"),
            inScopeAtClose = flag("inScopeAtClose"),
            commitMd = md("estimateAtCommitmentMd"),
            closeMd = md("estimateAtCloseMd"),
        )
    }
}

/**
 * The USER level's own `sprints` — one row per sprint, narrowed to [contributions]' one account
 * (the same removed-row rule [userGroups] applies, per sprint instead of summed across them).
 * `snapshot` is that account's FROZEN figures, computed by the same [figuresOf] rule from the sprint's
 * `fact_sprint_snapshot.scope` ([frozen], keyed like [contributions]; `.claude/docs/metrics.md`'s "per-user
 * velocity from the snapshot needs no child table"); `null` for a sprint with no snapshot (D13), and
 * [drift] is the team level's own [velocityDrift] over the two.
 */
private fun buildUserSprints(
    sprintRows: List<SprintRow>,
    contributions: List<ScopeContribution>,
    frozen: Map<Pair<UInt, Long>, List<ScopeContribution>>,
): List<VelocitySprint> {
    val byKey = contributions.groupBy { it.connectionId to it.sprintId }
    return sprintRows.map { row ->
        val live = figuresOf(byKey[row.connectionId to row.sprintId].orEmpty())
        val snapshot = frozen[row.connectionId to row.sprintId]?.let(::figuresOf)
        VelocitySprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = live.initialMd, initialItems = live.initialItems,
            finalMd = live.finalMd, finalItems = live.finalItems,
            snapshot = snapshot, drift = velocityDrift(live, snapshot),
        )
    }
}
