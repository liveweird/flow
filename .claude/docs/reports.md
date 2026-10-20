# Reports API (v0.3.0)

The read-only reports API over the `metrics` star (plan section 7). It opens with the shared
machinery every report inherits -- the filter parser, `Distribution`, the `meta` block,
`GET /api/v1/reports/filters` and the access posture -- then one section per report. Each number's
operational contract (grain, anchor, attribution, estimate snapshot, missing data, pinning test)
is `.claude/docs/measures.md`; what the numbers mean is `.claude/docs/domain-model.md`.

## Index of the nineteen reports and the Home overview (twenty-three endpoints)

Every endpoint is `GET`, under `/api/v1/reports/`, any signed-in user, no audit (D12).

| # | Report | Endpoint | Section |
|---|---|---|---|
| 1 | Commitments | `/commitments` | [Report 1](#report-1----commitments) |
| 2 | Throughput | `/throughput` | [Report 2](#report-2----throughput) |
| 3 | Task estimation accuracy | `/task-estimation-accuracy` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 4 | Epic estimation accuracy | `/epic-estimation-accuracy` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 5 | Estimate adjustments | `/estimate-adjustments` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 6 (6.1-6.3) | Sprint consistency (commitments vs velocity, carry-over, added scope) | `/sprint-consistency` | [Report 6](#report-6----sprint-consistency) |
| 7 | Cycle time | `/cycle-time` | [Reports 7, 8](#reports-7-8----cycle-time-and-reported-time-ratio) |
| 8 | Reported time / cycle time | `/reported-time-ratio` | [Reports 7, 8](#reports-7-8----cycle-time-and-reported-time-ratio) |
| 9 | WIP | `/wip` | [Reports 9, 10, 13](#reports-9-10-13----wip-and-the-estimated-backlog) |
| 10, 13 | Estimated backlog depth; item counts and backlog in sprints | `/backlog` | [Reports 9, 10, 13](#reports-9-10-13----wip-and-the-estimated-backlog) |
| 11 | Aging WIP | `/aging-wip` | [Reports 11, 12](#reports-11-12----aging-wip-and-blocked-time) |
| 12 | Blocked time | `/blocked-time` | [Reports 11, 12](#reports-11-12----aging-wip-and-blocked-time) |
| 14 | Data quality | `/data-quality` | [Report 14](#report-14----data-quality) |
| 15 | Epic progress (EVM) | `/epic-progress` | [Report 15](#report-15----epic-progress-evm) |
| 16 | Cost matrix and foreign work | `/cost-matrix` | [Report 16](#report-16----cost-matrix-and-foreign-work) |
| 17 | Deep dive (PV, Approximate EV and AC on one epic/task × time matrix) | `/deep-dive` (+ two option lists under `/deep-dive/`; SPA route `/reports/deep-dive`) | [Report 17](#report-17----deep-dive) |
| 18 | Compare people (two people head to head over a date range, across every team) | `/compare-people` (+ the drill `/compare-people/items`; SPA route `/reports/compare-people`) | [Report 18](#report-18----compare-people) |
| 19 | Task estimates (the distribution of the DONE tasks' estimates in MD, at done and at start, in fixed 0.5-MD bars) | `/task-estimates` (+ the drill `/task-estimates/items`; SPA route `/reports/task-estimates`) | [Report 19](#report-19----task-estimates) |
| — | Home overview (up to three picked teams, 90 days beside the previous 90; not a numbered report) | `/home` (+ the drill `/home/items`; SPA route `/`) | [Home overview](#home-overview-get-apiv1reportshome) |

The deep dive is three of the twenty-three endpoints: the report itself and the paged option lists its pickers read.

Report 13 (item counts beside SP, backlog in sprints) is not an endpoint of its own: the item
counts ride reports 1, 2, 6 and 10, and the backlog in sprints rides `/backlog`. The shared
`GET /api/v1/reports/filters` (the pickers' reference data) is not a report and is described
below.

## Access posture

Every `/api/v1/reports/*` operation is **any signed-in user, read-only, no audit event** (D12 --
`.claude/docs/domain-model.md`: "Every signed-in user sees every report at every level,
individuals included"). No `requireAdmin` guard anywhere in this package -- configuration and
data-source pages stay ADMIN-only, but a report itself never does. Since nothing here mutates
anything, there is no `audit(...)` call to add -- the observability doc's per-mutation rule simply
does not apply (`.claude/docs/observability.md`).

The ONE caller-shaped block is the cost matrix's per-person **capacity** (Report 16, A39): a TEAM/USER read of a real team carries
`capacity` and each row's `capacityMd` only for an ADMIN or the filtered team's ACTIVE manager (`TeamService.isActiveManager`,
checked per request in the route -- never cached in a claim -- the same predicate as the capacity editors). It is read-shaping, not a
guard: nobody is denied (no `403`), nothing is audited, every other caller just gets the matrix without the block, and the service
computes the block only when the route says so (no compute-then-strip). A conscious D12 exception of the kind the coefficient
privacy on the capacity GET already is (`.claude/docs/authorization.md`).

The SECOND caller-shaped block is Compare people's per-person capacity (Report 18, A41): the four capacity rows and the `capacity` block are
returned only to an ADMIN or the ACTIVE manager of EVERY team either of the two people belonged to during the period -- all or nothing for the
pair, decided in the route BEFORE the report read, no `403`, no audit, the keys simply absent for everyone else.

## Query budget (`reports.statementTimeoutSeconds`)

Any signed-in user can request an expensive report in a loop (deep dive: 500 tasks x 1100 days), and the pool is 20
connections, so every report read is bounded by a PostgreSQL **statement timeout**: `REPORTS_STATEMENT_TIMEOUT_SECONDS`
(`reports.statementTimeoutSeconds`, default 30, boot-validated 1..3600 by `requireConfigInt` in `configureReportRoutes`).
**One choke point:** `ReportService.reportTransaction { }` -- every report (all nineteen, `filters`, the deep-dive option
lists) opens its transaction through it, never through a bare `suspendTransaction` (a new report copies the idiom; a bare one
would run unbudgeted). It sets Exposed's `queryTimeout` on the transaction.

**Why seconds, and not a `SET LOCAL statement_timeout`:** Exposed's R2DBC executor requests the transaction's `queryTimeout`
(default 0, never null, whole seconds) from the driver before EVERY statement, and the connection layer
(`infra/db/StatementTimeoutCache.kt`, `persistence.md` "Statement timeouts") sends it only when it differs from the connection's
last confirmed value -- so the budget has to be the `queryTimeout`, and nothing else may set the statement timeout. The same
per-statement request is why the value cannot leak: the next Exposed statement on that pooled connection (every statement the app
runs) asks for 0, differs from the remembered 30 s and resets it (`ReportQueryBudgetTest` pins it).
The budget is per STATEMENT, not per request; it does not cover waiting for a pooled connection (`postgres.pool.maxAcquireTimeSeconds`).

**Answer:** a cancelled statement (SQLSTATE `57014`, `plugins/ErrorHandling.kt`'s `respondQueryTimedOut`) is a **500** problem
("The query exceeded its time budget -- narrow the selection and retry"), logged as a WARN with the path (no stack, no query
string). 500 on purpose: it is already declared on every operation and is a cross-cutting status the conformance gate does not
require a test per operation for (`OpenApiCoverageMerge.CROSS_CUTTING_STATUSES`), whereas a 503 would have to be declared and
covered on all 22 report operations. A per-user rate bucket is deliberately NOT part of this (the user's decision).
Tests: `ReportQueryBudgetTest` (a lock held on `metrics.dim_domain` makes `GET /reports/filters` outrun a 1 s budget -> the
problem body; `pg_sleep` cancelled with 57014; no leak to the pool; the knob's boot validation).

## The shared filter parser (`reports/ReportFilter.kt`)

Every report endpoint parses its query string through ONE function,
`Parameters.parseReportFilter(calendar, nowMs, defaultDomainView)`, into a `ReportFilter`. It does
**structural** validation only (ranges, mutual exclusion, ISO date syntax) -- id EXISTENCE (`teamId`,
`sprintId`, `connectionId` against the database) is each report's own service's job once it reads
the filter back, never this function's (the client-supplied-FK `400`-never-`404` idiom
`metrics/MetricsConfigService.kt` already uses throughout). This split is what keeps the parser a
pure, DB-free unit (`ReportFilterTest`) while still keeping every "is this id real" check where the
data actually lives.

- **Period -- exactly one of three, mutually exclusive (`400` otherwise):**
  - `from`/`to` -- ISO dates (`YYYY-MM-DD`), inclusive, in the configured zone
    (`metrics.settings.time_zone`). Default: the trailing 90 days ending today. `to` must not be
    before `from`; the span (`to - from`, inclusive of both ends) must not exceed 1100 days.
    Resolves to a half-open `[fromMs, toMs)` UTC-millis pair via `metrics/WorkingCalendar.kt`'s own
    `dayBoundsMs` -- the parser never reimplements day-boundary math itself.
  - `lastSprints` (1..52) -- each team's own last N CLOSED sprints, unioned at UNIT level
    (`.claude/docs/domain-model.md`: "sprint-relative periods ... are per team"). A report's own
    service resolves the actual sprint ids and reports them in `meta.resolvedSprints`.
  - `sprintId` -- one specific sprint's own period; must be >= 1 (`400` otherwise, like a `lastSprints` outside 1..52).
- **Level -- `teamId`/`accountId` (plan section 7):** no `teamId` -> `UNIT` (groups by team); `teamId`
  alone -> `TEAM` (groups by user); `teamId` AND `accountId` together -> `USER` (the one user).
  `accountId` without `teamId` is `400` -- a user-level read always needs to know within which
  team's roster it is being read (the one exception is Compare people, Report 18: its own parser passes `teamless = true` and reads two
  people across every team; no other route may). `accountId` REPEATS (1..8 distinct, `parseReportFilter(..., multipleAccounts = true)`) on
  commitments, throughput, cycle time, reported time, adjustments, task accuracy, task estimates and WIP only; on every other report a repeat is a `400`. `teamId = 0` is the UNASSIGNED sentinel bucket, a real value, not
  "absent" -- never rejected here (a real check that a POSITIVE `teamId` names an actual team is a
  report service's own job, downstream).
- **Group order.** The org drill (`groups`) of commitments, throughput, sprint consistency and the DONE-item reports is built by
  `orgGroups` (`reports/ReportSupport.kt`), ordered by label (null last), then team id, then account id -- so two groups
  with an equal label never swap between requests.
- **`domainView=TASK|EPIC`** (D3) -- the caller (each report's own route) supplies its own default
  (plan section 7: `EPIC` for PV/EV/AC-shaped measures -- backlog, worklog cost, epic accuracy; `TASK`
  elsewhere); an explicit `domainView` param overrides it.
- **Optional filters** -- `domain`, `activityType`, `workCategory` (the literal `UNCATEGORIZED` is a
  legal value, not just a real category name), `connectionId` -- all structural pass-through here,
  validated against the connection(s) in scope by the report's own service.
- **`tailPercent`** (integer `0..25`, default `5`, spec parameter `ReportTailPercent`) -- the share of the largest values every
  `Distribution` of the report folds into its histogram's final open bucket (`Distribution` below). Parsed for every report, read
  only by the eight whose response carries a `Distribution` (task/epic estimation accuracy, task estimates, estimate adjustments, cycle time,
  blocked time, reported time ratio, data quality; the spec declares it on exactly those paths). Non-integer or outside `0..25` is
  `400`, a repeat is `400`. `ReportFilter.tailPercent` is threaded to every `buildDistribution` call (the function has no default,
  so a new call site cannot forget it). The cycle-time trend reads only `p50`/`p90` and passes `0`.
- **No `breakdown` parameter.** A `breakdown=NONE|DOMAIN|ACTIVITY_TYPE|WORK_CATEGORY` slice of `groups` was parsed by
  every report but changed none of them, so it was removed (checkup C12); a request still carrying it is ignored like
  any unknown parameter name (`.claude/docs/list-endpoints.md`: unknown names stay ignored by deliberate leniency).
  Re-add it WITH its first consuming report, not before.
- **`touchedWithinDays`** (integer `0..3650`, the backlog report's OWN parameter, A38) is not part of the shared filter: `reports/BacklogTouches.kt`
  parses it in the route (`parseTouchedWithinDays`, the WIP `by`/`itemKind` precedent) through `optionalUInt`, so a non-integer, a negative
  number or a repeat is `400`, and `3651` and above is `400`; `0` and absent mean no filter. Declared inline on `/reports/backlog` only.
- Every scalar param goes through the shared strict readers (`infra/paging/QueryParams.kt`, see
  `.claude/docs/list-endpoints.md`): a repeated key is `400` (the `singleValue` rule), an unknown
  enum value is `400` (listing the allowed set).

## `Distribution` (`reports/Distribution.kt`)

The shared shape every per-item measure (cycle time, estimation accuracy, blocked time, ...)
returns: `{n, hidden, mean, min, max, p50, p90, p95, histogram[{from, to, count}], byUser?[{accountId, label, counts}], bucketWidth?}` (`to` is `null` on the one final open bucket; `byUser` is described below and is omitted whenever it is empty; `bucketWidth` is set by Task estimates alone, see "The fixed-width layout" below). `hidden = n <
minSampleSize` (`metrics.settings.min_sample_size`) -- when hidden, only `n` is populated, every
other field stays `null`/empty (`.claude/docs/domain-model.md` "Distributions": "Groups smaller
than the minimum sample size show counts only, with a note").

**Built in pure Kotlin over an already-fetched `List<Double>`, not a SQL fragment -- commit 10a's
own choice, documented in the file itself.** The plan allows either a
`percentile_cont`/`width_bucket` SQL fragment over a whitelisted column, or a pure Kotlin fallback;
commit 10a builds the reports FOUNDATION only, with no report query yet to attach a SQL fragment
to (the per-report queries, added in later commits, are pure-Kotlin consumers of it too -- plan section 10). The Kotlin path also
means `Distribution`'s own math is testable with zero database (`DistributionTest`). It mirrors
PostgreSQL's exact conventions so a later SQL-backed report never disagrees with this one over the
same input: `p50`/`p90`/`p95` use `percentile_cont`'s own linear-interpolation-between-closest-ranks
method; the histogram uses `width_bucket`'s own equal-width-bucket boundary rule (`[from, to)`
except the LAST equal-width bucket, closed on both ends, so the last body value always lands somewhere). A
later report whose dataset is too large to pull wholesale into the JVM can run the SQL aggregate
directly and feed its own numbers into this SAME `Distribution` shape -- the wire contract never
changes, only which side does the arithmetic.

**The long tail (`tailPercent`).** Equal-width buckets from `min` to `max` let a few far outliers stretch every bucket, so the
first one held nearly everything. With `tailPercent = N > 0` (default 5, the report parameter above) `buildDistribution` folds the
values above the (100 − N)th percentile (roughly the top N%; with a small `n` one top value can be more than N%) into ONE final open bucket: `X = percentile_cont((100 - N) / 100)` over all the values; the **tail** is the values
strictly `> X`; the **body** (values `<= X`) gets `histogramBuckets` (10) equal-width buckets over `[min, X]` by the rule above (the
last body bucket closed at `X`), then `{from: X, to: null, count: tail.size}`. No value above `X` (`N = 0`, or ties at the top), or `X` equal to the minimum (most values tie there, e.g. mostly-zero blocked time), means no
open bucket: the histogram is exactly the plain `[min, max]` one, so **`tailPercent=0` reproduces the pre-tail histogram bit for bit**.
Only the histogram moves: `n`, `mean`, `min`, `max`, `p50`/`p90`/`p95` stay over ALL values, the counts always sum to `n`, a hidden
distribution is untouched, and `to` is nullable on the wire only for that last bucket (written as an explicit `null`; `to` has no
Kotlin default because of `encodeDefaults = false`). Pinned by `DistributionTest` (hand-computed small inputs, the `tailPercent=0`
equivalence against an independent re-implementation of the old algorithm on random inputs, ties, `n = 1`, negatives) and, end to end,
`ReportCycleTimeTest` "tailPercent folds the top values into one open bucket and 0 restores the plain histogram" and
`ReportTailPercentTest` (every distribution report, on the wire JSON).

**The fixed-width layout (`fixedStep`, Task estimates only).** `buildDistribution`, `buildDistributionByUser` and `bucketAssignment` take an optional
`fixedStep` (null = everything above). Set (Report 19 passes `FIXED_BUCKET_STEP` = 0.5, MD), the body is not `histogramBuckets` equal-width buckets over
`[min, X]` but bars on the multiples of the width `w`: bar `k` covers `((k − 1)·w, k·w]` -- **left-open, right-closed, labelled by its upper bound** -- and a value `v`
is on bar `ceil(v ÷ w)`, so an exact 1-MD estimate sits on the bar ending at 1 and a 0.1-MD estimate on the bar ending at 0.5. The first bar is the one holding
the minimum, the last the one holding the last body value, and every bar between is drawn, EMPTY ones included (a true picture, not a compressed one). The long tail
keeps its meaning: the cut `X` is the same `percentile_cont((100 - N) / 100)`, **rounded UP to a multiple of `w`**, and the final open bucket
`{from: that multiple, to: null}` holds the values strictly above it (a value exactly ON the rounded cut stays in the last body bar); with nothing above the
rounded cut, or `X` equal to the minimum, there is no open bucket, as in the equal-width layout. **At most 40 bars (`MAX_FIXED_BUCKETS`, the open bucket
counted)**: when the 0.5-MD bars would exceed that, `w` becomes the smallest multiple of 0.5 whose bars fit (1, 1.5, 2, ... MD). The response carries
`bucketWidth` (`w`) so a page can tell the layouts apart and label a bar by its `to` instead of "from – to"; it is OMITTED on every other report (declared with
`EncodeDefault.Mode.NEVER`, because Ktor's default `Json` encodes defaults -- the other reports' wire stays byte-identical) and on a hidden distribution. The
per-person `byUser` shares and the drill use the very same `BucketLayout.indexOf`, so a share, a bar and the items behind it agree by construction. Pinned by
`FixedBucketDistributionTest` (hand-computed bars, the first bar, the rounded-up cut, the 40-bar widening, shares, random `bucketAssignment` agreement).

## `meta` (`reports/ReportMeta.kt`)

Every report response carries a `meta` block beside its own body:
`{derivedAt, configRevision, from, to, level, domainView, resolvedSprints[{teamId, sprintIds}],
minSampleSize}`. `derivedAt` is the latest SUCCEEDED `metrics.derive_runs.finished_at` across the
connection(s) the report actually read (`null` before any connection has ever completed a DERIVE);
`configRevision` is the revision the served figures were DERIVED under
(every DERIVE stamps its rows with the revision it ran under, invariant 12 -- `.claude/docs/domain-model.md`),
read off `metrics.derive_runs.config_revision`, NEVER the live `metrics.settings.config_revision`: between a
configuration change and its DERIVE the live revision is ahead of the data, and `meta` keeps saying what the data
reflects. Per connection it is the newest SUCCEEDED run's revision (`startedAt`, then `id`); with several
connections in scope (a unit-wide report with no `connectionId`) it is the OLDEST of those -- the oldest
configuration any served figure reflects, the same "oldest connection wins" rule `derivedCoverage` applies to the
snapshot reports' DERIVE clock (`derivedAt` stays the latest finish). A connection that never derived
successfully serves no figures and contributes to neither; `configRevision` is `null` (like `derivedAt`) before any
connection in scope has a successful run. `reports/ReportSupport.kt`'s `deriveStamp` computes both.
`from`/`to` are populated only for a
`from`/`to`-selected period; a `lastSprints`/`sprintId` period instead describes itself entirely
through `resolvedSprints`. `ReportFilter.toMeta(...)` assembles the DTO from an already-resolved
filter plus the figures a report's own service computes -- pure, no DB access of its own.

## `GET /api/v1/reports/filters`

The reference data every report's own filter bar/validation reads BEFORE a report is even
requested -- any authenticated user, read-only, **no query parameters at all** (so it declares no
`400` in the spec: `.claude/docs/testing.md`'s "declared statuses" rule -- an endpoint's status list
is trimmed to what it can actually answer, and this one has nothing to reject).

```
ReportFilters {
  teams: [{ id, name, sprints: [{sprintId, name, state, startAt, completeAt}], members: [{accountId, displayName}] }]
  domains: [{ domainKey, domainName }]
  activityTypes: [string]
  workCategories: [string]
  connections: [{ id, name, siteUrl }]
  derivedAt: epoch millis | null
  configRevision: integer | null   // the derived revision, as in `meta` (oldest across the active connections)
  minSampleSize: integer
  timeZone: string   // IANA, metrics.settings.time_zone
}
```

- **`teams`** -- every ACTIVE team (`teams/TeamService.Teams`, cross-feature read), with its
  `managerUserId` (the RAW stored manager, or null -- unlike `GET /teams` NOT blanked for a soft-deleted manager; the Home page defaults to
  the teams the viewer manages), the sprints its board has EVER produced (`metrics.dim_sprint` -- empty until at least one DERIVE has
  run for a connection whose board maps to that team) and its CURRENT (as of the request) D1 Jira
  roster, paired with a display name (`norm.people`, cross-feature read -- the FIRST place
  `/jira-memberships`' bare `accountId` gets a name attached, the same role `/jira-users` already
  plays).
- **`domains`/`activityTypes`/`workCategories`** -- the values a real DERIVE run has ACTUALLY
  produced (`metrics.dim_domain`/`dim_task`/`dim_epic`), never every value a per-connection
  config COULD map to: an admin's configured-but-unused mapping would otherwise clutter a filter
  dropdown with a value no report can ever return a row for. `activityTypes` is the union of `dim_task`'s types and the non-null
  `fact_worklog` ones (two `SELECT DISTINCT`s merged, sorted), so a type that appears solely on epic- or above-epic-logged worklogs
  (A31; e.g. "Program") is offered too -- report 16's rows carry it and an `activityType` slice by that name is valid.
- **`connections`** -- every ACTIVE connection (not additionally filtered to `enabled` -- a
  connection an admin has paused from syncing still owns whatever it already derived, and its
  history stays a legitimate filter choice), id, name and `siteUrl` ONLY. `siteUrl` is the Jira site's origin
  (`https://<site>.atlassian.net`, the organisation's own host, not a secret) -- the one `settings` key a report-reader sees, read
  straight from `source_connections.settings`; the SPA builds every issue-key link `{siteUrl}/browse/{KEY}` from it
  (`JiraKeyLink`, `web-features.md` "Jira links"). Never any other `settings` key (email, project keys, cloud id) nor the
  encrypted API token (`.claude/docs/security.md`); `ReportRoutesTest` pins the exact key set.
- **`timeZone`** -- the configured IANA zone (`metrics.settings.time_zone`, default `Europe/Warsaw`) that
  `from`/`to` are read in. The SPA renders every report date and computes its period presets, "today" and
  the date-picker maximum in this zone (never UTC, never the browser's), so a preset chosen at 00:30
  local on the 1st means the same day the server resolves.
- **`derivedAt`/`configRevision`/`minSampleSize`** -- the SAME figures every unit-wide report's own `meta`
  block carries, so a client can label a still-warming-up connection ("no data derived yet")
  without a second round trip.

`reports/ReportService.kt`'s `filters()` runs three cross-feature reads inside its own
transaction, listed in `.claude/docs/persistence.md`'s cross-feature-read rule:
`teams/TeamService.Teams`, `norm/WorkItemStore.People`, `ingest/DataSourceService.Connections`.

## Wiring

`reports/ReportRoutes.kt`'s `configureReportRoutes()` is this package's composition root -- it
mirrors `metrics/Metrics.kt`'s own shape rather than `infra/db/Database.kt`'s: this package
composes services `configureDatabase`/`configureJira`/`configureMetrics` have ALREADY published
(`MetricsSettingsServiceKey`, `TeamMembershipServiceKey`), so it constructs and publishes
`ReportService` right here, inside its own configure function, rather than in `Database.kt` itself
-- which runs BEFORE `configureMetrics` and so cannot see those keys yet. Registered in
`application.yaml` in the "-- features" group, after the metrics feature routes it reads from.

## Drill to items (`…/items`)

A bar of a report's chart is a number over a set of items; the drill lists that set. Each drillable report has a read-only
sub-resource `GET /api/v1/reports/<report>/items` (any signed-in user, no audit, `call.caller()` first, like every report) that takes
the PARENT report's full parameter list (the same `$ref`s; `accountId` repeats wherever the parent allows it) plus the bar's
identity, and answers the one report-shaped list every drill shares -- a deliberate exception to the paged-list envelope
(`.claude/docs/list-endpoints.md`):

```
ReportDrillItems { meta: ReportMeta, bar: ReportDrillBar, items: [ReportDrillItem], total, truncated }
ReportDrillBar   { count, sumMd: number|null, from: number|null, to: number|null }  // sumMd for MD bars, from/to for a histogram bucket
ReportDrillItem  { issueKey, summary|null, itemKind: TASK|EPIC|WORKLOG, accountId|null, assignee|null, teamId|null, anchorAt|null, figure|null }
```

- **One computation, not two.** The drill runs the report's OWN fetch over the same filter and applies the report's OWN per-item
  assignment to keep the rows of one bar -- `bar` is the report's bar recomputed over exactly the listed set, so `bar.count` equals the
  bar's item count and `bar.sumMd` its MD (to the two-decimal rounding), and `total == bar.count` always. `figure` is the bar's own
  per-item value (MD at done for throughput; `null` = unestimated, counted as 0 MD); `anchorAt` the instant the report anchors the
  item to (`done_at`); `assignee` the Jira display name of `accountId` (the id when unknown). Sorted `figure` descending (nulls last),
  newest `anchorAt`, then issue key; at most `DRILL_MAX_ITEMS` = 500 are listed (`truncated`), `total`/`bar` always cover every item.
  Labels (key, summary, names) are fetched for the LISTED items only (`workItemLabels`, `accountDisplayNames`).
- **`segmentAccountId`** narrows a STACKED bar to one picked person's share (USER level only; it must be one of the request's
  `accountId`s, else `400`): the listed items AND the `bar`/`total` are that person's, so the segment's count equals the parent's
  `byUser` entry and the segments of a bar add up to the whole bar.
- **Reconciliation is a gate.** Each drill ships a server test that, for EVERY bar the parent report returns on the derived fixture,
  asserts `drill.bar` == the bar (count and Σ MD), the listed items add up to it, every item lies in the bar by an independent
  re-derivation, and the segments equal `byUser`; a failing reconciliation is a real divergence, never grounds to loosen the test.
- **`400`s** (after `call.caller()` and the parent's own parser `400`s): a missing identity parameter, a `segmentAccountId` below
  USER level or outside the picks. Declared statuses per path: `200`, `400`, `401`, `500`.
- **Code**: `reports/ReportDrill.kt` (the DTOs, `DRILL_MAX_ITEMS`, `drillItems`, `requireSegment`), `reports/ReportDrillRoutes.kt` (the
  `@Resource` classes and `drillRoutes`, registered beside the report routes), one `<Name>Drill.kt` beside each drillable report.
  The web side (drawer, URL state, clickable table figures): `.claude/docs/web-features.md` "Drill to items".

| Path | Identity parameters | Items | `figure` |
|---|---|---|---|
| `/throughput/items` | `bucketStart` (the bucket's first day, ISO date) with `bucket` -- or, without `bucketStart`, a `sprintId` period (the sprint mode) | the `fetchThroughputTasks` rows whose `bucketStart(done_at)` is it; in sprint mode the sprint's `DELIVERED` scope rows | `estimate_at_done_md`; sprint mode: the scope's estimate at done |
| `/sprint-consistency/items` | a `sprintId` period (REQUIRED -- any other period is `400`) and `bucket` ∈ `COMMITTED, ADDED, REMOVED, FINAL, DELIVERED, CARRIED_OVER, DROPPED` (REQUIRED) | the sprint's `fact_sprint_scope` rows (the report's own `fetchScopeEntries`, with the `activityType`/`workCategory` slice) kept by `SprintScopeItem.inBucket(bucket)` | `SprintScopeItem.bucketMd(bucket)`: the estimate at commitment (COMMITTED/ADDED/REMOVED), at close (FINAL/CARRIED_OVER/DROPPED) or at done (DELIVERED) |
| `/cycle-time/items` | `view` (`WORKING_DAYS`, `ELAPSED_DAYS`, `EPIC_WORKING_DAYS`, `EPIC_ELAPSED_DAYS`) + `bucketIndex` | the measurable rows whose value lands in that bar of that histogram | working / elapsed days |
| `/reported-time-ratio/items` | `view` (`RATIO`, `FLOW_EFFICIENCY`) + `bucketIndex` | idem | ratio / fraction |
| `/task-estimation-accuracy/items` | `view` (`AT_START`, `AT_DONE`) + `bucketIndex` | idem (tasks with an `actual ÷ estimate`) | the ratio |
| `/task-estimates/items` | `view` (`AT_DONE`, `AT_START`) + `bucketIndex` | idem (tasks with an estimate in that view; the fixed 0.5-MD bars) | the estimate in MD |
| `/epic-estimation-accuracy/items` | `view` (`AT_START`, `AT_DONE`) + `bucketIndex` | idem (epics: `actual ÷ OWN estimate`) | the ratio |
| `/estimate-adjustments/items` | `view` (`TASKS`, `EPICS`) + `bucketIndex` | idem (the DONE items whose estimate CHANGED start -> done) | the signed fractional change |
| `/blocked-time/items` | `view` (`BLOCKED_WORKING_DAYS`, `SHARE_OF_CYCLE`) + `bucketIndex` + the parent's `itemKind` | idem (tasks, epics or both, each row's `itemKind` saying which) | blocked working days / share of cycle |
| `/cost-matrix/items` | `by` (`ACTIVITY_TYPE`, `WORK_CATEGORY`) + `key` (repeated, 1..100; the folded "Other" sends them all) | the tasks (sub-tasks rolled up to the parent; epics logged on directly as EPIC) with worklogs in that slice in the period, `assignee` = their authors | MD logged in the slice (anchor: the latest `started_at`) |
| `/compare-people/items` | `view` (the row's `drill`: `DELIVERED`, `CYCLE_WORKING_DAYS`, `CYCLE_ELAPSED_DAYS`, `REPORTED_TIME_RATIO`, `FLOW_EFFICIENCY`, `ACCURACY_AT_START`, `ACCURACY_AT_DONE`, `ADJUSTED`, `ADJUSTMENT_CHANGE`, `WIP_TASK_DAYS`, `WIP_AT_END`, `BLOCKED`, `LOGGED`, `FOREIGN`, `LATE`, `NO_WORKLOGS`, `NO_ESTIMATE`) + `segmentAccountId` (REQUIRED, one of the two picks) + `bucketIndex` (optional, the two cycle-time views) | the cell's own set -- see "Report 18"; the first consumer of `itemKind` `WORKLOG` | the row's unit (MD, days, a ratio, the age in working days, a worklog's MD...) |

`/throughput/items` runs `fetchThroughputTasks` (the rows `byBucket` aggregates) and the same pure `bucketStart(doneAt, zone, bucket)`
the report groups by, in the configured zone (a task done Monday 00:30 local is that Monday's, whatever the UTC date). A request
without `bucketStart` AND without a `sprintId` period is `400` ("bucketStart or a sprintId period is required"); with a `sprintId`
period and no `bucketStart` it is the sprint mode, which delegates to the sprint-scope drill below with the `DELIVERED` bucket (the
sprint view carries no activity/category slice, so the mode drops them). Tests: `ReportDrillThroughputTest` (every bucket at UNIT,
TEAM and USER with two picks -- union and both segments -- for WEEK and MONTH; the bucket-zone rule and the 501-item truncation on
hand-built rows; the `400`s).

**The sprint-scope drill is ONE endpoint for every sprint bar.** Commitments' initial/final, Throughput's sprint view, Sprint
consistency's seven figures and the Home chart are the same `fact_sprint_scope` rows read by the kernel's bucket predicates
(`metrics/DeriveSprintKernels.kt`: `SprintBucket`, `SprintScopeItem.inBucket(bucket)`, `SprintScopeItem.bucketMd(bucket)` -- the very
functions `sprintTotals` is expressed through, so a bar and its items cannot disagree; a REMOVED row, which still carries
`committed = true`, lists under REMOVED only). `reports/SprintScopeDrill.kt` (`sprintScopeItems`) fetches the sprint's scope rows
exactly as Sprint consistency does (`fetchScopeEntries`), keeps the bucket, and recomputes the bar with the kernel's own rounded sum
(`sumMd`). `anchorAt` is the sprint's `completedAt` (`null` while it is open), `teamId` the sprint's team, `accountId`/`assignee` the
assignee at commitment -- which is also what picked accounts and `segmentAccountId` narrow by. **Wart: this route takes several
`accountId`s (`multipleAccounts = true`, 1..8) although its parent report takes one**, because Commitments' and Throughput's stacked
USER-level segments need them; the union of the picks is the sum of each pick's own single-account figures. **A sprint id is not unique across connections** (two
connections to one Jira site share it, under different teams): without `teamId` the drill is, like the report, the union of every team's
sprint of that id, so the web always sends the bar's own team (`sprint:<teamId>:<sprintId>:<BUCKET>`, `sprintDrillFilter`) and the list
is that one team's sprint -- `ReportDrillSprintTeamPinTest` (two hand-built connections, the same sprint and issue ids). Tests:
`ReportDrillSprintScopeTest` (every sprint of the derived fixture, closed and open, at UNIT and TEAM level and every bucket: the bar equals
Sprint consistency's figure pair, COMMITTED/FINAL equal Commitments' `initial`/`final`, DELIVERED equals Throughput's `bySprint`, the items
equal an independent re-derivation of the bucket from the stored rows; USER level with two picks -- union and both segments against
every report's `byUser` and each pick's own single-account report; sliced requests against the sliced figures and the sprint mode
ignoring the slice; a hand-built added/removed sprint; the `400`s), `DeriveKernelsTest` ("sprintTotals equals the per-bucket predicates
applied item by item") and the unchanged digest tests (`DerivedStubFixtureTest`, `MetricsDigestTest`) for the refactor.

**Histogram drills (`DistributionDrill.kt`).** A `Distribution` bar is a bucket of its `histogram`, and the ONE place a value meets a
bucket is the private `BucketLayout.indexOf` in `Distribution.kt`; the drill never re-implements it. `bucketAssignment(values, minSampleSize,
tailPercent[, fixedStep])` exposes it (the `histogram` exactly as `buildDistribution` draws it plus one bucket index per input value, `null` while the
distribution is hidden), and `distributionDrillItems(...)` is the shared body of every histogram `…/items` route: the report's own fetch
(`rows`), the report's own per-item value (`valueOf`, `null` = the report excludes the item, so it is in no bucket -- the report's
distributions are built from the very same functions: `workingDaysOf`/`elapsedDaysOf`, `ratioValueOf`/`flowEfficiencyValueOf`, the epics'
`workingDays`/`elapsedDays`), `bucketAssignment`, then the rows of bucket `bucketIndex`. `bar` carries the bucket's `from`/`to` (`to` null =
the open tail) and no MD; `figure` is the item's value. The layout is always the UNION's -- at USER level one call over every picked person's
values, identical to the report's -- and `segmentAccountId` then narrows the listed items, `bar` and `total` to that person, so a segment's
count is the bucket's `byUser` count. `bucketIndex` is 0-based into the chosen distribution's `histogram` (the tail last); a missing or
unknown `view`, a `bucketIndex` that is not a non-negative integer or lies outside the histogram, and a hidden distribution (no bar exists:
fewer than `minSampleSize` measured items; at USER level always the case for the epic views, whose block is the all-zero answer) are `400`s
(`requireEnum`, `requireBucketIndex` in `ReportDrill.kt`). Both routes take the parent's full parameter list (`accountId` repeating, 1..8, and
`tailPercent`, which shapes the histogram; `cycle-time/items` also validates the trend's `bucket`, which plays no part in a bar).
Tests: `ReportDrillDistributionTest` (every bucket of every histogram on the derived fixture at UNIT, TEAM and USER with two picks, with the
tail off, at its default and widest, against an independent re-derivation of the values; the epic histograms), `ReportDrillDistributionHandBuiltTest`
(a zero-working-day cycle in bucket 0, every exclusion absent from every bucket, epics with their owner team, the `400` matrix) and `DistributionTest`
(`bucketAssignment` agrees with `buildDistribution`'s histogram for random inputs).

The accuracy and blocked-time drills (`TaskAccuracyDrill.kt`, `EpicAccuracyDrill.kt`, `BlockedTimeDrill.kt`) are three more bodies of the same shape.
Task accuracy takes `accountId` repeating and `segmentAccountId` (its `atStart`/`atDone` carry `byUser`); Epic accuracy and Blocked time take neither
(epics have no user, the blocked histograms no per-person split), so their spec paths declare no `segmentAccountId` and their parents' single
`accountId`. Each drill reuses the report's own functions, never a copy: `ratioAtStartOf` (worked, started, estimated at start) and `ratioAtDoneOf`
(worked, estimated at done) for tasks -- `accuracyOf` builds the report's distributions and exclusion buckets from them --, `AccuracyEpic.ratioAtStart`/`ratioAtDone`
for epics (at USER level the report reads no epics, so the epic drill answers a `400` saying epics are not read per person), and for Blocked time `fetchBlockedItems`
(the report's population for `itemKind` TASK, EPIC or BOTH, one `DoneItem` per task or epic) with `blockedValue` (only a blocked item -- a never-blocked one is in no bar) and
`blockedShare` (only a blocked item with a cycle above zero), the very values the report builds its histograms from. Tests: `ReportDrillAccuracyBlockedTest` (every bucket of every
histogram on the derived fixture -- task accuracy at UNIT, TEAM and USER with two picks, epic accuracy at UNIT and TEAM, blocked time for TASK, EPIC
and BOTH at UNIT, TEAM and USER -- with the tail off, at its default and widest, against an independent re-derivation from the fact rows) and
`ReportDrillAccuracyBlockedHandBuiltTest` (an at-done ratio for a never-started task, an at-start ratio absent for it, 0.00 logged and a zero estimate
in no bucket; an epic whose budget is only the child sum has no ratio; never-blocked items (a zero, a no-cycle and a zero-cycle one) in neither bar, `BOTH` listing both kinds;
the `400` matrix).

**The adjustments drill (`EstimateAdjustmentsDrill.kt`)** lists the DONE items behind one bar of the `tasks` or the `epics` change histogram (`view` `TASKS` / `EPICS`).
Its per-item value is `AdjustmentItem.adjustedChange(window)` -- the SAME function `figuresOf` feeds the report's `changeDistribution` (and `byUser`) with, so
the drill never re-derives the "changed items only" population: an item is in a bar only when it is DONE in the period, measurable (not estimated late,
an estimate both at start and at done) and its two estimates differ; the unchanged, estimated-late, unestimated and backfilled items (`changeExcluded`)
and every open item are in no bar, and the buckets together hold exactly `changeDistribution.n`. `figure` is the signed change (`-0.5` = halved), `anchorAt`
the `done_at`; a task's `accountId` is its assignee at done and `segmentAccountId` (USER level) narrows `TASKS` to one picked person's share (the `byUser`
count), an epic has no account. The epics' histogram is empty at USER level (epics carry no user), so `EPICS` there answers a `400` saying epics are not read
per person. Tests: `ReportDrillAdjustmentsTest` (every bucket of the tasks' histogram on the derived fixture at UNIT, TEAM and USER with two picks, the open
tail off / default / widest, against an independent re-derivation from the fact rows -- the stub's epics never change an estimate, so their histogram is hidden
there and the epic buckets are pinned on hand-built rows --; hand-built tasks and epics for the unchanged / late / unestimated / backfilled / open / sub-task /
out-of-window exclusions and a segment; the `400` matrix).

### Drillable charts, and the ones that are not offered

Every bar and stacked-bar chart of the reports has a drill: Throughput (the period and the sprint charts, the bucket and the person segments), Commitments
(team and user mode), Sprint consistency (its three charts), the Home commitments chart, and every histogram -- cycle time (four, epics block included), reported
time (two), task accuracy (two), task estimates (two), epic accuracy (two), estimate adjustments (tasks and epics) and blocked time (two), and every cell of Compare people's table (one person's figure; its two cycle-time histograms are bars too). The optional late-logging histogram of
Data quality is NOT drillable: its `worst` list already shows the 50 latest-logged worklogs, and a per-worklog drill would add a second path to the same
rows; if wanted later it is one more `view` (`LATE_LOGGING`, `itemKind` `WORKLOG`) on a `/data-quality/items` path over `DqWorklog`-shaped rows.
Not offered, and why:

- **Not bar charts**: WIP (end-of-day areas), Estimated backlog (daily-flow areas), Epic progress and the Deep dive burn-up (lines over daily increments and a
  PV spread per day), the cycle-time trend (p50/p90 lines), the aging scatter (its table is the keyboard route). WIP and the trend ARE decomposable at query
  time (`item_stage` covering a day end; the tasks done in a bucket) and would reuse `ReportDrill.kt` -- a follow-up, not this feature.
- **Throughput's cumulative view** (stacked areas) has no chart handler; the bucket table's Delivered figures still open the slot's own items.
- **The frozen snapshot figures** (the drift badge) are drawn by no chart; a drill lists the LIVE rows (the per-item frozen scope,
  `fact_sprint_snapshot.scope`, exists if a frozen drill is ever wanted).
- **Commitments' capacity line, the cost-matrix heat cells, the Deep dive matrix cells and the exclusion counts under a histogram** (`ExcludedList`) are not
  bars. (The cost matrix's activity-type and work-category DONUTS are drillable: `/cost-matrix/items`, Report 16.) The exclusion buckets are a cheap later extension of the same endpoints.

## Home overview (`GET /api/v1/reports/home`)

The landing page's data in ONE request: the overview of up to three PICKED ACTIVE teams (name order whatever the pick order, zero and empty figures
included so every chart shares one team order), each figure over the trailing 90 days beside the same figure over the 90 days before. Any signed-in user
(D12), no audit. The one parameter is an optional repeated `teamId` (`repeatedLongs`, 1..`HOME_MAX_TEAMS` = 3, ids of at least 1; duplicates collapse
before the bound): each id must be a real ACTIVE team (0, a non-integer, an id beyond the id range, an unknown or an inactive one, or four distinct ids, is a
`400`, the drill's client-FK idiom). Without it the first three active teams in name order are listed -- the same for every caller, because a report's content
never depends on WHO asks (D12; the report cache validators of draft PR #75 rely on it). The "default to the teams I manage" rule therefore lives in the web
(`/reports/filters` carries each team's `managerUserId`) and the SPA always sends explicit ids. The window is the shared parser's default period -- the 90 days ending today in the configured zone -- so a report link with
`from`/`to` of `window` (and the team) reproduces the figure; `previousWindow` is `from − 90 … from − 1` by date arithmetic (`HomeKernels.kt`, never
millisecond arithmetic across a DST change). It is composed from the other reports' own fetches under the UNIT default filter (`fetchAccuracyEpics`,
`fetchDqEpics`, `fetchOpenTasks`/`fetchOpenEpics`, `resolveSprintRows`, `fetchSnapshots`) plus ONE new read of the done tasks of both windows
(`fetchHomeDoneTasks`, ~180 days) that the two windows are split from in Kotlin.

```
HomeOverview {
  meta: ReportMeta                       // level UNIT, domainView TASK, from/to = window, resolvedSprints = each listed team's last closed sprint
  window, previousWindow: { from, to }   // ISO dates in timeZone
  timeZone, epicEstimateGapTolerancePercent
  freshness: [{ connectionId, name, lastSyncAt?, lastDeriveAt? }]         // active connections, name order
  teams: [{ teamId, name,
            lastSprint: HomeSprint | null,                                // section 1
            granularity: { estimateMd, cycleWorkingDays, costMd },        // section 2, HomeFigure each
            accuracy: { tasks, epics }: { pred10, pred25, pred50 },       // section 3
            wipEpics, wipTasks: { count, hidden, p50?, p85?, p95? },       // sections 4, 5
            throughput: { weeks: [{ bucketStart, deliveredMd, deliveredItems }], deliveredMd, deliveredItems, cycleP50, cycleP85 },  // 6
            quality: { tasksNoEstimate, tasksUnassigned, tasksEstimatedLate, epicsNoEstimate, epicsNoDates, epicsEstimateGap } }],  // 7
  unassigned: { doneTasks, openTasks, epics }
}
HomeFigure   { value?, n, hidden, numerator?, previous?: HomePrevious }
HomePrevious { value?, n, hidden, verdict?: BETTER | WORSE, difference? }   // difference = current − previous
```

- **Figures** are `Measured` (`HomeKernels.kt`): a median/percentile is `percentile_cont` of the per-item values, `hidden` (value `null`) under `meta.minSampleSize`
  values like a `Distribution`; counts and sums are never hidden; a PRED is `accuracyMeasures`' share with `numerator` = the items within it (`predWithin`, the same
  epsilon). Per-section definitions, populations and pins: `.claude/docs/measures.md` "Home overview".
- **Cost of a pick**: the reads (`readHomeRows`, sprints, snapshots) stay UNIT-wide and run once whatever the pick; only the per-team Kotlin (`teamOf`) runs
  for the listed teams. The request is still ONE `reportTransaction`, the trend unchanged. `unassigned` is the work of NO ACTIVE team (no team, or a
  soft-deleted one), always computed against every active team, never against the picked subset -- so another team's work never turns "unassigned"
  because it was not picked. `/home/items` takes no pick (its own required `teamId` names the one team).
- **The trend** (`trendOf`, `compareValues` extracted from Compare people's `compareCells`): previous = side A, current = side B; a tie within the unit's tolerance or
  a side under `minSampleSize` (non-item units) gives no `verdict`; the directions are the `HomeMeasure` enum (granularity medians and cycle percentiles lower, PRED
  and delivery higher, task findings lower). The epic findings, the WIP figures and the last sprint carry no trend (their population is "now").
- **The epic estimate gap** uses `metrics.settings.epic_estimate_gap_tolerance_percent` (V31), echoed as `epicEstimateGapTolerancePercent`, the SAME tolerance as
  Report 14.
- **Cost.** One `reportTransaction`, about 15 statements: settings + holidays, active connections (with their last sync), derive stamp, last derive per connection,
  active teams, last-sprint rows + snapshots, the done tasks (both windows), the accuracy epics, the quality epics (+ `dim_epic`) and the open tasks/epics.
  Everything else is Kotlin over those rows (the per-item working-day walk of the WIP ages is the largest piece). No index, no DERIVE change, no new aggregate.
- **Access.** Per-connection last-sync timestamps are visible to every signed-in user: a conscious D12 choice (the connection names are already public through
  `/reports/filters`; the ADMIN-only `sync-status` stays the detailed view).

### `GET /api/v1/reports/home/items` -- drill to items

The items behind one figure of one team (`HomeDrill.kt`): `view` (required) and `teamId` (required, a real active team; `0`, an unknown or an inactive id is a `400`).
The answer is the shared drill shape (`{meta, bar, items[<=500], total, truncated}`, "Drill to items" above); each view runs the overview's own fetch and per-item
value, so `bar.count` is the figure's `n` (a PRED's `numerator`; a count's value), `bar.sumMd` is set for `TASK_ESTIMATE` and `TASK_COST`, and `total == bar.count`.

| `view` | Lists | `figure` | `anchorAt` |
|---|---|---|---|
| `TASK_ESTIMATE`, `TASK_COST` | the tasks the median was taken over | the estimate at done / the logged MD | `done_at` |
| `TASK_CYCLE` | the started tasks | cycle in working days | `done_at` |
| `TASK_PRED10/25/50`, `EPIC_PRED10/25/50` | the tasks / epics within the share | `actual ÷ estimate at start` | `done_at` |
| `WIP_TASKS`, `WIP_EPICS` | the open items in progress now | age in working days | `started_at` |
| `QUALITY_TASKS_NO_ESTIMATE/_UNASSIGNED/_ESTIMATED_LATE` | the done tasks of the finding | the estimate at done | `done_at` |
| `QUALITY_EPICS_NO_ESTIMATE/_NO_DATES` | the epics of the finding | the own estimate | `done_at` (none while open) |
| `QUALITY_EPICS_ESTIMATE_GAP` | the epics beyond the tolerance | the signed gap in MD (tasks' sum − own) | `done_at` (none while open) |

The sprint bars of the page keep the existing `sprint-consistency/items` drill and the weekly bars `throughput/items`.

### Code and tests

`HomeWire.kt` (DTOs), `HomeKernels.kt` (windows, figure builders, `HomeMeasure`, `trendOf`), `HomeReads.kt` (the done-task read, teams, connections, freshness),
`HomeReport.kt` (`HomeContext`, the per-section builders), `HomeDrill.kt`, `HomeRoutes.kt`. Tests: `HomeKernelsTest` (no database), `ReportHomeTest` (the derived
fixture against `HomeOracle.kt`'s independent JDBC re-derivation, the hand-built world of `HomeScenario.kt`), `ReportHomeDrillTest`.

## Report 1 -- Commitments

`GET /api/v1/reports/commitments` (v0.3.0 M4 commit 10b, `.claude/docs/measures.md` "Report 1 --
Commitments"): `initial` = committed scope, `final` = final scope (A17) -- both read straight off
`metrics.fact_sprint` for the sprint-level rows, `metrics.fact_sprint_scope` for the per-user
breakdown. Takes the shared `from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId` and
`connectionId` parameters (`domainView` is accepted and echoed in `meta` but never changes the
result -- commitments carries no domain slice, `measures.md`'s Report 1 rows: domain "--").

```
CommitmentsReport {
  meta: ReportMeta
  sprints: [{
    sprintId, name, teamId, completedAt,
    initialMd, initialItems, finalMd, finalItems,
    snapshot: { initialMd, initialItems, finalMd, finalItems } | null,
    drift: boolean,
    capacityMd: number | null, capacitySource: "CALENDAR" | "TEAM_DEFAULT" | "DEFAULT" | "CONFIGURED" | null,
    byUser: [{ accountId, label, initialMd, initialItems, finalMd, finalItems }]
  }]
  groups: [{ teamId?, accountId?, label?, initialMd, initialItems, finalMd, finalItems }]
}
```

- **`sprints`** -- one row per (team, sprint) in scope. `snapshot` is the frozen
  `fact_sprint_snapshot` figures (`null` until the sprint was first seen closed and team-mapped by
  a DERIVE run -- D13); `drift` compares the two LIVE-vs-FROZEN figures with a 0.005 MD tolerance
  (item counts compared exactly) -- `true` whenever any of the four differs, always `false` when no
  snapshot exists yet.
- **Capacity.** `capacityMd`/`capacitySource` are the sprint's capacity (A3, A32, `.claude/docs/metrics.md` "Sprint capacity"):
  `fact_sprint.capacity_md` and `dim_sprint.capacity_source`, read by the same join that reads the sprint (no extra query per sprint) --
  `CALENDAR` (the sprint's calendar), `TEAM_DEFAULT` (the team's one figure) or `DEFAULT` (members x working days); `CONFIGURED`
  (the pre-V24 per-sprint override) is still in the enum for rows not yet re-derived and is never written again; both `null` when
  the sprint has none. Filled at UNIT and
  TEAM level; always `null` at USER level -- capacity is a team figure, so it is hidden when users are picked. Load
  (`committed / capacity`) stays on `fact_sprint`, not in this shape.
- **Levels, from the parsed filter** (`reports/ReportFilter.kt`'s own `ReportLevel`):
  - **UNIT** (no `teamId`) -- every team's own CLOSED sprints in the period; `sprints` carries the
    whole-team figures (straight off `fact_sprint`); `groups` sums FINAL MD/items per team (`Σ
    final`, from the SAME `sprints` rows this response already carries -- no separate query).
  - **TEAM** (`teamId`) -- narrows `sprints` to that one team's own sprints; `groups` becomes a
    per-user split keyed by `fact_sprint_scope.assignee_at_commitment` -- committed
    (`committed AND in_scope_at_close`) sums `estimate_at_commitment_md`, final
    (`in_scope_at_close`) sums `estimate_at_close_md`, the SAME removed-row rule
    `sprintTotals` applies to the team total itself (`.claude/docs/metrics.md`
    "Sprint scope, facts and snapshots"), so `Σ groups == the team total` (both buckets, both
    MD and items, exactly -- see "Rounding" under Report 6). A `null` `accountId`/`label` group is the unassigned-at-commitment
    bucket, listed last -- never a stored sentinel, the `credit_team_id` convention.
  - **USER** (`teamId` AND `accountId`, which REPEATS on this report and on the other multi-account reports (`multipleAccounts = true`, listed under "The shared filter parser"): `?accountId=a&accountId=b`, 1..8 distinct values, a
    ninth is a `400`; every other report keeps `accountId` strictly single-valued, a repeat is a `400` there) -- `sprints` itself
    narrows to the picked accounts' own contribution per sprint, SUMMED over them (the SAME committed/final predicates as the
    TEAM-level groups, applied per sprint instead of summed). Each sprint's `byUser` splits that sum: one entry per picked account
    in REQUEST order (duplicates collapse, first occurrence wins), zeros included, `label` the Jira display name (the TEAM-level
    group lookup; the account id when unknown); it is `[]` at UNIT/TEAM level. `groups` lists each picked account's period totals
    by the TEAM-level groups' rule (committed/final over its `assignee_at_commitment` rows, label-then-id order) -- an explicit
    pick is always listed, a zero entry when the account has no scope row in the period. One account gives exactly the figures it
    always did, plus a one-entry `byUser`. `snapshot` is the picked accounts' summed FROZEN figures, read from the sprint's `fact_sprint_snapshot.scope`
    JSONB (per-user commitments come from the snapshot, so no child table is needed) with the
    same predicates, `assignee_at_commitment` attribution and `sumMd` rounding as the live figures; a
    snapshotted sprint in which none of the picked accounts had items shows zeros, a sprint with no snapshot (D13)
    `null`. `drift` is the team level's own comparison. The unassigned bucket's frozen figure has no
    USER-level query. The parser and the frozen fetch are shared with Throughput's `bySprint` and Sprint
    consistency (`reports/FrozenScope.kt`, pinned by `FrozenScopeParserTest`). A malformed scope row is an
    invariant violation (the DERIVE writer is its only producer) and fails the request with an error naming
    the connection and sprint. A snapshot frozen
    before the per-item rounding fix holds `round2(Σ raw)`, so its Σ users may differ by a cent.
  - **`teamId = 0`** (UNASSIGNED) -- always empty (`sprints: []`, `groups: []`): a sprint always
    carries a real team or is excluded from this report entirely (an unmapped-board sprint shows
    only in report 14, per `measures.md`'s Report 1 rows).
- **Period.** `from`/`to` -> `fact_sprint.complete_at` in `[fromMs, toMs)`, team-scoped when
  narrowed. `lastSprints=N` -> each team's own last N sprints with a non-null `complete_at`, ordered
  descending, resolved in Kotlin (teams are few and admin-curated, the `ReportService.filters()`
  precedent) -- reported per team in `meta.resolvedSprints`. `sprintId` -> that ONE sprint; unknown
  (checked against `metrics.dim_sprint` across the connection scope, regardless of team mapping) is
  `400`; a real sprint with no team mapped still 400s only if it does not exist at all -- an
  existing-but-unmapped sprint simply returns `sprints: []`.
- **Open sprints.** An ACTIVE/future sprint has no `complete_at`, so only an explicit `sprintId`
  reaches it (`measures.md` conventions): `completedAt` is `null`, the figures are the live
  `fact_sprint` ones, `snapshot` is `null` and `drift` `false`. Pinned by `ReportCommitmentsTest`.
- **Connection scope.** `connectionId` narrows to one ACTIVE connection (`400` if unknown/inactive);
  absent, defaults to every ACTIVE (not soft-deleted) connection, the same set `/reports/filters`
  lists — a DISABLED connection only has syncing paused, and its derived data still counts.
- **`teamId`/`sprintId` existence.** A positive, non-zero `teamId` must name an ACTIVE team (`400`
  otherwise) -- the client-supplied-FK idiom, `reports/ReportFilter.kt`'s own doc comment: structural
  parsing stays in the parser, existence checks live in the service.
- **Drill.** The initial and final bars are the sprint-scope buckets `COMMITTED` and `FINAL`:
  `GET /api/v1/reports/sprint-consistency/items?sprintId=<id>&bucket=COMMITTED|FINAL` (with `accountId` repeated at USER level and
  `segmentAccountId` for one person's segment) lists the tasks behind them -- "Drill to items" above.

`reports/CommitmentsReport.kt` holds the wire DTOs, the drift comparison and every query/aggregation
function as an extension on `ReportService` (`reports/ReportService.kt`'s `database`/`metricsConfig`
are `internal`, not `private`, precisely so this sibling file can extend it without a second
constructor) -- the file the brief's own "past ~120 lines -> a new file" idiom names. Tests:
`ReportCommitmentsTest` (`DerivedStubFixture`-based: UNIT-level `finalMd` against `fact_sprint`, the
golden FLO sprint's `sprintId` period against `expected.json`'s `committedMd`/`finalMd`, TEAM-level
`Σ groups == team total`, the USER level's frozen figures ("USER level frozen figures sum with the
unassigned bucket to the team snapshot", "USER level snapshot is null for a sprint with no snapshot and
drift stays false", "USER level frozen figures come from the stored snapshot scope not the live
rows, per connection", "frozen scope parsing fails loudly on a malformed row and keeps a null estimate
null"), the capacity ("capacity is the stored fact_sprint capacity and source at UNIT and TEAM level, and null at
USER level", "a sprint calendar and a team default override the default at UNIT and TEAM level"), several accounts
("several accountIds sum the sprint and split it per user in request order", "one accountId keeps the figures
with a one-entry byUser and a user with no scope is a zero entry", "at most eight accountIds on
commitments, and a repeated accountId on another report stays 400"), `400` for `from > to` and an unknown `sprintId`; every request runs
through a non-admin `seededClient`, proving D12's `200` at the same time).

## Report 2 -- Throughput

`GET /api/v1/reports/throughput` (v0.3.0 M4 commit 10c, `.claude/docs/measures.md` "Report 2 --
Throughput", with the item counts of report 13): what was DELIVERED, in MD and items. Takes the
shared parameters plus `bucket=WEEK|MONTH` (default `WEEK`); `accountId` repeats (see Levels). Two views that **differ by design** --
a task finished after its sprint closed is in the period view but in no sprint's delivered scope,
and the sprint view prices at the sprint's close while the period view prices at `done_at`:

```
ThroughputReport {
  meta: ReportMeta
  bySprint: [{ sprintId, name, teamId, completedAt, deliveredMd, deliveredItems,
               snapshot: { deliveredMd, deliveredItems } | null, drift: boolean,
               byUser: [{ accountId, label, deliveredMd, deliveredItems }] }]
  byBucket: [{ bucketStart, deliveredMd, deliveredItems,
               byUser: [{ accountId, label, deliveredMd, deliveredItems }] }]
  groups:   [{ teamId?, accountId?, label?, deliveredMd, deliveredItems }]
}
```

Throughput is what is delivered over any time unit by any group of people, in MD (1 SP = 1 MD) or items.

- **`bySprint` (the SPRINT view)** -- straight off `fact_sprint.delivered_md/_items` (= Σ
  `fact_sprint_scope.done_in_sprint`), one row per (team, closed sprint) by `complete_at`, whole
  sprint or nothing. The period/`lastSprints`/`sprintId` resolution, connection scope and the `400`
  existence checks are commitments' own -- both reports share `reports/ReportSupport.kt`. `snapshot` is
  the frozen `fact_sprint_snapshot` delivered figures, `drift` the same 0.005 MD / exact-items
  comparison commitments uses. Sorted by `completedAt`, then `sprintId`. Carries no domain/activity/
  category slice (`measures.md`: domain "--").
- **`byBucket` + `groups` (the PERIOD view)** -- level-0 tasks (`fact_task_delivery.is_subtask =
  false`, D2) with `done_at` in the window, `deliveredMd` = Σ `COALESCE(estimate_at_done_md, 0)`,
  `deliveredItems` = count (an unestimated task is an item worth 0 MD). Team = `credit_team_id` (D5;
  `null` = UNASSIGNED, selected by `teamId=0`), user = `assignee_account_id_at_done`. Honours
  `domain` (TASK view, the default and what `meta.domainView` echoes: the task's own `domain_key`;
  EPIC view: `COALESCE(epic_domain_key, domain_key)`, A21 -- the fallback keys on
  `epic_domain_key IS NULL`, so it also covers an epic outside the ingested scope), `activityType` and `workCategory`
  (`UNCATEGORIZED` = no category -- so a real category literally named that cannot be selected).
- **Bucket rule.** `bucket=WEEK` weeks start Monday, `MONTH` starts on the 1st, both cut in the
  configured zone (`metrics.settings.time_zone`, never UTC -- a task done Monday 00:30 local belongs
  to that Monday's week even though it is still Sunday in UTC). `bucketStart` is the bucket's first
  day as an ISO date, so the first/last bucket may start outside the requested range (only tasks
  inside the range are counted). Empty buckets are zero-filled across the whole window, so a chart
  needs no gap handling. The math is the pure `bucketStart`/`bucketStarts` (`ThroughputBucketTest`).
- **Window rule.** A `from`/`to` period is exactly `[fromMs, toMs)` (exclusive end, as commitments). A
  `lastSprints`/`sprintId` period resolves the sprints (`meta.resolvedSprints`) and the period view
  reads their overall envelope `[min(start_at, else complete_at, else now), max(complete_at, else
  now)]` (inclusive end) -- an OPEN sprint (no `complete_at`, reachable only by an explicit `sprintId`;
  `bySprint` then carries its live figures, `completedAt` `null`, `snapshot` `null`) ends at the
  request's now, a sprint without `start_at` starts where it ends, and a not-yet-started future
  sprint gives an empty window. It is one window shared by every team, so at UNIT level a team whose sprints ended long before the
  latest one still sees the envelope, not its own narrower window. No resolved sprint means no window
  and an empty period view; `teamId=0` (UNASSIGNED) resolves no sprints at all, so with a
  sprint-relative period it yields nothing.
- **Levels.** UNIT: `groups` are one per credit team (a `teamId: null` group is UNASSIGNED, `label`
  the team name); TEAM (`teamId`): `bySprint` narrows to that team's sprints, the period view to tasks
  credited to it, and `groups` become one per assignee at done (`null` `accountId`/`label` =
  unassigned); USER (`teamId` + `accountId`, which REPEATS on this report and on the other multi-account reports (`multipleAccounts = true`, listed under "The shared filter parser"):
  `?accountId=a&accountId=b`, 1..8 distinct values in request order, a ninth is a `400`; every other report keeps
  `accountId` strictly single-valued): the period view narrows to tasks whose `assignee_account_id_at_done` is IN the
  picked accounts and SUMS over them, `groups` lists each picked account's period totals (the TEAM-level rule, label-then-id
  order; an explicit pick is always listed, a zero entry when it delivered nothing -- commitments' picked-user groups), and
  `bySprint` narrows to the picked accounts' deliveries by `assignee_at_commitment`, summed
  (`done_in_sprint` rows at `estimate_at_done_md`). `byUser` on every `bySprint` and `byBucket` entry splits the live
  figures per picked account in REQUEST order (duplicates collapse), zeros included, `label` the Jira display name (the account
  id when unknown); it is `[]` at UNIT/TEAM level. One account gives exactly the figures it always did, plus a one-entry
  `byUser`. The sprint `snapshot` is the picked accounts' summed FROZEN share: the
  sprint's `fact_sprint_snapshot.scope` JSON (commitments' shared reader, `reports/FrozenScope.kt`) run through
  `sprintTotals` -- the same `done_in_sprint` predicate, `assignee_at_commitment` attribution and
  per-item two-decimal rounding (`sumMd`) as the live figure, an unestimated delivery an item worth 0 MD; a
  snapshotted sprint in which the accounts delivered nothing shows zeros, a sprint with no snapshot (D13) `null`,
  and `drift` is the team level's own `deliveredDrift` over the two. The unassigned bucket has no USER-level query.
  **Σ `groups` == Σ `byBucket`** (MD and items) at UNIT and TEAM level.
- **Two separate measures.** Never expect Σ `bySprint` == Σ `byBucket` (see the two-views note above).
- **Drill.** `GET /api/v1/reports/throughput/items?...&bucketStart=<day>` lists the tasks behind ONE bar of the period chart (and one
  person's segment of it with `segmentAccountId`), produced by the same fetch and bucket rule as `byBucket` -- "Drill to items" above.
  A bar of the sprint view lists the sprint's delivered items: `GET /api/v1/reports/throughput/items?...&sprintId=<id>` (no
  `bucketStart`), the sprint-scope drill's `DELIVERED` bucket -- "Drill to items" above.

Code: `reports/ThroughputReport.kt` (DTOs, pure bucket math, the queries) over `reports/
ReportSupport.kt` (the connection scope / team-existence / sprint-row / snapshot readers extracted
from commitments). Tests: `ReportThroughputTest` -- `DerivedStubFixture`-based: `bySprint` against `fact_sprint` and
`golden.sprint.deliveredMd/Items`; `byBucket` against an independent read of `fact_task_delivery` over
two windows; the sprint-envelope window for a `sprintId` and for `lastSprints=2`; an open/future
sprint by `sprintId`; Σ groups at UNIT/TEAM (and the picked account's own group at USER); USER-level `bySprint` summed
over the golden sprint's assignees (plus the unassigned remainder) equalling the TEAM figure, its frozen
`snapshot` equal to an independent computation over the stored scope JSON and Σ users + unassigned == the team
snapshot ("USER-level bySprint snapshot equals the stored scope and sums to the team snapshot"), and hand-built
snapshots on two disabled clones sharing one sprint id (frozen figures from the JSON not the live rows, drift,
per-connection isolation, zeros for an account with no delivery; "USER-level bySprint snapshot reads the stored
scope per connection, with drift"); an open sprint's USER `snapshot` is `null`; `teamId=0`; the `activityType` and TASK-view `domain` slices; WEEK vs MONTH totals; `400` for a bad
`bucket` and `from > to`. Several `accountId`s ("several accountIds sum bySprint and its frozen share and split it per user in
request order", "several accountIds sum byBucket, split every bucket per user and list each pick in groups", "one accountId
keeps today's figures with a one-entry byUser", "at most eight accountIds on throughput, and a repeated accountId on another
report stays 400") are graded against independent SQL reads of `fact_sprint_scope`, the stored snapshot JSON and
`fact_task_delivery`, never the single-account runs alone. The EPIC domain view and the `workCategory` filters are pinned on
hand-built `fact_task_delivery` rows in a fresh disabled connection (the stub fixture has no work
categories and no cross-domain epics), with exactly hand-computed counts and MD. `ThroughputBucketTest`
pins the pure bucket math across Europe/Warsaw's DST change.

## Report 6 -- Sprint consistency

`GET /api/v1/reports/sprint-consistency` (v0.3.0 M4 commit 10d, reports 6.1-6.3 with the item counts of
13, `.claude/docs/measures.md` "Report 6"): how a sprint's scope moved and where it ended up --
committed, added, removed, final, delivered, carried over, dropped, MD beside items -- straight off
`metrics.fact_sprint` (+ `fact_sprint_snapshot`). Takes commitments' shared parameters (`from`/`to`,
`lastSprints`, `sprintId`, `teamId`/`accountId`, `connectionId`; `domainView` echoed, never changes
the result; `activityType`/`workCategory` slice the figures, see "Sliced mode"). Period resolution, connection scope, the `400` existence checks, `teamId = 0` (always
empty) and the open-sprint handling (`sprintId` only, `completedAt` null, live figures, no snapshot) are
commitments' own -- the shared `reports/ReportSupport.kt`, whose `SprintRow`/`SnapshotRow` now carry all
fourteen figures (`full`).

```
SprintConsistencyReport {
  meta: ReportMeta
  sprints: [{ sprintId, name, teamId, completedAt,
              committedMd, committedItems, addedMd, addedItems, removedMd, removedItems,
              finalMd, finalItems, deliveredMd, deliveredItems,
              carriedOverMd, carriedOverItems, droppedMd, droppedItems,
              snapshot: SprintFigures | null, drift: boolean }]      // SprintFigures = the same fourteen
  groups: [{ teamId?, accountId?, label?, ...the fourteen figures }]
}
```

- **The A17 partition** holds in every row and is what makes the report a consistency check: `final =
  delivered + carriedOver + dropped` (MD and items -- all four priced at the sprint's close), and
  `final = committed + added` in items (in MD only when nothing was re-estimated between an item's
  commitment/entry and the close); `removed` sits beside them, in no other bucket. `ReportSprintConsistencyTest`
  asserts it over every closed sprint of the fixture, so a `fact_sprint` regression fails there.
- **`snapshot`/`drift`.** `snapshot` is the frozen `fact_sprint_snapshot` (`null` until first seen closed
  and team-mapped, D13); `drift` is `true` when ANY of the fourteen live figures differs from it beyond
  0.005 MD (items exactly), `false` with no snapshot.
- **Levels.**
  - **UNIT** -- `sprints` are every team's sprints in scope; `groups` is one per team, summing every
    figure of that team's sprints (Σ groups == Σ sprints, all fourteen).
  - **TEAM** (`teamId`) -- `sprints` narrow to that team's; `groups` is one per
    `fact_sprint_scope.assignee_at_commitment` (a null `accountId`/`label` = unassigned at commitment).
    Each group's figures come from `sprintTotals` over that user's scope rows -- the SAME
    bucket predicates that produced the team's `fact_sprint` row (committed = committed ∧ in scope at
    close; added = `added_at` set; removed = `removed_at` set; final = in scope at close; delivered =
    `done_in_sprint`; carried over / dropped by their flags; each on the estimate column
    `sprintTotals` uses for it), never a re-implementation, so Σ groups == the team figures for every
    bucket and in items, exactly (MD: see the rounding note below). A removed row is attributed to the user
    assigned at commitment, an added one to the user assigned at entry; the unassigned (null) group
    sorts last, as in commitments. The scope rows fetched are exactly the in-scope (connection, sprint)
    pairs -- never the cross product of the connection and sprint id lists, since two connections to
    one Jira site share sprint ids (commitments' per-user reader does the same).
  - **USER** (`teamId` AND `accountId`) -- `sprints` narrow to that account's rows per sprint (the same
    kernel over its rows; a sprint with none shows zeros); `snapshot` is that account's FROZEN fourteen figures,
    the same `sprintTotals` over the account's items of the sprint's `fact_sprint_snapshot.scope` JSON
    (commitments' shared reader, `reports/FrozenScope.kt`; the stored items carry every field a bucket reads --
    `addedAtMs`, `removedAtMs`, the flags and the three estimates -- so nothing is approximated): the same
    `assignee_at_commitment` attribution (an added item to the assignee at entry), predicates and `sumMd` rounding
    as the live figures; zeros for a snapshotted sprint where the account had no items, `null` for a sprint with no
    snapshot (D13); `drift` is the report's own `figuresDrift` over the two. `groups` is empty. The unassigned bucket has no USER-level query -- it is the
    remainder Σ named users + unassigned == team.
- **Sliced mode (`activityType` / `workCategory`).** With either parameter set (`UNCATEGORIZED` = a task with no work category;
  both AND together) the report is LIVE-ONLY at every level: each sprint's fourteen figures are `sprintTotals` over its
  `fact_sprint_scope` rows inner-joined to `fact_task_delivery` on `(connection, issue)` and filtered by the task's `activity_type` /
  `work_category` (an item with no task row has neither attribute and drops out), `snapshot` is `null` and `drift` `false` (the frozen
  snapshot is unsliced, so it would be an apples-to-pears comparison), and a sprint with no matching row reads zeros. UNIT `groups` sum the
  sliced sprint figures per team, TEAM `groups` split the sliced rows per assignee at commitment, USER narrows by the account as usual, so
  Σ groups == Σ sprints still holds. Σ over every activity type (and over every named category plus `UNCATEGORIZED`) equals the unsliced
  figures. Unsliced requests take the original path unchanged. The web page requires a team (no unit-level view, no member control), but
  the server keeps `teamId` optional (a UNIT read is a valid answer; Home no longer makes one -- its last-sprint figures come from `/reports/home`).
- **Rounding.** Every MD figure of a sprint is `Σ round2(item)`: `sprintTotals` rounds each
  item's estimate half-up to two decimals (`sumMd`, `metrics/DeriveSprintKernels.kt`) BEFORE the exact sum, which is
  exactly what `fact_sprint_scope` stores per item, and the per-user groups here and in commitments re-sum the
  stored rows with the same `sumMd` -- so Σ groups == the team figure to the cent (items likewise), whatever
  decimals the estimates carry.
- **Not in this report.** Capacity and load (also under measures.md's Report 6 heading) are not part of
  this endpoint's shape; Report 1 (commitments) now reads capacity from `dim_sprint`/`fact_sprint`, load stays unread.

- **Drill.** Every figure of this report is a bucket of the sprint-scope drill: `GET /api/v1/reports/sprint-consistency/items?sprintId=<id>&bucket=<BUCKET>`
  lists the tasks behind it (and one person's segment of it with `segmentAccountId`), produced by the same `fact_sprint_scope` rows and the same
  kernel predicates as the figures -- "Drill to items" above. The page opens it from a double-click on any bar or stacked segment of its three charts (each series is one
  bucket) and from the sprints table's seven figure buttons (one per bucket; FINAL is drawn by no chart). The same endpoint serves Commitments (`COMMITTED`/`FINAL`) and
  Throughput's sprint view (`DELIVERED`).

Code: `reports/SprintConsistencyReport.kt` (DTOs, drift, the query/aggregation functions as an extension
on `ReportService`). Tests: `ReportSprintConsistencyTest` -- the golden FLO sprint's fourteen figures
against `expected.json` exactly (plus its snapshot, no drift); the A17 partition over every closed sprint
(`lastSprints=52`, which covers every closed sprint only while a team has at most 52) and UNIT Σ groups; TEAM Σ groups == team for all fourteen figures; USER-level figures
per named account equal to that account's TEAM group, and Σ users + the unassigned remainder == team; a
hand-built case (the stub fixture has no removed scope and no estimated added scope: a fresh DISABLED
connection with hand-inserted `dim_sprint`/`fact_sprint`/`fact_sprint_scope` rows) asserting the per-user
removed/added figures exactly and that a second connection's stale scope rows never leak in; an
open sprint by `sprintId` (`completedAt` null, no snapshot -- its USER `snapshot` is `null` too); sliced mode ("activityType and workCategory slice every figure live from the scope rows, with no snapshot and no drift": Σ activity types and Σ categories + `UNCATEGORIZED` == the unsliced figures at UNIT and TEAM level, Σ groups == Σ sprints, the golden sprint against an independent join); the USER-level frozen
figures ("USER level snapshot equals the stored scope and sums to the team snapshot": every account's fourteen figures
equal an independent computation over the stored JSON, Σ users + unassigned == the team snapshot; "USER level snapshot
reads the stored scope per connection, with drift": hand-built snapshots on two disabled clones sharing one sprint id --
committed/added/removed/delivered/carried/dropped attributed exactly, per-connection isolation, drift, zeros); `teamId=0`;
`400` for `from > to` and an unknown `sprintId`. Every request runs through a non-admin `seededClient` (D12).

## Reports 3, 4, 5 -- Estimation (accuracy and adjustments)

Three endpoints (v0.3.0 M4 commit 12, `.claude/docs/measures.md` "Reports 3, 4, 5") over
`fact_task_delivery`/`fact_epic_delivery`, sharing one preamble (`reports/ReportSupport.kt`'s
`resolveReportScope`: settings, connection scope, the `400` existence checks, meta, and the period window) and
the same slice predicates as throughput's period view (`taskFactSlice`/`epicFactSlice`). All take the shared
`from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId`, `domainView`, `domain`, `workCategory` and
`connectionId` parameters (tasks also `activityType`). A `lastSprints`/`sprintId` period resolves the sprints and reads their
overall **envelope** as the window, exactly as throughput's period view does (`periodWindow`, `meta.resolvedSprints`); `teamId=0` resolves
no sprint, so with a sprint-relative period it reads nothing.

**Tasks vs epics.** Task reads are level-0 tasks only (`is_subtask = false`, D2 -- a sub-task's worklogs and
estimates are already rolled into its parent); team = the D5 **credit** team (`teamId=0` = no credit team,
UNASSIGNED), user = assignee at done, domain per `domainView` (default `TASK`; `EPIC` = the epic's domain with
the A21 fallback, as in throughput). Epic reads use the epic's OWN estimate columns; team = the domain's
**owner** team (A19, `teamId=0` = UNOWNED), domain = the epic's own space (identical under either
`domainView`, which is only echoed in `meta` -- the epic accuracy default is `EPIC`, the PV/EV/AC-shaped
default of plan section 7), `workCategory` slices; `activityType` is not an epic attribute and is ignored.
Epics carry **no user** (measures.md: user "--"), so TEAM level has no epic groups and **USER level is always
empty for epics** (an all-zero answer, never a silently team-wide one).

### Report 3 -- `GET /api/v1/reports/task-estimation-accuracy`

```
TaskEstimationAccuracyReport {
  meta: ReportMeta
  atStart: Distribution          // actual_md / estimate_at_start_md   (D15, the primary view)
  atDone:  Distribution          // actual_md / estimate_at_done_md    (the second view)
  excluded: { population, noWorklogs, neverStarted, unestimatedAtStart, unestimatedAtDone }
  groups: [{ teamId?, accountId?, label?, atStart, atDone, excluded }]
  accuracyAtStart: AccuracyMeasures?   // over the ratios atStart summarises; null while it is hidden
  accuracyAtDone:  AccuracyMeasures?   // over the ratios atDone summarises; null while it is hidden
}
AccuracyMeasures { pred10, pred25, pred50, mdmre, smape, iqr, idr, mad, tailShare? }   // unrounded; tailShare nullable
```

- **Population** = DONE level-0 tasks with `done_at` in the window. Each is in a distribution or in exactly ONE
  exclusion bucket, checked in this order: `noWorklogs` (D14 -- `has_worklogs = false`, **or `actual_md` of 0.00**
  -- a minute or two logged is not a ratio of exactly 0; both views, mirroring the epics' `noActual`), then
  `neverStarted` (no `started_at`, so no estimate at start -- `atStart` only), then `unestimatedAtStart` (no
  estimate at start, estimated-late included; a backfill, A35, has none either) / `unestimatedAtDone` (no estimate at done). So `atStart.n +
  noWorklogs + neverStarted + unestimatedAtStart == population` and `atDone.n + noWorklogs + unestimatedAtDone
  == population`. (measures.md says `atDone` has "the same buckets"; `neverStarted` exists because a task with
  no start has no @start estimate, which is not a reason to drop it from the @done view -- it is not applied
  there, and the measures.md cell says so.) An estimate of 0 is stored as `null` = unestimated (domain-model.md).
- **Levels.** UNIT: `groups` one per credit team (`teamId` null = UNASSIGNED); TEAM: one per assignee at done
  (`accountId` null = unassigned); USER: one per picked account (a pick with no task an empty entry). Each group carries its own two
  distributions -- **hidden below `minSampleSize`, `n` always set**, so `Σ group n == n` -- and its own `excluded`.
- **USER level takes several `accountId`s** (1..8 distinct, `multipleAccounts = true`, like commitments, throughput, cycle time,
  reported time and adjustments; the web page also requires a team, the server keeps `teamId` optional). `atStart` and `atDone` read the UNION of the picked
  people (their done tasks, assignee at done) and carry `byUser`: every histogram bucket split per picked person
  (`buildDistributionByUser`, request order, a zero share for a pick with no task). `excluded` and the accuracy measures stay over the union;
  `groups` lists each pick (the TEAM-level rule per person, ordered label then account id) with its own PLAIN distributions. One `accountId`
  is today's figures plus a one-entry `byUser`; UNIT and TEAM are unchanged.
- Reading: ratio `1.0` = actual cost equals the estimate, above = it cost more.
- **Drill.** `GET /api/v1/reports/task-estimation-accuracy/items?...&view=AT_START|AT_DONE&bucketIndex=<n>[&segmentAccountId=<id>]` lists the tasks
  behind ONE bar ("Drill to items" above). The per-task values are `ratioAtStartOf`/`ratioAtDoneOf`, the functions `accuracyOf` builds the
  distributions and the exclusion buckets from, so a `noWorklogs`, `neverStarted` or unestimated task is in no bar of the view it is excluded from
  (a never-started task is still in the `AT_DONE` bars). At USER level the layout is the picks' union and a segment is one person's `byUser` share.
- **Accuracy measures** (`reports/AccuracyMeasures.kt`, the pure `accuracyMeasures(ratios, minSampleSize)`; Report 4 reuses it). Over the SAME
  ratios `r = actual ÷ estimate` the view's `Distribution` summarises (the union at USER level, never per person) and `null` under the same
  `minSampleSize` rule; unrounded on the wire. **The relative error is taken against the ACTUAL** (Conte et al.):
  `MRE = abs(actual − estimate) ÷ actual = abs(1 − 1/r)` (`actual > 0` is guaranteed by the `noWorklogs` exclusion), so a ratio of exactly 1 is a
  perfect estimate. `pred10`/`pred25`/`pred50` = the share of items with MRE ≤ 0.10/0.25/0.50 (an item exactly on the threshold counts as
  within, with a 1e-9 slack for float noise); `mdmre` = the median MRE (`percentile_cont` 0.5, the same function as `p50`); `smape` = the mean of
  `2·abs(r − 1) ÷ (r + 1)` (0..2); `iqr` = p75 − p25 and `idr` = p90 − p10 of the ratios; `mad` = the median of `abs(r − median r)`, unscaled;
  `tailShare` = the share of ratios with `abs(r − mean) > 2·s`, `s` the SAMPLE standard deviation (n − 1) -- the chosen reading of "kurtosis"
  (a normal distribution has about 4.6% there) -- `null` for n < 6 (with the sample SD no point lies more than (n − 1) ÷ √n SDs from the mean, under 2 for n ≤ 5, so a smaller view would always read a misleading 0%) or a constant sample. The web page hides p90/p95 (`DistributionPanel
  strip="centre"`), the `Distribution` schema is unchanged. Tests: `AccuracyMeasuresTest` (hand-computed set, PRED boundary, r ≡ 1, tail
  share null cases (n < 6, constant), the minimum) and the report test against `assertAccuracyMeasures` (its own `quantile`, MRE written `abs(r − 1) ÷ r`).

### Report 4 -- `GET /api/v1/reports/epic-estimation-accuracy`

```
EpicEstimationAccuracyReport {
  meta, atStart: Distribution, atDone: Distribution,        // actual_md / OWN estimate at start / at done
  excluded: { population, noActual, neverStarted, unestimatedAtStart, unestimatedAtDone },
  epics: [{ issueKey, summary?, ownerTeamId?, doneAt, ownEstimateAtStartMd?, ownEstimateAtDoneMd?,
            childSumMd, actualMd, ratio?, ratioAtDone? }],   // <= 200 rows
  epicsTruncated: boolean,
  groups: [{ teamId?, label?, atStart, atDone, excluded }],  // UNIT only: one per owner team
  accuracyAtStart: AccuracyMeasures?, accuracyAtDone: AccuracyMeasures?   // over the ratios atStart / atDone summarise; null while hidden
}
```

- **Population** = epics DONE by their own status (D11) with `done_at` in the window. Exactly ONE bucket each:
  `noActual` (`actual_md = 0`, first), then `neverStarted` (no `started_at`, so no estimate at start --
  `atStart` only, as for tasks), else `unestimatedAtStart` / `unestimatedAtDone` (no OWN estimate at that
  snapshot -- an epic whose budget is only the child sum has none; the child sum is **never** used as the
  estimate, it is listed beside it in `childSumMd`). The measures.md wording "`budget_source = CHILDREN`" is
  applied per snapshot: an epic that had an own estimate at start but lost it later is still measured at start.
- **`epics`** lists every DONE epic in scope -- including excluded ones, whose `ratio`/`ratioAtDone` are `null`
  -- **newest `doneAt` first, then connection and issue id**, at most 200 (`EPIC_ACCURACY_MAX_ROWS`);
  `epicsTruncated` is true when more matched. The distributions and groups always count every epic, not just the
  listed 200. `issueKey`/`summary` come from `dim_epic`.
- **Accuracy measures** are Report 3's, from the same `accuracyMeasures` kernel (above): `accuracyAtStart` over every epic's non-null `ratio`
  and `accuracyAtDone` over every non-null `ratioAtDone` -- the very ratios `atStart` / `atDone` summarise, so the same exclusions, `null`
  under the same `minSampleSize` rule; `tailShare` is also `null` for fewer than six epics or a constant sample (the Report 3 rule, one kernel).
  The relative error is taken against the ACTUAL (`actual_md > 0` is guaranteed by `noActual`), so the
  child sum never enters. The groups carry none. There is no `multipleAccounts`: an epic has an owner team and no user. The web page
  needs a specific team (page-level only, D1 -- the server keeps `teamId` optional), has no member control (`noMember`; a pasted `accountId` is
  dropped from the URL) and hides p90/p95 (`strip="centre"`).
  **Drill.** `GET /api/v1/reports/epic-estimation-accuracy/items?...&view=AT_START|AT_DONE&bucketIndex=<n>` lists the epics behind ONE bar
  ("Drill to items" above): each item has `itemKind` EPIC, no `accountId`, the owner team and the `ratio`/`ratioAtDone` the `epics` list prints
  as its `figure`; no `segmentAccountId`.
  Consequence: epics with no owner team (`teamId = 0`, the "No owner team" group) cannot be reached from the web report once a team is required;
  that is the accepted price of the item, and the unit-wide read stays available through the API.

### Report 5 -- `GET /api/v1/reports/estimate-adjustments`

```
EstimateAdjustmentsReport { meta, tasks: AdjustmentFigures, epics: AdjustmentFigures,
                            groups: [{ teamId?, accountId?, label?, tasks, epics? }] }
AdjustmentFigures { started, changedAfterStart, share?, estimatedLate,
                    changeDistribution: Distribution,
                    changeExcluded: { population, unchanged, estimatedLate, estimatedAfterDone, unestimated } }
```

- **Two populations, two anchors.** `started` = items with `started_at` in the window; `changedAfterStart` =
  those with `estimate_changes_after_start > 0` (an estimate change counts as an adjustment only between `started_at` and
  `done_at`, domain-model A35: a correction made after the item was done is not one); `share` = their **fraction 0..1** (null, counts only, **when fewer
  than `minSampleSize` items started** -- hidden like a distribution); `estimatedLate` = those started items that
  gained an estimate only after start. `changeDistribution` covers items with `done_at` in the window: the
  **fractional change start -> done**, `(estimate at done - estimate at start) / estimate at start` (`0.25` =
  +25 %, negative = shrank; a fraction, like the accuracy ratios, not percent points), **of the items whose estimate
  changed** -- an item whose two estimates are equal (a 0 % change) is measurable but NOT in it, so the one tall zero bar
  cannot dwarf the real adjustments. Every figure of that `Distribution` therefore reads the adjusted items only:
  `n`, `mean`, `min`/`max`, `p50`/`p90`/`p95`, the histogram (its equal-width buckets and open tail are laid out
  over the adjusted values alone, so no bucket is reserved for zero; a bucket may still span 0 when the changes have both
  signs) and, at USER level, `byUser`. The median is thus "the median ADJUSTMENT", not the median over all measurable items
  (with most items unchanged that would read 0 and say nothing). `changeExcluded` (over
  that same done population) puts each remaining item in ONE bucket: `unchanged` (both estimates present and equal; counted, shown
  beside the chart and as a term of the accounting line), else `estimatedLate` (counted separately,
  never a `+infinity` change) else `unestimated` (either snapshot missing, a never-started item included), so
  `changeDistribution.n + unchanged + estimatedLate + unestimated == population` and the measurable items are
  `changeDistribution.n + unchanged`. `unchanged` follows each figure block (tasks, epics, every group, and every USER pick's own group; the
  `byUser` shares carry no accounting of their own). `estimatedAfterDone` (A35) is a **breakout** of
  `unestimated`, not a fourth bucket (`estimatedAfterDone <= unestimated`): the done items whose estimate first appeared after
  `done_at`, a backfill. Mind the two `estimatedLate`s: the top-level one counts STARTED items (the started population), the one in
  `changeExcluded` counts DONE ones (the done population); for a done item "late" means the estimate exists AT DONE, so a backfilled
  item is in neither.
- **An estimated-late item is also "changed after start"**: its late estimate is itself a change point after
  `started_at` (`estimateSnapshots`), and the row's predicate is literally `changes > 0`.
  `ReportEstimateAdjustmentsTest` pins it against the stub generator: `estimatedLate` equals
  `expected.json`'s `taskEstimatedLateCount` and `changedAfterStart` equals `taskEstimateChangedAfterStartCount +
  taskEstimatedLateCount` (both counted by the generator against each story's FINAL `done_at`, A35), and
  `changeExcluded.estimatedAfterDone` equals `taskEstimatedAfterDoneCount` (the dedicated backfill cohort plus the "late" stories whose
  estimate landed after they were done). A second test re-derives both flags from the stored estimates, for every task and epic fact.
- **Epics** read the same two flags the tasks do (`fact_epic_delivery.estimated_late` / `estimated_after_done`, written by the
  one `estimateSnapshots` kernel since V28); the report no longer derives anything itself.
- **Attribution (A25): `credit` once done, `current` while open.** A DONE task is attributed to
  `credit_team_id` / `assignee_account_id_at_done`, a still-OPEN task to `current_team_id` /
  `current_assignee_account_id` -- branching on `done_at`, never `COALESCE(credit, current)` (a DONE task with no
  credit team is legitimately UNASSIGNED). So a started-but-open task never falls into UNASSIGNED merely for not
  being done, and a `teamId=X` read (`taskFactSlice(..., openAttribution = true)`) sees that team's open tasks too.
  Throughput and the accuracy reports read only DONE tasks, so they keep the plain credit/assignee-at-done
  predicates.
- **Levels.** UNIT: `groups` one per team, tasks and epics side by side (a null-team group is UNASSIGNED tasks /
  UNOWNED epics together); TEAM: one per assignee (at done / now, A25), tasks only (`epics` null); USER: `tasks` narrowed to the
  picked accounts, `epics` all zero, `groups` one per pick (tasks only, `epics` null -- the TEAM rule; a pick with no task an empty entry).
- **USER level takes several `accountId`s** (1..8 distinct, `multipleAccounts = true`, like commitments, throughput, cycle time, reported
  time and task accuracy; the web page also requires a team, the server keeps `teamId` optional). `tasks` reads the UNION of the picked people (A25: assignee at
  done once done, assignee now while open): the started counts, `share`, `changeExcluded` and every `changeDistribution` figure are over the
  union, and `tasks.changeDistribution` alone carries `byUser` -- every histogram bucket split per picked person
  (`buildDistributionByUser`, `Distribution` below), request order, a zero share for a pick with no change. `groups` lists each pick with
  its own PLAIN figures (ordered label then account id), so `Σ group` counts equal the `tasks` block. The `epics` block stays the all-zero
  USER block (epics carry no user, so it is never split per person). One `accountId` is today's figures plus a one-entry `byUser`; UNIT and
  TEAM are unchanged.
- **Drill.** `GET /api/v1/reports/estimate-adjustments/items?...&view=TASKS|EPICS&bucketIndex=<n>[&segmentAccountId=<id>]` lists the DONE items behind ONE bar of
  the `changeDistribution` ("Drill to items" above): only the adjusted items, through the same `adjustedChange` the distribution is built from.

Code: `reports/TaskAccuracyReport.kt`, `reports/EpicAccuracyReport.kt`, `reports/EstimateAdjustmentsReport.kt`
(DTOs, the pure partition/ratio functions, the queries as extensions on `ReportService`). The distributions are
built in Kotlin by `buildDistribution` over the fetched ratios. Tests -- one class per endpoint on
`DerivedStubFixture`: `ReportTaskEstimationAccuracyTest`, `ReportEpicEstimationAccuracyTest`,
`ReportEstimateAdjustmentsTest` (with `ReportEstimationTestSupport.kt`: an independent percentile/mean check that
never calls `buildDistribution`, raw fact readers, hand-built row builders). Each grades every distribution
(n, mean, min, max, p50/p90/p95, histogram total) and every exclusion count against an independent computation over
the raw fact rows -- whole population, per group (`Σ group n == n`), per user, `teamId=0`, and the slices -- and
pins the buckets, the ratios, the hidden state (`minSampleSize` pinned with `withMinSampleSize`), the 200-row cap and
the estimated-late separation and the after-done backfill on hand-built rows in a fresh DISABLED connection with hand-computed answers; the
pure kernel is `EstimateSnapshotsTest`.

**The per-person split (`byUser`, `buildDistributionByUser`).** A report that lets the user pick several people (task accuracy, cycle time, reported time and adjustments today) draws
ONE stacked histogram per `Distribution`: `buildDistributionByUser(valuesByUser, minSampleSize, tailPercent)` builds the union
exactly as `buildDistribution` over the flattened values, then adds `byUser` -- one `DistributionShare {accountId, label, counts}` per
picked person in REQUEST order, `counts` index-aligned with `histogram` (the open tail bucket included), an all-zero share for a
person with no values. Union and shares use ONE private bucket layout (`BucketLayout.indexOf`, the single place a value meets a bucket), so
`Σ shares[i] == histogram[i].count` and `Σ counts == that person's n` hold by construction. A HIDDEN union carries no shares, and
a plain `buildDistribution` never does (the field is empty and so omitted on the wire). The percentile strip, mean and the exclusion
accounting stay over the union; per-person n and median come from `groups`. The picks (`PickedAccounts.kt`: `pickedAccounts`,
`pickedGroups`) are shared by every report that splits per person.

## Reports 7, 8 -- Cycle time and reported time ratio

Two endpoints (v0.3.0 M4 commit 12b, `.claude/docs/measures.md` "Reports 7, 8") over `fact_task_delivery`, on the
same preamble and slice as the estimation reports (`resolveReportScope`, `taskFactSlice`, and the shared
`orgGroups` drill in `ReportSupport.kt`; the fetch `fetchDoneCycleTasks` is shared by both). Population = level-0
tasks (D2) with `done_at` in the window; team = the D5 **credit** team (`teamId=0` = UNASSIGNED), user = assignee
at done, domain per `domainView` (default `TASK`, D3), plus `activityType`/`workCategory`; a `lastSprints`/`sprintId`
period reads the resolved sprints' envelope, as throughput's period view. **Epics are in report 7 only, as its
separate `epics` block** (from `fact_epic_delivery`, below); report 8 stays tasks-only (the reported-time ratio and flow
efficiency are task measures).

### Report 7 -- `GET /api/v1/reports/cycle-time`

```
CycleTimeReport {
  meta, elapsedDays: Distribution, workingDays: Distribution,     // cycle_ms in days / cycle_working_days
  excluded: { population, neverStarted },
  trend: [{ bucketStart, p50?, p90?, n }],                        // bucket=WEEK|MONTH, default WEEK
  groups: [{ teamId?, accountId?, label?, elapsedDays, workingDays, excluded }],
  epics: { elapsedDays, workingDays: Distribution, excluded: { population, neverStarted },   // always present
           groups: [{ teamId?, label?, elapsedDays, workingDays, excluded }] }               // owner teams, UNIT only
}
```

- **Cycle** = `done_at - started_at` (first entry into IN_PROGRESS, WAITING or BLOCKED (A30, A36) to the start of the trailing DONE run, so a
  reopened task counts its whole span). `elapsedDays` is wall-clock (`cycle_ms / 86_400_000`), `workingDays` the
  configured calendar's fractional working days.
- **One exclusion**: `neverStarted` (`cycle_ms` null, i.e. no `started_at` -- created straight into DONE), so
  `workingDays.n == elapsedDays.n == population - neverStarted`. **A cycle of zero working days is a real value and
  stays in** (`zeroCycle` is a reported-time-ratio bucket only, where it would divide by zero).
- **`trend`**: one entry per week (Monday start) or month (the 1st) across the WHOLE window, zero-filled -- the
  throughput report's own `bucketStart`/`bucketStarts` math, in the configured zone -- placing each measurable task
  by `done_at`, on **working days**. `p50`/`p90` come from the same `buildDistribution` and are `null` when the
  bucket's `n` is below `minSampleSize` (`n` always set; an empty bucket has `n` 0), so hiding is per bucket.
- **Levels**: UNIT `groups` per credit team, TEAM per assignee at done, USER one per picked account (a pick with no task an
  empty entry); each group has its own two distributions (hidden below the minimum, `n` always set, so `Σ group n == n`) and
  `excluded`; groups are ordered by label (null last), then team id, then account id.
- **USER level takes several `accountId`s** (1..8 distinct, `multipleAccounts = true`, like commitments, throughput, reported time, adjustments and task accuracy; the
  web page also requires a team, the server keeps `teamId` optional: a UNIT read is a valid answer, though no page makes one since Home reads `/reports/home`). `elapsedDays` and
  `workingDays` read the UNION of the picked people (their done tasks, assignee at done) and carry `byUser`: every histogram bucket split
  per picked person (`buildDistributionByUser`, `Distribution` above), in request order, a zero share for a pick with no task. `excluded`
  stays over the union; `groups` lists each pick (the TEAM-level rule per person, ordered label then account id) with its own PLAIN
  distributions. **The `trend` stays the union of the picked people (one p50/p90 per bucket, never a line per person), and `epics` is
  unchanged** (the all-zero USER block). One `accountId` is today's figures plus a one-entry `byUser`; UNIT and TEAM are unchanged.
- **`epics`** (`reports/CycleTimeEpics.kt`): the same measure over the epics DONE by their OWN status (D11) with `done_at`
  in the same window, read from `fact_epic_delivery.cycle_ms` / `cycle_working_days` (the same `done_at - started_at` rule;
  `neverStarted` is again the only exclusion, `workingDays.n == elapsedDays.n == population - neverStarted`). The block is
  always present. Team = the domain's **owner** team (A19; `teamId=0` = UNOWNED), no user, domain = the epic's own space
  (`domain`/`workCategory` slice it through `epicFactSlice`; `domainView` has no effect and `activityType` is ignored, as in
  the estimation reports). **Levels** -- UNIT: `groups` one per owner team (null `teamId` = UNOWNED, label the team name,
  hidden below the minimum with `n` set, `Σ group n == n`); TEAM: the team's own epics, `groups` empty (epics carry no
  user); USER: an all-zero block with no groups -- an epic read is never a silently team-wide answer at USER level (the
  report-4/5 posture; the SPA shows the "not per person" note instead). No trend and no per-epic list (epics are few; the
  per-epic drill is Report 4's listing). The epics sit beside the task views and never change any task figure.

**Drill.** `GET /api/v1/reports/cycle-time/items?...&view=WORKING_DAYS|ELAPSED_DAYS|EPIC_WORKING_DAYS|EPIC_ELAPSED_DAYS&bucketIndex=<n>` lists
the tasks (epics, with their owner team and no person) behind ONE bar of one of the four histograms -- "Drill to items" above. The per-task
values are `workingDaysOf`/`elapsedDaysOf` (`null` = never started), the same functions `workingValues`/`elapsedValues` map the report's
distributions with.

### Report 8 -- `GET /api/v1/reports/reported-time-ratio`

```
ReportedTimeRatioReport { meta, ratio: Distribution,             // actual_md / cycle_working_days
                          excluded: { population, noWorklogs, neverStarted, zeroCycle },
                          flowEfficiency: Distribution,          // A18: active_ms / cycle_ms
                          flowEfficiencyExcluded: { population, neverStarted, zeroCycle },
                          groups: [{ teamId?, accountId?, label?, ratio, excluded,
                                     flowEfficiency, flowEfficiencyExcluded }] }
```

- Man-days logged per working day of cycle time. Each task is in the distribution or in exactly ONE bucket, checked
  in this order: `noWorklogs` (D14 -- `has_worklogs = false` or `actual_md` of 0.00, as in report 3), `neverStarted`
  (no cycle), `zeroCycle` (`cycle_working_days = 0`). So `ratio.n + noWorklogs + neverStarted + zeroCycle ==
  population`. Levels and groups as report 7, except USER level (below).
- **USER level takes several `accountId`s** (1..8 distinct, `multipleAccounts = true`, like commitments, throughput, cycle time, adjustments and task accuracy; the web page also
  requires a team). Both `ratio` and `flowEfficiency` read the UNION of the picked people (their done tasks, assignee at done) and carry
  `byUser`: every histogram bucket split per picked person (`buildDistributionByUser`, `Distribution` above), in request order, a zero
  share for a pick with no task. `excluded`/`flowEfficiencyExcluded` stay over the union; `groups` lists each pick (the TEAM-level
  rule per person, a pick with no task an empty entry, ordered label then account id) with its own PLAIN distributions -- shares are drawn
  on the two top-level distributions only. One `accountId` is today's figures plus a one-entry `byUser`; UNIT and TEAM are unchanged.
- **The ratio's mean, p95 and histogram can be dominated by very short cycles** -- dividing by a cycle of a fraction
  of a working day produces a very large ratio. That is a real outlier and is never dropped (only a cycle of exactly
  zero working days is excluded, as `zeroCycle`); read the p50 first.
- **Flow efficiency (A18) is shown beside it**, over the same DONE level-0 population: `flowEfficiency` is `active_ms /
  cycle_ms` (active = IN_PROGRESS-stage time minus blocked time while in progress, `.claude/docs/measures.md`'s "Flow
  efficiency (A18)" row; WAITING-stage (A30) and BLOCKED-stage (A36) time is wait, never active) and `flowEfficiencyExcluded` puts each unmeasurable task in exactly ONE bucket, in this order:
  `neverStarted` (no cycle), then `zeroCycle` (`cycle_ms = 0`). Worklogs play no part, so a task with none is still
  measured -- unlike the ratio there is no `noWorklogs` bucket -- and `flowEfficiency.n + neverStarted + zeroCycle ==
  population`. Note the two `zeroCycle`s differ: the ratio's is zero WORKING days, this one zero ELAPSED time. Groups
  carry both measures; `Σ group n == n` for each.

Code: `reports/CycleTimeReport.kt` (+ `reports/CycleTimeEpics.kt`, the `epics` block), `reports/ReportedTimeRatioReport.kt`. Tests -- one class per endpoint on
`DerivedStubFixture`, with the independent oracle of `ReportEstimationTestSupport.kt`: `ReportCycleTimeTest`
(distributions, per-group at every level, `teamId=0`, slices, the sprint envelope, and **every trend bucket's p50/p90/n
against an independent bucketing** -- WEEK and MONTH, zero-filled, hidden per bucket -- plus hand-built rows with
exact hand-computed numbers incl. a zero-working-day cycle, and the per-person split: several `accountId`s against an independent bucketing of each person's
raw values into the union's edges, one `accountId`, the trend staying the union, and the eight-account bound), `ReportCycleTimeEpicsTest` (the `epics` block: the fixture's
distributions and owner groups against an independent read of `fact_epic_delivery`, TEAM / `teamId=0` / USER, the
`domain`/`workCategory` slices and the ignored `activityType`, the sprint envelope, and hand-built epics with exact numbers
incl. the never-started exclusion and a zero-working-day cycle) and `ReportReportedTimeRatioTest` (the partition, groups,
slices, hand-built bucket precedence and hidden state, and the per-person split: several `accountId`s against an independent
bucketing of each person's raw values into the union's edges, one `accountId`, and the eight-account bound).

**Drill.** `GET /api/v1/reports/reported-time-ratio/items?...&view=RATIO|FLOW_EFFICIENCY&bucketIndex=<n>` lists the tasks behind ONE bar of the
ratio or the flow-efficiency histogram ("Drill to items" above); `ratioValueOf` and `flowEfficiencyValueOf` are the per-task values whose
`null`s are exactly the report's exclusion buckets, so a `noWorklogs`, `neverStarted` or `zeroCycle` task is in no bar.

## Reports 9, 10, 13 -- WIP and the estimated backlog

Two endpoints (v0.3.0 M5 commit 15, `.claude/docs/measures.md` "Report 9", "Reports 10, 13") that read the DAILY
AGGREGATES the DERIVE run writes (`.claude/docs/metrics.md` "Daily WIP aggregate", "Daily flow aggregate") instead of the
fact tables (WIP's DONE band alone is computed at query time, A34), so they share their own preamble (`resolveReportScope`, then `snapshotScopeOf` and `planSnapshotDays` in
`reports/SnapshotSupport.kt`). Both take the shared `from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId`, `domain`
and `connectionId` parameters. `domainView` is accepted and changes nothing, but **`meta.domainView` is
always `TASK`**: both reports read the task's own domain (D3 flow view; the epic side of WIP is the epic's own space), so an
explicit `domainView=EPIC` is not a `400` but is not echoed back as if it had been honoured.

**Scopes -- what the aggregate can answer.** `agg_daily_wip`/`agg_daily_flow` are stored per TEAM scope and per DOMAIN
scope only: never per user, never team x domain. So:

- **UNIT** (no `teamId`) sums every TEAM scope -- including the `UNASSIGNED` (tasks) and `UNOWNED` (epics, backlog)
  scopes -- or, with `domain`, reads that DOMAIN scope (a task's as-was domain, an epic's own space).
- **TEAM** (`teamId`) reads that team's TEAM scope. For WIP a task is in the team of D5 as of each day and an epic in
  its domain's owner team (A19) -- except the DONE band, where a done task is in its done-time team (`credit_team_id`, A34);
  the backlog's TEAM scope is the owner team (A19).
- **`teamId=0`** is `UNASSIGNED` on the task side and `UNOWNED` on the epic side of WIP (read with `itemKind=TASK` and `itemKind=EPIC` respectively),
  and `UNOWNED` for the backlog.
- **USER** (`teamId` and `accountId`): the daily aggregates have no per-user scope, so the **backlog** answers an EMPTY series with a
  `note` (never a team-wide number posing as a user's) and **WIP computes the level at query time** (Report 9 below).
  `400`, checked first: `domain` together with `teamId` (no team x domain split — so a USER request, which always carries `teamId`,
  has no `domain` either) and `activityType`/`workCategory` (not stored per day). Unknown `teamId`/`connectionId`/`sprintId` are `400` as elsewhere.

**Series days.** One point per calendar day of the period in the configured zone (a `from`/`to` period as parsed; a
`lastSprints`/`sprintId` period the resolved sprints' envelope, `periodWindow`, as throughput's period view). Days the
aggregate has no row for are ZERO (the rows are sparse) -- but only days the aggregate actually covers:

- **The cut-off.** The series stops after the last derived day, which with several connections in scope is the OLDEST, over
  the connections that have derived, of each one's newest SUCCEEDED `derive_runs.started_at` day in the configured zone (SQL
  `max()` per connection) -- so a lagging connection's missing days are never read as zeros. A period reaching past it lists
  nothing for those days; a period entirely past it is an empty series. A connection with no successful run is ignored for
  the cut-off (it has no rows either) but its id is named in `note`.
- **The backfill start (WIP only, decision C).** Report 9's series starts no earlier than the LATEST `source_connections.backfill_from`
  among the connections in scope that have derived (`backfillStart`, `planSnapshotDays(…, startAtBackfill = true)`): before it a
  connection's history is incomplete by construction (an item finished long before and not updated since was never fetched), and DERIVE
  writes no WIP row for those days either (`metrics.md` "Daily WIP aggregate"). With several connections every shown day counts every
  connection, so the later date wins; a connection that never derived does not move it (it has no rows either). A period that begins
  before the start lists nothing for those days and says so in `note` — "Before <date> the connection's history is incomplete (backfill
  window)", or, with several connections, "Before <date> the history of at least one connection in scope is incomplete (backfill window:
  the latest of their backfill dates)"; a period entirely before the start is an empty series with that note. The estimated backlog (reports
  10, 13) does not opt in: its aggregate keeps the creation-based start. The cut reads the CURRENT `backfill_from`, so rows an older DERIVE
  left before it are never listed.
- **Not derived yet.** When NO connection in scope has a successful DERIVE run the answer is an empty series/trend with
  `note` "Not derived yet: ..." -- never a period's worth of zeros.
- **`note`** carries whatever makes an empty or partial answer explicable: USER level (the backlog's "not stored"; WIP's "epics are left out"), not derived yet, no sprint
  resolved by a sprint-relative period (`teamId=0` gets its own wording: UNASSIGNED resolves no sprint), connections left out
  of the cut-off. Several apply -> joined by ". "; `null` when there is nothing to say. `meta.derivedAt` still tells a client
  how fresh the numbers are.

### Report 9 -- `GET /api/v1/reports/wip`

```
WipReport {
  meta, by: STATUS|STAGE|COLUMN, itemKind: TASK|EPIC,
  keys:   [{ key, label }],                                   // the legend, in display order
  series: [{ day, isWorkingDay, counts: { <key>: n },         // counts carries EVERY key, zero-filled
             byUser?: [{ accountId, label, counts: { <key>: n } }] }],   // USER level only, one per pick in request order
  note?:  string                                              // why the series is empty or partial (see "note" above)
}
```

- `itemKind` (default `TASK`) counts level-0 tasks; `EPIC` counts epics -- tasks and epics are different
  grains (D2) and an epic's tasks would count the same work twice, so the two are never summed: `itemKind=BOTH` is a `400` like any unknown value
  (the combined option was dropped; blocked time, report 12, keeps its `BOTH`); `by` (default `STAGE`)
  keys `counts`. `STAGE`: the six stages `NOT_STARTED`, `IN_PROGRESS`, `WAITING`, `BLOCKED`, `DONE`, `UNMAPPED` -- always all six; `WAITING` (A30: started,
  nothing actively worked on, a queue) and `BLOCKED` (A36: started, held by an impediment) are admin-mapped stages that no Jira category defaults
  to, each its own band, and `UNMAPPED` (a
  status with no stage mapping) is its own key. `STATUS`: the Jira status id, `label` its `norm.statuses` name; only
  statuses seen in the period, ordered by the status's earliest stage (in `NOT_STARTED, IN_PROGRESS, WAITING, BLOCKED, DONE, UNMAPPED` order — a per-domain stage override can give one status two stages in a scope) then name. `COLUMN`: the mapped board's columns in board order
  (`norm.board_columns`, read at query time so a board edit needs no re-derive), plus `(no column)` when a status no column
  holds has items in the period.
- **The counts are end-of-day snapshots**: the number of items whose `item_stage` interval covers the END of that day, all
  stages (a chart that wants "work in progress" sums the `IN_PROGRESS`, `WAITING` and `BLOCKED` keys, A30, A36). Invariant 9's
  other half lives in DERIVE's tests: the backlog never exceeds the NOT_STARTED WIP of its domain/day.
- **The DONE band is computed at query time, attributed as at done (decision B, A34).** DERIVE stores no `stage = DONE` row (a
  stored one -- an older DERIVE's leftover -- is never read): `reports/WipDoneBand.kt` (`wipDoneBandSql` / `readWipDoneBand`, one
  set-based statement per request over the period's `dim_date` days, never a per-day loop) counts an item on day `d` when a
  DONE-stage `item_stage` interval covers the end of `d` (`coveringAt`, the WIP step's own predicate), so a reopened item leaves the band while it is
  reopened. The status / column key is that interval's `status_id` (Done -> Closed moves the item between status keys). TEAM scope: a
  task is in `fact_task_delivery.credit_team_id` (null -> `UNASSIGNED`), an epic in its domain's owner team (else `UNOWNED`);
  DOMAIN scope: the task's `fact_task_delivery.domain_key` (the `task_domain` row DERIVE resolved at `done_at`, `[from, to)`; else its current domain), an epic's own domain. So the DONE band
  can differ from the as-was split the aggregate used to hold whenever a team or domain changed after done; the unit-wide band is
  unchanged. A reopened item that is still open has no `done_at`/`credit_team_id`: its earlier DONE days read `UNASSIGNED` /
  its current domain. The same helper takes an optional account filter (`WipDoneRequest.accountIds`, tasks only, by
  `assignee_account_id_at_done`) for the per-person WIP read. The band honours the backfill start like the other bands (its days
  are the plan's days).
- **`by=COLUMN` needs one team's board**: `400` at UNIT level, with `domain`, for `teamId=0` and for a team with no board
  mapped in the connections in scope (a team has at most one board, D10). It reads only the board's connection's rows. A
  USER-level request reads that team's board like a TEAM one (so `teamId=0` stays `400`).
- **USER level (brief 04) — a person's WIP over time, computed at query time** (`reports/WipUserReport.kt`; the eighth consumer of
  `multipleAccounts`: `accountId` repeats, 1..8 distinct, `teamId` required). `agg_daily_wip` is NOT given a per-user scope (it would add about
  one row per task-day to the table that is already the biggest, and every DERIVE rewrites it whole): the level-0 tasks a person holds are a small
  set-based read over the bridges DERIVE already writes. Per picked person and calendar day `d` (the same day list, derived-coverage cut-off, backfill
  start and notes as every other level): a task counts when an `item_stage` interval covers the END of `d` AND the `task_assignee` interval covering
  that same instant is the person's — both through `coveringAt`, the WIP step's own predicate, so a reassignment exactly at a day-end instant
  counts for exactly one person (`(from, to]`). The DONE band is the shared query-time read (`readWipDoneBand` with the account filter, decision B):
  a done task is attributed AS AT DONE, to its assignee at done (`fact_task_delivery.assignee_account_id_at_done`), not to whoever holds it on a later
  day. `STAGE`/`STATUS` keys come straight from `item_stage.stage`/`status_id` (the per-domain stage overrides are already in it), `COLUMN` as above.
  - **Team narrowing — the rule is every other USER-level report's** (`taskFactSlice`: `teamId` AND `accountId` intersect, credit team for done
    items, current team for open ones): the page's `teamId` is part of the question, not only its context. An open task-day counts when its as-was D5
    team is `teamId` — the sprint's team while that sprint is not yet closed at `d`, else the assignee's team (the very resolution of `teamTaskWipSql`) —
    and a done task when its `credit_team_id` is `teamId`. So with `teamId=T`, Σ over every assignee account + the tasks of team T nobody holds (a
    sprint's team with no assignee) equals the TEAM aggregate of T for every day and stage (`ReportWipUserTest`'s invariant); a person's task that sits
    in another team's sprint belongs to THAT team's WIP. `teamId=0` is the "no team" bucket (`team_id IS NULL`, `credit_team_id IS NULL`).
  - **`domain` is not combined with USER level**: `domain` + `teamId` is already a `400` (no team x domain split) and `accountId` requires `teamId`,
    so the combination stays a `400` — the web hides the member picker while a domain is chosen. (A domain-only person view would need the task's
    as-was domain through `task_domain`; nothing asks for it.)
  - **Response**: `series[].counts` is the UNION of the picks (a task is held by one assignee at an instant, so nothing counts twice); each point carries
    `byUser` — one `{accountId, label, counts}` per pick in request order (label = the Jira display name, else the account id), every key
    zero-filled, a pick with no task all zeros, a duplicate `accountId` collapsed — and omits it below USER level (`encodeDefaults = false`, like
    `Distribution.byUser`). One `accountId` is today's shape plus a one-entry `byUser`. `keys` is the legend over the union.
  - **Item kinds**: an epic has no assignee (D2; Aging WIP's USER level lists tasks only). `itemKind=EPIC` answers an EMPTY series
    and says so in `note` ("USER level counts the picked people's tasks only: an epic has no assignee, so epics are left out"); never a `400`.
  - **Cost**: one statement for the open bands (two `MATERIALIZED` CTEs fix the join order — pair the picks' assignee intervals with their tasks' stage
    intervals, keep the (day, pair) rows both cover, then resolve the team of those few thousand rows) plus one `readWipDoneBand` per pick (at most eight).
    Measured with `EXPLAIN ANALYZE` on a synthetic connection of the real size (6,600 tasks, 33,000 stage intervals, 13,200 assignee intervals, 45
    accounts in two teams, 60 sprints): the open bands for 8 people over 278 days take 80 ms (121-151 ms before the CTEs fixed the order: the
    planner resolved the team of every (day, assignee interval) pair, done tasks included), ~100 ms projected for 365 days; no index is needed
    (`task_assignee` is a 2,330-row seq scan for the 8 picks). `WipPlanShapeTest` pins the plan shape (no `SubPlan`). The DONE band's
    per-statement cost is the one measured for decision B.
  - **Against Aging WIP**: Aging WIP lists the CURRENT team/assignee as of the last DERIVE, the WIP series the as-was one at each day's end, so on the
    last day the two can differ slightly (a task reassigned or re-sprinted since the last DERIVE).
- `isWorkingDay` is the configured calendar's (weekends and holidays are not), so a chart can hide weekends. There is no
  `workingDaysOnly` parameter -- the flag is the contract.

### Reports 10, 13 -- `GET /api/v1/reports/backlog`

```
BacklogReport {
  meta,
  current: { asOfDay?, items, md, hiddenItems, hiddenMd, meanDeliveredMd?, windowSprints, sprintsUsed, backlogInSprints? },
  trend:   [{ day, items, md, hiddenItems, hiddenMd }],       // end-of-day, zero-filled
  touchedWithinDays?: number,                                 // the effective filter; null when off
  note?:   string                                             // why it is empty or partial (see "note" above)
}
```

- **`trend`** is the estimated backlog (D9: level-0 NOT_STARTED tasks with an OWN estimate > 0 in no started sprint;
  a parent estimated only through its sub-tasks is not in it, A23) at the end of each day, `md` in man-days. **`current`**
  is the trend's last listed day (`asOfDay`, `null` for an empty trend), so it is the snapshot on the period's last day
  (or on the last derived day when the period reaches beyond it).
- **Backlog in sprints (report 13)**: `backlogInSprints = md / meanDeliveredMd`, where `meanDeliveredMd` is the mean
  `fact_sprint.delivered_md` of the team's last `windowSprints` (`backlog_window_sprints`) CLOSED sprints completed before
  the end of `asOfDay` -- as of the period end, so an older period is graded against the sprints known then -- and
  `sprintsUsed` says how many were averaged (fewer than `windowSprints` when fewer exist; at UNIT level the MINIMUM across
  the teams that have closed a sprint, so "some team has fewer than N" is detectable). **`null`** (`meanDeliveredMd`,
  `backlogInSprints`) when no closed sprint exists; `backlogInSprints` alone is `null` for a mean of exactly 0. An open
  sprint (no `complete_at`) never counts.
- **Whose velocity**: the backlog's team is the OWNER team (A19), the sprint's team is the team of its board, both the same
  Flow team. TEAM level uses that team's own sprints. UNIT level uses the SUM of every team's own mean (the unit's delivery
  per sprint) over the unit's whole backlog -- including the `UNOWNED` part, which has no team velocity of its own, so a
  large unowned backlog inflates the unit figure (report 14 lists domains without an owner). A `domain` slice and
  `teamId=0` (UNOWNED) have no velocity of their own: `meanDeliveredMd` and `backlogInSprints` are `null`.
- **Recently touched only (`touchedWithinDays`, A38)**: with N in `1..3650` every trend day counts only the backlog items whose LATEST
  touch strictly before the day's end is no older than `N x 24 h` (`MILLIS_PER_DAY`) before it; a touch is the item's creation, any Jira
  history entry of it (any field) or any worklog on it at `started_at`; the item's own history only; a touch exactly at the end instant
  belongs to the next day. Applied PER DAY, so `trend` and `current` (its last day) agree. `items`/`md` are then the FILTERED figures,
  `hiddenItems`/`hiddenMd` the complement (`items + hiddenItems` is the unfiltered figure, pointwise), `current.hiddenItems/hiddenMd` the
  last day's, `touchedWithinDays` echoes N; `backlogInSprints` is `md ÷ mean` over the FILTERED `md` (the point of the filter). Absent or
  `0` = off: the stored aggregate, hidden fields 0, `touchedWithinDays` `null`. No DERIVE output, no migration: computed at query time,
  per connection in scope, in ONE statement (`reports/BacklogTouches.kt`): DERIVE's own D9 `rows` CTE (`backlogRowsCte`, shared; its
  `item_stage` side pruned to the period) narrowed to the scope; one index-only probe per candidate item for its touches
  (`fact_task_delivery.created_at` UNION `raw.jira_changelogs.created_at` UNION `norm.work_item_worklogs.started_at` -- the one `raw.*`
  read of a report, `persistence.md`); only the gaps LONGER than N between consecutive touches are kept (a day end E is stale iff
  `t_i + N < E <= t_i+1`), and each (item, day) row is anti-joined against its few gap rows. Measured on the real ONBOARDING connection
  (5,876 level-0 tasks, 233k histories), UNIT scope, a 365-day period, N = 90: ~125 ms warm (the unpruned `item_stage` join was ~585 ms);
  the filter-off path stays the aggregate read. On the stub fixture every request of `ReportBacklogTest` stays in the tens of ms.
  Touches are read LIVE from `raw`/`norm` while the backlog membership is as of the last DERIVE, so a filtered past-day figure can
  move between DERIVEs (a late-synced history or worklog lands on its own instant); the unfiltered figures only move with a DERIVE.

Code: `reports/WipReport.kt` (+ `reports/WipUserReport.kt`, the USER level), `reports/BacklogReport.kt` (+ `reports/BacklogTouches.kt`, the `touchedWithinDays` read), over `reports/SnapshotSupport.kt` (the scope predicates, the
working calendar builder, the derived coverage, the day plan and its note) and `reports/WipDoneBand.kt` (the DONE band). Tests -- `ReportWipTest` and `ReportBacklogTest` on
`DerivedStubFixture`, plus `ReportSnapshotTestSupport.kt` (independent readers of `agg_daily_wip`/`agg_daily_flow`/
`fact_sprint`, hand-row inserters) and `ReportWipDoneSupport.kt` (the independent DONE-band oracle `ReportWipTest` merges into the stored rows;
`ReportWipDoneBandTest` pins its rule: stub per scope/kind/day, the four stored bands against the unfiltered statements, hand-built team-at-done / reopen / Done -> Closed / domain-at-done rows, the account filter):
every day of every series against a sum taken straight off the aggregate rows plus that oracle (per
stage, status and board column; `TASK` and `EPIC` each; the DOMAIN scopes; a team; `teamId=0`; the sprint-id envelope; the
cut-off at the last derived day, incl. two connections with differing derive days and one that never derived; "not derived yet";
the `teamId=0` sprint-relative note; `meta.domainView` forced to TASK; weekends), the mean against an independent computation over `fact_sprint` at several
period ends (window full, fewer than N, one sprint), and hand-built rows in fresh DISABLED connections with hand-computed
answers (the zero-fill, keys and their order, `(no column)`, the UNASSIGNED/UNOWNED split incl. an epic never being
UNASSIGNED, scope isolation, the window, fewer than N, a mean of zero, no sprint at all, the UNIT sum); `touchedWithinDays` (A38): at 3650 it equals the unfiltered trend on every day and scope and at 7 days shown plus hidden adds up to it (`0` = absent), a per-item oracle over the touch timestamps on sampled days (`IndependentBacklogOracle` in `ReportSnapshotTestSupport.kt`, the bridge re-derivation `MetricsDerivationTest` shares), and hand-built rows pinning creation, a changelog, a worklog, the window boundary and the next day, the per-connection sum over two connections, and the `item_stage` pruning boundary. `400`s: a bad
`by`/`itemKind` (`BOTH` included), a `touchedWithinDays` outside `0..3650`, non-integer or repeated, `domain` with `teamId`, `activityType`, `workCategory`, an unknown team/connection/sprint, `by=COLUMN` at
UNIT level / for `teamId=0` / for a team with no board. USER level: `ReportWipUserTest` — the invariant on the stub (per team, per day and stage: Σ over
every assignee account in requests of up to 8 + the independent count of the tasks nobody holds, `independentUnassignedWip` in `ReportSnapshotTestSupport.kt`
written as correlated `EXISTS`, = the TEAM aggregate plus the independent DONE oracle, for the stub's team and for `teamId=0`) and hand-built rows (a reassignment exactly at a day-end
instant, a sub-task, an epic, a null-assignee interval, the sprint's team over the assignee's, the DONE band at its done-time assignee/credit team, `EPIC`
note, `byUser` order and zero-fill, STATUS and COLUMN keys); `400`s: nine accounts, `accountId` without `teamId`, `domain`/`activityType`/`workCategory` with an account.

## Reports 11, 12 -- Aging WIP and blocked time

Two endpoints (v0.3.0 M5 commit 15 part b, `.claude/docs/measures.md` "Report 11", "Report 12") over
`fact_task_delivery`/`fact_epic_delivery`, on the shared preamble (`resolveReportScope`, `taskFactSlice`/`epicFactSlice`, the
`orgGroups` drill). Same D12 posture and shared filter parameters as every report.

### Report 11 -- `GET /api/v1/reports/aging-wip`

```
AgingWipReport {
  meta,
  thresholds:     { n, hidden, percentiles: [{ percentile, workingDays? }] },   // tasks
  epicThresholds: { n, hidden, percentiles: [...] },                            // epics
  items: [{ issueKey, summary?, itemKind: TASK|EPIC, teamId?, assigneeAccountId?, assignee?, startedAt,
            ageWorkingDays, blocked, waiting, band?,
            statusId?, statusName?, stage: IN_PROGRESS|WAITING|BLOCKED, position }],    // oldest first, <= 500
  itemsTruncated,
  positions: [{ key, label, kind: STATUS|COLUMN }]                              // the aging chart's x axis, in workflow order
}
```

- **"As of now" -- the period is ignored.** The report has no period: the age is taken at the request's clock
  (`nowMillis()` in the route, injected into the service so tests pin it) with the configured calendar
  (`WorkingCalendar.workingDaysBetween(started_at, now)`, never negative). The shared parser still accepts `from`/`to`/
  `lastSprints`/`sprintId` (a sprint-relative one only resolves sprints, or 400s for an unknown id).
- **Items** = every OPEN level-0 task whose `current_stage` is IN_PROGRESS, WAITING or BLOCKED (A30, A36; `UNMAPPED` is not in progress) and every open epic
  whose own stage (`dim_epic.current_stage`) is IN_PROGRESS, WAITING or BLOCKED, each with `started_at` set. `waiting` = the item's current stage is
  WAITING (started, nothing actively worked on, a queue); a BLOCKED-stage item is `blocked` (its BLOCKED span is in `item_blocked`, covering the derive
  clock) and never `waiting`; either ages from `started_at` like any other item and is banded the same way. Tasks are attributed to the CURRENT
  team and assignee (A25: `taskFactSlice(openAttribution = true)`, `teamId=0` = no current team), epics to the owner team
  (`teamId=0` = UNOWNED) with no assignee; USER level (`accountId`) lists that assignee's tasks and no epics. `domain`,
  `activityType` and `workCategory` slice as elsewhere (`activityType` is not an epic attribute and is ignored for epics).
  `assignee` is the Jira display name (the account id when none is known). `blocked` = an `item_blocked` row covers the
  connection's DERIVE clock (its newest successful run's start: the deriver never writes an open row, it closes a still-open
  spell AT that clock), i.e. blocked as of the last derive. Sorted by age descending (then connection, issue id), at most 500 with `itemsTruncated`.
- **Thresholds** are the configured `aging_percentiles` (default 50/85/95) of `cycle_working_days` over the LAST
  `aging_window_items` DONE items by `done_at` -- level-0 tasks by the credit team for `thresholds`, epics by the owner team
  for `epicThresholds` (an epic's cycle is a different scale from a task's). They belong to the team: `accountId` narrows
  the listed items but not the window, and at UNIT level the window spans the unit (each item carries its `teamId`; drill
  with `teamId` for a team's own thresholds). Hidden below `minSampleSize` (`n` set, every `workingDays` null).
- **`band`** = the highest configured threshold the age is strictly above (`"P85"` = above p85 but not above the next),
  `"WITHIN"` when above none, `null` when that kind's thresholds are hidden.
- **Where an item sits** (`reports/AgingPositions.kt`; the web's aging chart plots it, `.claude/docs/web-features.md`). `statusId`/`statusName`
  (the `norm.statuses` name, WIP's `statusNames` helper) and `stage` (`IN_PROGRESS`|`WAITING`|`BLOCKED`, agreeing with `waiting`) come from the item's
  OPEN `metrics.item_stage` row (`valid_to IS NULL`) -- the same DERIVE as `fact_task_delivery.current_stage` / `dim_epic.current_stage`, so
  status, stage and band never contradict each other; `norm.work_items.status_id` is deliberately NOT read (PROCESS runs later than the last
  derive). Tiling leaves exactly one open interval per item but no DB constraint says so, so `ReportAgingWipPositionsTest` pins it over the whole
  fixture connection; an item with no open row (cannot happen after a DERIVE) has null `statusId`/`statusName`, the stage from the item's `current_stage` and
  position `(no status)`/`(no column)`. `position` is a key of the top-level **`positions`**, the chart's x axis in workflow order, computed over the
  LISTED items (after the 500 cap, so chart and table agree) and only the positions holding one: with ONE real team (`teamId` > 0, TEAM or USER
  level) that has a board mapped in the scope's connections, the board's columns in board order (`kind: COLUMN`, `key` = the column name, exactly as
  WIP `by=COLUMN` resolves them -- `boardColumnMappingOrNull`, the non-throwing half of `columnMappingFor`), a status on no column under `(no
  column)` last (an epic is placed the same way through its own status); otherwise (UNIT, `teamId=0`, a team with no board) the statuses
  (`kind: STATUS`, `key` = the status id), ordered like WIP `by=STATUS` (`sortStatusIds`: earliest stage in `IN_PROGRESS`, `WAITING`, `BLOCKED` order, then name,
  then id). Tasks and epics share the one list; the web shows one kind at a time (the page's Tasks | Epics switch filters this response client-side: thresholds, chart and table).

### Report 12 -- `GET /api/v1/reports/blocked-time`

```
BlockedTimeReport {
  meta, itemKind: TASK|EPIC|BOTH,
  blockedWorkingDays: Distribution,       // the BLOCKED DONE items of the period (blocked_working_days > 0)
  shareOfCycle:       Distribution,       // blocked_working_days / cycle_working_days, blocked items with cycle > 0
  blockedItems, excluded: { population, neverBlocked, neverStarted, zeroCycle },
  topItems: [{ issueKey, summary?, itemKind, teamId?, doneAt, blockedWorkingDays, cycleWorkingDays?, share? }],  // <= 20
  groups:   [{ teamId?, accountId?, label?, blockedWorkingDays, shareOfCycle, blockedItems, excluded }]
}
```

- **Population** = DONE items with `done_at` in the period (a sprint-relative period reads the resolved sprints' envelope):
  level-0 tasks (`itemKind=TASK`, the DEFAULT -- tasks and epics are different grains, D2) credited to the D5 credit team /
  assignee at done, epics (`EPIC`) to the owner team with no user, or both. USER level has no epics. Open items are not in
  this report (aging WIP lists them, with their current `blocked` flag).
- **`blockedWorkingDays`** is a `Distribution` over the BLOCKED DONE items only (`blocked_working_days > 0`). **Changed 2026-10-17, at the
  user's request:** a never-blocked item used to be "a real zero" that stayed in, so the first bar dwarfed the rest and the blocked
  items were unreadable; it now leaves BOTH histograms and is counted in `excluded.neverBlocked` (`blockedItems` = `population -
  neverBlocked` stays), which the page prints under the chart ("N of M items were never blocked") and lists in each view's accounting. "Never blocked"
  means no blocked WORKING day: an item flagged or blocked only over a weekend or a public holiday has `blocked_working_days` 0 and
  counts here (the old `blockedItems` rule, unchanged).
  Blocked time is what the deriver stored (`blocked_working_days`: Flagged or in a BLOCKED stage (A36), merged, clipped to `[started_at,
  done_at)`, where `started_at` is the first IN_PROGRESS-, WAITING- or BLOCKED-stage entry, A30, A36). **`shareOfCycle`** covers the blocked
  items with a cycle above zero. **One partition, checked in this order**, each item in exactly ONE `excluded` bucket: `neverBlocked`
  (blocked working days 0, wherever its cycle stands), then — among the blocked items — `neverStarted` (no cycle) and `zeroCycle` (0 working
  days). So `blockedWorkingDays.n + neverBlocked == population` and `shareOfCycle.n + neverBlocked + neverStarted + zeroCycle ==
  population` (`n` counts the values read, hidden or not; the same identities hold in every `groups` entry). A blocked item has started, so
  `neverStarted` and `zeroCycle` are 0 after a DERIVE -- they stay as defensive buckets (`ReportBlockedTimeTest` pins them on hand-built
  rows). Both distributions are hidden below `minSampleSize`.
- **`topItems`** = the 20 most-blocked items (`blockedWorkingDays > 0`, then newest `doneAt`), keys/summaries from
  `norm.work_items` (`dim_epic` for an epic). **Levels:** UNIT `groups` one per team (tasks and epics together), TEAM one per
  assignee at done over the TASKS only -- epics have no user, so at TEAM level with `itemKind` EPIC/BOTH the totals include the
  team's epics but Σ groups is the tasks' total -- and USER none.

Code: `reports/AgingWipReport.kt`, `reports/BlockedTimeReport.kt` (DTOs and queries as extensions on `ReportService`; the
shared `workItemLabels`). Tests: `ReportAgingWipTest` (hand-built rows with an INJECTED clock -- the service is called
directly, never wall time: exact ages, bands, the thresholds window, hidden thresholds, blocked, USER/UNIT, plus the fixture
graded on its populations and the thresholds against an independent percentile of the raw rows) and `ReportBlockedTimeTest`
(the fixture's distributions, exclusion partition, groups and top list against an independent computation over the fact
rows; hand-built rows with exact hand-computed numbers incl. the never-blocked exclusion, the defensive no-cycle and zero-cycle buckets,
the three item kinds and the drill). `400`s: an unknown team/connection/sprint, `accountId` without `teamId`, `from > to`, a bad `itemKind`.

**Drill.** `GET /api/v1/reports/blocked-time/items?...&itemKind=TASK|EPIC|BOTH&view=BLOCKED_WORKING_DAYS|SHARE_OF_CYCLE&bucketIndex=<n>` lists the
DONE items behind ONE bar of either histogram ("Drill to items" above). `fetchBlockedItems` is the report's own population fetch, so the list is
over the same `itemKind` the bar counted (the page always sends it, TASK by default); with `BOTH` each row's `itemKind` says task or epic, and the
web drawer adds a Kind column to every bar of that request, whether or not the bucket holds both kinds. `BLOCKED_WORKING_DAYS` has a value for every BLOCKED item and `SHARE_OF_CYCLE` only for a blocked item with a cycle above zero
(`DoneItem.blockedValue` / `blockedShare`, the very values the report builds its histograms from); a never-blocked item is in
neither bar (the first bar is no longer the zeros, so the bucket indices are those of the blocked items' own histogram). The histograms have no `byUser`, so there is no segment.

## Report 14 -- Data quality

`GET /api/v1/reports/data-quality` (v0.3.0 M5 commit 17, `.claude/docs/measures.md` "Report 14"): where the data the other
reports stand on is missing or inconsistent. One response, one section per finding; the shared parameters (`from`/`to`/
`lastSprints`/`sprintId`, `teamId`/`accountId`, `domainView` -- default `TASK` --, `domain`, `activityType`, `workCategory`,
`connectionId`). Same D12 posture as every report.

```
DataQualityReport {
  meta, hoursPerDay,
  populations:     { doneTasks, openStartedTasks, epics, worklogs },
  groups:          [{ teamId?, accountId?, label?, tasks: TaskCounts, worklogs: WorklogCounts, epics?: EpicCounts }],
  worklogCoverage: { doneTasks, withWorklogs, coverage?, without: TaskFinding },
  loggedHours:     { memberDays, hours, hoursPerMemberDay? },
  lateLogging:     { worklogs, measurable, over1Day, over7Days, distribution: Distribution, worst: [LateWorklog] },
  missing:         { noEstimate, noEpic, noWorkCategory: TaskFinding, workCategoryConfigured, unassigned: TaskFinding,
                     estimatedLate, estimatedAfterDone: TaskFinding,
                     epicsWithoutEstimate, epicsWithoutDates, epicsOutsidePvHorizon: QualityList<EpicRef> },
  outsideSprint: TaskFinding,  crossDomain: TaskFinding,
  epicDrift: QualityList<EpicRef>,
  epicEstimateGap: QualityList<EpicEstimateGap>,
  epicEstimateGapTolerancePercent: Int,  // the metrics setting the gap list used (V31)
  domainsWithoutOwner: QualityList<UnownedDomain>,  unmappedStatuses: QualityList<UnmappedStatus>,
  unmappedBoards: QualityList<UnmappedBoard>,  authorsWithoutTeam: QualityList<AuthorWithoutTeam>,
  itemsAboveEpic: QualityList<AboveEpicItem>,
  snapshotDrift: QualityList<SnapshotDrift>,  deriveWarnings: [DeriveWarning]
}
TaskFinding { done, open, total, md, items: [TaskRef] }     // TaskRef { issueKey, summary?, teamId?, assigneeAccountId?, assignee?, doneAt?, startedAt?, estimateMd? }
EpicEstimateGap { epic: EpicRef, ownEstimateMd, childSumMd, gapMd, direction: OVER | UNDER }
QualityList<T> { total, items: [T] }                        // items = the first 50; total says "and N more"
```

**Populations.** Task findings count level-0 tasks (D2) with `done_at` in the period -- the D5 credit team and the assignee at done
-- and, separately (the `open` half of a `TaskFinding`, `openStartedTasks`), every currently open task that has started,
whatever the period, attributed to the CURRENT team and assignee (A25, `taskFactSlice(openAttribution = true)`). Findings that
only make sense once a task is done (worklog coverage, unassigned, outside any sprint, cross-domain, estimated after done) have `open` = 0. Worklog
findings count `fact_worklog` rows with `started_at` in the period by the author's team as-was (`teamId=0` = no team) and
account; the `domain` slice follows `domainView` (TASK: the task's domain, EPIC: the epic's else the task's, A21). Epic findings
count epics open now or done in the period by the owner team (`teamId=0` = UNOWNED); USER level has no epics. A `lastSprints`/
`sprintId` period reads the resolved sprints' envelope (`periodWindow`); with no resolved sprint the done-anchored findings are
empty but the open ones remain.

**The findings.**

- **`worklogCoverage`** -- DONE tasks with worklogs; `coverage` is the fraction 0..1 (`null` with no DONE task), `without` lists the
  tasks with `has_worklogs = false` (a task with a 2-minute worklog has worklogs -- unlike the accuracy reports' D14 rule there is no
  `actual_md = 0.00` clause, measures.md's source is the flag).
- **`loggedHours`** -- Σ `fact_worklog.md` × `hoursPerDay` of authors who were in a team, over their **member-days**: the dated
  `metrics.team_membership` rows (D1) clipped to the period, capped at the request's now, counted in working days by the configured
  calendar (`WorkingCalendar.workingDaysBetween`, the rule `dim_date.is_working_day` stores). `hoursPerMemberDay` is `null` without
  member-days; read it against the top-level `hoursPerDay`. Each `groups[].worklogs` carries the same three numbers for its team
  (UNIT) or member (TEAM, roster members with no worklog included -- a `0` is a finding).
- **`lateLogging`** -- `late_ms` (created minus started, clamped at 0; `null` = creation time unknown, not measurable).
  `over1Day`/`over7Days` count strictly more than 1/7 days; `distribution` is the lateness in days (hidden below `minSampleSize`);
  `worst` the up-to-50 latest-logged ones (`lateDays` descending).
- **`missing`** -- `noEstimate` (`estimate_source = 'NONE'`), `noEpic` (`epic_id IS NULL`), `noWorkCategory` (`work_category IS NULL`,
  counted only for connections whose effective configuration has a work-category field; `workCategoryConfigured` says whether any
  has), `unassigned` (DONE, no assignee at done), `estimatedLate` (`estimated_late`: no estimate at start, one by `done_at` -- or
  now for an open task; done and open split, `md` as for the other findings) and `estimatedAfterDone` (A35, `estimated_after_done`,
  DONE only: the estimate first appeared after `done_at`, typically a bulk fill; its `md` and its list's `estimateMd` carry the
  BACKFILLED (current) estimate, since such a task has none at done); the three epic lists: `epicsWithoutEstimate` (`budget_source = 'CHILDREN'`),
  `epicsWithoutDates` (start or due null), `epicsOutsidePvHorizon` (both set but one outside ±10 years of the connection's last
  DERIVE clock -- its newest SUCCEEDED `derive_runs.started_at`, else the request clock -- `inPvHorizon`: no PV curve,
  A23; a half-dated epic is "without dates" only).
- **`outsideSprint`** (D10) -- DONE tasks with no sprint at done, `md` = their estimate at done. A task done inside a sprint of an
  unmapped board is not here: it is counted under `unmappedBoards[].doneTasks`. **`crossDomain`** -- DONE tasks whose epic is in another
  domain. **`epicDrift`** (D11) -- epics with a drift flag, `flags` naming them.
- **`epicEstimateGap`** -- epics with BOTH an own estimate (`fact_epic_delivery.own_estimate_current_md` > 0) and estimated tasks
  (`child_sum_estimate_md` > 0) whose two figures differ by MORE than the configured tolerance of the own estimate: `|child − own| > own × tolerance`
  (`metrics.settings.epic_estimate_gap_tolerance_percent`, V31, default 25, exact decimal arithmetic via `estimateGapTolerance(percent)` -- exactly at the
  tolerance is not flagged; the response echoes the value as `epicEstimateGapTolerancePercent`, so the page prints what was used), open and done alike (the epic population
  above). Each `EpicEstimateGap` carries the usual `epic` (`EpicRef`, no flags) plus `ownEstimateMd`, `childSumMd`, `gapMd` (=
  child − own, signed) and `direction`: `OVER` (the tasks sum above the epic: scope grew past the budget) or `UNDER` (below it: not
  broken down yet, or a padded epic). Not flagged: epics with only task estimates (already "epics without an own estimate") and epics
  with only their own estimate (mostly not broken down yet). The list is ordered by the widest absolute gap first (then connection and
  issue id), so the cap of 50 keeps the worst, unlike the other epic lists (connection, issue id). No derive change: both figures are
  stored. The tolerance is a constant; a `metrics.settings` value is possible later if it needs tuning. `groups[].epics.estimateGap`
  counts it per owner team, and the page's epic-findings column sums it with the other four epic counts.
- **`domainsWithoutOwner`** (A19, A22) -- `dim_domain.owner_team_id IS NULL`, with the domain's epic count; `domain` narrows; a real
  `teamId` sees none (an unowned domain belongs to no team), UNIT and `teamId=0` see them.
- **`unmappedStatuses`** -- statuses of `norm.statuses` with no stage in the connection's EFFECTIVE configuration
  (`MetricsConfigService.effectiveConfig` -- the map DERIVE used), plus any status DERIVE actually tiled UNMAPPED; `items` = work
  items (sub-tasks included) that ever sat in it, `openItems` those in it now (from `item_stage`). This is the **stage** map; a status
  missing from a board's columns (`DataProfile`'s `unmappedStatusNames`, the stub's GTM `Waiting`) is a different thing and, with the
  default category-based map, `Waiting` is mapped IN_PROGRESS (an admin can map it to the WAITING stage, A30).
- **`unmappedBoards`** -- `{total, items, unattributedDoneTasks}`: `norm.boards` with no team in the effective board map, each with its
  `sprints` (`norm.sprints`) and `doneTasks` (the period's DONE tasks that were done in one of them -- `sprint_id_at_done` set, no
  `sprint_team_id_at_done`). **`unattributedDoneTasks`** is the residual: tasks done in a teamless sprint that belongs to none of the
  listed boards (the sprint has no board, or its board IS mapped yet the sprint carries no team), so `outsideSprint` (no sprint),
  the listed boards and the residual together account for every DONE task without a sprint team.
- **`authorsWithoutTeam`** -- worklog authors with no team at `started_at`, by account (a `null` account = worklogs with no known
  author), most MD first. A real `teamId` sees none.
- **`itemsAboveEpic`** (A31) -- live `norm.work_items` (not deleted, not moved out) with `hierarchy_level >= 2` -- a Jira "Program"
  over epics, say --, which DERIVE leaves out of the task/epic model (no task, epic, backlog or estimate row). The list makes that
  exclusion visible: `AboveEpicItem { connectionId, issueKey, summary?, issueType, hierarchyLevel, projectKey, worklogMd }`, highest
  level first, then key, capped like every list (50, `total` beside it). `worklogMd` is Σ `fact_worklog.md` of the item's own
  worklogs (those rows are kept with a null `epic_id`), 0 when it has none -- ALL TIME, not bound to the report's period (the column
  reads "Logged MD (all time)"). A figure about the model's boundary, not a finding to fix: the card has no "Found" badge. Read from the live norm layer, so it shows the item even
  before the next DERIVE. Connection-level: neither the team nor the `domain` filter narrows it. Reader:
  `reports/DataQualityHierarchy.kt`.
- **`snapshotDrift`** (D13) -- for the closed, team-mapped sprints of the period (`resolveSprintRows`, the sprint's team; USER level
  none) every one of the 16 figures whose live `fact_sprint` value differs from `fact_sprint_snapshot`: the seven MD figures beyond
  0.005, their item twins exactly, `capacityMd` beyond 0.005, `load` beyond 0.0005 (a figure null on one side only counts);
  `live`, `frozen` and `delta`. A sprint with no snapshot has nothing to drift from.
- **`deriveWarnings`** (A13) -- per connection in scope, its NEWEST successful run (by start, then id) carrying
  `row_counts.sprintFieldUnresolved`: `{connectionId, connectionName, runId, startedAt, warnings: ["sprintFieldUnresolved"]}`.
  Connections without a run, or whose latest run is clean, are not listed.

**Not team-scoped.** `unmappedStatuses`, `unmappedBoards` (its `doneTasks` still follow the filter), `itemsAboveEpic` and `deriveWarnings` are
properties of a connection, not of a team: the team filter does not narrow them. `snapshotDrift` follows the sprint's own team.

**Levels (`groups`).** UNIT: one group per team that has any finding row or roster (tasks by credit/current team, worklogs by author
team, epics by owner team, roster by membership team); a `teamId: null` group is UNASSIGNED tasks and authors and UNOWNED epics. The
roster is global (D1: a team's members are not tied to a connection) while the findings are per connection, so the two reads differ:
the **default read** (every connection) keeps every roster team -- a silent team with member-days and no worklogs is the finding --
whereas a read narrowed to one `connectionId` keeps only the teams that have a finding row in that connection, so its hours are not
divided by the member-days of teams working elsewhere (the caveat: a team silent in that one connection is not listed). TEAM
(`teamId`): one group per member (tasks by assignee at done / now, worklogs by author, roster members), `epics` null. USER (`teamId`
and `accountId`): no groups, the top-level sections narrow to that member (no epics, no sprint findings). Ordered by label (null
last). Σ groups equals the matching top-level count at UNIT level for every task and worklog count.

**Cost.** Counts and sums are SQL: one `GROUP BY` (team, assignee) query per task population with conditional aggregates, one per
worklog slice, `count(DISTINCT)` for the unmapped-status items and the per-domain epic counts; only the capped lists fetch rows (`ORDER
BY … LIMIT 50`). The exceptions are deliberate: the lateness `Distribution` reads the one `late_ms` column of the period's worklogs
(`buildDistribution` is Kotlin over a fetched list, `Distribution` above), and epics (an admin-scale set) are read whole. The mapping
findings read the three configuration facts they need (`readConnectionMappings`: stage map, board map, work-category field, with
`effectiveConfig`'s stored-else-defaults rule) instead of calling `effectiveConfig`, whose defaults scan the work items; DERIVE
warnings read only each connection's newest successful run. Measured on the shared stub fixture scaled 20x (24k tasks, 24k
worklogs): about 85 ms per request; at 1x about 35 ms.

Code: `reports/DataQualityReport.kt` (DTOs, `dataQuality`), `DataQualityTasks.kt` (task, worklog and member-day rows),
`DataQualityEpics.kt` (incl. the estimate-gap rule), `DataQualityConfig.kt` (the mapping, drift and warning findings), `DataQualityAssembly.kt` (counting and
the drill). Tests: `ReportDataQualityEstimateTest` (A35: estimated late / estimated after done on hand-built rows), `ReportDataQualityEpicGapTest` (the estimate gap: both directions, the exactly-25% boundary, one-figure epics, the owner drill) and `ReportDataQualityTest` -- the stub fixture graded section by section against independent counts over the raw fact
rows (populations, coverage, every task finding, late logging against the generator's 1..5-day delays, teamless authors, epic
findings, unmapped boards and the ownerless domains) and its UNIT/TEAM/USER/`teamId=0`/domain-sliced/sprint-relative drill; a private
DERIVED clone whose configuration is changed and re-derived (a sprint calendar moves the live capacity and load away from the frozen
snapshot; removing `Waiting` from the stage map makes it an unmapped status) plus hand-moved live figures for the delta arithmetic;
hand-built rows for the populations and every task finding with hand-computed numbers, the member-days against plain weekday
arithmetic, epics incl. the horizon cut around the connection's DERIVE clock, the owner drill and the domains, and hand-inserted
DERIVE runs for the warnings. `400`s: `from > to`, `accountId` without `teamId`, an unknown connection/team/sprint, `lastSprints=0`,
a bad `domainView`.

## Report 15 -- Epic progress (EVM)

`GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, `.claude/docs/measures.md` "Report 15 -- EVM"): planned value
(PV), earned value (EV) and actual cost (AC) in man-days as curves, with SV/SPI/CV/CPI as of a day. Any signed-in
user, read-only, no audit (D12). It reads `agg_daily_flow`'s per-day INCREMENTS (A23, `.claude/docs/metrics.md`) and sums
them at query time. `asOf` is **CUMULATIVE** -- from the beginning of time, the running sum is not reset at `from`; a series
point is everything planned/earned/spent up to the end of that day under `basis=CUMULATIVE`, or (A37) rebased to the period's first
day under `basis=PERIOD`. Takes the shared `from`/`to`/`lastSprints`/`sprintId` period, `connectionId`, and its own `epicId`,
`basis` and `trendWindowDays`; `domainView` defaults to (and only accepts) `EPIC`.

```
EpicProgressReport {
  meta: ReportMeta                     // domainView always EPIC; level is the shared UNIT/TEAM/USER of teamId
  level: UNIT | DOMAIN | EPIC | TEAM   // what the report is about
  scope: { kind: EPIC|DOMAIN|TEAM, id?, key?, name } | null        // null at UNIT
  basis: PERIOD | CUMULATIVE           // the EFFECTIVE basis of `series` (A37)
  series: [{ date, pv, ev, ac, pvOriginal? }]                      // on `basis`, one point per calendar day; empty at UNIT
  asOf: { day?, pv, ev, ac, sv, spi?, cv, cpi? }                   // always CUMULATIVE
  period: { firstDay?, pv, ev, ac, sv, spi?, cv, cpi? }            // always the figures inside [firstDay, asOf.day] (A37)
  trend: [{ day, spi?, cpi? }]                                     // per week end over the trailing trendWindowDays (A37)
  epic?: { budgetMd?, budgetSource?, startAt?, dueAt?, inPvHorizon, hasPvCurve,
           baselines: [{ effectiveFrom, supersededAt?, startAt?, dueAt?, budgetMd? }], drift: { dates, budget } }   // EPIC only
  foreignWorkShare?: number            // TEAM only, cumulative window
  foreignWorkSharePeriod?: number      // TEAM only, worklogs started inside the period (A37)
  rows: [{ kind: EPIC|DOMAIN|TEAM, id?, key?, name, pv, ev, ac, sv, spi?, cv, cpi?, period: { pv, ev, ac, sv, spi?, cv, cpi? }, active? }]   // active: TEAM rows only
  note?: string
}
```

- **Scope -- at most one of `epicId` / `domain` / `teamId`** (`400` for two or more): `epicId` is an epic's ISSUE KEY
  (`FLO-33`, what every other report lists as `issueKey`; there is no numeric epic id on the wire) -> level `EPIC`; `domain` ->
  `DOMAIN`; `teamId` -> `TEAM` (`0` = UNASSIGNED, a legal value); none -> `UNIT`. What each reads, from the aggregate's own scopes:
  - **EPIC** / **DOMAIN**: the `EPIC` / `DOMAIN` scope rows. EV is the `estimate_at_done_md` of the level-0 tasks done under
    the epic, AC the worklog MD logged on the epic's tasks and on the epic itself, PV the epic's CURRENT baseline spread over the
    working days -- **epic-attributed work only** (a task with no epic has no plan to compare against, A23).
  - **TEAM**: the `TEAM` scope -- PV = the team's sprints' committed scope on each sprint's start day, EV = what those sprints
    delivered on the done day (A20), AC = the author's-team worklogs; `teamId=0` is the `UNASSIGNED` scope (AC only, so PV = 0 and SPI
    is `null`). **`foreignWorkShare`** = Σ `fact_worklog.md` with `foreign_work` for the team's authors ÷ Σ their `md`, over the
    SAME cumulative window as the cumulative CPI -- from the beginning of time to the end of `asOf.day` (`foreignWorkSharePeriod` is the period's, A37) (a fraction
    0..1, `null` when they logged nothing; the authors in no team of `teamId=0` are never foreign, so `0.0`): A20 -- read team CPI
    with it.
  - **UNIT**: every `DOMAIN` scope summed -- the epic basis, consistent with `domainView=EPIC`, and free of the double counting of
    an item that sits in overlapping sprints (a sprint sum would count it once per sprint). So the UNIT headline is epic-attributed
    work only, exactly what the domain rows below add up to. No series; `rows` is the drill.
- **`basis`, `period`, `trend` and the thin-index rule (A37, `reports/EpicProgressPeriod.kt`).** `basis` is `PERIOD` or `CUMULATIVE`
  (an unknown value is `400`); absent it defaults per level -- EPIC `CUMULATIVE` (an epic's whole life is the right scale), DOMAIN, TEAM
  and UNIT `PERIOD` -- and the EFFECTIVE value is echoed in the response (also in the empty answers). It only picks what `series` shows:
  `CUMULATIVE` points are the running sums from the beginning of time (the old series, unchanged); `PERIOD` points are `running(d) -
  running(firstDay - 1)`, so the curves start near 0 (at EPIC level `pvOriginal` is rebased by the same day's value, `null` staying
  `null`). `asOf` and every row's top-level figures are cumulative whatever the basis, and the `period` block is always returned:
  `firstDay` is the period's first listed day (the first day of the series, also at UNIT level where no series is listed), the figures are
  the deltas `ΔPV`/`ΔEV`/`ΔAC` over `[firstDay, asOf.day]` with `sv = ΔEV - ΔPV`, `cv = ΔEV - ΔAC`; a period that starts AFTER `asOf.day`
  has `firstDay` null and every figure 0. **`spi` / `cpi` of the `period` block, of every row's `period` and of every `trend` point are `null`
  while their denominator (`ΔPV` for SPI, `ΔAC` for CPI) is under `MIN_PERIOD_DENOMINATOR_MD` = 1.0 man-day** (EV arrives in lumps, so a
  ratio over a sliver is noise; a zero or negative delta is hidden too, exactly 1.0 is shown). The constant is not a `metrics.settings`
  value (no per-install tuning is expected, and a setting costs a migration and a settings-API change). The cumulative `asOf` / row indices
  keep the old rule (`null` only at PV or AC exactly 0). `trend` has one point per WEEK END of the period -- Monday-start weeks in the
  configured zone, the throughput report's week rule -- the last one cut at `asOf.day` (so a period of one day has one point), each
  `ΔEV ÷ ΔPV` and `ΔEV ÷ ΔAC` over the trailing `(day - trendWindowDays, day]`; `trendWindowDays` defaults to 28 and is bounded 7..182
  (`400` outside, not an integer, or repeated). The window reaches before the period start when it must (the cumulative fetch already
  holds those days), and the trend comes from the ONE fetch of the increments, in Kotlin -- never a query per point. It is present at every
  level, an empty list when the period lies after `asOf.day`. **Rows:** each row's `period` is its own `[firstDay, asOf.day]` delta (a second
  grouped read with `day >= firstDay`); the UNIT's domain rows sum to the UNIT headline's `period` exactly as they sum to its `asOf`.
  **`foreignWorkSharePeriod`** (TEAM) is the same share over the worklogs with `started_at` inside `[firstDay start, asOf.day end)`, the
  window of the period CPI; `foreignWorkShare` keeps the cumulative one -- the page shows the share that matches the CPI it shows.
- **`400`s**, checked before any data is read: an unknown `basis`, a `trendWindowDays` outside 7..182, more than one scope, `accountId` (there is no user-level EVM), an explicit
  `domainView=TASK` (EVM is always the EPIC view, D3), `activityType` / `workCategory` (not stored per day -- the WIP/backlog
  reports' precedent), a blank `epicId` (a present-but-empty value is a mistake, not "the whole unit"), and an unknown epic key, domain, team, sprint or connection -- always `400`, never `404`. An epic key that
  exists in several connections in scope is ambiguous (`400`, narrow with `connectionId`).
  A scope that cannot be checked because NOTHING in scope has derived yet (no `dim_epic`/`dim_domain` rows exist) is not `400`: it is
  the empty "not derived yet" answer below, the scope named by the key alone.
- **`asOf` -- the period rule.** `asOf.day` = min(the period's last day, today in the configured zone, the last DERIVED day);
  the last derived day is `derivedCoverage`'s (the OLDEST, over the connections in scope that have derived, of each one's newest
  SUCCEEDED `derive_runs.started_at` day -- the flow-snapshot reports' cut-off), because EV and AC are unknown past it and comparing
  them to a plan that keeps running would bias SPI/CPI. `series` lists one point per calendar day of the period (the resolved
  sprints' envelope, `periodWindow`, for a `lastSprints`/`sprintId` period) up to `asOf.day`. A period that starts AFTER `asOf.day`
  has an empty series, `asOf` still the running sum at `asOf.day`, and a `note` saying so. `sv = ev - pv`, `spi = ev / pv` (`null`
  when PV is 0), `cv = ev - ac`, `cpi = ev / ac` (`null` when AC is 0). MD are rounded to 2 decimals, the ratios are unrounded
  (as every other report's ratios).
- **`rows` -- the drill, as of `asOf.day`.** DOMAIN: one row per epic of that domain (`dim_epic.domain_key`, the epic's current
  domain) with a current baseline OR any EV/AC up to `asOf` (`kind` EPIC, `key` the issue key, `name` the summary, else the key),
  ordered by key -- an epic that changed domain is listed under its current one, while the DOMAIN totals keep the as-was attribution,
  so the DOMAIN total is authoritative. UNIT: one `DOMAIN` row per domain (every domain the connections know, plus any domain scope
  with figures) on the epic basis -- these add up to the unit's `asOf` -- then one `TEAM` row per team (every active team, plus any
  team scope with figures -- `id` 0 is UNASSIGNED, sorted last) on the sprint/author basis: a DIFFERENT view (sprint scope, author-team
  cost) whose rows do NOT sum to the UNIT headline. A team row carries `active`: `false` marks a soft-deleted team that still has
  figures, whose own drill (`teamId`) answers `400`; UNASSIGNED and live teams are `true`. EPIC and TEAM: empty.
- **The EPIC block.** `budgetMd`/`budgetSource` are the CURRENT baseline's (D4: `OWN` estimate, else `CHILDREN`), or, with no current
  baseline, the delivery fact's; `startAt`/`dueAt` the epic's own dates (UTC midnight millis of a calendar date, zone-free);
  `baselines` every `fact_epic_plan` baseline oldest first; `drift` compares the CURRENT baseline with the FIRST (`dates`: start or
  due moved, `budget`: it changed -- both `false` with one baseline). `inPvHorizon` is literal: the current baseline is complete and
  both its dates lie within +-10 years of the connection's DERIVE clock (`inPvHorizon`), otherwise there is no PV
  (A23) -- while EV and AC still count. `hasPvCurve` adds that the window holds a working day, so PV is actually spread (a weekend-only
  window is in the horizon but has no curve). `series[].pvOriginal` is the epic's FIRST baseline redrawn: the working days come from
  `pvCurve` under the calendar DERIVE used -- `metrics.dim_date.is_working_day` where `dim_date` covers the whole
  window (the table is global; a DERIVE at the current settings revision rewrites every row that differs, so `dim_date` carries the current
  calendar once any DERIVE of ANY connection at that revision has run — a calendar edited since moves `pvOriginal` only then), else the current settings
  calendar (a superseded baseline's window outside the range DERIVE keeps stamped) -- and each day's cumulative value is rounded
  exactly like the stored increments (`ROUND(budget * i / n, 2)`, the last day the budget), so an epic with ONE baseline has
  `pvOriginal == pv` on every day (also for a budget that does not divide) and a re-planned one shows the gap. It is `null` where that
  baseline has no curve (out of horizon, no working day) and at every other level.
- **Multi-connection and derive state** are the flow-snapshot reports' precedent: `connectionId` narrows to one active connection
  (else every active one, their rows summed); a connection with no successful DERIVE is ignored for the cut-off and named in `note`;
  nothing derived at all answers empty (`series`/`rows` empty, `asOf.day` null, every figure 0) with the same "Not derived yet" note;
  no sprint resolved for a sprint-relative period (or `teamId=0` with one) is empty with that note.

Code: `reports/EpicProgressReport.kt` (the DTOs, the `epicProgress` entry as an extension on `ReportService` and `progressOf`, the
assembly; `snapshotNotes` in `reports/SnapshotSupport.kt` is shared with WIP/backlog), split by concern like `DataQuality*`:
`EpicProgressTargets.kt` (`ProgressTarget`/`ResolvedTarget`, the `400` combination checks `progressTargetOf`, `resolveTarget`),
`EpicProgressEvm.kt` (the `Evm` triple, `aggPredicate`/`fetchIncrements`/`totalsThrough`, the EPIC-level plan block and curve replay
`epicDetail`, `foreignWorkShare`), `EpicProgressRows.kt` (the `domainRows`/`unitRows` drill tables, cumulative and period) and
`EpicProgressPeriod.kt` (A37: the `basis`/`trendWindowDays` options, the pure period delta, series rebase, trend and thin-index arithmetic).
Tests -- `EpicProgressPeriodKernelsTest` (the pure period/trend arithmetic and the options parser) and `ReportEpicProgressPeriodTest` (on the
derived fixture every level's PERIOD series, `period` block, trend, rows and the foreign-work share of the period against an INDEPENDENT read
of the persisted `agg_daily_flow` / `fact_worklog` rows, the UNIT identity on both bases, the per-level defaults and `400`s; on hand-built rows in
a fresh disabled connection the hand-computed deltas, the exactly-1.0 boundary, thin windows, a window reaching before the period, the derive
cut-off and a period after `asOf`); `ReportEpicProgressTest` stays the cumulative path (its level reads ask `basis=CUMULATIVE`): on the shared derived fixture every
level's `asOf`, every series point (monotone, ending at `asOf`), the DOMAIN/UNIT drill rows and the sum of DOMAIN epic rows against
an INDEPENDENT running sum of the persisted `agg_daily_flow` rows (UNIT against the DOMAIN scopes, the domain rows summing to it);
the golden epic's PV reaching its budget on its due date, its exactly-one baseline and its plan block against `expected.json`; sprint-relative and past-the-derive periods; and, on hand-built rows in fresh disabled connections,
the exact SV/SPI/CV/CPI (PV 0 -> SPI null, AC 0 -> CPI null), a superseded baseline (`pvOriginal` vs `pv`, both drift flags), an
out-of-horizon epic, the drill rows (incl. a soft-deleted team marked `active: false`), the oldest-derive cut-off
across connections, the never-derived answer, the cumulative team foreign-work share, `pvOriginal` against the stamped `dim_date`
calendar and an independent `ROUND(b*i/n, 2)` for a non-divisible budget, `hasPvCurve` for a weekend-only window, and every `400`
above (a blank `epicId` included; a plain user gets `200`).

## Report 16 -- Cost matrix and foreign work

`GET /api/v1/reports/cost-matrix` (v0.3.0 M5 commit 17b, `.claude/docs/measures.md` "Report 16"): the man-days logged in the period
as an author-team x domain matrix, with the foreign-work share beside every row. Any signed-in user, read-only, no audit (D12). It
reads `fact_worklog` directly (no aggregate, so no derive cut-off and no `note`): every worklog whose `started_at` is in the period
(inclusive ISO dates in the configured zone; a `lastSprints`/`sprintId` period reads the resolved sprints' envelope, `periodWindow`)
is charged to the AUTHOR's team as of `started_at` (`author_team_id`; null = UNASSIGNED) and to a domain.

```
CostMatrixReport {
  meta,
  columns: [{ domain?, name?, totalMd }],         // domains that received work, by key; `(no domain)` (domain null) last if any
  rows:    [{ teamId?, accountId?, label?, cells: [{ domain?, md }], totalMd, foreignMd, foreignShare? }],
  totalMd, foreignMd, foreignShare?,              // the grand figures
  byActivityType: [{ key, md }], byWorkCategory: [{ key, md }],   // the same worklogs split for the donuts (below); always present
  capacity?: { totalMd, days, calendarDays },    // ONLY for an ADMIN / the team's active manager (below); rows then carry capacityMd?
  split?: { kind: WEEK|SPRINT, buckets: [{ key, label?, from, to, sprintId? }] },   // ONLY with `split=WEEK|SPRINT` (below)
  bucketTotals?: [{ columns: [CostColumn], totalMd, foreignMd, foreignShare?, capacityMd? }]   // one per bucket; rows then carry
                                                  // byBucket?: [{ cells (dense over the PERIOD columns), totalMd, foreignMd, foreignShare?, capacityMd? }]
}
```

- **Columns and `domainView`.** `EPIC` (the DEFAULT -- a worklog-cost measure is PV/EV/AC-shaped, D3) uses `epic_domain_key`, an
  epic-less task falling back to its own `task_domain_key` (so there is no `(no epic)` column); `TASK` uses `task_domain_key`, so a
  cross-domain task moves from the epic's column to its own. A worklog logged ON an epic carries the epic's own domain in both
  columns (A21) and is the same in both views; so does a worklog logged on an issue ABOVE the epic level (A31): its own domain in both
  columns, no epic. `task_domain_key` is never null (invariant 6), so the `(no domain)` column
  (`domain` null) exists only so a corrupt row could never silently leave the totals. Columns are only the domains the scoped
  worklogs reach (empty for none), ordered by key; `name` is `dim_domain.name` (the lowest connection id's for a key seen on several),
  null when no row names it.
- **Rows and levels.** UNIT: one row per author team that logged work, ordered by team name, UNASSIGNED (`teamId` 0, `label` null --
  the authors in no team) last; `teamId` is the id a client drills with. `teamId` set: one row per AUTHOR of that team as of
  `started_at`, by display name (`norm.people`, the account id when none is known; `label` null and `accountId` null for worklogs with
  no known author, last), every row's `teamId` the requested one; `teamId=0` lists the authors in no team the same way. `teamId` +
  `accountId`: that author's one row (a zero row when they logged nothing there). The columns of a drill are those of its own worklogs.
  **Zero rows (A39 amended 2026-10-17).** At TEAM level of a real team (`teamId` >= 1) the rows are the authors above PLUS every member
  of the team whose `team_membership` interval covers at least one WORKING day of the period (`reports/CostMatrixRoster.kt`
  `costZeroRowAccounts`: the period's days from `periodDaysOf`, the ONE `WorkingCalendar` with public holidays off, each day matched
  against the membership with the A3 overlap predicate on the day's bounds; the whole period, not clipped to today), each with all-zero
  DENSE `cells`, `totalMd` 0, `foreignMd` 0, `foreignShare` `null` (and, with `split`, dense zero `byBucket`); a membership covering only
  weekend or holiday days of the period, or ended before it, adds none. At USER level the picked person is always listed (a zero row
  when they logged nothing; the `accountId` need not be a member). The set is the SAME FOR EVERY VIEWER and reads the roster and the
  calendar only, never a capacity figure (capacity is withheld from non-editors, so a capacity-dependent row set would leak it); an
  editor's zero row carries its `capacityMd`. A zero row sorts in by the one row order (display name through `norm.people`, the
  account id when none is known; `null` last, then team id, then account id), among the authors. Columns, totals and the grand figures
  are unchanged. The rule applies ON TOP of the slices (`domain`, `activityType`, `workCategory`, `connectionId`): they narrow the worklogs, never
  the roster, so a member who logged only outside the slice is a zero row. A member who changed teams mid-period appears in BOTH teams' TEAM
  views (each covers some working days of it). With members but no worklog in the period the matrix has `columns: []` and every row `cells: []`
  (and, with `split`, empty bucket `cells` and footer `columns`). UNIT level and `teamId=0` (no roster) are unchanged; a period that resolves
  nothing still answers the empty matrix.
  Authors and teams with no worklogs in scope otherwise have no row. A UNIT row carries `active`: `false` marks a soft-deleted author team that
  still logged work in the period (it keeps its name; its own drill `teamId` answers `400`, the epic-progress team-row precedent);
  UNASSIGNED and live teams are `true`; TEAM/USER rows leave it out. With a sprint-relative period a UNIT team row reads the unit's
  UNION envelope (`periodWindow` over every resolved sprint), while the team drill resolves only that team's own sprints, so the two
  can differ.
- **Cells are DENSE** -- every row carries one cell per column, in column order, `md` 0 where nothing was logged.
- **Rounding -- the rule, pinned by a test.** MD are summed EXACTLY (`decimal(8,4)` sums, no float) and rounded to 2 decimals half-up
  ONCE, at the figure: a cell is the rounded exact sum of its worklogs; a row total, a column total and the grand total are each the
  rounded exact sum of THEIR worklogs -- never the sum of already-rounded cells. Every worklog therefore lands in exactly one row and
  one column (invariant 6) and each total is the true figure to the cent, but a displayed total can differ from the sum of its
  displayed addends by up to 0.005 per addend (three cells of 0.3333 show 0.33 each and a row total of 1.00).
- **Foreign work (A21, A22).** `foreignMd` = the exact MD with `fact_worklog.foreign_work`, `foreignShare` = exact foreign MD / exact MD
  (a fraction 0..1, `null` when the row or the report logged nothing -- never 0). The flag is derive-time: task-logged = the author's
  team differs from the task's sprint team at `started_at`, else from the assignee's team then; epic-logged (and, A31, logged on an issue above the epic level) = differs from the item's own
  domain owner team; an unknown side is never foreign, so UNASSIGNED authors read 0.0. It is PER PERIOD -- the epic-progress report's
  team `foreignWorkShare` is the same ratio over the cumulative window up to `asOf`, a different figure by design.
- **Slices.** `domain` (matched against the same column the view uses), `activityType`, `workCategory` (`UNCATEGORIZED` = none) and
  `connectionId` (else every active connection, summed) restrict the worklogs.
- **Breakdowns (`byActivityType`, `byWorkCategory`) -- the two donuts.** The very worklogs the matrix reads (`worklogSlice`: period, level, domain
  and its view, `activityType`, `workCategory`, `connectionId`), split by `fact_worklog.activity_type` / `work_category` in two more `GROUP BY` queries
  of the SAME transaction (`reports/CostMatrixBreakdown.kt`, `fetchCostBreakdown`), so a list can never describe other worklogs than the matrix.
  One `{ key, md, exactMd }` per key, ordered by `md` descending, then key; `md` is the rounded EXACT sum (the matrix's rule) and `exactMd` that sum unrounded (4 decimals, as stored: a client that merges slices -- the folded "Other" -- adds these and rounds once, so its figure equals the drill's `bar.sumMd`), so the exact sums of each list
  equal the grand total's and the rounded slices add up to `totalMd` within 0.005 per slice (three thirds in three categories sum to 0.99 beside 1).
  **Null keys:** a NULL `work_category` is the key `UNCATEGORIZED` -- the sentinel the `workCategory` slice already uses; a NULL `activity_type` is the
  SAME key (the derive always fills the column -- `?: issue type` -- so only a corrupt row reaches it, and the `activityType` slice, which matches by
  equality, cannot select it either, exactly as it cannot reach it in the matrix); a literal `UNCATEGORIZED` value merges into it. The lists are
  present whatever the slice (a sliced breakdown has one key; the SPA does not draw it), empty for an empty matrix, and independent of `split` and of
  capacity. Zero-MD keys are listed (the server is honest about them); the SPA drops them and folds the slices under 3% into "Other"
  (`.claude/docs/web-features.md` "Cost matrix").
- **Drill (`GET /api/v1/reports/cost-matrix/items`).** The tasks behind one slice of a donut -- "Drill to items" above, `reports/CostMatrixDrill.kt`. It takes
  the cost matrix's parameter list (without `split`) plus `by` (`ACTIVITY_TYPE` | `WORK_CATEGORY`, required) and `key`, the slice: a REPEATED required
  key (`repeatedStrings`, 1..`COST_DRILL_MAX_KEYS` = 100 distinct, trimmed, no control characters; the first `IN` semantics of a drill, API-LIST-004), the
  folded "Other" slice sending all of its keys; `UNCATEGORIZED` also selects the NULLs. Worklogs: `worklogSlice` AND `column IN keys`, grouped per
  (connection, issue, author) in SQL with the exact MD and the latest `started_at`, then rolled up in Kotlin to the item they belong to: a sub-task's
  worklogs to its parent task (`dim_task.parent_task_id`, D2/invariant 7), a worklog logged ON an epic (`epic_id = issue_id`) lists the epic
  (`itemKind` EPIC, the Kind column), one logged on an issue above the epic level (A31) lists that issue as a TASK. `figure` = the item's MD in the slice
  (rounded exact sum), `anchorAt` = its latest `started_at`, `assignee` = its authors' display names joined with `, `, the largest contributor first (the shared `ReportDrillItem` schema describes this overload)
  (`accountId` set only for exactly one author; a worklog with no known author is not named; `teamId` null), `bar.sumMd` = the rounded exact sum
  of the slice's worklogs -- the donut's figure for one key, the union's for "Other" (the SPA's "Other" figure is the folded slices' `exactMd` added and rounded once, so it equals the bar exactly: two slices of 0.125 show 0.13 each and
  Other 0.25) --, `bar.count` = `total` = the items. An unknown key lists nothing (`200`); `400`s: the
  parent's own parser errors (after `call.caller()`), a missing/unknown `by`, a missing or blank `key`, more than 100 keys, a control character.

- **Capacity column (A39).** Who: an ADMIN or the ACTIVE manager of the filter's team, for a TEAM/USER read of a real team (`teamId`
  >= 1) -- UNIT level and `teamId=0` (UNASSIGNED: no calendars, no roster) never carry it, not even for an ADMIN, and neither does an
  empty period. Everyone else gets the matrix with the keys ABSENT (`@EncodeDefault(NEVER)` on the `null`-default fields -- Ktor's
  default `Json` encodes defaults, so a plain `= null` would send `null`; `ReportCostMatrixCapacityTest` asserts the raw JSON holds no
  `capacity` substring). What: `capacityMd` = the person's man-days available over the period's days (configured zone, clipped to
  today -- nothing can have been logged against a future day), `Σ avail(person, day)` in exact decimals rounded half-up ONCE (the
  totals row = the rounded exact sum over the ROWS' people, so it can differ from the sum of the displayed rows by 0.005 each; the
  no-author row has none). One **day map** decides each day's owner: the team's `dim_sprint` rows with a `start_at` (the lowest
  connection id's row for a sprint id seen on several), ordered by start then sprint id, each owning `dayOf(start) .. dayOf(max(start,
  end - 1))` (the A32 window rule); the FIRST owner of a day wins, so overlapping sprints never count a day twice. A day owned by a
  sprint with a STORED calendar saved for THIS team (a row left for another team after a board re-map is ignored, DERIVE's rule) and an
  accepted window (<= 60 days) is a **calendar day**: `projectCalendar(stored, window, roster, calendar, carry = null)` -- DERIVE's exact
  arguments, the roster being the team's memberships overlapping the sprint's instants -- then `personDayCapacity` (the kernel
  `calendarCapacityMd` sums), so a public holiday is off whatever is stored (A33), a stored non-member stays counted and a member
  missing from the document is added with coefficient 1. Every other day (a gap between sprints, a sprint with no calendar, no start,
  or over 60 days) is a **default day**: 1 MD for each person with a `team_membership` of the team overlapping the day when it is a
  working day (the A3 DEFAULT per person, how DERIVE prices a sprint without a calendar). `capacity.calendarDays` of `capacity.days`
  says how many period days read a saved calendar. The team default figure (`team_capacity_defaults`) is a team figure with no
  per-person split and plays no part; so Σ rows is not `fact_sprint.capacity_md` in general -- only a fully covered saved calendar
  reconciles to it, to the rounding bound. A person who changed teams has, in each team's drill, only that team's calendar and
  membership days. The reads (`dim_sprint`, `team_membership`, `sprint_calendars` through `metrics/SprintCalendarReads.kt`) run in the
  report's transaction, once per request, and only when the caller may see the block. Stored calendars are keyed by (connection id,
  sprint id) and looked up with the day owner's own connection, so a calendar is never paired with another connection's sprint row.
  Capacity is a FULL-period figure: the `domain`, `activityType` and `workCategory` slices narrow the logged MD, never the capacity
  beside it (a sliced MD sits next to the unsliced capacity).
- **Split (A39 part 2).** `split` = `NONE` (the default) | `WEEK` | `SPRINT`, a scalar enum parsed in the route right after the shared
  filter (`params.optionalEnum<CostSplit>("split")`, the `bucket` precedent: a bad value or a repeat is `400`; a dedicated key, NOT
  `bucket`, which is `WEEK|MONTH` on throughput and cycle time). `NONE` is today's response BYTE FOR BYTE: every new field is a
  `null`-default property annotated `@EncodeDefault(NEVER)` (Ktor's `Json` encodes defaults, so a plain `= null` would send the key);
  `ReportCostMatrixSplitTest` compares the raw bodies of `?...` and `?...&split=NONE` at UNIT, TEAM and USER level. With a split the
  response adds `split {kind, buckets}`, every row's `byBucket` (DENSE: one entry per bucket, in bucket order, its `cells` dense
  over the PERIOD's columns, plus `totalMd`/`foreignMd`/`foreignShare` and, where the row shows capacity, `capacityMd`) and
  `bucketTotals` (the footer per bucket: per-domain `columns`, `totalMd`, `foreignMd`, `foreignShare`, `capacityMd` = the rounded exact
  sum over the ROWS' people, PRESENT -- 0 when no row has a person -- exactly when the response carries `capacity`, `null` only when it
  does not; the no-author row alone has no `capacityMd`, in a bucket or out); `columns`, the rows' own `cells`/`totalMd`/... and the grand figures keep their PERIOD meaning. ONE
  day -> bucket map (`CostBucketPlan.keyByDay` over every period day, configured zone) decides the bucket of a worklog (its
  `calendar.dayOf(started_at)`, so a worklog at 00:30 Warsaw on a Monday is that Monday's week although it is Sunday in UTC) AND of a
  capacity day, so cost and capacity partition the period the same way; every figure is the rounded EXACT sum of ITS worklogs, so
  bucket figures differ from the period figure by at most 0.005 per addend. **WEEK**: `bucketStarts`/`bucketStart` of the
  throughput report (`ThroughputReport.kt`: Monday-start, configured zone, zero-filled), any level; a bucket's `from`/`to` are its
  days clipped to the period. **SPRINT**: needs a real team (`teamId` >= 1; absent or `0` is `400` "split=SPRINT needs a team
  (sprints are per team)"); the buckets are the team's sprints (`dim_sprint` with a `start_at`, the lowest connection id for a sprint
  id seen on several) that OWN at least one period day -- the day map of the capacity column (`sprintDayOwners`, shared, not
  re-derived; the first sprint by start then id owns a day two sprints share; a sprint without `start_at` and another team's sprint
  are no bucket) -- in start order, then LAST, only when some period day is owned by no sprint, the "Outside sprints" bucket (`key`
  `outside`, `label`/`sprintId` null, `from`/`to` its first/last such day, which need not be contiguous); a sprint bucket's `label` is the sprint name, `key` the sprint
  id. A SPRINT split hands its day map to `loadCostCapacity`, which then does not read the sprints a second time. Bound:
  `MAX_COST_BUCKETS` = 160 -> `400` "The split has N buckets; at most 160 -- narrow the period". A date range tops out at 1100 days
  = 158 weeks, so only a SPRINT split (degenerate one-day sprints) or a sprint-relative period (`lastSprints`/`sprintId`, whose
  envelope is not capped at 1100 days) can trip it; the latter's WEEK split answers a sprint-aware `400` ("The period spans N weeks;
  at most 160 -- too many weeks for a weekly split, use the sprint split or a shorter period"). Both split `400`s are thrown in the service AFTER `resolveReportScope`
  (the shared parser's `400`s keep their order); a period that resolves nothing answers the empty matrix with an empty
  `split.buckets`/`bucketTotals`. Data fetch: `fetchCostAggregates` cannot group by a configured-zone day in SQL, so a split reads
  the slice's per-worklog rows (`started_at`, `md`, author team/account, both domain keys, `foreign_work`, `worklogSlice` as the
  predicate) and folds them in Kotlin into per-(team, author, domain, foreign, bucket) EXACT sums (a team x year is about 6k
  rows; the pathological unit-wide 1100-day week split about 200k, under the statement budget; a `dim_date` range join is the
  perf follow-up if it is ever needed).
- **`400`s:** the shared parser's (`accountId` without `teamId`, `from` after `to`, mutually exclusive periods, `lastSprints` out of
  range, a bad `domainView`/enum, a repeated scalar key) and an unknown or inactive team, connection or sprint -- always `400`, never
  `404`. An unknown `domain`, `activityType` or `workCategory` is a valid slice with an empty answer; `teamId=0` with a sprint-relative
  period resolves no sprint and is empty (the epic-progress precedent, no note field here). Added by the split: `split=SPRINT` without a
  real team, more than 160 buckets, and a bad or repeated `split`.

Code: `reports/CostMatrixBreakdown.kt` (the `CostBreakdown` enum, `CostBreakdownSlice`, `fetchCostBreakdown`), `reports/CostMatrixDrill.kt` (the drill: key parsing, the roll-up, the items), `reports/CostMatrixReport.kt` (DTOs and the query as an extension on `ReportService`; the `orgGroups` drill and `resolveReportScope`
from `ReportSupport.kt`), `reports/CostMatrixCapacity.kt` (the day map, the default-day rule and the per-person per-day assembly;
`metrics/SprintCalendarReads.kt` reads the team's stored calendars; the visibility predicate sits in `ReportRoutes.kt`), `reports/CostMatrixRoster.kt` (the zero rows: the roster accounts covering a working day, and merging them into the sorted org-drill groups), `reports/CostMatrixSplit.kt` (the `split` enum, the bucket DTOs, the WEEK/SPRINT bucket plan, the per-worklog fetch and the per-bucket assembly). Tests -- `ReportCostMatrixTest`: on the shared derived fixture the grand total, every row, column, cell and
the foreign figures in both views against an INDEPENDENT sum over the raw `fact_worklog` rows (two periods, a domain slice, the rows
and columns reconciling to the total within the rounding bound); on hand-built rows in a fresh disabled connection the exact cells,
the TASK/EPIC switch for a cross-domain task, an epic-logged and an epic-less worklog, the thirds rounding rule, the foreign shares
(incl. null), the period and slice filters, the team/user/UNASSIGNED drills and every `400` (a plain user gets `200`).
`ReportCostMatrixZeroRowsTest` (fresh disabled connection, January 2026): a member with no worklogs is a zero row for a plain user and an
admin alike (the same rows and order, only `capacityMd` differs, the capacity totals counting the zero row's person); a membership
covering only a weekend or a public holiday of the period, a former member and another team's member add no row; USER level lists the
picked person, UNIT level and `teamId=0` are unchanged; with `split` a zero row has dense zero buckets.
`ReportCostMatrixBreakdownTest` (fresh disabled connection, January 2026, plus the derived fixture): both lists on hand-built worklogs -- order by MD then key, a NULL activity
type as `UNCATEGORIZED`, the thirds that sum to 6.74 beside 6.75, a sub-task, an epic-logged and a team-less worklog, February outside the period -- narrowed by team, author,
the `UNASSIGNED` bucket, `activityType`, `workCategory`, `domain` and `split`; the drill on the same rows (the sub-task rolled up to its parent, the authors, the epic listed
as an epic with its key, `UNCATEGORIZED`, the repeated keys of "Other", the level and slices, the `400` matrix incl. 100 keys fine and 101 not, the 501st-item
truncation); and on the derived fixture both lists against an independent grouping of the stored worklogs, in order, adding up to the total, and EVERY slice's drill bar equal to the slice.
`ReportCostMatrixCapacityTest` (the capacity column, fresh disabled connection, January 2026): an admin and the team's manager see the
figure while a plain user and another team's manager get a response whose raw JSON has no `capacity` key, also under `split=WEEK` and `split=SPRINT`; a cleared grant or a
soft-deleted manager loses it on the next request and UNIT/UNASSIGNED never carry it; hand-built calendars (team coefficient, a 0.5
person and cell, a turned-off day and person, the locked-off holiday, a new member, a stored non-member); default days (a weekend,
Jan 1, a mid-period membership, the team default changing nothing, days after today); overlapping sprints, another team's calendar and
a person who changed teams; the rounded exact total and the no-author row. `CostMatrixCapacityDaysTest` pins the pure day map and
default-day rule, a calendar paired only with its own connection's sprint row and the sprint-aware weekly-split `400`.
`ReportCostMatrixSplitTest` (fresh disabled connection, January 2026): WEEK zero-fills Monday-start weeks (a worklog at 23:30 UTC
on a Sunday is Monday in Warsaw, the first week is cut at the period start, an empty week is all zeros) with every worklog in exactly
one bucket; SPRINT buckets in start order with the outside time last (a first-day 08:00 worklog in its sprint, a shared day to the
earlier sprint, a sprint with no start and another team's sprint no bucket, no Outside bucket when every day is owned); the
bucket figures reconcile to the period figures within the rounding bound and capacity splits per bucket (stored calendar, default
days, holiday, outside days) with the footer, whose capacity is 0 (not a dash) when no row has a person or the period has no worklog; `split=NONE` is byte-identical and `SPRINT` without a team, 161 buckets (160 is fine) and
a bad or repeated `split` are `400`.

## Report 17 -- Deep dive

Status: implemented (A29, `.claude/docs/domain-model.md`; per-measure rows in `.claude/docs/measures.md` "Report 17"): the report
(`reports/DeepDiveReport.kt` with its wire shapes in `DeepDiveWire.kt`, `DeepDiveSelection.kt`) and the two option lists (`reports/DeepDiveOptions.kt`) on the server, the SPA page
(`pages/ReportDeepDive.tsx`, #71) with its drillable matrix, and a Burn-up tab (#74: `DeepDiveBurnup*`, pure client-side arithmetic over the SAME
response, no extra request). Reports-layer only: it reads facts `DERIVE` already writes (`fact_sprint_scope`,
`fact_task_delivery`, `item_stage`, `fact_worklog`, `fact_epic_plan`, `dim_date`) -- no new `DERIVE` step, no migration. Any signed-in user,
read-only, no audit (D12), every operation answers `200`/`400`/`401` only.

One matrix compares plan (PV), Approximate EV and cost (AC) for a chosen set of work, in man-days, all on one scale. The server returns sparse
DAILY series per task and layer; the client sums them into weeks, months and epic rows, so drilling never refetches. The layer rules (what a
task's PV is, where the EV marker and each worklog fall) are A29, and Approximate EV is A40; neither is repeated here. Approximate EV spreads a
DONE task's EV evenly over the working days it spent IN_PROGRESS, so it sums to EV exactly at every level. WAITING time (A30, a queue) and BLOCKED
time (A36, an impediment) earn nothing; a day Flagged in Jira while the task is still in an IN_PROGRESS status counts like any other in-progress
day (the flag is not subtracted -- flow efficiency, A18, is the measure that does).

### Endpoints

- `GET /api/v1/reports/deep-dive` -- the report.
- `GET /api/v1/reports/deep-dive/sprints?domain=` -- the sprint picker's options, for a domain.
- `GET /api/v1/reports/deep-dive/epics?q&domain` -- the epic picker's options, optionally narrowed to a domain; `q` is a case- and
  accent-insensitive substring over key and summary.

The two option lists are ordinary list endpoints (`.claude/docs/list-endpoints.md`): `parsePaging`/`applyPaging` with a per-endpoint sort
whitelist (`400` for an unknown sort field, `id`-asc tiebreaker), `q` through `containsNormalized` (`infra/db/Sql.kt`), the
`{items, page, pageSize, total}` envelope, count and rows on one predicate in one transaction. (A third list, one epic's tasks for a
"Tasks of an epic" handpick mode, was removed with that mode; see the `issueId` note under "Selection".)

Option list shapes (all take `connectionId`, `q`, `page`, `pageSize`, `sort`; items carry `connectionId` and an `id` that is the Jira id, unique
within a connection): **sprints** -- `domain` REQUIRED (missing, blank or unknown on an active connection in scope is `400`); the sprints in which
at least one level-0 task of that domain (the task's OWN domain, as mode (a) selects) was `in_scope_at_close`, each `{id (sprint id), connectionId,
name, state, startAt, endAt, completeAt, taskCount}` with `taskCount` the number of those tasks; `q` over the name; sort `id|name|startAt|completeAt`,
default `-id`. A domain absent from `dim_domain` (for example a task's as-was raw project key that the domain map no longer holds) is `400` here while `/epics`
answers an empty page, consistent with `/reports/filters` never offering such a domain. **epics** -- `dim_epic` rows `{id, connectionId, key, summary?, domain?}`; optional `domain` (an unknown domain is an empty page, not a
`400`); `q` over key and summary; sort `id|key|summary|domain`, default `key`. `domain` and `q` are trimmed and checked for control characters (a NUL is `400`, never a database error). The connection id is the final tiebreaker after `id`. No planned dates ride on the epic options: `dim_epic.start_at`/`due_at` are not the
`fact_epic_plan` baseline the report draws.

### Selection -- exactly one of two modes, set by the parameters

One selection reads ONE connection (`connectionId`, else resolved by `resolveConnectionScope`; a key present in several connections in
scope is ambiguous, `400`, narrow with `connectionId`; in mode (a) the domain narrows the candidate connections FIRST -- `connectionsWithDomain`,
`reports/ReportDomains.kt` -- so a sprint id shared by two connections is ambiguous only when both hold the domain, and the one ambiguity left is a
domain that spans connections). The SPA never asks for a connection: it sends `connectionId` only when the picks name exactly one of several active
connections (`.claude/docs/web-features.md` "Deep dive"). The report has its OWN parser (`reports/DeepDiveSelection.kt`), not
`parseReportFilter`: that parser's `singleValue` rule rejects repeated keys and its 90-day default has no meaning here. Repeated
parameters are the first consumer of `repeatedValues` (`infra/paging/QueryParams.kt`; `repeatedLongs` is added beside it).

| Mode | Parameters | Shows | Limit |
|---|---|---|---|
| (a) domain + sprints | `domain` and repeated `sprintId` | the domain's level-0 tasks that were `in_scope_at_close` in at least one of those sprints; tasks with no epic fall into a `(no epic)` row | 1 to 52 sprints |
| (b) epics | repeated `epicId` (issue keys, as in report 15) | every level-0 task under those epics | 1 to 50 epics, at most 500 tasks in all |

Common optional parameters: `connectionId`, `from`, `to` (ISO dates, inclusive, in the configured zone).

`issueId`, the retired handpick key of the removed "Tasks of an epic" mode, is ignored like any unknown parameter (API-LIST-004): it neither narrows
an epic selection nor fails the request (`ReportDeepDiveTest` pins it), and alone it is "no selection". The SPA drops it from a link and says so
(`reports.deepDive.note.retiredTasks`).

### Range

- Mode (a): the envelope of the selected sprints -- the first sprint's `dayOf(start_at)` to the last one's `dayOf(complete_at ?: end_at)`. Data
  outside it (a worklog logged after the sprints, a done day later) is cut from the SERIES but still counted in each task's `totals`.
- Mode (b): the envelope of every mark that would be drawn -- plan days, in-progress days, done days, cost days and the epics'
  planned windows.
- `from`/`to` CLIP that envelope (clipped data stays in `totals`). With BOTH given, `from > to` is `400` and so is a span over 1100 days
  (inclusive of both ends). A lone bound never fails: one outside the envelope pulls the implied other end onto itself, so the range is a
  single empty day (totals intact); one inside keeps the envelope's other end.
- An IMPLIED range (not both bounds given) over 1100 days is not an error: it keeps its LAST 1100 days -- a given `from` or `to` is
  honoured and the other end follows it; with neither, the days end at the envelope's end -- and `note` says so
  (`DEEP_DIVE_RANGE_CLAMPED_NOTE`, starting `RANGE_CLAMPED`; totals stay whole-life). **Approximate EV is whole-life, the range only clips
  it (A40):** each task's IN_PROGRESS working days are listed over its whole life -- the per-day walk `workingDaysBetween` already made --, the
  Approximate EV split runs over that list, and the range cuts the series at assembly, so a clamped or clipped range keeps each day's whole-life
  share (never a re-split over the visible days) and `totals.evMd` stays whole. The envelope reads a span by its first and last day. With no
  marks at all the range is the single day `from ?? to ?? today`.
- Day offsets in the series count from `range.from`. `range.asOfDay` is the day of the SELECTED connection's last DERIVE clock (its newest
  SUCCEEDED `derive_runs.started_at`, `deriveClocks`) -- the same clock open in-progress intervals stop at; a selected connection with
  dimension rows but no successful run reads as not derived. (`meta` is `deriveStamp` over the connections in scope, as everywhere.)

### `400`s

Checked before any data is read, always `400` and never `404`: no mode or mixed modes (for example `sprintId` with `epicId`); `domain` without
`sprintId` (mode a); too many values (more than 52 sprints or 50 epics); a selection that
resolves to more than 500 tasks; a GIVEN range (both `from` and `to`) over 1100 days or with `from` after `to`; an unknown or inactive sprint, epic or connection; an ambiguous epic key or sprint id (several connections in scope); an unknown `domain` (mode a, on a connection that
has derived); epics or sprints that do not share one connection; a malformed date or id; a repeated scalar parameter; control characters; and on the option lists
an unknown sort field or a bad page parameter. Nothing in scope derived yet is NOT a `400`: it is the empty answer below.

### Response

```
DeepDiveReport {
  meta: ReportMeta                      // from deriveStamp: derivedAt, configRevision (the derived revision, as everywhere)
  mode: SPRINTS | EPICS                 // which of the two selections ran
  range: { from, to, asOfDay? }         // ISO dates; asOfDay = the day of the selected connection's DERIVE clock (null when nothing derived)
  nonWorkingDays: [int]                 // day offsets from range.from that are not working days (the client hatches them)
  sprints: [{ ... }]                    // mode (a): the selected sprints, so the client can label the plan windows
  authors: [{ accountId, displayName }] // names for the cost entries (accountDisplayNames)
  epics: [{ key?, summary?, plannedStart?, plannedDue?, budgetMd?, ownCost? }]
                                        // key absent = the "(no epic)" row; the planned window and budget are the CURRENT fact_epic_plan
                                        // baseline (an outline, never summed); ownCost = worklog MD logged on the epic itself
  tasks: [{ key, summary?, epicKey?, planBasisMd?, planSource, noPlanReason?,
            pv[], approxEv[], inProgress[], evState, done?, cost[], totals }]
  quality: { ... }                      // counters, below
  note?: string                         // NOT_DERIVED_NOTE when nothing in scope has derived
}
```

- Every series is SPARSE: `pv[]` and `approxEv[]` are `(day offset, MD)` entries, `inProgress[]` a plain list of day offsets, `cost[]` adds the
  author (an index into `authors`), `done` is the EV marker (`day offset`, `evMd`). Days with nothing are absent. Typical size is about 25k
  entries (about 400 KB before compression) -- the reason aggregation is the client's job. The golden sprint (FLO Sprint 4, 23 tasks of its
  busiest domain: 198 series entries -- 121 PV, 41 Approximate EV, 28 cost and 8 EV markers -- plus 77 in-progress day offsets) answers in
  about 12.5 KB (12,549 bytes).
- Wire shapes (compact on purpose): `pv[]` and `approxEv[]` entries `{d, md}`, `inProgress[]` ints, `evState` `EARNED | OPEN | UNESTIMATED`,
  `cost[]` entry `{d, a, md}` (`a` = index into `authors`, `null` when Jira gave no author), `done` = `{d, evMd}`, every `d` a day offset from
  `range.from`. **Approximate EV (A40)** is the task's `done.evMd` spread by the PV rule (`ROUND(ev × i / n, 2)` running) over its WHOLE-LIFE
  in-progress working days, then clipped to the range: `approxEv` is empty unless `evState` is `EARNED` (done with a positive
  `estimate_at_done_md`), `OPEN` is a task not done yet and `UNESTIMATED` one done with a null or zero estimate; a done task with no in-progress
  working day carries its whole EV on its done day; `inProgress[]` lists the range-clipped working days IN_PROGRESS, also for a task that earns
  nothing (the page's hollow mark), so Σ `approxEv` = `totals.evMd` for an EARNED task whose days all lie in the range. The server
  serializes every property, so an "absent" optional in the shape above is a JSON `null` (`task.summary`, `epicKey`, `planBasisMd`,
  `noPlanReason`, `done`; `epics[].key`/`summary`/`plannedStart`/`plannedDue`/`budgetMd`/`ownCost`; `range.asOfDay`; `note`). `epics[].plannedStart`/
  `plannedDue` are day offsets from `range.from` (they may lie outside the range: an outline). `ownCost` = `{cost[], totalMd}` (the clipped series and
  the whole-life sum) and is non-null in mode EPICS only, and only for an epic with worklogs on itself. `sprints[]` = `{id, name, startDay, endDay}`
  (the plan window as offsets, `null` for a sprint contributing no day). `totals` = `{pvMd, evMd, costMd}`, whole-life, rounded
  from exact sums (Approximate EV has no total of its own: its whole-life sum is `evMd`). `quality` = `{neverInSprint, noEstimate, noWorkingDay,
  epicsWithoutWindow, laterFallback, openInProgress, doneUnestimated, epicOwnCostMd}`. `authors` lists the
  accounts the SENT (range-clipped) cost entries name, by display name; an account the people table does not know is named by its id.
- Rules the contract above leaves to the implementation: tasks come back in natural key order (`FLO-2` before `FLO-10`); `epics[]` lists the selected
  epics in request order, then the epics the tasks sit under by key, then the key-less `(no epic)` row when a task has none (a task whose
  `epic_id` has no `dim_epic` row counts as epic-less); a task's sprints are ordered by their start (else close) day, earliest first; an
  `epicId`/`sprintId` that resolves in several connections in scope is ambiguous even when another selected key is unambiguous; the working
  days are the configured calendar (the rule `dim_date.is_working_day` stores); a selection whose only data lie outside every window and
  has no envelope (no marks) answers the single day `from ?? to ?? today`; `note` is `NOT_DERIVED_NOTE` when nothing derived and the
  `RANGE_CLAMPED` message when an implied range was cut, else `null`.
- `planBasisMd` is the estimate the task's PV spreads (`estimate_at_commitment_md` of the chosen sprint); `planSource` says whether it is the
  earliest sprint's or the first-later-sprint FALLBACK, or that there is none (only a task with no estimate); `noPlanReason` names why a task
  has no PV: `NEVER_IN_SPRINT`, `NO_ESTIMATE`, or `NO_WORKING_DAY` (an estimate whose sprint windows hold no working day -- `planBasisMd` and
  `planSource` stay set, `pv` is empty). Whenever `noPlanReason` is absent, `pv` sums to `planBasisMd`. A sprint with no `start_at` is a
  one-day window on its close, one with neither `complete_at` nor `end_at` contributes no day, and a sprint window longer than 1100 days is
  clamped to its first 1100 days (A29). `totals` carries each layer's full sum over ALL days, in or out of `range`.
- Epic-row figures are not sent: the client sums the tasks of each epic, which makes "an epic row equals the sum of its tasks" true by
  construction; only the epic's own planned window and `ownCost` ride on `epics[]`.
- MD figures are rounded to 2 decimals; PV is spread with cumulative rounding so a task's PV sums to its estimate exactly (A29).
- **`quality`** counts what the page states openly as its limits: tasks never in a sprint (no PV), tasks with no estimate at commitment, tasks whose estimate has no working day
  to sit on (`noWorkingDay`), epics with no planned window, tasks whose PV came from the first-later-sprint fallback, open tasks with at least one
  in-progress working day (`openInProgress`, whole-life: worked, nothing earned yet), tasks done with no or a zero estimate (`doneUnestimated`), and
  the worklog MD logged on an epic itself. The fixed
  limits that are not data-dependent (as-was attribution A21, sub-tasks not rows, status-based Approximate EV with flagged and blocked time not
  subtracted, a multi-day worklog on its start day, freshness = the last `DERIVE`) are stated by the page and by A29 and A40.
- **Nothing derived yet** (no connection in scope has a successful `DERIVE`): `200` with empty `tasks`/`epics`/`authors`, `range.asOfDay` null,
  `meta.derivedAt` null and `note` = `NOT_DERIVED_NOTE` (`reports/SnapshotSupport.kt`) -- never a range's worth of zeros.

### Code and tests

`reports/DeepDiveSelection.kt` (parse and resolve; `parseDeepDive`, `resolveDeepDiveTargets`), `DeepDiveKernels.kt` (the pure plan, in-progress-day, Approximate EV and cost kernels, plus the shared
cumulative-rounding split extracted from `curveOf` in `EpicProgressEvm.kt`: `cumulativeSplit(total, count)` is the running `ROUND(total * i / n, 2)`
list that `curveOf` now uses, which report 15's tests already pin, and `spreadCumulative(total, days)` is the deep dive's per-day differences over it), `DeepDiveReport.kt` (`ReportService.deepDive`) with its
`@Serializable` wire shapes in `DeepDiveWire.kt` (the move that keeps `DeepDiveReport.kt` at its ratchet ceiling in `SourceFileSizeTest`), `DeepDiveOptions.kt` (the two lists), and a `deepDiveRoutes` registrar in `ReportRoutes.kt`. Tests:
`DeepDiveKernelsTest` (pure: the cumulative-rounding split, an overlapping sprint day counted once, weekends and holidays skipped, a Warsaw
daylight-saving day, partial and open in-progress intervals, the Approximate EV split and its edge cases) and `ReportDeepDiveTest` (the shared derived fixture read-only with a plain-user
client, each figure against an INDEPENDENT read of the stored facts: the mode-a task set, plan basis/source/reason and the day-by-day PV, the
in-progress days and the Approximate EV split (an independent per-span day walk; Σ approxEv = EV; the open, unestimated, no-in-progress-day and
reopened cases), the EV marker, cost per day and author, per-author sums with names, the epic outline, wide versus narrow
ranges (in-range plus clipped equals the total); hand-built rows in DISABLED connections for carry-over across
overlapping sprints, the later fallback, a task never in a sprint, no working day, an epic with no window, an epic-logged worklog, an unknown and a
missing author, 501 tasks (epic and sprint selections), a given range over 1100 days, lone `from`/`to` clips, an implied range over 1100 days clamped (an epic
window of years; a task in progress since 2020 whose Approximate EV keeps its whole-life split under the clamped range), the same issue in two connections keeping each one's figures, an unselected parent's sub-task
contributing nothing, ambiguity across connections and never-derived; every `400`).
The kernels' `taskPlan` takes an optional `daysOf` so the report memoizes `planDays` per distinct window set. `ReportDeepDiveOptionsTest` (landed with the option lists): the shared derived fixture graded against independent reads of the stored
facts (sprint counts per domain, the epic list, paging/total, sorts, uppercase/accented `q`), every option-list `400`, and hand-built
rows in a disabled connection for stored diacritics and an epic key present in two connections.

## Report 18 -- Compare people

Status: implemented (A41, `.claude/docs/domain-model.md`; per-measure rows in `.claude/docs/measures.md` "Report 18"). Two people head to head
over a `from`/`to` period, read ACROSS every team they belonged to: the other reports read a person inside one team (`accountId` requires `teamId`),
so two people in different teams, or one who changed team mid-range, could not be compared. Reports-layer only (no migration, no `DERIVE` step):
every row is a figure another report already defines, computed per person from that report's OWN fetch and value function. Any signed-in user
(D12), read-only, no audit; every operation answers `200`/`400`/`401`/`500`.

### Endpoints and parameters

- `GET /api/v1/reports/compare-people?accountId=A&accountId=B&from&to[&domain&activityType&workCategory&domainView&connectionId&tailPercent]` -- the report.
- `GET /api/v1/reports/compare-people/items?...&segmentAccountId&view[&bucketIndex]` -- the drill (below).

`accountId` repeats and takes EXACTLY two distinct values (one, three, or two that collapse to one are `400`; the first is side A, the second side B).
The period is `from`/`to` only (default the trailing 90 days, the shared parser's 1100-day cap); `teamId`, `lastSprints` and `sprintId` are `400`
("compare-people takes a from/to period only"). The slices (`domain` per `domainView`, default TASK, `activityType`, `workCategory`) apply to both
people alike, except to the WIP rows, which read `item_stage` and carry no slice (as Report 9). `reports/ComparePeopleSelection.kt`
(`parseComparePeople`) is the only caller of the shared parser's `teamless = true` mode, which yields a USER-level `ReportFilter` with a null `teamId`
-- an invariant the rest of the package relies on elsewhere (`snapshotScopeOf` and `readWipUserCells` need a team at USER level and refuse such a filter), so
it never leaves the compare-people code.

### Response

```
ComparePeopleReport {
  meta, people[2]{accountId, label},
  rows[]{key, group, unit, direction, target?, tolerance, a{value?, n, hidden}, b{...}, better?: A|B, difference?, margin?, drill?},
  cycleTime{workingDays, elapsedDays},          // Distribution of the UNION of both people, byUser split per person
  workMix{activityType[], workCategory[], domain[]},   // {key?, a{md, items, share?}, b{...}}
  capacity?{days},                               // only for an ADMIN / the manager of every team (A41)
  wipDays{workingDays, firstDay?, lastDay?}, note?
}
```

`rows` is the table, one entry per row of `COMPARE_ROWS` (`reports/ComparePeopleKernels.kt`, the ONE table: key, group, unit, direction, drill view). A cell
is `{value, n, hidden}`: `n` the cell's sample (done tasks, measured items, worklogs, working days...), `hidden` true with a null `value` for a
distribution-shaped figure under `minSampleSize` (as every report), `value` null when it has no denominator. **The verdict** (`compareCells`, pure):
`difference` = B's value minus A's whenever both values exist; `better` (`A`/`B`) is ABSENT for a NEUTRAL row, when either side has no value, when
either side's `n` is under `minSampleSize` (on every distribution, ratio, share, average and MD row -- NOT on the two pure item counts, `deliveredItems`
and `wipAtEndItems`: a count is a fact, so one item against eight still names a side; their `n` is reported all the same), and on a tie -- the gap (for TARGET the gap between the two distances to the target) within the row's `tolerance`,
a per-unit constant (MD and MD per MD 0.005, items 0, items per day and per MD 0.05, days 0.05, ratio 0.01, share 0.005); `margin` (>= 0) is by how
much, present exactly with `better`. There is no caveat text. Directions: higher (delivered MD and items, flow efficiency, PRED(25), worklog
coverage), lower (cycle p50/p90, average WIP and WIP at the end and its age, the blocked shares, late logging, tasks without an estimate), target 1.0
(the reported-time ratio, logged / available MD), neutral (logged and available MD, the foreign share, both estimate-adjustment rows, the work mix).

| Group | Rows (source report) |
|---|---|
| `DELIVERY` | `deliveredMd`, `deliveredItems` (2); with capacity: `deliveredMdPerAvailableMd`, `deliveredItemsPerAvailableMd` |
| `CYCLE` | `cycleWorkingDaysP50/P90`, `cycleElapsedDaysP50/P90` (7), `flowEfficiencyP50` (A18), `reportedTimeRatioP50` (8) |
| `ESTIMATION` | `accuracyAtStartPred25`, `accuracyAtDonePred25` (3), `adjustedShare`, `adjustmentMedianChange` (5) |
| `WIP` | `avgWip` (9), `wipAtEndItems`, `wipAtEndMedianAgeWd` (11), `blockedItemsShare`, `blockedShareOfCycle` (12) |
| `TIME` | `loggedMd` (16), `foreignShare` (A21); with capacity: `availableMd`, `loggedPerAvailableMd` |
| `QUALITY` | `worklogCoverage`, `lateLoggingShare`, `noEstimateShare` (14) |

**WIP is read at query time**, across every team (`agg_daily_wip` has no per-person scope): `planSnapshotDays` lists the days (cut off after the last derived
day, starting no earlier than the latest backfill start, the `note` says why a series is empty or partial), and ONE statement
(`readWipUserTaskDays` / `wipUserTaskDaysSql`, sharing its `held`/`held_days` CTEs with Report 9's USER-level `wipUserOpenBandSql` but with no team join)
returns the (day, task, person) rows held in IN_PROGRESS, WAITING or BLOCKED at a day end. `avgWip` is the mean over the listed WORKING days (a day
with no task counts 0; `n` = those days); `wipAtEndItems`/`wipAtEndMedianAgeWd` read the last listed day (the age is Report 11's `ageOf` from
`started_at` to that day's end; the age's `n` counts the held tasks that have a `started_at`, so it can be smaller than `wipAtEndItems`' `n`, and the
`WIP_AT_END` drill lists every held task, with a null `figure` for one without `started_at`). A hand-built or never-derived connection has no listed day: the cells are null with `n` 0 and the `note` says so.

**Capacity (A41, A39 per person).** The four capacity rows (`availableMd`, `loggedPerAvailableMd`, `deliveredMdPerAvailableMd`,
`deliveredItemsPerAvailableMd`) and the `capacity` block are computed and returned only for an ADMIN or the ACTIVE manager of EVERY team either person
belonged to during the period (`TeamService.isActiveManager`, per request, the route -- `ComparePeopleRoutes.kt` -- decides BEFORE the report read:
`comparePeopleTeamIds` then the manager check, the cost matrix's idiom). Otherwise those keys are absent from the JSON: all or nothing for the pair, never
one side. The route passes the team set it checked into the report (`CapacityGrant.Teams`), and the report drops the capacity if the memberships it reads
in its own transaction name a team outside that set, so a membership added between the check and the read never leaks it. A pair with no team in the
period has no manager, so only an ADMIN sees capacity (zeros); the same holds for a pair that belonged to a since-deleted team (a deleted team has no
active manager, so no non-admin passes). A person's `availableMd` sums the cost matrix's
capacity (`loadCostCapacity`: a stored sprint calendar where one owns the day, one man-day per member on a working day otherwise) of each team they
belonged to, restricted to the days a membership of THAT team covers; a day two teams cover (the transfer day) counts once, at the larger figure.

### Drill to items

`GET /compare-people/items` takes the parent's parameters plus `view` and a REQUIRED `segmentAccountId` (one of the picks: every figure is one person's);
`400` for a missing or unknown view, a missing or foreign segment, and a `bucketIndex` anywhere but the two cycle-time views (or outside the histogram, or
on a hidden distribution). Each view runs the report's own fetch and value function, so the list is exactly what the cell counted: `bar.count` is the cell's
`n` for a distribution or a count; for a share it is the NUMERATOR's items (`ADJUSTED` the started tasks changed after start, `BLOCKED` the blocked tasks,
`FOREIGN`/`LATE` the foreign / over-a-day-late worklogs, `NO_ESTIMATE` the tasks without an estimate), `NO_WORKLOGS` is the coverage's gap and
`WIP_TASK_DAYS` the distinct tasks held on the listed working days (`figure` = their days; the average's `n` is days). `LOGGED`/`FOREIGN`/`LATE` list
worklogs (`itemKind` `WORKLOG`, anchored on `started_at`, `figure` the MD, `bar.sumMd` their sum); `DELIVERED` sums the estimate at done. The cycle-time
views take a `bucketIndex` into the matching `cycleTime` histogram: the UNION's layout narrowed to the person, equal to the `byUser` count. A cell hidden under
`minSampleSize` still lists its items (its `n` is public); only a histogram bar of a hidden distribution is `400`.

### Code and tests

`reports/ComparePeopleKernels.kt` (the row table and the verdict, pure), `ComparePeopleCells.kt` (a person's cell of every row, the work mix),
`ComparePeopleReads.kt` (the done-task, worklog and WIP fetches), `ComparePeopleCapacity.kt` (the cross-team capacity), `ComparePeopleReport.kt` (DTOs and
`ReportService.comparePeople`, `loadCompareData`), `ComparePeopleSelection.kt`, `ComparePeopleRoutes.kt` (both routes), `ComparePeopleDrill.kt`. Tests:
`ComparePeopleKernelsTest` (every direction, ties, hidden sides, targets, tolerances; no DB), `ComparePeopleSelectionTest` and `ReportFilterTest` (the parser),
`WipPlanShapeTest` (the task-days statement joins no team), `ReportComparePeopleTest` (hand-built: two people in two teams, one a mover -- every row
hand-computed with its verdict, the histograms and the mix, the hidden rule, the mover's capacity, the 400 matrix, who sees capacity, D12),
`ReportComparePeopleFixtureTest` (the derived fixture's two busiest people against an INDEPENDENT SQL re-derivation, WIP included) and
`ReportComparePeopleDrillTest` (every view on hand-built rows, every cell and every histogram bar reconciled with its drill on both worlds, the 400 matrix).

## Report 19 -- Task estimates

`GET /api/v1/reports/task-estimates` (+ the drill `GET /api/v1/reports/task-estimates/items`; SPA route `/reports/task-estimates`, the LAST tab of the
Estimation group): the distribution of the estimates themselves, in MD, of the tasks a team finished. It is NOT a re-skin of Report 3: Task accuracy's
`atStart`/`atDone` are distributions of the RATIO `actual ÷ estimate` and exclude every task without worklogs (D14); here the measure is the estimate, so a task
without worklogs is drawn. It reuses Report 3's fetch and row (`fetchAccuracyTasks`, `AccuracyTask`), the shared parser (`multipleAccounts = true`), `meta`,
the sprint-relative envelope, `teamId = 0`, the levels, the long tail and the whole `Distribution`/`byUser`/drill machinery -- with ONE difference, the
fixed-width layout of "`Distribution`" above (`bucketWidth`). Any signed-in user, no audit (D12). Parameters: Report 3's (period, `teamId`/`accountId` repeating
1..8, `domainView` default `TASK`, `domain`, `activityType`, `workCategory`, `connectionId`, `tailPercent`).

```
TaskEstimatesReport {
  meta: ReportMeta
  atDone:  Distribution          // estimate_at_done_md, MD   (the primary view)
  atStart: Distribution          // estimate_at_start_md, MD  (the second view)
  excluded: { population, neverStarted, unestimatedAtStart, unestimatedAtDone }
  groups: [{ teamId?, accountId?, label?, atDone, atStart, excluded }]
}
```

- **Population** = DONE level-0 tasks with `done_at` in the window (Report 3's). Each is in a distribution or counted in exactly one exclusion bucket:
  `atDone` -- `unestimatedAtDone` (no estimate at done; an estimate of 0 is stored `null` = unestimated, `isEstimate()`); `atStart` -- `neverStarted` (no
  `started_at`, checked first, as `ratioAtStartOf` does) then `unestimatedAtStart` (incl. estimated-late, and a backfill, A35). So
  `atDone.n + unestimatedAtDone == population` and `atStart.n + neverStarted + unestimatedAtStart == population`. There is **no `noWorklogs` bucket**.
- **The bars.** Both `Distribution`s are fixed 0.5-MD bars `(x − 0.5, x]` labelled by their upper bound x (width, rounding-up tail cut and the 40-bar widening:
  "`Distribution`" above). The page labels them as values ("0.5", "1", "1.5", ... "≥ 6"; the open tail "≥ X"), the axis "estimate (MD)". Estimates are stored
  with two decimals, so 0.125 MD is stored 0.13.
- **Levels and groups.** UNIT: one group per credit team (`teamId` null = UNASSIGNED); TEAM: one per assignee at done; USER: one per picked account (an empty
  entry when it has no task). Each group carries its own PLAIN distributions (fixed layout too, hidden below `minSampleSize`, `n` always set) and exclusion counts.
- **USER level takes several `accountId`s** (1..8 distinct, like Task accuracy): both distributions read the UNION of the picked people and carry `byUser` (every
  bar split per person, request order, a zero share for a pick with no task); `excluded` stays the union; `groups` lists each pick.
- **Drill.** `GET /api/v1/reports/task-estimates/items?...&view=AT_DONE|AT_START&bucketIndex=<n>[&segmentAccountId=<id>]` ("Drill to items"): the per-task values
  are `estimateAtDoneOf` / `estimateAtStartOf` (the functions the report builds its distributions and exclusion buckets from; `AccuracyDrillView` is shared with
  Report 3), the `figure` is the estimate in MD, `bar` = `{count, sumMd: null, from, to}`, `anchorAt` = `done_at`, `teamId` = the credit team, `accountId` = the
  assignee at done. A never-started task is still in the `AT_DONE` bars.
- **Code and tests.** `reports/TaskEstimatesReport.kt`, `reports/TaskEstimatesDrill.kt`; `Distribution.kt`'s `FixedWidthLayout`. `ReportTaskEstimatesTest` (the
  derived fixture against an INDEPENDENT re-derivation, bars included -- `referenceFixedHistogram` --, the per-person split, the eight-account bound, hand-built rows
  pinning the exclusion buckets, exact bars, p50/mean/min/max and the hidden state), `FixedBucketDistributionTest` (the pure bar rule),
  `ReportDrillAccuracyBlockedTest` / `ReportDrillAccuracyBlockedHandBuiltTest` (every bar reconciled with its drill; the exclusions out of the bars; the 400 matrix),
  `ReportTailPercentTest` (`tailPercent` on this report too).
