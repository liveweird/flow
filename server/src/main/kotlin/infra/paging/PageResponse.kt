package ch.nokillswit.infra.paging

import kotlinx.serialization.Serializable

/**
 * The shared list-endpoint envelope: `{ items, page, pageSize, total }`. Every resource's
 * `XPageResponse` is a typealias over this (see e.g. `catalog/CatalogFile.kt`), so the JSON wire
 * shape and the OpenAPI `*Page` schemas stay identical while the Kotlin type is defined once.
 */
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
)

/** Assembles a [PageResponse] from this request's page/pageSize plus the fetched rows and total. */
fun <T> PageRequest.toPage(items: List<T>, total: Long): PageResponse<T> =
    PageResponse(items, page, pageSize, total)
