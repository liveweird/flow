### Build, test and CI times

Flow is a small app: a tiny database (the test stub is 1,200 issues), no external integration in the
tests, a few hundred source files. **Everything should be blazing fast.** When a build, test or CI
job is not, the job is to find out WHY — not to raise a timeout. This doc is the measuring kit, the
budgets, the history and the open investigations. Read it before you change a gate, a CI job, the
test harness (fixtures, forks, Kover) or anything that makes a gate slower.

## How to measure

Three tools, node built-ins only (no dependency; `scripts/` is outside the `web/` and `e2e/`
workspaces, so knip and eslint never see it). Budgets live in ONE place, `scripts/timings/budgets.json`.

**Remote — `node scripts/timings/ci-times.mjs`** (needs an authenticated `gh`; read-only calls):

```
node scripts/timings/ci-times.mjs --limit 20            # last 20 successful runs per workflow (ci, e2e)
node scripts/timings/ci-times.mjs --branch master       # the trunk only (PR noise excluded)
node scripts/timings/ci-times.mjs --steps web           # + the slowest steps of that job's latest run
node scripts/timings/ci-times.mjs --workflows e2e --steps e2e   # the nightly's per-step rows (build / start / specs)
node scripts/timings/ci-times.mjs --tsv                 # raw rows: created, sha, event, branch, job, seconds
```

`--steps JOB` also shows each step's target/alarm and an `over target`/`OVER ALARM` flag when the step has a
row under `ci-steps` in `budgets.json` (keys `<job>/<step name>`, the names the workflow gives its steps; today
the nightly `e2e` job's five steps).

Per job: latest, median of the last 5, median of the 5 before, min/max, the budget, and flags —
`TREND +N%` (recent median more than 25 % above the previous one and, once the median is 60 s or more, by at least 10 s too — below that the rule is relative-only),
`over target`, `OVER ALARM`. A `(run wall)` row is the time to green. Runs that were not successful
are listed separately (cancelled/failed counts, and `TIMEOUT?` for a job stopped right at a
`timeout-minutes` value — those never enter the medians).

**Local — `node scripts/timings/local-times.mjs`:**

```
node scripts/timings/local-times.mjs record server-build -- ./gradlew cleanTest build
node scripts/timings/local-times.mjs record web-gates -- sh -c 'cd web && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage && npm run build'
node scripts/timings/local-times.mjs report                       # latest / median 5 / previous 5 / flags
node scripts/timings/local-times.mjs slow-tests                   # top classes + cases from server/build/test-results/test
```

**The one entry point — `scripts/gates.sh [server|web|e2e|all]`** (default `all`) runs the gates the way CI does,
each wrapped in `record`: `server-build` (`./gradlew cleanTest build`, with `JAVA_HOME=$(mise where java)` and the
OrbStack `DOCKER_HOST` set only when unset and the socket exists), `web-gates`, `e2e-static`. It stops at the first
failing gate with that gate's exit code, prints one line per gate and ends with `local-times.mjs report`. Prefer it
to typing the commands above, so every pre-commit run lands in the series. (Playwright is not part of it — it needs
a running stack.)

`record` runs the command, appends one row (ISO time, git short sha, branch, worktree, activity,
seconds, exit code, host cores) to `${XDG_DATA_HOME:-~/.local/share}/flow/timings.tsv` — outside the
repo, **shared by every worktree** — prints the verdict against the budget and exits with the
command's own code. Non-zero exits are recorded but excluded from the medians. Activity names are
free-form; the ones with budgets are `server-build`, `server-test`, `web-gates`, `e2e-static`,
`e2e-playwright` (anything else gets trend flags but no budget; the in-test targets below are not
`record` activities — Gradle and JVM start-up would swamp them). Record a full-gate run whenever you run one anyway (the pre-commit
`server-build`, `web-gates`) — the point is a series, not a benchmark session.
`slow-tests` also takes `--wall <s>` (the Gradle-reported total), `--top-classes`, `--top-tests`,
`--budget ci.server-tests` and `--summary-file`. Suite span and the sum of class times are both shown
(the suite runs in one fork, so they agree).

**In CI** the `server` job ends its build with a "Test time report (job summary)" step (`if: always()`,
`continue-on-error`, never a gate): the top 15 slowest classes and top 10 cases go to the run's job
summary, and a `::warning::` annotation appears when the test time exceeds the `ci.server-tests` alarm (the test task's own budget; `ci.server` stays the whole job's).
It uses the runner's preinstalled `node` (the scripts need node >= 18; ubuntu-24.04 ships 20+).

## Budgets

`target` = what it SHOULD take for this app; `alarm` = the hard ceiling. Both are deliberately
ambitious — a target is a claim about the app ("tiny DB, no integrations → this cheap"), and a
miss is a question to answer, not a number to adjust. CI runners are public-repo `ubuntu-24.04`
(4 vCPU, 16 GB); local figures are for the dev machine (18 cores).

| Activity | Target | Alarm | Reasoning |
|---|---|---|---|
| CI `changes` | 15 s | 30 s | one checkout and one `git diff` |
| CI `server-tests` | 3 min | 8 min | the test task alone (its own budget — the CI warning compares test time with this, not the job): ~800 tests on 4 vCPU, leaving 2 min of the job's 5 min target and 2 of its 10 min alarm for setup, compile, detekt and Kover |
| CI `server` | 5 min | 10 min | compile + detekt + ~800 tests + Kover on 4 vCPU; a tiny DB does not need more. Was 3 min on 2026-09-26 with far fewer tests |
| CI `web` | 1.5 min | 3 min | cached `npm ci`, lint, knip, vitest, vite build of a small SPA; was 1m15s |
| CI `e2e-static` | 30 s | 1 min | cached `npm ci` + eslint + knip + tsc over a handful of specs; runs ~15 s today |
| CI `gradle-vulnerability-scan` | 1 min | 2 min | resolve the lockfiles + one trivy run over a few thousand lines |
| CI `images` | 2.5 min | 4 min | multi-stage docker build (gradle installDist + vite build) with the BuildKit layer cache (`.github/compose-buildx-cache.yaml`), then a Trivy scan of the image. Cache and scan landed together (2026-09-30) with no runs behind them: expect the scan to add ~30-60 s, decide after two master runs whether the target moves |
| CI `k8s-static` | 20 s | 45 s | one checkout, a pinned kubeconform download, validation of ~11 small objects twice (raw + rendered) |
| CI `e2e` (nightly) | 7 min | 15 min | PROVISIONAL: image build + compose up + Playwright over a tiny stack; revisit with more data |
| CI `e2e` step: `Install e2e deps` | 15 s | 45 s | `npm ci` of the e2e workspace with the npm cache |
| CI `e2e` step: `Install Playwright browser` | 30 s | 1.5 min | `install-deps` (apt) with the browser cached by Playwright version; ~1 min on a cold cache |
| CI `e2e` step: `Build images` | 2 min | 4 min | PROVISIONAL: compose build with the BuildKit layer cache; measured locally 2m08s cold, 1m32s warm with one changed source file |
| CI `e2e` step: `Start the stack` | 45 s | 2 min | PROVISIONAL: `docker compose up -d --wait` — postgres + app (JVM boot, Flyway) healthy |
| CI `e2e` step: `Run E2E` | 4 min | 9 min | PROVISIONAL: the Playwright specs alone (the old combined step, build and start-up included, took 7m06s of the 7m48s job on 09-29) |
| local `server-build` (`./gradlew cleanTest build`) | 3 min | 6 min | 18 cores; compile, detekt, all tests, Kover, alignment |
| local `server-test` | 2 min | 5 min | ~800 tests against ONE shared Postgres container |
| local `web-gates` | 1.5 min | 3 min | lint:api + check:api + lint + knip + coverage tests + build |
| local `e2e-static` | 30 s | 1 min | lint + knip + typecheck + scenario parity |
| local `e2e-playwright` | 5 min | 10 min | PROVISIONAL: blackbox suite on a running stack; revisit with the first recorded runs |

**In-test targets** (investigation targets, not measured by `record` and not in `budgets.json`: they are
timings inside a test or a stack, read from a test's own report or a log). A miss is a WHY question
below, never a failing check.

| Measure | Target | Ceiling | Reasoning / measured |
|---|---|---|---|
| `stub-clone` (`cloneProcessedData`) | 0.5 s | 1.5 s | an `INSERT … SELECT` of the same 14 tables measured 220 ms; the Exposed clone takes 3.6 s |
| `stub-process` (PROCESS, 1,200 issues) | 3 s | 8 s | ~2.5 ms per issue is generous for a page-batched read plus a handful of batched writes; measured 2.4-3.2 s after the page batching (2026-09-30), was 16-19 s at 13.5-15 ms per issue |
| `stub-derive` (one DERIVE of the stub) | 1 s | 3 s | 1,200 items, ~1,300 days; measured 2.5-2.9 s WITH the statistics fix, 7-26 s without |
| `derive-perf` (scale-20, ~24k issues, `docker-compose.perf.yaml`) | 20 s | 180 s | 20x the stub at 1 s; the ceiling is the v0.3.0 plan budget (< 3 min); measured 82 s |

## History so far

Source: 264 successful CI job runs from 61 runs, 2026-09-26 → 2026-09-29 (UTC; `ci-times.mjs --tsv`
rows). Failed and cancelled runs are excluded, so the worst cases (the timeout below) are under-shown.

**`server` job** (the one that matters):

| When | What was landing | Time |
|---|---|---|
| 09-26, early | v0.2.0 branch start, dependabot PRs | 2-4 min (median ~3 min, min 2m03s) |
| 09-26 evening | v0.2.0 SYNC/normalization tests, each driving a full ~30 s stub SYNC | 7 min → 14-20 min within hours |
| 09-27 | v0.2.0 + docs PRs on master | 16-20 min (median ~20 min) |
| 09-27 night | `SyncedStubFixture` (one shared SYNC per JVM; commit `2aa603a`) | back to 6m34s |
| 09-28 | v0.3.0 M1/M2 (metrics config + UI); `DerivedStubFixture` (`6a581f7`) cut `MetricsDerivationTest` 324 s → 41 s | 7-10 min |
| 09-29 | v0.3.0 M3 (DERIVE, aggregates, more re-derive/REPROCESS-digest tests) | ~21 min — the derive-heavy tests cost more than the fixtures saved (see the ANALYZE cause) |
| 09-29 | M4 (reports + web) | 26-29 min |
| 09-29 | M5 | 21-30 min; the docs-only PR #30 (`chore/backlog-v0.3.0`, run #69 — before the Markdown-only skip of the server job existed) hit the 30-min `timeout-minutes` (stopped at 30m12s); the timeout became 45 |

**Runner variance is large.** The same test code measured 17m47s (push to master) and 29m15s (the PR
run) for M4-web; M5's runs were 29m54s and 21m20s. Read a single run with suspicion, a median of 5
with care, and never conclude from one number. Locally (18 cores) the whole suite takes ~6-12 min, so
CI is 2-3x slower than the dev machine — a 4 vCPU runner running Postgres, the JVM and the Kover agent
side by side.

**Other jobs (daily medians, same source):**

| Job | 09-26 | 09-27 | 09-28 | 09-29 | Verdict |
|---|---|---|---|---|---|
| `web` | 1m16s (0m43-1m28) | 1m35s | 1m46s | 2m21s (1m03-3m16) | ~3x slower in 4 days — see WHY 6 |
| `images` | 1m50s | 2m16s | 2m14s | 2m20s (latest 2m25s) | +30 %, one step: `docker compose build` |
| `e2e-static` | 16 s | 16 s | 17 s | 15 s | flat |
| `gradle-vulnerability-scan` | 42 s | 38 s | 44 s | 42 s | flat (27-90 s = network noise) |
| `e2e` (nightly, Playwright) | 3m31s (dispatch) | 4m15s | 5m50s | 7m48s | +120 % in 3 days — unexplained, see WHY 7 |

## Trajectory and status, 2026-09-30

Source: `node scripts/timings/ci-times.mjs --branch master` (23 successful master runs, 09-26 → 09-30) and
`--tsv --limit 40` for the PR runs.

**CI trajectory over tonight's PRs.** The `server` job was ~30 min at its worst (09-29: the M4/M5 runs at
21-30 min; the docs-only run #69 hit the old 30-min timeout). Master pushes since: #33 (ANALYZE inside DERIVE + faster
fixtures) 15m08s → #34/#35 11m26s-12m37s → #36 (PROCESS in pages of 50) 12m56s → #40 (pooled test database) **8m18s**;
the latest PR run (#41's branch, `1e0dad4`) is **10m01s** on a 4 vCPU runner. Read every figure with the
runner-variance warning above in mind — two cancelled master runs (#91, #93) stopped at 5-10 min and never
counted. The `web` job: **3m15s (master, before #37) → 1m55s (first master run with #37: vitest `isolate: false`,
CSS/threads — WHY 6) → 1m23s (#40's push) → 1m09s** (#41's PR run), inside its 1m30s target. Still over
target: `server` (10m01s vs 5 min) and `images` (4m06s: the BuildKit cache and the Trivy scan landed together in
#35 — WHY 9 needs a fresh look, below).

**WHY 5 (CI 4 vCPU vs local 18 cores) — status 2026-09-30: pending.** Checkup A2 made parallel test forks
possible; checkup D1 is the change that ENABLES them (`maxParallelForks` above 1) and has not landed. Until it does
the suite stays single-fork and CI keeps its 4-vCPU penalty; the expected gain is still only ~15-25 % on 4 vCPU
(question 5 has the local -29 % / -33 %, and the fixture duplication that limits it). Re-measure with
`ci-times.mjs --branch master` on the first master run after D1.

**The 45-minute timeout.** The `server` job's `timeout-minutes: 45` (`.github/workflows/ci.yml`) was raised from 30
when run #69 hit it; it is a stop-gap, not a budget (the alarm is 10 min). Once D1 lands and the master median sits
below the alarm for a few runs, bring it down (to ~20 min, at least 2x the alarm) in the same change that
records the measurement — the same "measurement first" rule as `budgets.json`.

## Known causes and open WHY questions

Sources: the 2026-09-29 profiling of the server suite (local, 18 cores, 782 tests, instrumented,
worktree `test-speed`, branch `perf/test-suite-speed`) — numbers below are from it unless marked.

**Cause found: DERIVE never ANALYZEs what it just filled.** DERIVE rebuilds the `metrics` tables in one
transaction; the WIP and flow `INSERT … SELECT`s then plan nested loops over `rows=1` estimates
(autovacuum never gets a chance inside the transaction), and every re-derive adds dead tuples, so the
cost GROWS per derive (same connection derived 3x: 7.5 s → 12.3 s → 18.9 s; the backlog flow
statement 14.5 s inside the derive vs 21 ms with fresh statistics). One `ANALYZE` (16-90 ms) before
the WIP step: full suite 712 s → 369 s (-48 %), one DERIVE 7-26 s → 2.9 s, projected CI ~30 → ~17-19
min. **Fix in progress on `perf/test-suite-speed`** (one statement in `MetricsDeriver`/`MetricsStore`
plus a regression pin). Production caveat: at 24k issues the WIP team/task statement still costs ~9 µs
per row after ANALYZE (its two correlated sub-selects), so the documented 46.7 s WIP figure may be
that, not statistics — the effect of ANALYZE at scale 20 is NOT measured.

**Cause found: PROCESS paid ~13 statement round trips and one transaction PER ISSUE (2026-09-30,
`perf/process-speed`).** Measured with temporary per-statement timers (local, 18 cores, the 1,200-issue
stub, a clone of the synced raw rows, `needs_processing` on every issue; instrumentation removed):

| Per issue (before) | ms, unpooled test DB | ms, pooled |
|---|---|---|
| first statement of the transaction (`SELECT 1` probe: connection open + BEGIN) | **4.2** | 0.22-0.29 |
| the `raw.jira_issues` PK read (payload included) after it | 0.5 | 0.26-0.32 |
| changelog read / worklog read | 0.55 / 0.55 | 0.26 / 0.25 |
| 4 `DELETE`s by `(connection_id, issue_id)` (indexed, `V13`) | 0.45-0.52 each | 0.21-0.26 each |
| 4 `batchInsert`s (status / field intervals / field changes / worklogs) | 1.25 / 1.5 / 1.2 / 0.7 | 0.53-0.64 / 0.89-1.1 / 0.69-0.84 / 0.4-0.5 |
| existing-row select + `work_items` insert/update | 0.7 + 0.53 | 0.27-0.32 + 0.29-0.35 |
| `markProcessed` update | 0.43 | 0.24-0.29 |
| normalize (pure) | 0.12 | 0.10 |
| **per issue / 1,200-issue pass** | **14.4-15.5 ms / 16.3-18.9 s** | **5.7-7.0 ms / 6.9-8.5 s** (the pooled runs include four probe statements, ~1 ms) |

- **The "4.8 ms PK read" was not a read.** It was the connection: the FIRST statement of every
  per-issue transaction paid 4.2 ms (a second `SELECT 1` in the same transaction: 0.14 ms), while the
  read itself is 0.5 ms and the payload decode 0.4 ms. `TestEnvironment.kt`'s `sharedTestDatabase` is
  `R2dbcDatabase.connect(url, …)` — UNPOOLED, one new PostgreSQL backend per `suspendTransaction`
  (`persistence.md` "Connection pool" forbids exactly that in production, where `connectPooled` is
  used). So the profiled 16 s was ~5 s of test-harness connection setup plus ~11 s of the real cost;
  production PROCESS through the pool measured 6.9-8.5 s per pass, not 16 s.
- **The real cost is round trips: ~13 statements and a transaction per issue** (BEGIN/COMMIT, three
  reads, four deletes, four inserts, a select, an upsert, a mark) at ~0.25-0.5 ms each. Nothing was
  missing an index (no `EXPLAIN` was needed: every DELETE/read is an indexed `(connection_id,
  issue_id)` lookup whose 0.2-0.5 ms wall time is a round trip, cf. 0.09-0.14 ms for a bare `SELECT 1`) and no query returned a big payload.
- **Fix: one transaction and ~13 statements per PAGE of 50** (`JiraProcessStream`,
  `WorkItemStore.replaceWorkItems`, `JiraRawStore.issuesForProcessing`/`changelogPayloadsForIssues`/
  `worklogPayloadsForIssues`/`markProcessedBatch` — ingestion.md "PROCESS batching, failure
  isolation and progress"): `IN (…)` reads, in-memory normalization, one delete + one `batchInsert`
  per table, one `batchUpsert` of `work_items`, one mark; a DB error in the page write falls back to
  the per-issue path so failure isolation is unchanged. **No migration** (the indexes were right).
  Now: **2.4-3.2 s per pass (~2-2.7 ms per issue) on either database**; what is left is Exposed's
  per-ROW batch cost (~0.15 ms per inserted row: field intervals ~0.8 s, field changes ~0.6 s, status
  intervals ~0.45 s, the `work_items` upsert ~0.2 s of the ~2.5 s; reads 0.07 s, normalize 0.09 s).
  "Byte-identical rows" is established two ways. WITHIN the new code, by the digests:
  `NormalizationPipelineTest`'s REPROCESS digest (now over `norm.work_items` minus `processed_at`,
  the status/field intervals, the field changes and `norm.work_item_worklogs`, so the `batchUpsert`
  rewrite is pinned directly), the one-issue-page digest, the invariant sweep, `SyncedStubFixtureTest`'s
  clone digest and `MetricsDigestTest`'s REPROCESS-then-DERIVE digest. AGAINST master's per-issue
  code, once (2026-09-30, a temporary scratch test, removed): the same MD5 over all of those tables of
  the full-SYNC stub connection is `6b35a794d3360bec2f01ce30de8ecd7c` on master `4363cd4` and on this
  change.
- **Side finding fixed in the same change:** `issuesToProcess` re-claimed the lowest stale ids on
  every loop turn, so an issue that failed (and stays flagged) kept `PROCESS` spinning forever; it is
  now keyset-paged (`afterIssueId`), each stale issue is visited once per run.
- **Scale 20 (24k issues), projected from the per-issue figures (NOT measured at scale):** before
  ~5.4 min against the unpooled numbers (~2.3 min pooled — the production shape); after ~1 min. The
  first v0.3.0 deploy reprocesses the whole tenant, so this is the number that matters there.
- **Whole suite, local, `./gradlew cleanTest build` on master `4363cd4` vs this change** (one run
  each, 18 cores; the "after" run also carries three NEW ~6 s tests): wall **7m05s → 5m55s**, sum of
  class times **6m46s → 5m40s** (784 → 787 tests). Classes: `NormalizationPipelineTest` 1m27s → 52s,
  `DataProfileTest` 46s → 23s (its one-time shared sync: 45 s → 23 s), `MetricsDigestTest` 46s → 33s
  (`REPROCESS then DERIVE` 29 s → 16 s), `JiraSyncPipelineTest` 7.9 s → 8.1 s (it only READS the
  shared sync). `MetricsDerivationTest` (1m26s → 1m29s) is DERIVE-bound and unchanged. CI (4 vCPU)
  should gain more in absolute terms: its PROCESS-bearing classes were the ones that grew there.
  Still over the 3 min local target — the rest is DERIVE (question 3) and the unpooled harness
  database (question 10).

Open questions — each is "why does this take this long for a tiny dataset?", to be answered with
evidence and recorded here as a dated entry (finding + fix, or "measured, intended because …"):

1. **Exposed row-by-row clone.** `cloneProcessedData` takes 3.1-4.3 s (mean 3.6 s, 16 uses = 58 s per
   suite: `cloneRawData` ~1.0 s + norm rows ~2.6 s); a per-table `INSERT … SELECT` (columns from
   `information_schema`, serial ids skipped) of the same 14 tables took 220 ms with an identical
   status-interval digest. Why do we round-trip 1,200 items through the JVM to copy them? Target
   `stub-clone` 0.5 s. Estimated -54 s locally.
2. **PROCESS was 13.5 ms per issue — ANSWERED 2026-09-30, see "Cause found: PROCESS" above.**
3. **DERIVE's JVM passes.** Even with ANALYZE ~70 % of a DERIVE is JVM-side batched loops: pass 1 +
   pass 2 ~1.6 s and the sprint + worklog steps ~0.5 s for 1,200 items (the `dim_date` upsert alone is
   ~150 ms for 1,281 days). Why does computing facts for 1,200 rows cost 1.6 s? Target `stub-derive` 1 s
   (2.5-2.9 s today).
4. **Kover costs ~10 %.** Instrumentation off: suite 369 → 334 s (-9.5 %), the `MetricsDerivationTest`
   class -15 %. `koverVerify` is a `check` gate, so it stays on in CI; a local-dev switch is cheap.
   Open: is the floor worth 10 % of every CI run, or can coverage be measured on a schedule?
5. **CI 4 vCPU vs local 18 cores.** The suite is single-fork. `maxParallelForks=2/3` passed all 782
   tests locally (-29 % / -33 %) but broke the OpenAPI coverage gate (each fork writes the same
   `coverage.md`/`gaps.txt`; 59-62 false gaps), duplicates the per-fork Postgres + ~30 s sync + derived
   fixture (sum of class time 367 → 499 → 608 s), and Kover across forks is unverified. Expect only
   -15-25 % on 4 vCPU. Do it LAST, and only if CI is still over target after the fixes above.
6. **Web job growth — ANSWERED 2026-09-30 (checkup A17; CI 3m15s → 1m09s after #37, see the trajectory entry above).** 1m16s → ~2m20s median in four days; the
   latest master run spent 1m22s (68 %) in `npm run test:coverage`, lint 12 s, `npm ci` 10 s, build 7 s.
   Locally (18 cores; every count and time below is from before the A7 test landed — 108 files, 866 tests) the suite takes **12.5-13.4 s** (`real`; 143-159 s user +
   28-32 s sys of CPU), so the runner's 82 s is CPU-bound, not a slow file: `--maxWorkers=3` (a 4 vCPU
   runner's default) reproduces it at **34-44 s** on the same machine. vitest's own breakdown (summed
   over files): tests 47-56 %, **setup 24-32 %**, environment 10-14 %, import 5-7 %, transform 2-5 %.
   Where it goes:
   - **Isolation is the fixed cost.** Every file gets a fresh worker (`Isolate  108 workers spawned ·
     ~145 ms startup each`) and re-runs `src/test/setup.ts`. One no-op test file costs 152 ms with no
     setup file, 246 ms with only `../i18n` (both locale bundles), ~300 ms with only `@mantine/core`,
     and 428 ms with the real `setup.ts` — so ~275 ms x 108 files = ~30 s of CPU (about 20 %) is the
     setup import, before any test runs. About 20 of the 43 `.ts` test files render nothing, but a
     lean setup for them would save only ~6 s of CPU (~4 %) — not pursued.
   - **`isolate: false` halves the run — and, since 2026-09-30 (second batch), is on.** Under
     `--no-isolate` (3 workers) the suite took 17-18 s against ~40 s, but 9-15 tests failed, a different
     set per run. Root causes, all order dependence rather than real bugs: (1) **the shared module
     registry** — `vi.mock` only affects modules imported AFTER it, so a source module an earlier file
     left cached (`useDeleteConfirm` with the real toast, the report pages with a chart mock — or the
     real chart — from another file) kept the wrong dependency; fixed once in `src/test/setup.ts`, which
     now runs `vi.resetModules()` before each file (npm packages are externalised and stay cached, so
     it costs a re-transform of `src/`, not a re-import of Mantine) and imports `../i18n` after it so
     the file and the setup share ONE i18n instance; (2) **tests that assumed a warm lazy chart chunk**
     — `ReportEpicProgress` (two synchronous `getByTestId("line-chart")` after the first data
     assertion), `ReportBlockedTime` (`getAllByTestId("bar-chart")`), `ReportSprintConsistency`
     (`findAllByTestId` returns as soon as the FIRST of three lazy charts lands) and `Home` (the
     Suspense fallbacks are extra `status` regions until the chunks land) — they passed only because an
     earlier test in the file, in file order, had already loaded the chunk; they now await it
     (`findBy…`/`waitFor`), no assertion removed. These four also failed under `--sequence.shuffle`
     WITH isolation on, i.e. they were latent order dependencies regardless of this change. Proof: 25
     `--no-isolate --sequence.shuffle` runs green (871/871), then `npm run test:coverage` 5x and
     `--coverage --sequence.shuffle` 3x green with the coverage unchanged (statements 96.57, branches
     92.7, functions 94.73, lines 98.42). Interleaved 4 rounds against `--isolate` on the same tree:
     3 workers (CI-shaped) **36.2 s → 17.5 s (-52 %), CPU user+sys 125 s → 50 s**; default 18 workers
     **14.1 s → 9.9 s (-30 %), CPU 183 s → 104 s**. The rule that keeps it safe is in
     `.claude/docs/testing.md` ("Frontend tests"): a new test that leaks state fails under shuffle —
     reset the leak in `afterEach`, never flip `isolate` back.
   - **`css: true` processed every Mantine stylesheet in every file for nothing** — happy-dom lays
     nothing out and the `env="test"` rule already bypasses CSS-dependent visibility. Changed to
     `css: { include: [/src[\\/]index\.css/] }`: only `src/index.css` is still processed, because
     `theme.test.ts` reads it `?raw` (a plain `css: false` fails exactly that one test; every other
     file, `chartColors.test.ts` included, never touches CSS). Interleaved 4 rounds, 3 workers:
     37.0 s → 34.9 s median (-6 %), user CPU -5 s.
   - **`pool: 'threads'`** (worker threads instead of forked processes; still one module registry per
     file): interleaved 4 rounds, 3 workers: 37.0 s → 34.7 s (-6 %), sys time 13.7 s → 9 s. All 866
     tests green in every run.
   - **CSS + threads together, before the isolation work** (measured separately above, each kept
     because it is green and consistently faster): 3 workers **37.0 s → 32.7 s interleaved (-12 %), and 29-32 s in four later runs vs 34-44 s
     before**; default 18 workers 12.9 s → 11.5 s (-11 %; CPU user+sys ~183 s → ~157 s). Coverage figures identical (statements
     96.57, branches 92.68, functions 94.73, lines 98.42 — thresholds untouched). Coverage instrumentation
     itself costs ~15 % (`--coverage` 42 s vs 36 s without, 3 workers) and stays: it is a gate.
   - **Did not help / not tried:** narrowing `coverage.include` (not measured — the untested-file scan is
     small next to the per-file cost it would save), and a lean setup for the render-free `.ts` tests
     (moot once isolation is off: the setup now runs once per worker's file, not once per fresh worker).
     Expected CI effect of the whole WHY-6 series: ~82 s of vitest -> roughly 35-40 s (the 3-worker
     local ratio, -52 %, applied to the runner's 82 s minus the CSS/threads gain); re-measure with
     `ci-times.mjs --steps web` after the first master run — the `web` job budget (1.5 min) should then
     be met.

7. **The nightly `e2e` grew +120 % in 3 days** (3m31s → 7m48s) with no e2e budget or step breakdown:
   is it the growing compose image build, the stack start-up, or the Playwright specs (reports batches
   landed 09-29)? Run `ci-times.mjs --workflows e2e --steps e2e`.
8. **Test-harness side effects (not isolated).** `NormalizationPipelineTest.clonedConnection` and
   `IngestWorkerTest` create ENABLED connections, so any config change anywhere enqueues a DERIVE that a
   worker in another class runs with the real clock (`IngestWorkerTest` saw 40 derives; ~30
   empty-connection derives spend 2-6 s each in `dim_date` upsert row-lock contention). The effect on
   wall time is unmeasured.
9. **`images` is one 2m25s step** (`docker compose build`), +30 % since 09-26 with a cold layer cache
   each run. Is the layer cache being used at all (is there a `cache-from`)? Not investigated before
   2026-09-30; #35 then added the BuildKit layer cache (`.github/compose-buildx-cache.yaml`) AND a Trivy scan
   of the image in one change. Latest master figure 4m06s (median of 5, TREND +76 %, over the 4-min alarm):
   is the cache hit (`--steps images`), and how long is the scan? Still open.
10. **The shared test database was unpooled — ANSWERED 2026-09-30 (`perf/pooled-test-db`).**
   `sharedTestDatabase` (`TestEnvironment.kt`) opened a fresh PostgreSQL backend per
   `suspendTransaction` (~4.2 ms; a pooled connection answers a first statement in ~0.25 ms) for every
   test-side service call, fixture clone and read helper. It is now pooled through the SAME
   construction as production (`connectPooledDatabase` in `infra/db/Database.kt`, extracted from
   `connectPooled`, so `defaultMaxAttempts = 1` and the r2dbc-pool wiring cannot drift): `maxSize` 10,
   `initialSize` 1, acquire timeout 30 s, idle 10 min. Test-side calls are sequential, so 8 is
   headroom, and a genuine nested-transaction deadlock now fails in 30 s instead of hanging. No test
   needed adjusting: nothing in the suite uses session-level `SET`, `LISTEN`, advisory locks or temp
   tables (only transaction-scoped state), `ConnectionPoolTest` measures the APP's own pool (unchanged),
   and `SyncedStubFixture`'s plain-JDBC clone path is separate and left as is.
   Measured (local, 18 cores, `./gradlew cleanTest :server:test`, 791 tests, `gaps.txt` empty, no
   failures), interleaved because other worktrees share the machine — load average is the machine's
   1-minute figure at the start of each run:

   | Run | Load at start | Suite span (= sum of class times) | `MetricsDerivationTest` | `NormalizationPipelineTest` | `MetricsDigestTest` | `IngestWorkerTest` | `DataProfileTest` |
   |---|---|---|---|---|---|---|---|
   | before #1 (unpooled) | 4.2 | 6m00s | 1m44s | 51s | 32s | 28s | 22s |
   | after #1 (pooled) | 12.6 | 4m49s | 1m33s | 23s | 26s | 21s | 18s |
   | before #2 (unpooled) | 5.8 | 4m57s | 1m17s | 42s | 28s | 26s | 21s |
   | after #2 (pooled) | 5.1 | **3m50s** | 1m08s | 18s | 25s | 18s | 11s |

   The clean comparison is #2: **4m57s -> 3m50s (-23 %)**; run #1 of the unpooled variant ran under a
   load that inflated it (a 6m00s outlier — the same code took 4m57s at low load), so read the
   before/after from the low-load pair. The gain concentrates where test-side transactions are
   numerous (`NormalizationPipelineTest` -57 %, `DataProfileTest` -48 %); `MetricsDerivationTest` and
   `MetricsDigestTest` are DERIVE-bound (question 3) and move ~10 %.

**What the suite is made of (local, pre-fix run, 782 tests, 12m30s of class time):**
`MetricsDerivationTest` 5m55s (47 %), `MetricsDigestTest` 2m00s (16 %), `NormalizationPipelineTest`
1m16s, `ReportDataQualityTest` 47 s, `DataProfileTest` 35 s — 24 tests over 5 s account for 87 % of
the time. `local-times.mjs slow-tests` reproduces this table for any run.

## Review cadence

- Run `node scripts/timings/ci-times.mjs --branch master` at every milestone / PR, and in every
  checkup; run `local-times.mjs report` when a local gate feels slower.
- A `TREND` flag or a budget breach becomes a dated investigation entry in this doc (WHY, evidence,
  outcome) — **never just a raised budget.** A budget changes only with the measurement that justifies
  it, in the same commit as the edit to `scripts/timings/budgets.json` and the table above.
- A new gate or CI job gets a budget row before it lands.
- The `slow-tests` output of the CI job summary is the first stop when the `server` job regresses:
  a new class or case near the top of the list is almost always the cause.
