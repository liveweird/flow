package ch.nokillswit.infra.outbound

// The Jira Cloud site shape, owned by `infra/outbound` (the allow-list boundary) so the guard needs nothing from a feature
// package: `ingest/DataSource.kt` (create/update validation) and `OutboundGuard.kt` (the host allow-list) both read it, ONE
// source of truth. The web mirrors it in `web/src/utils/dataSourceForm.ts`.

/**
 * The allow-list boundary (`.claude/docs/security.md` "Outbound HTTP calls"): a Jira Cloud site's
 * ORIGIN only — no path, no query, no port, no trailing slash — so this value can never carry
 * more than a host the outbound guard will pin. The single capturing
 * group is the tenant label, checked against [ATLASSIAN_RESERVED_SITE_LABELS] in this file.
 */
val JIRA_SITE_URL_PATTERN: Regex = Regex("^https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.atlassian\\.net$")

/**
 * Labels under `*.atlassian.net` that are Atlassian's OWN product/marketing/support surfaces, not
 * a customer tenant — they match [JIRA_SITE_URL_PATTERN]'s shape but must never be accepted as a
 * connection target (an admin fat-fingering `https://api.atlassian.net` would otherwise point the
 * outbound guard at Atlassian's own infrastructure instead of a tenant site).
 */
val ATLASSIAN_RESERVED_SITE_LABELS: Set<String> = setOf(
    "api", "id", "admin", "www", "auth", "start", "home", "status", "developer",
    "support", "community", "marketplace", "my", "team",
)
