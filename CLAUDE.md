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

**Roadmap:** v0.1.0 foundation, v0.2.0 Jira ingestion, v0.3.0 the domain model, metrics and reports, and
**v0.4.0 (this codebase) — the Deep dive**: report 17 (A29), plan, execution and cost per task and day on a drillable
epic/task × time matrix; seventeen reports on sixteen pages. Next: the real-Jira first sync, then `BACKLOG.md`. The
per-version scope: `.claude/docs/product.md`.

Brand: blue (the `flow` colour tuple in `web/src/theme.ts`, `primaryShade: { light: 8, dark: 9 }`);
the logo is "Rolling" — a stream running round a blue disc and rolling inward into a curl
(`web/public/logo-{light,dark}.svg` + `favicon.svg`, rendered by `web/src/components/BrandLogo.tsx`).

## Donors

Flow's repository was copied from Covenant and trimmed to its generic foundation. **Port, don't reinvent**: when
Flow needs a capability one of the sibling repos already has, port its implementation rather than designing a new
one — Covenant (`~/Sources/covenant`, the primary donor; its `toadie/` connector is the template for `jira/`),
Lettuce (`~/Sources/lettuce`, charts and the WireMock stub pattern) and Toadie (`~/Sources/toadie`, the `UrlFetch`
SSRF guard — `.claude/docs/security.md`). What each one donated: `.claude/docs/product.md`.

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
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR; the blackbox Playwright suite (`e2e.yml`) runs
  nightly and on demand; Dependabot checks every workspace weekly. The job list, the Markdown-only skip, Trivy and
  grouping: `.claude/docs/ci.md` (dependency rules: `dependencies.md`, `dependency-reproducibility.md`).

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8084` (sign in as `admin@flow.local` — the README
says where the initial password comes from); local dev is `docker compose up postgres mailpit` (Postgres on host port
**5435**, Mailpit UI **8028**) + `./gradlew :server:run` (API on **8084**) + `cd web && npm run dev` (Vite on **5176**,
proxying `/api` to :8084), each in its own terminal. The ports deliberately avoid Lettuce's, Toadie's and Covenant's so
every sibling stack runs side by side; host ports bind to 127.0.0.1 only. Mailpit wiring, the sibling port table and
the Kubernetes (OrbStack) `flow` namespace: `.claude/docs/ci.md` "Running the full stack".

## Architecture

Flow is Covenant's stripped scaffold plus Jira ingestion (v0.2.0), metrics/reports (v0.3.0) and the Deep dive (v0.4.0).
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
override). One line per package; `ls` gives the files.

- `plugins/` — cross-cutting Ktor wiring (`configureXxx` that only `install` plugins; Security = JWT, ErrorHandling = RFC 7807). `Routing` (the SPA catch-all) is registered strictly last and early-returns unless `servesApi()`; `Health` (`/api/v1/health`, `/ready`) is ALWAYS registered, after Database; `Role` (the `FLOW_ROLE` switch: `AppRole`/`servesApi()`/`runsWorker()`) is registered early; `RateLimits` holds every per-IP bucket.
- `infra/mail/` — outbound email (Lettuce's, ported): `MAIL_TRANSPORT` log/smtp/disabled; the `log` transport is refused in production (fail-closed); a null mailer = email features answer 503.
- `infra/crypto/` — encryption at rest (Lettuce's, ported): `FieldCipher` (AES-256-GCM `enc:v1:` envelopes, a fresh nonce per value, current + rotation key), `configureCrypto` (`DATA_ENCRYPTION_KEY`; a burned key fails startup closed), `EncryptedAtRest` (the boot-backfill registry; the Jira API token is its only consumer), `Reencrypt.kt` (`reencryptRows`, the backfill/rotation body).
- `infra/db/` — Flyway bootstrap, the R2DBC composition root, the seed bootstrap (admin rotation, prod fail-closed), `SoftDelete.kt` (the `SoftDeletable` table trait: ONE `active()` predicate, `nowMillis()`, `lockActiveForUpdate`), `Sql.kt` (`containsNormalized` — the only substring filter), `Jsonb.kt` (the repo-local `jsonb` column type), `MultiRowInsert.kt` (`insertRows`/`upsertRows`, multi-row writes).
- `infra/paging/` — the shared list-endpoint machinery (Lettuce's, ported verbatim; `list-endpoints.md`).
- `infra/validation/` — cross-feature input helpers (`sanitizeSingleLine`: trim + control-char 400).
- `infra/outbound/` — `OutboundGuard.kt`, the SSRF guard for EVERY server-initiated call (address-range check, the Jira host allow-list, `GuardedDns`, the no-proxy/no-redirect client); see `.claude/docs/security.md` "Outbound HTTP calls".
- `infra/json/` — `CanonicalJson.kt` (canonical JSON + sha256 for stored payloads), `JsonArrays.kt`.
- `infra/time/` — `Millis.kt`, `MILLIS_PER_DAY`, the one fixed-24h-day constant.
- `infra/config/` — `requireConfigInt`/`requireConfigLong`, boot-validated numeric config (Lettuce's).
- `infra/Failures.kt` — `catchingFailures` (keeps the failure without swallowing cancellation).
- `audit/` — the security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs.
- `authz/` — `CallerPrincipal`, the guards (`requireAdmin`, `requireSelfOrAdmin`) and the typed HTTP exceptions (401/403/404/409/429).
- `auth/` — login (+ the email-MFA second step), refresh, logout, self-service password reset, token minting, password hashing, the revoked-token blocklist; in-memory login/reset/MFA state is capacity-bounded with audited 429 saturation.
- `users/` — the user domain: ADMIN-only CRUD (self-delete 403, last-admin 409), per-user feature flags (`Feature` enum, the V5 disabled-set model; MFA is the inverted-default login-scoped flag), the ONE synced per-user UI+email language (`Languages.kt` is the `SUPPORTED_LANGUAGES` whitelist).
- `teams/` — flat teams (V6) and the feature template: GET any authenticated, POST/PUT/DELETE + the members pair ADMIN only.
- `ingest/` — v0.2.0 Jira ingestion (`.claude/docs/ingestion.md`): the connector registry (`DataSource*`, V8, ADMIN-only), the `Connector`/`Stream` contracts, the `sync_jobs` queue (`SyncJobs.kt` facade over `SyncJobReads`/`SyncJobLeases`) + `IngestWorker` (scheduler/run; the lease ticker is `LeaseHeartbeat`, boot config `IngestWorkerConfig`; lease/heartbeat under `FOR UPDATE SKIP LOCKED`), `sync_cursors`, read-only status/raw-issue/profile views. `JobHandlers.kt`'s `JobHandlerRegistry` (on `attributes`) is how other packages plug in the DERIVE handler, extra PURGE steps and the config-revision source — `ingest/` never imports `metrics/` (checkup D5).
- `jira/` — the Jira Cloud connector (`.claude/docs/jira-integration.md`): the guarded client, `JiraRawStore` (V10–V12 `raw.jira_*`; the table objects + a one-line-delegation facade over the `JiraRaw*` per-concern collaborators), the streams, `JiraNormalizer` (raw → neutral shape); `Jira.kt` refuses a stub URL in production.
- `norm/` — the connector-agnostic normalized layer (V13 `norm.*`): `Tiling.kt` (pure status/field interval tiling + anomaly flags), `Normalization.kt` (`PROCESSING_VERSION`), `WorkItemStore.kt` (tables, row shapes and the ONE facade; one collaborator per concern: `WorkItemWriter`, `NormReferenceStore`, `NormDerivationReads`, `NormProfileReads`, `NormInspectorReads`, `NormPickerReads`, `NormPurge`). `SourceFileSizeTest` caps main source files at 400 lines.
- `metrics/` — v0.3.0 configuration + the DERIVE job (`.claude/docs/metrics.md`; `V15–V17` `metrics.*`). `MetricsStore` is a facade over `Metrics{DimDate,DimWrites,FactWrites,SprintWrites,Aggregate,DeriveRun}Store`/`MetricsAnalyzer`/`MetricsPurge`. The config service is split: `MetricsSettingsService` (the `metrics.settings` singleton + the shared revision bump), `MetricsConfigService` (per-connection config tables/PUT/PURGE drain), `MetricsConfigOptions` (the editor's reference data), `DomainOwnerResolver` (A19/A22 owner-team resolution). `MetricsJobHandlers.kt` (`registerMetricsHandlers`) plugs DERIVE/PURGE into the `ingest/` registry.
- `reports/` — the reports API (`.claude/docs/reports.md`): shared filter/`meta`/`Distribution` machinery (`ReportSupport`, `SnapshotSupport`, `DataQuality*`) + one `<Name>Report.kt` per report.

**Feature template — copy `teams/`** (a small ADMIN-curated registry with a roster; the only shape the foundation ships). Before adding a feature, route, service, migration or page, read `.claude/docs/conventions.md` in full.

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
| `.claude/docs/conventions.md` | adding a feature, route, service, migration or page: the `teams/` feature template (DTO + validator, routes, service, migration + checksum pin, spec paths, `gen:api`, lazy page + nav entry, e2e spec + scenario) |
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
| `.claude/docs/ci.md` | a CI job, Dependabot rule or static-analysis config (the CI job list, the detekt/eslint/knip policy details, the full-stack/Kubernetes notes) |
| `.claude/docs/product.md` | the per-version roadmap and what each donor repo (Covenant/Lettuce/Toadie) contributed |
| `.claude/docs/test-fixtures.md` | any server test fixture, the derived/synced stub fixtures, digests, OpenAPI conformance internals, e2e scenario files, Schemathesis |
| `.claude/docs/web-features.md` | a data-source, metrics-config or report page in `web/` (the per-feature frontend conventions) |
| `.claude/docs/web-internals.md` | the `web/` transport/session layer, the user/feature-flag pages, the language switcher, colour tokens/logo, changelog wiring (mechanics behind `web/CLAUDE.md`'s one-liners) |
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
