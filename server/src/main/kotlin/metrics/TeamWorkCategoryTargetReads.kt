package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsConfigService.WorkCategoryMap
import ch.nokillswit.metrics.TeamWorkCategoryTargetService.TeamWorkCategoryTargets
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * The reads of the work-category targets that more than the targets' own service needs (Costs part 4 reads them at query time to
 * set a team's target beside its actual time, `reports/CostsCategories.kt`). Both run in the CALLER's transaction. They live here, beside
 * the table and the "which categories can a team choose" rule, so the report and the PUT cannot disagree on either.
 */

/**
 * The work-category labels a team can choose: the distinct `metrics.work_category_map.category` labels of EVERY ACTIVE data source plus
 * [UNCATEGORIZED_CATEGORY] (not only the sources the team owns a board on: Costs attributes a team's worklogs by author team across all
 * active connections). Always contains [UNCATEGORIZED_CATEGORY]. The PUT validates against it; the Costs report flags a row whose key is
 * not in it as unmapped.
 */
internal suspend fun availableWorkCategories(): Set<String> {
    val mapped = WorkCategoryMap.join(
        DataSourceService.Connections, JoinType.INNER, WorkCategoryMap.connectionId, DataSourceService.Connections.id,
    )
        .select(WorkCategoryMap.category).where { DataSourceService.Connections.active() }
        .toList().map { it[WorkCategoryMap.category] }
    return mapped.toSet() + UNCATEGORIZED_CATEGORY
}

/** The team's stored targets, category to whole percent (empty = none configured). */
internal suspend fun storedWorkCategoryTargets(teamId: UInt): Map<String, Int> {
    val t = TeamWorkCategoryTargets
    return t.selectAll().where { t.teamId eq teamId }.toList().associate { it[t.category] to it[t.targetPercent] }
}
