package ch.nokillswit.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.response.header
import io.ktor.util.AttributeKey

private val ValidatorKey = AttributeKey<String>("ResponseValidator")

/**
 * A route that answers through cache validators (today only the report GETs, `reports/ReportValidators.kt`) OFFERS its ETag here
 * instead of writing headers: [ResponseValidators] turns the offer into `ETag` + `Cache-Control: no-store` (the owner's decision,
 * `.claude/docs/security.md` "Caching": nothing lands in a browser or proxy cache) only when the call really ends as a `200` or
 * `304`, and only once the body is rendered. A problem response (a `400` from the report, a `500` after the offer, a body that fails
 * to serialize) therefore never carries a validator and stays `no-store`, by construction - response headers cannot be taken back.
 */
fun ApplicationCall.offerValidator(etag: String) {
    attributes.put(ValidatorKey, etag)
}

/**
 * Whether this call's `application/json` answer is a revalidated one: [ResponseValidators] writes its `no-store`, so it is exempt from
 * the blanket one (`Http.kt`) - otherwise the `200` would carry two `Cache-Control` headers (the bodiless `304` never gets the blanket).
 */
internal fun ApplicationCall.isRevalidated(): Boolean =
    attributes.contains(ValidatorKey) &&
        (response.status() ?: HttpStatusCode.OK).let { it == HttpStatusCode.OK || it == HttpStatusCode.NotModified }

/** Writes an offered validator (see [offerValidator]) onto a `200`/`304` after the body is rendered, right before it is sent. */
internal val ResponseValidators = createApplicationPlugin("ResponseValidators") {
    // ResponseBodyReadyForSend: the content is final (a serialization failure has already become the problem response), so the
    // status and the content type seen here are what goes on the wire. `Http.kt` installs this BEFORE ContentNegotiation, which is why
    // the earlier `onCallRespond` hook (it ran before the body was rendered) leaked a tag onto a body that then failed to serialize.
    on(ResponseBodyReadyForSend) { call, content ->
        val etag = call.attributes.getOrNull(ValidatorKey)
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        val problem = content.contentType?.withoutParameters() == ProblemJson
        if (etag != null && !problem && (status == HttpStatusCode.OK || status == HttpStatusCode.NotModified)) {
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }
}
