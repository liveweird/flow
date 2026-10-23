# Product backlog

Updated 2026-10-08. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/`.

Entries are proposals, not delivery commitments.

**Status tags.** Every top-level item under a `##` section opens with one tag:
- `[next]`: what comes next;
- `[todo]`: confirmed, to do;
- `[new]`: proposed, not yet confirmed. That includes the decisions only the user can take. Claude adds items as
  `[new]`, and only the user promotes them;
- `[parked]`: deliberately set aside until a trigger;
- `[blocked]`: waiting on someone or something else.

The `backlog` mod (`liveweird/claude-mods`) summarises the tags on the status line and in `/backlog`.

## After the first real sync (needs the user)

The first real-Jira sync works end to end (2026-10-08, #112–#116): SYNC, PROCESS, DERIVE and RECONCILE on COOK.

- [new] **Confirm the search page-size ceiling** on a project with more than 100 issues. COOK's 46 issues fit on one
  page, so the real tenant hasn't shown it yet.

## Phase 3 follow-ups (the domain model, v0.3.0)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A27 override the body.

- [new] **`epic_domain_key` as-of.** Today it uses the epic's current domain, because no epic-domain history
  exists. Real data is in now; evaluate whether a parent move across projects shows up in COOK's changelog.
  - Evaluated 2026-10-09: COOK's changelog (2025-07-23 → 2026-09-25) has NO `project` or `Key` change on any issue.
    Its 100 `IssueParentAssociation` changes are re-parentings inside the project (30 tasks, 4 epics under the
    Program), which never change an epic's domain. Nothing to fix on real data; a candidate for [parked].
- [new] **Read `hoursPerDay` from Jira's time-tracking configuration (A5).** Real data is in now; check the
  endpoint and its scope on the tenant.
- [new] **Cache validators for the report endpoints.** Draft PR #75 (ETag/304 + an atomic DERIVE success mark) is
  open, waiting on the cache-posture sign-off. Compare people's capacity rows depend on the caller (ADMIN or the
  managers of every team involved; the Cost matrix's capacity column that shared this caveat was removed), so a report ETag must
  include that verdict for `/reports/compare-people` (or exempt it), or a 304 would serve the rows to someone else.
- [new] **Sprint capacities from Jira Plans.** Probed 2026-10-08: the Plans REST API (`/rest/api/3/plans/plan`, then
  `…/team/atlassian/{id}` for `capacity`, `planningStyle`, `sprintLength` and `issueSourceId`) returns 403 to the sync
  service account: "You do not have the Administer Jira global permission". Every Plans endpoint needs it, reads included.
  - No-go while that holds: a read-only sync token is not escalated to Jira admin for one figure per team.
  - Still unknown: whether the site has Premium, whether COOK's plan team has a capacity set, and in which unit.
  - Even with access: the capacity is in the plan's estimation unit (story points can't be converted to MD; hours need
    A5), it is one figure per team rather than per sprint, and the API is experimental.
  - If Atlassian drops the admin requirement: an optional REFERENCE step (like `PROJECT_FIELDS`), plan team →
    `issueSourceId` board → `board_team_map`, and a `PLAN` capacity source ranked CONFIGURED > PLAN > DEFAULT.
- [parked] **A28 — a composite estimate for estimated backlog.** Parked 2026-10-01: the teams do not estimate via
  sub-tasks.
  - Estimated backlog uses the OWN estimate only, so a parent estimated through its sub-tasks
    (`estimate_source = SUBTASKS`) is missing from it.
  - The fix is a composite-estimate bridge. Velocity (`sprintScope`) needs the same composite, or backlog-in-sprints
    is overstated.
  - Number the amendment A28 (A23 is taken).

## Engineering follow-ups

- [new] **Decide draft PR #294: `fact_worklog` by one `INSERT … SELECT`.** The last of the four Postgres pushdowns (the other three
  merged: #291, #292, #293). It works (byte-identical at scale 20; an Opus review recommends merging), but the gain is GC load, not
  speed (−720 MB allocated per DERIVE, −15 %; DERIVE wall time and peak within noise), and the attribution fallbacks would live in
  SQL as well as in the Kotlin kernels. Merging it also closes the [next] pushdown item.
- [new] **`WorkingCalendar.workingDaysBetween` walks every calendar day.** Found by the Aging WIP pushdown (2026-10-23, build-times
  WHY 29): after the SQL cap, a capped Aging WIP request still allocates ~100 MiB, almost all of it computing the 500 listed items'
  ages one day at a time (a `LocalDateTime` and boxed ints per day since `started_at`; JFR). A per-day prefix sum of working time
  (or whole-week arithmetic between the holiday set) would make an age O(1) after a one-off table; it must stay equal to the current
  rule (A33 holidays, partial first/last days) — pin with the existing calendar tests plus a randomised equality test.
- [new] **Home still loads every open item.** `HomeReads` uses `fetchOpenTasks`/`fetchOpenEpics` (all open items of the picked
  teams) for its WIP/aging figures; the Aging WIP pushdown (#292) left it alone. Measure first (Home is one request for up to three
  teams); push the aggregation into SQL if it shows in the allocation profile.
- [new] **The R2DBC "Response queue is full" CI flake: three occurrences, cause unknown.** 2026-10-17 (`DimDateContentionTest`),
  2026-10-22 (`ReportDrillAccuracyBlockedTest`, then a lock wait in `PublicHolidayMigrationTest`) and 2026-10-23 (`MetricsAnalyzeTest`,
  then a 5-minute hang of the next test on the same shared clone), all `ResponseQueueException: [08006]` ← `PostgresConnectionException`
  on a CI runner, all green on the re-run and never reproduced locally (build-times WHY 24). Find what overflows the driver's response
  queue (a slow consumer of a large streamed result? a backpressure setting?) before it becomes a production symptom; and why the failed
  connection's backend stays `idle in transaction` holding locks (the driver leaves the socket open?) — in production that would be a
  stuck lock behind every later DERIVE of the connection. Next occurrence: the failing test now prints the session list.
- [new] **A drill link without a team loses its drill on a team-required report.** Found 2026-10-22 by a test that failed 6 in 10
  shuffled runs. A hand-typed or pre-1.7.0 link to a team-required report with `drill=` and no `teamId` is normalised to the default team;
  that filter change makes `useReportDrill` (`hooks/useReportDrill.ts`, "A changed filter closes an open drill") drop the `drill`
  param, racing `useReportFilter`'s own rewrite. Links the app writes always carry the team, so only old/hand-made links are hit. Fix:
  let the drill survive the one-time team normalisation (compare the filter without the injected team), with a test per team-required
  report; the cost-matrix test now passes its team explicitly.
- [new] **Concurrent worklog fetches in the backfill.** Since the batched raw writes (`build-times.md` WHY 21) the stub SYNC is
  ~4.6 s, ~2 s of it the 1,201 sequential per-issue `GET /issue/{id}/worklog` calls. Fetching a claimed batch with the existing
  `maxConcurrentRequests` semaphore (`JiraHttp.kt`, default 4) would cut that, but it changes the request pattern against the real
  tenant (rate limits, the A1 worklog scope). Decide after the first real-Jira backfill shows its own figure.
- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - Last checked 2026-10-21 (1.6.1): `server` median-5 10m02s (on its 10-min alarm), `web` 3m12s (TREND +43 %, over its 3-min alarm) —
    both the runner draw (bimodal fast/slow runners; `build-times.md` status check 2026-10-21); WHY 20-23's "CI after" filled in;
    `web` ~10-20 s over its alarm on slow runners is accepted. 2026-10-18 (1.0.0): `server` median-5 12m09s (+52 %, over the 10-min alarm), `web` 3m28s (over its 3-min
    alarm), `images` 3m08s — runner-speed draw plus ~10 % server / ~20 % web suite growth (`build-times.md` WHY 19). 2026-10-17:
    `server` 13m41s (WHY 18: runner speed, suite growth, fork imbalance — the imbalance is fixed). 2026-10-15: `server` 6m36s; no jump. The nightly `e2e`
    had been red since 10-07 on a runner change (buildx bake fs entitlements), fixed the same day.
  - Where it stands:
    - `server` takes 8 min on a fast runner and 13–14 on a slow one (from ~30 on 2026-09-30);
    - `web` takes ~3 min;
    - the CI timeout is 25 min.
  - Budgets, history and the open WHY questions are in `.claude/docs/build-times.md`. Local runs use
    `scripts/gates.sh`.
- [parked] **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or
  unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- [parked] **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).**
  - Today these are Jira-shaped:
    - `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`;
    - `JiraConnectorKey` in `DataSourceRoutes.kt`;
    - `DataSourceRequest.jira`.
  - Generalising them is speculative until a second connector exists. Revisit with GitLab.

## Checkups — what is left (record: `.claude/docs/audit-status.md`)

- [new] **Checkup 3, decisions for the user.**
  - **C5/B1 — 32-bit ids on 11 rewritten tables (medium, D9-3).** `id SERIAL` (int32) on the DERIVE/PROCESS tables that are deleted
    and re-inserted whole (V13/V16); sequences never reset → ~1.6-2.4 years to exhaustion at hourly syncs (6-9 months at 15-min),
    then every DERIVE fails. Decision: may I read the sequences' `last_value` on the dev database (read-only), and ship the
    bigint migration now or after the pushdowns?
  - **C8 — refresh-token rotation (D7-1).** `/refresh` leaves the old pair valid; `/logout` revokes only the presented pair, so a
    stolen refresh token outlives the victim's sign-out. Decision: rotation (blocklist the presented jti, reuse → rejected),
    sign-out-everywhere (`credential_revision` bump), both, or the status quo (the doc will be corrected either way, A15).
  - **C2/B5 — e2e at PR time (D5-1, D8-5).** A deterministic e2e regression went unseen for two nights. Decision: a PR-time smoke
    subset (~3-8 min per PR), or nightly + an automatic GitHub issue on failure (outward-facing on the public repo), or both.
  - **C3 — Spectral posture (D8-3).** 14 warn-level rules can never fail the build. Decision: a Flow-local ruleset extending the
    shared one + `--fail-severity=warn`, or leave warnings advisory.
  - **C4 — spec version (D10-2).** `info.version` is still 1.0.0. Decision: track `APP_VERSION` (test-pinned) or a stated contract version.
  - **C6 — telemetry (D9-7).** OTLP exporter behind env, or remove `KtorServerTelemetry`. Decision: is a collector coming?
  - **C7 — the Feature flags page (D3-2).** A 343-line component for a one-value enum (`MFA`). Decision: keep, or simplify.
  - **A11 — drop the legacy `metrics.team_sprint_capacity` (D3-5).** Unused since V24; a V33 `DROP TABLE` is irreversible. Decision: drop.
  - **A16 — a rate bucket on the anonymous `/api/v1/ready` (D7-2, low after a live flood).** Decision: add (k8s probes stay far
    under it) or leave unbucketed.
- [new] **The nightly e2e: the next levers after WHY 23.** Heavy-first dispatch (a second Playwright project listed first, -20-40 s
  simulated), a minted login (port Lettuce's `e2e/sessions.ts`, -15 s), `reports.spec` onto the shared fixture (a throwaway team for its
  manager test), and a worker-count re-A/B once the syncs are gone. Measured 2026-10-21: `Run E2E` 486 s on the nightly (182 tests),
  not the ~370 s simulated (`build-times.md` WHY 23 "CI after"): re-measure the per-file `beforeAll` gaps first.
- [new] **A1 — protect `master`.**
  - The required checks: `server`, `web`, `e2e-static`, `gradle-vulnerability-scan`, `k8s-static`.
  - No bypass.
  - The user's decision.
- [new] **A13 — the Kubernetes front door.** The user decides between:
  - a TLS-terminating Ingress + ClusterIP Service;
  - a documented local-only overlay.

  Behind today's bare LoadBalancer, `X-Forwarded-For` is client-supplied.
- [blocked] **2C8 — the Polish glossary pass.** A judgement call that needs a native speaker.
- [new] **2D3 — password reset by confirm-link.** Today anyone who knows an email can force one rotation per 60 s
  (MFA still applies). The user's decision.
- [new] **2D4 — a per-request `credential_revision` check.** Today a delete or demote takes effect within the
  15-minute access-token window. The user's decision.
- [new] **A per-user report rate bucket.** The statement timeout bounds one query, not a stream of them. The user's
  decision.
- [new] **A PR-time image scan** (~3 min per PR). The nightly scan is the compromise. The user's decision.

## Security and operations

- [parked] **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory.
  - That's safe because the web Deployment runs one replica with Recreate.
  - Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- [todo] **Atlassian API-token quota: re-check developer.atlassian.com/changelog before releases.**
  - API-token traffic is excluded from the 2026 points-based rate limits.
  - Atlassian is evaluating a quota for tokens "with advance notice".
- [blocked] **Dependabot #195, OpenTelemetry 1.67.** Held until an `opentelemetry-instrumentation-bom-alpha` release
  pairs with it (2.32.0-alpha, the newest, still pairs with 1.66.0 — `gradle/libs.versions.toml`).

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
