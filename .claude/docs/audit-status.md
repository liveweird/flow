# Audit status

Flow's second checkup, 2026-10-06, ran from scratch on master `184495a` (v0.4.0, untagged) and was fixed the same day
(PRs [#91](https://github.com/liveweird/flow/pull/91)-[#108](https://github.com/liveweird/flow/pull/108); #107 and #108
came out of the live checks). It is the starting point for the next checkup: what was
audited, what was fixed, what is still open, what is parked. Use the linked docs for current behavior; the findings'
original `path:line` evidence is in the PR descriptions.

The first checkup, 2026-09-30 on `bef0e74` (v0.3.0 + PR #32), found every dimension **Mostly** (authn/authz **Yes**,
merge enforcement **No**) and fixed tiers A to D in PRs #31-#53 and #59-#68 (docs drift, DRY helpers, floors, CI/k8s
hardening, the server-suite speed-up from 13 minutes). Its full record is in the git history of this file:
`git log -p .claude/docs/audit-status.md`.

## Method

One `checkup-lead` (Fable) planned the run and adjudicated it: nine `auditor`/`security-auditor` agents, one per
dimension (docs vs code, DRY/SRP, complexity, conventions + spec-first API, tests, UI/i18n/a11y, authn/authz, wider
security, guardrails + times, architecture/infra), each high/medium claim re-read by the lead before it entered a tier.
A `gate-runner` took the baseline first. The user decided to fix the autonomous tiers in one session, one small commit
per concern, grouped into PRs, merged on green.

Opus reviewed the PRs where the dangerous bugs are the missing ones: the server hardening PR (#96), the statement-timeout
cache (#105) and the worker queue (#100). The queue PR took **three Opus rounds**, each
catching something real: round 1 — the first heartbeat tolerance could run a job past its own lease (a failing attempt
was unbounded), the cancel check had no tolerance, and run writes matched on id alone (a stale run could close the new
run's row); round 2 — the fences lacked a status check (a shutdown `release` after a committed `finish` reopened the job,
a stale `finish` could revive a row a claimer had closed), the budget started after the claim's clock read, and two tests
had ~170 ms of headroom; round 3 — an order-dependent prune test, docs that promised the lease *excludes* a second run
(it only bounds it: a body inside a statement unwinds when the statement returns), and an audit event on a no-op
release. The claim lock itself (an advisory *try*-lock, not `FOR UPDATE`, which every enqueue's FK share lock would
fight) held from round 1. #105 was reviewed against the r2dbc/Exposed bytecode (aborted-commit canary,
generation counter). The remaining DRY, split and web PRs got a Sonnet `reviewer` pass.

## Baseline (before fixes) against 2026-09-30

| | 2026-09-30 | 2026-10-06 |
| --- | --- | --- |
| Server tests | 782, 13m03s (single fork) | 988, 0 failures, test wall 1m45s on 2 forks (+206 tests) |
| OpenAPI `gaps.txt` | empty | empty (0 bytes) |
| Kover line / branch | 97.97 % / 79.74 % | 98.68 % / 82.29 % (floors 97/79) |
| Web tests | (not recorded here) | 1165 in 7.7 s; statements 97.23, branches 93.73, functions 95.71, lines 98.75 (thresholds 94/89/91/97); shuffle x2 green |
| Static | detekt 0, lint/knip/`check:api` clean | detekt up to date, lint/knip/build/`check:api` clean; `lint:api` 2 warnings (`x-sla`, `info.termsOfService`) |
| e2e statics | green | green, 14 specs |
| CI on master | server ~10-11 min, web ~1-2 min, `images` 4m00s | server 6m10s latest (median-5 8m35s), web 2m19s (+42 %, was 1m09s), `images` 3m14s, nightly e2e 11m21s (median 8m58s) |

The `images` job was red from #63 to #83 and green after #85, with nobody noticing: the Trivy scan only ran on a push to
master (fixed by #95).

## Verdicts and where they stand

Re-audit them, do not assume the table still holds.

| Dimension | Verdict 2026-10-06 | Now |
| --- | --- | --- |
| Docs vs code | Mostly | Drift fixed ([#91](https://github.com/liveweird/flow/pull/91)); this record rewritten last |
| DRY / SRP / naming | Mostly | Shared report helpers, one MD formatter, dead code out ([#97](https://github.com/liveweird/flow/pull/97), [#98](https://github.com/liveweird/flow/pull/98)) |
| Complexity (big files) | Mostly | The five biggest stores split and a 400-line guard added ([#103](https://github.com/liveweird/flow/pull/103), [#106](https://github.com/liveweird/flow/pull/106)): no grandfathered file left above 700 lines; Deep dive web split ([#101](https://github.com/liveweird/flow/pull/101)); 21 tables on `ColumnTable` ([#104](https://github.com/liveweird/flow/pull/104)) |
| Conventions, spec-first API | Mostly | Metrics-config bounds and `format: date` ([#94](https://github.com/liveweird/flow/pull/94)); `x-sla`/`termsOfService` warnings knowingly left |
| Tests | Mostly | Floors raised, shuffled vitest in CI ([#92](https://github.com/liveweird/flow/pull/92)); tripwire, limit pins, boundary test ([#99](https://github.com/liveweird/flow/pull/99)) |
| UI / i18n / a11y | Mostly | Mobile nav name, reduced motion, heading-outline guard ([#93](https://github.com/liveweird/flow/pull/93)); the Polish glossary pass is parked |
| Authn / authz | **Yes** | Unchanged; the two credential decisions (2D3, 2D4) are the user's |
| Wider security | Mostly | Report query budget, no-cache `index.html`, `Server: flow`, `/openapi` only where the API is served, audited 429s ([#96](https://github.com/liveweird/flow/pull/96)); nightly image scan ([#95](https://github.com/liveweird/flow/pull/95)) |
| Guardrails, times | Mostly (merge enforcement **No**) | Floors, shuffle gate, timing-tool fix ([#92](https://github.com/liveweird/flow/pull/92)); `replicas: 1` guard ([#102](https://github.com/liveweird/flow/pull/102)); branch protection (A1) is still the user's |
| Architecture, infra | Mostly | Claim race closed, runs fenced, heartbeat bounded ([#100](https://github.com/liveweird/flow/pull/100)); the per-statement timeout round trip removed ([#105](https://github.com/liveweird/flow/pull/105)); DERIVE heap at scale and cold start fixed after the live checks ([#107](https://github.com/liveweird/flow/pull/107), [#108](https://github.com/liveweird/flow/pull/108)) |

## Completed

| Area | What | PRs |
| --- | --- | --- |
| Docs (2B1-2B6, 2B8-2B10, 2B12) | B7 done (always-loaded set 35,487 bytes), Dependabot list, README/`reports.md`/`jira-integration.md` tense and shipped features, WHY 5 answered, `MultiRowInsert.kt`/`Reencrypt.kt` in the layout, 413 on all 20 body-taking operations, the password-reset no-confirmation rotation and the 15-minute token window stated, security updates disabled in repo settings stated, `ColumnTable` claim corrected. | [#91](https://github.com/liveweird/flow/pull/91) |
| Floors and gates (2A1, 2A6, 2C14, 2B13) | Kover 97/79 to 98/82, vitest 94/89/91/97 to 97/93/95/98 (statements/branches/functions/lines); `vitest --sequence.shuffle` in the CI `web` job and `scripts/gates.sh` (it caught one real order dependency in `ReportCycleTime.test.tsx`); `ci-times.mjs` no longer flags cancelled runs `TIMEOUT?`; WHY 12 (the web +42 % is code growth, `web/src` 9.9k to 42.3k lines); dead `checker/` reference out. | [#92](https://github.com/liveweird/flow/pull/92) |
| A11y (2A7, 2A8, 2A13, 2C9) | The mobile-nav `Burger` has an `aria-label` + `aria-expanded` (high) and an axe scan at 390x844, light and dark; `respectReducedMotion: true` in the production theme; `headingOutline.test.tsx` over all 34 routes (it caught four detail pages with no heading while loading/erroring); the "no change" bulk-flag toast became an inline note. | [#93](https://github.com/liveweird/flow/pull/93) |
| Spec and validation (2A2, 2C6, 2C17, 2B11) | `PUT metrics-config` is bounded to the column widths (was a 500 on overflow, NaN passed, a 3-decimal capacity was rounded and bumped the revision on every re-save); `format: date` everywhere, pinned by `OpenApiSpecTest`; `jira-memberships` documented as deliberately unpaged; template exceptions and the cross-cutting status pin in `conventions.md`/`testing.md`. | [#94](https://github.com/liveweird/flow/pull/94) |
| CI (2A5) | The nightly e2e ends with the digest-pinned Trivy scan of `flow-app` (HIGH/CRITICAL, fixable); verified by a dispatch run. | [#95](https://github.com/liveweird/flow/pull/95) |
| Server hardening (2A3, 2A4, 2A10, 2A16, 2C15, 2C18) | `reports.statementTimeoutSeconds` bounds every report read (a cancelled query answers a 500 asking to narrow the selection); `Cache-Control: no-cache` on `index.html` and SPA deep links; `/openapi` mounted only where the role serves the API; audit `path` without the query string; audited per-IP 429s (`rate_limit.exceeded`); `Server: flow`. | [#96](https://github.com/liveweird/flow/pull/96) |
| Web DRY (2C4, 2C5, 2C7, 2C13) | Date/filter primitives in one place (five copies out), one MD rounding rule, dead CSS/exports/i18n key out, restored spies, fake timers instead of real sleeps. | [#97](https://github.com/liveweird/flow/pull/97) |
| Server DRY (2C1, 2C2, 2C3, 2C7, 2C10) | `accountDisplayNames`, `sprintWindow`, `requireDomainName`/`singleConnection`/`requireEpicsByKey` (uniform 400 texts), dead server code out, `nowMillis()` adoption. | [#98](https://github.com/liveweird/flow/pull/98) |
| Tests (2C11, 2C12, 2A14, 2A15) | The shared-fixture tripwire re-verifies on later calls and fails the calling test (it was vacuous when it ran first); at-limit acceptance pins (52/50/500) and 600-char sprint/status names; `PackageBoundaryTest` (`ingest`/`norm`/`jira` never reference `metrics`/`reports`, `metrics` never `reports`); e2e cleanup hooks. | [#99](https://github.com/liveweird/flow/pull/99) |
| Worker queue (2A11, 2A12, 2C16, 2B7) | Per-connection advisory try-lock in `claim`, fenced run writes, bounded and tolerant heartbeat, `id` tiebreaker, honest docs (the lease bounds a stale run, it does not exclude one). | [#100](https://github.com/liveweird/flow/pull/100) |
| Deep dive web (2D2) | `useDeepDiveUrlState`, `useRovingGrid` (12 tests), `deepDiveMatrix.ts` split by concern; the password-reset e2e race fixed. | [#101](https://github.com/liveweird/flow/pull/101) |
| Guard (2D5) | `k8s-static` fails on `replicas != 1` for the web deployment or any HPA. | [#102](https://github.com/liveweird/flow/pull/102) |
| Splits (2D1, part) | `WorkItemStore` 1028 to 340, `SyncJobs` 502 to 230, `IngestWorker` 491 to 345; `SourceFileSizeTest` caps every server main file at 400 lines (11 grandfathered at their size, ceilings only go down). | [#103](https://github.com/liveweird/flow/pull/103) |
| Splits (2D1, rest) | `JiraRawStore` 879 to 273, `MetricsStore` 811 to 179 (facades over per-concern collaborators), `DeriveKernels` (790) to four files of top-level functions; their ceilings gone. | [#106](https://github.com/liveweird/flow/pull/106) |
| DERIVE heap at scale (live check) | Exposed kept every executed statement until the transaction ended, so DERIVE's ~8 MB multi-row INSERTs piled up and a scale-20 DERIVE died at `-Xmx512m`; `insertRows` releases them per chunk (pinned); `-XX:+ExitOnOutOfMemoryError` so an OOM restarts the process instead of leaving it half-dead; WHY 14. | [#107](https://github.com/liveweird/flow/pull/107) |
| Cold start (live check) | Ktor's 10 s module-loading timeout cut the ~63-103 s Flyway retry wait short, so every fresh k8s deploy crashed once; `ktor.application.startupTimeoutMillis: 140000`, pinned between the longest wait and the startup probe. | [#108](https://github.com/liveweird/flow/pull/108) |
| DB round trips (found during 2A3) | A per-physical-connection statement-timeout cache below the pool: Exposed's per-statement `SET STATEMENT_TIMEOUT` is sent only when it changes (forgotten on rollback, failed commit, autocommit; generation counter); WHY 13. | [#105](https://github.com/liveweird/flow/pull/105) |
| `ColumnTable` (2D7) | 21 hand-rolled tables onto it (24 users); the 9 needing sorting, `Tfoot`, row-header `Th`, sticky columns or the grid stay hand-rolled. | [#104](https://github.com/liveweird/flow/pull/104) |

## Notable findings worth remembering

- **#88's post-commit `ANALYZE` budget never applied.** Exposed R2DBC 1.5.0 sends `SET STATEMENT_TIMEOUT = <queryTimeout|0>`
  before every statement, so a `SET LOCAL statement_timeout` is silently overwritten. The budget now rides the
  transaction's `queryTimeout` ([#96](https://github.com/liveweird/flow/pull/96), pinned by a `MetricsAnalyzeTest`
  case). The same rule bounds the worker heartbeat ([#100](https://github.com/liveweird/flow/pull/100)).
- **A cross-process claim race.** `claim`'s "one RUNNING job per connection" check was a plain read, so two worker
  processes could run SYNC and DERIVE on one connection. Fixed by the advisory try-lock ([#100](https://github.com/liveweird/flow/pull/100)),
  with a mutation-checked concurrent-claim test.
- **Exposed's per-statement round trip.** The same `SET` costs a separate awaited round trip per statement: 43 % of
  PROCESS and 49 % of DERIVE round trips (about 10 % off PROCESS, 3-6 % off DERIVE, 8-15 % off a report). A cache below
  the pool that skips an unchanged value shipped in [#105](https://github.com/liveweird/flow/pull/105); its rule: only the
  transaction's `queryTimeout` may set the statement timeout (`persistence.md`).
- **Password-reset e2e race.** The worker sends the email before it stores the new password (deliberate), and the
  store hashes at bcrypt cost 12, so the email can arrive a few hundred ms before the password works. The spec now
  polls the login API first ([#101](https://github.com/liveweird/flow/pull/101)); product behaviour unchanged.
- **DERIVE ran out of heap at production scale — a regression from the 2026-10-01 multi-row writes (WHY 3)**, invisible to
  every test (the stub is 1,200 issues) and found only by the scale-20 live run: 24k issues at `-Xmx512m` passed in
  137.8 s on 2026-09-29 and OOMed on 2026-10-06. Cause and fix: WHY 14, [#107](https://github.com/liveweird/flow/pull/107).
  Lesson: re-run the scale-20 overlay after any change to DERIVE's write path, not only for timings.
- **Cold start crashed once per fresh deploy** although `ingestion.md` promised it waits for Postgres
  ([#108](https://github.com/liveweird/flow/pull/108)); only a live k8s deploy shows it.
- **Master's `images` job was red for six days** (#63 to #83) unnoticed; hence the nightly scan ([#95](https://github.com/liveweird/flow/pull/95)).

## In flight

- [#75](https://github.com/liveweird/flow/pull/75) (draft): report cache validators and an atomic DERIVE success mark,
  waiting for the user's cache-posture sign-off.

## Decisions made on the user's behalf

Recorded for review; overturn any of them.

- **2A9 skipped**: `x-sla` and `info.termsOfService` are registered gaps in the guidelines, so `lint:api` keeps its two
  warnings rather than the spec carrying values nobody owns.
- **2D6 dropped**: `jira-memberships` stays unpaged and is documented as deliberately so, with what bounds it ([#94](https://github.com/liveweird/flow/pull/94)).
- `hasFeature` stays: it is the documented feature-gate accessor awaiting its first consumer ([#97](https://github.com/liveweird/flow/pull/97)).
- A report query over budget is a **500** with a "narrow the selection" problem body, not a 503 (a client retry does not help).
- `rate_limit.exceeded` is coalesced to one event per bucket per minute and carries no client address.
- `Server: flow` replaces the framework's version header.
- The 400-line ratchet: every server main file at or under 400 lines, the old giants frozen at their size, a ceiling
  may only go down (`testing.md`).
- `replicas: 1` is pinned for the web tier only; the worker may scale (claims are serialised per connection).
- The statement-timeout cache lives below the pool and is conditional on one client connection = one server session
  (no transaction-mode PgBouncer).
- `ColumnTable`'s API was not widened: the 9 tables needing more stay hand-rolled.
- The MD rounding rule changed at cent level only (a float-noise half now rounds like its decimal).

## Parked / needs the user

No implementation scheduled until the user decides:

- **A1** protect `master` (GitHub setting): require `server`, `web`, `e2e-static`, `gradle-vulnerability-scan`,
  up-to-date branch, no bypass actors. `server` is skipped on Markdown-only PRs, which GitHub treats as passing.
- **A13** ingress vs a local overlay: a bare LoadBalancer answers plain HTTP with a `301` in production mode and
  `X-Forwarded-For` is client-spoofable behind it (per-IP rate limits key on it).
- **#75**: the cache posture.
- **Dependabot** PRs [#12](https://github.com/liveweird/flow/pull/12), [#47](https://github.com/liveweird/flow/pull/47),
  [#64](https://github.com/liveweird/flow/pull/64)-[#67](https://github.com/liveweird/flow/pull/67) are held under the
  dependency rule; Dependabot alerts and security updates are **disabled** in the repo settings (the owner's call).
- **2C8** the Polish glossary pass: a judgement call that needs a native speaker.
- **2D3** password reset by confirm-link (today anyone who knows an email can force one rotation per 60 s; MFA still applies).
- **2D4** a per-request `credential_revision` check (today a delete/demote takes effect within the 15-minute access-token window).
- A per-user report rate bucket (the statement timeout bounds one query, not a stream of them).
- A PR-time image scan (~3 min per PR); the nightly scan is the compromise.
- **D6** (de-Jira the `Connector` seam, waits for the GitLab connector) and the real-Jira first-sync items.
- Tagging 0.4.0.

Small items left open (not scheduled): the `HEAD` probe per secured `GET` in `AnonymousAccessTest` (2C15's second
half); `ingest/DataSourceService.kt` (421) and the other grandfathered files under the ratchet.

## Verification and limits

Baseline (before fixes) is in the table above. Every fix PR ran its local gates and merged on green
(`./gradlew cleanTest build -Pforks=2`, the web gates with shuffled vitest, Playwright 143/143 where the web changed).

### Live checks (2026-10-06/07)

- **Compose (dev stack):** every UI-touching PR ran the full Playwright suite (143 tests, axe light/dark included)
  against its own image on the compose stack: all green (one password-reset race found and fixed, #101).
- **Compose, scale-20 overlay** (`docker-compose.perf.yaml`, 24k issues, production sizing `-Xmx512m`/768 MiB), on
  master after #107: SYNC (cold) 281.5 s (was 478 s on 2026-09-29), RECONCILE 3.9 s; DERIVE first 38.0 s, board mapped
  50.1 s, warm 52.2 s (were 137.8 / 86.3 / 82.4 s); live heap 130-275 MB of 494; two connections DERIVING at once
  (`workerSlots: 2`, never measured before) 68.9 / 69.3 s, 1.3-1.5x one derive, still inside 512 MB; database 854 MB
  with two connections. Reports far under the 1.5 s p95 target: `aging-wip` UNIT 274/279 ms (547/563 over two
  connections), `data-quality` UNIT wide 131/144, `cycle-time` UNIT wide 108/117, `deep-dive` FLO 3 sprints 37/38 ms
  (4 sprints exceed the 500-task cap at this scale, by design). Before #107 the same run OOMed (above).
- **k8s (OrbStack, namespace `flow`, created for the run and deleted after), 8/8 pass:** pods non-root, read-only
  root, no SA token; role split (worker 404s every API path, and `/openapi` even with `HTTP_EXPOSE_OPENAPI=true`; web
  `index.html` has `Cache-Control: no-cache`, `Server: flow`, HSTS, CSP); NetworkPolicies (a throwaway pod reaches
  nothing; app/worker reach only postgres and the stub; metadata endpoint blocked); fail-closed boot (burned JWT key,
  burned encryption key, stub URL in production mode → CrashLoop with the expected message); stub SYNC + mapping +
  DERIVE; graceful delete mid-SYNC and mid-DERIVE → `sync_job.released`, reclaimed as attempt 2, SUCCEEDED; SIGKILL
  mid-SYNC → reclaimed after the 300 s lease, complete data, no job ever reopened (2 s poller); two workers on one
  connection → never more than one RUNNING across 109 samples, zero overlapping job intervals; web stays at one
  replica. Found the cold-start crash (#108). Noted, not changed: a production web answers `/openapi` with the SPA's
  `index.html` (200, no spec), and deleting a worker pod overlaps old/new for a second or two (`Recreate` governs
  rollouts only) — release/reclaim handled it.

### Not established

- Polish (UI and the glossary) was reviewed by grep and samples only, not a full read by a native speaker.
- No penetration test, load test or visual review; the report budget and the heartbeat are pinned by tests, not by load.
- Post-fix figures are not re-baselined in one place; re-run the baseline next time.
- The checkup covers Jira stub data only; nothing here says how a real instance behaves.
- Worker memory at `workerSlots: 2` fits at scale 20 (above); a real 24-month backfill has ~2x the days — re-measure on
  the first real sync.

Update this record when a parked topic is resumed, an in-flight PR merges, an open item lands, or the next checkup
changes a verdict.
