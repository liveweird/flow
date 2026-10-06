package ch.nokillswit.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import io.ktor.util.AttributeKey

/** The `Cache-Control` of a revalidated answer: stored by a private cache at most, revalidated on every use. */
const val REVALIDATED_CACHE_CONTROL = "private, no-cache"

private val ValidatorKey = AttributeKey<String>("ResponseValidator")

/**
 * A route that answers through cache validators (today only the report GETs, `reports/ReportValidators.kt`) OFFERS its ETag here
 * instead of writing headers: [ResponseValidators] turns the offer into `ETag` + `Cache-Control: private, no-cache` +
 * `Vary: Authorization` only when the call really ends as a `200` or `304`. A problem response (a `400` from the report, a `500`
 * after the offer) therefore never carries a validator and stays `no-store`, by construction — response headers cannot be taken back.
 */
fun ApplicationCall.offerValidator(etag: String) {
    attributes.put(ValidatorKey, etag)
}

/** Whether this call's `application/json` answer is a revalidated one, i.e. exempt from the blanket `no-store` (`Http.kt`). */
internal fun ApplicationCall.isRevalidated(): Boolean =
    attributes.contains(ValidatorKey) &&
        (response.status() ?: HttpStatusCode.OK).let { it == HttpStatusCode.OK || it == HttpStatusCode.NotModified }

/** Writes an offered validator (see [offerValidator]) onto a `200`/`304` right before it is sent. */
internal val ResponseValidators = createApplicationPlugin("ResponseValidators") {
    onCallRespond { call, body ->
        val etag = call.attributes.getOrNull(ValidatorKey)
        // The final status is not on the response yet at this point: a bare `respond(HttpStatusCode)` IS its status, a content
        // object (every problem response, `respondProblem`) carries it, and an unset status ends as 200.
        val status = when (body) {
            is HttpStatusCode -> body
            is OutgoingContent -> body.status ?: call.response.status()
            else -> call.response.status()
        } ?: HttpStatusCode.OK
        val problem = body is OutgoingContent && body.contentType?.withoutParameters() == ProblemJson
        if (etag != null && !problem && (status == HttpStatusCode.OK || status == HttpStatusCode.NotModified)) {
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, REVALIDATED_CACHE_CONTROL)
            call.response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
        }
    }
}
