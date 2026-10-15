package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.WorkItemStore
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The calendar service's `norm.people` reads (domain-model A32), apart from `SprintCalendarService` for its file-size ceiling. Both run
 * inside the caller's transaction and only ever see people of ACTIVE connections: a soft-deleted connection's people are neither named
 * nor accepted as account ids.
 */

private fun activePeople(columns: List<Expression<*>>, extra: Op<Boolean>): Query =
    WorkItemStore.People
        .join(DataSourceService.Connections, JoinType.INNER, WorkItemStore.People.connectionId, DataSourceService.Connections.id)
        .select(columns).where { DataSourceService.Connections.active() and extra }

/** The grid's rows: the stored people with their Jira display name (the account id when no active connection knows it) and membership. */
internal suspend fun personViews(document: SprintCalendarDocument, members: Set<String>, canEdit: Boolean): List<SprintCalendarPersonView> {
    val ids = document.people.map { it.accountId }
    val names = if (ids.isEmpty()) emptyMap() else {
        activePeople(
            listOf(WorkItemStore.People.accountId, WorkItemStore.People.displayName, WorkItemStore.People.connectionId),
            WorkItemStore.People.accountId inList ids,
        )
            .orderBy(WorkItemStore.People.connectionId to SortOrder.DESC) // last wins = the lowest connection id
            .toList().associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
    }
    return document.people.map {
        val displayName = names[it.accountId] ?: it.accountId
        val coefficient = it.coefficient.takeIf { canEdit }
        SprintCalendarPersonView(it.accountId, displayName, it.accountId in members, it.enabled, coefficient, it.cells)
    }
}

/**
 * An account id no active connection's `norm.people` knows is a 400 (the client-supplied-FK idiom); a non-member is fine (historical).
 * Accounts in [except] (the stored document's and the current roster's) are not checked: an unchanged GET document is always saveable.
 */
internal suspend fun requireKnownAccounts(accountIds: List<String>, except: Collection<String>) {
    val unchecked = accountIds.filter { it !in except }
    if (unchecked.isEmpty()) return
    val known = activePeople(listOf(WorkItemStore.People.accountId), WorkItemStore.People.accountId inList unchecked).withDistinct()
        .toList().map { it[WorkItemStore.People.accountId] }.toSet()
    unchecked.firstOrNull { it !in known }?.let { throw BadRequestException("Unknown Jira account id: $it") }
}
