### Testing

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `testApplication`) against ONE Testcontainers
Postgres per JVM fork (`PostgresTestSupport`, started lazily, image pinned by digest — `PostgresImagePinTest`).
Running them requires a Docker daemon; with OrbStack and no `/var/run/docker.sock`, export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`. The container runs **all** Flyway migrations, so the V3 seed
admin (`admin@flow.local`) is present — tests scope their assertions with unique prefixes/filters
(`uniqueEmail("marker")`) rather than asserting absolute counts. Test-side direct database access is
`sharedDatabaseForTests()`. The setup reference (pool rationale, the full harness): `.claude/docs/test-fixtures.md`
"Server test setup, forks and the `TestEnvironment.kt` harness".

**Parallel forks (`-Pforks=N`).** `maxParallelForks` comes from the `forks` Gradle property (an integer 1..8, anything
else fails any build that runs the `test` task; default 1; CI runs `-Pforks=2`; `forkEvery` is deliberately unset).
Each fork is its own JVM with its OWN container and fixture singletons, so every "shared suite" rule in this doc holds
per fork, never across forks: a test must never assume another class ran before it on the same JVM, or that a class
ran at all in this one. Class order is not a contract. `PostgresTestSupport` migrates the container itself on start,
so a fixture or raw-JDBC helper never needs its own "has Flyway run yet?" guard. The OpenAPI gate below merges the
forks' coverage (cost and gain: `build-times.md` WHY 5).

**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup (per-helper reference:
`.claude/docs/test-fixtures.md`):

- App: `configureApp(vararg overrides)` points the app at the shared container (CSRF off) WITHOUT starting it —
  startup/fail-closed tests call `startApplication()` themselves; `usePostgresTestcontainer()` configures and starts.
- Clients: `jsonClient()` / `authedClient(email, password)` (the shared defaults, incl. the `OpenApiConformance`
  plugin); `seededClient(prefix, role)` seeds a user and logs in.
- Users and teams: `TestUsers.seed(...)` (bcrypt cost 4; ADMIN by default — pass `UserRole.USER` for a
  non-privileged caller), `TestUsers.softDelete(id)`, `TestUsers.withSoloAdmins(ids) { }` (the last-admin pin),
  `TestTeams.seed(name, memberIds)`. Shared suite state (the seed admin) is never mutated destructively — tests mint
  UNIQUE rows and remove their own.
- Audit and bootstrap: `LogCapture` + `hasKeyValue`/`awaitEvent`, `withAuditCapture { }` (detach in a finally),
  `postJson`/`putJson`, `HttpClient.login`, `withSeedRestored { }`, `assertStartupFails(part) { }` — use these instead
  of re-rolling the blocks they replaced.
- **Many failed logins → seeded accounts.** A login for an UNKNOWN email pays a discarded cost-12 bcrypt verify
  (~225 ms); a test that needs a dozen failures (rate limit, proxy trust) logs in as freshly seeded accounts with a
  wrong password (~1 ms, `build-times.md` WHY 11) and soft-deletes them afterwards.
- **`IngestWorker.tick()` claims from the whole shared queue**, so a direct tick runs whatever other classes left
  pending — wrap it in `withOnlyConnections(setOf(connId), tickClockMillis) { }`.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's password in the
  SHARED container; call it afterwards. Production-mode boots must also override `"mail.transport" to "disabled"` and
  `"security.encryption.key" to strongEncryptionKey()`; the checks fire in module order (JWT → mail → crypto → seed
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

**Static analysis (detekt).** `./gradlew detekt` rides `check`; the gate is zero findings with **no baseline file**.
Tune only in `config/detekt/detekt.yml`, one commented override per deliberate repo idiom; fix new findings in code
first, prefer a config override over `@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in
seconds, no Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** Same zero-findings/no-baseline policy: `cd web && npm run lint`
(eslint + `eslint-plugin-sonarjs`; every override in `web/eslint.config.js` carries the idiom comment) and
`cd web && npm run knip` (dead code; test files count as entries). Details: `.claude/docs/ci.md`.

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** (`Foo.test.tsx` beside `Foo.tsx`);
`src/test/setup.ts` forces `en`, `src/test/render.tsx` is the shared wrapper and `src/test/http.ts` holds the
fetch-stubbing helpers. Every `MantineProvider` in a test — the shared wrapper and any file-local one — must pass
**`env="test"`** (otherwise the Popover/Combobox dropdown stays `display: none` in happy-dom and Select-option clicks
silently fail). Reduced-motion rationale: `.claude/docs/test-fixtures.md` "Frontend test internals".

**The suite runs with `isolate: false`** (`web/vite.config.ts`; the gain: `build-times.md` WHY 6): evaluated `src/`
modules carry over between files (`setup.ts` calls `vi.resetModules()` before every file). Tests must not assume
anything an earlier test left behind — await lazy chart chunks (`findBy…`/`waitFor`, never a synchronous `getBy…`
right after the first data assertion), reset module-level state in `afterEach`, and unstub globals/timers a test
installed. The proof is `cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test
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
