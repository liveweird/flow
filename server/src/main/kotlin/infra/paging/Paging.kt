package ch.nokillswit.infra.paging

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.r2dbc.Query

const val DEFAULT_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 100

data class SortField(val name: String, val descending: Boolean)

data class PageRequest(
    val page: Int,
    val pageSize: Int,
    val sort: List<SortField>,
)

fun ApplicationCall.parsePaging(
    sortable: Set<String>,
    defaultSort: List<SortField> = listOf(SortField("id", descending = false)),
): PageRequest {
    val params = request.queryParameters

    val page = params.singleValue("page")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("page must be a positive integer")
        if (parsed < 1) throw BadRequestException("page must be >= 1")
        parsed
    } ?: 1

    val pageSize = params.singleValue("pageSize")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("pageSize must be an integer")
        if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
            throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
        }
        parsed
    } ?: DEFAULT_PAGE_SIZE

    val sortParam = params.singleValue("sort")
    val requested = if (sortParam.isNullOrBlank()) {
        defaultSort
    } else {
        sortParam.split(',').map { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw BadRequestException("sort contains an empty field")
            val descending = trimmed.startsWith('-')
            val field = if (descending) trimmed.drop(1) else trimmed
            if (field !in sortable) {
                throw BadRequestException("Unknown sort field: $field (allowed: ${sortable.sorted().joinToString()})")
            }
            SortField(field, descending)
        }
    }
    val withTiebreaker = if (requested.any { it.name == "id" }) requested else requested + SortField("id", false)
    return PageRequest(page = page, pageSize = pageSize, sort = withTiebreaker)
}

fun Query.applyPaging(req: PageRequest, columns: Map<String, Column<*>>): Query {
    val order = req.sort.map { sf ->
        val column = columns[sf.name]
            ?: error("Sort field '${sf.name}' has no mapped column (paging helper invariant violated)")
        column to if (sf.descending) SortOrder.DESC else SortOrder.ASC
    }
    return orderBy(*order.toTypedArray())
        .limit(req.pageSize)
        .offset(((req.page - 1).toLong()) * req.pageSize)
}
