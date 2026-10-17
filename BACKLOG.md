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

- [next] **Remember every filter a user sets, per screen (client-side), like Lettuce.** Coming back to a screen restores
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
- [next] **A new view: two people head to head, over a time range.** Design draft (2026-10-17), the open questions
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
- [next] **Cost matrix: an activity-type and a work-category pie, with a drill.** Two parts-of-whole charts beside the
  matrix, over the same worklogs the matrix reads (period, team/person, domain and the other filters):
  - **Activity type** — logged MD per activity type, shown only when the Activity type filter is unset (with it set
    there is one slice);
  - **Work category** — logged MD per work category (`UNCATEGORIZED` included), shown only when the Work category filter
    is unset.
  - Each slice shows MD and its share; the slices add up to the matrix total (the same rounding rule as the matrix).
  - Many slices: live COOK + ONBOARDING data has 15 activity types (Development 3709 MD down to Administrative 0) and 7
    work categories. Keep the largest slices and fold the tail into one "Other" slice (e.g. under 3%), listed in its
    tooltip; a donut with a legend table beside it, the legend being the keyboard and screen-reader route.
  - **Drill:** clicking a slice (or its legend row) opens the items pane with what is in it: the tasks with worklogs in
    that slice in the period, each with its logged MD and authors, the total reconciling to the slice. "Other" lists
    the folded types' items. New `GET /api/v1/reports/cost-matrix/items` on the shared drill shape (`ReportDrill.kt`,
    "Drill to items"); the Cost matrix has no drill today (`reports.md` lists its heat cells as "not bars").
  - Server: the cost-matrix response gains `byActivityType` and `byWorkCategory` (`[{ key, md }]`), computed in the
    same transaction as the matrix. OpenAPI, `gen:api`, tests per declared status, EN+PL copy, an e2e step.
  - User-visible: a version bump with an EN+PL changelog entry.
- [next] **Deep dive: the selection panel's pickers** (Deep dive → Deep dive, `DeepDiveSelectionPanel.tsx`).
  1. **Sprint options carry no dates.** The Sprints picker labels an option with the sprint's name only, although
     `/reports/deep-dive/sprints` already returns `startAt`/`endAt`/`completeAt`. Label it `name · from → to` like
     Capacity's picker (`Capacity.tsx`'s `sprintLabel`: the configured zone, the last day = `end - 1`, a sprint without
     a start is its name). Move that helper to a shared util and use it in every sprint picker, including the report
     filter bar's single-sprint picker (`ReportPeriodControls.tsx`, name only today). Web only.
  2. **Connection instead of Domain is incoherent.** Why it is there: a selection reads ONE connection, because sprint
     and epic ids are unique only within a connection (`reports.md` "Report 17"), so with several connections the
     panel shows a Connection picker. That is an ingestion detail; the user thinks in domains. Replace it with a Domain
     picker in every mode (Sprints already has one; the epics option list already takes `domain`; tasks follow their
     epic), and derive the connection from the picks: every option already carries its `connectionId`. Only a domain
     spanning several connections would be ambiguous; then the options name their connection. Same reasoning as the
     Cost matrix item above.
  3. **Changing Connection clears the picked sprints, epics and tasks** (`onChange` sets `sprints: [], epics: [],
     tasks: []`, by design: "changing the domain, the epic or the connection drops the picks"). With (2) the Connection
     picker goes, and with it this reset. The general rule for the remaining scope controls (domain, epic): keep every
     pick still valid under the new scope, drop only those that are not, and say so (e.g. "2 sprints removed: not in
     ONBOARDING"), never a silent wipe.
  4. **Remove the "Tasks of an epic" mode** — not needed (the user, 2026-10-17). Two modes stay: sprints of a domain,
     and epics. Goes: the TASKS mode and its pickers in the panel and the URL state (`useDeepDiveUrlState`), the
     `issueId` selection parameter (`DeepDiveSelection.kt`), `GET /api/v1/reports/deep-dive/epics/{epicKey}/tasks`
     (`DeepDiveOptions.kt`) with its spec entry and `gen:api`, the EN+PL copy, the tests and the e2e step/scenario
     (`reports.spec.ts`, `scenarios/reports.md`), `reports.md` "Report 17" and `list-endpoints.md`'s `issueId` mention.
     An old link with `issueId` gets a `400` (or the page drops it and says so; the plan decides).
  - User-visible: a version bump with an EN+PL changelog entry. Server: the deep-dive endpoints resolve the connection
    from the picks when `connectionId` is absent (`DeepDiveSelection.kt`); OpenAPI, tests, the e2e scenario.
- [next] **Deep dive: replace "Execution (task-days)" with "Approximate EV" (man-days).** Agreed with the user
  2026-10-17. Execution's task-days are an artificial unit with their own scale, comparable to neither PV nor AC, and
  what they show (tasks in progress per day) is the WIP and Cycle time reports' job.
  - **The rule:** a DONE task's EV (`estimate_at_done_md`, unchanged, still a marker on `dayOf(done_at)`) is spread
    evenly over the working days on which it was IN_PROGRESS (the days Execution counts today), with PV's cumulative
    rounding (`ROUND(total * i / n, 2)`), so Σ Approximate EV == EV exactly per task, and so at every level (epic,
    week, month, the selection). Example: 3 MD, in progress on 5 working days → 0.6 MD on each of them; EV 3 MD on the
    done day. A linear-progress simplification, labelled as such.
  - **Edge cases (decided):**
    - an OPEN task gets none until it is done (as EV); its in-progress days show in the tooltip and the page's limits
      note. When it finishes, earlier periods gain its share: accepted, the view is live and whole-life;
    - a task with NO estimate has no EV and so none; keep a visible "worked, not estimated" mark on its in-progress
      days so it does not vanish;
    - a task DONE without any in-progress day: the whole EV on the done day;
    - a REOPENED task: spread over all its in-progress days;
    - WAITING and BLOCKED-stage days get nothing (as Execution today). Say precisely in the copy that a day flagged
      in Jira while in an in-progress status still counts (today's note "blocked time is not subtracted" is vague).
  - **Follows through:** a new domain-model amendment (A40) superseding A29's Execution layer; the kernel
    (`DeepDiveKernels.kt`), the wire field (`exec[]` `{d, td}` → MD), OpenAPI and `gen:api`; the matrix (one man-day
    scale for every layer, the legend's separate execution scale gone), tooltips, the summary/bucket tables' execution
    columns, `reports.md` "Report 17"; the Burn-up tab gains a cumulative Approximate EV line beside the stepped EV;
    tests (the independent re-derivation, Σ == EV), EN+PL copy, the e2e scenario. User-visible: a version bump with an
    EN+PL changelog entry.
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
