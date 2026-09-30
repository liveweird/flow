# Reports API (v0.3.0)

The read-only reports API over the `metrics` star (plan section 7). It opens with the shared
machinery every report inherits -- the filter parser, `Distribution`, the `meta` block,
`GET /api/v1/reports/filters` and the access posture -- then one section per report. Each number's
operational contract (grain, anchor, attribution, estimate snapshot, missing data, pinning test)
is `.claude/docs/measures.md`; what the numbers mean is `.claude/docs/domain-model.md`.

## Index of the sixteen reports (fifteen endpoints)

Every endpoint is `GET`, under `/api/v1/reports/`, any signed-in user, no audit (D12).

| # | Report | Endpoint | Section |
|---|---|---|---|
| 1 | Velocity | `/velocity` | [Report 1](#report-1----velocity) |
| 2 | Throughput | `/throughput` | [Report 2](#report-2----throughput) |
| 3 | Task estimation accuracy | `/task-estimation-accuracy` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 4 | Epic estimation accuracy | `/epic-estimation-accuracy` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 5 | Estimate adjustments | `/estimate-adjustments` | [Reports 3, 4, 5](#reports-3-4-5----estimation-accuracy-and-adjustments) |
| 6 (6.1-6.3) | Sprint consistency (velocity vs throughput, carry-over, added scope) | `/sprint-consistency` | [Report 6](#report-6----sprint-consistency) |
| 7 | Cycle time | `/cycle-time` | [Reports 7, 8](#reports-7-8----cycle-time-and-reported-time-ratio) |
| 8 | Reported time / cycle time | `/reported-time-ratio` | [Reports 7, 8](#reports-7-8----cycle-time-and-reported-time-ratio) |
| 9 | WIP | `/wip` | [Reports 9, 10, 13](#reports-9-10-13----wip-and-the-estimated-backlog) |
| 10, 13 | Estimated backlog depth; item counts and backlog in sprints | `/backlog` | [Reports 9, 10, 13](#reports-9-10-13----wip-and-the-estimated-backlog) |
| 11 | Aging WIP | `/aging-wip` | [Reports 11, 12](#reports-11-12----aging-wip-and-blocked-time) |
| 12 | Blocked time | `/blocked-time` | [Reports 11, 12](#reports-11-12----aging-wip-and-blocked-time) |
| 14 | Data quality | `/data-quality` | [Report 14](#report-14----data-quality) |
| 15 | Epic progress (EVM) | `/epic-progress` | [Report 15](#report-15----epic-progress-evm) |
| 16 | Cost matrix and foreign work | `/cost-matrix` | [Report 16](#report-16----cost-matrix-and-foreign-work) |

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
  team's roster it is being read. `teamId = 0` is the UNASSIGNED sentinel bucket, a real value, not
  "absent" -- never rejected here (a real check that a POSITIVE `teamId` names an actual team is a
  report service's own job, downstream).
- **Group order.** The org drill (`groups`) of velocity, throughput, sprint consistency and the DONE-item reports is built by
  `orgGroups` (`reports/ReportSupport.kt`), ordered by label (null last), then team id, then account id -- so two groups
  with an equal label never swap between requests.
- **`domainView=TASK|EPIC`** (D3) -- the caller (each report's own route) supplies its own default
  (plan section 7: `EPIC` for PV/EV/AC-shaped measures -- backlog, worklog cost, epic accuracy; `TASK`
  elsewhere); an explicit `domainView` param overrides it.
- **Optional filters** -- `domain`, `activityType`, `workCategory` (the literal `UNCATEGORIZED` is a
  legal value, not just a real category name), `connectionId` -- all structural pass-through here,
  validated against the connection(s) in scope by the report's own service.
- **No `breakdown` parameter.** A `breakdown=NONE|DOMAIN|ACTIVITY_TYPE|WORK_CATEGORY` slice of `groups` was parsed by
  every report but changed none of them, so it was removed (checkup C12); a request still carrying it is ignored like
  any unknown parameter name (`.claude/docs/list-endpoints.md`: unknown names stay ignored by deliberate leniency).
  Re-add it WITH its first consuming report, not before.
- Every scalar param goes through the shared strict readers (`infra/paging/QueryParams.kt`, see
  `.claude/docs/list-endpoints.md`): a repeated key is `400` (the `singleValue` rule), an unknown
  enum value is `400` (listing the allowed set).

## `Distribution` (`reports/Distribution.kt`)

The shared shape every per-item measure (cycle time, estimation accuracy, blocked time, ...)
returns: `{n, hidden, mean, min, max, p50, p90, p95, histogram[{from, to, count}]}`. `hidden = n <
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
except the LAST bucket, closed on both ends, so the maximum value always lands somewhere). A
later report whose dataset is too large to pull wholesale into the JVM can run the SQL aggregate
directly and feed its own numbers into this SAME `Distribution` shape -- the wire contract never
changes, only which side does the arithmetic.

## `meta` (`reports/ReportMeta.kt`)

Every report response carries a `meta` block beside its own body:
`{derivedAt, configRevision, from, to, level, domainView, resolvedSprints[{teamId, sprintIds}],
minSampleSize}`. `derivedAt` is the latest SUCCEEDED `metrics.derive_runs.finished_at` across the
connection(s) the report actually read (`null` before any connection has ever completed a DERIVE);
`configRevision` is the LIVE `metrics.settings.config_revision` at read time, NOT the revision the
data was derived under (every DERIVE stamps its rows with the revision it ran under, invariant 12 --
`.claude/docs/domain-model.md` -- but the response does not carry that one; after a configuration
change and before its DERIVE finishes, `configRevision` is ahead of the data. `derivedAt` is the
honest freshness signal). `from`/`to` are populated only for a
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
  connections: [{ id, name }]
  derivedAt: epoch millis | null
  configRevision: integer
  minSampleSize: integer
  timeZone: string   // IANA, metrics.settings.time_zone
}
```

- **`teams`** -- every ACTIVE team (`teams/TeamService.Teams`, cross-feature read), with the
  sprints its board has EVER produced (`metrics.dim_sprint` -- empty until at least one DERIVE has
  run for a connection whose board maps to that team) and its CURRENT (as of the request) D1 Jira
  roster, paired with a display name (`norm.people`, cross-feature read -- the FIRST place
  `/jira-memberships`' bare `accountId` gets a name attached, the same role `/jira-users` already
  plays).
- **`domains`/`activityTypes`/`workCategories`** -- the values a real DERIVE run has ACTUALLY
  produced (`metrics.dim_domain`/`dim_task`/`dim_epic`), never every value a per-connection
  config COULD map to: an admin's configured-but-unused mapping would otherwise clutter a filter
  dropdown with a value no report can ever return a row for.
- **`connections`** -- every ACTIVE connection (not additionally filtered to `enabled` -- a
  connection an admin has paused from syncing still owns whatever it already derived, and its
  history stays a legitimate filter choice), id and name ONLY -- never `settings`/the encrypted
  API token (`.claude/docs/security.md`).
- **`timeZone`** -- the configured IANA zone (`metrics.settings.time_zone`, default `Europe/Warsaw`) that
  `from`/`to` are read in. The SPA renders every report date and computes its period presets, "today" and
  the date-picker maximum in this zone (never UTC, never the browser's), so a preset chosen at 00:30
  local on the 1st means the same day the server resolves.
- **`derivedAt`/`configRevision`/`minSampleSize`** -- the SAME figures every report's own `meta`
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

## Report 1 -- Velocity

`GET /api/v1/reports/velocity` (v0.3.0 M4 commit 10b, `.claude/docs/measures.md` "Report 1 --
Velocity"): `initial` = committed scope, `final` = final scope (A17) -- both read straight off
`metrics.fact_sprint` for the sprint-level rows, `metrics.fact_sprint_scope` for the per-user
breakdown. Takes the shared `from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId` and
`connectionId` parameters (`domainView` is accepted and echoed in `meta` but never changes the
result -- velocity carries no domain slice, `measures.md`'s Report 1 rows: domain "--").

```
VelocityReport {
  meta: ReportMeta
  sprints: [{
    sprintId, name, teamId, completedAt,
    initialMd, initialItems, finalMd, finalItems,
    snapshot: { initialMd, initialItems, finalMd, finalItems } | null,
    drift: boolean
  }]
  groups: [{ teamId?, accountId?, label?, initialMd, initialItems, finalMd, finalItems }]
}
```

- **`sprints`** -- one row per (team, sprint) in scope. `snapshot` is the frozen
  `fact_sprint_snapshot` figures (`null` until the sprint was first seen closed and team-mapped by
  a DERIVE run -- D13); `drift` compares the two LIVE-vs-FROZEN figures with a 0.005 MD tolerance
  (item counts compared exactly) -- `true` whenever any of the four differs, always `false` when no
  snapshot exists yet.
- **Levels, from the parsed filter** (`reports/ReportFilter.kt`'s own `ReportLevel`):
  - **UNIT** (no `teamId`) -- every team's own CLOSED sprints in the period; `sprints` carries the
    whole-team figures (straight off `fact_sprint`); `groups` sums FINAL MD/items per team (`Σ
    final`, from the SAME `sprints` rows this response already carries -- no separate query).
  - **TEAM** (`teamId`) -- narrows `sprints` to that one team's own sprints; `groups` becomes a
    per-user split keyed by `fact_sprint_scope.assignee_at_commitment` -- committed
    (`committed AND in_scope_at_close`) sums `estimate_at_commitment_md`, final
    (`in_scope_at_close`) sums `estimate_at_close_md`, the SAME removed-row rule
    `DeriveKernels.sprintTotals` applies to the team total itself (`.claude/docs/metrics.md`
    "Sprint scope, facts and snapshots"), so `Σ groups == the team total` (both buckets, both
    MD and items; up to 0.01 MD per sprint of rounding when estimates have more than two decimals --
    see "Rounding" under Report 6). A `null` `accountId`/`label` group is the unassigned-at-commitment
    bucket, listed last -- never a stored sentinel, the `credit_team_id` convention.
  - **USER** (`teamId` AND `accountId`) -- `sprints` itself narrows to that ONE account's own
    contribution per sprint (the SAME committed/final predicates as the TEAM-level groups, applied
    per sprint instead of summed); `groups` is always empty -- there is nothing further to drill.
    `snapshot`/`drift` are always `null`/`false` at this level: a per-user FROZEN figure would need
    parsing `fact_sprint_snapshot.scope`'s JSONB (`.claude/docs/metrics.md`'s own documented "per-user
    velocity from the snapshot needs no child table" -- a reader this commit does not add), a
    deliberate, documented scope narrowing rather than a silent guess.
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
  `fact_sprint` ones, `snapshot` is `null` and `drift` `false`. Pinned by `ReportVelocityTest`.
- **Connection scope.** `connectionId` narrows to one ACTIVE connection (`400` if unknown/inactive);
  absent, defaults to every ACTIVE (not soft-deleted) connection, the same set `/reports/filters`
  lists — a DISABLED connection only has syncing paused, and its derived data still counts.
- **`teamId`/`sprintId` existence.** A positive, non-zero `teamId` must name an ACTIVE team (`400`
  otherwise) -- the client-supplied-FK idiom, `reports/ReportFilter.kt`'s own doc comment: structural
  parsing stays in the parser, existence checks live in the service.

`reports/VelocityReport.kt` holds the wire DTOs, the drift comparison and every query/aggregation
function as an extension on `ReportService` (`reports/ReportService.kt`'s `database`/`metricsConfig`
are `internal`, not `private`, precisely so this sibling file can extend it without a second
constructor) -- the file the brief's own "past ~120 lines -> a new file" idiom names. Tests:
`ReportVelocityTest` (`DerivedStubFixture`-based: UNIT-level `finalMd` against `fact_sprint`, the
golden FLO sprint's `sprintId` period against `expected.json`'s `committedMd`/`finalMd`, TEAM-level
`Σ groups == team total`, `400` for `from > to` and an unknown `sprintId`; every request runs
through a non-admin `seededClient`, proving D12's `200` at the same time).

## Report 2 -- Throughput

`GET /api/v1/reports/throughput` (v0.3.0 M4 commit 10c, `.claude/docs/measures.md` "Report 2 --
Throughput", with the item counts of report 13): what was DELIVERED, in MD and items. Takes the
shared parameters plus `bucket=WEEK|MONTH` (default `WEEK`). Two views that **differ by design** --
a task finished after its sprint closed is in the period view but in no sprint's delivered scope,
and the sprint view prices at the sprint's close while the period view prices at `done_at`:

```
ThroughputReport {
  meta: ReportMeta
  bySprint: [{ sprintId, name, teamId, completedAt, deliveredMd, deliveredItems,
               snapshot: { deliveredMd, deliveredItems } | null, drift: boolean }]
  byBucket: [{ bucketStart, deliveredMd, deliveredItems }]
  groups:   [{ teamId?, accountId?, label?, deliveredMd, deliveredItems }]
}
```

- **`bySprint` (the SPRINT view)** -- straight off `fact_sprint.delivered_md/_items` (= Σ
  `fact_sprint_scope.done_in_sprint`), one row per (team, closed sprint) by `complete_at`, whole
  sprint or nothing. The period/`lastSprints`/`sprintId` resolution, connection scope and the `400`
  existence checks are velocity's own -- both reports share `reports/ReportSupport.kt`. `snapshot` is
  the frozen `fact_sprint_snapshot` delivered figures, `drift` the same 0.005 MD / exact-items
  comparison velocity uses. Sorted by `completedAt`, then `sprintId`. Carries no domain/activity/
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
- **Window rule.** A `from`/`to` period is exactly `[fromMs, toMs)` (exclusive end, as velocity). A
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
  unassigned); USER (`teamId` + `accountId`): the period view narrows to that account, `groups` is
  empty and `bySprint` narrows to that account's deliveries by `assignee_at_commitment`
  (`done_in_sprint` rows at `estimate_at_done_md`; `snapshot`/`drift` are `null`/`false`, velocity's
  same narrowing). **Σ `groups` == Σ `byBucket`** (MD and items) at UNIT and TEAM level.
- **Two separate measures.** Never expect Σ `bySprint` == Σ `byBucket` (see the two-views note above).

Code: `reports/ThroughputReport.kt` (DTOs, pure bucket math, the queries) over `reports/
ReportSupport.kt` (the connection scope / team-existence / sprint-row / snapshot readers extracted
from velocity). Tests: `ReportThroughputTest` -- `DerivedStubFixture`-based: `bySprint` against `fact_sprint` and
`golden.sprint.deliveredMd/Items`; `byBucket` against an independent read of `fact_task_delivery` over
two windows; the sprint-envelope window for a `sprintId` and for `lastSprints=2`; an open/future
sprint by `sprintId`; Σ groups at UNIT/TEAM (and empty groups at USER); USER-level `bySprint` summed
over the golden sprint's assignees (plus the unassigned remainder) equalling the TEAM figure;
`teamId=0`; the `activityType` and TASK-view `domain` slices; WEEK vs MONTH totals; `400` for a bad
`bucket` and `from > to`. The EPIC domain view and the `workCategory` filters are pinned on
hand-built `fact_task_delivery` rows in a fresh disabled connection (the stub fixture has no work
categories and no cross-domain epics), with exactly hand-computed counts and MD. `ThroughputBucketTest`
pins the pure bucket math across Europe/Warsaw's DST change.

## Report 6 -- Sprint consistency

`GET /api/v1/reports/sprint-consistency` (v0.3.0 M4 commit 10d, reports 6.1-6.3 with the item counts of
13, `.claude/docs/measures.md` "Report 6"): how a sprint's scope moved and where it ended up --
committed, added, removed, final, delivered, carried over, dropped, MD beside items -- straight off
`metrics.fact_sprint` (+ `fact_sprint_snapshot`). Takes velocity's shared parameters (`from`/`to`,
`lastSprints`, `sprintId`, `teamId`/`accountId`, `connectionId`; `domainView` echoed, never changes
the result). Period resolution, connection scope, the `400` existence checks, `teamId = 0` (always
empty) and the open-sprint handling (`sprintId` only, `completedAt` null, live figures, no snapshot) are
velocity's own -- the shared `reports/ReportSupport.kt`, whose `SprintRow`/`SnapshotRow` now carry all
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
    Each group's figures come from `DeriveKernels.sprintTotals` over that user's scope rows -- the SAME
    bucket predicates that produced the team's `fact_sprint` row (committed = committed ∧ in scope at
    close; added = `added_at` set; removed = `removed_at` set; final = in scope at close; delivered =
    `done_in_sprint`; carried over / dropped by their flags; each on the estimate column
    `sprintTotals` uses for it), never a re-implementation, so Σ groups == the team figures for every
    bucket (MD up to the rounding note below), and items exactly. A removed row is attributed to the user
    assigned at commitment, an added one to the user assigned at entry; the unassigned (null) group
    sorts last, as in velocity. The scope rows fetched are exactly the in-scope (connection, sprint)
    pairs -- never the cross product of the connection and sprint id lists, since two connections to
    one Jira site share sprint ids (velocity's per-user reader does the same).
  - **USER** (`teamId` AND `accountId`) -- `sprints` narrow to that account's rows per sprint (the same
    kernel over its rows; a sprint with none shows zeros); `snapshot` is `null` and `drift` `false`
    (velocity's documented narrowing: a per-user frozen figure would mean parsing the snapshot's
    JSONB scope), `groups` is empty. The unassigned bucket has no USER-level query -- it is the
    remainder Σ named users + unassigned == team.
- **Rounding.** `fact_sprint` stores `round2(Σ unrounded)` per sprint while the per-user groups are
  computed over `fact_sprint_scope` rows whose estimates are stored to two decimals, so Σ groups == team up
  to 0.01 MD per sprint of rounding when estimates have more than two decimals (both here and in
  velocity); items always match exactly. `BACKLOG.md` tracks rounding per item before summing in
  `sprintTotals`.
- **Not in this report.** Capacity and load (also under measures.md's Report 6 heading) are not part of
  this endpoint's shape; they stay on `dim_sprint`/`fact_sprint` for a later reader.

Code: `reports/SprintConsistencyReport.kt` (DTOs, drift, the query/aggregation functions as an extension
on `ReportService`). Tests: `ReportSprintConsistencyTest` -- the golden FLO sprint's fourteen figures
against `expected.json` exactly (plus its snapshot, no drift); the A17 partition over every closed sprint
(`lastSprints=52`, which covers every closed sprint only while a team has at most 52) and UNIT Σ groups; TEAM Σ groups == team for all fourteen figures; USER-level figures
per named account equal to that account's TEAM group, and Σ users + the unassigned remainder == team; a
hand-built case (the stub fixture has no removed scope and no estimated added scope: a fresh DISABLED
connection with hand-inserted `dim_sprint`/`fact_sprint`/`fact_sprint_scope` rows) asserting the per-user
removed/added figures exactly and that a second connection's stale scope rows never leak in; an
open sprint by `sprintId` (`completedAt` null, no snapshot); `teamId=0`; `400` for `from > to` and an
unknown `sprintId`. Every request runs through a non-admin `seededClient` (D12).

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
}
```

- **Population** = DONE level-0 tasks with `done_at` in the window. Each is in a distribution or in exactly ONE
  exclusion bucket, checked in this order: `noWorklogs` (D14 -- `has_worklogs = false`, **or `actual_md` of 0.00**
  -- a minute or two logged is not a ratio of exactly 0; both views, mirroring the epics' `noActual`), then
  `neverStarted` (no `started_at`, so no estimate at start -- `atStart` only), then `unestimatedAtStart` (no
  estimate at start, estimated-late included) / `unestimatedAtDone` (no estimate at done). So `atStart.n +
  noWorklogs + neverStarted + unestimatedAtStart == population` and `atDone.n + noWorklogs + unestimatedAtDone
  == population`. (measures.md says `atDone` has "the same buckets"; `neverStarted` exists because a task with
  no start has no @start estimate, which is not a reason to drop it from the @done view -- it is not applied
  there, and the measures.md cell says so.) An estimate of 0 is stored as `null` = unestimated (domain-model.md).
- **Levels.** UNIT: `groups` one per credit team (`teamId` null = UNASSIGNED); TEAM: one per assignee at done
  (`accountId` null = unassigned); USER: none. Each group carries its own two distributions -- **hidden below
  `minSampleSize`, `n` always set**, so `Σ group n == n` -- and its own `excluded`.
- Reading: ratio `1.0` = actual cost equals the estimate, above = it cost more.

### Report 4 -- `GET /api/v1/reports/epic-estimation-accuracy`

```
EpicEstimationAccuracyReport {
  meta, atStart: Distribution, atDone: Distribution,        // actual_md / OWN estimate at start / at done
  excluded: { population, noActual, neverStarted, unestimatedAtStart, unestimatedAtDone },
  epics: [{ issueKey, summary?, ownerTeamId?, doneAt, ownEstimateAtStartMd?, ownEstimateAtDoneMd?,
            childSumMd, actualMd, ratio?, ratioAtDone? }],   // <= 200 rows
  epicsTruncated: boolean,
  groups: [{ teamId?, label?, atStart, atDone, excluded }]   // UNIT only: one per owner team
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

### Report 5 -- `GET /api/v1/reports/estimate-adjustments`

```
EstimateAdjustmentsReport { meta, tasks: AdjustmentFigures, epics: AdjustmentFigures,
                            groups: [{ teamId?, accountId?, label?, tasks, epics? }] }
AdjustmentFigures { started, changedAfterStart, share?, estimatedLate,
                    changeDistribution: Distribution,
                    changeExcluded: { population, estimatedLate, unestimated } }
```

- **Two populations, two anchors.** `started` = items with `started_at` in the window; `changedAfterStart` =
  those with `estimate_changes_after_start > 0`; `share` = their **fraction 0..1** (null, counts only, **when fewer
  than `minSampleSize` items started** -- hidden like a distribution); `estimatedLate` = those started items that
  gained an estimate only after start. `changeDistribution` covers items with `done_at` in the window: the
  **fractional change start -> done**, `(estimate at done - estimate at start) / estimate at start` (`0.25` =
  +25 %, negative = shrank; a fraction, like the accuracy ratios, not percent points). `changeExcluded` (over
  that same done population) puts each remaining item in ONE bucket: `estimatedLate` (counted separately,
  never a `+infinity` change) else `unestimated` (either snapshot missing, a never-started item included), so
  `changeDistribution.n + estimatedLate + unestimated == population`.
- **An estimated-late item is also "changed after start"**: its late estimate is itself a change point after
  `started_at` (`DeriveKernels.estimateSnapshots`), and the row's predicate is literally `changes > 0`.
  `ReportEstimateAdjustmentsTest` pins it against the stub generator: `estimatedLate` equals
  `expected.json`'s `taskEstimatedLateCount` and `changedAfterStart` equals `taskEstimateChangedAfterStartCount +
  taskEstimatedLateCount`.
- **Epics** use the own-estimate columns; `estimatedLate` is derived from them (`started_at` set, no own
  estimate at start, one now -- the task kernel's rule, since the epic fact stores no such column).
- **Attribution (A25): `credit` once done, `current` while open.** A DONE task is attributed to
  `credit_team_id` / `assignee_account_id_at_done`, a still-OPEN task to `current_team_id` /
  `current_assignee_account_id` -- branching on `done_at`, never `COALESCE(credit, current)` (a DONE task with no
  credit team is legitimately UNASSIGNED). So a started-but-open task never falls into UNASSIGNED merely for not
  being done, and a `teamId=X` read (`taskFactSlice(..., openAttribution = true)`) sees that team's open tasks too.
  Throughput and the accuracy reports read only DONE tasks, so they keep the plain credit/assignee-at-done
  predicates.
- **Levels.** UNIT: `groups` one per team, tasks and epics side by side (a null-team group is UNASSIGNED tasks /
  UNOWNED epics together); TEAM: one per assignee (at done / now, A25), tasks only (`epics` null); USER: `tasks` narrowed to the
  account, `epics` all zero, no groups.

Code: `reports/TaskAccuracyReport.kt`, `reports/EpicAccuracyReport.kt`, `reports/EstimateAdjustmentsReport.kt`
(DTOs, the pure partition/ratio functions, the queries as extensions on `ReportService`). The distributions are
built in Kotlin by `buildDistribution` over the fetched ratios. Tests -- one class per endpoint on
`DerivedStubFixture`: `ReportTaskEstimationAccuracyTest`, `ReportEpicEstimationAccuracyTest`,
`ReportEstimateAdjustmentsTest` (with `ReportEstimationTestSupport.kt`: an independent percentile/mean check that
never calls `buildDistribution`, raw fact readers, hand-built row builders). Each grades every distribution
(n, mean, min, max, p50/p90/p95, histogram total) and every exclusion count against an independent computation over
the raw fact rows -- whole population, per group (`Σ group n == n`), per user, `teamId=0`, and the slices -- and
pins the buckets, the ratios, the hidden state (`minSampleSize` pinned with `withMinSampleSize`), the 200-row cap and
the estimated-late separation on hand-built rows in a fresh DISABLED connection with hand-computed answers.

## Reports 7, 8 -- Cycle time and reported time ratio

Two endpoints (v0.3.0 M4 commit 12b, `.claude/docs/measures.md` "Reports 7, 8") over `fact_task_delivery`, on the
same preamble and slice as the estimation reports (`resolveReportScope`, `taskFactSlice`, and the shared
`orgGroups` drill in `ReportSupport.kt`; the fetch `fetchDoneCycleTasks` is shared by both). Population = level-0
tasks (D2) with `done_at` in the window; team = the D5 **credit** team (`teamId=0` = UNASSIGNED), user = assignee
at done, domain per `domainView` (default `TASK`, D3), plus `activityType`/`workCategory`; a `lastSprints`/`sprintId`
period reads the resolved sprints' envelope, as throughput's period view. **Epics are not in these reports**
(measures.md's Report 7 row mentions "epics by own status", but the model's Report 7 source is `fact_task_delivery`
alone -- an epic cycle time is not shown).

### Report 7 -- `GET /api/v1/reports/cycle-time`

```
CycleTimeReport {
  meta, elapsedDays: Distribution, workingDays: Distribution,     // cycle_ms in days / cycle_working_days
  excluded: { population, neverStarted },
  trend: [{ bucketStart, p50?, p90?, n }],                        // bucket=WEEK|MONTH, default WEEK
  groups: [{ teamId?, accountId?, label?, elapsedDays, workingDays, excluded }]
}
```

- **Cycle** = `done_at - started_at` (first entry into IN_PROGRESS to the start of the trailing DONE run, so a
  reopened task counts its whole span). `elapsedDays` is wall-clock (`cycle_ms / 86_400_000`), `workingDays` the
  configured calendar's fractional working days.
- **One exclusion**: `neverStarted` (`cycle_ms` null, i.e. no `started_at` -- created straight into DONE), so
  `workingDays.n == elapsedDays.n == population - neverStarted`. **A cycle of zero working days is a real value and
  stays in** (`zeroCycle` is a reported-time-ratio bucket only, where it would divide by zero).
- **`trend`**: one entry per week (Monday start) or month (the 1st) across the WHOLE window, zero-filled -- the
  throughput report's own `bucketStart`/`bucketStarts` math, in the configured zone -- placing each measurable task
  by `done_at`, on **working days**. `p50`/`p90` come from the same `buildDistribution` and are `null` when the
  bucket's `n` is below `minSampleSize` (`n` always set; an empty bucket has `n` 0), so hiding is per bucket.
- **Levels**: UNIT `groups` per credit team, TEAM per assignee at done, USER none; each group has its own two
  distributions (hidden below the minimum, `n` always set, so `Σ group n == n`) and `excluded`; groups are ordered by
  label (null last), then team id, then account id.

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
  population`. Levels and groups as report 7.
- **The ratio's mean, p95 and histogram can be dominated by very short cycles** -- dividing by a cycle of a fraction
  of a working day produces a very large ratio. That is a real outlier and is never dropped (only a cycle of exactly
  zero working days is excluded, as `zeroCycle`); read the p50 first.
- **Flow efficiency (A18) is shown beside it**, over the same DONE level-0 population: `flowEfficiency` is `active_ms /
  cycle_ms` (active = IN_PROGRESS-stage time minus blocked time while in progress, `.claude/docs/measures.md`'s "Flow
  efficiency (A18)" row) and `flowEfficiencyExcluded` puts each unmeasurable task in exactly ONE bucket, in this order:
  `neverStarted` (no cycle), then `zeroCycle` (`cycle_ms = 0`). Worklogs play no part, so a task with none is still
  measured -- unlike the ratio there is no `noWorklogs` bucket -- and `flowEfficiency.n + neverStarted + zeroCycle ==
  population`. Note the two `zeroCycle`s differ: the ratio's is zero WORKING days, this one zero ELAPSED time. Groups
  carry both measures; `Σ group n == n` for each.

Code: `reports/CycleTimeReport.kt`, `reports/ReportedTimeRatioReport.kt`. Tests -- one class per endpoint on
`DerivedStubFixture`, with the independent oracle of `ReportEstimationTestSupport.kt`: `ReportCycleTimeTest`
(distributions, per-group at every level, `teamId=0`, slices, the sprint envelope, and **every trend bucket's p50/p90/n
against an independent bucketing** -- WEEK and MONTH, zero-filled, hidden per bucket -- plus hand-built rows with
exact hand-computed numbers incl. a zero-working-day cycle) and `ReportReportedTimeRatioTest` (the partition, groups,
slices, hand-built bucket precedence and hidden state).

## Reports 9, 10, 13 -- WIP and the estimated backlog

Two endpoints (v0.3.0 M5 commit 15, `.claude/docs/measures.md` "Report 9", "Reports 10, 13") that read the DAILY
AGGREGATES the DERIVE run writes (`.claude/docs/metrics.md` "Daily WIP aggregate", "Daily flow aggregate") instead of the
fact tables, so they share their own preamble (`resolveReportScope`, then `snapshotScopeOf` and `planSnapshotDays` in
`reports/SnapshotSupport.kt`). Both take the shared `from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId`, `domain`
and `connectionId` parameters. `domainView` is accepted and changes nothing, but **`meta.domainView` is
always `TASK`**: both reports read the task's own domain (D3 flow view; the epic side of WIP is the epic's own space), so an
explicit `domainView=EPIC` is not a `400` but is not echoed back as if it had been honoured.

**Scopes -- what the aggregate can answer.** `agg_daily_wip`/`agg_daily_flow` are stored per TEAM scope and per DOMAIN
scope only: never per user, never team x domain. So:

- **UNIT** (no `teamId`) sums every TEAM scope -- including the `UNASSIGNED` (tasks) and `UNOWNED` (epics, backlog)
  scopes -- or, with `domain`, reads that DOMAIN scope (a task's as-was domain, an epic's own space).
- **TEAM** (`teamId`) reads that team's TEAM scope. For WIP a task is in the team of D5 as of each day and an epic in
  its domain's owner team (A19); the backlog's TEAM scope is the owner team (A19).
- **`teamId=0`** is `UNASSIGNED` on the task side and `UNOWNED` on the epic side of WIP (so an explicit `itemKind=BOTH` adds the two),
  and `UNOWNED` for the backlog.
- **USER** (`teamId` and `accountId`) has no storage: the answer is an EMPTY series with a `note`, never a team-wide
  number posing as a user's. `400`, checked first: `domain` together with `teamId` (no team x domain split) and
  `activityType`/`workCategory` (not stored per day). Unknown `teamId`/`connectionId`/`sprintId` are `400` as elsewhere.

**Series days.** One point per calendar day of the period in the configured zone (a `from`/`to` period as parsed; a
`lastSprints`/`sprintId` period the resolved sprints' envelope, `periodWindow`, as throughput's period view). Days the
aggregate has no row for are ZERO (the rows are sparse) -- but only days the aggregate actually covers:

- **The cut-off.** The series stops after the last derived day, which with several connections in scope is the OLDEST, over
  the connections that have derived, of each one's newest SUCCEEDED `derive_runs.started_at` day in the configured zone (SQL
  `max()` per connection) -- so a lagging connection's missing days are never read as zeros. A period reaching past it lists
  nothing for those days; a period entirely past it is an empty series. A connection with no successful run is ignored for
  the cut-off (it has no rows either) but its id is named in `note`.
- **Not derived yet.** When NO connection in scope has a successful DERIVE run the answer is an empty series/trend with
  `note` "Not derived yet: ..." -- never a period's worth of zeros.
- **`note`** carries whatever makes an empty or partial answer explicable: USER level (below), not derived yet, no sprint
  resolved by a sprint-relative period (`teamId=0` gets its own wording: UNASSIGNED resolves no sprint), connections left out
  of the cut-off. Several apply -> joined by ". "; `null` when there is nothing to say. `meta.derivedAt` still tells a client
  how fresh the numbers are.

### Report 9 -- `GET /api/v1/reports/wip`

```
WipReport {
  meta, by: STATUS|STAGE|COLUMN, itemKind: TASK|EPIC|BOTH,
  keys:   [{ key, label }],                                   // the legend, in display order
  series: [{ day, isWorkingDay, counts: { <key>: n } }],      // counts carries EVERY key, zero-filled
  note?:  string                                              // why the series is empty or partial (see "note" above)
}
```

- `itemKind` (default `TASK`) counts level-0 tasks; `EPIC` counts epics and `BOTH` adds the two -- tasks and epics are different
  grains (D2), so mixing them is an explicit choice; `by` (default `STAGE`)
  keys `counts`. `STAGE`: the four stages `NOT_STARTED`, `IN_PROGRESS`, `DONE`, `UNMAPPED` -- always all four, `UNMAPPED` (a
  status with no stage mapping) is its own key. `STATUS`: the Jira status id, `label` its `norm.statuses` name; only
  statuses seen in the period, ordered by stage then name. `COLUMN`: the mapped board's columns in board order
  (`norm.board_columns`, read at query time so a board edit needs no re-derive), plus `(no column)` when a status no column
  holds has items in the period.
- **The counts are end-of-day snapshots**: the number of items whose `item_stage` interval covers the END of that day, all
  stages (the `DONE` count only grows -- a chart that wants "work in progress" picks the `IN_PROGRESS` key). Invariant 9's
  other half lives in DERIVE's tests: the backlog never exceeds the NOT_STARTED WIP of its domain/day.
- **`by=COLUMN` needs one team's board**: `400` at UNIT level, with `domain`, for `teamId=0` and for a team with no board
  mapped in the connections in scope (a team has at most one board, D10). It reads only the board's connection's rows. A
  USER-level request still validates the team's board, then answers empty.
- `isWorkingDay` is the configured calendar's (weekends and holidays are not), so a chart can hide weekends. There is no
  `workingDaysOnly` parameter -- the flag is the contract.

### Reports 10, 13 -- `GET /api/v1/reports/backlog`

```
BacklogReport {
  meta,
  current: { asOfDay?, items, md, meanDeliveredMd?, windowSprints, sprintsUsed, backlogInSprints? },
  trend:   [{ day, items, md }],                              // end-of-day, zero-filled
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

Code: `reports/WipReport.kt`, `reports/BacklogReport.kt`, over `reports/SnapshotSupport.kt` (the scope predicates, the
working calendar builder, the derived coverage, the day plan and its note). Tests -- `ReportWipTest` and `ReportBacklogTest` on
`DerivedStubFixture`, plus `ReportSnapshotTestSupport.kt` (independent readers of `agg_daily_wip`/`agg_daily_flow`/
`fact_sprint`, hand-row inserters): every day of every series against a sum taken straight off the aggregate rows (per
stage, status and board column; `BOTH` = `TASK` + `EPIC`; the DOMAIN scopes; a team; `teamId=0`; the sprint-id envelope; the
cut-off at the last derived day, incl. two connections with differing derive days and one that never derived; "not derived yet";
the `teamId=0` sprint-relative note; `meta.domainView` forced to TASK; weekends), the mean against an independent computation over `fact_sprint` at several
period ends (window full, fewer than N, one sprint), and hand-built rows in fresh DISABLED connections with hand-computed
answers (the zero-fill, keys and their order, `(no column)`, the UNASSIGNED/UNOWNED split incl. an epic never being
UNASSIGNED, scope isolation, the window, fewer than N, a mean of zero, no sprint at all, the UNIT sum). `400`s: a bad
`by`/`itemKind`, `domain` with `teamId`, `activityType`, `workCategory`, an unknown team/connection/sprint, `by=COLUMN` at
UNIT level / for `teamId=0` / for a team with no board.

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
            ageWorkingDays, blocked, band? }],                                  // oldest first, <= 500
  itemsTruncated
}
```

- **"As of now" -- the period is ignored.** The report has no period: the age is taken at the request's clock
  (`nowMillis()` in the route, injected into the service so tests pin it) with the configured calendar
  (`WorkingCalendar.workingDaysBetween(started_at, now)`, never negative). The shared parser still accepts `from`/`to`/
  `lastSprints`/`sprintId` (a sprint-relative one only resolves sprints, or 400s for an unknown id).
- **Items** = every OPEN level-0 task whose `current_stage` is IN_PROGRESS (`UNMAPPED` is not in progress) and every open epic
  whose own stage (`dim_epic.current_stage`) is IN_PROGRESS, each with `started_at` set. Tasks are attributed to the CURRENT
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

### Report 12 -- `GET /api/v1/reports/blocked-time`

```
BlockedTimeReport {
  meta, itemKind: TASK|EPIC|BOTH,
  blockedWorkingDays: Distribution,       // every DONE item in the period, zeros included
  shareOfCycle:       Distribution,       // blocked_working_days / cycle_working_days, cycle > 0
  blockedItems, excluded: { population, neverStarted, zeroCycle },
  topItems: [{ issueKey, summary?, itemKind, teamId?, doneAt, blockedWorkingDays, cycleWorkingDays?, share? }],  // <= 20
  groups:   [{ teamId?, accountId?, label?, blockedWorkingDays, shareOfCycle, blockedItems, excluded }]
}
```

- **Population** = DONE items with `done_at` in the period (a sprint-relative period reads the resolved sprints' envelope):
  level-0 tasks (`itemKind=TASK`, the DEFAULT -- tasks and epics are different grains, D2) credited to the D5 credit team /
  assignee at done, epics (`EPIC`) to the owner team with no user, or both. USER level has no epics. Open items are not in
  this report (aging WIP lists them, with their current `blocked` flag).
- **`blockedWorkingDays`** is a `Distribution` over EVERY DONE item -- an item never blocked is a real zero and stays in
  (`blockedItems` counts those blocked at all, so a sea of zeros is visible rather than hidden). Blocked time is what the
  deriver stored (`blocked_working_days`: Flagged or in a configured blocked status, merged, clipped to `[started_at,
  done_at)`). **`shareOfCycle`** covers the items with a cycle above zero; the rest are in exactly ONE `excluded` bucket,
  `neverStarted` (no cycle) then `zeroCycle` (0 working days), so `shareOfCycle.n + neverStarted + zeroCycle == population`.
  Both are hidden below `minSampleSize`.
- **`topItems`** = the 20 most-blocked items (`blockedWorkingDays > 0`, then newest `doneAt`), keys/summaries from
  `norm.work_items` (`dim_epic` for an epic). **Levels:** UNIT `groups` one per team (tasks and epics together), TEAM one per
  assignee at done over the TASKS only -- epics have no user, so at TEAM level with `itemKind` EPIC/BOTH the totals include the
  team's epics but Σ groups is the tasks' total -- and USER none.

Code: `reports/AgingWipReport.kt`, `reports/BlockedTimeReport.kt` (DTOs and queries as extensions on `ReportService`; the
shared `workItemLabels`). Tests: `ReportAgingWipTest` (hand-built rows with an INJECTED clock -- the service is called
directly, never wall time: exact ages, bands, the thresholds window, hidden thresholds, blocked, USER/UNIT, plus the fixture
graded on its populations and the thresholds against an independent percentile of the raw rows) and `ReportBlockedTimeTest`
(the fixture's distributions, exclusion partition, groups and top list against an independent computation over the fact
rows; hand-built rows with exact hand-computed numbers incl. never-blocked zeros, both exclusions, the three item kinds and the
drill). `400`s: an unknown team/connection/sprint, `accountId` without `teamId`, `from > to`, a bad `itemKind`.

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
                     epicsWithoutEstimate, epicsWithoutDates, epicsOutsidePvHorizon: QualityList<EpicRef> },
  outsideSprint: TaskFinding,  crossDomain: TaskFinding,
  epicDrift: QualityList<EpicRef>,
  domainsWithoutOwner: QualityList<UnownedDomain>,  unmappedStatuses: QualityList<UnmappedStatus>,
  unmappedBoards: QualityList<UnmappedBoard>,  authorsWithoutTeam: QualityList<AuthorWithoutTeam>,
  snapshotDrift: QualityList<SnapshotDrift>,  deriveWarnings: [DeriveWarning]
}
TaskFinding { done, open, total, md, items: [TaskRef] }     // TaskRef { issueKey, summary?, teamId?, assigneeAccountId?, assignee?, doneAt?, startedAt?, estimateMd? }
QualityList<T> { total, items: [T] }                        // items = the first 50; total says "and N more"
```

**Populations.** Task findings count level-0 tasks (D2) with `done_at` in the period -- the D5 credit team and the assignee at done
-- and, separately (the `open` half of a `TaskFinding`, `openStartedTasks`), every currently open task that has started,
whatever the period, attributed to the CURRENT team and assignee (A25, `taskFactSlice(openAttribution = true)`). Findings that
only make sense once a task is done (worklog coverage, unassigned, outside any sprint, cross-domain) have `open` = 0. Worklog
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
  has), `unassigned` (DONE, no assignee at done); the three epic lists: `epicsWithoutEstimate` (`budget_source = 'CHILDREN'`),
  `epicsWithoutDates` (start or due null), `epicsOutsidePvHorizon` (both set but one outside ±10 years of the connection's last
  DERIVE clock -- its newest SUCCEEDED `derive_runs.started_at`, else the request clock -- `DeriveKernels.inPvHorizon`: no PV curve,
  A23; a half-dated epic is "without dates" only).
- **`outsideSprint`** (D10) -- DONE tasks with no sprint at done, `md` = their estimate at done. A task done inside a sprint of an
  unmapped board is not here: it is counted under `unmappedBoards[].doneTasks`. **`crossDomain`** -- DONE tasks whose epic is in another
  domain. **`epicDrift`** (D11) -- epics with a drift flag, `flags` naming them.
- **`domainsWithoutOwner`** (A19, A22) -- `dim_domain.owner_team_id IS NULL`, with the domain's epic count; `domain` narrows; a real
  `teamId` sees none (an unowned domain belongs to no team), UNIT and `teamId=0` see them.
- **`unmappedStatuses`** -- statuses of `norm.statuses` with no stage in the connection's EFFECTIVE configuration
  (`MetricsConfigService.effectiveConfig` -- the map DERIVE used), plus any status DERIVE actually tiled UNMAPPED; `items` = work
  items (sub-tasks included) that ever sat in it, `openItems` those in it now (from `item_stage`). This is the **stage** map; a status
  missing from a board's columns (`DataProfile`'s `unmappedStatusNames`, the stub's GTM `Waiting`) is a different thing and, with the
  default category-based map, `Waiting` is mapped IN_PROGRESS.
- **`unmappedBoards`** -- `{total, items, unattributedDoneTasks}`: `norm.boards` with no team in the effective board map, each with its
  `sprints` (`norm.sprints`) and `doneTasks` (the period's DONE tasks that were done in one of them -- `sprint_id_at_done` set, no
  `sprint_team_id_at_done`). **`unattributedDoneTasks`** is the residual: tasks done in a teamless sprint that belongs to none of the
  listed boards (the sprint has no board, or its board IS mapped yet the sprint carries no team), so `outsideSprint` (no sprint),
  the listed boards and the residual together account for every DONE task without a sprint team.
- **`authorsWithoutTeam`** -- worklog authors with no team at `started_at`, by account (a `null` account = worklogs with no known
  author), most MD first. A real `teamId` sees none.
- **`snapshotDrift`** (D13) -- for the closed, team-mapped sprints of the period (`resolveSprintRows`, the sprint's team; USER level
  none) every one of the 16 figures whose live `fact_sprint` value differs from `fact_sprint_snapshot`: the seven MD figures beyond
  0.005, their item twins exactly, `capacityMd` beyond 0.005, `load` beyond 0.0005 (a figure null on one side only counts);
  `live`, `frozen` and `delta`. A sprint with no snapshot has nothing to drift from.
- **`deriveWarnings`** (A13) -- per connection in scope, its NEWEST successful run (by start, then id) carrying
  `row_counts.sprintFieldUnresolved`: `{connectionId, connectionName, runId, startedAt, warnings: ["sprintFieldUnresolved"]}`.
  Connections without a run, or whose latest run is clean, are not listed.

**Not team-scoped.** `unmappedStatuses`, `unmappedBoards` (its `doneTasks` still follow the filter) and `deriveWarnings` are
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
`DataQualityEpics.kt`, `DataQualityConfig.kt` (the mapping, drift and warning findings), `DataQualityAssembly.kt` (counting and
the drill). Tests: `ReportDataQualityTest` -- the stub fixture graded section by section against independent counts over the raw fact
rows (populations, coverage, every task finding, late logging against the generator's 1..5-day delays, teamless authors, epic
findings, unmapped boards and the ownerless domains) and its UNIT/TEAM/USER/`teamId=0`/domain-sliced/sprint-relative drill; a private
DERIVED clone whose configuration is changed and re-derived (a capacity override moves the live capacity and load away from the frozen
snapshot; removing `Waiting` from the stage map makes it an unmapped status) plus hand-moved live figures for the delta arithmetic;
hand-built rows for the populations and every task finding with hand-computed numbers, the member-days against plain weekday
arithmetic, epics incl. the horizon cut around the connection's DERIVE clock, the owner drill and the domains, and hand-inserted
DERIVE runs for the warnings. `400`s: `from > to`, `accountId` without `teamId`, an unknown connection/team/sprint, `lastSprints=0`,
a bad `domainView`.

## Report 15 -- Epic progress (EVM)

`GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, `.claude/docs/measures.md` "Report 15 -- EVM"): planned value
(PV), earned value (EV) and actual cost (AC) in man-days as CUMULATIVE curves, with SV/SPI/CV/CPI as of a day. Any signed-in
user, read-only, no audit (D12). It reads `agg_daily_flow`'s per-day INCREMENTS (A23, `.claude/docs/metrics.md`) and sums
them at query time **from the beginning of time** -- the running sum is not reset at `from`, so a series point is everything
planned/earned/spent up to the end of that day. Takes the shared `from`/`to`/`lastSprints`/`sprintId` period, `connectionId`,
and its own `epicId`; `domainView` defaults to (and only accepts) `EPIC`.

```
EpicProgressReport {
  meta: ReportMeta                     // domainView always EPIC; level is the shared UNIT/TEAM/USER of teamId
  level: UNIT | DOMAIN | EPIC | TEAM   // what the report is about
  scope: { kind: EPIC|DOMAIN|TEAM, id?, key?, name } | null        // null at UNIT
  series: [{ date, pv, ev, ac, pvOriginal? }]                      // cumulative, one point per calendar day; empty at UNIT
  asOf: { day?, pv, ev, ac, sv, spi?, cv, cpi? }
  epic?: { budgetMd?, budgetSource?, startAt?, dueAt?, inPvHorizon, hasPvCurve,
           baselines: [{ effectiveFrom, supersededAt?, startAt?, dueAt?, budgetMd? }], drift: { dates, budget } }   // EPIC only
  foreignWorkShare?: number            // TEAM only
  rows: [{ kind: EPIC|DOMAIN|TEAM, id?, key?, name, pv, ev, ac, sv, spi?, cv, cpi?, active? }]   // active: TEAM rows only
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
    SAME cumulative window as CPI -- from the beginning of time to the end of `asOf.day`, never just the requested period (a fraction
    0..1, `null` when they logged nothing; the authors in no team of `teamId=0` are never foreign, so `0.0`): A20 -- read team CPI
    with it.
  - **UNIT**: every `DOMAIN` scope summed -- the epic basis, consistent with `domainView=EPIC`, and free of the double counting of
    an item that sits in overlapping sprints (a sprint sum would count it once per sprint). So the UNIT headline is epic-attributed
    work only, exactly what the domain rows below add up to. No series; `rows` is the drill.
- **`400`s**, checked before any data is read: more than one scope, `accountId` (there is no user-level EVM), an explicit
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
  both its dates lie within +-10 years of the connection's DERIVE clock (`DeriveKernels.inPvHorizon`), otherwise there is no PV
  (A23) -- while EV and AC still count. `hasPvCurve` adds that the window holds a working day, so PV is actually spread (a weekend-only
  window is in the horizon but has no curve). `series[].pvOriginal` is the epic's FIRST baseline redrawn: the working days come from
  `DeriveKernels.pvCurve` under the calendar DERIVE used -- `metrics.dim_date.is_working_day` where `dim_date` covers the whole
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

Code: `reports/EpicProgressReport.kt` (DTOs, the query functions as extensions on `ReportService`; `snapshotNotes` in
`reports/SnapshotSupport.kt` is now shared with WIP/backlog). Tests -- `ReportEpicProgressTest`: on the shared derived fixture every
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
  totalMd, foreignMd, foreignShare?               // the grand figures
}
```

- **Columns and `domainView`.** `EPIC` (the DEFAULT -- a worklog-cost measure is PV/EV/AC-shaped, D3) uses `epic_domain_key`, an
  epic-less task falling back to its own `task_domain_key` (so there is no `(no epic)` column); `TASK` uses `task_domain_key`, so a
  cross-domain task moves from the epic's column to its own. A worklog logged ON an epic carries the epic's own domain in both
  columns (A21) and is the same in both views. `task_domain_key` is never null (invariant 6), so the `(no domain)` column
  (`domain` null) exists only so a corrupt row could never silently leave the totals. Columns are only the domains the scoped
  worklogs reach (empty for none), ordered by key; `name` is `dim_domain.name` (the lowest connection id's for a key seen on several),
  null when no row names it.
- **Rows and levels.** UNIT: one row per author team that logged work, ordered by team name, UNASSIGNED (`teamId` 0, `label` null --
  the authors in no team) last; `teamId` is the id a client drills with. `teamId` set: one row per AUTHOR of that team as of
  `started_at`, by display name (`norm.people`, the account id when none is known; `label` null and `accountId` null for worklogs with
  no known author, last), every row's `teamId` the requested one; `teamId=0` lists the authors in no team the same way. `teamId` +
  `accountId`: that author's one row (no row when they logged nothing there). The columns of a drill are those of its own worklogs.
  Teams and authors with no worklogs in scope have no row. A UNIT row carries `active`: `false` marks a soft-deleted author team that
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
  team differs from the task's sprint team at `started_at`, else from the assignee's team then; epic-logged = differs from the epic's
  domain owner team; an unknown side is never foreign, so UNASSIGNED authors read 0.0. It is PER PERIOD -- the epic-progress report's
  team `foreignWorkShare` is the same ratio over the cumulative window up to `asOf`, a different figure by design.
- **Slices.** `domain` (matched against the same column the view uses), `activityType`, `workCategory` (`UNCATEGORIZED` = none) and
  `connectionId` (else every active connection, summed) restrict the worklogs.
- **`400`s:** the shared parser's (`accountId` without `teamId`, `from` after `to`, mutually exclusive periods, `lastSprints` out of
  range, a bad `domainView`/enum, a repeated scalar key) and an unknown or inactive team, connection or sprint -- always `400`, never
  `404`. An unknown `domain`, `activityType` or `workCategory` is a valid slice with an empty answer; `teamId=0` with a sprint-relative
  period resolves no sprint and is empty (the epic-progress precedent, no note field here).

Code: `reports/CostMatrixReport.kt` (DTOs and the query as an extension on `ReportService`; the `orgGroups` drill and `resolveReportScope`
from `ReportSupport.kt`). Tests -- `ReportCostMatrixTest`: on the shared derived fixture the grand total, every row, column, cell and
the foreign figures in both views against an INDEPENDENT sum over the raw `fact_worklog` rows (two periods, a domain slice, the rows
and columns reconciling to the total within the rounding bound); on hand-built rows in a fresh disabled connection the exact cells,
the TASK/EPIC switch for a cross-domain task, an epic-logged and an epic-less worklog, the thirds rounding rule, the foreign shares
(incl. null), the period and slice filters, the team/user/UNASSIGNED drills and every `400` (a plain user gets `200`).
