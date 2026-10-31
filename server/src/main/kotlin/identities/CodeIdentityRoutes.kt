package ch.nokillswit.identities

import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.norm.CodeActivityStoreKey
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.server.application.Application

/**
 * Wires the identity mapping (`.claude/docs/gitlab-integration.md` "Identity"). The resolver and the service are published in EVERY role
 * (DERIVE and the reports read links without serving the API); only a role that serves the API goes on to register the routes.
 * Registered right after the data-profile routes in `application.yaml`: after `configureDatabase` (the database handle), `configureJira`
 * (`WorkItemStoreKey`) and `configureGitLab` (`CodeActivityStoreKey`, published whether or not GitLab is on), before the catch-all.
 */
fun Application.configureCodeIdentities() {
    val database = attributes[R2dbcDatabaseKey]
    val links = CodeIdentityLinkStore(database)
    attributes.put(IdentityResolverKey, IdentityResolver(links))
    attributes.put(
        CodeIdentityServiceKey,
        CodeIdentityService(database, links, attributes[CodeActivityStoreKey], attributes[WorkItemStoreKey]),
    )
    if (!servesApi()) return
}
