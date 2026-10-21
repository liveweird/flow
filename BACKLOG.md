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
  open, waiting on the cache-posture sign-off. The cost matrix's capacity column depends on the caller (ADMIN or the
  team's manager), so a report ETag must include that verdict for `/reports/cost-matrix` (or exempt it), or a 304 would
  serve the column to someone else.
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

## Report follow-ups

- [next] **Revamp the Cost matrix into "Costs".** Asked by the user 2026-10-22.
  1. The nav entry "Cost matrix" becomes **"Costs"** (EN; PL to match), and moves up between Estimation and Flow metrics
     (`web/src/utils/navigation.ts`, today: Delivery, Estimation, Flow, Data quality, Cost, Compare people, Deep dive). The page title,
     route and i18n follow the rename the way "Flow users" did (route `/reports/costs` with a redirect from the old path; whether the
     REST path `GET /reports/cost-matrix` follows is to settle).
  2. **Remove** the "Man-days by author and domain" matrix and the "Man-days by epic and author" table (1.5.0, `byEpic`), with their
     drills, spec fields, tests, e2e steps and docs. Check what else reads them (Home's cost links, `measures.md` rows).
  3. **New: actual cost per sprint, split by epic.** A stacked bar chart grouped by the team's sprints in the filtered period (the
     filters apply: team, domain, activity type, work category), each bar the sprint's AC in MD stacked by epic ("No epic", top N +
     "Other epics"), with its data table below. One epic attribution, colour and top-N rule shared with the Commitments/Throughput
     by-epic charts. To settle: which worklogs belong to a sprint — logged between the sprint's start and end (by the team's members),
     or logged on the sprint's items.
  4. **New: actual cost vs capacity per sprint.** Per sprint, AC beside the sprint's capacity (A32 `capacity_md`), chart + table, the same
     filters. Capacity stays visible only to an ADMIN or the team's manager (A39), so other viewers see AC alone (or the widget hides).
  5. **Work categories against a team's target distribution.** "Man-days by work category" shows the ACTUAL split today.
     - A team gets a target distribution (category → %, summing to exactly 100 %): a new screen reached from a fourth row action on
       Teams (beside Members, Edit, Delete); ADMIN-only writes like the rest of Teams. New persisted config (a migration, a
       `team_work_category_targets`-like table, the spec, audit events). To settle: where the category list comes from (the
       connection-mapped work categories; a team spanning connections), whether targets are dated (a change keeps history) or one
       current set, how "no category" counts, the % precision.
     - A widget: target vs actual per category for the filtered period (the difference in percentage points), chart + table.
     - A multi-line chart, one line per work category: its cumulative share of AC over the filtered period (each point = the share
       from the period's start to that day/week), chart + table.
  - Keeps: the activity-type and work-category donuts, the cost drill to worklogs, the team-required filter (1.7.0).
  - Needs a domain-model amendment (the target distribution) and `measures.md` rows for every new figure.
- [next] **Commitments: initial and final scope per sprint, split by epic.** Asked by the user 2026-10-22: a chart like "Initial and
  final scope per sprint", stacked by epic, to see whether a team keeps its epic-level WIP low (few epics in flight per sprint).
  - The shape: per sprint, the initial and the final bar each stacked by the epics their items belong to (a task or sub-task counts
    toward its epic by the one hierarchy rule, A31 `norm/Hierarchy.kt`; an epic in the sprint itself counts toward itself; work without an
    epic is a "No epic" segment). Epic level only: no per-task breakdown.
  - Data: `metrics.fact_sprint_scope` (one row per sprint × issue: `committed`, `in_scope_at_close`, `estimate_at_commitment_md`,
    `estimate_at_close_md`) joined to each item's epic, so the segments add up to the existing chart's `initialMd`/`finalMd` (and items).
    A `byEpic` beside `byUser` on `GET /reports/commitments`, with the spec, `gen:api` and conformance tests.
  - To settle: many epics in one chart (top N per period + "Other epics", as the cost matrix's epic table does, with a colour that stays
    the same for an epic across sprints); MD or item count (follow the existing chart's unit switch); the drill from a segment to its
    items (the shared sprint-scope drill, narrowed to the epic); whether the USER level (picked people) shows it too.
- [next] **Throughput: delivered per sprint, split by epic.** Asked by the user 2026-10-22: a chart like "Delivered per sprint",
  stacked by epic, to see whether a team keeps its epic-level WIP low. Epic level only: no per-task breakdown.
  - The shape: per closed sprint, the delivered bar (`bySprint`, = Σ `fact_sprint_scope.done_in_sprint`) stacked by the epics its done
    items belong to (A31 hierarchy rule; "No epic" for the rest), so the segments add up to `deliveredMd`/`deliveredItems`.
  - Build it with the Commitments by-epic chart above: one epic attribution, one top-N + "Other epics" rule, one epic colour across
    both charts and sprints, the same drill (the sprint-scope drill narrowed to an epic).
- [next] **Throughput: an "Actual cost" bar beside "Delivered", in both "Delivered per period" and "Delivered per sprint".** Asked by the
  user 2026-10-22: how much time was reported, summed by the same criteria as "Delivered".
  - The figure: actual cost (AC, the domain model's reported-time dimension), in MD like the delivered bar, from `fact_worklog`.
  - To settle, with the user: "the same criteria" as worklogs ON the delivered items (all their time, whenever logged: the cost of what
    was delivered — per sprint the sprint's done items, per period the items done in the bucket), or worklogs LOGGED in that sprint or
    period by the scope's people (the effort spent in it, delivered or not). The first compares cost to value; the second is effort per
    period, which the Cost matrix already shows.
  - Then: the item-count view has no AC (hide the bar or show it only in MD); the USER level splits it per person; the drill from the AC
    bar lists the worklogs (the cost matrix's drill shape); `measures.md` gets the contract row; the period view's domain / activity /
    work-category filters apply as they do to Delivered, the sprint view ignores them as Delivered does.
- [new] **A team is required on Ageing WIP, Blocked time, Data quality and the Cost matrix.** Asked by the user 2026-10-21: "All teams" goes,
  one specific team is always picked.
  - Today each page's Team picker is clearable with an "All teams" placeholder, and the server reads an absent `teamId` as unit-wide
    (`reports/ReportScope.kt` `resolveReportScope`). Only the cost matrix's `split=SPRINT` already needs a team (`CostMatrixSplit.kt`).
  - The pattern to copy: `ReportControls.teamRequired` (`web/src/utils/reportFilter.ts`), as on Commitments, Task accuracy and Reported
    time. The picker then cannot be cleared, drops "All teams", and the URL is normalised to a team (`useReportFilter`, `requiredTeamId`).
  - To settle:
    - Is it web-only, like every `teamRequired` page today, or does the server also answer a 400 without `teamId`? A 400 breaks old
      bookmarks and any drill link without a team, and needs the OpenAPI spec, conformance tests and the drill routes (`…/items`) to follow.
    - Does a picked Domain still combine with the team (it does today on all four)?
    - The cost matrix's columns are the author teams (`orgGroups`); with one team they become that team's authors, as in a team's view
      today. Check the Home overview's links and every drill into these pages carry a team.
- [new] **Team OR domain is required on Estimated backlog and Epic progress.** Asked by the user 2026-10-21.
  - Today both pages let Team and Domain be cleared (unit-wide), and the two are already exclusive (`domainExcludesTeam`; the server
    answers 400 for both, `SnapshotSupport.kt` `snapshotScopeOf`, `EpicProgressTargets.kt` `progressTargetOf`).
  - The pattern to copy: Flow metrics → WIP (`ReportWip.tsx`: `teamRequired + teamOrDomain + domainExcludesTeam`, web-only).
  - To settle: the same web-only vs server-400 question as above. Epic progress's single-epic mode (`epicId`) needs neither, and must
    stay reachable from its drills.
- [new] **Deep dive: by epics, a Domain is picked before the epics.** Asked by the user 2026-10-21: it shortens the epic list.
  - Today the Domain select above the picker is optional in both modes (`DeepDiveSelectionPanel.tsx`); the epic list
    (`GET /reports/deep-dive/epics`, `DeepDiveOptions.kt`) filters by domain and `q` and pages 100 at a time; a selection holds at most 50 epics.
  - To settle: in epic mode the picker stays disabled (with a hint) until a domain is chosen; whether changing the domain clears epics
    already picked from another domain, or a selection may span domains; whether the server's `deep-dive/epics` then requires `domain`
    (a 400 without it) or only the page does.
- [new] **Rename "Users" to "Flow users" everywhere, to tell Flow's login accounts from Jira users.** Asked by the user 2026-10-21: the
  nav entry first, and code, mappings and routing consistently.
  - The visible text: the nav entry (`appShell.json` `users`), the page titles and empty states (`users.json`: "Users", "No users",
    "Back to users"), the create/edit/features pages' headings and the account menu's links, in EN and PL (PL: "Użytkownicy Flow").
  - The code: the `Users`/`CreateUser`/`EditUser`/`UserFeatures` pages, `api/users.ts`, `utils/userLinks.ts`, the i18n namespace
    `users` and the SPA routes `/users…` (an old `/users` link redirects to the new path). The e2e spec `users.spec.ts` and its
    scenario file, and the docs naming the page (`web-features.md`, `web-internals.md`, `conventions.md` if it cites it).
  - The REST API follows (the user's decision, 2026-10-21): `/api/v1/users…` → `/api/v1/flow-users…`, a breaking change for any
    script. With it: the OpenAPI spec (paths, schema names), `npm run gen:api` → `schema.ts`, the server routes and their tests, the
    401 sweep and the conformance coverage, the e2e API helpers, and the rate-limit/authorization docs that name the paths.
  - Still to settle: the audit event names (`user.*`; log consumers may key on them), the server package `users/`, the `users` table
    (renaming it is a migration of persisted state; likely not worth it) and the `Feature` per-user flags' wording. "Jira users"
    elsewhere (people pickers, Compare people, "by person") keep their wording.
  - A changelog entry (EN+PL) says where the page went.

## Engineering follow-ups

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
