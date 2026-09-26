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

- **v0.1.0 (this codebase) — foundation.** Sign-in with email MFA, users, teams, feature flags,
  EN/PL, light/dark theme. No Jira/GitLab data exists yet — `web/src/pages/Home.tsx` states that
  plainly instead of rendering an empty dashboard.
- **v0.2.0 — Jira ingestion.** An ADMIN-managed Jira Cloud connection (an Atlassian service
  account + a scoped read-only API token, encrypted at rest with `infra/crypto/FieldCipher` —
  wired and waiting, `infra/db/Bootstrap.kt`'s `encryptedAtRestServices()` is empty today), a raw
  store with incremental cursors, and a neutral normalized layer above it. Port Covenant's
  connector conventions (see "Donors" below) rather than inventing an ingestion shape.
- **Next — the domain model.** Assumptions, a conceptual model and its invariants for flow
  metrics, built on the normalized layer above.

Brand: blue (the `flow` colour tuple in `web/src/theme.ts`, `primaryShade: { light: 8, dark: 9 }`);
the logo is three streamlines on a blue tile (`web/src/components/BrandLogo.tsx`).

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
  dashboards to come; its WireMock teams-stub pattern for integration-testing an external API
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
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (server — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, and a HIGH/CRITICAL Gradle lockfile vulnerability scan,
  web — incl. the API-contract gate (`lint:api` + `check:api`), e2e statics —
  lint/knip/typecheck/scenario parity/setup — an image build on `master`); the blackbox Playwright
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

Flow is, today, the generic developer-tool foundation left once Covenant's contract-catalog domain
is stripped from its scaffold: **JWT auth** with a sliding refresh pair and a server-side
revocation blocklist, opt-in **email MFA**, self-service password reset, per-account lockout and
per-IP rate limits, **ADMIN-managed users** with per-user **feature flags**, a synced per-user
UI/email **language** (EN/PL), flat **teams** (an ADMIN-curated registry with rosters — the
ownership unit the Jira domain model will point at), and the React shell (nav model, command
palette, theme, changelog). Every feature landed in the shape of the feature template below; the
next one does too.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules
plus two standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`.
  Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`e2e/`** — Playwright blackbox suite against the compose stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (set in root `build.gradle.kts`) — inherited
from the donor scaffold; renaming it is out of scope until it actually matters. Dependency
versions are centralized in `gradle/libs.versions.toml` (every pin carries its rationale); Ktor
itself comes from a separate version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog`
in `settings.gradle.kts`.

Resolved Gradle dependencies use strict locking and SHA-256 verification, including artifact
metadata. Normal builds enforce the committed state; intentional updates follow
`.claude/docs/dependency-reproducibility.md`. Docker packaging copies the same lock/checksum files.

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The
application is wired declaratively in `server/src/main/resources/application.yaml` under
`ktor.application.modules` — each entry is a fully-qualified extension function on `Application`
(e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins →
infra (Mail → Crypto → Flyway → Database → Bootstrap → Health; Database is the composition root
that publishes every service into `Application.attributes` via `AttributeKey`s) → feature route
modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all). To add a cross-cutting
concern, create a `configureXxx()` extension under `plugins/` and register it in
`application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via
`attributes`.

### Bootstrap model — the role switch

`FLOW_ROLE` (`app.role` in `application.yaml`, default `all`) is read once at boot by
`plugins/Role.kt` and published as `AppRole { WEB, WORKER, ALL }` on `Application.attributes`; an
unrecognized value fails startup in every mode. `web` serves the HTTP API (feature routes plus the
SPA/static catch-all); `worker` serves only the health/ready probes — its one HTTP surface — and
runs the ingestion worker arriving in v0.2.0 commit 5; `all` (dev, `docker compose`, the test
suite) does both in one process. Every feature `configureXRoutes()` and `RoutingKt.configureRouting`
early-return via `Application.servesApi()`; `Application.runsWorker()` is the WORKER|ALL
counterpart. `configureHealth` always registers, and Flyway/Bootstrap always run, regardless of
role. See `.claude/docs/ingestion.md` "Roles" for the operator-facing writeup.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>`
(no `ch/nokillswit` directory nesting — a deliberate idiom, protected by the `InvalidPackageDeclaration`
detekt override).

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all — early-returns unless `servesApi()`)
│                       + Health (the public /api/v1/health and /api/v1/ready probes, after Database
│                       — ALWAYS registered, regardless of role)
│                       + RateLimits (every per-IP bucket and its name — login, refresh,
│                       password-reset, MFA)
│                       + Role (the FLOW_ROLE switch — AppRole/servesApi()/runsWorker(),
│                       registered early, before the infra/feature modules)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText/PasswordEmail (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/crypto/       Lettuce's encryption at rest, ported and READY but not yet consumed:
│                       FieldCipher (AES-256-GCM `enc:v1:` envelopes, a fresh nonce per value,
│                       current + rotation key), Reencrypt.kt (the boot backfill body),
│                       configureCrypto (DATA_ENCRYPTION_KEY, the burned-key fail-closed check),
│                       EncryptedAtRest (the boot backfill registry in `infra/db/Bootstrap.kt`'s
│                       `encryptedAtRestServices()` — empty today; the Jira API token in v0.2.0
│                       is its first consumer)
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed, `Bootstrap.kt`) +
│                       SoftDelete.kt (the SoftDeletable table trait — ONE active() predicate,
│                       nowMillis(), lockActiveForUpdate)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400)
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429)
├── auth/               PasswordResetEmail.kt (the async reset worker) + POST /api/v1/login (+
│                       the email-MFA branch and /login/mfa second step — MfaChallenges/MfaEmail),
│                       /refresh, /logout + the self-service POST /api/v1/password-reset (uniform
│                       acceptance/throttling, async send-before-store, PasswordResetThrottle) +
│                       token minting + password hashing/generation + LoginThrottle + the
│                       revoked-token blocklist; login/reset/MFA in-memory state has strict
│                       configurable capacities and audited 429 saturation paths
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
│                       users; create with an initial roster; addMember/removeMember;
│                       activeTeamIdsOf), TeamRoutes.kt — GET /api/v1/teams (+ {id}) any
│                       authenticated, POST/PUT/DELETE + the members pair ADMIN only
├── ingest/             v0.2.0 Jira ingestion (`.claude/docs/ingestion.md`): DataSource.kt/
│                       DataSourceService.kt/DataSourceRoutes.kt — the generic connector registry
│                       (V8, ADMIN-only CRUD, the first `EncryptedAtRest` consumer) + Connector.kt
│                       (the per-kind interface every connector, e.g. `jira/`, implements —
│                       `testConnection`/`run`/`purgeSteps`) + SyncJob.kt/SyncJobs.kt/
│                       SyncJobRoutes.kt (V9 `sync_jobs` — the job queue and its ADMIN-only
│                       enqueue/list/cancel API) + SyncCursors.kt (V9 `sync_cursors` — the
│                       per-stream resumable cursor store) + IngestWorker.kt (the `FLOW_ROLE=worker`
│                       scheduler: enqueues due jobs, claims with a lease/heartbeat under
│                       `FOR UPDATE SKIP LOCKED`, runs each claim's connector, releases on shutdown)
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
coverage-map line. A fuller shape (ownership guard, sub-collections, a checks pipeline) arrives
with the Jira ingestion domain model in v0.2.0+ — see "Product" above and Covenant's `contracts/`
package for the reference this repo will port from.

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

@.claude/docs/persistence.md
@.claude/docs/list-endpoints.md
@.claude/docs/security.md
@.claude/docs/authorization.md
@.claude/docs/observability.md
@.claude/docs/testing.md
@.claude/docs/dependencies.md
@.claude/docs/dependency-reproducibility.md
@.claude/docs/app-releases.md
@.claude/docs/ingestion.md
@.claude/docs/jira-integration.md

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n
with EN/PL parity, the transport layer, theming — the blue `flow` brand is the interactive accent
only; red = blocking, orange = waived, teal = success).
