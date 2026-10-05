### Testing

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `io.ktor.server.testing.testApplication`)
and override the `postgres.*` config keys via `MapApplicationConfig` to point at a Testcontainers
`PostgreSQLContainer("postgres:18.6-alpine@sha256:77f58511…")` (the same digest `docker-compose.yaml` and
`k8s/postgres-deployment.yaml` pin — `PostgresImagePinTest`) started lazily by `PostgresTestSupport` and **shared
across the whole suite within a JVM fork** (one container per fork — "Parallel forks" below) (test-side direct database access, `sharedDatabaseForTests()`, goes through a small
r2dbc-pool built by production's own `connectPooledDatabase` — an unpooled connect paid ~4 ms of backend
setup per transaction, `.claude/docs/build-times.md` WHY 10). Running tests requires a working Docker daemon (Docker Desktop,
OrbStack, etc. — with OrbStack and no `/var/run/docker.sock`, export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`). The container runs **all** Flyway
migrations, so the V3 seed admin (`admin@flow.local`) is present — tests scope their assertions
with unique prefixes/filters (`uniqueEmail("marker")`) rather than asserting absolute counts.

**Parallel forks (`-Pforks=N`).** `server/build.gradle.kts` sets `maxParallelForks` from the `forks` Gradle
property (an integer 1..8, anything else fails any build that runs the `test` task; default 1 = one JVM; CI runs `-Pforks=2`; `forkEvery` is deliberately unset). Each fork is its own
JVM, so it starts its OWN Testcontainers Postgres (`PostgresTestSupport` is a per-JVM `object`) and holds its own
fixture singletons (`SyncedStubFixture`, `DerivedStubFixture`) — every "shared suite" rule in this doc
(`withSoloAdmins`, `restoreSeedAccounts`, the metrics settings row, `dim_date`, worker claims, the fixtures'
tripwires) therefore holds per fork, never across forks; a test must never assume another class ran before it
on the same JVM, or that a class ran at all in this one. Gradle hands whole CLASSES to forks in whatever order it
likes, so **class order is not a contract**: any class may be the first thing a fork runs. The one rule that
follows: the container is migrated by `PostgresTestSupport` itself the moment it starts (a `Flyway.migrate()`
in its lazy init, the same call as `infra/db/Flyway.kt`), so a fixture or raw-JDBC helper never needs its own
"has Flyway run yet?" guard (`PostgresTestSupport.ensureMigrated()` is the explicit spelling; `MetricsDigestTest`
run alone is the pin). The OpenAPI gate below
merges the forks' coverage (per-fork fixed cost and the point where more forks stop paying: `build-times.md` WHY 5).

**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- `configureApp(vararg overrides)` points the app at the shared container (with CSRF off)
  WITHOUT starting it — tests that assert startup behavior (the fail-closed checks) add their
  overrides and call `startApplication()` themselves; `usePostgresTestcontainer()` is the
  configure-and-start shorthand.
- `jsonClient()` / `authedClient(email, password)` — the standard HTTP clients; both go through
  the shared test-client defaults (JSON + `application/problem+json` negotiation + the
  `OpenApiConformance` plugin), and `authedClient` logs in and attaches the bearer on every
  request.
- `LogCapture(loggerName)` + `hasKeyValue` — a Logback `ListAppender` for asserting the audit
  trail (`ch.nokillswit.audit`); `awaitEvent` polls for asynchronously produced events. Detach in
  a finally.
- `TestUsers.seed(email, password, name, role)` (bcrypt cost 4 for speed; defaults to ADMIN — pass
  `UserRole.USER` for a non-privileged caller) — every seeded user funnels through
  `UserService.create`, so they carry the inverted-default MFA-disabled row and log in
  single-step and `TestUsers.softDelete(id)` (direct table update, bypassing the delete endpoint's
  guards) and `TestUsers.withSoloAdmins(ids) { }` (temporarily parks every other active admin —
  the last-admin-protection pin). `seededClient(prefix, role)` is the one-line seed+login
  fixture.
- `postJson`/`putJson` (the JSON body ceremony) + `HttpClient.login` (the raw login POST),
  `withAuditCapture { }` (attach/detach on the audit logger), `withSeedRestored { }` and
  `assertStartupFails(part) { }` for bootstrap/fail-closed tests — use these instead of re-rolling
  the blocks they replaced.
- **Many failed logins → seeded accounts.** A login for an UNKNOWN email pays a discarded cost-12 bcrypt
  verify (the timing equalizer, ~225 ms locally); a test that needs a dozen failures (rate limit,
  proxy trust) logs in as freshly seeded accounts (`TestUsers.seed`, cost 4) with a wrong password —
  the same 401 path at ~1 ms (`.claude/docs/build-times.md` WHY 11); soft-delete them afterwards.
- **`IngestWorker.tick()` claims from the whole shared queue** (and enqueues due jobs for every enabled
  connection), so a direct tick runs whatever other classes left pending. Wrap it in
  `withOnlyConnections(setOf(connId), tickClockMillis) { }` (`TestEnvironment.kt`, beside `withSoloAdmins`):
  for the duration, every OTHER connection is disabled and every OTHER job the claim scan could take (PENDING,
  or RUNNING with `lease_until` below the tick's clock) is parked under a far-future lease — set up in one
  transaction, restored in a `finally`.
- `TestTeams.seed(name, memberIds)` — a fresh team fixture; beside it, raw-row readers for what
  the API hides (`TestTeams.rawRows`/`rawMemberIds`, `TestUsers.stampPasswordChangedAt`). Shared
  suite state (the seed admin) is never mutated destructively — tests mint UNIQUE rows and remove
  their own.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's
  password in the SHARED container; call this afterwards so later tests (and re-runs) see the
  pristine V3 state. Production-mode boots must also override `"mail.transport" to "disabled"` —
  the dev-default `log` transport is refused in production (`MailTransportTest`) — and
  `"security.encryption.key" to strongEncryptionKey()` — the dev-default data-encryption key is
  burned (`CryptoBootTest`); the checks fire in module order (JWT → mail → crypto → seed
  passwords), so a test asserting a later check must satisfy every earlier one.

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in
`server/build.gradle.kts` (the `minBound` line/branch floors, wired into `check` via
`koverVerify`). The measurement EXCLUDES classes annotated `@kotlinx.serialization.Serializable`
(`kover.reports.filters`; behavior never lives in a wire shape — services, validators and a DTO's
companion object stay measured). The floors sit just below current actuals — **re-measure and
raise** them as coverage improves, never lower them for new code (`check` runs only `koverVerify`;
`./gradlew :server:koverXmlReport` gives fresh actuals). Kover stays on everywhere `check` runs and
has no off-switch Gradle property; a tight-loop disable is never committed (the recipe, the fork-sharing
note and the cost figures: `.claude/docs/test-fixtures.md` "Coverage and fork measurements"). Frontend vitest enforces thresholds in
`web/vite.config.ts` (`test.coverage.thresholds`, same re-measure convention — the current
actuals are noted in a comment beside them); run `cd web && npm run test:coverage`.

**Static analysis (detekt).** `./gradlew detekt` runs detekt over `core` + `server` (plain rule
sets, no type resolution) and rides `check`, so `build` fails on any finding — the gate is zero
findings with **no baseline file**. Repo tuning lives in `config/detekt/detekt.yml`, layered on
the bundled defaults; every override there carries a one-line comment naming the deliberate idiom
it protects (wildcard Ktor imports, the flat feature-package layout, declarative `*Routes.kt`
registrars, the validation-throw convention, guard-clause returns). Fix new findings in code
first; extend the config only for a genuinely deliberate idiom, and prefer a config override over
`@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in seconds, no
Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** The SPA's counterpart, same
zero-findings/no-baseline policy: `cd web && npm run lint` carries `eslint-plugin-sonarjs`
(recommended set) plus core size/complexity backstops tuned generously for React's
one-function-per-page architecture (`cognitive-complexity` 40, `complexity` 50 — backstops
against future monsters, not targets); every override in `web/eslint.config.js` carries the idiom
comment. `cd web && npm run knip` is the dead-code gate (unused files/exports/dependencies; test
files count as entries, so a flagged export is unused even by tests) — the generated
`src/api/schema.ts` is excluded (type-checked by `tsc`, not style-linted).

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** next to the source
(`Foo.test.tsx` beside `Foo.tsx`). `src/test/setup.ts` imports `../i18n` and forces `en`, so text
assertions match the English resources; `src/test/render.tsx` is the shared wrapper and — like
every file-local `MantineProvider` — must pass **`env="test"`** (since Mantine 9.4 the
Popover/Combobox dropdown is `display: none` until Floating UI sees a real bounding box, which
never happens in happy-dom, so Select-option clicks silently fail without it). `src/test/http.ts`
holds the fetch-stubbing helpers. The shared setup also forces the reduced-motion media query and
makes every Mantine test provider honor it: `Transition` invokes its animation hook even with
`env="test"`, so synchronous reduced-motion transitions prevent callbacks from outliving happy-dom
teardown. Other media queries retain their normal behavior; the application theme is unchanged.

**The suite runs with `isolate: false`** (`web/vite.config.ts` — a worker reuses its module registry and
globals across files; the measured gain is in `build-times.md`, WHY 6). Vitest already scopes `vi.mock`
registrations per test file; what carries over is the EVALUATED `src/` modules, so `setup.ts` calls
`vi.resetModules()` before every file (npm packages stay cached) and each file's own mocks apply. Tests must not assume anything an earlier test left
behind — await lazy chart chunks (`findBy…`/`waitFor`, never a synchronous `getBy…` right after the first
data assertion), reset module-level state in `afterEach`, and unstub globals/timers a test installed. The
proof is `cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test
that only passes in file order is the bug, not the config.

`locales/parity.test.ts` enforces EN↔PL key parity for every shipped language (auto-discovers
language folders; also pins folders == `SUPPORTED_LANGUAGES`).

**Normalized-layer pipeline test and digests.** `NormalizationPipelineTest` asserts over the PERSISTED
`norm.*` rows, never `Tiling`/`Normalization`'s in-memory guarantees: an invariant SQL sweep over every row
(counts cross-checked against an INDEPENDENT re-derivation, never the same code path grading itself;
whole-dataset fixture figures are `<=` bounds, never equalities) plus a reprocess digest (hash the rebuilt
state, re-run, assert equal). A red digest is a real nondeterminism bug, never grounds to loosen the test.
Details: `.claude/docs/test-fixtures.md`.

**Shared synced fixture (`SyncedStubFixture`).** One full Jira SYNC of the stub per JVM fork. A test that
only READS a full backfill calls `SyncedStubFixture.connectionId()` and NEVER mutates it; a test whose subject
is REPROCESS/RECONCILE or a raw-row simulation clones (`SyncedStubFixture.cloneRawData`) and drives only the
one stream under test; a test whose subject is the sync streams keeps a real HTTP sync on the smallest path.
`SyncedStubFixtureTest` is the tripwire against a read-only test mutating the shared connection. Details:
`.claude/docs/test-fixtures.md`.

**Derived fixture (`DerivedStubFixture`).** One DERIVE per JVM fork under a pinned clock. A test that only READS
one DERIVE under the default config uses `DerivedStubFixture.connectionId()` and never mutates or re-derives it;
a test whose subject is a second derive, a config/membership/settings mutation or a raw-row simulation clones its
OWN connection (`SyncedStubFixture.cloneProcessedData`) and derives it itself. A connection a metrics test derives
itself is created DISABLED (`createConnection(enabled = false)`) so a worker cannot re-derive it with the real
clock. Never re-run PROCESS/PROFILE in a metrics test unless PROCESS is the subject. The metrics digest
(`DerivedStubFixtureTest`, `MetricsDigestTest`) is the determinism tripwire — a red digest is a real bug. `dim_date`
is global and rewritten whole by a calendar change: a test that changes the calendar restores the table in a
`finally`. A connection's newest SUCCEEDED `derive_runs` row is never pruned, so a unit-wide last-derived-day
cut-off test places its days before every pinned clock in the suite (2020 today) and deletes the runs it inserts.
Details: `.claude/docs/test-fixtures.md`.

**Runtime OpenAPI conformance.** Every `/api/` interaction in the server suite is validated against
`documentation.yaml` by the `OpenApiConformance` client plugin, installed via `jsonClient()`/`authedClient()`;
`testApplication`'s default `client` bypasses it — never use it for `/api/` calls. Drift fails the exercising test
(`-Dopenapi.conformance=warn|off` is for drift triage only). The spec stays 3.0-compatible (`nullable:`, never
`type: [..., "null"]`, `OpenApiSpecTest`). The coverage gate fails the `test` task on a non-empty
`build/reports/openapi-conformance/gaps.txt` (or a missing one) when the WHOLE suite ran, so a new operation lands
with a test per declared status or with its status list trimmed to what the route can answer; `413` is declared on
every body-taking operation but covered by the one `PayloadValidationTest` case. Details:
`.claude/docs/test-fixtures.md`.

**Schemathesis (optional manual fuzz pass, not in CI).** Never fuzz `/api/v1/logout` (it revokes the bearer
token); a `Server error` count above zero is the real signal. Recipe: `.claude/docs/test-fixtures.md`.

**E2E scenarios.** `e2e/README.md` governs the Playwright suite. Every spec has a scenario file in
`e2e/scenarios/` whose `## Scenario:` headings equal the `test()` titles verbatim; a new or behaviorally changed
test lands in the same commit with its scenario file and its coverage-map line in the e2e README;
`cd e2e && npm run check:scenarios` (parity, both directions) and `npm run typecheck` enforce it. Details:
`.claude/docs/test-fixtures.md`.
