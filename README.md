# Flow

Flow is a developer-intelligence tool (DX/Jellyfish-like) for a ~70-developer SaaS unit: it reads
Jira Cloud (GitLab later) via the official Atlassian REST APIs, stores the raw data incrementally,
and processes it locally into **flow metrics** — cycle/lead time, throughput, WIP, flow efficiency
(active vs. waiting time), work-item age — plus bottleneck diagnosis, trend and team comparison,
outlier detection, and input for continuous improvement. The name refers to the "flow of work"
(Theory of Constraints, Kanban, Reinertsen's cost-of-delay economics): exposing where work waits,
not who is busy.

## What's here today (v0.4.0 — metrics, reports and the Deep dive)

- **Metrics configuration** — ADMIN-managed: global settings (working calendar and time zone,
  hours per day, sample-size and aging-WIP thresholds) and, per Jira connection, the status →
  stage, project → domain and board → team maps and the estimate, epic-date and work-category
  fields, plus effective-dated team memberships (a Jira user belongs to at most one team at a
  time). Every change is one recorded configuration revision.
- **Derivation** — a `DERIVE` job runs on the worker after every sync and every configuration
  change and rebuilds the analytical `metrics` star (facts in man-days, daily aggregates, frozen
  sprint snapshots) from the normalized layer and the configuration — never from a Jira call.
  See `.claude/docs/metrics.md`.
- **Seventeen reports** on sixteen pages (the backlog in sprints sits on the estimated-backlog
  page), open to every signed-in user and drilling unit → team → user; period-based reports take
  any calendar period or the last N sprints: velocity, throughput, sprint consistency and cycle
  time (tasks, and epics by owner team); task and epic estimation accuracy, estimate adjustments
  and reported ÷ cycle time; WIP, the estimated backlog, aging WIP and blocked time; epic progress
  (planned value, earned value and actual cost — PV/EV/AC — in man-days); data quality; and the
  team × domain cost matrix with foreign work. The **Deep dive** lays plan, execution and cost per
  task and day side by side for work selected by sprints of a domain, by epics or by handpicked
  tasks of an epic, drilling months → weeks → days and epics → tasks. The Home page is the unit
  overview. See `.claude/docs/reports.md`.
- **Jira Cloud ingestion** (v0.2.0) — ADMIN-managed connections (a service account's scoped,
  read-only API token, encrypted at rest), scheduled and on-demand syncs into a raw store with
  resumable cursors, a daily reconcile, a neutral normalized layer (status/field intervals,
  worklogs, sprints, boards), a data profile of what the tenant's data actually contains, and a
  raw issue inspector — see "Connecting Jira" below.

Built on the v0.1.0 foundation:

- accounts — JWT sign-in with a sliding refresh pair and a revocation blocklist, opt-in **email
  MFA**, self-service password reset, per-account lockout and per-IP rate limits,
- administration — user management with a one-time generated-password reveal, per-user feature
  flags,
- flat **teams** with rosters, administrator-curated — the ownership unit later domain features
  will point at,
- a bilingual (English/Polish) UI with light/dark themes and a ⌘K / Ctrl K command palette,
- every quality gate wired, locally and in CI.

## Roadmap

- **Next** — the first sync against a real Jira tenant and the adjustments it brings, then the
  follow-ups in `BACKLOG.md` (seeding memberships from the Team
  field, cache validators for the reports); GitLab as a second connector on the same ingestion
  framework.

See `CLAUDE.md`'s "Product" and "Donors" sections for the full roadmap and which sibling project
(Covenant, Lettuce, Toadie) each future capability ports from.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations and
  [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807 problem-detail
  errors, Swagger UI at `/openapi` (development mode). Application-level encryption at rest
  (`infra/crypto/FieldCipher`, AES-256-GCM) protects the stored Jira API token.
- **Frontend**: [Vite](https://vite.dev) + React 19 + TypeScript + [Mantine](https://mantine.dev),
  react-i18next (English + Polish), a typed API client generated from the OpenAPI contract.
- **Quality gates**: detekt (zero findings), Kover coverage floors, dependency-family alignment,
  ESLint + sonarjs, knip, Vitest coverage floors, runtime OpenAPI conformance in the server test
  suite, and Playwright e2e with axe accessibility scans. Pushes to `master` and pull requests run
  the server/web gates and E2E static/setup checks; the full browser suite runs nightly and on
  demand.

## Running the whole stack (one command)

```bash
docker compose up --build
```

Then open <http://localhost:8084> and sign in as `admin@flow.local` / `changeme`. This compose
file runs in development mode (`KTOR_DEVELOPMENT=true`), which tolerates the seed admin's
well-known `changeme` password; any real deployment ships `KTOR_DEVELOPMENT=false` (the Docker
image's default) and **refuses to start** while any active account still carries that password —
set `ADMIN_INITIAL_PASSWORD` to rotate it at first boot instead (see "Configuration" below and
`.claude/docs/security.md`). Swagger UI: <http://localhost:8084/openapi>. Mailpit (reset and MFA
email): <http://localhost:8028>.

Ports are chosen to coexist with [Lettuce](https://github.com/liveweird/lettuce) (8080 / 5432 /
5173 / 8025), [Toadie](https://github.com/liveweird/toadie) (8081 / 5433 / 5174 / 8026) and
[Covenant](https://github.com/liveweird/covenant) (8082 / 5434 / 5175 / 8027) on the same machine:
the app is on **8084**, Postgres is host-mapped to **5435**, the Vite dev server uses **5176**,
Mailpit **8028**. All host ports bind to 127.0.0.1.

On a cold start the app waits for Postgres rather than exiting: Flyway retries its boot connection
(`POSTGRES_CONNECT_RETRIES`, default 10 — about a minute of backing-off attempts; see "Configuration"),
so `docker compose up` and Kubernetes restarts need no manual ordering.

### Scale-20 performance check

`docker-compose.perf.yaml` is the phase-3 performance check: the normal stack with its Jira stub
serving a generated ~24k-issue dataset, run as its own compose project (`flow-perf`, its own
volumes and host ports) so it never touches a dev stack. It verifies that DERIVE stays inside its
budget at that scale; the recipe, the measured timings and where the time goes are in
`.claude/docs/metrics.md` "Performance (scale 20)".

## Running on Kubernetes (local)

Three Deployments, each a single replica with `Recreate` updates (the old pod stops before the
new one starts, so an upgrade briefly interrupts it): `postgres`, `app` (`FLOW_ROLE=web` — the
HTTP API and the SPA) and `worker` (`FLOW_ROLE=worker` — the ingestion/DERIVE worker; it serves
only the health probes and is never selected by the Service). Both application pods run the same
image, migrate on boot and run in production mode (`KTOR_DEVELOPMENT=false`).

With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
FLOW_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"
docker build -t "flow-app:${FLOW_IMAGE_TAG}" .
kubectl create namespace flow
# create the flow-secrets Secret — see the header comment in k8s/secret.yaml
./k8s/apply-local.sh "$FLOW_IMAGE_TAG"
```

The helper renders the deployment image with the selected build tag and excludes the Secret
template. Use a new tag for each rebuild; do not apply `k8s/` directly, since the checked-in image
name is a placeholder. Existing installations already have the namespace and Secret.

The manifests are hardened: no ServiceAccount token in any pod, non-root/seccomp/`drop: [ALL]` on the
app, worker and postgres containers, and `k8s/network-policies.yaml` — default-deny plus only
app/worker → postgres:5432, DNS and public egress on 443 (Jira) / 587, 465, 25 (SMTP), and 8084 into
the app. NetworkPolicy is enforced only by a CNI that implements it (OrbStack's cluster does). Details
in `.claude/docs/security.md` ("Kubernetes pod and network hardening"). CI validates every manifest and
the `apply-local.sh` render with `kubeconform` (`k8s-static` job).

**Known gap: no ingress is shipped.** `app` is exposed through a bare `type: LoadBalancer`
Service, but the `app` Deployment is configured as if a TLS-terminating proxy sat in front
(`HTTP_BEHIND_PROXY=true`, `HTTP_PROXY_HOPS=1`). Today that means:

- Plain HTTP through the LoadBalancer (e.g. `http://localhost:8084`) is answered with a `301` to
  `https://…` — production mode redirects every request that does not arrive as HTTPS. The pods'
  own probes send `X-Forwarded-Proto: https`, which is why they still pass.
- `X-Forwarded-For` and `X-Forwarded-Proto` are client-supplied here: a bare LoadBalancer neither
  terminates TLS nor strips them, so the per-IP rate-limit buckets key on a value the caller
  controls, and a caller can send `X-Forwarded-Proto: https` to skip the redirect (unverified on
  OrbStack: how its LoadBalancer treats these headers has not been checked).
- For throwaway local testing over plain HTTP, edit `k8s/web-deployment.yaml` yourself —
  `apply-local.sh` only substitutes the image tag — setting `KTOR_DEVELOPMENT` to `"true"` (and
  `HTTP_BEHIND_PROXY` to `"false"`), and never commit or apply that in a shared or production
  cluster. Development mode is a different posture, not just "HTTP allowed": it also tolerates
  the burned demo credentials, exposes `/openapi` and the Swagger UI, lifts the login per-IP
  bucket from 10/min to 1000/min when it is left blank, and permits `jira.stubBaseUrl`.

Whether the reference deployment gains a TLS-terminating Ingress (with a `ClusterIP` Service) or a
documented local-only overlay is still to be decided; until then treat this manifest set as a local
reference, not a production recipe.

## Local development

Run each long-lived process in a separate terminal from the repository root:

```bash
docker compose up postgres mailpit   # database on :5435 and local mail on :8028
./gradlew :server:run                # API on :8084
(cd web && npm install --legacy-peer-deps && npm run dev)    # SPA on :5176
```

The local JDK and Node runtime are pinned in `mise.toml` (Temurin 21 and Node 24 LTS). Run
`mise install` and use `mise exec -- <command>` or activate mise in your shell. If `./gradlew`
resolves the wrong JDK (a system JRE ahead of the mise shim on `PATH`), point it explicitly:
`JAVA_HOME=$(mise where java) ./gradlew build`. If server tests need a Docker daemon and you use
OrbStack without `/var/run/docker.sock`, export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
first. See [dependency maintenance](.claude/docs/dependencies.md) for compatibility pins,
automated updates, and runtime verification.

## Connecting Jira

Once the stack is running, an ADMIN can point Flow at a real Jira Cloud site from the **Data
sources** page (Administration nav). Before clicking New, have ready:

- An **Atlassian service account** — a dedicated account for Flow's own reads, not a real person's.
- A **scoped, read-only API token** for that account. Create the token with these scopes (a SYNC needs
  all of them but the optional one below, not only the Test connection probes):
  - `read:jira-user`, `read:jql:jira`, `read:field:jira`, `read:status:jira`, `read:workflow:jira`,
    `read:project:jira`;
  - `read:issue-details:jira`, `read:issue:jira`, `read:issue-status:jira`, `read:issue-type:jira`;
  - `read:issue.changelog:jira`, `read:issue-worklog:jira`, `read:resolution:jira`,
    `read:issue-link-type:jira`;
  - `read:board-scope:jira-software`, `read:board-scope.admin:jira-software`,
    `read:sprint:jira-software`;
  - **recommended, optional:** `read:field-configuration:jira` — lets the REFERENCE stream read each project's field
    scheme (Jira's experimental `GET /rest/api/3/projects/fields`), so the metrics-config **Fields** tab lists only the
    fields your projects use instead of every custom field on the site (1,300+ on a large tenant). Without it (or if
    Atlassian withdraws the endpoint, or the call fails for any Jira-side reason) the sync is unaffected and the tab falls back to fields that have data in the
    synced issues; Test connection shows the optional `project_fields` row.

  The board and sprint scopes are not optional: a SYNC reads boards, board configurations and
  sprints for every connection, and a 401/403 there fails the job. On a scoped token a missing scope
  comes back as **401**, not 403, so one failing Test connection probe while `myself` passes means a
  missing scope, not bad credentials. `.claude/docs/jira-integration.md` maps every endpoint to its
  scopes ("Test connection" and "Scopes per endpoint").
- The Jira **site URL**, exactly `https://<site>.atlassian.net`.
- The **project keys** to bring into scope (worklogs and issue data are stored for these projects
  only, never the whole tenant — see `.claude/docs/ingestion.md`'s WORKLOGS "A1" note).

In the editor modal, fill in the site URL, email and API token, pick the auth scheme (Basic is the
usual choice), list the project keys, then run **Test connection** — every probe's result (ok/scope
hint) shows before you save, so a scope problem surfaces immediately rather than after the first
sync fails partway through. Save, then open the connection (its name in the list) and click **Sync
now** to enqueue the first SYNC job. The details page follows it live — the current job's stream and
progress counters, the per-stream cursors and the raw-store counts refresh every 5 seconds while a
job is open — and keeps the paged job history, with **Reconcile now**, **Reprocess** and **Cancel job**
beside it. Once that job reaches **Succeeded**, the **Data profile** page shows the connection's data
profile: what workflows, boards, custom fields, estimate/worklog coverage and reopen rate this
tenant's own data actually has. The **Raw issue inspector** looks one issue up by key or id and shows its
raw payload, changelog, worklogs and normalized status intervals — the place to start when a
profile number looks wrong. See `.claude/docs/ingestion.md`'s "Reading the data profile after the
first real sync" for the full walkthrough of what to look at first.

To try all of this without a real tenant, the compose stack's `jira-stub` service serves a
deterministic sample dataset: create a data source with any `https://<name>.atlassian.net` site
URL, any email and token, and the project keys `FLO`, `PLT`, `GTM`, `OPS` — the app reroutes every
Jira call to the stub in development mode (`JIRA_STUB_BASE_URL`; see `sample-data/README.md`).

The compose stack routes **every** Jira call to that bundled stub, so a real `*.atlassian.net`
connection would silently talk to the stub. To connect a real tenant, start the stack with the
reroute turned off: `JIRA_STUB_BASE_URL= docker compose up` (an explicitly empty value, not unset).

## Configuration (environment variables)

`server/src/main/resources/application.yaml` is the authoritative reference — every setting there
is a `$VAR:default` pair, and the table below is generated from it (defaults as shipped; the
Docker image additionally sets `KTOR_DEVELOPMENT=false`, `MAIL_TRANSPORT=disabled` and
`WEB_STATIC_DIR=/app/web`). Production mode refuses the burned demo keys and the `log` mail
transport at startup (`.claude/docs/security.md`).

| Variable | Default | Purpose |
|---|---|---|
| `KTOR_DEVELOPMENT` | `true` | Development mode (`true`) vs production mode — HSTS/HTTPS redirect and the fail-closed startup checks. The image ships `false`. |
| `FLOW_ROLE` | `all` | `web` (HTTP API + SPA, no ingestion worker), `worker` (health/ready probes only, runs the ingestion worker) or `all` (both, one process). An unrecognized value refuses to start. See `.claude/docs/ingestion.md` "Roles". |
| `LOGIN_LOCKOUT_THRESHOLD` | `5` | Consecutive failures per account before `/login` answers 429. |
| `LOGIN_LOCKOUT_DURATION_SECONDS` | `900` | How long a locked account stays locked. |
| `LOGIN_LOCKOUT_MAX_TRACKED` | `10000` | Maximum in-memory login identities; new identities receive 429 at capacity while active counters and locks remain. |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP login bucket; blank follows the mode (10 prod / 1000 dev). |
| `REFRESH_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP refresh bucket (blank = 30). |
| `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP reset bucket; blank follows the mode (5 prod / 100 dev). |
| `PASSWORD_RESET_MIN_INTERVAL_SECONDS` | `60` | One reset request per submitted email per interval. |
| `PASSWORD_RESET_MAX_TRACKED` | `10000` | Maximum in-memory reset cooldowns; new identities receive 429 at capacity. |
| `MFA_CODE_TTL_SECONDS` | `300` | Lifetime of an emailed MFA code. |
| `MFA_MAX_ATTEMPTS` | `5` | Wrong-code attempts before a challenge dies. |
| `MFA_MAX_TRACKED` | `10000` | Maximum pending email-MFA challenges; new issuance receives 429 at capacity. |
| `DATA_ENCRYPTION_KEY` | *(dev key, burned)* | AES-256-GCM key (64 hex) for stored credentials (today: the Jira API token) — the dev default is burned, production refuses it. Back it up apart from the database. |
| `DATA_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | Decrypt-only fallback during a key rotation (boot once, then remove). |
| `SECURITY_CSRF_ENABLED` | `false` | CSRF plugin gate — off (bearer JWT, no cookies). |
| `JWT_SECRET` | `secret` | HMAC key for the access/refresh pair — production requires a private 64-hex key (`openssl rand -hex 32`); the placeholders and compose demo key are burned. |
| `JWT_ISSUER` | `http://0.0.0.0:8084/` | The `iss` claim the verifier requires. |
| `JWT_AUDIENCE` | `flow-api` | The `aud` claim the verifier requires. |
| `JWT_REALM` | `flow-api` | The `WWW-Authenticate` realm. |
| `JWT_ACCESS_EXPIRES_IN_SECONDS` | `900` | Access-token lifetime (the API bearer). |
| `JWT_REFRESH_EXPIRES_IN_SECONDS` | `3600` | Refresh-token lifetime — the idle-session window. |
| `ADMIN_INITIAL_PASSWORD` | *(blank)* | Rotates the V3 seed admin's `changeme` at startup while it still carries the seed hash; production rejects known placeholders and passwords outside the ordinary account limits. |
| `HTTP_BEHIND_PROXY` | `false` | Honour `X-Forwarded-*` from a TLS-terminating proxy (rate-limit keys, HTTPS redirect). |
| `HTTP_PROXY_HOPS` | `1` | Trusted proxies in front of the app when `HTTP_BEHIND_PROXY=true`; `X-Forwarded-For` is trusted from the END of the list (1 = the last proxy's value). |
| `CORS_ALLOWED_HOSTS` | *(blank)* | Comma-separated cross-origin hosts; blank = CORS not installed. |
| `HTTP_EXPOSE_OPENAPI` | *(blank)* | Serve Swagger UI + the spec at `/openapi`; blank follows the mode. |
| `WEB_STATIC_DIR` | *(blank)* | Directory of the built SPA to serve; blank in local dev (Vite serves it). The image sets `/app/web`. |
| `MAIL_TRANSPORT` | `log` | `log` (dev only — production refuses it), `smtp`, or `disabled` (email features answer 503; the image default). |
| `SMTP_HOST` | *(blank)* | SMTP server (required for `smtp`). |
| `SMTP_PORT` | `587` | SMTP port. |
| `SMTP_USER` | *(blank)* | SMTP user (optional). |
| `SMTP_PASSWORD` | *(blank)* | SMTP password (optional). |
| `SMTP_STARTTLS` | `true` | STARTTLS on the SMTP connection. |
| `MAIL_FROM` | `flow@localhost` | Sender address of every outbound email. |
| `MAIL_APP_URL` | *(blank)* | Absolute URL of this deployment — emails carry a sign-in link when set. |
| `JIRA_REQUEST_TIMEOUT_SECONDS` | `30` | Per-attempt HTTP timeout (request + connect) for the Jira client. |
| `JIRA_REQUEST_DEADLINE_SECONDS` | `180` | Total budget for one logical Jira call across every retry/backoff attempt. |
| `JIRA_MAX_RESPONSE_BYTES` | `33554432` | Bounded-read ceiling on a single Jira response body (32 MiB). |
| `JIRA_MAX_CONCURRENT_REQUESTS` | `4` | Semaphore cap on concurrent in-flight Jira calls per process. |
| `JIRA_MAX_RETRIES` | `4` | Retries on 429/5xx/IOException before the Jira client fails terminally. |
| `JIRA_PAGE_SIZE` | `100` | The ISSUES stream's `search/jql` page size (`maxResults`, 1..500); the stub ignores it and returns its own fixed-size pages. |
| `JIRA_INCREMENTAL_OVERLAP_MINUTES` | `10` | How far the ISSUES stream re-widens its relative `updated` window past the last completed run's watermark (0..1440). |
| `JIRA_CHANGELOG_BULK_SIZE` | `50` | The CHANGELOGS stream's `changelog/bulkfetch` chunk size (1..1000) — MUST match the stub's own fixed 50-id chunking. |
| `JIRA_STUB_BASE_URL` | *(blank)* | Reroutes the Jira tenant_info + gateway hosts to the in-JVM/compose stub — development only; production refuses a non-blank value. |
| `INGEST_SCHEDULER_TICK_SECONDS` | `15` | How often the `worker`-role scan loop runs: enqueue due jobs, prune old rows, claim+run. |
| `INGEST_WORKER_SLOTS` | `2` | Cap on concurrently RUNNING sync jobs for this worker instance. |
| `INGEST_LEASE_SECONDS` | `300` | A claimed job's lease lifetime; heartbeated at `leaseSeconds/3`. An expired lease is reclaimable by any worker. |
| `INGEST_MAX_ATTEMPTS` | `3` | How many times a job may be (re)claimed before it fails `RETRIES_EXHAUSTED`. |
| `INGEST_JOB_RETENTION_DAYS` | `90` | Finished (`SUCCEEDED`/`FAILED`/`CANCELLED`) `sync_jobs` rows older than this are hard-deleted (the documented `sync_jobs` history-pruning exception, `.claude/docs/persistence.md`). |
| `INGEST_PURGE_GRACE_DAYS` | `7` | Days a soft-deleted data source's connector rows survive before the internal `PURGE` job removes them. |
| `INGEST_WORKER_ID` | *(blank)* | This worker instance's lease-owner identity; blank derives hostname + a random suffix. |
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5435/flow` | Flyway's JDBC URL. |
| `POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5435/flow` | The runtime R2DBC URL. |
| `POSTGRES_USER` | `flow` | Database user. |
| `POSTGRES_PASSWORD` | `flow` | Database password. |
| `POSTGRES_CONNECT_RETRIES` | `10` | How many times Flyway retries its boot connection (doubling waits capped at 8 s, ~63 s at 10) so a cold start waits for Postgres instead of crash-looping; `0` fails fast (0..15 — 15 is ~2 min, inside the k8s startup-probe budget). |
| `POSTGRES_POOL_MAX_SIZE` | `20` | Ceiling on concurrent pooled R2DBC connections for this instance (1..1000). |
| `POSTGRES_POOL_INITIAL_SIZE` | `2` | Connections the pool fills up to on its first acquire (0..maxSize). |
| `POSTGRES_POOL_MAX_ACQUIRE_SECONDS` | `10` | How long a caller waits for a free pooled connection before failing (1..600). |
| `POSTGRES_POOL_MAX_IDLE_SECONDS` | `600` | How long an idle pooled connection may sit before recycling (1..86400). |
| `REPORTS_STATEMENT_TIMEOUT_SECONDS` | `30` | Per-statement time budget of every report read (1..3600, whole seconds); a statement over it is cancelled and answers a 500 problem (`.claude/docs/reports.md` "Query budget"). |

## Useful Gradle tasks

| Task | What it does |
| ---- | ------------ |
| `./gradlew build` | Compiles everything and runs every gate: detekt, tests (Testcontainers), Kover verify, dependency alignment |
| `./gradlew :server:run` | Runs the API against the compose Postgres |
| `./gradlew :server:test` | Server test suite (needs a Docker daemon for Testcontainers) |
| `./gradlew detekt` | Static analysis only |
| `./gradlew :server:checkDependencyAlignment` | One version per aligned dependency family on the runtime classpath |
| `./gradlew :server:installDist` | Builds the runnable distribution (used by the Docker image; never `buildFatJar`) |

Frontend: `cd web && npm run build | lint | test | test:coverage | knip | gen:api`.
E2E: `cd e2e && npm ci && npx playwright install chromium && npm test`.

## License

MIT — see [LICENSE](LICENSE).
