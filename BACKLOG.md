# Product backlog

Updated 2026-09-29. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/`.

Entries are proposals, not delivery commitments.

## Next: the phase 2 exit — real Jira (needs the user)

- Create an Atlassian service account with a scoped, read-only API token (scopes in `.claude/docs/jira-integration.md`).
- Add the data source, run Test connection, and fix any scope gaps it reports.
- Backfill 24 months, then read the data profile together (runbook: `.claude/docs/ingestion.md`, "Reading the data profile after the first real sync").
- The first sync will bring adjustments (A10): the defaults the metrics configuration ships with (status → stage, the estimate and epic fields, the work-category field) meet real data for the first time.
- Things to confirm on the real tenant, where the spike marked them uncertain:
  - Basic vs Bearer auth;
  - bulk-changelog availability and its per-request cap;
  - the search page-size ceiling;
  - how Sprint changes appear in the changelog.

## Phase 3: the domain model — done in v0.3.0 (released 2026-09-29)

Plan: `~/.claude/plans/flow-phase3-metrics.md`. The §0 amendments A1–A27 override the body. A11–A16 were overnight judgement calls (approved 2026-09-28), A23, A25, A26 and A27 approved 2026-09-29; A17–A21 came from the measure contract (`.claude/docs/measures.md`); A22 from the 9d review.

- **Delivered:**
  - M1 (PR #22), M2 (PR #23), the configuration e2e (PR #24), Dependabot #17;
  - M3, the derivation (PR #26): the V16/V17 star, DERIVE, the facts, `agg_daily_wip`/`agg_daily_flow`, the invariant-12 digest, and the scale-20 perf check (DERIVE 136 s cold on 24k issues);
  - M4, the reports API (PR #27) and the report pages 1–8, the estimation batch and the reports e2e (PR #28);
  - M5 (PR #29): reports 9–16 (WIP and backlog, aging WIP and blocked time, epic progress, data quality, cost matrix) with their pages, the Home unit overview, the batch-2 e2e, the docs sweep and the v0.3.0 changelog.
- **Release:** v0.3.0 is tagged and released on GitHub (2026-09-29; process in `.claude/docs/app-releases.md`). The first deploy reprocesses the whole tenant (`PROCESSING_VERSION = 2`) and DERIVE follows — minutes on the worker, once.
- **Next:**
  - the real-Jira first sync and the adjustments it brings (A10) — see the section above;
  - a compact always-loaded `conventions.md` (step 2 of the instruction-size work: `CLAUDE.md` plus the always-loaded docs still exceed the budget).
- **Small follow-ups (M2–M5):**
  - The Jira member picker resolves names from the first 100 unit people. Page through, or look up by account id.
  - "End membership" uses UTC; consider the configured zone.
  - `epic_domain_key` uses the epic's current domain, not as-of (no epic-domain history exists).
  - Pin the case of a soft-deleted configured owner on a domain that also has a mapped board (the code resolves it to none, per A22; there is no test yet).
  - Velocity: the per-user snapshot figures are always null (reading the snapshot's scope JSON is not built).
  - Read `hoursPerDay` from Jira's time-tracking configuration (A5).
  - A per-domain status→stage override UI.
  - Seed memberships from the Team field (D1).
  - Cache validators for the report endpoints.
  - Report `meta.configRevision` should be the revision of the last successful DERIVE, not the live one.
  - Round each item's estimate before summing in `DeriveKernels.sprintTotals`, so `fact_sprint` and the per-user report groups agree exactly (today Σ groups can differ from the team by 0.01 MD per sprint when estimates have more than two decimals).
  - Report 7: an `epics` block (cycle time from `fact_epic_delivery`, owner team) — deferred from 12b.
  - `dim_date` rows outside a run's range keep a previous time zone's day bounds after a zone change (range joins could double-match at a stale boundary) — rewrite the whole table on a zone change.
  - Estimated backlog uses the OWN estimate only. A parent estimated through its sub-tasks (`estimate_source = SUBTASKS`) is missing from the backlog (A23). The fix is a composite-estimate bridge.

## Engineering follow-ups

- **CI duration — open.** The 2026-09-28 `SyncedStubFixture` work (three pipeline classes, one shared SYNC per fork) cut the local server test run from 10.7 min to 2m54s, but the suite has grown again with v0.3.0 (DERIVE, the reports); the `server` CI job was far over its budget before PR #33 (ANALYZE, SQL clones; the master `server` job measured 15m08s) — re-measure. Measurements, budgets, causes and the open WHY questions live in `.claude/docs/build-times.md` (`node scripts/timings/ci-times.mjs --branch master` for the current numbers). Rule already in force: a test that runs a full sync does it outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
- **Review test gaps** (from the commit 4 security review; the fixes themselves landed):
  - The connection-release test does not fail with the old `client.request()` code in this Ktor/OkHttp version. A blocking interceptor could force the leak window open.
  - `DirectSocketFactory` and `fastFallback(false)` have no isolated tests.
- **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- **Details page: the sync-jobs history doesn't auto-refresh.** Only the summary above it (connection, current job, counts) refetches every 5 s while a job is open, so a history row keeps saying Running until a reload. Refresh the history query on the same condition. (Found by the v0.2.0 e2e journey.)
- **`dim_date` deadlock risk between two connections' DERIVEs (pre-existing).** Both upsert the global `dim_date` inside their one transaction; if A's `widenDimDate` reaches below B's range start while B holds rows A needs (and vice versa), Postgres raises 40P01 — one derive ends FAILED and is retried. Take `dim_date` out of the per-derive transaction or pre-extend it.
- **`workerSlots=2` gives DERIVE no real parallelism.** Two derives serialize on the `dim_date` row locks held to commit; consider taking `dim_date` out of the per-derive transaction or pre-extending it (same fix as above).
- **Two connections to one Jira site are allowed** (different project scopes). Confirm this is the wanted behaviour once real usage exists.
- **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).** `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`, `JiraConnectorKey` in `DataSourceRoutes.kt` and `DataSourceRequest.jira` are Jira-shaped. Generalising them is speculative until a second connector exists; revisit with GitLab.

## Security and operations

- **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory. That's safe because the web Deployment runs one replica with Recreate. Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- **Atlassian API-token quota.** API-token traffic is excluded from the 2026 points-based rate limits, but Atlassian is evaluating a quota for tokens "with advance notice". Re-check developer.atlassian.com/changelog before releases.
- **Dependabot #12, OpenTelemetry 1.66.** Held until an `opentelemetry-instrumentation-bom-alpha` release pairs with it.

## Sibling handoffs (the user's to dispatch)

- `~/Sources/covenant/flow-hardening-handoff.md`, `~/Sources/lettuce/flow-hardening-handoff.md` and `~/Sources/toadie/flow-hardening-handoff.md`: guardrails and fixes found while building Flow. Flow never edits sibling repos.
