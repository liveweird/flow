# Repository Guidelines

## Sources of Truth

This file is the Codex entry point. Before changing or reviewing code, also read the relevant
sections of `CLAUDE.md`; when working under `web/`, read `web/CLAUDE.md` as well. `CLAUDE.md`
uses Claude's `@...` import syntax to reference the cross-cutting conventions in `.claude/docs/`
(persistence, list endpoints, security, authorization, observability, testing, dependencies,
app releases); Codex must open the applicable files directly. Together those files contain the
detailed, actively maintained security, persistence, UI, and testing conventions shared by the
project. For API work, `api-guidelines/API-GUIDELINES.md` is authoritative and its stable rule IDs
should be cited in reviews. If documentation and executable configuration disagree, the
configuration and code win; update the affected guidance in the same change. Keep this entry
point's feature, package, and command summaries synchronized when those surfaces change; detailed
conventions remain in the shared references rather than being copied here.

Flow is a developer-intelligence tool for a ~70-developer SaaS unit (Jira Cloud today, GitLab
later): raw data ingested incrementally, processed locally into flow metrics (cycle/lead time,
throughput, WIP, flow efficiency, work-item age), bottleneck diagnosis, trends, team comparison,
and outliers. Version 0.2.0 adds Jira Cloud ingestion (raw store, normalized layer, data profile)
on top of the v0.1.0 foundation; there are no flow metrics yet. See `CLAUDE.md`'s "Product" section
for the roadmap (next: the domain model).

Flow's repository was copied from [Covenant](https://github.com/liveweird/covenant) and trimmed to
its generic foundation. The implemented surface is authentication/session handling (JWT pair,
revocation blocklist, capacity-bounded lockout, rate limits), capacity-bounded email MFA and
password reset, admin-managed users and per-user feature flags, the synced user language, shared
paging, flat teams with rosters, and the React shell (nav model, command palette, theme,
changelog). **Port, don't reinvent**: when adding a capability one of the sibling repos already
has, port its implementation. `CLAUDE.md`'s "Donors" section lists which sibling to port from for
the Jira connector (Covenant's `toadie/`), charts (Lettuce), and the outbound SSRF guard (Toadie).

The playbooks in `.claude/skills/` are useful repository-local references even outside Claude:
`api-review` covers the two-pass OpenAPI review, `run-stack` covers packaging/deployment, and
`verify` covers browser verification and cleanup.

## Project Structure & Architecture

This is a Kotlin/Gradle backend plus two standalone npm workspaces:

- `core/` is Kotlin Multiplatform (currently JVM-targeted) and owns the shared OpenTelemetry SDK
  bootstrap.
- `server/` is the Kotlin/JVM Ktor application. Feature packages live directly under
  `server/src/main/kotlin/`: `auth`, `users`, `teams` (the flat-teams registry with rosters —
  the feature template), and the ingestion trio `ingest` (connector registry, job queue, worker,
  status/inspector/profile views), `jira` (the Jira Cloud connector and its streams) and `norm`
  (the neutral normalized layer). Cross-cutting wiring and policy live in `plugins/`, `audit/`, and
  `authz/`; database, mail, encryption at rest (`infra/crypto`, consumed by the Jira API token),
  outbound-call guarding (`infra/outbound`), paging, and shared validation infrastructure live in
  `infra/`. See `CLAUDE.md`
  "Package layout" for the detailed map.
- `server/src/main/resources/application.yaml` declaratively registers application modules.
  `main.kt` only starts `EngineMain`; do not wire features from it. Module order matters because
  modules publish and consume Ktor application attributes.
- PostgreSQL is the only database. Flyway migrations under `server/src/main/resources/db/migration/`
  (currently `V1`–`V13`) are the schema source of truth; Exposed over R2DBC is used for runtime
  queries. Never introduce runtime DDL such as `SchemaUtils.create`, and never edit an applied
  migration, including its comments: Flyway checksums are pinned by `MigrationChecksumTest`; add a
  new migration instead.
- `server/src/main/resources/openapi/documentation.yaml` is the hand-maintained API contract.
- `web/` is a standalone Vite + React 19 + TypeScript SPA. Gradle does not build it. Source is
  organized into `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`, and bilingual
  resources under `locales/{en,pl}/`.
- Backend tests are in `server/src/test/kotlin/`, colocated frontend tests use `*.test.ts(x)`, and
  Playwright journeys are in `e2e/tests/*.spec.ts` with their design artifacts in
  `e2e/scenarios/*.md`.

Routing is feature-local. Cross-cutting Ktor wiring functions are named `configureXxx` and must be
registered in `application.yaml`. `plugins/Routing.kt` is only the final SPA/static-file catch-all.
Route registrar files (`<Entity>Routes.kt`) stay declarative — a flat list of endpoints; past
~100 lines they delegate to private `Route.xxx(deps)` functions grouped by concern rather than
splitting into more files (see `config/detekt/detekt.yml`'s `LongMethod`/`CyclomaticComplexMethod`
overrides, which protect exactly this idiom).

## Build, Test, and Development Commands

- `docker compose up --build`: build and run PostgreSQL, the API, and the SPA at
  `http://localhost:8084` (sign in as `admin@flow.local` — see README for the initial password
  source); Mailpit captures reset and MFA email at `http://localhost:8028`.
- `docker compose up postgres mailpit`: start the development database on **5435** and mail
  catcher on **8028**.
- `./gradlew build`: compile and verify the Gradle modules with the JDK 21 toolchain (the local
  dev JDK is pinned in `mise.toml`).
- `./gradlew :server:run`: start Ktor/Netty on port 8084.
- `./gradlew test` or `./gradlew :server:test`: run Kotlin tests; Docker is required for
  Testcontainers (OrbStack without `/var/run/docker.sock`: export
  `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`).
- `./gradlew :server:test --tests "<fully-qualified test name>"`: run one backend test.
- `./gradlew detekt`: static analysis over `core` + `server` — zero-findings gate, no baseline.
  Tune rules in `config/detekt/detekt.yml` with a documented rationale; never add an uncommented
  `@Suppress`.
- `./gradlew :server:checkDependencyAlignment`: one version per aligned dependency family on the
  runtime classpath.
- `./gradlew :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment :dependencies :core:dependencies :server:dependencies --write-locks`:
  refresh the resolved project and plugin inventory for vulnerability scanning; review the locks.
- `cd web && npm run dev`: start Vite on port 5176, proxying `/api` to Ktor on :8084.
- `cd web && npm run build && npm run lint && npm test`: type-check, bundle, lint, and run Vitest.
- `cd web && npm run test:coverage`: run frontend coverage gates. `npm run knip`: dead-code gate.
- `cd web && npm run gen:api`: regenerate `web/src/api/schema.ts` from the OpenAPI contract.
- `cd web && npm run lint:api`: Spectral-lint the OpenAPI spec and the conformant fixture against
  `api-guidelines/`. `cd web && npm run check:api`: diff an in-memory regeneration of
  `schema.ts` against the committed file (never overwrites it).
- `cd e2e && npm ci && npx playwright install chromium && npm test`: install and run Playwright
  against the full stack on port 8084. `npm run lint`, `npm run knip`, `npm run typecheck`, and
  `npm run check:scenarios`, and `npm run test:setup` are the Docker-free gates. Setup reuses or
  starts the default stack and leaves services and volumes intact.
- `.github/workflows/ci.yml` runs the server (incl. Gradle lockfile vulnerability scan), web, and
  e2e-static gates on pushes to `master` and on pull requests (including server OpenAPI coverage and
  frontend spec → `schema.ts` drift, and an image build on `master`); `e2e.yml` runs the blackbox
  suite nightly against `master` and on demand.

For a clean frontend install, use `cd web && npm install --legacy-peer-deps`;
`openapi-typescript` declares a TypeScript 5 peer while the project uses TypeScript 6. Keep the
Gradle and npm toolchains disjoint.

If `./gradlew` resolves the wrong JDK (a system JRE ahead of the mise shim on `PATH`), point it
explicitly: `JAVA_HOME=$(mise where java) ./gradlew build`.

For dependency updates, read `.claude/docs/dependencies.md`: it covers automated update scopes,
compatibility pairs, image/JDK verification, and the maintenance checks for pins outside
Dependabot.

Package deployments with `./gradlew :server:installDist`. Never use `buildFatJar`: merging Flyway
service descriptors breaks plugin discovery at runtime. JVM runtime flags are intentionally set in
`server/build.gradle.kts`; consult `.claude/skills/run-stack/SKILL.md` before changing them.

## API and Backend Conventions

Follow `api-guidelines/API-GUIDELINES.md` for resource naming, pagination, filtering, sorting,
errors, statuses, auth, and conformance. All error bodies are RFC 7807
`application/problem+json`. Keep authorization checks before resource-dependent validation so
callers cannot infer inaccessible state (403 wins over 400). List endpoints use the
`{items, page, pageSize, total}` envelope and the already-ported `infra/paging` machinery; copy
the users list implementation rather than parsing pagination, filters, or sorting again (see
`.claude/docs/list-endpoints.md`).

When an API changes, update all of the following in the same change:

1. Route/service behavior and focused tests.
2. `server/src/main/resources/openapi/documentation.yaml`.
3. The generated `web/src/api/schema.ts` via `npm run gen:api`.
4. API guideline conformance, using the Spectral ruleset and review checklist described in
   `.claude/skills/api-review/SKILL.md`.

The server's OpenAPI document declares 3.1.0 but uses only 3.0-compatible constructs for the
conformance harness; `OpenApiSpecTest` guards this restriction. `AnonymousAccessTest` sweeps the
spec for every operation missing `security: []` and probes it token-less (the "401 sweep") — a new
route is covered the moment its spec entry lands.

Use `V<number>__description.sql` for migrations (current range `V1`–`V13`). Business entities
follow the established soft-delete convention (`marked_as_deleted`, active-row filtering on every
read/count/mutation, and partial unique indexes where deleted values may be reused); follow the
detailed pattern in `.claude/docs/persistence.md` (the `SoftDeletable` trait in
`infra/db/SoftDelete.kt`) rather than inventing a variant. Emit structured `audit(...)` events for
security-relevant mutations and denials, and never log passwords or tokens; see
`.claude/docs/observability.md` for the full event catalog and `.claude/docs/persistence.md` for
persistence conventions.

Use four-space indentation, preserve existing package boundaries, PascalCase for Kotlin types, and
camelCase for functions and variables. Name backend test classes `*Test`.

## Frontend Conventions

Use two-space indentation, PascalCase for React components, and the existing shared
components/hooks instead of cloning transport, error-mapping, or session logic. The design system
is owned by `web/src/theme.ts`, `web/src/themeVariables.ts` (AA-tested colour tokens, guarded by
`theme.test.ts`), and `web/src/theme.module.css`: the brand blue (`flow` tuple,
`primaryShade: { light: 8, dark: 9 }`) is reserved for primary actions, active navigation, and
focus; don't reintroduce stock-green success states (success is teal, a blocking error is red, a
waived finding is orange — reserved for the checks the domain model will add). Keep accessibility
roles, labels, and semantic tables stable because tests and Playwright use them as contracts.

After successful mutations while a list remains mounted, use `utils/queryRefresh.ts` to cancel
pending reads before invalidating all affected query prefixes. This prevents an initial filtered
fetch from restoring pre-mutation rows while `keepPreviousData` displays the previous page; see
`web/CLAUDE.md` for the shared refresh convention.

All user-facing strings must use react-i18next. Keep English and Polish resources in parity
(enforced by `locales/parity.test.ts`); Polish uses inclusive slash forms, active voice, never
impersonal/passive dodges. Errors render inline as red Alerts; follow `web/CLAUDE.md` for the
exact transport, i18n, and theming patterns.

Pages are lazy and use shared `PageHeader` chrome; navigation is defined once in
`utils/navigation.ts` for the sidebar, user menu, and command palette. User creation/reset
passwords are generated client-side and revealed exactly once; the server never returns plaintext
passwords. The selected UI language is also stored on the user and drives server-composed email.

`web/src/changelog/version.ts` (`APP_VERSION`) is the sole source of the displayed app version;
the Gradle snapshot version is unrelated. A release adds the newest bilingual markdown entry to
`web/src/changelog/entries.ts` and bumps `APP_VERSION` in the same change; tests pin their parity.
Publication also requires an annotated version tag at the verified main release commit and
matching bilingual GitHub release notes; follow `.claude/docs/app-releases.md`. Test-only or
documentation changes can retain the app version and never move an existing release tag.

## Testing and Verification

Use Kotlin Test/Ktor Test Host and Vitest with Testing Library (web), and Playwright for
cross-stack journeys. Add focused regression coverage for behavioral changes. Backend tests boot
PostgreSQL through Testcontainers, apply every Flyway migration, and include the V3 seed admin;
use unique markers (`uniqueEmail(...)`) instead of asserting global counts.

Every `/api/` interaction made through the shared backend test clients is checked against OpenAPI.
Prefer `jsonClient()`/`authedClient()` so tests do not bypass conformance validation. A coverage
report (exercised vs. declared operation/status pairs) is a gate: `server/build/reports/openapi-conformance/gaps.txt`
must be empty whenever the whole suite ran (`CoverageGapsTest` pins the cross-cutting statuses a
shared plugin answers for every route). `check` enforces the Kover floors in
`server/build.gradle.kts` and the Vitest floors in `web/vite.config.ts`. Re-measure and raise
floors as coverage improves; do not lower them to accommodate new code. Any test-local Mantine
provider must set `env="test"` so popovers and selects work under happy-dom.

A new or behaviorally changed e2e test lands with its scenario file in `e2e/scenarios/` and its
coverage-map line in `e2e/README.md` in the same commit (`npm run check:scenarios` enforces the
parity); scenario headings and Playwright test titles must match exactly. For nontrivial
cross-stack behavior, verify through the SPA using the workflow in `.claude/skills/verify/SKILL.md`,
and clean up any records created in the development database.

## Commit, Documentation, and Security

Use Conventional Commit subjects such as `feat:`, `fix:`, `fix(e2e):`, and `docs:`. PRs should
explain behavior and risk, list verification commands, link issues, and include screenshots for UI
changes. Keep migrations, API contract, generated schema, tests, and both translations
synchronized when applicable.

Never commit production JWT, database, or encryption secrets. Committed `changeme` values and
development keys are burned demo credentials; production mode deliberately refuses them (the JWT
fail-closed check in `plugins/Security.kt` and the seed-password check in `infra/db/Bootstrap.kt`).
Mail transport is also fail-closed: production refuses the `log` transport, and SMTP with a blank
host fails in every mode. The compose demo uses SMTP through Mailpit; the image defaults to
disabled mail, where reset and MFA-dependent flows return 503. `infra/crypto/FieldCipher` is wired
and fail-closed on burned encryption keys, ready for its first consumer (the Jira API token,
v0.2.0).
