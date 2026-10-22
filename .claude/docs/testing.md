### Testing

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `testApplication`) against ONE Testcontainers
Postgres per JVM fork (`PostgresTestSupport`, started lazily, image pinned by digest — `PostgresImagePinTest`).
Running them requires a Docker daemon (OrbStack without `/var/run/docker.sock`: export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`). The container runs **all** Flyway migrations, so the V3 seed
admin (`admin@flow.local`) is present — tests scope their assertions with unique prefixes/filters
(`uniqueEmail("marker")`) rather than asserting absolute counts. Test-side direct database access is
`sharedDatabaseForTests()`. The full reference (pool rationale, every helper, forks, watchdog, Kover, fixtures,
conformance internals, Schemathesis, e2e scenarios): `.claude/docs/test-fixtures.md`.

**Parallel forks (`-Pforks=N`).** `maxParallelForks` comes from the `forks` Gradle property (an integer 1..8, anything
else fails any build that runs the `test` task; default 1; CI runs `-Pforks=2`; `forkEvery` is deliberately unset).
Each fork is its own JVM with its OWN container and fixture singletons, so every "shared suite" rule in this doc holds
per fork, never across forks: a test must never assume another class ran before it on the same JVM, or that a class
ran at all in this one. Class order is not a contract (with `-Pforks>=2` the classes are weight-balanced from
`server/test-weights.tsv`; refresh it with `node scripts/timings/local-times.mjs test-weights` after a full run).
`PostgresTestSupport` migrates the container itself on start, so a fixture or raw-JDBC helper never needs its own
"has Flyway run yet?" guard. Test classes are selected by file name (`**/*Test.class`, `TestClassNamingTest`).
**A hung fork is thread-dumped and killed, not waited for** (`-PtestStallMinutes`, 1..60, default 5; dumps in
`server/build/reports/hung-tests/`); test-side raw JDBC uses `PostgresTestSupport.connect()`, never a bare
`DriverManager.getConnection`.

**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- App: `configureApp(vararg overrides)` points the app at the shared container (CSRF off) WITHOUT starting it —
  startup/fail-closed tests call `startApplication()` themselves; `usePostgresTestcontainer()` configures and starts.
- Clients: `jsonClient()` / `authedClient(email, password)` (the shared defaults, incl. the `OpenApiConformance`
  plugin); `seededClient(prefix, role)` seeds a user and logs in.
- Users and teams: `TestUsers.seed(...)` (bcrypt cost 4; ADMIN by default — pass `FlowUserRole.USER` for a
  non-privileged caller), `TestUsers.softDelete(id)`, `TestUsers.withSoloAdmins(ids) { }` (the last-admin pin),
  `TestTeams.seed(name)`. Shared suite state (the seed admin) is never mutated destructively — tests mint
  UNIQUE rows and remove their own.
- Audit and bootstrap: `LogCapture` + `hasKeyValue`/`awaitEvent`, `withAuditCapture { }` (detach in a finally),
  `postJson`/`putJson`, `HttpClient.login`, `withSeedRestored { }`, `assertStartupFails(part) { }` — use these instead
  of re-rolling the blocks they replaced.
- **Data sources: one fixed site, unique keys for HTTP.** Every test data source is on `TEST_JIRA_SITE_URL`, a row
  created or PUT over HTTP takes `uniqueProjectKeys(n)` (`TestConnections.kt`), and the direct fixture service is
  `SyncedStubFixture.dataSources()` (rules OFF) - never a per-class `DataSourceService(...)` or a random site
  (`.claude/docs/ingestion.md` "Data sources").
- **Many failed logins → seeded accounts** (an UNKNOWN email pays a ~225 ms bcrypt verify), soft-deleted afterwards.
- **`IngestWorker.tick()` claims from the whole shared queue**, so wrap a direct tick in
  `withOnlyConnections(setOf(connId), tickClockMillis) { }`.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's password in the
  SHARED container; call it afterwards. Production-mode boots must also override `"mail.transport" to "disabled"` and
  `"security.encryption.key" to strongEncryptionKey()`; the checks fire in module order (JWT → mail → crypto → seed
  passwords), so a test asserting a later check must satisfy every earlier one.

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in `server/build.gradle.kts` (the
`minBound` floors, wired into `check` via `koverVerify`; `@kotlinx.serialization.Serializable` classes are excluded
from the measurement). The floors sit just below current actuals — **re-measure and raise** them as coverage improves
(`./gradlew :server:koverXmlReport`), never lower them for new code; a tight-loop Kover disable is never committed. Frontend vitest enforces thresholds in `web/vite.config.ts` (`test.coverage.thresholds`,
same convention); run `cd web && npm run test:coverage`.

**Static analysis (detekt).** `./gradlew detekt` rides `check`; zero findings, **no baseline file**. Tune only in
`config/detekt/detekt.yml` (one commented override per deliberate idiom); fix code first, a per-site `@Suppress` needs a
justifying comment. `SourceFileSizeTest` caps main source files at 400 lines (a new file
stays under it; a grandfathered ceiling only goes down — raising one is a reviewed exception justified in the PR).

**Frontend static analysis.** Same zero-findings/no-baseline policy: `cd web && npm run lint` (eslint + sonarjs; every
override in `web/eslint.config.js` carries its idiom comment) and `npm run knip` (test files count as entries); `ci.md`.

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** (`Foo.test.tsx` beside `Foo.tsx`);
`src/test/setup.ts` forces `en`, `src/test/render.tsx` is the shared wrapper, `src/test/http.ts` the fetch stubs. Every `MantineProvider` in a test must pass **`env="test"`** (otherwise Popover/Combobox
dropdowns stay `display: none` and Select-option clicks silently fail).

**The suite runs with `isolate: false`** (`web/vite.config.ts`): evaluated `src/` modules carry over between files
(`setup.ts` calls `vi.resetModules()` before every file). Tests must not assume anything an earlier test left
behind — await lazy chart chunks (`findBy…`/`waitFor`, never a synchronous `getBy…` right after the first data
assertion), reset module-level state in `afterEach`, and unstub globals/timers a test installed. The proof is
`cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test that only passes in
file order is the bug, not the config.

`locales/parity.test.ts` enforces EN↔PL key parity (and pins folders == `SUPPORTED_LANGUAGES`).

**Normalized-layer pipeline test.** `NormalizationPipelineTest` asserts over the PERSISTED `norm.*` rows (an invariant
SQL sweep against an INDEPENDENT re-derivation, fixture figures as `<=` bounds, plus a reprocess digest). A red digest is a
real nondeterminism bug, never grounds to loosen the test.

**Shared synced fixture (`SyncedStubFixture`).** One full Jira SYNC of the stub per JVM fork. A test that only READS
a full backfill calls `SyncedStubFixture.connectionId()` and NEVER mutates it; a test whose subject is
REPROCESS/RECONCILE or a raw-row simulation clones (`SyncedStubFixture.cloneRawData`) and drives only the stream under
test; a test whose subject is the sync streams keeps a real HTTP sync on the smallest path. **A class that reads a
shared fixture (either one) warms it in a `@BeforeClass`** — `companion object { @JvmStatic @BeforeClass fun
warmFixtures() = warmStubFixtures() }` — so the one-time Postgres + SYNC + DERIVE cost lands outside
`testApplication`'s hard 60 s limit; `StubFixtureWarmupGuardTest` fails a class that forgets.

**Derived fixture (`DerivedStubFixture`).** One DERIVE per JVM fork under a pinned clock. A test that only READS one
DERIVE under the default config uses `DerivedStubFixture.connectionId()` and never mutates or re-derives it; a test
whose subject is a second derive, a config/membership/settings mutation or a raw-row simulation clones its OWN
connection (`SyncedStubFixture.cloneProcessedData`) and derives it itself. A connection a metrics test derives itself
is created DISABLED (`createConnection(enabled = false)`) so a worker cannot re-derive it with the real clock. Never
re-run PROCESS/PROFILE in a metrics test unless PROCESS is the subject. A red metrics digest (`DerivedStubFixtureTest`, `MetricsDigestTest`) is a real bug. `dim_date` is global: a test that
changes the calendar restores the table in a `finally`. The `derive_runs` pruning rule for cut-off tests: `test-fixtures.md`.

**Runtime OpenAPI conformance.** Every `/api/` interaction in the server suite is validated against
`documentation.yaml` by the `OpenApiConformance` client plugin, installed via `jsonClient()`/`authedClient()`;
`testApplication`'s default `client` bypasses it — never use it for `/api/` calls. Drift fails the exercising test
(`-Dopenapi.conformance=warn|off` is for drift triage only). The spec stays 3.0-compatible (`nullable:`, never
`type: [..., "null"]`, `OpenApiSpecTest`). The coverage gate fails the `test` task on a non-empty
`build/reports/openapi-conformance/gaps.txt` (or a missing one) when the WHOLE suite ran, so a new operation lands
with a test per declared status, or its list trimmed to what the route can answer — except the cross-cutting
`400/401/413/415/429/500/default` (`CROSS_CUTTING_STATUSES`).

**Schemathesis** (optional manual fuzz, not in CI): never fuzz `/api/v1/logout` (it revokes the bearer token).

**E2E scenarios.** `e2e/README.md` governs the Playwright suite. Every spec has a scenario file in
`e2e/scenarios/` whose `## Scenario:` headings equal the `test()` titles verbatim; a new or behaviorally changed
test lands in the same commit with its scenario file and its coverage-map line in the e2e README;
`cd e2e && npm run check:scenarios` (parity, both directions) and `npm run typecheck` enforce it.
