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
node scripts/timings/local-times.mjs record server-build -- ./gradlew cleanTest check :server:installDist
node scripts/timings/local-times.mjs record web-gates -- sh -c 'cd web && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage && npm run build'
node scripts/timings/local-times.mjs report                       # latest / median 5 / previous 5 / flags
node scripts/timings/local-times.mjs slow-tests                   # top classes + cases from server/build/test-results/test
node scripts/timings/local-times.mjs slow-tests --forks 2         # + the fork-balance block (span vs sum/N, fixture costs, weight drift; warns past budgets.json forkBalance)
node scripts/timings/local-times.mjs test-weights                 # per-class weights (suite time minus fixture-cost) of a full run -> server/test-weights.tsv
```

**The one entry point — `scripts/gates.sh [server|web|e2e|all]`** (default `all`) runs the gates the way CI does,
each wrapped in `record`: `server-build` (`./gradlew cleanTest check :server:installDist`, with `JAVA_HOME=$(mise where java)` and the
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
(they agree with one fork; with `-Pforks=N` the span is the wall and the sum is N forks' CPU time, fixtures included).

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
| CI `images` | 2.5 min | 4 min | multi-stage docker build (gradle installDist + vite build) as a plain `docker compose build` — no layer cache (it cost more than it saved, WHY 9) — then a Trivy scan of the image. Measured 4m05s with the cache (2026-09-30), ~3 min expected without (133 s build median + ~45 s scan): the target is missed by the floor of the job (Gradle in Docker ~113 s + Trivy DB download ~25 s), documented, not raised. Since 2026-10-23 it also runs on every pull request that changes more than Markdown: ~3 min of runner time per PR, in parallel with `server` (~10-14 min), so no PR waits longer |
| CI `k8s-static` | 20 s | 45 s | one checkout, a pinned kubeconform download, validation of ~11 small objects twice (raw + rendered) |
| CI `e2e` (nightly) | 7 min | 15 min | PROVISIONAL: image build + compose up + Playwright over a tiny stack; revisit with more data |
| CI `e2e` step: `Install e2e deps` | 15 s | 45 s | `npm ci` of the e2e workspace with the npm cache |
| CI `e2e` step: `Install Playwright browser` | 30 s | 1.5 min | `install-deps` (apt) with the browser cached by Playwright version; ~1 min on a cold cache |
| CI `e2e` step: `Build images` | 2.5 min | 4 min | a plain `docker compose build`, the same build as `images` (the layer cache is gone, WHY 22): 125-164 s on master 10-18..10-20; the floor is Gradle `installDist` in Docker (~133 s) |
| CI `e2e` step: `Start the stack` | 45 s | 2 min | PROVISIONAL: `docker compose up -d --wait` — postgres + app (JVM boot, Flyway) healthy |
| CI `e2e` step: `Run E2E` | 4 min | 9 min | PROVISIONAL: the Playwright specs alone (the old combined step, build and start-up included, took 7m06s of the 7m48s job on 09-29) |
| local `server-build` (`./gradlew cleanTest check :server:installDist`) | 3 min | 6 min | 18 cores; compile, detekt, all tests, Kover, alignment |
| local `server-test` | 2 min | 5 min | ~800 tests against ONE shared Postgres container per fork (default 1 fork; `-Pforks=2` ~-28 %, WHY 5) |
| local `web-gates` | 1.5 min | 3 min | lint:api + check:api + lint + knip + coverage tests + build |
| local `e2e-static` | 30 s | 1 min | lint + knip + typecheck + scenario parity |
| local `e2e-playwright` | 5 min | 10 min | PROVISIONAL: blackbox suite on a running stack; revisit with the first recorded runs |

**In-test targets** (investigation targets, not measured by `record` and not in `budgets.json`: they are
timings inside a test or a stack, read from a test's own report or a log). A miss is a WHY question
below, never a failing check.

| Measure | Target | Ceiling | Reasoning / measured |
|---|---|---|---|
| `stub-clone` (`cloneProcessedData`) | 0.5 s | 1.5 s | an `INSERT … SELECT` of the same 14 tables measured 220 ms; the Exposed clone takes 3.6 s |
| `stub-process` (PROCESS, 1,200 issues) | 3 s | 8 s | ~2.5 ms per issue is generous for a page-batched read plus a handful of batched writes; measured 0.6-0.65 s with the statement-timeout cache (2026-10-06, WHY 13), 0.75-0.85 s with `insertRows` + `upsertRows` (2026-10-06), 1.0-1.1 s with `insertRows` alone (WHY 3 follow-up), 2.2-2.3 s with `batchInsert` after the page batching (2026-09-30: 2.4-3.2 s), was 16-19 s at 13.5-15 ms per issue |
| `stub-sync` (one full stub SYNC, PROCESS + PROFILE included) | 4 s | 6 s | ~1,330 HTTP calls to the local WireMock + the raw writes; measured 4.6-5.3 s with the batched raw writes (2026-10-20, WHY 21), 9.3-11.2 s writing one row per transaction; ~0.4 s less in the backfill phase with the concurrent worklog fetch, inside the SYNC's noise (WHY 33) |
| `stub-derive` (one DERIVE of the stub) | 1 s | 3 s | 1,200 items, ~1,300 days; measured ~1.1 s steady with the statement-timeout cache (2026-10-06, WHY 13), 1.17-1.75 s with `insertRows` (2026-10-01, WHY 3; 2.9-3.7 s with `batchInsert`), 7-26 s before the statistics fix; of the rest the WIP `INSERT … SELECT` is 0.5-0.75 s |
| `derive-perf` (scale-20, ~24k issues, `docker-compose.perf.yaml`) | 20 s | 180 s | 20x the stub at 1 s; the ceiling is the v0.3.0 plan budget (< 3 min); measured 82 s on 2026-09-29, 38-52 s at 512m on 2026-10-07 (WHY 14) |

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
| 09-29 | M5 | 21-30 min; the docs-only PR #30 (`chore/backlog-v0.3.0`, run #69 — before the Markdown-only skip of the server job existed) hit the 30-min `timeout-minutes` (stopped at 30m12s); the timeout became 45 (25 since #51) |

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
| `e2e` (nightly, Playwright) | 3m31s (dispatch) | 4m15s | 5m50s | 7m48s | +120 % in 3 days — tests 31 → 44 (→ 134 by 10-01) plus runner noise, see WHY 7 |

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
#35 — answered 2026-10-01, WHY 9 below: the cache was the regression and is gone).

**WHY 5 (CI 4 vCPU vs local 18 cores) — ANSWERED 2026-09-30.** Checkup D1 landed in #51: CI runs the server suite in two
parallel forks (`-Pforks=2`), the local default stays one. The measurement (local -28 % at 2 forks; the `server` job
7-8 min on master afterwards) and the fork-safety work that made it possible are in "Known causes and open WHY
questions", question 5.

**The server job timeout is 25 minutes.** `timeout-minutes: 25` (`.github/workflows/ci.yml`): run #69 hit the old 30-min
limit, it was stop-gapped to 45, and #51 brought it down to 25 together with the two forks (the alarm stays 10 min;
25 is over 3x the 7-8 min master median). Re-check the margin with `ci-times.mjs --branch master` whenever the suite grows — the same
"measurement first" rule as `budgets.json`.

## Status check, 2026-10-15 (milestone: capacity management, before 0.4.0)

Source: `ci-times.mjs --branch master`, 30 successful master runs 10-06 → 10-15. `server` median of the last 5
**6m36s** (previous 5: 8m52s; min 5m31s, max 10m13s, one run over the 10-min alarm), `web` 2m19s, `images` 3m19s —
all three over target, none at alarm on the median; no trend jump to investigate. **The nightly `e2e` was red
2026-10-07 → 10-15 with no test failing**: the runner image's newer Compose builds through `buildx bake`, which
refuses the local `cache_from`/`cache_to` directory (`/tmp/buildx-cache`, outside the build context) without
`--allow=fs.read/fs.write`, so "Build images" died and the Trivy re-scan found no `flow-app`. Fixed by
`BUILDX_BAKE_ENTITLEMENTS_FS: "0"` on that step (`e2e.yml`); the first green dispatch took 12m37s (job wall,
target 7 min — WHY 7's data now resumes from here).

## Status check, 2026-10-17 (after the report follow-ups, 0.19.0-0.28.0)

Source: `ci-times.mjs --branch master`, 30 successful master runs 10-16 → 10-17. `server` median of the last 5
**13m41s** (previous 5: 11m30s; min 7m25s, max 14m14s) — **over the 10-min alarm**, investigated as WHY 18 (runner
speed + suite growth + fork imbalance). `images` 3m09s and `web` 2m55s over target, under alarm. The nightly `e2e`
median-5 11m21s (+29 % on the previous 5, target 7 min, alarm 15) — not investigated in this check; it feeds the
`[todo]` nightly image-build-cache item (WHY 7's data).

## Status check, 2026-10-18 (milestone: 1.0.0)

Source: `ci-times.mjs --branch master`, 30 successful master runs 10-17 → 10-18 (0.29.0 → 1.0.0). `server` median-5 **12m09s**
(previous 5: 7m59s; min 7m33s, max 14m14s) — **over the 10-min alarm**, TREND +52 %; `web` median-5 **3m28s** (previous 5:
2m53s) — **over its 3-min alarm**; `images` 3m08s over target; the nightly `e2e` median-5 12m04s (+37 %, under its 15-min alarm,
green on every run since 10-16 — the 9 counted failures are 10-07..10-15). Investigated as WHY 19.

## Status check, 2026-10-21 (after 1.1.0-1.6.1 and the WHY 20-23 fixes)

Source: `ci-times.mjs --branch master`, 30 successful master runs 10-17 → 10-21. `server` median-5 **10m02s** (previous 5: 11m39s;
min 7m33s, max 12m43s) — on the 10-min alarm; `web` median-5 **3m12s**, TREND +43 % (previous 5: 2m14s) — over its 3-min alarm;
`images` 3m22s over target; the nightly `e2e` median-5 12m35s (under its 15-min alarm). Both flags are the runner draw, not a regression:
every job is still bimodal (`web` 103-156 s on fast runners, 173-206 s on slow ones; `server` 474-602 s fast, 699-763 s slow), the last
five runs drew three slow runners, the previous five three fast ones. Per job, the fixes' effect and what grew is in the "CI after" lines
of WHY 20-23. Accepted: `web` on a slow runner stays ~10-20 s over the 3-min alarm (WHY 20 took the lint step from 30-38 s to 2-5 s; the
tests grew 2,261 → 2,359 and took most of it back) — the next lever is splitting the job (WHY 20 "Not done"), not worth it yet.
`server/test-weights.tsv` refreshed (16 of 199 classes had drifted or were missing; the fork excess had crept from 0.2-1.6 % to 2.1-2.8 %).

## Status check, 2026-10-23 (after 1.7.0-1.14.3: the costs revamp, the memory work, checkup 3, the pushdowns)

Source: `ci-times.mjs --branch master`, 30 successful master runs 10-22 → 10-23. `server` median-5 **14m12s** (previous 5: 13m01s;
min 7m44s, max 14m33s) — OVER the 10-min alarm; `web` median-5 **3m27s**, TREND +33 % — over its 3-min alarm; `images` 3m31s over
target; the nightly `e2e` 13m02s (under its 15-min alarm). Investigated (job logs and the "Test time report" of 8 server / 4 web runs):
- **Mostly the runner draw, which got worse.** Identical code on the identical image (ubuntu-24.04 20261012.334.1) runs 1.7x apart:
  run 39887372500 test task 351 s (class sum 11m29s, stub-sync 19.3 s/fork) vs run 39988643153 588 s (18m27s, 28.9 s/fork) — every
  phase and fixture scales together, compile + detekt 93 → 124 s. Docs-only merges ran 464-858 s. Server runs >= 700 s per day:
  10-20 6/14, 10-21 6/7, 10-22 11/15, 10-23 16/23 — the 14m12s median IS the slow draw. The CPU model is not in the logs.
- **Real growth is small:** fast-runner class sums 11m19s (10-21, 1,584 tests) → 11m29s (10-23, 1,691 tests), span +5 %; no heavy new
  class (the pushdowns #291-#294, #304, #303, #289 each add 1-2 s). `web`: `test:coverage` grew with the suite (2,359 → 2,592 tests, +12 %
  files), fast-runner vitest 90 → 113 s.
- **Fork balance had drifted to the 8 % warn line** (44-48 s excess on slow runs; 9 of 220 classes off or missing from the weights):
  `server/test-weights.tsv` refreshed from a full local run (231 classes; simulated excess 3.5 s, 1.9 %).
- **CI runs `check :server:installDist`, not `build`** (the zips/tars and the never-used fat JAR cost ~15 s and verified nothing the
  image uses; `scripts/gates.sh` the same).
- **Not reachable by tuning:** the code levers above are worth ~1-2 min of a slow run; the 10-min alarm on a slow runner needs a
  bigger (paid, 8-vCPU) runner, or sharing the stub fixture across forks (~30-40 s) plus a third fork (uncertain on 4 vCPU). The paid
  runner is the user's call; the alarm is not raised.

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
that, not statistics — the effect of ANALYZE at scale 20 is NOT measured (measured 2026-10-09: WHY 16).

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

**Cause found: DERIVE's "JVM passes" were `batchInsert`'s per-row cost, not JVM compute (2026-10-01,
`perf/why-3-9`).** Measured with a temporary harness (local, 18 cores, the 1,200-issue stub, a disabled
clone derived nine times in one JVM under the pinned clock; lap timers around `runDerivation`'s steps and
around every `metricsStore.insert*`/`workItemStore.*` read, plus a JFR recording of derives 4-9; all
instrumentation removed):

| Step of one stub DERIVE (steady state, derives 4-9) | Before, ms | After, ms |
|---|---|---|
| read + trim work items, contexts, deletes | ~40 | ~40 |
| pass 1 (`item_stage`/`item_blocked`/`item_estimate`) | 717-945 | 118-155 |
| pass 2 (tasks + four bridges + `fact_task_delivery`) | 987-1,244 | 189-294 |
| sprint step / worklog step | 326-458 / 185-238 | 76-126 / 63-82 |
| WIP `INSERT … SELECT` + flow + `ANALYZE` | 510-720 + 90 + 17 | 508-765 + 100 + 18 |
| **whole derive** | **2.9-3.7 s** | **1.17-1.75 s** |

- **The time was round trips, not compute.** Inside the steps, the `insert*` calls alone added up to
  2.3-2.7 s; reads (`fieldIntervalsByIssue` 39 calls, `statusIntervalsByIssue`, `worklogsByIssue`) 0.1 s; the
  pure Kotlin kernels (`deriveItem`, `buildTaskRow`, …) were below the timers' noise. Every
  `batchInsert` cost **0.16-0.21 ms per row regardless of the table** (`item_stage` 3,603 rows 680 ms =
  0.19 ms/row; `task_assignee` 2,361 rows 384 ms; `fact_task_delivery` 1,186 rows 247 ms; `dim_task` 193 ms;
  `fact_worklog` 201 ms; ... ~15,000 rows in all). JFR agrees: six recorded derives took 20 s of wall
  time and produced 378 execution samples at a 5 ms period (1.9 s, ~9 % of the wall) — the threads were
  waiting on the database, and the hottest Java frames were r2dbc-postgresql/netty/reactor plumbing, not
  Flow code. It is the same ~0.15 ms/row Exposed batch cost the PROCESS entry above found.
- **Isolated and tried** (3,600 `item_stage` rows in chunks of 200, one transaction, 5 runs, ms): `batchInsert`
  as written 620-640 (first run 1,036); `batchInsert(shouldReturnGeneratedValues = false)` 490-690 (no help —
  the cost is the per-row execution, not the `RETURNING`); ONE multi-row `INSERT … VALUES (…), (…)` per chunk
  **54-62**; the database's own cost for the same rows with `generate_series` (GiST + btree indexes and the
  `connection_id` FK check included) **35-39**.
- **Fix: `infra/db/MultiRowInsert.kt`'s `insertRows`** — `batchInsert`'s call shape (`table.insertRows(rows) {
  this[Col] = … }`), one multi-row `VALUES` statement per chunk of at most 32,000 bind parameters (the wire
  protocol's limit is 65,535), every value still bound and converted by its column's own type (the
  `EntityID` reference columns bind through the referenced id column; client-side `default(…)` columns are
  filled like `batchInsert` does; rows must set the same columns, all of this table's, and the table must have
  no sequence-backed `autoIncrement("seq")`; Exposed's client-side `varchar` length check is not run, an overflow
  fails server-side with SQLSTATE 22001). **Statement texts are quantized** (review finding): the SQL text
  depends on the row count and every distinct text is a named server-side prepared statement that
  r2dbc-postgresql caches per connection — unbounded by default — so a chunk is the table's maximum rows per
  statement or a power of two (`chunkSizes`: 13 rows -> 8 + 4 + 1; at most ~14 distinct texts per table, pinned
  by `MultiRowInsertTest`), and `connectPooledDatabase` bounds the driver cache
  (`PREPARED_STATEMENT_CACHE_QUERIES` = 256, `persistence.md` "Connection pool"). Measured on one pooled
  connection with `pg_prepared_statements` (scratch test, removed): 0 statements before, **128 after the first
  stub DERIVE and still 128 after the ninth** — flat, not growing per derive; the stub DERIVE after the change
  took 1.15-1.41 s steady (1.65 s the first, 9 derives), unchanged by the quantization. All `MetricsStore.insertX` methods use it
  (`batchUpsert` for `dim_date` is unchanged: a cold-start-only ~150-250 ms). 3,600 rows: 73-93 ms.
  **Byte-identical output is established two ways.** `MultiRowInsertTest` writes the same rows through
  `batchInsert` and `insertRows` into two connections and compares the stored rows (two chunks, NULLs, jsonb,
  a nullable reference, an unset default column). And a one-off A/B over the whole derive (temporary test,
  removed): for every one of the 20 `metrics.*` tables a SQL MD5 over the rows minus `id`, `connection_id`,
  `config_revision`, `snapshot_at`/`reconstructed` and the team-id columns (those vary per fixture) plus
  the row count was **identical before and after** (e.g. `item_stage=3603:cf3bb42b…`,
  `fact_task_delivery=1186:b7a9b66b…`, `agg_daily_wip=13202:1db61cc1…`); the existing
  `MetricsDigestTest`/`DerivedStubFixtureTest`/`SyncedStubFixtureTest`/`MetricsDerivationTest` pass unchanged.
- **Suite effect** (one run each, local, shared machine — read the per-class rows, not the totals): `MetricsDerivationTest`
  65-89 s -> 29 s, `MetricsDigestTest` 25-33 s -> 12 s, `DerivedStubFixtureTest` 3.9-4.4 s -> 2.5 s; the whole gated
  `cleanTest build -Pforks=2` (872 tests, `gaps.txt` empty, detekt and `koverVerify` green) took 1m55s against 2m23s
  on 09-30 with 822 tests.
- **What is left of a stub DERIVE (~1.2-1.5 s):** the WIP `INSERT … SELECT` 0.5-0.75 s (13,200 rows; database
  side — the two correlated sub-selects, `metrics.md` "Performance"), the flow step 0.1 s, passes 1 + 2 +
  sprint + worklog ~0.4 s together (batched reads + multi-row writes, no single item over 0.2 s), 40 ms of
  setup. `stub-derive` is back inside its 3 s ceiling and within ~20-50 % of its 1 s target; the remainder is
  the WIP statement, not the JVM. At scale 20 (24,000 issues, ~300,000 bridge/fact rows) the per-row figures
  (0.17 ms before, ~0.02 ms after) project the ~51 s "JVM side" recorded in `metrics.md` "Performance" to
  roughly 6-10 s including reads — projected, NOT re-measured at scale.
- **Follow-up, not done here (scope):** PROCESS (`WorkItemStore.replaceWorkItems`: `batchInsert` per table,
  ~2.1 s of its 2.4-3.2 s per pass is the same per-row cost) can use `insertRows` the same way — a separate
  change with its own digest pins (`NormalizationPipelineTest`). **Done 2026-10-06, see the next entry.**

**Cause found and fixed: PROCESS's four child-table inserts were the same per-row `batchInsert` cost (2026-10-06,
`perf/process-insert-rows`).** Measured with a temporary per-table timer around `replaceWorkItems`' writes (local,
18 cores, shared machine, the 1,200-issue stub: a processed clone, every issue re-flagged `needs_processing`,
`JiraProcessStream.run` eight times in one JVM, medians; instrumentation and scratch test removed):

| One stub PROCESS pass | Before (`batchInsert`), ms | After (`insertRows`), ms |
|---|---|---|
| field intervals | 760 | 150 |
| field changes | 545 | 130 |
| status intervals | 392 | 98 |
| worklogs | 123 | 46 |
| **the four child inserts** | **~1,820 (80 % of the pass)** | **~420** |
| `work_items` `batchUpsert` (unchanged code) | 168 | 275 (the same code and statement; the rise is reproducible across all eight runs and NOT explained — the upsert is the one write that still runs one execution per row, so it may be paying for connection/pool effects the child inserts used to absorb; a guess, not measured; `pg_stat_statements` on the test database, per-statement mean time of the upsert before/after, is the way to settle it) |
| **whole `JiraProcessStream.run` pass** | **2,225-2,288 (median 2,246)** | **976-1,105 (median 1,024), -54 %** |

- **Fix:** the four per-page writers (`StatusIntervals`/`FieldIntervals`/`FieldChanges`/`Worklogs`) call
  `insertRows(rows) { … }` instead of `batchInsert(rows, shouldReturnGeneratedValues = false) { … }`: same lambda,
  same columns, same values, same transaction (the page's, unchanged), same order of statements (deletes, the
  four inserts, the `work_items` upsert, the mark). The reference-table rebuilds (statuses, people, boards,
  board columns, sprints; a few hundred rows once per run) and the `work_items` `batchUpsert` (`ON CONFLICT`,
  not an insert) are untouched. The prepared-statement texts stay bounded: the quantized chunk sizes give each
  of the four tables at most ~14 texts (a page has at most 50 issues, so a table's row counts are small and varied) —
  ~56 for PROCESS. The driver's statement cache is per CONNECTION and shared with DERIVE's `insertRows` tables
  (128 texts after a stub DERIVE, WHY 3), so on a worker connection that runs both the worst case is ~184 here
  and can exceed `PREPARED_STATEMENT_CACHE_QUERIES` = 256 on a connection that has also served other `insertRows`
  shapes: the least-recently-used text is then evicted and re-prepared — a performance cost only, never a
  correctness one. Not measured on a long-lived worker connection.
- **Bad-row path.** One multi-row statement fails as a whole, but the page was already one transaction that
  PostgreSQL aborts on its first failing statement, so the page-level fallback (each issue in its own
  transaction, which now writes one issue's rows as one multi-row statement per table) is unchanged and still
  isolates the bad issue (`NormalizationPipelineTest` "falls back to per-issue transactions" — a planted worklog
  PK conflict —, "a bad row alone on its page", the V19 `ProcessReferenceRowsTest` cases, all green unchanged). One
  behavioural difference, pinned by a NEW test: `insertRows` skips Exposed's CLIENT-side `varchar(n)` length check,
  so an over-long CHILD-table value is now PostgreSQL's SQLSTATE 22001 (class 22) rather than Exposed's
  `IllegalArgumentException`; `isDataError` classifies both as a bad row (the new `NormalizationPipelineTest` case "a
  value too long for a CHILD table column" plants a 150-character `field_id` and expects `issuesFailed = 1`, flagged
  for the next pass, run succeeding).
- **Byte-identical output**, three ways: `NormalizationPipelineTest`'s REPROCESS digest, `SyncedStubFixtureTest`,
  `MetricsDigestTest` and `DerivedStubFixtureTest` pass unchanged; `MultiRowInsertTest` still pins the helper against
  `batchInsert`; and a one-off A/B against the OLD writer (temporary scratch test, removed): a clone of the stub's raw
  rows, every issue flagged, one PROCESS pass, then the REPROCESS digest function (every non-surrogate column of
  `work_items` minus `processed_at`, status/field intervals, field changes, worklogs, ordered by their natural keys)
  over all 1,200 items — `e9bd350e4fb4c11ddd3aa4296bb4da37` with `batchInsert` (master `5118d1b`) and with
  `insertRows`.
- **What is left of a stub PROCESS pass (~1.0 s):** the `work_items` `batchUpsert` ~0.27 s (an `ON CONFLICT` upsert
  has no multi-row helper yet — the next candidate, ~0.15-0.25 ms per row), the four child inserts ~0.42 s, the rest
  (page reads, deletes, mark, normalize, the reference rebuild) ~0.3 s. `stub-process` is back inside its 3 s target
  (1.0 s against 3 s). At scale 20 (24,000 issues) the pass projects from ~1 min to ~25-30 s — projected from the
  per-issue figures, NOT measured at scale.

**The `work_items` upsert moved to `upsertRows` (2026-10-06, `perf/work-items-upsert`).** The follow-up #83 recorded: PROCESS's
last per-row write, Exposed's `batchUpsert` into `norm.work_items`, becomes ONE multi-row `INSERT … ON CONFLICT` per chunk.
Measured first, same method as #83 (local, shared machine, the 1,200-issue stub: a processed clone, every issue re-flagged,
`JiraProcessStream.run` nine times in one JVM, the first discarded, medians, a temporary timer around the upsert call —
removed):

| One stub PROCESS pass | `batchUpsert`, ms | `upsertRows`, ms |
|---|---|---|
| `work_items` write | 233-254 (median 240, 27 % of the pass) | 76-88 (median 80) |
| **whole `JiraProcessStream.run` pass** | **884-932 (median 892)** | **753-855 (median 778 / 774 in two runs), -13 %** |

(The pass reads ~0.9 s here against ~1.0 s in the entry above: the machine was less loaded; compare rows within a column
pair only.) The upsert was 27 % of the pass, above the 15 % / 100 ms bar for doing it, and the saving (~115 ms per
stub pass, ~0.16 ms per row gone) is real but modest: what remains of the `work_items` write (~80 ms) is the database
building 36-column rows with five `jsonb` columns, not round trips. The pass is now ~0.78 s; at scale 20 the
projection moves from ~25-30 s by ~5 s, projected, NOT measured.

- **Helper:** `infra/db/MultiRowInsert.kt`'s `table.upsertRows(rows, keys = listOf(Col, …)) { this[Col] = … }` beside `insertRows`
  (they share one private `writeRows`: the same collection, validation, column list, chunking — quantized to a power
  of two or the table maximum — and the 32,000-parameter cap, so a 36-column table is at most 888 rows per statement).
  SQL shape per chunk:
  `INSERT INTO norm.work_items (connection_id, issue_id, …) VALUES (?, ?, …), (?, ?, …) ON CONFLICT (connection_id, issue_id) DO UPDATE SET issue_key = EXCLUDED.issue_key, …`
  — the `DO UPDATE SET` list is every statement column except the keys (here every column but the PK, exactly what
  `batchUpsert` updated: no `onUpdateExclude`, no `WHERE`), and the suffix is constant, so a PROCESS page (at most
  50 issues -> sizes 32/16/8/4/2/1) adds at most six statement texts to the connection's prepared-statement cache (it was one
  per-row text before); the ~184 worst case above becomes ~190.
- **Duplicate keys:** one `INSERT … ON CONFLICT DO UPDATE` cannot touch a row twice (`cardinality_violation`), while
  `batchUpsert`'s sequential executions let the last row win. PROCESS cannot actually produce a repeated `issue_id` in a
  page (the page is read keyed by the raw table's `(connection_id, issue_id)` primary key), but the helper does not rely on that: it collapses repeated
  keys to the LAST row before chunking (pinned by `MultiRowInsertTest` "collapses a repeated key to its last row").
- **Behavioural difference:** like the child tables in #83, an over-long `work_items` varchar (`issue_key` 20, `status_id`
  50, `assignee_account_id` 100 …) is now PostgreSQL's SQLSTATE 22001 instead of Exposed's client-side
  `IllegalArgumentException`; `isDataError` classifies both as a bad row, and the existing `NormalizationPipelineTest`
  case "a value too long for a sized column alone on its page" (a 30-character issue key) passes unchanged (only its comment changed).
- **Equivalence, four ways:** `MultiRowInsertTest` writes the same 1,000 `work_items` rows through `batchUpsert` and
  `upsertRows` into two private connections (insert path), then 1,001 rows overlapping 501 of them with every nullable
  column flipped between NULL and a value (update path, several chunks), and compares every stored column; the REPROCESS
  digest, `SyncedStubFixtureTest`, `MetricsDigestTest` and `DerivedStubFixtureTest` pass unchanged; the bad-row fallback
  tests pass unchanged; and the one-off A/B of #83 repeated (a clone of the stub's raw rows, every issue flagged, one pass,
  the REPROCESS digest function over all 1,200 items): `e9bd350e4fb4c11ddd3aa4296bb4da37` — the same digest as with
  `batchUpsert`.
- **What is left of a stub PROCESS pass (~0.78 s):** the four child inserts ~0.42 s, the `work_items` write ~0.08 s, the rest
  (page reads, deletes, mark, normalize, the reference rebuild) ~0.3 s. Nothing in PROCESS is a per-row round trip any more.

**Cause found and fixed: DERIVE's ANALYZE held shared table locks to the commit (2026-10-06,
`perf/derive-analyze-after-commit`).** `analyzeDerivedTables` ran inside the derive transaction, so its 16
`SHARE UPDATE EXCLUSIVE` locks (the tables are shared by every connection) lived to the COMMIT and a second
connection's derive queued on them for the rest of the first's run (~50 % of a stub derive: ANALYZE + WIP + flow +
commit). The fix moves it AFTER the commit and `markRunSucceeded` for every derive whose predecessor's statistics
describe its rows (per table: every `row_counts` key — tasks, epics, sprints, worklogs, epic plans, item estimates — is 0 or was > 0
and at most doubled; otherwise — first derive, an empty table filled by a config change, a doubled connection — it stays in the transaction: the WHY 1 failure mode is ABSENT or wrong-sized
statistics, not one-derive-old ones). **Step 0 gate (measured before keeping any code):** a temporary harness (removed), local,
18 cores, shared machine, the 1,200-issue stub, a disabled processed clone derived three times under the pinned
clock, LEGACY (in-transaction ANALYZE) and NEW (post-commit) interleaved, plus `EXPLAIN (ANALYZE, BUFFERS)` of every
WIP/flow statement during derives 1-3 (the statement itself was run through the `EXPLAIN`, so the plans are the ones
the derive really used):

| Whole derive, ms (derives 1 / 2 / 3) | LEGACY | NEW |
|---|---|---|
| run A | 1,538 / 1,239 / 1,352 | 1,312 / 1,280 / 1,344 |
| run B | 1,334 / 1,270 / 1,304 | 1,215 / 1,353 / 1,359 |
| run with `EXPLAIN` on every statement | 1,330 / 1,406 / 1,382 | 1,294 / 1,436 / 1,462 |

- **Derives 2-3 are within noise of the in-transaction ANALYZE and of the 1.17-1.75 s steady state** — nowhere near the
  no-stats regression (7.5 → 12.3 → 18.9 s). The `EXPLAIN` buffer hits agree statement by statement: the heaviest
  (WIP team/task) 496k / 680k / 825k hits LEGACY vs 496k / 680k / 861k NEW on derives 1 / 2 / 3, every other WIP/flow
  statement 0.3k-40k in both (the regression was 18.6 M on the backlog flow statement). The WIP team/task growth per
  derive is the same in both variants (dead tuples — row estimates, not plans).
- **Concurrency (`MetricsAnalyzeTest` "two connections re-derive concurrently", two disabled clones, one derive each
  sequentially vs both at once, three runs per variant, logged not asserted):** NEW 3,191-3,350 ms sequential vs
  1,704-1,926 ms concurrent (**0.53-0.58**, near the 0.5 of perfect overlap on a machine whose derives also compete
  for CPU); LEGACY (ANALYZE in the transaction for both) 2,864-3,208 ms vs 2,250-2,527 ms (**0.78-0.83**).
  The scale-20 two-connection run is NOT done (BACKLOG).
- **Bounded wait:** the post-commit ANALYZE runs under `SET LOCAL lock_timeout = 5000` (per table lock,
  `DEFAULT_ANALYZE_LOCK_TIMEOUT_MS`) and the transaction's `queryTimeout` of 30 s (the whole statement,
  `DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS`, rounded up to whole seconds — a `SET LOCAL statement_timeout` would be overwritten by Exposed; a healthy ANALYZE is 16-90 ms), so a foreign lock (a long manual `VACUUM`,
  another ANALYZE) occupies the worker slot for 30 s at most — slot occupancy, not lease safety: the heartbeat is a
  concurrent `leaseSeconds/3` ticker (`statement_timeout` does not cover pool acquire, 30 s by default, so ~60 s worst
  case outside shutdown). A timeout is a WARN and the run stays SUCCEEDED; the WARN can occur when a re-derive's
  post-commit ANALYZE overlaps another connection's in-transaction one. The in-transaction ANALYZE has no timeout. The
  post-commit one is NOT `NonCancellable` (a shutdown must be able to interrupt it).
- **Test cost:** `MetricsAnalyzeTest` went from 2 tests / 9.6 s (the WHY 3 table's older figure) to 10 tests / ~15 s of its own work (31.5-33.2 s run alone,
  including the one-time ~16 s shared stub sync; three runs) — the re-derive tests share one
  derived clone, and the two-connection overlap measurement above is opt-in (`FLOW_MEASURE_DERIVE_OVERLAP=1`) and costs the
  suite nothing.
- **Left as is:** a derive whose predecessor's statistics do not describe its rows (a new connection, one after
  PURGE, an empty table filled by a config change, a doubled connection) still serializes with another such derive from the ANALYZE to its
  commit.

Open questions — each is "why does this take this long for a tiny dataset?", to be answered with
evidence and recorded here as a dated entry (finding + fix, or "measured, intended because …"):

1. **Exposed row-by-row clone.** `cloneProcessedData` takes 3.1-4.3 s (mean 3.6 s, 16 uses = 58 s per
   suite: `cloneRawData` ~1.0 s + norm rows ~2.6 s); a per-table `INSERT … SELECT` (columns from
   `information_schema`, serial ids skipped) of the same 14 tables took 220 ms with an identical
   status-interval digest. Why do we round-trip 1,200 items through the JVM to copy them? Target
   `stub-clone` 0.5 s. Estimated -54 s locally.
2. **PROCESS was 13.5 ms per issue — ANSWERED 2026-09-30, see "Cause found: PROCESS" above.**
3. **DERIVE's JVM passes — ANSWERED 2026-10-01, see "Cause found: DERIVE's JVM passes" above.** They were
   Exposed `batchInsert`'s ~0.17 ms per row (~15,000 rows = 2.3-2.7 s of a 2.9-3.7 s derive), not compute;
   `insertRows` (one multi-row `INSERT` per chunk) brought a stub DERIVE to 1.17-1.75 s, identical rows. What
   remains is the database-side WIP statement (0.5-0.75 s).
4. **Kover costs ~10 % — ANSWERED 2026-10-01 (`perf/why-3-9`): confirmed at 11-14 % of the test wall and ~20 % of the CPU; it stays on.**
   Measured locally (18 cores, but the machine is shared with other worktrees — load average 5-20 while these ran, so
   wall figures swing +-15 % between identical runs: four interleaved full suites, 872 tests, `cleanTest :server:test
   -Pforks=2`, gave 162 / 145 s and 127 / 128 s for instrumented / not; the class-time sums 264 / 251 s and 213 / 225 s —
   no conclusion can be drawn from those). The CPU time is what load cannot distort: two instrumented/not pairs with
   `/usr/bin/time -p ./gradlew --no-daemon cleanTest :server:test -Pforks=2` (the test JVMs are descendants, so their
   user+sys is included):

   | Pair | Instrumented: wall, user + sys | Not instrumented: wall, user + sys | Saved |
   |---|---|---|---|
   | 1 | 139.7 s, 241.2 + 26.0 = 267.1 s CPU | 124.0 s, 186.6 + 24.5 = 211.1 s CPU | wall -11.2 %, CPU -21.0 % |
   | 2 | 112.1 s, 207.0 + 21.4 = 228.4 s CPU | 95.9 s, 163.2 + 19.9 = 183.1 s CPU | wall -14.4 %, CPU -19.8 % |

   So the ~10 % of WHY 4's first measurement (369 -> 334 s, -9.5 %) holds: 11-14 % of the wall on a machine with spare
   cores, ~20 % of the CPU — and a 4-vCPU runner running two forks, two Postgres containers and the agent is
   CPU-bound, so its wall follows the CPU figure more than the local one (expect up to ~15-20 % of the `test` task,
   i.e. a few tens of seconds of the `server` job). Not measured here: `koverVerify`/`koverXmlReport` themselves
   (they ride `check` after the tests).
   **Is there a local switch? No.** `server/build.gradle.kts` has no `-PnoKover`-style property and none was added
   (nothing under `*.kts`, `gradle.properties`, `scripts/` or the workflows mentions one); the plugin
   (`org.jetbrains.kotlinx.kover` 0.9.9) instruments every `Test` task while it is applied. The measurement above used a
   temporary, uncommitted `kover { currentProject { instrumentation { disabledForTestTasks.add("test") } } }` block
   (no `test.ic` is written then, so `koverVerify` has nothing to verify — use it only for `:server:test`
   timing or a dev loop, never `build`); `testing.md` "Coverage gates" records it. **Decision: Kover stays on in CI
   and in `check`.** The line/branch floors are a `check` gate and `koverVerify` needs `test.ic`; measuring coverage on a
   schedule instead would let a coverage drop merge green and surface a night later, and the price is ~11-14 % of a
   job that is not the critical-path problem (the `server` job's time is dominated by the suite itself, WHY 5) — measured,
   intended. Revisit only if a 4-vCPU CI run shows the instrumented test task costing more than ~1 min over a clean one.
5. **CI 4 vCPU vs local 18 cores — ANSWERED 2026-09-30 (`perf/parallel-forks`, checkup D1).** The suite now
   runs in parallel JVM forks: `server/build.gradle.kts` reads `-Pforks=N` into `maxParallelForks` (default 1; validated as an integer 1..8, else any build that runs the `test` task fails),
   CI passes `-Pforks=2`. What made it possible (the three blockers the old entry listed): (1) the OpenAPI gate
   is fork-safe since #42 (each fork writes `exercised-<pid>-<uuid>.txt`, the last one to exit re-merges them
   into ONE `coverage.md`/`gaps.txt`); (2) **class order was not robust** — `MetricsDigestTest` first in a JVM
   died with `relation "source_connections" does not exist` (nothing had run Flyway) — `PostgresTestSupport`
   now migrates the container inside its own lazy init, so any first class works; (3) Kover across forks,
   verified below. `forkEvery` is unset and the test JVM keeps Gradle's default 512 MiB heap, so 2 forks x
   512 MiB + 2 Postgres containers is ~1.5 GB on a 16 GB runner.

   Measured locally (18 cores; the machine is shared with other worktrees, so the 1-minute load average at the
   start of each run is listed; `cleanTest :server:test`, 822 tests, wall = Gradle's whole invocation incl. ~15 s
   of configuration/up-to-date checks):

   | forks | wall, run 1 (load) | wall, run 2 (load) | vs 1 fork | sum of class time |
   |---|---|---|---|---|
   | 1 | 213 s (2.8) | 228 s (6.3) | — | 3m45s |
   | 2 | 166 s (4.0) | 151 s (7.8) | -28 % | ~4m30s |
   | 3 | 134 s (11.5) | — | -39 % | — |

   Fixtures are per fork (each JVM pays its own container + Flyway + ~25 s stub SYNC + one DERIVE), so class time
   sums to more than the single fork's (~3m45s -> ~4m30s, +20 % of CPU) — the wall gain is real but sub-linear:
   2 forks -28 %, 3 forks -39 %, and a 4th would mostly add fixed cost. On the 4 vCPU CI runner (Postgres per
   fork + the Kover agent share those cores) expect the smaller end of the report's -15-25 %; the local default
   stays 1 (a fork per class-batch also multiplies the Postgres containers on a dev laptop already running a
   compose stack) — pass `-Pforks=2` when you want the faster loop. The whole gated `cleanTest build`
   (compile, detekt, tests, Kover, alignment) measured **203 s single fork vs 143 s at `-Pforks=2`**
   (-30 %, final tree, load ~5.5 both). **Gates under forks (all verified):** `gaps.txt` empty and
   `coverage.md` listing "62 of 62 operations exercised; 249 of 367 declared pairs" in every forked run (identical
   to the single fork); `koverVerify` passes; Kover line/branch coverage with `-Pforks=2` is 98.19 % / 80.19 %
   against 98.23 % / 80.23 % single-fork (the reports merge; the 0.04-point difference is timing-dependent
   coverage of async paths).

   **The largest class does not bound the forked wall time — measured, so `MetricsDerivationTest` was NOT
   split.** At 1 fork it is 65 s of 225 s; ten of its 37 tests (the ones cloning their own connection) are
   ~63 s of that, the 27 read-only tests are nearly free. A trial split into read / sprint-clone /
   attribution-clone classes (pure move, compiled, detekt clean, same 37 test names and bodies) measured
   157 s and 174 s at 2 forks and 137 s at 3 (vs 166/151 and 134 unsplit): no gain, because the wall is
   bound by per-fork fixed cost plus total work, not by one class. It stays one class; revisit only if a
   class grows past ~1/3 of the suite.

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

7. **The nightly `e2e` grew +120 % in 3 days — ANSWERED 2026-10-06 (`ci/e2e-image-cache`): new tests, plus runner noise; the image build is not it.**
   Sources: `ci-times.mjs --branch master --steps e2e` (9 successful runs 09-27 → 10-05), `gh run view <id> --json jobs`
   for every run (the step split exists since 09-30), and the Playwright list-reporter log of the 10-05, 10-04 and
   10-01 runs (per-test durations, parsed per spec file).

   | Night (run) | Tests | Job | `Build images` | `Start the stack` | `Run E2E` (specs; 09-26 → 09-29 includes build + start) |
   |---|---|---|---|---|---|
   | 09-26 dispatch | 31 | 3m31s | - | - | 181 s |
   | 09-27 | 34 | 4m15s | - | - | 209 s |
   | 09-28 | 38 | 5m50s | - | - | 317 s |
   | 09-29 | 44 | 7m48s | - | - | 426 s |
   | 09-30 | 57 | 7m38s | 153 s (cache miss) | 18 s | 203 s |
   | 10-01 | 134 | 9m37s | 157 s (cache miss) | 22 s | 289 s |
   | 10-02 | 134 | 8m49s | 56 s (hit) | 18 s | 403 s |
   | 10-03 | 134 | 9m03s | 57 s (hit) | 20 s | 401 s |
   | 10-04 | 134 | 7m27s | 64 s (hit) | 21 s | 303 s |
   | 10-05 | 134 | 8m58s | 57 s (hit) | 19 s | 406 s |

   **Attribution.**
   - **Tests, not the image.** 31 → 134 tests (x4.3) took the nightly from 3m31s to ~9 min; the build is 57 s on a
     cache hit and 155 s on a miss (two misses in ten nights: a lockfile changed, which is what the key is for), the
     stack start-up a flat 18-22 s, the browser install (cached) ~19 s. `Run E2E` is 70-75 % of the job. The jump is
     the axe sweeps: `accessibility.spec.ts` + `accessibility-data.spec.ts` are ~96 of the 134 tests (every page, light
     and dark), one `login()` plus a page load plus axe each, ~5-8 s on the runner.
   - **Runner noise is as large as the growth.** The five nights 10-01 → 10-05 ran the SAME commit (`9f52356`) and
     `Run E2E` took 289 / 403 / 401 / 303 / 406 s (summed test time, where the log was read: 824 s on 10-01, 849 s on 10-04,
     1,173 s on 10-05 — every test ~1.4x slower on a slow runner, the same tests). So "8m58s vs 6m44s" is mostly which runner the night drew, not a
     regression; read the trend with the 09-30 → 10-01 test-count jump in mind.
   - **The wall is a critical path, not throughput.** Per-spec spans (10-05 / 10-04 / 10-01): `accessibility-data`
     starts its first test at 117 / 96 / 97 s (the beforeAll stub sync + DERIVE) and then runs its ~38 scans serially in
     one file: 285 / 203 / 190 s of tests, ending at 403 / 300 / 287 s — which IS the run's end. `accessibility.spec`
     (348 / 233 / 195 s) and `metrics-config` (185 / 145 / 172 s) finish earlier. Test time / 4 workers is 293 / 212 / 206 s,
     so ~28 % of the worker-slot time is idle (sync waits and the tail), not CPU-bound Chromium.
   - Not the cause, checked: the image cache (key stable across the five identical-SHA nights, 4 hits in 4), the
     `restore-keys` partial hit (only matters on a miss; it works — the miss nights are the Gradle-layer rebuilds), retries
     (no flaky test in the logs), the start-up.

   **Changes (all keep every check; none raises a budget).**
   1. `e2e/tests/helpers.ts` `gotoSignInForm`: on a fresh page (about:blank = a fresh browser context, nothing to clear)
      load `/login` ONCE; the leftover-session path (clear localStorage, reload) is unchanged. Every `login()` used to
      load the SPA twice. **Expected:** ~0.5-1 s per login on the runner, i.e. ~20-40 s off the `accessibility-data`
      chain (38 logins in series) and ~15-25 s of wall off the rest. NOT measured (the compose stack was off-limits for
      this change) — verify, below.
   2. `e2e.yml` `Build images`: export the layer cache only on a key MISS (`actions/cache` saves only on a miss, so the
      export on a hit was discarded — WHY 9 measured it at 14.6 s); a hit uses the new read-only override
      `.github/compose-buildx-cache-readonly.yaml`. **Expected:** `Build images` ~57 s → ~42 s on hit nights; unchanged
      (~155 s) on a miss.
   3. `e2e.yml` gains a `workers` input on `workflow_dispatch` (default 4, the nightly always 4) feeding `E2E_WORKERS`:
      the idle slot time above says 5-6 workers MAY use the sync waits, but a 4 vCPU runner shared with the JVM and
      Postgres may just slow every test (the `expect` timeout is 10 s) — an experiment to run with data, not a default to flip.
   **Considered and left out:** (a) a Playwright setup project doing the stub sync once so the axe scans fan out over the
   workers — project dependencies run in whole-project PHASES (`runner/index.js` `createPhasesTask`), so the data scans
   would start only after every other spec finished: estimated ~250 s vs ~287 s (-12 %) for a config/ownership/teardown
   redesign nobody could run here; (b) sharing one `storageState` login per worker — `web/src/api/session.ts` guards
   against another tab replacing the session (identity key + token-pair compare), so shared contexts risk the refresh
   rotation; (c) `fullyParallel` on `accessibility.spec.ts` — a simulation over the measured durations moved the wall by
   -29 / +4 / +37 s (greedy scheduling puts `metrics-config` last), i.e. noise.
   **Verify** (`workflow_dispatch` of `e2e.yml` on `ci/e2e-image-cache`, at least twice — one run is noise, see the
   10-01..05 spread): `ci-times.mjs --workflows e2e --steps e2e` for `Build images` (~42 s on the first dispatch that
   hits master's cache) and `Run E2E`; from `gh run view <id> --log`, the `accessibility-data` span (first test start to
   last test end, was 190-285 s) and the summed test time per spec — a change is real when the per-test sum drops, not
   only the wall. A/B the workers with `workers=5` and `workers=6` on two dispatches each against the `4` ones.
8. **Test-harness side effects (partly answered 2026-09-30 — see 11).** `IngestWorkerTest`'s ticks are measured and fenced (`withOnlyConnections`); the effect of other classes' enabled-connection leftovers elsewhere is still unmeasured. `NormalizationPipelineTest.clonedConnection` and
   `IngestWorkerTest` create ENABLED connections, so any config change anywhere enqueues a DERIVE that a
   worker in another class runs with the real clock (`IngestWorkerTest` saw 40 derives; ~30
   empty-connection derives spend 2-6 s each in `dim_date` upsert row-lock contention — ADDRESSED: `dim_date` is now
   ensured in its own short transaction (`MetricsStore.ensureDimDate`), and derives instead serialized from
   `analyzeDerivedTables` to their commit, on the ANALYZE lock of the shared tables — since 2026-10-06 only a
   derive without usable statistics does, see "Cause found and fixed: DERIVE's ANALYZE" above). The effect on
   wall time is unmeasured.
9. **`images` at ~4 min — ANSWERED 2026-10-01 (`perf/why-3-9`): the BuildKit layer cache made it SLOWER; removed.**
   Sources: `ci-times.mjs --branch master --steps images`, `gh run view <id> --json jobs` for every master push
   that ran `images` (22 successful runs), and the full log of run #119 (`f2f0b65`).

   | Variant | Runs | Build step | Extra steps | Job |
   |---|---|---|---|---|
   | plain `docker compose build` (docker driver, no cache, no scan), 09-27 → 09-30 | 15 (#45-#81) | 105-142 s, **median 133 s** | none | 109-146 s |
   | BuildKit `docker-container` builder + `actions/cache` layer cache + Trivy (09-30) | 7 (#84-#119) | 151-179 s, **median 170 s** | buildx setup 5-10 s, cache restore 6-11 s, Trivy 39-51 s (86 s once), buildx teardown 8-13 s | 240-268 s, median 248 s |

   Inside the 163 s build step of run #119: cache import from the restored directory ~19 s; the Gradle
   `installDist` RUN **113 s** (~50 s before its first task runs — daemon fork, plugin and dependency
   resolution and download; `compileKotlin` ~46 s cold; it shares the 4 vCPUs with the 23 s web `tsc` +
   `vite build` that runs beside it); runtime stage ~2 s; image export + `docker load` ~8.5 s (a
   `docker-container` builder cannot hand the image to the daemon for free); **cache export 14.6 s — thrown
   away on every exact key hit**, because `actions/cache` saves only on a miss (its post step ran twice in
   seven runs). What `CACHED` saved: `apk add git`, `npm ci` (both in the web stage, which runs parallel to
   Gradle — off the critical path), the `./gradlew --version` Gradle-distribution download (~10 s) and the
   runtime `apt-get` layer. The expensive layer — resolve + compile — sits after `COPY server/src`, so it is
   rebuilt on every commit and NO layer cache can hold it. Net: ~10 s saved on the critical path against
   ~11 (restore) + ~19 (import) + ~15 (export) + ~10 (teardown) + ~8 (load) ≈ 60 s spent, which is exactly the
   +35 s build and +20 s steps the table shows.
   **Trivy** (39-51 s): image pull ~2 s, vulnerability DB download ~5 s, Java DB download ~20 s, the scan itself
   under a second. Measured locally the two databases are 1.4 GB each uncompressed; persisting them with
   `actions/cache` costs a restore of that size per run against a ~25 s download — not worth it, left as is.
   **Fix (`ci.yml` `images`):** back to a plain `docker compose build` on the runner's Docker — no
   `setup-buildx-action`, no `actions/cache`, no `compose-buildx-cache.yaml`; the scan step is unchanged, so the
   job still builds the shipped Dockerfile and scans the resulting `flow-app`. Expected ~133 s build + ~45 s scan
   + a few seconds of checkout ≈ **3 min** (was 4m05s). The 2.5-min target is still missed by ~30 s and the floor
   is what the job proves: the Gradle build inside Docker (~113 s, dependency resolution + Kotlin compile of the
   server) plus the Trivy databases (~25 s of download); neither can go away without weakening it (building
   `installDist` on the runner would stop testing the Dockerfile). Budgets are unchanged. **Not changed:** the
   nightly `e2e.yml` keeps its cache (one sample of its `Build images` step with it: 153 s + 11 s setup + 26 s
   teardown; the old combined step gives no comparable split) — re-check it with WHY 7's data. **Verify** with
   `ci-times.mjs --branch master --steps images` after the first master run on this change; the job is not on
   the run's critical path (`server` is ~9 min), so this is cost, not latency.
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

11. **Small tests that took seconds for no data reason — ANSWERED 2026-09-30 (`perf/slow-small-tests`).**
   Prompted by a CI run of PR #40 (4 vCPU) in which single-request or handful-of-request tests cost
   5-20 s. Each was measured locally (18 cores, load average 4-5 from other worktrees, so single figures
   carry +-10 %; scratch timing tests, removed) against the baseline full run of master (`:server:test`,
   795 tests, suite span 3m19s):

   | Test (class) | CI | Local before | Cause (measured) | Verdict | Local after |
   |---|---|---|---|---|---|
   | `AnonymousAccessTest` "a correctly signed access token without a jti is 401" (one request) | 20 s | 5.5-6.0 s | It is the FIRST test of the JVM (the class sorts first): Testcontainers start 1.9 s + the first `testApplication` 2.1 s (Flyway over an empty database, class loading; the 2nd and 3rd boots take 75 ms) + JIT. On CI add the postgres/ryuk image pull and a 2-3x slower core. The same test alone in the class after the first costs 0.08-0.23 s | **Intended, attribution only**: whichever test runs first pays the one-time container + migrate cost; no per-test waste to remove | 5.3-5.6 s (unchanged) |
   | `ForwardedHeadersTest` (5 tests, 3 of them 2.3-2.6 s) | 11 s | 7.5 s | Each of the 3 tests sends 11-12 failed logins with a distinct UNKNOWN email; the login route answers an unknown email with a discarded full cost-12 bcrypt verify (`TIMING_EQUALIZER_HASH`, the anti-enumeration timing equalizer): 226 ms measured per verify locally, x 11-12 = 2.5 s per test. Nothing to do with proxy trust | **Fixed**: each attempt logs in as a freshly SEEDED account (`TestUsers.seed`, bcrypt cost 4, ~1 ms) with a wrong password — the same 401 path, same bucket, same assertions; the equalizer itself is production behaviour and untouched | 0.32 s |
   | `RawForwardedForLinesTest` (1 test) | 5.3 s | 3.0 s | Same: 12 raw unknown-email logins x 226 ms = 2.7 s over ~0.3 s of Netty boot | **Fixed**: same seeded-account approach | 0.6 s |
   | `DerivedStubFixtureTest` (1 test) | 12 s | 3.8-4.4 s | The test only compares a digest; the time is `DerivedStubFixture.connectionId()`'s one-time clone (0.27 s) + pinned DERIVE (2.9-4.4 s) that its first user pays (and, on CI when nothing ran before it, the shared ~16-23 s sync) | **Intended, attribution only**: `MetricsDerivationTest` pays it instead if this class is skipped; the suite total is identical | 3.9-4.4 s (unchanged) |
   | `MetricsAnalyzeTest` (2 tests, 9.6 s) | 9.6 s | 3.5 s | Its subject IS a DERIVE (`ANALYZE` inside DERIVE): clone 0.27 s + derive 2.9-4.4 s; the `last_analyze` poll resolves on its first read (7 ms, 1 poll — the 500 ms poll interval is never slept) | **Intended** (a derive is the subject; a smaller one would not pin the same statistics) | 3.5-3.7 s |
   | `ConnectionPoolTest` (8 tests) | 5 s | 4.4 s | (a) "concurrent transactions are bounded" polled a FIXED 2 s window; (b) "a saturated pool times out an acquire" waits for the real timeout | (a) **Fixed**: watch until the pool shows its `maxSize` backends, then a 500 ms settle window (2 s stays the ceiling), same assertion; (b) **Intended**: the timeout is the subject and `maxAcquireTimeSeconds` has `min = 1`. Side finding, not changed: r2dbc-pool surfaced the acquire failure at **2x the configured value** (1 s -> 2.0 s, 2 s -> 4.0 s, measured on the bare `ConnectionPool.create()` too), so the effective production acquire budget is twice `postgres.pool.maxAcquireTimeSeconds` | 2.9 s (a: 2.1 -> 0.6 s; b: 2.1 s) |
   | `IngestWorkerTest` "heartbeat writes progress" 5.6 s, "a failing run schedules a backoff" 3.4 s, "a tick enqueues a due SYNC job" 3.0 s | (not in the CI list) | 13.3 s the class | The three tests drive `IngestWorker.tick()`, which enqueues due jobs for EVERY enabled connection in the shared database and claims from the ENTIRE shared queue. Probed in the full run: 13-14 pending DERIVEs of enabled connections left by earlier classes (`DataSourceRoutesTest`, the encryption tests, `DataProfileTest`, and the 1,200-issue `jira-shared-fixture` connection — a real ~4 s derive) and, after the first tick, a SYNC plus its chained DERIVE per enabled foreign connection. The same tests alone take 0.45-1.1 s. This is open question 8 (harness side effects), now measured | **Fixed**: `withOnlyConnections` (`TestEnvironment.kt`) fences each tick — every OTHER enabled connection is temporarily disabled and every OTHER claimable job (PENDING, or RUNNING with an expired lease) parked under a far-future lease, set up in one transaction and restored in a finally (the `TestUsers.withSoloAdmins` precedent: borrowed, never destroyed). No assertion changed | 2.3 s the class (0.3-0.8 s per test) |

   Whole suite, local, `cleanTest :server:test` (795 tests, gaps empty): sum of the four touched classes
   (`IngestWorkerTest`, `ForwardedHeadersTest`, `RawForwardedForLinesTest`, `ConnectionPoolTest`)
   **28.2 s -> 6.1 s (-22.1 s)**; suite span 3m19s -> 3m02s / 3m14s (two runs; the noise from
   the shared machine, +-10 s on `MetricsDerivationTest` alone, is as large as the gain, so read the
   per-class rows, not the totals). CI should gain about twice the local figure: bcrypt and the
   derive-free classes scale with the 2-3x slower core, and the fence removes the backlog derives that ran
   on it. Not touched: any other test under 2 s (`ProductionHttpTest` ~1 s each boots a real Netty engine,
   `BootstrapTest` ~1 s is a cost-12 hash of the rotated admin password, the cancel test's 1 s is the
   heartbeat interval — all intended).
   Guidance for new tests (also in `testing.md`): a test that needs MANY failed logins (rate-limit,
   lockout, proxy trust) seeds real accounts — an unknown email costs a cost-12 bcrypt verify per attempt; a
   test that calls `IngestWorker.tick()` wraps it in the queue fence.

12. **The `web` job +42 % since checkup 1 (1m09s → 1m51s–2m19s on master) — ANSWERED 2026-10-06 (checkup 2): the code grew, the job didn't regress.**
   Between the 09-26 runs (60-80 s) and master 184495a, `web/src` grew from 9.9k to 42.3k lines (×4.3: the v0.3.0 report
   pages and the 0.4.0 Deep dive) and from 42 to 124 test files (×3). Step for step (09-26 → 10-06, `gh run view`):
   `npm run lint` 6-8 s → 19-24 s, `test:coverage` 22-37 s → 53-72 s, `build` 5-7 s → 9-13 s; `npm ci`, the API check and knip
   unchanged. The job grows slower than the code (×1.8 for ×4.3), so nothing per-file got worse; the 1m09s at checkup 1 was a
   fast-runner sample. Checkup 2 adds the shuffled vitest run (~10 s on CI). No budget change: the job sits inside its
   3-min alarm; the next lever, if it nears it, is an eslint cache (`--cache` + `actions/cache`), since lint grew fastest.

13. **Half of every PROCESS/DERIVE round trip was `SET STATEMENT_TIMEOUT` — ANSWERED 2026-10-06 (`perf/exposed-statement-timeout`): a per-connection cache skips it.**
   Found in bytecode by a review: Exposed R2DBC 1.5.0 calls `setTimeout(transaction.queryTimeout)` before EVERY statement
   (`SuspendExecutableKt.executeIn`) and r2dbc-postgresql's `PostgresqlConnection.setStatementTimeout` sends `SET STATEMENT_TIMEOUT = n`
   as a separate simple query Exposed awaits — app-wide, even at the default 0. **Counted** on the real stack (the test Postgres with
   `log_statement=all`, the server log lines of one run; a wrapper counter agreed exactly on the SETs; "real statements" are executes, so a
   batch insert re-executing one statement counts several):

   | One run on the stub | Round trips | `SET STATEMENT_TIMEOUT` | BEGIN/COMMIT/ROLLBACK | Real statements | Round trips with the cache |
   |---|---|---|---|---|---|
   | PROCESS (clone, 1,200 issues) | 1,711 | **729** (43 %) | 61 | 921 | 982 |
   | DERIVE (pinned, clone) | 894 | **440** (49 %) | 7 | 447 | 454 |
   | report `cycle-time` | 20 | 8 | 2 | 10 | 14 (2 SETs) |
   | report `filters` | 32 | 15 | 1 | 16 | 17 (0) |
   | report `velocity` | 18 | 7 | 2 | 9 | 13 (2) |

   A report keeps two SETs by design: its transaction needs 30 s, the next borrower of that connection needs 0 again.
   **Fix:** `infra/db/StatementTimeoutCache.kt`, a `Connection` decorator below the pool that remembers the last value the server confirmed per
   physical connection and skips an unchanged `setStatementTimeout` (design and rule: `persistence.md` "Statement timeouts").
   **The finding that shaped it:** PostgreSQL reverts a session-level `SET` made inside a transaction when that transaction rolls back, and a
   statement cancelled by the budget aborts its transaction — so the cache must forget its value on every rollback, failed commit and
   `setAutoCommit(true)`; without that, a budget that was just enforced is silently NOT applied to the next report (mutation-tested: removing
   the rollback invalidation turns two `StatementTimeoutCacheTest` cases red).
   **Before / after** (local, 18 cores, a SHARED machine — load average swung 3-11 between runs, so read the figures as a trend, not a
   benchmark; alternated off/cache back to back, one JVM per run, steady-state laps 2-7, medians, ms):

   | | off | cache | off | cache | off | cache | off |
   |---|---|---|---|---|---|---|---|
   | PROCESS (stub) | 728 | **623** | 843 | 776 | 687 | **609** | 672 |
   | DERIVE (stub) | 1,172 | **1,112** | 1,355 | 1,275 | 1,112 | **1,080** | 1,108 |
   | 20x `cycle-time` report | 112 | **101** | 143 | 131 | 118 | **100** | 118 |

   PROCESS 8-15 % faster, DERIVE 3-6 % (its time is mostly the database-side WIP `INSERT … SELECT`), reports 8-15 %. Micro-benchmark
   (`exec("SELECT 1")` through Exposed): 3,000 selects in one transaction 460-520 ms off vs 236-330 ms with the cache (about -45 to -50 %,
   two round trips become one); 1,000 single-select transactions 332-386 ms vs 254-308 ms (-20 to -25 %). Whole suite
   (`cleanTest :server:test -Pforks=2`, alternated): 133 s / 142 s off vs 127 s / 136 s with the cache — about -5 %, inside the noise.
   `stub-process` is now ~0.6-0.65 s (was 0.75-0.85 s), `stub-derive` ~1.1 s steady. Behaviour change to know: a hand-rolled
   `SET LOCAL statement_timeout` is no longer reliably undone by Exposed — the rule stays "only the `queryTimeout` sets it".

14. **DERIVE died with `OutOfMemoryError` at production sizing after the multi-row INSERT work — ANSWERED 2026-10-07 (`fix/derive-heap-retention`): Exposed kept every executed statement until the transaction ended.**
   Live on master `3f51ab4`: the scale-20 DERIVE (24,000 issues, `-Xmx512m`, 768 MiB container, `docker-compose.perf.yaml`) that passed in 137.8 s on
   2026-09-29 (before WHY 3's `insertRows`) failed with `Java heap space`, in `MultiRowInsert.writeRows` and in
   `PostgresqlSqlParser.tokenize` (the victim, not the cause); even a 3 GiB heap kept ≥ 725 MB live (a Full GC reclaimed nothing). Class histograms
   mid-run: `ParsedSql$Token` ~4.0 M, `String` 4.3 M, `byte[]` 4.4 M, `EncodedParameter` 1.75 M — real derive data is a few MB.
   **Cause (bytecode + measurement, not a guess):** `R2dbcTransaction.exec(sql, args)` wraps the SQL in a `SuspendExecutable`, and
   `SuspendExecutableKt.executeIn` does `transaction.executedStatements.add(statement)` (before the after-execution interceptors); the only things
   that empty the list are `clearExecutedStatements()` — which `executeIn` itself calls before a statement when `!db.supportsMultipleResultSets` —
   and `closeStatementsAndConnection`, which runs at the end of every top-level transaction (commit OR rollback) and does nothing else — it closes
   no statement (`javap -c` of all three). DERIVE is deliberately ONE transaction, so each of its multi-row statements (up to 32,000 bind parameters: ~100,000 parsed SQL
   tokens in the driver's per-statement `ParsedSql` plus every `EncodedParameter`) stays reachable until the end. The probe
   (`item_stage`, one maximum 31,998-parameter chunk per round, one transaction, heap after `System.gc()`): `executedStatements` grew by one
   per chunk and the live heap by **~8.2 MB per chunk** (72 -> 109 -> 142 -> 174 -> 206 -> 238 -> 270 MB over 24 chunks); with the fix the list
   stays empty and the heap sits flat at 86 MB for all 24. **Not the driver's statement cache:** r2dbc-postgresql's `BoundedStatementCache`
   (bounded to `PREPARED_STATEMENT_CACHE_QUERIES` = 256) keys on `CacheKey(String sql, int[] parameterTypes)` and maps to a statement NAME — it
   never holds the `ParsedSql` (that lives on the `PostgresqlStatement` instance) — and the flat 86 MB above was measured WITH the cache active.
   Its cost is the SQL strings of the cached texts only (the ~128 texts of a DERIVE; a few MB).
   **Fix:** `MultiRowInsert.writeRows` calls `tx.clearExecutedStatements()` after every chunk (the one public hook; nothing is closed by
   Exposed when a transaction ends either, so nothing is lost, and `openResultRowsCount` it also resets is a log-threshold counter).
   **Scope caveat:** the call empties the transaction's WHOLE list, the caller's earlier statements too (DERIVE's per-batch `inList` SELECTs, ~80 MB at
   scale 20 if kept). DERIVE's per-batch reads are bounded only because every batch ends in an `insertRows`; a future step that reads per batch
   but writes via `batchInsert`/`exec` would bring the linear retention back. One transaction stays one
   transaction (the whole-rebuild atomicity is untouched); PROCESS's page transactions commit and clear anyway, so its speed is unchanged. Alternatives
   not taken: `INSERT … SELECT FROM unnest($1::t[], …)` (constant SQL text, but a new binding path for every column type — a rewrite for no gain
   once the retention is gone) and a smaller chunk (it only slows the leak).
   **Pin:** `MultiRowInsertRetentionTest` — (1) after 3 chunks in one transaction the `executedStatements` list (reflection on the Exposed field; a
   rename on an upgrade fails loudly) is empty: the cause (negative control: with the `clearExecutedStatements()` line removed it fails). A second case measured
   the effect (retained heap after 4 warm-up + 8 maximum-size chunks: < 30 MB flat vs ~66 MB leaking) but cost 4.7-5.0 s per fork for no
   extra signal over the cause pin, so it was dropped before merge (build times matter); the class now takes ~0.02 s. **Result at scale 20 / 512m** (`metrics.md` "Performance (scale 20)"): DERIVE first 38.0 s, mapped 50.1 s, warm 52.2 s, live heap
   130-275 MB, two connections at once 68.9 / 69.3 s and still inside 512m. Also added: `-XX:+ExitOnOutOfMemoryError` in `applicationDefaultJvmArgs`
   — the OOM had killed single threads (the worker coroutine, a Netty event loop) and left a half-dead process that compose/k8s never restarted.

15. **The `web` job crossed its 3-min alarm (3m29s median on master, 2026-10-09) — ANSWERED 2026-10-09 (`ci/web-single-vitest`): the suite ran twice.**
   Step for step (`gh run view`, master 47db43c → a4fa9b5): the shuffled vitest run checkup 2 added (WHY 12 said "~10 s") took
   **30-58 s**, and `test:coverage` grew 57-65 s → 76-80 s as the 0.4.0 tests landed — lint, build and `npm ci` stayed flat. Fix:
   ONE run, `npm run test:coverage -- --sequence.shuffle` (CI and `scripts/gates.sh web`), which keeps both the coverage
   thresholds and the order-independence proof (coverage under shuffle was already shown identical, WHY 12's checkup notes).
   Expected: the job drops by the 30-58 s second run, back under the alarm. No budget change. The next lever is unchanged:
   an eslint cache.

16. **DERIVE at scale 20, two connections at once, and ANALYZE at scale — ANSWERED 2026-10-09 (master `347dbd8`, a measurement, no code change).**
   Same machine and `docker-compose.perf.yaml` sizing as WHY 14 (`-Xmx512m` in 768 MiB, postgres 1 GiB), 24,001 issues (the A31
   Program added one), two connections with their own teams, `ingest.workerSlots` = 2 (the default); figures in `metrics.md`
   "Performance (scale 20)", the 2026-10-09 table.
   - **Warm DERIVE is bimodal — ~32 s or ~53 s — and the planner decides which.** The WIP team/task statement's correlated
     sub-select (task → sprint → team, ~4.5 M executions per derive) reads the 168-row `dim_sprint` either by its primary-key index
     (cost 8.17) or by a seq scan (8.15); a statistics nudge flips it. Index: the statement takes 11-15 s; seq scan: 27-28 s alone,
     42-45 s in a pair (confirmed from auto_explain plans on every slow and fast run). Dead tuples don't explain it (a slow run
     started with 0, a fast one with 620k). The epic WIP statement flips too (2.95 s vs 8.4 s).
     **Fixed 2026-10-14 (`perf/wip-sprint-team`):** the sprint → team and assignee → team lookups are set-based joins
     (`sprint_team`/`assignee_team` CTEs, `LEFT JOIN`ed per (day, task)), so `dim_sprint` is read once per statement and
     there is no per-row plan choice left. Same scale-20 data, one connection, three teams with 40 memberships (so the
     assignee fallback runs too): the statement alone 23.5-33.0 s → 2.6-3.3 s (`EXPLAIN ANALYZE`, `EXCEPT ALL` both ways
     empty: identical rows), 1.4-3.9 s inside a derive; **warm DERIVE 50.4-62.0 s → 22.0-27.6 s** (13 runs). Pinned by
     `WipPlanShapeTest` (no `SubPlan` node in the statement's plan). The epic WIP statement kept ONE plan in every logged
     run (auto_explain, 4 derives): its 3.0-4.5 s spread, and the domain statement's 2.2-11 s, are run-to-run variance,
     not a plan flip; the domain statement is now the largest WIP cost.
   - **Two at once vs one alone:** 1.07-1.29x one derive on the fast plan, 1.25-1.37x on the slow one (0.55-0.68 of sequential;
     10-07 measured 1.3-1.5x). **No real lock contention:** 1 s `pg_stat_activity` samples over ~350 s show derive sessions on
     CPU (364 samples) or the app computing (96 idle-in-transaction), a handful of WAL/IO waits, and ONE relation lock wait
     (< 1 s, one post-commit ANALYZE behind the other on `dim_date`). The pair penalty is shared CPU (postgres ~1 → ~2 cores).
   - **ANALYZE at scale 20 costs 0.33-0.45 s per derive** (~1 % of a derive): post-commit 326 ms (statement log), in-transaction
     on a first derive 383 ms — but that one holds its locks to the commit (~33 s here), blocking another connection's first
     derive meanwhile. Two post-commit ANALYZEs plus autovacuum on the same tables collided once: 3.8 s / 4.1 s.
   - **Memory headroom shrank:** app peak 712-731 MiB of the 768 MiB limit (617 MiB on 10-07), alone and in pairs alike, no
     OOM — under 60 MiB left. Answered by WHY 17: the heap reaching its cap, not growth.
   - Seen on the way: autovacuum works `agg_daily_wip`, `item_stage` and `task_assignee` hard (each ~50 % dead after a derive), and
     one pair's `DELETE FROM metrics.dim_task` took 3.1 s in both connections at the same millisecond (a shared lock holder,
     likely autovacuum truncating — inferred, not measured).

17. **The app peaked at 712-731 MiB of its 768 MiB at scale 20 (617 MiB on 10-07): what grew? — ANSWERED 2026-10-15 (master `2321048`, a measurement, no code change): the heap's COMMITTED size reached its `-Xmx512m` cap; nothing leaks and the live data did not grow.**
   Same machine and `docker-compose.perf.yaml` sizing (`-Xmx512m` in 768 MiB), 24,001 issues, two connections with their own teams, the
   board mapped and 30 seeded memberships; 3 single DERIVEs, then 3 pairs. Sampled every 5 s: the cgroup (`memory.current`, `memory.stat`
   anon/file) and Native Memory Tracking (`-XX:NativeMemoryTracking=summary` added to `JAVA_OPTS`; the runtime image is a JRE, so `jcmd`
   comes from the JDK image copied into the container: `docker cp` its `/opt/java/openjdk` to `/tmp/jdk`, then
   `docker exec flow-perf-app /tmp/jdk/bin/jcmd 1 VM.native_memory summary scale=MB`; GC logging at runtime: `jcmd 1 VM.log output=/tmp/gc.log what=gc`).
   - **The heap committed in steps, 262 → 352 → 436 → 512 MB**, the last two during the pairs. SerialGC expands the old generation when
     a young collection's promotions don't fit, and shrinks it only slowly after a Full GC.
   - **The live set did not grow:** after a Full GC 26-213 MB in the pairs (10-07: 230 MB in pairs, 275 MB for a cold first derive). A31,
     the WIP joins and the capacity work added no retained data.
   - **The JVM's non-heap is flat at ~150 MB** over all nine derives: metaspace 55, class space 10, code 22 (C1 only), symbols 25, thread
     stacks 12 (~105 threads), the rest ≤ 5 each (NIO/Netty direct memory 4-5). Memory outside NMT (glibc malloc, native libraries) was 30 MB at
     most (anon minus NMT committed).
   - **The budget is therefore fixed, not a trend:** 512 (heap at its cap) + ~150 (non-heap) + ≤ ~30 ≈ 690 MiB of private memory at worst;
     measured peak **667 MiB anon**. The cgroup's own peak (`memory.peak` 745 MiB, docker stats ~720) also counts ~50 MiB of page
     cache, which the kernel reclaims before it OOM-kills. Real headroom under the 768 MiB limit is ~80-100 MiB, not < 60. 617 MiB on
     10-07 was simply a run whose heap had not yet expanded to its cap.
   - **What to watch at the next scale step:** the live set after a Full GC against `-Xmx` (213 / 512 MB at scale 20 in pairs), not the
     container RSS. The heap is the part that scales with data. If it outgrows the cap, `-XX:+ExitOnOutOfMemoryError` restarts the
     process cleanly; the container limit is reached only if non-heap grows, and it didn't. Doubling the data (scale 40) projects
     to ~430 MB live in pairs, which is too close to 512: raise `-Xmx` and the container limit together then (+256 each), or run one
     worker slot.

   - **2026-10-16 follow-up (brief 03 decision B, branch `feat/wip-done-band-at-query-time`):** the WIP step no longer writes `stage = DONE`
     rows (60 % of `agg_daily_wip` on real data), and report 9 reads that band at query time (`reports/WipDoneBand.kt`, one set-based
     statement, `WipPlanShapeTest` pins "no SubPlan"). `EXPLAIN (ANALYZE, BUFFERS)` on a synthetic connection of 6,000 tasks (4,500
     done, `dim_date` 757 days, stats analyzed, stub-sized Docker Postgres): unit-wide 186 days **63 ms** (a nested loop of days x DONE
     intervals with the covering predicate as join filter, 644k pairs, hash-aggregated), all 757 days **211 ms**, a team / UNASSIGNED /
     a domain **8-13 ms**. The DERIVE side (fewer rows to insert) was not re-measured at scale 20.

   - **2026-10-22 follow-up: the compose `all` role was OOM-killed at 512m on real data.** `docker-compose.yaml`'s `app` (web + worker
     in one JVM) still had the web budget, the baked `-Xmx256m` in `mem_limit: 512m`. On the user's stack (three real connections,
     2,288 / 4,355 / 7,841 work items) a membership save queued three DERIVEs; two ran at once and the kernel killed the container
     (`OOMKilled`, exit 137) with the heap committed at its 256 MB cap (150 MB used, no `OutOfMemoryError`) and non-heap at 106 MB.
     Re-run at a 1 GiB limit: `memory.peak` 522 MiB (~93 MiB page cache), anon 423 MiB, of which heap 247.5 MiB, non-heap 93.7 MiB and
     ~82 MiB outside both. The jobs' leases were reclaimed and they succeeded after the restart. Fix: the compose `app` takes the
     worker's sizing measured above, `JAVA_OPTS=-Xmx512m` in `mem_limit: 768m`; the baked default and the k8s web/worker sizing are
     unchanged. WHY 26 (`perf/jvm-memory`) then cut the footprint itself: the same pair now peaks ~470 MiB anon instead of 675.

18. **`server` over its 10-min alarm on master (median-5 13m41s, 2026-10-17; 6m36s on 10-15) — CAUSE FOUND 2026-10-17 (master `04c6e32`, a measurement): runner speed, suite growth, and Gradle's static fork assignment. Fork imbalance: ANSWERED 2026-10-17 (`perf/balanced-forks`, below); runner speed: not ours.**
   Three effects, separated with the uploaded HTML test reports (`server-reports`, per-class times) and one local
   `-Pforks=2` run (18 cores, `server/build/test-results/test` XML timestamps):

   | master run | tests | sum of class time | test task wall | wall / sum |
   |---|---|---|---|---|
   | 10-14 `1ada964` / `7c580d5` (job 6m36 / 5m45) | 1176-1184 | 467 / 421 s | 4m07 / 3m43 | 0.53 |
   | 10-16 `b09bf3c` / `33df3b8` (job 7m25 / 12m20) | 1393 | 469 / 810 s | 5m20 / 9m06 | 0.68 / 0.67 |
   | 10-17 `4479128` / `04c6e32` (job 8m02 / 14m14) | 1447 | 532 / 934 s | 5m59 / 10m32 | 0.67 / 0.68 |

   - **Runner speed (not ours):** the same tree costs 532 s of class time on one runner and 934 s on another
     (x1.75, every database-heavy class slower by the same factor; same `ubuntu-24.04` image `20261012.334.1`, so
     the difference is the hardware behind it, which the job log does not name). Master alternated 8 and 13-14 min
     all of 10-16/17. #190 (Gradle 9.8, Kover 0.9.11) is NOT it: PR branches cut before it ran 12-14 min the same
     morning. The 25-min job timeout still holds (worst 14m14s).
   - **Suite growth (expected):** +23 % tests in three days (the report follow-ups), +14-26 % class time.
   - **Fork imbalance (the fixable part):** Gradle hands test classes to the `maxParallelForks` JVMs ROUND-ROBIN in
     scan (alphabetical) order, all up front, with no work stealing. In the local run one fork's queue was empty at
     95 s and the other ran alone to 213 s (`MetricsDerivationTest` 44 s, `MetricsAnalyzeTest` 17 s,
     `DimDateContentionTest` 15 s, `MetricsDigestTest` 9 s and the `Report*`/drill tail all drew the same fork);
     an even split is ~155 s (-27 %), on CI ~270 s instead of ~360 s on a fast runner. Every new class shifts every
     later class to the other fork, which is why wall/sum drifted from 0.53 to 0.67 with no code getting slower —
     and why WHY 5's "the largest class does not bound the wall" held on 09-30 but is luck, not a property.
     Fixed below.

   **Fix landed 2026-10-17 (`perf/balanced-forks`).** With `-Pforks>=2`, `server/build.gradle.kts`
   (`balancedCandidateOrder`) hands Gradle the test classes in a planned order: LPT over the per-class weights of
   `server/test-weights.tsv` (suite time minus fixture-cost markers), each fork capped at its round-robin share of
   slots, laid out so that position `p` is exactly fork `p % forks`'s next class (Gradle visits a composite of
   per-class file trees in declaration order, and deals round-robin up front). So each fork opens with a heavy class
   and the queues end together; no class is added, dropped or run twice (same candidate set, `**/*Test.class`).

   | local, 18 cores, `-Pforks=2`, 182 classes / 1448 tests | span | ideal (sum / 2) | wall / sum | test-task wall |
   |---|---|---|---|---|
   | before (alphabetical, one fork alone from ~95 s) | 3m32s | 2m33s | 0.691 | 3m31s-3m35s |
   | after, run 1 | 2m33s | 2m32s | 0.502 | 2m39s |
   | after, run 2 | 2m37s | 2m37s | 0.502 | 2m43s |
   | after, run 3 (weights refreshed from a balanced run — the committed file) | 2m43s | 2m34s | 0.531 | — |

   Run-to-run noise on a shared laptop is a few seconds either way (run 3: 9.7 s, 6.3 % over ideal, inside the 8 %
   `forkBalance` warning). In runs 1-2 both forks finish within ~1 s of each other (152.2 / 153.0 s and 156.4 / 157.3 s into the run); `MetricsDerivationTest`
   and `MetricsAnalyzeTest` start at t=0 on different forks. Per-fork fixed costs from the `fixture-cost` markers:
   Postgres container 3.4-4.7 s, stub SYNC 9.3-10.2 s, first DERIVE ~1 s (so ~15 s a fork, not the ~25 s older docs
   quoted); a fork pays them in whichever class touches the fixture first. Scan overhead: the first suite starts ~4-5 s
   after the Gradle invocation against ~1 s with the plain tree (the walk of the class dirs plus ~182 file-tree
   components, ~3-4 s once per invocation); `-Pforks=1` never enters the branch. The weights file is not a task
   input: touching it leaves `:server:test` UP-TO-DATE. CI figures (8 green master runs 2026-10-17, `5dd9edc`..`7836089`, from the
   slow-tests fork-balance block in the `server` log): excess over ideal 0.7-3.5 s (0.2-0.8 %), span / sum 0.501-0.504 on every run, so
   the forks end together on CI as locally. The suite sum still swings 10m20s-16m33s and the `server` job 7m37s-12m15s (median 11m17s)
   between runners on the same tree size — runner speed, not imbalance, is what is left.
   Honest limit: runner speed (x1.75 between runners, above) is untouched; this removes the avoidable ~27 % of the
   test wall, not the slow-runner days.
   The balanced order also surfaced a latent hazard (CI run 39034284450, PR #202): each fork now opens with a heavy
   fixture class, whose first test paid Postgres + SYNC + DERIVE (15.5 + 22.6 + 6.1 s on the 4-vCPU runner) inside
   `testApplication`'s hard 60 s limit and died with `UncompletedCoroutinesError`; it is closed by warming the fixtures
   in a `@BeforeClass` (`warmStubFixtures()`, enforced by `StubFixtureWarmupGuardTest`), so that cost now lands in
   class-level setup, whatever order a fork runs in.

19. **`server` +52 % and `web` over its alarm at the 1.0.0 milestone (2026-10-18) — CAUSE FOUND 2026-10-18, no fix yet.**
   Data: the fork-balance block of the 24 green master runs 10-17 16:35 → 10-18 10:37 (`7d9c5f8`..`0d1e161`), per run the suite
   sum and the per-fork fixture costs.
   - *Runner speed, again, is most of it.* The suite sum is bimodal — 10m20s–12m37s on fast runners, 15m04s–17m38s on slow
     ones — and the stub-SYNC fixture cost tracks it exactly (26–31 s fast, 37–45 s slow, per fork). The latest five runs landed
     mostly on slow runners, the previous five mostly on fast ones; the +52 % is that draw, not a regression.
   - *Suite growth is ~10 %.* Normalised by the same runner (suite sum ÷ stub-SYNC cost) the ratio went from ~22.5 to ~24.9
     over the window: 0.29.0 → 1.0.0 added Compare people, Home, Jira links, the tolerance setting and their tests.
   - *The forks stay balanced* (excess over ideal 0.2–1.5 % on every run), so WHY 18's fix holds.
   - *`web`:* the shuffled coverage step went 103 s → 125 s (+21 %) with the suite 1,9xx → 2,261 tests (+19 %), lint 31 → 36 s:
     linear suite growth, the same shape WHY 15 answered on 10-09.
   Candidate fixes (answered: `server` by WHY 21, `web` by WHY 20): give each fork the synced+derived stub fixture from a database template/dump instead
   of a SYNC + DERIVE per fork (the largest fixed cost left: 30–50 s per fork, worst on slow runners); for `web`, an eslint
   cache in CI and a look at the vitest pool size on the 4-vCPU runner. Raising the alarms is not the answer. The `web` half is answered by WHY 20.

20. **`web` back under its 3-min alarm on slow runners (median-5 3m28s on master, 2026-10-18) — ANSWERED 2026-10-20 (`ci/web-under-alarm`): an ESLint result cache in CI; the vitest pool size and the coverage reporter were measured and left alone.**
   Question (WHY 19's `web` half): master runs are bimodal, ~110-135 s on fast runners and ~175-213 s on slow ones; on a fast run
   the shuffled coverage step is 79 s (59 %, 103 -> 125 s on slow runners as the suite grew to ~2,261 tests), `npm run lint` 22 s
   (31-36 s slow), build 11 s, `npm ci` 9 s. Which lever pays without weakening a gate (coverage thresholds, the shuffled order and
   `isolate: false` stay)?

   | Lever | Measured | Verdict |
   |---|---|---|
   | 1. ESLint cache (`--cache --cache-strategy content`) | `npm run lint` locally (18 cores): cold **11.8-12.1 s**, warm **0.54-0.60 s** (-95 %); one edited file with an error: 0.52-0.62 s and still red every run (a file with findings is re-linted, never cached as clean); a config change (`max-depth` 5 -> 4): **12.1 s**, i.e. a full re-lint. CI: expected -20 to -35 s on a PR run (the step shrinks to ~1-3 s: Node start-up + the files the PR touched) | **kept** |
   | 2. Cheaper coverage reporter (`text-summary` instead of `text` + `html`) | Full suite, CI-shaped container (below), 3 rounds interleaved, vitest `Duration`: `text`+`html` (base) 40.1 / 43.0 / 43.6 s, `text` only 41.7 / 48.5 / 49.6 s, `text-summary` 41.4 / 49.3 / 53.0 s — never faster than the base. Report phase isolated on one tiny test file (the report still walks all of `src/`): `text`+`html` 1.14-1.16 s, `text-summary` 1.13-1.14 s, no coverage 0.58-0.73 s — so the whole coverage cost beyond the instrumented run is ~0.5 s and the HTML writer is lost in it | **rejected** (no gain; the HTML report stays for local runs) |
   | 3. Vitest pool size on 4 vCPU (`--maxWorkers=4` against the default 3) | 9 paired runs, vitest `Duration`: default 40.1 / 43.0 / 43.6 / 54.7 / 46.9 / 41.4 / 54.1 / 50.5 / 39.8 s (median **43.6**), `--maxWorkers=4` 33.4 / 39.2 / 44.5 / 50.1 / 60.8 / 43.3 / 45.7 / 55.7 / 42.3 s (median **44.5**). Round 1 looked like -16 %, the next eight rounds did not repeat it: the spread between two runs of the SAME variant (40 -> 55 s) is bigger than any difference. The file times sum to 128 s of CPU over 3 workers = 43 s, i.e. the default pool already keeps three cores busy and a fourth worker has to share a core with vitest's main process | **rejected** (within noise) |
   | 3b. `pool: 'forks'` instead of `threads` | 43.4 / 48.1 / 49.5 s against 40.1 / 43.0 / 43.6 s (base, same rounds) — slower, as WHY 6 found | **rejected** |
   | 4. Coverage instrumentation itself | no-coverage runs 39.6 / 38.6 / 39.8 s against 40.1 / 43.0 / 43.6 s (-8 % median, ~3.5 s here) — the price of a gate, untouched | none |

   **How the suite was measured:** a container pinned to four cores (`docker run --cpuset-cpus=0-3 node:24.21.0-bookworm-slim`,
   `os.availableParallelism()` = 4, so the default pool is 3 workers), a copy of `web/` with its OWN `npm ci` inside the container,
   `GIT_SHA`/`GIT_COMMIT_TIME` set (the image has no git, and `VersionStamp.test.tsx` reads the stamp), `--coverage --sequence.shuffle`, all
   2,261 tests green in every run. The absolute numbers (38-55 s) are about half of the runner's 79-125 s (different hardware, and a shared dev
   machine: the same variant swung ±15 s), so only the paired comparisons mean anything. Honest limit: a
   GitHub 4-vCPU runner is 2 physical cores with SMT, which this container is not, so a fourth worker could behave differently there
   — a result for the first CI runs, not for this table; if someone wants to try it, `maxWorkers: 4` is a one-line change and the series says
   whether it moves the median.

   **Fix:** `web/package.json` `lint` = `eslint . --max-warnings 0 --cache --cache-strategy content` (`content`: a fresh checkout gives every file a
   new mtime, so the default `metadata` strategy would miss everything), `web/.gitignore` covers `.eslintcache`, and the `web` job restores/saves
   `web/.eslintcache` with `actions/cache` (the SHA the e2e workflow already pins) under the key `eslint-web-<hash of web/package-lock.json and
   web/eslint.config.js>-<commit sha>` with the same prefix as `restore-keys`. Why it is sound: the ESLint config has no `parserOptions.project`
   (not type-aware), so a file's findings depend on its bytes and the config; ESLint hashes the config, its own version and node's version into
   every cache entry; the key adds the lockfile, so a plugin bump starts cold; `--max-warnings 0` is applied to the cached messages too. A PR run
   restores the newest master cache (caches written on the default branch are readable from PR runs), re-lints what it changed and saves its own
   (about 240 KB). The cache is used locally too: `npm run lint` after the first run is ~0.6 s.
   **Not done, as an option:** splitting `web` into a static job (lint, knip, API gate, build) and a test job would cut the time to green by
   roughly the static share (~35-60 s on a slow runner) at the price of a second `npm ci` and runner; with the lint cache the static share is
   mostly knip + build, so it is not worth it until the tests alone approach the alarm.
   **CI after (16 master runs 10-20 → 10-21, `78a6609`..`f535fca`):** `npm run lint` **2-5 s** on a warm key (30-38 s on slow runners and
   20-23 s on fast ones before); the one cold-key run (`78a6609`, the merge itself) 36 s; `Restore the ESLint cache` 0-1 s. The job: 103-156 s
   on fast runners, 173-197 s on slow ones (195-213 s before) — about -25 s, as expected. The shuffled coverage step meanwhile grew with the
   suite (2,261 → 2,359 tests: slow runners 120-131 → 134-142 s), so a slow runner still lands ~10-20 s over the 3-min alarm; accepted at the
   2026-10-21 status check.

21. **The per-fork stub fixture: a pre-built snapshot per fork, or a cheaper SYNC? (WHY 19's `server` half, 2026-10-20) — ANSWERED 2026-10-20 (`perf/batched-raw-writes`): the SYNC wrote one row per transaction; batched, it halves. No snapshot.**
   CI per-fork fixture cost (the fork-balance block of four green master runs 10-17/18):

   | job (sha) | `server` job | suite sum / span | postgres | stub-sync | stub-derive | per fork |
   |---|---|---|---|---|---|---|
   | `ac5756b` | 7m33s | 10m22s / 5m16s | 12.8 s | 25.8 s | 4.2 s | 42.8 s |
   | `54ee2ea` | 7m58s | 11m07s / 5m35s | 15.5 s | 25.5 s | 4.1 s | 45.1 s |
   | `0d1e161` | 10m54s | 15m04s / 7m38s | 14.9 s | 36.7 s | 5.9 s | 57.5 s |
   | `71cc33c` | 12m20s | 17m37s / 8m52s | 12.2 s | 42.7 s | 7.9 s | 62.8 s |

   The forks end within 0.4-1.5 % of ideal, so one per-fork fixture second is one wall second.
   - *A snapshot does not pay.* Both forks already pay the SYNC in parallel, so building a snapshot once inside the run saves ~0. Reusing
     one ACROSS runs (a CI cache keyed by a digest of everything that shapes it: migrations, stub files, the jira/ingest/norm/metrics
     code, build and dependencies) would have hit on ≤ 64 % of runs (87 of 136 PRs merged since 10-06 left those inputs untouched)
     for ~15-30 s per run on average, and leaves a hole: a key gap restores stale `raw.*` rows that no test re-checks against a real SYNC
     — closing it costs a real SYNC in one fork, half the saving back. Seeding `raw.*` straight from the stub files duplicates the streams'
     mapping in test code and needs the same tripwire; one container with a per-fork template database gains nothing within a run.
   - *Where the SYNC went* (JFR on one local `SyncedStubFixtureTest`, `fixture-cost stub-sync` 11.3 s): the test JVM ran Java 5-19 % of
     one core — it was WAITING on round trips: ~1,330 sequential HTTP calls (1,201 of them the per-issue worklog GETs) and **~11,800 SQL
     statements in ~1,540 transactions**, because `JiraRawWrites` wrote every issue/entity/worklog as `SELECT … FOR UPDATE` + `INSERT`
     in its own transaction and every changelog history as its own `insertIgnore`, and the worklog backfill committed per issue. The
     same shape WHY 3/13 fixed for PROCESS; on a contended 4-vCPU runner every round trip stretches, which is why the cost tracked
     runner speed.
   - *Postgres:* 12-15 s per fork on CI against ~4 s locally with a cached image — ~8-10 s of it is the image pull.

   **Fix.** (1) Batched raw writes (`jira/JiraRawBatchWrites.kt`, `jira-integration.md` "Batched raw writes"): per page, ONE locking
   `SELECT` of the keys (sorted, 1,000 per statement), the new rows as multi-row `insertRows`, the unchanged ones as ONE `UPDATE … IN`,
   the changed/resurrected ones through the single-row path's own helpers (one shared `classifyUpsert` rule); changelogs as
   `insertRowsIgnoringConflicts`; the worklog backfill fetches its claimed batch of 100 issues one by one and writes them in ONE
   transaction (flushed early at 5,000 worklogs, and before a fetch failure is rethrown). Proof of identity: the synced fixture's
   status-interval digest, the derived fixture's metrics digest and an MD5 over all four raw tables are identical before and after
   (`a1748c74…`, `131cfb6d…`, `4b2e6a19…`). (2) The `server` job pre-pulls the pinned Postgres image in the background while Gradle compiles.

   | local, 18 cores, `fixture-cost stub-sync` | runs |
   |---|---|
   | before | 11.18 / 10.89 / 10.84 / 10.23 s |
   | after | 4.75 / 5.26 / 4.64 / 4.59 / 4.64 s |

   What is left of the ~4.6 s: the 1,201 sequential worklog GETs (~2 s), the changelog calls (~0.9 s) and PROCESS (~1 s). Concurrent
   worklog fetches would cut the first but change the request pattern against the real tenant — a separate decision (done in WHY 33).
   Expected on CI: `stub-sync` ~25 → ~10-12 s fast, ~40 → ~15-18 s slow, `postgres` ≤ 7 s — about -20 to -35 s of the test wall.
   Honest limit: the x1.75 runner draw stays; a slow runner still lands over the 10-min alarm.
   **CI after (the fork-balance block of 14 green master runs 10-20 → 10-21, `88fc1b1`..`f535fca`):** `stub-sync` **17-22 s** on fast runners
   (25-33 s before) and **26-29 s** on slow ones (37-45 s); `postgres` **7.7-10.7 s** (11-15.5 s, the pre-pull); `stub-derive` unchanged
   (4-8 s). Per fork: ~30-33 s fast (was ~43-45), ~44-46 s slow (was ~57-63) — **-10 to -17 s**, half the expected -20 to -35 s: on a 4-vCPU
   runner the batched SYNC is ~4-6x its local 4.6 s, not the ~2x the estimate assumed. The `server` job did not shrink with it (474-602 s fast,
   699-763 s slow; 453-548 s and 654-763 s before): the suite grew over the same window (1.1.0-1.6.1: capacity claims, the cost-by-epic
   table, the data-source scope rules), and the fork excess crept to 2.1-2.8 % on new classes missing from `test-weights.tsv` (refreshed at
   the 2026-10-21 status check).

22. **Does the nightly `e2e` image-build cache pay? (the `[todo]` WHY 7 left open, 2026-10-20) — ANSWERED 2026-10-20 (`ci/e2e-drop-image-cache`): no; removed, as WHY 9 removed it from `images`.**
   Data: the `Build images` step of every nightly/dispatch run since the cache's last change (WHY 7, 10-06) against the plain
   `docker compose build` of `ci.yml`'s `images` job on the same Dockerfile (12 green master runs 10-18..10-20).

   | Build | n | Step time |
   |---|---|---|
   | nightly, with the layer cache (key hit or miss) | 14 (10-06..10-20) | 146-198 s, median ~170 s, plus the restore (~4-13 s for ~1 GB) and the post-step save (~4-5 s) |
   | `images`, no cache | 12 | 125-164 s, median ~150 s |

   - *Why a key hit no longer saves anything.* On 10-20 the key hit (same commit as 10-19) and the build still took 167 s: every
     dependency-shaped layer was `CACHED` (Gradle wrapper, `npm ci`), but `RUN ./gradlew :server:installDist` took 133 s — the
     Dockerfile copies the sources before it, so dependency download and compile share one layer, rebuilt whenever a source file
     changes. WHY 7's 57 s "hits" were nights on an unchanged commit (10-02..10-05, the same `9f52356`), when even that layer was cached.
   - *What it cost besides time.* Each entry is ~1 GB (`mode=max`); with branch dispatches writing their own, the repository held
     10.56 GB of Actions caches against GitHub's 10 GB quota, so LRU eviction dropped other entries — the Playwright browser cache
     missed on 10-18 (a cold `playwright install` is ~1 min).
   - *Not done:* a dependency-only Gradle layer before `COPY core/src server/src` would let a cache keep the downloads (~20-40 s of the
     133 s), but it buys nothing for the uncached `images` build, still pays the ~1 GB restore and the quota, and couples the Dockerfile
     to a resolve task that must cover every configuration. Not worth it at ~150 s.
   **Fix:** `e2e.yml` builds with a plain `docker compose build` (no `setup-buildx-action`, no `actions/cache` of the layers, no
   `BUILDX_BAKE_ENTITLEMENTS_FS`); `.github/compose-buildx-cache{,-readonly}.yaml` are deleted; the `e2e/Build images` budget follows
   the `images` measurement (target 2.5 min, alarm 4 min). Expected: the step ~150 s instead of ~170 s, ~1 GB per key off the cache quota.
   **Open, not this item:** `Run E2E` itself is over its 9-min alarm (555 s on 10-18; 174 tests): a WHY of its own (BACKLOG `[new]`).
   **CI after:** `Build images` **153 s** on the 10-21 nightly and 145-172 s (median ~159 s) on the eight dispatches 10-20, with no restore or
   save step around it (before: 146-198 s, median ~170 s, plus ~10-18 s of cache restore/save). The repository's Actions caches went from
   10.56 GB to **3.38 GB** (71 entries, 2026-10-21), under the 10 GB quota; the Playwright browser cache has been restored on every run since (3-6 s).

23. **The nightly `Run E2E` over its 9-min alarm (555 s on 10-18, 174 tests) — CAUSE FOUND 2026-10-20: ten stub SYNCs queued on the app's 2 worker slots and 15 s tick, not test volume or Chromium. Fixed in part (`perf/e2e-shared-fixture`).**
   Method: the list reporter's per-test `(x.xs)` EXCLUDES `beforeAll`, so WHY 7's "summed test time" never saw the setup; the gap
   between a worker freeing up and its file's first test (an earliest-free-worker dispatch model that reproduces all nine runs) is the
   `beforeAll` cost.

   | `Run E2E` | tests | wall | Σ tests | Σ `beforeAll` gaps | worker-slot use |
   |---|---|---|---|---|---|
   | 10-05 nightly (WHY 7) | 134 | 406 s | 1173 s | 245 s (2 syncs) | 72 % |
   | 10-17 nightly / 3 dispatches | 164 | 500 / 571-673 s | 1116 / 1345-1519 s | — | 56-60 % |
   | 10-18 nightly | 171 | 555 s | 1123 s | 749 s | 51 % |
   | 10-20 dispatches, 4 workers | 174 | 512 / 515 s | 1085 / 1118 s | 627 / 534 s | 53 % |
   | 10-20 dispatch, 5 workers | 174 | 602 s | 1611 s | 919 s | 54 % |
   | 10-20 dispatches, 6 workers | 174 | 458 / 681 s | 1259 / 1452 s | 882 / — s | 46 / 36 % |

   - *Sync chains are a third of the slot time* (534-749 slot-s a run): ten full stub SYNCs (six in `beforeAll`s, three in
     `metrics-config`, one through the `data-sources` UI) plus ~20 DERIVEs queue on `ingest.workerSlots` = 2 with a 15 s claim tick, and
     every membership/holiday/settings edit fans a DERIVE out to every enabled connection (`metrics.md` "DERIVE fan-out"). Per file
     (4 workers, 10-20, gap + tests): accessibility-data 98 + 243 s, reports 72 + 116, reports-drill 92 + 41, reports-compare 103 + 11,
     capacity 94 + 29, public-holidays 73 + 13. `reports`, dispatched last (alphabetical), inherits a ~80 s sync wait and ends the run.
   - *Growth 134 → 174 tests* added +48 tests (335 s) and four specs that each own a synced connection (≈ +360 slot-s of gaps); the
     126 unchanged tests got FASTER (1115 → 750 s: WHY 7's single-load login).
   - *Per-test overhead is small:* 232 logins a run at ~0.36 s server + ~0.6 s browser each.
   - *More workers make it worse:* they start more syncs at once on the same 2 slots (gaps 534 → 882-919 s, per-test Σ +13-44 % from
     Chromium contention), and one 6-worker run starved `accessibility-data`'s `beforeAll` for 340 s — the sync wait required
     `currentJob === null`, which fan-out DERIVEs kept set after the SYNC had succeeded. Keep 4 workers.

   **Fix (S1-S3).** (1) The e2e stack runs the scheduler at its 5 s floor (`INGEST_SCHEDULER_TICK_SECONDS`, compose default still 15),
   sync waits key on the SYNC job id (no `currentJob` condition; a gone source or a lost session fails at once), polls 5 → 2 s.
   (2) A shared READ-ONLY fixture: global setup creates one stub connection + team and enqueues its SYNC without waiting; the first
   consumer completes it (SYNC by id, the FLO board mapped only while unmapped — a 409 from two first writers is re-read and accepted —,
   the golden sprint's DERIVE, pause), and global teardown deletes it. `accessibility-data`, `reports-drill`, `reports-compare` and
   `metrics-config`'s tests 3-4 read it (the user's decisions, 2026-10-20); `reports` keeps its own (it sets its team's manager).
   Expected (simulated on the 10-20 costs): ~513 → ~370 s at 4 workers — wrong, see "CI after". Local (18 cores, isolated stack, 174
   tests): 3.8 min.
   **Measure next, not done (done in WHY 37):** heavy-first dispatch (a second Playwright project listed first; -20-40 s simulated) and a minted login
   (Lettuce's `e2e/sessions.ts`; -15 s); `reports` onto the shared fixture with a throwaway team for its manager test; a worker-count
   re-A/B once the syncs are gone. Considered and left out: `fullyParallel` on `accessibility.spec`, more ingest worker slots (CPU-bound
   on 4 vCPU), JVM flags in the shipped image.
   **CI after:** the two `workflow_dispatch` runs of the branch (10-20, 174 tests) **519 / 527 s**, the first nightly with it (10-21, 182
   tests) **486 s** — against 512 / 515 s for the same 174 tests before (4 workers) and 555 s on the 10-18 nightly. The run is under its
   9-min alarm, but the gain is ~0-70 s, not the simulated ~145 s: the simulation over-predicted, and why is not analysed yet (the per-file
   `beforeAll` gaps of the 10-21 nightly are the first thing to measure, before any of the "Measure next" levers — BACKLOG).

24. **A hung `server` job: one failed test, then 19 silent minutes to the job timeout (2026-10-17, CI run 39062260538) — ANSWERED 2026-10-21 (`ci/hung-test-watchdog`): a stall watchdog that thread-dumps and kills the stuck test JVM, and bounded raw JDBC.**
   Data: `DimDateContentionTest > DERIVE holds no dim_date row locks…` FAILED 3 min into the test task (`ExposedR2dbcException` ←
   `PostgresConnectionException` ← `ResponseQueueException`); the job printed nothing more until the 25-min timeout cancelled it, and a
   cancelled job uploads no `server-reports` artifact, so nothing said which thread was stuck. It passed on the re-run and 3/3 locally.
   - *Likely cause (read, not reproduced):* the test's lock holder and poller ran on plain JDBC with no `socketTimeout`, as children of a
     `coroutineScope`; when the DERIVE failed, the scope waited for every child, and a child parked in a socket read on a dead connection
     ignores cancellation. The pool was not it: R2DBC acquire has a 30 s timeout (x2), so exhaustion fails rather than hangs. The same
     unbounded raw-JDBC shape sat at 20 test call sites.
   - *Fix:* (1) `TestStallWatchdog` (`server/build.gradle.kts`): a Test-task listener that tracks each "Gradle Test Executor N"'s last
     suite/test event; one quiet for `-PtestStallMinutes` (default 5) gets `jcmd Thread.print` (bounded at 30 s) into the log and
     `build/reports/hung-tests/`, then its JVM is killed, so the task fails once the other forks finish. (2) `PostgresTestSupport.connect()`:
     connect/login timeouts 30 s, `socketTimeout` 180 s, for every test-side raw JDBC connection. (3) `DimDateContentionTest`'s holder and
     poller run in their own supervised scope with a bounded cleanup.
   - *The default:* the longest quiet gap of a full local `-Pforks=2` run is 13 s (a class's `@BeforeClass` warm-up), the slowest test 7 s;
     on CI ~30-50 s. 5 min is >6x that and 5x under the job timeout. Each executor logs its own longest gap when it finishes (`test watchdog:
     … longest quiet gap`), so the default is re-measured from CI logs, never raised on a hunch.
   - *Verified:* a throwaway `Thread.sleep(Long.MAX_VALUE)` test with `-PtestStallMinutes=1` was dumped and killed 65 s after its last
     event (the dump names `ZzThrowawayHangTest.sleeps forever` as TIMED_WAITING); the build failed at 1m19s instead of hanging.
   Also in this change: the "test fork balancing skipped" warning that every CI log printed at task-graph time (before the test classes were
   compiled, so it found no candidates — the balancing itself always worked) now fires only for a missing or unparseable weights file.
   - *Second occurrence (2026-10-22, PR #274 run 39855383144, attempt 1; passed on the re-run):* `ReportDrillAccuracyBlockedTest` got a 500
     from `blocked-time` (`ResponseQueueException: [08006] Response queue is full` ← `PostgresConnectionException`), and
     `PublicHolidayMigrationTest`'s `ALTER TABLE metrics.settings` then waited out the 180 s `socketTimeout` behind a lock in the same fork,
     most likely the failed connection's session. The watchdog and the bounded JDBC did their job (the job failed in 12 min, not at the
     timeout); the R2DBC queue overflow itself is still unexplained (BACKLOG [new]).
   - *Third occurrence (2026-10-23, master run 39878908182, a web-only merge; green on the next run) — the mechanism of the follow-on hang
     found and fixed:* `MetricsAnalyzeTest > a re-DERIVE whose post-commit ANALYZE cannot get its lock in time…` failed 1.1 s in with the same
     `ResponseQueueException` ← `PostgresConnectionException` (a SELECT of the DERIVE, on a CI runner; never reproduced locally), then the NEXT
     test on the class's shared clone (`…statement budget is cancelled…`, `MetricsAnalyzeTest.kt:239`) made no progress for 304 s and the watchdog
     killed the fork (exit 137). The dump: the test thread parked in `BlockingCoroutine.joinBlocking` (the test's own `runBlocking`), every other
     thread idle (`DefaultDispatcher-worker-1..4` parked, `reactor-tcp-epoll-1..4` in `epollWait`, no JDBC/socket read). The reading: the failed
     test's R2DBC connection was left open but unusable, so its backend sat `idle in transaction` holding the DERIVE's row locks on the shared
     clone's `metrics.*` rows (the same zombie session that held the `ALTER TABLE` of the second occurrence for 180 s); the next derive of the
     same connection blocked on them (the main DERIVE transaction has no lock timeout), `awaitWaiter` never saw an ANALYZE queue behind the
     holder and failed after its 120 s backstop, and the failed test body then waited FOREVER in `runBlocking` for its `async` child, a derive
     stuck inside Postgres that cancellation cannot reach. *Reproduced the hang half locally* (a raw JDBC session holding `UPDATE
     metrics.fact_task_delivery … WHERE connection_id = <shared clone>` open): the same in-flight test, the same dump shape, killed by the
     watchdog at 249 s. The trigger half (what overflows the driver's queue) is still the BACKLOG item.
     *Fix (test only):* `withItemStageAnalyzeHeld` now starts the re-derive in a scope of its own (a `SupervisorJob`, cancelled in the cleanup
     with a 10 s bound) instead of as a child of `runBlocking`, so a stuck derive can no longer outlive its failed test; `awaitWaiter` fails after
     15 s when a backend is blocked by a session other than the holder, and every failure lists the non-idle sessions (`pid | state | wait event |
     blockers | query`) so the zombie is named; the derive's own failure is rethrown instead of "completed without an ANALYZE"; the holder sets a
     60 s `lock_timeout` and its `held.await()` is bounded. With the same zombie the test now fails in 45 s with the session list (instead of a
     fork kill at 5 min) and the rest of the class still runs.
   - *Both halves explained (2026-10-23, `fix/r2dbc-stuck-session`), reproduced locally against r2dbc-postgresql 1.1.3 (the newest release;
     its `main` has no change to this code since):*
     **The trigger** is a check-then-act race in `ReactorNettyClient.BackendMessageSubscriber.demandMore()` (ReactorNettyClient.java:1004-1005):
     `if (!hasBufferedItems() && demand.compareAndSet(0, 256)) upstream.request(256)`. The response buffer holds 256 messages and the
     driver requests 256 at a time only when the buffer is empty and nothing is outstanding. `demandMore` also runs on the CONSUMER's thread
     (a coroutine worker requesting the next row: `onRequest` → `tryDrainLoop` → `potentiallyDemandMore`). If that thread is descheduled
     between the two checks, the event loop meanwhile delivers the outstanding rows into the buffer (the consumer, being descheduled, takes
     none) and the counter reaches 0; the thread resumes, the CAS succeeds, and 256 more rows arrive on top of a nearly full buffer —
     `offer` fails at line 881 and line 884 raises "Response queue is full". Evidence: a probe copy of the driver class (test classpath
     only, never committed) that parks the consumer thread 300 µs between the two checks — nothing else changed — overflowed 8 times in
     20 iterations of 30 × 1,000-row streamed SELECTs (a stale request saw 252 buffered rows), against 0 without the park. A 300 µs stall
     is one involuntary preemption on a CPU-starved 4-vCPU runner, which is why CI hits it and an 18-core laptop never did. Ruled out on the
     way: netty/reactor-netty over-delivering (`FluxReceive` honours demand exactly), an event-loop-side window alone (widening it 300 µs-2 ms
     gave 0 overflows), and a cancelled `firstOrNull` (the driver's `discardOnCancel` drains at unbounded demand). No upstream issue
     exists (searched "Response queue", `ResponseQueueException`, `demandMore`). **Not fixed in Flow:** the one Flow-side knob that bounds
     the buffer is a fetch size below ~250 (the driver then fetches through a portal and asks for the next chunk only after the previous
     one was drained), but it costs every statement one extra round trip (Close + Sync after CommandComplete), which WHY 13 fought to
     remove; left as a decision. The right fix is upstream (make the check-and-request atomic, or request only from the event loop).
     **The follow-on hang** is a second driver bug: after the overflow, `onError` completes the request sink, whose `doAfterTerminate`
     runs `handleClose()` and sets `isClosed`; the `close()` it calls next then returns without sending Terminate or closing the channel,
     and so does every later `Connection.close()`. Measured with the probe (`isClosed before close() = true channelOpen=true`): the socket
     stays open even after `pool.dispose()`, and the backend keeps its transaction and locks — `idle in transaction` if the rest of the
     result fit in the socket buffers (600 rows), `active` in `ClientWrite` forever otherwise (200,000 rows), where no server timeout
     applies. *Fix:* (1) `infra/db/LeakedSocketGuard.kt` disposes the channel after the driver's close when the client is closed but the
     channel is open (the pool's release validation destroys such a connection at once); (2) `idle_in_transaction_session_timeout` = 300 s
     on every pooled connection as the generic backstop; (3) `lock_timeout` = max(300 s, that idle timeout) on DERIVE's rebuild transaction, so a DERIVE behind any
     other stuck holder fails `RUN_FAILED` instead of holding a worker slot (`persistence.md` "Connection pool", `metrics.md` "Failure and
     cancellation"). Pinned by `LeakedSocketGuardTest` (the driver failure injected exactly where the overflow raises it; with the guard
     switched off the backend stays `idle in transaction` and the test fails) and `DeriveLockTimeoutTest`. What stays unknown: whether the
     overflow can also be reached without a preemption.

25. **DERIVE's reads allocate less: `custom_fields` cut in SQL, worklogs per batch, rows mapped as they stream (2026-10-22, `perf/derive-reads`) — a memory item (the app container OOM deep dive, option D), not a wall-time one.**
   Question: the DERIVE allocates ~450 MB/s and its start holds the whole connection's raw `custom_fields` twice (JSON strings, then parsed
   trees) plus every worklog row of the connection and a `ResultRow` list beside each mapped list (the deep dive's "Top consumers" 1-2),
   although `trimCustomFields` then keeps ~5 field ids of ~1.1 KB. Calibration (dev DB, one connection): 6,387 items, `custom_fields` avg
   1,097 B / p95 2,797 / max 12,722 (6.7 MiB of text), 33,693 worklogs.
   - *Done:* (a) `workItemsForDerivation` selects only the columns a `DerivationWorkItemRow` uses and, given the configured ids, keeps those
     `custom_fields` keys in SQL (`jsonb_object_agg` over `jsonb_each` filtered by key; chosen over a Kotlin trim per row because the
     discarded ~95 % is then never transferred, decoded to a `String` or parsed, and jsonb renders the kept entries exactly as the old
     whole-object read did — no key-order or number-format change); (b) `worklogsByIssue(connectionId, issueIds)` is scoped per batch of 200
     issues in `runWorklogStep` (the items to batch come from `worklogSecondsByIssue`, which has a key for every issue with a worklog row) and
     ordered by issue/worklog id; (c) the six DERIVE reads map/collect rows as they stream instead of `toList().map` / `toList().groupBy`.
   - *Expected saving (arithmetic from the calibration, not measured):* (a) the 6.7 MiB of text becomes ~0.3 MiB, and the parsed trees
     (a `JsonObject` + `LinkedHashMap` + `JsonLiteral`s per entry is typically 5-10x the text) go from ~35-70 MiB for this one connection to a
     few MiB, all of it young-gen churn that otherwise survives into the first GCs while the derive start holds it — plus the unused columns
     (`labels`, `components`, `anomalies`, …); (b) 33,693 worklog rows at ~250 B each (7 boxed fields, a String, list/map overhead) is ~8 MiB held
     for the whole run, now ~1-2k rows (~0.4 MiB) at a time; (c) each read's peak drops by up to the size of its `ResultRow` list. Together
     tens of MiB less transient/promoted heap per DERIVE on a tenant this size; the stub (1,200 items) saves 2-5 MiB. Wall time: not expected to move
     (one extra jsonb subselect per row against ~1 KB less to ship); the stub-derive budget (`stub-derive`, 1 s) is the check.
   - *Verified:* `DerivedStubFixtureTest`, `MetricsDigestTest`, `NormalizationPipelineTest` and `MetricsDerivationTest` unchanged and green (the metrics
     digest is the byte-identical proof); `NormDerivationReadsTest` pins the trimmed read against the old `filterKeys` rule on a hand-built item with
     Rank/ADF/null/array extras, and the scoped worklog read against the whole-connection one. Re-measure the effect with the deep dive's mid-pair
     `-all` histogram and young-GC count on the perf overlay before claiming the numbers above.
   - *Not done:* (d) of the deep dive (positional rows / `unnest` inserts) — a new binding path, a separate item.

26. **The app peaked at 675 MiB anon for a DERIVE pair and never gave the heap back: how little can it need? — ANSWERED 2026-10-22 (`perf/jvm-memory`): fix the young generation, let the old one shrink, size the thread pools for 2 cores, and collect once when the worker goes idle. Peak -30 %, the DERIVE time unchanged.**
   WHY 17 found a fixed budget (heap at its cap + ~150 MiB of non-heap); the memory deep dive (2026-10-22, scale 7, 8.4k issues per connection) found the heap cap was
   reached by *promotion*, not by live data: SerialGC sizes the young generation as a third of the COMMITTED heap, so at a 115 MB heap the survivors were 4 MB, every
   DERIVE batch (~450 MB/s of garbage) overflowed them into the old generation, and the old generation shrinks only at a Full GC that leaves 70 % free. Fix, in
   `applicationDefaultJvmArgs` (every role and `:server:run`; the Dockerfile adds `MALLOC_ARENA_MAX=2`): `-Xmn64m -XX:SurvivorRatio=2 -XX:MinHeapFreeRatio=10
   -XX:MaxHeapFreeRatio=30 -XX:-ShrinkHeapInSteps -XX:ActiveProcessorCount=2` (the default 1 MiB stack stays, see the `-Xss` bullet); and `ingest/IdleHeapGiveBack.kt`: when the worker's last running job ends
   (`BusyTracker`, once per idle transition) it runs one `System.gc()` (detekt `ExplicitGarbageCollectionCall` excluded for that file; `-XX:+DisableExplicitGC` must
   never be set). `-Xmx256m` (web) and `JAVA_OPTS=-Xmx512m` (worker/all) are unchanged; **no container limit was lowered** (headroom first). `JvmMemoryFlagsTest` (checkup 3, A10) pins the sizing: the last `-Xmx` + 256 MiB (the ~150 MiB non-heap of WHY 17 + <= ~30 MiB malloc + slack) fits every limit, `ExitOnOutOfMemoryError` on, `DisableExplicitGC` nowhere, `MALLOC_ARENA_MAX=2`, `SERVER_OPTS` only in the Dockerfile.
   - **Method.** The `docker-compose.perf.yaml` shape under its own project (`flow-memperf`, ports 8484/5835/8428/8494), **scale 20** (24,001 issues, the
     generator's `--scale 20`), two Jira connections (the stub ignores the project filter, so each holds all 24k) with their own teams, the FLO board mapped, 30 seeded
     memberships, `-Xmx512m` in **768m** (production sizing for worker/all), NMT on, a GC log. The same database for every row; a fresh JVM per variant: 30 s idle, 2 single
     DERIVEs, 3 pairs (both connections enqueued together), 100 s of idle. Sampled every 3 s (cgroup `anon`, `VmRSS`, `jcmd` NMT/`GC.heap_info`). Two runs per variant. **A** = master's
     flags (the same image with the launcher line and `MALLOC_ARENA_MAX` put back, `-XX:+DisableExplicitGC` so the new hook is inert); **B** = the new flags with
     the hook disabled (`-XX:+DisableExplicitGC`); **C** = the shipped build. Host: Apple M5 Max, 18 cores visible to the container.

     | Metric (run 1 / run 2) | A baseline | B flags only | C flags + give-back |
     |---|---|---|---|
     | Idle after boot: anon / RSS MiB | 165 / 185 · 163 / 183 | 182 / 202 · 183 / 202 | 184 / 204 · 183 / 202 |
     | **DERIVE pair peak anon** MiB | **675 · 676** | **452 · 460** | **469 · 480** |
     | DERIVE pair peak RSS MiB | 688 · 691 | 472 · 479 | 486 · 499 |
     | Heap committed at the pair peak (NMT) MB | 511 · 512 (the cap) | 335 · 322 | 358 · 344 |
     | Single DERIVE peak anon MiB | 433 · 345 | 305 · 345 | 317 · 312 |
     | cgroup `memory.peak` (incl. page cache) of 768 MiB | 744 · 720 | 528 · 517 | 551 · 541 |
     | **Anon 100 s after the last job** MiB | **697 · 676** | 410 · 395 | **315 · 337** |
     | Heap committed after the burst MB | 506 · 512 | 270 · 256 | 174 · 197 |
     | Threads, idle / peak | 67-71 / 95-98 | 33-34 / 37-39 | 34-35 / 37-39 |
     | NMT after the burst: heap / thread stacks / GC structures MB | 506 / 11 / 1.7 | 270 / 3.6 / 1.0 | 174 / 3.6 / 0.6 |
     | Single DERIVE, s (runs 1 / 2) | 21.2, 19.4 · 16.9, 16.8 | 17.6, 17.6 · 17.3, 17.2 | 18.1, 18.1 · 17.8, 17.8 |
     | Pair DERIVE, s (mean of 6) | 23.3 · 19.5 | 21.7 · 20.1 | 20.5 · 21.3 |
     | Full GCs (allocation failure) / young GCs per session | 14-16 / 473-513 | 41-44 / 1,341 | 44-46 / 1,340 + 5 explicit |
     | GC pause time per session (full + young) | 3.6 s · 3.2 s | 7.7 s · 7.4 s | 8.1 s · 7.8 s |

     (A's single-DERIVE peaks differ between runs because the heap commits in steps; compare pairs, where it reaches the cap in both.) No OOM kill, no restart, no failed
     job in any of the six runs; 48 DERIVEs all SUCCEEDED. The pair peak anon fell **-30 %** (675 -> ~470), the cgroup peak ~730 -> ~545 of the 768 MiB limit (headroom
     ~30 -> ~220 MiB), and the DERIVE time is inside the run-to-run noise (the two A runs differ by 4 s; the flags are not slower).
   - **Where the memory went.** NMT after the burst: heap 506 -> 174 MB, thread stacks 11 -> 3.6 MB (threads 98 -> 38; the runs used `-Xss512k`, which was then dropped: with the default 1 MiB stack that is ~7 MB, the saving is the thread count), malloc/GC structures -1 MB; metaspace (52 MB), symbols
     (24), code (16), CDS (12) are unchanged, so the non-heap floor stays ~150 MB. The idle footprint is **+18 MiB** (a 64 MB young generation is touched and stays resident).
   - **Give-back evidence (C).** One `Pause Full (System.gc())` per idle transition and nothing else: five in a session (2 singles + 3 pairs), 33-62 ms each, e.g. after
     the third pair `244M->93M(181M)` (run 2) and `222M->77M(158M)` (run 1); the sampler shows anon 398 -> 318 MiB within one 3 s sample of the last job ending and flat at 315 afterwards,
     the old generation 197 -> 113 MB committed. Without it (B) the same heap stays at 270 MB / 410 MiB for good; `jcmd GC.run` on a heap that still has `ShrinkHeapInSteps` shrank nothing (deep dive).
   - **The cost.** More, shorter collections: young GCs x2.7 (pauses 1-2 ms), and **3x as many Full GCs** (`Allocation Failure`, 60-150 ms: the tight heap meets its edge more often before it
     grows), about +4 s of GC pause over a ~8-minute session of 8 derives (~0.5 s per derive, a few percent of the 18-24 s, inside the noise). The Full GC's live-set readings reach 282 MB (A: 204-255 MB): the old
     generation's ceiling is now `-Xmx512m` - 64 MB = 448 MB, so **the figure to watch is still the live set after a Full GC against that ceiling** (WHY 17); scale 40 still needs more heap.
   - **The web role** (`FLOW_ROLE=web`, baked `-Xmx256m`, 512m, same database, 130 report requests: 26 endpoints x 5): idle anon 157 -> 181 MiB, after the sweep 240 -> 233 MiB (RSS 256 -> 249),
     threads 92 -> 36, request latency identical (median 42 vs 40 ms, p95 690 vs 696 ms, the same five 400s). No RSS gain, and none was promised: the saving is the thread/arena trim and a heap that cannot ratchet. The web role has no worker, so no give-back.
   - **`-Xss512k` was tried and DROPPED (decision 2026-10-22): the default 1 MiB thread stack stays.** It saved only ~4 MB of committed stacks at ~38 threads but halved the nesting depth the code can store: parsing,
     `canonicalize` and `JsonElement.toString()` all recurse per nesting level (the parser is NOT stack-free). On a cold, interpreted (worst-case) thread, 512 KiB parsed 439 object+array pairs and canonicalised/rendered 222;
     1 MiB parses 1,015 and canonicalises/renders 515 (a warm JIT'd thread about doubles that). The deepest real Jira ADF document is a few dozen levels, so nothing real broke, but a deeper payload would fail its job on every retry.
     `CanonicalJsonTest` pins 250 pairs (500 nested structures, about half the 1 MiB limit) on a 1 MiB thread. The runs above (boot, both 24k-issue SYNCs, 48 DERIVEs, the report sweep) used 512 KiB and logged 0 `StackOverflowError`; the
     Gradle `test` task never gets the launcher flags.
   - **API concurrency (does `ActiveProcessorCount=2` cap the route handlers? - no; the young generation costs a little under load).** Ktor sizes its Netty groups from `availableProcessors`, so the call group fell from ~18 to 2
     threads (`eventLoopGroupProxy` 29 -> 5-7). Measured on the scale-7 stub (8.4k issues x 2 connections), `all` role, `-Xmx512m` in 768m, a node client sending waves of 8 concurrent report requests (160 requests per row), variants
     recreated on the same database; **setting `ktor.deployment.callGroupSize` (2 / 4 / 8) changes nothing**, so it is NOT set (an env-overridable `callGroupSize: 8` was tried and dropped):

     | Variant (8-wide waves) | mixed 14 reports p50 / p95 ms, req/s | `home` x8 p50 / p95 ms, req/s | `aging-wip` x8 p50 / p95 ms, req/s | `wip` x8 req/s |
     |---|---|---|---|---|
     | A: master flags, call group 18 | 51 / 306-329, 34-36 | 435-446 / 462-489, 18.0 | 380-386 / 430-435, 20.4 | 69 |
     | New flags, call group 2 (what the JVM derives) | 50 / 330-336, 34 | 528-541 / 560-583, 14.6 | 435 / 471, 17.9 | 66-70 |
     | New flags, call group 4 | n/a (that run did not start) | 549-564 / 584-599, 14.0 | 438-443 / 464-479, 17.8 | 70 |
     | New flags, call group 8 | 51-53 / 336-344, 33 | 535-546 / 567-569, 14.5 | 437-439 / 466-473, 17.9 | 66-69 |
     | New flags, call group 8 + `-Dreactor.netty.ioWorkerCount=8` | | 533-537 / 558-565, 14.6 | 431 / 463, 18.2 | |
     | ... + `-Dkotlinx.coroutines.scheduler.core.pool.size=8` | | 544-552 / 571-586, 14.2 | 440-443 / 469-477, 17.8 | |
     | ... + `-XX:ActiveProcessorCount=18` (threads back to 95) | | 541-554 / 568-590, 14.3 | 431-440 / 456-475, 18.0 | |
     | ... + `-Xmn128m` (call group 8) | | 476-485 / 502-518, 16.4 | 404-418 / 445, 19.3 | |

     The 20 % and 12 % drop of the allocation-heavy reports (`home` ~140 MB garbage per request) is the 32 MB eden: 8 concurrent requests trigger a young collection every ~0.2 s, and requests in flight get promoted. Thread counts do not move it
     (re-raising the processor count, the reactor loops or `Dispatchers.Default` all give the same 14.3-14.6 req/s); doubling the young generation recovers about half of it. Light reports (`wip`) are unaffected. The
     trade is the same one as the memory saving: `-Xmn` is the knob (64 MiB shipped; 128 MiB costs ~+55 MiB idle per the deep dive). **Decision (2026-10-22): `-Xmn64m` is kept** - memory is the goal; the slowdown is attacked at its source, the reports' allocation (the backlog's set-based Postgres pushdowns for `home`/WIP), not with a bigger eden.
   - **Reports during a DERIVE pair, `all` role** (same waves of 8, mixed reports, three pairs per variant; n = 1,056-1,080 requests): p50 / p95 / max ms during the pairs, with the idle reference in brackets: A baseline 52 / 332 / 402
     (313); new flags 57 / 420-422 / 470-521 (334-339), i.e. **p95 +27 %** while a pair runs and the worst request +70-120 ms (one Full GC, 90-150 ms, now lands inside a request; A has 14-16 Full GCs per session, the new flags 41-46),
     throughput 32-33 -> 27-28 req/s. **`all`-role caveat:** the stop-the-world Full GCs of the tighter heap and the give-back `System.gc()` (33-62 ms) pause the API in compose/dev, where one JVM serves both; k8s splits the roles (the web JVM
     has no worker and no give-back).
   - **Not done.** Lowering any container limit (a follow-up once a real-data run confirms the headroom), `custom_fields`/worklog reads in DERIVE (the deep dive's D(a)/(b) - the real-data lever), G1. (AppCDS, the deep dive's option E: done in WHY 27.)

27. **AppCDS: the class metadata out of anon memory — ANSWERED 2026-10-22 (`perf/appcds`): a build-time archive dumped from a committed class list; boot 0.86 -> 0.42 s, a DERIVE pair's peak anon -27..-30 MiB, +107 MB on disk.**
   WHY 26 left ~95 MB (NMT committed) per JVM that no heap flag touches: metaspace 52-58 MB + class space 10 + symbols 24-26 (15k classes at ~3.7 KB each, Kotlin lambdas and suspend state machines, all re-parsed from the jars at every boot). Class-data sharing maps
   those structures from a file instead: read-only, file-backed, shared by every JVM that maps the same file (web + worker on a node share the page cache), and the classes are already verified and linked at boot.
   - **Mechanism and why (the candidates were measured, `flow-cds` project, same database, scale 7, `all` role, `-Xmx512m` in 768m).** The runtime image is a JRE, which has everything `-Xshare:dump` and `-XX:ArchiveClassesAtExit` need
     (`java` + the default `classes.jsa`); no JDK is needed in the final stage. (b) `-XX:+AutoCreateSharedArchive` was rejected without a run: it dumps at the first exit, so the first boot of every container and of every rescheduled pod (an emptyDir
     dies with the pod) gains nothing, and it needs a writable path on a root filesystem that the pods keep read-only. (a) build-time, three ways:

     | Archive (all dumped from the base image, then COPYed in) | file | classes served from the archive at idle / after sweep + DERIVE | boot ("Application started in") | idle anon | DERIVE-pair peak anon / RSS |
     |---|---|---|---|---|---|
     | none (master) | - | 1,289 / 1,292 of 12.0k / 14.6k (the JDK's default archive only) | 0.84-0.88 s | 184 MiB | 287-291 / 307-310 MiB |
     | dynamic, training run WITHOUT a database (boots to the Flyway failure) | 34.5 MB | 6,260 of 12.0k (52 %) / 6,279 of 14.6k (43 %) | 0.69-0.72 s | 179 | 273-276 / 299-302 |
     | dynamic, training run with the full workload (the deep dive's V6b) | 82 MB | 11,593 of 11.9k (97 %) / 13,889 of 14.3k (97 %) | 0.43 s | 170 | 257-259 / 290-292 |
     | static, from the class list, dumped under SerialGC (no archived heap objects) | 100.7 MB | 11,600 of 11.9k / 13,896 of 14.4k | 0.42-0.44 s | 176-177 | 260-265 / 294-299 |
     | **static, from the class list, dumped under G1 (shipped)** | 107 MB | 11,593 of 11.9k / 13,889 of 14.3k | 0.39-0.42 s | 172 | 262-263 / 297 |

     A no-database training run is the only one that needs nothing committed, but the app fails at Flyway before the routes, services, reports, Exposed and R2DBC classes load, so it archives half the classes and earns half the saving. A
     dynamic archive from a real run needs a running stack inside `docker build`. **The shipped answer is a static archive dumped from a committed class list**: `java -Xshare:dump -XX:SharedClassListFile=…` needs no database and no
     application start (4-5 s), and the list is what a REAL run loads, so coverage matches the full-workload archive. `-XX:+UseG1GC -XX:-UseSerialGC` applies to the dump only (the JVM writes archived heap objects - strings, class mirrors, the module
     graph - only under G1; the runtime's SerialGC loads them fine): boot 0.42-0.44 -> 0.39-0.42 s and ~5 MiB less anon than the same list dumped under Serial.
   - **Wiring.** `Dockerfile` runtime stage: after the `COPY`s and the `chown` (the archive is validated against the exact jar paths, sizes and mtimes, and a later `chown` would copy it into a second layer) one `RUN` dumps `/app/cds/flow.jsa` from
     `server/cds/classlist.txt` (a bind mount: the list itself is not in the image) by running the launcher itself with `JAVA_OPTS=…-Xshare:dump…`, so the classpath is the launcher's; the build fails when no archive comes out and prints the class
     counts (15,604 classes: 3,001 boot, 12,335 app, 61 platform). `ENV SERVER_OPTS="-XX:SharedArchiveFile=/app/cds/flow.jsa"` switches it on for every run of `bin/server` (the Gradle launcher appends `$SERVER_OPTS` last; nothing else in the repo
     sets it, `JAVA_OPTS` stays the operators' knob; `SERVER_OPTS=` turns it off). Not in `applicationDefaultJvmArgs`: with a missing file the JVM switches CDS off altogether (even the JDK's own archive), which would slow every `:server:run`, which has
     no archive; and not `JDK_JAVA_OPTIONS`, which prints a NOTE on every `java` start. Every role gets it (web, worker, all); compose and k8s need no change: the archive is only read, so the pods' `readOnlyRootFilesystem` stays and no volume is added.
   - **The class list** (`server/cds/classlist.txt`, 13,086 classes + 2,077 lambda proxies + 477 form invokers, 1.4 MB, canonical: sorted, deduplicated, no ids) is regenerated by `node scripts/cds-classlist.mjs` (~1.5 min plus the image build): own compose project
     `flow-cds-train` (ports 8588/5939/8532/8598; it builds its own image from the tree - `--image <tag>` instead in a git worktree, whose `.git` file breaks `COPY .git` - and removes it with the project), the committed scale-1 WireMock stub, the image started with `-XX:DumpLoadedClassList` and its own archive switched off, then login (and a failed one), a password reset mail, team/user/data-source CRUD,
     SYNC, the metrics-config PUT, two DERIVEs, 144 report reads and a refresh/logout, a graceful stop, and the sorted list. It writes the file (atomically) only after checking the run was healthy: at least 85 % of the 144 reads answer 2xx (132 do; the 12 400s are reports that need parameters), the last SYNC and DERIVE SUCCEEDED, and the list has at least 12,000 classes and 1,000 application classes (13,086 / 2,389 now); otherwise it throws and leaves the committed list alone. Two runs give byte-identical lists. A class missing from the list is simply loaded from the jar, so a stale list costs startup and metaspace, never correctness;
     `CdsClassListTest` is the tripwire (canonical shape; at least 85 % of the application classes in it still exist) and names the script. Regenerate on a refactor wave, a big dependency move or before a release. Trained at scale 1,
     the list covers the scale-7 run just as well (95.8 % of the classes loaded after the sweep and two pairs come from the archive; the rest are lambda forms and hidden classes the dump cannot store).
   - **Measured (final image `flow-cds:after`, two runs each; `all` role, 768m, a sweep + 2 DERIVE pairs per run, NMT on):**

     | | master | AppCDS (2 runs) | |
     |---|---|---|---|
     | "Application started in" (3 boots each) | 0.84-0.88 s | 0.42-0.44 s | -50 % |
     | healthy after `compose up` (poll) | 2.0-2.1 s | 1.4-1.5 s | |
     | idle anon / RSS, 30 s after boot | 184 / 204 MiB | 177-178 / 211-213 | anon -6, RSS +7..+9 |
     | DERIVE pair peak anon / RSS | 287-291 / 307-310 | 258-263 / 296-298 | anon -27..-30, RSS -10..-12 |
     | anon / RSS settled after the pairs | 261-263 / 281-283 | 236-239 / 271-272 | anon -24..-27 |
     | NMT Metadata + class space | 57.9 + 10.3 MB | 3.9 + 2.0 MB | |
     | NMT Symbol | 26.4 MB | 2.0 MB | |
     | NMT Shared class space (file-backed, only touched pages are resident) | 12.0 MB | 98.4 MB | |
     | classes served from the archive, idle / after the pairs | 1,289 of 12,033 / 1,292 of 14,622 (11 % / 9 %) | 11,559 of 11,865 (97.4 %) / 13,754 of 14,355 (95.8 %) | |
     | web role (`FLOW_ROLE=web`, `-Xmx256m`, 512m) boot | 0.86-0.91 s | 0.42 s | |
     | web role idle anon / RSS; after 60 report requests | 180 / 200; 207 / 227 MiB | 175 / 209; 191 / 226 | anon -5; -16 |

     NMT's committed figures fall by ~87 MB, the resident anon by less (committed is not resident). The saving is **anon** (what the OOM killer and `container_memory_rss` see); the mapped archive pages are clean file cache, so total RSS and the page-cache part of `memory.current` do not fall (idle RSS is a little HIGHER: the touched pages of the
     archive count as RSS). They are reclaimable, shared between the web and worker pods of one node, and read back from the image layer when evicted. The DERIVE time is unchanged (pairs 8 s in every row; the JIT is C1-only either way).
   - **Mismatch behaviour (checked on the image, then through compose with a real database):** an archive that does not fit never fails a start. A changed jar (touched mtime, grown size) prints
     `[warning][cds] … timestamp/size has changed` / `shared class paths mismatch` on stdout and the app runs with 0 classes shared, booting in 0.91-1.0 s (master 0.84-0.89: the JDK's own archive is dropped too, so a mismatched start is up to ~0.1 s
     slower than master, never broken); `-XX:-UseCompressedOops`, `-XX:ObjectAlignmentInBytes=16` and `-Xshare:off` run silently without it; a different GC (`-XX:+UseG1GC`) still uses it. Run read-only as the pod does (`--read-only --tmpfs /tmp --user 10001 --cap-drop ALL`, `FLOW_ROLE=worker`): healthy in
     1 s, 10,842 of 11,143 classes from the archive, no warnings. Never run production with `-Xshare:on` (that one does fail on a mismatch).
   - **Tripwire (CI).** A mismatching archive is silent at run time (above), so the `images` job runs `scripts/check-cds-archive.sh flow-app` after the build: the image's own launcher (the runtime's classpath and baked flags) with
     `-XX:+PrintSharedArchiveAndExit`, which validates the archive and exits non-zero on a mismatch without starting the app (no database, well under a second), and the script also requires the application jars in its output, so an image
     with `SERVER_OPTS` cleared (the JDK's own archive would pass) fails too. Proven both ways locally: the good image exits 0 (168 classpath entries checked); `CDS_CHECK_EXTRA_OPTS=-XX:-UseCompressedOops`, `-XX:ObjectAlignmentInBytes=16` and a jar with
     a touched mtime each exit 1 with the JVM's message. (`-Xshare:on -version` does NOT work: the launcher stops reading options at `-version`, and `--dry-run` skips the classpath check, so a touched jar passes it.) Adding
     `-XX:-UseCompressedOops`/`ObjectAlignmentInBytes` to `applicationDefaultJvmArgs` would disable the archive; this check catches it.
   - **Cost.** Image +107 MB uncompressed (one new layer; ~27 MB gzipped on the wire) on 674 MB; the image build gains the ~5 s dump (plus the 1.4 MB list in the context); Trivy (`0.74.0`, HIGH/CRITICAL fixable) still reports nothing - the archive is an opaque blob to it
     and holds only classes of the jars it already scans. The dump is deterministic for a given list, jars and JDK, so a Dependabot bump re-dumps by itself.
   - **Not done.** Dynamic layering on top of the JDK archive (smaller, ~80 MB, but needs a training run in the build); `-XX:+AutoCreateSharedArchive`; a CI job that regenerates the list; `COPY --chown` in the Dockerfile (the existing `chown -R` copies the 66 MB of
     jars into a second layer; it is not part of this change, and the archive is deliberately dumped after it so it is not copied too).

28. **Compare people's WIP read: counted in Postgres, not as one object per task-day (2026-10-23, `perf/wip-user-set-based`) - ANSWERED: allocation -58 % on the report and -83 % on its WIP drill, p50 -55 % / -51 % at c=1 and -74 % / -70 % at c=4.**
   The memory deep dive's heap histogram showed `WipTaskDay` (37.6k instances) in a heavy request. The BACKLOG item named it "the WIP report's USER level", but that level (`readWipUserCells`, `wipUserOpenBandSql`) was ALREADY one grouped statement; the
   consumer of `WipTaskDay` is **Report 18, Compare people** (`GET /reports/compare-people` and its `WIP_TASK_DAYS` / `WIP_AT_END` drills): `readWipUserTaskDays` shipped every (day, task, person) row to the JVM, which then did `groupingBy(day).eachCount()`
   (the `avgWip` per-day counts) and `groupBy(task)` (the drill's per-task day counts). Now: `wipUserDayCountsSql` (`GROUP BY day, account_id` over the same `held_days` CTE: one row per day and person) feeds `CompareWip.dayCounts`;
   `wipUserTaskCountsSql` (`GROUP BY connection_id, issue_id` with the working days as `IN` literals) feeds the drill; only the tasks held on the LAST listed day (what the report lists and ages) still come as rows, via the old statement over a one-day range.
   No `generate_series`: the `held_days` CTE already joins the picks' intervals to `metrics.dim_date`'s `day_end_ms`, which is the `coveringAt` instant, so the day list stays the one the DONE band and the WIP step use. Plan shape pinned in `WipPlanShapeTest` (aggregate node, no team join, no `SubPlan`).
   - **Correctness.** (1) The JSON is byte-identical: 75 responses (the report + both WIP drills for both people: 3 periods over the derived stub fixture, 6 periods x 2 pairs over a hand-built world with a reassignment exactly at a day end and mid-day, intervals crossing both period edges, a null-assignee
     gap, sub-task/UNMAPPED/DONE exclusions, holidays, a period ending at the derive cut-off, an empty period) were recorded from the unchanged code (a `git archive` of the base commit) and re-compared after the change; and on the scale-7 compose stack
     three reports and their 12 drills (417-day periods, `avgWip` up to 51 over up to 291 working days) diffed byte for byte between the `base` and `new` images. (2) `CompareWipPushdownTest` keeps the old expansion as the oracle (every task-day row counted in Kotlin) against
     the set-based reads over the hand-built world (7 periods x 4 pick sets) and the derived fixture, plus four hand-computed answers; `ReportComparePeople*`, `ReportWip*`, `WipPlanShapeTest` pass unchanged.
   - **Measured** (own compose project `flow-pdu`, scale-7 stub = 8.4k issues, `all` role, `-Xmx512m -Xmn64m` (eden 32 MiB) in 768m, two busiest people, 2025-09-01..2026-10-23 = 417 days; fresh JVM per run, 60 warm-up requests, then 400 requests; base/new/base/new,
     allocation = `Pause Young` count x eden / 400; the two rounds agreed within 1 %; latency from the second round):

     | request | before p50 / p95 (c=1) | after | before p50 / p95 (c=4) | after | allocation per request before | after |
     |---|---|---|---|---|---|---|
     | `compare-people` (10 KB) | 115 / 134 ms | 52 / 60 ms | 245 / 296 ms | 64 / 75 ms | **125 MiB** | **52 MiB** (-58 %) |
     | `compare-people/items` `WIP_TASK_DAYS` (54 KB) | 71 / 85 ms | 35 / 39 ms | 137 / 167 ms | 41 / 48 ms | **58 MiB** | **9.8 MiB** (-83 %) |
     | `wip` USER level (control, untouched) | 67 / 72 ms | 69 / 79 ms | 83 / 94 ms | 91 / 102 ms | 17.3 MiB | 17.3 MiB |

     At c=4 the base image also ran 200-256 Full GCs per 400 requests (4 x 125 MiB in flight against a 512 MiB heap with a 64 MiB young generation); the new one ran none - the headline is the headroom, not the p50. The control (`wip` USER) did not move, so the run-to-run noise is the
     ~10 % seen on its p50. What is left in `compare-people`'s 52 MiB is its other fetches (worklogs, done tasks, histograms), not WIP.
   - **Not done.** The `WIP_AT_END` rows still travel per task (a few per person); `readWipUserTaskDays` stays for them. `WipTaskDay` is no longer built per task-day anywhere.

29. **Aging WIP caps at 500 in the query, not after loading every open item — ANSWERED 2026-10-23 (`perf/aging-wip-cap-in-query`; number provisional, renumber against the parallel pushdown PRs): p50 120 -> 63 ms and 215 -> 101 MiB allocated per request on 1,795 open tasks.**
   Question (BACKLOG "Move whole-connection loads and report expansions into Postgres"): `GET /reports/aging-wip` fetched EVERY open task and epic,
   computed the working-day age of each in the JVM (`WorkingCalendar.workingDaysBetween`, one `LocalDateTime` + boxed `Integer` per calendar day
   per item) and sorted and cut to 500 afterwards, so the request cost grew with the open WIP and not with the 500 listed.
   - *Done:* `reports/AgingOpenItems.kt`. Age never grows with `started_at`, so each kind (tasks, epics-joined-to-`dim_epic`) is read with
     `ORDER BY started_at, connection_id, issue_id LIMIT 500` (the `idx_metrics_fact_task_delivery_wip` partial index serves the filter) plus a
     `COUNT(*)` only when that page is full (the `itemsTruncated` flag needs the total). Two items with different `started_at` can tie on age
     (both inside a weekend or a holiday, or both after the clock), and the JVM tie-break is (connection, issue id), not `started_at`, so the
     rows past the 500th are read in keyset pages of 100 (`(started_at, connection_id, issue_id) > last row`, so a deep tie block costs the same per page) while their age still equals the 500th's (each age computed once, never past the first
     difference) and the real order is then applied to that small set. The thresholds are untouched: `fetchDoneCycles` already reads only the
     last N DONE items per kind and never saw the open set. `fetchOpenTasks`/`fetchOpenEpics` stayed for the Home overview (same predicates, now shared) until WHY 34 removed Home's use of them (they live on as the tests' `legacyOpenTasks`/`legacyOpenEpics`).
   - *Measured* (compose project `flow-pda`, scale-7 stub: 8,302 tasks, 1,795 open in an aging stage + 22 epics; web-role containers of
     `master` 14ad809 and of this tree on ONE database, `-Xmx256m -Xmn64m` SerialGC as shipped, 512m limit; alternating before/after rounds of
     5 sequential requests after 25 warm-up each; allocation = young GCs x eden + eden growth from `jstat -gc`, per request; 50-60 requests per cell;
     other pushdown branches were building on the same machine, so read the ratios, not the absolute ms):

     | read | open items matched | p50 before -> after | p95 before -> after | allocated per request before -> after | young GCs per 60 requests |
     |---|---|---|---|---|---|
     | unit (no filter) | 1,817 | 120 -> 63 ms | 131 -> 66 ms | 215 -> 101 MiB | 403 -> 189 |
     | `teamId=1` | 1,588 tasks + epics | 110 -> 64 ms | 127 -> 76 ms | 188 -> 96 MiB | 352 -> 179 |
     | `teamId=0` (223 items, under the cap) | 223 | 24.3 -> 24.9 ms | 27 -> 27 ms | 31.0 -> 30.9 MiB | 58 -> 58 |
     | `teamId=1` + one `accountId` (57 items) | 57 | 12.2 -> 12.4 ms | 14.3 -> 14.3 ms | 9.5 -> 9.5 MiB | 18 -> 18 |

     A read that already fits the cap is unchanged (two queries instead of three, the same ages). The remaining ~100 MiB of a capped read is the 500
     listed items' own age (`workingDaysBetween` walks every calendar day since `started_at`: ~150 days x 500 items) - the next lever, outside this item (a per-day prefix sum was suggested;
     WHY 32 took whole-week arithmetic instead). A first version that read the tie page eagerly (500 more rows, all aged) measured 165 MiB: the
     tie check must stop at the first row that differs.
   - *Verified byte-identical:* `ReportAgingWipCapTest` rebuilds the old read from `fetchOpenTasks` + `fetchOpenEpics` (sort in the JVM, take 500) and
     compares the listed items, their order, their ages and `itemsTruncated` with the new report at a pinned clock, over hand-built worlds of OLD (distinct
     ages), WEEKEND (one age for the block, issue ids falling as the start rises, so the naive "first 500 by `started_at`" fails) and FUTURE (age 0) items:
     (1) 648 open tasks and 538 open epics on one connection (both pages fill), the cut inside a weekend tie block, a second connection, team / unassigned /
     user / unit reads; (2) the cut inside an EPIC tie block (576+ open epics, the epic extension pages twice, tasks tying with it at the merge); (3) a kind
     with exactly 500 open (not truncated), one with 501 (truncated - only the COUNT sees it), and 300 tasks + 300 epics (each under the cap, together over it);
     (4) 1,100 age-0 ties past the 500th (eleven keyset pages). Dropping the epic extension, the paging loop or the COUNT each fails one of them. Over HTTP
     on the stub, `master` and this tree answer the same JSON for eight filter variants once `ageWorkingDays` (which moves with the wall clock between the two
     requests, < 4e-6) is set aside.

30. **The drills read every worklog of their bar to list 500 items - ANSWERED 2026-10-23 (`perf/drill-worklog-limits`): the cost drill and compare-people's worklog drills now form, order and cut the items in Postgres. Allocation per request -4x..-8x on a big bar, latency -2x..-3x; small bars unchanged.**
   The costs drill (`costs/items`) fetched the bar's worklogs grouped per (issue, author) and rolled them up in Kotlin, the sprint-bar shape fetched the whole chart envelope, and compare-people's `LOGGED`/`FOREIGN`/`LATE` read every worklog of both people; all three then sorted and kept 500
   (`DRILL_MAX_ITEMS`). Now ONE statement per drill (`CostsDrillPage.kt` `fetchCostDrillPage`, `ComparePeopleReads.kt` `fetchCompareWorklogPage`) groups to the item (a sub-task to its parent, `Case`/`Coalesce`; no bind parameter inside the grouped expression, or Postgres
   cannot match SELECT, GROUP BY and ORDER BY), orders by the drill order (`ROUND(SUM(md), 2)` desc, `MAX(started_at)` desc, connection, issue id) and `LIMIT 500`s, with the whole set's item count and exact MD as `COUNT(1) OVER ()` / `SUM(SUM(md)) OVER ()` in the same statement
   (so `bar`/`total`/`truncated` are exact and cannot be split from the page by a concurrent DERIVE). Each costs item carries its (author, MD) pairs as one `jsonb_agg` (the authors line); the sprint bars list the chart's rule as a predicate (`sprintBarSlice`, `EpicSegmentSelector.costPredicate()`).
   - **Measured** (`flow-pdd` project, `all` role, `-Xmx512m` plus the baked Serial `-Xmn64m`; the scale-7 stub on TWO data sources = 2 x 3,335 items / 8,356 worklogs each, 30 authors; image built from master 14ad809 vs this branch, the same database; three fresh containers per variant,
     10 warm-up + 60 sequential requests each; median of the three runs; allocation = eden accounting from the JVM's perf counters (young GCs x 32 MiB eden + the eden delta) minus the idle worker's 20 s background rate):

     | call (`items` listed <= 500) | items behind the bar | old p50 / p95 ms | new p50 / p95 ms | old alloc MiB/req | new alloc MiB/req |
     |---|---|---|---|---|---|
     | costs slice, unit, one key | 2,654 | 73 / 80 | 36 / 42 | 29.8 | 7.7 |
     | costs slice, unit, all keys (the folded "Other") | 6,274 | 141 / 162 | 49 / 56 | 67.8 | 8.1 |
     | costs slice, team, all keys | 6,256 | 137 / 156 | 50 / 59 | 66.8 | 7.9 |
     | costs slice, work category UNCATEGORIZED | 6,274 | 135 / 174 | 48 / 54 | 67.8 | 7.7 |
     | costs sprint bar, whole | 192 | 14 / 16 | 17 / 21 | 5.8 | 3.8 |
     | costs sprint bar, one epic segment / OTHER_EPICS / NO_EPIC | 1 / 40 / 147 | 12 / 14, 12 / 13, 13 / 15 | 8 / 12, 10 / 17, 14 / 19 | 4.4, 4.7, 5.5 | 1.7, 2.2, 3.3 |
     | compare-people LOGGED / FOREIGN / LATE | 638 / 94 / 94 | 20 / 25, 16 / 19, 16 / 20 | 20 / 26, 16 / 20, 15 / 18 | 8.0, 6.5, 6.4 | 5.1, 2.7, 2.6 |

     The win is where the bar is much bigger than the page: the slice drills no longer scale with the worklogs behind them (8 MiB per request whatever the bar). A request has a ~1.7-4.4 MiB floor (auth, scope, labels), so a bar already under 500 items gains only the
     allocation, not the latency (run-to-run noise on the shared laptop is +-3 ms; the sprint whole bar reads +2 ms in the medians and is level in an interleaved 300-request A/B). The statement itself costs 1.7 ms against 0.85 ms for the old row fetch on a 411-worklog bar (`EXPLAIN ANALYZE`: one index scan, a hash join to `dim_task`).
   - **Byte-identical answers.** A sweep of 1,025 answers (every activity-type and work-category key of nine scopes, every listed sprint of team 1 with its whole bar and every epic segment, 30 neighbour pairs x three compare views x both people; 771 non-empty, 71 truncated) was recorded from both images over the same
     database: `diff -r` empty. In the suite, `DrillPushdownOracle.kt` keeps the pre-pushdown JVM implementation as the reference: `ReportDrillPushdownGoldenTest` (550 items with figure/anchor ties across the 500th place, sub-tasks (also one with no parent and one whose parent has no row), epics logged on, above-epic issues, null authors, items and bars summing to exactly x.xx5 beside x.xx49 (the `ROUND` vs `HALF_UP` boundary), a closed, an open and an unstarted sprint with instants
     on the window edges, > 500 worklogs for one person) and the fixture tests compare the whole `ReportDrillItems` and its JSON. Mutation-checked: dropping the connection/issue tie-break from the `ORDER BY` fails it.
   - **Not done.** The Costs chart still splits its envelope fetch in the JVM (the chart needs per-sprint per-epic sums; a follow-up); `ThroughputActualCostDrill` (its own, smaller read); compare-people's report cells (they need every worklog of the pair).

31. **`fact_worklog` through the JVM or through Postgres? — ANSWERED 2026-10-23 (`perf/fact-worklog-insert-select`, the first of the backlog's set-based pushdowns): one `INSERT … SELECT` for every task- and epic-logged worklog; the step's 720 MB of garbage per DERIVE is gone (-15 % of the DERIVE's allocation, -14 % young GCs), the step 1.85 -> 1.0 s, the DERIVE wall time and peak memory inside the noise.**
   Question: since WHY 25 reads worklogs per 200-issue batch, the worklog step still makes every worklog a `DerivationWorklogRow`, a `FactWorklogRow` and a batch of bound parameters, and re-reads each batch's PARENT/`issuekey`/SPRINT/ASSIGNEE intervals to attribute them (the deep dive's option J). Can Postgres build the rows from what the same DERIVE has just written, without the kernels' rules being written twice?
   - **Answer: yes, nearly all of it, because the kernel output is already in tables.** `DeriveWorklogSql.kt` (`worklogFactSql`) reads the as-of domain from `metrics.task_domain` and the as-of epic from `metrics.task_epic` (pass 2 wrote them with `taskDomainHistory`/`taskEpicHistory`; a covering row with a null epic is kept, no covering row falls back to `dim_task.epic_id`), the current domain/activity type/OWN category from `dim_task`/`dim_epic`, the owner team from `dim_domain`, the author's/assignee's team from `team_membership` (an exclusion constraint makes it one row), the sprint and assignee at `started_at` from `norm.work_item_field_intervals` (first covering row by `seq`, via `LEFT JOIN LATERAL … LIMIT 1`, so a malformed overlap can never multiply a row and hit the primary key). What the statement restates is arithmetic and lookups only: the covering predicate, `isForeignWork` (`foreignWorkSql`) and `md` (`worklogMdSql`), each pinned against its Kotlin kernel by `FactWorklogSqlTest` (a 27-case grid; 4,000 random and tie-case `seconds` x nine `hoursPerDay` against `worklogMd`). `md` runs in float8 in the kernel's order and goes through the double's shortest text, exactly like `Double.toBigDecimal`, so `numeric(8, 4)` rounds the same decimal. Two inputs have no table after defaulting and are bound from the `DeriveContext`: sprint -> board -> team (from `norm.sprints` and the board mapping, NOT `dim_sprint`, which the sprint step does not write while the Sprint field is unresolved - the hand-built test world has exactly that shape) and issue type -> activity type.
   - **What stays in the JVM, and why.** Worklogs on an item ABOVE the epic level (A31; the stub has one, a real tenant almost never): no `dim_*` row to read, and the work category is a JSON read through `fieldValueOptions`, a kernel that is not worth a second copy for a handful of rows. `aboveEpicWorklogRows` writes them in the old batches; the connection's `fact_worklog` count is read back after both writers.
   - **Correctness.** `MetricsDigestTest`, `DerivedStubFixtureTest`, `NormalizationPipelineTest`, `MetricsDerivationTest` (48), `MetricsHierarchyLevelTest` and every report test unchanged and green; `FactWorklogSqlTest` derives a hand-built world with literal expected rows (a member who changes team mid-period, an `issuekey` move between two domains and a parent move between two epics, a covering parent row naming NO epic, a sub-task keeping its parent's current epic, own/epic/no category, foreign work by sprint team / assignee team / epic owner, a non-member and an author-less worklog, a worklog on an epic and one above the epic level, a deleted and a moved-out item, the `md` tie 36 s = 0.00125 -> 0.0013, `late_ms` clamp and null), and fails when the as-of domain read is removed. On the real-shape data below the md5 of every `fact_worklog` column (row text, NULLs kept) is identical before and after for both connections (23,978 rows each, 23,858 with an author team, 7,769 foreign on the second).
   - **Method.** The `docker-compose.perf.yaml` shape under its own project (`flow-pdw`, ports 8884/6235/8828/8894), **scale 20** (24,001 issues, 23,978 worklogs on 9,699 items, 30 memberships), two Jira connections (the stub ignores the project filter, so each holds all 24k; teams 1 and 2, the FLO board mapped, the 30 memberships on team 1), `-Xmx512m` in **768m**, NMT on, a GC log, sampled every 3 s. Two images from the tree before and after (`flow-pdw-app:before-i`/`:after-i`), each with ONE extra line around the worklog step (wall time and `totalThreadAllocatedBytes`, printed to stderr; nothing else differs). The same database for every row, a fresh JVM per run, in the order before, after, before, after: 30 s idle, 2 single DERIVEs, 3 pairs (both connections enqueued together), 100 s idle. Allocation per DERIVE is read from the GC log (`used before GC - used after the previous GC`, summed over the run's `derive_runs` window); the step's own figure is the thread-bytes delta (a pair double-counts, so singles only).

     | Metric (run 1 / run 2) | before | after |
     |---|---|---|
     | **Worklog step, single DERIVE** | 1.93, 2.10 / 1.78, 1.58 s (mean 1.85) | **0.94, 0.96 / 1.00, 1.10 s (mean 1.0)** |
     | Worklog step inside a pair (6 per run) | 1.73-2.15 s | 1.06-1.54 s |
     | **JVM allocation inside the step, single** | 720, 721 / 722, 722 MB | **0-2 MB** |
     | **Allocation per single DERIVE (GC log)** | 5,087, 5,078 / 5,087, 5,066 MB | **4,351, 4,329 / 4,351, 4,330 MB (-15 %)** |
     | Young GCs / GC pause per single DERIVE | 160, 159 / 160, 159; 827, 701 / 850, 610 ms | 137, 136 / 137, 136; 697, 624 / 782, 725 ms |
     | Single DERIVE wall | 21.4, 21.1 / 21.3, 18.2 s (mean 20.5) | 17.3, 18.2 / 19.9, 21.1 s (mean 18.9) |
     | Pair wall (the later of the two; 3 pairs per run) | 21.3, 24.1, 24.8 / 24.2, 24.1, 23.7 s (mean 23.7) | 24.2, 21.6, 25.6 / 22.8, 23.0, 24.7 s (mean 23.7) |
     | Single DERIVE peak anon | 258, 282 / 256, 257 MiB | 254, 256 / 256, 255 MiB |
     | Pair peak anon (p1, p2, p3) | 343, 387, 382 / 323, 360, 360 MiB | 312, 360, 360 / 319, 364, 359 MiB |
     | cgroup `memory.peak` of 768 MiB | 470 / 445 MiB | 436 / 442 MiB |
     | Full GCs / young GCs per session (completed pauses) | 34 / 1,280; 41 / 1,282 | 41 / 1,100; 42 / 1,099 |

     No OOM kill, no restart, 32 DERIVEs all SUCCEEDED. **Reading it honestly:** the step itself is faster by ~0.85 s and its 720 MB of short-lived rows no longer exist (the DERIVE allocates 5.08 -> 4.34 GB, 23 fewer young collections), but a DERIVE at this size is 18-25 s of which the worklog step was 9 %, and identical code differs by 3-4 s between runs (18.2-21.4 s), so the wall time is "-0.85 s expected, not separable from the noise" (singles mean 20.5 -> 18.9, pairs unchanged at 23.7). The peak did not move beyond the run-to-run spread (pair peak mean 359 -> 346 MiB, the second and third pair identical): the peak is set by the passes' live data (items, intervals, the context maps), and the worklog rows were garbage, not live set. The saving is allocation rate, which is what the young generation (`-Xmn64m`, WHY 26) pays for in GC work and in requests that overlap a DERIVE.
   - **The statement itself** (`EXPLAIN (ANALYZE, BUFFERS)` on the 24k-row connection, deleted first inside a rolled-back transaction): 845 ms for 23,977 rows - a hash join of `norm.work_item_worklogs` to `dim_task`/`dim_epic`, index probes into `task_domain`/`task_epic`/the field intervals (the sprint and assignee probes are memoized on `(issue, started_at)`), no sort or spill. The rest is writing the rows (primary key, two indexes, three foreign-key checks, which the old path paid as well). A real tenant (34k worklogs) is ~1.2 s by the same arithmetic.
   - **Not done.** The above-epic remainder; any change to `fact_worklog`'s indexes or foreign keys (the likeliest next gain inside the step); the other pushdowns on the backlog item (WIP per user, aging WIP, the drills' worklog reads).

32. **`WorkingCalendar.workingDaysBetween` counts the days between instead of visiting them — ANSWERED 2026-10-23 (`perf/working-days-o1`): the 500 listed ages of a capped Aging WIP request 16.3 MiB / 11.5 ms -> 0.35 MiB / 0.21 ms, the request 30.7 -> 5.0 MiB (-84 %) and p50 30 -> 12-14 ms, every answer bit-for-bit the same (for any instant after 1867: the Alaska-purchase transitions turned local dates backwards mid-day).**
   Question (the item WHY 29 left open): the age of an open item is `workingDaysBetween(started_at, now)`, which made a `LocalDateTime`, a `Pair` of day bounds and boxed ints per calendar day since `started_at`; can a span's working time be read in constant time after a cheap per-calendar structure, with the SAME answer?
   - **Structure chosen: whole-week arithmetic plus a sorted array, no per-day table** (`metrics/WorkingDayIndex.kt`, used by `WorkingCalendar`). Only the first and last day of a span can be partial; every day between is a full `1.0`, so the middle is a COUNT: `weeks x workingWeekdays + the <= 6 leftover weekdays - (holidays and zone-skipped dates on a working weekday inside the range)`, the last term two binary searches in a `LongArray` of the dictionary's holidays. Cost per calendar: 7 booleans + one `LongArray` of at most the holiday count (tens), built lazily on the first span (microseconds, nothing for a calendar never asked), so it is built per request together with the calendar (`MetricsSettingsService.readWithCalendar` builds a fresh one on every read) and there is NO cross-request cache to invalidate. A per-day prefix sum was rejected: it grows with the span (4 B per calendar day, lazily extended for an unbounded `started_at`) and needs a cache to pay off, while the week arithmetic is O(log holidays) for any span and immutable.
   - **Equal to the last bit, not to a tolerance.** The old result is a running double sum: the first day's fraction, then `+ 1.0` per working day, then the last fraction. Adding the COUNT in one go can round differently in the last place once the sum crosses a power of two (the test fails with `start + count`), and DERIVE stores these numbers. `WorkingCalendar.addWholeDays` reproduces the stepwise rounding exactly: inside one binade adding whole numbers is exact, so a run collapses into one addition, and only the step that crosses into the next binade rounds, once - at most one loop pass per power of two (about ten for a 500-day age). The one other way the walk could differ is a zone that skips a whole calendar date (Pacific/Apia 2011-12-30: empty `[dayStart, dayEnd)`, so the walk counted it as 0): `WorkingDayIndex.skippedDates` finds them from the zone's historical gaps of a day or more, where `dayBoundsMs` is empty (none for any real-world configured zone; pinned anyway).
   - **Pinned by** `WorkingCalendarEqualityTest` (seeded, 7,200 random calendar x span cases over nine zones - Warsaw, UTC, New York, Kolkata, Lord Howe's half-hour DST, Sao Paulo's midnight DST, Apia, Kiritimati, Tokyo - six weekend sets including none and all seven days, up to 40 holidays also on weekends; spans reversed, empty, tiny, same-day, adjacent-day, to 1,500 days across year boundaries; plus 3,000 spans that start and end exactly on holidays and day boundaries, 4,000 spans around the DST and skipped days, and 5,000 `addWholeDays` cases), each compared by raw double bits with a verbatim copy of the old walk kept in the test only. Replacing `addWholeDays` by `start + count`, or dropping the skipped dates, each fails it. The existing `WorkingCalendarTest` is unchanged; the whole server suite (1,717 tests, `-Pforks=2`) is green with no digest touched: `DerivedStubFixtureTest`, `MetricsDigestTest`, `NormalizationPipelineTest`, `MetricsDerivationTest`, `ReportAgingWipCapTest`, the estimation-accuracy and snapshot tests.
   - **Callers.** All four callers of `workingDaysBetween` take the O(log) path with no code change: Aging WIP's `ageOf` (the reason for this item; the Home overview shares it), DERIVE's lead/cycle/blocked working days (`DeriveTaskRows.kt`, `DerivePasses.kt`) and the data-quality member-days (`DataQualityTasks.kt`). Not changed, because their OUTPUT is the days and not a count: `inProgressDays` (Deep dive, A40), `dimDateRows`, the sprint/capacity day lists (`CapacityKernels`, `CostCapacity`) and the WIP day grids; their comments no longer say they are "the very walk `workingDaysBetween` makes".
   - **Measured** (scratch tests, not committed; the derived stub's migrated Postgres in Testcontainers, never the dev stack; allocation = `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` summed over ALL threads before and after each call, so the coroutine and R2DBC threads count; the "before" is the same tree with the old loop switched on by an environment variable, both runs in alternating order on one machine under other agents' load, so read the ratios):

     | what | before | after |
     |---|---|---|
     | one capped `agingWip` call (service level, no JSON), 1,800 open tasks started 20-220 days back, unit read, 60 calls after 40 warm-up | p50 29.6, 30.2 ms; **30.7 MiB**, p95 34.8, 35.2 ms | p50 11.7, 13.9 ms; **5.0 MiB**, p95 16.4, 17.6 ms |
     | the 500 listed ages alone (`workingDaysBetween` x 500, ~120 days each) | 11.5, 12.2 ms; **16.3 MiB** | 0.21, 0.22 ms; **0.35 MiB** |
     | one DERIVE of the stub (1,200 items, a warm re-derive, 6 per mode) | 662-820 ms; 203-204 MiB | 653-675 ms; 199-200 MiB (-1.5 %) |

     The request's remaining 5 MiB is the SQL read and the rows (the age is now ~0.35 MiB of it). The request figure here is smaller in absolute terms than WHY 29's 101 MiB (that was HTTP over a scale-7 stub with JSON, a 256 MiB heap and a different age spread); the HTTP leg was not re-run, so what carries over is the change in the age's own cost (16.3 MiB per 500 ages -> 0.35), not the percentage. The stub DERIVE hardly moves (its spans are short and few); a real tenant's long cycle times and the scale-20 perf stack were not re-measured, the expected gain there is the same per-age saving times three spans per item.
   - **Not done.** The HTTP-level and scale-20 measurements above; a wall-time claim for DERIVE (not separable from run-to-run noise on the stub).

33. **The worklog backfill fetched its 1,201 issues one at a time - ANSWERED 2026-10-23 (the backlog's "Concurrent worklog fetches in the backfill"): the claimed batch is fetched concurrently within the client's own `maxConcurrentRequests` and settled in claim order; the stub backfill phase 2.18 -> 1.74 s (-20 %), the stub SYNC 6.21 -> 5.92 s (-5 %, inside the noise), far less than the ~2 s WHY 21 hoped for - the stub is not latency-bound; a real tenant is.**
   Question: since WHY 21 the stub SYNC is ~4.6 s and ~2 s of it the sequential per-issue `GET /issue/{id}/worklog`. Can the batch be fetched concurrently without a second request limit, without changing a stored row, a stamp, a cursor or what a failure leaves behind?
   - **Fix** (`jira/JiraFetchAhead.kt`, `JiraWorklogStream.fetchAndFlush`; `jira-integration.md` "Batched raw writes"): `fetchAheadInOrder` starts up to `window` fetches in list order and hands every outcome to ONE consumer in list order; a failure is captured as a `Result` and rethrown at its own position, so the issues before the first failing one are flushed and stamped exactly as before (the earliest failure in claim order wins, not the first in time). Heartbeat, skip budget, progress counters, `FetchedBatch` and the 5,000-worklog early flush all stay in the consumer. The limit is not new: every call still goes through `JiraHttp`'s `jira.maxConcurrentRequests` semaphore (default 4, shared by the whole process), and the look-ahead window IS that number (`JiraClient.maxConcurrentRequests`; 1 = sequential), and no fetch starts while the finished-but-unconsumed results hold more than 5,000 worklogs, so memory is bounded by worklogs, not only by issues (`jira-integration.md` "Batched raw writes" has the worst case). Fetches are children of the stream's scope: cancellation, a lost lease or a failure cancels and awaits them. A page chain of one issue stays sequential.
   - **Proof of identity.** `NormalizationPipelineTest`, `SyncedStubFixtureTest`, `JiraSyncPipelineTest`, `JiraOversizedBatchTest` (incl. the batch-failure and skip cases) and the digest tests are unchanged and green. New: `JiraFetchAheadTest` (order, window bound, failure position, cancellation) and `JiraWorklogConcurrencyTest` (the real `HttpJiraClient` + `JiraHttp` over a `MockEngine` counting calls in flight at the wire: peak = the limit, never above; a limit of 1 peaks at 1; a failure mid-batch leaves the rows and stamps of the sequential loop; cancellation and a lost lease leave nothing in flight).
   - **Method.** `SyncedStubFixtureTest`, the `fixture-cost stub-sync` marker plus a temporary timer around `backfillPerIssue` (removed), local 18 cores with other agents' builds running (load average 8-10; this set is the final code: window = the limit, the worklog budget, per-tenant pause), six interleaved pairs; "before" is the window forced to 1 through a temporary env override (the sequential loop, one `async` per issue of overhead), "after" the default (4 at a limit of 4).

     | local, `SyncedStubFixtureTest` | runs (ms) | mean |
     |---|---|---|
     | backfill phase, sequential | 2130 / 2219 / 2136 / 2580 / 2350 / 1685 | **2183** |
     | backfill phase, concurrent | 1721 / 1725 / 2147 / 1981 / 1566 / 1286 | **1738** |
     | `stub-sync`, sequential | 6108 / 6523 / 5972 / 6915 / 7014 / 4726 | **6210** |
     | `stub-sync`, concurrent | 6402 / 5784 / 6685 / 6947 / 5310 / 4402 | **5922** |

     An earlier set, before the window was cut from `2 x` the limit (8) to the limit (4) and the worklog budget and the per-tenant pause were added, measured 2036 -> 1413 ms for the backfill phase and 5742 -> 5142 ms for `stub-sync` on a quieter machine; the smaller window and the run-to-run spread (the sequential column alone runs 4.7-7.0 s) account for the difference.
   - **Reading it honestly.** The gain is ~0.45 s on the backfill phase and not separable from the noise on the whole SYNC, not ~2 s: the stub answers in ~1 ms, so a call is dominated by the client's own CPU in a cold test JVM (request building, response parse, the in-order consumer), and the fixture drives the SYNC from the test's `runBlocking` loop, which most likely serializes that part (not isolated); the overlap is only the wire and WireMock. A real tenant is the opposite case (each call ~100+ ms of latency), where the backfill should approach `1 / maxConcurrentRequests` of its sequential time. Design check, not a tenant measurement: 60 issues at 25 ms per call take well under a second at a limit of 3 (0.74 s with the seeding, before the window was cut to the limit) (`JiraWorklogConcurrencyTest`), against 60 x 25 ms = 1.5 s sequential. The first real-Jira backfill has not been re-measured with this change; its figure is the number to record here.
   - **Request pattern against a real tenant.** From one `GET /issue/{id}/worklog` at a time to at most `jira.maxConcurrentRequests` (default 4) at a time, process-wide, same guarded client, token and per-call backoff/`Retry-After`. API-token traffic is outside Atlassian's 2026 points-based limits (BACKLOG note); the knob if a tenant answers 429s is `JIRA_MAX_CONCURRENT_REQUESTS` (1 = the old sequential backfill).  A 429 (or a 5xx with `Retry-After`) sets a per-tenant (cloud id) pause in `JiraHttp` that the tenant's other calls wait out before starting a request, without holding a semaphore permit (`JiraHttpSharedPauseTest`), so the siblings do not keep firing into a rate limit and another tenant is not stalled.
   - **Not done.** The changelog calls (~0.9 s) and PROCESS (~1 s) of the remaining stub SYNC; concurrent fetches in the CHANGELOGS per-issue fallback.

**What the suite is made of (local, pre-fix run, 782 tests, 12m30s of class time):**
`MetricsDerivationTest` 5m55s (47 %), `MetricsDigestTest` 2m00s (16 %), `NormalizationPipelineTest`
1m16s, `ReportDataQualityTest` 47 s, `DataProfileTest` 35 s — 24 tests over 5 s account for 87 % of
the time. `local-times.mjs slow-tests` reproduces this table for any run.

34. **Home loaded every open item to print three percentiles - ANSWERED 2026-10-23 (`perf/home-open-items`): the open items are counted and sampled in Postgres; allocation per overview request -61 % / -64 %, p50 -28..-38 % (3 teams); the WIP drill of a big team -45 % in both. Every response is byte-identical.**
   Question (BACKLOG "Home still loads every open item"): `GET /reports/home` fetched every open task and epic of the UNIT (`fetchOpenTasks`/`fetchOpenEpics`, whatever the pick), filtered them per listed team and aged each one in Kotlin
   (`WorkingCalendar.workingDaysBetween`: one `LocalDateTime` + boxed `Integer` per calendar day per item) to sort the ages and read three percentiles; the `WIP_TASKS`/`WIP_EPICS` drills did the same for one team and cut to 500 afterwards. WHY 28-30 left Home alone.
   - **Measured first** (a scratch test, not committed, against a scaled hand-built world - the derived stub is too small - on the shared Testcontainers Postgres: 3 picked teams of 4 + team-less rows, open tasks started over the last 200 days
     (70 % IN_PROGRESS), open epics at 1/15 of the tasks, done tasks over 178 days; 10 warm-up calls then 30 timed with a GC before each; allocation = the sum of `ThreadMXBean.getThreadAllocatedBytes` over all threads; one JVM, no HTTP layer, so read the ratios, not the absolute ms).
     Scale A = 2,000 open tasks + 133 open epics + 4,000 done tasks (the scale-7 stub has 1,795 open tasks); scale B = 6,000 + 400 + 12,000. Where one overview request (3 teams) went, scale B, allocation per request, each piece measured on its own:
     the done-task read 56.9 MiB (30 %); the two open-item row reads 14.0 MiB (7 %); **the working-day ages of the listed teams' open items ~100-110 MiB (~55 %; the age kernel over ALL 5,800 started items alone is 132-160 MiB, ~24 KiB per item)**; everything else (quality epics 3.8, accuracy epics 1.7, sprints, snapshots, context, per-team Kotlin) ~7-11 MiB.
     By time the same ranking: ~90 ms the done-task read, ~26 ms the open-item reads, ~60 ms the ages, of 191-203 ms. So the open items DID dominate (~62 % of the allocation, ~45 % of the time), mostly through the per-item age walk, which WHY 32 (merged first) made O(1) in `WorkingCalendar`. **These figures, before and after, were measured on master d739291, BEFORE WHY 32**: on today's master the ages are already cheap, so this change's own share is the open-item row reads (~17 % of the allocation as
     measured here) plus the per-item ages it no longer computes - the headline percentages overstate the gain on top of WHY 32.
   - **Done: `reports/HomeOpenItems.kt`.** An age never grows with `started_at`, so a team's ages sorted ascending are its `started_at` values sorted DESCENDING and `percentileContinuous` reads at most six ranks of that list. Per kind (tasks, epics): (1) a `GROUP BY team` count (it also gives `unassigned.openTasks`:
     the teams that are not active, and no team); (2) only for a listed team of at least `minSampleSize` items, ONE statement: `row_number()` and `COUNT(*) OVER` partitioned by team over the same predicate, filtered by an `OR` of (team, rank) pairs, returning `started_at` at those ranks plus the team's count; a count that changed between (1) and (2)
     (a DERIVE rewriting the fact rows in between) is read again, three tries. The ages are computed for those few instants, and the figure is `percentileContinuous` ITSELF over `SparseAges` - a list that knows only the ranks a dry run over `IndexProbe` showed it will touch - so the interpolation is the old code's, not a copy of it (`HomeOpenRanksTest`: identical doubles for every size 1..3000).
     The drill takes the team's open items through `AgingOpenItems.kt`'s `TaskOpenSource`/`EpicOpenSource` (now restricted to one team and shared): the first 500 by `started_at` plus every later row whose age still equals the 500th's (WHY 29's `candidatesOf`), a `COUNT` only when the page is full, and the drill order (`DRILL_ORDER`: age, then the NEWEST start, then connection and issue id - not Aging's) applied to that
     superset, so a cut inside a weekend tie block lists the block's latest starts, as before. `fetchOpenTasks`/`fetchOpenEpics` are gone from `src/main` (they are `legacyOpenTasks`/`legacyOpenEpics` in the tests now, the oracle).
   - **Measured after** (same harness; the base tree extracted from the base commit with `git archive` next to this one; base/new/base/new, two rounds each, the second round shown with the first in parentheses where it differs; the first round of the new tree allocated 72 MiB at scale B on a cold JIT, the second 67.5; the untouched done-task read is the control):

     | request | scale | p50 before -> after | allocation before -> after |
     |---|---|---|---|
     | `home` 3 teams | A | 76.7 -> 50.4 ms (70.2 -> 50.2) | 63.4 -> 24.8 MiB (-61 %) |
     | `home` 3 teams | B | 203.2 -> 129.7 ms (191.3 -> 117.9) | 189.1 -> 67.5 MiB (-64 %) |
     | `home` 1 team | A / B | 57.9 -> 43.3 / 145.5 -> 107.5 ms | 37.9 -> 22.3 / 112.4 -> 63.4 MiB |
     | `home/items` `WIP_TASKS` (team of ~500 / ~1,350 open) | A / B | 20.5 -> 16.9 / 46.5 -> 25.6 ms | 16.6 -> 14.9 / 47.4 -> 25.9 MiB |
     | `home/items` `WIP_EPICS` | A / B | 6.8 -> 6.1 / 8.9 -> 6.9 ms | 2.5 -> 2.2 / 5.0 -> 3.8 MiB |
     | control: the done-task read | A / B | 34.8 -> 33.9 / 94.5 -> 93.7 ms | 19.2 -> 19.2 / 56.9 -> 56.7 MiB |

     The open-item reads of an overview are now 5-8 ms and ~1.8 MiB per kind (6,000 open tasks). A team under 500 open items gains little allocation from the drill pushdown (the page is capped by the team's own size, and the ages still cost ~24 KiB each): that is the age walk, the calendar PR's lever.
     **What is left is the done-task read: 84 % of an overview's allocation at scale B** (the whole unit's ~180 days, filtered to the listed teams in Kotlin) - the next lever, outside this item: WHY 35 (a `credit_team_id` filter in SQL with a count for `unassigned.doneTasks`; the medians and percentiles in Postgres are still open).
   - **Verified byte-identical.** (1) 24 responses recorded from the unchanged code over `HomeOpenWorld.kt` (the overview at `minSampleSize` 3 and 1 for both pick sets, every team's two WIP drills at both) and re-compared after the change: `diff -r` empty once the random team-name suffix is set aside. (2) `HomeWipPushdownGoldenTest` grades the new reads against the old ones kept in `HomeWipOracle.kt`
     (`legacyOpenTasks`/`legacyOpenEpics`/`legacyWip`/`legacyWipDrill`) over the same world: 790 open tasks and 610 epics for team A (the drill cut at the 500th place falls inside a 120-item weekend block; 30 age-0 items; a second connection repeating the block's instants), a team at exactly `minSampleSize` (3), one under it (hidden), a team of one (`minSampleSize` 1), 50 tasks of an unpicked team, a soft-deleted
     team's 10 and 30 team-less tasks (`unassigned`), NOT_STARTED and unstarted rows. Mutation-checked: `place` off by one fails both overview tests, dropping the tie extension fails the drill test. `ReportHomeTest`, `ReportHomeDrillTest` and `ReportAgingWipCapTest` (now on the oracle) pass unchanged.
   - **Not done.** The done-task read and its Kotlin medians (above); the per-item age walk itself (`WorkingCalendar`, WHY 32); a plan-shape test for the rank statement (two statements per kind, 5-8 ms together at 6,000 open items; no measurement suggested one).

35. **Home read the whole unit's done tasks to print up to three teams - ANSWERED 2026-10-23 (the backlog's "Home's done-task read is now most of its cost"): the done tasks are filtered on `credit_team_id` in SQL and the work of no active team is a `GROUP BY` count; allocation per overview request -22..-28 % for 3 of 4 teams (both scales, both rounds) and -66..-71 % for one team, p50 -2..-25 % (3 teams) and -53..-65 % (1 team); a team's done-task drill -65..-72 % in allocation, -51..-67 % in p50. Every response is byte-identical. The listed teams' rows are still ~85 % of what is left.**
   Question: after WHY 34, `GET /reports/home` still read every done task of the UNIT over the ~180 days of both windows (`fetchHomeDoneTasks`), `teamOf` kept the listed teams' rows in Kotlin and `unassigned.doneTasks` counted the rest there; each done-task Home drill (`TASK_*`, `QUALITY_TASKS_*`) read the unit to keep one team.
   - **Measured first** (WHY 34's scratch harness again, not committed: a scaled hand-built world on the shared Testcontainers Postgres, 10 warm-up calls then 30 timed with a GC before each, allocation = the sum of `ThreadMXBean.getThreadAllocatedBytes` over all threads, one JVM, no HTTP layer;
     scale A = 2,000 open tasks + 133 open epics + 4,000 done tasks, scale B = 6,000 + 400 + 12,000; the teams a, b, c, d (a, b, c picked) own 8 of every 9 rows, no team the ninth, one more team is soft-deleted; the base tree extracted from the base commit with `git archive` next to this one, base/new/base/new). The base overview at scale B
     allocated 63.7 MiB; WHY 34 had put the done-task read at 56.7 MiB of it (84 %), which is why the whole read was the lever.
   - **Done: `reports/HomeReads.kt`.** `fetchHomeDoneTasks(filter, connections, window, teams)` adds `credit_team_id IN (teams)` (an empty set runs no statement) and returns only those rows; `countHomeDoneOutside` is one `GROUP BY credit_team_id` count of the CURRENT window's done tasks (same slice and window predicate, one shared helper), of which the groups that are `null` or not an active team are summed - the old
     `creditTeamId !in activeIds`, soft-deleted teams included. `HomeReport.kt` reads both (the active team ids now reach `readHomeRows`); `HomeDrill.kt`'s `doneTasks()` asks for its one team. The per-item values, the Kotlin split into windows and every figure are untouched; one statement is added (~16 per overview).
   - **Measured after** (same harness; round 2 shown with round 1 in parentheses where it differs; the untouched `WIP_EPICS` drill is the control):

     | request | scale | p50 before -> after | allocation before -> after |
     |---|---|---|---|
     | `home` 3 teams (a, b, c: 6 of 9 rows) | A | 39.5 -> 38.6 ms (40.6 -> 33.4) | 22.7 -> 17.6 MiB (22.6 -> 16.6; -22 % / -27 %) |
     | `home` 3 teams | B | 96.7 -> 83.3 ms (100.7 -> 75.9) | 63.7 -> 48.1 MiB (63.2 -> 45.2; -24 % / -28 %) |
     | `home` 1 team | A / B | 41.1 -> 17.7 / 97.9 -> 37.2 ms | 21.3 -> 7.2 / 60.9 -> 18.4 MiB (-66 % / -70 %) |
     | `home/items` `TASK_ESTIMATE` (one team) | A / B | 19.9 -> 9.7 / 51.6 -> 17.2 ms | 11.0 -> 3.9 / 30.0 -> 8.7 MiB (-65 % / -71 %) |
     | control: `home/items` `WIP_EPICS` | A / B | 5.3 -> 6.9 (4.5 -> 4.8) / 5.3 -> 5.0 ms | 1.0 -> 1.0 / 1.3 -> 1.3 MiB |

     The gain follows the picked teams' share of the unit's rows, which the read is now proportional to: 3 of the 5 row groups here, so an overview of 3 teams out of a unit of ten gains more than this table, one of 1 gains what the 1-team rows show. The count is cheap: 4.8 ms and <0.1 MiB beyond the shared context (3.0 ms) at scale B.
     **What is left is still the done-task read of the listed teams:** 38.2 of 45.2 MiB (85 %) and 61 of 79 ms at scale B for 3 teams (~8,000 rows, ~4.8 KiB each).
   - **Verified byte-identical.** (1) 106 responses recorded from the unchanged tree (the overview at `minSampleSize` 3 and 1 over four pick sets, each of five teams' nine done-task drills at both, and the scaled worlds' overviews at both scales for 3 teams and 1 team plus two drills) and re-compared after the change: `diff -r` empty once the random connection and team-name suffixes are set aside (the first
     round differed only in the order of two same-prefix connections in `freshness`, which sorts by their random names: a harness artefact, fixed by naming the connections per scale). (2) `HomeDonePushdownGoldenTest` grades the new read, the count, the overview's done-task figures (granularity, task accuracy, throughput incl. the weekly series, the three task findings, `unassigned.doneTasks`) and the nine done-task drills against the old whole-unit read kept in `HomeDoneOracle.kt` (`legacyHomeDoneTasks`/`legacyDoneFigures`/`legacyDoneOutside`) over `HomeDoneWorld.kt` (seeded by `HomeOpenWorld.kt`):
     A with 400 + 120 tasks over both windows on two connections, a task at each edge of the 180 days (a millisecond before and at the previous window's start, before and at the current one's, last millisecond and first one after the end: the outer two in no window), B at exactly `minSampleSize` 3, C under it, G with one task and none before, an unpicked team, a soft-deleted team's 35 and 42 team-less tasks (+ 8 and one in the current window's last millisecond), a sub-task and a not-started task credited to a team, every missing-data branch. Mutation-checked: dropping the `credit_team_id` filter fails the read and the drill tests, counting only teams (`it != null && it !in active`) fails the read and the overview tests, counting over both windows fails the overview test, and a flipped window edge fails all three. `ReportHomeTest`, `ReportHomeDrillTest` and `HomeWipPushdownGoldenTest` pass unchanged.
   - **Not done.** The medians and percentiles in Postgres (`percentile_cont` per team and window, plus the PRED shares and weekly sums): the remaining 85 % is the row transport (~4.8 KiB per row through R2DBC and `HomeDoneTask`), so it is the next lever, but it moves every figure's arithmetic (`percentileContinuous`, the BigDecimal sums, `workingDaysOf`, `ratioAtStartOf`) out of the shared kernels the drills and the other reports use and would need its own oracle per figure - a separate item, not a rider on this one. The cheaper steps before it were taken in WHY 36 (the row trimmed, the grouping measured and dropped). No index was tried.

36. **Home's done-task rows cost ~4.9 KiB each, nearly all of it per decoded COLUMN - ANSWERED 2026-10-24 (the backlog's "Home's done-task rows still cost ~4.8 KiB each"): the read returns eleven columns instead of fifteen (three column pairs folded into one `CASE` column each, an unused one dropped); allocation per overview request -17..-23 % (3 teams, both scales, both rounds), p50 -21..-23 %; a team's done-task drill -12..-20 % in allocation. Every response is byte-identical. Grouping the rows once was measured at 0.5 % and not shipped.**
   Question: after WHY 35 the listed teams' rows were still ~85 % of an overview's allocation (38 of 45 MiB at scale B, 3 teams, ~8,000 rows). The brief's levers, cheapest first: trim what a `HomeDoneTask` row reads and carries; group the listed teams' rows once instead of `teamOf` filtering the list per team; anything else the measurement points at.
   - **Measured first** (WHY 34/35's scratch harness again, not committed: scaled hand-built worlds A (4,000 done + 2,000 open tasks + 133 epics) and B (12,000 + 6,000 + 400) on the shared Testcontainers Postgres, the picked teams a, b, c owning 2 of every 9 rows each and d, a soft-deleted e and no team 1 each, 10 warm-up calls then 30 timed with a GC before each,
     allocation = the sum of `ThreadMXBean.getThreadAllocatedBytes`, one JVM, no HTTP layer; the base tree extracted from master 093631cd with `git archive`; base/new/base/new). Scale B, 3 teams, per request: `fetchHomeDoneTasks` 38.3 MiB (8,001 rows, 4.9 KiB each) of 45.4. **A bare select of the same rows with no mapping costs 33.5 MiB for the fifteen columns and 5.6 MiB for one**:
     a fixed ~0.3 KiB per row plus ~0.27 KiB per column whatever its type (a `BIGINT`, a `NUMERIC` and a `VARCHAR` cost within ~0.1 KiB of each other), so the cost is the NUMBER of columns, not their types; mapping into `HomeDoneTask` is the remaining ~0.6 KiB. A JFR allocation profile of the read (`jdk.ObjectAllocationSample`, 60 calls, one 2.5 GB first-sample artefact set aside) says where a column's bytes go:
     r2dbc-postgresql returns every result column as TEXT (`forceBinary` is off by default), so each value is a netty `ByteBuf` slice (20 %), a `String` plus a temp `byte[]` (~40 %, `ByteBufUtil.decodeString`) and for a `NUMERIC` also a `char[]` and the `BigDecimal` (~8 %), and Exposed adds a mapper cache key per read (6 %) and a lambda per `ResultRow.get` (6 %).
     The grouping lever, on the 8,001 rows: `teamOf`'s per-team `filter` passes cost 0.23 MiB and 0.4 ms in all (0.5 % of the request); grouping once is the same 0.23 MiB and 0.3 ms.
   - **Done: `reports/HomeReads.kt` (+ `HomeReport.kt`, `HomeDrill.kt`).** `HomeDoneTask` is eleven columns: `connection_id`, `issue_id`, `done_at`, `credit_team_id`, the assignee, `estimate_at_done_md`, `estimated_late`, plus `actual_md` only `CASE WHEN has_worklogs` (the D14 "worked" test, with `actual_md > 0`, in one column),
     `estimate_at_start_md` only `CASE WHEN started_at IS NOT NULL` (D15: a task that never started has no ratio), `cycle_working_days` only `CASE WHEN cycle_ms IS NOT NULL` (`workingDaysOf`'s two conditions) and `estimate_source = 'NONE'` as a boolean. `active_ms` was read and used by nobody. The per-item values (`cycleDaysOf`, `costMdOf`, `taskRatioOf`; `estimateMdOf` unchanged) work on those fields directly, the ratio being `actualOverEstimate` itself,
     so a figure no longer allocates an `AccuracyTask`/`DoneCycleTask` per call (up to five per row before). The `CASE` is a small `ValueWhen` expression class, NOT Exposed's `Case().When().Else()`: that rebuilds a list of its branches on every `columnType` call, which a `ResultRow` makes once per value read, and was 18 % of the read's allocation when first tried (41.5 MiB per overview at scale B instead of 35.2).
   - **Measured after** (same harness; round 2 shown with round 1 in parentheses where it differs, then the repeat of the new tree; the untouched `WIP_EPICS` drill is the control; 1-minute load average while each round ran: base 1 ~6, new 1 ~5, base 2 ~2, new 2 ~2, the repeat ~2, and no `flow-e2e-*` container running):

     | request | scale | p50 before -> after | allocation before -> after |
     |---|---|---|---|
     | `home` 3 teams | A | 36.1 -> 27.9 ms (35.0 -> 27.7) | 16.8 -> 13.9 MiB (-17 %; round 1 13.2, -21 %) |
     | `home` 3 teams | B | 85.6 -> 67.1 ms (84.6 -> 65.9; repeat 65.1) | 45.4 -> 37.1 MiB (-18 %; round 1 35.2, -22 %; repeat 37.3) |
     | `home` 1 team | A / B | 17.2 -> 15.5 / 39.6 -> 33.5 ms | 6.9 -> 6.0 / 17.4 -> 14.8 MiB (-13 % / -15 %; round 1 -16 % / -20 %) |
     | `home/items` `TASK_ESTIMATE` (one team) | A / B | 8.5 -> 7.7 / 15.1 -> 12.1 ms | 3.7 -> 3.3 / 8.3 -> 6.9 MiB (-12 % / -16 %; round 1 -15 % / -20 %) |
     | the read alone, 3 teams | A / B | 22.5 -> 17.0 / 58.9 -> 41.7 ms | 13.2 -> 10.5 / 38.3 -> 30.1 MiB (-21 % / -21 %; round 1 -26 % / -27 %) |
     | control: `home/items` `WIP_EPICS` | A / B | 4.7 -> 4.7 / 6.1 -> 6.1 ms | 1.0 -> 1.0 / 1.3 -> 1.4 MiB |

     The new tree's allocation moved between JVMs (round 1 35.2 MiB at scale B, round 2 and the repeat 37.1 and 37.3; the base's did not: 45.39 and 45.40), most likely which allocations the JIT eliminated: read the range. What is left per row is ~3.7 KiB, ~270 bytes of it per remaining column.
   - **Verified byte-identical.** (1) 122 responses recorded from the unchanged tree and re-compared after the change: the overview at `minSampleSize` 3 and 1 over four pick sets plus each of five teams' nine done-task drills at both (98, over `HomeOpenWorld.kt`/`HomeDoneWorld.kt` with the new rows below) and the scaled worlds' overview for 3 teams, 1 team and two other teams plus the nine drills of one team at both scales (24):
     `diff -r` empty once the random connection and team-name suffixes are set aside. (2) `HomeDonePushdownGoldenTest` grades the trimmed read against the old fifteen-column row (`LegacyDoneTask` in `HomeDoneOracle.kt`, which carries the OLD per-item values over `AccuracyTask`/`DoneCycleTask`): the full row of every pick, the new test "the trimmed row yields the old per-item values, row by row" (the estimate, cycle, cost and ratio doubles and the findings of every credited fact row, ~680), the overview's figures and the nine drills.
     `HomeDoneWorld.kt` gained four rows no DERIVE writes, whose cycle columns disagree (`cycle_ms` set without `cycle_working_days` and the reverse, in both windows), because the fold must equal the old two-column test on them too. Mutation-checked: gating `actual_md` on itself instead of `has_worklogs`, gating the start estimate on itself instead of `started_at`, gating the cycle on `cycle_working_days` instead of `cycle_ms` and flipping `estimate_source = 'NONE'` each fail all four golden tests;
     dropping the `actual_md > 0` test from `costMdOf` fails three (not the read test). `ReportHomeTest`, `ReportHomeDrillTest`, `HomeKernelsTest` (only its row constructor changed), `HomeWipPushdownGoldenTest` and `HomeOpenRanksTest` pass.
   - **Not done.** (a) One flags column for `estimate_source = 'NONE'` and `estimated_late` (ten columns instead of eleven): measured with plain stand-in columns at -1.8 MiB (~5 % of an overview at scale B; 37.7 against 41.8 ms for the bare select), an opaque bit-packed `CASE` for a gain at the 5 % line. (b) Grouping the rows by team once (0.5 %, above). (c) `forceBinary` on the connection: tried in the harness and WORSE (38.0 against 35.2 MiB), so not a lead.
     (d) Scaled `BIGINT` instead of `NUMERIC` for the four decimals: a `BIGINT` column costs what a `NUMERIC` one does here. (e) Dropping `credit_team_id` by reading one statement per team: a column saved, but three statements and the single snapshot lost. (f) The medians and percentiles in Postgres (WHY 35's list), still a separate item.

37. **The nightly `Run E2E` after WHY 23: a minted login and the long chains first - ANSWERED 2026-10-24 (the backlog's "The nightly e2e: the next levers after WHY 23"): `Run E2E` on the isolated local stack 216 / 217 s -> 130 s (4 identical runs, -40 %), 1.1 s off every sign-in and `reports` first behind four locks; CI 486-556 s -> 347 s on one dispatched run.**
   Question: WHY 23 left four levers (heavy-first dispatch, a minted login, `reports` onto the shared fixture, a worker-count re-A/B) and one open number: the nightly ran 486-556 s (182-185 tests), not the ~370 s simulated. Where does a nightly's wall go, and which of the four moves it?
   - **Measured first** (the nightlies 10-22 and 10-23, from the `playwright-report` artifact: its `report.json` carries every test's start and duration and the "Before Hooks" step of the first test of a file, which is the `beforeAll`, so the gaps WHY 23 modelled are now read, not inferred; the extractor is a few lines of node over the report's embedded zip):

     | nightly `Run E2E` | tests | wall | Σ test time | Σ `beforeAll` | slot use (Σ tests + Σ hooks) / 4 / wall |
     |---|---|---|---|---|---|
     | 10-22 | 184 | 556 s | 1504 s | 340 s (reports 94, capacity 88, accessibility-data 85, public-holidays 56) | 83 % |
     | 10-23 | 185 | 535 s | 1612 s | 282 s (capacity 87, accessibility-data 73, public-holidays 62, reports 43) | 89 % |

     So the wall is only ~60 s above the work, and **no packing lever can win more than that**; the larger term is the work, 1500-1600 test-seconds on a 4-vCPU runner (2.5x the laptop's 595-632 s for the same tests). The shape of the 10-23 run: the files start in file-name order (`accessibility-data`, `accessibility`, `auth`, `capacity`, ... `reports`, ... on the first free worker), `accessibility` (68 tests, 426 s) and `accessibility-data` (46 tests, 441 s, started at 73 s after waiting for the shared fixture) are one worker each for the whole run, and `reports` (alphabetically late) starts its sync at ~240 s and ends the run alone at 535 s. Locally (18 cores, isolated stack, 185 tests, base tree) the same shape: 216 / 217 s, `reports` dispatched at 96-107 s and alone for the last 50 s, the other three workers done at ~165 s. Why WHY 23's simulation over-predicted is still not proven (a hypothesis, not a finding: on a CPU-bound runner a worker idle in a `beforeAll` poll does not cost throughput, the others get its CPU, so removing waits would not have paid as the model said).
   - **Done 1: a minted login** (`e2e/tests/helpers.ts`). `login()` posts the real `POST /api/v1/login` through the page's request context, writes the five `flow.auth.*` keys the SPA's own `storeSession` writes, and loads `/` once (a static asset, `/favicon.svg`, gives the empty page its origin without booting the SPA); a refused login, an MFA challenge or a shell that does not accept the session falls back to the form. The tokens are the server's (the revocation blocklist and any per-request credential check apply as for any client) and one session is minted per call, because `signOut` revokes the pair - Lettuce's `sessions.ts` does the same; a per-worker `storageState`, the other option, was ruled out in WHY 7 (the SPA's session-identity guard) and is not needed. The form stays as `loginViaForm()` for the specs whose subject is a credential (`flow-users`' first sign-in with the revealed password, `password-reset`'s new password, `i18n`'s language riding the login response; `auth` and `mfa` drive the form inline). **1.1 s per sign-in**: Σ test time 632 -> 427 / 398 s (-32..-37 %) over the same 185 tests, wall 217 -> 175 / 160 s; `accessibility` alone 166 -> 92 s.
   - **Done 2: the long chains first, `accessibility-data` in four groups, and `reports` locked apart from every live connection** (`e2e/playwright.config.ts`, five specs). The runner queues test groups in project order, so three projects over the same browser: `first` = `accessibility` (the longest file that needs no data, so the others' sync waits overlap it) and `reports` (the longest chain), `chromium` = the rest, `last` = `accessibility-data`, whose describe is now `parallel` (its 46 scans are independent and read-only): four groups of 12 at the tail, when the shared fixture is long ready, fill the end of the run evenly. **The order was load-bearing and the first versions failed on it**: `reports` reads team-level figures (a team's tasks are credited by its roster across every connection derived since) and drills them against the figure it just read, and alphabetically it ran when every other spec's connection was gone. Started first it failed 2 runs of 9 (a Costs figure after a holiday re-derive, 137 s; a Throughput cost drill whose summary differed from the figure while `data-sources`' connection finished its DERIVE, 143 s: "the data changed since the chart was drawn"). The rule is now explicit: `reports` holds four Playwright locks (`REPORTS_EXCLUSIVE_LOCKS`, `helpers.ts`) and `capacity`, `data-sources`, `public-holidays` and `metrics-config` one each (so they stay free to overlap each other), and `reports`' `beforeAll` runs the shared fixture's preparation (`sharedFixtureReady`) before it puts its roster person on the team, so the shared connection is paused before that credit exists. Wall 160-175 s (minted login only) -> 130 s on top; the file that ends the run is `metrics-config`, after `reports` (the lock chain, 94 + 36 s).
   - **Measured after** (local isolated stack, compose project `flow-e2e`, 4 workers, 185 tests incl. MFA and password reset; the 1-min load average at the start of the run in brackets - a sibling Gradle JVM and the other repos' idle stacks shared the machine; `base` is the master tree, `new` this branch):

     | run | wall (Playwright) | Σ tests | notes |
     |---|---|---|---|
     | base 1 / base 2 | 216 s (2.5) / 217 s (3.1) | 595 / 632 s | base 1 without Mailpit: the two email specs skipped |
     | + minted login only, 1 / 2 | 175 s (3.2) / 160 s (2.0) | 427 / 398 s | |
     | + order and groups, no locks | 137 s (1.5) | 409 s | one red `reports` Costs figure, see above |
     | + order, groups and two locks (holidays, settings) | 142 / 141 / 125 s green (2.5 / 3.7 / 2.0), 143 s red (4.7) | 401 / 417 / 403 / 406 s | the red run: a `reports` drill, `data-sources` overlap |
     | **final (four locks + fixture guard)**, 4 runs | **130 / 130 / 131 / 130 s** (1.0 / 4.6 / 4.9 / 4.7) | 421 / 424 / 423 / 413 s | all 185 green incl. MFA and password reset; `first` ends at ~94 s, the four lock holders run 94-130 s |
     | CPU-poor emulation (14 busy loops beside the stack and the 4 workers): base 1 / 2 | 277 s (2.3) / 252 s (4.9) | 633 / 633 s | `reports` dispatched at 133 s, ends at 275 s |
     | the same, final tree, 2 runs | 154 s (4.8) / 155 s (4.4) | 481 / 479 s | -45 % / -39 % |

     The emulation starves the app and Postgres (the sync chains stretch: `reports`' `beforeAll` 40 s base, 33 s new) more than the host's Chromiums, so it is a stand-in for the runner's shape, not for its speed; it is not a measurement of CI.
   - **Not done 3: `reports` onto the shared fixture.** The premise (a throwaway team for its manager test) is out of date: `reports.spec.ts` has no manager test; what keeps it on its own connection is that its team needs the FLO board mapped to IT (a board belongs to one team, so the shared team's sprints cannot be read through another team), one stub person on its roster (Costs, the one-person picks) and a mutated work-category-targets table - all of which the shared fixture's rule forbids (`reports-compare` also takes stub people for its own throwaway team, and a person belongs to one team globally). Moving them onto the shared team would change four specs' assumptions to save one stub SYNC (`reports`' `beforeAll` is 11-31 s locally, 43-94 s on CI, mostly a latency chain of claim ticks and DERIVEs), and WHY 23's own note says idle slot time is not the bottleneck on CI. Not worth the coupling.
   - **Not done 4: the worker-count re-A/B** - the syncs are not gone (five specs still own one), and 18 cores cannot decide a 4-vCPU question. Only the shape, the two-lock tree (before the last locks), one run each (wall; the CPU-poor emulation's 1-min load was 4.8-5.2, the unconstrained rows had left-over 5-min load ~11 from the emulation runs just before, so read them with that in mind): 3 workers 175 s (emulated) / 158 s, 4 workers 165-166 s / 141-142 s, 5 workers 159 s / 117 s, 6 workers 144 s (emulated). It only says more workers do not hurt when cores are free. **For CI: dispatch `e2e.yml` with `workers` 4, 5 and 6 once the nightly has the levers**, and keep the number whose Σ test time does not grow (the `expect` timeout is 10 s; WHY 23: 5-6 workers made every test slower on the runner).
   - **Not done 5: starting every own-connection SYNC in global setup** (not on the backlog's list; the shared fixture's mechanism extended to `reports`, `capacity`, `public-holidays`, `metrics-config`). The `beforeAll` gaps it would remove are 190 slot-s on CI (10-23) but hidden in idle slots rather than lost CPU, and it would keep five more enabled connections alive from the first second, so every membership, holiday or settings edit of any spec fans a DERIVE out to all of them on the CPU-bound runner. Not measurable here (18 cores); revisit with the CI numbers below.
   - **Not done 6: the lock chain's tail.** After `reports` (94 s locally) the four lock holders run side by side for 36 s, `metrics-config` last. Narrowing the chain needs `metrics-config`'s four tests dealt separately (`parallel`) so only its first test (2 s) holds a lock, which changes their concurrency with each other (two take stub people for their rosters) for ~10-35 s of local tail that CI's longer shared work should hide (the work bound on CI is ~370 s, the chain ~300). Also left: `fullyParallel` on `accessibility.spec.ts` (one file is 290 s of work after the minted login, below a worker's share of the run) and narrowing `reports`' sidebar-opened pages by `connectionId` (Home cannot be narrowed).
   - **Expected CI effect and what to compare.** Σ test time -30 % (the minted login saves a fixed ~1.1 s of CPU per sign-in locally; more on the slower runner), `reports` first and `accessibility-data` dealt over four workers: a `Run E2E` of **~330-400 s against 486-556 s**, with the caveat that WHY 23 predicted -145 s and got -0..-70. On the first nightly or dispatch with it compare with `ci-times.mjs --workflows e2e --steps e2e` (`Run E2E`: 10-21 486 s, 10-22 556 s, 10-23 535 s), and in the run's `playwright-report` artifact: (1) Σ test time (1504-1612 s before; ≤ ~1200 s if the sign-in saving scales), (2) Σ `beforeAll` (282-340 s before; it should not grow), (3) the file that ends the run (was `reports`; expect `metrics-config`, `capacity` or `accessibility-data`) and whether `reports` and `accessibility-data` are no longer a single worker's tail, (4) any retry/flaky mark in the report on a `reports` Costs or aging figure (CI retries 2 times, so a lock that does not hold shows as a flaky mark, not a red run; the overlap failed 2 of 9 local runs before the locks), (5) then the `workers` 5 / 6 dispatches.
  - **CI after** (`e2e.yml` dispatched on the branch, run 40051606105, 4 workers, 185 tests): `Run E2E` **347 s** against the nightlies' 486 / 556 / 535 s (-29 % to -38 %); 184 passed, 1 flaky on its first retry - `reports`' "a user's filters are remembered when they come back" (the URL kept `accountId` but lost the period), the same test that needed a retry on master's nightly of 10-22, so not this change (BACKLOG [new]).
  - **The first nightly after it, and the worker re-A/B (2026-10-24).** The nightly of 10-24 (master e4cf241c, 4 workers) ran `Run E2E`
    542 s with Σ test time 1533 s against 1612 s on 10-23 (-5 %, not the -30 % expected; the minted login was in use, no form fallback),
    `reports`' `beforeAll` 192 s. Dispatched on master 50854a74 (1.16.1): 4 workers three times, 5 workers three times, 6 workers once;
    the runner draw spread identical code 313-515 s, so each run is normalised by its own "Build images" step (the same work at every
    worker count):

    | workers | `Run E2E` (s) | `Run E2E` / build | Σ test time / build | flaky |
    |---|---|---|---|---|
    | 4 | 473, 515, 313 | 2.77, 2.70, 2.46 (mean 2.64) | 9.1, 8.3, 7.7 (mean 8.4) | 1 of 3 runs |
    | 5 | 381, 529, 385 | 2.68, 2.88, 2.52 (mean 2.69) | 9.5, 9.7, 9.3 (mean 9.5, +14 %) | 1 of 3 runs |
    | 6 | 478 | 2.86 | 10.5 (+25 %) | 1 run of 1 |

    **Kept at 4:** five workers make every test ~14 % slower on the 4-vCPU runner and save no wall time; six are worse (WHY 23 again).
    The flaky marks are two `reports.spec` throughput tests (BACKLOG [new]). The 347 s of the branch dispatch was a fast runner.
38. **Home's done-task figures computed in Postgres, not over the rows - ANSWERED 2026-10-24 (the backlog's "Home's medians and percentiles in Postgres"): two aggregate statements replace the listed teams' done-task rows; allocation per overview request -84 % (3 teams, scale B) and -70 % (3 teams, scale A), p50 -30..-35 % (B) and -22..-25 % (A); every response is byte-identical. The first cut reached only -55 % at B and nothing at A: reading statement 2's rows by expression re-rendered its `CASE` and window SQL on every lookup.**
   Question: after WHY 36 the listed teams' done-task rows were still most of an overview (30.1 of 37.1 MiB at scale B, 3 teams, ~8,000 rows), and what was left per row was the transport itself. The figures they feed are counts, exact sums, three PRED shares and the medians and the cycle-time p50/p85: can Postgres compute them bit for bit, and is the request then cheaper?
   - **Decided first** (architect plan, per figure): counts, sums, the findings and the weekly delivery are a `GROUP BY` team, window and Monday week (the weeks via `width_bucket` over Monday starts computed in Kotlin, so no SQL date or zone arithmetic); PRED compares the same float8 relative error the Kotlin kernel computes (`NUMERIC` -> float8 and `BigDecimal.toDouble()` are both correctly rounded; the `CASE` guard is load-bearing because SQL `AND` does not short-circuit), `PRED_EPSILON` in the bound; the percentiles stay `percentileContinuous` in the JVM over the two values at its ranks, which a `row_number()`/`COUNT OVER` statement returns per sample (the ranks are the same float8 `floor`/`ceil` of `f x (n - 1)`; `percentile_cont` itself was NOT used: its interpolation may be FMA-contracted on some Postgres builds, a last-bit difference). The drills keep their rows.
   - **Done: `reports/HomeDoneSql.kt` (the two statements), `HomeDoneFigures.kt` (rows -> figures), `HomeReport.kt` (wired in).** The per-row kernels moved to the test oracle (`HomeDoneOracle.kt`). The two statements are separate READ COMMITTED snapshots: a DERIVE committing between them can skew one against the other, as `unassigned` already can (`reports.md` "Home overview").
   - **Measured** (WHY 34-36's scratch harness, not committed, same worlds A (4,000 done + 2,000 open tasks + 133 epics) and B (12,000 + 6,000 + 400), 10 warm-ups + 30 timed calls with a GC before each, allocation = the sum of `ThreadMXBean.getThreadAllocatedBytes`, one JVM, no HTTP; base = master 50854a74 in a second worktree; base/new/base/new, then the fix twice; 1-minute load 1.7-3.6, no `flow-e2e-*` container):

     | request | scale | base (r1 / r2) | first cut (r1 / r2) | shipped (r1 / r2) |
     |---|---|---|---|---|
     | `home` 3 teams | B | 53.3 / 65.1 ms, 37.1 / 35.2 MiB | 40.3 / 50.9 ms, 16.1 / 16.2 MiB (-55 %) | **34.3 / 45.7 ms, 5.6 / 5.8 MiB (-84 %)** |
     | `home` 3 teams | A | 25.1 / 26.5 ms, 13.9 / 13.2 MiB | 24.5 / 24.8 ms, 13.7 / 13.8 MiB (0 %) | **18.9 / 20.5 ms, 4.1 / 4.1 MiB (-70 %)** |
     | `home` 1 team | B | 24.7 / 32.6 ms, 14.8 / 14.0 MiB | 22.3 / 28.3 ms, 8.2 / 8.3 MiB | **19.7 / 26.4 ms, 4.9 / 5.0 MiB (-66 %)** |
     | `home` 1 team | A | 13.9 / 14.4 ms, 6.0 / 5.8 MiB | 15.3 / 14.6 ms, 6.8 / 6.8 MiB (+15 %) | **12.7 / 12.8 ms, 3.3 / 3.4 MiB (-43 %)** |
     | statement 1 alone (groups), 3 teams | A / B | - | 6.7 / 12.7 ms, 1.0 / 1.0 MiB | 6.5-6.9 / 12.5 ms, 1.0 / 1.0 MiB |
     | statement 2 alone (samples), 3 teams | A / B | - | 11.9 / 18.1 ms, 11.3 / 12.2 MiB | **6.8 / 12.8 ms, 1.4 / 1.4 MiB** |
     | controls: `home/items` `TASK_ESTIMATE`, `WIP_EPICS`, context only | A / B | 7.0-11 ms 3.2-6.9 MiB, 4.3-5.8 ms 1.0-1.3 MiB, 2.5-2.8 ms 0.6 MiB | unchanged | unchanged |

     **Why the first cut stalled:** statement 2 returned ~40 rows for 3 teams (~13 for one) yet allocated ~11 MiB at BOTH scales. A JFR allocation profile of it (`jdk.ObjectAllocationSample`) put ~65 % in Exposed rendering SQL (`quoteIfNecessary`, `QueryBuilder.append`/`toString`) under the row loop's `row[ranked[...]]`: a `ResultRow` lookup hashes its expression by rendering it, and these `CASE` and window expressions cost ~30 KB a render, nine lookups a row. Statement 2 is now built with the DSL, rendered once, run through `exec` and read by column position. Statement 1's grouped rows are read the ordinary way (its expressions are cheap: 1.0 MiB). **The lesson for the next pushdown:** read a statement whose select list holds heavy expressions by position, not by `row[expression]`.
   - **Verified.** (1) Byte-identical: the harness's 122 responses (the hand world's overview at `minSampleSize` 3 and 1 over four pick sets and five teams' nine done-task drills, 98; the scaled worlds' overviews and drills, 24) recorded from the base and from the shipped tree: `diff -r` empty once the random team and connection name suffixes are blanked. (2) `HomeDoneFiguresGoldenTest` grades every figure against the per-row oracle on five seeded random worlds (`HomeDoneRandomWorld.kt`: ties, nulls, `NUMERIC` extremes, the PRED boundary pairs, rows at every window edge and around every Monday midnight +-1 ms across both DST changes; a test pins that the worlds hold samples of 0, 1 and 2 values beside more tasks) at `minSampleSize` 3 and 1, reconciles each figure with its drill, and pins the rank SQL against `percentileRanks` for every n <= 3000 over six fractions (four of them where an exact `NUMERIC` product and the float8 one disagree). Mutation-checked: `>` to `>=` on a sample predicate, `actual_md >= 0` in the PRED guard, NULLS FIRST, `ceil` for both ranks, a `NUMERIC` product in the rank SQL, the `CASE` guard dropped (division by zero), a week bound off by 1 ms, the window edge flipped and the bound without `PRED_EPSILON` each fail a test. `HomeDonePushdownGoldenTest`, `ReportHomeTest` and `ReportHomeDrillTest` pass unchanged.
   - **Not done.** (a) `percentile_cont` in SQL (the FMA risk above). (b) One snapshot for the request (`REPEATABLE READ` for Home's report transaction): a cross-cutting persistence idiom, not needed while no figure sums across the two statements. (c) The drills' rows: one team, the current window, already -65 % since WHY 35.
39. **A fork's cold start moved out of `testApplication`'s 60 s: a pinned warm-up class per fork - ANSWERED 2026-10-24 (the backlog's "A fork-level Postgres warm-up for the first `testApplication` class"): `ForkWarmup<k>Test` is the first class of fork k and pays Postgres + Flyway + one app boot in an untimed `@BeforeClass`; `AnonymousAccessTest`'s first test at `-Pforks=1` 5.4-5.9 s -> 0.6 s, the suite wall unchanged (-4 % / +1 %).**
   Question: Ktor's `testApplication` (3.6.0) runs its body under a hard 60 s limit with no parameter, and the per-JVM Postgres container is lazy, so whichever class first touches it inside a `testApplication` paid container start + Flyway + the first app boot inside the limit (14-15 s cold in the flake sweep, ~4x margin, exceeded at 4x CPU oversubscription by `JiraHttpClientLifecycleTest`). Exposed: `-Pforks=1` (the `gates.sh` and bare `./gradlew :server:test` default: `AnonymousAccessTest` opens the alphabetical order), `-Pforks>=4` (`IngestWorkerTest` opens fork 3) and the no-weights fallback. A fork-wide warm-up, not a per-class `@BeforeClass`.
   - **Decided (architect plan).** The suite is plain JUnit 4 (kotlin-test-junit), and the build already owns the visit order (the balanced deal of WHY 5/18: Gradle deals position p to fork p % forks, in visit order). So positions 0..forks-1 become `ForkWarmup1Test..ForkWarmup{forks}Test`, whose `@BeforeClass` (untimed by JUnit 4) calls `ForkWarmup.warm()`: `PostgresTestSupport.ensureMigrated()` plus one throwaway `TestApplication { configureApp() }` start/stop, with an `app-boot` fixture-cost marker. Eight classes, not one listed eight times: Gradle's failed-first stage keeps definitions in a set keyed by class name, and `TestClassNamingTest` allows one `*Test` class per file. A ledger (`ForkWarmupLedger`) fails a full run unless each pinned warm-up ran once, successfully, in a distinct test JVM. Rejected: the JUnit Platform's `LauncherSessionListener` (a framework migration with vintage, new dependencies and locks, and a container start for every focused run), a static initializer or agent (nothing of ours loads first), a Gradle-daemon-side container (breaks the per-fork container), raising Ktor's limit (no parameter).
   - **Done: `server/build.gradle.kts`** (the pinned head for every forks value, warm-ups excluded from the deal and the surplus slots from the run, the ledger, the missing-class check in the test task's `doFirst`), **`server/src/test/kotlin/ForkWarmup.kt` + `ForkWarmup1Test..8Test.kt`**, `configureApp` widened to `TestApplicationBuilder`; `test-fixtures.md` "Balanced fork order" and "Parallel forks". A `--tests` run never picks a warm-up (no container for a focused run); a failed-first rerun or a `--tests` subset still opens cold (documented).
   - **Measured** (`cleanTest :server:test`, master 50854a74 in a second worktree against this branch, base/new alternating per forks value, two rounds, nothing else running; 1-minute load 1.3-5.4):

     | forks | wall base (r1 / r2) | wall new (r1 / r2) | where `postgres` lands | first test of `AnonymousAccessTest` |
     |---|---|---|---|---|
     | 1 | 385 / 372 s | 368 / 361 s | `AnonymousAccessTest` -> `ForkWarmup1Test` (postgres 3.1 s + app-boot 1.6 s) | 5.86 / 5.35 s -> 0.63 / 0.63 s |
     | 2 | 199 / 202 s | 203 / 203 s | the fixture classes' `@BeforeClass` -> `ForkWarmup1Test`, `ForkWarmup2Test` (3.4-3.5 s + 1.8 s each) | 0.04 s both (not first) |

     The cost moves, it is not added: at `-Pforks=2` the two forks already opened with fixture classes whose `@BeforeClass` paid Postgres (CI's balanced order made them first), so the gain there is the safety for other orders, and the extra throwaway boot shows as +1..4 s (~1 %). `IngestWorkerTest`'s first test stays 0.02-0.05 s in every run.
   - **Verified.** Full runs at `-Pforks` 1, 2 and 3 put every `postgres` marker in exactly `forks` `ForkWarmup<k>Test` XMLs, each with its `app-boot`; `--tests ch.nokillswit.OpenApiSpecTest` produces no warm-up and starts no container; a missing `ForkWarmup2Test.kt` fails the run in `doFirst`; pinning only one warm-up at `-Pforks=2` fails the ledger; a stale build (classes compiled before the warm-ups existed) plans, compiles and pins them (review finding: the first version threw at configuration). `scripts/gates.sh server` green.
   - **Not done.** The JUnit Platform listener, the only mechanism that also covers `--tests` subsets and failed-first reruns: revisit if the suite moves to JUnit 5 for its own reasons.
40. **What the telemetry sampler costs (1.17.0) - ANSWERED 2026-10-24 (the telemetry panel's measurement step): a tick is ~8 ms and 1.3 MiB for the process that holds the cluster section (~5 ms / 0.3 MiB for the others); the heavy collectors take ~0.37 s at ten 6.4k-issue projects (0.3 % of their budget); the panel's reads are <= 80 ms; `ops.*` levels off at ~125 MiB.**
   Question: the sampler (PR #347/#349) runs in every process every 5 minutes, and once per interval one process measures storage and the per-connection estimate (a count per connection over every table with a `connection_id`). Is any of it a measurable cost at production scale (~10 projects the size of the largest real project (A)), and are the read API's queries fine at 90 days of samples?
   - **Measured** (scratch harness, not committed; master 329d1374; one JVM on the shared Testcontainers Postgres 18.6 with defaults; 20 warm-ups + 200 timed ticks, reads 10 + 30, a GC before each; allocation = sum of `ThreadMXBean.getThreadAllocatedBytes`; two rounds, round 2 ~8 % slower under an unrelated host load spike):

     | | wall p50 (p95) | allocation p50 |
     |---|---|---|
     | tick, the process holding the cluster section (queue, retention) | 8.2 ms (11.6) | 1.27 MiB |
     | the same tick with the hourly rollup + prune (1 in 12) | 11.7 ms | - |
     | tick of any other process (cluster section skipped on the marker) | 4.9 ms (5.6) | 0.32 MiB |
     | cluster tick at 10x traffic (15k requests per interval, 32 routes) | 8.9 ms (13.2) | 2.26 MiB |
     | probes alone: JVM / cgroup / latency (1.5k requests) | 0.04 / 0.15 / 0.18 ms | 6 / 10 / 210 KiB |
     | the sample insert (106 rows, one transaction) | 3.6 ms | 0.84 MiB |

     The cost of a tick is its Postgres round trips (the multi-row insert ~8 KiB allocated per row, the process touch, the lock and marker lookups), not the probes. A day of ticks is ~4 s of work across a web and a worker process.
   - **Heavy collectors on a scaled world** (every `raw`/`norm`/`metrics` table with a `connection_id` replicated to project A's size per connection: 6.0k issues, 234k changelog rows, 34.5k worklogs; issue payloads padded to ~40 KB; 10 connections = 13.6M rows, 5.7 GiB, conservative against the ~4.3 GB production estimate):

     | connections | database | estimate p50 (first run) | storage |
     |---|---|---|---|
     | 1 | 0.76 GiB | 67 ms (91) | 2-3 ms |
     | 5 | 2.95 GiB | 202 ms (242) | 2.5 ms |
     | 10 | 5.68 GiB | 367 ms (380) | 2.7 ms |
     | 10, every norm/metrics row just rewritten, no vacuum | | 574 ms (2,274: hint bits) | |

     Linear at ~36 ms per connection; the slowest single count (`raw.jira_changelogs`, 2.35M rows) is ~70 ms against a 30 s statement timeout. The raw tables count index-only (heap fetches 0); the big interval tables (`norm.work_item_field_intervals`, `status_intervals`, `metrics.item_stage`) are parallel heap scans, so their vacuum state hardly matters (contrary to what `observability.md` first anticipated). One pass touches ~880 MiB of pages, all cached here; a cold-disk bound is ~4-5 s, still far inside the 2/5-interval budget.
   - **Reads at steady-state volume** (7 days of raw + 90 days of hourly rows for a web and a worker process with 20 routes = 484k rows; 10 connections x 90 days of SYNC every 15 min and DERIVE every 2 h): `current` 31 ms; the heaviest history reads are `sync` 90d (81 ms) and `requests` 90d (76 ms, 660 KB of JSON); a first panel load is ~135 ms at 24h. `ops.telemetry_samples` at that volume is 124 MiB (52 heap + 72 indexes, ~269 B per row), the retention's ceiling.
   - **On the dev stack after the rebuild** (1.17.0, three real projects, 13k issues, 886 MB): the first estimate took 197 ms, storage 13 ms; working set 177 MB of the 768 MiB compose limit.
   - **Not done (not worth it now):** the marker lookups (`newestRawTs`, the rollup pre-check) filter `resolution < 3600` and read every entry of the metric (1.6-2.4 ms each, three per cluster tick) — an equality prefix would read one; the cold-disk case and a CPU-limited pod were not measured (macOS/OrbStack, 18 CPUs).
41. **Production scale: ~10 6.4k-issue projects (64k issues) - ANSWERED 2026-10-25 (the backlog's "production-scale check"): DERIVE, memory, size and every team-level report page are well inside budget; the one team-level read that is not is Backlog's "touched within" (899 ms alone, 3.0 s p95 at 8 concurrent: one heavy statement per data source); the UNIT-wide wide-period reads, which no page sends, reach 1.4-1.7 s; the backfill lever was the changelog page size (#356).**
   Question: production will hold ~10 projects the size of the largest real project, A (6.4k issues, ~235k histories, ~34k worklogs each). The measurements stopped at scale B (Home) and scale 20 (24k issues, 13 months, stub density). How long is the backfill, what do DERIVE and the reports cost, how big is the database, and do the web (256m in 512Mi) and worker (512m in 768Mi) JVMs fit?
   - **World.** The 2026-10-24 dev backup (before project D) restored into the isolated `flow-perf` project with the production role split (#355; image = master 0aea6255), every data source disabled with its token replaced and no stub service, so no call could leave the machine; real projects A, B and C REPROCESSed (trimmed), then project A copied 8 times by SQL (raw, norm and the per-connection config, account ids suffixed, a team, board, domain and sprint calendar per copy) and DERIVEd: **11 data sources, 64,156 issues, 2.41M histories, 347k worklogs, 24 months of real density and workflows**. The copies' Jira ids (issue, worklog, history, sprint, board, and the parent/sprint values in intervals and field changes) are offset by k x 10^9: on one Jira site every id is unique across data sources, and the first world without the offset over-read 9x wherever a read matches `issue_id` alone (Throughput's actual cost: 40,347 worklog rows, ~150 MiB per request, instead of ~4.5k and ~43 MiB). Laptop, 1-minute load 3-6 from other work, no `flow-e2e-*` containers; 2 warm-ups + 20 timed requests per case from a node script on the host against the web container.
   - **Backfill (Jira-bound, so mostly arithmetic).** ~75 % of a backfill was the changelog fetch at 50 histories per `changelog/bulkfetch` call (~4,700 sequential calls for project A). #356 (1.17.2) asks Jira's default 1,000: ~250-360 calls, so ~15-20 min instead of 46-53 per project A, ~3 h for 10 projects in sequence, less on 2 worker slots if Jira does not throttle two chains (not measured: the user runs two backfills together with the next two projects; a BACKLOG item). The in-app part is small: REPROCESS (TRIM + PROCESS + PROFILE) 36.4 s for project A, 37.0 s for B, 18.5 s for C.
   - **DERIVE** (worker, 2 slots): one 6.4k-issue data source 8.2-8.9 s warm (3 runs); two at once 9.4-11.3 s each (1.1-1.3x); all 11 queued 91-101 s in total (5.0-14.9 s each), the 8 copies' first derives 61-71 s; no FAILED run. Worker working set <= 253 MiB of 768, heap used <= 103 MiB, old-gen peak 74 MiB. Against the plan thresholds (warm <= 60 s, pair <= 1.5x, storm <= 5 min, <= 550 MiB): all pass by 4-7x. The unit-wide parts (ANALYZE, the context reads) did not show up.
   - **Database size.** After `VACUUM (FULL, ANALYZE)` **3.0 GB = 47 MB per 1,000 issues**: raw 1,878 MB (`raw.jira_changelogs` 1.33 GB of it, 2.41M rows), norm 611, metrics 495, public 2, ops 0.5 MB; uncompacted after REPROCESS and derives 3.3-4.4 GB. Growth is history-driven, ~1 GB a year at today's pace (estimate). The 20Gi claim (#354) holds it with room. The telemetry size estimate takes 676 ms at this size (WHY 40: 367 ms on the padded 10-connection world).
   - **Reports, sequential** (p50 / p95 ms). TEAM = the Onboarding team, *wide* = 24 months:

     | case | TEAM default | TEAM wide | UNIT wide |
     |---|---|---|---|
     | `throughput` | - | 116 / 120 | **1,571 / 1,705** |
     | `wip` | - | 138 / 145 | **1,351 / 1,428** |
     | `data-quality` | 122 / 143 | 139 / 159 | 666 / 727 |
     | `commitments` | - | 44 / 49 | 312 / 326 |
     | `costs` | - | 82 / 96 | 90 / 97 |
     | `cycle-time`, `reported-time-ratio`, the accuracy reports | <= 20 | <= 23 | 185-243 |
     | `backlog` with `touchedWithinDays=90` | **806 / 899** | | |

     Also: Home 1 team 58 / 68, 3 teams 61 / 71; `home/items` <= 13; `aging-wip` TEAM 14 / 27, UNIT 38 / 41; Compare people (two busy people) 90 days 78 / 96, wide 290 / 317, its WIP drill 290 / 312; Deep dive 3 sprints 18 / 26, 10 epics 44 / 59; `filters` 44 / 49; a USER-level read <= 68. Every other TEAM case is under 50 ms.
   - **8 concurrent** (10 rounds, p50 / p95 ms): Backlog touched **2,954 / 3,032**; data-quality TEAM wide 382 / 438; Compare people wide 388 / 472; Throughput TEAM wide 351 / 411; Costs TEAM wide 277 / 326; WIP TEAM wide 241 / 271; Home 3 teams 204 / 229. Postgres reached ~845 % CPU, the web ~385 %. Only Backlog breaks the 1.5 s line; most cases are 2-3.7x their single time, all others under 0.5 s.
   - **During a DERIVE storm** (8 derives on the worker): every heavy TEAM case within 1.2x idle (Backlog 657, data-quality 174, Throughput 340, Home 53 ms p95), and the storm took its idle 71 s.
   - **Memory and allocation (web).** Working set <= 284 MiB of 512 (cgroup peak 400 MiB under the over-reading first world), heap committed <= 112 MB of 256, GC ~3 % of the sweep. Allocation per request (young-GC log over 20 requests): Throughput TEAM wide ~43 MiB, UNIT wide **~661 MiB**; data-quality TEAM wide ~34, UNIT wide ~177 MiB; Compare people wide ~34; Costs ~32; Home 3 teams ~21; WIP 11-18; Backlog ~3 (its cost is in Postgres).
   - **Why Backlog "touched within" is slow:** 90 statements and 734 ms in Postgres per request, one `touchedBacklogSql` per data source in scope (`reports/BacklogTouches.kt`): each builds its connection's day x backlog rows BEFORE the owner-team filter, so a team request pays for every data source. **Method note:** JFR could not attribute the web's allocation (the coroutine frames are off the stack while R2DBC decodes rows); `pg_stat_statements` ranked by `rows` named the statement in one request.
   - **Conclusion.** Production scale fits: DERIVE, memory and size have large margins, every page a user opens answers in well under its budget alone, and the backfill is Jira-bound (now ~3x shorter). Queued as `[new]` BACKLOG items: (a) Backlog "touched within" per data source; (b) the UNIT-wide wide-period reads (API-only: a guard or a pushdown); (c) the REFERENCE users pass on every SYNC of every data source (an estimated ~47 of the ~74 calls an incremental SYNC makes); (d) two backfills together on the real tenant (the user); (e) `fetchBulk` follows `nextPageToken` without a page ceiling (security review of #356, pre-existing).
   - **Not done:** cold caches, a mixed many-user load, and the real tenant's concurrency (d). The harness (clone/offset SQL, sweep and allocation scripts) is not committed, as in WHY 34-40.
   - **Follow-up (d), 2026-10-25, 1.17.4 on the dev stack:** the user created two new data sources 3 s apart, so the 2 worker slots ran both backfills together: 2,760 issues / 108,295 histories / 15,839 worklogs in **4.8 min** and 1,766 / 60,680 / 8,298 in **3.5 min**, **zero** 429s, back-off or pauses (no `jira*` key on either job); DERIVE 6.7 s and 3.4 s. The histories arrived at ~60k a minute on one slot (project D alone at 50 per page: ~8.4k), so the page size (#356) did what the arithmetic said; the per-issue worklog backfill is now the longest step (~3 of the first one's 4.8 min). By extrapolation an 6.4k-issue backfill is ~10 min and 10 projects two at a time well under an hour; the tenant showed no sign of throttling two chains.
42. **Backlog "touched within" at production scale - ANSWERED 2026-10-25: a team request now reads only the data sources where the team owns a domain; 720 -> 74 ms p95 alone, 3,284 -> 77 ms p95 at 8 concurrent, the 24-month period 3.7 s -> 481 ms.**
   Question: WHY 41 found Backlog with `touchedWithinDays` the one team-level read over budget under load (899 ms alone, 3.0 s p95 at 8 concurrent), because `readTouchedBacklog` ran `touchedBacklogSql` for every data source in scope and each statement built its connection's day x backlog rows before the owner-team filter. Does skipping the data sources that cannot answer fix it?
   - **The change.** At TEAM level `connectionsToRead` (`reports/BacklogTouches.kt`) keeps only the connections with a `metrics.dim_domain` row the team owns, one primary-key-sized read before the loop. Exact: the statement keeps a row only when `COALESCE(owner_team_id::text, 'UNOWNED')` equals the team id, which needs such a row; `ReportBacklogTest` pins the skip and that the answer equals the owning connection's alone. UNIT, `teamId=0` and a domain slice still read every connection (no ownership names their rows).
   - **World.** WHY 41's recipe on the 2026-10-25 dev backup (`flow-before-8e50ef20`, after project D): real projects A, B, C and D plus 8 id-offset copies of A, **12 data sources, 68,516 issues**, tokens replaced, every data source disabled, no stub; the copies DERIVEd in 60 s. Before = master 93c19b5f, after = this change, the same database, the web container rebuilt in between; 2 warm-ups + 20 timed requests (8 concurrent: 10 rounds of 8). Laptop load 3-11 from other work.
   - **Results** (p50 / p95 ms, TEAM = the Onboarding team, which owns domains in one data source):

     | case | before | after |
     |---|---|---|
     | `backlog` TEAM `touchedWithinDays=90` | 703 / 720 | **68 / 74** |
     | the same, 8 concurrent | 3,225 / 3,284 | **71 / 77** |
     | the same, 24 months | 3,640 / 3,698 | **281 / 481** |
     | `backlog` DOMAIN (project A) `touchedWithinDays=90` (unchanged path) | 144 / 152 | 166 / 180 |
     | `backlog` UNIT `touchedWithinDays=90` (unchanged path) | 730 / 755 | 760 / 793 |
     | `backlog` without the filter, TEAM / UNIT (unchanged) | 11 / 15, 11 / 13 | 7 / 8, 9 / 11 |

     The first after-pass of the unfiltered cases read 37-54 ms p50 on a freshly started web JVM (two warm-ups are not enough after a rebuild); the re-run above is the steady state.
   - **Why the domain slice was already fast:** its `r.domain_key = ?` filter is pushed down into the inlined `rows` CTE; the TEAM filter goes through a LEFT JOIN on `dim_domain` and a `COALESCE`, which the planner cannot push. A TEAM predicate rewritten as `r.domain_key IN (the team's domains)` would push down too; not needed at these figures.
   - **Not done:** the UNIT-level touched read (760 ms; API-only, added to the BACKLOG item on the UNIT-wide reads); a domain slice still visits every data source (~100 ms of its 166).

## Review cadence

- Run `node scripts/timings/ci-times.mjs --branch master` at every milestone / PR, and in every
  checkup; run `local-times.mjs report` when a local gate feels slower.
- A `TREND` flag or a budget breach becomes a dated investigation entry in this doc (WHY, evidence,
  outcome) — **never just a raised budget.** A budget changes only with the measurement that justifies
  it, in the same commit as the edit to `scripts/timings/budgets.json` and the table above.
- A new gate or CI job gets a budget row before it lands.
- The `slow-tests` output of the CI job summary is the first stop when the `server` job regresses:
  a new class or case near the top of the list is almost always the cause.
- Refresh `server/test-weights.tsv` (after a full run: `node scripts/timings/local-times.mjs test-weights`, one
  command) whenever the CI summary's fork-balance block warns and at every milestone check; a class missing from
  the file weighs the average until then.
