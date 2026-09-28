# Product backlog

Updated 2026-09-28. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

## Phase 3: the domain model — implementation in progress (v0.3.0)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A16 override the body; A11–A16 are judgement calls made on 2026-09-28 and need a review.

- **Done and merged:**
  - M1 (PR #22): sample data, V14 norm gaps, the Jira `+0000` timestamp fix, V15 metrics config, dated Jira-user membership, per-connection config API.
  - M2 (PR #23): the settings page, Jira members card and per-connection config page.
  - The configuration e2e journeys (PR #24).
- **M3 (derivation), in progress** on `feat/v0.3.0-m3-derivation`:
  - Commit 7 is done: V16 star, DERIVE job, kernels, task and epic facts. Two review rounds followed: purge of derived rows, lost config revisions, real Jira estimate fields, effective-dated bridges, batching.
  - Commit 8 is done: sprint scope and facts, D13 snapshots, A3 default capacity, the golden sprint exact match, and the Sprint field resolved by id.
- **Next:**
  1. **Commit 9, worklog facts.** Parked on `wip/m3-9a-worklog-facts`, green alone but red in the full suite. `MetricsDerivationTest` assumes `hours_per_day` = 8, but another test changes the global settings singleton and doesn't restore it. Make the test read the value used at derive time, or restore settings in that other test. Then finish commit 9: epic plan baselines and PV curves, `agg_daily_*`, the re-derive digest and the scale-20 performance run.
  2. **Push M3 as a PR** and merge when CI is green.
  3. **M4:** the reports API and pages for reports 1–8, plus the reports e2e.
  4. **M5:** reports 9–16 (A7 EVM, A8 cost matrix), the A9 overview Home and the v0.3.0 release.
- **Small follow-ups from M2 and M3:**
  - The Jira member picker resolves names from the first 100 unit people. Page through, or look up by account id.
  - "End membership" uses UTC; consider the configured zone.
  - Surface `derive_runs.row_counts.sprintFieldUnresolved` in report 14 (data quality).
  - `fact_worklog`'s epic domain uses the epic's current domain, not as-of.
  - Read `hoursPerDay` from Jira's time-tracking configuration (A5).
  - A per-domain status→stage override UI.
  - Seed memberships from the Team field (D1).
  - Cache validators for the report endpoints.

## Engineering follow-ups

- **CI duration — resolved (2026-09-28).** `NormalizationPipelineTest` (14 tests, was 421s),
  `JiraSyncPipelineTest` (9 tests, was 138s) and `DataProfileTest` (was 30s) each drove a complete
  stub-driven SYNC from scratch per test, though most only read the result — 87% of the ~10.7 min
  local server test run. `SyncedStubFixture` (`.claude/docs/testing.md` "Shared synced fixture")
  now runs that backfill once per JVM fork; read-only tests share its one connection, and tests
  whose subject is REPROCESS/RECONCILE/a raw-row simulation clone its raw rows
  (`SyncedStubFixture.cloneRawData`) instead of re-syncing. Measured before/after (local,
  `./gradlew :server:test`): the three classes together 589s → ~117s (76.9s + 8.8s + 30.6s), full
  `:server:test` 10.7 min → 2m54s. Rule already in force: a test that runs a full sync does it
  outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
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
