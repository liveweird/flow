# Product backlog

Updated 2026-09-27. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/`.

Entries are proposals, not delivery commitments.

## Next: finish v0.2.0 (Jira ingestion, draft PR #15)

1. **Leftovers from the admin pages.**
   - `web/CLAUDE.md`'s "Data sources" section doesn't cover the details, job history, raw-issue inspector or data-profile pages yet.
   - Web branch coverage is 87.97% against an 87% floor; add tests to lift it above 88.5%.
2. **The release commit.**
   - An e2e journey against the compose `jira-stub`:
     1. Create a data source and run Test connection.
     2. Sync now and wait for SUCCEEDED.
     3. Open the inspector and the profile.
     4. Check that a non-admin sees no nav leaf.
   - Its scenario doc and coverage-map line.
   - The v0.2.0 changelog in EN and PL, plus `APP_VERSION`.
   - A docs sweep: the README "Connecting Jira" section still says the profile has no page in the app.
3. **Ship.**
   - Full gates, rebuild the compose stack (the local one predates phase 2) and run the e2e suite.
   - Mark PR #15 ready and merge when CI is green.

## Then: the phase 2 exit — real Jira (needs the user)

- Create an Atlassian service account with a scoped, read-only API token (scopes in `.claude/docs/jira-integration.md`).
- Add the data source, run Test connection, and fix any scope gaps it reports.
- Backfill 24 months, then read the data profile together (runbook: `.claude/docs/ingestion.md`, "Reading the data profile after the first real sync").
- Things to confirm on the real tenant, where the spike marked them uncertain:
  - Basic vs Bearer auth;
  - bulk-changelog availability and its per-request cap;
  - the search page-size ceiling;
  - how Sprint changes appear in the changelog.

## Phase 3: requirements

- Drafted from the data profile, then decided in short rounds:
  - key assumptions;
  - the conceptual model (work item levels; status → stage mapping with commitment and delivery points; team attribution over time; sprints, worklogs, estimates);
  - domain invariants and constraints;
  - the flow-metric definitions.
- Output: `.claude/docs/domain-model.md` plus a decisions log.
- After that: the metric catalogue, the interpretation layer (a `metrics` schema), and the first dashboards (port Lettuce's `@mantine/charts` + `recharts`).

## Engineering follow-ups

- **CI duration.** The server job takes about 15–20 minutes on CI (about 7 locally) because several tests run full stub-driven syncs. Options:
  - share one synced fixture per test class;
  - or tune CI test parallelism.

  Rule already in force: a test that runs a full sync does it outside `testApplication` (its `runTest` timeout ends in `UncompletedCoroutinesError`).
- **Review test gaps** (from the commit 4 security review; the fixes themselves landed):
  - The connection-release test does not fail with the old `client.request()` code in this Ktor/OkHttp version. A blocking interceptor could force the leak window open.
  - `DirectSocketFactory` and `fastFallback(false)` have no isolated tests.
- **Data profile: multi-project boards.** A board whose filter spans several projects shows no observed or unmapped statuses, because `BoardRef` carries a single project key. Revisit if real boards span projects.
- **Two connections to one Jira site are allowed** (different project scopes). Confirm this is the wanted behaviour once real usage exists.

## Security and operations

- **Instance-local auth state.** The login lockout, reset throttle and MFA challenges live in memory. That's safe because the web Deployment runs one replica with Recreate. Moving to several web replicas first needs Lettuce's database-backed version (its V81 auth-state tables).
- **Atlassian API-token quota.** API-token traffic is excluded from the 2026 points-based rate limits, but Atlassian is evaluating a quota for tokens "with advance notice". Re-check developer.atlassian.com/changelog before releases.
- **Dependabot #12, OpenTelemetry 1.66.** Held until an `opentelemetry-instrumentation-bom-alpha` release pairs with it.

## Sibling handoffs (the user's to dispatch)

- `~/Sources/covenant/flow-hardening-handoff.md`, `~/Sources/lettuce/flow-hardening-handoff.md` and `~/Sources/toadie/flow-hardening-handoff.md`: guardrails and fixes found while building Flow. Flow never edits sibling repos.
