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

## Next: the phase 2 exit — real Jira (needs the user)

- [next] **The real-Jira first sync.** The steps:
  - Create an Atlassian service account with a scoped, read-only API token. The scope list is in the README,
    "Connecting Jira", and includes `read:workflow:jira`.
  - Start the stack with the stub off (`JIRA_STUB_BASE_URL= docker compose up`). Add the data source, run Test
    connection, and fix any scope gaps it reports.
    - Check whether the boards 401 persists once the token has the board scopes.
  - Backfill 24 months, then read the data profile together (runbook: `.claude/docs/ingestion.md`, "Reading the data
    profile after the first real sync").
  - The first sync will bring adjustments (A10). The defaults the metrics configuration ships with meet real data for
    the first time: status → stage, the estimate and epic fields, the work-category field.
  - Things to confirm on the real tenant, where the spike marked them uncertain:
    - Basic vs Bearer auth;
    - bulk-changelog availability and its per-request cap;
    - the search page-size ceiling;
    - how Sprint changes appear in the changelog.

## Phase 3 follow-ups (the domain model, v0.3.0)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A27 override the body.

- [blocked] **`epic_domain_key` as-of.** Today it uses the epic's current domain, because no epic-domain history
  exists. Waits on the first real sync.
- [blocked] **Read `hoursPerDay` from Jira's time-tracking configuration (A5).** Waits on the first real sync.
- [blocked] **Seed memberships from the Team field (D1).** Waits on the first real sync.
- [new] **Cache validators for the report endpoints.** Draft PR #75 (ETag/304 + an atomic DERIVE success mark) is
  open, waiting on the cache-posture sign-off.
- [new] **Issues above epic level are derived as tasks.** The real tenant's COOK project has a "Program" issue at
  hierarchy level 2 (above its epics). DERIVE treats every non-epic as a task (`hierarchyLevel != 1`:
  `MetricsDeriver.kt`, `DeriveSprintStep.kt`). The domain model says a TASK is a level-0 issue, so the Program counts in
  task WIP, throughput and cycle time like ordinary work.
  - Likely fix: tasks are level 0 (plus sub-tasks rolling up), and level ≥ 2 is excluded, unless initiatives or
    programs should be reported somewhere. That's a domain-model decision (a new D-entry or amendment).
  - The stub has no issue above level 1. Add one with the fix.
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
- [blocked] **Check the real tenant's longest Jira names at the first sync.**
  - Every Jira-supplied free-text name is `TEXT` since V19. An over-long reference KEY or enum is skipped and logged
    (`referenceRowsSkipped`, `.claude/docs/ingestion.md` "Reference-row robustness").
  - What is still bounded is the identifiers:
    - per-issue `issue_key` and `project_key`: 20;
    - `status_id`: 50;
    - account ids: 100;
    - `rank`: 100;
    - `field_id`: 100;
    - `value_id`: 200.

    A value past one of these is a counted bad row (`issuesFailed`) that stays `needs_processing` forever.
  - After the first real sync, look for `referenceRowsSkipped` and `issuesFailed`, and widen what really overflows
    (the V19 pattern).
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
