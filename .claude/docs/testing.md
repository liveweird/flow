### Testing

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `io.ktor.server.testing.testApplication`)
and override the `postgres.*` config keys via `MapApplicationConfig` to point at a Testcontainers
`PostgreSQLContainer` (`postgres:18.6-alpine`, digest-pinned in `PostgresTestSupport`) started lazily by `PostgresTestSupport` and **shared
across the whole suite** (test-side direct database access, `sharedDatabaseForTests()`, goes through a small
r2dbc-pool built by production's own `connectPooledDatabase` — an unpooled connect paid ~4 ms of backend
setup per transaction, `.claude/docs/build-times.md` WHY 10). Running tests requires a working Docker daemon (Docker Desktop,
OrbStack, etc. — with OrbStack and no `/var/run/docker.sock`, export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`). The container runs **all** Flyway
migrations, so the V3 seed admin (`admin@flow.local`) is present — tests scope their assertions
with unique prefixes/filters (`uniqueEmail("marker")`) rather than asserting absolute counts.

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
(`kover.reports.filters`): kotlinx-serialization synthesizes one branch per optional property in a
data class's generated constructor and serializer — noise no test can exercise; behavior never
lives in a wire shape (services, validators and a DTO's companion object stay measured). The
floors sit just below current actuals — the convention is to **re-measure and raise** them as
coverage improves, never to lower them for new code: `check` runs only `koverVerify`, so run
`./gradlew :server:koverXmlReport` for fresh actuals. Frontend vitest enforces thresholds in
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

**The normalized-layer pipeline test (invariant SQL sweep + reprocess digest).**
`NormalizationPipelineTest` (v0.2.0 plan §8/§11, `.claude/docs/ingestion.md` "Normalized layer")
asserts over the PERSISTED `norm.*` rows produced by a SYNC/RECONCILE/REPROCESS against the shared
`JiraStubServer` fixture, not `Tiling`/`Normalization`'s own in-memory guarantees (`TilingTest`
already covers those exhaustively) — the DB is the thing that has to be right. Two patterns worth
reusing for any other derived/rebuilt table set:

- **The invariant SQL sweep.** Rather than asserting one golden fixture value, read back EVERY
  persisted row for a connection (`WorkItemStore.statusIntervalsByIssue`) and re-check the domain's
  own invariant list — contiguous `seq`, the first interval starting at `created_at`, exactly one
  open interval per issue — against all 1,200 in-scope issues at once ("PROCESS after backfill
  produces work items whose persisted intervals satisfy every invariant"). Where a count matters
  (reopens), cross-check it against an INDEPENDENT re-derivation computed straight from
  `raw.jira_issues`/`raw.jira_changelogs` using a hand-maintained, separately-sourced lookup table
  (`EXPECTED_STATUS_CATEGORY`, never `Tiling`/`JiraNormalizer`'s own code path) — never trust the
  same code path to grade its own homework twice. Treat a whole-dataset fixture figure
  (`sample-data/jira/expected.json`, which includes the out-of-scope `SEC` project) as a
  plausibility BOUND on the in-scope subset (`<=`), never an exact-equality assertion, since no
  correct in-scope sync can ever reach a whole-dataset number.
- **The reprocess digest.** `statusIntervalDigest` MD5-hashes every issue's ordered persisted
  status intervals (issue id, seq, status id, `from_at`/`to_at`, source) into one string, taken
  once after the initial SYNC and again after a REPROCESS run over the SAME connection; asserting
  the two digests are EQUAL proves REPLACE is idempotent — byte-for-byte identical rows, not merely
  "doesn't crash (or duplicate) the second time" ("REPROCESS leaves the normalized digest
  unchanged"). This is the general pattern for testing an idempotent rebuild/replace pipeline: hash
  the rebuilt state, re-run the rebuild, hash again, assert equality — cheaper and more precise than
  comparing row counts alone, which would miss a REPLACE that silently reordered or reworded rows.

**Shared synced fixture (`SyncedStubFixture`, `server/src/test/kotlin/SyncedStubFixture.kt`).**
`NormalizationPipelineTest`, `JiraSyncPipelineTest` and `DataProfileTest` each used to drive a
COMPLETE `JiraConnector` SYNC of the ~1,200-issue stub from scratch per test — most of them only to
READ the result, at ~30s a sync. `SyncedStubFixture.connectionId()` runs that same backfill (REFERENCE
→ ISSUES → CHANGELOGS → WORKLOGS → PROCESS → PROFILE) exactly ONCE per JVM fork (a `Mutex`-guarded
lazy init) and returns its connection id; whichever of those three classes' tests happens to run
first pays that one-time cost, every later READ-ONLY test across all three classes reuses the SAME
connection instead of syncing its own. The rule going forward:

- **A test that only READS the result of a full backfill** (an invariant sweep, a count, the data
  profile) calls `SyncedStubFixture.connectionId()` and reads straight from it — never mutates it.
- **A test whose SUBJECT is REPROCESS, RECONCILE, or a raw-row simulation (a `processing_version`
  downgrade, an index gap)** must never touch the shared connection — `SyncedStubFixture
  .cloneRawData(fromConnectionId, toConnectionId)` copies `raw.jira_issues`/`raw.jira_entities`/
  `raw.jira_changelogs`/`raw.jira_worklogs` verbatim (including `needs_processing`/
  `processing_version`/the tombstone columns) into a fresh connection id with ONE SQL
  `INSERT … SELECT` per table (the columns come from the Exposed table objects and an
  `information_schema` check fails the clone if they ever differ from the database's, so a new column
  cannot be silently dropped; surrogate SERIAL ids are not copied) — cheap, no HTTP — and the test
  drives only the ONE stream under test (`JiraProcessStream`, `JiraReconcileStream`,
  `JiraWorklogStream`, …) directly against that clone, the same production code a real job would run,
  just without the surrounding streams that already ran once to produce the shared fixture.
- **A test whose SUBJECT is the sync streams themselves** (cursor resume, `CURSOR_EXPIRED`, lease
  loss, the bulkfetch fallback, the day2 incremental/worklog feed) keeps a REAL HTTP sync — but
  drives the smallest path that exercises it: a single stream directly where the fixture already
  established the raw prerequisites (`JiraIssuesStream` alone for cursor mechanics — no `raw.*` rows
  needed at all; a clone plus the ONE stream under test — RECONCILE, WORKLOGS — where the prior
  streams' output is a prerequisite, not the subject).

`SyncedStubFixtureTest` is the tripwire: it re-snapshots the shared connection's raw/norm row
counts and its status-interval digest and compares them against the baseline `SyncedStubFixture`
captured the moment its own backfill first completed — a read-only test that started mutating the
shared connection by mistake fails this test, not silently corrupts every other test sharing it.
Effect: these three classes' combined runtime fell from ~590s (one full sync per test, ~30 of them)
to under 2 minutes (one full sync, plus a handful of cheap clones and small real-HTTP stream runs).

**The derived fixture (`DerivedStubFixture`, `server/src/test/kotlin/DerivedStubFixture.kt`).**
`MetricsDerivationTest` used to clone `SyncedStubFixture`'s raw rows and re-run PROCESS → PROFILE
(`jira/JiraProcessStream.kt`/`jira/JiraProfileStream.kt`, ~20s) for EVERY test, most of them only to
READ a single DERIVE's result. `SyncedStubFixture.cloneProcessedData(fromConnectionId,
toConnectionId)` extends `cloneRawData` with the ALREADY-PROCESSED `norm.*` rows (work items,
status/field intervals, field changes, worklogs, the rebuilt-wholesale reference tables) plus
`source_connections.profile`/`profile_at`, copied verbatim (surrogate `SERIAL id` columns excluded,
every other column as-is) — so a test needing a processed connection never re-runs PROCESS/PROFILE
at all, whether or not it goes on to derive. `DerivedStubFixture` builds on that: it clones once,
maps the FLO board (id 1) to one freshly seeded team, and derives ONCE under a pinned clock and
`hoursPerDay = 8.0`, exactly once per JVM fork (the same `Mutex`-guarded lazy-init shape as
`SyncedStubFixture.connectionId()`). The rule going forward, one layer up the pipeline from
`SyncedStubFixture`'s own rule above:

- **A test that only READS the result of a single DERIVE under the DEFAULT per-connection config**
  (no config/membership/settings mutation, one derive only) calls `DerivedStubFixture.connectionId()`
  and reads straight from it — never mutates it, never derives again.
- **A test whose SUBJECT is a second derive, a config/membership/settings mutation, a sprint/team
  membership setup, or a raw-row simulation** must never touch the shared derived connection — it
  clones its OWN connection via `SyncedStubFixture.cloneProcessedData` (PROCESS/PROFILE-free, same
  as any other processed-clone test) and derives it itself as many times as the test needs.
- **A connection a metrics test derives itself is created DISABLED**
  (`SyncedStubFixture.createConnection(enabled = false)`): every config change anywhere in the suite
  (`MetricsConfigService.bumpRevision`) enqueues a DERIVE for every ENABLED connection, and a worker
  running inside another test's `testApplication` would re-derive it with the REAL clock between
  this test's own pinned-clock DERIVE and its assertions — a flake that only shows up in full-suite
  order.
- **Never re-run PROCESS/PROFILE in a metrics test unless PROCESS itself is the subject** —
  `cloneProcessedData` (or, for a read-only test, `DerivedStubFixture` outright) is always cheaper
  and exercises the exact same stored shape a real connection would have after its first sync.

`DerivedStubFixtureTest` is the tripwire, the same role `SyncedStubFixtureTest` plays for
`SyncedStubFixture`: it re-derives `DerivedStubFixture.metricsDigest` — an MD5 over EVERY derived
`metrics.*` table (dimensions, bridges, facts, both daily aggregates — NOT the global `dim_date`,
which any deriving test re-stamps, so the tripwire stays class-order independent; ordered by primary key, else
by every hashed column; the surrogate `id`, `derive_runs` and the snapshot's `snapshot_at`/
`reconstructed` bookkeeping excluded; the reprocess-digest pattern above, applied to invariant 12)
— and compares it against the baseline captured the moment the fixture's own DERIVE first
completed. **The metrics digest joins the reprocess-digest pattern:** `MetricsDigestTest` derives a
private disabled clone twice (same clock, same `config_revision`, both inside ONE
`withMetricsSettings` block — each wrapper call would bump the revision every derived row carries)
and asserts identical digests plus an unchanged `fact_sprint_snapshot` row count, then does the same
across a REPROCESS (`markAllNeedsProcessing` + the PROCESS stream over the clone's raw rows), and
proves the digest is sensitive (one nudged value / one deleted bridge row changes it). Only that
test opts into `includeDimDate`. A red
digest is a real nondeterminism bug in the deriver, never grounds to loosen the test.
**A connection's newest SUCCEEDED `derive_runs` row is never pruned** (it is the snapshot reports' DERIVE clock), so a derive under a pinned PAST clock stays that connection's clock for the rest of the suite — including `IngestWorkerTest`'s 2024-01-01 worker clock, which derives whatever DERIVE jobs other tests left pending. A test of the unit-wide last-derived-day cut-off (no `connectionId`, so every connection is in scope) must therefore place its days before every pinned clock in the suite (2020 today), and a test that inserts its own runs deletes them afterwards.
`SyncedStubFixtureTest` also pins `cloneProcessedData` itself: a processed clone's
status-interval digest must equal the source connection's. Effect: `MetricsDerivationTest`'s own
runtime fell from ~324s (one full clone-and-reprocess per test, 16 of them) to well under a
minute.

**Runtime OpenAPI conformance.** Every `/api/` interaction the server test suite produces is
validated against `documentation.yaml` by the `OpenApiConformance` Ktor client plugin
(`server/src/test/kotlin/OpenApiConformance.kt`), installed via the shared test-client defaults —
so `jsonClient()`/`authedClient()` traffic is checked automatically and drift (undeclared
endpoint/method/status, response-schema or content-type mismatch) fails the exercising test with
the validation report. Response side is fully validated; request-side validation is ignored except
unknown-path/method (tests deliberately send malformed payloads and missing tokens). The spec is
fed to the validator relabeled 3.0.3 in memory (swagger-request-validator's 3.1 support is
unreliable); `OpenApiSpecTest` pins that the document stays 3.0-compatible (use `nullable:`, never
`type: [..., "null"]`) plus static invariants (unique operationIds, 401/500 declared, paths under
`/api/v1/`). `-Dopenapi.conformance=warn|off` relaxes enforcement for drift triage only (default
`fail`). A coverage report (exercised vs. declared operation/status pairs) is written to
`server/build/reports/openapi-conformance/coverage.md` after each test run, and it is a GATE: the
sibling `gaps.txt` lists every declared (operation, status) pair the suite never produced — minus
the cross-cutting statuses a shared plugin answers for every route alike and one test pins each
(`400` malformed id/body, `401`, `413`, `415`, `429`) and `500`/`default`, which the public API
offers no honest way to force — and `server/build.gradle.kts` fails the `test` task on a
non-empty file whenever the WHOLE suite ran (a `--tests` filter skips the gate). A new operation
therefore lands with a test per declared status, or with its status list trimmed to what the
route can actually answer (`CoverageGapsTest` pins the cross-cutting statuses). Tests that use
`testApplication`'s default `client` bypass the plugin — prefer `jsonClient()`.

**Schemathesis (optional manual fuzz pass, not in CI).** Property-based fuzzing of the running
stack from the spec: `docker compose up --build` (compose ships dev mode, so `/openapi` is
exposed), grab a token —
`TOKEN=$(curl -s -X POST localhost:8084/api/v1/login -H 'Content-Type: application/json' -d '{"email":"admin@flow.local","password":"changeme"}' | jq -r .token)`
— then
`uvx schemathesis run -c all -H "Authorization: Bearer $TOKEN" --exclude-path /api/v1/logout http://localhost:8084/openapi/documentation.yaml --url http://localhost:8084`.
The `/logout` exclusion is load-bearing: fuzzing it **revokes the bearer token** (everything after
401s). Login fuzzing also trips the per-account lockout for `admin@flow.local` (the spec's
example email) — in-memory, so `docker compose restart app` clears it. Expect residual noise from
stateful invariants the spec cannot express (rate-limit 429s, TRACE probes); a **`Server error`
count above zero is the real signal**. It complements, not replaces, the suite-piggybacked
conformance layer above; fuzz junk lives only in the compose volume (`docker compose down -v`
resets). Needs `uv` (or `pipx`); no Python dependency lives in the repo.

**E2E scenarios (design artifacts).** The Playwright suite in `e2e/` is governed by
`e2e/README.md` (run recipes, the parallel state-ownership rulebook, the coverage map). Every spec
has a **natural-language scenario file** in `e2e/scenarios/` — versioned, deliberately
non-executable design artifacts (actors, owned state, numbered user-level steps, expected
outcomes; `## Scenario:` headings equal the `test()` titles verbatim). `e2e/scenarios/README.md`
holds the format and the **compiler contract** — the house rules any human/agent/tool must
satisfy when turning a scenario into spec code. Same-commit rule: a new or behaviorally changed
test lands with its scenario file and its one-line entry in the e2e README's coverage map;
`cd e2e && npm run check:scenarios` enforces the parity mechanically (both directions, orphan
files included), and `npm run typecheck` covers what Playwright's transpile-only TS handling
never checks. CI also runs `cd e2e && npm run test:setup`; the setup tests cover reuse of a
healthy stack, startup of the unavailable default stack, and the invariant that services and
volumes remain intact. E2E global setup reuses a healthy stack, starts the default stack when
unavailable, and leaves services and volumes intact; there is no global teardown.
