package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException

/** The scope a request selects — at most one of `epicId` (an issue key), `domain`, `teamId`; none is the unit. */
internal sealed interface ProgressTarget {
    val level: EpicProgressLevel

    data object WholeUnit : ProgressTarget {
        override val level = EpicProgressLevel.UNIT
    }

    data class Domain(val key: String) : ProgressTarget {
        override val level = EpicProgressLevel.DOMAIN
    }

    data class Epic(val key: String) : ProgressTarget {
        override val level = EpicProgressLevel.EPIC
    }

    data class Team(val teamId: UInt) : ProgressTarget {
        override val level = EpicProgressLevel.TEAM
    }
}

/** A `dim_epic` row an `epicId` resolved to. */
internal data class ResolvedEpic(
    val connectionId: UInt,
    val issueId: Long,
    val issueKey: String,
    val summary: String?,
    val startAt: Long?,
    val dueAt: Long?,
)

/** A [ProgressTarget] checked against the data: its response [scope] and, for an epic, its [epic] row. */
internal class ResolvedTarget(val target: ProgressTarget, val scope: EpicProgressScope?, val epic: ResolvedEpic?)

/** The display name of the `teamId=0` bucket. */
internal const val TEAM_UNASSIGNED_NAME = "Unassigned"

/** The scope selected, or `400` for a combination EVM cannot answer (see `.claude/docs/reports.md` "Report 15"). */
internal fun progressTargetOf(filter: ReportFilter, epicKey: String?): ProgressTarget {
    if (filter.accountId != null) throw BadRequestException("accountId is not available: EVM has no user level")
    if (filter.domainView == DomainView.TASK) {
        throw BadRequestException("domainView=TASK is not available: EVM is always the EPIC view (D3)")
    }
    if (filter.activityType != null || filter.workCategory != null) {
        throw BadRequestException("activityType and workCategory are not available for the daily EVM aggregates")
    }
    val chosen = listOfNotNull(
        "epicId".takeIf { epicKey != null },
        "domain".takeIf { filter.domain != null },
        "teamId".takeIf { filter.teamId != null },
    )
    if (chosen.size > 1) throw BadRequestException("epicId, domain and teamId are mutually exclusive, got ${chosen.joinToString()}")
    val teamId = filter.teamId
    return when {
        epicKey != null -> ProgressTarget.Epic(epicKey)
        filter.domain != null -> ProgressTarget.Domain(filter.domain)
        teamId != null -> ProgressTarget.Team(teamId)
        else -> ProgressTarget.WholeUnit
    }
}

/**
 * Names the scope and (with [validate]) checks it exists in the connections in scope — `400` for an unknown domain, an unknown
 * epic key or one shared by several connections (narrow with `connectionId`); the team was already checked by
 * [resolveReportScope]. Without [validate] an epic/domain is named by its key alone.
 */
internal suspend fun resolveTarget(target: ProgressTarget, connectionIds: List<UInt>, validate: Boolean): ResolvedTarget = when (target) {
    ProgressTarget.WholeUnit -> ResolvedTarget(target, null, null)
    is ProgressTarget.Team -> {
        val name = if (target.teamId == UNASSIGNED_TEAM_ID) TEAM_UNASSIGNED_NAME else teamNames(listOf(target.teamId))[target.teamId]
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.TEAM, target.teamId, null, name ?: target.teamId.toString()), null)
    }
    is ProgressTarget.Domain -> {
        val name = if (validate) requireDomainName(target.key, connectionIds) else target.key
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.DOMAIN, null, target.key, name), null)
    }
    is ProgressTarget.Epic -> {
        val epic = if (validate) requireEpicsByKey(listOf(target.key), connectionIds).single() else null
        val name = epic?.let { it.summary ?: it.issueKey } ?: target.key
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.EPIC, null, target.key, name), epic)
    }
}
