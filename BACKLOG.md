# Product backlog

Updated 2026-10-06. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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
  - `epic_domain_key` uses the epic's current domain, not as-of (no epic-domain history exists). After the first real sync.
  - Read `hoursPerDay` from Jira's time-tracking configuration (A5). After the first real sync.
  - Seed memberships from the Team field (D1). After the first real sync.
  - Cache validators for the report endpoints.
  - **Parked (2026-10-01; the teams do not estimate via sub-tasks):** estimated backlog uses the OWN estimate only, so a parent estimated through its sub-tasks (`estimate_source = SUBTASKS`) is missing from it. The fix is a composite-estimate bridge, and velocity (`sprintScope`) needs the same composite or backlog-in-sprints is overstated. Number the amendment A28 (A23 is taken).

## Engineering follow-ups

- **CI duration — tracked, now within budget.** The `server` CI job fell from ~30 min (one timeout) to 7–8 min on 2026-09-30 (#33 ANALYZE, #36 PROCESS batching, #40 pooled test DB, #44 slow small tests, #51 two parallel forks); the `web` job to ~1–2 min (#37); the CI timeout is 25 min. Local: `scripts/gates.sh`. Budgets, history and the open WHY questions live in `.claude/docs/build-times.md`; check `node scripts/timings/ci-times.mjs --branch master` at every milestone — a trend jump gets an investigation, never a raised budget. Rule already in force: a test that runs a full sync does it outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
- **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- **DERIVEs still serialize from ANALYZE to commit.** `dim_date` no longer couples them (it is ensured in its own transaction, so the deadlock is gone and the fact-building phase overlaps), but `analyzeDerivedTables` ANALYZEs 16 tables shared by every connection inside the derive transaction, and `SHARE UPDATE EXCLUSIVE` conflicts with itself: a second derive's ANALYZE waits until the first commits, so `workerSlots=2` gives only partial DERIVE parallelism. The ANALYZE must still see the transaction's own uncommitted rows (a skippable/`SKIP_LOCKED` variant would leave stale statistics — build-times WHY 1 regression); a real fix needs per-connection statistics or committing the facts before the aggregates.
- **Check the real tenant's longest Jira names at the first sync.** Every Jira-supplied free-text name is `TEXT` since V19 and an over-long reference KEY/enum is skipped and logged (`referenceRowsSkipped`, `.claude/docs/ingestion.md` "Reference-row robustness"). What is still bounded is identifiers: per-issue `issue_key`/`project_key` 20, `status_id` 50, account ids 100, `rank` 100, `field_id` 100, `value_id` 200 — a value past one of those is a counted bad row (`issuesFailed`) that stays `needs_processing` forever. Look for `referenceRowsSkipped` and `issuesFailed` after the first real sync; widen what really overflows (the V19 pattern).
- **Two connections to one Jira site are allowed** (different project scopes). Confirm this is the wanted behaviour once real usage exists.
- **D6 — de-Jira the `Connector` seam: not before the GitLab connector (YAGNI).** `ingest/Connector.kt` `testConnection(siteUrl, email, apiToken, projectKeys, authScheme)`, `JiraConnectorKey` in `DataSourceRoutes.kt` and `DataSourceRequest.jira` are Jira-shaped. Generalising them is speculative until a second connector exists; revisit with GitLab.
- **Shutdown audit lines are lost.** `sync_job.released` (the worker's lease release on a graceful stop) is missing from the log on graceful restarts — seen live on two of them; the DB release does happen. Suspected cause: the OpenTelemetry console-exporter flush racing the Ktor stop hook.

## Checkup 2026-09-30 — what is left (record: `.claude/docs/audit-status.md`)

The checkup fixed tiers A–D in PRs #31–#53; the leftovers (A15, C8, D2–D5 and the small test/build items) landed in
PRs #59–#61. What remains is the build-time follow-ups, B7 step 3 and the user's own decisions (item ids as in the
report, `~/.claude/plans/flow-checkup-2026-09-30.md`):

- **Build-time follow-ups** (`build-times.md`): WHY 3/4/9 are answered (2026-10-01); PROCESS's child inserts
  moved to `insertRows` (2026-10-06: a stub pass 2.25 s -> 1.0 s; its `work_items` `batchUpsert` is the remaining
  ~0.27 s, an `ON CONFLICT` multi-row helper would be the next step), the nightly `e2e`
  image-build cache (WHY 7's data decides); `images` is ~3 min and green again since #85 (a base-image libssl CVE).
- **B7 — the always-loaded instruction budget (steps 1-3 done):** step 2 (2026-10-05) moved the feature template to
  `conventions.md`, the fixture narrative to `test-fixtures.md` and `web/CLAUDE.md`'s per-feature sections to
  `web-features.md`, and cut the package tree to one line per package. Step 3 (2026-10-06) moved `CLAUDE.md`'s CI
  paragraph, the full-stack notes, the roadmap history and the donor detail to the new `ci.md` and `product.md`, and
  `testing.md`'s harness bullets, setup/forks narrative, static-analysis detail and frontend-test internals to
  `test-fixtures.md`/`ci.md` (moved verbatim, one-line rules kept). The always-loaded set is now 35.1k chars (from
  40.3k, originally 59.0k), on the ~35k target. What remains is optional: `web/CLAUDE.md` is 36.2k (from 72.8k) and
  loads only when working in `web/`.
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
