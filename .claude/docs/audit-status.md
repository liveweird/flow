# Audit status

Flow's first checkup, 2026-09-30, run on `bef0e74` (v0.3.0 + PR #32) and fixed the same night;
this record is current as of master `53c4d10` (PR #74), 2026-10-06. It is the starting point for the next
checkup: what was audited, what was fixed, what is still open, what is parked. Use the linked
docs for current behavior; the findings' original `path:line` evidence is in the PR descriptions.

Method: ten `auditor`/`security-auditor` agents, one per dimension (docs, DRY/SRP, dead code,
conventions + spec-first API, tests, UI/i18n/a11y, authn/authz/security, guardrails + times,
architecture/infra), each high/medium claim re-read by the lead before it entered a tier. A
`gate-runner` took the baseline first. The user decided to fix tiers A to D autonomously, one
small commit per concern, grouped into PRs, merged on green.

## Verdicts and where they stand

Every dimension came back **Mostly** (authn/authz itself: **Yes**; merge enforcement: **No**).
Re-audit them, do not assume the table still holds.

| Dimension | Verdict then | Now |
| --- | --- | --- |
| Docs vs code | Mostly (AGENTS/BACKLOG stale) | Drift fixed ([#34](https://github.com/liveweird/flow/pull/34)); the always-loaded budget (B7) is met (35.1k, step 3) |
| DRY / SRP / naming | Mostly | Helpers shared, dead code out ([#39](https://github.com/liveweird/flow/pull/39), [#41](https://github.com/liveweird/flow/pull/41), [#49](https://github.com/liveweird/flow/pull/49)); the big-file splits (D2-D5) are open |
| Conventions, spec-first API | Mostly | Validators, strict readers, spec consistency ([#41](https://github.com/liveweird/flow/pull/41), [#42](https://github.com/liveweird/flow/pull/42)) |
| Tests | Mostly (single fork, stale floors) | Floors raised, fork-safe gate ([#42](https://github.com/liveweird/flow/pull/42)); parallel forks in flight |
| UI / i18n / a11y | Mostly | Fixed for the findings ([#37](https://github.com/liveweird/flow/pull/37), [#38](https://github.com/liveweird/flow/pull/38)); axe/e2e widening (A15) is open |
| Authn / authz, security | Mostly (authz: Yes) | CSP, audit, config bounds fixed ([#42](https://github.com/liveweird/flow/pull/42)); the ingress decision is parked |
| Guardrails, times | Mostly (enforcement: No) | CI hardening and timings landed; branch protection (A1) is the user's |
| Architecture, infra | Mostly | k8s hardening, `.dockerignore`, live deploys passed |

## Completed

| Area | Status | Evidence |
| --- | --- | --- |
| Docs-only PRs | A Markdown-only PR skips the server job. | [#31](https://github.com/liveweird/flow/pull/31), [build-times](build-times.md) |
| Time tracking | CI/local timing tools, budgets, trend flags; `scripts/gates.sh` records the local series; dated WHY entries (A18, A19). | [#32](https://github.com/liveweird/flow/pull/32), [#43](https://github.com/liveweird/flow/pull/43), [build-times](build-times.md) |
| Server suite speed | `ANALYZE` inside DERIVE (the dominant cause: rows=1 estimates), SQL-side fixture clones; PROCESS a page of 50 issues per transaction (stub 16-19 s to ~3 s); pooled test database (-23 %); the seconds-long small tests fixed. | [#33](https://github.com/liveweird/flow/pull/33), [#36](https://github.com/liveweird/flow/pull/36), [#40](https://github.com/liveweird/flow/pull/40), [#44](https://github.com/liveweird/flow/pull/44), [metrics](metrics.md), [build-times](build-times.md) |
| Docs drift (B1-B6) | AGENTS.md is a pointer, BACKLOG/CLAUDE.md/web CLAUDE.md/security/persistence/observability corrected, stale comments fixed. | [#34](https://github.com/liveweird/flow/pull/34) |
| CI and k8s (A11, A13 part, A14, A16) | Toolchain pins and drift guard, Gradle wrapper checksum, npm audit, master `trivy image` scan, `.dockerignore` (context 3.84 GB to 17.7 MB), nightly e2e split into cached, measurable steps; pod `securityContext`, no SA tokens, default-deny NetworkPolicies, `k8s-static` job. | [#35](https://github.com/liveweird/flow/pull/35), [dependencies](dependencies.md) |
| Web (A7, A17, C10, C11, C14, C15) | Person-picker load/error states; vitest -52 % (`css: false`); user link helpers, shared report bits, Polish voice pass, heading levels/table names. | [#37](https://github.com/liveweird/flow/pull/37), [#38](https://github.com/liveweird/flow/pull/38) |
| Server DRY (C1-C7, C9, C12, C13) | Shared report helpers and one route helper, covering-predicate SQL helper, shared JSON/time helpers, dead code out, `breakdown` removed, spec consistency (413, `format: date`, `minimum`). | [#39](https://github.com/liveweird/flow/pull/39), [#41](https://github.com/liveweird/flow/pull/41), [reports](reports.md) |
| Server tier A (A2-A6, A8-A10, A12) | Fork-safe OpenAPI coverage gate, raised Kover/vitest floors, exact CSP pin + the `/openapi` no-CSP production gap closed, bootstrap audit events, `validateDataSource` in the service + strict readers, boot-validated numeric config, Jira `HttpClient` closed on stop, `Cache-Control: no-store` on API JSON, Postgres test image pin test. | [#42](https://github.com/liveweird/flow/pull/42), [security](security.md), [observability](observability.md) |
| Web refactors (D7, D8) | `ReportFilterBar` split; generic `ColumnTable`. | [#49](https://github.com/liveweird/flow/pull/49) |
| Dependency advisory | `brace-expansion` 5.0.12 (high DoS, dev tooling only), caught by the new npm audit gate. | [#50](https://github.com/liveweird/flow/pull/50) |
| Live deployment checks | Compose 13 of 14 pass (perf overlay skipped); k8s 12 of 12 pass (web/worker split, NetworkPolicies probed, read-only root, fail-closed boot, restart mid-SYNC/DERIVE, SIGKILL reclaimed after the 300 s lease). | this record, "Verification and limits" |

CI effect (`node scripts/timings/ci-times.mjs --branch master`, 25 runs): server job ~30 min (max
27m27s, median-5 21m20s at the report) to ~10-11 min (latest 10m18s; still over the 10 min alarm, target 5);
web ~3 min to ~1-2 min (latest 1m09s, in budget); `images` 4m00s is over its alarm.

## In flight

Nothing from the checkup itself: #51 (parallel forks, D1) and the live-check findings (#53) are merged. Other work
open on 2026-10-06 (`gh pr list -R liveweird/flow --state open`):

- [#75](https://github.com/liveweird/flow/pull/75) draft: report cache validators (ETag/304) and an atomic DERIVE
  success mark; waits for the user's cache-posture sign-off.
- [#76](https://github.com/liveweird/flow/pull/76): per-domain status-to-stage overrides (editor UI and DERIVE).
- Branch `fix/sync-history-refresh`: the Details page's sync-jobs history auto-refreshes while a job is open.
- Branch `fix/process-reference-lengths` (in progress): PROCESS reference rows (statuses, people, boards, sprints)
  that overflow their `varchar` columns no longer fail a whole run (BACKLOG).
- Dependabot #12, #47, #64-#67 are held under the dependency rule.

## Still open (the next session)

Done since the checkup (2026-10-01..06): A15 (#61), C8 and the small test/build items (#59), D2-D5 (#60),
D9 WHY 3 and the Kover cost (#62), B7 steps 2-3 (#68, step 3 (2026-10-06)), the class-order fixture dependency (#59, `PostgresTestSupport`
migrates on start), and the `value_text` overflow (V18). What remains:

- **B7** — done: the always-loaded set is 35.1k chars against a ~35k target after step 3 (BACKLOG). Only `web/CLAUDE.md` (36.2k, loaded in `web/` only) could still shrink.
- **Follow-ups**: shutdown audit lines lost to an OTel flush race (BACKLOG). The r2dbc-pool acquire timeout firing at
  ~2x `postgres.pool.maxAcquireTimeSeconds` is documented by design in `persistence.md` (the retry also recovers stale
  connections).
- Not doing: D6 (de-Jira the `Connector` seam) waits for the GitLab connector (BACKLOG).

## Decisions made on the user's behalf

Recorded for review; overturn any of them.

- `AGENTS.md` shrank to a pointer at CLAUDE.md (B1): it had drifted because it was a paraphrase.
- The `breakdown` report parameter was removed (C12): no report read it; it returns with its first consumer.
- `conventions.md` replaces rather than adds (B7), with `testing.md` split; step 3 added `ci.md` and `product.md` (moved text, rows in the CLAUDE.md table).
- `scripts/gates.sh` is the local timing wrapper (A18), not a git hook.
- WIP and Backlog drop inapplicable params from pasted links.
- Older amendments (for example the UNIT EVM basis) predate this checkup and were not re-litigated.
- A13 was split: the uncontroversial hardening shipped; the ingress choice was not made.

## Parked by the user

No implementation scheduled until the user decides:

- **A1** protect `master` (GitHub setting, not code): require `server`, `web`, `e2e-static`,
  `gradle-vulnerability-scan`, up-to-date branch, no bypass actors. `server` is skipped on
  Markdown-only PRs, which GitHub treats as passing.
- **A13** ingress vs a local overlay. Today a bare LoadBalancer answers plain HTTP with a `301`
  in production mode, and `X-Forwarded-For` is client-spoofable behind it (per-IP rate limits key on
  it). Options: an Ingress that overwrites the header + `ClusterIP`, or a documented
  `k8s/local-overlay` with `KTOR_DEVELOPMENT=true` / `HTTP_BEHIND_PROXY=false`.
- ~~The sprint `value_text` fix~~ — decided and done after the checkup: V18 widens the column to `TEXT`.
- Dependabot PRs [#12](https://github.com/liveweird/flow/pull/12),
  [#45](https://github.com/liveweird/flow/pull/45), [#46](https://github.com/liveweird/flow/pull/46),
  [#47](https://github.com/liveweird/flow/pull/47), [#48](https://github.com/liveweird/flow/pull/48):
  not merged, dependency changes need the user.
- Deleting the stale worktrees under `.claude/worktrees/`.

## Verification and limits

Baseline (before fixes): 782 server tests green in 13m03s, OpenAPI `gaps.txt` empty, Kover line
97.97 % / branch 79.74 %, detekt 0, web lint/knip/`check:api` clean, e2e statics green. Every fix
PR ran the local gates and merged on green; the live checks ran 05:02-05:36 on master `41ce803`
(just before #42) against Compose (project `flow`, on :8184) and OrbStack k8s (namespace `flow`,
created for the run and deleted afterwards).

Not established:

- Post-fix figures were not re-baselined in one place; re-run the baseline for the next checkup.
- The live checks predate #42 and the in-flight fix branch; the perf overlay (scale 20) was skipped.
- Old e2e connections in `flow_postgres-data` are still enabled and sync on boot (noise).
- Static review of report-page e2e coverage and Polish voice rests on grep/samples, not a full read.
- Kover across parallel forks is unverified until #51 lands; no exhaustive penetration test,
  load test or visual review was done.

Update this record when a parked topic is resumed, an open item lands, or the next checkup changes
a verdict.
