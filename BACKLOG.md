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

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A25 override the body. A11–A16 were overnight judgement calls (approved 2026-09-28), A23 and A25 approved 2026-09-29; A17–A21 came from the measure contract (`.claude/docs/measures.md`); A22 from the 9d review.

- **Done and merged:**
  - M1 (PR #22), M2 (PR #23), the configuration e2e (PR #24), Dependabot #17;
  - M3, the derivation (PR #26): the V16/V17 star, DERIVE, the facts, `agg_daily_wip`/`agg_daily_flow`, the invariant-12 digest, and the scale-20 perf check (DERIVE 136 s cold on 24k issues);
  - M4, the reports API (PR #27) and the report pages 1–8, the estimation batch and the reports e2e (PR #28).
- **M5** on `feat/v0.3.0-m5`: 15a (`/reports/wip`, `/reports/backlog`) and 15b (`/reports/aging-wip`, `/reports/blocked-time`).
- **Next:**
  1. **M5 15c:** `/reports/epic-progress` (report 15, EVM; A7 + A20). It was due in commit 15 and was missed.
  2. **16:** the flow pages for reports 9–12 and 15.
  3. **17 and 18:** the data quality (14) and cost matrix (16) reports and their pages, and the Home overview (A9).
  4. **19:** the batch-2 e2e, the docs sweep, and the v0.3.0 changelog. No tag or release without asking.
- **Small follow-ups from M2 and M3:**
  - The Jira member picker resolves names from the first 100 unit people. Page through, or look up by account id.
  - "End membership" uses UTC; consider the configured zone.
  - Surface `derive_runs.row_counts.sprintFieldUnresolved` in report 14 (data quality).
  - `epic_domain_key` uses the epic's current domain, not as-of (no epic-domain history exists).
  - Pin the case of a soft-deleted configured owner on a domain that also has a mapped board (the code resolves it to none, per A22; there is no test yet).
  - Velocity: the per-user snapshot figures are always null (reading the snapshot's scope JSON is not built).
  - Read `hoursPerDay` from Jira's time-tracking configuration (A5).
  - A per-domain status→stage override UI.
  - Seed memberships from the Team field (D1).
  - Cache validators for the report endpoints.
  - Round each item's estimate before summing in `DeriveKernels.sprintTotals`, so `fact_sprint` and the per-user report groups agree exactly (today Σ groups can differ from the team by 0.01 MD per sprint when estimates have more than two decimals).
  - Report 7: an `epics` block (cycle time from `fact_epic_delivery`, owner team) — deferred from 12b.
  - Report 14: flag epics whose dates fall outside the PV horizon (no PV curve).
  - `dim_date` rows outside a run's range keep a previous time zone's day bounds after a zone change (range joins could double-match at a stale boundary) — rewrite the whole table on a zone change.
  - Estimated backlog uses the OWN estimate only. A parent estimated through its sub-tasks (`estimate_source = SUBTASKS`) is missing from the backlog (A23). The fix is a composite-estimate bridge.

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
