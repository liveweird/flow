# Jira ingestion (v0.2.0)

This doc grows commit by commit alongside the v0.2.0 Jira Cloud ingestion work (see the Product
section of `CLAUDE.md` for the roadmap). It starts with the role split that every later ingestion
commit builds on; the sync-job queue, the Jira client, the raw/normalized data model and the data
profile each add their own section as they land.

## Roles

One codebase and one image serve three process shapes, switched by `FLOW_ROLE` (`app.role` in
`server/src/main/resources/application.yaml`, read once at boot by `plugins/Role.kt` into
`AppRole { WEB, WORKER, ALL }`):

- **`web`** serves the HTTP API — every feature route module (`configureUserRoutes`,
  `configureTeamRoutes`, `configureAuthRoutes`, …) plus the SPA/static catch-all
  (`plugins/Routing.kt`) — but runs no ingestion worker.
- **`worker`** serves only the health/ready probes (`plugins/Health.kt` — `/api/v1/health` and
  `/api/v1/ready`, its one HTTP surface) and runs the ingestion worker
  (`ingest/IngestWorker.kt`, arriving in commit 5 of the v0.2.0 plan: the sync-job scheduler,
  lease/heartbeat claim loop and the Jira sync/reconcile/reprocess/purge jobs).
- **`all`** (the default — unset `FLOW_ROLE`) does both in one process.

`Flyway` and `Bootstrap` always run, in every role: schema and seed state must be current
regardless of which surface a given instance serves. An unrecognized `FLOW_ROLE` value fails
startup in every mode — a bad role is a deploy-time config error, not something to limp along
with.

`Application.servesApi()` (true for `WEB`/`ALL`) is the guard every feature route module and the
SPA catch-all check first; `Application.runsWorker()` (true for `WORKER`/`ALL`) is its counterpart
for the ingestion worker.

**Dev, `docker compose` and the test suite run `all`** — a single process, the simplest shape for
local iteration and CI. **Production runs a `web` Deployment and a separate `worker` Deployment**
(`k8s/web-deployment.yaml` renamed from `app-deployment.yaml`, `k8s/worker-deployment.yaml` — both
arriving in v0.2.0 commit 5), so the worker's long-running Jira syncs scale and restart
independently of the request-serving replicas, and only the `web` Deployment sits behind the
Service. Set the environment variable:

```
FLOW_ROLE=web     # or worker, or all (default)
```

See the [run-stack skill](../skills/run-stack/SKILL.md) for how this fits into local dev, compose
and Kubernetes, and the README's environment-variable table for `FLOW_ROLE`'s default and purpose.

## Data sources

`source_connections` (V8, `ingest/DataSourceService.kt`) is the generic connector registry: `kind`
(`JIRA_CLOUD` today), typed common columns (schedule, sync status, `config_revision`) and a
`settings` jsonb holding the Jira-specific shape (`siteUrl`, `email`, `projectKeys`, `authScheme`,
`cloudId`) — a future GitLab connector reuses this table, its queue and its worker outright. It
stays in `public` (the main-session PG-schemas amendment keeps operational tables there;
connector-raw and normalized data get their own `raw`/`norm` schemas starting at V10). The scoped
read-only Jira API token is the first `EncryptedAtRest` consumer
(`.claude/docs/security.md` "Encryption at rest").

`ingest/DataSourceRoutes.kt` exposes ADMIN-only CRUD at `/api/v1/data-sources` (list/create/get/
update/delete — see `.claude/docs/authorization.md`): `jira.siteUrl` must be exactly
`https://<site>.atlassian.net` (the outbound allow-list boundary, `.claude/docs/security.md`) and
is fixed at create — a later change is `409`. `jira.apiToken` is write-only: every response
carries `jira.hasApiToken` instead, and it is required (non-blank) on create; omitted OR blank on
update keeps the current token (the SPA's masked-password field sends `""` for "unchanged"), any
other present value rotates it and audits `data_source.token_rotated` separately from the ordinary
`.updated` event. Delete is soft — it disables the connection; the raw/normalized rows purge
later, once the sync-job queue and its `PURGE` job land (plan §0 A2). The uniqueness constraint is
on `name`, not `jira.siteUrl` — **two data sources may legitimately point at the same Jira site**
(e.g. different `projectKeys` scopes owned by different teams), so create/update never reject on a
repeated `siteUrl` alone.
The response's `status.state` (`NEVER_SYNCED`/`CURRENT`/`STALE`/`FAILED`/`DISABLED`) is derived
from the sync-status columns alone in this commit — `runningJobId` stays `null` until the sync-job
queue (plan commit 5) gives it something to report. Test-connection, sync-jobs, status and profile
endpoints are NOT part of this surface yet; they arrive with the Jira client and the sync-job
queue (plan commits 4-9).

**`infra/db/Jsonb.kt` + `infra/json/CanonicalJson.kt`** (this commit's supporting infra, detailed
in `.claude/docs/persistence.md` "Data sources (V8)"): the repo-local `jsonb` column binding
`source_connections.settings` uses, and the canonical-JSON/sha256 helper the Jira raw store (V10)
will hash payloads with.
