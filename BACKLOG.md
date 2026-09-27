# Product backlog

Updated 2026-09-27. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/`.

Entries are proposals, not delivery commitments.

## Next: the phase 2 exit — real Jira (needs the user)

- Create an Atlassian service account with a scoped, read-only API token (scopes in `.claude/docs/jira-integration.md`).
- Add the data source, run Test connection, and fix any scope gaps it reports.
- Backfill 24 months, then read the data profile together (runbook: `.claude/docs/ingestion.md`, "Reading the data profile after the first real sync").
- Things to confirm on the real tenant, where the spike marked them uncertain:
  - Basic vs Bearer auth;
  - bulk-changelog availability and its per-request cap;
  - the search page-size ceiling;
  - how Sprint changes appear in the changelog.

## Phase 3: the domain model

- **Agreed (2026-09-27):** `.claude/docs/domain-model.md`, covering:
  - the entities: DOMAIN, TASK, USER, TEAM, EPIC, SPRINT, WORKLOG;
  - plan, delivery and cost as PV, EV and AC, measured in man-days (1 SP = 1 MD);
  - the configuration;
  - the `metrics` star model and its invariants;
  - the ten target reports mapped to facts (velocity, throughput, task and epic estimation accuracy, estimate adjustments, sprint consistency, cycle time, reported time ÷ cycle time, WIP, estimated backlog);
  - decisions D1–D9 (D4 revised: an epic's budget is its own estimate).
- **Next: an implementation plan**, in this order:
  1. PROCESS additions (the doc's "Gaps in `norm` today"), with a `PROCESSING_VERSION` bump:
     - epic-membership history;
     - estimate intervals for tasks and epics;
     - epic dates;
     - configurable custom-field capture (work category).
  2. The `metrics` schema with its configuration tables.
  3. The configuration UI: status stages, estimate field(s), epic date fields, domain map, board → team, capacity, calendar, activity-type and work-category maps, and dated team membership (D1).
  4. The derivation, plus invariant SQL sweeps.
  5. The report pages, one per report in the doc's "Reports" section. Each has the unit → team → user drill-down, a period picker (calendar and per-team sprints), domain, activity-type and work-category filters, and p50/p90/p95 plus full distributions. Port Lettuce's `@mantine/charts` + `recharts` and load the `dataviz` skill.
- Confirm the doc's Jira-dependent assumptions against the real tenant's data profile once it exists:
  - the parent changelog spelling;
  - the estimate and epic-date fields;
  - board-to-team fit.

## Engineering follow-ups

- **CI duration.** The server job takes about 15–20 minutes on CI (about 7 locally) because several tests run full stub-driven syncs. Options:
  - share one synced fixture per test class;
  - or tune CI test parallelism.

  Rule already in force: a test that runs a full sync does it outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
- **Review test gaps** (from the commit 4 security review; the fixes themselves landed):
  - The connection-release test does not fail with the old `client.request()` code in this Ktor/OkHttp version. A blocking interceptor could force the leak window open.
  - `DirectSocketFactory` and `fastFallback(false)` have no isolated tests.
- **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- **Details page: the sync-jobs history doesn't auto-refresh.** Only the summary above it (connection, current job, counts) refetches every 5 s while a job is open, so a history row keeps saying Running until a reload. Refresh the history query on the same condition. (Found by the v0.2.0 e2e journey.)
- **Two connections to one Jira site are allowed** (different project scopes). Confirm this is the wanted behaviour once real usage exists.

## Security and operations

- **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory. That's safe because the web Deployment runs one replica with Recreate. Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- **Atlassian API-token quota.** API-token traffic is excluded from the 2026 points-based rate limits, but Atlassian is evaluating a quota for tokens "with advance notice". Re-check developer.atlassian.com/changelog before releases.
- **Dependabot #12, OpenTelemetry 1.66.** Held until an `opentelemetry-instrumentation-bom-alpha` release pairs with it.

## Sibling handoffs (the user's to dispatch)

- `~/Sources/covenant/flow-hardening-handoff.md`, `~/Sources/lettuce/flow-hardening-handoff.md` and `~/Sources/toadie/flow-hardening-handoff.md`: guardrails and fixes found while building Flow. Flow never edits sibling repos.
