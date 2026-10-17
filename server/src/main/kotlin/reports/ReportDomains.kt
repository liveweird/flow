package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

// The ONE "domain exists" check the reports share, over ONE private query (split out of `ReportSupport.kt`, whose size ceiling is a
// ratchet). Both functions run inside the CALLER's `suspendTransaction`.

/** The `dim_domain` rows of [domainKey] among [connectionIds], lowest connection id first: the ONE "domain exists" check's query. */
private suspend fun domainRows(domainKey: String, connectionIds: Collection<UInt>): List<Pair<UInt, String>> {
    val d = MetricsTables.DimDomain
    return d.select(d.connectionId, d.name)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domainKey) }
        .orderBy(d.connectionId to SortOrder.ASC)
        .toList().map { it[d.connectionId].value to it[d.name] }
}

/**
 * The name of the `dim_domain` row [domainKey] resolves to among [connectionIds] (the lowest connection id's when several carry it), or a
 * `400` — an unknown domain is never an empty page. The ONE "domain exists" check (epic progress, the deep-dive options and selection).
 */
internal suspend fun requireDomainName(domainKey: String, connectionIds: Collection<UInt>): String =
    domainRows(domainKey, connectionIds).firstOrNull()?.second ?: throw BadRequestException("Unknown domain: $domainKey")

/**
 * The connections among [connectionIds] that hold the `dim_domain` row [domainKey] (ascending), or the same `400` as [requireDomainName]
 * when none does. The deep dive's sprint selection narrows to these BEFORE it resolves the sprint ids.
 */
internal suspend fun connectionsWithDomain(domainKey: String, connectionIds: Collection<UInt>): List<UInt> =
    domainRows(domainKey, connectionIds).map { it.first }.ifEmpty { throw BadRequestException("Unknown domain: $domainKey") }
