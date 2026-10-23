# Audit status

Flow's third checkup, 2026-10-23, ran from scratch on master `fbf0cc4` (app 1.14.0, 489 commits after the second
checkup's `184495a`) and was audited by a `checkup-lead` plus eleven auditors. This file is the starting point for the
next checkup and the working record for the fixes: the findings below were turned into `BACKLOG.md` items the same
night (13 `[next]` items titled "Checkup 3, A…/B…" for the autonomous tiers and one `[new]` "Checkup 3, decisions for
the user" for tier C plus A11/A16), and the fixing PRs are listed under "Fixed so far". The fixing PRs
work from this file, so every finding keeps its id, severity, `path:line` evidence and proposed fix; use the linked
docs for current behavior.

The second checkup, 2026-10-06 on `184495a` (v0.4.0, untagged), found every dimension **Mostly** (authn/authz **Yes**,
merge enforcement **No**) and fixed the autonomous tiers the same day in PRs [#91](https://github.com/liveweird/flow/pull/91)-[#108](https://github.com/liveweird/flow/pull/108):
docs drift and the always-loaded set (#91), floors and the shuffled-vitest gate (#92), a11y (#93), spec bounds (#94),
the nightly image scan (#95), the server hardening round (#96: report statement budget, `no-cache`, `Server: flow`,
audited 429s), DRY helpers (#97, #98), tripwire/boundary tests (#99), the worker queue (#100: advisory try-lock, fenced
run writes, bounded heartbeat; three Opus review rounds), the Deep dive web split (#101), the `replicas: 1` guard
(#102), the 400-line ratchet and the big-store splits (#103, #106), `ColumnTable` (#104), the statement-timeout cache
(#105) and two fixes found only by live checks (#107 DERIVE heap at scale, #108 cold-start crash). Its full record is in
the git history of this file: `git log -p .claude/docs/audit-status.md`.

The first checkup, 2026-09-30 on `bef0e74` (v0.3.0 + PR #32), found every dimension **Mostly** (authn/authz **Yes**,
merge enforcement **No**) and fixed tiers A to D in PRs #31-#53 and #59-#68 (docs drift, DRY helpers, floors, CI/k8s
hardening, the server-suite speed-up from 13 minutes). Its full record is in the same git history.

## Method

One `checkup-lead` (Fable) planned the run and adjudicated it: eleven `auditor`/`security-auditor` agents, one per
dimension (docs and specs, DRY/SRP/SOLID, unnecessary complexity, conventions, tests, UI consistency, authn/authz,
guardrails, architecture and infrastructure, API spec, smells and dead code; `security-auditor` for authn/authz), in
parallel. A `gate-runner` took the baseline first. Every high/medium claim was re-read by the lead against the code
before it entered a tier ("auditor-verified" marks CI-log evidence the lead did not reproduce). Live checks ran on an
isolated compose project `flow-check` (stub data only, ports 8684/6035/8628/8694, torn down with `down -v`; the dev
stack was never touched). Audit only: no repo edit.

Lessons from earlier rounds that still hold: re-run the scale-20 overlay (`docker-compose.perf.yaml`) after any change
to DERIVE's write path, not only for timings (the stub is 1,200 issues and hides heap problems); only the
transaction's `queryTimeout` may set the statement timeout (a `SET LOCAL` is silently overwritten, `persistence.md`);
only a live k8s deploy shows cold-start behaviour.

## Baseline (all gates green)

| Gate | Result | Wall | Numbers |
| --- | --- | --- | --- |
| `./gradlew cleanTest build -Pforks=2` | PASS | 218 s | 210 classes, **1638 tests**, 0 failures/errors/skipped; detekt 0; `koverVerify` PASS; `checkDependencyAlignment` PASS |
| OpenAPI coverage `gaps.txt` | PASS | n/a | 0 bytes |
| Kover actuals (`koverXmlReport`) | PASS | 2 s | **line 98.82 %** (floor 98), **branch 85.68 %** (floor 82) |
| Fork balance (`slow-tests`) | n/a | n/a | span 2m52s, excess 4.4 s (2.6 %); 7 of 210 classes missing from `server/test-weights.tsv` |
| web `lint:api` | PASS | 1 s | 0 errors, 2 warnings (`sla-extension-present`, `terms-of-service-present`, the decided gaps), 151 hints |
| web `check:api`, `lint`, `knip`, `build` | PASS | 1/13/0/5 s | clean |
| web `test:coverage --sequence.shuffle` | PASS | 14 s | 220 files, **2507 tests**; statements 98.45 (floor 97), branches 93.90 (93), functions 98.50 (95), lines 99.06 (98) |
| web `test --sequence.shuffle` (2nd seed) | PASS | 16 s | stable |
| e2e `lint`, `knip`, `typecheck`, `check:scenarios` | PASS | 2/1/0/1 s | 18 specs in sync with scenarios |
| `check-toolchain-pins.mjs` | PASS | n/a | node 24.21.0, java 21.0.12.1 agree |
| tree after gates | clean | n/a | `git status` empty before and after |

Versus 2026-10-06: server 988 to 1638 tests (test wall 1m45s to 2m52s on 2 forks), Kover 98.68/82.29 to 98.82/85.68,
web 1165 to 2507 tests (7.7 to 13.2 s), web coverage 97.23/93.73/95.71/98.75 to 98.45/93.90/98.50/99.06. The worktree's
`web/node_modules` and `e2e/node_modules` were symlinks into the main checkout (untracked; the gates ran against them).

**CI history (via `gh`):** master `ci.yml`, last 60 runs: 49 success, 9 cancelled (superseded pushes), 2 failures
(2026-10-20 `web`: the known order-dependent `Home.test.tsx` shuffle failure, fixed by 9072740; 2026-10-21 `images`: a
Docker Hub blob 4xx during build). Nightly `e2e.yml`, last 30 runs: 19 success, 11 failures: 9 (10-07..10-15) the Trivy
step `No such image: flow-app:latest` (infra, fixed 10-15), 2 (10-19, 10-20) a deterministic `reports.spec.ts:1702`
strict-mode failure (green from the 10-20 dispatch on). The latest 9 nightly/dispatch runs are green. The R2DBC
"Response queue is full" flake occurred on PR runs only (10-17, 10-22), both green on re-run.

**Live checks (`flow-check`, stub data):** boot healthy in ~25 s; headers on `/` and `/api/v1/health` as `security.md`
promises (`Server: flow`, CSP `script-src 'self'`, `Cache-Control: no-cache` on `index.html`, `no-store` on the API,
nosniff/DENY/Referrer/Permissions-Policy); `/openapi` 200 in development mode; stub data source: SYNC + RECONCILE +
DERIVE SUCCEEDED in 20 s; app container memory 182.6 MiB idle after boot, **225 MiB peak** during SYNC/DERIVE, 229 MiB
after 120 s idle (cgroup anon 239 MB, file 19 MB), a stub-scale datapoint only. Authorization probes as a USER (no roles,
not a manager), 48 calls: every ADMIN surface 403 (`flow-users`, `data-sources/*` incl. status/profile/metrics-config/
sync-jobs/raw-issues, `metrics-settings`, `jira-teams`, writes on teams/capacity/work-category-targets/jira-memberships/
public-holidays); 403 before 404 (`GET /flow-users/999999`, `PUT|DELETE /teams/999999`, `PUT /teams/999999/capacity`)
and 403 before 400 (`PUT /teams/999999 {}`); `GET /teams/{id}/capacity` answers `canEdit: false`; `home?teamId=999999`
and `teamId=0` give 400 with distinct details; anonymous gives 401; logout 204, then the same access token 401 and the
revoked refresh token 401 "Refresh token revoked"; errors are `application/problem+json`. **Playwright on `flow-check`**
(16 specs, the two Mailpit specs excluded, L-2; `INGEST_SCOPE_RULES=off`, 4 workers): **180 passed, 1 failed in
3.9 min**; the failure, `reports.spec.ts:1953` "the user reads the distribution of task estimates" (`toHaveURL(/teamId=11/)`
received `teamId=15`), passed 3/3 alone, a parallelism flake (L-3).

## Verdicts

Re-audit them, do not assume the table still holds. The second checkup's verdicts were all **Mostly**, authn/authz
**Yes**, merge enforcement **No**.

| # | Dimension | Verdict | Why (one line) |
| --- | --- | --- | --- |
| 1 | Documentation and specs | **Mostly** | Paths, migrations V1-V32, the doc table, env table, rate-limit table, e2e parity and the changelog are accurate; drift is in status framing (three docs still say "v0.4.0 (this codebase)" at 1.14.0), three wrong `Metrics*` class names and `/ready` in `CLAUDE.md`, README's "overview of every team", six citations of an untracked `briefs/` dir, and the always-loaded set at 48,736 bytes (+37 % over the 35,487 the second checkup fixed). |
| 2 | DRY, SRP, SOLID | **Mostly** | Server line-level duplication is near zero and #97/#98's single helpers hold, except `sprintWindow`, which now has a same-named twin with swapped parameters; four `requireActiveTeam` copies, two byte-identical `*Distributions` classes, and the report pages repeat the drill lambda (5x) and the page scaffold (13x). |
| 3 | No unnecessary complexity | **Mostly** | The 400-line ratchet held exactly (6 grandfathered files at their ceilings, down from 11); `MetricsDeriver` at 651 is the remaining weight, four files sit within 10 lines of the cap, `FeatureFlags.tsx` is a 343-line function for a one-value `Feature` enum, plus a legacy table object and a stale KDoc. |
| 4 | Conventions met | **Yes** | Every mechanical sweep came back clean: guard-before-receive on all 28 body handlers, audit on all 33 mutations, 32/32 migrations pinned, 0 `Instant.parse`, 0 bare `DriverManager`, 0 unwarmed fixture classes, 0 `toLocale*`, all Modals/Tables/charts compliant; only 13 `batchInsert` sites outside the documented `insertRows` rule's scope. |
| 5 | Tests stable, decent coverage | **Mostly** | Suites are disciplined (local Playwright 180/181, the one failure a parallelism flake found live; no skips, shuffled vitest stable twice, digests strict, warm-up guard, cleanup hooks) and master CI had 2 failures in 60 runs; but the nightly e2e caught a deterministic regression a day late and was red 11/30 nights (9 infra), the R2DBC flake is unexplained, floors are 3.7/3.5 points below actuals, 7 classes are off the weights file, and the e2e Mailpit URL is hard-coded to the dev stack. |
| 6 | UI consistent and aesthetic | **Yes** | 38/40 pages on `PageHeader` + `Stack gap="md"` (the two auth pages by design), 0 forbidden colours/hex/shade props, every chart with its table and styles import, all overlays/tables/icons labelled, 0 placeholder mismatches EN/PL; only low polish (`grape` undocumented, red what's-new dot, PL "pozycja"/"element", one unlabelled SegmentedControl). |
| 7 | Authn/authz without gaps | **Yes** (wider security **Mostly**) | All 85 secured operations sit inside `authenticate {}` with the documented guard and 403/404/400 ordering (confirmed live on 48 USER probes), live grants are re-checked in the transaction, the six anonymous handlers are exactly the spec's `security: []` set, #96's hardening holds; open are the refresh token's missing rotation (the doc overstates the `/logout` remedy), an unbucketed anonymous `/api/v1/ready`, and two headers pinned by no test. |
| 8 | Guardrails in place and executed | **Mostly** (merge enforcement **No**) | Every CI gate is pinned, least-privilege and executed; but four gates are CI-only (`npm audit`, `k8s-static`, lockfile scan, `test:setup`/toolchain pins) so a local green can be a CI red, Spectral warnings cannot fail (`--fail-severity` unset, 14 warn rules), `cancel-in-progress` also cancels master pushes (9 of 40), nothing alerts on a red nightly, floors unraised since 10-06; master still unprotected (known A1). |
| 9 | Architecture and infrastructure | **Mostly** | Boundaries, role split, shutdown ordering, k8s hardening and all #100/#102/#105/#107/#108 fixes hold; one real defect (a worker slot permit leaks when `claim()` throws, a silent ingestion stall after two DB hiccups), an int32 `SERIAL` on eleven DERIVE/PROCESS-rewritten tables, and the memory flags/limits pinned by no test. |
| 10 | API spec complete, accurate, coherent | **Mostly** | 91 routes to 91 operations, 70/70 enums, 237 DTO shapes, all 12 drill pairs, 25/25 body ops with 413/415, mechanically in lockstep; stale `info.description`/`version: 1.0.0`, the report-budget 500 undocumented, a dead `BadGatewayException` 502 mapping, `ResourceId minimum: 0`, registered header gaps. |
| 11 | No code smells, no dead code | **Yes** | 0 dead i18n keys (1,820), 0 unread config keys (67), 0 unused test helpers (650), 0 orphan routes, 0 TODO/FIXME, 0 `lateinit`/`GlobalScope`/`println`; what remains is a handful of production members only tests call (`defaultCalendar`, `exactFor`, `sampleGated`, `recordReconcileFailed`...) and three exports knip cannot see. |

## Regressions since the second checkup

- **B7 (always-loaded set)**, fixed at 35,487 bytes in #91; now **48,736 bytes** (`CLAUDE.md` 24,715 + `testing.md`
  12,893 + `list-endpoints.md` 11,128). `list-endpoints.md` is mostly the per-endpoint "first...twelfth consumer" ledger
  (D1-2).
- **#98 `sprintWindow` single copy**: `metrics/CapacityKernels.kt:64` added a second `sprintWindow(startAtMs, endAtMs,
  completeAtMs, calendar): SprintWindow?` (commit 4539a7b) beside `reports/DeepDiveKernels.kt:106` `sprintWindow(startMs,
  completeMs, endMs, calendar): Pair<LocalDate, LocalDate>?`: same name, second and third parameters swapped, different
  semantics (D2-3).
- **#92 "re-measure and raise"**: the floors were not touched in 489 commits; Kover branch 85.68 vs 82, vitest functions
  98.50 vs 95 (D8-4/D5-2/D5-3). A convention lapse, not a code regression.
- Everything else was verified in place by the lead's own sweep: `statementTimeoutSeconds`, `no-cache`/`Server: flow`,
  `rate_limit.exceeded`, `respectReducedMotion`, `headingOutline` over all 41 routes, `ExitOnOutOfMemoryError`,
  `startupTimeoutMillis: 140000`, shuffled vitest in CI, nightly Trivy, the `replicas: 1` guard, `insertRows` per-chunk
  release, the advisory try-lock and fenced run writes, `PackageBoundaryTest`, `SourceFileSizeTest` (every ceiling equals
  `wc -l`), `StubFixtureWarmupGuardTest`, e2e cleanup hooks.

## Findings

Format: **id** (dimension, effort, new/known) `evidence`: problem. Fix. `[tier]` names the remediation tier below; the
BACKLOG item for each tier id is in "Tiers". Effort S/M/L. Sections below: high, medium, low, security (dimension 7),
then dropped or downgraded.

### High

- **D9-1** (9, S, new) `server/src/main/kotlin/ingest/IngestWorker.kt:199-218`: `claimAvailable()` does
  `slots.tryAcquire()` then `syncJobs.claim(...)` with no try/finally; the permit is released only on a `null` claim or
  in the launched job's `finally`. A `claim()` that throws (pool-acquire timeout, Postgres restart) propagates to
  `start()`'s catch ("Ingest scheduler tick failed") with the semaphore still decremented; with `workerSlots = 2`, two
  such ticks leave the worker unable to claim anything while `/api/v1/health` and `/api/v1/ready` stay green. No
  `IngestWorkerTest` covers a throwing claim. Fix: `try { claim } catch (e) { slots.release(); throw e }` (release unless
  the job was launched); add an `IngestWorkerTest` case with a `SyncJobs.claim` that throws once and asserts the next
  tick still claims. `[A1]`

### Medium

- **D9-3** (9, M, new) `db/migration/V16__create_metrics_star.sql:111,121,131,145,159,172,187,403`;
  `V13__create_norm_layer.sql:64,84,103`; `metrics/MetricsFactWrites.kt:20,73,106,137`, `MetricsDimWrites.kt:32-33`
  (`deleteWhere ... connectionId` then re-insert): eleven tables that DERIVE/PROCESS delete and re-insert whole on every
  run use `id SERIAL` (int32); no later migration widens them and sequences are never reset. A DERIVE is chained after
  every SYNC/RECONCILE/REPROCESS and config change; at a realistic 100-150k `item_stage` rows per derive and an hourly
  sync, ~2.4-3.6 M ids/day, exhaustion in ~1.6-2.4 years (6-9 months at a 15-minute sync). Failure mode: every DERIVE
  fails with `nextval: reached maximum value`, reports go stale. Fix: first measure on the real tenant (the user runs
  `SELECT last_value FROM metrics.item_stage_id_seq` etc. on the dev stack; not for an audit to read); then one migration
  `ALTER TABLE ... ALTER COLUMN id TYPE bigint` + `ALTER SEQUENCE ... AS bigint` per table (a table rewrite, schedule it),
  or drop the surrogate ids where nothing reads them. Pin with a `MigrationChecksumTest` entry and a scan test that no new
  `SERIAL` lands in `metrics.*`/`norm.*`. `[B1 = C5]`
- **D9-2** (9, S/M, new) `k8s/worker-deployment.yaml:179-198`, `plugins/Health.kt:47`, `IngestWorker.kt:171`: the worker's
  readiness is one DB round trip; nothing observes the scan loop (a leaked permit per D9-1, a dead coroutine, a stuck
  tick). Fix: on a WORKER/ALL role, `/api/v1/ready` also checks "last tick age < 3 x `schedulerTickSeconds`" from an
  `AttributeKey` timestamp the tick loop writes; test with a pinned clock. `[B2]`
- **D9-4** (9, S, new) `server/build.gradle.kts:88-98` (`applicationDefaultJvmArgs`), `k8s/web-deployment.yaml`,
  `k8s/worker-deployment.yaml`, `docker-compose.yaml:118-124`, `docker-compose.perf.yaml`: the #272-#274 memory flags and
  the "raise `-Xmx` and the limit together" rule live in comments only; no test asserts `-XX:+ExitOnOutOfMemoryError` is
  present, `-XX:+DisableExplicitGC` absent (it would no-op `IdleHeapGiveBack`), or that each `-Xmx` fits its memory limit
  (today they agree: web 256m/512Mi, worker and compose 512m/768Mi). Fix: a source-scan test in the `PackageBoundaryTest`
  style over those five files. `[A10]`
- **D2-3** (2, S, new regression) `metrics/CapacityKernels.kt:64` vs `reports/DeepDiveKernels.kt:106`: two `sprintWindow`
  functions, same name, different return types and a swapped `(end, complete)` order; a wrong-order call compiles.
  Regression against #98. Fix: rename the metrics one (`capacitySprintWindow`) or unify the parameter order and have
  DeepDive delegate; a test pins the order. `[A7]`
- **D2-2** (2, S, new) `metrics/TeamWorkCategoryTargetService.kt:94`, `metrics/SprintCalendarService.kt:320,327`,
  `metrics/TeamMembershipService.kt:96`, `reports/ReportSupport.kt:97`: four private/internal `requireActiveTeam` copies of
  the same `Teams.id eq ... and active()` query (three throw 404, reports throws 400) plus `lockTeamAndAuthorize`. Fix: one
  `TeamService.requireActive(teamId, orElse)` (or a `Teams` extension) with the exception as a parameter. `[A7]`
- **D2-1** (2, S, new) `reports/CycleTimeReport.kt:118-131` identical to `reports/ReportedTimeRatioReport.kt:93-107`;
  `TaskAccuracyReport.kt:126`, `TaskEstimatesReport.kt:74`: the `*Distributions` (`plain`/`drawn`, per-picked-person
  split) classes are byte-identical and two more repeat the shape for `AccuracyTask`: four copies of the `byUser` rule.
  Fix: one generic `PickedDistributions<T>(picked, minSample, tailPercent, accountOf)` in `Distribution.kt`; existing
  report tests pin behaviour. `[A7]`
- **D2-4 / D2-5** (2, M, new) `web/src/pages/Report{TaskAccuracy:57,TaskEstimates:61,ReportedTime:56,EstimateAdjustments:61,CycleTime:77}.tsx`
  (the `openBar` lambda, 5 verbatim + 3 variants); the `{filters && !noTeams && ...}` scaffold in 13 pages, the
  `error={query.error} empty={...population === 0}` wrapper in 5, the `ReportGroupsTable` block in 4-5: the per-report page
  re-rolls the drill opener and the page skeleton. Fix: `useHistogramDrill(drill, anchor)` returning `openBar`; a
  `ReportPageShell`/`ReportGroupsSection` component; page tests unchanged. `[B3]`
- **D1-1** (1, S, new) `CLAUDE.md:~24` ("v0.4.0 (this codebase)"), `README.md:11`, `.claude/docs/product.md:17`: the shipped
  product is 1.14.0 but the roadmap/status stops at v0.4.0; `product.md` has no entry for Home (1.0), Compare people, Task
  estimates, the Flow-users rename (1.8), the Costs revamp (1.11), work-category targets (1.12-1.14). Fix: add a "1.x
  (current)" bullet with those features and relabel the three headings. `[A4]`
- **D1-2** (1, M, new regression) `CLAUDE.md` + `.claude/docs/testing.md` + `.claude/docs/list-endpoints.md` = 48,736 bytes:
  the always-loaded set is 37 % over the budget the second checkup fixed (B7); `list-endpoints.md` is mostly the consumer
  ledger. Fix: move the "first...twelfth consumer" ledger into `reports.md` ("Repeated keys"), keep the rules; target
  <= 36 KB. `[B8]`
- **D1-3** (1, S, new) `CLAUDE.md:159`: `Metrics{DimDate,DimWrites,FactWrites,SprintWrites,Aggregate,DeriveRun}Store`; three
  names do not exist (the files are `MetricsDimWrites.kt`, `MetricsFactWrites.kt`, `MetricsSprintWrites.kt`). Fix: write
  `Metrics{DimDate,Aggregate,DeriveRun}Store` + `Metrics{Dim,Fact,Sprint}Writes`. `[A4]`
- **D1-4** (1, S, new) `README.md:29-30`: "The Home page is the overview of every team" contradicts `CLAUDE.md`/`product.md`/
  `web/CLAUDE.md` (up to three picked teams). Fix: reword. `[A4]`
- **D1-5** (1, S, new) `.claude/docs/domain-model.md:571,641,676`, `persistence.md:947`, `metrics.md:968,987`: six citations
  of `briefs/03-STORAGE-OPTIMIZATION.md`, `briefs/07-BLOCKED-STAGE.md`, `briefs/08-EPIC-PROGRESS-PERIOD-VIEW.md` as the
  authority for decisions; `git ls-files | grep ^briefs` = 0. Fix: inline the decision summary (2-3 lines each) and drop the
  pointer, or state the briefs are untracked session notes. `[A4]`
- **D3-1** (3, M, known in part) `metrics/MetricsDeriver.kt` 651 lines (ceiling 651); `AuthRoutes.kt` 519, `DeepDiveReport.kt`
  513, `JiraNormalizer.kt` 463, `ReportSupport.kt` 449, `MetricsTables.kt` 406; next in line `MetricsConfigService.kt` 398,
  `DeriveTaskRows.kt` 398, `SprintCalendarService.kt` 394, `DataSourceService.kt` 393: 659 lines of grandfathered excess,
  and four files one feature away from tripping the ratchet (the near-cap list is new; the grandfathered files were
  the second checkup's "small items left open"). Fix: split `MetricsDeriver` by derive step (the `Derive*` files already
  exist); plan the four near-cap splits before the next feature touches them; lower each ceiling as it drops. `[B4]`
- **D3-2** (3, M, new) `web/src/pages/FeatureFlags.tsx:44` (343-line component; `eslint max-lines-per-function` 360 at
  `web/eslint.config.js:56`); `flowusers/FlowUser.kt:28` `enum class Feature { MFA }`: the biggest web function is 17 lines
  under the backstop, for a one-flag feature. Fix: extract the table and row editor into components, or decide (tier C)
  whether a one-value flag registry still needs the bulk-flag page at all. `[C7]`
- **D5-1** (5, M, new) `e2e/tests/reports.spec.ts:1702`; nightly runs 39226967041 (10-19), 39394984718 (10-20): a
  deterministic strict-mode failure ("dives into the golden sprint and drills an epic") was red two nights and no PR gate
  saw it; e2e runs only nightly/on demand. Auditor-verified via `gh run view --log-failed`; the lead re-checked the run
  list (10-19/10-20 schedule failures, green since). Fix: either a PR-time smoke subset (tier C, ~8 min per PR) or a failure
  notification (D8-5). `[C2/B5]`
- **D8-4 / D5-2 / D5-3** (8, 5, S, new) `server/build.gradle.kts:126,132` (Kover `minBound` 98/82, comment dated
  2026-10-06); `web/vite.config.ts:94-101` (98/97/95/93, comment cites the 10-06 actuals): floors unraised in 489 commits;
  actuals today Kover 98.82/85.68, vitest 98.45/93.90/98.50/99.06. Fix: raise Kover branch 82 to 85; vitest statements 97
  to 98, functions 95 to 98 (keep branches 93 and lines 98, there is 0.9/1.06 headroom); refresh the comments with today's
  actuals. `[A2]`
- **D8-1** (8, S, new) `.github/workflows/ci.yml:15-17` (`group: ci-${{ github.ref }}`, `cancel-in-progress: true`) vs the
  comment at `:26` ("every master commit ... is fully verified"): 9 of the last 40 master push runs were cancelled by the
  next merge, so a release commit may never finish CI. Fix: `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`.
  `[A6]`
- **D8-2** (8, S, new) `scripts/gates.sh:56-61` vs `ci.yml:213,237,242,246` and the `k8s-static`/`gradle-vulnerability-scan`
  jobs: `e2e_static` omits `npm run test:setup` and `check-toolchain-pins.mjs`; `npm audit` (both workspaces),
  kubeconform/`replicas` and the lockfile Trivy scan have no local counterpart although `gates.sh` is billed as "the way CI
  runs them". Fix: add `test:setup` and the pins check to `e2e_static`; list the CI-only scans in the script header (and
  optionally a `gates.sh scans` target when Trivy/kubeconform are installed). `[A6]`
- **D8-3** (8, S, new) `web/package.json:18` (`spectral lint ... --ruleset ...`, no `--fail-severity`);
  `api-guidelines/api-guidelines.spectral.yaml` has 14 `severity: warn` rules incl. `security-is-declared`,
  `paging-params-are-refs`, `operation-declares-500`, `input-operation-declares-400`, `created-response-sets-location`:
  only error-severity findings fail `lint:api`; a new warn-level violation passes CI silently. Only the two `x-sla`/
  `termsOfService` warnings are decided. Fix: tier C decision: a local ruleset in `web/` that `extends` the shared one and
  sets the two registered-gap rules to `off`, then `--fail-severity=warn`; or leave as is and accept the blind spot. `[C3]`
- **D8-5** (8, M, new) `.github/workflows/e2e.yml` (no step on `failure()` beyond artifact upload), `ci.yml` `images`:
  nothing notifies on a red nightly or a red master `images`; the second checkup's six-day red `images` job is mitigated
  by #95, not eliminated; the 10-19/10-20 nightly reds went unnoticed until the next day. Fix: an `if: failure()` step that
  opens/updates one GitHub issue (no secret needed), or a documented daily glance. `[B5 = C2]`
- **D10-1** (10, S, new) `openapi/documentation.yaml:5-13`: `info.description` still says "This scaffold exposes the generic
  foundation ... Jira-analytics features land on top" and that ADMIN "owns the whole `/flow-users` and `/teams` surfaces",
  with 91 operations, `GET /teams*` any-authenticated and `requireSelfOrAdmin` on `/flow-users/{id}/...`. Fix: rewrite the
  intro (five lines). `[A5]`
- **D10-2** (10, S, new) `documentation.yaml:4` `version: 1.0.0`; `servers` only `http://localhost:8084`: the spec version
  never moved while the app is at 1.14.0; nothing states whether it tracks the app or an API contract. Fix: tier C: pick
  the rule (`APP_VERSION` mirrored by a test, or a stated `v1` contract version) and document it in `conventions.md`. `[C4]`
- **D10-3** (10, S, new) `plugins/ErrorHandling.kt:163-168` vs `components.responses.InternalServerError`: the report-budget
  timeout answers a 500 whose `detail` asks to "narrow the selection and retry" (`reports.md` "Query budget"), but the
  shared 500 and the 34 report operations never say so; a client cannot tell a timeout from a fault. Fix: one sentence on
  the shared 500 or the `reports` tag. `[A5]`
- **D10-4** (10, S, new) `plugins/ErrorHandling.kt:286`, `authz/Exceptions.kt:46`: `BadGatewayException` mapped to 502 but
  thrown nowhere, and the spec declares no 502 (`documentation.yaml:1163` says the probe never answers 502). Fix: delete the
  class and the mapping (`AnonymousAccessTest`/conformance stay green). `[A5]`
- **D11-1 / D11-2** (11, S, new) `metrics/CapacityKernels.kt:126` (`defaultCalendar`, 0 production callers, 5 test refs);
  `reports/CostCapacity.kt:61` (`exactFor`), `reports/ComparePeopleKernels.kt:73` (`sampleGated`, referenced only by KDoc and
  `ComparePeopleKernelsTest.kt:133`): production members only tests call; the tests grade code the product never runs. Fix:
  make the production path call them or move/delete with their tests. `[A9]`
- **L-3** (lead, live; 5, S, new; related BACKLOG "A drill link without a team loses its drill") `e2e/tests/reports.spec.ts:884-888`
  (`readTaskEstimates`: `openGroup(page, "Estimation")` then the "Task estimates" tab, expecting `teamId=${teamId}`);
  `web/src/components/ReportTabs.tsx` carries the CURRENT team in the tab link (`ReportTabs.test.tsx:56`): the journey opens
  the Estimation group without a `teamId`, so the Task-accuracy page normalises to the default team (the first active team by
  name) and the tab hop carries that team; under 4 parallel workers another spec's freshly created team (id 15) sorted first
  and the assertion got `teamId=15` instead of the fixture's 11. 1 failure in 181 with 4 workers, 3/3 green alone. Fix: open
  the group with the explicit team (`openGroup(page, "Estimation", `&teamId=${teamId}`)` like `openReport` does) and assert
  the team before the hop; audit the other `openGroup` callers for the same reliance. `[A13]`
- **D7-1** (7, M code / S doc, new): the refresh token has no rotation or reuse detection; see the security section.
  `[C8; doc half A15]`

### Low

- **L-1** (lead; 1, S, new) `CLAUDE.md:140`: "`Health` (`/api/v1/health`, `/ready`)"; the probe is `/api/v1/ready`
  (`plugins/Health.kt:47`, both k8s readiness probes); a plain `/ready` answers the SPA's `index.html` (200), observed
  live. Fix: fix the path. `[A4]`
- **L-2** (lead; 5, S, new; the user's memory notes it as a gotcha, not in BACKLOG before) `e2e/tests/mfa.spec.ts:17`,
  `e2e/tests/password-reset.spec.ts:9` (`const MAILPIT = "http://localhost:8028"`): the Mailpit URL is hard-coded to the dev
  stack while `E2E_BASE_URL` is configurable; on any isolated stack these two specs detect the dev Mailpit as "up", run,
  read the dev stack's mailbox and fail. Fix: `E2E_MAILPIT_URL` with the 8028 default, mentioned in `e2e/README.md`. `[A13]`
- **D1-6** (1, S) `.claude/docs/observability.md`: `team.jira_membership_updated` and `team.jira_membership_removed` are
  emitted (`metrics/` routes) but not listed; the other names the auditor flagged resolve via the doc's `.suffix`
  shorthand. Fix: add the two. `[A4]`
- **D1-7** (1, S) `BACKLOG.md:3` "Updated 2026-10-08" vs last commit 2026-10-22: stale date. Fix: bump. `[A4]`
- **D1-10** (1, S) `.claude/docs/ci.md:7-13`: job ids `changes`, `gradle-vulnerability-scan`, `web`, `e2e-static` never named.
  Fix: add them. `[A4]`
- **D2-6** (2, S) `jira/Jira{Changelog:17,Issues:15,Reference:17,Reconcile:13,Worklog:15}Stream.kt`: five identical
  `Json { encodeDefaults = true; ignoreUnknownKeys = true }` + four private `encode(cursor)`. Fix: one
  `CURSOR_JSON`/`encodeCursor` in `jira/`. `[A7]`
- **D2-7** (2, S) `reports/EpicAccuracyReport.kt:97`, `reports/TaskAccuracyReport.kt:81`: two `ratioOf` with subtly different
  guards. Fix: name the intent apart or share. `[A7]`
- **D2-8 / D2-9** (2, S) `web/src/utils/dataSourceForm.ts:50,57` (`todayIso`, `.toISOString().slice(0,10)` vs
  `utils/isoDate.ts:52`), `web/src/utils/homeOverview.ts:54` (`86_400_000` vs `DAY_MS`): re-rolled primitives. Fix: import
  the canonical ones. `[A8]`
- **D2-10** (2, S) 25 files with `<Alert color="red" variant="light">{error}</Alert>` for form/action errors: `ErrorAlert`
  fits queries, not strings; the string case is hand-rolled ~20x. Fix: `FormErrorAlert`. `[A8]`
- **D2-12 / D2-13 / D9-6** (2, 9, S) `ingest/Connector.kt:24-30` (Jira credentials in the "agnostic" interface, parked D6);
  `PackageBoundaryTest.kt:36-54` covers only `ingest/norm/jira` to `metrics/reports` and `metrics` to `reports`;
  `infra/outbound/OutboundGuard.kt:4-5` imports `ingest` constants; `teams` and `metrics` form a cycle
  (`teams/TeamService.kt:6-7`; documented `persistence.md:148`): boundary rules thinner than the architecture they protect.
  Fix: move the two constants to `infra`; extend the test to `infra` to `{reports,metrics,jira,ingest}`; document the
  composition-root exceptions. The Connector seam stays parked (D6 known; the rest new). `[B6]`
- **D3-4 / D9-8** (3, S) `plugins/Role.kt:48-50`: KDoc says "arriving in commit 5 of the phase-2 plan, no consumer reads
  this yet"; `IngestWorker.kt:62` reads it. Fix: reword. `[A4]`
- **D3-5** (3, S) `metrics/MetricsConfigService.kt:111,262`; `metrics.team_sprint_capacity` (V15, left unread by V24): a
  dead table kept only so PURGE can drain it. Fix: a migration (V33) dropping the table + the object + the purge line
  (irreversible, hence a decision). `[A11, decisions item]`
- **D3-7** (3, S) `metrics/WorkingCalendar.kt:101,109`: `zoneOf` falls back to UTC and `of` skips unparseable holidays
  silently although the write path validates; a masking branch. Fix: fail loudly or log once. (Not tiered in the report.)
- **D3-9** (3, M) the four files within 10 lines of 400 (see D3-1): the next feature trips the ratchet. Fix: plan the
  splits. `[B4]`
- **D4-1** (4, S; downgraded from medium: the documented rule's scope excludes these writes)
  `norm/NormReferenceStore.kt:74,91,110,120,139`, `metrics/MetricsConfigService.kt:171-237` (13 `batchInsert` sites):
  `persistence.md:667` scopes the `insertRows` rule to the metrics star's `insertX`; the PROCESS reference rebuild
  (`People` can be thousands of rows) still pays exposed-r2dbc's per-row statements. Fix: switch `People`/`Sprints`/
  `Statuses` to `insertRows`, or add one doc line exempting config/reference writes. (Not tiered in the report.)
- **D4-2** (4, S) `plugins/Health.kt:62`: the readiness probe reads `FlowUserService.FlowUsers`; `persistence.md`'s
  cross-feature list ("the list IS the permission") has no entry. Fix: add it. `[A4]`
- **D4-3** (4, S, unverified) `V16`, `V22`, `V24` each have two commits (feature + same-PR review fix): fine if no release
  sat between; the pins match. Fix: confirm from tags; nothing else.
- **D5-4** (5, S) `server/test-weights.tsv` (source run 2026-10-22, 203 rows): 7 of 210 classes missing (`BusyTrackerTest`,
  `DeriveWorkCategoryFieldTest`, `MonitoringReporterConfigTest`, `NormDerivationReadsTest`, `ReportCostsCategoriesTest`,
  `TeamWorkCategoryTargetsTest`, `TeamWorkCategoryTargetsValidationTest`). Fix: `node scripts/timings/local-times.mjs
  test-weights` after a full run. `[A3]`
- **D5-6** (5, M) ~46 `web/src/components` files > 80 lines with no direct test (`DataQualitySummary.tsx` 271,
  `SprintCalendarEditor.tsx` 225, `useDeepDiveTooltip.ts` 177, ...): covered only through page tests (line coverage 99 %).
  Fix: direct tests for the branchiest; otherwise accept. `[B9]`
- **D5-7** (5, S) `web/src/utils/printing.ts:9` and other module-level state (`auth.tsx:25`, `api/http.ts:55`,
  `api/session.ts:30`, `utils/metricsForm.ts:25`): under `isolate: false` any un-reset module state can leak; shuffle has
  not caught one. Fix: verify each has an `afterEach` reset. `[B9]`
- **D5-8** (5, M) `e2e/tests/reports.spec.ts`: 113 `.first()/.nth()/.last()`; brittleness risk (the 10-19 failure was a
  strict-mode violation there). Fix: prefer role/name locators where feasible. `[D, opportunistic]`
- **D6-1** (6, S) `web/src/components/AgingItemsTable.tsx:149` `Badge color="grape"`: outside `web/CLAUDE.md`'s colour
  vocabulary (documented in `web-features.md:618` and `chartColors.ts:62` only). Fix: add "grape = waiting" to the
  vocabulary bullet. `[A4]`
- **D6-2 / D6-3** (6, S) `UserMenu.tsx:162` red filled what's-new dot; `RawIssueInspector.tsx:201,206` red "deleted"/"moved
  out" badges: red means blocking; these are informational/soft. Fix: gray/blue dot; orange or gray badges, or document.
  (Not tiered in the report; fits the UI polish item.)
- **D6-4** (6, S) `UserMenu.tsx:130-140`: theme `SegmentedControl` unnamed; language items show the choice by icon only (no
  `menuitemradio`/`aria-checked`). Fix: `aria-label` + `role="menuitemradio"`. `[A12]`
- **D6-5** (6, S) `HomeTeamBarsChart.tsx:39,72`, `AgingScatterChart.tsx:90` literal `"gray.6"`: not from `chartColors.ts`.
  Fix: named constant. `[A12]`
- **D6-6 / D6-7** (6, S) `ReportTile.tsx:24`, `DataQualitySummary.tsx:36,53`, `AgingThresholdsRow.tsx:35`;
  `EpicProgressRows.tsx:61`, `EpicPlanPanel.tsx:97`: three KPI figure styles; two h4s without `size="h5"`. Fix: one
  `KpiFigure`; pass `size`. `[A12]`
- **D6-8 / D6-9 / D6-10 / D6-11** (6, S) PL "pozycja" (68 keys) vs "element" (14); `reports.excluded.population` plural in
  PL but not EN; `reports.empty`/`metrics.teamMembers.empty` state only the absence; 18 of 118 description keys lack
  terminal punctuation: concrete, provable i18n polish (distinct from the parked glossary pass 2C8). Fix: unify on
  "pozycja"; EN `_one/_other`; add the next-action hint; one punctuation rule. `[A12]`
- **D6-12** (6, S, contrast unverified) `ComparePeopleTable.tsx:142-143`, `FilterPanel.tsx:58` raw `var(--mantine-color-...)`
  on `bg`/`c`: off the AA-token path. Fix: theme props/tokens. (Not tiered in the report.)
- **D8-8** (8, S) `ci.yml:325` vs `:96`: image scans use `--ignore-unfixed`, the lockfile scan does not. Fix: document the
  asymmetry. `[D]`
- **D8-10** (8, L) detekt 1.23.8 without type resolution: `IgnoredReturnValue`, `SuspendFunSwallowedCancellation`,
  `UnsafeCallOnNullableType`... invisible. Fix: revisit at detekt 2.x. `[D]`
- **D8-11** (8, S) Kotlin build: no `allWarningsAsErrors`; warning count unverified. Fix: count; enable if zero. (Not
  tiered in the report.)
- **D8-12** (8) a web file-size guard (the server has `SourceFileSizeTest`, the web has none). Optional. `[D]`
- **D8-13** (8, S) `web/eslint.config.js:16` scans `**/*.{ts,tsx}` only: `web/scripts/check-api.mjs` (a gate script) is never
  linted; the e2e config covers `.mjs`. Fix: add `**/*.mjs`. `[A6]`
- **D8-15** (8, S) shuffle seed random per run: order dependence surfaced after merge once (10-20). Fix: print the seed in
  the job summary. `[A6]`
- **D9-5** (9, S) `k8s/*-deployment.yaml` (no `terminationGracePeriodSeconds`), `plugins/OpenTelemetry.kt:14-18`: STOP_WAIT
  20 s + SDK close <= 10 s = exactly the 30 s default, zero margin, and the worker's 5 s join also fits inside it. Fix:
  `terminationGracePeriodSeconds: 45`. `[A10]`
- **D9-7** (9, S) `core/src/commonMain/kotlin/OpenTelemetry.kt:20-22`, `plugins/OpenTelemetry.kt`: exporters are `none` while
  `KtorServerTelemetry` creates and discards spans; the Dropwizard registry is dumped to the log every 300 s. Deliberate
  interim (`observability.md:18-20`) with no backlog item. Fix: tier C: OTLP via env when a collector exists, or drop the
  unused telemetry. `[C6]`
- **D9-9** (9, S) 18 FK columns without a leading index (`metrics.fact_task_delivery` team FKs x4, `fact_worklog` x3,
  `owner_team_id`/`team_id` on the sprint/epic/domain facts and dims, `updated_by_user_id` x4,
  `sync_jobs.requested_by_user_id`): parents are soft-deleted only, so no cascade scan today. Fix: none until hard deletes
  appear. `[D]`
- **D10-5 / D10-6 / D10-7** (10, M, known) 429 without `Retry-After`; `CallId` without `replyToHeader`; no `ETag`:
  registered guideline gaps / draft PR #75.
- **D10-8** (10, S) `api-guidelines/API-GUIDELINES.md` register row API-ERR-007: says duplicate 409s identify only the
  failing request; `DataSourceScopeConflictException` (`ingest/DataSourceScope.kt:67-74`) now sets `instance` to the
  conflicting resource. Fix: narrow the row. `[A4]`
- **D10-9 / D10-10 / D10-11 / D10-12 / D10-13 / D10-14** (10, S/M; D10-11 partly known via #94) `ResourceId` `int32, minimum:
  0` (ids are `UInt`, 0 never exists, D10-9); compare-people items params inline instead of `$ref` (D10-10); unbounded
  strings/arrays (`timeZone`, `refreshToken`, `code`, `weekendDays`, `statusStages`..., unverified against server limits,
  D10-11); 7 tags without descriptions (D10-12); 1 `example` in 7,710 lines (D10-13); no `GET jira-memberships/{membershipId}`
  (D10-14): spec polish. Fix: `minimum: 1`; shared refs; `maxLength`/`maxItems` where enforced; tag descriptions; examples
  on the hot schemas. `[A14: D10-9/10/12; B7: D10-11]`; D10-13/14 not tiered.
- **D11-3 / D11-4 / D11-6 / D11-7 / D11-8** (11, S; `hasFeature`/`requireFeatureEnabled` are the decided keep)
  `ingest/DataSourceScope.kt:70` `conflictingName` unread; `recordReconcileFailed`, `ingestWorkerStarted`,
  `worstCaseConnectWaitSeconds`, `compareRow`, `homeWindows`, `progressSnapshot` test-only; `session.ts:77,93`
  `getRoles`/`getDisabledFeatures` and `metrics.ts:145` `withDeadline` exported for tests only; `GET
  /data-sources/{id}/sync-jobs/{jobId}` has no web/e2e caller; `"login.rejected_locked"` x3, `"login.mfa_failure"` x2,
  `"login.capacity_rejected"` x2 literals: small smells knip/detekt cannot see. Fix: judge each: un-export, drop, or keep
  as a documented test oracle; constants for the three event names. `[A9]`

### Security (dimension 7)

**Scope (security-auditor, re-read by the lead):** 91 handlers reconciled one-to-one with the 91 spec operations; guard
classes ADMIN 36, manager-or-admin 2, self-or-admin 3, conditional 1 (`jira-users`: UNIT any-authenticated / SITE ADMIN),
any-authenticated 43 (34 of them report operations), anonymous 6, exactly the six `security: []` operations (health, ready,
login, login/mfa, refresh, password-reset) and the only handlers outside `authenticate {}`. Every ADMIN route calls
`requireAdmin` before `call.receive()` and before the id lookup; the live USER probes confirmed 403-before-404 and
403-before-400 on every sampled surface. Grants that must be live (team manager, Compare people's capacity block) are read
from the DB per request and re-checked inside the write transaction (`metrics/SprintCalendarService.kt:326-329`,
`reports/ComparePeopleReport.kt:144-148`). Token model verified: HS256 pinned, `aud`/`iss`/`typ`/`jti` checked, 30 s verdict
cache seeded by revoke, refresh checks revision equality and `user_gone`; fail-closed boots for the JWT key, the encryption
key, the `log` mail transport, the stub URL and the seed password; lockout, reset throttle and MFA bounded and audited; the
five per-IP buckets match `security.md`; CSP/nosniff/DENY/no-referrer/`no-store` pinned; one guarded outbound client,
redirects refused, private ranges blocked incl. IPv4-mapped/NAT64; the Jira token encrypted at rest and write-only; no
tracked `.env`; containers non-root with read-only root and no SA token. #96's hardening is all present (statement budget,
`no-cache`, `/openapi` behind `servesApi()`, audited 429s, `Server: flow`).

**Verdict:** authn/authz **Yes**; wider security **Mostly**. The known items 2D3, 2D4, A13,
instance-local auth state and the HEAD sweep were re-verified unchanged and are not re-reported.

- **D7-1** (7, medium, M code / S doc, new) `auth/AuthRoutes.kt:375-416` (`/refresh`: "a fresh pair is minted and the old
  tokens are left to expire on their own (not revoked)"), `:470-511` (`/logout` revokes only the two tokens the client
  presents); `.claude/docs/security.md:69-71`: the doc calls the localStorage refresh token "revocable through `/logout` or
  a password change/reset"; in the code `/refresh` never blocklists the presented refresh jti and there is no reuse
  detection, so a refresh token read once off localStorage yields a chain the victim's sign-out does not end; only a
  password change (`credential_revision`) or an admin delete does. `authorization.md:33-34` states the no-rotation choice;
  `security.md` overstates the remedy. Fix: tier C decision: refresh rotation (blocklist the presented refresh jti on each
  `/refresh`; a replay then audits as `refresh.rejected revoked`) and/or a `credential_revision` bump on sign-out ("sign out
  everywhere"); at minimum (tier A) correct `security.md:69-71`. Test: a refresh-reuse case in `RefreshTest`. `[C8; doc half
  A15]`
- **D7-2** (7, low; downgraded by the lead from medium, S, new) `plugins/Health.kt:47-64` (`/api/v1/ready`,
  `READINESS_TIMEOUT_MS` 3 s, one pooled round trip), `plugins/RateLimits.kt:57-64` (five buckets, none here),
  `application.yaml:313,322` (pool max 20, acquire 10 s), `k8s/network-policies.yaml:56-61` (port-only ingress rule): an
  unauthenticated, unbucketed endpoint that takes a pool connection per call; `authorization.md:40-43` presents the probes
  as harmless. Live on `flow-check`: 600 `GET /api/v1/ready` at 60 concurrent answered 600x200 while authenticated `GET
  /teams` stayed at 2-3 ms; the pool saturation needs a slow database, so the impact is NOT demonstrated (a hardening item,
  not a shown DoS). Fix: a per-IP bucket on `/api/v1/ready` (the kubelet needs 6-12/min) or memoise the verdict for ~1 s;
  test: a burst of N calls answers 429 after the bucket, and a `BlocklistOutageTest`-style proof that authenticated calls
  stay fast. `[A16, decisions item]`
- **D7-3** (7, low, S) `server/src/test/kotlin/AnonymousAccessTest.kt:101-102`: `MIN_SECURED_OPERATIONS = 52` ("56 operations
  at v0.3.0") while 85 of 91 are secured; 33 operations could vanish from the secured set before the tripwire fires (the
  sweep itself still covers every operation). Fix: raise to ~80, refresh the comment. `[A15]`
- **D7-4** (7, low, S) `plugins/SecurityHeaders.kt:41`, `plugins/Http.kt:132-135`; no test mentions
  `Strict-Transport-Security` or `Permissions-Policy`: `security.md:71-75` says the header set is "pinned by `ServerTest`";
  `Permissions-Policy` is emitted (seen live) but in neither the doc nor a test, and HSTS (production-only) is pinned by no
  test. Fix: add both to `ServerTest` (HSTS via a production-mode boot with the strong-key overrides); list
  `Permissions-Policy` in `security.md`. `[A15]`
- **D7-5** (7, low, S) `ingest/DataSourceService.kt:184-190` (`StoredJiraConnection.apiToken`), `auth/AuthRoutes.kt:63-93`
  (login/MFA/refresh/logout requests), `flowusers/FlowUser.kt:34,116` (`passwordHash`, `PasswordUpdateRequest`): default
  `toString()` on secret-bearing classes; only `JiraConnectionRequest` masks (`ingest/DataSource.kt:72-74`). No log line
  templates them today (grep clean); defence in depth. Fix: masked `toString()` overrides on the `JiraConnectionRequest`
  precedent. `[A15]`
- **D7-6** (7, low, S) `.claude/docs/security.md:238` "all 20 today": 25 body-taking operations declare 413 now; the count
  is complete, the figure stale. Fix: say 25 or drop the number. `[A15]`

Named test gaps: no refresh-rotation/reuse test (D7-1), no HSTS/`Permissions-Policy` pin (D7-4), no `/ready` flood or
bucket test (D7-2), no HEAD sweep (known).

### Dropped or downgraded by the lead

- D9-10 (liveness `timeoutSeconds` default 1 s): `k8s/web-deployment.yaml:150` documents the choice; dropped.
- D5-5 (two tests "without asserts"): `reconcile(...)` in `ReportComparePeopleDrillTest.kt` asserts four times; dropped.
- D4-1 downgraded to low (the documented rule's scope excludes these writes).
- D3-3, D11-5 (`hasFeature`/`requireFeatureEnabled`): the second checkup's decided keep; recorded, not a finding.
- D2-12 (Connector seam): BACKLOG `[parked] D6`, known.
- D7-2 (`/api/v1/ready` pool exhaustion) downgraded to low: the live flood (600 calls, 60 concurrent) left authenticated
  requests at 2-3 ms.

## Tiers

Commit granularity: one concern per commit, grouped into PRs; every PR runs `scripts/gates.sh all` (with `FLOW_FORKS=2`),
docs-only PRs skip the server job by the Markdown rule. Each BACKLOG title below is "Checkup 3, <ids>: ..." under the
`BACKLOG.md` section "Checkups — what is left"; remove a BACKLOG item when its PR merges and list the PR under "Fixed so far".

### Tier A: autonomous quick fixes (S each; two or three PRs)

- **A1** worker permit leak (D9-1): `IngestWorker.claimAvailable` releases the permit on a throwing `claim()`; test:
  `IngestWorkerTest` "a claim that throws does not consume a slot" (a `SyncJobs` fake throwing once, then the tick claims).
  Opus `reviewer` (the worker queue is where the missing bug hides). BACKLOG: "Checkup 3 (2026-10-23), A1".
- **A2** floors (D8-4): Kover branch 82 to 85; vitest statements 97 to 98, functions 95 to 98; comments re-dated with today's
  actuals. Test: `koverVerify`/`test:coverage` green. BACKLOG: "Checkup 3, A2+A3".
- **A3** test weights (D5-4): `node scripts/timings/local-times.mjs test-weights` after the full run. BACKLOG: "Checkup 3, A2+A3".
- **A4** docs drift batch (D1-1, D1-3, D1-4, D1-5, D1-6, D1-7, D1-10, L-1, D3-4, D6-1, D4-2, D10-8): one docs commit;
  `MeasureContractTest` unaffected. Test: `check-toolchain-pins` and a doc path resolver report zero misses. BACKLOG:
  "Checkup 3, A4+B8".
- **A5** spec text (D10-1, D10-3, D10-4): rewrite `info.description`, one sentence on the shared 500, delete
  `BadGatewayException` + mapping; `npm run gen:api` + `check:api` in the same commit; conformance suite pins it. BACKLOG:
  "Checkup 3, A5+A14+B7".
- **A6** CI (D8-1, D8-2, D8-13, D8-15): `cancel-in-progress` for PRs only; `gates.sh` `e2e_static` gains `test:setup` + the
  pins check and a header listing the CI-only scans; eslint covers `web/scripts/*.mjs`; the vitest seed printed to the job
  summary. BACKLOG: "Checkup 3, A6".
- **A7** DRY server (D2-1, D2-2, D2-3, D2-6, D2-7): `PickedDistributions<T>`, one `requireActiveTeam`, the `sprintWindow`
  rename/order unification (test pins the parameter order), one cursor JSON, `ratioOf` disambiguated. Existing report/digest
  tests pin behaviour (`MetricsDigestTest` must stay equal). BACKLOG: "Checkup 3, A7+A9".
- **A8** DRY web small (D2-8, D2-9, D2-10): `todayIsoDate`/`DAY_MS` imports, `FormErrorAlert`. BACKLOG: "Checkup 3, A8+A12+B3".
- **A9** dead/test-only members (D11-1, D11-2, D11-3, D11-6, D11-8): move or delete with their tests; un-export; three
  audit-event constants. Test: knip/detekt green, Kover not lowered. BACKLOG: "Checkup 3, A7+A9".
- **A10** infra pins (D9-4, D9-5): a `JvmMemoryFlagsTest` scanning `build.gradle.kts`, both k8s deployments and both compose
  files for `-Xmx` <= limit, `ExitOnOutOfMemoryError` present, `DisableExplicitGC` absent; `terminationGracePeriodSeconds: 45`
  (kubeconform in `k8s-static` pins the YAML). BACKLOG: "Checkup 3, A10".
- **A11** legacy table (D3-5): V34 drops `metrics.team_sprint_capacity`, removes the object and the purge line;
  `MigrationChecksumTest` pin. Irreversible, so it sits in BACKLOG "Checkup 3, decisions for the user" (A11).
- **A12** UI polish (D6-4, D6-5, D6-6, D6-7, D6-8, D6-9, D6-10, D6-11): labels, constants, one `KpiFigure`, PL "pozycja", EN
  plural, empty-state hints; `parity.test.ts` and `theme.test.ts` pin. BACKLOG: "Checkup 3, A8+A12+B3".
- **A13** e2e (L-2, L-3): `E2E_MAILPIT_URL` with the 8028 default; `openGroup` takes the explicit team; README line. Test:
  the suite on an isolated stack with 4 workers, twice. BACKLOG: "Checkup 3, A13".
- **A14** spec polish (D10-9, D10-10, D10-12): `ResourceId minimum: 1`, shared drill refs, tag descriptions; `gen:api`/
  `check:api`. BACKLOG: "Checkup 3, A5+A14+B7".
- **A15** security small (D7-3, D7-4, D7-5, D7-6, D7-1 doc half): `MIN_SECURED_OPERATIONS` ~80; HSTS + `Permissions-Policy`
  pinned in `ServerTest` (production-mode boot with the strong-key overrides); masked `toString()` on the secret-bearing
  classes; `security.md` says 25 body operations and states the refresh token's real lifecycle (no rotation; `/logout`
  revokes the presented pair only). Opus review. BACKLOG: "Checkup 3, A15".
- **A16** ready-probe bucket (D7-2): a per-IP bucket on `/api/v1/ready` (or a 1 s memoised verdict) with a burst test; the
  kubelet's 6-12/min stays far under it. Decision item (A16).

### Tier B: autonomous, larger (M)

- **B1** int32 to bigint (D9-3): after the user's `last_value` reading: one migration widening the eleven `SERIAL` ids and
  sequences; a scan test that no `SERIAL` lands in `metrics.*`/`norm.*`; measure the rewrite on the scale-20 overlay first.
  Decision item (C5/B1).
- **B2** worker tick-age readiness (D9-2): `/api/v1/ready` on WORKER/ALL checks the last tick age; pinned-clock test.
  BACKLOG: "Checkup 3, B2".
- **B3** report page scaffolding (D2-4, D2-5): `useHistogramDrill`, `ReportPageShell`/`ReportGroupsSection`; page tests
  unchanged; shuffle twice. BACKLOG: "Checkup 3, A8+A12+B3".
- **B4** splits (D3-1, D3-9): `MetricsDeriver` by step; pre-split the four near-cap files; ceilings lowered in
  `SourceFileSizeTest`. BACKLOG: "Checkup 3, B4".
- **B5** failure alerting (D8-5): an `if: failure()` step on `e2e.yml` and `images` that opens/updates a GitHub issue.
  Decision item (C2/B5; outward-facing on the public repo).
- **B6** boundary test (D2-13, D9-6): `infra` to feature rules, the two `OutboundGuard` constants moved to `infra`,
  composition-root exceptions documented. BACKLOG: "Checkup 3, B6+B9".
- **B7** spec bounds (D10-11): `maxLength`/`maxItems` mirrored from the validators; `PayloadValidationTest` cases per bound.
  BACKLOG: "Checkup 3, A5+A14+B7".
- **B8** always-loaded set (D1-2): move the consumer ledger from `list-endpoints.md` into `reports.md`; target <= 36 KB;
  cross-references kept. BACKLOG: "Checkup 3, A4+B8".
- **B9** direct tests for the branchiest untested components (D5-6); `afterEach` resets audited (D5-7). BACKLOG: "Checkup 3,
  B6+B9".

Not tiered in the report (fold into the nearest PR or leave): D3-7, D4-1, D4-3, D6-2/D6-3, D6-12, D8-11, D10-13, D10-14.

### Tier C: needs a user decision

Decided by the user 2026-10-23 (all but C1 and C9); each shipped item is listed under "Fixed so far".

- **C1** A1 branch protection (known): require `server`, `web`, `e2e-static`, `gradle-vulnerability-scan`, `k8s-static`; no
  bypass. Decision: do it, and how to treat the Markdown-skipped `server` check.
- **C2** PR-time e2e (D5-1, with B5/D8-5): DECIDED, nightly + alerting only (no per-PR subset); shipped as B5, see "Fixed so far".
- **C3** Spectral posture (D8-3): DECIDED, a Flow-local ruleset (`web/spectral.flow.yaml`) + `--fail-severity=warn`.
- **C4** spec version rule (D10-2): DECIDED, `info.version` = `APP_VERSION` (test-pinned).
- **C5** int32 timing (D9-3, with B1): DECIDED, the dev `last_value` read (2026-10-23: `item_stage` 8.3 M after ~26 days,
  ~18 years of headroom at that rate) and B1 now, as insurance.
- **C6** telemetry (D9-7): DECIDED, keep `KtorServerTelemetry`, a collector is coming; the OTLP exporter is a `[blocked]`
  BACKLOG item until it exists.
- **C7** `FeatureFlags` page (D3-2): DECIDED, simplify (the bulk action stays).
- **C8** refresh-token rotation (D7-1): DECIDED, rotation (sign-out-everywhere may follow later).
- **C9** known decisions still open: the R2DBC flake investigation (invest now or keep logging under WHY 24), Dependabot
  alerts (owner's call), 2D3/2D4, A13.
- Plus A11 (drop the legacy table) and A16 (the `/ready` bucket) from tier A, as above.

### Tier D: parked / advice

- 2C8 Polish glossary (native speaker), A13 ingress, 2D3/2D4, PR-time image scan: unchanged. (The per-user report bucket shipped 2026-10-23, 1.14.4.)
- D8-10 detekt type resolution: wait for 2.x. D8-12 a web file-size guard: optional. D9-9 FK indexes: none until hard
  deletes. D8-8 scan asymmetry: a doc line. D5-8 locator brittleness: opportunistic.
- Memory: at stub scale the `all` role sits at ~225-230 MiB (anon 239 MB) with no visible give-back after 120 s idle; the
  `[next]` memory deep dive should measure the scale-20 overlay, not the stub.

## Fixed so far

List each fixing PR here as it lands (tier ids, PR link, one line), and remove its BACKLOG item. The record itself is
[#277](https://github.com/liveweird/flow/pull/277). Every autonomous item is done; what is open is listed after the list.

- **A1** (D9-1), [#285](https://github.com/liveweird/flow/pull/285): a claim that throws gives its worker slot back; start-write failures and shutdown handled.
- **A4 + B8** (D1-1, D1-3..D1-7, D1-10, L-1, D3-4, D6-1, D4-2, D10-8; D1-2), [#279](https://github.com/liveweird/flow/pull/279): docs drift batch; the always-loaded set back under 36 KB (the consumer ledger moved from `list-endpoints.md` to `reports.md`).
- **A5 + A14 + B7** (D10-1/3/4/9/10/11/12), [#280](https://github.com/liveweird/flow/pull/280): the spec describes Flow 1.x; shared drill refs, ids from 1, `maxLength`/`maxItems` mirrored from the validators.
- **A6** (D8-1, D8-2, D8-13, D8-15), [#278](https://github.com/liveweird/flow/pull/278): master runs never cancelled; local gates match CI; the shuffle seed printed.
- **A7 + A9** (D2-1/2/3/6/7, D11-1/2/3/6/8), [#281](https://github.com/liveweird/flow/pull/281): one distribution split, one active-team check, distinct window names; test-only members used or moved.
- **A8 + A12 + B3** (D2-4/5/8/9/10, D6-4..D6-11), [#286](https://github.com/liveweird/flow/pull/286): one form error alert, a histogram drill hook, a report page shell; UI polish (app 1.14.1).
- **A10** (D9-4, D9-5), [#282](https://github.com/liveweird/flow/pull/282): the JVM memory sizing pinned by a test; k8s pods get 45 s to shut down.
- **A13** (L-2, L-3), [#283](https://github.com/liveweird/flow/pull/283): Mailpit specs take `E2E_MAILPIT_URL`; team-required reports open with their team.
- **A15** (D7-3..D7-6, D7-1 doc half), [#284](https://github.com/liveweird/flow/pull/284): secrets masked in `toString()`, HSTS pinned, the real refresh lifecycle documented.
- **B4** (D3-1, D3-9), [#287](https://github.com/liveweird/flow/pull/287): `MetricsDeriver.kt` 651 -> 249 lines (ceiling removed) over `DeriveRunLifecycle`/`DeriveContextBuilder`/`DerivePasses`/`DeriveAnalyzePolicy`; `MetricsConfigService` 398 -> 351 (`MetricsConfigDefaults.kt`), `DeriveTaskRows` 398 -> 267 (`DeriveAttribution.kt`), `SprintCalendarService` 389 -> 336 (`SprintCalendarWrites.kt`). `DataSourceService.kt` was left for after #284 (done below, B4 rest).
- **B4 (rest):** `DataSourceService` pre-split - PR pending: `DataSourceService.kt` 390 -> 282 lines (public/nested names unchanged) over `DataSourceReads.kt` (row -> response/status/decrypted shape), `DataSourceSchedule.kt` (the due scans) and `DataSourceOutcomes.kt` (the job-outcome writes), verbatim moves with an explicit `database`/`cipher` parameter.
- **B6 + B9** (D2-13, D9-6, D5-6, D5-7), [#288](https://github.com/liveweird/flow/pull/288): `infra` never imports a feature package; direct tests for the branchiest web components.
- **B2** (D9-2), PR [#289](https://github.com/liveweird/flow/pull/289): readiness sees a stalled worker.
- **C2/B5** (D5-1, D8-5), PR [#299](https://github.com/liveweird/flow/pull/299): decided by the user as nightly + alerting only (no PR-time e2e subset, to keep PR CI fast). A red nightly `e2e` run (schedule only) and a red `images` job on a master push each open or comment on ONE labelled GitHub issue via the reusable `.github/workflows/ci-alert.yml`; `ci.md` "Failure alerting". D5-1's root cause stays unidentified.
- **C5/B1 + A11** (D9-3, D3-5), PR [#303](https://github.com/liveweird/flow/pull/303): V33 widens the eleven `SERIAL` ids and sequences of the PROCESS/DERIVE-rewritten tables to `bigint` (`NarrowIdMigrationScanTest` bars new int32 ids in `raw`/`norm`/`metrics`); V34 drops `metrics.team_sprint_capacity` with its object and PURGE line.
- **C3 + C4** (D8-3, D10-2), PR [#300](https://github.com/liveweird/flow/pull/300): `lint:api` runs the Flow-local `web/spectral.flow.yaml` (extends the shared ruleset; only the registered gaps API-META-001/002 off) with `--fail-severity=warn`; `info.version` = `APP_VERSION`, pinned by `OpenApiSpecTest`.
- **C7** (D3-2), PR [#301](https://github.com/liveweird/flow/pull/301): `FeatureFlags.tsx` 387 -> 167 lines over focused hooks/components; behaviour unchanged, the bulk action kept.
- **A16** (D7-2), PR [#302](https://github.com/liveweird/flow/pull/302): a per-IP `ready` bucket (60/min, `READY_RATE_LIMIT_PER_MINUTE`) on `/api/v1/ready`; `/health` unbucketed.
- **C8** (D7-1), PR [#304](https://github.com/liveweird/flow/pull/304), 1.14.2: refresh tokens rotate (atomic jti claim), a 60 s replay grace (at most two re-mints, never after a logout), later replays `reason=replayed`; the SPA serialises refreshes across tabs and refreshes-then-retries a sign-out whose access token expired.
- **A2 + A3 + the docs-budget guard** (D8-4, D5-2/3/4), the wrap-up PR: Kover floors line 98 (actual 98.81) / branch 85 (actual 85.64, was 82); vitest floors lines 99 / statements 98 / functions 98 / branches 94 (actuals 99.19 / 98.56 / 98.57 / 94.42); `server/test-weights.tsv` refreshed from a full `-Pforks=2` run; `AlwaysLoadedDocsBudgetTest` fails when `CLAUDE.md` plus its two imported docs reach 36,000 bytes.

**Still open after the autonomous tiers:**

- Tier C: C1 branch protection and C9 the known open decisions (their own BACKLOG items); C6's OTLP exporter waits for the
  collector (`[blocked]`). Everything else in tiers A-C is shipped.
- The lows the report did not tier: D3-7, D4-1, D4-3, D6-2/D6-3, D6-12, D8-11, D10-13, D10-14.
- Tier D stays parked/advice as written above.

## Standing decisions (made on the user's behalf in earlier checkups; overturn any of them)

- `x-sla` and `info.termsOfService` are registered gaps in the guidelines; rather than the spec carrying values nobody owns
  (2A9), Flow's local ruleset `web/spectral.flow.yaml` switches the two rules off (C3, 2026-10-23).
- `jira-memberships` stays unpaged and is documented as deliberately so (2D6, [#94](https://github.com/liveweird/flow/pull/94)).
- `hasFeature`/`requireFeatureEnabled` stay: the documented feature-gate accessor (third checkup D3-3/D11-5 re-confirmed).
- A report query over budget is a **500** with a "narrow the selection" problem body, not a 503 (a client retry does not
  help). `rate_limit.exceeded` is coalesced to one event per bucket per minute and carries no client address. `Server:
  flow` replaces the framework's version header.
- The 400-line ratchet: every server main file at or under 400 lines, the old giants frozen at their size, a ceiling may only
  go down (`testing.md`).
- `replicas: 1` is pinned for the web tier only; the worker may scale (claims are serialised per connection).
- The statement-timeout cache lives below the pool and is conditional on one client connection = one server session (no
  transaction-mode PgBouncer).
- `ColumnTable`'s API was not widened: the 9 tables needing more stay hand-rolled.

## In flight

- [#75](https://github.com/liveweird/flow/pull/75) (draft): report cache validators (its atomic DERIVE success mark shipped on its own, 2026-10-23),
  waiting for the user's cache-posture sign-off.

## Parked / needs the user

No implementation scheduled until the user decides (reconciled with tiers C and D above):

- **A1** protect `master` (GitHub setting), tier C1. `server` is skipped on Markdown-only PRs, which GitHub treats as passing.
- **A13** ingress vs a local overlay: a bare LoadBalancer answers plain HTTP with a `301` in production mode and
  `X-Forwarded-For` is client-spoofable behind it (per-IP rate limits key on it).
- **#75**: the cache posture.
- **Dependabot** PRs [#12](https://github.com/liveweird/flow/pull/12), [#47](https://github.com/liveweird/flow/pull/47),
  [#64](https://github.com/liveweird/flow/pull/64)-[#67](https://github.com/liveweird/flow/pull/67) are held under the
  dependency rule; Dependabot alerts and security updates are **disabled** in the repo settings (the owner's call).
- **2C8** the Polish glossary pass: a judgement call that needs a native speaker.
- **2D3** password reset by confirm-link (today anyone who knows an email can force one rotation per 60 s; MFA still applies).
- **2D4** a per-request `credential_revision` check (today a delete/demote takes effect within the 15-minute access-token
  window). Related to, but separate from, C8.
- A PR-time image scan (~3 min per PR); the nightly scan is the compromise.
- **D6** (de-Jira the `Connector` seam, waits for the GitLab connector) and the real-Jira first-sync items.
- Tagging 0.4.0.

## Not established

- Polish reviewed by scripts and samples only (parity, placeholders, term consistency), not by a native speaker.
- No load test or penetration test; the D9-3 projection is arithmetic over assumed row counts, to be replaced by the real
  `last_value`.
- The two Mailpit specs were excluded from the local Playwright run (they hard-code the dev stack's Mailpit, L-2); the
  nightly covers them.
- D5-1's root cause (the 10-19/10-20 nightly red) and its fixing commit were not identified.
- Memory figures are stub-scale on compose, not the scale-20 overlay or k8s.
- The checkup covers Jira stub data only; nothing here says how a real instance behaves (a real 24-month backfill has ~2x the
  days of the scale-20 run; re-measure on the first real sync).

Update this record when a fixing PR merges, a parked topic is resumed, an in-flight PR merges, or the next checkup changes a
verdict.
