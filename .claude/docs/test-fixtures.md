### Test fixtures and harness internals

Read on demand before touching a server test fixture, the synced/derived stub fixtures, a digest, OpenAPI-conformance internals, e2e scenario files or the Schemathesis pass; the binding one-line rules live in `.claude/docs/testing.md`.

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
  (`MetricsSettingsService.bumpRevision`) enqueues a DERIVE for every ENABLED connection, and a worker
  running inside another test's `testApplication` would re-derive it with the REAL clock between
  this test's own pinned-clock DERIVE and its assertions — a flake that only shows up in full-suite
  order.
- **Never re-run PROCESS/PROFILE in a metrics test unless PROCESS itself is the subject** —
  `cloneProcessedData` (or, for a read-only test, `DerivedStubFixture` outright) is always cheaper
  and exercises the exact same stored shape a real connection would have after its first sync.

`DerivedStubFixtureTest` is the tripwire, the same role `SyncedStubFixtureTest` plays for
`SyncedStubFixture`: it re-derives `DerivedStubFixture.metricsDigest` — an MD5 over EVERY derived
`metrics.*` table (dimensions, bridges, facts, both daily aggregates — NOT the global `dim_date`,
which any deriving test under another calendar rewrites, so the tripwire stays class-order independent; ordered by primary key, else
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
**`dim_date` is global and rewritten whole by a calendar change** (`MetricsStore.ensureDimDate`): a test that switches the time zone/weekend/holidays and derives (`DimDateContentionTest`) restores the table afterwards from a snapshot (delete every row, re-insert the snapshot with each row's original `config_revision`), and a test that needs a row the calendar would never write stamps it with `DerivedStubFixture.stampDimDate` and restores it in a `finally`.
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
non-empty file whenever the WHOLE suite ran (a `--tests` filter skips the gate). The gate is
**fork-safe**: each test JVM writes its own `exercised-<pid>-<uuid>.txt` and re-merges every fork's file
under a file lock into the ONE `coverage.md` + `gaps.txt` (`OpenApiCoverageMerge`, pinned by
`OpenApiCoverageMergeTest` — a pair exercised by ANY fork is covered, so the last fork to exit
leaves the complete union), and the `test` task clears the directory first so stale per-fork files
never leak in; a whole-suite run that leaves NO `gaps.txt` fails the gate too. A new operation
therefore lands with a test per declared status, or with its status list trimmed to what the
route can actually answer (`CoverageGapsTest` pins the cross-cutting statuses). `413` is declared on EVERY operation that takes a request body
(`OpenApiSpecTest` pins it) but is never a per-operation test — the one `PayloadValidationTest` case covers the shared
limit. Tests that use
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

**Coverage and fork measurements** (moved from `testing.md` "Coverage gates" and "Parallel forks"; the binding rules stay there).

Per-fork fixed cost is the container, Flyway, one ~25 s stub SYNC and one DERIVE — the
measured gain and the point where more forks stop paying are in `build-times.md` WHY 5.

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in
`server/build.gradle.kts` (the `minBound` line/branch floors, wired into `check` via
`koverVerify`). The measurement EXCLUDES classes annotated `@kotlinx.serialization.Serializable`
(`kover.reports.filters`): kotlinx-serialization synthesizes one branch per optional property in a
data class's generated constructor and serializer — noise no test can exercise; behavior never
lives in a wire shape (services, validators and a DTO's companion object stay measured). The
floors sit just below current actuals — the convention is to **re-measure and raise** them as
coverage improves, never to lower them for new code: `check` runs only `koverVerify`, so run
`./gradlew :server:koverXmlReport` for fresh actuals. Every fork's Kover agent writes into the one shared `test.ic` (if CI ever flakes on Kover, fall back to `-Pforks=1`), so
`-Pforks=2` measures the same code as the single fork (2026-09-30: line 98.19 % vs 98.23 %, branch 80.19 % vs
80.23 % — the ±0.04 is timing-dependent coverage, not a loss; `koverVerify` passes either way). **Kover costs ~11-14 % of the test wall and ~20 % of its CPU**
(2026-10-01, `build-times.md` WHY 4) and stays on everywhere `check` runs — the floors are a gate. There is **no
Gradle property to switch it off** (none was added); for a timing run or a tight dev loop over `:server:test` alone, temporarily add
`kover { currentProject { instrumentation { disabledForTestTasks.add("test") } } }` to `server/build.gradle.kts` and never commit
it (no `test.ic` is written, so `koverVerify` would have nothing to check). Frontend vitest enforces thresholds in
`web/vite.config.ts` (`test.coverage.thresholds`, same re-measure convention — the current
actuals are noted in a comment beside them); run `cd web && npm run test:coverage`.
