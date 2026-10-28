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

- [parked] **`epic_domain_key` as-of.** Today it uses the epic's current domain, because no epic-domain history
  exists. Real data is in now; evaluate whether a parent move across projects shows up in the first real project's changelog.
  - Evaluated 2026-10-09: the first real project's changelog (14 months) has NO `project` or `Key` change on any issue.
    Its 100 `IssueParentAssociation` changes are re-parentings inside the project (30 tasks, 4 epics under the
    Program), which never change an epic's domain. Nothing to fix on real data; a candidate for [parked].
  - Parked by the user 2026-10-23: nothing to fix on real data.
- [parked] **Read `hoursPerDay` from Jira's time-tracking configuration (A5).** Real data is in now; check the
  endpoint and its scope on the tenant.
  - Parked by the user 2026-10-23: Flow's own `hoursPerDay` setting is enough unless the tenant uses a non-8-hour day.
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
- [parked] **A28 — a composite estimate for estimated backlog.** Parked 2026-10-01: the teams do not estimate via
  sub-tasks.
  - Estimated backlog uses the OWN estimate only, so a parent estimated through its sub-tasks
    (`estimate_source = SUBTASKS`) is missing from it.
  - The fix is a composite-estimate bridge. Velocity (`sprintScope`) needs the same composite, or backlog-in-sprints
    is overstated.
  - Number the amendment A28 (A23 is taken).

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
  - Last checked 2026-10-28 (1.22.1): `server` median-5 13m00s (prev-5 15m14s; 30 master runs 10-24..10-28), `web` 3m38s, `images` 3m39s —
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
- [new] **UNIT-wide wide-period reads are slow and allocate heavily (API only).** WHY 41: with no `teamId` and 24 months, `throughput` 1.7 s
  p95 and ~661 MiB allocated per request, `wip` 1.4 s, `data-quality` 727 ms / ~177 MiB; Backlog with `touchedWithinDays` at UNIT level 793 ms (WHY 42). No page sends them (every report page is team-
  or domain-scoped), but the API answers them. Decide: a server-side guard (team or domain required) or a pushdown.
- [new] **Person trend with a big team: the next levers (WHY 46).** After 1.22.1 a 15-person team's year takes 522 ms, ~5.4x Compare people
  (target 2x; a 5-person team is at ~2x). `build-times.md` WHY 46 "What is left" has the numbers: the last-day WIP read as arrays (~-110 ms;
  changes `CompareWip`'s shape), decoding only the trend's columns (~-85 ms), and a per-window peer-cell cache (the only lever to 2x; new
  server state with invalidation and a memory bound — a design decision).
- [new] **Remove the TRIM step once every install is trimmed.** `jira/JiraTrimStream.kt` + `JiraRawTrim.kt` + the `trim` cursor row rewrite the raw rows
  stored before the payload trim (1.17.1) existed, once. When the dev stack and every other install show `phase: DONE` on the `trim` cursor (the data source status
  endpoint), delete the stream, the collaborator, its wiring in `JiraConnector` (SYNC and REPROCESS), its tests and the docs; a `DELETE FROM sync_cursors WHERE stream = 'trim'` migration is optional.
- [new] **Measure, then decide on the raw `issuelinks`, `subtasks` and `environment` keys.** The 1.17.1 trim keeps them (unread today, kept for follow-up measures such as
  blockers and sub-task roll-ups). After the first real sync, `jsonb_object_keys` over `raw.jira_issues.payload->'fields'` with `pg_column_size` per key shows what they cost; drop the ones nobody reads.

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

- [new] **Jira paging loops: what the ceilings do not cover.** The REFERENCE, PROJECT_FIELDS, ISSUES, RECONCILE and WORKLOGS/CHANGELOGS
  loops carry page, row and (ISSUES, RECONCILE) byte ceilings, the response cap is 8 MiB and a body over a structural node ceiling is
  refused before the parse (`jira-integration.md` "Paging ceilings", "Response size cap", "Node and depth ceiling"). Left:
  - `GET /field` stays one shot (~4 MB at ~10k fields vs the 8 MiB cap): `GET /field/search` would page it, but Atlassian's docs
    contradict themselves on whether a non-admin token may call it (`jira-integration.md` "`GET /field` stays one shot"); settle it against
    a real non-admin token before switching.
  - Heap: a hostile host behind the allow-list can still hold several dense trees in a `-Xmx512m` worker (the worst shape found,
    `[{"a":0},...]` at the 1 Mi-token ceiling, is ~108 MiB of tree per response; the second slot's DERIVE is ~350 MiB at scale 20), and
    `ExitOnOutOfMemoryError` makes an OOM a worker exit, so a retried job crash-loops every connection of that worker. Closing it needs
    a per-slot heap budget (admission by estimated tree size before the parse), a bigger worker heap, or a streaming parse that never
    builds the tree (`jira-integration.md` "Node and depth ceiling").
- [new] **Ceiling-trip pause: remaining gaps.** Shipped in 1.22.0 (`ingestion.md` "Ceiling trips and the pause"); what it still misses:
  - A hard-cap loop that re-commits pages every run (REFERENCE's all-boards SPRINT pass, `jira-integration.md` "Paging ceilings") reads as
    progress, so it never pauses; it keeps the failure back-off (6 h cap) and the status page. A fix needs a per-stream notion of "new rows".
  - Progress is per stream: a wall-clock stop in a later stream, after an earlier one stored a lot, reads as no progress when the tripping
    stream itself stored nothing.
  - The wall clock is checked between pages only: a Jira call in flight (with its 429 back-off sleeps) and a page's transaction run to
    their end.
  - A scheduled RECONCILE already queued when the pause starts still runs once; RECONCILE trips are never counted.
- [parked] **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory.
  - That's safe because the web Deployment runs one replica with Recreate.
  - Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- [todo] **Atlassian API-token quota: re-check developer.atlassian.com/changelog before releases.**
  - API-token traffic is excluded from the 2026 points-based rate limits.
  - Atlassian is evaluating a quota for tokens "with advance notice".
  - Last checked 2026-10-28 (1.22.1): no token quota announced; the 2026-10-20 Tier 2 clarification covers apps (per-tenant quotas), not tokens.

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
