# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product

Flow is a developer-intelligence tool (DX/Jellyfish-like) for a ~70-developer SaaS unit: it reads
Jira Cloud (GitLab later) via the official Atlassian REST APIs, stores the raw data incrementally,
and processes it locally into flow metrics — cycle/lead time, throughput, WIP, flow efficiency
(active vs. waiting time), work-item age — plus bottleneck diagnosis, trend and team comparison,
outlier detection, and input for continuous improvement. The name refers to the "flow of work"
(Theory of Constraints, Kanban, Reinertsen's cost-of-delay economics): the discipline of exposing
where work waits, not who is busy.

**Roadmap:**

- **v0.1.0 — foundation.** Sign-in with email MFA, users, teams, feature flags, EN/PL,
  light/dark theme.
- **v0.2.0 — Jira ingestion.** An ADMIN-managed Jira Cloud connection (an Atlassian service
  account + a scoped read-only API token, encrypted at rest with `infra/crypto/FieldCipher`), a raw
  store with incremental cursors (`raw.*`), a neutral normalized layer above it (`norm.*` — facts
  only, no interpretation), the data profile and the admin pages over all of it
  (`.claude/docs/ingestion.md`).
- **v0.3.0 (this codebase) — the domain model, metrics, reports.** The model in
  `.claude/docs/domain-model.md`, implemented: metrics configuration, a `DERIVE` job building the
  `metrics` star from `norm` (`metrics/`), and sixteen reports (`reports/`; fifteen pages); Home is
  the unit overview. Next: the real-Jira first sync, then `BACKLOG.md`.

Brand: blue (the `flow` colour tuple in `web/src/theme.ts`, `primaryShade: { light: 8, dark: 9 }`);
the logo is "Rolling" — a stream running round a blue disc and rolling inward into a curl
(`web/public/logo-{light,dark}.svg` + `favicon.svg`, rendered by `web/src/components/BrandLogo.tsx`).

## Donors

Flow's repository was copied from Covenant and trimmed to its generic foundation — auth, users,
teams, feature flags, i18n, theming, quality gates — with the contract-catalog domain removed.
**Port, don't reinvent**: when Flow needs a capability one of these siblings already has, port its
implementation rather than designing a new one.

- **Covenant** (`~/Sources/covenant`, the primary donor) — this repo's entire foundation IS
  Covenant's scaffold. Its `toadie/` server package (an ADMIN-curated external-API connector:
  encrypted connection config, scoped read-only credentials, bounded paginated reads, a
  raw/derived cache split) is the template for Flow's own Jira connector in v0.2.0 — port its
  *shape*, not its GraphQL specifics.
- **Lettuce** (`~/Sources/lettuce`) — `@mantine/charts` + `recharts` for the flow-metrics
  dashboards; its WireMock teams-stub pattern for integration-testing an external API
  client without hitting the real service.
- **Toadie** (`~/Sources/toadie`) — the `UrlFetch` SSRF guard (public-host validation before any
  server-initiated outbound call) — forward guidance for the Jira client from v0.2.0; see
  `.claude/docs/security.md`.

## Commands

Gradle wrapper is at `./gradlew` (use `gradlew.bat` on Windows). JDK 21 toolchain is required
(auto-provisioned via foojay-resolver; the local dev JDK is pinned in `mise.toml` —
`temurin-21.0.12+101.0.LTS`, Node 24.21.0). Run `mise install` once, then `mise exec -- <command>`
or activate mise in your shell. If `./gradlew` still resolves the wrong JDK (a system JRE ahead of
the mise shim on `PATH`), point it explicitly: `JAVA_HOME=$(mise where java) ./gradlew build`.

- All local gates, timed like CI: `scripts/gates.sh [server|web|e2e|all]` (stops at the first failure, records to the shared timings TSV — `.claude/docs/build-times.md`)
- Build everything: `./gradlew build`
- Run the server (Ktor + Netty on port 8084): `./gradlew :server:run`
- Run all tests: `./gradlew test`
- Run server tests only: `./gradlew :server:test` (needs a Docker daemon — Testcontainers; with
  OrbStack and no `/var/run/docker.sock`, export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
  first)
- Run a single test: `./gradlew :server:test --tests "ch.nokillswit.ServerTest.security headers are set on responses"`
- Static analysis (detekt, both Kotlin modules): `./gradlew detekt` — rides `check`/`build`,
  zero-findings gate (no baseline file). Rule tuning lives in `config/detekt/detekt.yml` ONLY, one
  commented override per deliberate repo idiom; never add an uncommented `@Suppress`.
- Dependency alignment: `./gradlew :server:checkDependencyAlignment` — one version per aligned
  family (Netty, OpenTelemetry, kotlin stdlib/reflect, Jackson 2) on the runtime classpath; rides
  `check`.
- Package the server for deployment: `./gradlew :server:installDist`. **Never use
  `:server:buildFatJar`** — the fat JAR breaks Flyway's `ServiceLoader` discovery and NPEs at
  startup.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`) — the
  rationale is commented in place.
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required).
  See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then
  `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`). The
  API-contract gate is `npm run lint:api` (Spectral lint of the OpenAPI spec + the conformant
  fixture against `api-guidelines/`) and `npm run check:api` (in-memory spec → `schema.ts` diff).
- E2E: `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `npm run lint`,
  `npm run knip`, `npm run typecheck` and `npm run check:scenarios`).
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (a PR changing only Markdown, `measures.md` aside, skips the server job; server — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, and a HIGH/CRITICAL Gradle lockfile vulnerability scan,
  web — incl. the API-contract gate (`lint:api` + `check:api`), e2e statics —
  lint/knip/typecheck/scenario parity/setup, `npm audit` (high+) on both npm workspaces, `k8s-static` (kubeconform over
  `k8s/`), and on `master` an image build plus a Trivy scan of it); the blackbox Playwright
  suite (`e2e.yml`) runs nightly and on demand. Dependabot (`.github/dependabot.yml`) checks every
  workspace, Actions and container manifests weekly; `.claude/docs/dependencies.md` describes
  grouping, compatibility pins and runtime verification, and
  `.claude/docs/dependency-reproducibility.md` describes the Gradle lock/checksum mechanism itself.

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8084` (sign in as
`admin@flow.local` — see the README for where the initial password comes from); local dev is
`docker compose up postgres mailpit` (Postgres on host port **5435**) + `./gradlew :server:run`
(API on **8084**) + `cd web && npm run dev` (Vite on **5176**, proxying `/api` to :8084), each in
its own terminal. The compose stack bundles **Mailpit** (`http://localhost:8028`) and wires the
app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid
Lettuce's (8080/5432/5173/8025), Toadie's (8081/5433/5174/8026) and Covenant's (8082/5434/5175/8027)
so every sibling stack can run side by side; host ports bind to 127.0.0.1 only. Kubernetes
(OrbStack) deployment targets the dedicated `flow` namespace — see `k8s/secret.yaml`'s header for
the secret-creation command.

## Architecture

Flow is Covenant's stripped scaffold plus Jira ingestion (v0.2.0) and metrics/reports (v0.3.0).
The foundation: **JWT auth** with a sliding refresh pair and a server-side revocation blocklist,
opt-in **email MFA**, self-service password reset, per-account lockout and per-IP rate limits,
**ADMIN-managed users** with per-user **feature flags**, a synced per-user UI/email **language**
(EN/PL), flat **teams** (the ownership unit the metrics layer points at), and the React shell
(nav model, palette, theme, changelog). Every feature follows the feature template below.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules
plus two standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`.
  Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`e2e/`** — Playwright blackbox suite against the compose stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (root `build.gradle.kts`) — inherited from the
donor scaffold; renaming is out of scope. Dependency versions are centralized in
`gradle/libs.versions.toml` (every pin carries its rationale); Ktor itself comes from a separate
version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog` in `settings.gradle.kts`.

Resolved Gradle dependencies use strict locking and SHA-256 verification, including artifact
metadata. Normal builds enforce the committed state; intentional updates follow
`.claude/docs/dependency-reproducibility.md`. Docker packaging copies the same lock/checksum files.

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The
application is wired declaratively in `server/src/main/resources/application.yaml` under
`ktor.application.modules` — each entry is a fully-qualified extension function on `Application`
(e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins →
infra (Mail → Crypto → Flyway → Database → Bootstrap → Health) → Jira → Metrics → IngestWorker →
feature route modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all).
`configureDatabase` (most services), `configureJira`, `configureMetrics` and the reports routes
each publish their own services into `Application.attributes` via `AttributeKey`s. To add a cross-cutting concern, create a `configureXxx()` extension under
`plugins/` and register it in `application.yaml`; do not call it from `main.kt`. There is no DI
framework — services travel via `attributes`.

### Bootstrap model — the role switch

`FLOW_ROLE` (`app.role` in `application.yaml`, default `all`) is read once at boot by
`plugins/Role.kt` and published as `AppRole { WEB, WORKER, ALL }` on `Application.attributes`; an
unrecognized value fails startup in every mode. `web` serves the HTTP API (feature routes plus the
SPA/static catch-all); `worker` serves only the health/ready probes — its one HTTP surface — and
runs the ingestion worker (incl. the metrics `DERIVE` job); `all` (the default: dev, `docker
compose`) does both in one process — the test suite defaults to `web` (`TestEnvironment.kt`).
Every feature `configureXRoutes()` and `RoutingKt.configureRouting` early-return via
`Application.servesApi()`; `Application.runsWorker()` is the WORKER|ALL counterpart.
`configureHealth` always registers, and Flyway/Bootstrap always run, regardless of role. See `.claude/docs/ingestion.md` "Roles" for the operator-facing writeup.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>`
(no `ch/nokillswit` directory nesting — a deliberate idiom, the `InvalidPackageDeclaration` detekt
override).

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all — early-returns unless `servesApi()`)
│                       + Health (the public /api/v1/health and /api/v1/ready probes, after Database
│                       — ALWAYS registered) + RateLimits (every per-IP bucket and its name)
│                       + Role (the FLOW_ROLE switch — AppRole/servesApi()/runsWorker(),
│                       registered early)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText/PasswordEmail (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503
├── infra/crypto/       Lettuce's encryption at rest, ported:
│                       FieldCipher (AES-256-GCM `enc:v1:` envelopes, a fresh nonce per value,
│                       current + rotation key), Reencrypt.kt (the boot backfill body),
│                       configureCrypto (DATA_ENCRYPTION_KEY, the burned-key fail-closed check),
│                       EncryptedAtRest (the boot backfill registry in `infra/db/Bootstrap.kt`'s
│                       `encryptedAtRestServices()` — the Jira API token in
│                       `ingest/DataSourceService` is its only consumer)
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed, `Bootstrap.kt`) +
│                       SoftDelete.kt (the SoftDeletable table trait — ONE active() predicate,
│                       nowMillis(), lockActiveForUpdate) + Sql.kt (`containsNormalized`) +
│                       Jsonb.kt (the repo-local `jsonb` column type)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400)
├── infra/outbound/     OutboundGuard.kt — the SSRF guard for every server-initiated call (Toadie's
│                       address-range check + the Jira host allow-list, GuardedDns, the no-proxy/
│                       no-redirect OkHttp client; `.claude/docs/security.md` "Outbound HTTP calls")
├── infra/json/         CanonicalJson.kt — key-sorted canonical JSON + sha256 for stored payloads;
│                       JsonArrays.kt — the shared string-array codec for array columns
├── infra/time/         Millis.kt — MILLIS_PER_DAY, the one fixed-24h-day constant
├── infra/config/       requireConfigInt/requireConfigLong — boot-validated numeric config (Lettuce's)
├── infra/Failures.kt   catchingFailures — run a block, keep the failure without swallowing
│                       cancellation (the blocklist-outage 500 path in plugins/Security.kt)
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429)
├── auth/               PasswordResetEmail.kt (the async reset worker) + POST /api/v1/login (+
│                       the email-MFA branch and /login/mfa second step — MfaChallenges/MfaEmail),
│                       /refresh, /logout + the self-service POST /api/v1/password-reset (uniform
│                       acceptance/throttling, async send-before-store, PasswordResetThrottle) +
│                       token minting + password hashing/generation + LoginThrottle + the
│                       revoked-token blocklist; in-memory login/reset/MFA state is
│                       capacity-bounded with audited 429 saturation
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + the per-user feature
│                       flags (Feature enum + PUT {id}/features, the V5 disabled-set model; MFA
│                       is the inverted-default login-scoped flag) + the per-user language (V1:
│                       PUT {id}/language, self-or-admin — the ONE synced UI+email language;
│                       Languages.kt is the SUPPORTED_LANGUAGES whitelist) + Validation.kt
├── teams/              flat teams (V6): Team.kt (DTOs + sanitizers + validateTeam*),
│                       TeamService.kt (Teams + the TeamMembers hard-delete join; paged list with
│                       name/memberId filters and active-member counts; roster read joining
│                       users; create with an initial roster; addMember/removeMember),
│                       TeamRoutes.kt — GET /api/v1/teams (+ {id}) any authenticated,
│                       POST/PUT/DELETE + the members pair ADMIN only
├── ingest/             v0.2.0 Jira ingestion (`.claude/docs/ingestion.md`): DataSource.kt/
│                       DataSourceService.kt/DataSourceRoutes.kt — the generic connector registry
│                       (V8, ADMIN-only CRUD, the first `EncryptedAtRest` consumer) + Connector.kt
│                       (the per-kind interface every connector implements —
│                       `testConnection`/`run`/`purgeSteps`) + SyncJob.kt/SyncJobs.kt/
│                       SyncJobRoutes.kt (V9 `sync_jobs` — the job queue and its ADMIN-only
│                       enqueue/list/cancel API) + SyncCursors.kt (V9 `sync_cursors` — the
│                       per-stream resumable cursor store) + IngestWorker.kt (the `FLOW_ROLE=worker`
│                       scheduler: enqueues due jobs, claims with a lease/heartbeat under
│                       `FOR UPDATE SKIP LOCKED`, runs each claim's connector, releases on shutdown)
│                       + Stream.kt (the `Stream`/`StreamContext` contract every stream implements)
│                       + SyncStatus.kt/SyncStatusRoutes.kt (GET …/{id}/status), RawIssueInspection
│                       .kt/RawIssueInspectorRoutes.kt (GET …/{id}/raw-issues/{issueKey}),
│                       DataProfile.kt/DataProfileRoutes.kt (GET …/{id}/profile) — read-only views
├── jira/               the Jira Cloud connector (`.claude/docs/jira-integration.md`): Jira.kt
│                       (configureJira, the guarded HttpClient, the stub-URL production refusal),
│                       JiraHttp.kt/JiraClient.kt/JiraModels.kt (backoff, bounded reads, typed
│                       endpoints), JiraJql.kt, JiraTime.kt, JiraConnector.kt
│                       (testConnection + the per-kind stream order), JiraRawStore.kt (V10–V12
│                       `raw.jira_*`), the streams
│                       (JiraReferenceStream/IssuesStream/ChangelogStream/WorklogStream/
│                       ReconcileStream/ProcessStream/ProfileStream), JiraNormalizer.kt (raw →
│                       the neutral shape) and JiraProfile.kt (the data-profile aggregates)
├── norm/               the connector-agnostic normalized layer (V13 `norm.*`): Tiling.kt (pure
│                       status/field interval tiling + anomaly flags), Normalization.kt
│                       (PROCESSING_VERSION, the glue), WorkItemStore.kt (per-page REPLACE (per-issue scope),
│                       reference-row rebuilds, purge)
├── metrics/            v0.3.0 metrics configuration + the DERIVE job (`.claude/docs/metrics.md`):
│                       config/memberships/Jira-users services + routes, MetricsSettings,
│                       DataSourceMetricsConfig, WorkingCalendar, MetricsDeriver + Derive*Step/
│                       Kernels/Model/TaskRows files, MetricsStore (the batch writers) + MetricsTables (the Exposed
│                       table objects, `MetricsTables.DimDate` …) + MetricsRows (the row shapes) (V15–V17 `metrics.*`)
└── reports/            the reports API (`.claude/docs/reports.md`): shared filter/`meta`/
                        `Distribution` machinery (ReportSupport, SnapshotSupport, DataQuality*)
                        + one `<Name>Report.kt` per report
```

**Feature template — copy `teams/` (a small ADMIN-curated registry with a roster)**: it is the
only shape the foundation ships. `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`)
with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling
`<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes
under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on
every mutation, authorization BEFORE body decoding so 403 wins over 400 — declarative and flat; past
~100 lines it delegates to private `Route.xxx(deps)` functions grouped by concern rather than
splitting into more files, which is exactly what `config/detekt/detekt.yml`'s `LongMethod`
(threshold 100) and `CyclomaticComplexMethod` (excludes `*Routes.kt`) overrides protect),
`<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`,
soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one
predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`),
spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages +
`NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc +
coverage-map line. Fuller shapes (sub-collections, pipelines) live in `ingest/`, `metrics/`, `reports/`.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every
endpoint change edits it in the same commit. The server test suite validates every test-client
`/api/` interaction against it (`OpenApiConformance.kt`, default `-Dopenapi.conformance=fail`); the
frontend derives its request/response types from it (`npm run gen:api` → committed
`web/src/api/schema.ts` — regenerate in the same commit as a spec change). The file says
`openapi: 3.1.0` but must use only 3.0-compatible constructs (the conformance harness relabels it
in memory; `OpenApiSpecTest` guards this). `AnonymousAccessTest` sweeps every operation without a
declared `security: []` for a 401 straight from the spec — the "401 sweep" — so a new route is
covered the moment its spec entry lands.

### Cross-cutting conventions

Two docs apply to nearly every change and are always loaded:

@.claude/docs/testing.md
@.claude/docs/list-endpoints.md

Every other convention doc is **read on demand, not imported** — together they exceed the
instruction-size budget. They are binding all the same: **before touching an area, read its doc
in full** (and name it in any implementer/reviewer brief for that area). When a doc below and
this file disagree, the doc wins.

| Doc | Read before you touch |
|---|---|
| `.claude/docs/persistence.md` | any migration (`db/migration/V*.sql` — applied bytes are immutable, `MigrationChecksumTest`), an Exposed table/`*Service.kt`, soft delete, a cross-feature table read/write (the list there IS the permission), the connection pool, the `raw`/`norm`/`metrics` schemas |
| `.claude/docs/security.md` | auth/JWT/MFA/password reset, rate limits, headers/CSP, outbound HTTP (the SSRF guard), secrets/encryption at rest, production fail-closed startup checks, payload validation |
| `.claude/docs/authorization.md` | any route: its guard (ADMIN vs any authenticated), 403-before-400/404 ordering, existence disclosure, the error/`ProblemDetail` mapping |
| `.claude/docs/observability.md` | any mutation or denial path (it needs an `audit(...)` event, listed there), logging, OpenTelemetry, health probes |
| `.claude/docs/ingestion.md` | `ingest/`, the sync-job queue/worker, streams and cursors, PROCESS/`norm` tiling, the data profile, the Jira stub fixture |
| `.claude/docs/jira-integration.md` | `jira/`: the Jira client, auth/gateway, backoff, endpoints, timestamps (never `Instant.parse` on Jira text) |
| `.claude/docs/domain-model.md` | anything in `metrics/` or a report: entities, the PV/EV/AC dimensions, decisions D1–D16, amendments, invariants |
| `.claude/docs/metrics.md` | `metrics/`: the configuration model, DERIVE, the `metrics` star mechanics, the daily aggregates, performance figures |
| `.claude/docs/measures.md` | any derived number or report: the per-measure contract (grain, anchor, attribution, estimate snapshot, missing data; `MeasureContractTest` checks its "Pinned by" column) |
| `.claude/docs/reports.md` | `reports/` or any report page: the reports API (filter parser, `Distribution`, `meta`, D12 posture) and each report's shape, levels and period rules |
| `.claude/docs/dependencies.md` | any dependency, image or runtime-pin change (grouping, compatibility pins, acceptance checks) |
| `.claude/docs/dependency-reproducibility.md` | lockfiles or `gradle/verification-metadata.xml` (the empty-`GRADLE_USER_HOME` rule) |
| `.claude/docs/app-releases.md` | a version bump, changelog entry, tag or GitHub release |
| `.claude/docs/build-times.md` | any CI/build/test-time change; when a gate gets slower (`scripts/timings/`, budgets, history) |
| `.claude/docs/audit-status.md` | the checkup record: what was audited, fixed, parked; start here for the next checkup |

A new doc under `.claude/docs/` gets a row here, not an `@` import.

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n
with EN/PL parity, the transport layer, theming — the blue `flow` brand is the interactive accent
only; red = blocking, orange = waived, teal = success).
