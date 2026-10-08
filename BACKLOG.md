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
  - check "Ready" → DONE and whether "On Hold" is also a Blocked status;
  - add the team's Jira members (Cookiecutter Team has one).
- [new] **Confirm the search page-size ceiling** on a project with more than 100 issues. COOK's 46 issues fit on one
  page, so the real tenant hasn't shown it yet.

## Phase 3 follow-ups (the domain model, v0.3.0)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A27 override the body.

- [new] **`epic_domain_key` as-of.** Today it uses the epic's current domain, because no epic-domain history
  exists. Real data is in now; evaluate whether a parent move across projects shows up in COOK's changelog.
- [new] **Read `hoursPerDay` from Jira's time-tracking configuration (A5).** Real data is in now; check the
  endpoint and its scope on the tenant.
- [new] **Seed memberships from the Team field (D1).** Possible now: COOK's Team field is filled on 43 of 46 issues.
- [new] **Cache validators for the report endpoints.** Draft PR #75 (ETag/304 + an atomic DERIVE success mark) is
  open, waiting on the cache-posture sign-off.
- [new] **A per-level (epic vs task) status → stage override.** Epics and tasks have different workflows: on COOK,
  To Do, In Progress, On Hold, Ready and Closed are shared by both. Flow maps each status id to ONE stage, with
  per-domain overrides only, so a shared status can't mean NOT_STARTED for an epic and DONE for a task.
  - The Statuses tab now badges each status Epic/Task (#115). Decide after reviewing them whether any shared status
    differs.
  - If one does, this needs a domain-model amendment plus config, DERIVE and UI changes.
- [new] **Issues above epic level are derived as tasks.** The real tenant's COOK project has a "Program" issue at
  hierarchy level 2 (above its epics). DERIVE treats every non-epic as a task (`hierarchyLevel != 1`:
  `MetricsDeriver.kt`, `DeriveSprintStep.kt`). The domain model says a TASK is a level-0 issue, so the Program counts in
  task WIP, throughput and cycle time like ordinary work.
  - Likely fix: tasks are level 0 (plus sub-tasks rolling up), and level ≥ 2 is excluded, unless initiatives or
    programs should be reported somewhere. That's a domain-model decision (a new D-entry or amendment).
  - The stub has no issue above level 1. Add one with the fix.
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

- [todo] **CI duration: check the trend at every milestone.** Run `node scripts/timings/ci-times.mjs --branch master`.
  A trend jump gets an investigation, never a raised budget.
  - It is within budget today:
    - `server` takes 7–8 min (from ~30 on 2026-09-30);
    - `web` takes ~1–2 min;
    - the CI timeout is 25 min.
  - Budgets, history and the open WHY questions are in `.claude/docs/build-times.md`. Local runs use
    `scripts/gates.sh`.
- [todo] **DERIVE parallelism at scale 20 — measurement pending.**
  - Re-derives now ANALYZE after the commit, so two connections' derives overlap (stub: ~0.55 of the sequential
    time, `build-times.md` 2026-10-06).
  - A derive whose previous statistics do not describe its rows still ANALYZEs in its transaction. That covers the
    first derive, a table that was empty, and a table that has doubled.
  - The scale-20 two-connection run is still to be done: the perf overlay, `workerSlots=2`, the `derive_runs` overlap
    and the `pg_stat_activity` relation waits. It would also close "ANALYZE at scale 20 NOT measured".
- [new] **Scheduler back-off gaps (SYNC and RECONCILE alike).** Found in the #112 review:
  - A job that exhausts its attempts (`SyncJobLeases.claim` → `RETRIES_EXHAUSTED`, e.g. a worker crash loop) never
    reaches `onFailed`. So no back-off is recorded, and the next tick enqueues a fresh job at once.
  - `onFailed` writes FAILED and the back-off in two transactions. Another worker's tick can enqueue one extra retry
    in between (a millisecond window).
  - A config PUT (e.g. a rotated token) doesn't reset `consecutive_failures`/`next_sync_at` or
    `reconcile_failures`/`next_reconcile_at`. The fixed connection waits out up to 6 h unless someone requests a job
    manually.
- [new] **An oversized `fields=*all` issue page stalls SYNC.** If one `search/jql` page exceeds
  `jira.maxResponseBytes` (32 MiB), `LIMIT_EXCEEDED` repeats for the same page on every run. Halving `maxResults` on
  `LIMIT_EXCEEDED` would unstick it. Jira already shrinks pages for heavy field sets, so this is unlikely at 100.
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
- [blocked] **Dependabot #47 and #64–#67.** Held under the dependency rule; the user's decision. #45, #46 and #48 were
  closed 2026-10-03.
- [blocked] **2C8 — the Polish glossary pass.** A judgement call that needs a native speaker.
- [new] **2D3 — password reset by confirm-link.** Today anyone who knows an email can force one rotation per 60 s
  (MFA still applies). The user's decision.
- [new] **2D4 — a per-request `credential_revision` check.** Today a delete or demote takes effect within the
  15-minute access-token window. The user's decision.
- [new] **A per-user report rate bucket.** The statement timeout bounds one query, not a stream of them. The user's
  decision.
- [new] **A PR-time image scan** (~3 min per PR). The nightly scan is the compromise. The user's decision.
- [new] **Tag and release 0.4.0.** The user's decision (`.claude/docs/app-releases.md`).

## Security and operations

- [parked] **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory.
  - That's safe because the web Deployment runs one replica with Recreate.
  - Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- [todo] **Atlassian API-token quota: re-check developer.atlassian.com/changelog before releases.**
  - API-token traffic is excluded from the 2026 points-based rate limits.
  - Atlassian is evaluating a quota for tokens "with advance notice".
- [blocked] **Dependabot #12, OpenTelemetry 1.66.** Held until an `opentelemetry-instrumentation-bom-alpha` release
  pairs with it.

## Sibling handoffs

- [blocked] **Dispatch the sibling handoffs** (the user's to dispatch). Guardrails and fixes found while building Flow;
  Flow never edits sibling repos:
  - `~/Sources/covenant/flow-hardening-handoff.md`;
  - `~/Sources/lettuce/flow-hardening-handoff.md`;
  - `~/Sources/toadie/flow-hardening-handoff.md`.
