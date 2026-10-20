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

- [new] **Estimation: a new "Task estimates" tab — the distribution of a team's task estimates.** A histogram of how many tasks
  are estimated at how many MD, beside the other Estimation tabs (`ESTIMATION_TABS`, `web/src/utils/reportLinks.ts`):
  - the standard filter bar: period, team (always one), member (`accountId`), domain, activity type and work category;
  - the domain view switch (delivered in / earned in, `domainView`) and the long-tail control (`tailPercent`,
    `ReportTailControl`), as on the other distribution reports;
  - the plan decides which tasks count (done in the period, or estimated in it) and which estimate snapshot (at start or at
    done, `measures.md`); tasks with no estimate are counted in a note, not drawn;
  - the per-person split (`accountId` repeated, `byUser`) as on Task accuracy, every bar drills to its tasks, and the numbers
    sit in a table beside the chart (chart rules);
  - a new report: its `measures.md` contract row, `reports.md` section, spec and e2e spec + scenario.

## Engineering follow-ups

- [new] **Concurrent worklog fetches in the backfill.** Since the batched raw writes (`build-times.md` WHY 21) the stub SYNC is
  ~4.6 s, ~2 s of it the 1,201 sequential per-issue `GET /issue/{id}/worklog` calls. Fetching a claimed batch with the existing
  `maxConcurrentRequests` semaphore (`JiraHttp.kt`, default 4) would cut that, but it changes the request pattern against the real
  tenant (rate limits, the A1 worklog scope). Decide after the first real-Jira backfill shows its own figure.
- [new] **Jira links for more than one Jira site.** Since 0.45.0 issue keys link to `{siteUrl}/browse/{KEY}`, but most report row
  shapes carry no `connectionId` (the drill items, Aging WIP, Blocked time, Epic accuracy, Data quality task/epic refs, Epic progress,
  the Deep dive rows), so the web uses the one site all connections share and falls back to plain text when two different sites
  exist. Add `connectionId` to those shapes (spec + gen:api + tests) so every key links to its own site.
- [new] **A hung server test must fail fast, with a thread dump.** On 2026-10-17 (PR #219, run 39062260538) `DimDateContentionTest`
  failed after ~6 min with an R2DBC `PostgresConnectionException`/`ResponseQueueException` inside its first DERIVE, and the `server`
  job then sat idle until the 25-min job timeout cancelled it (no further test output for 19 min). It passed on the re-run and 3/3
  locally, so a CI flake — but a broken pooled connection should fail the remaining tests, not hang the fork. Investigate the hang (likely
  later tests in that fork waiting on a poisoned pool) and add a safety net: a per-class or per-test timeout for the server suite and a
  `jstack` of the test JVMs when it trips, so the next occurrence names the stuck thread. `build-times.md` gets the WHY.
- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - Last checked 2026-10-18 (1.0.0): `server` median-5 12m09s (+52 %, over the 10-min alarm), `web` 3m28s (over its 3-min
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
- [parked] **Two connections to one Jira site are allowed** (with different project scopes). Confirm this is the wanted
  behaviour once real usage exists.
- [parked] **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).**
  - Today these are Jira-shaped:
    - `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`;
    - `JiraConnectorKey` in `DataSourceRoutes.kt`;
    - `DataSourceRequest.jira`.
  - Generalising them is speculative until a second connector exists. Revisit with GitLab.

## Checkups — what is left (record: `.claude/docs/audit-status.md`)

- [new] **The nightly e2e: the next levers after WHY 23.** Heavy-first dispatch (a second Playwright project listed first, -20-40 s
  simulated), a minted login (port Lettuce's `e2e/sessions.ts`, -15 s), `reports.spec` onto the shared fixture (a throwaway team for its
  manager test), and a worker-count re-A/B once the syncs are gone. Measure the WHY 23 fix on the nightlies first.
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
