package ch.nokillswit.fetch

import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Semaphore

/**
 * The in-flight-call permits of the worker's outbound transports, shared across connectors. Jira's [SourceHttp] and GitLab's draw on ONE
 * pool, so the worker heap fit (`jira.maxConcurrentRequests x jira.maxResponseBytes` of in-flight bodies, `JiraHeapFit`) stays exact
 * with GitLab enabled: a GitLab body is capped at or below the Jira ceiling (`GitLabSettings`), and a permit is what bounds how many
 * bodies exist at once. Published on `Application.attributes` by `configureJira` ([OutboundPermitsKey]).
 */
class OutboundPermits(val count: Int) {
    val semaphore = Semaphore(count)
}

val OutboundPermitsKey = AttributeKey<OutboundPermits>("OutboundPermits")
