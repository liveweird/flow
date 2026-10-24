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

- [parked] **`epic_domain_key` as-of.** Today it uses the epic's current domain, because no epic-domain history
  exists. Real data is in now; evaluate whether a parent move across projects shows up in COOK's changelog.
  - Evaluated 2026-10-09: COOK's changelog (2025-07-23 → 2026-09-25) has NO `project` or `Key` change on any issue.
    Its 100 `IssueParentAssociation` changes are re-parentings inside the project (30 tasks, 4 epics under the
    Program), which never change an epic's domain. Nothing to fix on real data; a candidate for [parked].
  - Parked by the user 2026-10-23: nothing to fix on real data.
- [parked] **Read `hoursPerDay` from Jira's time-tracking configuration (A5).** Real data is in now; check the
  endpoint and its scope on the tenant.
  - Parked by the user 2026-10-23: Flow's own `hoursPerDay` setting is enough unless the tenant uses a non-8-hour day.
- [parked] **Cache validators for the report endpoints.** Draft PR #75 (ETag/304; its atomic DERIVE success mark shipped
  separately, so the draft now conflicts in `metrics/`) is open, waiting on the cache-posture sign-off. Compare people's capacity rows depend on the caller (ADMIN or the
  managers of every team involved; the Cost matrix's capacity column that shared this caveat was removed), so a report ETag must
  include that verdict for `/reports/compare-people` (or exempt it), or a 304 would serve the rows to someone else.
  - Parked by the user 2026-10-23 while report latency is fine; draft #75 stays open untouched.
- [blocked] **Sprint capacities from Jira Plans.** Probed 2026-10-08: the Plans REST API (`/rest/api/3/plans/plan`, then
  `…/team/atlassian/{id}` for `capacity`, `planningStyle`, `sprintLength` and `issueSourceId`) returns 403 to the sync
  service account: "You do not have the Administer Jira global permission". Every Plans endpoint needs it, reads included.
  - No-go while that holds: a read-only sync token is not escalated to Jira admin for one figure per team.
  - Still unknown: whether the site has Premium, whether COOK's plan team has a capacity set, and in which unit.
  - Even with access: the capacity is in the plan's estimation unit (story points can't be converted to MD; hours need
    A5), it is one figure per team rather than per sprint, and the API is experimental.
  - If Atlassian drops the admin requirement: an optional REFERENCE step (like `PROJECT_FIELDS`), plan team →
    `issueSourceId` board → `board_team_map`, and a `PLAN` capacity source ranked CONFIGURED > PLAN > DEFAULT.
  - Blocked (2026-10-23) on the Administer Jira requirement.
- [parked] **A28 — a composite estimate for estimated backlog.** Parked 2026-10-01: the teams do not estimate via
  sub-tasks.
  - Estimated backlog uses the OWN estimate only, so a parent estimated through its sub-tasks
    (`estimate_source = SUBTASKS`) is missing from it.
  - The fix is a composite-estimate bridge. Velocity (`sprintScope`) needs the same composite, or backlog-in-sprints
    is overstated.
  - Number the amendment A28 (A23 is taken).

## Engineering follow-ups

- [new] **Home's medians and percentiles in Postgres.** After WHY 36 the listed teams' done-task rows are still most of a Home
  overview's allocation (~3.7 KiB per row, ~30 MiB at scale B for 3 teams); what remains is the row transport itself. The next lever
  is computing the figures (cycle-time percentiles, the estimate/cost medians and sums) in SQL instead of shipping the rows: its own
  oracle per figure, and the kernels it would move are shared with the drills (build-times WHY 35/36 "Not done").
- [new] **Report the two r2dbc-postgresql 1.1.3 bugs upstream (needs the user: a public action).** Found 2026-10-23 (build-times WHY 24):
  `ReactorNettyClient.BackendMessageSubscriber.demandMore()` checks "no buffered items" and CASes the demand in two steps, so a
  preempted consumer re-requests 256 rows onto a nearly full buffer ("Response queue is full", reproduced with a 300 µs park); and on that
  error `onError` completes the request sink, `handleClose()` sets `isClosed` first, so `close()` is a no-op and the socket/backend leak
  (Flow's `LeakedSocketGuard` closes it; its `LeakedSocketGuardTest` turns red when upstream fixes the leak, and the guard can go).
  Draft issue text: the description of PR #311. The only Flow-side cure for the overflow itself
  (a fetch size < ~250 rows) costs a round trip per statement and is not taken.
- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - Last checked 2026-10-23 (1.14.3): `server` median-5 14m12s (OVER the 10-min alarm), `web` 3m27s (TREND +33 %, over its 3-min alarm) —
    mostly the runner draw (identical code 1.7x apart; 16 of 23 master runs on 10-23 drew a slow runner), real growth ~5 % (server) /
    ~12 % (web tests); weights refreshed, CI runs `check :server:installDist`; the alarm on a slow runner needs a paid 8-vCPU runner (the
    user's call) — `build-times.md` status check 2026-10-23. 2026-10-21 (1.6.1): `server` median-5 10m02s (on its 10-min alarm), `web` 3m12s (TREND +43 %, over its 3-min alarm) —
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
- [new] **A fork-level Postgres warm-up for the first `testApplication` class.** The flake sweep (2026-10-24) measured a fork's
  first `testApplication` class paying Postgres start + Flyway + app boot (~14-15 s cold) inside Ktor's hard 60 s limit: a ~4x
  margin, exceeded only at 4x CPU oversubscription (`JiraHttpClientLifecycleTest`). A fork-wide warm-up (not a per-class
  `@BeforeClass`) would remove it.

## Checkups — what is left (record: `.claude/docs/audit-status.md`)

- [blocked] **C6 — an OTLP exporter for `KtorServerTelemetry`.** Decided 2026-10-23: keep the telemetry, a collector is
  coming. Blocked until the collector exists; then wire the exporter (endpoint by env, off when unset).
- [next] **The nightly e2e after WHY 37: the worker count on CI.** WHY 37 took the isolated local `Run E2E` from 216 s to 130 s
  (a minted login, long chains first behind four locks); the nightly after it shows the CI effect. Then dispatch `e2e.yml` with
  `workers` 5 and 6 and keep the count whose Σ test time does not grow. `reports.spec` stays off the shared fixture: it needs its own
  board mapping, roster person and work-category targets (the "manager test" premise was out of date).
- [new] **A1 — protect `master`.**
  - The required checks: `server`, `web`, `e2e-static`, `gradle-vulnerability-scan`, `k8s-static`.
  - No bypass.
  - The user's decision.
- [new] **A13 — the Kubernetes front door.** The user decides between:
  - a TLS-terminating Ingress + ClusterIP Service;
  - a documented local-only overlay.

  Behind today's bare LoadBalancer, `X-Forwarded-For` is client-supplied.
- [blocked] **2C8 — the Polish glossary pass.** A judgement call that needs a native speaker.

## Security and operations

- [parked] **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory.
  - That's safe because the web Deployment runs one replica with Recreate.
  - Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- [todo] **Atlassian API-token quota: re-check developer.atlassian.com/changelog before releases.**
  - API-token traffic is excluded from the 2026 points-based rate limits.
  - Atlassian is evaluating a quota for tokens "with advance notice".

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
