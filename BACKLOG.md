# Product backlog

Updated 2026-09-30. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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
  - the real-Jira first sync and the adjustments it brings (A10) — see the section above.
- **Small follow-ups (M2–M5):**
  - The Jira member picker resolves names from the first 100 unit people. Page through, or look up by account id.
  - "End membership" uses UTC; consider the configured zone.
  - `epic_domain_key` uses the epic's current domain, not as-of (no epic-domain history exists). After the first real sync.
  - Velocity: the per-user snapshot figures are always null (reading the snapshot's scope JSON is not built).
  - Read `hoursPerDay` from Jira's time-tracking configuration (A5). After the first real sync.
  - A per-domain status→stage override UI.
  - Seed memberships from the Team field (D1). After the first real sync.
  - Cache validators for the report endpoints.
  - Report 7: an `epics` block (cycle time from `fact_epic_delivery`, owner team) — deferred from 12b.
  - **Parked (2026-10-01; the teams do not estimate via sub-tasks):** estimated backlog uses the OWN estimate only, so a parent estimated through its sub-tasks (`estimate_source = SUBTASKS`) is missing from it. The fix is a composite-estimate bridge, and velocity (`sprintScope`) needs the same composite or backlog-in-sprints is overstated. Number the amendment A28 (A23 is taken).

## Engineering follow-ups

- **CI duration — tracked, now within budget.** The `server` CI job fell from ~30 min (one timeout) to 7–8 min on 2026-09-30 (#33 ANALYZE, #36 PROCESS batching, #40 pooled test DB, #44 slow small tests, #51 two parallel forks); the `web` job to ~1–2 min (#37); the CI timeout is 25 min. Local: `scripts/gates.sh`. Budgets, history and the open WHY questions live in `.claude/docs/build-times.md`; check `node scripts/timings/ci-times.mjs --branch master` at every milestone — a trend jump gets an investigation, never a raised budget. Rule already in force: a test that runs a full sync does it outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
- **Review test gaps** (from the commit 4 security review; the fixes themselves landed):
  - The connection-release test does not fail with the old `client.request()` code in this Ktor/OkHttp version. A blocking interceptor could force the leak window open.
  - `DirectSocketFactory` and `fastFallback(false)` have no isolated tests.
- **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- **Details page: the sync-jobs history doesn't auto-refresh.** Only the summary above it (connection, current job, counts) refetches every 5 s while a job is open, so a history row keeps saying Running until a reload. Refresh the history query on the same condition. (Found by the v0.2.0 e2e journey.)
- **DERIVEs still serialize from ANALYZE to commit.** `dim_date` no longer couples them (it is ensured in its own transaction, so the deadlock is gone and the fact-building phase overlaps), but `analyzeDerivedTables` ANALYZEs 16 tables shared by every connection inside the derive transaction, and `SHARE UPDATE EXCLUSIVE` conflicts with itself: a second derive's ANALYZE waits until the first commits, so `workerSlots=2` gives only partial DERIVE parallelism. The ANALYZE must still see the transaction's own uncommitted rows (a skippable/`SKIP_LOCKED` variant would leave stale statistics — build-times WHY 1 regression); a real fix needs per-connection statistics or committing the facts before the aggregates.
- **PROCESS reference rows can still fail a whole run on an over-long Jira value.** `rebuildReferenceRows` (`jira/JiraProcessStream.kt`) writes statuses, people, boards and sprints outside the per-issue bad-row classifier, into `varchar(100/200/254)` columns (`norm/WorkItemStore.kt`); a sprint or board name past its limit throws Exposed's client-side length check out of `run()`, so every PROCESS fails. Per-issue columns (`issue_type` 50, `status_name` 100, …) are now counted bad rows but stay `needs_processing` forever. Check the real tenant's longest names at the first sync; widen what can realistically overflow to `TEXT` (the V18 pattern).
- **Two connections to one Jira site are allowed** (different project scopes). Confirm this is the wanted behaviour once real usage exists.
- **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).** `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`, `JiraConnectorKey` in `DataSourceRoutes.kt` and `DataSourceRequest.jira` are Jira-shaped. Generalising them is speculative until a second connector exists; revisit with GitLab.
- **Shutdown audit lines are lost.** `sync_job.released` (the worker's lease release on a graceful stop) is missing from the log on graceful restarts — seen live on two of them; the DB release does happen. Suspected cause: the OpenTelemetry console-exporter flush racing the Ktor stop hook.
- **r2dbc-pool acquire timeout reports at 2× the setting.** The pool reports an acquire timeout after twice `postgres.pool.maxAcquireTimeSeconds`, not once. Find out why (per-attempt timeout plus a retry?) and fix it or document it in `.claude/docs/persistence.md`.

## Checkup 2026-09-30 — what is left (record: `.claude/docs/audit-status.md`)

The checkup fixed tiers A–D in PRs #31–#53. Still open (item ids as in the report,
`~/.claude/plans/flow-checkup-2026-09-30.md`):

- **A15 — e2e coverage:** the four report pages with no e2e touch (epic estimation accuracy, estimate adjustments,
  reported time, estimated backlog), the axe sweep over every report page and the data-source detail/profile/inspect/
  metrics-config pages, plus a dark-scheme pass.
- **C8 — soft-delete helpers:** `TeamService`'s hand-rolled `markedAsDeleted eq` filters → `UserService.Users.active()`;
  add `SoftDeletable.deleted()` to `infra/db/SoftDelete.kt` for `DataSourceService`.
- **D2–D5 — server structure:** split `metrics/MetricsStore.kt` (tables / rows / store), `MetricsConfigService.kt`
  (settings / per-connection config / options / owner resolution) and `reports/EpicProgressReport.kt` (entry /
  targets / EVM math / rows); break the `ingest` ↔ `metrics` import cycle with a `JobHandler` registry. Each is a pure
  move pinned by the digest, golden and route tests.
- **Build-time follow-ups** (`build-times.md`): WHY 3/4/9 are answered (2026-10-01); what is left is PROCESS on
  `infra/db/MultiRowInsert.kt`'s `insertRows` (the same per-row `batchInsert` cost, ~2.1 s of a 2.4-3.2 s pass), the
  nightly `e2e` image-build cache (WHY 7's data decides), and re-checking `images` after its first master run.
- **B7 — the always-loaded instruction budget, step 3:** step 2 (2026-10-05) moved the feature template to
  `conventions.md`, the fixture narrative to `test-fixtures.md` and `web/CLAUDE.md`'s per-feature sections to
  `web-features.md`, and cut the package tree to one line per package. The always-loaded set is 40.3k chars (from
  59.0k), against a ~35k target, and `web/CLAUDE.md` is 36.2k (from 72.8k). Next candidates: `CLAUDE.md`'s Commands/CI
  paragraph and `testing.md`'s harness bullets.
- **Small:** the five redundant private `ensureMigrated()` copies in tests (use `PostgresTestSupport.ensureMigrated()`);
  a test pinning that a class's first DB touch finds a migrated schema; `-Pforks` input validation.
- **The user's decisions:** A1 — protect `master` (required checks: `server`, `web`, `e2e-static`,
  `gradle-vulnerability-scan`, `k8s-static`; no bypass); A13 — a TLS-terminating Ingress + ClusterIP Service vs a
  documented local-only overlay (behind today's bare LoadBalancer `X-Forwarded-For` is client-supplied); Dependabot
  #45–#48 (held under the dependency rule).

## Security and operations

- **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory. That's safe because the web Deployment runs one replica with Recreate. Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- **Atlassian API-token quota.** API-token traffic is excluded from the 2026 points-based rate limits, but Atlassian is evaluating a quota for tokens "with advance notice". Re-check developer.atlassian.com/changelog before releases.
- **Dependabot #12, OpenTelemetry 1.66.** Held until an `opentelemetry-instrumentation-bom-alpha` release pairs with it.

## Sibling handoffs (the user's to dispatch)

- `~/Sources/covenant/flow-hardening-handoff.md`, `~/Sources/lettuce/flow-hardening-handoff.md` and `~/Sources/toadie/flow-hardening-handoff.md`: guardrails and fixes found while building Flow. Flow never edits sibling repos.
