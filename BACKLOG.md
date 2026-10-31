# Product backlog

Updated 2026-10-31. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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
  - Last checked 2026-10-31 (1.38.0, the GitLab plan's end): `server` median-5 18m15s (prev-5 16m35s; 30 master runs 10-29..10-31; min
    10m29s, max 19m10s), `web` 4m00s (TREND +38 % flag: the runner draw, 149-285 s run to run, the last two 169-181 s), `images` 4m10s — no
    new jump: the fast mode is unchanged (658-715 s against 629-773 s) and the slow mode moved from 981-1084 s to 1095-1150 s as the suite
    grew from ~2,280 to 2,831 tests over the GitLab PRs (each measured locally in WHY 55-58); `server`, `web`, `images` and `e2e-static` over
    their alarms; nightly `e2e` median-5 12m56s.
    2026-10-30 (1.31.0): `server` median-5 16m22s (prev-5 12m53s, so a TREND +27 % flag; 30 master runs 10-28..10-30; min 9m18s,
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
- [new] **GitLab backfill fetch-ahead.** Measured 2026-10-31 (`build-times.md` WHY 59): a full backfill of 15k commits and 3k merge
  requests is 283 s, about 23.6k calls at about 12 ms each (the stub's time included); COMMIT FILES (157 s) and MR DETAILS (115 s) are 96 %
  of it and are latency-bound, because a GitLab job holds ONE permit and its calls are sequential.
  - Derived, not measured: at 60k commits / 20k merge requests that is about 118k calls, about 24 min at 12 ms and about 20 min more per
    extra 10 ms of per-call latency, i.e. the real instance's latency decides it.
  - The shape: a fetch-ahead inside a claimed batch (WORKLOGS', `jira.maxConcurrentRequests` calls in flight). An estimated several-fold
    cut on those two streams, not measured.
  - The caveat: it adds response trees in flight, which the heap fit's one-tree-per-slot assumption
    (`gitlab-integration.md` "Outbound calls", Memory) does not cover, so it needs the fit extended and a re-measure beside a Jira job
    (the worker's old generation peaked at 86 of 448 MiB in the pair).
  - Wait for the real-instance smoke run's per-call cost before deciding: at 12 ms it is minutes, at 100 ms it is hours.
- [new] **Reports over `norm.code_*`.** The later plan the GitLab work left open (`gitlab-integration.md` "Status"): nothing derives
  metrics from the code layer yet.
  - The entry point is `identities/IdentityResolver` (`accountsByKey` / `keysByAccount`, LINKED decisions only); the team at an instant is
    `metrics/`'s `teamAt`, which `identities/` must not import.
  - A reports plan must count approvals as distinct merge requests per person (an approve / unapprove / approve leaves two `APPROVAL` events).
  - Inputs measured 2026-10-31: 15k commits, 3k merge requests and 20.7k events are about 68 MB a source; the identities list answers in
    4-8 ms at 72 identities.
- [new] **Jira DERIVE worklog statement: 35.8 s for the second of two connections (needs a reproduction).** Found 2026-10-31
  (`build-times.md` WHY 59): in a Jira scale-20 pair, the worklog fact statement (`WITH sprint_team ... logged AS`) took 35,795 ms in the
  second connection's DERIVE against 477 ms in the first connection's, over the same 24k-issue world (the whole DERIVE: 49.8 s against 14.1 s).
  - Hypothesis, NOT confirmed: stale planner statistics for the second connection's freshly written `norm.*` rows (the DERIVE's own
    `ANALYZE metrics.*` ran 394 ms after the statement), the shape of the "DERIVE never ANALYZEs what it just filled" cause in `build-times.md`.
  - It ran while a GitLab SYNC was running on the same Postgres; whether that matters is unknown. It is a pre-existing Jira path, not GitLab.
  - Next: derive two same-sized connections back to back, with and without a GitLab job beside them; `EXPLAIN (ANALYZE, BUFFERS)` of the
    statement for the second connection; try `ANALYZE` of the `norm.*` tables before the DERIVE. A fix may be a migration or an `ANALYZE` placement.
- [new] **GitLab PROFILE event count: 2.4 s for the second GitLab connection (needs a reproduction).** Found 2026-10-31
  (`build-times.md` WHY 59): in the pair, the `SELECT e.kind, count(*) FROM norm.code_mr_events e JOIN norm.code_merge_requests m ... WHERE
  e.connection_id = <id> AND m.deleted_at IS NULL GROUP BY e.kind` of the data profile took 2,436 ms; the first GitLab connection's, over
  the same sizes (20.7k events, 3k merge requests), stayed under the 300 ms logging threshold.
  - Hypothesis, NOT confirmed: the same stale-statistics shape as above (freshly written rows, no `ANALYZE` yet). It did not reproduce
    after a `VACUUM FULL`.
  - Next: reproduce with a second connection's PROFILE straight after its PROCESS, `EXPLAIN (ANALYZE, BUFFERS)`, and see whether the
    existing index on `norm.code_mr_events` serves the join; a fix could be an `ANALYZE` after PROCESS or a different query shape.

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
  - Last checked 2026-10-31 (1.38.0): no token quota announced (the only entry since 10-29, the Forge custom-field UI RFC-154, does not touch
    Flow).
    2026-10-30 (1.31.0): no token quota announced (the only new entry, a Forge custom-field UI RFC, does not touch Flow).
    2026-10-30 (1.28.0): no token quota announced (entries through 10-28: Bitbucket SSH, Confluence byline, org-level app
    sharing, an MCP tool promotion — none touch Flow's endpoints). 2026-10-29 (1.26.0): no token quota announced (entries through 10-28; the 10-27 `portfolioChildIssuesOf()` JQL change
    does not touch Flow, which never sends it). 2026-10-28 (1.22.1): the 2026-10-20 Tier 2 clarification covers apps (per-tenant quotas), not tokens.

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
