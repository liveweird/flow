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

The first real-Jira sync works end to end (2026-10-08, #112–#116): SYNC, PROCESS, DERIVE and RECONCILE on the first real project.

## Phase 3 follow-ups (the domain model, v0.3.0)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A27 override the body.

- [next] **Epic-level facts as-of, the rest (A49).** 1.32.0 made the epic's own fact, its WIP bands and A34's DONE band
  as-of; report 15's per-domain epic list still shows a moved epic once, under its current domain, at its full value
  (split rows with each domain's share, `partial`, next release).
- [blocked] **Sprint capacities from Jira Plans.** Probed 2026-10-08: the Plans REST API (`/rest/api/3/plans/plan`, then
  `…/team/atlassian/{id}` for `capacity`, `planningStyle`, `sprintLength` and `issueSourceId`) returns 403 to the sync
  service account: "You do not have the Administer Jira global permission". Every Plans endpoint needs it, reads included.
  - No-go while that holds: a read-only sync token is not escalated to Jira admin for one figure per team.
  - Still unknown: whether the site has Premium, whether that project's plan team has a capacity set, and in which unit.
  - Even with access: the capacity is in the plan's estimation unit (story points can't be converted to MD; hours need
    A5), it is one figure per team rather than per sprint, and the API is experimental.
  - If Atlassian drops the admin requirement: an optional REFERENCE step (like `PROJECT_FIELDS`), plan team →
    `issueSourceId` board → `board_team_map`, and a `PLAN` capacity source ranked CONFIGURED > PLAN > DEFAULT.
  - Blocked (2026-10-23) on the Administer Jira requirement.

## Engineering follow-ups

- [new] **Report the two r2dbc-postgresql 1.1.3 bugs upstream (needs the user: a public action).** Found 2026-10-23 (build-times WHY 24):
  `ReactorNettyClient.BackendMessageSubscriber.demandMore()` checks "no buffered items" and CASes the demand in two steps, so a
  preempted consumer re-requests 256 rows onto a nearly full buffer ("Response queue is full", reproduced with a 300 µs park); and on that
  error `onError` completes the request sink, `handleClose()` sets `isClosed` first, so `close()` is a no-op and the socket/backend leak
  (Flow's `LeakedSocketGuard` closes it; its `LeakedSocketGuardTest` turns red when upstream fixes the leak, and the guard can go).
  Draft issue text: the description of PR #311. The only Flow-side cure for the overflow itself
  (a fetch size < ~250 rows) costs a round trip per statement and is not taken.
- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - Last checked 2026-10-30 (1.31.0): `server` median-5 16m22s (prev-5 12m53s, so a TREND +27 % flag; 30 master runs 10-28..10-30; min 9m18s,
    max 18m04s), `web` 3m42s, `images` 3m03s — no new jump: the median equals the 1.28.0 check and the last 14 runs are the same two modes
    (fast 629-773 s, slow 981-1084 s; the slow mode was 982-988 s on 10-29), so the flag is the window moving across the draw; `server` and `web`
    still over their alarms; nightly `e2e` median-5 12m49s.
    2026-10-30 (1.28.0): `server` median-5 16m21s (prev-5 16m22s; 30 master runs 10-27..10-29; min 9m18s, max 18m04s), `web` 3m20s,
    `images` 3m49s — no new jump, the runner draw (fast 9-11 min, slow 16-18); suite ~2,280 tests; `server` and `web` still over their alarms;
    nightly `e2e` median-5 12m49s.
    2026-10-29 (1.26.0): `server` median-5 14m54s (prev-5 12m52s; 30 master runs 10-24..10-29; latest 17m11s, the max), `web` 3m56s,
    `images` 3m39s — the runner draw: the 280 classes common to a fast run (597 s Gradle step) and a slow one (1020 s) slowed by one factor, 1.56x
    (886 → 1386 s summed). Real growth +34 tests / 4 classes, of which `JiraProcessBudgetTest` (1.24.2) took 27-43 s: its one-issue-per-page walk
    over every stub issue re-rendered 50 candidates per claim — bounded to 12 claims (1 s locally). `server` and `web` still over their alarms.
    2026-10-28 (1.22.1): `server` median-5 13m00s (prev-5 15m14s; 30 master runs 10-24..10-28), `web` 3m38s, `images` 3m39s —
    no new jump, still the runner draw: fast runs 8m30s-11m, slow ones 13m53s-15m27s (was 13-14), so ~5-10 % suite growth since 1.16.1
    (2,009+ tests); `server` and `web` over their alarms; the nightly `e2e` green since 10-25 (its 14 failures are the 10-24 throughput-flake runs).
    2026-10-24 (1.16.1): `server` median-5 11m19s (prev-5 13m38s), `web` 3m36s (prev-5 3m32s), `images` 3m33s — both still
    over their alarms on the runner draw, no new jump. 2026-10-23 (1.14.3): `server` median-5 14m12s (OVER the 10-min alarm), `web` 3m27s (TREND +33 %, over its 3-min alarm) —
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

## Checkups — what is left (record: `.claude/docs/audit-status.md`)

- [blocked] **C6 — an OTLP exporter for `KtorServerTelemetry`.** Decided 2026-10-23: keep the telemetry, a collector is
  coming. Blocked until the collector exists; then wire the exporter (endpoint by env, off when unset).
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
  - Last checked 2026-10-30 (1.31.0): no token quota announced (the only new entry, a Forge custom-field UI RFC, does not touch Flow).
    2026-10-30 (1.28.0): no token quota announced (entries through 10-28: Bitbucket SSH, Confluence byline, org-level app
    sharing, an MCP tool promotion — none touch Flow's endpoints). 2026-10-29 (1.26.0): no token quota announced (entries through 10-28; the 10-27 `portfolioChildIssuesOf()` JQL change
    does not touch Flow, which never sends it). 2026-10-28 (1.22.1): the 2026-10-20 Tier 2 clarification covers apps (per-tenant quotas), not tokens.

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
