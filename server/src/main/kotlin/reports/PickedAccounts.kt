package ch.nokillswit.reports

/**
 * The picked accounts of a USER-level request: their ids in request order and their Jira display names (the account id when none
 * is known). Both empty below USER level, so a report asking for per-person figures there gets none. Ported from Throughput's
 * private `PickedUsers`; reports that split a figure per picked person use this one.
 */
internal class PickedAccounts(val ids: List<String>, private val labels: Map<String, String>) {
    fun label(id: String): String = labels[id] ?: id
}

/** The [PickedAccounts] of [filter]: its `accountIds` with their display names at USER level, none below it. */
internal suspend fun pickedAccounts(filter: ReportFilter): PickedAccounts {
    if (filter.level != ReportLevel.USER) return PickedAccounts(emptyList(), emptyMap())
    check(filter.accountIds.isNotEmpty()) { "USER level always carries accountIds (ReportFilter's own invariant)" }
    return PickedAccounts(filter.accountIds, accountDisplayNames(filter.accountIds))
}

/**
 * USER-level `groups`: one entry per pick (the TEAM-level rule — [rows] by [accountOf], the assignee at done), an empty row list for
 * a pick with none, ordered like every other drill (label, then account id). The caller maps each `(key, rows)` into its group DTO.
 */
internal fun <T> pickedGroups(
    picked: PickedAccounts,
    rows: List<T>,
    accountOf: (T) -> String?,
): List<Pair<OrgGroupKey, List<T>>> {
    val byAccount = rows.groupBy(accountOf)
    return picked.ids.map { id -> OrgGroupKey(null, id, picked.label(id)) to byAccount[id].orEmpty() }
        .sortedWith(byLabelThenId({ it.first.label }, { null }, { it.first.accountId }))
}
