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

- [new] **Finish COOK's metrics configuration** (the user's, on the Metrics configuration page):
  - map the work-category values (none are mapped, so no task has a category);
  - map the waiting statuses (Ready for Review, Ready for Test, On Hold, …) to **Waiting** (A30);
  - check "Ready" → DONE and whether "On Hold" should map to the Blocked stage;
  - add the team's Jira members (Cookiecutter Team has one).
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
- [new] **A per-level (epic vs task) status → stage override.** Epics and tasks have different workflows: on COOK,
  To Do, In Progress, On Hold, Ready and Closed are shared by both. Flow maps each status id to ONE stage, with
  per-domain overrides only, so a shared status can't mean NOT_STARTED for an epic and DONE for a task.
  - The Statuses tab now badges each status Epic/Task (#115). Decide after reviewing them whether any shared status
    differs.
  - If one does, this needs a domain-model amendment plus config, DERIVE and UI changes.
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

- [new] **Cost matrix: zero rows for team members with capacity but no worklogs.** Today rows are only authors who
  logged; a manager may want members with capacity and zero cost listed too (a changed row set, for every viewer).
- [new] **Drill-to-items UX, on every report that has it** (reported on Delivery → Commitments). Applies to Commitments,
  Sprint consistency, Throughput, the Home commitments tile and the six histogram reports (Cycle time, Reported time,
  Task accuracy, Estimate adjustments, Epic accuracy, Blocked time).
  1. **The items pane is too narrow and too short.** The drawer (`ReportDrillDrawer`, `size="xl"`) is narrower than its
     table, so a double-clicked bar opens with horizontal scroll bars, and the table does not fill the pane's height.
     Widen the pane (or let the table wrap/shed columns) and let the table fill the remaining height.
  2. **Replace the icon buttons in the "Items" column with clickable figures.** Commitments has two icons per row,
     Sprint consistency seven. Instead, each figure that a drill bucket stands behind (Commitments: Initial and Final,
     MD and items; Sprint consistency: each bucket's figures; Throughput: Delivered; a histogram's count per bucket)
     becomes a link-styled button opening the pane with that bucket's items. It stays the keyboard route to the chart's
     double-click: a real button, an `aria-label` naming sprint and bucket, focus returning to it on close. Then drop
     the Items column (`DrillColumn.tsx`'s `sprintDrillColumn`/`drillColumn`).

  The "Since completion" column (the D13 drift badge with the frozen figures) stays as it is — the user's decision,
  2026-10-17.
- [new] **Remember every filter a user sets, per screen (client-side), like Lettuce.** Coming back to a screen restores
  the last values, as long as they still make sense.
  - Today: the list pages already do it (`useStoredState`, ported from Lettuce: Users, Feature flags, the registries), but
    the reports keep their filters in the URL only and remember just the team (`useReportFilter`'s `reports.teamId`). A
    bare nav click to a report loses the period, level, person(s), domain, activity type, work category and the view
    toggles. The same applies to the Deep dive (`useDeepDiveUrlState`), Capacity's team/sprint and the other
    URL-state hooks (`useThroughputView`, `useAgingKind`).
  - Shape: per screen, store the last filter under `flow.viewSettings.<screen>.*`; a URL with no filter params restores
    it (and is rewritten with replace, as the remembered team is today); a URL that carries params is taken as written
    (deep links win).
  - "Still makes sense": every restored value is validated against the current reference data and dropped if stale —
    a deleted team, a person no longer in the team, a sprint/epic/issue id that is gone, an unknown enum value, a
    period no longer valid for the report. Through the report's existing `normalize`, so the page and the URL agree.
- [new] **A new view: two people head to head, over a time range.** Design draft (2026-10-17), the open questions
  answered by the user; next: an architect plan.
  - **Inputs:** person A and person B (Jira accounts, any team — a person who moved teams in the range is read across
    both, by their dated memberships) and a `from`/`to` range only (presets: last 30/90 days, last quarter, custom). No
    sprint periods: two people need not share sprints. The common filters (domain, activity type, work category) apply
    to both sides alike.
  - **Layout:** one row per metric, A | B | difference, each cell with its sample size; a metric under `minSampleSize`
    shows counts only, as the reports do. Distributions (cycle time) as one histogram with two series. Every figure
    drills to its items (the existing `…/items` endpoints with `segmentAccountId`).
  - **Metrics, from the reports that read a person over a period:**
    - Throughput: delivered MD and items (assignee at done);
    - Cycle time: elapsed and working days, p50/p90 and the histogram;
    - Reported time ratio (logged vs estimate);
    - Task estimation accuracy (at start, at done) and Estimate adjustments (changed share, change size);
    - WIP: average items in progress per working day, and the aging WIP at the range end;
    - Blocked time on their items;
    - Time: logged MD, available MD from the capacity calendars, logged/available, and the foreign-work share (Cost
      matrix);
    - Work mix: the share of delivered MD per activity type, work category and domain;
    - Data quality: worklog coverage, late logging, tasks without an estimate.
  - **Left out, on purpose:** Commitments and Sprint consistency (sprint-based), Epic progress, Epic accuracy and the
    estimated backlog (epics carry no person).
  - **Normalisation:** next to the raw figures, the volume metrics per available MD, so a part-timer or someone with
    holidays in the range is not read as slower.
  - **Server:** most of these reports already take two picked people (`accountId` ×2 at USER level), but only inside
    one team (`teamRequired`). Either a new `GET /api/v1/reports/compare-people?accountId=A&accountId=B&from&to` that
    reuses the report kernels without the one-team rule, or the page composes the existing endpoints and the one-team
    rule is relaxed for USER level. The architect decides.
  - **Decided with the user (2026-10-17):**
    - **Access:** every signed-in user, like every other report (D12). No extra guard.
    - **Difference column: yes, and it says who has the better value**, with visual cues, not text alone:
      - each metric declares its direction: *higher is better* (delivered MD/items, estimation accuracy, worklog
        coverage), *lower is better* (cycle time, blocked time, aging WIP, late logging, tasks without an estimate),
        *closer to a target is better* (reported time ratio and logged/available, target 1.0), or *neutral* (work mix,
        foreign-work share, raw logged MD — no winner, no cue);
      - the better side gets an arrow pointing to it and the success hue (teal, the palette's "good"); the other side
        stays plain — never red, which means "blocking" in Flow's palette. Colour is never the only cue (the arrow,
        plus an `aria-label` such as "A better by 1.4 days");
      - no cue on a tie (the difference within a small per-metric tolerance) or when either side is under
        `minSampleSize`.
    - **No caveat text** about different kinds of work: obvious, skipped.

## Engineering follow-ups

- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - Last checked 2026-10-17: `server` median-5 13m41s, over the 10-min alarm — runner speed, suite growth and fork
    imbalance (`build-times.md` WHY 18); `web` 2m55s, `images` 3m09s. Before that, 2026-10-15: `server` 6m36s; no jump. The nightly `e2e`
    had been red since 10-07 on a runner change (buildx bake fs entitlements), fixed the same day.
  - Where it stands:
    - `server` takes 8 min on a fast runner and 13–14 on a slow one (from ~30 on 2026-09-30);
    - `web` takes ~3 min;
    - the CI timeout is 25 min.
  - Budgets, history and the open WHY questions are in `.claude/docs/build-times.md`. Local runs use
    `scripts/gates.sh`.
- [new] **Remove the Flow-login team roster.** A team has two member lists:
  - the Jira-account one (`metrics.team_membership`, dated, D1), which every metric, report, capacity and report
    filter reads;
  - the Flow-login one (`team_members`, V6, inherited from Covenant), which nothing outside `teams/` reads: no
    metric, report or permission (capacity editing is the team's manager, V23, or an ADMIN).

  Drop the second:
  - a migration dropping `team_members`;
  - `TeamService` (`TeamMembers`, add/remove member), the members field on the team responses;
  - `/api/v1/teams/{id}/members/{userId}` and the members part of the team schemas in `documentation.yaml`, `gen:api`;
  - the Members section on the team page (`TeamDetails.tsx`), its EN+PL copy, tests, the e2e spec and scenario;
  - the docs that describe it (`conventions.md`'s `teams/` template, `authorization.md`, the D1 note).

  User-visible, so a version bump with an EN+PL changelog entry.
- [parked] **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or
  unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- [parked] **Two connections to one Jira site are allowed** (with different project scopes). Confirm this is the wanted
  behaviour once real usage exists.
- [parked] **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).**
  - Today these are Jira-shaped:
    - `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`;
    - `JiraConnectorKey` in `DataSourceRoutes.kt`;
    - `DataSourceRequest.jira`.
  - Generalising them is speculative until a second connector exists. Revisit with GitLab.

## Checkups — what is left (record: `.claude/docs/audit-status.md`)

- [todo] **The nightly `e2e` image-build cache.** WHY 7's data decides (`build-times.md`).
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
