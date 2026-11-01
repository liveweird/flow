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

**Trimmed payloads and the stub.** The stub's issue, changelog and worklog payloads are already trim-clean: an assignee/reporter is `{accountId, displayName}`,
an author `{accountId}`, and they carry no comment, description, `self` or description/remote-link item, so the write-path trim (`jira/JiraPayloadTrim.kt`) leaves their
stored bytes - and every fixture/norm/metrics digest - unchanged. `JiraPayloadTrimTest` therefore proves the equivalence property over every raw row of the shared
synced fixture with the dropped keys INJECTED (normalizing the trimmed injected documents gives exactly the rows of the stored ones), and
`JiraUserReferenceSlimmingTest` scripts a full-user, dropped-keys answer for one private issue id (a WireMock override, removed in `@AfterTest`) to prove the
ISSUES, CHANGELOGS and WORKLOGS streams store it trimmed.

**The GitLab stub (`GitLabStubServer`, `GitLabStubServerTest`).** `server/src/test/kotlin/GitLabStubServer.kt` is `JiraStubServer`'s twin over
`sample-data/gitlab-stub` (a `by lazy` WireMock singleton, request journal disabled, `resetScenarios`/`setScenarioState`/`addOverride`/`removeOverride`,
the `gitlab-day2` scenario name as a constant). `GitLabStubServerTest` drives it with plain `java.net.http` (no app, no Postgres) and takes every
figure from `sample-data/gitlab/expected.json`; a test that flips `gitlab-day2` resets it in a `finally` and a `@Before` resets it for the next. The
generator's own checks are `node --test sample-data/gitlab/generate.test.mjs` (determinism, the committed stub equals a fresh run, every diff counts
to its ground truth, the Jira-email alignment against `jira/expected.json`'s team roster): run it after any generator change and commit the
regenerated `gitlab-stub/` and `gitlab/expected.json`. **The GitLab data-source tests (1.34.0).** `GitLabFixtures` (`GitLabTestSupport.kt`) is the GitLab twin of `SyncedStubFixture`'s plumbing: `dataSources()` (a
direct `DataSourceService` with `ScopeRules.OFF` and a base-URL policy that accepts the stub, since the production policy is `configureGitLab`'s),
`createConnection(...)` (a DISABLED connection on `GitLabStubServer.baseUrl` with groups `platform`/`apps`), `connector(...)` over a plain OkHttp
client, and `run(connector, connId, kind, onDemand, progress)`; the connection must be removed by its test (`purgeAll` then `delete`). An app test
that needs GitLab on boots with `configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())` (the dev stub's origin is then the ONLY base URL the
policy accepts) or `"gitlab.allowedHosts" to "gitlab.example.com"` (any `https://` origin on the list, never called); the default app has GitLab OFF
(`gitlab-disabled`). The classes: `GitLabClientTest` (MockEngine: `PRIVATE-TOKEN`, `X-Next-Page`, the `%2F` pin, the rate-limit pause, per-base-URL
pauses), `GitLabRawStoreTest` (the V41 diff rule, sweeps, PURGE), `GitLabConnectorTest` (Test connection rows and a SYNC against the in-JVM stub,
ground truth `expected.json`), `GitLabDataSourceRoutesTest` (the wire, `/kinds`, the allow-list, GitLab off, audits), `GitLabScopeTest`,
`SourceHttpHeadersTest` (the `fetch/` additions). Group paths over HTTP take `grp-<8 hex>` so the overlap rule never depends on another class's rows.
**`SyncedGitLabFixture` (PR 7, 1.35.0)** is the GitLab connector fixture: ONE full SYNC of the stub (all six GitLab streams) per fork, `connectionId()`/
`assertUnchanged()`, a tripwire of 300 live commits, 60 live merge requests and no pending commit files or merge-request details re-checked on every call, and a `fixture-cost\tgitlab-sync\t<ms>` marker
(`build-times.md` WHY 54/55). It has its OWN warm-up, `warmGitLabFixture()` (`TestEnvironment.kt`), not `warmStubFixtures()`: a class that reads it declares
`companion object { @JvmStatic @BeforeClass fun warmFixtures() = warmGitLabFixture() }` and `StubFixtureWarmupGuardTest` checks every fixture a class triggers against ITS warm-up
call (a list of trigger/warm pairs). After the SYNC the fixture's data source is SOFT-DELETED and stamped purged (`recordPurgeSucceeded`, so the scheduler's `dueForPurge` never selects it): every active GitLab source must read one instance, so a permanent
source on the stub would 409 every other-instance create of `GitLabScopeTest` in the same fork; the raw rows stay and readers use the raw store by connection id.
A test that mutates (a second SYNC, `day2`, a head or budget change) creates its own connection with `GitLabFixtures.createConnection`. The classes: `GitLabCommitsStreamTest`
(the fixture read-only against `expected.json`, then own-connection RANGE, day-2 force-push and rename, the call budget and the terminal outcomes), `GitLabCommitsWalkTest` (a scripted
`FakeClient` (`GitLabFakeClient.kt`): modes and decisions, the resumable cursor, the re-list sweep with its `merge_base` confirmations and cap, the files stream's cap, skip budget and
call budget), `GitLabCommitStoreTest` (the V42 store), `GitLabDiffCounterTest`, `GitLabTimeTest`. PR 8 adds `GitLabMrWalkTest` (a scripted `FakeClient`: the MERGE REQUESTS listing - the resumable per-group pass, the watermarks and overlap, skipped groups and merge requests, the ceiling), `GitLabMrDetailsTest` (the same fake: the per-merge-request transaction and its kill points, the GraphQL gap rules, the notes cap, the terminal outcomes, the skip and call budgets, the failures that must propagate), `GitLabMrStreamsTest` (the fixture read-only against `expected.json` - rows, events, diff stats, the privacy check against the stub's descriptions and note bodies - then own connections running only PROJECTS, the listing and the details: the day-2 incremental, the call budget, a GraphQL outage, a notes 404, an oversized notes page) and `GitLabMrNotesTest`/`GitLabMrStoreTest` (A2/A3). `FakeClient` gained the merge-request lambdas and call records (`listedMrs`, `graphqlCalls`, `restCalls`). `GitLabFixtures` gained `freshContext(connId)` and `runStream(stream, connId)`. PR 9 adds `GitLabReconcileTest` (the fake client: the moved-out project probe, the re-list mode and its cursor, the candidate rule and every item of the wrong-tombstone checklist, the project pre- and post-probes, the cap and its rotation, the stats re-ask, the retries, the durable-change accounting; `FakeClient` gained `projectOf`/`mergeRequestOf` and `projectProbes`/`mrProbes`) and `GitLabReconcileStreamsTest` (the stub through the connector's RECONCILE, own connections only and no fixture: the Started state is PROJECTS + COMMITS + MERGE REQUESTS through `runStream`; day two, a deleted project, the day-zero no-op, the stats refill, `Reconcile now` and the oversized stamp). `GitLabCommitsWalkTest` also pins the commits reconcile mode.

**The GitLab code layer's fixtures (PR 10, 1.37.0).** `SyncedGitLabFixture`'s SYNC now ends with PROCESS and PROFILE, so it also carries the `norm.code_*` tables (five at V44, six since V46's `code_mr_issue_keys`), the `code_process` cursor and a stored profile; `assertUnchanged` adds 300 live norm commits, 60 live norm merge requests and `countNeedsProcessing == 0`. `cloneRawData(from, to)` copies the six `raw.gitlab_*` tables (parents before children) and `cloneProcessedData` also the norm tables (`PROCESSED_CLONE_TABLES`, incl. `code_mr_issue_keys`), the profile columns and the `code_process` cursor row (without it the clone's first PROCESS would re-flag everything); both go through `CloneSupport` (`CloneSupport.kt`: the `INSERT ... SELECT` mechanics lifted verbatim from `SyncedStubFixture`, column set proven equal to the database's, one transaction). `GitLabFixtures` gains `processStore()`, `codeStore()`, `purgeAll(connId)` (raw AND code: the cleanup of every test whose connector run produced norm rows) and `codeDigest(connId)` (an MD5 over every `norm.code_*` row in primary-key order, `processed_at` excluded: REPROCESS must equal the incremental state, and a red digest is a real bug); `GitLabCodeTables` lists the eleven tables with counts, a row-text dump and `information_schema` columns. The classes: `GitLabProcessStreamTest`, `GitLabProfileTest`, `GitLabPurgeTest`, `GitLabRawPayloadWhitelistTest` (the privacy whitelist; a harvested stub text equal to a stored TITLE is not a leak), `GitLabNormalizerTest` (no database) and `CodeNormalizationTest`. The stub's merge-request titles and branches carry deterministic issue-key edge cases (`sample-data/gitlab/lib/keycases.mjs`, 1.45.0; `sample-data/README.md` "Issue keys"): `expected.json` `mergeRequests.issueKeyCandidates` is the oracle `GitLabProcessStreamTest` and `GitLabProfileTest` compare the persisted rows and the profile counts with. A poisoned page is made with the `ProcessNormalizers` seam (a lambda that throws), not a row no real source produces.

**The identity mapping's tests (PR 11, 1.38.0).** `CodeIdentityRoutesTest` warms BOTH fixtures in its `@BeforeClass` (`warmStubFixtures()` puts the stub's 30 Jira people into `norm.people`, `warmGitLabFixture()` the nine code identities; the proposals case is the end-to-end alignment check against `expected.json`) and creates its OWN GitLab connection on the in-JVM stub origin: ACTIVE (the routes only skip soft-deleted rows) and DISABLED (no worker syncs it), filled by `SyncedGitLabFixture.cloneProcessedData` (a test that needs a second source on the host clones again). The links are keyed by the stub's host (`sourceHostOf(GitLabStubServer.baseUrl)`), so the class empties that host through `CodeIdentityLinkStore.deleteHost` before and after every test and in `@AfterClass`, and removes its connections with `GitLabFixtures.purgeAll` + `dataSources.delete`; it seeds and soft-deletes its own admin. `CodeIdentityServiceTest`, `CodeIdentityLinkStoreTest`, `CodeIdentityProposalMatchesTest` and `IdentityResolverTest` need no fixture: direct rows under a per-test token and a unique host, emptied afterwards (the people of every ACTIVE connection are proposal candidates, so addresses and account ids carry the token). The two insert races are pinned deterministically: a raw `PostgresTestSupport.connect()` transaction holds a decision INSERT uncommitted until the service blocks on it, then commits (`CodeIdentityServiceTest`, bounded by a timeout so a hang fails fast). `CodeIdentityProposalsTest` is pure.

**The post-PROCESS ANALYZE's tests (1.38.1, build-times WHY 60).** `NormAnalyzeTest` (the rule on a scratch table's catalog - the scratch table has a `connection_id` column, so `pg_stats` and `n_mod_since_analyze` are exercised for chosen ids; the Jira wiring on a `cloneRawData` clone) and `NormAnalyzeGitLabTest` (the GitLab twin on a `SyncedGitLabFixture.cloneRawData` clone, warming its own fixture) share `NormAnalyzeSupport`: `analyzeCounts` reads `pg_stat_user_tables.analyze_count` (MANUAL analyzes only - autovacuum has its own counter, so it cannot move what a test compares; note the counter is not transactional, a table reached before a lock timeout still counts) and `holdingAnalyzeLock` keeps `SHARE UPDATE EXCLUSIVE` on a table from a second `PostgresTestSupport.connect()` session. The shared database's statistics describe whatever the fork's earlier classes left, so a test that needs the ANALYZE ATTEMPTED settles the statistics with `NormAnalyzeSupport.settle` first (the clone's connection is then absent from the most common values, so the ANALYZE is attempted whatever the fork left; `NormAnalyzeSettings` through the store's constructor shortens `lockMs` for the timeout case), and a test that asserts NO ANALYZE settles `quiet = true`, which first makes every pooled connection report its pending table counters (`flushPooledStatistics`: PostgreSQL reports at most once a second per session, else at the idle timeout up to 10 s later, and a report landing after the settle would count as new modifications; the flush calls `pg_stat_force_next_flush()` - PostgreSQL 15+, the CALLING session reports everything it holds at its next transaction end - in one transaction on each of the pool's ten connections, a barrier holding all ten open at once so they are ten DISTINCT connections: no wait, a few milliseconds; it replaced a 1.2 s sleep plus ten `pg_sleep(0.3)` transactions, 1.5 s per call). The scratch-table tests need no flush at all: they write through short raw `PostgresTestSupport.connect()` sessions, and a session that closes reports everything on exit. The PROCESS wiring tests flag only 150 of the clone's 1,201 issues (`WIRING_ISSUES`): the ANALYZE wiring is the subject, not the volume (a full PROCESS is 0.7-1.0 s, 150 issues 0.1 s), and the lock-timeout cases wait 100 ms for the foreign lock, not 300 ms. The statement-budget case still holds its lock for 1 s: the budget is the transaction's `queryTimeout` in WHOLE seconds (the only knob that may set the statement timeout, `persistence.md` "Statement timeouts"), so 1 s is the smallest budget there is; a test that pins a PROCESS's whole counter map compares through `StreamContext.progressWithoutNormAnalyze()`, because whether a PROCESS ends with `normAnalyzed` depends on that state.

**Shared synced fixture (`SyncedStubFixture`, `server/src/test/kotlin/SyncedStubFixture.kt`).**
`NormalizationPipelineTest`, `JiraSyncPipelineTest` and `DataProfileTest` each used to drive a
COMPLETE `JiraConnector` SYNC of the ~1,200-issue stub from scratch per test — most of them only to
READ the result, at ~30s a sync. `SyncedStubFixture.connectionId()` runs that same backfill (REFERENCE
→ ISSUES → CHANGELOGS → WORKLOGS → PROCESS → PROFILE) exactly ONCE per JVM fork (a `Mutex`-guarded
lazy init) and returns its connection id; whichever of those three classes' tests happens to run
first pays that one-time cost, every later READ-ONLY test across all three classes reuses the SAME
connection instead of syncing its own. The rule going forward:

- **Warm the fixtures in a `@BeforeClass`.** Every test class that reads `SyncedStubFixture` or `DerivedStubFixture`
  (`connectionId()`/`assertUnchanged()`, or `FrozenScopeFixtures.derivedDisabledClone`, which clones the shared connection)
  declares `companion object { @JvmStatic @BeforeClass fun warmFixtures() = warmStubFixtures() }`
  (`TestEnvironment.kt`; `import org.junit.BeforeClass`; a `private companion object` works — the `@JvmStatic` method is
  still emitted public static). It runs `DerivedStubFixture.connectionId()`, i.e. Postgres + migrate + the shared SYNC + the
  shared DERIVE, once per fork (idempotent and cheap afterwards). Why: Ktor's `testApplication` runs its body under
  `runTestWithRealTime` with a hard-coded 60 s wall-clock limit, and the fixtures are lazy — whichever test touched them
  first paid the whole cost INSIDE that limit. CI run 39034284450 (PR #202, weight-balanced forks, so every fork opens
  with a heavy class): `ReportDrillSprintScopeTest` paid Postgres 15.5 s + SYNC 22.6 s + DERIVE 6.1 s plus its own body
  and died at 60.7 s with `UncompletedCoroutinesError`. JUnit 4 does not time a `@BeforeClass`, so the cost now lands in
  class-level setup, whatever order the fork runs its classes in. `StubFixtureWarmupGuardTest` enforces it (a source scan,
  `TestClassNamingTest`-style) and also fails a non-test helper that would hide a trigger from it.
- **A test that only READS the result of a full backfill** (an invariant sweep, a count, the data
  profile) calls `SyncedStubFixture.connectionId()` and reads straight from it — never mutates it.
- **A test whose SUBJECT is REPROCESS, RECONCILE, or a raw-row simulation (a `processing_version`
  downgrade, an index gap)** must never touch the shared connection — `SyncedStubFixture
  .cloneRawData(fromConnectionId, toConnectionId)` copies `raw.jira_issues`/`raw.jira_entities`/
  `raw.jira_changelogs`/`raw.jira_worklogs` verbatim (including `needs_processing`/
  `processing_version`/the tombstone columns) into a fresh connection id with ONE SQL
  `INSERT … SELECT` per table (the columns come from the Exposed table objects and an
  `information_schema` check fails the clone if they ever differ from the database's, so a new column
  cannot be silently dropped; surrogate `id` columns, `BIGINT` since V33, are not copied) — cheap, no HTTP — and the test
  drives only the ONE stream under test (`JiraProcessStream`, `JiraReconcileStream`,
  `JiraWorklogStream`, …) directly against that clone, the same production code a real job would run,
  just without the surrounding streams that already ran once to produce the shared fixture. A clone copies no `sync_cursors` row.
- **A test whose SUBJECT is the sync streams themselves** (cursor resume, `CURSOR_EXPIRED`, lease
  loss, the bulkfetch fallback, the day2 incremental/worklog feed) keeps a REAL HTTP sync — but
  drives the smallest path that exercises it: a single stream directly where the fixture already
  established the raw prerequisites (`JiraIssuesStream` alone for cursor mechanics — no `raw.*` rows
  needed at all; a clone plus the ONE stream under test — RECONCILE, WORKLOGS — where the prior
  streams' output is a prerequisite, not the subject).

`SyncedStubFixtureTest` is the explicit tripwire: it re-snapshots the shared connection's raw/norm row
counts and its status-interval digest and compares them against the baseline `SyncedStubFixture`
captured the moment its own backfill first completed. That test alone proves nothing when it runs
before the mutator, so the check also rides EVERY consumer (`FixtureTripwire.kt`): each
`connectionId()` call after the first re-snapshots (~25 ms) and fails the calling test when the
connection changed — a read-only test that started mutating the shared connection fails the NEXT
test that asks for it (the message names the previous caller class), whatever the class order.
After a failure the baseline is re-captured, so one mutation is one red test. `FixtureTripwireTest` pins the class itself (failure, re-capture, the per-class gate, the caller detection). Residual gap: the
last consumer of a fork is only caught by the explicit tripwire test, if that runs after it.
Effect: these three classes' combined runtime fell from ~590s (one full sync per test, ~30 of them)
to under 2 minutes (one full sync, plus a handful of cheap clones and small real-HTTP stream runs).

**The history horizon and the stub's dates (A48).** V39 wakes `metrics.settings.history_horizon_months` at 24 and the reports apply it at read time, so a test reading fixed old dates (2020, 2024) on the
real clock would be clamped or refused. `PostgresTestSupport` therefore switches the horizon OFF right after migrating the container (the suite baseline; `MetricsSettingsRoutesTest.defaults` restores `null`
too), and a test of the horizon sets its own through `withMetricsSettings`, which restores the baseline. `ReportHistoryHorizonTest` pins `ReportService.clock` to 2026-06-01 and sets 6 months (cutoff
2025-12-01, inside the stub). The e2e stack has no such baseline -- it runs with the migration's 24 months -- and the stub's 2025-09 .. 2026-09 dates age out of that default in **2027-09**: from then
`e2e/global-setup.ts` must set `historyHorizonMonths = null`, or the stub be regenerated at later dates.

**The derived fixture (`DerivedStubFixture`, `server/src/test/kotlin/DerivedStubFixture.kt`).**
`MetricsDerivationTest` used to clone `SyncedStubFixture`'s raw rows and re-run PROCESS → PROFILE
(`jira/JiraProcessStream.kt`/`jira/JiraProfileStream.kt`, ~20s) for EVERY test, most of them only to
READ a single DERIVE's result. `SyncedStubFixture.cloneProcessedData(fromConnectionId,
toConnectionId)` extends `cloneRawData` with the ALREADY-PROCESSED `norm.*` rows (work items,
status/field intervals, field changes, worklogs, the rebuilt-wholesale reference tables) plus
`source_connections.profile`/`profile_at`, copied verbatim (surrogate `id` columns, `BIGINT` since V33, excluded,
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
  (`SyncedStubFixture.createConnection(enabled = false)`): every global config change anywhere in the suite
  (`MetricsSettingsService.bumpRevision()`: settings, membership, team capacity) enqueues a DERIVE for every ENABLED connection
  (a per-connection config or calendar save only for its own), and a worker
  running inside another test's `testApplication` would re-derive it with the REAL clock between
  this test's own pinned-clock DERIVE and its assertions — a flake that only shows up in full-suite
  order.
- **Never re-run PROCESS/PROFILE in a metrics test unless PROCESS itself is the subject** —
  `cloneProcessedData` (or, for a read-only test, `DerivedStubFixture` outright) is always cheaper
  and exercises the exact same stored shape a real connection would have after its first sync.

`DerivedStubFixtureTest` is the explicit tripwire, the same role `SyncedStubFixtureTest` plays for
`SyncedStubFixture` (and `DerivedStubFixture.connectionId()` re-verifies through the same
`FixtureTripwire` — but its digest costs ~180 ms, so only when the calling TEST CLASS changes; a caller
that cannot be identified from the stack re-checks every time): it re-derives `DerivedStubFixture.metricsDigest` — an MD5 over EVERY derived
`metrics.*` table (dimensions, bridges, facts, both daily aggregates — NOT the global `dim_date`,
which any deriving test under another calendar rewrites, so the tripwire stays class-order independent; ordered by primary key, else
by every hashed column; the surrogate `id`, `derive_runs` and the snapshot's `snapshot_at`/
`reconstructed` bookkeeping excluded; the reprocess-digest pattern above, applied to invariant 12)
— and compares it against the baseline captured the moment the fixture's own DERIVE first
completed. Cost of the per-consumer re-checks (measured 2026-10-06, one full `-Pforks=2` suite): 9 and 11 derived re-checks (~1.6 s, ~2 s) plus 50 and 19 synced ones (~1.3 s, ~0.5 s) in the two forks, no unidentifiable derived caller (97 of 117 derived calls are gated out) — at most ~3 s of a fork's wall time, below the noise of back-to-back full runs. **The metrics digest joins the reprocess-digest pattern:** `MetricsDigestTest` derives a
private disabled clone twice (same clock, same `config_revision`, both inside ONE
`withMetricsSettings` block — each wrapper call would bump the revision every derived row carries)
and asserts identical digests plus an unchanged `fact_sprint_snapshot` row count, then does the same
across a REPROCESS (`markAllNeedsProcessing` + the PROCESS stream over the clone's raw rows), and
proves the digest is sensitive (one nudged value / one deleted bridge row changes it). Only that
test opts into `includeDimDate`. A red
digest is a real nondeterminism bug in the deriver, never grounds to loosen the test.
**Data-source scope rules (`TestConnections.kt`).** The running app ENFORCES "one Jira site across every active data source, no project key in two" (`ingest/DataSourceScope.kt`) over the table every class in a fork shares, so a test data source never invents its own site or reuses a stub key over HTTP. Every test data source is on `TEST_JIRA_SITE_URL` (`https://flow-test.atlassian.net`); a row created or PUT over HTTP (`POST`/`PUT /api/v1/data-sources`, i.e. through the ENFORCED app) takes `uniqueProjectKey()`/`uniqueProjectKeys(n)` (`U` + 8 hex digits, inside `PROJECT_KEY_PATTERN`); the direct fixture service `SyncedStubFixture.dataSources()` is built with `ScopeRules.OFF` (the in-JVM twin of `INGEST_SCOPE_RULES=off`), because fixtures create connections over the stub's four real keys (`IN_SCOPE_PROJECT_KEYS`, which the stub needs: REFERENCE keeps only boards of configured keys), many of them at once. A per-class `DataSourceService(...)` construction is therefore a smell: delegate to `SyncedStubFixture.dataSources()`. A test that PUTs a fixture-created connection over HTTP creates it with unique keys (`RowLockVersusDeriveTest`). The rules' own cases are `DataSourceScopeTest` (HTTP + the eight-way concurrent create under the lock) and `DataSourceScopeBootTest` (the `ingest.scopeRules` fail-closed boot checks). **A `GITLAB` row** (V40 admits the kind; the 1.34.0 routes create real ones, but the kind-leak and seam tests want one with no token and no connector) is inserted with `GitLabRowFixture.insert(...)` (`GitLabRowFixture.kt`: unique name, minimal GitLab settings JSON) and removed with `remove(id)` (hard delete, jobs first); `ConnectorSeamTest` and `KindLeakTest` are its only users, and no fixture or shared-state test may see one left behind.

**`dim_date` is global and rewritten whole by a calendar change** (`MetricsStore.ensureDimDate`): a test that switches the time zone/weekend/holidays and derives (`DimDateContentionTest`) restores the table afterwards from a snapshot (delete every row, re-insert the snapshot with each row's original `config_revision`), and a test that needs a row the calendar would never write stamps it with `DerivedStubFixture.stampDimDate` and restores it in a `finally`. **The public-holiday dictionary (`metrics.public_holidays`) is global too and V25 seeds the Polish holidays 2017–2030**, so a figure that depends on working days in that range already counts them: a test needing a holiday on a given day adds it with `TestHolidays.with(date) { }` (removed in a `finally`; it bumps the shared revision like a settings PUT) and, when it derives under it, puts the touched `dim_date` row back (`MetricsDerivationTest`'s public-holiday case); a test that needs the calendar reads it through `TestHolidays.calendar()`/`TestHolidays.dates()`, never from settings. `PolishHolidays` (tests) is the independent generator `PublicHolidaySeedTest` compares V25's literal rows with.
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
Generated report requests with no `teamId`/`domain` are `400` by design (the reports scope guard, `reports.md` "Scope"), not a signal; the three `teamId`-required operations always get a team.

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
unavailable, and leaves services and volumes intact; the only global teardown deletes the **shared read-only fixture** (one synced
Jira-stub connection and its team, created by global setup, exported to the workers as `E2E_SHARED_*`, prepared by whichever of
`accessibility-data`/`reports-drill`/`reports-compare` runs first via `sharedFixtureReady`, never mutated by a consumer —
`e2e/README.md` "Parallel execution").

**Coverage and fork measurements** (moved from `testing.md` "Coverage gates" and "Parallel forks"; the binding rules stay there).

Per-fork fixed cost is the container (with Flyway), one stub SYNC and one DERIVE — measured by the
`fixture-cost` markers (2026-10-20, local, 18 cores): Postgres 3.4-4.7 s, stub SYNC 4.6-5.3 s since the batched raw writes
(9.3-11.2 s before, `build-times.md` WHY 21), first DERIVE ~1-1.5 s, so ~10 s a fork. The fork warm-up classes ("Balanced fork order") print one more marker, `app-boot` (the throwaway first Ktor boot, ~1.6-1.9 s), beside the `postgres` one in `TEST-ch.nokillswit.ForkWarmup<k>Test.xml`. The measured gain and the point where more forks stop paying are in `build-times.md` WHY 5.

**Balanced fork order (`-Pforks>=2`).** Gradle's `MaxNParallelTestDefinitionProcessor` deals candidate classes to
the forks round-robin in visit order (position `p` goes to fork `p % forks`), all up front, with no work stealing;
the scanner visits each directory in sorted order. `server/build.gradle.kts` therefore replaces `testClassesDirs`
with a composite of one file tree per candidate class (a composite is visited in declaration order and keeps
package-relative paths) plus a per-directory catch-all for everything that is not `**/*Test.class`, so the candidate
set equals the plain tree's. The order comes from `balancedCandidateOrder`: classes sorted by weight, descending
(ties by name), dealt greedily to the least loaded fork that still has a free slot (each fork's slot count is capped
at its round-robin share, so the interleaving reproduces the plan), then position `p` takes fork `p % forks`'s next
class. The weights are `server/test-weights.tsv` (`<FQN><TAB><seconds>`, suite time minus the fixture-cost markers,
written by `local-times.mjs test-weights` from a full run). Degradation: a missing or unparseable file warns once and
keeps today's alphabetical order; a class not in the file weighs the average; a deleted class's row is ignored.
`--tests` filters work unchanged. The file is not a task input (only the class trees are). A red local rerun is
reordered by Gradle's `RunPreviousFailedFirst` (failed classes go first), so the balance is off for that rerun.
`slow-tests --forks N --weights server/test-weights.tsv` prints the fork-balance block (span vs sum / N, drift of
measured against recorded weights).

**The pinned head: fork warm-up (every `-Pforks`).** The first class of a JVM pays the container, Flyway and the first
Ktor boot, and when it runs in `testApplication` that is inside the hard 60 s limit (14-15 s cold in the flake sweep;
`-Pforks=1` made `AnonymousAccessTest` pay it, `-Pforks>=4` `IngestWorkerTest`). `ForkWarmup.kt` moves it into a
`@BeforeClass` (JUnit 4 does not time it): `ForkWarmup.warm()` migrates the container (`ensureMigrated()`), boots the app
once via `TestApplication { configureApp() }` and throws it away (marker `app-boot`); `ForkWarmup.assertWarm()` is the
one test of each `ForkWarmup<k>Test`. There are eight such classes (`ForkWarmup1Test`..`ForkWarmup8Test`, one per
`-Pforks` value 1..8) because Gradle deals one class FILE to one fork and collapses a repeated class name, while
`TestClassNamingTest` allows one concrete `*Test` class per file and forbids an abstract `*Test` base. The `testClassesDirs`
Callable puts `ForkWarmup1Test`..`ForkWarmup<forks>Test` at positions `0..forks-1` (one file tree each), so the round-robin
deal gives fork k its own `ForkWarmup<k>Test` as the first class it runs. `balancedCandidateOrder` (via `isForkWarmup`) never
plans a warm-up class; the surplus `ForkWarmup<forks+1..8>Test` stay out of the run through the per-dir
`exclude(**/*Test.class)` catch-all in the weights path and through `exclude(FORK_WARMUP_GLOB)` in the plain path (`-Pforks=1`
or no weights file). The Callable never throws: before `compileTestKotlin` ran, or while the compiled tree is stale (built before
the warm-up classes existed) and lacks a needed `ForkWarmup<k>Test.class`, it returns the plain dirs (so `--dry-run` works) and is
evaluated again at execution; the test task's `doFirst`, after the compile, then fails a run whose class is still missing
(naming the file; "run clean" if the source exists). Not covered: a `--tests` run drops every
warm-up class (no test in it matches), and a failed-first rerun (`RunPreviousFailedFirst`) may open a fork with another
class; both still open cold, which is fine for a focused run. The **tripwire** (`ForkWarmupLedger`, next to the stall
watchdog in `server/build.gradle.kts`) records each JVM's first class and the result of every `ForkWarmup*Test`; a FULL run
(the OpenAPI gate's condition: no `--tests`) fails unless exactly `ForkWarmup1..forksTest` ran SUCCESSFULLY in `forks`
distinct JVMs, and only warns about a JVM whose first class was not its warm-up. If the previous run's warm-up class failed, Gradle runs failed classes first and can deal two warm-ups to one fork, so the distinct-JVM check can fire once on that rerun: rerun. It guards the Gradle-internals
assumptions (round-robin dealing, in-order forwarding, class-name dedupe). The throwaway boot runs `Bootstrap` on a fresh
database before any test: no test may assume it is the fork's first boot. Never call the stub fixtures from `ForkWarmup`
(`StubFixtureWarmupGuardTest`).

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

**Server test setup, forks and the `TestEnvironment.kt` harness** (moved from `testing.md`; the binding one-line rules stay there).

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
likes, so **class order is not a contract**: any class may be the first thing a fork runs after the fork's pinned
`ForkWarmup<k>Test` (a `--tests` run or a failed-first rerun has no such pin and opens cold - "Balanced fork order").
The one rule that follows: the container is migrated by `PostgresTestSupport` itself the moment it starts (a `Flyway.migrate()`
in its lazy init, the same call as `infra/db/Flyway.kt`), so a fixture or raw-JDBC helper never needs its own
"has Flyway run yet?" guard (`PostgresTestSupport.ensureMigrated()` is the explicit spelling; `MetricsDigestTest`
run alone is the pin). The OpenAPI gate below
merges the forks' coverage (per-fork fixed cost and the point where more forks stop paying: `build-times.md` WHY 5).

**Stall watchdog (`-PtestStallMinutes=N`).** Why: on 2026-10-17 (CI run 39062260538) a failed `DimDateContentionTest` left the `server` job silent until the
25-minute timeout cancelled it, and a cancelled job uploads no `server-reports` artifact, so there was no evidence. `TestStallWatchdog` (top of the `test`
task's section in `server/build.gradle.kts`) is a Test-task listener that tracks, per test JVM (the "Gradle Test Executor N" suite in a descriptor's parent chain),
the time of the last suite/test start or finish. An executor that has started, not finished and been quiet for the threshold gets (1) `jcmd <pid> Thread.print -l`
(the `jcmd` of the test task's `javaLauncher`; the worker pid is found among Gradle's descendants by its command line, which ends in `'Gradle Test Executor N'`)
written to `server/build/reports/hung-tests/Gradle-Test-Executor-N-<yyyyMMdd-HHmmss>.txt` and logged at ERROR with the class/test in flight, then (2) its
JVM is destroyed, so the task fails ("Process 'Gradle Test Executor N' finished with non-zero exit value 137") once the other forks have run their
queues, instead of at the CI job timeout (`jcmd` itself is bounded at 30 s, so a worker too wedged to attach to is still killed). The
directory is cleared at the start of each run; CI uploads `server/build/reports` with the `server-reports` artifact when the job is not cancelled, and the
dump is in the log either way. `-PtestStallMinutes` is an integer 1..60 (default `DEFAULT_TEST_STALL_MINUTES` = 5; the measurement behind it - a 13 s worst quiet gap and 7 s worst test in a full local `-Pforks=2` run, CI
~1.75x - is in the comment on the constant); every executor also logs `test watchdog: … longest quiet gap N s (in flight: …)` when it finishes, which
is the number to re-measure the default from. The quiet gap is a single test's run time or a class's `@BeforeClass` fixture warm-up, nothing else.
The matching source-side bound: every raw JDBC connection a test opens comes from `PostgresTestSupport.connect()` (pgjdbc `connectTimeout`/`loginTimeout` 30 s,
`socketTimeout` 180 s, `tcpKeepAlive`), never a bare `DriverManager.getConnection`: a call parked in a socket read cannot be cancelled, and the 180 s bound is
above any legitimate statement (a lock wait behind a DERIVE) and below the watchdog, so one half-dead connection fails ITS test with a stack trace. The same
rule shapes `DimDateContentionTest.holdingDimDateRowLocks`: its JDBC holder and poller run in their own scope (a `coroutineScope` waits for every child, and a
child in a socket read ignores cancellation), and cleanup is bounded.

**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- `configureApp(vararg overrides)` (on `TestApplicationBuilder`, so `ForkWarmup.warm()` can use it outside `testApplication`) points the app at the shared container (with CSRF off)
  WITHOUT starting it — tests that assert startup behavior (the fail-closed checks) add their
  overrides and call `startApplication()` themselves; `usePostgresTestcontainer()` is the
  configure-and-start shorthand. It also pins `ingest.deriveQuietSeconds` to `0` (no DERIVE debounce): tests drive the worker under a
  pinned clock and expect a config PUT's DERIVE due at once; `DeriveDebounceTest` builds its own `SyncJobsService(..., deriveQuietMillis)`
  under an injected clock (and one app with the window set) for the debounce itself. A directly constructed `SyncJobsService` defaults
  to no debounce for the same reason. It also sets `mail.appUrl` to `https://localhost:8084` (the origin the emailed reset link is built
  from, validated at boot in every mode - so `https`, which the production-mode boot tests, reusing this config, also accept); a test
  that wants the blank-origin 503 or a malformed origin overrides it.
  It sets `telemetry.enabled` to `false` (the sampler would write a process row and samples into the shared database from every booted app):
  the telemetry tests (`TelemetrySamplerTest`, `TelemetryStoreTest`) build the sampler directly over `TelemetryFixtures.kt`'s helpers and empty `ops`
  before each test (`wipeTelemetry`; nothing else writes there), and `TelemetryRoleTest` opts in with `"telemetry.enabled" to "true"` plus
  `TelemetryOverridesKey` (a fixed identity, an immediate first tick).
- `ForkWarmup.warm()` / `ForkWarmup.assertWarm()` (`ForkWarmup.kt`): the fork's untimed Postgres + first-boot warm-up, called only by the eight `ForkWarmup<k>Test` classes ("Balanced fork order"); no other test calls it.
- Password-reset fixtures: `newPasswordResetService(ttlMillis, clock)` builds a `PasswordResetService` over the shared database (an
  injected clock for expiry), `issueToken(userId, revision, email)` unwraps its `IssueOutcome` to the emailed token (null when stale
  or capped), and `resetTokenHashes(userId)` lists the stored digests (the table never holds a raw token).
- `jsonClient()` / `authedClient(email, password)` — the standard HTTP clients; both go through
  the shared test-client defaults (JSON + `application/problem+json` negotiation + the
  `OpenApiConformance` plugin), and `authedClient` logs in and attaches the bearer on every
  request.
- `LogCapture(loggerName)` + `hasKeyValue` — a Logback `ListAppender` for asserting the audit
  trail (`ch.nokillswit.audit`); `awaitEvent` polls for asynchronously produced events. Detach in
  a finally.
- `TestUsers.seed(email, password, name, role)` (bcrypt cost 4 for speed; defaults to ADMIN — pass
  `FlowUserRole.USER` for a non-privileged caller) — every seeded user funnels through
  `FlowUserService.create`, so they carry the inverted-default MFA-disabled row and log in
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
- `TestTeams.seed(name)` — a fresh team fixture; beside it, raw-row readers for what
  the API hides (`TestTeams.rawRows`, `TestUsers.stampPasswordChangedAt`). Shared
  suite state (the seed admin) is never mutated destructively — tests mint UNIQUE rows and remove
  their own.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's
  password in the SHARED container; call this afterwards so later tests (and re-runs) see the
  pristine V3 state. Production-mode boots must also override `"mail.transport" to "disabled"` —
  the dev-default `log` transport is refused in production (`MailTransportTest`) — and
  `"security.encryption.key" to strongEncryptionKey()` — the dev-default data-encryption key is
  burned (`CryptoBootTest`); the checks fire in module order (JWT → mail → crypto → seed
  passwords), so a test asserting a later check must satisfy every earlier one.

**Frontend test internals** (moved from `testing.md`; the binding rules stay there).

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
`setup.ts` also raises testing-library's `asyncUtilTimeout` to 4 s (and `vite.config.ts` the `testTimeout` to 10 s): the
module registry is cleared per file, so the first test of a shuffled file to touch a lazy chart chunk pays its cold
import, which exceeds the 1 s `findBy`/`waitFor` default on a contended machine. A chart read is still always awaited
(`findBy…`/`waitFor`) — a synchronous `getBy…` after the page's own controls appear is the bug, not the timeout. To
reproduce shuffle flakes run several `npx vitest run --sequence.shuffle --sequence.seed=N src/pages src/components`
in parallel (a quiet single run hides them).

**The suite runs with `isolate: false`** (`web/vite.config.ts` — a worker reuses its module registry and
globals across files; the measured gain is in `build-times.md`, WHY 6). Vitest already scopes `vi.mock`
registrations per test file; what carries over is the EVALUATED `src/` modules, so `setup.ts` calls
`vi.resetModules()` before every file (npm packages stay cached) and each file's own mocks apply. Tests must not assume anything an earlier test left
behind — await lazy chart chunks (`findBy…`/`waitFor`, never a synchronous `getBy…` right after the first
data assertion), reset module-level state in `afterEach`, and unstub globals/timers a test installed. The
proof is `cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test
that only passes in file order is the bug, not the config.

**Emptying the shared world inside a rolled-back transaction (a deliberate exception).** The shared Testcontainer always holds derived
data, so a test that must see "nothing derived" (`ReportHomeTest` "nothing derived reads as an empty overview…") cannot use a clean
database. It deletes `derive_runs` and the fact tables INSIDE one `suspendTransaction` whose body calls the service (the report's own
`reportTransaction` joins the outer one), captures the result and throws a sentinel so the whole transaction rolls back; nothing is ever
committed, and the test asserts afterwards that the world is intact. Use it only for such a "global emptiness" subject; everything else
mints its own rows and removes them.

**Report 21's worlds (1.39.0).** `CodeActivityWorld.kt` (`withCodeWorld(zone) { }`) writes a hand-built `norm.code_*` world on a DISABLED GitLab connection with a UNIQUE host (so its links meet no other class's), one team with one linked member, unique Jira accounts and the decider, and removes all of it in `finally`; `CodeActivityReadsTest` (zone UTC) and `ReportCodeActivityTest` (the report zone, `ReportService.clock` pinned to day 14) share it. `ReportCodeActivityFixtureTest` clones the processed GitLab fixture into its own connection on the stub host, confirms the proposals over HTTP (the code-identities discipline: the stub host's decisions are emptied at the start and the end) and compares every figure with a plain-JDBC re-derivation.
